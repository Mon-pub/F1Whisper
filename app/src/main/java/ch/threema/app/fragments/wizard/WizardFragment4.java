package ch.threema.app.fragments.wizard;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.slf4j.Logger;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;
import ch.threema.app.R;
import ch.threema.app.ui.interop.ButtonPrimaryXml;
import ch.threema.app.threemasafe.ThreemaSafeServerInfo;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import static ch.threema.common.JavaCompat.isNullOrEmpty;

public class WizardFragment4 extends WizardFragment {
    private static final Logger logger = getThreemaLogger("WizardFragment4");
    private TextView nicknameText, safeText;
    private ProgressBar safeProgress;
    private ButtonPrimaryXml finishButtonCompose;
    private SettingsInterface callback;
    public static final int PAGE_ID = 3;

    private static final String BUNDLE_FINISH_BUTTON_ENABLED = "finishButtonEnabled";
    private boolean isFinishButtonEnabled = true;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        if (savedInstanceState != null) {
            isFinishButtonEnabled = savedInstanceState.getBoolean(BUNDLE_FINISH_BUTTON_ENABLED, true);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_wizard4, container, false);

        nicknameText = rootView.findViewById(R.id.wizard_nickname_preset);
        safeText = rootView.findViewById(R.id.threema_safe_preset);
        safeProgress = rootView.findViewById(R.id.threema_safe_progress);

        finishButtonCompose = rootView.findViewById(R.id.wizard_finish_compose);
        finishButtonCompose.setOnClickListener(v -> onClickFinish());

        return rootView;
    }

    @Override
    protected int getAdditionalInfoText() {
        return 0;
    }

    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        callback = (SettingsInterface) activity;
    }

    void initValues() {
        if (!isResumed()) {
            return;
        }
        nicknameText.setText(callback.getNickname());
        setThreemaSafeInProgress(false, null);
        setFinishButtonEnabled(isFinishButtonEnabled);
    }

    @Override
    @SuppressLint("NewApi")
    public void onResume() {
        super.onResume();

        initValues();

        if (callback.isSkipWizard()) {
            onClickFinish();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(BUNDLE_FINISH_BUTTON_ENABLED, isFinishButtonEnabled);
    }

    public void setFinishButtonEnabled(final boolean isEnabled) {
        this.isFinishButtonEnabled = isEnabled;
        if (finishButtonCompose != null) {
            finishButtonCompose.setButtonEnabled(isEnabled);
        }
    }

    private void onClickFinish() {
        setFinishButtonEnabled(false);
        callback.onWizardFinished();
    }

    public void setThreemaSafeInProgress(boolean inProgress, String text) {
        safeProgress.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        if (isNullOrEmpty(text)) {
            if (isNullOrEmpty(callback.getSafePassword())) {
                safeText.setText(R.string.off);
            } else {
                if (callback.getSafeServerInfo().isDefaultServer()) {
                    safeText.setText(getString(R.string.on));
                } else {
                    safeText.setText(String.format("%s - %s", getString(R.string.on), callback.getSafeServerInfo().getHostName()));
                }
            }
        } else {
            safeText.setText(text);
        }
    }

    public interface SettingsInterface {

        @Nullable
        String getNickname();

        boolean getSafeForcePasswordEntry();

        boolean getSafeSkipBackupPasswordEntry();

        boolean isSafeEnabled();

        boolean isSafeForced();

        @Nullable
        String getSafePassword();

        @NonNull
        ThreemaSafeServerInfo getSafeServerInfo();

        boolean isReadOnlyProfile();

        boolean isSkipWizard();

        void onWizardFinished();
    }
}
