package ch.threema.app.activities.wizard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import androidx.annotation.NonNull;
import androidx.annotation.Px;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.LifecycleOwner;
import androidx.viewpager.widget.ViewPager;
import ch.threema.app.R;
import ch.threema.app.activities.ThreemaAppCompatActivity;
import ch.threema.app.di.DependencyContainer;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.dialogs.WizardDialog;
import ch.threema.app.fragments.wizard.WizardFragment0;
import ch.threema.app.fragments.wizard.WizardFragment1;
import ch.threema.app.fragments.wizard.WizardFragment2;
import ch.threema.app.fragments.wizard.WizardFragment4;
import ch.threema.app.threemasafe.ThreemaSafeMDMConfig;
import ch.threema.app.threemasafe.ThreemaSafeServerInfo;
import ch.threema.app.threemasafe.usecases.CheckBadPasswordUseCase;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.ParallaxViewPager;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.StepPagerStrip;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.utils.executor.BackgroundExecutor;
import ch.threema.app.utils.executor.BackgroundTask;
import ch.threema.app.workers.WorkSyncWorker;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.domain.taskmanager.TriggerSource;

import static ch.threema.app.AppConstants.PHONE_LINKED_PLACEHOLDER;
import static ch.threema.app.di.DIJavaCompat.isSessionScopeReady;
import static ch.threema.app.protocolsteps.ApplicationSetupStepsKt.runApplicationSetupSteps;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;
import static ch.threema.app.threemasafe.usecases.CheckBadPasswordUseCase.Result.BAD_PASSWORD;

