package ch.threema.app.fragments.mediaviews;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSourceFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.alexvasilkov.gestures.GestureController;
import com.alexvasilkov.gestures.Settings;
import com.alexvasilkov.gestures.State;
import com.alexvasilkov.gestures.views.GestureFrameLayout;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.slf4j.Logger;

import java.io.File;
import java.lang.ref.WeakReference;

import ch.threema.app.R;
import ch.threema.app.activities.MediaViewerActivity;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.VideoUtil;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

@SuppressLint("UnsafeOptInUsageError")
public class VideoViewFragment extends MediaViewFragment implements Player.Listener {
    private static final Logger logger = getThreemaLogger("VideoViewFragment");

    private WeakReference<ImageView> previewImageViewRef;
    private WeakReference<CircularProgressIndicator> progressBarRef;
    private WeakReference<PlayerView> videoViewRef;
    private WeakReference<GestureFrameLayout> gestureFrameLayoutRef;
    private ExoPlayer videoPlayer;
    private boolean isImmediatePlay, isPreparing;

    // F1Whisper: picture-in-picture surface-scaling state.
    // While in PiP we neutralize the wrapping GestureFrameLayout's draw transform so the PlayerView
    // (match_parent + RESIZE_MODE_FIT) renders the full frame at the small window bounds. The OS does
    // not deliver the final PiP window size synchronously, so we re-apply the neutral transform on
    // EVERY layout pass (via this listener) until the window settles — defeating the race where a
    // single post() reads stale fullscreen dimensions and leaves a corner-only crop.
    private @Nullable View.OnLayoutChangeListener pipLayoutListener;
    private boolean isInPipScaling = false;
    // Saved fullscreen gesture settings, restored on PiP exit.
    private @Nullable Settings.Fit savedFitMethod;
    private boolean savedGesturesEnabled = true;
    private boolean savedBoundsEnabled = true;

    private final GestureController.OnStateChangeListener onGestureStateChangeListener = new GestureController.OnStateChangeListener() {
        @Override
        public void onStateChanged(@NonNull State state) {
            if (state.getZoom() > 1.05f || state.getZoom() < 0.95f) {
                PlayerView playerView = videoViewRef.get();
                if (playerView != null && playerView.isControllerFullyVisible()) {
                    playerView.hideController();
                }
            }
        }

        @Override
        public void onStateReset(State oldState, State newState) {
        }
    };

