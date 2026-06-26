package ch.threema.app.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.Process;
import android.util.Rational;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.Lifecycle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.fragment.app.FragmentTransaction;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.viewpager.widget.PagerAdapter;

import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import ch.threema.app.AppConstants;
import ch.threema.app.R;
import ch.threema.app.di.DependencyContainer;
import ch.threema.app.dialogs.ExpandableTextEntryDialog;
import ch.threema.app.emojis.EmojiMarkupUtil;
import ch.threema.app.fragments.mediaviews.AudioViewFragment;
import ch.threema.app.fragments.mediaviews.FileViewFragment;
import ch.threema.app.fragments.mediaviews.ImageViewFragment;
import ch.threema.app.fragments.mediaviews.MediaPlayerViewFragment;
import ch.threema.app.fragments.mediaviews.MediaViewFragment;
import ch.threema.app.fragments.mediaviews.VideoViewFragment;
import ch.threema.app.mediagallery.MediaGalleryActivity;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.services.AudioPlayerService;
import ch.threema.app.services.MessageService;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.LockableViewPager;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.IntentDataUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.ThreemaException;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.MessageContentsType;

import static ch.threema.app.di.DIJavaCompat.isSessionScopeReady;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

public class MediaViewerActivity extends ThreemaToolbarActivity implements ExpandableTextEntryDialog.ExpandableTextEntryDialogClickListener {

    private static final Logger logger = getThreemaLogger("MediaViewerActivity");

