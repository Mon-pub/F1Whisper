package ch.threema.app.services;

import androidx.annotation.NonNull;

/**
 * F1Whisper (third follow-up S3-05 / T3-10): pure, unit-tested naming + cleanup logic for the
 * self-update download service's operation files.
 *
 * <p>The defect: the operation's partial file was named {@code <final>.part-<startId>}, but start
 * ids are unique only WITHIN a single service instance. A destroyed-and-recreated instance restarts
 * that id sequence, so two instances could pick the SAME partial name, and the old cleanup step
 * deleted every {@code F1Whisper-*.apk}/{@code *.apk.part-*} — including a partial another (still
 * winding-down) instance was writing. This class makes the operation file PROCESS-UNIQUE (via a
 * per-instance random nonce) and makes cleanup delete only the stale FINAL apk plus THIS instance's
 * own partials, never a foreign instance's live partial.</p>
 *
 * <p>The real service-lifecycle interleaving (onDestroy racing a scheduled worker across API
 * 26/31/33/35) remains a device-matrix item; this class makes the file-isolation decisions — the
 * part that previously caused cross-instance collisions — JVM-testable.</p>
 */
public final class ApkUpdateOperationFiles {

    private ApkUpdateOperationFiles() {
    }

    /** The process-unique partial download filename: {@code <finalName>.part-<instanceNonce>-<startId>}. */
    @NonNull
    public static String partFileName(@NonNull String finalName, @NonNull String instanceNonce, int startId) {
        return finalName + ".part-" + instanceNonce + "-" + startId;
    }

    /** The prefix every partial file of {@code instanceNonce} shares. */
    @NonNull
    private static String ownPartPrefix(@NonNull String finalName, @NonNull String instanceNonce) {
        return finalName + ".part-" + instanceNonce + "-";
    }

    /**
     * Whether {@code cleanupOldApks} should delete {@code candidateName} on behalf of the instance
     * identified by {@code instanceNonce}. Deletes the stale FINAL apk (safe: it was atomically
     * published or is left over from a prior run) and this instance's OWN partials; NEVER a foreign
     * instance's partial (which a winding-down predecessor may still be writing).
     */
    public static boolean shouldDelete(@NonNull String candidateName, @NonNull String finalName, @NonNull String instanceNonce) {
        if (candidateName.equals(finalName)) {
            return true;
        }
        return candidateName.startsWith(ownPartPrefix(finalName, instanceNonce));
    }
}