    public VideoViewFragment() {
        super();
        logger.debug("new instance");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        logger.debug("onCreateView");

        this.isImmediatePlay = getArguments() != null && getArguments().getBoolean(MediaViewerActivity.EXTRA_ID_IMMEDIATE_PLAY, false);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
            .build();

        try {
            this.videoPlayer = VideoUtil.getExoPlayer(requireContext());
            this.videoPlayer.setAudioAttributes(audioAttributes, true);
            this.videoPlayer.addListener(this);
        } catch (OutOfMemoryError e) {
            logger.error("Exception", e);
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    protected int getFragmentResourceId() {
        return R.layout.fragment_media_viewer_video;
    }

    @Override
    protected void showThumbnail(@NonNull Drawable thumbnail) {
        logger.debug("showThumbnail");

        if (previewImageViewRef != null && previewImageViewRef.get() != null) {
            this.previewImageViewRef.get().setImageDrawable(thumbnail);
        }
    }

    @Override
    protected void handleDecryptingFile() {
        logger.debug("handleDecryptingFile");

        if (progressBarRef.get() != null) {
            this.progressBarRef.get().setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void handleDecryptFailure() {
        if (progressBarRef.get() != null) {
            this.progressBarRef.get().setVisibility(View.GONE);
        }
    }

    @Override
    protected void created(@Nullable Bundle savedInstanceState, @NonNull ViewGroup rootView) {
        if (this.videoPlayer == null) {
            return;
        }
        logger.debug("created");

        this.previewImageViewRef = new WeakReference<>(rootView.findViewById(R.id.image));
        this.progressBarRef = new WeakReference<>(rootView.findViewById(R.id.progress_bar));
        this.videoViewRef = new WeakReference<>(rootView.findViewById(R.id.video_view));

        gestureFrameLayoutRef = new WeakReference<>(rootView.findViewById(R.id.video_gesture_frame));
        gestureFrameLayoutRef.get().getController().getSettings().setMaxZoom(2.5f);
        gestureFrameLayoutRef.get().getController().addOnStateChangeListener(onGestureStateChangeListener);

        this.videoViewRef.get().setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
            VideoViewFragment.this.showUi(visibility == View.VISIBLE)
        );
        this.videoViewRef.get().setVisibility(View.GONE);
        this.videoViewRef.get().setPlayer(this.videoPlayer);
        this.videoViewRef.get().setControllerHideOnTouch(true);
        this.videoViewRef.get().setControllerShowTimeoutMs(MediaViewerActivity.ACTIONBAR_TIMEOUT);
        this.videoViewRef.get().setControllerAutoShow(true);

        logger.debug("View Type: {}", (this.videoViewRef.get().getVideoSurfaceView() instanceof TextureView ? "Texture" : "Surface"));

        handleExoPlayerControllerViewInsets(this.videoViewRef.get());
    }

    private void handleExoPlayerControllerViewInsets(@NonNull PlayerView playerView) {
        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            playerView.findViewById(R.id.exo_center_controls),
            InsetSides.horizontal(),
            SpacingValues.all(R.dimen.exo_styled_controls_padding)
        );
        ViewExtensionsKt.applyDeviceInsetsAsMargin(
            playerView.findViewById(R.id.exo_bottom_bar),
            InsetSides.lbr()
        );
        ViewExtensionsKt.applyDeviceInsetsAsMargin(
            playerView.findViewById(R.id.exo_progress),
            InsetSides.lbr(),
            new SpacingValues(
                null,
                null,
                R.dimen.exo_styled_progress_margin_bottom,
                null
            )
        );
    }

    @Override
    protected void handleDecryptedFile(final File file) {
        logger.debug("handleDecryptedFile");

        if (this.isAdded()) {
            if (this.videoPlayer != null && this.videoPlayer.getPlaybackState() == Player.STATE_READY) {
                // navigated back to fragment
                playVideo(this.isImmediatePlay);
            } else {
                // new fragment
                loadVideo(Uri.fromFile(file));
            }
        } else {
            logger.debug("Fragment no longer added. Get out of here");
        }
    }

    @UiThread
    private void playVideo(boolean play) {
        logger.debug("playVideo");

        videoViewRef.get().setVisibility(View.VISIBLE);
        previewImageViewRef.get().setVisibility(View.GONE);
        progressBarRef.get().setVisibility(View.GONE);

        videoPlayer.setPlayWhenReady(play);
    }

    private void loadVideo(Uri videoUri) {
        logger.debug("loadVideo");

        if (this.videoPlayer != null) {
            DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(
                requireContext(),
                Util.getUserAgent(requireContext(), requireContext().getString(R.string.app_name))
            );
            MediaSource videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(videoUri));

            this.videoPlayer.setPlayWhenReady(this.isImmediatePlay);

            this.isPreparing = true;
            this.videoPlayer.prepare(videoSource);

            this.progressBarRef.get().setVisibility(View.VISIBLE);

            this.videoViewRef.get().setVisibility(View.GONE);
            this.previewImageViewRef.get().setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void showFileNotFoundContent() {
        logger.debug("showFileNotFoundContent");
        if (this.progressBarRef.get() != null) {
            this.progressBarRef.get().setVisibility(View.GONE);
        }
        super.showFileNotFoundContent();
    }

    @Override
    public void onDestroyView() {
        logger.debug("onDestroyView");

        if (this.videoPlayer != null) {
            this.videoPlayer.release();
            this.videoPlayer = null;
        }

        if (this.gestureFrameLayoutRef != null && this.gestureFrameLayoutRef.get() != null) {
            this.gestureFrameLayoutRef.get().getController().removeOnStateChangeListener(onGestureStateChangeListener);
            // F1Whisper: drop the PiP layout listener so it does not retain the destroyed view.
            if (this.pipLayoutListener != null) {
                this.gestureFrameLayoutRef.get().removeOnLayoutChangeListener(this.pipLayoutListener);
            }
        }
        this.pipLayoutListener = null;
        this.isInPipScaling = false;

        super.onDestroyView();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        keepScreenOn(isPlaying);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        logger.debug("onPlaybackStateChanged = {}", playbackState);

        if (isPreparing && playbackState == Player.STATE_READY) {
            isPreparing = false;

            this.progressBarRef.get().setVisibility(View.GONE);
            this.videoViewRef.get().setVisibility(View.VISIBLE);
            this.previewImageViewRef.get().setVisibility(View.GONE);
        }
        if (playbackState == Player.STATE_ENDED) {
            this.videoPlayer.setPlayWhenReady(false);
            this.videoPlayer.seekTo(0);
            this.videoViewRef.get().showController();
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        logger.error("ExoPlayer failed to play video with error code {}", error.getErrorCodeName(), error);

        this.progressBarRef.get().setVisibility(View.GONE);

        Toast.makeText(getContext(), R.string.unable_to_play_video, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        logger.debug("setUserVisibleHint = {}", isVisibleToUser);

        // F1Whisper: do NOT pause when the host activity is entering / already in picture-in-picture.
        // Entering PiP drives the activity through onPause(), which would otherwise pause the player
        // here and freeze the floating window on a single decoded frame. The ExoPlayer surface keeps
        // rendering across the PiP transition, so the live video continues playing in the PiP window.
        if (!isVisibleToUser && isHostInPictureInPicture()) {
            logger.debug("setUserVisibleHint: in PiP — keeping player running");
            return;
        }

        // stop player if fragment comes out of view
        if (!isVisibleToUser && this.videoPlayer != null && (this.videoPlayer.isLoading() || this.videoPlayer.isPlaying())) {
            this.videoPlayer.setPlayWhenReady(false);
            this.videoPlayer.pause();
        }
    }

    /**
     * F1Whisper: true while the host {@link MediaViewerActivity} is transitioning into or already in
     * picture-in-picture mode. Used to keep the ExoPlayer surface rendering across the transition.
     */
    private boolean isHostInPictureInPicture() {
        return getActivity() instanceof MediaViewerActivity
            && ((MediaViewerActivity) getActivity()).isEnteringOrInPictureInPictureMode();
    }

    /**
     * F1Whisper: hides the on-surface playback controls in PiP so ONLY the raw video surface shows in
     * the floating window, and restores them on exit. Does NOT touch playback — the player keeps
     * rendering. Gesture (pinch-zoom) suppression is handled by the PiP scaling lifecycle in
     * {@link #onEnterPip()} / {@link #onExitPip()}, not here.
     */
    public void setChromeVisibleForPip(boolean visible) {
        PlayerView playerView = videoViewRef != null ? videoViewRef.get() : null;
        if (playerView != null) {
            if (visible) {
                playerView.setUseController(true);
            } else {
                playerView.hideController();
                playerView.setUseController(false);
            }
        }
    }

    /**
     * F1Whisper: fixes the "only the upper-left quarter of the video shows in the PiP window" crop.
     *
     * <p><b>Root cause.</b> The {@code PlayerView} is wrapped in a {@link GestureFrameLayout}, which
     * does NOT let its child lay out at the window size and rely on ExoPlayer's resize-mode to scale
     * the video. Instead {@code GestureFrameLayout.dispatchDraw} always does
     * {@code canvas.concat(matrix)} with a matrix produced by its gesture controller's {@code State}.
     * The controller computes that matrix as a "fit" of the child's measured size (laid out at the
     * FULLSCREEN size) into the current viewport. When the window shrinks into PiP, the controller's
     * image size still reflects the fullscreen child while the canvas is clipped to the tiny PiP
     * window, so the child is drawn at fullscreen scale anchored at the origin — only the top-left
     * corner is visible.</p>
     *
     * <p><b>Fix.</b> While in PiP we force the gesture transform to the identity matrix so the
     * PlayerView (match_parent + {@code RESIZE_MODE_FIT}) draws 1:1 at the window bounds and ExoPlayer's
     * own internal {@code AspectRatioFrameLayout} does the aspect-correct letterbox — exactly how
     * Telegram lets its {@code AspectRatioFrameLayout} (match_parent) size the texture in PiP. The
     * identity matrix is produced by setting the gesture {@code Fit.NONE} with image size == viewport
     * size, which makes the controller's fit-zoom 1.0 with zero translation. We do NOT touch the
     * {@code TextureView}'s transform or its {@code SurfaceTexture} default buffer size: those are owned
     * by the media3 {@code PlayerView}, and overriding the buffer size to the view size (the prior
     * attempt) fights the player and distorts/crops the frame.</p>
     *
     * <p><b>Race fix.</b> The OS does not deliver the final PiP window size synchronously, and
     * {@code requestLayout()} only schedules a pass. So instead of posting once and reading (stale)
     * dimensions, we register an {@link View.OnLayoutChangeListener} and re-apply the identity transform
     * on EVERY layout pass while in PiP. The {@code GestureFrameLayout}'s own {@code onSizeChanged} →
     * {@code setViewport(...)} → {@code updateState()} re-fits against the (still fullscreen) image on
     * each resize, and our listener immediately neutralizes it again — so no matter when the window
     * settles to its final PiP bounds, the very next layout pass corrects the transform.</p>
     */
    public void onEnterPip() {
        final GestureFrameLayout gestureFrame = gestureFrameLayoutRef != null ? gestureFrameLayoutRef.get() : null;
        final PlayerView playerView = videoViewRef != null ? videoViewRef.get() : null;
        if (gestureFrame == null || playerView == null) {
            return;
        }

        // Save the fullscreen gesture settings exactly once so PiP exit fully restores them.
        if (!isInPipScaling) {
            final Settings settings = gestureFrame.getController().getSettings();
            savedFitMethod = settings.getFitMethod();
            savedGesturesEnabled = settings.isGesturesEnabled();
            savedBoundsEnabled = settings.isRestrictBounds();
            isInPipScaling = true;
        }

        // PlayerView fits the full frame (correct aspect, letterboxed) into the window.
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);

        // Apply immediately for the first frame, then keep re-applying on every layout pass until the
        // PiP window settles — defeats the race where a single post() reads stale fullscreen bounds.
        neutralizeGestureTransformForPip();

        if (pipLayoutListener == null) {
            pipLayoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                neutralizeGestureTransformForPip();
        }
        gestureFrame.removeOnLayoutChangeListener(pipLayoutListener);
        gestureFrame.addOnLayoutChangeListener(pipLayoutListener);
    }

    /**
     * F1Whisper: forces the {@link GestureFrameLayout} draw transform to the identity so the wrapped
     * {@link PlayerView} renders 1:1 at the current (PiP) window bounds. Reads the live view bounds at
     * call time (never a stale cached value) and is safe to call repeatedly.
     */
    private void neutralizeGestureTransformForPip() {
        if (!isInPipScaling) {
            return;
        }
        final GestureFrameLayout gestureFrame = gestureFrameLayoutRef != null ? gestureFrameLayoutRef.get() : null;
        if (gestureFrame == null || !isAdded()) {
            return;
        }
        final int viewportW = gestureFrame.getWidth();
        final int viewportH = gestureFrame.getHeight();
        if (viewportW <= 0 || viewportH <= 0) {
            return;
        }
        final GestureController controller = gestureFrame.getController();
        final Settings settings = controller.getSettings();
        // Fit.NONE + image size == viewport size => fit-zoom 1.0, no translation => identity matrix.
        settings.setFitMethod(Settings.Fit.NONE)
            .setViewport(viewportW, viewportH)
            .setImage(viewportW, viewportH);
        if (settings.isGesturesEnabled()) {
            settings.disableGestures();
        }
        if (settings.isRestrictBounds()) {
            settings.disableBounds();
        }
        controller.resetState();
        controller.updateState();
    }

    /**
     * F1Whisper: restores normal fullscreen rendering when the user expands the PiP window back. Removes
     * the PiP layout listener and restores the saved gesture {@link Settings} (fit method, gestures,
     * bounds), then lets the {@link GestureFrameLayout} recompute its fit transform against the restored
     * fullscreen viewport and the real child (video) size.
     */
    public void onExitPip() {
        final GestureFrameLayout gestureFrame = gestureFrameLayoutRef != null ? gestureFrameLayoutRef.get() : null;
        if (gestureFrame != null && pipLayoutListener != null) {
            gestureFrame.removeOnLayoutChangeListener(pipLayoutListener);
        }
        if (!isInPipScaling) {
            return;
        }
        isInPipScaling = false;

        final PlayerView playerView = videoViewRef != null ? videoViewRef.get() : null;
        if (playerView != null) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        if (gestureFrame == null) {
            return;
        }

        final Settings settings = gestureFrame.getController().getSettings();
        // Restore the saved fit method and re-balance the disable counters we incremented for PiP.
        if (savedFitMethod != null) {
            settings.setFitMethod(savedFitMethod);
        }
        if (!settings.isGesturesEnabled() && savedGesturesEnabled) {
            settings.enableGestures();
        }
        if (!settings.isRestrictBounds() && savedBoundsEnabled) {
            settings.enableBounds();
        }

        // Recompute the fit against the restored fullscreen viewport and the real child size, once the
        // expand relayout has produced the fullscreen bounds.
        gestureFrame.post(() -> {
            final GestureFrameLayout gf = gestureFrameLayoutRef != null ? gestureFrameLayoutRef.get() : null;
            if (gf == null || !isAdded()) {
                return;
            }
            final int viewportW = gf.getWidth();
            final int viewportH = gf.getHeight();
            final View child = gf.getChildCount() > 0 ? gf.getChildAt(0) : null;
            final int imageW = child != null && child.getMeasuredWidth() > 0 ? child.getMeasuredWidth() : viewportW;
            final int imageH = child != null && child.getMeasuredHeight() > 0 ? child.getMeasuredHeight() : viewportH;
            if (viewportW > 0 && viewportH > 0) {
                gf.getController().getSettings()
                    .setViewport(viewportW, viewportH)
                    .setImage(imageW, imageH);
            }
            gf.getController().resetState();
            gf.getController().updateState();
        });
    }

    /**
     * F1Whisper: stops + releases the ExoPlayer immediately. Called by {@link MediaViewerActivity} when
     * the PiP window is dismissed via the system "X" so audio/video does not keep decoding in the
     * background (Telegram releases its player on PiP close). Idempotent and null-safe; {@code
     * onDestroyView()} also releases, but only after the activity is destroyed — which is too late for
     * a PiP dismissal that leaves the player running.
     */
    public void stopAndReleasePlayer() {
        if (this.videoPlayer != null) {
            logger.debug("stopAndReleasePlayer");
            this.videoPlayer.setPlayWhenReady(false);
            this.videoPlayer.stop();
            this.videoPlayer.release();
            this.videoPlayer = null;
        }
    }

    /**
     * F1Whisper: resumes playback when entering picture-in-picture so the floating window shows live
     * motion (Telegram-style), but only when the player is READY and the video has not ended — never
     * forces a stalled/errored player to play. Also makes the player surface visible (it may be GONE
     * if PiP was entered before the user pressed play).
     */
    public void ensurePlayingForPip() {
        if (this.videoPlayer == null) {
            return;
        }
        PlayerView playerView = videoViewRef != null ? videoViewRef.get() : null;
        if (playerView != null && playerView.getVisibility() != View.VISIBLE
            && this.videoPlayer.getPlaybackState() == Player.STATE_READY) {
            playerView.setVisibility(View.VISIBLE);
            if (previewImageViewRef != null && previewImageViewRef.get() != null) {
                previewImageViewRef.get().setVisibility(View.GONE);
            }
            if (progressBarRef != null && progressBarRef.get() != null) {
                progressBarRef.get().setVisibility(View.GONE);
            }
        }
        int state = this.videoPlayer.getPlaybackState();
        if ((state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            && !this.videoPlayer.isPlaying()) {
            this.videoPlayer.setPlayWhenReady(true);
        }
    }

    /**
     * F1Whisper: the intrinsic size of the currently decoded video, or {@code null} if the player is
     * not ready / has no video track yet. Used by the host activity to compute the PiP aspect ratio
     * from the REAL video dimensions instead of a hardcoded 16:9.
     */
    @Nullable
    public androidx.media3.common.VideoSize getCurrentVideoSize() {
        if (this.videoPlayer == null) {
            return null;
        }
        androidx.media3.common.VideoSize size = this.videoPlayer.getVideoSize();
        if (size == null || size.width <= 0 || size.height <= 0) {
            return null;
        }
        return size;
    }

    /**
     * F1Whisper: the on-screen bounds of the live player surface, used as the PiP source-rect hint so
     * the OS animates the shrink from exactly where the video is rendered. Returns {@code null} when
     * the surface is not laid out.
     */
    @Nullable
    public android.graphics.Rect getPlayerSurfaceScreenBounds() {
        PlayerView playerView = videoViewRef != null ? videoViewRef.get() : null;
        if (playerView == null) {
            return null;
        }
        View surfaceView = playerView.getVideoSurfaceView();
        View boundsView = surfaceView != null ? surfaceView : playerView;
        if (boundsView.getWidth() <= 0 || boundsView.getHeight() <= 0) {
            return null;
        }
        int[] location = new int[2];
        boundsView.getLocationOnScreen(location);
        return new android.graphics.Rect(
            location[0],
            location[1],
            location[0] + boundsView.getWidth(),
            location[1] + boundsView.getHeight()
        );
    }
}
