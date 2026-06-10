package ch.threema.app.activities.wizard;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import java.time.Instant;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.R;
import ch.threema.app.activities.EnterSerialActivity;
import ch.threema.app.di.DependencyContainer;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.dialogs.WizardDialog;
import ch.threema.app.licensing.StoreLicenseCheck;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.NewWizardFingerPrintView;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.ThreemaException;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import static ch.threema.app.di.DIJavaCompat.isSessionScopeReady;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

public class WizardFingerPrintActivity extends WizardBackgroundActivity
    implements WizardDialog.WizardDialogCallback, GenericAlertDialog.DialogClickListener {

    private static final Logger logger = getThreemaLogger("WizardFingerPrintActivity");

    public static final int PROGRESS_MAX = 100;
    private static final String DIALOG_TAG_CREATE_ID = "ci";
    private static final String DIALOG_TAG_CREATE_ERROR = "ni";
    private static final String DIALOG_TAG_CREATE_ERROR_TA001 = "ni_ta001";
    private static final String DIALOG_TAG_FINGERPRINT_INFO = "fi";
    /** Prefix returned by APIConnector when the server rejects the activation key as already used. */
    private static final String TA001_PREFIX = "TA001:";
    private ProgressBar swipeProgress;
    private ImageView fingerView;

    @NonNull
    private final DependencyContainer dependencies = KoinJavaComponent.get(DependencyContainer.class);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        if (!isSessionScopeReady()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_new_fingerprint);

        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            findViewById(R.id.new_fingerprint_content),
            InsetSides.vertical(),
            SpacingValues.all(R.dimen.grid_unit_x2)
        );

        swipeProgress = findViewById(R.id.wizard1_swipe_progress);
        swipeProgress.setMax(PROGRESS_MAX);
        swipeProgress.setProgress(0);

        fingerView = findViewById(R.id.finger_overlay);
        findViewById(R.id.wizard_icon_info).setOnClickListener(v -> {
            WizardDialog wizardDialog = WizardDialog.newInstance(R.string.new_wizard_info_fingerprint, R.string.ok);
            wizardDialog.show(getSupportFragmentManager(), DIALOG_TAG_FINGERPRINT_INFO);
        });

        ((NewWizardFingerPrintView) findViewById(R.id.wizard1_finger_print))
            .setOnSwipeByte((bytes, step, maxSteps) -> {
                swipeProgress.setProgress(step);

                if (fingerView != null) {
                    fingerView.setVisibility(View.GONE);
                    fingerView = null;
                }

                if (step >= maxSteps) {
                    // disable fingerprint widget
                    findViewById(R.id.wizard1_finger_print).setEnabled(false);
                    // generate id and stuff
                    createIdentity(bytes);
                }
            }, PROGRESS_MAX);

        findViewById(R.id.cancel_compose).setOnClickListener(v -> finish());
    }

    @SuppressLint("StaticFieldLeak")
    private void createIdentity(final byte[] bytes) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected void onPreExecute() {
                GenericProgressDialog.newInstance(R.string.wizard_first_create_id,
                    R.string.please_wait).show(getSupportFragmentManager(), DIALOG_TAG_CREATE_ID);
            }

            @Override
            protected String doInBackground(Void... params) {
                try {
                    if (!dependencies.getUserService().hasIdentity()) {
                        dependencies.getUserService().createIdentity(bytes);
                        dependencies.getPreferenceService().resetIDBackupCount();
                        dependencies.getPreferenceService().setLastIDBackupReminderTimestamp(Instant.now());
                        dependencies.getNotificationPreferenceService().setWizardRunning(true);
                    }
                } catch (final ThreemaException e) {
                    logger.error("Exception", e);
                    return e.getMessage();
                } catch (final Exception e) {
                    logger.error("Exception", e);
                    return getString(R.string.new_wizard_need_internet);
                }
                return null;
            }

            @Override
            protected void onPostExecute(String errorString) {
                DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_CREATE_ID, true);

                if (TestUtil.isEmptyOrNull(errorString)) {
                    Intent intent = new Intent(WizardFingerPrintActivity.this, WizardBaseActivity.class);
                    intent.putExtra(WizardBaseActivity.EXTRA_NEW_IDENTITY_CREATED, true);
                    startActivity(intent);

                    overridePendingTransition(R.anim.abc_fade_in, R.anim.abc_fade_out);
                    finish();
                } else {
                    // Only attempt cleanup if an identity was actually stored (creation may have
                    // failed before the identity was written, e.g. on a TA001 server rejection).
                    if (dependencies.getUserService().hasIdentity()) {
                        try {
                            dependencies.getUserService().removeIdentity();
                        } catch (Exception e) {
                            logger.error("Exception removing identity after failed create", e);
                        }
                    }

                    if (errorString.startsWith(TA001_PREFIX)) {
                        // The server rejected the key as already redeemed. Show a clear,
                        // dedicated message. Do NOT offer "Try again" -- retrying with the
                        // same key will always fail and produce a toast loop.
                        GenericAlertDialog dialog = GenericAlertDialog.newInstance(
                            getString(R.string.error),
                            (CharSequence) getString(R.string.activation_key_already_redeemed),
                            R.string.use_different_key,
                            R.string.cancel);
                        getSupportFragmentManager().beginTransaction().add(dialog, DIALOG_TAG_CREATE_ERROR_TA001).commitAllowingStateLoss();
                    } else {
                        GenericAlertDialog dialog = GenericAlertDialog.newInstance(
                            getString(R.string.error),
                            errorString,
                            R.string.try_again,
                            R.string.cancel,
                            R.string.use_different_key);
                        dialog.setData(bytes);
                        getSupportFragmentManager().beginTransaction().add(dialog, DIALOG_TAG_CREATE_ERROR).commitAllowingStateLoss();
                    }
                }
            }
        }.execute();
    }

    @Override
    public void onYes(@Nullable String tag, @Nullable Object data) {
        if (tag != null && tag.equals(DIALOG_TAG_CREATE_ERROR)) {
            // Normal error: check license again and retry identity creation.
            StoreLicenseCheck.checkLicense(this, dependencies.getUserService());
            createIdentity((byte[]) data);
        } else if (tag != null && tag.equals(DIALOG_TAG_CREATE_ERROR_TA001)) {
            // TA001 dialog: positive button is "Use a different key" -- clear stored
            // credentials and route the user back to the activation key entry screen.
            clearLicenseAndGoToSerial();
        }
    }

    @Override
    public void onNeutral(@Nullable String tag, @Nullable Object data) {
        if (tag != null && tag.equals(DIALOG_TAG_CREATE_ERROR)) {
            // The stored activation key was already redeemed (e.g. server "TA001: already
            // redeemed key"). Clear the license credentials so the user can enter a fresh key
            // without reinstalling. This is the symmetric inverse of EnterSerialActivity.check(),
            // which writes exactly these four preferences. We deliberately do NOT touch any
            // already-created identity; we only drop the license state and route back to the
            // initial activation screen.
            clearLicenseAndGoToSerial();
        }
    }

    /** Clear stored license credentials and navigate back to the activation key entry screen. */
    private void clearLicenseAndGoToSerial() {
        var preferenceService = dependencies.getPreferenceService();
        preferenceService.setLicenseUsername(null);
        preferenceService.setLicensePassword(null);
        preferenceService.setOppfUrl(null);
        preferenceService.setLicensedStatus(false);

        startActivity(EnterSerialActivity.createIntent(this));
        finish();
    }

    @Override
    public void onNo(@Nullable String tag, @Nullable Object data) {
        finish();
    }

    @Override
    public void onNo(String tag) {
    }
}
