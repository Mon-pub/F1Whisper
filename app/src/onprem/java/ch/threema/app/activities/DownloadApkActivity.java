package ch.threema.app.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import ch.threema.app.R;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.utils.DownloadUtil;
import ch.threema.app.utils.IntentDataUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

/**
 * F1Whisper: the in-app self-updater for the sideloaded OnPrem build (the other foss/libre flavors
 * ship a no-op stub). The actual APK download URL is supplied at runtime via the intent
 * ({@link IntentDataUtil#getUrl}) from the check_license updateUrl (= the latest GitHub release APK
 * asset), so the downloader itself is brand-agnostic.
 * <p>
 * The download runs fully in the BACKGROUND via the system {@link DownloadManager} (which survives
 * this activity finishing and shows its own progress notification). There is no blocking modal: after
 * enqueuing, this activity finishes so the user keeps using the app. On completion
 * {@link ch.threema.app.receivers.UpdateDownloadCompleteReceiver} posts an "update ready, tap to
 * install" notification which relaunches this activity with {@link #EXTRA_INSTALL_DOWNLOAD_ID} to run
 * the install (reusing the unknown-sources grant flow). The manual-fallback help URL points at the
 * F1Whisper GitHub releases page.
 */
public class DownloadApkActivity extends ThreemaActivity implements GenericAlertDialog.DialogClickListener {
    private static final Logger logger = getThreemaLogger("DownloadApkActivity");

    private static final String DIALOG_TAG_DOWNLOAD_UPDATE = "cfu";

    private static final String PREF_STRING = "download_apk_dialog_time";

    private static final String BUNDLE_DOWNLOAD_ID = "download_id";

    public static final String EXTRA_FORCE_UPDATE_DIALOG = "forceu";

    // F1Whisper: relaunch extra carrying the completed download id, set by the "update ready"
    // notification so this activity performs the install step (and the unknown-sources grant flow).
    public static final String EXTRA_INSTALL_DOWNLOAD_ID = "installid";

    // F1Whisper: persisted id of the currently enqueued self-update download. Read by
    // UpdateDownloadCompleteReceiver so it only acts on our own download.
    public static final String PREF_DOWNLOAD_ID = "self_update_download_id";

    // F1Whisper: where to send the user if the automatic download/install fails.
    private static final String FALLBACK_DOWNLOAD_URL = "https://github.com/Mon-pub/F1Whisper/releases/latest";

    private SharedPreferences sharedPreferences;
    private long downloadId = -1;

    private int numFailures = 0;

    @Nullable
    private String pendingDownloadUrl;

    private final ActivityResultLauncher<Intent> requestUnknownSourcesSettingsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
        result -> {
            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadId > 0) {
                installPackage(downloadManager.getUriForDownloadedFile(downloadId));
            } else {
                logger.error("downloadId should be set");
                finishUp();
            }
        });

    // F1Whisper: request POST_NOTIFICATIONS (Android 13+) so the system download-progress
    // notification isn't silently suppressed. We proceed with the download regardless of the result.
    private final ActivityResultLauncher<String> postNotificationsLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
        granted -> reallyDownload(pendingDownloadUrl));

    private void finishUp() {
        new Handler().postDelayed(this::finish, 1000);
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
            logger.info("Downloaded file to: {}", downloadedFileUri.getPath());
            try {
                startActivity(installIntent);
                finishUp();
            } catch (Exception e) {
                numFailures++;
                logger.error("Error installing apk", e);
                if (numFailures > 1) {
                    showHelpOnUpdateFailure();
                    return;
                }
                // Try to download it on external directory (needed for some OPPO, OnePlus and realme devices)
                reallyDownload(IntentDataUtil.getUrl(getIntent()));
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        if (savedInstanceState != null) {
            downloadId = savedInstanceState.getLong(BUNDLE_DOWNLOAD_ID, -1);
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();

        // F1Whisper: relaunched from the "update ready" completion notification -> run the install.
        final long installId = intent.getLongExtra(EXTRA_INSTALL_DOWNLOAD_ID, -1);
        if (installId > 0) {
            downloadId = installId;
            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = downloadManager.getUriForDownloadedFile(installId);
            if (uri == null) {
                logger.error("Downloaded file uri is null for id {}", installId);
                showHelpOnUpdateFailure();
                return;
            }
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

        outState.putLong(BUNDLE_DOWNLOAD_ID, downloadId);
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
     * F1Whisper: enqueue the background download and finish, so the user keeps using the app. The
     * system shows download progress; completion is handled by UpdateDownloadCompleteReceiver.
     */
    private void reallyDownload(@Nullable String data) {
        if (data != null) {
            try {
                long id = DownloadUtil.downloadUpdate(this, data);
                sharedPreferences.edit().putLong(PREF_DOWNLOAD_ID, id).apply();
                Toast.makeText(getApplicationContext(), R.string.self_updater_downloading_background, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                logger.error("Exception while downloading update", e);
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
