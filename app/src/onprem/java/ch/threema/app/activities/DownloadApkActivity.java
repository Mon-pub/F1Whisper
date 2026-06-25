package ch.threema.app.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;

import java.io.File;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import ch.threema.app.R;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.services.ApkUpdateDownloadService;
import ch.threema.app.utils.IntentDataUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

/**
 * F1Whisper: the in-app self-updater for the sideloaded OnPrem build (the other foss/libre flavors
 * ship a no-op stub). The actual APK download URL is supplied at runtime via the intent
 * ({@link IntentDataUtil#getUrl}) from the check_license updateUrl (= the latest GitHub release APK
 * asset), so the downloader itself is brand-agnostic.
 * <p>
 * The download runs fully in the BACKGROUND via {@link ApkUpdateDownloadService} (an OkHttp
 * foreground-service download that survives this activity finishing and shows its own progress
 * notification). There is no blocking modal: after kicking it off, this activity finishes so the user
 * keeps using the app. On completion the service posts an "update ready, tap to install" notification
 * which relaunches this activity with {@link #EXTRA_INSTALL_FILE_PATH} to run the install (reusing the
 * unknown-sources grant flow, with the apk handed to the package installer via a FileProvider uri).
 * The manual-fallback help URL points at the F1Whisper GitHub releases page.
 * <p>
 * The system {@link android.app.DownloadManager} is intentionally NOT used: it is silently broken on
 * locked-down OEMs (Xiaomi/MIUI freezes the downloads provider, so enqueue succeeds but nothing ever
 * downloads). See {@link ApkUpdateDownloadService} for the full rationale.
 */
public class DownloadApkActivity extends ThreemaActivity implements GenericAlertDialog.DialogClickListener {
    private static final Logger logger = getThreemaLogger("DownloadApkActivity");

    private static final String DIALOG_TAG_DOWNLOAD_UPDATE = "cfu";

    private static final String PREF_STRING = "download_apk_dialog_time";

    private static final String BUNDLE_INSTALL_PATH = "install_path";

    public static final String EXTRA_FORCE_UPDATE_DIALOG = "forceu";

    // F1Whisper: relaunch extra carrying the absolute path of the finished apk, set by the "update
    // ready" notification so this activity performs the install step (and the unknown-sources grant
    // flow). Replaces the former DownloadManager-id extra.
    public static final String EXTRA_INSTALL_FILE_PATH = "installpath";

    // F1Whisper: where to send the user if the automatic download/install fails.
    private static final String FALLBACK_DOWNLOAD_URL = "https://github.com/Mon-pub/F1Whisper/releases/latest";

    private SharedPreferences sharedPreferences;

    private int numFailures = 0;

    // F1Whisper: the apk to install (content:// FileProvider uri) + its source path, kept across the
    // unknown-sources settings round-trip and config changes.
    @Nullable
    private Uri pendingInstallUri;
    @Nullable
    private String pendingInstallPath;

    @Nullable
    private String pendingDownloadUrl;

