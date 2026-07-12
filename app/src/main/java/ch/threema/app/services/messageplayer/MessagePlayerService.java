package ch.threema.app.services.messageplayer;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.session.MediaController;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;

import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.storage.models.AbstractMessageModel;

public interface MessagePlayerService {
    MessagePlayer createPlayer(AbstractMessageModel m, @Nullable WeakReference<Activity> activityWeakReference, MessageReceiver<?> messageReceiver, @Nullable ListenableFuture<MediaController> mediaControllerFuture);

    void release();

    /**
     * F1Whisper: release every player EXCEPT the one for {@code keepMessageId} (a voice message kept
     * playing in the background). The kept player stays in the map with its decrypted file, but its
     * chat-view (decorator) listeners and controller binding are detached so it neither leaks the
     * destroyed chat's view tree nor pokes the released controller. All other players are fully
     * released (stopped + files deleted) as in {@link #release()}.
     */
    void releaseExcept(int keepMessageId);

    /**
     * F1Whisper: release a single kept background player and delete its decrypted file. Called once
     * the background session for {@code messageId} truly ends out-of-chat (natural end or banner
     * close) so the plaintext audio does not linger in cache.
     */
    void releasePlayer(int messageId);

    void stopAll();

    void pauseAll(int source);

    void resumeAll(Activity activity, MessageReceiver<?> messageReceiver, int source);

    void setTranscodeProgress(@NonNull AbstractMessageModel messageModel, int progress);

    void setTranscodeStart(@NonNull AbstractMessageModel messageModel);

    void setTranscodeFinished(@NonNull AbstractMessageModel messageModel, boolean success, @Nullable String message);
}
