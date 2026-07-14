package ch.threema.app.services;

import android.content.Context;
import android.os.PowerManager;

import ch.threema.base.crypto.NaCl;

import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import ch.threema.app.BuildConfig;
import ch.threema.app.utils.FileUtil;
import ch.threema.base.ProgressListener;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import static kotlin.io.ByteStreamsKt.readBytes;

import ch.threema.base.utils.Utils;
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
    private final ArrayList<Download> downloads = new ArrayList<>();
    private final ApiService apiService;
    private final PowerManager powerManager;
    @NonNull
    private final Context appContext;

    private final static class Download {
        int messageModelId;
        byte[] blobId;
        BlobLoader blobLoader;
        // Single-flight join: a concurrent duplicate download() for the same blob awaits this latch and
        // reuses `result` (a pristine, still-encrypted snapshot) instead of returning null. The old null
        // return was mistaken by the caller for a failure, which cancelled/orphaned this running loader
        // and spawned a second concurrent fetch of the same blob (slow + spurious "download failed").
        final CountDownLatch done = new CountDownLatch(1);
        volatile byte[] result;

        public Download(int messageModelId, byte[] blobId, BlobLoader blobLoader) {
            this.messageModelId = messageModelId;
            this.blobId = blobId;
            this.blobLoader = blobLoader;
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

    private void removeDownloadByBlobId(@NonNull byte[] blobId) {
        synchronized (this.downloads) {
            Download download = getDownloadByBlobId(blobId);
            if (download != null) {
                logger.info("Blob {} remove downloader", Utils.byteArrayToHexString(blobId));
                downloads.remove(download);
            }
        }
    }

    private boolean removeDownloadByMessageModelId(int messageModelId, boolean cancel) {
        synchronized (this.downloads) {
            List<Download> matchingDownloads = getDownloadsByMessageModelId(messageModelId);
            if (!matchingDownloads.isEmpty()) {
                for (Download download : matchingDownloads) {
                    logger.info("Blob {} remove downloader for message {}. Cancel = {}",
                        Utils.byteArrayToHexString(download.blobId),
                        messageModelId,
                        cancel);
                    if (cancel) {
                        download.blobLoader.cancelDownload();
                    }
                    this.downloads.remove(download);
                }
                return true;
            }
            return false;
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

            final String blobIdHex = Utils.byteArrayToHexString(blobId);
            logger.info("Blob {} for message {} download requested", blobIdHex, messageModelId);

            byte[] blobBytes = null;
            File downloadFile = this.getTemporaryDownloadFile(blobId);
            boolean downloadSuccess = false;

            try {
                //check if a temporary file exist
                if (downloadFile.exists()) {
                    if (downloadFile.length() >= NaCl.BOX_OVERHEAD_BYTES) {
                        logger.warn("Blob {} download file already exists", blobIdHex);
                        try (FileInputStream fileInputStream = new FileInputStream(downloadFile)) {
                            return readBytes(fileInputStream);
                        }
                    } else {
                        // invalid download file - try again
                        FileUtil.deleteFileOrWarn(downloadFile, "Download File", logger);
                    }
                }

                BlobLoader blobLoader = null;
                Download existingDownload = null;
                synchronized (this.downloads) {
                    existingDownload = getDownloadByBlobId(blobId);
                    if (existingDownload == null) {
                        blobLoader = this.apiService.createLoader(blobId);
                        myDownload = new Download(messageModelId, blobId, blobLoader);
                        this.downloads.add(myDownload);
                        logger.info("Blob {} downloader created", blobIdHex);
                    } else {
                        logger.info("Blob {} downloader already exists. Joining in-flight download", blobIdHex);
                    }
                }

                // Duplicate caller: join the single in-flight fetch instead of returning null (which the
                // caller treats as a failure and then orphans the running loader, spawning a second
                // concurrent GET of the same blob). Await OUTSIDE the lock so the creator can progress;
                // return a clone because the caller decrypts the returned array in place.
                if (myDownload == null) {
                    try {
                        if (existingDownload.done.await(BLOB_JOIN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                            byte[] joined = existingDownload.result;
                            return joined == null ? null : joined.clone();
                        }
                        logger.warn("Blob {} timed out joining in-flight download", blobIdHex);
                        return null;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }

                if (progressListener != null) {
                    blobLoader.progressListener = progressListener;
                }

                // Load blob from server
                logger.info("Blob {} now fetching", blobIdHex);
                blobBytes = blobLoader.load(blobScopeDownload);

                if (blobBytes != null) {
                    synchronized (this.downloads) {
                        //check if loader already existing in array (otherwise its canceled)
                        if (getDownloadByBlobId(blobId) != null) {
                            logger.info("Blob {} now saving", blobIdHex);
                            //write to temporary file
                            FileUtil.createNewFileOrLog(downloadFile, logger);
                            if (downloadFile.isFile()) {
                                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(downloadFile))) {
                                    bos.write(blobBytes);
                                    bos.flush();
                                }

                                if (downloadFile.length() == blobBytes.length) {
                                    downloadSuccess = true;
                                    // Publish a pristine, still-encrypted snapshot for any joining
                                    // duplicate BEFORE our own caller decrypts the returned array in
                                    // place (cloning here avoids a shared-array race + double in-place
                                    // decrypt; result stays null on any failure so joiners see a real,
                                    // not spurious, failure).
                                    myDownload.result = blobBytes.clone();

                                    //ok download saved, set as done if set
                                    if (blobScopeMarkAsDone != null) {
                                        logger.info("Blob {} scheduled for marking as downloaded", blobIdHex);
                                        try {
                                            new Thread(() -> {
                                                Download download;
                                                synchronized (this.downloads) {
                                                    download = getDownloadByBlobId(blobId);
                                                }
                                                if (download != null) {
                                                    if (download.blobLoader != null) {
                                                        download.blobLoader.markAsDone(download.blobId, blobScopeMarkAsDone);
                                                    }
                                                    logger.info("Blob {} marked as downloaded", blobIdHex);
                                                }
                                            }, "MarkAsDownThread").start();
                                        } catch (Exception ignored) {
                                            // markAsDown thread failed
                                            // catch java.lang.InternalError: Thread starting during runtime shutdown
                                        }
                                    }
                                } else {
                                    logger.warn("Blob and file size don't match.");
                                    return null;
                                }
                            } else {
                                logger.warn("Blob file is a directory");
                            }
                        } else {
                            logger.debug("No blob loaders, canceled?");
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Exception during blob download", e);
            }

            if (downloadSuccess) {
                logger.info("Blob {} successfully downloaded. Size = {}", blobIdHex, blobBytes.length);
            } else {
                logger.warn("Blob {} download failed.", blobIdHex);
            }

            if (blobBytes == null) {
                synchronized (this.downloads) {
                    // download failed. remove loader
                    Download download = getDownloadByBlobId(blobId);
                    if (download != null) {
                        logger.info("Blob {} remove downloader. Download failed.", blobIdHex);
                        this.downloads.remove(download);
                    }
                }
            }
            return blobBytes;
        } finally {
            // Always release joining duplicates (result was set above only on genuine success), even on
            // exception or early return; only the creator signals.
            if (myDownload != null) {
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
        // success has been signalled. remove loader
        removeDownloadByBlobId(blobId);

        // remove temp file
        File f = this.getTemporaryDownloadFile(blobId);
        if (f.exists()) {
            FileUtil.deleteFileOrWarn(f, "remove temporary blob file", logger);
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
}
