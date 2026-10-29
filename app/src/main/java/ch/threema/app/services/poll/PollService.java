package ch.threema.app.services.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import ch.threema.app.exceptions.NotAllowedException;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.base.SessionScoped;
import ch.threema.base.ThreemaException;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.MessageTooLongException;
import ch.threema.domain.protocol.csp.messages.BadMessageException;
import ch.threema.domain.protocol.csp.messages.poll.PollId;
import ch.threema.domain.protocol.csp.messages.poll.PollSetupInterface;
import ch.threema.domain.protocol.csp.messages.poll.PollVoteInterface;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.group.GroupModelOld;
import ch.threema.storage.models.poll.PollChoiceModel;
import ch.threema.storage.models.poll.PollModel;
import ch.threema.storage.models.poll.PollVoteModel;
import ch.threema.storage.models.poll.LinkPollModel;

@SessionScoped
public interface PollService {

    interface PollFilter {
        MessageReceiver<?> getReceiver();

        PollModel.State[] getStates();

        default String createdOrNotVotedByIdentity() {
            return null;
        }
    }

    PollModel create(
        ContactModel contactModel,
        String description,
        PollModel.State state,
        PollModel.Assessment assessment,
        PollModel.Type type,
        PollModel.ChoiceType choiceType,
        @NonNull PollId pollId
    ) throws NotAllowedException;

    PollModel create(
        GroupModelOld groupModel,
        String description,
        PollModel.State state,
        PollModel.Assessment assessment,
        PollModel.Type type,
        PollModel.ChoiceType choiceType,
        @NonNull PollId pollId
    ) throws NotAllowedException;

    void modifyFinished(
        @NonNull PollModel pollModel,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws MessageTooLongException;

    /**
     * F1Whisper CHECKLIST: replace the choice set of an existing, already-published checklist with a
     * new one (covers add / remove / reorder uniformly) and re-broadcast it over the existing Poll
     * wire as a fresh PollSetup (same poll id, new {@code choiceList}). Only the creator may do
     * this, and only for a CHECKLIST -- a real poll's choice set is immutable after publish.
     *
     * <p>The supplied list is the desired final state: existing items are matched by their stored
     * primary-key id and updated in place (name + order), items not present are deleted (their votes
     * are removed too), and items with id {@code <= 0} are inserted. Votes for surviving items are
     * preserved.
     *
     * @param pollModel     the existing checklist poll (must be mine + a CHECKLIST + OPEN)
     * @param targetChoices the desired final choice set (order field already assigned per position)
     * @param triggerSource the trigger source (LOCAL for a user edit)
     */
    void modifyChecklistChoices(
        @NonNull PollModel pollModel,
        @NonNull List<PollChoiceModel> targetChoices,
        @NonNull TriggerSource triggerSource
    ) throws NotAllowedException, MessageTooLongException;

    boolean viewingPoll(PollModel pollModel, boolean view);

    boolean update(PollModel pollModel, PollChoiceModel choice) throws NotAllowedException;

    boolean close(
        Integer pollModelId,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws NotAllowedException, MessageTooLongException;

    @Nullable
    PollModel get(int pollId);

    PollModel get(@NonNull String id, @Nullable String creator);

    List<PollModel> getPolls(PollFilter filter) throws NotAllowedException;

    long countPolls(PollFilter filter);

    boolean belongsToMe(Integer pollModelId, MessageReceiver<?> messageReceiver);

    /**
     * Create / Update poll from createMessage
     *
     * @param createMessage PollCreateMessage received from server
     * @throws ThreemaException if an error occurred during processing
     */
    @NonNull
    PollUpdateResult update(
        @NonNull PollSetupInterface createMessage,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws ThreemaException, BadMessageException;

    boolean update(PollModel pollModel);

    PollPublishResult publish(
        MessageReceiver<?> messageReceiver,
        PollModel pollModel,
        AbstractMessageModel abstractMessageModel,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws NotAllowedException, MessageTooLongException;

    PollPublishResult publish(
        MessageReceiver messageReceiver,
        PollModel pollModel,
        AbstractMessageModel abstractMessageModel,
        @Nullable Collection<String> receivingIdentities,
        @NonNull MessageId messageId,
        @NonNull TriggerSource triggerSource
    ) throws NotAllowedException, MessageTooLongException;

    LinkPollModel getLinkedPollModel(PollModel pollModel) throws NotAllowedException;

    boolean remove(PollModel pollModel) throws NotAllowedException;

    boolean remove(MessageReceiver<?> receiver);

    /*
    choice stuff
     */
    List<PollChoiceModel> getChoices(Integer pollModelId) throws NotAllowedException;

    /*
    voting stuff
    */

    PollVoteResult vote(Integer pollModelId, Map<Integer, Integer> values, @NonNull TriggerSource triggerSource) throws NotAllowedException;

    PollVoteResult vote(PollVoteInterface pollVoteMessage) throws NotAllowedException;

    /**
     * return the count of votings depending on the poll properties
     */
    int getVotingCount(PollChoiceModel choiceModel);

    boolean removeVotes(MessageReceiver<?> receiver, String identity);

    @NonNull
    List<String> getVotedParticipants(Integer pollModelId);

    @NonNull
    List<String> getPendingParticipants(Integer pollModelId);

    @NonNull
    String[] getParticipants(Integer pollModelId);

    boolean hasVoted(Integer pollModelId, String fromIdentity);

    /**
     * get my votes
     */
    @NonNull
    List<PollVoteModel> getMyVotes(Integer pollModelId) throws NotAllowedException;

    /**
     * get all votes of a poll
     */
    List<PollVoteModel> getPollVotes(Integer pollModelId) throws NotAllowedException;

    MessageReceiver<?> getReceiver(PollModel pollModel);

    PollMatrixData getMatrixData(int pollModelId);

    boolean removeAll();
}
