package ch.threema.app.activities.ballot;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;
import ch.threema.app.ExecutorServices;
import ch.threema.app.R;
import ch.threema.app.activities.ThreemaActivity;
import ch.threema.app.di.DependencyContainer;
import ch.threema.app.exceptions.NotAllowedException;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.RootViewDeferringInsetsCallback;
import ch.threema.app.ui.StepPagerStrip;
import ch.threema.app.ui.TranslateDeferringInsetsAnimationCallback;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.BallotUtil;
import ch.threema.app.utils.IntentDataUtil;
import ch.threema.app.utils.TestUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.messages.ballot.BallotId;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.models.ballot.BallotChoiceModel;
import ch.threema.storage.models.ballot.BallotModel;

import static ch.threema.app.di.DIJavaCompat.isSessionScopeReady;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

public class BallotWizardActivity extends ThreemaActivity {
    private static final Logger logger = getThreemaLogger("BallotWizardActivity");

    /**
     * F1Whisper: boolean intent extra. When {@code true}, the wizard opens in checklist mode: a
     * shared interactive checklist (displayType=CHECKLIST, multiple-choice, intermediate results)
     * rather than a poll. The poll-only options are hidden so creating a checklist is a direct,
     * Scarlet-Notes-like flow surfaced from the attach menu.
     */
    public static final String EXTRA_CREATE_CHECKLIST = "create_checklist";

    private static final int NUM_PAGES = 2;

    @NonNull
    private final DependencyContainer dependencies = KoinJavaComponent.get(DependencyContainer.class);

    private ViewPager pager;
    private ScreenSlidePagerAdapter pagerAdapter;
    private StepPagerStrip stepPagerStrip;
    private MaterialButton nextButton, copyButton, prevButton;
    private MessageReceiver<?> receiver;

    private final List<BallotChoiceModel> ballotChoiceModelList = new ArrayList<>();
    private String ballotDescription;
    private BallotModel.Type ballotType;
    private BallotModel.Assessment ballotAssessment;
    // F1Whisper: LIST_MODE for a normal poll, CHECKLIST for an interactive shared checklist.
    private BallotModel.DisplayType ballotDisplayType = BallotModel.DisplayType.LIST_MODE;
    // F1Whisper: true when the wizard was launched as a dedicated "Checklist" flow (see
    // EXTRA_CREATE_CHECKLIST). Drives the wizard UI to hide poll-only options.
    private boolean checklistMode = false;