    private final ActivityResultLauncher<Intent> requestUnknownSourcesSettingsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (pendingInstallUri != null) {
                installPackage(pendingInstallUri);
            } else {
                logger.error("pendingInstallUri should be set");
                finishUp();
            }
        });

    // F1Whisper: request POST_NOTIFICATIONS (Android 13+) so the download progress + "update ready"
    // notifications aren't silently suppressed. We proceed with the download regardless of the result.
    private final ActivityResultLauncher<String> postNotificationsLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
        granted -> reallyDownload(pendingDownloadUrl));

    private void finishUp() {
        new Handler().postDelayed(this::finish, 1000);
    }

    /**
     * F1Whisper: build a FileProvider content uri for the downloaded apk so it can be granted to the
     * system package installer. The apk lives in our app-private external files dir, exposed via the
     * {@code <external-files-path>} entry in file_paths.xml.
     */
    @Nullable
    private Uri uriForApk(@NonNull String absolutePath) {
        try {
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(absolutePath));
        } catch (IllegalArgumentException e) {
            logger.error("Could not build FileProvider uri for {}", absolutePath, e);
            return null;
        }
    }

    /**
     * Use this on Android N and newer.
     *
     * @param downloadedFileUri the uri of the downloaded apk file
     */
    private void installPackage(@NonNull Uri downloadedFileUri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(getApplicationContext(), getString(R.string.enable_unknown_sources, getString(R.string.app_name)), Toast.LENGTH_LONG).show();
            finishUp();
        } else {
            Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.setData(downloadedFileUri);
            logger.info("Installing downloaded apk: {}", downloadedFileUri);
            try {
                startActivity(installIntent);
                finishUp();
            } catch (Exception e) {
                numFailures++;
                logger.error("Error installing apk", e);
                showHelpOnUpdateFailure();
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        if (savedInstanceState != null) {
            pendingInstallPath = savedInstanceState.getString(BUNDLE_INSTALL_PATH, null);
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();

        // F1Whisper: relaunched from the "update ready" completion notification -> run the install.
        final String installPath = intent.getStringExtra(EXTRA_INSTALL_FILE_PATH);
        if (installPath != null) {
            pendingInstallPath = installPath;
            final Uri uri = uriForApk(installPath);
            if (uri == null) {
                logger.error("Downloaded apk uri is null for {}", installPath);
                showHelpOnUpdateFailure();
                return;
            }
            pendingInstallUri = uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !getPackageManager().canRequestPackageInstalls()) {
                try {
                    requestUnknownSourcesSettingsLauncher.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse(String.format("package:%s", getPackageName()))));
                } catch (ActivityNotFoundException e) {
                    logger.error("No activity for unknown sources", e);
                    Toast.makeText(getApplicationContext(), getString(R.string.enable_unknown_sources, getString(R.string.app_name)), Toast.LENGTH_LONG).show();
                    finishUp();
                }
            } else {
                installPackage(uri);
            }
            return;
        }

        long lastShownTime = sharedPreferences.getLong(PREF_STRING, 0);

        if (intent.getBooleanExtra(EXTRA_FORCE_UPDATE_DIALOG, false) || (System.currentTimeMillis() > (lastShownTime + DateUtils.DAY_IN_MILLIS))) {
            GenericAlertDialog dialog = GenericAlertDialog.newInstance(R.string.update_available, IntentDataUtil.getMessage(intent), R.string.download, R.string.not_now, false);
            dialog.setData(IntentDataUtil.getUrl(intent));
            getSupportFragmentManager().beginTransaction().add(dialog, DIALOG_TAG_DOWNLOAD_UPDATE).commitAllowingStateLoss();
        } else {
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (pendingInstallPath != null) {
            outState.putString(BUNDLE_INSTALL_PATH, pendingInstallPath);
        }
    }

    @Override
    public void onYes(String tag, Object data) {
        pendingDownloadUrl = (String) data;
        // F1Whisper: make sure the progress notification can show before kicking off the download.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            reallyDownload(pendingDownloadUrl);
        }
    }

    /**
     * F1Whisper: kick off the background download (foreground service) and finish, so the user keeps
     * using the app. The service shows download progress; completion posts a "tap to install"
     * notification.
     */
    private void reallyDownload(@Nullable String data) {
        if (data != null) {
            try {
                ApkUpdateDownloadService.enqueue(this, data);
                Toast.makeText(getApplicationContext(), R.string.self_updater_downloading_background, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                logger.error("Exception while starting update download", e);
                Toast.makeText(getApplicationContext(), R.string.an_error_occurred, Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    @Override
    public void onNo(String tag, Object data) {
        sharedPreferences.edit().putLong(PREF_STRING, System.currentTimeMillis()).apply();

        finish();
    }

    private void showHelpOnUpdateFailure() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        SpannableString failMessage = new SpannableString(getString(R.string.self_updater_installation_failed));
        Linkify.addLinks(failMessage, Linkify.WEB_URLS);
        builder.setMessage(failMessage).setTitle(R.string.error).setPositiveButton(R.string.ok, (dialog, which) -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(FALLBACK_DOWNLOAD_URL));
            startActivity(browserIntent);
            finish();
        }).setNegativeButton(R.string.cancel, (dialog, which) -> finish());
        AlertDialog dialog = builder.create();
        dialog.show();
        ((TextView) dialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
    }

}
