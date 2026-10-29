package ch.threema.app.services;

import android.content.Context;
import android.os.PowerManager;

import ch.threema.base.crypto.NaCl;

import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import ch.threema.app.BuildConfig;
import ch.threema.app.utils.FileUtil;
import ch.threema.base.ProgressListener;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import static ch.threema.common.JavaCompat.toHexString;

import ch.threema.common.ByteArrayExtensionsKt;
import ch.threema.domain.protocol.blob.BlobLoader;
import ch.threema.domain.protocol.blob.BlobScope;

public class DownloadServiceImpl implements DownloadService {
    private static final Logger logger = getThreemaLogger("DownloadServiceImpl");

    private static final String TAG = "DownloadService";
    private static final String WAKELOCK_TAG = BuildConfig.APPLICATION_ID + ":" + TAG;
    private static final int DOWNLOAD_WAKELOCK_TIMEOUT = 10 * 1000;
    // Upper bound a duplicate caller waits to join an in-flight download of the same blob. The creator
    // always signals on completion (success or failure), so this only caps a pathological hang; keep it
    // generously above any realistic blob transfer so a slow-but-progressing download is never abandoned.
    private static final long BLOB_JOIN_TIMEOUT_MINUTES = 5;
    private static final String PART_FILE_SUFFIX = ".part";
    // How often a download waiting for the part file checks whether it was cancelled meanwhile.
    private static final long PART_FILE_WAIT_SLICE_MILLIS = 1000;
    // Pending marks of downloads whose caller never completed them (the media did not decrypt) are dropped beyond this.
    @VisibleForTesting
    static final int MAX_PENDING_MARKS_AS_DONE = 64;
    private final ArrayList<Download> downloads = new ArrayList<>();
    // F1-PATCH: the one download that writes a blob's part file, guarded by `downloads`. Only that download ever leaves
    // this map (when it has stopped); a cancelled download leaves the registered downloads at once but may still be
    // unwinding its last write, so a retry of the same blob waits until the slot is free.
    private final Map<String, Download> partFileWriters = new HashMap<>();
    // F1-PATCH: guarded by `downloads`. A blob is marked as done once, from complete(), i.e. only after the caller has
    // decrypted (MAC verified) and saved it: the server may delete a blob marked as done, and with it any chance of
    // downloading it again or continuing a partial download. Kept by blob, not by registration, so a completion still
    // finds it after an unrelated error report or a cancel has removed the registration.
    private final Map<String, PendingMarkAsDone> pendingMarkAsDone = new LinkedHashMap<String, PendingMarkAsDone>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PendingMarkAsDone> eldest) {
            return size() > MAX_PENDING_MARKS_AS_DONE;
        }
    };
    // Test seam: starts the thread that marks a blob as done.
    @VisibleForTesting
    volatile Consumer<Runnable> markAsDoneStarter = runnable -> new Thread(runnable, "MarkAsDownThread").start();
    @VisibleForTesting
    volatile long joinTimeoutMillis = TimeUnit.MINUTES.toMillis(BLOB_JOIN_TIMEOUT_MINUTES);
    // Test seam: runs in a joining caller after its wait and before it opens the creator's temporary file.
    @VisibleForTesting
    @Nullable
    volatile Runnable joinerAwakeHook = null;
    private final ApiService apiService;
    private final PowerManager powerManager;
    @NonNull
    private final Context appContext;

    private final static class Download {
        int messageModelId;
        byte[] blobId;
        BlobLoader blobLoader;
        // Single-flight join: a concurrent duplicate download() for the same blob awaits this latch and then
        // reads the creator's temporary file instead of returning null. The old null return was mistaken by
        // the caller for a failure, which cancelled/orphaned this running loader and spawned a second
        // concurrent fetch of the same blob (slow + spurious "download failed").
        final CountDownLatch done = new CountDownLatch(1);
        // F1-PATCH: no retained in-memory copy of the ciphertext (it doubled the heap a large file needs).
        // `readers` and `completed` are guarded by `downloads`. A completed download stays registered while
        // joiners still have to open its temporary file, so later callers join it instead of racing its
        // deletion; the last of those readers unregisters it and deletes the file after opening it.
        volatile boolean succeeded;
        int readers;
        boolean completed;

        public Download(int messageModelId, byte[] blobId, BlobLoader blobLoader) {
            this.messageModelId = messageModelId;
            this.blobId = blobId;
            this.blobLoader = blobLoader;
        }
    }

    /**
     * F1-PATCH: the download gave up waiting for its part file; an expected outcome of a cancelled retry, not an error.
     */
    private static final class PartFileBusyException extends IOException {
        PartFileBusyException(String message) {
            super(message);
        }
    }

    private final static class PendingMarkAsDone {
        final BlobLoader blobLoader;
        final BlobScope scope;

        PendingMarkAsDone(BlobLoader blobLoader, BlobScope scope) {
            this.blobLoader = blobLoader;
            this.scope = scope;
        }
    }

    private @NonNull List<Download> getDownloadsByMessageModelId(int messageModelId) {
        ArrayList<Download> matchingDownloads = new ArrayList<>();
        for (Download download : this.downloads) {
            if (download.messageModelId == messageModelId) {
                matchingDownloads.add(download);
            }
        }
        return matchingDownloads;
    }

    private @Nullable Download getDownloadByBlobId(@NonNull byte[] blobId) {
        for (Download download : this.downloads) {
            if (Arrays.equals(blobId, download.blobId)) {
                return download;
            }
        }
        return null;
    }

    private boolean removeDownloadByMessageModelId(int messageModelId, boolean cancel) {
        synchronized (this.downloads) {
            List<Download> matchingDownloads = getDownloadsByMessageModelId(messageModelId);
            // F1-PATCH: an error is reported by a caller whose own download() has already returned, so a download of
            // this message that is still in flight is a newer attempt (a retry after a cancel, the media next to a
            // failed thumbnail) and is not the reporter's to remove. A finished download that joiners still have to
            // read is settled like a completion instead: it stays registered until its last reader has opened the
            // temporary file, so a late error or cancel can never pull the file out from under them.
            boolean settled = false;
            for (Download download : new ArrayList<>(matchingDownloads)) {
                boolean inFlight = download.done.getCount() > 0;
                if (inFlight && !cancel) {
                    matchingDownloads.remove(download);
                } else if (!inFlight && download.readers > 0) {
                    logger.info("Blob {} settled for message {}, kept for {} joining reader(s)",
                        toHexString(download.blobId), messageModelId, download.readers);
                    download.completed = true;
                    matchingDownloads.remove(download);
                    settled = true;
                }
            }
            for (Download download : matchingDownloads) {
                logger.info("Blob {} remove downloader for message {}. Cancel = {}",
                    toHexString(download.blobId),
                    messageModelId,
                    cancel);
                if (cancel) {
                    download.blobLoader.cancelDownload();
                }
                this.downloads.remove(download);
                // F1-PATCH: an error after a successful download (the media did not decrypt or could not be saved) discards
                // the downloaded file, so the next attempt downloads the blob again instead of failing on the same bytes.
                if (!cancel && download.succeeded && getDownloadByBlobId(download.blobId) == null) {
                    File downloadFile = getTemporaryDownloadFile(download.blobId);
                    if (downloadFile.exists()) {
                        FileUtil.deleteFileOrWarn(downloadFile, "remove temporary blob file", logger);
                    }
                }
            }
            return settled || !matchingDownloads.isEmpty();
        }
    }

    public DownloadServiceImpl(@NonNull Context appContext, @NonNull ApiService apiService) {
        this.appContext = appContext;
        this.apiService = apiService;
        this.powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
    }

    @Override
    @WorkerThread
    public @Nullable byte[] download(
        int messageModelId,
        final @Nullable byte[] blobId,
        @NonNull BlobScope blobScopeDownload,
        @Nullable BlobScope blobScopeMarkAsDone,
        @Nullable ProgressListener progressListener
    ) {
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        // Non-null only for the thread that actually creates the loader (the "creator"); a duplicate
        // caller that joins an in-flight download leaves this null and must not signal the latch.
        Download myDownload = null;
        try {
            if (wakeLock != null) {
                wakeLock.acquire(DOWNLOAD_WAKELOCK_TIMEOUT);
                logger.info("Acquire download wakelock");
            }

            if (blobId == null) {
                logger.warn("Blob ID is null");
                return null;
            }

            final String blobIdHex = toHexString(blobId);
            logger.info("Blob {} for message {} download requested", blobIdHex, messageModelId);

            byte[] blobBytes = null;
            File downloadFile = this.getTemporaryDownloadFile(blobId);
            File partFile = this.getPartDownloadFile(blobId);
            boolean downloadSuccess = false;

            try {
                BlobLoader blobLoader = null;
                Download existingDownload = null;
                // F1-PATCH: an in-flight download wins over the downloaded file (which it may still be writing).
                synchronized (this.downloads) {
                    existingDownload = getDownloadByBlobId(blobId);
                    if (existingDownload != null) {
                        existingDownload.readers++;
                        logger.info("Blob {} downloader already exists. Joining in-flight download", blobIdHex);
                    } else {
                        blobLoader = this.apiService.createLoader(blobId);
                        myDownload = new Download(messageModelId, blobId, blobLoader);
                        this.downloads.add(myDownload);
                        logger.info("Blob {} downloader created", blobIdHex);
                    }
                }

                // Duplicate caller: join the single in-flight fetch instead of returning null (which the
                // caller treats as a failure and then orphans the running loader, spawning a second
                // concurrent GET of the same blob). Await OUTSIDE the lock so the creator can progress.
                if (myDownload == null) {
                    return joinInFlightDownload(existingDownload, downloadFile, blobIdHex);
                }

                acquirePartFile(myDownload, blobIdHex);
                // Keeps a part file that is continued now from expiring in the cache cleanup meanwhile, and the downloaded
                // file renamed from it, which joiners still have to open, from being taken for an old one.
                if (partFile.exists() && !partFile.setLastModified(System.currentTimeMillis())) {
                    logger.debug("Could not refresh the part file of blob {}", blobIdHex);
                }

                if (progressListener != null) {
                    blobLoader.progressListener = progressListener;
                }

                // F1-PATCH: a complete download is opened under the same lock complete() and error() delete it under.
                FileInputStream downloadedFile = null;
                synchronized (this.downloads) {
                    if (downloadFile.exists() && downloadFile.length() >= NaCl.BOX_OVERHEAD_BYTES) {
                        logger.warn("Blob {} download file already exists", blobIdHex);
                        downloadedFile = new FileInputStream(downloadFile);
                    } else if (downloadFile.exists()) {
                        // invalid download file - try again
                        FileUtil.deleteFileOrWarn(downloadFile, "Download File", logger);
                    }
                }

                if (downloadedFile == null) {
                    // Load blob from server, continuing the part file an earlier attempt left behind
                    logger.info("Blob {} now fetching", blobIdHex);
                    if (blobLoader.loadToFile(blobScopeDownload, partFile)) {
                        synchronized (this.downloads) {
                            // check that THIS download is still registered (otherwise it was cancelled; its complete part
                            // file stays for the next attempt, which only has to confirm it)
                            if (getDownloadByBlobId(blobId) == myDownload) {
                                if (!partFile.renameTo(downloadFile)) {
                                    throw new IOException("Could not move the downloaded blob into place");
                                }
                                downloadedFile = new FileInputStream(downloadFile);
                            } else {
                                logger.info("Blob {} download was cancelled, discarding its result", blobIdHex);
                            }
                        }
                    }
                }

                if (downloadedFile != null) {
                    blobBytes = readTemporaryDownloadFile(downloadedFile, blobIdHex);
                }

                if (blobBytes != null) {
                    synchronized (this.downloads) {
                        if (getDownloadByBlobId(blobId) == myDownload) {
                            downloadSuccess = true;
                            // Joiners read the complete temporary file; it stays false on any failure so
                            // they see a real, not spurious, failure.
                            myDownload.succeeded = true;
                            // The pending mark keeps the loader; it must not keep the caller's listener (and its UI) alive.
                            blobLoader.progressListener = null;
                            if (blobScopeMarkAsDone != null) {
                                this.pendingMarkAsDone.put(blobIdHex, new PendingMarkAsDone(blobLoader, blobScopeMarkAsDone));
                            } else {
                                this.pendingMarkAsDone.remove(blobIdHex);
                            }
                        } else {
                            // F1-PATCH: cancelled (or replaced by a newer download of the same blob). Its bytes are not
                            // handed out, so its caller never completes, and thereby unregisters, the newer download.
                            logger.info("Blob {} download was cancelled, discarding its result", blobIdHex);
                            blobBytes = null;
                        }
                    }
                }
            } catch (PartFileBusyException e) {
                logger.info("Blob {} not downloaded: {}", blobIdHex, e.getMessage());
            } catch (Exception e) {
                logger.error("Exception during blob download", e);
            } catch (OutOfMemoryError e) {
                // F1-PATCH: a blob too large for the available memory is a failed download, not a crash.
                logger.error("Blob {} is too large for the available memory", blobIdHex);
                blobBytes = null;
                downloadSuccess = false;
            }

            if (!downloadSuccess) {
                // F1-PATCH: a download that did not succeed as a whole hands out nothing. Its bytes would leave its
                // registration behind for good (complete() only settles successful downloads), and every later caller
                // would join that dead download.
                blobBytes = null;
            }

            if (downloadSuccess) {
                logger.info("Blob {} successfully downloaded. Size = {}", blobIdHex, blobBytes.length);
            } else {
                logger.warn("Blob {} download failed.", blobIdHex);
            }

            if (blobBytes == null) {
                synchronized (this.downloads) {
                    // download failed. remove THIS loader (never a newer download of the same blob)
                    if (this.downloads.remove(myDownload)) {
                        logger.info("Blob {} remove downloader. Download failed.", blobIdHex);
                    }
                }
            }
            return blobBytes;
        } finally {
            // Always release joining duplicates (succeeded was set above only on genuine success), even on
            // exception or early return; only the creator signals.
            if (myDownload != null) {
                synchronized (this.downloads) {
                    this.partFileWriters.remove(toHexString(myDownload.blobId), myDownload);
                }
                myDownload.done.countDown();
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                logger.info("Release download wakelock");
                wakeLock.release();
            }
        }
    }

    @Override
    public void complete(int messageModelId, byte[] blobId) {
        synchronized (this.downloads) {
            // success has been signalled. remove loader
            Download download = getDownloadByBlobId(blobId);
            if (download != null && !download.succeeded) {
                // F1-PATCH: a newer download of the same blob is still in flight; the temporary file is its own.
                logger.info("Blob {} complete ignored, a newer download is in flight", toHexString(blobId));
                return;
            }
            markAsDone(blobId);
            if (download != null) {
                download.completed = true;
                // F1-PATCH: a joiner that has not opened the temporary file yet unregisters the download and deletes
                // the file once it has; until then later callers join it. Repeated completion changes nothing.
                if (download.readers > 0) {
                    logger.info("Blob {} completed, temporary file kept for {} joining reader(s)", toHexString(blobId), download.readers);
                    return;
                }
                logger.info("Blob {} remove downloader", toHexString(blobId));
                downloads.remove(download);
            }

            // remove temp file
            File f = this.getTemporaryDownloadFile(blobId);
            if (f.exists()) {
                FileUtil.deleteFileOrWarn(f, "remove temporary blob file", logger);
            }
        }
    }

    /**
     * F1-PATCH: takes the blob's part file slot, waiting while another download (a cancelled one that is still
     * unwinding) holds it, so the part file only ever has one writer. Only the holder releases the slot, so giving up
     * never hands it to anyone else. A download cancelled while it waits stops waiting.
     */
    private void acquirePartFile(@NonNull Download mine, @NonNull String blobIdHex) throws PartFileBusyException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(joinTimeoutMillis);
        while (true) {
            Download writer;
            synchronized (this.downloads) {
                if (getDownloadByBlobId(mine.blobId) != mine) {
                    throw new PartFileBusyException("cancelled while waiting for its part file");
                }
                writer = this.partFileWriters.get(blobIdHex);
                if (writer == null) {
                    this.partFileWriters.put(blobIdHex, mine);
                    return;
                }
            }
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new PartFileBusyException("a cancelled download is still writing its part file");
            }
            try {
                writer.done.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(PART_FILE_WAIT_SLICE_MILLIS)), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PartFileBusyException("interrupted while waiting for its part file");
            }
        }
    }

    /**
     * F1-PATCH: marks the blob as done on the server if its download asked for it, once. Guarded by `downloads`.
     */
    private void markAsDone(@NonNull byte[] blobId) {
        final PendingMarkAsDone pending = this.pendingMarkAsDone.remove(toHexString(blobId));
        if (pending == null) {
            return;
        }
        logger.info("Blob {} scheduled for marking as downloaded", toHexString(blobId));
        try {
            markAsDoneStarter.accept(() -> {
                pending.blobLoader.markAsDone(blobId, pending.scope);
                logger.info("Blob {} marked as downloaded", toHexString(blobId));
            });
        } catch (Exception | OutOfMemoryError e) {
            // markAsDown thread failed (e.g. java.lang.InternalError: Thread starting during runtime shutdown, or no
            // memory for another thread). The media is saved; the server keeps the blob until it expires.
            logger.warn("Blob {} could not be marked as downloaded", toHexString(blobId), e);
        }
    }

    /**
     * F1-PATCH: waits for the creator of an in-flight download of the same blob and reads its complete temporary file.
     */
    private @Nullable byte[] joinInFlightDownload(@NonNull Download download, @NonNull File downloadFile, @NonNull String blobIdHex) {
        boolean finished;
        try {
            finished = download.done.await(joinTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            logger.warn("Blob {} timed out joining in-flight download", blobIdHex);
        }
        FileInputStream joinedFile = null;
        try {
            Runnable hook = joinerAwakeHook;
            if (hook != null) {
                hook.run();
            }
            synchronized (this.downloads) {
                if (finished && download.succeeded) {
                    try {
                        joinedFile = new FileInputStream(downloadFile);
                    } catch (IOException e) {
                        logger.warn("Blob {} temporary download file is gone", blobIdHex, e);
                    }
                }
            }
        } finally {
            // Released on every exit, so a registration can never keep a completed download registered forever.
            synchronized (this.downloads) {
                download.readers--;
                if (download.readers == 0 && download.completed) {
                    this.downloads.remove(download);
                    // Already open (or not needed): deleting it does not affect a stream opened above. Only while no
                    // newer download of the same blob is registered, which would own the file now.
                    if (getDownloadByBlobId(download.blobId) == null) {
                        FileUtil.deleteFileOrWarn(downloadFile, "remove temporary blob file", logger);
                    }
                }
            }
        }
        return joinedFile == null ? null : readTemporaryDownloadFile(joinedFile, blobIdHex);
    }

    /**
     * F1-PATCH: reads a temporary download file into one array of exactly its size (the previous readBytes needed
     * about twice that). Running out of memory is a failed download, not a crash.
     */
    private @Nullable byte[] readTemporaryDownloadFile(@NonNull FileInputStream fileInputStream, @NonNull String blobIdHex) {
        try (FileInputStream input = fileInputStream) {
            long length = input.getChannel().size();
            if (length < NaCl.BOX_OVERHEAD_BYTES || length > Integer.MAX_VALUE) {
                logger.warn("Blob {} temporary download file has an unusable length {}", blobIdHex, length);
                return null;
            }
            byte[] data = new byte[(int) length];
            new DataInputStream(input).readFully(data);
            if (input.read() != -1) {
                logger.warn("Blob {} temporary download file grew while it was read", blobIdHex);
                return null;
            }
            return data;
        } catch (IOException e) {
            logger.warn("Blob {} could not read the temporary download file", blobIdHex, e);
            return null;
        } catch (OutOfMemoryError e) {
            logger.error("Blob {} is too large for the available memory", blobIdHex);
            return null;
        }
    }

    @VisibleForTesting
    int registeredReaders(@NonNull byte[] blobId) {
        synchronized (this.downloads) {
            Download download = getDownloadByBlobId(blobId);
            return download == null ? 0 : download.readers;
        }
    }

    @Override
    public boolean cancel(int messageModelId) {
        return removeDownloadByMessageModelId(messageModelId, true);
    }

    @Override
    public boolean isDownloading(int messageModelId) {
        synchronized (this.downloads) {
            return !getDownloadsByMessageModelId(messageModelId).isEmpty();
        }
    }

    @Override
    public boolean isDownloading() {
        synchronized (this.downloads) {
            return !this.downloads.isEmpty();
        }
    }

    @Override
    public void error(int messageModelId) {
        // error has been signalled. remove loaders for this MessageModel
        removeDownloadByMessageModelId(messageModelId, false);
    }

    private File getTemporaryDownloadFile(byte[] blobId) {
        final String fileName = ByteArrayExtensionsKt.toHexString(blobId, 0);
        return new File(appContext.getCacheDir(), fileName);
    }

    /**
     * F1-PATCH: the ciphertext received so far for a download that has not finished; a later attempt continues it.
     */
    private File getPartDownloadFile(byte[] blobId) {
        return new File(appContext.getCacheDir(), ByteArrayExtensionsKt.toHexString(blobId, 0) + PART_FILE_SUFFIX);
    }
}