    private final List<WeakReference<BallotWizardFragment>> fragmentList = new ArrayList<>();
    private final Runnable createBallotRunnable = new Runnable() {
        @Override
        public void run() {
            // Initialize the ballot choice api id and the order
            for (int i = 0; i < ballotChoiceModelList.size(); i++) {
                BallotChoiceModel ballotChoiceModel = ballotChoiceModelList.get(i);
                ballotChoiceModel.setApiBallotChoiceId(i);
                ballotChoiceModel.setOrder(i);
            }

            BallotUtil.createBallot(
                receiver,
                ballotDescription,
                ballotType,
                ballotAssessment,
                ballotDisplayType,
                ballotChoiceModelList,
                new BallotId(),
                MessageId.random(),
                TriggerSource.LOCAL
            );
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        if (!isSessionScopeReady()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_ballot_wizard);

        pager = findViewById(R.id.pager);
        pagerAdapter = new ScreenSlidePagerAdapter(getSupportFragmentManager());
        pager.setAdapter(pagerAdapter);

        stepPagerStrip = findViewById(R.id.strip);
        stepPagerStrip.setPageCount(NUM_PAGES);
        stepPagerStrip.setCurrentPage(0);

        copyButton = findViewById(R.id.copy_ballot);
        copyButton.setOnClickListener(v -> startCopy());

        prevButton = findViewById(R.id.prev_page_button);
        prevButton.setOnClickListener(v -> prevPage());

        nextButton = findViewById(R.id.next_page_button);
        nextButton.setOnClickListener(v -> nextPage());

        pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i2) {
            }

            @Override
            public void onPageSelected(int position) {
                for (WeakReference<BallotWizardFragment> fragment : fragmentList) {
                    BallotWizardCallback callback = (BallotWizardCallback) fragment.get();
                    if (callback != null) {
                        callback.onPageSelected(position);
                    }
                }
                if (position == 1) {
                    if (checkTitle()) {
                        prevButton.setVisibility(View.VISIBLE);
                        nextButton.setText(R.string.finish);
                        copyButton.setVisibility(View.GONE);
                    } else {
                        position = 0;
                    }
                } else {
                    prevButton.setVisibility(View.GONE);
                    nextButton.setText(R.string.next);
                    copyButton.setVisibility(View.VISIBLE);
                }
                stepPagerStrip.setCurrentPage(position);
            }

            @Override
            public void onPageScrollStateChanged(int i) {
            }
        });

        setDefaults();
        handleIntent();

        handleDeviceInsetsAndImeAnimation();
    }

    private void handleDeviceInsetsAndImeAnimation() {

        final @NonNull ViewPager viewPager = findViewById(R.id.pager);
        ViewExtensionsKt.applyDeviceInsetsAsMargin(viewPager, InsetSides.all());

        final String tag = "ballot_wizard";

        // Set inset listener that will effectively apply the final view paddings for the views affected by the keyboard
        final @NonNull RootViewDeferringInsetsCallback rootInsetsDeferringCallback = new RootViewDeferringInsetsCallback(
            tag,
            null,
            null,
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
        );
        final FrameLayout bottomContainerAnimationParent = findViewById(R.id.bottom_container_animation_parent);
        ViewCompat.setWindowInsetsAnimationCallback(bottomContainerAnimationParent, rootInsetsDeferringCallback);
        ViewCompat.setOnApplyWindowInsetsListener(bottomContainerAnimationParent, rootInsetsDeferringCallback);

        // Set inset animation listener to temporarily push up/down the foreground control views while an IME animation is ongoing
        final RelativeLayout bottomControlsContainer = findViewById(R.id.bottom_container);
        final TranslateDeferringInsetsAnimationCallback keyboardAnimationInsetsCallback = new TranslateDeferringInsetsAnimationCallback(
            tag,
            bottomControlsContainer,
            null,
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout(),
            WindowInsetsCompat.Type.ime(),
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        );
        ViewCompat.setWindowInsetsAnimationCallback(bottomControlsContainer, keyboardAnimationInsetsCallback);
    }

    @Override
    protected void onDestroy() {
        synchronized (this.fragmentList) {
            fragmentList.clear();
        }
        super.onDestroy();
    }

    /**
     * save the attached fragments to update on copy command
     */
    @Override
    public void onAttachFragment(@NonNull Fragment fragment) {
        super.onAttachFragment(fragment);

        if (fragment instanceof BallotWizardFragment) {
            synchronized (this.fragmentList) {
                this.fragmentList.add(new WeakReference<>((BallotWizardFragment) fragment));
            }
        }
    }

    private void setDefaults() {
        setBallotType(BallotModel.Type.INTERMEDIATE);
        setBallotAssessment(BallotModel.Assessment.SINGLE_CHOICE);
        setResult(RESULT_CANCELED);
    }

    private void handleIntent() {
        this.receiver = IntentDataUtil.getMessageReceiverFromIntent(this, getIntent());
        if (this.receiver == null) {
            logger.info("No message receiver");
            finish();
            return;
        }

        // F1Whisper: a checklist is created from a dedicated attach-menu entry. Pre-configure the
        // wizard so the user lands on "enter your items" with the right semantics already set:
        //  - CHECKLIST display type (rides the poll wire, F1Whisper <-> F1Whisper only),
        //  - multiple-choice assessment (each member's checks are an independent, re-votable set),
        //  - intermediate results (everyone's checks are always visible, never closed).
        if (getIntent().getBooleanExtra(EXTRA_CREATE_CHECKLIST, false)) {
            this.checklistMode = true;
            setBallotDisplayType(BallotModel.DisplayType.CHECKLIST);
            setBallotAssessment(BallotModel.Assessment.MULTIPLE_CHOICE);
            setBallotType(BallotModel.Type.INTERMEDIATE);
        }
    }

    /**
     * F1Whisper: whether this wizard was opened as a dedicated checklist flow (the poll-only
     * options are hidden when {@code true}).
     */
    public boolean isChecklistMode() {
        return this.checklistMode;
    }

    @Override
    protected boolean enableOnBackPressedCallback() {
        return true;
    }

    @Override
    protected void handleOnBackPressed() {
        int currentItem = pager.getCurrentItem();
        if (currentItem == 0) {
            finish();
        } else {
            pager.setCurrentItem(currentItem - 1);
        }
    }

    private boolean checkTitle() {
        if (TestUtil.isEmptyOrNull(this.ballotDescription)) {
            BallotWizardCallback callback = (BallotWizardCallback) this.fragmentList.get(0).get();
            if (callback != null) {
                callback.onMissingTitle();
            }
            pager.setCurrentItem(0);
            return false;
        }
        return true;
    }

    public void nextPage() {
        int currentItem = pager.getCurrentItem() + 1;
        if (currentItem < NUM_PAGES) {
            pager.setCurrentItem(currentItem);
        } else {
            /* end */
            if (checkTitle()) {
                BallotWizardFragment1 fragment = (BallotWizardFragment1) pagerAdapter.instantiateItem(pager, pager.getCurrentItem());
                fragment.saveUnsavedData();
                // A poll needs at least two answers to be a choice; a checklist is meaningful with a
                // single item, so it only requires one.
                int minItems = checklistMode ? 1 : 2;
                if (this.ballotChoiceModelList.size() >= minItems) {
                    ExecutorServices.getSendMessageExecutorService().execute(createBallotRunnable);
                    setResult(RESULT_OK);
                    finish();
                } else {
                    int errorRes = checklistMode
                        ? R.string.checklist_item_count_error
                        : R.string.ballot_answer_count_error;
                    Toast.makeText(BallotWizardActivity.this, getString(errorRes), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    public void prevPage() {
        pager.setCurrentItem(0);
    }

    public void setBallotDescription(@Nullable String description) {
        this.ballotDescription = description != null ? description.trim() : null;
    }

    public void setBallotType(BallotModel.Type ballotType) {
        this.ballotType = ballotType;
    }

    public void setBallotAssessment(BallotModel.Assessment ballotAssessment) {
        this.ballotAssessment = ballotAssessment;
    }

    public void setBallotDisplayType(BallotModel.DisplayType ballotDisplayType) {
        this.ballotDisplayType = ballotDisplayType;
    }

    public BallotModel.DisplayType getBallotDisplayType() {
        return this.ballotDisplayType;
    }

    public List<BallotChoiceModel> getBallotChoiceModelList() {
        return this.ballotChoiceModelList;
    }

    public String getBallotDescription() {
        return this.ballotDescription;
    }

    public BallotModel.Type getBallotType() {
        return this.ballotType;
    }

    public BallotModel.Assessment getBallotAssessment() {
        return this.ballotAssessment;
    }

    private static class ScreenSlidePagerAdapter extends FragmentStatePagerAdapter {
        public ScreenSlidePagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new BallotWizardFragment0();
                case 1:
                    return new BallotWizardFragment1();
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

    public void startCopy() {
        Intent copyIntent = new Intent(this, BallotChooserActivity.class);
        startActivityForResult(copyIntent, ThreemaActivity.ACTIVITY_ID_COPY_BALLOT);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == ThreemaActivity.ACTIVITY_ID_COPY_BALLOT) {
                //get the ballot to copy
                int ballotToCopyId = IntentDataUtil.getBallotId(data);
                if (ballotToCopyId > 0) {
                    BallotModel ballotModel = dependencies.getBallotService().get(ballotToCopyId);
                    if (ballotModel != null) {
                        this.copyFrom(ballotModel);
                    } else {
                        logger.error("not a valid ballot model");
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void copyFrom(BallotModel ballotModel) {
        if (ballotModel != null) {
            this.ballotDescription = ballotModel.getName();
            this.ballotType = ballotModel.getType();
            this.ballotAssessment = ballotModel.getAssessment();

            this.ballotChoiceModelList.clear();

            try {
                for (BallotChoiceModel ballotChoiceModel : dependencies.getBallotService().getChoices(ballotModel.getId())) {
                    BallotChoiceModel choiceModel = new BallotChoiceModel();
                    choiceModel.setName(ballotChoiceModel.getName());
                    choiceModel.setType(ballotChoiceModel.getType());
                    choiceModel.setApiBallotChoiceId(ballotChoiceModel.getApiBallotChoiceId());
                    this.ballotChoiceModelList.add(choiceModel);
                }
            } catch (NotAllowedException e) {
                //cannot get choices
                logger.error("Exception", e);
            }

            //goto first page
            pager.setCurrentItem(0);

            //loop all active fragments
            for (WeakReference<BallotWizardFragment> ballotFragment : this.fragmentList) {
                BallotWizardFragment f = ballotFragment.get();
                if (f != null && f.isAdded()) {
                    f.updateView();
                }
            }
        }
    }

    public interface BallotWizardCallback {
        void onMissingTitle();

        void onPageSelected(int page);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
