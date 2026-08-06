package ch.threema.app.services;

import android.content.Intent;

import androidx.annotation.NonNull;
import ch.threema.base.SessionScoped;

/**
 * The Lifetime Service is responsible for opening and closing a connection to the Threema
 * chat server. Every part of the code that needs a connection can acquire a "connection slot".
 * If there are 1 or more slots open, then a connection is kept open, otherwise it is closed.
 * <p>
 * In some situations we want to stop the connection even if the counter is >0,
 * for example when the device is going to deep sleep. For this, the {@link #pause()}
 * and {@link #unpause()} methods can be used.
 * <p>
 * If you want to acquire a connection that should not be pauseable (e.g. for the Threema Push
 * service), then set the `unpauseable` flag (or use {@link #acquireUnpauseableConnection(String)})
 * when acquiring the connection.
 */
@SessionScoped
public interface LifetimeService {
    interface LifetimeServiceListener {
        /**
         * The connection was stopped.
         * Return `true` to deregister this listener, `false` to keep it registered.
         */
        boolean connectionStopped();
    }

    /**
     * Acquire a connection. If not already connected, the connection will be started immediately,
     * and kept active until releaseConnection() is called. A call to acquireConnection() must always
     * be balanced with a call to releaseConnection() with the same tag.
     *
     * @param sourceTag   identifier of the connection slot
     * @param unpauseable if set to true, then the connection will not be paused in deep sleep
     */
    void acquireConnection(@NonNull String sourceTag, boolean unpauseable);

    /**
     * Shortcut for {@link #acquireConnection(String, boolean)} with unpauseable=false.
     */
    default void acquireConnection(@NonNull String source) {
        acquireConnection(source, false);
    }

    /**
     * Shortcut for {@link #acquireConnection(String, boolean)} with unpauseable=true.
     */
    default void acquireUnpauseableConnection(@NonNull String source) {
        acquireConnection(source, true);
    }

    /**
     * Release the connection. If no other callers have acquired the connection anymore, it will
     * be closed.
     *
     * @param sourceTag identifier of the connection slot
     */
    void releaseConnection(@NonNull String sourceTag);

    /**
     * Release the connection, but keep it active ("linger") for the given amount of milliseconds
     *
     * @param sourceTag identifier of the connection slot
     * @param timeoutMs time in milliseconds that the connection should linger
     */
    void releaseConnectionLinger(@NonNull String sourceTag, long timeoutMs);

    /**
     * If the connection is not active but there are connection slots registered,
     * establish a connection. If the connection is already active, or if no connection
     * slots are registered, nothing will happen.
     * <p>
     * Note: Sometimes when acquiring a connection, the connection cannot be established
     * (e.g. if the identity is not yet set up). In this case, this method can be called
     * to trigger a reconnect attempt.
     */
    void ensureConnection();

    /**
     * Alarm elapsed (for use by AlarmManagerBroadcastReceiver)
     */
    void alarm(Intent intent);

    /**
     * Return whether a connection is active (connected).
     */
    boolean isActive();

    /**
     * F1Whisper: how many connection slots are currently held. Reporting only, for the connection
     * diagnostics.
     * <p>
     * This is the quantity that makes {@link #isActive()} meaningful: a held slot means a connection
     * is <em>wanted</em>. A report showing slots held, {@code isActive() == true} and a connection
     * that is nonetheless DISCONNECTED with no restart in flight is the signature of the wedge this
     * accessor exists to make visible.
     * <p>
     * Deliberately a {@code default} rather than abstract. {@code MessageProcessorProvider} declares
     * an anonymous {@code LifetimeService} for instrumented tests, and {@code androidTest} is compiled
     * by neither the unit-test gate nor {@code assembleOnpremDebug}, so making this abstract would
     * break that source set <em>silently</em>. The only production implementor overrides it; a
     * non-overriding one reports 0, which understates rather than fabricates.
     */
    default int getConnectionSlotCount() {
        return 0;
    }

    /**
     * Pause (close) the connection, even if the slotCount is >0.
     */
    void pause();

    /**
     * Restore (reconnect) the connection if it was previously paused.
     */
    void unpause();

    void addListener(LifetimeServiceListener listener);
}
