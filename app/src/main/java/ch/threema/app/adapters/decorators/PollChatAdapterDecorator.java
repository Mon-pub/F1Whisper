package ch.threema.app.adapters.decorators;

import android.content.Context;
import android.graphics.Paint;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import ch.threema.android.FlowJavaCompat;
import ch.threema.android.ToastDuration;
import ch.threema.app.ExecutorServices;
import ch.threema.app.adapters.ChecklistEditAdapter;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.eventbus.GlobalEventFlows;
import ch.threema.app.eventbus.events.PollEvent;
import ch.threema.app.ui.DebouncedOnClickListener;
import ch.threema.app.ui.SelectorDialogItem;
import ch.threema.app.ui.listitemholder.ComposeMessageHolder;
import ch.threema.app.utils.PollUtil;
import ch.threema.app.utils.LinkifyUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.poll.PollChoiceModel;
import ch.threema.storage.models.poll.PollModel;
import ch.threema.storage.models.poll.PollVoteModel;
import ch.threema.storage.models.data.media.PollDataModel;

import static ch.threema.android.ToastKt.showToast;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

public class PollChatAdapterDecorator extends ChatAdapterDecorator {
    private static final Logger logger = getThreemaLogger("PollChatAdapterDecorator");

    public final static int ACTION_VOTE = 0, ACTION_RESULTS = 1, ACTION_CLOSE = 2;

    /**
     * Trailing window for coalescing rapid checklist toggles into a single vote send. The send uses
     * a LEADING + TRAILING debounce: the FIRST toggle of a quiescent burst is sent immediately
     * (leading edge) so peers see the change right away, and any further toggles within this window
     * are coalesced into ONE trailing {@code PollVote}/{@code GroupPollVote} carrying the final
     * selection -- so a rapid burst still collapses to ~1-2 sends, peers do not wait, and network
     * traffic stays minimal. The actor's own UI is already instant via the optimistic overlay.
     */
    private static final long VOTE_DEBOUNCE_MS = 250L;

