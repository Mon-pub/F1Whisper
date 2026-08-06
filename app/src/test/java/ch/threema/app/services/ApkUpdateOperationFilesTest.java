package ch.threema.app.services;

import org.junit.Assert;
import org.junit.Test;

/**
 * F1Whisper (third follow-up S3-05 / T3-10): the process-unique operation-file naming and cleanup
 * decisions of the self-update download service. These are the file-isolation decisions that
 * previously caused cross-instance collisions ({@code .part-<startId>} repeats across a
 * destroyed-and-recreated instance while cleanup deleted shared files). The real Service
 * onDestroy-vs-worker lock/timing interleaving stays a device-matrix item.
 */
public class ApkUpdateOperationFilesTest {

    private static final String FINAL_NAME = "F1Whisper-update.apk";

    @Test
    public void partFileNameIsInstanceAndStartIdSpecific() {
        Assert.assertEquals(
            "F1Whisper-update.apk.part-nonceA-1",
            ApkUpdateOperationFiles.partFileName(FINAL_NAME, "nonceA", 1));
    }

    @Test
    public void twoInstancesNeverCollideOnAPartFileForTheSameStartId() {
        // Start ids restart per instance, so the same startId across two instances must still yield
        // DISTINCT partial files — the whole point of the per-instance nonce.
        final String a = ApkUpdateOperationFiles.partFileName(FINAL_NAME, "nonceA", 1);
        final String b = ApkUpdateOperationFiles.partFileName(FINAL_NAME, "nonceB", 1);
        Assert.assertNotEquals(a, b);
    }

    @Test
    public void cleanupDeletesTheStaleFinalApk() {
        Assert.assertTrue(ApkUpdateOperationFiles.shouldDelete(FINAL_NAME, FINAL_NAME, "nonceA"));
    }

    @Test
    public void cleanupDeletesOwnPartials() {
        final String ownPart = ApkUpdateOperationFiles.partFileName(FINAL_NAME, "nonceA", 3);
        Assert.assertTrue(ApkUpdateOperationFiles.shouldDelete(ownPart, FINAL_NAME, "nonceA"));
    }

    @Test
    public void cleanupNeverDeletesAForeignLivePartial() {
        // A partial written by another (possibly still winding-down) instance must survive our
        // cleanup — deleting it is exactly the T3-10 defect.
        final String foreignPart = ApkUpdateOperationFiles.partFileName(FINAL_NAME, "nonceB", 1);
        Assert.assertFalse(
            "a foreign instance's partial must never be deleted",
            ApkUpdateOperationFiles.shouldDelete(foreignPart, FINAL_NAME, "nonceA"));
    }

    @Test
    public void cleanupIgnoresUnrelatedFiles() {
        Assert.assertFalse(ApkUpdateOperationFiles.shouldDelete("something-else.apk", FINAL_NAME, "nonceA"));
        // A user-visible release apk name is not our operation file and must be left alone.
        Assert.assertFalse(
            ApkUpdateOperationFiles.shouldDelete("F1Whisper-v6.4.3-38-onprem-release.apk", FINAL_NAME, "nonceA"));
    }
}