    {
        // Always use night mode for this activity. Note that setting it here avoids the activity being recreated.
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    private static final int PERMISSION_REQUEST_SAVE_MESSAGE = 1;
    private static final long LOADING_DELAY = 600;
    public static final int ACTIONBAR_TIMEOUT = 4000;

    public static final String EXTRA_ID_IMMEDIATE_PLAY = "play";
    public static final String EXTRA_ID_REVERSE_ORDER = "reverse";
    public static final String EXTRA_FILTER = "filter";
    public static final String EXTRA_IS_VOICE_MESSAGE = "vm";
    public static final String EXTRA_IS_PRIVATE_CHAT = "is_private_chat";

    private LockableViewPager pager;
    private File currentMediaFile;
    private ActionBar actionBar;

    private AbstractMessageModel currentMessageModel;
    private MessageReceiver currentReceiver;

    @NonNull
    private final DependencyContainer dependencies = KoinJavaComponent.get(DependencyContainer.class);

    private EmojiMarkupUtil emojiMarkupUtil;

    private List<AbstractMessageModel> messageModels;
    private int currentPosition = -1;
    private MediaViewFragment[] fragments;
    private File[] decryptedFileCache;

    private FrameLayout captionContainer;
    private TextView caption;
    private final Handler loadingFragmentHandler = new Handler();
    private MenuItem saveMenuItem, shareMenuItem, viewMenuItem;

    private boolean isPrivateChat = false;

    // F1Whisper: picture-in-picture state
    private @Nullable MenuItem pipMenuItem;
    private boolean isInPictureInPictureMode = false;
    // True from the moment enterPipMode() is invoked until the window has fully entered PiP, so the
    // video fragment keeps its ExoPlayer running across the activity's onPause() during the transition.
    private boolean isEnteringPictureInPictureMode = false;
    // True between onStart() and onStop(). Mirrors Telegram's PipActivityHandler.isActivityStarted:
    // it lets onPictureInPictureModeChanged(false) distinguish "expand back to fullscreen" (activity
    // still started → onResume follows) from "closed via the PiP X button" (onStop already ran →
    // !isActivityStarted), so we only tear the player down on a real dismissal.
    private boolean isActivityStarted = false;

    private @Nullable ListenableFuture<MediaController> mediaControllerFuture = null;
    private volatile @Nullable MediaController mediaController = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);
        if (!isSessionScopeReady()) {
            finish();
        }
    }

    @Override
    protected void handleDeviceInsets() {
        super.handleDeviceInsets();
        ViewExtensionsKt.applyDeviceInsetsAsMargin(
            findViewById(R.id.caption_container),
            InsetSides.lbr(),
            SpacingValues.horizontal(R.dimen.grid_unit_x2)
        );
    }

    @Override
    protected boolean initActivity(Bundle savedInstanceState) {
        if (!super.initActivity(savedInstanceState)) {
            return false;
        }
        logger.debug("initActivity");
        showSystemUi();

        Intent intent = getIntent();

        final @Nullable String messageType = IntentDataUtil.getAbstractMessageType(intent);
        final int messageId = IntentDataUtil.getAbstractMessageId(intent);
        if (TestUtil.isEmptyOrNull(messageType) || messageId <= 0) {
            finish();
            return false;
        }

        this.emojiMarkupUtil = EmojiMarkupUtil.getInstance();

        this.actionBar = getSupportActionBar();
        if (this.actionBar == null) {
            finish();
            return false;
        }
        this.actionBar.setDisplayHomeAsUpEnabled(true);
        this.actionBar.setTitle(" ");

        this.captionContainer = findViewById(R.id.caption_container);
        this.caption = findViewById(R.id.caption);

        this.currentMessageModel = IntentDataUtil.getAbstractMessageModel(intent, dependencies.getMessageService());
        try {
            this.currentReceiver = dependencies.getMessageService().getMessageReceiver(this.currentMessageModel);
        } catch (ThreemaException e) {
            logger.error("Exception", e);
            finish();
            return false;
        }

        if (currentMessageModel == null || currentReceiver == null) {
            finish();
            return false;
        }

        this.isPrivateChat = dependencies.getConversationCategoryService().isPrivateChat(
            this.currentReceiver.getUniqueIdString()
        );

        final @MessageContentsType int[] filter = intent.hasExtra(EXTRA_FILTER)
            ? intent.getIntArrayExtra(EXTRA_FILTER)
            : null;

        final @NonNull SessionToken sessionToken = new SessionToken(this, new ComponentName(this, AudioPlayerService.class));
        this.mediaControllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();

        //load all records of receiver to support list pager
        try {
            this.messageModels = this.currentReceiver.loadMessages(new MessageService.MessageFilter() {
                @Override
                public long getPageSize() {
                    return 0;
                }

                @Override
                public Integer getPageReferenceId() {
                    return null;
                }

                @Override
                public boolean withStatusMessages() {
                    return false;
                }

                @Override
                public boolean withUnsaved() {
                    return false;
                }

                @Override
                public boolean onlyUnread() {
                    return false;
                }

                @Override
                public boolean onlyDownloaded() {
                    return true;
                }

                @Override
                public MessageType[] types() {
                    return new MessageType[]{MessageType.IMAGE, MessageType.VIDEO, MessageType.FILE, MessageType.VOICEMESSAGE};
                }

                @Override
                @MessageContentsType
                public int[] contentTypes() {
                    return filter;
                }

                @Override
                public int[] displayTags() {
                    return null;
                }
            });
        } catch (Exception x) {
            logger.error("Exception", x);
            finish();
            return false;
        }

        if (intent.getBooleanExtra(EXTRA_ID_REVERSE_ORDER, false)) {
            // reverse order
            Collections.reverse(messageModels);
            for (int n = messageModels.size() - 1; n >= 0; n--) {
                if (this.messageModels.get(n).getId() == this.currentMessageModel.getId()) {
                    this.currentPosition = n;
                    break;
                }
            }
        } else {
            for (int n = 0; n < this.messageModels.size(); n++) {
                if (this.messageModels.get(n).getId() == this.currentMessageModel.getId()) {
                    this.currentPosition = n;
                    break;
                }
            }
        }

        if (currentPosition == -1) {
            Toast.makeText(this, R.string.media_file_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return false;
        }

        //create array
        this.fragments = new MediaViewFragment[this.messageModels.size()];
        this.decryptedFileCache = new File[this.fragments.length];

        // Instantiate a ViewPager and a PagerAdapter.
        this.pager = findViewById(R.id.pager);
        this.pager.setOnPageChangeListener(new LockableViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i2) {
            }

            @Override
            public void onPageSelected(int i) {
                currentFragmentChanged(i);
            }

            @Override
            public void onPageScrollStateChanged(int i) {
            }
        });

        this.attachAdapter();

        return true;
    }

    @Override
    public int getLayoutResource() {
        return R.layout.activity_media_viewer;
    }

    private void updateActionBarTitle(AbstractMessageModel messageModel) {
        String title = NameUtil.getContactDisplayNameOrNickname(
            this,
            messageModel,
            dependencies.getContactService(),
            dependencies.getUserService(),
            dependencies.getPreferenceService().getContactNameFormat()
        );

        @Nullable String subtitle = null;
        if (messageModel != null) {
            subtitle = MessageUtil.getDisplayDate(
                this,
                messageModel.getPostedAt(),
                messageModel.isOutbox(),
                messageModel.getModifiedAt(),
                true
            );
        }

        logger.debug("show updateActionBarTitle: '{}' '{}'", title, subtitle);

        if (getToolbar() != null && title != null) {
            getToolbar().setTitle(title);
            getToolbar().setSubtitle(subtitle);
        } else {
            getToolbar().setTitle(null);
        }

        String captionText = MessageUtil.getCaptionText(messageModel);
        if (!TestUtil.isEmptyOrNull(captionText)) {
            this.caption.setText(emojiMarkupUtil.addMarkup(this, captionText));
        } else {
            this.caption.setText("");
        }
        this.captionContainer.setVisibility(TestUtil.isEmptyOrNull(captionText) ? View.GONE : View.VISIBLE);
    }

    private void updateMenus() {
        boolean visibility = currentMediaFile != null && !dependencies.getAppRestrictions().isShareMediaDisabled();

        if (saveMenuItem != null) {
            saveMenuItem.setVisible(visibility);
            shareMenuItem.setVisible(visibility);
            viewMenuItem.setVisible(visibility);
        }
    }

    private void hideCurrentFragment() {
        if (this.currentPosition >= 0 && this.currentPosition < this.messageModels.size()) {
            MediaViewFragment f = this.getFragmentByPosition(this.currentPosition);
            if (f != null) {
                f.hide();
            }
        }
    }

    private void currentFragmentChanged(final int index) {
        this.loadingFragmentHandler.removeCallbacksAndMessages(null);
        this.loadingFragmentHandler.postDelayed(
            () -> loadCurrentFrame(index),
            LOADING_DELAY
        );
    }

    private void loadCurrentFrame(int index) {
        this.hideCurrentFragment();

        if (index >= 0 && index < this.messageModels.size()) {
            this.currentPosition = index;
            this.currentMessageModel = this.messageModels.get(this.currentPosition);

            updateActionBarTitle(this.currentMessageModel);
            // F1Whisper: refresh pip menu visibility when the viewed item changes
            updatePipMenuItem();

            final @Nullable MediaViewFragment currentMediaViewFragment = this.getCurrentFragment();
            for (@Nullable MediaViewFragment mediaViewFragment : fragments) {
                if (mediaViewFragment != null) {
                    mediaViewFragment.setIsCurrentlyInFocus(false);
                }
            }
            if (currentMediaViewFragment != null) {
                currentMediaViewFragment.setIsCurrentlyInFocus(true);
                RuntimeUtil.runOnUiThread(() -> {
                    logger.debug("showUI - loadCurrentFrame");
                    showUi();
                });
                currentMediaViewFragment.showDecrypted();
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.activity_media_viewer, menu);

        try {
            MenuBuilder menuBuilder = (MenuBuilder) menu;
            menuBuilder.setOptionalIconsVisible(true);
        } catch (Exception ignored) {
        }

        saveMenuItem = menu.findItem(R.id.menu_save);
        shareMenuItem = menu.findItem(R.id.menu_share);
        viewMenuItem = menu.findItem(R.id.menu_view);
        // F1Whisper: pip menu item — only shown for video when PiP is supported (API 26+)
        pipMenuItem = menu.findItem(R.id.menu_pip);
        updatePipMenuItem();

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.menu_save) {
            if (ConfigUtils.requestWriteStoragePermissions(this, null, PERMISSION_REQUEST_SAVE_MESSAGE)) {
                saveMedia();
            }
            return true;
        } else if (itemId == R.id.menu_view) {
            viewMediaInGallery();
            return true;
        } else if (itemId == R.id.menu_share) {
            shareMedia();
            return true;
        } else if (itemId == R.id.menu_gallery) {
            showGallery();
            return true;
        } else if (itemId == R.id.menu_show_in_chat) {
            showInChat(this.currentMessageModel);
            return true;
        } else if (itemId == R.id.menu_pip) {
            // F1Whisper: enter picture-in-picture from the menu
            enterPipMode();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void saveMedia() {
        AbstractMessageModel messageModel = this.getCurrentMessageModel();
        if (messageModel != null) {
            if (currentMediaFile == null) {
                Toast.makeText(this, R.string.media_file_not_found, Toast.LENGTH_LONG).show();
            } else {
                dependencies.getFileService().saveMedia(this, null, new CopyOnWriteArrayList<>(Collections.singletonList(messageModel)), true);
            }
        }
    }

    private void shareMedia() {
        final AbstractMessageModel messageModel = this.getCurrentMessageModel();
        if (messageModel != null) {
            final ExpandableTextEntryDialog alertDialog = ExpandableTextEntryDialog.newInstance(
                getString(R.string.share_media),
                R.string.add_caption_hint, messageModel.getCaption(),
                R.string.next, R.string.cancel, true);
            alertDialog.setData(messageModel);
            alertDialog.show(getSupportFragmentManager(), null);
        } else {
            logger.error("shareMedia: messageModel is null");
        }
    }

    @Override
    public void onYes(String tag, Object data, String text) {
        AbstractMessageModel messageModel = (AbstractMessageModel) data;
        Uri shareUri = dependencies.getFileService().copyToShareFile(messageModel, currentMediaFile);
        dependencies.getMessageService().shareMediaMessages(this,
            new ArrayList<>(Collections.singletonList(messageModel)),
            new ArrayList<>(Collections.singletonList(shareUri)), text);
    }

    @Override
    public void onNo(String tag) {
    }

    public void viewMediaInGallery() {
        AbstractMessageModel messageModel = this.getCurrentMessageModel();
        Uri shareUri = dependencies.getFileService().copyToShareFile(messageModel, currentMediaFile);
        dependencies.getMessageService().viewMediaMessage(this, messageModel, shareUri);
    }

    private void showGallery() {
        AbstractMessageModel messageModel = this.getCurrentMessageModel();
        if (messageModel != null) {
            Intent mediaGalleryIntent = new Intent(this, MediaGalleryActivity.class);
            mediaGalleryIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            switch (this.currentReceiver.getType()) {
                case MessageReceiver.Type_CONTACT:
                    mediaGalleryIntent.putExtra(AppConstants.INTENT_DATA_CONTACT, messageModel.getIdentity());
                    break;
                case MessageReceiver.Type_GROUP:
                    mediaGalleryIntent.putExtra(AppConstants.INTENT_DATA_GROUP_DATABASE_ID, (long) ((GroupMessageModel) messageModel).getGroupId());
                    break;
                case MessageReceiver.Type_DISTRIBUTION_LIST:
                    mediaGalleryIntent.putExtra(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID, ((DistributionListMessageModel) messageModel).getDistributionListId());
                    break;
            }
            IntentDataUtil.append(messageModel, mediaGalleryIntent);
            startActivity(mediaGalleryIntent);
            finish();
        }
    }

    private void showInChat(AbstractMessageModel messageModel) {
        if (messageModel == null) {
            return;
        }
        startActivityForResult(IntentDataUtil.getJumpToMessageIntent(this, messageModel), ThreemaActivity.ACTIVITY_ID_COMPOSE_MESSAGE);
        finish();
    }

    private void hideSystemUi() {
        logger.debug("hideSystemUi");
        if (getWindow() != null) {
            if (!isDestroyed()) {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                        | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
            }
        }
    }

    private void showSystemUi() {
        logger.debug("showSystemUi");
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    public void hideUi() {
        hideSystemUi();
        actionBar.hide();
        if (this.captionContainer != null) {
            this.captionContainer.setVisibility(View.GONE);
        }
    }

    public void showUi() {
        logger.debug("showUI");

        showSystemUi();
        actionBar.show();
        if (this.captionContainer != null && !TestUtil.isBlankOrNull(caption.getText())) {
            this.captionContainer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        // fixes https://code.google.com/p/android/issues/detail?id=19917
        super.onSaveInstanceState(outState);
        if (outState.isEmpty()) {
            outState.putBoolean("bug:fix", true);
        }
    }

    private AbstractMessageModel getCurrentMessageModel() {
        if (this.messageModels != null && this.currentPosition >= 0 && this.currentPosition < this.messageModels.size()) {
            return this.messageModels.get(this.currentPosition);
        }
        return null;
    }

    private MediaViewFragment getCurrentFragment() {
        return this.getFragmentByPosition(this.currentPosition);
    }

    private MediaViewFragment getFragmentByPosition(int position) {
        if (this.fragments != null && position >= 0 && position < this.fragments.length) {
            return this.fragments[position];
        }
        return null;
    }

    private void attachAdapter() {
        PagerAdapter pageAdapter = new ScreenSlidePagerAdapter(this, getSupportFragmentManager());
        this.pager.setAdapter(pageAdapter);
        this.pager.setCurrentItem(this.currentPosition);

        currentFragmentChanged(this.currentPosition);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Track the started/stopped window so onPictureInPictureModeChanged(false) can tell a PiP
        // expand from a PiP dismissal (Telegram PipActivityHandler.isActivityStarted pattern).
        this.isActivityStarted = true;
    }

    @Override
    protected void onStop() {
        // Mark the window stopped BEFORE super so onPictureInPictureModeChanged(false) — which the OS
        // fires AFTER onStop() on a PiP X-button dismissal — can detect the dismissal via
        // !isActivityStarted. We deliberately do NOT release the player here: pressing Home enters PiP
        // and ALSO drives the activity to onStop() while still in PiP (NOT a dismissal), so releasing
        // here would kill the floating window. Teardown happens in onPictureInPictureModeChanged.
        // (Mirrors Telegram LaunchActivity, which tears down in onPictureInPictureModeChanged when
        // !isStarted, not in onStop.)
        this.isActivityStarted = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        final @Nullable MediaController audioMediaController = this.mediaController;
        if (audioMediaController != null) {
            audioMediaController.stop();
            audioMediaController.clearMediaItems();
            audioMediaController.release();
        }
        if (mediaControllerFuture != null) {
            MediaController.releaseFuture(mediaControllerFuture);
        }

        //cleanup file cache
        loadingFragmentHandler.removeCallbacksAndMessages(null);

        if (decryptedFileCache != null) {
            for (int n = 0; n < this.decryptedFileCache.length; n++) {
                if (this.decryptedFileCache[n] != null && this.decryptedFileCache[n].exists()) {
                    FileUtil.deleteFileOrWarn(this.decryptedFileCache[n], "MediaViewerCache", logger);
                    this.decryptedFileCache[n] = null;
                }
            }
        }
        super.onDestroy();
    }

    public AbstractMessageModel getMessageModel(int position) {
        return messageModels.get(position);
    }

    public File[] getDecryptedFileCache() {
        return this.decryptedFileCache;
    }

    /**
     * Provides the {@code MediaController} after it was bound to the {@code AudioPlayerService}.
     * If the {@code MediaController} failed to create or bind, {@code null} will be passed to {@code mediaControllerConsumer}.
     *
     * @see MediaViewerActivity#getAudioMediaController()
     * @see AudioPlayerService#onGetSession
     */
    public void awaitAudioMediaController(@NonNull Consumer<MediaController> mediaControllerConsumer) {
        if (mediaControllerFuture == null) {
            mediaControllerConsumer.accept(null);
            return;
        }
        if (mediaController != null) {
            mediaControllerConsumer.accept(mediaController);
            return;
        }
        mediaControllerFuture.addListener(
            () -> {
                try {
                    mediaController = mediaControllerFuture.get();
                } catch (InterruptedException e) {
                    logger.error("Media Controller interrupted exception", e);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.error("Media Controller exception", e);
                } finally {
                    mediaControllerConsumer.accept(mediaController);
                }
            },
            MoreExecutors.directExecutor()
        );
    }

    /**
     * @return The {@code MediaController} that is currently bound to {@code AudioPlayerService} or null.
     * @see MediaViewerActivity#awaitAudioMediaController
     */
    @Nullable
    public MediaController getAudioMediaController() {
        return this.mediaController;
    }

    /**
     * Page Adapter that instantiates ImageViewFragments
     */
    public static class ScreenSlidePagerAdapter extends FragmentStatePagerAdapter {

        private final MediaViewerActivity mediaViewerActivity;
        private final FragmentManager fragmentManager;
        private final SparseArray<Fragment> fragments;
        private FragmentTransaction curTransaction;

        public ScreenSlidePagerAdapter(MediaViewerActivity mediaViewerActivity, FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            this.mediaViewerActivity = mediaViewerActivity;
            fragmentManager = fm;
            fragments = new SparseArray<>();
        }

        @NonNull
        @SuppressLint("CommitTransaction")
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            Fragment fragment = getItem(position);
            if (curTransaction == null) {
                curTransaction = fragmentManager.beginTransaction();
            }
            curTransaction.add(container.getId(), fragment, "fragment:" + position);
            fragments.put(position, fragment);
            return fragment;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object fragment) {
            return ((Fragment) fragment).getView() == view;
        }

        @NonNull
        @Override
        public Fragment getItem(final int position) {
            logger.debug("getItem {}", position);

            if (mediaViewerActivity.fragments[position] == null) {
                final AbstractMessageModel messageModel = mediaViewerActivity.messageModels.get(position);
                MediaViewFragment mediaViewFragment;
                Bundle args = new Bundle();

                // check if caller wants the item to be played immediately
                final Intent intent = mediaViewerActivity.getIntent();
                if (intent.getExtras() != null && intent.getExtras().getBoolean(EXTRA_ID_IMMEDIATE_PLAY, false)) {
                    args.putBoolean(EXTRA_ID_IMMEDIATE_PLAY, true);
                    intent.removeExtra(EXTRA_ID_IMMEDIATE_PLAY);
                }

                switch (messageModel.getType()) {
                    case VIDEO:
                        mediaViewFragment = new VideoViewFragment();
                        break;
                    case FILE:
                        String mimeType = messageModel.getFileData().getMimeType();
                        if (MimeUtil.isSupportedImageFile(mimeType)) {
                            mediaViewFragment = new ImageViewFragment();
                        } else if (MimeUtil.isVideoFile(mimeType)) {
                            mediaViewFragment = new VideoViewFragment();
                        } else if (MimeUtil.isAudioFile(mimeType)) {
                            if (MimeUtil.isMidiFile(mimeType) || MimeUtil.isFlacFile(mimeType)) {
                                mediaViewFragment = new MediaPlayerViewFragment();
                            } else {
                                args.putBoolean(EXTRA_IS_VOICE_MESSAGE, messageModel.getMessageContentsType() == MessageContentsType.VOICE_MESSAGE);
                                args.putBoolean(EXTRA_IS_PRIVATE_CHAT, mediaViewerActivity.isPrivateChat);
                                mediaViewFragment = new AudioViewFragment();
                            }
                        } else {
                            mediaViewFragment = new FileViewFragment();
                        }
                        break;
                    case VOICEMESSAGE:
                        args.putBoolean(EXTRA_IS_PRIVATE_CHAT, mediaViewerActivity.isPrivateChat);
                        mediaViewFragment = new AudioViewFragment();
                        break;
                    default:
                        mediaViewFragment = new ImageViewFragment();
                }

                args.putInt("position", position);
                mediaViewFragment.setArguments(args);

                mediaViewFragment.setOnImageLoaded(new MediaViewFragment.OnMediaLoadListener() {
                    @Override
                    public void decrypting() {
                        mediaViewerActivity.currentMediaFile = null;
                    }

                    @Override
                    public void decrypted(boolean success) {
                        if (!success) {
                            mediaViewerActivity.currentMediaFile = null;
                            mediaViewerActivity.updateMenus();
                        }
                    }

                    @Override
                    public void loaded(File file) {
                        mediaViewerActivity.currentMediaFile = file;
                        mediaViewerActivity.updateMenus();
                    }

                    @Override
                    public void thumbnailLoaded(Drawable bitmap) {
                        //do nothing!
                    }
                });
                mediaViewerActivity.fragments[position] = mediaViewFragment;
            }

            return mediaViewerActivity.fragments[position];
        }

        @SuppressLint("CommitTransaction")
        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            logger.debug("destroyItem {}", position);

            if (curTransaction == null) {
                curTransaction = fragmentManager.beginTransaction();
            }
            curTransaction.detach(fragments.get(position));
            fragments.remove(position);

            if (position >= 0 && position < mediaViewerActivity.fragments.length) {
                if (mediaViewerActivity.fragments[position] != null) {
                    //free memory
                    mediaViewerActivity.fragments[position].destroy();

                    //remove from array
                    mediaViewerActivity.fragments[position] = null;
                }
            }
        }

        @Override
        public void finishUpdate(@NonNull ViewGroup container) {
            if (curTransaction != null) {
                curTransaction.commitAllowingStateLoss();
                curTransaction = null;
                fragmentManager.executePendingTransactions();
            }
        }

        @Override
        public int getCount() {
            return mediaViewerActivity.messageModels.size();
        }

        @Override
        public Parcelable saveState() {
            // fix TransactionTooLargeException
            Bundle bundle = (Bundle) super.saveState();
            if (bundle != null) {
                bundle.putParcelableArray("states", null);
            }
            return bundle;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_SAVE_MESSAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveMedia();
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    ConfigUtils.showPermissionRationale(this, findViewById(R.id.pager), R.string.permission_storage_required);
                }
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        ConfigUtils.adjustToolbar(this, getToolbar());
    }

    //region F1Whisper: picture-in-picture (video minimize)

    /** Returns true when the currently visible item is a video (VIDEO message or FILE with video MIME). */
    private boolean isCurrentlyShowingVideo() {
        AbstractMessageModel model = getCurrentMessageModel();
        if (model == null) {
            return false;
        }
        if (model.getType() == MessageType.VIDEO) {
            return true;
        }
        if (model.getType() == MessageType.FILE && model.getFileData() != null) {
            return MimeUtil.isVideoFile(model.getFileData().getMimeType());
        }
        return false;
    }

    /**
     * Shows the PiP menu item only when a video is displayed AND the device supports PiP (API 26+).
     * Never shown in PiP mode itself (chrome is hidden there anyway).
     */
    private void updatePipMenuItem() {
        if (pipMenuItem == null) {
            return;
        }
        boolean showPip = !isInPictureInPictureMode
            && ConfigUtils.supportsPictureInPicture(this)
            && isCurrentlyShowingVideo();
        pipMenuItem.setVisible(showPip);
    }

    /** Returns the currently visible {@link VideoViewFragment}, or null if the current item is not a video. */
    private @Nullable VideoViewFragment getCurrentVideoFragment() {
        MediaViewFragment fragment = getCurrentFragment();
        if (fragment instanceof VideoViewFragment) {
            return (VideoViewFragment) fragment;
        }
        return null;
    }

    /**
     * F1Whisper: true while we are transitioning into, or already in, picture-in-picture. The video
     * fragment queries this so it does NOT pause the ExoPlayer during the activity's onPause() that
     * the PiP transition triggers — the surface keeps rendering the live video into the PiP window.
     */
    public boolean isEnteringOrInPictureInPictureMode() {
        return isEnteringPictureInPictureMode || isInPictureInPictureMode;
    }

    /**
     * Builds the {@link PictureInPictureParams} from the REAL decoded video dimensions so the floating
     * window fits the video without cropping, and a source-rect hint taken from the live player surface
     * so the OS animates the shrink from exactly where the video is rendered.
     *
     * <p>Aspect-ratio clamping mirrors Telegram's PipSourceParams (valid PiP range is roughly
     * 0.45..2.39): below 45:100 we clamp to 45:100, above 235:100 we clamp to 235:100.</p>
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private @NonNull PictureInPictureParams buildPictureInPictureParams(@Nullable VideoViewFragment videoFragment) {
        PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();

        // Derive the aspect ratio from the actual decoded video size (accounting for non-square
        // pixels via pixelWidthHeightRatio); fall back to 16:9 only if the player has no video track
        // size yet (e.g. still preparing).
        int videoWidth = 0;
        int videoHeight = 0;
        if (videoFragment != null) {
            androidx.media3.common.VideoSize videoSize = videoFragment.getCurrentVideoSize();
            if (videoSize != null) {
                float pixelRatio = videoSize.pixelWidthHeightRatio > 0f ? videoSize.pixelWidthHeightRatio : 1f;
                videoWidth = Math.round(videoSize.width * pixelRatio);
                videoHeight = videoSize.height;
            }
        }

        final Rational aspectRatio;
        if (videoWidth > 0 && videoHeight > 0) {
            float ratio = (float) videoWidth / (float) videoHeight;
            // Clamp to the valid PiP range, mirroring Telegram's PipSourceParams.
            if (ratio < 0.45f) {
                aspectRatio = new Rational(45, 100);
            } else if (ratio > 2.35f) {
                aspectRatio = new Rational(235, 100);
            } else {
                aspectRatio = new Rational(videoWidth, videoHeight);
            }
        } else {
            aspectRatio = new Rational(16, 9);
        }
        pipBuilder.setAspectRatio(aspectRatio);

        // Source-rect hint from the live player surface (preferred) or the pager as a fallback.
        Rect sourceRect = null;
        if (videoFragment != null) {
            sourceRect = videoFragment.getPlayerSurfaceScreenBounds();
        }
        if (sourceRect == null) {
            View pagerView = pager;
            if (pagerView != null && pagerView.getWidth() > 0 && pagerView.getHeight() > 0) {
                int[] location = new int[2];
                pagerView.getLocationOnScreen(location);
                sourceRect = new Rect(
                    location[0],
                    location[1],
                    location[0] + pagerView.getWidth(),
                    location[1] + pagerView.getHeight()
                );
            }
        }
        if (sourceRect != null && !sourceRect.isEmpty()) {
            pipBuilder.setSourceRectHint(sourceRect);
        }

        return pipBuilder.build();
    }

    /**
     * Enters picture-in-picture mode for the video currently on screen.
     * Guards: API 26+, FEATURE_PICTURE_IN_PICTURE, AppOps permission.
     */
    private void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        if (!ConfigUtils.supportsPictureInPicture(this)) {
            return;
        }
        if (!isCurrentlyShowingVideo()) {
            return;
        }

        // Respect the per-app PiP permission the user controls in Settings.
        AppOpsManager appOpsManager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (appOpsManager != null
            && appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                Process.myUid(),
                getPackageName()) != AppOpsManager.MODE_ALLOWED) {
            // The user has disabled PiP for this app in Settings — do nothing silently.
            logger.info("PiP disabled by user in system settings");
            return;
        }

        final @Nullable VideoViewFragment videoFragment = getCurrentVideoFragment();
        final PictureInPictureParams params = buildPictureInPictureParams(videoFragment);

        // Mark the transition BEFORE entering so the fragment's onPause()/setUserVisibleHint(false)
        // (triggered by entering PiP) keeps the ExoPlayer running instead of pausing the surface.
        isEnteringPictureInPictureMode = true;
        try {
            if (!enterPictureInPictureMode(params)) {
                isEnteringPictureInPictureMode = false;
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            isEnteringPictureInPictureMode = false;
            logger.error("Unable to enter PiP mode", e);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Auto-enter PiP when the user presses Home while a video is playing, matching
        // the CallActivity behaviour.
        if (isCurrentlyShowingVideo()) {
            enterPipMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        this.isInPictureInPictureMode = isInPip;
        // The transition has resolved; clear the "entering" guard regardless of direction.
        this.isEnteringPictureInPictureMode = false;

        final @Nullable VideoViewFragment videoFragment = getCurrentVideoFragment();

        if (isInPip) {
            // Hide all chrome (action bar, caption, playback controls) so ONLY the player surface
            // is visible in the floating window. The ExoPlayer keeps playing — no pause/resume,
            // no release; its TextureView surface stays attached and renders the live stream.
            logger.debug("Entering PiP mode — hiding chrome, keeping player surface live");
            hideUi();
            if (videoFragment != null) {
                videoFragment.setChromeVisibleForPip(false);
                // Scale the player surface to the (now small) PiP window so the FULL frame fits
                // instead of only its top-left corner — see VideoViewFragment.onEnterPip().
                videoFragment.onEnterPip();
                // Resume playback so the floating window shows live motion (Telegram-style).
                videoFragment.ensurePlayingForPip();
            }
        } else {
            // isInPip == false. Two very different cases reach here:
            //   1. The user tapped the PiP window to EXPAND back to fullscreen → the activity is still
            //      started (onStart ran, onResume follows). Restore the chrome and continue playing.
            //   2. The user tapped the system "X" to CLOSE the PiP → the OS already drove us through
            //      onStop() (so isActivityStarted == false). This is the reliable dismissal signal:
            //      stop + release the player and finish, otherwise ExoPlayer keeps decoding audio/video
            //      in the background until the whole app is force-stopped. (Mirrors Telegram
            //      LaunchActivity.onPictureInPictureModeChanged → destroyPhotoViewer/releasePlayer when
            //      !isStarted.)
            //
            // Detection = onStop already ran (!isActivityStarted) OR the activity is finishing OR the
            // lifecycle is below STARTED. The lifecycle cross-check makes the dismissal signal robust on
            // OEMs that fire this callback before our onStop() flag flips (Android PiP docs: "onStop
            // while in PiP == closed"). An EXPAND keeps the activity at/above STARTED so it falls through.
            final boolean belowStarted =
                !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
            if (!isActivityStarted || isFinishing() || belowStarted) {
                logger.debug("PiP closed via X (activity stopped/finishing) — releasing player and finishing");
                stopAndReleaseVideoPlayer();
                finish();
                return;
            }
            // Case 1: expand back. Playback state is preserved (still playing / paused exactly as the
            // user left it in the PiP window).
            logger.debug("Exiting PiP mode via expand — restoring chrome");
            showUi();
            if (videoFragment != null) {
                videoFragment.onExitPip();
                videoFragment.setChromeVisibleForPip(true);
            }
        }
        // Refresh pip menu item visibility (hidden in pip, visible on expand for video).
        updatePipMenuItem();
    }

    /**
     * F1Whisper: stops and releases the currently visible video fragment's ExoPlayer. Called when the
     * PiP window is dismissed via the system "X" so playback does not leak into the background.
     */
    private void stopAndReleaseVideoPlayer() {
        final @Nullable VideoViewFragment videoFragment = getCurrentVideoFragment();
        if (videoFragment != null) {
            videoFragment.stopAndReleasePlayer();
        }
    }

    //endregion
}
