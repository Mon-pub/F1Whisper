package ch.threema.app.managers;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.listeners.ContactSettingsListener;
import ch.threema.app.listeners.GroupTypingListener;
import ch.threema.app.listeners.NewSyncedContactsListener;
import ch.threema.app.listeners.SMSVerificationListener;
import ch.threema.app.listeners.SynchronizeContactsListener;
import ch.threema.app.listeners.ThreemaSafeListener;
import ch.threema.app.listeners.VoipCallListener;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

// TODO(ANDR-4687): These listeners should be replaced with event buses,
//  or possibly injectable singleton StateFlows if they represent a state
@Deprecated
public class ListenerManager {
    private static final Logger logger = getThreemaLogger("ListenerManager");

    @Deprecated
    public interface HandleListener<T> {
        void handle(@NonNull T listener);
    }

    @Deprecated
    public static class TypedListenerManager<T> {
        private final List<T> listeners = new ArrayList<>();

        public void add(@Nullable T listener) {
            if (listener != null) {
                synchronized (listeners) {
                    if (!listeners.contains(listener)) {
                        listeners.add(listener);
                    }
                }
            }
        }

        public void remove(@Nullable T listener) {
            if (listener != null) {
                synchronized (listeners) {
                    listeners.remove(listener);
                }
            }
        }

        public void handle(ListenerManager.HandleListener<T> handleListener) {
            if (handleListener != null) {
                // Since a handler might modify the array of listeners, there's the danger
                // of a ConcurrentModificationException or a deadlock.
                // Therefore, we iterate over a copy of the listeners, to avoid that problem.
                final List<T> listenersCopy;
                synchronized (this.listeners) {
                    listenersCopy = new ArrayList<>(this.listeners);
                }

                for (final @Nullable T listener : listenersCopy) {
                    if (listener != null) {
                        try {
                            handleListener.handle(listener);
                        } catch (Exception e) {
                            logger.error("Failed to handle listener event", e);
                        }
                    }
                }
            }
        }
    }

    // F1Whisper: group typing indicators
    public static final TypedListenerManager<GroupTypingListener> groupTypingListeners = new TypedListenerManager<>();
    // TODO(ANDR-4436): Remove this listener.
    public static final TypedListenerManager<SynchronizeContactsListener> synchronizeContactsListeners = new TypedListenerManager<>();
    public static final TypedListenerManager<ContactSettingsListener> contactSettingsListeners = new TypedListenerManager<>();
    public static final TypedListenerManager<SMSVerificationListener> smsVerificationListeners = new TypedListenerManager<>();
    public static final TypedListenerManager<VoipCallListener> voipCallListeners = new TypedListenerManager<>();
    public static final TypedListenerManager<ThreemaSafeListener> threemaSafeListeners = new TypedListenerManager<>();
    public static final TypedListenerManager<NewSyncedContactsListener> newSyncedContactListener = new TypedListenerManager<>();
}
