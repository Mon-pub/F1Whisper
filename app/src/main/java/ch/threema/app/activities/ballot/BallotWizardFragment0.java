package ch.threema.app.activities.ballot;

import android.os.Bundle;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputLayout;

import org.slf4j.Logger;

import androidx.annotation.NonNull;
import ch.threema.app.R;
import ch.threema.app.ui.SimpleTextWatcher;
import ch.threema.app.utils.ViewUtil;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import ch.threema.storage.models.ballot.BallotModel;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

public class BallotWizardFragment0 extends BallotWizardFragment implements BallotWizardActivity.BallotWizardCallback {
    private static final Logger logger = getThreemaLogger("BallotWizardFragment0");

    private EditText editText;
    private TextInputLayout textInputLayout;
    private CheckBox secretCheckbox;
    private CheckBox typeCheckbox;
    private CheckBox checklistCheckbox;
    private TextView checklistHint;
    private TextView wizardTitle;
    private TextView wizardBody;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        ViewGroup rootView = (ViewGroup) inflater.inflate(
            R.layout.fragment_ballot_wizard0, container, false);

        this.editText = rootView.findViewById(R.id.wizard_edittext);
        this.editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == getResources().getInteger(R.integer.ime_wizard_next) || actionId == EditorInfo.IME_ACTION_DONE) {
                    if (getBallotActivity() != null) {
                        getBallotActivity().nextPage();
                    }
                }
                return false;
            }
        });
        this.editText.addTextChangedListener(new SimpleTextWatcher() {
            public void afterTextChanged(@NonNull Editable editable) {
                if (getBallotActivity() != null) {
                    getBallotActivity().setBallotDescription(editText.getText().toString());
                }
                if (editable.length() > 0) {
                    textInputLayout.setError(null);
                }
            }
        });

        this.textInputLayout = rootView.findViewById(R.id.wizard_edittext_layout);

        this.typeCheckbox = rootView.findViewById(R.id.type);
        this.typeCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (getBallotActivity() != null) {
                    getBallotActivity().setBallotAssessment(
                        isChecked ? BallotModel.Assessment.MULTIPLE_CHOICE : BallotModel.Assessment.SINGLE_CHOICE
                    );
                }
            }
        });
        this.secretCheckbox = rootView.findViewById(R.id.visibility);
        this.secretCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (getBallotActivity() != null) {
                    getBallotActivity().setBallotType(
                        isChecked ? BallotModel.Type.INTERMEDIATE : BallotModel.Type.RESULT_ON_CLOSE
                    );
                }
            }
        });

        this.wizardTitle = rootView.findViewById(R.id.wizard_title);
        this.wizardBody = rootView.findViewById(R.id.wizard_body);
        this.checklistHint = rootView.findViewById(R.id.checklist_hint);
        this.checklistCheckbox = rootView.findViewById(R.id.checklist);
        this.checklistCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (getBallotActivity() != null) {
                    // A checklist rides the existing Poll wire; it is F1Whisper <-> F1Whisper only.
                    getBallotActivity().setBallotDisplayType(
                        isChecked ? BallotModel.DisplayType.CHECKLIST : BallotModel.DisplayType.LIST_MODE
                    );
                    // A checklist REQUIRES multiple-choice assessment: every member's checks are an
                    // independent, re-votable set, and multiple members can check the same item.
                    // SINGLE_CHOICE would make a checklist a one-shot single pick (the rejected bug),
                    // so toggling "Create as checklist" must force MULTIPLE_CHOICE. When turning the
                    // toggle back off we restore SINGLE_CHOICE, the normal poll default.
                    getBallotActivity().setBallotAssessment(
                        isChecked
                            ? BallotModel.Assessment.MULTIPLE_CHOICE
                            : BallotModel.Assessment.SINGLE_CHOICE
                    );
                }
                applyChecklistToggleUi(isChecked);
            }
        });

        this.updateView();
        return rootView;
    }

    /**
     * F1Whisper: whether this wizard was launched as a dedicated checklist (from the attach-menu
     * "Checklist" entry). In that mode all poll-only controls are hidden and the screen reads as a
     * checklist editor, so the user goes straight from a title to "enter your items".
     */
    private boolean isDedicatedChecklist() {
        return getBallotActivity() != null && getBallotActivity().isChecklistMode();
    }

    /**
     * Reflect the non-dedicated "Create as checklist" toggle in the surrounding UI. Keeps the
     * poll-only controls consistent with the checklist invariant:
     * <ul>
     *   <li>the multiple-choice ("type") checkbox is forced checked and disabled while checklist is
     *       on -- a checklist is ALWAYS multiple-choice, so the user must not be able to flip it
     *       back to single-choice (which produced the rejected one-shot-poll bug); when the toggle
     *       is turned back off the checkbox becomes interactive again;</li>
     *   <li>the "show intermediate results" ("secret") checkbox is hidden -- a checklist always
     *       shows everyone's checks;</li>
     *   <li>the fork-only hint and the screen title are swapped.</li>
     * </ul>
     * Idempotent; safe to call from both the toggle handler and updateView.
     */
    private void applyChecklistToggleUi(boolean isChecklist) {
        if (this.typeCheckbox != null) {
            // Force-reflect the enforced MULTIPLE_CHOICE state without re-firing the listener loop.
            if (this.typeCheckbox.isChecked() != isChecklist) {
                this.typeCheckbox.setChecked(isChecklist);
            }
            this.typeCheckbox.setEnabled(!isChecklist);
        }
        if (this.checklistHint != null) {
            this.checklistHint.setVisibility(isChecklist ? View.VISIBLE : View.GONE);
        }
        if (this.secretCheckbox != null) {
            this.secretCheckbox.setVisibility(isChecklist ? View.GONE : View.VISIBLE);
        }
        if (this.wizardTitle != null) {
            this.wizardTitle.setText(isChecklist ? R.string.checklist : R.string.ballot_create);
        }
    }

    /**
     * Hide every poll-only option and re-label the screen as a checklist editor. Idempotent; safe to
     * call from both onCreateView and updateView.
     */
    private void applyChecklistModeUi() {
        if (this.typeCheckbox != null) {
            this.typeCheckbox.setVisibility(View.GONE);
        }
        if (this.secretCheckbox != null) {
            this.secretCheckbox.setVisibility(View.GONE);
        }
        if (this.checklistCheckbox != null) {
            // The mode is already decided; the toggle would be redundant and confusing.
            this.checklistCheckbox.setVisibility(View.GONE);
        }
        if (this.checklistHint != null) {
            this.checklistHint.setVisibility(View.VISIBLE);
        }
        if (this.wizardTitle != null) {
            this.wizardTitle.setText(R.string.checklist);
        }
        if (this.wizardBody != null) {
            this.wizardBody.setText(R.string.checklist_wizard0_explain);
        }
        if (this.textInputLayout != null) {
            this.textInputLayout.setHint(getString(R.string.checklist_subject_hint));
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);
    }

    @Override
    public void updateView() {
        if (getBallotActivity() != null) {
            ViewUtil.showAndSet(this.editText,
                this.getBallotActivity().getBallotDescription());
        }
        if (this.getBallotActivity() == null) {
            return;
        }

        // Dedicated checklist flow: the mode is fixed, so hide every poll-only option and re-label
        // the screen. Don't run the checkbox-driven poll logic below.
        if (isDedicatedChecklist()) {
            applyChecklistModeUi();
            return;
        }

        boolean isChecklist = this.getBallotActivity().getBallotDisplayType() == BallotModel.DisplayType.CHECKLIST;

        // A checklist is always multiple-choice, so when the persisted state already says checklist
        // (e.g. restored on configuration change or copied from an existing checklist) keep the
        // assessment correct -- the model must never end up CHECKLIST + SINGLE_CHOICE.
        if (isChecklist
            && this.getBallotActivity().getBallotAssessment() != BallotModel.Assessment.MULTIPLE_CHOICE) {
            this.getBallotActivity().setBallotAssessment(BallotModel.Assessment.MULTIPLE_CHOICE);
        }

        ViewUtil.showAndSet(this.typeCheckbox,
            this.getBallotActivity().getBallotAssessment() == BallotModel.Assessment.MULTIPLE_CHOICE);

        ViewUtil.showAndSet(this.secretCheckbox,
            this.getBallotActivity().getBallotType() == BallotModel.Type.INTERMEDIATE);

        ViewUtil.showAndSet(this.checklistCheckbox, isChecklist);
        if (this.typeCheckbox != null) {
            this.typeCheckbox.setVisibility(View.VISIBLE);
        }
        if (this.checklistCheckbox != null) {
            this.checklistCheckbox.setVisibility(View.VISIBLE);
        }
        // Centralised: forces the multiple-choice checkbox checked+disabled, hides "secret", and
        // swaps the title/hint when the checklist toggle is on (mirrors the toggle handler).
        applyChecklistToggleUi(isChecklist);
        if (this.wizardBody != null) {
            this.wizardBody.setText(R.string.ballot_wizard0_explain);
        }
        if (this.textInputLayout != null) {
            this.textInputLayout.setHint(getString(R.string.ballot_subject_hint));
        }
    }

    @Override
    public void onMissingTitle() {
        this.textInputLayout.setError(getString(R.string.title_cannot_be_empty));
        this.editText.setFocusableInTouchMode(true);
        this.editText.setFocusable(true);
        this.editText.requestFocus();
    }

    @Override
    public void onPageSelected(int page) {
        if (page == 1) {
            this.editText.clearFocus();
            this.editText.setFocusableInTouchMode(false);
            this.editText.setFocusable(false);
        } else {
            this.editText.setFocusableInTouchMode(true);
            this.editText.setFocusable(true);
        }
    }
}
