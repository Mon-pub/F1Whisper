package ch.threema.app.services;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * F1Whisper (fork review H-08): the self-update download must never hand an APK to the installer
 * unless it is this application, strictly newer, and signed with a certificate the installed app
 * also carries.
 */
public class ApkUpdateValidatorTest {

    private static final String PKG = "info.f1tech.threema";
    private static final String RELEASE_SIGNER =
        "0e6f089e8b01ab7c1d4ddc7da36f4340df36f3ed1ab371d7bf1432f0980f51a7";
    private static final String OTHER_SIGNER =
        "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    private static final Set<String> INSTALLED_SIGNERS = Collections.singleton(RELEASE_SIGNER);

    @Test
    public void happyPathIsAccepted() {
        assertEquals(
            ApkUpdateValidator.Result.OK,
            ApkUpdateValidator.validate(PKG, 1186, Collections.singleton(RELEASE_SIGNER), PKG, 1185, INSTALLED_SIGNERS)
        );
    }

    @Test
    public void wrongPackageIsRejected() {
        assertEquals(
            ApkUpdateValidator.Result.WRONG_PACKAGE,
            ApkUpdateValidator.validate("com.evil.app", 1186, Collections.singleton(RELEASE_SIGNER), PKG, 1185, INSTALLED_SIGNERS)
        );
    }

    @Test
    public void equalVersionIsRejected() {
        assertEquals(
            ApkUpdateValidator.Result.NOT_NEWER,
            ApkUpdateValidator.validate(PKG, 1185, Collections.singleton(RELEASE_SIGNER), PKG, 1185, INSTALLED_SIGNERS)
        );
    }

    @Test
    public void olderVersionIsRejected() {
        assertEquals(
            ApkUpdateValidator.Result.NOT_NEWER,
            ApkUpdateValidator.validate(PKG, 1148, Collections.singleton(RELEASE_SIGNER), PKG, 1185, INSTALLED_SIGNERS)
        );
    }

    @Test
    public void wrongSignerIsRejected() {
        assertEquals(
            ApkUpdateValidator.Result.SIGNER_MISMATCH,
            ApkUpdateValidator.validate(PKG, 1186, Collections.singleton(OTHER_SIGNER), PKG, 1185, INSTALLED_SIGNERS)
        );
    }

    @Test
    public void anySharedCertificateIsAcceptedForKeyRotation() {
        Set<String> rotated = new HashSet<>();
        rotated.add(OTHER_SIGNER);
        rotated.add(RELEASE_SIGNER);
        assertEquals(
            ApkUpdateValidator.Result.OK,
            ApkUpdateValidator.validate(PKG, 1186, rotated, PKG, 1185, INSTALLED_SIGNERS)
        );
    }

    @Test
    public void unreadableArchiveIsRejected() {
        assertEquals(
            ApkUpdateValidator.Result.UNREADABLE,
            ApkUpdateValidator.validate(null, 1186, Collections.singleton(RELEASE_SIGNER), PKG, 1185, INSTALLED_SIGNERS)
        );
        assertEquals(
            ApkUpdateValidator.Result.UNREADABLE,
            ApkUpdateValidator.validate(PKG, 1186, null, PKG, 1185, INSTALLED_SIGNERS)
        );
        assertEquals(
            ApkUpdateValidator.Result.UNREADABLE,
            ApkUpdateValidator.validate(PKG, 1186, Collections.emptySet(), PKG, 1185, INSTALLED_SIGNERS)
        );
    }
}
