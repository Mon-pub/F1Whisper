package ch.threema.app.services;

import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * F1Whisper (fork review H-08): pure decision logic for validating a downloaded self-update APK
 * BEFORE it is handed to the package installer. Extracted from the download service so the rules
 * are unit-testable without Android.
 *
 * <p>The signer rule compares the candidate's signing certificates against the certificates of the
 * INSTALLED app: any shared certificate means the update is upgrade-compatible. This is the exact
 * property that matters (Android refuses cross-signer upgrades) and, unlike a hardcoded release
 * fingerprint, it also behaves correctly for debug/test installs. The release CI additionally pins
 * the exact release-certificate fingerprint at build time.</p>
 */
public final class ApkUpdateValidator {

    public enum Result {
        OK,
        /** The archive could not be parsed, or exposed no package/signature information. */
        UNREADABLE,
        /** The APK is not this application (wrong package name). */
        WRONG_PACKAGE,
        /** The APK's versionCode is not strictly newer than the installed app. */
        NOT_NEWER,
        /** No signing certificate in common with the installed app — the upgrade would be refused. */
        SIGNER_MISMATCH,
    }

    private ApkUpdateValidator() {
    }

    @NonNull
    public static Result validate(
        @Nullable String candidatePackage,
        long candidateVersionCode,
        @Nullable Set<String> candidateSignerSha256,
        @NonNull String installedPackage,
        long installedVersionCode,
        @NonNull Set<String> installedSignerSha256
    ) {
        if (candidatePackage == null || candidateSignerSha256 == null || candidateSignerSha256.isEmpty()) {
            return Result.UNREADABLE;
        }
        if (!installedPackage.equals(candidatePackage)) {
            return Result.WRONG_PACKAGE;
        }
        if (candidateVersionCode <= installedVersionCode) {
            return Result.NOT_NEWER;
        }
        for (String signer : candidateSignerSha256) {
            if (installedSignerSha256.contains(signer)) {
                return Result.OK;
            }
        }
        return Result.SIGNER_MISMATCH;
    }
}
