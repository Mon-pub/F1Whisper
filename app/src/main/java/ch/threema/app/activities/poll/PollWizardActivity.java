package ch.threema.app.activities.poll;

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
import ch.threema.app.utils.PollUtil;
import ch.threema.app.utils.IntentDataUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.messages.poll.PollId;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.models.poll.PollChoiceModel;
import ch.threema.storage.models.poll.PollModel;

import static ch.threema.app.di.DIJavaCompat.isSessionScopeReady;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;
import static ch.threema.common.JavaCompat.isNullOrEmpty;

public class PollWizardActivity extends ThreemaActivity {
    private static final Logger logger = getThreemaLogger("PollWizardActivity");

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

    private final List<PollChoiceModel> pollChoiceModelList = new ArrayList<>();
    private String pollDescription;
    private PollModel.Type pollType;
    private PollModel.Assessment pollAssessment;
    // F1Whisper: LIST_MODE for a normal poll, CHECKLIST for an interactive shared checklist.
    private PollModel.DisplayType pollDisplayType = PollModel.DisplayType.LIST_MODE;
    // F1Whisper: true when the wizard was launched as a dedicated "Checklist" flow (see
    // EXTRA_CREATE_CHECKLIST). Drives the wizard UI to hide poll-only options.
    private boolean checklistMode = false;

    private final List<WeakReference<PollWizardFragment>> fragmentList = new ArrayList<>();
    private final Runnable createPollRunnable = new Runnable() {
        @Override
        public void run() {
            // Initialize the poll choice api id and the order
            for (int i = 0; i < pollChoiceModelList.size(); i++) {
                PollChoiceModel pollChoiceModel = pollChoiceModelList.get(i);
                pollChoiceModel.setApiPollChoiceId(i);
                pollChoiceModel.setOrder(i);
            }

            PollUtil.createPoll(
                receiver,
                pollDescription,
                pollType,
                pollAssessment,
                pollDisplayType,
                pollChoiceModelList,
                new PollId(),
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

        setContentView(R.layout.activity_poll_wizard);

        pager = findViewById(R.id.pager);
        pagerAdapter = new ScreenSlidePagerAdapter(getSupportFragmentManager());
        pager.setAdapter(pagerAdapter);

        stepPagerStrip = findViewById(R.id.strip);
        stepPagerStrip.setPageCount(NUM_PAGES);
        stepPagerStrip.setCurrentPage(0);

        copyButton = findViewById(R.id.copy_poll);
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
                for (WeakReference<PollWizardFragment> fragment : fragmentList) {
                    PollWizardCallback callback = (PollWizardCallback) fragment.get();
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

        final String tag = "poll_wizard";

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

        if (fragment instanceof PollWizardFragment) {
            synchronized (this.fragmentList) {
                this.fragmentList.add(new WeakReference<>((PollWizardFragment) fragment));
            }
        }
    }

    private void setDefaults() {
        setPollType(PollModel.Type.INTERMEDIATE);
        setPollAssessment(PollModel.Assessment.SINGLE_CHOICE);
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
            setPollDisplayType(PollModel.DisplayType.CHECKLIST);
            setPollAssessment(PollModel.Assessment.MULTIPLE_CHOICE);
            setPollType(PollModel.Type.INTERMEDIATE);
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
        if (isNullOrEmpty(this.pollDescription)) {
            PollWizardCallback callback = (PollWizardCallback) this.fragmentList.get(0).get();
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
                PollWizardFragment1 fragment = (PollWizardFragment1) pagerAdapter.instantiateItem(pager, pager.getCurrentItem());
                fragment.saveUnsavedData();
                // A poll needs at least two answers to be a choice; a checklist is meaningful with a
                // single item, so it only requires one.
                int minItems = checklistMode ? 1 : 2;
                if (this.pollChoiceModelList.size() >= minItems) {
                    ExecutorServices.getSendMessageExecutorService().execute(createPollRunnable);
                    setResult(RESULT_OK);
                    finish();
                } else {
                    int errorRes = checklistMode
                        ? R.string.checklist_item_count_error
                        : R.string.ballot_answer_count_error;
                    Toast.makeText(PollWizardActivity.this, getString(errorRes), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    public void prevPage() {
        pager.setCurrentItem(0);
    }

    public void setPollDescription(@Nullable String description) {
        this.pollDescription = description != null ? description.trim() : null;
    }

    public void setPollType(PollModel.Type pollType) {
        this.pollType = pollType;
    }

    public void setPollAssessment(PollModel.Assessment pollAssessment) {
        this.pollAssessment = pollAssessment;
    }

    public void setPollDisplayType(PollModel.DisplayType pollDisplayType) {
        this.pollDisplayType = pollDisplayType;
    }

    public PollModel.DisplayType getPollDisplayType() {
        return this.pollDisplayType;
    }

    public List<PollChoiceModel> getPollChoiceModelList() {
        return this.pollChoiceModelList;
    }

    public String getPollDescription() {
        return this.pollDescription;
    }

    public PollModel.Type getPollType() {
        return this.pollType;
    }

    public PollModel.Assessment getPollAssessment() {
        return this.pollAssessment;
    }

    private static class ScreenSlidePagerAdapter extends FragmentStatePagerAdapter {
        public ScreenSlidePagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new PollWizardFragment0();
                case 1:
                    return new PollWizardFragment1();
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
        Intent copyIntent = new Intent(this, PollChooserActivity.class);
        startActivityForResult(copyIntent, ThreemaActivity.ACTIVITY_ID_COPY_POLL);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == ThreemaActivity.ACTIVITY_ID_COPY_POLL) {
                //get the poll to copy
                int pollToCopyId = IntentDataUtil.getPollId(data);
                if (pollToCopyId > 0) {
                    PollModel pollModel = dependencies.getPollService().get(pollToCopyId);
                    if (pollModel != null) {
                        this.copyFrom(pollModel);
                    } else {
                        logger.error("not a valid poll model");
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void copyFrom(PollModel pollModel) {
        if (pollModel != null) {
            this.pollDescription = pollModel.getName();
            this.pollType = pollModel.getType();
            this.pollAssessment = pollModel.getAssessment();

            this.pollChoiceModelList.clear();

            try {
                for (PollChoiceModel pollChoiceModel : dependencies.getPollService().getChoices(pollModel.getId())) {
                    PollChoiceModel choiceModel = new PollChoiceModel();
                    choiceModel.setName(pollChoiceModel.getName());
                    choiceModel.setType(pollChoiceModel.getType());
                    choiceModel.setApiPollChoiceId(pollChoiceModel.getApiPollChoiceId());
                    this.pollChoiceModelList.add(choiceModel);
                }
            } catch (NotAllowedException e) {
                //cannot get choices
                logger.error("Exception", e);
            }

            //goto first page
            pager.setCurrentItem(0);

            //loop all active fragments
            for (WeakReference<PollWizardFragment> pollFragment : this.fragmentList) {
                PollWizardFragment f = pollFragment.get();
                if (f != null && f.isAdded()) {
                    f.updateView();
                }
            }
        }
    }

    public interface PollWizardCallback {
        void onMissingTitle();

        void onPageSelected(int page);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
