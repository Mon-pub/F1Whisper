package ch.threema.app.listeners;

import java.util.Set;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

/**
 * F1Whisper: notified when the set of currently-typing members of a group changes.
 *
 * @param groupDatabaseId  the local database id of the group
 * @param typingIdentities the identities of the members currently typing (may be empty)
 */
public interface GroupTypingListener {
    @AnyThread
    void onGroupTypingChanged(long groupDatabaseId, @NonNull Set<String> typingIdentities);
}