public class WizardBaseActivity extends ThreemaAppCompatActivity implements
    LifecycleOwner,
    ViewPager.OnPageChangeListener,
    View.OnClickListener,
    WizardFragment1.OnSettingsChangedListener,
    WizardFragment2.OnSettingsChangedListener,
    WizardFragment4.SettingsInterface,
    WizardDialog.WizardDialogCallback {

    private static final Logger logger = getThreemaLogger("WizardBaseActivity");

    public static final String EXTRA_NEW_IDENTITY_CREATED = "newIdentity";
    private static final String EXTRA_WORK_SYNC_PERFORMED = "workSyncPerformed";
    private static final String DIALOG_TAG_USE_ID_AS_NICKNAME = "nd";
    private static final String DIALOG_TAG_THREEMA_SAFE = "sd";
    private static final String DIALOG_TAG_PASSWORD_BAD = "pwb";
    private static final String DIALOG_TAG_PASSWORD_BAD_WORK = "pwbw";
    private static final String DIALOG_TAG_APPLICATION_SETUP_RETRY = "app-setup-retry";

    private static final int NUM_PAGES = 4;
    private static final long DIALOG_DELAY = 200;

    private static final String DIALOG_TAG_WORK_SYNC = "workSync";
    private static final String DIALOG_TAG_PASSWORD_PRESET_CONFIRM = "pwPreset";

    @NonNull
    private final DependencyContainer dependencies = KoinJavaComponent.get(DependencyContainer.class);
    private final CheckBadPasswordUseCase badPasswordUseCase = KoinJavaComponent.get(CheckBadPasswordUseCase.class);

    private static int lastPage = 0;
    private ParallaxViewPager viewPager;
    private MaterialButton prevButton, nextButton;
    private StepPagerStrip stepPagerStrip;
    private String nickname, email, number, prefix, presetMobile, presetEmail, safePassword;
    private ThreemaSafeServerInfo safeServerInfo = new ThreemaSafeServerInfo();
    private boolean skipWizard = false, readOnlyProfile = false;
    private ThreemaSafeMDMConfig safeConfig;
    private boolean isNewIdentity = false;
    private WizardFragment4 fragment4;
    private final BackgroundExecutor backgroundExecutor = new BackgroundExecutor();
    private boolean workSyncPerformed = false;

    private final Handler dialogHandler = new Handler();

    private Runnable showDialogDelayedTask(final int current, final int previous) {
        return () -> RuntimeUtil.runOnUiThread(() -> {
            if (current == WizardFragment2.PAGE_ID && previous == WizardFragment1.PAGE_ID && TestUtil.isEmptyOrNull(getSafePassword())) {
                if (safeConfig.isBackupForced()) {
                    setPage(WizardFragment1.PAGE_ID);
                } else if (!isReadOnlyProfile()) {
                    WizardDialog wizardDialog = WizardDialog.newInstance(R.string.safe_disable_confirm, R.string.yes, R.string.no, WizardDialog.Highlight.NEGATIVE);
                    wizardDialog.show(getSupportFragmentManager(), DIALOG_TAG_THREEMA_SAFE);
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        if (savedInstanceState != null) {
            workSyncPerformed = savedInstanceState.getBoolean(EXTRA_WORK_SYNC_PERFORMED);
        }

        if (!isSessionScopeReady()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_wizard);

        nextButton = findViewById(R.id.next_page_button);
        nextButton.setOnClickListener(v -> nextPage());

        prevButton = findViewById(R.id.prev_page_button);
        prevButton.setVisibility(View.GONE);
        prevButton.setOnClickListener(v -> prevPage());

        stepPagerStrip = findViewById(R.id.strip);
        stepPagerStrip.setPageCount(NUM_PAGES);
        stepPagerStrip.setCurrentPage(WizardFragment0.PAGE_ID);

        viewPager = findViewById(R.id.pager);
        viewPager.addLayer(findViewById(R.id.layer0));
        viewPager.addLayer(findViewById(R.id.layer1));

        handleDeviceInsets();

        Intent intent = getIntent();
        if (intent != null) {
            isNewIdentity = intent.getBooleanExtra(EXTRA_NEW_IDENTITY_CREATED, false);
        }

        if (ConfigUtils.isWorkBuild() && !workSyncPerformed) {
            performWorkSync();
        } else {
            setupConfig();
        }
    }

    private void handleDeviceInsets() {

        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            viewPager,
            InsetSides.top(),
            SpacingValues.vertical(R.dimen.wizard_contents_padding)
        );

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.navigation_footer), (view, windowInsets) -> {

            final @NonNull Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());

            final @Px int paddingLeft = insets.left;
            final @Px int paddingRight = insets.right;
            final @Px int paddingBottom;

            if (windowInsets.isVisible(WindowInsetsCompat.Type.ime())) {
                final @NonNull Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                paddingBottom = imeInsets.bottom;
            } else {
                paddingBottom = insets.bottom;
            }

            view.setPadding(paddingLeft, 0, paddingRight, paddingBottom);

            return windowInsets;
        });
    }

    private void setupConfig() {
        safeConfig = ThreemaSafeMDMConfig.getInstance();

        viewPager.setAdapter(new ScreenSlidePagerAdapter(getSupportFragmentManager()));
        viewPager.addOnPageChangeListener(this);

        if (ConfigUtils.isWorkRestricted()) {
            if (isSafeEnabled()) {
                if (isSafeForced()) {
                    safePassword = safeConfig.getPassword();
                }
                safeServerInfo = safeConfig.getServerInfo();
            }

            String stringPreset;
            Boolean booleanPreset;

            stringPreset = dependencies.getAppRestrictions().getLinkedEmail();
            if (stringPreset != null) {
                email = stringPreset;
            }
            stringPreset = dependencies.getAppRestrictions().getLinkedPhone();
            if (stringPreset != null) {
                splitMobile(stringPreset);
            }
            stringPreset = dependencies.getAppRestrictions().getNickname();
            if (stringPreset != null) {
                nickname = stringPreset;
            } else {
                nickname = dependencies.getUserService().getIdentity();
            }
            booleanPreset = dependencies.getAppRestrictions().isReadOnlyProfileOrNull();
            if (booleanPreset != null) {
                readOnlyProfile = booleanPreset;
            }
            if (dependencies.getAppRestrictions().isSkipWizard()) {
                skipWizard();
            }
        } else {
            // ignore backup presets in restricted mode
            if (!TestUtil.isEmptyOrNull(presetMobile)) {
                splitMobile(presetMobile);
            }
            if (!TestUtil.isEmptyOrNull(presetEmail)) {
                email = presetEmail;
            }

        }

        presetMobile = dependencies.getUserService().getLinkedMobile();
        presetEmail = dependencies.getUserService().getLinkedEmail();

        if (ConfigUtils.isWorkRestricted()) {
            // confirm the use of a managed password
            if (!safeConfig.isBackupDisabled() && safeConfig.isBackupPasswordPreset()) {
                WizardDialog wizardDialog = WizardDialog.newInstance(R.string.safe_managed_password_confirm, R.string.accept, R.string.real_not_now, WizardDialog.Highlight.NONE);
                wizardDialog.show(getSupportFragmentManager(), DIALOG_TAG_PASSWORD_PRESET_CONFIRM);
            }
        }
    }

    /**
     * Perform an early synchronous fetch2. In case of failure due to rate-limiting, do not allow user to continue
     */
    private void performWorkSync() {
        GenericProgressDialog.newInstance(R.string.work_data_sync_desc,
            R.string.please_wait).show(getSupportFragmentManager(), DIALOG_TAG_WORK_SYNC);

        WorkSyncWorker.Companion.performOneTimeWorkSync(
            this,
            () -> {
                // On success
                DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_WORK_SYNC, true);
                workSyncPerformed = true;
                setupConfig();
            },
            () -> {
                // On fail
                DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_WORK_SYNC, true);
                RuntimeUtil.runOnUiThread(() -> Toast.makeText(WizardBaseActivity.this, R.string.unable_to_fetch_configuration, Toast.LENGTH_LONG).show());
                logger.info("Unable to post work request for fetch2");
                try {
                    dependencies.getUserService().removeIdentity();
                } catch (Exception e) {
                    logger.error("Unable to remove identity", e);
                }
                finishAndRemoveTask();
            });
    }

    private void splitMobile(String phoneNumber) {
        if (PHONE_LINKED_PLACEHOLDER.equals(phoneNumber)) {
            prefix = "";
            number = PHONE_LINKED_PLACEHOLDER;
        } else {
            try {
                PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
                Phonenumber.PhoneNumber numberProto = phoneNumberUtil.parse(phoneNumber, "");
                prefix = "+" + numberProto.getCountryCode();
                number = String.valueOf(numberProto.getNationalNumber());
            } catch (NumberParseException e) {
                logger.error("Exception", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        viewPager.removeOnPageChangeListener(this);

        super.onDestroy();
    }

    /**
     * This method will be invoked when the current page is scrolled, either as part
     * of a programmatically initiated smooth scroll or a user initiated touch scroll.
     *
     * @param position             Position index of the first page currently being displayed.
     *                             Page position+1 will be visible if positionOffset is nonzero.
     * @param positionOffset       Value from [0, 1) indicating the offset from the page at position.
     * @param positionOffsetPixels Value in pixels indicating the offset from position.
     */
    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

    }

    /**
     * This method will be invoked when a new page becomes selected. Animation is not
     * necessarily complete.
     *
     * @param position Position index of the new selected page.
     */
    @SuppressLint("StaticFieldLeak")
    @Override
    public void onPageSelected(int position) {
        prevButton.setVisibility(position == WizardFragment0.PAGE_ID ? View.GONE : View.VISIBLE);
        nextButton.setVisibility(position == NUM_PAGES - 1 ? View.GONE : View.VISIBLE);

        stepPagerStrip.setCurrentPage(position);

        if (position == WizardFragment1.PAGE_ID && safeConfig.isSkipBackupPasswordEntry()) {
            if (lastPage == WizardFragment0.PAGE_ID) {
                nextPage();
            } else {
                prevPage();
            }
            return;
        }

        if (position == WizardFragment2.PAGE_ID && lastPage == WizardFragment1.PAGE_ID) {
            if (!TextUtils.isEmpty(safePassword)) {
                new AsyncTask<Void, Void, Boolean>() {
                    @Override
                    protected Boolean doInBackground(Void... voids) {
                        return badPasswordUseCase.call(safePassword) == BAD_PASSWORD;
                    }

                    @Override
                    protected void onPostExecute(Boolean isBad) {
                        if (isBad) {
                            if (dependencies.getAppRestrictions().getSafePasswordPattern() != null) {
                                WizardDialog wizardDialog = WizardDialog.newInstance(dependencies.getAppRestrictions().getSafePasswordMessage(), R.string.try_again);
                                wizardDialog.show(getSupportFragmentManager(), DIALOG_TAG_PASSWORD_BAD_WORK);
                            } else {
                                WizardDialog wizardDialog = WizardDialog.newInstance(R.string.password_bad_explain, R.string.continue_anyway, R.string.try_again, WizardDialog.Highlight.NEGATIVE);
                                wizardDialog.show(getSupportFragmentManager(), DIALOG_TAG_PASSWORD_BAD);
                            }
                        }
                    }
                }.execute();
            }
        }

        if (position > lastPage && position >= WizardFragment2.PAGE_ID && position <= WizardFragment4.PAGE_ID) {
            // we delay dialogs for a few milliseconds to prevent stuttering of the page change animation
            dialogHandler.removeCallbacks(showDialogDelayedTask(position, lastPage));
            dialogHandler.postDelayed(showDialogDelayedTask(position, lastPage), DIALOG_DELAY);
        }

        lastPage = position;
    }

    /**
     * Called when the scroll state changes. Useful for discovering when the user
     * begins dragging, when the pager is automatically settling to the current page,
     * or when it is fully stopped/idle.
     *
     * @param state The new scroll state.
     * @see ViewPager#SCROLL_STATE_IDLE
     * @see ViewPager#SCROLL_STATE_DRAGGING
     * @see ViewPager#SCROLL_STATE_SETTLING
     */
    @Override
    public void onPageScrollStateChanged(int state) {
    }

    /**
     * Called when a view has been clicked.
     *
     * @param v The view that was clicked.
     */
    @Override
    public void onClick(View v) {
        if (v.equals(nextButton)) {
            nextPage();
        } else if (v.equals(prevButton)) {
            prevPage();
        }
    }

    @Override
    public void onWizardFinished(WizardFragment4 fragment) {
        fragment4 = fragment;

        viewPager.lock(true);
        prevButton.setVisibility(View.GONE);

        dependencies.getUserService().setPublicNickname(this.nickname, TriggerSource.LOCAL);

        // Anonymous setup: no phone/email linking and no contact sync. Persist the disabled
        // contact-sync policy so the post-setup screens never prompt for the contacts permission,
        // then proceed straight to Threema Safe preparation.
        /* trigger a connection now - as application lifecycle was set to resumed state when there was no identity yet */
        dependencies.getLifetimeService().ensureConnection();
        dependencies.getSynchronizedSettingsService().getContactSyncPolicySetting().setFromLocal(false);

        prepareThreemaSafe();
    }

    @Override
    public void onNicknameSet(String nickname) {
        this.nickname = nickname;
    }

    @Override
    public void onSafePasswordSet(final String password) {
        safePassword = password;
    }

    @Override
    public void onSafeServerInfoSet(ThreemaSafeServerInfo safeServerInfo) {
        this.safeServerInfo = safeServerInfo;
    }

    @Override
    public String getNickname() {
        return this.nickname;
    }

    @Override
    public boolean getSafeForcePasswordEntry() {
        return safeConfig.isBackupForced();
    }

    @Override
    public boolean getSafeSkipBackupPasswordEntry() {
        return safeConfig.isSkipBackupPasswordEntry();
    }

    @Override
    public boolean isSafeEnabled() {
        return !safeConfig.isBackupDisabled();
    }

    @Override
    public boolean isSafeForced() {
        return safeConfig.isBackupForced();
    }

    @Override
    public String getSafePassword() {
        return this.safePassword;
    }

    @Override
    public ThreemaSafeServerInfo getSafeServerInfo() {
        return this.safeServerInfo;
    }

    @Override
    public boolean isReadOnlyProfile() {
        return this.readOnlyProfile;
    }

    @Override
    public boolean isSkipWizard() {
        return this.skipWizard;
    }

    /**
     * Return whether the identity was just created
     *
     * @return true if it's a new identity, false if the identity was restored
     */
    public boolean isNewIdentity() {
        return isNewIdentity;
    }

    @Override
    public void onYes(String tag, Object data) {
        switch (tag) {
            case DIALOG_TAG_USE_ID_AS_NICKNAME:
                this.nickname = dependencies.getUserService().getIdentity();
                break;
            case DIALOG_TAG_PASSWORD_BAD_WORK:
                prevPage();
                break;
            case DIALOG_TAG_PASSWORD_BAD:
            case DIALOG_TAG_THREEMA_SAFE:
            case DIALOG_TAG_PASSWORD_PRESET_CONFIRM:
                break;
            case DIALOG_TAG_APPLICATION_SETUP_RETRY:
                runApplicationSetupStepsAndRestart();
                break;
        }
    }

    @Override
    public void onNo(String tag) {
        switch (tag) {
            case DIALOG_TAG_USE_ID_AS_NICKNAME:
                prevPage();
                break;
            case DIALOG_TAG_THREEMA_SAFE:
                prevPage();
                break;
            case DIALOG_TAG_PASSWORD_BAD:
                setPage(WizardFragment1.PAGE_ID);
                break;
            case DIALOG_TAG_PASSWORD_PRESET_CONFIRM:
                finish();
                System.exit(0);
                break;
        }
    }

    @Override
    protected boolean enableOnBackPressedCallback() {
        return true;
    }

    @Override
    protected void handleOnBackPressed() {
        if (prevButton != null && prevButton.getVisibility() == View.VISIBLE) {
            prevPage();
        }
    }

    private static class ScreenSlidePagerAdapter extends FragmentStatePagerAdapter {
        public ScreenSlidePagerAdapter(FragmentManager fm) {
            super(fm, FragmentStatePagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case WizardFragment0.PAGE_ID:
                    return new WizardFragment0();
                case WizardFragment1.PAGE_ID:
                    return new WizardFragment1();
                case WizardFragment2.PAGE_ID:
                    return new WizardFragment2();
                case WizardFragment4.PAGE_ID:
                    return new WizardFragment4();
                default:
                    break;
            }
            return null;
        }

        @Override
        public int getCount() {
            return NUM_PAGES;
        }
    }

    public void nextPage() {
        int currentItem = viewPager.getCurrentItem() + 1;
        if (currentItem < NUM_PAGES) {
            viewPager.setCurrentItem(currentItem);
        }
    }

    public void prevPage() {
        int currentItem = viewPager.getCurrentItem();
        if (currentItem != 0) {
            viewPager.setCurrentItem(currentItem - 1);
        }
    }

    public void setPage(int page) {
        viewPager.setCurrentItem(page);
    }

    public void skipWizard() {
        skipWizard = true;
        viewPager.post(() -> viewPager.setCurrentItem(WizardFragment4.PAGE_ID));
    }

    private void runApplicationSetupStepsAndRestart() {
        backgroundExecutor.execute(new BackgroundTask<Boolean>() {
            @Override
            public void runBefore() {
                // Nothing to do
            }

            @Override
            public Boolean runInBackground() {
                return runApplicationSetupSteps(dependencies.getServiceManager());
            }

            @Override
            public void runAfter(Boolean result) {
                if (!Boolean.TRUE.equals(result)) {
                    WizardDialog.newInstance(R.string.application_setup_steps_failed, R.string.retry)
                        .show(getSupportFragmentManager(), DIALOG_TAG_APPLICATION_SETUP_RETRY);
                    return;
                }

                dependencies.getNotificationPreferenceService().setWizardRunning(false);
                dependencies.getPreferenceService().setLatestVersion(WizardBaseActivity.this);

                // Flush conversation cache (after a restore) to ensure that the conversation list
                // will be loaded from the database to prevent the list being incomplete.
                try {
                    dependencies.getConversationService().reset();
                } catch (Exception e) {
                    logger.error("Exception", e);
                }

                ConfigUtils.recreateActivity(WizardBaseActivity.this);
            }
        });
    }

    @SuppressLint("StaticFieldLeak")
    private void prepareThreemaSafe() {
        if (!TestUtil.isEmptyOrNull(getSafePassword())) {
            new AsyncTask<Void, Void, byte[]>() {
                @Override
                protected void onPreExecute() {
                    fragment4.setThreemaSafeInProgress(true, getString(R.string.preparing_threema_safe));
                }

                @Override
                protected byte[] doInBackground(Void... voids) {
                    return dependencies.getThreemaSafeService().deriveMasterKey(getSafePassword(), dependencies.getUserService().getIdentity());
                }

                @Override
                protected void onPostExecute(byte[] masterkey) {
                    fragment4.setThreemaSafeInProgress(false, getString(R.string.menu_done));

                    if (masterkey != null) {
                        dependencies.getThreemaSafeService().storeMasterKey(masterkey);
                        dependencies.getPreferenceService().setThreemaSafeServerInfo(safeServerInfo);
                        dependencies.getThreemaSafeService().setEnabled(true);
                        dependencies.getThreemaSafeService().uploadNow(true);
                    } else {
                        Toast.makeText(WizardBaseActivity.this, R.string.safe_error_preparing, Toast.LENGTH_LONG).show();
                    }

                    runApplicationSetupStepsAndRestart();
                }
            }.execute();
        } else {
            // no password was set
            // do not save mdm settings if backup is forced and no password was set - this will cause a password prompt later
            if (!(ConfigUtils.isWorkRestricted() && ThreemaSafeMDMConfig.getInstance().isBackupForced())) {
                dependencies.getThreemaSafeService().storeMasterKey(new byte[0]);
            }
            runApplicationSetupStepsAndRestart();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_WORK_SYNC_PERFORMED, workSyncPerformed);
    }

    @NonNull
    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, WizardBaseActivity.class);
    }
}
