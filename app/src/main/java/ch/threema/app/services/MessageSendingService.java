package ch.threema.app.services;

import androidx.annotation.Nullable;

import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.storage.models.AbstractMessageModel;

/**
 * Handling methods for messages
 */
public interface MessageSendingService {
    interface MessageSendingServiceState {
        /**
         * Called when the exponential-backoff pipeline has exhausted its attempts for a message.
         *
         * @param cause the exception from the final failed attempt (may be {@code null} if the
         *              backoff finished without a captured exception). F1Whisper uses it to
         *              distinguish a transient connectivity failure (leave the message pending for
         *              the reconnect auto-resend scan) from a terminal one (mark SENDFAILED now).
         */
        void processingFailed(AbstractMessageModel messageModel, MessageReceiver<AbstractMessageModel> receiver, @Nullable Exception cause);

        void exception(Exception x, int tries);
    }

    interface MessageSendingProcess {
        MessageReceiver<AbstractMessageModel> getReceiver();

        AbstractMessageModel getMessageModel();

        boolean send() throws Exception;
    }

    void addToQueue(MessageSendingProcess process);

    void abort(String messageUid);
}