    // ------------------------------------------------------------------------------------------
    // Optimistic-UI state (PROCESS-WIDE, not per-instance).
    //
    // A fresh PollChatAdapterDecorator is constructed for every row on every notifyDataSetChanged,
    // so per-instance fields would be discarded between binds and the optimistic overlay would be
    // lost the instant the list refreshes. These maps are therefore static and keyed by pollId.
    //
    //  - optimisticChecks: pollId -> the set of choiceIds I (the local user) have checked, as a
    //    pure local overlay. Seeded from the DB on first touch, mutated instantly on every tap, and
    //    cleared once the persisted DB state has caught up (eventual-consistency reconcile).
    //  - pendingVoteTasks: pollId -> the scheduled (trailing) vote-send future. A new tap during
    //    the trailing window cancels and reschedules it, coalescing the burst into one trailing send.
    //  - lastVoteSendAt: pollId -> uptimeMillis of the most recent send, used to detect a quiescent
    //    burst so the FIRST toggle fires immediately (leading edge) instead of waiting the window.
    // ------------------------------------------------------------------------------------------
    private static final Map<Integer, Set<Integer>> optimisticChecks = new ConcurrentHashMap<>();
    private static final Map<Integer, ScheduledFuture<?>> pendingVoteTasks = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastVoteSendAt = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService voteSendScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChecklistVoteCoalescer");
            t.setDaemon(true);
            return t;
        });

    // Prune the process-wide optimistic-UI overlay whenever a poll leaves play (removed or
    // closed). Without this, a pollId whose burst never reconciled -- e.g. the user navigated away
    // mid-toggle, or the poll was deleted -- would leak its entry in optimisticChecks /
    // pendingVoteTasks indefinitely, and a later poll REUSING that primary-key id (after a wipe
    // or reinstall-restore) would inherit stale checks. Subscribed exactly once for the process.
    private static final AtomicBoolean optimisticStateCleanupRegistered = new AtomicBoolean(false);

    private static void registerOptimisticStateCleanup() {
        if (!optimisticStateCleanupRegistered.compareAndSet(false, true)) {
            return;
        }
        GlobalEventFlows globalEventFlows = KoinJavaComponent.get(GlobalEventFlows.class);
        FlowJavaCompat.collect(
            ProcessLifecycleOwner.get(),
            Lifecycle.State.CREATED,
            globalEventFlows.getPolls(),
            event -> {
                // An in-place edit or a vote keeps the overlay valid; only a poll that closes or
                // disappears drops it.
                if (event instanceof PollEvent.PollClosed || event instanceof PollEvent.PollRemoved) {
                    clearOptimisticState(event.getPoll().getId());
                }
            }
        );
    }

    /**
     * Drop any optimistic overlay and cancel any pending coalesced vote for a poll. Called both on
     * the eventual-consistency reconcile path and from the lifecycle cleanup subscription so neither map
     * can retain an entry for a poll that is gone.
     */
    private static void clearOptimisticState(int pollId) {
        optimisticChecks.remove(pollId);
        lastVoteSendAt.remove(pollId);
        ScheduledFuture<?> pending = pendingVoteTasks.remove(pollId);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    public interface PollChatListener {
        void showSelectorDialog(
            ArrayList<Integer> action,
            String title,
            ArrayList<SelectorDialogItem> items,
            PollModel pollModel
        );

        void openDefaultActivity(PollModel pollModel, boolean canVote);
    }

    @NonNull
    private final PollChatListener listener;

    public PollChatAdapterDecorator(
        AbstractMessageModel messageModel,
        @NonNull ChatAdapterDecoratorListener chatAdapterDecoratorListener,
        @NonNull LinkifyUtil.LinkifyListener linkifyListener,
        Helper helper,
        @NonNull PollChatListener listener
    ) {
        super(messageModel, chatAdapterDecoratorListener, linkifyListener, helper);
        this.listener = listener;
        registerOptimisticStateCleanup();
    }

    @Override
    protected void configureChatMessage(final ComposeMessageHolder holder, Context context, int position) {
        try {
            final AbstractMessageModel messageModel = this.getMessageModel();
            String explain = "";

            PollDataModel pollData = messageModel.getPollData();

            final PollModel pollModel = this.helper.getPollService().get(pollData.getPollId());

            // F1Whisper: locate the optional interactive-checklist container. The poll bubble has
            // no content_block, so it is searched from the message block (the card view) which
            // includes conversation_list_item_poll (where the container lives).
            final LinearLayout checklistContainer = holder.messageBlockView != null
                ? holder.messageBlockView.findViewById(R.id.checklist_container)
                : null;

            if (pollModel == null) {
                holder.bodyTextView.setText("");
                hideChecklist(checklistContainer);
            } else if (PollUtil.isChecklist(pollModel)) {
                // Interactive checklist bubble (rides the Poll wire). The title still renders; the
                // "tap to vote" affordance is replaced by the inline checkable rows.
                if (this.showHide(holder.bodyTextView, true)) {
                    holder.bodyTextView.setText(pollModel.getName());
                }
                renderChecklist(context, holder, pollModel, checklistContainer);
            } else {
                hideChecklist(checklistContainer);
                switch (pollData.getType()) {
                    case POLL_CREATED:
                    case POLL_MODIFIED:
                        if (PollUtil.canVote(pollModel, helper.getMessageReceiver())) {
                            explain = context.getString(R.string.ballot_tap_to_vote);
                        }
                        break;
                    case POLL_CLOSED:
                        explain = context.getString(R.string.ballot_tap_to_view_results);
                        break;
                }

                if (this.showHide(holder.bodyTextView, true)) {
                    holder.bodyTextView.setText(pollModel.getName());
                }
            }

            boolean isChecklist = PollUtil.isChecklist(pollModel);

            if (this.showHide(holder.secondaryTextView, !isChecklist)) {
                holder.secondaryTextView.setText(explain);
            }

            this.setOnClickListener(new DebouncedOnClickListener(500) {
                @Override
                public void onDebouncedClick(View v) {
                    if (isChecklist) {
                        // Toggling happens on the individual rows; ignore whole-bubble taps.
                        return;
                    }
                    if (messageModel.getState() != MessageState.FS_KEY_MISMATCH && messageModel.getState() != MessageState.SENDFAILED) {
                        showChooser(v.getContext(), pollModel);
                    }
                }
            }, holder.messageBlockView);

            if (holder.controller != null) {
                holder.controller.setIconResource(R.drawable.ic_outline_rule);
                holder.controller.setContentDescription(
                    context.getString(isChecklist ? R.string.checklist_placeholder : R.string.ballot_placeholder));
            }

            RuntimeUtil.runOnUiThread(() -> setupResendStatus(holder));
        } catch (Exception e) {
            logger.error("Exception", e);
        }
    }

    private void hideChecklist(LinearLayout checklistContainer) {
        if (checklistContainer != null) {
            checklistContainer.removeAllViews();
            checklistContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Render the interactive checklist rows. Each poll choice is a checkbox; "checking" an item is
     * a {@code PollVote}/{@code GroupPollVote} over the existing wire. Checked items sink to the
     * bottom and unchecked rise to the top; each checked item lists the members who checked it in a
     * small font.
     *
     * <p>The local user's own check state is driven by the OPTIMISTIC overlay (see
     * {@link #optimisticChecks}) when one exists, so a tap reflects instantly without waiting for the
     * vote round-trip. Once the persisted DB state matches the overlay (reconciled), the overlay is
     * dropped and rendering falls back to the DB truth.
     */
    private void renderChecklist(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer
    ) {
        if (checklistContainer == null) {
            return;
        }
        checklistContainer.removeAllViews();
        checklistContainer.setVisibility(View.VISIBLE);
        // Stamp the container with the poll it now renders. A deferred refresh (see
        // refreshChecklistOnUi) re-checks this tag before touching the holder so a row recycled to a
        // different poll (or a non-poll message) is not mutated by a stale callback.
        checklistContainer.setTag(R.id.checklist_container, pollModel.getId());

        try {
            final int pollId = pollModel.getId();
            final List<PollChoiceModel> choices = new ArrayList<>(this.helper.getPollService().getChoices(pollId));

            // Prune the optimistic overlay against the live choice set. After an edit removes an item
            // (the creator may now delete checked items too), a stale choiceId could otherwise linger
            // in the overlay forever and block reconcile (overlay never equals the persisted set).
            // Drop any overlay choiceId no longer present; if pruning empties the overlay and nothing
            // is in flight, clear it entirely.
            {
                final Set<Integer> overlayToPrune = optimisticChecks.get(pollId);
                if (overlayToPrune != null) {
                    final Set<Integer> liveChoiceIds = new LinkedHashSet<>();
                    for (PollChoiceModel c : choices) {
                        liveChoiceIds.add(c.getId());
                    }
                    synchronized (overlayToPrune) {
                        overlayToPrune.retainAll(liveChoiceIds);
                    }
                }
            }

            // Map of choiceId -> ordered set of voter identities who checked it (choice == 1).
            final Map<Integer, Set<String>> votersByChoice = new HashMap<>();
            // My own checked choices as persisted in the DB (the committed truth).
            final Set<Integer> persistedMyChecks = new LinkedHashSet<>();
            final String myIdentity = this.helper.getMyIdentity();

            for (PollVoteModel vote : this.helper.getPollService().getPollVotes(pollId)) {
                if (vote.getChoice() == 1) {
                    votersByChoice
                        .computeIfAbsent(vote.getPollChoiceId(), k -> new LinkedHashSet<>())
                        .add(vote.getVotingIdentity());
                    if (myIdentity != null && myIdentity.equals(vote.getVotingIdentity())) {
                        persistedMyChecks.add(vote.getPollChoiceId());
                    }
                }
            }

            // Reconcile: if the persisted state already matches the optimistic overlay, the vote
            // round-trip has caught up, so drop the overlay and render pure DB truth. Otherwise the
            // overlay wins (the user's tap has not been confirmed yet).
            final Set<Integer> overlay = optimisticChecks.get(pollId);
            final Set<Integer> myChecks;
            if (overlay == null) {
                myChecks = persistedMyChecks;
            } else {
                // Snapshot the overlay under its own lock before comparing: it is a synchronized set
                // mutated from the UI thread (toggle) while the coalescer thread reads it, so an
                // unguarded equals() could race.
                final Set<Integer> overlaySnapshot;
                synchronized (overlay) {
                    overlaySnapshot = new LinkedHashSet<>(overlay);
                }
                if (overlaySnapshot.equals(persistedMyChecks) && !pendingVoteTasks.containsKey(pollId)) {
                    // Persisted DB state has caught up AND no send is still in flight -> reconciled.
                    // Drop the overlay (and any leftover future) so we render pure DB truth.
                    clearOptimisticState(pollId);
                    myChecks = persistedMyChecks;
                } else {
                    // The user's tap has not been confirmed yet (or a send is still pending): the
                    // overlay wins so the UI stays instant + consistent with the intended state.
                    myChecks = overlaySnapshot;
                }
            }

            // The "is this item checked at all (by anyone)" used for sink-order must follow the
            // optimistic overlay for MY checks too, so my just-tapped item sinks/rises instantly.
            final java.util.function.IntPredicate isCheckedByAnyone = choiceId -> {
                if (myChecks.contains(choiceId)) {
                    return true;
                }
                Set<String> voters = votersByChoice.get(choiceId);
                if (voters == null) {
                    return false;
                }
                // Ignore my own persisted vote here; my state is governed by the overlay above.
                for (String voter : voters) {
                    if (myIdentity == null || !myIdentity.equals(voter)) {
                        return true;
                    }
                }
                return false;
            };

            // Sink checked items: checked items go to the bottom, preserving the manual order field
            // within each group (stable sort). Unchecked items keep their user-defined order.
            Collections.sort(choices, new Comparator<PollChoiceModel>() {
                @Override
                public int compare(PollChoiceModel a, PollChoiceModel b) {
                    boolean aChecked = isCheckedByAnyone.test(a.getId());
                    boolean bChecked = isCheckedByAnyone.test(b.getId());
                    if (aChecked != bChecked) {
                        return aChecked ? 1 : -1;
                    }
                    return Integer.compare(a.getOrder(), b.getOrder());
                }
            });

            final boolean canToggle = PollUtil.canVote(pollModel, helper.getMessageReceiver());
            final boolean canEdit = PollUtil.isMine(pollModel, myIdentity)
                && PollUtil.canVote(pollModel, helper.getMessageReceiver());
            final LayoutInflater inflater = LayoutInflater.from(context);

            for (int i = 0; i < choices.size(); i++) {
                final PollChoiceModel choice = choices.get(i);
                final int rowIndex = i;
                View row = inflater.inflate(R.layout.item_checklist_choice, checklistContainer, false);
                MaterialCheckBox checkBox = row.findViewById(R.id.checklist_item_checkbox);
                TextView nameView = row.findViewById(R.id.checklist_item_name);
                TextView votersView = row.findViewById(R.id.checklist_item_voters);
                View dragHandle = row.findViewById(R.id.checklist_item_drag_handle);
                View deleteButton = row.findViewById(R.id.checklist_item_delete);

                final boolean isItemChecked = myChecks.contains(choice.getId());
                final boolean isItemCheckedByAnyone = isCheckedByAnyone.test(choice.getId());

                nameView.setText(choice.getName());
                // Scarlet-Notes treatment: a checked item is struck through and dimmed; an unchecked
                // item is clear and slightly less prominent than full opacity.
                applyCheckedAppearance(row, nameView, isItemChecked);

                // Always detach the listener before setting state so recycled rows don't fire.
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(isItemChecked);
                checkBox.setEnabled(canToggle);

                Set<String> voters = votersByChoice.get(choice.getId());
                // Show the persisted voters; additionally fold in an optimistic "me" if my overlay
                // checked this item but the DB has not committed it yet.
                Set<String> displayVoters = voters == null ? new LinkedHashSet<>() : new LinkedHashSet<>(voters);
                if (isItemChecked && myIdentity != null) {
                    displayVoters.add(myIdentity);
                } else if (!isItemChecked && myIdentity != null) {
                    displayVoters.remove(myIdentity);
                }
                if (!displayVoters.isEmpty()) {
                    votersView.setVisibility(View.VISIBLE);
                    votersView.setText(formatVoterNames(context, displayVoters));
                } else {
                    votersView.setVisibility(View.GONE);
                }

                if (canToggle) {
                    checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                        toggleChecklistItem(context, holder, pollModel, checklistContainer, choice.getId(), isChecked));
                    // Like Scarlet-Notes, tapping the item text (not just the checkbox) toggles it.
                    // Flipping the checkbox triggers the listener above, which sends the re-vote.
                    nameView.setOnClickListener(v -> checkBox.toggle());
                }

                // Edit affordances (Scarlet-Notes style) live on ACTIVE (unchecked) items only.
                // A checked item is "done": it is struck through, dimmed, and auto-sunk to the
                // bottom, so it carries NO reorder handle and NO delete button -- both edit
                // affordances appear together, only on unchecked rows, so they read as one coherent
                // edit surface rather than a half-feature. To remove a completed item the creator
                // un-checks it first (it rises back into the editable block). Deleting an unchecked
                // item is safe end-to-end: modifyChecklistChoices() drops any dangling votes locally
                // and recipients' mergeChecklistUpdate() drops votes for choices no longer in the
                // re-broadcast set, so no orphan votes linger. The confirm dialog warns first.
                final boolean showEditAffordances = canEdit && !isItemCheckedByAnyone;

                if (deleteButton != null) {
                    if (showEditAffordances) {
                        deleteButton.setVisibility(View.VISIBLE);
                        deleteButton.setOnClickListener(v ->
                            confirmRemoveChecklistChoice(context, holder, pollModel, checklistContainer, choice));
                    } else {
                        deleteButton.setVisibility(View.GONE);
                        deleteButton.setOnClickListener(null);
                    }
                }

                // Reorder handle: only the creator, and only on UNCHECKED items (checked items are
                // sunk and not manually orderable). The inline checklist renders inside a ListView row,
                // where an ItemTouchHelper drag cannot function, so tapping the handle opens a dedicated
                // "Edit checklist" dialog (RecyclerView + ItemTouchHelper) where items can be
                // drag-reordered, renamed, deleted and added. Apply persists the new order/set and
                // re-broadcasts it over the existing Poll wire (receivers merge via mergeChecklistUpdate).
                if (dragHandle != null) {
                    if (showEditAffordances) {
                        dragHandle.setVisibility(View.VISIBLE);
                        dragHandle.setOnClickListener(v ->
                            showEditChecklistDialog(context, holder, pollModel, checklistContainer));
                    } else {
                        dragHandle.setVisibility(View.GONE);
                        dragHandle.setOnClickListener(null);
                    }
                }

                // The row was inflated detached (attachToRoot=false); add it to the bubble container.
                checklistContainer.addView(row);
            }

            // "+ Add item" footer row -- only the creator may add items.
            if (canEdit) {
                View addRow = inflater.inflate(R.layout.item_checklist_add, checklistContainer, false);
                addRow.setOnClickListener(v ->
                    showAddItemDialog(context, holder, pollModel, checklistContainer));
                checklistContainer.addView(addRow);
            }
        } catch (Exception e) {
            logger.error("Could not render checklist", e);
            hideChecklist(checklistContainer);
        }
    }

    /**
     * Apply the Scarlet-Notes list-note appearance to a checklist row: a checked item is struck
     * through and dimmed to 0.5 alpha, an unchecked item is clear and rendered at 0.8 alpha. Driven
     * by the local user's own check state (the checkbox), so the strike/dim follows the box.
     */
    private static void applyCheckedAppearance(@NonNull View row, @NonNull TextView nameView, boolean checked) {
        if (checked) {
            nameView.setPaintFlags(nameView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            row.setAlpha(0.5f);
        } else {
            nameView.setPaintFlags(nameView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            row.setAlpha(0.8f);
        }
    }

    private String formatVoterNames(Context context, @NonNull Set<String> identities) {
        StringBuilder names = new StringBuilder();
        String myIdentity = this.helper.getMyIdentity();
        for (String identity : identities) {
            String name;
            if (myIdentity != null && myIdentity.equals(identity)) {
                name = context.getString(R.string.me_myself_and_i);
            } else {
                name = NameUtil.getShortName(
                    identity,
                    this.helper.getContactService(),
                    this.helper.getPreferenceService().getContactNameFormat()
                );
            }
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        }
        return context.getString(R.string.checklist_checked_by, names.toString());
    }

    /**
     * Optimistically toggle a checklist item.
     *
     * <ol>
     *   <li><b>Instant local state:</b> seed the optimistic overlay from the DB on first touch, then
     *       flip the tapped choice in it immediately.</li>
     *   <li><b>Instant render:</b> re-draw the checklist from the overlay so the checkbox, strike,
     *       voter-name and sink-order update with zero latency.</li>
     *   <li><b>Debounce + coalesce:</b> (re)schedule a single delayed vote send for this poll. A
     *       burst of rapid taps cancels and reschedules, so the quiescent burst sends exactly one
     *       {@code PollVote}/{@code GroupPollVote} carrying the final selection.</li>
     * </ol>
     * Reconciliation (and rollback on real failure) is handled by {@link #sendDebouncedVote} and the
     * poll event handler in {@code ComposeMessageFragment}.
     */
    private void toggleChecklistItem(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer,
        int choiceId,
        boolean isChecked
    ) {
        final int pollId = pollModel.getId();

        // 1. LOCAL STATE: seed the overlay from the committed DB votes on first touch, then mutate.
        Set<Integer> overlay = optimisticChecks.get(pollId);
        if (overlay == null) {
            overlay = Collections.synchronizedSet(new LinkedHashSet<>());
            try {
                for (PollVoteModel vote : this.helper.getPollService().getMyVotes(pollId)) {
                    if (vote.getChoice() == 1) {
                        overlay.add(vote.getPollChoiceId());
                    }
                }
            } catch (Exception e) {
                logger.error("Could not seed optimistic checklist state", e);
            }
            optimisticChecks.put(pollId, overlay);
        }
        if (isChecked) {
            overlay.add(choiceId);
        } else {
            overlay.remove(choiceId);
        }

        // 2. LOCAL RENDER: instant feedback (runs on the UI thread we are already on).
        renderChecklist(context, holder, pollModel, checklistContainer);

        // 3. LEADING + TRAILING debounce of the actual vote send for this poll.
        //
        // Leading edge: if no send happened within the last VOTE_DEBOUNCE_MS (the burst is
        // quiescent), fire the FIRST toggle immediately so peers see the change without waiting the
        // window. Trailing edge: any toggle during an active burst (re)schedules a single delayed
        // send so the remaining rapid taps collapse into one trailing PollVote/GroupPollVote carrying
        // the final selection. Net effect on a fast burst is ~1-2 sends, peers updated promptly.
        final PollModel pollForSend = pollModel;
        final long now = android.os.SystemClock.uptimeMillis();
        final Long last = lastVoteSendAt.get(pollId);
        final boolean quiescent = last == null || (now - last) >= VOTE_DEBOUNCE_MS;

        ScheduledFuture<?> previous = pendingVoteTasks.get(pollId);
        if (previous != null) {
            previous.cancel(false);
        }

        if (quiescent) {
            // Leading edge: send the first change of a quiescent burst right away.
            lastVoteSendAt.put(pollId, now);
            voteSendScheduler.execute(() -> sendDebouncedVote(pollForSend));
        } else {
            // Active burst: coalesce into one trailing send after the window goes quiet.
            ScheduledFuture<?> task = voteSendScheduler.schedule(
                () -> sendDebouncedVote(pollForSend),
                VOTE_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS
            );
            pendingVoteTasks.put(pollId, task);
        }
    }

    /**
     * Send the coalesced vote for a poll once its toggle burst has gone quiet. Builds the full
     * selection map from the current optimistic overlay (the user's intended final state) and sends
     * exactly one {@code PollVote}/{@code GroupPollVote} over the existing wire. On success the
     * persisted votes will match the overlay and the next render reconciles (drops the overlay); on a
     * real failure the overlay is cleared so the UI rolls back to the committed DB truth on the next
     * bind.
     */
    private void sendDebouncedVote(@NonNull PollModel pollModel) {
        final int pollId = pollModel.getId();
        pendingVoteTasks.remove(pollId);
        // Stamp the send so the leading-edge check in toggleChecklistItem coalesces the immediately
        // following toggles into a trailing send (a leading send already stamped this on the UI
        // thread; re-stamping on the trailing send keeps the window anchored to the last real send).
        lastVoteSendAt.put(pollId, android.os.SystemClock.uptimeMillis());

        final Set<Integer> overlay = optimisticChecks.get(pollId);
        if (overlay == null) {
            return;
        }

        // Snapshot the overlay so a concurrent toggle does not mutate the selection mid-build.
        final Set<Integer> snapshot;
        synchronized (overlay) {
            snapshot = new LinkedHashSet<>(overlay);
        }

        try {
            Map<Integer, Integer> selection = new HashMap<>();
            for (PollChoiceModel choice : this.helper.getPollService().getChoices(pollId)) {
                selection.put(choice.getId(), snapshot.contains(choice.getId()) ? 1 : 0);
            }
            this.helper.getPollService().vote(pollId, selection, TriggerSource.LOCAL);
            // Success: vote() persisted the votes + fired onSelfVote, which triggers a refresh that
            // reconciles (the overlay now equals the persisted state and is dropped).
        } catch (Exception e) {
            logger.error("Could not send coalesced checklist vote, rolling back optimistic state", e);
            // Real failure: roll back the optimistic overlay to the committed DB truth. The UI will
            // reflect this on the next bind (and the ComposeMessageFragment vote listener forces a
            // refresh on any vote-state change), so we do not hold a direct adapter handle here.
            clearOptimisticState(pollId);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Edit (add / remove) and reorder. Only the creator may edit; the new choice set is persisted
    // locally and re-broadcast over the existing Poll wire as a fresh PollSetup (same pollId,
    // new choiceList). Recipients merge it (PollServiceImpl.update checklist-merge branch).
    // ------------------------------------------------------------------------------------------

    /**
     * Show the "Add item" dialog and, on confirm, append a new checklist choice.
     */
    private void showAddItemDialog(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer
    ) {
        final TextInputLayout inputLayout = new TextInputLayout(context);
        final TextInputEditText input = new TextInputEditText(context);
        input.setHint(R.string.checklist_add_item_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        inputLayout.addView(input);
        int padding = context.getResources().getDimensionPixelSize(R.dimen.listitem_standard_margin_left_right);
        inputLayout.setPadding(padding, padding / 2, padding, 0);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.checklist_add_item_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.checklist_add_item_button, (dialog, which) -> {
                CharSequence raw = input.getText();
                String itemName = raw == null ? "" : raw.toString().trim();
                if (!itemName.isEmpty()) {
                    addChecklistChoice(context, holder, pollModel, checklistContainer, itemName);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();

        input.requestFocus();
    }

    /**
     * Append a new choice to the checklist (local persist + re-broadcast). The new apiPollChoiceId
     * and order are one past the current maxima so they stay unique and land at the bottom of the
     * unchecked block. The full resulting choice set is handed to the service, which diffs it against
     * the DB (add/remove/reorder are all the same "set the new choice list" operation).
     */
    private void addChecklistChoice(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer,
        @NonNull String itemName
    ) {
        ExecutorServices.getSendMessageExecutorService().execute(() -> {
            try {
                List<PollChoiceModel> target = new ArrayList<>(this.helper.getPollService().getChoices(pollModel.getId()));
                int maxApiId = 0;
                int maxOrder = -1;
                for (PollChoiceModel c : target) {
                    maxApiId = Math.max(maxApiId, c.getApiPollChoiceId());
                    maxOrder = Math.max(maxOrder, c.getOrder());
                }

                PollChoiceModel newChoice = new PollChoiceModel()
                    .setPollId(pollModel.getId())
                    .setApiPollChoiceId(maxApiId + 1)
                    .setType(PollChoiceModel.Type.Text)
                    .setName(itemName)
                    .setOrder(maxOrder + 1);
                newChoice.setCreatedAt(Instant.now());
                newChoice.setModifiedAt(Instant.now());
                target.add(newChoice);

                this.helper.getPollService().modifyChecklistChoices(pollModel, target, TriggerSource.LOCAL);
                refreshChecklistOnUi(context, holder, pollModel, checklistContainer);
            } catch (Exception e) {
                logger.error("Could not add checklist item", e);
            }
        });
    }

    /**
     * Confirm deletion of a checklist item, then remove it (local persist + re-broadcast). Only
     * offered for items nobody has checked, so no votes are orphaned.
     */
    private void confirmRemoveChecklistChoice(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer,
        @NonNull PollChoiceModel choice
    ) {
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.checklist_delete_item_title)
            .setMessage(context.getString(R.string.checklist_delete_item_message, choice.getName()))
            .setPositiveButton(R.string.checklist_delete_button, (dialog, which) ->
                removeChecklistChoice(context, holder, pollModel, checklistContainer, choice))
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void removeChecklistChoice(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer,
        @NonNull PollChoiceModel choice
    ) {
        ExecutorServices.getSendMessageExecutorService().execute(() -> {
            try {
                List<PollChoiceModel> target = new ArrayList<>();
                for (PollChoiceModel c : this.helper.getPollService().getChoices(pollModel.getId())) {
                    if (c.getId() != choice.getId()) {
                        target.add(c);
                    }
                }
                // A checklist needs at least two items (same floor as creation), otherwise the
                // wire re-broadcast would be rejected. Refuse to drop below that and warn.
                if (target.size() < 2) {
                    logger.warn("Refusing to delete checklist item: a checklist needs at least 2 items");
                    showToast(ThreemaApplication.getAppContext(), R.string.checklist_item_count_error, ToastDuration.LONG);
                    return;
                }
                this.helper.getPollService().modifyChecklistChoices(pollModel, target, TriggerSource.LOCAL);
                refreshChecklistOnUi(context, holder, pollModel, checklistContainer);
            } catch (Exception e) {
                logger.error("Could not remove checklist item", e);
            }
        });
    }

    /**
     * Open the "Edit checklist" dialog: a {@link RecyclerView} + {@link ItemTouchHelper} hosting an
     * editable working copy of the checklist's items. The inline checklist renders inside a ListView
     * row where a drag gesture cannot work, so the creator reorders items here with a real long-press
     * drag (touch the reorder handle), renames an item by tapping it, deletes an item, and adds a new
     * one. On Apply the resulting set/order is persisted locally and re-broadcast over the existing Poll
     * wire (receivers merge it via mergeChecklistUpdate); Cancel discards the working copy.
     *
     * <p>The working copy is a deep clone of the persisted choices so a Cancel leaves the real poll
     * untouched; only Apply commits. Items keep their stable {@code apiPollChoiceId} so receivers
     * match survivors and preserve votes; new items get a fresh apiPollChoiceId on Apply.
     */
    private void showEditChecklistDialog(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer
    ) {
        final List<PollChoiceModel> working = new ArrayList<>();
        try {
            // Deep-clone the persisted choices (ordered by their manual order) into a working copy so
            // edits/reorders/deletes only take effect on Apply. Clone preserves the stable
            // apiPollChoiceId (for receiver vote-survival matching) and the primary-key id (so an
            // existing item is updated in place, not re-inserted) by modifyChecklistChoices().
            final List<PollChoiceModel> persisted =
                new ArrayList<>(this.helper.getPollService().getChoices(pollModel.getId()));
            Collections.sort(persisted, Comparator.comparingInt(PollChoiceModel::getOrder));
            for (PollChoiceModel c : persisted) {
                PollChoiceModel clone = new PollChoiceModel()
                    .setPollId(pollModel.getId())
                    .setApiPollChoiceId(c.getApiPollChoiceId())
                    .setType(PollChoiceModel.Type.Text)
                    .setName(c.getName())
                    .setOrder(c.getOrder());
                // Preserve the existing primary-key id so modifyChecklistChoices() updates this row in
                // place (and thus keeps its votes) instead of treating it as a brand-new item.
                clone.setId(c.getId());
                clone.setCreatedAt(c.getCreatedAt());
                clone.setModifiedAt(c.getModifiedAt());
                working.add(clone);
            }
        } catch (Exception e) {
            logger.error("Could not load checklist for editing", e);
            return;
        }

        final View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_checklist_edit, null, false);
        final RecyclerView recyclerView = dialogView.findViewById(R.id.checklist_edit_recycler);
        final View addRow = dialogView.findViewById(R.id.checklist_edit_add_row);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        // The ItemTouchHelper and the adapter are referenced from the adapter's own callbacks, so they
        // are held in one-element arrays to allow assignment after the adapter is constructed (the
        // callbacks only RUN later, on user interaction, by which point both are assigned).
        final ItemTouchHelper[] touchHelperRef = new ItemTouchHelper[1];
        final ChecklistEditAdapter[] adapterRef = new ChecklistEditAdapter[1];

        final ChecklistEditAdapter adapter = new ChecklistEditAdapter(working, new ChecklistEditAdapter.Callbacks() {
            @Override
            public void onRenameRequested(int position, @NonNull PollChoiceModel choice) {
                showRenameItemDialog(context, adapterRef[0], position, choice);
            }

            @Override
            public void onStartDrag(@NonNull RecyclerView.ViewHolder viewHolder) {
                if (touchHelperRef[0] != null) {
                    touchHelperRef[0].startDrag(viewHolder);
                }
            }
        });
        adapterRef[0] = adapter;
        recyclerView.setAdapter(adapter);

        // Drag-only ItemTouchHelper (UP|DOWN, no swipe) -- reorders the working list as the user drags.
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0
        ) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                adapter.moveItem(vh.getBindingAdapterPosition(), target.getBindingAdapterPosition());
                return true;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // Drag is started explicitly from the handle's touch listener (more discoverable than a
                // whole-row long press), so disable the implicit long-press-anywhere drag.
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // No swipe-to-dismiss; deletion is via the per-row delete button.
            }
        };
        final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(callback);
        touchHelperRef[0] = itemTouchHelper;
        itemTouchHelper.attachToRecyclerView(recyclerView);

        addRow.setOnClickListener(v -> showAddItemForEditDialog(context, adapter, pollModel));

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.checklist_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.checklist_edit_apply, (dialog, which) ->
                applyChecklistEdits(context, holder, pollModel, checklistContainer, adapter.getChoices()))
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /**
     * Prompt to rename a single working-copy item inside the Edit dialog (no persist/broadcast yet --
     * the change lands in the working list and is only committed on Apply).
     */
    private void showRenameItemDialog(
        Context context,
        @NonNull ChecklistEditAdapter adapter,
        int position,
        @NonNull PollChoiceModel choice
    ) {
        final TextInputLayout inputLayout = new TextInputLayout(context);
        final TextInputEditText input = new TextInputEditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(choice.getName());
        if (choice.getName() != null) {
            input.setSelection(choice.getName().length());
        }
        inputLayout.addView(input);
        int padding = context.getResources().getDimensionPixelSize(R.dimen.listitem_standard_margin_left_right);
        inputLayout.setPadding(padding, padding / 2, padding, 0);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.checklist_rename_item_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                CharSequence raw = input.getText();
                String newName = raw == null ? "" : raw.toString().trim();
                if (!newName.isEmpty()) {
                    adapter.renameAt(position, newName);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
        input.requestFocus();
    }

    /**
     * Prompt for a new item name and append it to the Edit dialog's working list (not yet persisted).
     * The new item gets a fresh, in-working-set-unique apiPollChoiceId so it lands as a brand-new
     * choice for receivers on Apply.
     */
    private void showAddItemForEditDialog(
        Context context,
        @NonNull ChecklistEditAdapter adapter,
        @NonNull PollModel pollModel
    ) {
        final TextInputLayout inputLayout = new TextInputLayout(context);
        final TextInputEditText input = new TextInputEditText(context);
        input.setHint(R.string.checklist_add_item_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        inputLayout.addView(input);
        int padding = context.getResources().getDimensionPixelSize(R.dimen.listitem_standard_margin_left_right);
        inputLayout.setPadding(padding, padding / 2, padding, 0);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.checklist_add_item_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.checklist_add_item_button, (dialog, which) -> {
                CharSequence raw = input.getText();
                String itemName = raw == null ? "" : raw.toString().trim();
                if (!itemName.isEmpty()) {
                    int maxApiId = 0;
                    for (PollChoiceModel c : adapter.getChoices()) {
                        maxApiId = Math.max(maxApiId, c.getApiPollChoiceId());
                    }
                    PollChoiceModel newChoice = new PollChoiceModel()
                        .setPollId(pollModel.getId())
                        .setApiPollChoiceId(maxApiId + 1)
                        .setType(PollChoiceModel.Type.Text)
                        .setName(itemName)
                        .setOrder(adapter.getItemCount());
                    newChoice.setCreatedAt(Instant.now());
                    newChoice.setModifiedAt(Instant.now());
                    adapter.addItem(newChoice);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
        input.requestFocus();
    }

    /**
     * Commit the Edit dialog's working list back to the poll: reassign contiguous order values to the
     * final on-screen order, enforce the two-item floor (else the wire re-broadcast would be rejected),
     * then persist + re-broadcast via modifyChecklistChoices(). Surviving items keep their primary-key
     * id (votes preserved); removed items drop their votes; new items are inserted. Checked items still
     * sink to the bottom in the rendered bubble, but their relative manual order now follows this list.
     */
    private void applyChecklistEdits(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer,
        @NonNull List<PollChoiceModel> editedChoices
    ) {
        if (editedChoices.size() < 2) {
            showToast(context, R.string.checklist_item_count_error, ToastDuration.LONG);
            return;
        }
        // Snapshot the final order on the UI thread (the list is the adapter's live backing list).
        final List<PollChoiceModel> target = new ArrayList<>(editedChoices.size());
        for (int i = 0; i < editedChoices.size(); i++) {
            PollChoiceModel c = editedChoices.get(i);
            c.setOrder(i);
            target.add(c);
        }
        ExecutorServices.getSendMessageExecutorService().execute(() -> {
            try {
                this.helper.getPollService().modifyChecklistChoices(pollModel, target, TriggerSource.LOCAL);
                refreshChecklistOnUi(context, holder, pollModel, checklistContainer);
            } catch (Exception e) {
                logger.error("Could not apply checklist edits", e);
            }
        });
    }

    private void refreshChecklistOnUi(
        Context context,
        ComposeMessageHolder holder,
        @NonNull PollModel pollModel,
        LinearLayout checklistContainer
    ) {
        final int expectedPollId = pollModel.getId();
        RuntimeUtil.runOnUiThread(() -> {
            try {
                // The holder (a recycled ListView row) may have been re-bound to a different message
                // between scheduling and now. Re-resolve the live checklist container from the
                // holder and only re-render if it still binds THIS poll; otherwise the row moved
                // on and touching its views would mutate the wrong bubble.
                final LinearLayout liveContainer = holder.messageBlockView != null
                    ? holder.messageBlockView.findViewById(R.id.checklist_container)
                    : null;
                if (liveContainer == null) {
                    return;
                }
                final Object boundPollId = liveContainer.getTag(R.id.checklist_container);
                if (!(boundPollId instanceof Integer) || (Integer) boundPollId != expectedPollId) {
                    // Row recycled to another poll (or a non-checklist message): drop this refresh.
                    return;
                }
                renderChecklist(context, holder, pollModel, liveContainer);
            } catch (Exception e) {
                logger.error("Could not refresh checklist", e);
            }
        });
    }

    private void showChooser(Context context, final PollModel pollModel) {
        ArrayList<SelectorDialogItem> items = new ArrayList<>();
        final ArrayList<Integer> action = new ArrayList<>();
        String title = null;

        if (PollUtil.canVote(pollModel, helper.getMessageReceiver())) {
            items.add(new SelectorDialogItem(context.getString(R.string.ballot_vote), R.drawable.ic_vote_outline));
            action.add(ACTION_VOTE);
        }

        var canView = PollUtil.canViewMatrix(pollModel);
        if (canView) {
            if (pollModel.getState() == PollModel.State.CLOSED) {
                items.add(new SelectorDialogItem(context.getString(R.string.ballot_result_final), R.drawable.ic_poll_outline));
            } else {
                items.add(new SelectorDialogItem(context.getString(R.string.ballot_result_intermediate), R.drawable.ic_poll_outline));
            }
            action.add(ACTION_RESULTS);
        }

        var canClose = PollUtil.canClose(pollModel, helper.getMyIdentity(), helper.getMessageReceiver());
        if (canClose) {
            items.add(new SelectorDialogItem(context.getString(R.string.ballot_close), R.drawable.ic_check));
            action.add(ACTION_CLOSE);
        }

        if (canClose || canView) {
            title = String.format(context.getString(R.string.ballot_received_votes),
                helper.getPollService().getVotedParticipants(pollModel.getId()).size(),
                helper.getPollService().getParticipants(pollModel.getId()).length);
        }

        if (items.size() > 1) {
            listener.showSelectorDialog(action, title, items, pollModel);
        } else if (!items.isEmpty()) {
            boolean canVote = PollUtil.canVote(pollModel, helper.getMessageReceiver());
            listener.openDefaultActivity(pollModel, canVote);
        }
    }
}
