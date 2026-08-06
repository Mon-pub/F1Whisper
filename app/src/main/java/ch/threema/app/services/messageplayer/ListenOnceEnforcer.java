package ch.threema.app.services.messageplayer;

import org.slf4j.Logger;

import java.util.List;

import androidx.annotation.NonNull;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.services.FileService;
import ch.threema.app.services.MessageService;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.media.FileDataModel;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

/**
 * F1Whisper: the two durable transitions of a "listen once" voice message, in one place because two
 * callers need them and a second copy would drift.
 *
 * <ul>
 *   <li>{@link #claim} - written by {@link AudioMessagePlayer} <em>before</em> the decrypted audio is
 *   handed to the media player. From that moment the message can never be played again.</li>
 *   <li>{@link #burn} - deletes the media and marks the message consumed. Normally runs when
 *   playback ends; also run by the bubble to finish a burn whose playback was interrupted.</li>
 * </ul>
 *
 * <p>Both are best-effort, client-side enforcement. A modified client, a rooted device or a screen
 * recorder still captures the audio; this is not a cryptographic guarantee.</p>
 *
 * <p>Both persist on a worker thread, since both write a row. {@link #claim} reports back through a
 * callback so the caller can order the media release strictly after the write - that ordering is the
 * whole point of having a claim.</p>
 */
public final class ListenOnceEnforcer {
    private static final Logger logger = getThreemaLogger("ListenOnceEnforcer");

    private ListenOnceEnforcer() {
    }

    /**
     * @return the message's listen-once gate, or {@link ListenOnceGate#NOT_APPLICABLE} for anything
     * that is not an incoming listen-once voice message. Safe to call on any message model.
     */
    @NonNull
    public static ListenOnceGate gateOf(@NonNull AbstractMessageModel messageModel) {
        final boolean isFileMessage = messageModel.getType() == MessageType.FILE;
        final FileDataModel fileData = isFileMessage ? messageModel.getFileData() : null;
        if (fileData == null) {
            return ListenOnceGate.NOT_APPLICABLE;
        }
        return ListenOnceDecision.evaluate(
            messageModel.isOutbox(),
            true,
            fileData.isListenOnce(),
            fileData.isListenOnceClaimed(),
            fileData.isListenOnceConsumed()
        );
    }

    /**
     * Persistently claim the message, then invoke {@code onClaimed} on the UI thread.
     *
     * <p>The callback exists so the caller can release the plaintext strictly after the claim has
     * been written. It is invoked even when the write throws: refusing to play the message would
     * turn a database hiccup into a silently unplayable voice message, which is a worse failure than
     * the replay window it would close. The window is a single failed write, and the next successful
     * playback claims again.</p>
     */
    public static void claim(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageService messageService,
        @NonNull Runnable onClaimed
    ) {
        RuntimeUtil.runOnWorkerThread(() -> {
            try {
                // F1Whisper (fifth fork review, F5-04): the claim writes the body column of the CURRENT row, conditionally.
                // It used to mutate the caller's instance and full-row-save it, which could recreate a message deleted
                // while the player was starting up, and would revert any other flag a concurrent transition had written
                // into the same serialised metadata.
                final boolean claimed = messageService.updateMediaMetadata(messageModel, current -> {
                    final FileDataModel fileData = current.getFileData();
                    if (fileData == null || fileData.isListenOnceClaimed()) {
                        return false;
                    }
                    fileData.setListenOnceClaimed();
                    current.setFileData(fileData);
                    return true;
                });
                if (claimed) {
                    logger.info("Claimed listen-once message {} before playback", messageModel.getId());
                }
            } catch (Exception e) {
                logger.error("Failed to claim listen-once message before playback", e);
            } finally {
                // The owner-approved tradeoff, unchanged: playback proceeds even when the claim could not be written.
                // Refusing to play would turn a database hiccup into a permanently unplayable voice message, and the
                // window it leaves is a single failed write - the next successful playback claims again.
                RuntimeUtil.runOnUiThread(onClaimed);
            }
        });
    }

    /**
     * Mark the message consumed and delete its stored media so it can never be decrypted again.
     *
     * @param playBurnAnimation whether the bubble should play the one-shot burn burst. True when the
     *   user just watched playback finish; false when this is repairing an interrupted burn from a
     *   previous process, where an animation would appear out of nowhere on a message the user did
     *   not just play.
     */
    public static void burn(
        @NonNull AbstractMessageModel messageModel,
        @NonNull MessageService messageService,
        @NonNull FileService fileService,
        boolean playBurnAnimation
    ) {
        logger.info("Enforcing listen-once deletion for {}", messageModel.getId());

        RuntimeUtil.runOnWorkerThread(() -> {
            try {
                // F1Whisper (fifth fork review, F5-04): the burned state and the move to CONSUMED are ONE conditional,
                // non-inserting write against the current row, and they come BEFORE the files are deleted.
                //
                // They used to be three separate full-row saves (markAsConsumed, then the metadata) around a file
                // deletion, from a detached model. A message hard-deleted while a burn was running therefore came back -
                // as a row whose media had just been erased - and a burn that lost the race to any other transition
                // reverted that transition's flags along with its own.
                //
                // The metadata is what makes the burn durable, not MessageState: it survives reopen and cannot be
                // clobbered by a later reaction or receipt moving the state away from CONSUMED. A burn always implies a
                // claim, so both flags are set, which keeps them from disagreeing on a message burned by a path that
                // never claimed.
                messageService.consumeAndUpdateMediaMetadata(messageModel, current -> {
                    final FileDataModel fileData = current.getFileData();
                    if (fileData == null) {
                        return false;
                    }
                    if (fileData.isListenOnceClaimed() && fileData.isListenOnceConsumed() && !fileData.isDownloaded()) {
                        return false;
                    }
                    fileData.setListenOnceClaimed();
                    fileData.setListenOnceConsumed();
                    // Reflect that the media is gone so the bubble offers no replay
                    fileData.isDownloaded(false);
                    current.setFileData(fileData);
                    return true;
                });

                // Delete the stored encrypted media + thumbnail so it can never be decrypted again. Deliberately NOT
                // conditional on the write above having changed anything: the shape an interrupted burn leaves behind is
                // now "flags written, files still on disk", and this call IS the repair for it. A burn must never end
                // with decryptable media still present because a previous attempt got as far as the metadata.
                fileService.removeMessageFiles(messageModel, true);

                if (playBurnAnimation) {
                    // Signal that this message JUST burned so the bubble plays the one-shot burn
                    // animation exactly once on the re-render below (consumed by the decorator; not
                    // replayed on chat reopen). Recipient path only - the sender never plays it back.
                    ListenOnceBurnRegistry.markForBurnAnimation(messageModel.getId());
                }

                // Refresh any visible bubble for this message
                ListenerManager.messageListeners.handle(listener -> listener.onModified(List.of(messageModel)));
            } catch (Exception e) {
                logger.error("Failed to enforce listen-once deletion", e);
            }
        });
    }
}
