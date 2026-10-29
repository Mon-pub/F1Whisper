package ch.threema.storage.models.data;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.SOURCE)
@IntDef(
    flag = true,
    value = {
        DisplayTag.DISPLAY_TAG_NONE,
        DisplayTag.DISPLAY_TAG_STARRED,
        DisplayTag.DISPLAY_TAG_PINNED,
        DisplayTag.DISPLAY_TAG_SEND_FAILED_TERMINAL
    }
)
public @interface DisplayTag {
    /* Regular messages */
    int DISPLAY_TAG_NONE = 0;
    /* Messages that have been marked with a star by user. Such messages can be displayed separately */
    int DISPLAY_TAG_STARRED = 1;
    /* F1Whisper: messages that have been pinned locally; shown in the banner above the message list */
    int DISPLAY_TAG_PINNED = 2;
    /*
     * F1Whisper auto-resend: set on an outgoing message whose send failed for a TERMINAL reason
     * (unknown/invalid recipient, FS BadDHState, ballot NotAllowed, MessageTooLong, user cancel).
     * The auto-resend scan uses this bit to skip such messages: a SENDFAILED message WITHOUT this
     * bit is treated as a connectivity failure and is eligible for silent auto-resend, whereas a
     * SENDFAILED message WITH this bit stays manual-only and still nags. Reuses the existing
     * displayTags INTEGER column so no DB migration is needed. It is orthogonal to the STARRED (1)
     * and PINNED (2) bits and is cleared whenever the message re-enters a send flow.
     */
    int DISPLAY_TAG_SEND_FAILED_TERMINAL = 4;
    /* More tags could be added here - note that these must be flags */
}


