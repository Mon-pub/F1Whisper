package ch.threema.app.fragments.composemessage;

import android.Manifest;
import android.animation.Animator;
import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.Filter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.badge.BadgeUtils;
import com.google.android.material.badge.ExperimentalBadgeUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;
import com.google.common.util.concurrent.ListenableFuture;

import org.jetbrains.annotations.Contract;
import org.koin.android.compat.ViewModelCompat;
import org.koin.core.parameter.ParametersHolder;
import org.koin.java.KoinJavaComponent;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.AnyThread;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.compose.ui.platform.ComposeView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.transition.Slide;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import ch.threema.android.ActivityExtensionsKt;
import ch.threema.app.AppConstants;
import ch.threema.app.BuildConfig;
import ch.threema.app.ExecutorServices;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.actions.SendAction;
import ch.threema.app.actions.TextMessageSendAction;
import ch.threema.app.activities.ComposeMessageActivity;
import ch.threema.app.activities.DistributionListAddActivity;
import ch.threema.app.activities.ImagePaintActivity;
import ch.threema.app.activities.MediaViewerActivity;
import ch.threema.app.activities.RecipientListBaseActivity;
import ch.threema.app.activities.SendMediaActivity;
import ch.threema.app.activities.ThreemaActivity;
import ch.threema.app.activities.ThreemaToolbarActivity;
import ch.threema.app.activities.ballot.BallotOverviewActivity;
import ch.threema.app.activities.notificationpolicy.ContactNotificationsActivity;
import ch.threema.app.activities.notificationpolicy.GroupNotificationsActivity;
import ch.threema.app.adapters.ComposeMessageAdapter;
import ch.threema.app.adapters.decorators.AudioChatAdapterDecorator;
import ch.threema.app.adapters.decorators.BallotChatAdapterDecorator;
import ch.threema.app.adapters.decorators.ChatAdapterDecoratorListener;
import ch.threema.app.adapters.decorators.FileChatAdapterDecorator;
import ch.threema.app.adapters.decorators.ImageChatAdapterDecorator;
import ch.threema.app.adapters.decorators.MessagePlayerFactory;
import ch.threema.app.adapters.decorators.VoipStatusDataChatAdapterDecorator;
import ch.threema.app.asynctasks.EmptyOrDeleteConversationsAsyncTask;
import ch.threema.app.availabilitystatus.AvailabilityStatusContactBannerView;
import ch.threema.app.availabilitystatus.AvailabilityStatusIconElevatedView;
import ch.threema.app.availabilitystatus.AvailabilityStatusTooltipPopupManager;
import ch.threema.app.availabilitystatus.ViewFullAvailabilityStatusBottomSheetDialog;
import ch.threema.app.cache.ThumbnailCache;
import ch.threema.app.compose.common.interop.ComposeJavaBridge;
import ch.threema.app.contactdetails.ContactDetailActivity;
import ch.threema.app.di.DependencyContainer;
import ch.threema.app.dialogs.BottomSheetGridDialog;
import ch.threema.app.dialogs.ExpandableTextEntryDialog;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.dialogs.ResendGroupMessageDialog;
import ch.threema.app.dialogs.SelectorDialog;
import ch.threema.app.drafts.DraftManager;
import ch.threema.app.drafts.DraftUpdateTextWatcher;
import ch.threema.app.drafts.MessageDraft;
import ch.threema.app.emojireactions.EmojiHintPopupManager;
import ch.threema.app.emojireactions.EmojiReactionsOverviewActivity;
import ch.threema.app.emojireactions.EmojiReactionsPickerActivity;
import ch.threema.app.emojireactions.EmojiReactionsPopup;
import ch.threema.app.emojis.EmojiButton;
import ch.threema.app.emojis.EmojiMarkupUtil;
import ch.threema.app.emojis.EmojiPicker;
import ch.threema.app.emojis.EmojiTextView;
import ch.threema.app.glide.AvatarOptions;
import ch.threema.app.home.HomeActivity;
import ch.threema.app.listeners.BallotListener;
import ch.threema.app.listeners.BallotVoteListener;
import ch.threema.app.listeners.ContactListener;
import ch.threema.app.listeners.ContactTypingListener;
import ch.threema.app.listeners.ConversationListener;
import ch.threema.app.listeners.GroupTypingListener;
import ch.threema.app.listeners.GroupListener;
import ch.threema.app.listeners.MessageDeletedForAllListener;
import ch.threema.app.listeners.MessageListener;
import ch.threema.app.listeners.MessagePlayerListener;
import ch.threema.app.listeners.QRCodeScanListener;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.mediaattacher.MediaAttachActivity;
import ch.threema.app.mediaattacher.MediaFilterQuery;
import ch.threema.app.mediagallery.MediaGalleryActivity;
import ch.threema.app.messagedetails.MessageDetailsActivity;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.messagereceiver.DistributionListMessageReceiver;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.messagereceiver.SendingPermissionValidationResult;
import ch.threema.app.multidevice.MultiDeviceManager;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.routines.MarkAsReadRoutine;
import ch.threema.app.services.ActivityService;
import ch.threema.app.services.BlockedIdentitiesService;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.ConversationCategoryService;
import ch.threema.app.services.ConversationService;
import ch.threema.app.services.DeviceService;
import ch.threema.app.services.DistributionListService;
import ch.threema.app.services.DownloadService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.GroupFlowDispatcher;
import ch.threema.app.services.GroupService;
import ch.threema.app.services.MessageService;
import ch.threema.app.services.RingtoneService;
import ch.threema.app.services.DisappearingMessageService;
import ch.threema.app.services.ScheduledMessageService;
import ch.threema.app.services.UserService;
import ch.threema.app.services.VoiceMessagePlayerService;
import ch.threema.app.services.WallpaperService;
import ch.threema.app.services.ballot.BallotService;
import ch.threema.app.services.license.LicenseService;
import ch.threema.app.services.messageplayer.MessagePlayer;
import ch.threema.app.services.messageplayer.MessagePlayerService;
import ch.threema.app.services.notification.NotificationService;
import ch.threema.app.ui.AvatarView;
import ch.threema.app.ui.BottomSheetItem;
import ch.threema.app.ui.ContentCommitComposeEditText;
import ch.threema.app.ui.ConversationListView;
import ch.threema.app.ui.DebouncedOnClickListener;
import ch.threema.app.linkpreview.ComposeLinkPreviewController;
import ch.threema.app.linkpreview.LinkPreviewImageFactory;
import ch.threema.app.linkpreview.LinkPreviewResult;
import ch.threema.app.ui.ListViewTouchSwipeListener;
import ch.threema.app.ui.LongToast;
import ch.threema.app.ui.MediaItem;
import ch.threema.app.ui.OngoingCallNoticeMode;
import ch.threema.app.ui.OngoingCallNoticeView;
import ch.threema.app.ui.OpenBallotNoticeView;
import ch.threema.app.ui.QuotePopup;
import ch.threema.app.ui.ReportSpamView;
import ch.threema.app.ui.RootViewDeferringInsetsCallback;
import ch.threema.app.ui.ScrollButtonManager;
import ch.threema.app.ui.SelectorDialogItem;
import ch.threema.app.ui.SendButton;
import ch.threema.app.ui.SimpleTextWatcher;
import ch.threema.app.ui.SingleToast;
import ch.threema.app.ui.TooltipPopup;
import ch.threema.app.ui.TranslateDeferringInsetsAnimationCallback;
import ch.threema.app.ui.TypingIndicatorTextWatcher;
import ch.threema.app.ui.VerificationLevelImageView;
import ch.threema.app.ui.listitemholder.ComposeMessageHolder;
import ch.threema.app.utils.AnimationUtil;
import ch.threema.app.utils.BallotUtil;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.ContactUtil;
import ch.threema.app.utils.ConversationUtil;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.EditTextUtil;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.GroupCallUtil;
import ch.threema.app.utils.GroupFeatureAdoptionRate;
import ch.threema.app.utils.GroupFeatureSupport;
import ch.threema.app.utils.IntentDataUtil;
import ch.threema.app.utils.LinkifyUtil;
import ch.threema.app.utils.LocaleUtil;
import ch.threema.app.utils.MediaSpoilerUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.DisappearingMessageUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.MessageUtilKt;
import ch.threema.app.utils.NavigationUtil;
import ch.threema.app.utils.QuoteUtil;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.ShortcutUtil;
import ch.threema.app.utils.SoundEffectPlayer;
import ch.threema.app.utils.TapTargetViewUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.utils.ToolbarUtil;
import ch.threema.app.voicemessage.VoiceRecorderActivity;
import ch.threema.app.voip.groupcall.GroupCallDescription;
import ch.threema.app.voip.groupcall.GroupCallManager;
import ch.threema.app.voip.groupcall.GroupCallObserver;
import ch.threema.app.voip.listeners.VoipCallEventListener;
import ch.threema.app.voip.managers.VoipListenerManager;
import ch.threema.app.voip.services.VoipCallService;
import ch.threema.app.voip.services.VoipStateService;
import ch.threema.app.voip.util.VoipUtil;
import ch.threema.app.webviews.WorkExplainActivity;
import ch.threema.data.datatypes.AvailabilityStatus;
import ch.threema.data.datatypes.IdColor;
import ch.threema.data.datatypes.NotificationTriggerPolicyOverride;
import ch.threema.data.models.ContactModelData;
import ch.threema.data.models.GroupIdentity;
import ch.threema.data.models.GroupModel;
import ch.threema.data.models.GroupModelData;
import ch.threema.data.repositories.EmojiReactionsRepository;
import ch.threema.data.repositories.GroupModelRepository;
import ch.threema.domain.models.IdentityType;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.models.VerificationLevel;
import ch.threema.domain.protocol.ThreemaFeature;
import ch.threema.domain.protocol.csp.ProtocolDefines;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.factories.RejectedGroupMessageFactory;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.ConversationModel;
import ch.threema.storage.models.DateSeparatorMessageModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.DistributionListModel;
import ch.threema.storage.models.FirstUnreadMessageModel;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.ScheduledMessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ballot.BallotModel;
import ch.threema.storage.models.data.DisplayTag;
import ch.threema.storage.models.data.MessageContentsType;
import ch.threema.storage.models.group.GroupMessageModel;
import ch.threema.storage.models.group.GroupModelOld;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.widget.AbsListView.CHOICE_MODE_MULTIPLE;
import static android.widget.AbsListView.CHOICE_MODE_MULTIPLE_MODAL;
import static ch.threema.android.ToastKt.showToast;
import static ch.threema.app.AppConstants.THREEMA_CHANNEL_IDENTITY;
import static ch.threema.app.AppConstants.THREEMA_SUPPORT_IDENTITY;
import static ch.threema.app.ThreemaApplication.getAppContext;
import static ch.threema.app.adapters.ComposeMessageAdapter.MIN_CONSTRAINT_LENGTH;
import static ch.threema.app.adapters.decorators.BallotChatAdapterDecorator.ACTION_CLOSE;
import static ch.threema.app.adapters.decorators.BallotChatAdapterDecorator.ACTION_RESULTS;
import static ch.threema.app.adapters.decorators.BallotChatAdapterDecorator.ACTION_VOTE;
import static ch.threema.app.messagereceiver.MessageReceiverExtensionsKt.isGatewayChat;
import static ch.threema.app.services.messageplayer.MessagePlayer.SOURCE_AUDIORECORDER;
import static ch.threema.app.services.messageplayer.MessagePlayer.SOURCE_LIFECYCLE;
import static ch.threema.app.services.messageplayer.MessagePlayer.SOURCE_VOIP;
import static ch.threema.app.ui.ScrollButtonManager.SCROLLBUTTON_VIEW_TIMEOUT;
import static ch.threema.app.ui.ScrollButtonManager.TYPE_DOWN;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;
import static ch.threema.app.utils.MessageUtil.canDeleteRemotely;
import static ch.threema.app.utils.ShortcutUtil.TYPE_CHAT;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;
import static ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_PINNED;
import static ch.threema.storage.models.data.DisplayTag.DISPLAY_TAG_STARRED;

public class ComposeMessageFragment extends Fragment implements
    LifecycleOwner,
    DefaultLifecycleObserver,
    SwipeRefreshLayout.OnRefreshListener,
    GenericAlertDialog.DialogClickListener,
    SelectorDialog.SelectorDialogClickListener,
    EmojiPicker.EmojiPickerListener,
    ReportSpamView.OnReportButtonClickListener,
    ThreemaToolbarActivity.OnSoftKeyboardChangedListener,
    ExpandableTextEntryDialog.ExpandableTextEntryDialogClickListener,
    VoipStatusDataChatAdapterDecorator.VoipStatusDataChatListener,
    BallotChatAdapterDecorator.BallotChatListener,
    ChatAdapterDecoratorListener,
    LinkifyUtil.LinkifyListener,
    MessagePlayerFactory,
    ImageChatAdapterDecorator.ImageListener,
    FileChatAdapterDecorator.DownloadAlertDialogListener,
    AudioChatAdapterDecorator.UserInteractionListener {

    private static final Logger logger = getThreemaLogger("ComposeMessageFragment");

    private static final String CONFIRM_TAG_DELETE_DISTRIBUTION_LIST = "deleteDistributionList";
    public static final String DIALOG_TAG_CONFIRM_CALL = "dtcc";
    private static final String DIALOG_TAG_CHOOSE_SHORTCUT_TYPE = "st";
    private static final String DIALOG_TAG_EMPTY_CHAT = "ccc";
    private static final String DIALOG_TAG_CONFIRM_BLOCK = "block";
    private static final String DIALOG_TAG_DECRYPTING_MESSAGES = "dcr";
    private static final String DIALOG_TAG_SEARCHING = "src";
    private static final String DIALOG_TAG_LOADING_MESSAGES = "loadm";
    private static final String DIALOG_TAG_MESSAGE_DETAIL = "messageLog";
    private static final String DIALOG_TAG_CONFIRM_MESSAGE_DELETE = "msgdel";
    private static final String DIALOG_TAG_CONFIRM_RESEND = "confirm_resend";
    private static final String DIALOG_TAG_EDIT_MESSAGES_UNSUPPORTED_WARNING = "editmsg_unsupported";
    private static final String DIALOG_TAG_DELETE_MESSAGES_UNSUPPORTED_WARNING = "deletemsg_unsupported";

    public static final String EXTRA_API_MESSAGE_ID = "apimsgid";
    public static final String EXTRA_SEARCH_QUERY = "searchQuery";
    public static final String EXTRA_OVERRIDE_BACK_TO_HOME_BEHAVIOR = "backOverride";
    public static final String EXTRA_LAST_MEDIA_SEARCH_QUERY = "searchMediaQuery";
    public static final String EXTRA_LAST_MEDIA_TYPE_QUERY = "searchMediaType";

    private static final int PERMISSION_REQUEST_SAVE_MESSAGE = 2;
    private static final int PERMISSION_REQUEST_ATTACH_VOICE_MESSAGE = 7;
    private static final int PERMISSION_REQUEST_ATTACH_CAMERA = 8;
    private static final int PERMISSION_REQUEST_ATTACH_CAMERA_VIDEO = 11;

    private static final int ACTIVITY_ID_VOICE_RECORDER = 9731;

    public static final long VIBRATION_MSEC = 300;
    private static final long MESSAGE_PAGE_SIZE = 100;
    private static final int SMOOTHSCROLL_THRESHOLD = 10;
    private static final int MAX_SELECTED_ITEMS = 100; // may not be larger than MESSAGE_PAGE_SIZE
    public static final int MAX_FORWARDABLE_ITEMS = 50;

    private static final String CAMERA_URI = "camera_uri";
    private static final String BUNDLE_LIST_POSITION = "list_position";
    private static final String BUNDLE_LIST_RECEIVER_ID = "list_receiver_id";
    private static final String BUNDLE_LIST_TOP = "list_top";
    private static final String BUNDLE_LIST_LONG_CLICK_ITEM = "list_long_click_item";

    @NonNull
    private final DependencyContainer dependencies = KoinJavaComponent.get(DependencyContainer.class);

    private ComposeMessageViewModel viewModel;

    private ContentCommitComposeEditText messageText;
    private SendButton sendButton;
    private ImageButton attachButton, cameraButton, sendEditMessageButton;
    private ContactModel contactModel;
    private @Nullable MessageReceiver messageReceiver;

    private AudioManager audioManager;
    private ConversationListView convListView;
    private FrameLayout historyParent;
    // F1Whisper: scheduled-messages indicator bar
    private @Nullable View scheduledMessagesBar;
    private @Nullable TextView scheduledMessagesCount;
    private @Nullable ComposeMessageAdapter composeMessageAdapter;
    private View isTypingView;

    private MenuItem mutedMenuItem;
    private MenuItem blockMenuItem;
    private MenuItem deleteDistributionListItem;
    private MenuItem callItem;
    private MenuItem showOpenBallotWindowMenuItem;
    private MenuItem showBallotsMenuItem;
    private MenuItem showEmptyChatMenuItem;
    private MenuItem disappearingMessagesMenuItem;
    private TextView dateTextView;
    private TextInputLayout textInputLayout;
    private ConstraintLayout conversationParent;

    private ActionMode actionMode = null;
    private ActionMode searchActionMode = null;
    private ActionMode editMessageActionMode = null;
    private FrameLayout dateView = null;
    private FrameLayout bottomPanel = null;
    private ComposeLinkPreviewController linkPreviewController = null; // F1Whisper: link-preview chip
    private String identity;
    private Long groupDbId = 0L;
    private Long distributionListId = 0L;
    private Uri cameraUri;
    private long intentTimestamp = 0L;
    private int longClickItem = AbsListView.INVALID_POSITION;
    private int listViewTop = 0, lastFirstVisibleItem = -1;
    private TypingIndicatorTextWatcher typingIndicatorTextWatcher;
    private @Nullable DraftUpdateTextWatcher draftUpdateTextWatcher;
    private @NonNull Map<String, Integer> identityColors = Collections.emptyMap();
    private MediaFilterQuery lastMediaFilter;
    private RootViewDeferringInsetsCallback rootInsetsDeferringCallback = null;
    private TranslateDeferringInsetsAnimationCallback keyboardAnimationInsetsCallback = null;

    private MultiDeviceManager multiDeviceManager;
    private PreferenceService preferenceService;
    private ContactService contactService;
    private MessageService messageService;
    private NotificationService notificationService;
    private BlockedIdentitiesService blockedIdentitiesService;
    private ConversationService conversationService;
    private DeviceService deviceService;
    private WallpaperService wallpaperService;
    private ConversationCategoryService conversationCategoryService;
    private RingtoneService ringtoneService;
    private UserService userService;
    private FileService fileService;
    private VoipStateService voipStateService;
    private DownloadService downloadService;
    private LicenseService licenseService;
    private EmojiReactionsRepository emojiReactionsRepository;
    private DraftManager draftManager;
    private ComposeMessageFragmentUtils composeMessageFragmentUtils;
    private SoundEffectPlayer soundEffectPlayer;

    private ActivityResultLauncher<Intent> wallpaperLauncher;
    private final ActivityResultLauncher<Intent> emojiReactionsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        rootInsetsDeferringCallback.setEnabled(true);
        keyboardAnimationInsetsCallback.setEnabled(true);
        if (actionMode != null) {
            actionMode.finish();
        }
    });
    private final ActivityResultLauncher<Intent> imageReplyLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_CANCELED) {
            logger.info("Canceled image reply");
            return;
        }

        Intent resultIntent = result.getData();
        if (resultIntent == null) {
            logger.error("Result intent must not be null");
            return;
        }

        @SuppressWarnings("rawtypes")
        MessageReceiver receiver = IntentDataUtil.getMessageReceiverFromIntent(getContext(), resultIntent);
        MediaItem mediaItem = resultIntent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (receiver == null) {
            logger.error("The receiver must not be null");
            return;
        }
        if (mediaItem == null) {
            logger.error("The media item must not be null");
            return;
        }

        messageService.sendMediaAsync(Collections.singletonList(mediaItem), Collections.singletonList(receiver));
    });

    private boolean listUpdateInProgress = false, isPaused = false;
    @NonNull
    private final List<AbstractMessageModel> unreadMessages = new ArrayList<>();
    @NonNull
    private final List<AbstractMessageModel> messageValues = new ArrayList<>();
    @NonNull
    private final List<AbstractMessageModel> selectedMessages = new ArrayList<>(1);

    private EmojiMarkupUtil emojiMarkupUtil;
    private EmojiPicker emojiPicker;
    private EmojiButton emojiButton;
    private SwipeRefreshLayout swipeRefreshLayout;
    private Integer currentPageReferenceId = null;
    private EmojiTextView actionBarTitleTextView;
    private TextView actionBarSubtitleTextView;
    private VerificationLevelImageView actionBarSubtitleImageView;
    private AvatarView actionBarAvatarView;
    private ImageView wallpaperView;
    private ActionBar actionBar;
    private TooltipPopup workTooltipPopup;
    private TooltipPopup availabilityStatusTooltipPopup;
    private EmojiReactionsPopup emojiReactionsPopup;
    private QuotePopup quotePopup;
    private OpenBallotNoticeView openBallotNoticeView;
    private ReportSpamView reportSpamView;
    private AvailabilityStatusContactBannerView availabilityStatusBannerView;
    // F1Whisper: pinned-message banner views + cycler state.
    // The cycler is keyed by the message's globally-unique uid (Telegram-style jump-by-id), NEVER by
    // text content or object identity: with several pins sharing the same body (e.g. two "Test"
    // messages) the banner must jump to the EXACT pinned message, and successive taps must cycle
    // through the distinct pinned messages in pin order. uid is stable across adapter rebuilds and a
    // deleted message simply drops out of the recollected set.
    private View pinnedBannerContainer;
    private TextView pinnedBannerPreview;
    private TextView pinnedBannerLabel;
    // uids of the currently-pinned messages, in pin (list) order; rebuilt on every banner refresh
    private final List<String> pinnedMessageUids = new ArrayList<>();
    // uid of the message currently shown in the banner (the next tap jumps to THIS one)
    @Nullable
    private String currentPinnedMessageUid = null;
    private boolean pinnedBannerDismissed = false; // user hit X; hide for this session only
    // transient jump-target highlight (Material 3): the row we briefly flash after a banner jump
    @NonNull
    private final Handler pinnedHighlightHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private Runnable pinnedHighlightClear = null;
    // F1Whisper: coalesce a burst of ballot-vote callbacks (a busy group checklist can fire many in a
    // row) into a single list re-bind ~200ms after the burst goes quiet, so the list does not jank or
    // disturb scroll on every vote. A single pending runnable is reused per burst.
    @NonNull
    private final Handler ballotRefreshHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private Runnable ballotRefreshRunnable = null;
    private static final long BALLOT_REFRESH_DEBOUNCE_MS = 200L;
    private ComposeMessageActivity activity;
    private View fragmentView;
    private FrameLayout coordinatorLayout;
    private BallotService ballotService;
    private DatabaseService databaseService;
    private LayoutInflater layoutInflater;
    private ListViewTouchSwipeListener listViewTouchSwipeListener;

    private GroupService groupService;
    private GroupModelRepository groupModelRepository;
    private GroupCallManager groupCallManager;
    private GroupFlowDispatcher groupFlowDispatcher;
    private boolean isGroupChat = false;
    private GroupModel groupModel;
    private Date listInitializedAt;
    private boolean isDistributionListChat = false;
    private DistributionListService distributionListService;
    private DistributionListModel distributionListModel;
    private MessagePlayerService messagePlayerService;
    private int listInstancePosition = AbsListView.INVALID_POSITION;
    private int listInstanceTop = 0;
    private String listInstanceReceiverId = null;
    private String conversationUid = null;
    private int unreadCount = 0, recentlyAddedCount = 0;
    private TextView searchCounter;
    private CircularProgressIndicator searchProgress;
    private ImageView searchNextButton, searchPreviousButton;
    private ViewGroup editMessageBubbleContainer;
    private View dimBackground;
    private ComposeView editMessageBubbleComposeView;

    private OngoingCallNoticeView ongoingCallNotice;
    private GroupCallObserver groupCallObserver;
    private ScrollButtonManager scrollButtonManager;
    private final EmojiHintPopupManager emojiHintPopupManager = new EmojiHintPopupManager(
        getAppContext(),
        () -> activity,
        () -> convListView
    );

    @SuppressLint("SimpleDateFormat")
    private final SimpleDateFormat dayFormatter = new SimpleDateFormat("yyyyMMdd");
    private ThumbnailCache<?> thumbnailCache = null;

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        logScreenVisibility(this, logger);
    }

    @Override
    public boolean isActionModeEnabled() {
        return actionMode != null;
    }

    @Override
    public boolean isInChoiceMode() {
        if (convListView == null) {
            return false;
        }
        int choiceMode = convListView.getChoiceMode();
        return choiceMode == CHOICE_MODE_MULTIPLE || choiceMode == CHOICE_MODE_MULTIPLE_MODAL;
    }

    @Override
    public boolean shouldHandleLinkClick() {
        return !isActionModeEnabled();
    }

    private final MessageService.MessageFilter nextMessageFilter = new MessageService.MessageFilter() {
        @Override
        public long getPageSize() {
            return MESSAGE_PAGE_SIZE;
        }

        @Override
        public Integer getPageReferenceId() {
            return getCurrentPageReferenceId();
        }

        @Override
        public boolean withStatusMessages() {
            return true;
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
            return false;
        }

        @Override
        public MessageType[] types() {
            return null;
        }

        @Override
        public int[] contentTypes() {
            return null;
        }

        @Override
        public int[] displayTags() {
            return null;
        }
    };

    // handler to remove dateview button after a certain time
    private final Handler dateViewHandler = new Handler();
    private final Runnable dateViewTask = () -> RuntimeUtil.runOnUiThread(() -> {
        if (dateView != null && dateView.getVisibility() == View.VISIBLE) {
            AnimationUtil.slideOutAnimation(dateView, false, 1f, null);
        }
    });

    // Listeners
    private final VoipCallEventListener voipCallEventListener = new VoipCallEventListener() {
        @Override
        public void onRinging(String peerIdentity) {
        }

        @Override
        public void onStarted(String peerIdentity, boolean outgoing) {
            logger.info("VoipCallEventListener onStarted"); // TODO(ANDR-2441): re-set to debug level
            updateVoipCallMenuItem(false);
            if (messagePlayerService != null) {
                messagePlayerService.pauseAll(SOURCE_VOIP);
            }
        }

        @Override
        public void onFinished(long callId, @NonNull String peerIdentity, boolean outgoing, int duration) {
            logger.info("VoipCallEventListener onFinished"); // TODO(ANDR-2441): re-set to debug level
            updateVoipCallMenuItem(true);
            hideOngoingVoipCallNotice();
        }

        @Override
        public void onRejected(long callId, String peerIdentity, boolean outgoing, byte reason) {
            logger.info("VoipCallEventListener onRejected"); // TODO(ANDR-2441): re-set to debug level
            updateVoipCallMenuItem(true);
            hideOngoingVoipCallNotice();
        }

        @Override
        public void onMissed(long callId, String peerIdentity, boolean accepted, @Nullable Date date) {
            logger.info("VoipCallEventListener onMissed"); // TODO(ANDR-2441): re-set to debug level
            updateVoipCallMenuItem(true);
            hideOngoingVoipCallNotice();
        }

        @Override
        public void onAborted(long callId, String peerIdentity) {
            logger.info("VoipCallEventListener onAborted"); // TODO(ANDR-2441): re-set to debug level
            updateVoipCallMenuItem(true);
            hideOngoingVoipCallNotice();
        }
    };

    private final MessageListener messageListener = new MessageListener() {
        @Override
        public void onNew(final AbstractMessageModel newMessage) {
            if (newMessage != null) {
                RuntimeUtil.runOnUiThread(() -> {
                    if (isAdded() && !isDetached() && !isRemoving()) {
                        if (newMessage.isOutbox()) {
                            if (addMessageToList(newMessage)) {
                                if (!newMessage.isStatusMessage() && newMessage.getType() != MessageType.VOIP_STATUS && newMessage.getType() != MessageType.GROUP_CALL_STATUS) {
                                    playSentSound();

                                    if (reportSpamView != null && reportSpamView.getVisibility() == View.VISIBLE) {
                                        reportSpamView.hide();
                                    }
                                }
                            }
                        } else {
                            if (addMessageToList(newMessage) && !isPaused) {
                                if (!newMessage.isStatusMessage() && newMessage.getType() != MessageType.VOIP_STATUS && newMessage.getType() != MessageType.GROUP_CALL_STATUS) {
                                    playReceivedSound();
                                }

                                if (convListView != null) {
                                    convListView.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (convListView.canScrollList(View.SCROLL_AXIS_VERTICAL)) {
                                                // list view is not fully scrolled to the bottom
                                                if (scrollButtonManager != null) {
                                                    scrollButtonManager.showButton(ScrollButtonManager.TYPE_DOWN, recentlyAddedCount);
                                                }
                                            }
                                        }
                                    });
                                }
                            }
                        }
                    }
                });
            }
        }

        @Override
        public void onModified(@NonNull final List<AbstractMessageModel> modifiedMessageModels) {

            final @NonNull List<AbstractMessageModel> safeModifiedMessageModels = modifiedMessageModels
                .stream()
                .filter(this::ensureMessageTypeIsCorrectForCurrentChat)
                .collect(Collectors.toUnmodifiableList());

            //replace model
            synchronized (messageValues) {
                for (final AbstractMessageModel modifiedMessageModel : safeModifiedMessageModels) {
                    if (modifiedMessageModel.getId() != 0) {
                        for (int n = 0; n < messageValues.size(); n++) {
                            AbstractMessageModel listModel = messageValues.get(n);
                            if (listModel != null && listModel.getId() == modifiedMessageModel.getId()) {
                                //if the changed message is different to the created
                                if (modifiedMessageModel != listModel) {
                                    //replace item
                                    messageValues.set(n, modifiedMessageModel);
                                }
                                break;
                            }
                        }
                    }
                }
            }
            RuntimeUtil.runOnUiThread(() -> {
                if (composeMessageAdapter != null) {
                    composeMessageAdapter.notifyItemsChanged(safeModifiedMessageModels);
                }
                if (safeModifiedMessageModels.size() == 1) {
                    final @Nullable AbstractMessageModel modifiedMessageModel = safeModifiedMessageModels.get(0);
                    if (modifiedMessageModel != null && modifiedMessageModel.isDeleted()) {
                        updateActionModeIfNecessary(modifiedMessageModel);
                        dismissEmojiReactionPopupIfMessageWasDeleted(modifiedMessageModel);
                    }
                }
                // F1Whisper: a message's displayTags may have changed (e.g., pin/unpin); keep banner in sync
                updatePinnedBanner();
            });
        }

        @Override
        public void onRemoved(final AbstractMessageModel removedMessageModel) {
            RuntimeUtil.runOnUiThread(() -> {
                if (composeMessageAdapter != null && removedMessageModel != null) {
                    composeMessageAdapter.remove(removedMessageModel);
                }
                // F1Whisper: a removed message may have been pinned; drop it from the banner set
                // (no crash, no stale jump) and re-render / hide the banner accordingly.
                updatePinnedBanner();
            });
        }

        @Override
        public void onRemoved(List<AbstractMessageModel> removedMessageModels) {
            RuntimeUtil.runOnUiThread(() -> {
                if (composeMessageAdapter != null && removedMessageModels != null) {
                    for (AbstractMessageModel removedMessageModel : removedMessageModels) {
                        composeMessageAdapter.remove(removedMessageModel);
                    }
                }
                // F1Whisper: same as the single-removal path — keep the pinned banner consistent.
                updatePinnedBanner();
            });
        }

        @Override
        public void onProgressChanged(AbstractMessageModel messageModel, int newProgress) {
            RuntimeUtil.runOnUiThread(() -> {
                if (composeMessageAdapter != null) {
                    composeMessageAdapter.notifyItemsChanged(Collections.singletonList(messageModel));
                }
            });
        }

        private boolean ensureMessageTypeIsCorrectForCurrentChat(final @NonNull AbstractMessageModel abstractMessageModel) {
            return abstractMessageModel instanceof FirstUnreadMessageModel
                || (isGroupChat && abstractMessageModel instanceof GroupMessageModel)
                || (isDistributionListChat && abstractMessageModel instanceof DistributionListMessageModel)
                || (abstractMessageModel instanceof MessageModel);
        }

        /**
         *  If we currently selected one message we have to consider dismissing the emoji-reactions-popup
         *  if the message was remote-deleted.
         *
         *  @param  deletedMessageModel The deleted message model
         */
        private void dismissEmojiReactionPopupIfMessageWasDeleted(final @NonNull AbstractMessageModel deletedMessageModel) {

            if (emojiReactionsPopup == null || !emojiReactionsPopup.isShowing() || selectedMessages.isEmpty()) {
                return;
            }

            // Determine if the newly remote-deleted message is currently selected
            final boolean deletedMessageIsCurrentlySelected = selectedMessages.stream().anyMatch(
                (selectedMessageModel -> selectedMessageModel.getId() == deletedMessageModel.getId())
            );

            if (deletedMessageIsCurrentlySelected) {
                emojiReactionsPopup.dismiss();
            }
        }

        /**
         *  If we currently have some messages selected (actionMode is visible) we have to consider updating the
         *  menu items it displays when a message was remote-deleted.
         */
        private void updateActionModeIfNecessary(final @NonNull AbstractMessageModel deletedMessageModel) {

            // It is only possible to remote-delete a single message at a time
            if (actionMode == null || selectedMessages.isEmpty()) {
                return;
            }

            // Determine if the newly remote-deleted message is currently selected
            final boolean deletedMessageIsCurrentlySelected = selectedMessages.stream().anyMatch(
                (selectedMessageModel -> selectedMessageModel.getId() == deletedMessageModel.getId())
            );

            if (deletedMessageIsCurrentlySelected) {
                actionMode.invalidate();
            }
        }
    };

    private final MessageDeletedForAllListener messageDeletedForAllListener = new MessageDeletedForAllListener() {
        @Override
        public void onDeletedForAll(@NonNull AbstractMessageModel message) {
            RuntimeUtil.runOnUiThread(() -> {
                if (composeMessageAdapter != null) {
                    composeMessageAdapter.notifyDataSetChanged();
                }
            });
        }
    };

    private final GroupListener groupListener = new GroupListener() {
        @Override
        public void onCreate(@NonNull GroupIdentity groupIdentity) {
            //do nothing
        }

        @Override
        public void onRename(@NonNull GroupIdentity groupIdentity) {
            final boolean changeAffectsCurrentGroup = updateLocalGroupModelAndReceiver(groupIdentity);
            if (!changeAffectsCurrentGroup) {
                return;
            }
            updateToolBarTitleInUIThread();
        }

        @Override
        public void onUpdatePhoto(@NonNull GroupIdentity groupIdentity) {
            final boolean changeAffectsCurrentGroup = updateLocalGroupModelAndReceiver(groupIdentity);
            if (!changeAffectsCurrentGroup) {
                return;
            }
            updateToolBarTitleInUIThread();
        }

        @Override
        public void onRemove(long groupDbId) {
            if (isGroupChat && ComposeMessageFragment.this.groupDbId != null && ComposeMessageFragment.this.groupDbId == groupDbId) {
                RuntimeUtil.runOnUiThread(() -> finishActivity());
            }
        }

        @Override
        public void onNewMember(@NonNull GroupIdentity groupIdentity, String identityNew) {
            final boolean changeAffectsCurrentGroup = updateLocalGroupModelAndReceiver(groupIdentity);
            if (!changeAffectsCurrentGroup) {
                return;
            }
            final boolean isMe = userService.isMe(identityNew);
            RuntimeUtil.runOnUiThread(() -> {
                updateToolbarTitle();
                // Update menus because the group may have been changed from a notes group to a regular group
                updateMenus();
                if (isMe) {
                    updateGroupCallObserverRegistration();
                    setupMessageTextClickListener();
                    updateActionModeIfVisible();
                }
            });
        }

        @Override
        public void onMemberLeave(@NonNull GroupIdentity groupIdentity, @NonNull String identityLeft) {
            final boolean changeAffectsCurrentGroup = updateLocalGroupModelAndReceiver(groupIdentity);
            if (!changeAffectsCurrentGroup) {
                return;
            }
            RuntimeUtil.runOnUiThread(() -> {
                updateToolbarTitle();
                // Update menus because the group may now be a notes group
                updateMenus();
            });
        }

        @Override
        public void onMemberKicked(@NonNull GroupIdentity groupIdentity, String identityKicked) {
            final boolean changeAffectsCurrentGroup = updateLocalGroupModelAndReceiver(groupIdentity);
            if (!changeAffectsCurrentGroup) {
                return;
            }
            final boolean isMe = userService.isMe(identityKicked);
            RuntimeUtil.runOnUiThread(
                () -> {
                    updateToolbarTitle();
                    // Update menus because the group may now be a notes group
                    updateMenus();
                    if (isMe) {
                        updateGroupCallObserverRegistration();
                        hideEmojiPopupIfShown();
                        hideEmojiPickerIfShown();
                        setupMessageTextClickListener();
                        updateActionModeIfVisible();
                        SingleToast.getInstance().showLongText(getString(R.string.you_are_not_a_member_of_this_group));
                    }
                }
            );
        }

        @Override
        public void onUpdate(@NonNull GroupIdentity groupIdentity) {
            final boolean changeAffectsCurrentGroup = updateLocalGroupModelAndReceiver(groupIdentity);
            if (!changeAffectsCurrentGroup) {
                return;
            }
            RuntimeUtil.runOnUiThread(
                () -> {
                    updateToolbarTitle();
                    updateGroupCallObserverRegistration();
                    updateMuteMenu();
                }
            );
        }

        @Override
        public void onLeave(@NonNull GroupIdentity groupIdentity) {
            GroupModel group = groupModelRepository.getByGroupIdentity(groupIdentity);
            if (isGroupChat && groupDbId != null && group != null && groupDbId == group.getDatabaseId()) {
                RuntimeUtil.runOnUiThread(() -> finishActivity());
            }
        }

        /**
         * Updates both the legacy {@code groupModel} and the {@code messageReceiver} variables of this fragment. We do not refresh the
         * data from the database if the passed {@code groupIdentityOfChangedGroup} is not the currently displayed group in this chat.
         *
         * @return Whether the changed group affects the currently opened conversation
         */
        private boolean updateLocalGroupModelAndReceiver(@NonNull GroupIdentity groupIdentityOfChangedGroup) {
            if (!(messageReceiver instanceof GroupMessageReceiver)) {
                // We are not even in a group conversation
                return false;
            }
            if (!(((GroupMessageReceiver) messageReceiver).getGroup().getGroupIdentity().equals(groupIdentityOfChangedGroup))) {
                // The group chat that is currently opened is not the one that changed
                return false;
            }
            final @Nullable GroupModel currentGroupModel = groupModelRepository.getByGroupIdentity(groupIdentityOfChangedGroup);
            if (currentGroupModel != null) {
                messageReceiver = groupService.createReceiver(groupModel);
                composeMessageAdapter.setMessageReceiver(messageReceiver);
            }
            return true;
        }

        private void updateGroupCallObserverRegistration() {
            // groupModel may be null if Fragment was re-configured with a new intent
            if (isGroupChat && groupModel != null && groupModel.isMember()) {
                registerGroupCallObserver();
            } else {
                // Remove ongoing group call notice if not a member of the group anymore
                updateOngoingCallNotice();
                removeGroupCallObserver();
            }
        }

        @UiThread
        private void updateActionModeIfVisible() {
            if (actionMode != null) {
                actionMode.invalidate();
            }
        }
    };

    private final ContactListener contactListener = new ContactListener() {
        @Override
        public void onModified(final @NonNull String identity) {
            if (!identity.equals(ComposeMessageFragment.this.identity)) {
                // Another contact was updated
                return;
            }
            final ContactModel modifiedContactModel = contactService.getByIdentity(identity);
            if (modifiedContactModel != null) {
                RuntimeUtil.runOnUiThread(() -> updateContactModelData(modifiedContactModel));
            }
        }

        @Override
        public void onAvatarChanged(final @NonNull String identity) {
            updateToolBarTitleInUIThread();
        }

        @Override
        public void onRemoved(@NonNull String identity) {
            if (contactModel != null && contactModel.getIdentity().equals(identity)) {
                // our contact has been removed. finish activity.
                RuntimeUtil.runOnUiThread(() -> finishActivity());
            }
        }
    };

    private final ContactTypingListener contactTypingListener = new ContactTypingListener() {
        @Override
        public void onContactIsTyping(final @NonNull ContactModel contactModel, final boolean isTyping) {
            RuntimeUtil.runOnUiThread(() -> {
                if (ComposeMessageFragment.this.contactModel != null && contactModel.getIdentity().equals(ComposeMessageFragment.this.contactModel.getIdentity())) {
                    contactTypingStateChanged(isTyping);
                }
            });
        }
    };

    // F1Whisper: refresh the group header "... is typing" line when group typing state changes
    private final GroupTypingListener groupTypingListener = new GroupTypingListener() {
        @Override
        public void onGroupTypingChanged(long groupDatabaseId, final @NonNull Set<String> typingIdentities) {
            RuntimeUtil.runOnUiThread(() -> {
                if (isGroupChat && groupModel != null
                    && ComposeMessageFragment.this.groupDbId != null
                    && ComposeMessageFragment.this.groupDbId == groupDatabaseId) {
                    updateToolbarTitle();
                }
            });
        }
    };

    private final ConversationListener conversationListener = new ConversationListener() {
        @Override
        public void onNew(@NonNull ConversationModel conversationModel) {
        }

        @Override
        public void onModified(@NonNull ConversationModel modifiedConversationModel) {
        }

        @Override
        public void onRemoved(@NonNull ConversationModel conversationModel) {
            boolean itsMyConversation = false;
            if (contactModel != null) {
                itsMyConversation = (conversationModel.getContact() != null
                    && TestUtil.compare(conversationModel.getContact().getIdentity(), contactModel.getIdentity()));
            } else if (distributionListModel != null) {
                itsMyConversation = conversationModel.getDistributionList() != null
                    && conversationModel.getDistributionList().getId() == distributionListModel.getId();
            } else if (groupModel != null) {
                itsMyConversation = conversationModel.getGroup() != null
                    && conversationModel.getGroup().getId() == groupModel.getDatabaseId();
            }

            if (itsMyConversation) {
                RuntimeUtil.runOnUiThread(() -> {
                    if (getActivity() != null) {
                        getActivity().finish();
                    }
                });
            }
        }

        @Override
        public void onModifiedAll() {
        }
    };

    private final MessagePlayerListener messagePlayerListener = new MessagePlayerListener() {
        @Override
        public void onAudioPlayEnded(AbstractMessageModel messageModel, ListenableFuture<MediaController> mediaControllerFuture) {
            // Play next audio message, if any
            RuntimeUtil.runOnUiThread(() -> {
                if (composeMessageAdapter != null) {
                    int index = composeMessageAdapter.getNextVoiceMessage(messageModel);
                    if (index != AbsListView.INVALID_POSITION) {
                        logger.info("Playing next audio message at index {}", index);
                        View view = composeMessageAdapter.getView(index, null, null);

                        ComposeMessageHolder holder = (ComposeMessageHolder) view.getTag();
                        if (holder.messagePlayer != null) {
                            holder.messagePlayer.open();
                            composeMessageAdapter.notifyDataSetChanged();
                        }
                    } else {
                        if (mediaControllerFuture != null) {
                            try {
                                MediaController mediaController = mediaControllerFuture.get();
                                if (mediaController != null) {
                                    mediaController.stop();
                                    mediaController.clearMediaItems();
                                }
                            } catch (Exception e) {
                                logger.error("Unable to clear MediaController", e);
                            }
                        }
                    }
                }
            });
        }
    };

    private final QRCodeScanListener qrCodeScanListener = new QRCodeScanListener() {
        @Override
        public void onScanCompleted(final String scanResult) {
            if (scanResult != null && !scanResult.isEmpty() && messageReceiver != null) {
                draftManager.set(messageReceiver.getUniqueIdString(), scanResult);
            }
        }
    };

    private final BallotListener ballotListener = new BallotListener() {
        @Override
        public void onClosed(BallotModel ballotModel) {
            openBallotNoticeView.update();
        }

        @Override
        public void onModified(BallotModel ballotModel) {
            // F1Whisper: a checklist STRUCTURE change (add / remove / reorder of items) arrives here --
            // both on the creator's own re-broadcast and on a receiver after mergeChecklistUpdate()
            // applies an incoming GroupPollSetup (0x52) modify. Re-render the open chat's checklist
            // bubble promptly via the SAME debounced, targeted rebind used for vote changes so the new
            // items/order show without waiting for a natural row recycle.
            refreshListForBallot(ballotModel);
        }

        @Override
        public void onCreated(BallotModel ballotModel) {
            try {
                if (ballotModel != null && userService.getIdentity().equals(ballotModel.getCreatorIdentity())) {
                    BallotUtil.openDefaultActivity(
                        getContext(),
                        getFragmentManager(),
                        ballotService.get(ballotModel.getId()),
                        messageReceiver
                    );
                }
            } catch (Exception e) {
                logger.error("Failed to open ballot", e);
            }
        }

        @Override
        public void onRemoved(BallotModel ballotModel) {
            openBallotNoticeView.update();
        }

        @Override
        public boolean handle(BallotModel ballotModel) {
            return true;
        }
    };

    /**
     * F1Whisper: refresh the message list when any ballot vote changes (my own optimistic toggle
     * being confirmed/reconciled, or a remote participant's check). This is what makes an interactive
     * checklist's voter-names and sink-order update without waiting for a natural row recycle, and is
     * the reconcile half of the optimistic checklist toggle in BallotChatAdapterDecorator.
     */
    private final BallotVoteListener ballotVoteListener = new BallotVoteListener() {
        @Override
        public void onSelfVote(BallotModel ballotModel) {
            refreshListForBallot(ballotModel);
        }

        @Override
        public void onVoteChanged(BallotModel ballotModel, String votingIdentity, boolean isFirstVote) {
            refreshListForBallot(ballotModel);
        }

        @Override
        public void onVoteRemoved(BallotModel ballotModel, String votingIdentity) {
            refreshListForBallot(ballotModel);
        }

        @Override
        public boolean handle(BallotModel ballotModel) {
            return true;
        }
    };

    /**
     * Coalesce ballot callbacks (vote changes AND checklist structure edits) into one re-bind
     * ~200ms after the burst quiesces (a busy group checklist fires many in quick succession), and
     * re-bind ONLY the affected ballot's rows (notifyItemsChanged) when they can be resolved, falling
     * back to a full notifyDataSetChanged only if the rows can't be found. Keeps the list from
     * janking / losing scroll on every vote or item edit. Shared by both the {@code ballotVoteListener}
     * (vote/sink changes) and the {@code ballotListener.onModified} (add/remove/reorder of items).
     */
    private void refreshListForBallot(@Nullable BallotModel ballotModel) {
        RuntimeUtil.runOnUiThread(() -> {
            if (ballotRefreshRunnable != null) {
                ballotRefreshHandler.removeCallbacks(ballotRefreshRunnable);
            }
            ballotRefreshRunnable = () -> rebindBallotRows(ballotModel);
            ballotRefreshHandler.postDelayed(ballotRefreshRunnable, BALLOT_REFRESH_DEBOUNCE_MS);
        });
    }

    /**
     * Re-bind the chat rows carrying a given ballot. Prefers the surgical
     * {@link ComposeMessageAdapter#notifyItemsChanged} on just that ballot's message models (resolved
     * via the message service); falls back to a full {@code notifyDataSetChanged} when the rows can't
     * be resolved or anything goes wrong. Must run on the UI thread.
     */
    @UiThread
    private void rebindBallotRows(@Nullable BallotModel ballotModel) {
        if (composeMessageAdapter == null) {
            return;
        }
        try {
            if (ballotModel != null && messageService != null) {
                List<AbstractMessageModel> ballotMessages = messageService.getMessageForBallot(ballotModel);
                if (ballotMessages != null && !ballotMessages.isEmpty()) {
                    composeMessageAdapter.notifyItemsChanged(ballotMessages);
                    return;
                }
            }
        } catch (Exception e) {
            logger.error("Could not resolve ballot rows for surgical refresh", e);
        }
        // Fallback: the ballot's rows could not be resolved -> full refresh.
        composeMessageAdapter.notifyDataSetChanged();
    }

    private final QuotePopup.QuotePopupListener quotePopupListener = new QuotePopup.QuotePopupListener() {
        @Override
        public void onHeightSet(int height) {
            if (historyParent != null) {
                historyParent.postDelayed(() ->
                    historyParent.setPadding(
                        historyParent.getPaddingLeft(),
                        historyParent.getPaddingTop(),
                        historyParent.getPaddingRight(),
                        height), 30);
            }
        }

        @Override
        public void onDismiss() {
            if (historyParent != null) {
                historyParent.postDelayed(() ->
                    historyParent.setPadding(
                        historyParent.getPaddingLeft(),
                        historyParent.getPaddingTop(),
                        historyParent.getPaddingRight(),
                        0), 70);
            }
        }

        @Override
        public void onPostVisibilityChange() {
            if (messageText != null) {
                updateSendButton(messageText.getText());
                updateCameraButton();
            }
        }
    };

    @Override
    public void onRefresh() {
        logger.debug("onRefresh");
        if (actionMode != null || searchActionMode != null || editMessageActionMode != null) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }
        viewModel.loadNextRecords(messageReceiver, nextMessageFilter);
    }

    @Override
    public void onAttach(@NonNull Activity activity) {
        ((FragmentActivity) activity).getLifecycle().addObserver(this);
        logger.debug("onAttach");

        super.onAttach(activity);

        setHasOptionsMenu(true);

        this.activity = (ComposeMessageActivity) activity;
        this.audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);

        if (bottomPanel != null) {
            bottomPanel.setVisibility(View.VISIBLE);
        }

        if (this.emojiPicker != null) {
            this.emojiPicker.init(this.activity, ThreemaApplication.requireServiceManager().getEmojiService(), true);
        }

        // resolution and layout may have changed after being attached to a new activity
        ConfigUtils.getPreferredThumbnailWidth(activity, true);
        ConfigUtils.getPreferredAudioMessageWidth(activity, true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        logger.info("onCreate");
        super.onCreate(savedInstanceState);

        ListenerManager.contactTypingListeners.add(this.contactTypingListener);
        ListenerManager.groupTypingListeners.add(this.groupTypingListener);
        ListenerManager.messageListeners.add(this.messageListener, true);
        ListenerManager.messageDeletedForAllListener.add(this.messageDeletedForAllListener);
        ListenerManager.groupListeners.add(this.groupListener);
        ListenerManager.contactListeners.add(this.contactListener);
        ListenerManager.conversationListeners.add(this.conversationListener);
        ListenerManager.messagePlayerListener.add(this.messagePlayerListener);
        ListenerManager.qrCodeScanListener.add(this.qrCodeScanListener);
        ListenerManager.ballotListeners.add(this.ballotListener);
        ListenerManager.ballotVoteListeners.add(this.ballotVoteListener);
        VoipListenerManager.callEventListener.add(this.voipCallEventListener);

        initializeMedia3Controller();
    }

    @Override
    @ExperimentalBadgeUtils
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        logger.info("onCreateView");

        if (!requiredInstances()) {
            finishActivity();
            return this.fragmentView;
        }

        this.layoutInflater = inflater;

        if (this.fragmentView == null) {
            // set font size
            activity.getTheme().applyStyle(preferenceService.getFontStyle(), true);
            this.fragmentView = inflater.inflate(R.layout.fragment_compose_message, container, false);

            this.coordinatorLayout = fragmentView.findViewById(R.id.compose_root);

            this.convListView = fragmentView.findViewById(R.id.history);
            ViewCompat.setNestedScrollingEnabled(this.convListView, true);
            this.convListView.setDivider(null);
            this.convListView.setClipToPadding(false);
            this.convListView.getLayoutTransition().disableTransitionType(LayoutTransition.CHANGING);
            this.convListView.getLayoutTransition().disableTransitionType(LayoutTransition.CHANGE_APPEARING);
            this.convListView.getLayoutTransition().disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);

            if (ConfigUtils.isTabletLayout()) {
                this.convListView.setPadding(0, 0, 0, 0);
            }

            this.historyParent = fragmentView.findViewById(R.id.history_parent);

            this.listViewTop = this.convListView.getPaddingTop();
            this.swipeRefreshLayout = fragmentView.findViewById(R.id.ptr_layout);
            this.swipeRefreshLayout.setOnRefreshListener(this);
            this.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(
                ConfigUtils.getColorFromAttribute(getContext(), R.attr.colorSurfaceContainer)
            );
            this.swipeRefreshLayout.setColorSchemeColors(
                ConfigUtils.getColorFromAttribute(getContext(), R.attr.colorPrimary)
            );
            this.swipeRefreshLayout.setSize(SwipeRefreshLayout.LARGE);
            this.messageText = fragmentView.findViewById(R.id.embedded_text_editor);

            this.sendEditMessageButton = this.fragmentView.findViewById(R.id.confirm_edit_button);
            this.sendButton = this.fragmentView.findViewById(R.id.send_button);
            this.attachButton = this.fragmentView.findViewById(R.id.attach_button);
            this.cameraButton = this.fragmentView.findViewById(R.id.camera_button);
            this.cameraButton.setOnClickListener(v -> {
                if (actionMode != null) {
                    actionMode.finish();
                }
                dismissQuotePopup();
                if (!validateSendingPermission()) {
                    return;
                }
                if (ConfigUtils.requestCameraPermissions(activity, this, PERMISSION_REQUEST_ATTACH_CAMERA)) {
                    attachCamera();
                }
            });
            updateCameraButton();

            this.emojiButton = this.fragmentView.findViewById(R.id.emoji_button);
            this.emojiButton.setOnClickListener(v -> {
                if (isGroupChatWhereUserIsNotMemberOf()) {
                    SingleToast.getInstance().showLongText(getString(R.string.you_are_not_a_member_of_this_group));
                } else {
                    showEmojiPicker();
                }
            });

            this.emojiMarkupUtil = EmojiMarkupUtil.getInstance();
            this.wallpaperView = this.fragmentView.findViewById(R.id.wallpaper_view);
            final MaterialButton quickscrollUpView = this.fragmentView.findViewById(R.id.quickscroll_top);
            final MaterialButton quickscrollDownView = this.fragmentView.findViewById(R.id.quickscroll_bottom);
            final FrameLayout quickscrollDownContainer = this.fragmentView.findViewById(R.id.quickscroll_bottom_container);
            this.dateView = this.fragmentView.findViewById(R.id.date_separator_container);
            this.dateTextView = this.fragmentView.findViewById(R.id.text_view);

            // F1Whisper: scheduled-messages indicator chip
            this.scheduledMessagesBar = this.fragmentView.findViewById(R.id.scheduled_messages_bar);
            this.scheduledMessagesCount = this.fragmentView.findViewById(R.id.scheduled_messages_count);
            if (this.scheduledMessagesBar != null) {
                this.scheduledMessagesBar.setOnClickListener(v -> showScheduledMessagesDialog());
            }

            this.editMessageBubbleContainer = this.fragmentView.findViewById(R.id.edit_message_bubble_container);
            this.editMessageBubbleComposeView = this.fragmentView.findViewById(R.id.edit_message_bubble_compose_view);
            this.dimBackground = this.fragmentView.findViewById(R.id.dim_background);

            this.bottomPanel = this.fragmentView.findViewById(R.id.bottom_panel);
            // F1Whisper: Signal-style link-preview chip above the input (sender-only fetch).
            final View linkPreviewChip = this.fragmentView.findViewById(R.id.compose_link_preview_chip);
            if (linkPreviewChip != null && this.preferenceService != null) {
                this.linkPreviewController = new ComposeLinkPreviewController(linkPreviewChip, this.preferenceService);
            }
            this.openBallotNoticeView = this.fragmentView.findViewById(R.id.open_ballots_layout);
            this.reportSpamView = this.fragmentView.findViewById(R.id.report_spam_layout);
            this.reportSpamView.setListener(this);

            this.availabilityStatusBannerView = this.fragmentView.findViewById(R.id.availability_status_banner_view);

            // F1Whisper: wire pinned-message banner
            this.pinnedBannerContainer = this.fragmentView.findViewById(R.id.pinned_banner_container);
            this.pinnedBannerPreview = this.fragmentView.findViewById(R.id.pinned_banner_preview);
            this.pinnedBannerLabel = this.fragmentView.findViewById(R.id.pinned_banner_label);
            final View pinnedTapArea = this.fragmentView.findViewById(R.id.pinned_banner_tap_area);
            final View pinnedDismiss = this.fragmentView.findViewById(R.id.pinned_banner_dismiss);
            if (pinnedTapArea != null) {
                pinnedTapArea.setOnClickListener(v -> cyclePinnedMessageBanner());
            }
            if (pinnedDismiss != null) {
                pinnedDismiss.setOnClickListener(v -> {
                    pinnedBannerDismissed = true;
                    if (pinnedBannerContainer != null) {
                        pinnedBannerContainer.setVisibility(View.GONE);
                    }
                });
            }

            quickscrollDownContainer.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    quickscrollDownContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                    Context context = getContext();
                    if (context != null) {
                        final BadgeDrawable quickscrollDownBadge = BadgeDrawable.createFromResource(context, R.xml.badge_compose);
                        quickscrollDownBadge.setHorizontalOffset(getResources().getDimensionPixelOffset(R.dimen.quickscroll_badge_offset));
                        quickscrollDownBadge.setVerticalOffset(getResources().getDimensionPixelOffset(R.dimen.quickscroll_badge_offset));
                        BadgeUtils.attachBadgeDrawable(quickscrollDownBadge, quickscrollDownView, quickscrollDownContainer);
                        quickscrollDownBadge.setVisible(false);

                        scrollButtonManager = new ScrollButtonManager(quickscrollUpView, quickscrollDownContainer, quickscrollDownBadge);
                    }
                }
            });

            quickscrollDownView.setOnClickListener(v -> {
                removeDateView();
                if (scrollButtonManager != null) {
                    scrollButtonManager.hideAllButtons();
                }
                scrollList(Integer.MAX_VALUE);

            });
            quickscrollUpView.setOnClickListener(v -> {
                removeDateView();
                if (scrollButtonManager != null) {
                    scrollButtonManager.hideAllButtons();
                }
                scrollList(0);
            });

            textInputLayout = fragmentView.findViewById(R.id.textinputlayout_compose);
            conversationParent = fragmentView.findViewById(R.id.conversation_parent);

            this.getValuesFromBundle(savedInstanceState);
            this.handleIntent(activity.getIntent());
            this.setupListeners();
        }

        if (!ConfigUtils.isDefaultEmojiStyle()) {
            // remove emoji button
            this.emojiButton.setVisibility(View.GONE);
            // Emoji button lives at the layout START; use the relative padding API so the reduced
            // start inset mirrors correctly under RTL instead of being applied to the physical left.
            this.messageText.setPaddingRelative(getResources().getDimensionPixelSize(R.dimen.no_emoji_button_padding_left), this.messageText.getPaddingTop(), this.messageText.getPaddingEnd(), this.messageText.getPaddingBottom());
        } else {
            try {
                final EmojiPicker.EmojiKeyListener emojiKeyListener = new EmojiPicker.EmojiKeyListener() {
                    @Override
                    public void onBackspaceClick() {
                        messageText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    }

                    @Override
                    public void onEmojiClick(String emojiCodeString) {
                        RuntimeUtil.runOnUiThread(() -> messageText.addEmoji(emojiCodeString));
                    }

                    @Override
                    public void onShowPicker() {
                        showEmojiPicker();
                    }
                };
                this.emojiPicker = (EmojiPicker) fragmentView.findViewById(R.id.emoji_picker);
                this.emojiPicker.init(activity, ThreemaApplication.requireServiceManager().getEmojiService(), true);
                this.emojiButton.attach(this.emojiPicker);
                this.emojiPicker.setEmojiKeyListener(emojiKeyListener);
                this.emojiPicker.addEmojiPickerListener(this);
            } catch (Exception e) {
                logger.error("Exception", e);
                finishActivity();
            }
        }

        emojiHintPopupManager.showOrDismissIfNecessary();

        return this.fragmentView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = ViewModelCompat.getViewModel(this, ComposeMessageViewModel.class);
        setObservers();
    }

    private void setObservers() {
        viewModel.getEvents().observe(getViewLifecycleOwner(), this::handleViewModelEvent);
        viewModel.getContactAvailabilityStatus().observe(getViewLifecycleOwner(), this::onContactAvailabilityStatusChanged);
    }

    private void handleViewModelEvent(@NonNull ComposeMessageEvent event) {
        if (event instanceof ComposeMessageEvent.NextRecordsLoaded) {
            onNextRecordsLoadedEvent((ComposeMessageEvent.NextRecordsLoaded) event);
        }
    }

    private void onContactAvailabilityStatusChanged(@NonNull AvailabilityStatus availabilityStatus) {
        if (!BuildConfig.AVAILABILITY_STATUS_ENABLED) {
            return;
        }
        // Toolbar avatar icon
        if (actionBarAvatarView != null) {
            actionBarAvatarView.setAvailabilityStatusBadgeState(
                availabilityStatus instanceof AvailabilityStatus.Set
                    ? (AvailabilityStatus.Set) availabilityStatus
                    : null
            );
        }
        // Banner
        if (availabilityStatusBannerView != null) {
            final @Nullable AvailabilityStatus.Set availabilityStatusSet = availabilityStatus instanceof AvailabilityStatus.Set
                ? (AvailabilityStatus.Set) availabilityStatus
                : null;
            final @Nullable Function0<Unit> onClickOnOverflowListener = availabilityStatusSet != null
                ? () -> onClickViewFullAvailabilityStatus(availabilityStatusSet)
                : null;
            final int bannerVisibility = availabilityStatusSet != null
                ? View.VISIBLE
                : View.GONE;
            availabilityStatusBannerView.setVisibility(bannerVisibility);
            availabilityStatusBannerView.setState(
                availabilityStatusSet,
                preferenceService.getEmojiStyle(),
                onClickOnOverflowListener
            );

            if (availabilityStatusSet != null) {
                availabilityStatusTooltipPopup = AvailabilityStatusTooltipPopupManager.showInConversation(requireActivity(), availabilityStatusBannerView);
                if (availabilityStatusTooltipPopup != null) {
                    emojiHintPopupManager.setSuppressed(true);
                }
            }
        }
    }

    private Unit onClickViewFullAvailabilityStatus(@NonNull AvailabilityStatus.Set availabilityStatusSet) {
        ViewFullAvailabilityStatusBottomSheetDialog
            .newInstance(
                availabilityStatusSet
            )
            .show(
                getParentFragmentManager(),
                "view-full-availability-status-bottom-sheet"
            );
        return Unit.INSTANCE;
    }

    private void onNextRecordsLoadedEvent(@NonNull ComposeMessageEvent.NextRecordsLoaded nextRecodsLoadedEvent) {
        valuesLoaded(nextRecodsLoadedEvent.messageModels);
        if (composeMessageAdapter != null) {
            int numberOfInsertedRecords = insertToList(nextRecodsLoadedEvent.messageModels, false, true, true);
            if (numberOfInsertedRecords > 0) {
                convListView.setSelection(convListView.getSelectedItemPosition() + numberOfInsertedRecords + 1);
            }
        }
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
            swipeRefreshLayout.setEnabled(nextRecodsLoadedEvent.hasMoreRecords);
        }
        // F1Whisper: a batch of older messages may contain pinned ones; refresh the banner
        updatePinnedBanner();
    }

    @AnyThread
    private void initOngoingCallState() {
        ongoingCallNotice = fragmentView.findViewById(R.id.ongoing_call_notice);
        if (ongoingCallNotice != null) {
            if (groupModel != null && groupModel.isMember()) {
                registerGroupCallObserver();
            } else {
                updateOngoingCallNotice();
            }
        }
    }

    @AnyThread
    private void updateOngoingCallNotice() {
        boolean hasRunningOOCall = VoipCallService.isRunning()
            && contactModel != null
            && contactModel.getIdentity().equals(VoipCallService.getOtherPartysIdentity());

        GroupCallDescription chosenCall = getChosenCall();
        boolean hasRunningGroupCall = chosenCall != null;
        boolean hasJoinedGroupCall = hasRunningGroupCall
            && groupCallManager.isJoinedCall(chosenCall);

        if (hasRunningOOCall && hasJoinedGroupCall) {
            logger.warn("Invalid state: joined 1:1 AND group call, not showing call notice");
            updateVoipCallMenuItem(true);
            hideOngoingCallNotice();
        } else if (hasRunningOOCall) {
            showOngoingVoipCallNotice();
        } else if (hasRunningGroupCall) {
            OngoingCallNoticeMode mode = hasJoinedGroupCall
                ? OngoingCallNoticeMode.MODE_GROUP_CALL_JOINED
                : OngoingCallNoticeMode.MODE_GROUP_CALL_RUNNING;
            showOngoingGroupCallNotice(mode, chosenCall);
        } else {
            updateVoipCallMenuItem(true);
            hideOngoingCallNotice();
        }
    }

    @Nullable
    private GroupCallDescription getChosenCall() {
        return ConfigUtils.isGroupCallsEnabled() && groupModel != null && groupCallManager != null
            ? groupCallManager.getCurrentChosenCall(groupModel)
            : null;
    }

    @AnyThread
    private void showOngoingVoipCallNotice() {
        logger.info("Show ongoing voip call notice (notice set: {})", ongoingCallNotice != null); // TODO(ANDR-2441): remove eventually
        if (ongoingCallNotice != null) {
            ongoingCallNotice.showVoip();
        }
    }

    @AnyThread
    private void hideOngoingVoipCallNotice() {
        logger.info("Hide ongoing voip call notice (notice set: {})", ongoingCallNotice != null); // TODO(ANDR-2441): remove eventually
        if (ongoingCallNotice != null) {
            ongoingCallNotice.hideVoip();
        }
    }

    @AnyThread
    private void hideOngoingCallNotice() {
        logger.info("Hide ongoing call notice (notice set: {})", ongoingCallNotice != null);  // TODO(ANDR-2441): remove eventually
        if (ongoingCallNotice != null) {
            ongoingCallNotice.hide();
        }
    }

    @AnyThread
    private void registerGroupCallObserver() {
        removeGroupCallObserver();
        if (groupModel != null && groupCallManager != null) {
            groupCallObserver = call -> updateOngoingCallNotice();
            logger.info("Add group call observer for group {}", groupModel.getDatabaseId());
            groupCallManager.addGroupCallObserver(groupModel, groupCallObserver);
        }
    }

    @AnyThread
    private void showOngoingGroupCallNotice(OngoingCallNoticeMode mode, @NonNull GroupCallDescription call) {
        if (ongoingCallNotice != null) {
            ongoingCallNotice.showGroupCall(call, mode);
            updateVoipCallMenuItem(false);
        }
    }

    private boolean isEmojiPickerShown() {
        return emojiPicker != null && emojiPicker.isShown();
    }

    @UiThread
    private void hideEmojiPickerIfShown() {
        if (isEmojiPickerShown()) {
            emojiPicker.hide();
            addAllInsetsToInsetPaddingContainer();
        }
    }

    @UiThread
    private void hideEmojiPopupIfShown() {
        if (emojiReactionsPopup != null && emojiReactionsPopup.isShowing()) {
            emojiReactionsPopup.dismiss();
        }
    }

    private void showEmojiPicker() {
        logger.debug("Emoji button clicked");

        if (activity.isSoftKeyboardOpen() && !isEmojiPickerShown()) {
            if (rootInsetsDeferringCallback != null && keyboardAnimationInsetsCallback != null) {
                rootInsetsDeferringCallback.openingEmojiPicker = true;
                keyboardAnimationInsetsCallback.skipNextAnimation = true;
            }

            logger.debug("Show emoji picker after keyboard close");
            activity.runOnSoftKeyboardClose(() -> {
                if (emojiPicker != null) {
                    emojiPicker.show(activity.loadStoredSoftKeyboardHeight());
                    removeVerticalInsetsFromInsetPaddingContainer();
                }
            });

            messageText.post(() -> EditTextUtil.hideSoftKeyboard(messageText));
        } else {
            if (emojiPicker != null) {
                if (emojiPicker.isShown()) {
                    logger.debug("Emoji picker currently shown. Closing.");
                    if (ConfigUtils.isLandscape(activity) && !ConfigUtils.isTabletLayout()) {
                        emojiPicker.hide();
                        addAllInsetsToInsetPaddingContainer();
                    } else {
                        if (rootInsetsDeferringCallback != null && keyboardAnimationInsetsCallback != null) {
                            rootInsetsDeferringCallback.openingEmojiPicker = true;
                            keyboardAnimationInsetsCallback.skipNextAnimation = true;
                        }
                        activity.openSoftKeyboard(messageText);
                    }
                } else {
                    logger.debug("Show emoji picker immediately");
                    emojiPicker.show(activity.loadStoredSoftKeyboardHeight());
                    removeVerticalInsetsFromInsetPaddingContainer();
                }
            }
        }
    }

    /**
     * If the emoji picker is shown, we have to make sure that no vertical padding insets are applied.
     * The emoji picker has to handle the vertical insets internally.
     * <p>
     * This will remove any vertical padding of {@code inset_padding_container} while still respecting the horizontal insets.
     */
    private void removeVerticalInsetsFromInsetPaddingContainer() {
        final Insets insets = ActivityExtensionsKt.getCurrentInsets(
            activity,
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
        );
        final FrameLayout insetPaddingContainer = getView().findViewById(R.id.inset_padding_container);
        insetPaddingContainer.setPadding(insets.left, 0, insets.right, 0);
    }

    private void addAllInsetsToInsetPaddingContainer() {
        final @Nullable View fragmentView = getView();
        if (fragmentView == null) {
            return;
        }
        final Insets insets = ActivityExtensionsKt.getCurrentInsets(
            activity,
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
        );
        final @NonNull FrameLayout insetPaddingContainer = fragmentView.findViewById(R.id.inset_padding_container);
        insetPaddingContainer.setPadding(insets.left, 0, insets.right, insets.bottom);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        logger.debug("onActivityCreated");

        super.onActivityCreated(savedInstanceState);
        /*
         * This callback tells the fragment when it is fully associated with the new activity instance.
         * This is called after onCreateView(LayoutInflater, ViewGroup, Bundle) and before onViewStateRestored(Bundle).
         */
        if (preferenceService == null) {
            return;
        }

        final String tag = "compose-message-fragment";

        // Set inset listener that will effectively apply the final view paddings
        rootInsetsDeferringCallback = new RootViewDeferringInsetsCallback(
            tag,
            emojiPicker,
            activity,
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
        );
        final FrameLayout insetPaddingContainer = getView().findViewById(R.id.inset_padding_container);
        ViewCompat.setWindowInsetsAnimationCallback(insetPaddingContainer, rootInsetsDeferringCallback);
        ViewCompat.setOnApplyWindowInsetsListener(insetPaddingContainer, rootInsetsDeferringCallback);

        // Set inset listener to temporarily push up/down the chat views while an IME animation takes place
        keyboardAnimationInsetsCallback = new TranslateDeferringInsetsAnimationCallback(
            tag,
            conversationParent,
            emojiPicker,
            WindowInsetsCompat.Type.systemBars(),
            WindowInsetsCompat.Type.ime(),
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        );
        ViewCompat.setWindowInsetsAnimationCallback(conversationParent, keyboardAnimationInsetsCallback);

        activity.addOnSoftKeyboardChangedListener(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        logger.info("onResume"); // TODO(ANDR-2441): Re-set to debug level

        if (messageReceiver == null) {
            return;
        }

        this.notificationService.setVisibleReceiver(this.messageReceiver);

        isPaused = false;

        // F1Whisper: on chat open, sweep any disappearing messages that expired while the chat was
        // backgrounded (covers the case the alarm missed), then re-arm. Worker-thread; never throws.
        new Thread(() -> DisappearingMessageService.getInstance().purgeOverdueAndRearm(),
            "DisappearingChatOpenSweep").start();

        // mark all unread messages as read
        if (!unreadMessages.isEmpty()) {
            logger.debug("markAllRead");
            new MarkAsReadRoutine(conversationService, messageService, notificationService)
                .runAsync(
                    unreadMessages,
                    messageReceiver,
                    /* onSuccess */ () -> {
                        unreadMessages.clear();
                        return Unit.INSTANCE;
                    }
                );
        }

        // update menus
        updateMuteMenu();
        if (isGroupChat) {
            updateGroupCallMenuItem();
        } else {
            updateBlockMenu();
        }

        // start media players again
        this.messagePlayerService.resumeAll(getActivity(), this.messageReceiver, SOURCE_LIFECYCLE);

        // make sure to remark the active chat
        if (ConfigUtils.isTabletLayout() && conversationUid != null) {
            ListenerManager.chatListeners.handle(listener -> listener.onChatOpened(conversationUid));
        }

        // restore scroll position after orientation change
        if (getActivity() != null) {
            Intent intent = getActivity().getIntent();
            if (intent != null && !intent.hasExtra(EXTRA_API_MESSAGE_ID) && !intent.hasExtra(EXTRA_SEARCH_QUERY)) {
                convListView.post(() -> {
                    if (listInstancePosition != AbsListView.INVALID_POSITION &&
                        messageReceiver != null &&
                        messageReceiver.getUniqueIdString().equals(listInstanceReceiverId)) {
                        logger.debug("restoring position {}", listInstancePosition);
                        convListView.setSelectionFromTop(listInstancePosition, listInstanceTop);
                        if (activity != null && convListView.getCheckedItemCount() > 0 && actionMode == null) {
                            SparseBooleanArray itemPositions = convListView.getCheckedItemPositions();
                            for (int i = 0; i < itemPositions.size(); i++) {
                                selectedMessages.add(composeMessageAdapter.getItem(itemPositions.keyAt(i)));
                            }
                            actionMode = activity.startSupportActionMode(new ComposeMessageAction(this.longClickItem));
                        }
                    } else {
                        jumpToFirstUnreadMessage();
                    }
                    // make sure it's not restored twice
                    listInstancePosition = AbsListView.INVALID_POSITION;
                    listInstanceReceiverId = null;
                });
            }
        }

        updateOngoingCallNotice();

        viewModel.onResume(messageReceiver);

        // F1Whisper: refresh the scheduled-messages indicator for this conversation
        updateScheduledMessagesBar();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        logger.info("onPause");
        isPaused = true;

        onEmojiPickerClose();

        if (this.notificationService != null) {
            this.notificationService.setVisibleReceiver(null);
        }

        // save unfinished text
        if (editMessageActionMode == null) {
            updateMessageDraft();
        }

        if (this.typingIndicatorTextWatcher != null) {
            this.typingIndicatorTextWatcher.stopSending();
        }

        // F1Whisper: spoilers (media AND text) reveal strictly per chat-visit. Forget every revealed
        // spoiler when leaving the conversation so re-entering shows them obscured again (tap-to-reveal
        // each time). Bubbles re-bind on chat re-entry seeding each span's revealed flag from these
        // stores, so clearing them is sufficient; no explicit adapter refresh is needed here.
        MediaSpoilerUtil.clearRevealed();
        ch.threema.app.emojis.SpoilerRevealState.getInstance().clear();

        preserveListInstanceValues();
    }

    @Override
    public void onStop() {
        logger.info("onStop");

        if (this.typingIndicatorTextWatcher != null) {
            this.typingIndicatorTextWatcher.stopSending();
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            // close keyboard to prevent layout corruption after unlocking phone
            if (this.messageText != null) {
                EditTextUtil.hideSoftKeyboard(this.messageText);
            }
        }
        super.onStop();
    }

    @Override
    public void onDetach() {
        logger.debug("onDetach");

        hideEmojiPickerIfShown();
        dismissMentionPopup();
        dismissQuotePopup();

        this.activity = null;

        super.onDetach();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        removeGroupCallObserver();
        if (linkPreviewController != null) {
            linkPreviewController.destroy();
            linkPreviewController = null;
        }
        // F1Whisper: cancel any pending pinned-jump highlight cleanup to avoid touching a torn-down view.
        if (pinnedHighlightHandler != null && pinnedHighlightClear != null) {
            pinnedHighlightHandler.removeCallbacks(pinnedHighlightClear);
            pinnedHighlightClear = null;
        }
    }

    @Override
    public void onDestroy() {
        logger.debug("onDestroy");

        try {
            ListenerManager.contactTypingListeners.remove(this.contactTypingListener);
            ListenerManager.groupTypingListeners.remove(this.groupTypingListener);
            ListenerManager.groupListeners.remove(this.groupListener);
            ListenerManager.messageListeners.remove(this.messageListener);
            ListenerManager.messageDeletedForAllListener.remove(this.messageDeletedForAllListener);
            ListenerManager.contactListeners.remove(this.contactListener);
            ListenerManager.conversationListeners.remove(this.conversationListener);
            ListenerManager.messagePlayerListener.remove(this.messagePlayerListener);
            ListenerManager.qrCodeScanListener.remove(this.qrCodeScanListener);
            ListenerManager.ballotListeners.remove(this.ballotListener);
            ListenerManager.ballotVoteListeners.remove(this.ballotVoteListener);
            // F1Whisper: drop any pending coalesced ballot re-bind so it can't fire on a torn-down view.
            if (ballotRefreshRunnable != null) {
                ballotRefreshHandler.removeCallbacks(ballotRefreshRunnable);
                ballotRefreshRunnable = null;
            }
            VoipListenerManager.callEventListener.remove(this.voipCallEventListener);

            if (scrollButtonManager != null) {
                scrollButtonManager.hideAllButtons();
            }
            emojiHintPopupManager.onDestroy();

            dismissTooltipPopup(workTooltipPopup, true);
            workTooltipPopup = null;
            if (availabilityStatusTooltipPopup != null) {
                availabilityStatusTooltipPopup.dismiss(true);
                availabilityStatusTooltipPopup = null;
            }

            dismissMentionPopup();
            dismissQuotePopup();

            if (this.emojiButton != null) {
                this.emojiButton.detach(this.emojiPicker);
            }

            if (this.emojiPicker != null) {
                this.emojiPicker.removeEmojiPickerListener(this);
            }

            if (!requiredInstances()) {
                super.onDestroy();
                return;
            }

            if (this.messagePlayerService != null) {
                this.messagePlayerService.release();
            }

            if (this.thumbnailCache != null) {
                this.thumbnailCache.flush();
            }

            if (this.messageText != null) {
                //remove typing change listener
                if (this.typingIndicatorTextWatcher != null) {
                    this.messageText.removeTextChangedListener(this.typingIndicatorTextWatcher);
                }
                if (draftUpdateTextWatcher != null) {
                    messageText.removeTextChangedListener(draftUpdateTextWatcher);
                }
                // http://stackoverflow.com/questions/18348049/android-edittext-memory-leak
                this.messageText.setText(null);
            }

            if (soundEffectPlayer != null) {
                soundEffectPlayer.destroy();
            }

            // remove wallpaper
            if (wallpaperView != null) {
                wallpaperView.setImageBitmap(null);
            }

            removeIsTypingFooter();
            this.isTypingView = null;

            //clear all records to remove all references
            if (this.composeMessageAdapter != null) {
                this.composeMessageAdapter.clear();
                this.composeMessageAdapter = null;
            }

            if (draftUpdateTextWatcher != null) {
                draftUpdateTextWatcher.stop();
            }

            releaseMedia3Controller();
        } catch (Exception x) {
            logger.error("Exception", x);
        }

        composeMessageFragmentUtils = null;

        super.onDestroy();
    }

    private void removeDateView() {
        if (dateView != null && dateView.getVisibility() == View.VISIBLE) {
            AnimationUtil.slideOutAnimation(dateView, false, 1f, null);
        }

        if (actionMode != null) {
            actionMode.finish();
        }
    }

    private void setupListeners() {
        setupConversationListScrollListener();
        setupConversationListSwipeListener();
        setupSendButtonClickListener();
        setupAttachButtonClickListener();
        setupMessageTextListeners();
    }

    private void setupConversationListScrollListener() {
        // Setting this scroll listener is required to ensure that during ListView scrolling,
        // we don't look for swipes or pulldowns
        this.convListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView absListView, int scrollState) {
                if (listViewTouchSwipeListener != null) {
                    listViewTouchSwipeListener.setEnabled(scrollState != AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                }

                if (!absListView.canScrollList(View.SCROLL_AXIS_VERTICAL)) {
                    if (scrollButtonManager != null) {
                        scrollButtonManager.hideButton(ScrollButtonManager.TYPE_DOWN);
                    }
                }

                if (!absListView.canScrollList(-View.SCROLL_AXIS_VERTICAL)) {
                    if (scrollButtonManager != null) {
                        scrollButtonManager.hideButton(ScrollButtonManager.TYPE_UP);
                    }
                }
                emojiHintPopupManager.setScrolling(
                    scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                );
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (view != null && view.getChildCount() > 0) {
                    View itemView = view.getChildAt(0);

                    boolean onTop = firstVisibleItem == 0 && itemView.getTop() == listViewTop;
                    swipeRefreshLayout.setEnabled(onTop);

                    if (firstVisibleItem != lastFirstVisibleItem) {
                        if (lastFirstVisibleItem < firstVisibleItem) {
                            // scrolling down
                            if (view.canScrollList(View.SCROLL_AXIS_VERTICAL)) {
                                if (scrollButtonManager != null) {
                                    scrollButtonManager.showButton(ScrollButtonManager.TYPE_DOWN, 0);
                                }
                            } else {
                                if (scrollButtonManager != null) {
                                    scrollButtonManager.hideButton(ScrollButtonManager.TYPE_DOWN);
                                }
                                recentlyAddedCount = 0;
                            }
                        } else {
                            // scrolling up
                            if (view.canScrollList(-View.SCROLL_AXIS_VERTICAL)) {
                                if (scrollButtonManager != null) {
                                    scrollButtonManager.showButton(ScrollButtonManager.TYPE_UP, 0);
                                }
                            } else {
                                if (scrollButtonManager != null) {
                                    scrollButtonManager.hideButton(ScrollButtonManager.TYPE_UP);
                                }
                            }
                        }

                        lastFirstVisibleItem = firstVisibleItem;

                        if (dateView.getVisibility() != View.VISIBLE && composeMessageAdapter != null && composeMessageAdapter.getCount() > 0) {
                            AnimationUtil.slideInAnimation(dateView, false, 200);
                        }

                        dateViewHandler.removeCallbacks(dateViewTask);
                        dateViewHandler.postDelayed(dateViewTask, SCROLLBUTTON_VIEW_TIMEOUT);

                        if (composeMessageAdapter != null) {
                            AbstractMessageModel abstractMessageModel = composeMessageAdapter.getItem(firstVisibleItem);
                            if (abstractMessageModel != null) {
                                final Date createdAt = abstractMessageModel.getCreatedAt();
                                if (createdAt != null) {
                                    final String text = LocaleUtil.formatDateRelative(createdAt.getTime());
                                    dateTextView.setText(text);
                                    dateView.post(() -> dateTextView.setText(text));
                                }
                            }
                        }
                    }
                } else {
                    swipeRefreshLayout.setEnabled(false);
                }
            }
        });
    }

    private void setupConversationListSwipeListener() {
        listViewTouchSwipeListener = new ListViewTouchSwipeListener(
            this.convListView,
            new ListViewTouchSwipeListener.DismissCallbacks() {
                @Override
                public boolean canSwipe(int position) {
                    if (actionMode != null) {
                        return false;
                    }

                    if (messageReceiver == null || !messageReceiver.validateSendingPermission().isValid()) {
                        return false;
                    }

                    int viewType = composeMessageAdapter.getItemViewType(position);

                    if (viewType == ComposeMessageAdapter.TYPE_STATUS ||
                        viewType == ComposeMessageAdapter.TYPE_FIRST_UNREAD ||
                        viewType == ComposeMessageAdapter.TYPE_DATE_SEPARATOR ||
                        viewType == ComposeMessageAdapter.TYPE_GROUP_CALL_STATUS) {
                        return false;
                    }

                    AbstractMessageModel messageModel = composeMessageAdapter.getItem(position);

                    if (messageModel == null) {
                        return false;
                    }

                    return QuoteUtil.isQuoteable(messageModel);
                }

                @Override
                public void onSwiped(int position) {
                    if (composeMessageAdapter == null) {
                        return;
                    }

                    AbstractMessageModel abstractMessageModel = composeMessageAdapter.getItem(position);
                    if (preferenceService.isInAppVibrate()) {
                        if (isAdded() && !isDetached() && activity != null) {
                            Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                            if (vibrator != null && vibrator.hasVibrator()) {
                                vibrator.vibrate(100);
                            }
                        }
                    }
                    if (abstractMessageModel != null) {
                        if (isQuotePopupShown() && abstractMessageModel.equals(quotePopup.getQuoteInfo().getMessageModel())) {
                            dismissQuotePopup();
                        } else {
                            if (searchActionMode != null) {
                                searchActionMode.finish();
                            }
                            logger.info("Message swiped, showing quote popup");
                            showQuotePopup(abstractMessageModel, true);
                        }
                    }
                }
            }
        );
    }

    private void setupSendButtonClickListener() {
        if (sendButton != null) {
            sendButton.setOnClickListener(new DebouncedOnClickListener(500) {
                @Override
                public void onDebouncedClick(View v) {
                    logger.info("Send button clicked");
                    sendMessage();
                }
            });
            // F1Whisper: long-press the send button to schedule the currently typed message
            sendButton.setOnLongClickListener(v -> {
                if (!validateSendingPermission()) {
                    return true;
                }
                if (TestUtil.isBlankOrNull(messageText.getText())) {
                    // nothing typed -> let the normal (voice) long-press behaviour stand
                    return false;
                }
                logger.info("Send button long-pressed, showing schedule picker");
                showScheduleMessagePicker();
                return true;
            });
        }
    }

    private void setupEditMessageButtonClickListener(@NonNull AbstractMessageModel messageModel) {
        if (sendEditMessageButton != null) {
            sendEditMessageButton.setOnClickListener(new DebouncedOnClickListener(500) {
                @Override
                public void onDebouncedClick(View v) {
                    logger.info("Send edit button clicked");
                    final @Nullable Editable messageTextEditable = messageText.getText();
                    if (messageTextEditable != null) {
                        onSendEditMessage(messageModel, messageTextEditable.toString());
                    }
                }
            });
        }
    }

    private void setupAttachButtonClickListener() {
        if (attachButton != null) {
            attachButton.setOnClickListener(new DebouncedOnClickListener(1000) {
                @Override
                public void onDebouncedClick(View v) {
                    if (validateSendingPermission()) {
                        logger.info("Attach media button clicked");
                        if (actionMode != null) {
                            actionMode.finish();
                        }

                        dismissQuotePopup();

                        Intent intent = new Intent(activity, MediaAttachActivity.class);
                        IntentDataUtil.addMessageReceiverToIntent(intent, messageReceiver);
                        if (ComposeMessageFragment.this.lastMediaFilter != null) {
                            intent = IntentDataUtil.addLastMediaFilterToIntent(intent, ComposeMessageFragment.this.lastMediaFilter);
                        }
                        startActivityForResult(intent, ThreemaActivity.ACTIVITY_ID_ATTACH_MEDIA);
                        activity.overridePendingTransition(R.anim.fast_fade_in, R.anim.fast_fade_out);
                    }
                }
            });
        }
    }

    private void setupSendMessageTextActionListener() {
        this.messageText.setOnEditorActionListener(
            setupTextActionListener(this::sendMessage)
        );
    }

    @NonNull
    private TextView.OnEditorActionListener setupTextActionListener(@NonNull Runnable onAction) {
        return (view, actionId, event) -> {
            if ((actionId == EditorInfo.IME_ACTION_SEND) ||
                (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && preferenceService.isEnterToSend())) {
                logger.info("Enter key pressed to send message");
                onAction.run();
                return true;
            }
            return false;
        };
    }

    private void setupMessageTextListeners() {
        setupSendMessageTextActionListener();

        setupMessageTextClickListener();

        this.messageText.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(@NonNull CharSequence text, int start, int before, int count) {
                ActivityService.activityUserInteract(activity);
                updateSendButton(text);
                // F1Whisper: drive the link-preview chip (no chip while editing/quoting).
                if (linkPreviewController != null && editMessageActionMode == null && !isQuotePopupShown()) {
                    linkPreviewController.onTextChanged(text);
                } else if (linkPreviewController != null) {
                    linkPreviewController.reset();
                }
            }

            @Override
            public void afterTextChanged(@NonNull Editable editable) {
                updateCameraButton();
            }
        });
    }

    /**
     * Setup listener for text input field.
     * <p>
     * If this is a group chat and the user is not an active member of the group, we prevent any input.
     * <p>
     * If default emojis are enabled and the device is in portrait mode,
     * we will handle activation. Otherwise, leave it to the system
     */
    @UiThread
    private void setupMessageTextClickListener() {
        if (this.messageText == null) {
            return;
        }

        final boolean canUserComposeMessage = !isGroupChatWhereUserIsNotMemberOf();
        this.messageText.setFocusable(canUserComposeMessage);
        this.messageText.setFocusableInTouchMode(canUserComposeMessage);

        if (!canUserComposeMessage) {
            this.messageText.setOnClickListener(v -> {
                SingleToast.getInstance().showLongText(getString(R.string.you_are_not_a_member_of_this_group));
            });
        } else if (ConfigUtils.isDefaultEmojiStyle() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            this.messageText.setOnClickListener(v -> {
                if (isEmojiPickerShown()) {
                    if (ConfigUtils.isLandscape(activity) && !ConfigUtils.isTabletLayout()) {
                        emojiPicker.hide();
                        addAllInsetsToInsetPaddingContainer();
                    } else {
                        if (rootInsetsDeferringCallback != null && keyboardAnimationInsetsCallback != null) {
                            rootInsetsDeferringCallback.openingEmojiPicker = true;
                            keyboardAnimationInsetsCallback.skipNextAnimation = true;
                        }
                    }
                }
                activity.openSoftKeyboard(messageText);
            });
        } else {
            this.messageText.setOnClickListener(null);
        }
    }

    private void updateCameraButton() {
        if (cameraButton == null || attachButton == null || messageText == null || editMessageActionMode != null) {
            return;
        }

        boolean isCameraPermissionGranted = true;

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

            // shouldShowRequestPermissionRationale returns false if
            // a) the user selected "never ask again"; or
            // b) a permission dialog has never been shown
            // we hide the camera button only in case a)
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) && preferenceService.getCameraPermissionRequestShown()) {
                cameraButton.setVisibility(View.GONE);
                isCameraPermissionGranted = false;
            }
        }

        final int attachButtonVisibility = isQuotePopupShown() ?
            View.GONE : View.VISIBLE;

        final int cameraButtonVisibility =
            (messageText.getText() == null ||
                messageText.getText().length() == 0) &&
                !isQuotePopupShown() &&
                isCameraPermissionGranted ?
                View.VISIBLE : View.GONE;

        final boolean attachButtonVisibilityChange = attachButton.getVisibility() != attachButtonVisibility;
        final boolean cameraButtonVisibilityChange = cameraButton.getVisibility() != cameraButtonVisibility;

        if (cameraButtonVisibilityChange) {
            Transition cameraTransition = new Slide(Gravity.END);
            cameraTransition.setStartDelay(cameraButtonVisibility == View.VISIBLE && attachButtonVisibilityChange ? 100 : 0);
            cameraTransition.setDuration(120);
            cameraTransition.setInterpolator(new LinearInterpolator());
            cameraTransition.addTarget(R.id.camera_button);
            TransitionManager.beginDelayedTransition((ViewGroup) cameraButton.getParent(), cameraTransition);
            cameraButton.setVisibility(cameraButtonVisibility);
        }

        if (attachButtonVisibilityChange) {
            Transition attachTransition = new Slide(Gravity.END);
            attachTransition.setStartDelay(attachButtonVisibility == View.VISIBLE ? 0 : 100);
            attachTransition.setDuration(120);
            attachTransition.setInterpolator(new LinearInterpolator());
            attachTransition.addTarget(R.id.attach_button);
            TransitionManager.beginDelayedTransition((ViewGroup) attachButton.getParent(), attachTransition);
            attachButton.setVisibility(attachButtonVisibility);
        }

        messageText.postDelayed(() -> fixMessageTextPadding(cameraButtonVisibility, attachButtonVisibility), 50);
    }

    private void fixMessageTextPadding(int cameraButtonVisibility, int attachButtonVisibility) {
        if (isAdded()) {
            // This is the inline padding that reserves room for the camera/attach/send button
            // cluster, which lives at the layout END (right in LTR, left in RTL). Use the
            // relative (start/end) padding API so the reserved space mirrors correctly under RTL.
            int endPadding = ThreemaApplication.getAppContext().getResources().getDimensionPixelSize(R.dimen.emoji_and_photo_button_width);

            if (cameraButtonVisibility != View.VISIBLE) {
                endPadding -= getResources().getDimensionPixelSize(R.dimen.emoji_button_width);
            }

            if (attachButtonVisibility != View.VISIBLE) {
                endPadding -= getResources().getDimensionPixelSize(R.dimen.emoji_button_width);
            }

            endPadding = Math.max(endPadding, getResources().getDimensionPixelSize(R.dimen.no_emoji_button_padding_left));

            messageText.setPaddingRelative(messageText.getPaddingStart(), messageText.getPaddingTop(), endPadding, messageText.getPaddingBottom());
        }
    }

    private void updateSendButton(CharSequence text) {
        if (isQuotePopupShown()) {
            if (TestUtil.isBlankOrNull(text)) {
                sendButton.setEnabled(false);
            } else {
                sendButton.setSend();
                sendButton.setEnabled(true);
            }
        } else {
            if (TestUtil.isBlankOrNull(text)) {
                sendButton.setRecord();
                sendButton.setEnabled(true);
            } else {
                sendButton.setSend();
                sendButton.setEnabled(true);
            }
        }
        if (emojiButton != null) {
            emojiButton.setVisibility(ConfigUtils.isDefaultEmojiStyle() ? View.VISIBLE : View.GONE);
        }
        if (messageText != null) {
            messageText.setVisibility(View.VISIBLE);
        }
    }

    private void updateSendEditMessageButton(@Nullable String oldMessageText, @Nullable String newMessageText) {
        if (canSendEditMessage(oldMessageText, newMessageText)) {
            sendEditMessageButton.setEnabled(true);
            sendEditMessageButton.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_circle_send));
            sendEditMessageButton.setColorFilter(ConfigUtils.getColorFromAttribute(requireContext(), R.attr.colorOnPrimary));
        } else {
            sendEditMessageButton.setEnabled(false);
            sendEditMessageButton.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_circle_send_disabled));
            sendEditMessageButton.setColorFilter(ConfigUtils.getColorFromAttribute(requireContext(), R.attr.colorOnSurfaceVariant));
        }
    }

    /**
     * Check if the value of {@code edit} would be a valid change compared to the current value of {@code original}.
     *
     * @param original The body <b>or</b> caption of the original message before the edit. Can be {@code null} in case of a file message without a
     *                 caption
     * @param edited   The potentially changed body <b>or</b> caption text. Values of {@code null} or blank are never valid because even for a file
     *                 message we currently do not support removing a caption completely.
     * @see EditMessageActionMode#getEditableText
     */
    private boolean canSendEditMessage(@Nullable String original, @Nullable String edited) {
        return edited != null && !edited.isBlank() && !edited.equals(original);
    }

    private void setBackgroundWallpaper() {
        if (isAdded() && this.wallpaperView != null) {
            wallpaperService.setupWallpaperBitmap(this.messageReceiver, this.wallpaperView, ConfigUtils.isLandscape(activity), ConfigUtils.isTheDarkSide(activity));
        }
    }

    private void resetDefaultValues() {
        removeGroupCallObserver();

        this.distributionListId = 0L;
        this.groupDbId = 0L;
        this.identity = null;

        this.groupModel = null;
        this.distributionListModel = null;
        this.contactModel = null;

        this.messageReceiver = null;
        composeMessageFragmentUtils = null;
        this.listInstancePosition = AbsListView.INVALID_POSITION;
        this.listInstanceReceiverId = null;

        if (ConfigUtils.isTabletLayout()) {
            dismissQuotePopup();
        }

        // remove message detail dialog if still open
        DialogUtil.dismissDialog(getParentFragmentManager(), DIALOG_TAG_MESSAGE_DETAIL, true);
    }

    private void removeGroupCallObserver() {
        if (groupModel != null && groupCallObserver != null && groupCallManager != null) {
            logger.info("Remove group call observer for group {}", groupModel.getDatabaseId());
            groupCallManager.removeGroupCallObserver(groupModel, groupCallObserver);
            groupCallObserver = null;
        }
    }

    private void getValuesFromBundle(Bundle bundle) {
        if (bundle != null) {
            this.groupDbId = bundle.getLong(AppConstants.INTENT_DATA_GROUP_DATABASE_ID, 0L);
            this.distributionListId = bundle.getLong(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID, 0);
            this.identity = bundle.getString(AppConstants.INTENT_DATA_CONTACT);
            this.intentTimestamp = bundle.getLong(AppConstants.INTENT_DATA_TIMESTAMP, 0L);
            this.cameraUri = bundle.getParcelable(CAMERA_URI);
            this.listInstancePosition = bundle.getInt(BUNDLE_LIST_POSITION);
            this.listInstanceReceiverId = bundle.getString(BUNDLE_LIST_RECEIVER_ID);
            this.listInstanceTop = bundle.getInt(BUNDLE_LIST_TOP);
            this.longClickItem = bundle.getInt(BUNDLE_LIST_LONG_CLICK_ITEM);
        }
    }

    public void onNewIntent(Intent intent) {
        logger.debug("onNewIntent");

        if (!requiredInstances()) {
            return;
        }

        if (this.messagePlayerService != null) {
            this.messagePlayerService.stopAll();
            this.messagePlayerService.release();
        }

        MediaController mediaController = getMedia3Controller();
        if (mediaController != null) {
            mediaController.stop();
            mediaController.clearMediaItems();
        }

        resetDefaultValues();

        this.dismissQuotePopup();

        handleIntent(intent);

        // initialize various toolbar items
        if (actionMode != null) {
            actionMode.finish();
        }
        if (searchActionMode != null) {
            searchActionMode.finish();
        }
        if (editMessageActionMode != null) {
            editMessageActionMode.finish();
        }
        this.updateToolbarTitle();
        this.updateMenus();
    }

    private void setupToolbar() {
        View actionBarTitleView = layoutInflater.inflate(R.layout.actionbar_compose_title, null);

        if (actionBarTitleView != null) {
            this.actionBarTitleTextView = actionBarTitleView.findViewById(R.id.title);
            this.actionBarSubtitleImageView = actionBarTitleView.findViewById(R.id.subtitle_image);
            this.actionBarSubtitleTextView = actionBarTitleView.findViewById(R.id.subtitle_text);
            this.actionBarAvatarView = actionBarTitleView.findViewById(R.id.avatar_view);
            final RelativeLayout actionBarTitleContainer = actionBarTitleView.findViewById(R.id.title_container);
            actionBarTitleContainer.setOnClickListener(v -> {
                Intent intent;
                if (isGroupChat) {
                    logger.info("Clicked title of group chat");
                    intent = groupService.getGroupDetailIntent(groupModel, activity);
                } else if (isDistributionListChat) {
                    logger.info("Clicked title of distribution list");
                    intent = DistributionListAddActivity.createIntent(activity);
                } else {
                    logger.info("Clicked title of contact chat");
                    intent = new Intent(activity, ContactDetailActivity.class);
                    intent.putExtra(AppConstants.INTENT_DATA_CONTACT_READONLY, true);
                }
                if (messageReceiver != null) {
                    addExtrasToIntent(intent, messageReceiver);
                    activity.startActivity(intent);
                }
            });

            if (BuildConfig.AVAILABILITY_STATUS_ENABLED && messageReceiver != null && messageReceiver instanceof ContactMessageReceiver) {
                final @Nullable ch.threema.data.models.ContactModel contactModel = ((ContactMessageReceiver) messageReceiver).getContactModel();
                final @Nullable ContactModelData contactModelData = contactModel != null ? contactModel.getData() : null;
                final @Nullable AvailabilityStatus availabilityStatus = contactModelData != null ? contactModelData.availabilityStatus : null;
                if (availabilityStatus != null && actionBarAvatarView != null) {
                    actionBarAvatarView.setAvailabilityStatusBadgeState(
                        availabilityStatus instanceof AvailabilityStatus.Set
                            ? (AvailabilityStatus.Set) availabilityStatus
                            : null
                    );
                }
            }

            if (contactModel != null) {
                if (contactModel.getIdentityType() == IdentityType.WORK) {
                    if (!ConfigUtils.isWorkBuild()) {
                        if (!preferenceService.getIsWorkHintTooltipShown()) {
                            actionBarTitleTextView.postDelayed(() -> {
                                if (getActivity() != null && isAdded()) {
                                    dismissTooltipPopup(workTooltipPopup, true);

                                    int[] location = new int[2];
                                    actionBarAvatarView.getLocationOnScreen(location);
                                    location[0] += actionBarAvatarView.getWidth() / 2;
                                    location[1] += actionBarAvatarView.getHeight();

                                    workTooltipPopup = new TooltipPopup(getActivity(), R.string.preferences__tooltip_work_hint_shown, this, R.drawable.ic_badge_work_24dp);
                                    workTooltipPopup.setListener(new TooltipPopup.TooltipPopupListener() {
                                        @Override
                                        public void onClicked(@NonNull TooltipPopup tooltipPopup) {
                                            logger.info("Clicked Threema Work tooltip");
                                            startActivity(WorkExplainActivity.createIntent(requireContext()));
                                        }
                                    });
                                    workTooltipPopup.show(getActivity(), actionBarAvatarView, null, getString(R.string.tooltip_work_hint), TooltipPopup.Alignment.BELOW_ANCHOR_ARROW_LEFT, location, 4000);
                                }
                            }, 1000);
                        }
                    }
                }
            } else if (groupModel != null) {
                if (ConfigUtils.isGroupCallsEnabled()) {
                    showTooltip();
                }
            }
        }

        if (activity == null) {
            activity = (ComposeMessageActivity) getActivity();
        }

        if (activity != null) {
            this.actionBar = activity.getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_HOME_AS_UP);
                actionBar.setCustomView(actionBarTitleView);
            }
        }
    }

    @UiThread
    public void showTooltip() {
        if (activity == null) {
            return;
        }

        if (!preferenceService.getIsGroupCallsTooltipShown()) {
            Toolbar toolbar = activity.getToolbar();
            if (toolbar != null) {
                toolbar.postDelayed(() -> {
                    if (activity == null || !activity.hasWindowFocus() || !isGroupChat) {
                        return;
                    }
                    final View itemView = toolbar.findViewById(R.id.menu_threema_call);
                    final @ColorInt int textColor = ConfigUtils.getColorFromAttribute(getContext(), R.attr.colorOnPrimary);

                    final ViewGroup contentView = activity.findViewById(R.id.main_content);

                    try {
                        TapTargetViewUtil.showFor(activity,
                            TapTarget.forView(itemView, getString(R.string.group_calls_tooltip_title), getString(R.string.group_calls_tooltip_text))
                                .outerCircleColorInt(ConfigUtils.getColorFromAttribute(getContext(), R.attr.colorPrimary))      // Specify a color for the outer circle
                                .outerCircleAlpha(0.96f)            // Specify the alpha amount for the outer circle
                                .targetCircleColor(android.R.color.white)   // Specify a color for the target circle
                                .titleTextSize(24)                  // Specify the size (in sp) of the title text
                                .titleTextColorInt(textColor)      // Specify the color of the title text
                                .descriptionTextSize(18)            // Specify the size (in sp) of the description text
                                .descriptionTextColorInt(textColor)  // Specify the color of the description text
                                .textColorInt(textColor)            // Specify a color for both the title and description text
                                .textTypeface(Typeface.SANS_SERIF)  // Specify a typeface for the text
                                .dimColor(android.R.color.black)            // If set, will dim behind the view with 30% opacity of the given color
                                .drawShadow(true)                   // Whether to draw a drop shadow or not
                                .cancelable(true)                  // Whether tapping outside the outer circle dismisses the view
                                .tintTarget(true)                   // Whether to tint the target view's color
                                .transparentTarget(false)           // Specify whether the target is transparent (displays the content underneath)
                                .targetRadius(50),                  // Specify the target radius (in dp)
                            new TapTargetView.Listener() {          // The listener can listen for regular clicks, long clicks or cancels
                                @Override
                                public void onTargetClick(TapTargetView view) {
                                    super.onTargetClick(view);
                                    itemView.performClick();
                                }
                            },
                            contentView);
                        preferenceService.setGroupCallsTooltipShown(true);
                    } catch (Exception ignore) {
                        // catch null typeface exception on CROSSCALL Action-X3
                    }
                }, 2000);
            }
        }
    }

    @UiThread
    private void handleIntent(Intent intent) {
        logger.debug("handleIntent");
        this.isGroupChat = false;
        this.isDistributionListChat = false;
        setCurrentPageReferenceId(null);
        this.reportSpamView.hide();

        //remove old indicator every time!
        //fix ANDR-432
        if (messageText != null) {
            if (typingIndicatorTextWatcher != null) {
                messageText.removeTextChangedListener(this.typingIndicatorTextWatcher);
            }
            if (draftUpdateTextWatcher != null) {
                messageText.removeTextChangedListener(draftUpdateTextWatcher);
            }
        }

        if (intent.hasExtra(AppConstants.INTENT_DATA_GROUP_DATABASE_ID) || this.groupDbId != 0) {
            this.isGroupChat = true;
            if (this.groupDbId == 0) {
                this.groupDbId = intent.getLongExtra(AppConstants.INTENT_DATA_GROUP_DATABASE_ID, 0L);
            }
            this.groupModel = groupModelRepository.getByLocalGroupDbId(this.groupDbId);

            if (this.groupModel == null || this.groupModel.isDeleted()) {
                logger.error("Group not found");
                showToast(requireContext(), R.string.group_not_found);
                finishActivity();
                return;
            }

            intent.removeExtra(AppConstants.INTENT_DATA_GROUP_DATABASE_ID);
            this.messageReceiver = this.groupService.createReceiver(this.groupModel);
            // F1Whisper: send group typing indicators as the user types
            final long typingGroupDatabaseId = this.groupModel.getDatabaseId();
            this.typingIndicatorTextWatcher = new TypingIndicatorTextWatcher(
                isTyping -> {
                    groupService.sendTypingIndicator(typingGroupDatabaseId, isTyping);
                    return Unit.INSTANCE;
                },
                this
            );
            this.conversationUid = ConversationUtil.getGroupConversationUid(this.groupDbId);

            this.messageText.enableMentionPopup(
                requireActivity(),
                groupService,
                this.contactService,
                this.userService,
                this.preferenceService,
                groupModel,
                textInputLayout
            );
        } else if (intent.hasExtra(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID) || this.distributionListId != 0) {
            this.isDistributionListChat = true;

            try {
                if (this.distributionListId == 0) {
                    this.distributionListId = intent.getLongExtra(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID, 0);
                }
                this.distributionListModel = distributionListService.getById(this.distributionListId);

                if (this.distributionListModel == null) {
                    logger.error("Invalid distribution list");
                    finishActivity();
                    return;
                }

                intent.removeExtra(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID);
                this.messageReceiver = distributionListService.createReceiver(this.distributionListModel);
            } catch (Exception e) {
                logger.error("Exception", e);
                return;
            }
            this.conversationUid = ConversationUtil.getDistributionListConversationUid(this.distributionListId);
        } else {
            if (TestUtil.isEmptyOrNull(this.identity)) {
                this.identity = intent.getStringExtra(AppConstants.INTENT_DATA_CONTACT);
            }

            // Device address-book "open chat" (tap-to-chat) has been removed for privacy reasons.

            intent.removeExtra(AppConstants.INTENT_DATA_CONTACT);
            if (this.identity == null || this.identity.isEmpty() || this.identity.equals(this.userService.getIdentity())) {
                logger.error("no identity found");
                finishActivity();
                return;
            }

            this.contactModel = this.contactService.getByIdentity(this.identity);
            if (this.contactModel == null) {
                Toast.makeText(getContext(), getString(R.string.contact_not_found) + ": " + this.identity, Toast.LENGTH_LONG).show();
                Intent homeIntent = HomeActivity.createIntent(activity);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(homeIntent);
                activity.overridePendingTransition(0, 0);
                finishActivity();
                return;
            }
            this.messageReceiver = this.contactService.createReceiver(this.contactModel);
            this.typingIndicatorTextWatcher = new TypingIndicatorTextWatcher(
                isTyping -> {
                    contactService.sendTypingIndicator(identity, isTyping);
                    return Unit.INSTANCE;
                },
                this
            );
            this.conversationUid = ConversationUtil.getContactConversationUid(this.identity);
        }

        initOngoingCallState();

        if (this.messageReceiver == null) {
            logger.error("Invalid receiver");
            finishActivity();
            return;
        }

        composeMessageFragmentUtils = KoinJavaComponent.get(
            ComposeMessageFragmentUtils.class,
            null,
            () -> new ParametersHolder(List.of(ComposeMessageFragment.this, messageReceiver, isGroupChat), null)
        );

        // hide chat from view and prevent screenshots - may not work on some devices
        if (this.conversationCategoryService.isPrivateChat(this.messageReceiver.getUniqueIdString())) {
            try {
                activity.getWindow().addFlags(FLAG_SECURE);
            } catch (Exception ignored) {
            }
        }

        // set wallpaper based on message receiver
        this.setBackgroundWallpaper();

        // report shortcut as used
        if (preferenceService.isDirectShare()) {
            var appContext = activity.getApplicationContext();
            var shortcutId = messageReceiver.getUniqueIdString();
            RuntimeUtil.runOnWorkerThread(() -> {
                try {
                    ShortcutManagerCompat.reportShortcutUsed(appContext, shortcutId);
                } catch (IllegalStateException e) {
                    logger.warn("Failed to report shortcut use", e);
                }
            });
        }

        this.initConversationList(intent.hasExtra(EXTRA_API_MESSAGE_ID) && intent.hasExtra(EXTRA_SEARCH_QUERY) ? () -> {
            String apiMessageId = intent.getStringExtra(EXTRA_API_MESSAGE_ID);
            String searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);

            AbstractMessageModel targetMessageModel = messageService.getMessageModelByApiMessageIdAndReceiver(apiMessageId, messageReceiver);

            if (targetMessageModel != null && !TestUtil.isEmptyOrNull(apiMessageId) && !TestUtil.isEmptyOrNull(searchQuery)) {
                String identity;
                if (targetMessageModel instanceof GroupMessageModel) {
                    identity = targetMessageModel.isOutbox() ? userService.getIdentity() : targetMessageModel.getIdentity();
                } else {
                    identity = targetMessageModel.getIdentity();
                }

                if (identity == null) {
                    logger.error("Identity is null");
                } else {
                    QuoteUtil.QuoteContent quoteContent = QuoteUtil.QuoteContent.createV2(
                        identity,
                        searchQuery,
                        searchQuery,
                        apiMessageId,
                        targetMessageModel,
                        messageReceiver,
                        null,
                        null
                    );

                    if (composeMessageAdapter != null) {
                        ComposeMessageAdapter.ConversationListFilter filter = (ComposeMessageAdapter.ConversationListFilter) composeMessageAdapter.getQuoteFilter(quoteContent);
                        searchV2Quote(apiMessageId, filter);

                        intent.removeExtra(EXTRA_API_MESSAGE_ID);
                    }
                }
            } else {
                Toast.makeText(ThreemaApplication.getAppContext(), R.string.message_not_found, Toast.LENGTH_SHORT).show();
            }
        } : () -> {
            if (isPossibleSpamContact(contactModel)) {
                reportSpamView.show(contactModel, preferenceService.getContactNameFormat());
            }
        });

        // work around the problem that the same original intent may be sent
        // each time a singleTop activity (like this one) is coming back to front
        // causing - in this case - duplicate delivery of forwarded messages.
        // so we make sure the intent is only handled if it's newer than
        // any previously handled intent
        long newTimestamp = 0L;
        try {
            newTimestamp = intent.getLongExtra(AppConstants.INTENT_DATA_TIMESTAMP, 0L);
            if (newTimestamp != 0L && newTimestamp <= this.intentTimestamp) {
                return;
            }
        } finally {
            this.intentTimestamp = newTimestamp;
        }

        this.messageText.setText("");
        this.messageText.setMessageReceiver(this.messageReceiver);
        this.openBallotNoticeView.setMessageReceiver(this.messageReceiver);
        this.openBallotNoticeView.setOnCloseClickedListener(() -> {
            toggleOpenBallotNoticeViewVisibility();
            getActivity().invalidateOptionsMenu();
        });


        // restore draft before setting predefined text
        restoreMessageDraft(false);

        String defaultText = intent.getStringExtra(AppConstants.INTENT_DATA_TEXT);
        if (!TestUtil.isEmptyOrNull(defaultText)) {
            this.messageText.setText(null);
            this.messageText.append(defaultText);
        }

        setupMessageTextClickListener();
        updateSendButton(this.messageText.getText());
        updateCameraButton();

        boolean editFocus = intent.getBooleanExtra(AppConstants.INTENT_DATA_EDITFOCUS, false);
        if (editFocus || this.unreadCount <= 0) {
            messageText.setSelected(true);
            messageText.requestFocus();
        }

        this.notificationService.setVisibleReceiver(this.messageReceiver);

        // F1Whisper: typing indicators for both 1:1 and group chats (not distribution lists)
        if (!this.isDistributionListChat && this.typingIndicatorTextWatcher != null) {
            this.messageText.addTextChangedListener(this.typingIndicatorTextWatcher);
        }

        draftUpdateTextWatcher = new DraftUpdateTextWatcher(
            draftManager,
            messageReceiver.getUniqueIdString(),
            () -> {
                var text = messageText.getText();
                return text != null ? text.toString() : null;
            },
            () -> {
                if (!isQuotePopupShown()) {
                    return null;
                }
                var quoteMessageModel = quotePopup.getQuoteInfo().getMessageModel();
                return quoteMessageModel != null
                    ? quoteMessageModel.getMessageId()
                    : null;
            }
        );
        messageText.addTextChangedListener(draftUpdateTextWatcher);

        if (ConfigUtils.isTabletLayout() && conversationUid != null) {
            ListenerManager.chatListeners.handle(listener -> listener.onChatOpened(conversationUid));
        }
    }

    private boolean validateSendingPermission() {
        if (this.messageReceiver == null) {
            return false;
        }
        @NonNull final SendingPermissionValidationResult validationResult = this.messageReceiver.validateSendingPermission();
        if (validationResult.isDenied()) {
            @Nullable Integer errorResId = ((SendingPermissionValidationResult.Denied) validationResult).getErrorResId();
            if (errorResId != null) {
                RuntimeUtil.runOnUiThread(() -> SingleToast.getInstance().showLongText(getString(errorResId)));
            }
            return false;
        }
        return true;
    }

    private void showDeleteMessagesLocallyDialog() {
        if (selectedMessages.isEmpty()) {
            if (actionMode != null) {
                actionMode.finish();
            }
            return;
        }

        List<AbstractMessageModel> deletableMessages = new ArrayList<>(selectedMessages);

        selectedMessages.clear();
        if (actionMode != null) {
            actionMode.finish();
        }

        logger.info("Showing local deletion dialog for {} message(s) ", selectedMessages.size());
        GenericAlertDialog dialog = GenericAlertDialog.newInstance(
            null,
            ConfigUtils.getSafeQuantityString(requireContext(), R.plurals.delete_messages, deletableMessages.size(), deletableMessages.size()),
            R.string.delete_from_this_device,
            R.string.cancel
        );
        dialog.setCallback((tag, data) -> {
            logger.info("Deletion of local messages confirmed");
            deleteMessages(deletableMessages);
        });
        dialog.show(getChildFragmentManager(), DIALOG_TAG_CONFIRM_MESSAGE_DELETE);
    }

    private void showDeleteMessagesForAllDialog(@NonNull AbstractMessageModel message) {
        if (actionMode != null) {
            actionMode.finish();
        }

        GenericAlertDialog dialog = GenericAlertDialog.newInstance(
            null,
            ConfigUtils.getSafeQuantityString(requireContext(), R.plurals.delete_messages, 1, 1),
            R.string.delete_for_all,
            R.string.delete_from_this_device,
            R.string.cancel
        );

        dialog.setCallback(new GenericAlertDialog.DialogClickListener() {
            @Override
            public void onYes(@Nullable String tag, @Nullable Object data) {
                logger.info("Deletion of message for everyone confirmed");
                onConfirmDeleteMessageForAll(message);
            }

            @Override
            public void onNo(@Nullable String tag, @Nullable Object data) {
                logger.info("Deletion of message from device confirmed");
                deleteMessages(List.of(message));
            }
        });

        logger.info("Showing deletion dialog");
        dialog.show(getChildFragmentManager(), DIALOG_TAG_CONFIRM_MESSAGE_DELETE);
    }

    private void onConfirmDeleteMessageForAll(@NonNull AbstractMessageModel message) {
        if (messageReceiver == null) {
            return;
        }

        if (messageReceiver instanceof ContactMessageReceiver) {
            ContactMessageReceiver receiver = (ContactMessageReceiver) messageReceiver;
            deleteContactMessageForAll(receiver, message);
        } else if (messageReceiver instanceof GroupMessageReceiver && groupModel != null && groupModel.isMember()) {
            deleteGroupMessageForAll(message, groupModel);
        } else {
            logger.warn("Cannot delete message for receiver of type {}", messageReceiver.getClass().getName());
        }
    }

    private void deleteContactMessageForAll(@NonNull ContactMessageReceiver receiver, @NonNull AbstractMessageModel message) {
        if (ThreemaFeature.canDeleteMessages(receiver.getContact().getFeatureMask())) {
            sendDeleteMessage(message);
        } else {
            logger.warn("Tried to delete a message for a contact that does not support it");
        }
    }

    private void deleteGroupMessageForAll(
        @NonNull AbstractMessageModel message,
        @NonNull GroupModel groupModel
    ) {
        final @Nullable GroupModelData groupModelData = groupModel.getData();
        if (groupModelData == null) {
            logger.warn("Cannot delete message for all in a group where the data is null");
            return;
        }
        // If this is a notes group, the feature-support checks can be skipped
        if (Boolean.TRUE.equals(groupModel.isNotesGroup())) {
            sendDeleteMessage(message);
            return;
        }
        GroupFeatureSupport featureSupport = groupService.getFeatureSupport(groupModelData, ThreemaFeature.DELETE_MESSAGES);
        if (featureSupport.getAdoptionRate() == GroupFeatureAdoptionRate.ALL) {
            sendDeleteMessage(message);
        } else if (featureSupport.getAdoptionRate() == GroupFeatureAdoptionRate.PARTIAL) {
            List<ContactModel> membersWithoutFeatureSupport = featureSupport.getContactsWithoutFeatureSupport();
            GenericAlertDialog dialog = GenericAlertDialog.newInstance(
                R.string.warning,
                getString(
                    R.string.delete_message_not_supported_for_all_group_members,
                    ContactUtil.joinDisplayNames(getContext(), membersWithoutFeatureSupport, preferenceService.getContactNameFormat())
                ),
                R.string.ok,
                R.string.cancel
            );
            dialog.setCallback((tag, data) -> sendDeleteMessage(message));
            dialog.show(getChildFragmentManager(), DIALOG_TAG_DELETE_MESSAGES_UNSUPPORTED_WARNING);
        } else {
            logger.warn("Tried to delete a message for a group where none of the members support it");
        }
    }

    private void sendDeleteMessage(@NonNull AbstractMessageModel message) {
        if (messageReceiver == null) {
            return;
        }

        try {
            messageService.sendDeleteMessage(message, messageReceiver);
        } catch (Exception e) {
            logger.error("sendDeleteMessage failed", e);
        }
    }

    /**
     * Check if the clues indicate that the sender of this chat might be a spammer
     *
     * @param contactModel Contact model of possible spammer
     * @return true if the contact could be a spammer, false otherwise
     */
    private boolean isPossibleSpamContact(@Nullable ContactModel contactModel) {
        if (contactModel == null || composeMessageAdapter == null) {
            return false;
        }

        // No spam reporting in on-prem build
        if (ConfigUtils.isOnPremBuild()) {
            return false;
        }

        // Exclude verified contacts
        if (contactModel.verificationLevel != VerificationLevel.UNVERIFIED) {
            return false;
        }

        // Exclude contacts where the name is set
        if (!TestUtil.isEmptyOrNull(contactModel.getFirstName()) || !TestUtil.isEmptyOrNull(contactModel.getLastName())) {
            return false;
        }

        // Exclude blocked contacts
        if (blockedIdentitiesService.isBlocked(contactModel.getIdentity())) {
            return false;
        }

        // Exclude group contacts
        if (contactModel.getAcquaintanceLevel() == ContactModel.AcquaintanceLevel.GROUP) {
            return false;
        }

        // Exclude official Threema Gateway contacts
        if (THREEMA_CHANNEL_IDENTITY.equals(contactModel.getIdentity()) || THREEMA_SUPPORT_IDENTITY.equals(contactModel.getIdentity())) {
            return false;
        }

        int numMessages = composeMessageAdapter.getCount();
        if (numMessages >= MESSAGE_PAGE_SIZE || numMessages < 2) {
            return false;
        }

        AbstractMessageModel firstMessageModel;
        int positionOfFirstIncomingMessage = 0;
        for (int i = 0; i < numMessages; i++) {
            firstMessageModel = composeMessageAdapter.getItem(i);
            if (firstMessageModel == null) {
                return false;
            }
            if (firstMessageModel.isOutbox()) {
                return false;
            }
            if (contactModel.getIdentity().equals(firstMessageModel.getIdentity())) {
                positionOfFirstIncomingMessage = i;
                break;
            }
        }

        AbstractMessageModel messageModel = composeMessageAdapter.getItem(positionOfFirstIncomingMessage);

        if (messageModel == null) {
            return false;
        }

        Date contactCreated = contactModel.getDateCreated();
        Date firstMessageDate = messageModel.getCreatedAt();

        if (contactCreated == null || firstMessageDate == null) {
            return false;
        }

        if (firstMessageDate.getTime() - contactCreated.getTime() > DateUtils.DAY_IN_MILLIS) {
            return false;
        }

        for (int i = positionOfFirstIncomingMessage; i < numMessages; i++) {
            AbstractMessageModel abstractMessageModel = composeMessageAdapter.getItem(i);
            if (abstractMessageModel == null || abstractMessageModel.isOutbox()) {
                return false;
            }
        }

        return true;
    }

    private synchronized void deleteMessages(@NonNull List<AbstractMessageModel> messages) {
        if (messages.isEmpty()) {
            return;
        }
        for (AbstractMessageModel m : messages) {
            messageService.remove(m);
        }
    }

    @UiThread
    private void contactTypingStateChanged(boolean isTyping) {
        RuntimeUtil.runOnUiThread(() -> {
            if (isTypingView != null) {
                logger.debug("is typing {} footer view count {}", isTyping, convListView.getFooterViewsCount());
                if (isTyping) {
                    //remove if the the another footer element added
                    if (convListView.getFooterViewsCount() == 0) {
                        isTypingView.setVisibility(View.VISIBLE);
                        convListView.addFooterView(isTypingView, null, false);
                    }
                } else {
                    removeIsTypingFooter();
                }
            }
        });
    }

    private void removeIsTypingFooter() {
        if (isTypingView != null) {
            isTypingView.setVisibility(View.GONE);
            if (convListView != null && convListView.getFooterViewsCount() > 0) {
                convListView.removeFooterView(isTypingView);
            }
        }
    }

    @UiThread
    private boolean addMessageToList(AbstractMessageModel message) {
        if (message == null || this.messageReceiver == null || this.composeMessageAdapter == null) {
            return false;
        }

        if (message.getType() == MessageType.BALLOT && !message.isOutbox()) {
            // If we receive a new ballot message
            openBallotNoticeView.update();
        }

        // check if the message already added
        // F1Whisper: exempt outbox messages from the "already loaded" guard. This guard skips
        // pre-existing history during async list loads by comparing createdAt against the raw-clock
        // listInitializedAt watermark. Since v6.4.3-30 (TrustedClock), an outgoing message's
        // createdAt is stamped on the server-corrected clock (system + offset); on a phone whose
        // clock runs ahead of the OnPrem server the offset is negative, so a just-composed outgoing
        // message's createdAt lands BEFORE the raw watermark and was silently dropped from the live
        // list (it only appeared after closing+reopening the chat, which reloads from the DB). An
        // outbox message reaching onNew was just created this session by fireOnCreatedMessage and can
        // never be pre-load history, so the historical-dedup check must not apply to it. The incoming
        // path (raw-clock createdAt vs raw-clock watermark) is left untouched.
        if (!message.isOutbox() && this.listInitializedAt != null && message.getCreatedAt().before(this.listInitializedAt)) {
            return false;
        }

        if (!this.messageReceiver.isMessageBelongsToMe(message)) {
            //do nothing, not my thread
            return false;
        }

        logger.debug("addMessageToList: started");

        this.composeMessageAdapter.removeFirstUnreadPosition();

        // if previous message is from another date, add a date separator
        synchronized (this.messageValues) {
            int size = this.messageValues.size();
            Date date = new Date();
            Date createdAt = size > 0 ? this.messageValues.get(size - 1).getCreatedAt() : new Date(0L);
            if (!dayFormatter.format(createdAt).equals(dayFormatter.format(date))) {
                final DateSeparatorMessageModel dateSeparatorMessageModel = new DateSeparatorMessageModel();
                dateSeparatorMessageModel.setCreatedAt(date);
                this.messageValues.add(size, dateSeparatorMessageModel);
            }
        }

        this.composeMessageAdapter.add(message);

        if (!this.isPaused) {
            this.recentlyAddedCount++;
            new MarkAsReadRoutine(conversationService, messageService, notificationService)
                .runAsync(Collections.singletonList(message), messageReceiver);
        } else {
            this.unreadMessages.add(message);
        }

        if (message.isOutbox()) {
            // scroll to bottom on outgoing message
            scrollList(Integer.MAX_VALUE);
        }

        if (!(message instanceof FirstUnreadMessageModel) && currentPageReferenceId == null) {
            setCurrentPageReferenceId(message.getId());
        }

        logger.debug("addMessageToList: finished");

        return true;
    }

    @UiThread
    private void scrollList(final int targetPosition) {
        logger.debug("scrollList {}", targetPosition);

        if (this.listUpdateInProgress) {
            logger.debug("Update in progress");
            return;
        }

        if (this.composeMessageAdapter == null) {
            return;
        }

        this.convListView.post(() -> {
            int topEntry = convListView.getFirstVisiblePosition();

            // update only if really necessary
            if (targetPosition != topEntry) {
                listUpdateInProgress = true;

                int listEntryCount = convListView.getCount();

                if (topEntry > targetPosition) {
                    // scroll up
                    int startPosition = targetPosition + SMOOTHSCROLL_THRESHOLD;

                    if (startPosition < listEntryCount) {
                        convListView.setSelection(targetPosition);
                    } else {
                        convListView.smoothScrollToPosition(targetPosition);
                    }
                } else {
                    // scroll down
                    int startPosition = listEntryCount - SMOOTHSCROLL_THRESHOLD;

                    if (listEntryCount - convListView.getLastVisiblePosition() > SMOOTHSCROLL_THRESHOLD && startPosition > 0) {
                        convListView.setSelection(targetPosition);
                    } else {
                        convListView.smoothScrollToPosition(targetPosition);
                    }
                }
                listUpdateInProgress = false;
            }
        });
    }

    /**
     * Loading the next records for the listview
     */
    @WorkerThread
    private List<AbstractMessageModel> getNextRecords() {
        List<AbstractMessageModel> messageModels = this.messageService.getMessagesForReceiver(this.messageReceiver, this.nextMessageFilter);
        this.valuesLoaded(messageModels);
        return messageModels;
    }

    @WorkerThread
    private List<AbstractMessageModel> getAllRecords() {
        List<AbstractMessageModel> messageModels = this.messageService.getMessagesForReceiver(this.messageReceiver);
        this.valuesLoaded(messageModels);
        return messageModels;
    }

    /**
     * Append records to the list, adding date separators if necessary
     * Locks list by calling setNotifyOnChange(false) on the adapter to speed up list ctrl
     * Don't forget to call notifyDataSetChanged() on the adapter in the UI thread after inserting
     *
     * @param values     MessageModels to insert
     * @param clear      Whether previous list entries should be cleared before appending
     * @param markasread Whether chat should be marked as read
     * @return Number of items that have been added to the list INCLUDING date separators and other decoration
     */
    @UiThread
    private int insertToList(final List<AbstractMessageModel> values, boolean clear, boolean markasread, boolean notify) {
        int insertedSize = 0;

        this.composeMessageAdapter.setNotifyOnChange(false);
        synchronized (this.messageValues) {
            int initialSize = this.messageValues.size();

            Date date = new Date();
            if (clear) {
                this.messageValues.clear();
            } else {
                // prevent duplicate date separators when adding messages to an existing chat (e.g. after pull-to-refresh)
                if (!this.messageValues.isEmpty()) {
                    if (this.messageValues.get(0) instanceof DateSeparatorMessageModel) {
                        this.messageValues.remove(0);
                    }
                    AbstractMessageModel topmostMessage = this.messageValues.get(0);
                    if (topmostMessage != null) {
                        Date topmostDate = topmostMessage.getCreatedAt();
                        if (topmostDate != null) {
                            date = topmostDate;
                        }
                    }
                }
            }

            for (AbstractMessageModel m : values) {
                Date createdAt = m.getCreatedAt();
                if (createdAt != null) {
                    if (!dayFormatter.format(createdAt).equals(dayFormatter.format(date))) {
                        if (!this.messageValues.isEmpty()) {
                            final DateSeparatorMessageModel dateSeparatorMessageModel = new DateSeparatorMessageModel();
                            dateSeparatorMessageModel.setCreatedAt(this.messageValues.get(0).getCreatedAt());
                            this.messageValues.add(0, dateSeparatorMessageModel);
                        }
                        date = createdAt;
                    }
                }

                this.messageValues.add(0, m);
            }

            if (!this.messageValues.isEmpty() && !(this.messageValues.get(0) instanceof DateSeparatorMessageModel)) {
                // add topmost date separator
                final DateSeparatorMessageModel dateSeparatorMessageModel = new DateSeparatorMessageModel();
                dateSeparatorMessageModel.setCreatedAt(this.messageValues.get(0).getCreatedAt());
                this.messageValues.add(0, dateSeparatorMessageModel);
            }

            this.listInitializedAt = new Date();

            insertedSize = this.messageValues.size() - initialSize;
        }

        if (clear) {
            composeMessageAdapter.setNotifyOnChange(true);
            composeMessageAdapter.notifyDataSetInvalidated();
        } else {
            if (notify) {
                composeMessageAdapter.notifyDataSetChanged();
            } else {
                composeMessageAdapter.setNotifyOnChange(true);
            }
        }

        if (markasread) {
            markAsRead();
        }
        return insertedSize;
    }

    private void valuesLoaded(List<AbstractMessageModel> values) {
        if (values != null && !values.isEmpty()) {
            AbstractMessageModel topMessageModel = values.get(values.size() - 1);
            // the topmost message may be a unread messages indicator. as it does not have an id, skip it.
            if (topMessageModel instanceof FirstUnreadMessageModel && values.size() > 1) {
                topMessageModel = values.get(values.size() - 2);
            }
            setCurrentPageReferenceId(topMessageModel.getId());
        }
    }

    /**
     * initialize conversation list and set the unread message count
     */
    @SuppressLint({"StaticFieldLeak", "WrongThread"})
    @UiThread
    private void initConversationList(@Nullable Runnable runAfter) {
        this.unreadCount = (int) this.messageReceiver.getUnreadMessagesCount();
        if (this.unreadCount > MESSAGE_PAGE_SIZE) {
            new AsyncTask<Void, Void, List<AbstractMessageModel>>() {
                @Override
                protected void onPreExecute() {
                    GenericProgressDialog.newInstance(0, R.string.please_wait).show(getParentFragmentManager(), DIALOG_TAG_LOADING_MESSAGES);
                }

                @Override
                protected List<AbstractMessageModel> doInBackground(Void... voids) {
                    return messageService.getMessagesForReceiver(messageReceiver, new MessageService.MessageFilter() {
                        @Override
                        public long getPageSize() {
                            return unreadCount;
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
                            return false;
                        }

                        @Override
                        public MessageType[] types() {
                            return new MessageType[0];
                        }

                        @Override
                        public int[] contentTypes() {
                            return null;
                        }

                        @Override
                        public int[] displayTags() {
                            return null;
                        }
                    });
                }

                @Override
                protected void onPostExecute(List<AbstractMessageModel> values) {
                    valuesLoaded(values);
                    populateList(values);
                    DialogUtil.dismissDialog(getParentFragmentManager(), DIALOG_TAG_LOADING_MESSAGES, true);
                    if (runAfter != null) {
                        runAfter.run();
                    }
                }
            }.execute();
        } else {
            populateList(getNextRecords());
            if (runAfter != null) {
                runAfter.run();
            }
        }
    }

    /**
     * Populate ListView with provided message models
     */
    @UiThread
    private void populateList(List<AbstractMessageModel> values) {
        if (composeMessageAdapter != null) {
            // re-use existing adapter (for example on tablets)
            composeMessageAdapter.clear();
            composeMessageAdapter.setThumbnailWidth(ConfigUtils.getPreferredThumbnailWidth(getContext(), false));
            composeMessageAdapter.setGroupId(groupDbId);
            composeMessageAdapter.setMessageReceiver(messageReceiver);
            composeMessageAdapter.setUnreadMessagesCount(unreadCount);
            insertToList(values, true, true, true);
            updateToolbarTitle();
        } else {
            thumbnailCache = new ThumbnailCache<Integer>(null);

            composeMessageAdapter = new ComposeMessageAdapter(
                requireContext(),
                messageValues,
                userService,
                contactService,
                fileService,
                messageService,
                ballotService,
                preferenceService,
                downloadService,
                licenseService,
                emojiReactionsRepository,
                messageReceiver,
                convListView,
                thumbnailCache,
                ConfigUtils.getPreferredThumbnailWidth(getContext(), false),
                /* chatAdapterDecoratorListener = */ this,
                /* linkifyListener = */ this,
                unreadCount,
                mediaControllerFuture,
                /* voipStatusDataChatListener = */ this,
                /* ballotChatListener = */this,
                /* messagePlayerFactory = */this,
                /* imageListener = */this,
                /* downloadAlertDialogListener = */this,
                /* userInteractionListener = */this
            );

            //adding footer before setting the list adapter (android < 4.4)
            if (null != convListView && !isGroupChat && !isDistributionListChat) {
                //create the istyping instance for later use
                isTypingView = layoutInflater.inflate(R.layout.conversation_list_item_typing, null);
                convListView.addFooterView(isTypingView, null, false);
            }

            composeMessageAdapter.setGroupId(groupDbId);
            composeMessageAdapter.setOnClickListener(new ComposeMessageAdapter.OnClickListener() {
                @Override
                public void click(View view, int position, AbstractMessageModel messageModel) {
                    if (actionMode == null && messageModel.isOutbox() && (messageModel.getState() == MessageState.SENDFAILED || messageModel.getState() == MessageState.FS_KEY_MISMATCH) && messageReceiver.isMessageBelongsToMe(messageModel)) {
                        final Set<String> finalRecipientIdentities = new HashSet<>();

                        Runnable resendMessage = () -> ExecutorServices.getSendMessageExecutorService().execute(() -> {
                            try {
                                messageService.resendMessage(messageModel, messageReceiver, null, finalRecipientIdentities, MessageId.random(), TriggerSource.LOCAL);
                            } catch (Exception e) {
                                RuntimeUtil.runOnUiThread(() -> {
                                    if (isAdded()) {
                                        Toast.makeText(getContext(), R.string.original_file_no_longer_avilable, Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        });

                        if (messageModel instanceof GroupMessageModel) {
                            // Check whether sending failed or a fs reject was received
                            if (messageModel.getState() == MessageState.SENDFAILED) {
                                logger.info("Failed group message clicked, trying to re-send");
                                // If sending failed, we try to resend it to every group member
                                finalRecipientIdentities.addAll(groupModel.getRecipients());
                                resendMessage.run();
                                return;
                            }

                            // For group messages we first show a dialog to indicate the affected
                            // recipients
                            MessageId messageId = messageModel.getMessageId();

                            RejectedGroupMessageFactory rejectedGroupMessageFactory = databaseService.getRejectedGroupMessageFactory();
                            finalRecipientIdentities.addAll(rejectedGroupMessageFactory.getMessageRejects(messageId, groupModel));

                            if (finalRecipientIdentities.isEmpty()) {
                                // If there is no rejected recipient, we can just update the message
                                // state as the rejected recipient is no longer a group member.
                                // Note that this should never happen.
                                messageService.updateOutgoingMessageState(messageModel, MessageState.SENT, new Date());
                                logger.warn("Resend for group members requested, although no member rejected it");
                                return;
                            }
                            ResendGroupMessageDialog.getInstance(
                                finalRecipientIdentities,
                                contactService,
                                preferenceService,
                                resendMessage::run
                            ).show(getParentFragmentManager(), DIALOG_TAG_CONFIRM_RESEND);
                        } else {
                            logger.info("Failed message clicked, trying to re-send");
                            finalRecipientIdentities.add(messageModel.getIdentity());
                            resendMessage.run();
                        }
                    } else {
                        logger.info("Message clicked");
                        onListItemClick(view, position, messageModel);
                    }
                }

                @Override
                public void longClick(View view, int position, AbstractMessageModel messageModel) {
                    logger.info("Message long-clicked");
                    onListItemLongClick(view, position);
                }

                @Override
                public boolean touch(View view, MotionEvent motionEvent, AbstractMessageModel messageModel) {
                    if (actionMode != null) {
                        return false;
                    }
                    if (listViewTouchSwipeListener != null) {
                        // performs (long) click manually
                        //  to propagate event to click listeners only after checking for swipe
                        return listViewTouchSwipeListener.onTouch(view, motionEvent);
                    }
                    return false;
                }

                @Override
                public void avatarClick(View view, int position, AbstractMessageModel messageModel) {
                    if (messageModel != null && messageModel.getIdentity() != null) {
                        ContactModel contactModel = contactService.getByIdentity(messageModel.getIdentity());
                        if (contactModel != null) {
                            Intent intent;
                            if (messageModel instanceof GroupMessageModel || messageModel instanceof DistributionListMessageModel) {
                                logger.info("Message avatar clicked in group chat or distribution list, opening compose screen for contact");
                                intent = new Intent(getActivity(), ComposeMessageActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                intent.setData((Uri.parse("foobar://" + SystemClock.elapsedRealtime())));
                                IntentDataUtil.append(contactModel, intent);
                                requireActivity().finish();

                            } else {
                                logger.info("Message avatar clicked in contact chat, opening contact details");
                                intent = new Intent(getActivity(), ContactDetailActivity.class);
                                intent.putExtra(AppConstants.INTENT_DATA_CONTACT_READONLY, true);
                                IntentDataUtil.append(contactModel, intent);
                            }
                            getActivity().startActivity(intent);
                        }
                    }
                }

                @SuppressLint("DefaultLocale")
                @Override
                public void onSearchResultsUpdate(int searchResultsIndex, int searchResultsSize, final int queryLength) {
                    RuntimeUtil.runOnUiThread(() -> {
                        if (searchCounter != null) {
                            try {
                                if (queryLength < MIN_CONSTRAINT_LENGTH && searchResultsSize == 0) {
                                    searchCounter.setText(getString(R.string.min_n_chars, MIN_CONSTRAINT_LENGTH));
                                    searchCounter.setVisibility(View.VISIBLE);
                                    searchPreviousButton.setVisibility(View.INVISIBLE);
                                    searchNextButton.setVisibility(View.INVISIBLE);
                                } else {
                                    searchCounter.setText(String.format("%d / %d", searchResultsIndex, searchResultsSize));
                                    searchCounter.setVisibility(View.VISIBLE);
                                    searchPreviousButton.setVisibility(View.VISIBLE);
                                    searchNextButton.setVisibility(View.VISIBLE);
                                }
                            } catch (Exception e) {
                                //
                            }
                        }
                    });
                }

                @Override
                public void onSearchInProgress(boolean inProgress) {
                    RuntimeUtil.runOnUiThread(() -> {
                        if (searchNextButton != null && searchPreviousButton != null) {
                            try {
                                searchProgress.setVisibility(inProgress ? View.VISIBLE : View.INVISIBLE);
                            } catch (Exception e) {
                                //
                            }
                        }
                    });
                }

                @Override
                public void onEmojiReactionClick(@Nullable String emojiSequence, @Nullable AbstractMessageModel messageModel) {
                    if (isGroupChatWhereUserIsNotMemberOf()) {
                        SingleToast.getInstance().showLongText(getString(R.string.you_are_not_a_member_of_this_group));
                        return;
                    }

                    composeMessageFragmentUtils.onEmojiReactionClicked(emojiSequence, messageModel);
                }

                @Override
                public void onEmojiReactionLongClick(@Nullable String emojiSequence, @Nullable AbstractMessageModel messageModel) {
                    logger.info("Emoji reaction long-clicked");
                    showEmojiReactionsOverview(messageModel, emojiSequence);
                }

                @Override
                public void onSelectButtonClick(@Nullable AbstractMessageModel messageModel) {
                    if (isGroupChatWhereUserIsNotMemberOf()) {
                        RuntimeUtil.runOnUiThread(() ->
                            SingleToast.getInstance().showLongText(getString(R.string.you_are_not_a_member_of_this_group))
                        );
                    } else if (MessageUtil.canEmojiReact(messageModel)) {
                        logger.info("Emoji select button clicked, showing picker");
                        showEmojiReactionsPicker(messageModel);
                    }
                }

                @Override
                public void onMoreReactionsButtonClick(@Nullable AbstractMessageModel messageModel) {
                    logger.info("More reactions button clicked");
                    showEmojiReactionsOverview(messageModel, null);
                }
            });

            insertToList(values, false, !conversationCategoryService.isPrivateChat(messageReceiver.getUniqueIdString()), false);
            convListView.setAdapter(composeMessageAdapter);
            convListView.setItemsCanFocus(false);
            convListView.setVisibility(View.VISIBLE);
        }

        setIdentityColors();

        removeIsTypingFooter();

        // F1Whisper: refresh the pinned-message banner after the list is populated
        updatePinnedBanner();
    }

    /**
     * @return {@code true} if the user is not a member of this group chat. In case we are in a group conversation, but we could not determine the
     * current member status we also return {@code true}.
     */
    private boolean isGroupChatWhereUserIsNotMemberOf() {
        if (!isGroupChat || groupModel == null) {
            return false;
        }
        return !groupModel.isMember();
    }

    private void showEmojiReactionsOverview(@Nullable AbstractMessageModel messageModel, @Nullable String emojiSequence) {
        if (messageModel == null) {
            logger.error("MessageModel is null");
            return;
        }

        Intent intent = new Intent(activity, EmojiReactionsOverviewActivity.class);
        IntentDataUtil.append(messageModel, intent);
        if (emojiSequence != null) {
            intent.putExtra(EmojiReactionsOverviewActivity.EXTRA_INITIAL_EMOJI, emojiSequence);
        }
        emojiReactionsLauncher.launch(intent);
        rootInsetsDeferringCallback.setEnabled(false);
        keyboardAnimationInsetsCallback.setEnabled(false);
    }

    // F1Whisper: bound the unread-anchor retries (see jumpToFirstUnreadMessage). ~6 x 120ms covers
    // the async populate of a large unread list without a noticeable delay.
    private static final int UNREAD_ANCHOR_MAX_RETRIES = 6;
    private static final int UNREAD_ANCHOR_RETRY_DELAY_MS = 120;

    /**
     * Jump to first unread message keeping in account shift caused by date separators and other decorations
     * Currently depends on various globals...
     */
    @UiThread
    private void jumpToFirstUnreadMessage() {
        jumpToFirstUnreadMessage(0);
    }

    /**
     * F1Whisper: when opening a chat with unread messages, the unread divider
     * ({@link FirstUnreadMessageModel}) may not be in the list yet because a large unread list
     * (> MESSAGE_PAGE_SIZE) is populated on a background task while this anchor runs from onResume.
     * Upstream fell straight through to {@link android.widget.ListView#setSelection(int)} with
     * {@code Integer.MAX_VALUE} (the bottom) in that case, which randomly defeated the unread anchor.
     * Instead we retry a bounded number of times until the divider appears, keeping {@code unreadCount}
     * intact, and only as a last resort anchor on the arithmetic start of the unread region (never the
     * bottom, never the very top).
     */
    @UiThread
    private void jumpToFirstUnreadMessage(int attempt) {
        if (unreadCount > 0) {
            synchronized (this.messageValues) {
                int entryCount = convListView.getCount();
                int arithmeticPosition = Math.min(entryCount - unreadCount, this.messageValues.size() - 1);
                int position = arithmeticPosition;
                while (position >= 0) {
                    if (this.messageValues.get(position) instanceof FirstUnreadMessageModel) {
                        break;
                    }
                    position--;
                }

                if (position > 0) {
                    final int finalUnreadCount = unreadCount;
                    if (!isHidden()) {
                        unreadCount = 0;
                    }
                    final int finalPosition = position;
                    logger.debug("jump to initial position {}", finalPosition);

                    convListView.setSelection(finalPosition);
                    convListView.postDelayed(() -> {
                        convListView.setSelection(finalPosition);
                        if (convListView.canScrollList(View.SCROLL_AXIS_VERTICAL)) {
                            if (scrollButtonManager != null) {
                                scrollButtonManager.showButton(TYPE_DOWN, finalUnreadCount);
                            }
                        }
                    }, 500);

                    return;
                }

                // Divider not in the (still-populating) list yet: retry instead of jumping to the
                // bottom, and do NOT zero unreadCount so the retry can still find it.
                if (attempt < UNREAD_ANCHOR_MAX_RETRIES) {
                    convListView.postDelayed(() -> jumpToFirstUnreadMessage(attempt + 1), UNREAD_ANCHOR_RETRY_DELAY_MS);
                    return;
                }

                // Last resort: the divider never materialized. Anchor on the arithmetic start of the
                // unread region (an O(1) list index) rather than the bottom or the very top.
                if (!isHidden()) {
                    unreadCount = 0;
                }
                convListView.setSelection(Math.max(0, arithmeticPosition));
                return;
            }
        }
        convListView.setSelection(Integer.MAX_VALUE);
    }

    private void setIdentityColors() {
        logger.debug("setIdentityColors");

        if (this.isGroupChat) {
            Map<String, IdColor> colorIndices = groupService.getGroupParticipantIDColors(groupModel);
            Map<String, Integer> colors = new HashMap<>();
            boolean darkTheme = ConfigUtils.isTheDarkSide(getContext());
            for (Map.Entry<String, IdColor> entry : colorIndices.entrySet()) {
                String memberIdentity = entry.getKey();
                IdColor memberIdColor = entry.getValue();
                int idColor = darkTheme
                    ? memberIdColor.getColorDark()
                    : memberIdColor.getColorLight();
                colors.put(memberIdentity, idColor);
            }

            this.identityColors = colors;
        } else {
            this.identityColors.clear();
        }
        this.composeMessageAdapter.setIdentityColors(this.identityColors);
    }

    private void onListItemClick(View view, int position, AbstractMessageModel messageModel) {
        if (view == null) {
            return;
        }

        if (actionMode != null) {
            if (selectedMessages.contains(messageModel)) {
                // remove from selection
                selectedMessages.remove(messageModel);
                convListView.setItemChecked(position, false);
                logger.info("Message deselected for action mode");
            } else {
                if (convListView.getCheckedItemCount() < MAX_SELECTED_ITEMS &&
                    isItemSelectable(composeMessageAdapter.getItemViewType(position), messageModel)) {
                    // add this to selection
                    selectedMessages.add(messageModel);
                    convListView.setItemChecked(position, true);
                    logger.info("Message selected for action mode");
                } else {
                    convListView.setItemChecked(position, false);
                    logger.info("Message deselected for action mode, limit reached or not selectable");
                }
            }

            final int checked = convListView.getCheckedItemCount();
            if (checked > 0) {
                // invalidate menu to update display => onPrepareActionMode()
                actionMode.invalidate();
            } else {
                actionMode.finish();
            }
        } else {
            if (view.isSelected()) {
                view.setSelected(false);
            }
            if (convListView.isItemChecked(position)) {
                convListView.setItemChecked(position, false);
            }
            // check if item is a quote
            if (QuoteUtil.isQuoteV1(messageModel.getBody())) {
                QuoteUtil.QuoteContent quoteContent = QuoteUtil.getQuoteContent(
                    messageModel,
                    messageReceiver,
                    false,
                    thumbnailCache,
                    getContext(),
                    this.messageService,
                    this.userService,
                    this.fileService,
                    this.preferenceService.getContactNameFormat()
                );

                if (quoteContent != null) {
                    if (searchActionMode != null) {
                        searchActionMode.finish();
                    }

                    ComposeMessageAdapter.ConversationListFilter filter = (ComposeMessageAdapter.ConversationListFilter) composeMessageAdapter.getQuoteFilter(quoteContent);
                    // search for quoted text
                    filter.filter(quoteContent.quotedText, count -> {
                        if (count == 0) {
                            SingleToast.getInstance().showShortText(getString(R.string.quote_not_found));
                        }
                    });
                }
            } else if (messageModel.getQuotedMessageId() != null) {
                QuoteUtil.QuoteContent quoteContent = QuoteUtil.getQuoteContent(
                    messageModel,
                    messageReceiver,
                    false,
                    thumbnailCache,
                    getContext(),
                    this.messageService,
                    this.userService,
                    this.fileService,
                    this.preferenceService.getContactNameFormat()
                );
                if (quoteContent != null) {
                    if (searchActionMode != null) {
                        searchActionMode.finish();
                    }

                    AbstractMessageModel quotedMessageModel = messageService.getMessageModelByApiMessageIdAndReceiver(messageModel.getQuotedMessageId(), messageReceiver);
                    logger.info("Trying to jump to quoted message");
                    // F1Whisper: a disappearing quoted message that is now overdue is removed here so
                    // the jump degrades to "message deleted" instead of reviving a gone message.
                    if (quotedMessageModel != null && DisappearingMessageService.enforceIfExpired(quotedMessageModel)) {
                        quotedMessageModel = null;
                    }
                    if (quotedMessageModel != null) {
                        ComposeMessageAdapter.ConversationListFilter filter = (ComposeMessageAdapter.ConversationListFilter) composeMessageAdapter.getQuoteFilter(quoteContent);
                        searchV2Quote(quotedMessageModel.getApiMessageId(), filter);
                    } else {
                        Toast.makeText(getContext().getApplicationContext(), R.string.quoted_message_deleted, Toast.LENGTH_SHORT).show();
                    }
                }
            } else if ((messageModel.getType() == MessageType.TEXT && !messageModel.isStatusMessage()) || messageModel.isDeleted()) {
                logger.info("Opening message details screen");
                showMessageDetailScreen(messageModel);
            }
        }
    }

    /**
     * Recursively search for message with provided apiMessageId in chat and gradually load more records to Adapter until matching message is found by provided Filter
     *
     * @param apiMessageId to search for
     * @param filter       Filter to use for this search
     */
    @UiThread
    synchronized private void searchV2Quote(final String apiMessageId, final ComposeMessageAdapter.ConversationListFilter filter) {
        filter.filter("#" + apiMessageId, new Filter.FilterListener() {
            @SuppressLint("StaticFieldLeak")
            @Override
            public void onFilterComplete(int count) {
                if (count == 0) {
                    new AsyncTask<Void, Void, Integer>() {
                        List<AbstractMessageModel> messageModels;

                        @Override
                        protected Integer doInBackground(Void... params) {
                            messageModels = getNextRecords();
                            if (messageModels != null) {
                                return messageModels.size();
                            }
                            return null;
                        }

                        @Override
                        protected void onPostExecute(Integer result) {
                            if (getContext() != null) {
                                if (result != null && result > 0) {
                                    insertToList(messageModels, false, false, true);

                                    if (getFragmentManager() != null) {
                                        if (getFragmentManager().findFragmentByTag(DIALOG_TAG_SEARCHING) == null) {
                                            GenericProgressDialog.newInstance(R.string.searching, R.string.please_wait).show(getFragmentManager(), DIALOG_TAG_SEARCHING);
                                        }
                                        searchV2Quote(apiMessageId, filter);
                                    }
                                } else {
                                    SingleToast.getInstance().showShortText(getString(R.string.quote_not_found));
                                    swipeRefreshLayout.setEnabled(false);
                                    DialogUtil.dismissDialog(getParentFragmentManager(), DIALOG_TAG_SEARCHING, true);
                                }
                            }
                        }
                    }.execute();
                } else {
                    DialogUtil.dismissDialog(getFragmentManager(), DIALOG_TAG_SEARCHING, true);
                }
            }
        });
    }

    @UiThread
    private void onListItemLongClick(@NonNull View view, final int position) {
        int viewType = composeMessageAdapter.getItemViewType(position);
        AbstractMessageModel selectedMessage = composeMessageAdapter.getItem(position);

        if (!isItemSelectable(viewType, selectedMessage)) {
            return;
        }

        selectedMessages.clear();
        selectedMessages.add(selectedMessage);

        if (actionMode != null) {
            convListView.clearChoices();
            convListView.setItemChecked(position, true);
            actionMode.invalidate();
        } else {
            convListView.setChoiceMode(CHOICE_MODE_MULTIPLE);
            convListView.setItemChecked(position, true);
            view.setSelected(true);
            actionMode = activity.startSupportActionMode(new ComposeMessageAction(position));
        }

        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        // fix linkify on longclick problem
        // see: http://stackoverflow.com/questions/16047215/android-how-to-stop-linkify-on-long-press
        longClickItem = position;

        if (viewType == ComposeMessageAdapter.TYPE_STATUS_DATA_RECV ||
            viewType == ComposeMessageAdapter.TYPE_STATUS_DATA_SEND) {
            // Don't show popup for these view types (but allow them to be selected)
            return;
        }

        showEmojiReactionsPopup(view, selectedMessage);
    }

    private void showEmojiReactionsPopup(@NonNull View originView, @NonNull AbstractMessageModel messageModel) {
        if (messageReceiver == null) {
            logger.error("No MessageReceiver to show emoji reactions popup for");
            return;
        }

        // Don't even show popup in case of a DistributionList
        if (messageReceiver instanceof DistributionListMessageReceiver) {
            logger.debug("Cannot react on distribution list messages");
            return;
        }

        // Don't show the reaction popup if we are in a group chat and we are not an active group member anymore
        if (isGroupChatWhereUserIsNotMemberOf()) {
            logger.debug("Cannot react on group message because we are not an active group member anymore");
            return;
        }

        // check if we can react on this kind of message
        if (!MessageUtil.canEmojiReact(messageModel)) {
            return;
        }

        boolean isReactionsSupportNone = messageReceiver.getEmojiReactionSupport() == MessageReceiver.Reactions_NONE;

        if (messageReceiver instanceof ContactMessageReceiver
            && isReactionsSupportNone
            && messageModel.isOutbox()) {
            logger.debug("Cannot react on my own messages if reactions are not yet supported by recipient");
            return;
        }

        boolean isGatewayChat = isGatewayChat(messageReceiver);
        boolean isSendingReactionsAllowed = !isReactionsSupportNone && !isGatewayChat;
        emojiReactionsPopup = new EmojiReactionsPopup(
            requireContext(),
            convListView,
            getParentFragmentManager(),
            isSendingReactionsAllowed,
            isGatewayChat);
        emojiReactionsPopup.setListener(new EmojiReactionsPopup.EmojiReactionsPopupListener() {
            @Override
            public void onTopReactionClicked(@NonNull final AbstractMessageModel messageModel, @NonNull final String emojiSequence) {
                RuntimeUtil.runOnWorkerThread(() -> {
                    try {
                        messageService.sendEmojiReaction(messageModel, emojiSequence, Objects.requireNonNull(messageReceiver), false);
                    } catch (Exception e) {
                        logger.error("Failed to send emoji reaction", e);
                    }
                });

                if (actionMode == null) {
                    return;
                }
                actionMode.finish();
            }

            @Override
            public void onAddReactionClicked(@NonNull final AbstractMessageModel messageModel) {
                showEmojiReactionsPicker(messageModel);
            }
        });
        emojiReactionsPopup.show(originView.findViewById(R.id.message_block), selectedMessages.get(0));
    }

    private void showEmojiReactionsPicker(@Nullable AbstractMessageModel messageModel) {
        if (messageModel != null && messageReceiver != null) {
            Intent intent = new Intent(activity, EmojiReactionsPickerActivity.class);
            IntentDataUtil.append(messageModel, intent);
            emojiReactionsLauncher.launch(intent);
            rootInsetsDeferringCallback.setEnabled(false);
            keyboardAnimationInsetsCallback.setEnabled(false);
        } else {
            logger.debug("MessageModel or Receiver is null");
        }
    }

    /**
     * Check whether the selected item in the conversation list can be selected
     *
     * @param viewType        View type of the item
     * @param selectedMessage Message Model of the item
     * @return true if item is selectable, false otherwise
     */
    @Contract("_, null -> false")
    private boolean isItemSelectable(int viewType, @Nullable AbstractMessageModel selectedMessage) {
        if (viewType == ComposeMessageAdapter.TYPE_FIRST_UNREAD ||
            viewType == ComposeMessageAdapter.TYPE_DATE_SEPARATOR) {
            // Do not allow to select these view types
            return false;
        }

        if (selectedMessage == null) {
            return false;
        }

        if (viewType == ComposeMessageAdapter.TYPE_FILE_VIDEO_SEND && selectedMessage.getState() == MessageState.TRANSCODING) {
            // transcoding messages cannot be selected
            return false;
        }

        return true;
    }

    private boolean isMuted() {
        if (messageReceiver == null) {
            return false;
        }
        final @Nullable NotificationTriggerPolicyOverride currentNotificationTriggerPolicyOverride = messageReceiver.getNotificationTriggerPolicyOverrideOrNull();
        return currentNotificationTriggerPolicyOverride != null && currentNotificationTriggerPolicyOverride.getMuteAppliesRightNow();
    }

    private boolean isMentionsOnly() {
        if (messageReceiver == null) {
            return false;
        }
        final @Nullable NotificationTriggerPolicyOverride currentNotificationTriggerPolicyOverride = messageReceiver.getNotificationTriggerPolicyOverrideOrNull();
        return currentNotificationTriggerPolicyOverride instanceof NotificationTriggerPolicyOverride.MutedIndefiniteExceptMentions;
    }

    private boolean isSilent() {
        if (messageReceiver != null && ringtoneService != null) {
            String uniqueId = messageReceiver.getUniqueIdString();
            return !TestUtil.isEmptyOrNull(uniqueId) && ringtoneService.hasCustomRingtone(uniqueId) && ringtoneService.isSilent(uniqueId, isGroupChat);
        }
        return false;
    }

    private void playInAppSound(final int resId, final boolean isVibrate) {
        if (isMuted() || isSilent()) {
            //do not play
            return;
        }
        var context = requireContext().getApplicationContext();
        RuntimeUtil.runOnUiThread(() -> {
            if (preferenceService.isInAppSounds()) {
                soundEffectPlayer.play(resId);
            }

            if (preferenceService.isInAppVibrate() && isVibrate) {
                Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    switch (audioManager.getRingerMode()) {
                        case AudioManager.RINGER_MODE_VIBRATE:
                        case AudioManager.RINGER_MODE_NORMAL:
                            vibrator.vibrate(VIBRATION_MSEC);
                            break;
                        default:
                            break;
                    }
                }
            }
        });
    }

    private void playSentSound() {
        playInAppSound(R.raw.sent_message, false);
    }

    private void playReceivedSound() {
        playInAppSound(R.raw.received_message, true);
    }

    private void sendMessage() {
        if (typingIndicatorTextWatcher != null) {
            typingIndicatorTextWatcher.stopSending();
        }

        if (!this.validateSendingPermission()) {
            return;
        }

        if (!TestUtil.isBlankOrNull(this.messageText.getText())) {
            prepareSendTextMessage();
        } else {
            if (ConfigUtils.requestAudioPermissions(requireActivity(), this, PERMISSION_REQUEST_ATTACH_VOICE_MESSAGE)) {
                attachVoiceMessage();
            }
        }
    }

    private void prepareSendTextMessage() {
        final CharSequence message;
        final boolean isQuote = isQuotePopupShown();

        if (isQuote) {
            QuotePopup.QuoteInfo quoteInfo = quotePopup.getQuoteInfo();
            message = QuoteUtil.quote(
                this.messageText.getText().toString(),
                quoteInfo.getQuoteIdentity(),
                quoteInfo.getQuoteText(),
                quoteInfo.getMessageModel()
            );

            messageText.postDelayed(this::dismissQuotePopup, 500);
        } else {
            message = this.messageText.getText();
        }

        if (!TestUtil.isBlankOrNull(message)) {
            // F1Whisper: if a link preview was prepared for this (non-quoted) text, send it as a
            // Signal-style preview media message (og:image + caption + E2E metadata) instead of a
            // plain text message. The preview was fetched on THIS device only.
            final LinkPreviewResult preview = (linkPreviewController != null && !isQuote)
                ? linkPreviewController.consume(message.toString())
                : null;
            if (preview != null) {
                sendLinkPreviewMessage(preview, message.toString());
            } else {
                sendTextMessage(message);
            }
        } else {
            logger.warn("Message text is empty");
        }
    }

    /**
     * F1Whisper: send a Signal-style link preview as an image media message. The og:image (or a
     * generated placeholder when the page had none) becomes the image blob, the user's text becomes
     * the caption, and the preview url/title/description ride in the E2E file metadata so a receiving
     * F1Whisper client renders the card without ever contacting the URL.
     */
    private void sendLinkPreviewMessage(@NonNull LinkPreviewResult preview, @NonNull String text) {
        // Clear the input immediately, like a normal text send.
        this.messageText.setText("");
        if (typingIndicatorTextWatcher != null) {
            typingIndicatorTextWatcher.stopSending();
        }
        if (messageReceiver == null) {
            return;
        }

        final MessageReceiver receiver = messageReceiver;
        new Thread(() -> {
            try {
                byte[] imageBytes = preview.getImageBytes();
                if (imageBytes == null || imageBytes.length == 0) {
                    imageBytes = LinkPreviewImageFactory.createPlaceholder(getContext(), preview.getUrl());
                }
                if (imageBytes == null) {
                    // No image at all -> fall back to a plain text message so we still send something.
                    RuntimeUtil.runOnUiThread(() -> sendTextMessage(text));
                    return;
                }

                final Uri uri = LinkPreviewImageFactory.writeTempImage(getContext(), imageBytes);
                if (uri == null) {
                    RuntimeUtil.runOnUiThread(() -> sendTextMessage(text));
                    return;
                }

                final MediaItem mediaItem = new MediaItem(uri, MediaItem.TYPE_IMAGE, MimeUtil.MIME_TYPE_IMAGE_JPEG, text);
                mediaItem.setFilename("preview.jpg");
                mediaItem.setDeleteAfterUse(true);
                mediaItem.setLinkPreview(preview.getUrl(), preview.getTitle(), preview.getDescription());

                messageService.sendMediaAsync(
                    Collections.singletonList(mediaItem),
                    Collections.singletonList(receiver));
            } catch (Exception e) {
                logger.error("Failed to send link preview", e);
                RuntimeUtil.runOnUiThread(() -> sendTextMessage(text));
            }
        }).start();
    }

    // region F1Whisper scheduled messages

    /**
     * Telegram-style scheduling: a single bottom sheet with smooth day / hour / minute wheels
     * (plus an AM/PM wheel on 12-hour locales). On confirm, schedule the currently typed message.
     */
    private void showScheduleMessagePicker() {
        showScheduleMessagePicker(null);
    }

    /**
     * Show the day/time wheel picker. When {@code existing} is {@code null} the picker schedules the
     * currently typed message; otherwise it reschedules the given pending message (seeded with its
     * current fire time).
     */
    private void showScheduleMessagePicker(@Nullable final ScheduledMessageModel existing) {
        final Context context = getContext();
        if (messageReceiver == null || context == null) {
            return;
        }

        final boolean is24h = DateFormat.is24HourFormat(context);
        final BottomSheetDialog dialog = new BottomSheetDialog(context);
        final View sheet = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_schedule_message, null);
        dialog.setContentView(sheet);

        final NumberPicker dayPicker = sheet.findViewById(R.id.day_picker);
        final NumberPicker hourPicker = sheet.findViewById(R.id.hour_picker);
        final NumberPicker minutePicker = sheet.findViewById(R.id.minute_picker);
        final NumberPicker ampmPicker = sheet.findViewById(R.id.ampm_picker);
        final MaterialButton confirmButton = sheet.findViewById(R.id.schedule_confirm_button);

        // Seed the wheels from the existing fire time when rescheduling, otherwise from now.
        final Calendar seed = Calendar.getInstance();
        if (existing != null && existing.getScheduledAt() > System.currentTimeMillis()) {
            seed.setTimeInMillis(existing.getScheduledAt());
        }

        // Day offset (in calendar days) of the seed relative to today, for the day wheel default.
        final Calendar startOfToday = Calendar.getInstance();
        startOfToday.set(Calendar.HOUR_OF_DAY, 0);
        startOfToday.set(Calendar.MINUTE, 0);
        startOfToday.set(Calendar.SECOND, 0);
        startOfToday.set(Calendar.MILLISECOND, 0);
        final long dayMillis = 24L * 60 * 60 * 1000;
        int seedDayOffset = (int) ((seed.getTimeInMillis() - startOfToday.getTimeInMillis()) / dayMillis);

        // Day wheel: a year of days, labelled Today / Tomorrow / a localized weekday-date.
        final int daysAhead = 365;
        if (seedDayOffset < 0) {
            seedDayOffset = 0;
        } else if (seedDayOffset > daysAhead) {
            seedDayOffset = daysAhead;
        }
        final String[] dayLabels = new String[daysAhead + 1];
        final Calendar dayCursor = Calendar.getInstance();
        for (int i = 0; i <= daysAhead; i++) {
            if (i == 0) {
                dayLabels[i] = context.getString(R.string.schedule_today);
            } else if (i == 1) {
                dayLabels[i] = context.getString(R.string.schedule_tomorrow);
            } else {
                dayLabels[i] = DateUtils.formatDateTime(
                    context,
                    dayCursor.getTimeInMillis(),
                    DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_ABBREV_ALL | DateUtils.FORMAT_NO_YEAR);
            }
            dayCursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        dayPicker.setMinValue(0);
        dayPicker.setMaxValue(daysAhead);
        dayPicker.setDisplayedValues(dayLabels);
        dayPicker.setWrapSelectorWheel(false);
        dayPicker.setValue(seedDayOffset);

        // Minute wheel: 00..59, zero-padded.
        final NumberPicker.Formatter twoDigits =
            value -> String.format(java.util.Locale.getDefault(), "%02d", value);
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        minutePicker.setFormatter(twoDigits);
        minutePicker.setValue(seed.get(Calendar.MINUTE));
        minutePicker.setWrapSelectorWheel(true);

        // Hour wheel: 0..23 on 24h locales, else 1..12 with an AM/PM wheel.
        if (is24h) {
            hourPicker.setMinValue(0);
            hourPicker.setMaxValue(23);
            hourPicker.setFormatter(twoDigits);
            hourPicker.setValue(seed.get(Calendar.HOUR_OF_DAY));
            ampmPicker.setVisibility(View.GONE);
        } else {
            hourPicker.setMinValue(1);
            hourPicker.setMaxValue(12);
            int hour12 = seed.get(Calendar.HOUR);
            hourPicker.setValue(hour12 == 0 ? 12 : hour12);
            final String[] ampmLabels =
                new java.text.DateFormatSymbols(java.util.Locale.getDefault()).getAmPmStrings();
            ampmPicker.setMinValue(0);
            ampmPicker.setMaxValue(1);
            ampmPicker.setDisplayedValues(ampmLabels);
            ampmPicker.setValue(seed.get(Calendar.AM_PM));
            ampmPicker.setWrapSelectorWheel(false);
            ampmPicker.setVisibility(View.VISIBLE);
        }

        confirmButton.setOnClickListener(v -> {
            final Calendar scheduled = Calendar.getInstance();
            scheduled.add(Calendar.DAY_OF_MONTH, dayPicker.getValue());
            final int hourOfDay;
            if (is24h) {
                hourOfDay = hourPicker.getValue();
            } else {
                final int base12 = hourPicker.getValue() % 12; // 12 -> 0
                hourOfDay = ampmPicker.getValue() == Calendar.PM ? base12 + 12 : base12;
            }
            scheduled.set(Calendar.HOUR_OF_DAY, hourOfDay);
            scheduled.set(Calendar.MINUTE, minutePicker.getValue());
            scheduled.set(Calendar.SECOND, 0);
            scheduled.set(Calendar.MILLISECOND, 0);
            final long atMillis = scheduled.getTimeInMillis();

            if (atMillis <= System.currentTimeMillis()) {
                Toast.makeText(getActivity(), R.string.scheduled_time_in_past, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            if (existing != null) {
                ScheduledMessageService.getInstance().reschedule(existing.getId(), atMillis);
                Toast.makeText(getActivity(), R.string.scheduled_message_rescheduled, Toast.LENGTH_SHORT).show();
                updateScheduledMessagesBar();
            } else {
                scheduleCurrentMessage(atMillis);
            }
        });

        dialog.show();
    }

    /**
     * Persist the currently typed message (quote-encoded like an immediate send) into the
     * scheduled-messages store for the given fire time.
     */
    private void scheduleCurrentMessage(long atMillis) {
        if (messageReceiver == null) {
            return;
        }

        final CharSequence message;
        if (isQuotePopupShown()) {
            QuotePopup.QuoteInfo quoteInfo = quotePopup.getQuoteInfo();
            message = QuoteUtil.quote(
                this.messageText.getText().toString(),
                quoteInfo.getQuoteIdentity(),
                quoteInfo.getQuoteText(),
                quoteInfo.getMessageModel()
            );
            messageText.postDelayed(this::dismissQuotePopup, 500);
        } else {
            message = this.messageText.getText();
        }

        if (TestUtil.isBlankOrNull(message)) {
            logger.warn("Scheduled message text is empty");
            return;
        }

        ScheduledMessageService.getInstance().schedule(messageReceiver, message.toString(), atMillis);
        messageText.setText("");
        LongToast.makeText(getActivity(), R.string.message_scheduled, Toast.LENGTH_SHORT).show();
        updateScheduledMessagesBar();
    }

    /**
     * F1Whisper: show the disappearing-messages duration picker (Signal-style fixed presets). On
     * confirm the per-conversation timer is set via {@link DisappearingMessageService}, which persists
     * it, sends the control message to the peer/members, and inserts the local status message.
     */
    private void showDisappearingMessagesPicker() {
        final Context context = getContext();
        if (messageReceiver == null || context == null) {
            return;
        }

        // Current conversation timer (seconds; null/0 = off) used to pre-select the wheel.
        Integer currentTimer = null;
        if (messageReceiver instanceof ContactMessageReceiver) {
            currentTimer = ((ContactMessageReceiver) messageReceiver).getContact().getDisappearingMessagesTimerSeconds();
        } else if (messageReceiver instanceof GroupMessageReceiver) {
            currentTimer = ((GroupMessageReceiver) messageReceiver).getGroup().getDisappearingMessagesTimerSeconds();
        }

        final BottomSheetDialog dialog = new BottomSheetDialog(context);
        final View sheet = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_disappearing_messages, null);
        dialog.setContentView(sheet);

        final NumberPicker durationPicker = sheet.findViewById(R.id.duration_picker);
        final MaterialButton confirmButton = sheet.findViewById(R.id.disappearing_confirm_button);

        final String[] labels = DisappearingMessageUtil.getPickerLabels(context);
        durationPicker.setMinValue(0);
        durationPicker.setMaxValue(labels.length - 1);
        durationPicker.setDisplayedValues(labels);
        durationPicker.setWrapSelectorWheel(false);
        durationPicker.setValue(DisappearingMessageUtil.indexForSeconds(currentTimer));

        confirmButton.setOnClickListener(v -> {
            final int seconds = DisappearingMessageUtil.DURATIONS_SECONDS[durationPicker.getValue()];
            dialog.dismiss();
            final MessageReceiver<?> receiver = this.messageReceiver;
            if (receiver != null) {
                // Service persists the timer, sends the control message, and inserts the status row.
                DisappearingMessageService.getInstance().setConversationTimer(receiver, seconds);
            }
        });

        dialog.show();
    }

    @Nullable
    private String scheduledReceiverKey() {
        if (messageReceiver == null) {
            return null;
        }
        return ScheduledMessageService.receiverKeyOf(messageReceiver);
    }

    /**
     * Refresh the scheduled-messages indicator chip for the current conversation.
     */
    private void updateScheduledMessagesBar() {
        if (scheduledMessagesBar == null || messageReceiver == null) {
            return;
        }
        final int receiverType = messageReceiver.getType();
        final String receiverKey = scheduledReceiverKey();
        if (receiverKey == null) {
            scheduledMessagesBar.setVisibility(View.GONE);
            return;
        }
        new Thread(() -> {
            final int count = ScheduledMessageService.getInstance().countByReceiver(receiverType, receiverKey);
            RuntimeUtil.runOnUiThread(() -> {
                if (scheduledMessagesBar == null) {
                    return;
                }
                if (count > 0) {
                    if (scheduledMessagesCount != null) {
                        scheduledMessagesCount.setText(getString(R.string.scheduled_messages_count, count));
                    }
                    scheduledMessagesBar.setVisibility(View.VISIBLE);
                } else {
                    scheduledMessagesBar.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    /**
     * Show a dialog listing the pending scheduled messages for this conversation, each cancellable.
     */
    private void showScheduledMessagesDialog() {
        if (messageReceiver == null) {
            return;
        }
        final int receiverType = messageReceiver.getType();
        final String receiverKey = scheduledReceiverKey();
        if (receiverKey == null) {
            return;
        }

        new Thread(() -> {
            final List<ScheduledMessageModel> models =
                ScheduledMessageService.getInstance().getByReceiver(receiverType, receiverKey);
            RuntimeUtil.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                if (models.isEmpty()) {
                    Toast.makeText(getActivity(), R.string.no_scheduled_messages, Toast.LENGTH_SHORT).show();
                    updateScheduledMessagesBar();
                    return;
                }

                final CharSequence[] entries = new CharSequence[models.size()];
                for (int i = 0; i < models.size(); i++) {
                    ScheduledMessageModel model = models.get(i);
                    Date when = new Date(model.getScheduledAt());
                    String dateStr = DateUtils.formatDateTime(getContext(), when.getTime(),
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
                    String preview = model.getBody();
                    if (preview != null && preview.length() > 40) {
                        preview = preview.substring(0, 40) + "…";
                    }
                    entries[i] = getString(R.string.scheduled_message_send_at, dateStr, preview != null ? preview : "");
                }

                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.scheduled_messages_title)
                    .setItems(entries, (dialog, which) ->
                        showScheduledMessageActions(models.get(which)))
                    .setNegativeButton(R.string.close, null)
                    .show();
            });
        }).start();
    }

    /**
     * Per-item action chooser for a pending scheduled message: send now, edit text, reschedule, or
     * delete (Telegram-style scheduled-message management).
     */
    private void showScheduledMessageActions(@NonNull ScheduledMessageModel model) {
        if (!isAdded()) {
            return;
        }
        final CharSequence[] actions = new CharSequence[]{
            getString(R.string.scheduled_message_action_send_now),
            getString(R.string.edit),
            getString(R.string.scheduled_message_action_reschedule),
            getString(R.string.delete),
        };
        new MaterialAlertDialogBuilder(requireContext())
            .setItems(actions, (d, which) -> {
                switch (which) {
                    case 0: // send now
                        new Thread(() -> {
                            ScheduledMessageService.getInstance().sendNow(model.getId());
                            RuntimeUtil.runOnUiThread(() -> {
                                if (!isAdded()) {
                                    return;
                                }
                                Toast.makeText(getActivity(), R.string.message_sent, Toast.LENGTH_SHORT).show();
                                updateScheduledMessagesBar();
                            });
                        }).start();
                        break;
                    case 1: // edit text
                        showEditScheduledMessageText(model);
                        break;
                    case 2: // reschedule
                        showScheduleMessagePicker(model);
                        break;
                    case 3: // delete
                        new MaterialAlertDialogBuilder(requireContext())
                            .setMessage(R.string.scheduled_message_cancel)
                            .setPositiveButton(R.string.ok, (dd, w) -> {
                                ScheduledMessageService.getInstance().cancel(model.getId());
                                Toast.makeText(getActivity(), R.string.scheduled_message_canceled, Toast.LENGTH_SHORT).show();
                                updateScheduledMessagesBar();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                        break;
                    default:
                        break;
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /**
     * Edit the body text of a pending scheduled message in place (fire time unchanged).
     */
    private void showEditScheduledMessageText(@NonNull ScheduledMessageModel model) {
        if (!isAdded()) {
            return;
        }
        final EditText input = new EditText(requireContext());
        input.setText(model.getBody());
        input.setSelection(input.getText().length());
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);
        final FrameLayout container = new FrameLayout(requireContext());
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scheduled_message_edit_title)
            .setView(container)
            .setPositiveButton(R.string.ok, (d, w) -> {
                final String newBody = input.getText().toString();
                if (TestUtil.isBlankOrNull(newBody)) {
                    return;
                }
                new Thread(() -> {
                    ScheduledMessageService.getInstance().updateBody(model.getId(), newBody);
                    RuntimeUtil.runOnUiThread(() -> {
                        if (isAdded()) {
                            Toast.makeText(getActivity(), R.string.scheduled_message_updated, Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // endregion

    private void sendTextMessage(CharSequence message) {
        // block send button to avoid double posting
        this.messageText.setText("");

        if (typingIndicatorTextWatcher != null) {
            messageText.removeTextChangedListener(typingIndicatorTextWatcher);
        }

        if (typingIndicatorTextWatcher != null) {
            messageText.addTextChangedListener(typingIndicatorTextWatcher);
        }

        //send stopped typing message
        if (typingIndicatorTextWatcher != null) {
            typingIndicatorTextWatcher.stopSending();
        }

        new Thread(() -> TextMessageSendAction.getInstance()
            .sendTextMessage(new MessageReceiver[]{messageReceiver}, message.toString(), new SendAction.ActionHandler() {
                @Override
                public void onError(final String errorMessage) {
                    RuntimeUtil.runOnUiThread(() -> {
                        LongToast.makeText(getActivity(), errorMessage, Toast.LENGTH_LONG).show();
                        if (!TestUtil.isBlankOrNull(message)) {
                            messageText.setText(message);
                            messageText.setSelection(messageText.length());
                        }
                    });
                }

                @Override
                public void onWarning(String warning, boolean continueAction) {
                }

                @Override
                public void onProgress(final int progress, final int total) {
                }

                @Override
                public void onCompleted() {
                    RuntimeUtil.runOnUiThread(() -> {
                        if (ConfigUtils.isTabletLayout() && messageReceiver != null) {
                            // remove draft right now to make sure conversations pane is updated
                            draftManager.remove(messageReceiver.getUniqueIdString());
                        }
                    });
                }
            })).start();
    }

    private void attachVoiceMessage() {
        dismissQuotePopup();

        // stop all message players
        if (this.messagePlayerService != null) {
            this.messagePlayerService.pauseAll(SOURCE_AUDIORECORDER);
        }

        Intent intent = new Intent(activity, VoiceRecorderActivity.class);
        IntentDataUtil.addMessageReceiverToIntent(intent, messageReceiver);
        activity.startActivityForResult(intent, ACTIVITY_ID_VOICE_RECORDER);
        activity.overridePendingTransition(R.anim.fast_fade_in, R.anim.fast_fade_out);
    }

    private void tryEditingSelectedMessage() {
        if (selectedMessages.size() != 1) {
            logger.error("Cannot edit more than one selected message.");
            return;
        }

        AbstractMessageModel message = selectedMessages.get(0);

        if (messageReceiver instanceof ContactMessageReceiver) {
            ContactMessageReceiver receiver = (ContactMessageReceiver) messageReceiver;
            startEditingContactMessage(receiver, message);
        } else if (messageReceiver instanceof GroupMessageReceiver && groupModel != null) {
            startEditingGroupMessage(groupModel, message);
        } else if (messageReceiver != null) {
            logger.error("Cannot edit message for receiver of type {}", messageReceiver.getClass().getName());
        }
    }

    private void startEditingContactMessage(@NonNull ContactMessageReceiver receiver, @NonNull AbstractMessageModel message) {
        if (ThreemaFeature.canEditMessages(receiver.getContact().getFeatureMask())) {
            startMessageEditor(message);
        } else {
            LongToast.makeText(
                getContext(),
                getResources().getString(R.string.edit_message_not_supported_for_contact),
                Toast.LENGTH_LONG).show();
        }
    }

    private void startEditingGroupMessage(@NonNull GroupModel groupModel, @NonNull AbstractMessageModel message) {
        if (Boolean.TRUE.equals(groupModel.isNotesGroup())) {
            startMessageEditor(message);
            return;
        }

        GroupModelData groupModelData = groupModel.getData();
        if (groupModelData == null) {
            logger.warn("Cannot start editing group message of deleted group");
            return;
        }

        GroupFeatureSupport featureSupport = groupService.getFeatureSupport(groupModelData, ThreemaFeature.EDIT_MESSAGES);
        if (featureSupport.getAdoptionRate() == GroupFeatureAdoptionRate.ALL) {
            startMessageEditor(message);
        } else if (featureSupport.getAdoptionRate() == GroupFeatureAdoptionRate.PARTIAL) {
            List<ContactModel> membersWithoutFeatureSupport = featureSupport.getContactsWithoutFeatureSupport();
            GenericAlertDialog dialog = GenericAlertDialog.newInstance(
                R.string.warning,
                getString(
                    R.string.edit_message_not_supported_for_all_group_members,
                    ContactUtil.joinDisplayNames(getContext(), membersWithoutFeatureSupport, preferenceService.getContactNameFormat())
                ),
                R.string.ok,
                R.string.cancel
            );
            dialog.setCallback((tag, data) -> startMessageEditor(message));
            dialog.show(getChildFragmentManager(), DIALOG_TAG_EDIT_MESSAGES_UNSUPPORTED_WARNING);
        } else {
            LongToast.makeText(
                getContext(),
                getResources().getString(R.string.edit_message_not_supported_for_any_group_members),
                Toast.LENGTH_LONG).show();
        }
    }

    private void startMessageEditor(@NonNull AbstractMessageModel message) {
        if (quotePopup != null && message == quotePopup.getQuoteInfo().getMessageModel()) {
            quotePopup.dismiss();
        }
        editMessageActionMode = activity.startSupportActionMode(new EditMessageActionMode(message));
    }

    private void onSendEditMessage(@NonNull AbstractMessageModel messageModel, @NonNull String editedText) {
        if (editMessageActionMode != null) {
            editMessageActionMode.finish();
        }

        if (isGroupChatWhereUserIsNotMemberOf()) {
            SingleToast.getInstance().showLongText(getString(R.string.you_are_not_a_member_of_this_group));
            return;
        }

        try {
            // when message failed to send edit it locally only
            if (messageModel.getState() == MessageState.SENDFAILED) {
                messageService.saveEditedMessageText(messageModel, editedText, null);
            } else {
                Date editedAt = new Date();
                messageService.sendEditedMessageText(messageModel, editedText, editedAt, Objects.requireNonNull(messageReceiver));
            }
        } catch (Exception e) {
            logger.error("Failed to edit message", e);
        }
    }

    private void copySelectedMessagesToClipboard() {
        if (selectedMessages.isEmpty()) {
            logger.error("no selected messages");
            return;
        }

        StringBuilder body = new StringBuilder();
        for (AbstractMessageModel message : selectedMessages) {
            if (body.length() > 0) {
                body.append("\n");
            }

            body.append(message.getType() == MessageType.TEXT
                ? QuoteUtil.getMessageBody(
                message.getType(),
                message.getBody(),
                message.getCaption(),
                message.isOutbox(),
                false,
                preferenceService.getContactNameFormat()
            )
                : message.getCaption());
        }

        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clipData = ClipData.newPlainText(null, body.toString());
                if (clipData != null) {
                    clipboard.setPrimaryClip(clipData);
                    Toast.makeText(
                        getContext(),
                        getResources().getQuantityString(R.plurals.message_copied, selectedMessages.size()),
                        Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            // Some Android 4.3 devices raise an IllegalStateException when writing to the clipboard
            // while there is an active clipboard listener
            // see https://code.google.com/p/android/issues/detail?id=58043
            logger.error("Exception", e);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void shareMessages() {
        if (selectedMessages.size() > 1) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {
                    GenericProgressDialog.newInstance(R.string.decoding_message, R.string.please_wait).show(getFragmentManager(), DIALOG_TAG_DECRYPTING_MESSAGES);
                }

                @Override
                protected Void doInBackground(Void... voids) {
                    fileService.loadDecryptedMessageFiles(selectedMessages, new FileService.OnDecryptedFilesComplete() {
                        @Override
                        public void complete(ArrayList<Uri> uris) {
                            shareMediaMessages(uris);
                        }

                        @Override
                        public void error(String message) {
                            RuntimeUtil.runOnUiThread(() -> LongToast.makeText(activity, message, Toast.LENGTH_LONG).show());
                        }
                    });
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    DialogUtil.dismissDialog(getFragmentManager(), DIALOG_TAG_DECRYPTING_MESSAGES, true);
                }
            }.execute();
        } else {
            final AbstractMessageModel messageModel = selectedMessages.get(0);

            if (messageModel != null) {
                fileService.loadDecryptedMessageFile(messageModel, new FileService.OnDecryptedFileComplete() {
                    @Override
                    public void complete(File decryptedFile) {
                        if (decryptedFile != null) {
                            String filename = null;
                            if (messageModel.getType() == MessageType.FILE) {
                                filename = messageModel.getFileData().getFileName();
                            }
                            shareMediaMessages(Collections.singletonList(fileService.getShareFileUri(decryptedFile, filename)));
                        } else {
                            messageService.shareTextMessage(activity, messageModel);
                        }
                    }

                    @Override
                    public void error(final String message) {
                        RuntimeUtil.runOnUiThread(() -> LongToast.makeText(activity, message, Toast.LENGTH_LONG).show());
                    }
                });
            }
        }
    }

    private void shareMediaMessages(List<Uri> uris) {
        if (selectedMessages.size() == 1) {
            logger.info("Showing share dialog for {} message(s)", selectedMessages.size());
            ExpandableTextEntryDialog alertDialog = ExpandableTextEntryDialog.newInstance(
                getString(R.string.share_media),
                R.string.add_caption_hint, selectedMessages.get(0).getCaption(),
                R.string.label_continue, R.string.cancel, true);
            alertDialog.setData(uris);
            alertDialog.setTargetFragment(this, 0);
            alertDialog.show(getParentFragmentManager(), null);
        } else {
            messageService.shareMediaMessages(activity,
                new ArrayList<>(selectedMessages),
                new ArrayList<>(uris), null);
        }
    }

    @Override
    public void onYes(String tag, Object data, String text) {
        logger.info("Sharing dialog confirmed");
        List<Uri> uris = (List<Uri>) data;
        messageService.shareMediaMessages(activity,
            new ArrayList<>(selectedMessages),
            new ArrayList<>(uris), text);
    }

    @UiThread
    private void showQuotePopup(@Nullable AbstractMessageModel messageModel, boolean isUserInitiated) {
        final @NonNull AbstractMessageModel quotedMessageModel;
        if (messageModel == null) {
            quotedMessageModel = selectedMessages.get(0);
        } else {
            quotedMessageModel = messageModel;
        }
        if (quotedMessageModel == null) {
            return;
        }

        sendButton.setEnabled(messageText != null && !TestUtil.isBlankOrNull(messageText.getText()));

        dismissMentionPopup();
        dismissQuotePopup();

        String identity = quotedMessageModel.isOutbox() ? userService.getIdentity() : quotedMessageModel.getIdentity();

        /**
         *  This quote bar view will never have any selected or pressed state. Nevertheless, we use the defined color-state-list here.
         *  This way we have a centralized color definition for this component.
         */
        @NonNull ColorStateList barColor = getContext().getColorStateList(R.color.bubble_quote_bar_default_colorstatelist);
        if (!quotedMessageModel.isOutbox()) {
            if (isGroupChat && identityColors.containsKey(identity)) {
                barColor = ColorStateList.valueOf(identityColors.get(identity));
            } else if (contactModel != null) {
                barColor = ColorStateList.valueOf(contactModel.getIdColor().getThemedColor(requireContext()));
            }
        }

        quotePopup = new QuotePopup(
            activity,
            contactService,
            userService,
            fileService,
            preferenceService,
            thumbnailCache
        );

        final @NonNull ColorStateList barColorFinal = barColor;
        Runnable showPopup = () -> {
            if (isVisible()) {
                quotePopup.show(activity, messageText, textInputLayout, quotedMessageModel, identity, barColorFinal, quotePopupListener);
            }
        };

        if (!isUserInitiated) {
            // In case the user did not initiate opening the quote popup, then we show the popup
            // with a short delay. This prevents that the quote popup is shown before the edit text
            // is lay out with its new size.
            messageText.postDelayed(showPopup, 150);
        } else if (activity.isSoftKeyboardOpen() || isEmojiPickerShown()) {
            // In case the keyboard or the emoji picker is already shown, we can show the quote
            // popup immediately.
            messageText.requestFocus();
            showPopup.run();
        } else {
            // In case the keyboard is not shown and the user initiated opening the quote popup, we
            // open the keyboard and show the quote popup delayed for a smoother experience.
            EditTextUtil.focusWindowAndShowSoftKeyboard(messageText);
            messageText.postDelayed(showPopup, 550);
        }
    }

    private void reopenQuotePopup() {
        if (!isQuotePopupShown()) {
            return;
        }

        AbstractMessageModel quotedMessageModel = quotePopup.getQuoteInfo().getMessageModel();
        if (quotedMessageModel != null) {
            showQuotePopup(quotedMessageModel, false);
        }
    }

    private void dismissQuotePopup() {
        dismissQuotePopup(null);
    }

    private void dismissQuotePopup(@Nullable Runnable runAfterQuotePopupClosed) {
        if (isQuotePopupShown()) {
            try {
                quotePopup.dismiss();
                quotePopup = null;
            } catch (Exception e) {
                logger.error("Error dismissing quote popup", e);
            }
            updateSendButton(messageText.getText());
            if (runAfterQuotePopupClosed != null) {
                runAfterQuotePopupClosed.run();
            }
        }
    }

    private boolean isQuotePopupShown() {
        return quotePopup != null && quotePopup.isShowing();
    }

    private void startForwardMessage() {
        if (!selectedMessages.isEmpty()) {
            if (selectedMessages.size() == 1) {
                final AbstractMessageModel messageModel = selectedMessages.get(0);

                if (messageModel.getType() == MessageType.TEXT) {
                    // allow editing before sending if it's a single text message
                    String body = QuoteUtil.getMessageBody(
                        messageModel.getType(),
                        messageModel.getBody(),
                        messageModel.getCaption(),
                        messageModel.isOutbox(),
                        false,
                        preferenceService.getContactNameFormat()
                    );
                    Intent intent = new Intent(activity, RecipientListBaseActivity.class);
                    intent.setType("text/plain");
                    intent.setAction(Intent.ACTION_SEND);
                    intent.putExtra(Intent.EXTRA_TEXT, body);
                    intent.putExtra(AppConstants.INTENT_DATA_IS_FORWARD, true);
                    activity.startActivity(intent);
                    return;
                }
            }
            FileUtil.forwardMessages(activity, RecipientListBaseActivity.class, selectedMessages);
        }
    }

    /**
     * Toggles the "starred" flag for the provided message and saves it to the database
     *
     * @param messageModel AbstractMessageModel of the message
     */
    private void toggleStar(@Nullable AbstractMessageModel messageModel) {
        if (messageModel != null && messageReceiver != null) {
            messageModel.setDisplayTags(messageModel.getDisplayTags() ^ DISPLAY_TAG_STARRED);
            messageModel.setSaved(true);
            messageReceiver.saveLocalModel(messageModel);
        }
    }

    /**
     * F1Whisper: toggle the DISPLAY_TAG_PINNED bit for the provided message and persist it.
     * After the toggle, refresh the pinned banner so it reflects the new pinned set.
     * Mirrors {@link #toggleStar(AbstractMessageModel)} exactly.
     */
    @UiThread
    private void togglePin(@Nullable AbstractMessageModel messageModel) {
        if (messageModel != null && messageReceiver != null) {
            final int newTags = messageModel.getDisplayTags() ^ DISPLAY_TAG_PINNED;
            final boolean nowPinned = (newTags & DISPLAY_TAG_PINNED) == DISPLAY_TAG_PINNED;
            messageModel.setDisplayTags(newTags);
            messageModel.setSaved(true);
            messageReceiver.saveLocalModel(messageModel);
            // Keep the known full pin set in sync immediately so the banner counter is correct
            // without waiting on the async full scan (which still runs to reconcile paged-out pins).
            final String toggledUid = messageModel.getUid();
            if (toggledUid != null) {
                if (nowPinned) {
                    if (!pinnedMessageUids.contains(toggledUid)) {
                        pinnedMessageUids.add(toggledUid);
                    }
                    // Point the banner at the message the user just pinned.
                    currentPinnedMessageUid = toggledUid;
                } else {
                    pinnedMessageUids.remove(toggledUid);
                    if (toggledUid.equals(currentPinnedMessageUid)) {
                        // Fall back to the first remaining pin (or null -> banner hides).
                        currentPinnedMessageUid = pinnedMessageUids.isEmpty() ? null : pinnedMessageUids.get(0);
                    }
                }
            }
            pinnedBannerDismissed = false;
            updatePinnedBanner();
        }
    }

    /**
     * F1Whisper: collect the uids of all currently-pinned messages from the loaded adapter, in
     * list (pin) order. Uses the globally-unique {@code uid} (never text/object identity) so two
     * pins that share the same body remain distinct. Messages with a null uid are skipped (they
     * cannot be jumped-to reliably).
     * <p>
     * NOTE: this only sees the bounded page of messages currently held by the adapter. A pinned
     * message that has been paged out of the loaded window will be missed here. The authoritative,
     * window-independent set is discovered asynchronously by {@link #refreshFullPinnedSet()} which
     * queries the whole conversation from the database; this fast adapter scan only seeds the banner
     * label so it can render immediately without waiting on the background query.
     */
    @UiThread
    private void collectPinnedUids(@NonNull List<String> out) {
        out.clear();
        if (composeMessageAdapter == null) {
            return;
        }
        for (int i = 0; i < composeMessageAdapter.getCount(); i++) {
            final AbstractMessageModel m = composeMessageAdapter.getItem(i);
            if (m != null
                && m.getUid() != null
                && (m.getDisplayTags() & DISPLAY_TAG_PINNED) == DISPLAY_TAG_PINNED) {
                out.add(m.getUid());
            }
        }
    }

    // F1Whisper: guards against running more than one full-conversation pin scan at a time.
    private boolean pinnedFullScanInProgress = false;

    /**
     * F1Whisper: discover the COMPLETE pinned set for this conversation — including pins that have
     * been paged out of the loaded adapter window — by querying the whole conversation from the
     * local database on a worker thread, then refresh the banner on the UI thread.
     * <p>
     * Without this, the cycler in {@link #cyclePinnedMessageBanner()} and the "N/M" counter in
     * {@link #updatePinnedBanner()} would only ever know about pins that happen to be loaded, so the
     * counter would be wrong and older pins would be unreachable. All data is local; this is a pure
     * read, no wire change.
     */
    @UiThread
    private void refreshFullPinnedSet() {
        if (messageReceiver == null || pinnedFullScanInProgress) {
            return;
        }
        pinnedFullScanInProgress = true;
        new AsyncTask<Void, Void, List<String>>() {
            @Override
            protected List<String> doInBackground(Void... voids) {
                final List<String> uids = new ArrayList<>();
                try {
                    // null filter => the entire conversation (same query the search action uses).
                    final List<AbstractMessageModel> all = messageService.getMessagesForReceiver(messageReceiver);
                    if (all != null) {
                        // getMessagesForReceiver returns newest-first; reverse to oldest-first so the
                        // pin order matches the loaded-adapter order used elsewhere.
                        for (int i = all.size() - 1; i >= 0; i--) {
                            final AbstractMessageModel m = all.get(i);
                            if (m != null
                                && m.getUid() != null
                                && !m.isStatusMessage()
                                && (m.getDisplayTags() & DISPLAY_TAG_PINNED) == DISPLAY_TAG_PINNED) {
                                uids.add(m.getUid());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.info("refreshFullPinnedSet failed", e);
                }
                return uids;
            }

            @Override
            protected void onPostExecute(List<String> fullUids) {
                pinnedFullScanInProgress = false;
                if (!isAdded() || composeMessageAdapter == null || pinnedBannerContainer == null) {
                    return;
                }
                if (pinnedBannerDismissed) {
                    return;
                }
                if (!fullUids.isEmpty()) {
                    pinnedMessageUids.clear();
                    pinnedMessageUids.addAll(fullUids);
                    // Repair the shown uid if the freshly-discovered set no longer contains it.
                    if (currentPinnedMessageUid == null || !pinnedMessageUids.contains(currentPinnedMessageUid)) {
                        currentPinnedMessageUid = pinnedMessageUids.get(0);
                    }
                    renderPinnedBannerFromState();
                } else {
                    // No pins anywhere in the conversation: drop stale state and hide.
                    currentPinnedMessageUid = null;
                    pinnedMessageUids.clear();
                    pinnedBannerContainer.setVisibility(View.GONE);
                }
            }
        }.execute();
    }

    /**
     * F1Whisper: (re-)render the pinned-message banner above the list.
     * <p>
     * Rebuilds the pinned-uid cycler from the live adapter, repairs {@link #currentPinnedMessageUid}
     * if its message was deleted or unpinned (Telegram-style: the deleted id is dropped from the
     * set and the banner advances to the first remaining pin), previews the message the next tap
     * will jump to, and hides the banner when nothing is pinned.
     */
    @UiThread
    private void updatePinnedBanner() {
        if (pinnedBannerContainer == null || composeMessageAdapter == null) {
            return;
        }
        if (pinnedBannerDismissed) {
            pinnedBannerContainer.setVisibility(View.GONE);
            return;
        }
        // Fast path: seed the cycler from the loaded adapter so the banner can render immediately.
        // This may UNDERCOUNT pins that are paged out of the loaded window.
        final List<String> loadedPins = new ArrayList<>();
        collectPinnedUids(loadedPins);
        // Merge the loaded pins into the known set without dropping paged-out pins discovered by a
        // previous full scan. The authoritative, complete set is (re)established by the async
        // refreshFullPinnedSet() kicked off below.
        for (String uid : loadedPins) {
            if (!pinnedMessageUids.contains(uid)) {
                pinnedMessageUids.add(uid);
            }
        }
        if (pinnedMessageUids.isEmpty() && loadedPins.isEmpty()) {
            // Nothing pinned in the loaded window AND nothing known from a prior scan: provisionally
            // hide, but still run the full scan in case a pin lives outside the loaded window.
            currentPinnedMessageUid = null;
            pinnedBannerContainer.setVisibility(View.GONE);
            refreshFullPinnedSet();
            return;
        }
        if (currentPinnedMessageUid == null || !pinnedMessageUids.contains(currentPinnedMessageUid)) {
            currentPinnedMessageUid = pinnedMessageUids.get(0);
        }
        renderPinnedBannerFromState();
        // Reconcile against the whole conversation (discovers paged-out pins, prunes deleted ones).
        refreshFullPinnedSet();
    }

    /**
     * F1Whisper: render the banner label + preview from the current {@link #pinnedMessageUids} /
     * {@link #currentPinnedMessageUid} state, WITHOUT re-collecting. The complete set is owned by
     * {@link #refreshFullPinnedSet()}; this only paints what state already holds.
     */
    @UiThread
    private void renderPinnedBannerFromState() {
        if (pinnedBannerContainer == null) {
            return;
        }
        if (pinnedMessageUids.isEmpty() || currentPinnedMessageUid == null) {
            pinnedBannerContainer.setVisibility(View.GONE);
            return;
        }
        final int shownIndex = Math.max(0, pinnedMessageUids.indexOf(currentPinnedMessageUid));
        // The preview can only be sourced from a loaded message; a paged-out pin shows a generic
        // label until it is loaded (e.g. on the first jump). Never blank out an existing preview.
        final AbstractMessageModel shown = findMessageByUid(currentPinnedMessageUid);
        if (pinnedBannerPreview != null) {
            if (shown != null) {
                String preview = shown.getBody();
                if (preview == null || preview.isEmpty()) {
                    // graceful fallback for media messages with no text body
                    preview = shown.getType() != null ? shown.getType().toString() : "";
                }
                pinnedBannerPreview.setText(preview);
            } else if (pinnedBannerPreview.length() == 0) {
                pinnedBannerPreview.setText(R.string.pinned_message_banner_label);
            }
        }
        // Counter label: plain "Pinned message" for a single pin, "Pinned message N/M" for several
        // so the user understands successive taps cycle through them.
        if (pinnedBannerLabel != null) {
            if (pinnedMessageUids.size() > 1) {
                pinnedBannerLabel.setText(getString(
                    R.string.pinned_message_banner_label_counter,
                    shownIndex + 1,
                    pinnedMessageUids.size()));
            } else {
                pinnedBannerLabel.setText(R.string.pinned_message_banner_label);
            }
        }
        pinnedBannerContainer.setVisibility(View.VISIBLE);
    }

    /**
     * F1Whisper: locate the loaded adapter item whose globally-unique uid matches {@code uid}.
     * Returns {@code null} if no loaded message carries that uid (e.g. it was deleted).
     */
    @UiThread
    @Nullable
    private AbstractMessageModel findMessageByUid(@Nullable String uid) {
        if (uid == null || composeMessageAdapter == null) {
            return null;
        }
        for (int i = 0; i < composeMessageAdapter.getCount(); i++) {
            final AbstractMessageModel m = composeMessageAdapter.getItem(i);
            if (m != null && uid.equals(m.getUid())) {
                return m;
            }
        }
        return null;
    }

    /**
     * F1Whisper: find the adapter position of the loaded message with the given uid, or -1.
     */
    @UiThread
    private int findAdapterPositionByUid(@Nullable String uid) {
        if (uid == null || composeMessageAdapter == null) {
            return -1;
        }
        for (int i = 0; i < composeMessageAdapter.getCount(); i++) {
            final AbstractMessageModel m = composeMessageAdapter.getItem(i);
            if (m != null && uid.equals(m.getUid())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * F1Whisper: tap on the pinned banner — jump to the EXACT pinned message identified by
     * {@link #currentPinnedMessageUid} (by unique id, never by text), briefly highlight it, then
     * advance the cycler to the next pinned message in pin order so the next tap continues the
     * cycle. Deleted/unpinned targets are handled by re-resolving against the live adapter.
     */
    @UiThread
    private void cyclePinnedMessageBanner() {
        if (composeMessageAdapter == null) {
            logger.info("Pinned banner tapped but adapter is null; ignoring");
            return;
        }
        // A full-conversation load is already running for a previous tap: ignore re-taps so we don't
        // stack loads or scroll to a stale target mid-load.
        if (pinnedJumpInProgress) {
            logger.info("Pinned banner tapped while a jump-load is in progress; ignoring re-tap");
            return;
        }
        // Reconcile the known pin set against the loaded window before cycling. We cannot do a
        // synchronous full-DB scan on the UI thread, so we keep paged-out pins (those not present in
        // the loaded window can't be judged here) but PRUNE any loaded message that is no longer
        // pinned, and ADD any newly-pinned loaded message in its correct list position. This stops
        // the cycler from ever advancing to a loaded-but-unpinned uid. A still-stale paged-out uid is
        // a non-issue: the robust jumpToPinnedMessage() does a full load + reconcile and skips to the
        // first surviving pin, so every tap still lands somewhere.
        mergeLoadedPinsPreservingOrder();
        if (pinnedMessageUids.isEmpty()) {
            logger.info("Pinned banner tapped but no pins remain after reconcile; hiding banner");
            updatePinnedBanner(); // hides the banner + kicks a full scan
            return;
        }
        if (currentPinnedMessageUid == null || !pinnedMessageUids.contains(currentPinnedMessageUid)) {
            currentPinnedMessageUid = pinnedMessageUids.get(0);
        }
        logger.info("Pinned banner tapped: cycling to uid={} ({} pins total)",
            currentPinnedMessageUid, pinnedMessageUids.size());
        jumpToPinnedMessage(currentPinnedMessageUid);
    }

    /**
     * F1Whisper: reconcile {@link #pinnedMessageUids} against the currently-loaded adapter window
     * WITHOUT discarding paged-out pins discovered by a prior full scan.
     * <p>
     * For every uid currently known: if it is present in the loaded window we trust the live pinned
     * flag (prune it if it has been unpinned); if it is NOT in the loaded window we keep it as-is
     * (it may be a legitimately paged-out pin — only the async full scan, or a full-load jump, can
     * judge it). Then we add any newly-pinned loaded message that is not yet tracked. Pin/list order
     * is preserved: surviving known pins keep their relative order, then any brand-new loaded pins are
     * appended in adapter order. Rebuilding into a fresh list (rather than mutating in place) keeps
     * the ordering deterministic.
     */
    @UiThread
    private void mergeLoadedPinsPreservingOrder() {
        if (composeMessageAdapter == null) {
            return;
        }
        // Snapshot which loaded uids are currently pinned.
        final Set<String> loadedPinnedSet = new HashSet<>();
        final List<String> loadedPinnedOrdered = new ArrayList<>();
        for (int i = 0; i < composeMessageAdapter.getCount(); i++) {
            final AbstractMessageModel m = composeMessageAdapter.getItem(i);
            if (m != null
                && m.getUid() != null
                && (m.getDisplayTags() & DISPLAY_TAG_PINNED) == DISPLAY_TAG_PINNED) {
                loadedPinnedSet.add(m.getUid());
                loadedPinnedOrdered.add(m.getUid());
            }
        }
        // Track which loaded uids exist at all (pinned or not) so we can distinguish "loaded and
        // unpinned" (prune) from "not loaded / paged out" (keep).
        final Set<String> loadedAnyUids = new HashSet<>();
        for (int i = 0; i < composeMessageAdapter.getCount(); i++) {
            final AbstractMessageModel m = composeMessageAdapter.getItem(i);
            if (m != null && m.getUid() != null) {
                loadedAnyUids.add(m.getUid());
            }
        }

        final List<String> reconciled = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final String uid : pinnedMessageUids) {
            if (loadedAnyUids.contains(uid)) {
                // It is loaded: keep it only if it is still pinned.
                if (loadedPinnedSet.contains(uid) && seen.add(uid)) {
                    reconciled.add(uid);
                }
                // else: loaded but no longer pinned -> drop it.
            } else {
                // Paged out: cannot judge here, keep it (full-load jump / async scan will reconcile).
                if (seen.add(uid)) {
                    reconciled.add(uid);
                }
            }
        }
        // Append any newly-pinned loaded message not already tracked, in adapter order.
        for (final String uid : loadedPinnedOrdered) {
            if (seen.add(uid)) {
                reconciled.add(uid);
            }
        }

        pinnedMessageUids.clear();
        pinnedMessageUids.addAll(reconciled);
    }

    // F1Whisper: guards a single in-flight robust pinned jump (full-conversation load) so rapid
    // re-taps don't stack background loads or race the scroll.
    private boolean pinnedJumpInProgress = false;

    /**
     * F1Whisper: robustly jump to the pinned message with the given uid (Telegram-style
     * "jump to message even if it isn't loaded"), then advance the cycler to the next pin.
     * <p>
     * If the target is already in the loaded adapter window, we scroll + highlight immediately. If
     * it is paged out, we load the ENTIRE conversation into the adapter on a worker thread (the same
     * proven path the in-chat search uses), then scroll + highlight once it is present. If, after a
     * full load, the uid still does not resolve, the message was deleted from the database: we prune
     * it from the pin set and advance to the next real pin so every tap always lands somewhere. All
     * data is local; no wire change.
     */
    @UiThread
    private void jumpToPinnedMessage(@NonNull final String jumpUid) {
        final int targetPosition = findAdapterPositionByUid(jumpUid);
        logger.info("Jumping to pinned uid={}, in-window lookup result={}", jumpUid, targetPosition);
        if (targetPosition >= 0) {
            logger.info("Pinned target in-window: scrolling to position={}, uid={}", targetPosition, jumpUid);
            scrollToPinnedAndHighlight(targetPosition, jumpUid);
            advancePinnedCycler(jumpUid);
            return;
        }
        // Target not in the loaded window: load the full conversation, then jump. This is the same
        // proven "load every record into the adapter, then resolve by uid" path the in-chat search /
        // quote-jump uses to reach an off-window message (searchV2Quote -> getAllRecords). After the
        // load the target uid is guaranteed present unless its message was deleted from the DB.
        logger.info("Pinned target uid={} NOT in loaded window; loading full conversation to page it in", jumpUid);
        pinnedJumpInProgress = true;
        new AsyncTask<Void, Void, List<AbstractMessageModel>>() {
            @Override
            protected List<AbstractMessageModel> doInBackground(Void... voids) {
                try {
                    return getAllRecords();
                } catch (Exception e) {
                    logger.info("jumpToPinnedMessage: full conversation load failed", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(@Nullable List<AbstractMessageModel> all) {
                pinnedJumpInProgress = false;
                if (!isAdded() || composeMessageAdapter == null || convListView == null) {
                    logger.info("Pinned full-load completed but fragment/adapter detached; aborting jump");
                    return;
                }
                if (all != null) {
                    // Replace the loaded window with the entire conversation (same as search load-all).
                    insertToList(all, true, false, true);
                    logger.info("Pinned full load complete: {} records now loaded; reconciling pins", all.size());
                } else {
                    logger.info("Pinned full load returned null; reconciling pins against current window");
                }
                // The entire conversation is now loaded, so the adapter is the authoritative source of
                // truth for which pins still exist. Reconcile the pin set against it (in pin order),
                // dropping any pin whose message was deleted/unpinned, BEFORE we attempt any scroll.
                reconcilePinnedSetFromAdapter();
                if (pinnedMessageUids.isEmpty()) {
                    logger.info("No pins survived full-load reconcile; hiding banner");
                    currentPinnedMessageUid = null;
                    updatePinnedBanner(); // hides the banner
                    return;
                }

                // Prefer the originally-requested target if it survived the reconcile.
                final int positionAfterLoad = findAdapterPositionByUid(jumpUid);
                if (positionAfterLoad >= 0 && pinnedMessageUids.contains(jumpUid)) {
                    currentPinnedMessageUid = jumpUid;
                    logger.info("Jump successful after full load: scrolled to pinned uid={}, position={}",
                        jumpUid, positionAfterLoad);
                    scrollToPinnedAndHighlight(positionAfterLoad, jumpUid);
                    advancePinnedCycler(jumpUid);
                    return;
                }

                // The requested target was deleted: jump to the first SURVIVING pin instead so the tap
                // never "goes nowhere". Find the first pin that actually resolves to a real position
                // (defensive: reconcile already pruned non-resolving pins, but guard regardless).
                String nextUid = null;
                int nextPosition = -1;
                for (final String uid : pinnedMessageUids) {
                    final int pos = findAdapterPositionByUid(uid);
                    if (pos >= 0) {
                        nextUid = uid;
                        nextPosition = pos;
                        break;
                    }
                }
                if (nextUid == null || nextPosition < 0) {
                    // No surviving pin resolves to a real row: hide the banner instead of scrolling
                    // to an invalid (-1) position, which would silently no-op ("goes nowhere").
                    logger.info("Target uid={} was deleted and no surviving pin resolves to a row; hiding banner", jumpUid);
                    currentPinnedMessageUid = null;
                    pinnedMessageUids.clear();
                    updatePinnedBanner();
                    return;
                }
                logger.info("Target uid={} was deleted; falling back to first surviving pin uid={}, position={}",
                    jumpUid, nextUid, nextPosition);
                currentPinnedMessageUid = nextUid;
                scrollToPinnedAndHighlight(nextPosition, nextUid);
                advancePinnedCycler(nextUid);
            }
        }.execute();
    }

    // F1Whisper: breathing-room gap (dp) left above the jumped-to row so it lands cleanly at the top
    // of the conversation viewport, just under the pinned banner. The banner is a SIBLING above the
    // list in the ConstraintLayout (the list's top is constrained below it), so it does NOT overlay
    // the list — the list already starts beneath the banner. We therefore only need a small visual
    // margin here, NOT the full banner height (which would leave a banner-height dead band above the
    // target). When the banner is visible we add a couple of extra dp to clear its card elevation.
    private static final int PINNED_JUMP_TOP_GAP_DP = 8;
    private static final int PINNED_JUMP_TOP_GAP_WITH_BANNER_DP = 12;
    // F1Whisper: delay (ms) before the second selection re-apply. The robust jump path runs straight
    // after insertToList(clear=true) -> notifyDataSetInvalidated(), which forces AbsListView to throw
    // away its layout and (with stackFromBottom=true) re-anchor at the BOTTOM on its next layout pass.
    // A single selection request issued in the same frame gets clobbered by that relayout, which is
    // exactly why far jumps used to "go nowhere". We therefore re-apply the selection once more after
    // this short delay — the same proven double-apply the unread anchor uses — so it wins over the
    // invalidate-driven relayout. Matches the search-quote path's 500ms settle window.
    private static final int PINNED_JUMP_REAPPLY_DELAY_MS = 500;

    /**
     * F1Whisper: deterministic pinned jump — scroll the target row to land JUST BELOW the pinned
     * banner and flash it, surviving the post-invalidate relayout for ANY scroll distance.
     * <p>
     * ROOT-CAUSE NOTE (this regressed three times): the robust jump path loads the whole conversation
     * via {@link #insertToList(java.util.List, boolean, boolean, boolean)} with {@code clear=true},
     * which calls {@link android.widget.ListView#getAdapter()}'s {@code notifyDataSetInvalidated()}.
     * That discards the ListView's layout state, and because the list is {@code stackFromBottom="true"}
     * it re-anchors at the BOTTOM on its very next layout pass. A selection requested in the SAME frame
     * (a single {@code post(setSelectionFromTop)}) is then overwritten by that relayout, so the far jump
     * silently parked at the bottom — "went nowhere". The fix is NOT a different scroller; it is to
     * APPLY the selection immediately AND RE-APPLY it once after a short settle delay (the same proven
     * double-apply the unread anchor and the search-quote jump use), so our selection wins the race with
     * the invalidate-driven stack-from-bottom relayout. We then flash the row by re-resolving its
     * position by uid (never a stale captured position), using the same native activated-state flash the
     * search-quote jump uses ({@link android.widget.ListView#setItemChecked(int, boolean)}), which the
     * bubble selectors render — no home-grown background mutation that could corrupt a recycled row.
     *
     * @param targetPosition resolved adapter position of the target row (negative is a no-op)
     * @param uid            unique id of the message to flash once it is laid out
     */
    @UiThread
    private void scrollToPinnedAndHighlight(final int targetPosition, @NonNull final String uid) {
        if (targetPosition < 0 || convListView == null) {
            logger.info("scrollToPinnedAndHighlight: skipping invalid position {} (uid={})", targetPosition, uid);
            return;
        }
        // Top offset = a small breathing-room gap so the row lands cleanly at the top of the list
        // viewport (which already sits BELOW the banner via the layout constraint — the banner is not
        // an overlay). A touch more gap when the banner is showing, to clear its card elevation/shadow.
        // This is deliberately NOT the full banner height (that would push the target a banner-height
        // too far down, leaving dead space above it).
        final boolean bannerShown = pinnedBannerContainer != null
            && pinnedBannerContainer.getVisibility() == View.VISIBLE
            && pinnedBannerContainer.getHeight() > 0;
        final int gapDp = bannerShown ? PINNED_JUMP_TOP_GAP_WITH_BANNER_DP : PINNED_JUMP_TOP_GAP_DP;
        final int offset = (int) (gapDp * getResources().getDisplayMetrics().density);
        logger.info("scrollToPinnedAndHighlight: setSelectionFromTop position={} offset={}px (bannerShown={}) uid={}",
            targetPosition, offset, bannerShown, uid);
        // First application: issued now, after insertToList already ran on this same UI turn, so it is
        // the last layout instruction queued for the current pass.
        convListView.setSelectionFromTop(targetPosition, offset);
        // Second application: after the invalidate-driven (stack-from-bottom) relayout has settled, so
        // our selection is the one that sticks even for a far jump. Re-resolve the position by uid in
        // case the adapter shifted between the two applications, then flash the now-stable row.
        convListView.postDelayed(() -> {
            if (convListView == null || !isAdded()) {
                return;
            }
            final int livePosition = findAdapterPositionByUid(uid);
            if (livePosition < 0) {
                logger.info("Pinned re-apply: uid={} no longer resolves; skipping flash", uid);
                return;
            }
            convListView.setSelectionFromTop(livePosition, offset);
            // Flash on the next layout pass, once the re-applied selection has laid the row out.
            convListView.post(() -> flashPinnedRow(uid));
        }, PINNED_JUMP_REAPPLY_DELAY_MS);
    }

    /**
     * F1Whisper: re-derive the complete pinned set from the CURRENTLY-LOADED adapter, in list (pin)
     * order, and reconcile {@link #pinnedMessageUids} / {@link #currentPinnedMessageUid} against it.
     * <p>
     * This is only authoritative when the full conversation is loaded (it is called right after a
     * full-conversation load in the robust jump path): at that point the adapter holds every message,
     * so any pin that does not resolve to an adapter item has been deleted or unpinned and must be
     * dropped. Rebuilding (rather than appending) guarantees the cycler can never advance to a stale
     * uid that no longer exists, and preserves the true pin order from the conversation.
     */
    @UiThread
    private void reconcilePinnedSetFromAdapter() {
        final List<String> fresh = new ArrayList<>();
        collectPinnedUids(fresh);
        pinnedMessageUids.clear();
        pinnedMessageUids.addAll(fresh);
        if (currentPinnedMessageUid != null && !pinnedMessageUids.contains(currentPinnedMessageUid)) {
            currentPinnedMessageUid = pinnedMessageUids.isEmpty() ? null : pinnedMessageUids.get(0);
        }
    }

    /**
     * F1Whisper: after a successful jump, advance {@link #currentPinnedMessageUid} to the next pin
     * in the cycle and repaint the banner so the next tap continues to the following pin.
     */
    @UiThread
    private void advancePinnedCycler(@NonNull String jumpedUid) {
        final int currentIndex = pinnedMessageUids.indexOf(jumpedUid);
        if (currentIndex < 0 || pinnedMessageUids.isEmpty()) {
            logger.info("advancePinnedCycler: jumped uid={} no longer in pin set; refreshing banner", jumpedUid);
            updatePinnedBanner();
            return;
        }
        // Advance to the NEXT pin in cycle order. The cycler MUST always move to a DIFFERENT pin when
        // more than one is pinned — even when the other pins are paged out of the loaded window. We
        // prefer the first sibling that already resolves to a loaded row (so the FOLLOWING tap lands
        // instantly), but if NONE of the siblings is loaded we still advance to the arithmetic-next pin
        // so the cycle progresses; jumpToPinnedMessage() pages that pin in via a full-conversation load
        // on the next tap. We deliberately scan siblings ONLY (steps 1..size-1) and never wrap back onto
        // the just-jumped uid: wrapping-to-self is correct ONLY for a genuine single pin. (The old loop
        // wrapped to self whenever the siblings were merely paged out, which stranded every tap on the
        // one loaded pin — the "every tap targets the same uid" bug — so the paged-out pins were
        // unreachable.)
        final int size = pinnedMessageUids.size();
        String chosen;
        if (size == 1) {
            // Genuinely a single pin: stay on it.
            chosen = jumpedUid;
        } else {
            final String arithmeticNext = pinnedMessageUids.get((currentIndex + 1) % size);
            chosen = null;
            for (int step = 1; step < size; step++) {
                final String candidate = pinnedMessageUids.get((currentIndex + step) % size);
                if (findAdapterPositionByUid(candidate) >= 0) {
                    // First resolving sibling: cycle to it.
                    chosen = candidate;
                    break;
                }
                // candidate does not resolve in the loaded window: skip it, keep scanning siblings.
            }
            if (chosen == null) {
                // No sibling is loaded (all other pins are paged out): advance to the next pin anyway so
                // the cycle reaches a DIFFERENT message; the full-load jump pages it in on the next tap.
                chosen = arithmeticNext;
                logger.info("advancePinnedCycler: no sibling pin resolves in window; advancing to arithmetic next uid={}", chosen);
            }
        }
        currentPinnedMessageUid = chosen;
        logger.info("advancePinnedCycler: next tap will target uid={}", currentPinnedMessageUid);
        renderPinnedBannerFromState();
    }

    // Duration the jumped-to row stays highlighted before we clear the activated state, in ms.
    private static final long PINNED_HIGHLIGHT_DURATION_MS = 1400L;

    /**
     * F1Whisper: flash the jumped-to pinned row using the SAME native, recycle-safe mechanism the
     * upstream search-quote jump uses — {@link android.widget.ListView#setItemChecked(int, boolean)}.
     * <p>
     * The list is in {@code choiceMode="singleChoice"}; activating an item drives the bubble's
     * {@code state_activated} selector (e.g. {@code bubble_fade_send_selector}), which the bubble
     * MaterialCardView renders as a brief tint — no home-grown background mutation on the row root
     * (the previous approach called {@code row.setBackground(null)}, which fought the choice-mode /
     * activated selector and could clear a recycled row's real background). The position is re-resolved
     * by uid at apply-time (Telegram-style scrollToMessageId), so a message that shifted or was deleted
     * never flashes a wrong row. The activated state is cleared after {@link #PINNED_HIGHLIGHT_DURATION_MS}
     * by re-resolving the position by uid again (the row may have recycled by then).
     */
    @UiThread
    private void flashPinnedRow(@Nullable final String uid) {
        if (convListView == null || uid == null) {
            return;
        }
        final int position = findAdapterPositionByUid(uid);
        if (position < 0) {
            logger.info("Pinned highlight: uid={} no longer resolves; skipping flash", uid);
            return;
        }
        // Cancel any in-flight highlight clear so rapid taps don't leave a stuck activated row.
        if (pinnedHighlightClear != null) {
            pinnedHighlightHandler.removeCallbacks(pinnedHighlightClear);
            pinnedHighlightClear = null;
        }
        logger.info("Pinned highlight: flashing row at position={} for uid={}", position, uid);
        convListView.setItemChecked(position, true);
        // Clear by re-resolving the position by uid (the row may have recycled/shifted by now), so we
        // never leave a stale activated row and never un-check an unrelated message.
        pinnedHighlightClear = () -> {
            if (convListView == null) {
                return;
            }
            final int livePosition = findAdapterPositionByUid(uid);
            if (livePosition >= 0) {
                convListView.setItemChecked(livePosition, false);
            } else {
                convListView.clearChoices();
                convListView.requestLayout();
            }
        };
        pinnedHighlightHandler.postDelayed(pinnedHighlightClear, PINNED_HIGHLIGHT_DURATION_MS);
    }

    @UiThread
    /**
     * F1Whisper: build the "&lt;names&gt; is/are typing…" subtitle for a group chat, or {@code null}
     * if no member is currently typing.
     */
    @Nullable
    private CharSequence buildGroupTypingText() {
        if (!isGroupChat || groupModel == null) {
            return null;
        }
        final Set<String> typingIdentities = groupService.getTypingMembers(groupModel.getDatabaseId());
        if (typingIdentities.isEmpty()) {
            return null;
        }
        final List<String> names = new ArrayList<>(typingIdentities.size());
        for (String identity : typingIdentities) {
            names.add(NameUtil.getShortName(
                contactService.getByIdentity(identity),
                preferenceService.getContactNameFormat()
            ));
        }
        // stable, deterministic ordering for a steady subtitle
        Collections.sort(names);
        switch (names.size()) {
            case 1:
                return getString(R.string.group_one_typing, names.get(0));
            case 2:
                return getString(R.string.group_two_typing, names.get(0), names.get(1));
            default:
                return getString(R.string.group_many_typing, names.get(0), names.size() - 1);
        }
    }

    private void updateToolbarTitle() {
        if (
            actionBar == null
                || actionBarSubtitleImageView == null
                || actionBarSubtitleTextView == null
                || actionBarTitleTextView == null
                || emojiMarkupUtil == null
                || messageReceiver == null
                || !isAdded()
                || getActivity() == null
                || !requiredInstances()
        ) {
            return;
        }

        this.actionBarSubtitleTextView.setVisibility(View.GONE);
        this.actionBarSubtitleImageView.setVisibility(View.GONE);
        this.actionBarAvatarView.setVisibility(View.VISIBLE);

        this.actionBarTitleTextView.setText(
            this.messageReceiver.getDisplayName(preferenceService.getContactNameFormat())
        );
        this.actionBarTitleTextView.setPaintFlags(this.actionBarTitleTextView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);

        if (this.isGroupChat) {
            if (groupModel != null && !groupModel.isMember()) {
                this.actionBarTitleTextView.setPaintFlags(this.actionBarTitleTextView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
            // F1Whisper: show "... is typing" when group members are typing, otherwise the member list
            CharSequence groupSubtitle = buildGroupTypingText();
            if (groupSubtitle == null) {
                groupSubtitle = groupService.getMembersString(groupModel);
            }
            actionBarSubtitleTextView.setText(groupSubtitle);
            actionBarSubtitleTextView.setVisibility(View.VISIBLE);
            if (actionBarAvatarView.getAvatarView().isAttachedToWindow()) {
                groupService.loadAvatarIntoImageView(
                    groupModel,
                    actionBarAvatarView.getAvatarView(),
                    AvatarOptions.PRESET_DEFAULT_FALLBACK,
                    Glide.with(requireActivity())
                );
            }
            actionBarAvatarView.setWorkBadgeVisible(false);
            setAvatarContentDescription(R.string.prefs_group_notifications);
        } else if (this.isDistributionListChat) {
            actionBarSubtitleTextView.setText(this.distributionListService.getMembersString(this.distributionListModel));
            actionBarSubtitleTextView.setVisibility(View.VISIBLE);
            if (this.distributionListModel.isHidden()) {
                actionBarAvatarView.setVisibility(View.GONE);
                actionBarTitleTextView.setText(getString(R.string.threema_message_to, ""));
            } else {
                if (actionBarAvatarView.getAvatarView().isAttachedToWindow()) {
                    distributionListService.loadAvatarIntoImage(
                        distributionListModel.getId(),
                        actionBarAvatarView.getAvatarView(),
                        AvatarOptions.PRESET_DEFAULT_AVATAR_NO_CACHE,
                        Glide.with(requireActivity())
                    );
                }
            }
            actionBarAvatarView.setWorkBadgeVisible(false);
            setAvatarContentDescription(R.string.distribution_list);
        } else {
            if (contactModel != null) {
                this.actionBarSubtitleImageView.setVerificationLevel(
                    contactModel.verificationLevel,
                    contactModel.getWorkVerificationLevel()
                );
                this.actionBarSubtitleImageView.setVisibility(View.VISIBLE);
                if (actionBarAvatarView.getAvatarView().isAttachedToWindow()) {
                    contactService.loadAvatarIntoImage(
                        contactModel.getIdentity(),
                        this.actionBarAvatarView.getAvatarView(),
                        AvatarOptions.PRESET_DEFAULT_FALLBACK,
                        Glide.with(requireActivity())
                    );
                }
                this.actionBarAvatarView.setWorkBadgeVisible(contactService.showBadge(contactModel));
            }
            setAvatarContentDescription(R.string.prefs_header_chat);
        }
        this.actionBarTitleTextView.invalidate();
        this.actionBarSubtitleTextView.invalidate();
        this.actionBarSubtitleImageView.invalidate();
    }

    private void setAvatarContentDescription(@StringRes int stringRes) {
        try {
            actionBarAvatarView.setContentDescription(getString(stringRes));
        } catch (IllegalStateException e) {
            logger.error("Can't set content description", e);
        }
    }

    @Override
    @SuppressLint("StaticFieldLeak")
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.fragment_compose_message, menu);
        this.setupToolbar();

        super.onCreateOptionsMenu(menu, inflater);

        ConfigUtils.addIconsToOverflowMenu(menu);
    }

    @Override
    @Deprecated
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        this.callItem = menu.findItem(R.id.menu_threema_call);
        this.deleteDistributionListItem = menu.findItem(R.id.menu_delete_distribution_list);
        this.mutedMenuItem = menu.findItem(R.id.menu_muted);
        this.blockMenuItem = menu.findItem(R.id.menu_block_contact);
        this.showOpenBallotWindowMenuItem = menu.findItem(R.id.menu_ballot_window_show);
        this.showBallotsMenuItem = menu.findItem(R.id.menu_ballot_show_all);
        this.showEmptyChatMenuItem = menu.findItem(R.id.menu_empty_chat);
        this.disappearingMessagesMenuItem = menu.findItem(R.id.menu_disappearing_messages);

        // initialize menus
        updateMenus();
        updateMuteMenu();

        // initialize various toolbar items
        this.updateToolbarTitle();
    }

    @SuppressLint("StaticFieldLeak")
    private void updateMenus() {
        logger.debug("updateMenus");

        if (
            callItem == null
                || deleteDistributionListItem == null
                || mutedMenuItem == null
                || blockMenuItem == null
                || showOpenBallotWindowMenuItem == null
                || showEmptyChatMenuItem == null
                || !isAdded()
        ) {
            return;
        }

        this.deleteDistributionListItem.setVisible(this.isDistributionListChat);
        this.mutedMenuItem.setVisible(!this.isDistributionListChat && !isNotesGroupChat());
        updateMuteMenu();

        // F1Whisper: disappearing-messages timer only applies to real conversations with a peer.
        // Distribution lists are local-only (no E2E peer) and notes groups have no recipient, so the
        // timer would have nothing to sync to; group chats need the user to still be a member.
        if (this.disappearingMessagesMenuItem != null) {
            final boolean canDisappear = !this.isDistributionListChat
                && !isNotesGroupChat()
                && !isGroupChatWhereUserIsNotMemberOf();
            this.disappearingMessagesMenuItem.setVisible(canDisappear);
        }

        if (contactModel != null) {
            this.blockMenuItem.setVisible(true);
            updateBlockMenu();
            contactTypingStateChanged(contactService.isTyping(contactModel.getIdentity()));
        } else {
            this.blockMenuItem.setVisible(false);
        }

        if (BallotUtil.canVote(messageReceiver)) {
            new AsyncTask<Void, Void, Long>() {
                @Override
                protected Long doInBackground(Void... voids) {
                    return ballotService.countBallots(new BallotService.BallotFilter() {
                        @Override
                        public MessageReceiver getReceiver() {
                            return messageReceiver;
                        }

                        @Override
                        public BallotModel.State[] getStates() {
                            return new BallotModel.State[]{BallotModel.State.OPEN};
                        }

                        @Override
                        public String createdOrNotVotedByIdentity() {
                            return userService.getIdentity();
                        }
                    });
                }

                @Override
                protected void onPostExecute(Long openBallots) {
                    showOpenBallotWindowMenuItem.setVisible(openBallots > 0L);

                    if (preferenceService.getBallotOverviewHidden()) {
                        showOpenBallotWindowMenuItem.setIcon(R.drawable.ic_outline_visibility);
                        showOpenBallotWindowMenuItem.setTitle(R.string.ballot_window_show);
                    } else {
                        showOpenBallotWindowMenuItem.setIcon(R.drawable.ic_outline_visibility_off);
                        showOpenBallotWindowMenuItem.setTitle(R.string.ballot_window_hide);
                    }
                    Context context = getContext();
                    if (context != null) {
                        ConfigUtils.tintMenuIcon(context, showOpenBallotWindowMenuItem, R.attr.colorOnSurface);
                    }
                }
            }.execute();
        } else {
            showOpenBallotWindowMenuItem.setVisible(false);
        }

        new AsyncTask<Void, Void, Long>() {
            @Override
            protected Long doInBackground(Void... voids) {
                return ballotService.countBallots(new BallotService.BallotFilter() {
                    @Override
                    public MessageReceiver getReceiver() {
                        return messageReceiver;
                    }

                    @Override
                    public BallotModel.State[] getStates() {
                        return new BallotModel.State[]{BallotModel.State.OPEN, BallotModel.State.CLOSED};
                    }
                });
            }

            @Override
            protected void onPostExecute(Long hasBallots) {
                showBallotsMenuItem.setVisible(hasBallots > 0L);
            }
        }.execute();

        // Show "empty chat" only if chat is not empty
        this.showEmptyChatMenuItem.setVisible(composeMessageAdapter != null && !composeMessageAdapter.isEmpty());

        updateVoipCallMenuItem(null);
    }

    private boolean isNotesGroupChat() {
        return isGroupChat && groupModel != null && Boolean.TRUE.equals(groupModel.isNotesGroup());
    }

    @UiThread
    private void updateMuteMenu() {
        if (!isAdded() || this.mutedMenuItem == null) {
            // do not update if no longer attached to activity
            return;
        }
        if (isMentionsOnly()) {
            this.mutedMenuItem.setIcon(R.drawable.ic_dnd_mention_grey600_24dp);
            this.mutedMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        } else if (isMuted()) {
            this.mutedMenuItem.setIcon(R.drawable.ic_dnd_total_silence_grey600_24dp);
            this.mutedMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        } else if (isSilent()) {
            this.mutedMenuItem.setIcon(R.drawable.ic_notifications_off_outline);
            this.mutedMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        } else {
            this.mutedMenuItem.setIcon(R.drawable.ic_notifications_active_outline);
            this.mutedMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
    }

    private void updateBlockMenu() {
        if (!isAdded()) {
            // do not update if no longer attached to activity
            return;
        }
        if (blockMenuItem != null && blockedIdentitiesService != null && contactModel != null) {
            boolean hasBlockedThisIdentity = this.blockedIdentitiesService.isBlocked(this.contactModel.getIdentity());
            this.blockMenuItem.setTitle(hasBlockedThisIdentity ? getString(R.string.unblock_contact) : getString(R.string.block_contact));
            this.blockMenuItem.setShowAsAction(hasBlockedThisIdentity ? MenuItem.SHOW_AS_ACTION_ALWAYS : MenuItem.SHOW_AS_ACTION_NEVER);
            this.mutedMenuItem.setShowAsAction(hasBlockedThisIdentity ? MenuItem.SHOW_AS_ACTION_NEVER : MenuItem.SHOW_AS_ACTION_IF_ROOM);
            this.mutedMenuItem.setVisible(!hasBlockedThisIdentity);

            this.callItem.setShowAsAction(hasBlockedThisIdentity ? MenuItem.SHOW_AS_ACTION_NEVER : MenuItem.SHOW_AS_ACTION_ALWAYS);

            updateVoipCallMenuItem(!hasBlockedThisIdentity);
        }
    }

    @AnyThread
    private void updateVoipCallMenuItem(@Nullable final Boolean showVoipCallMenuItem) {
        RuntimeUtil.runOnUiThread(() -> {
            if (isGroupChat) {
                updateGroupCallMenuItem();
            } else if (callItem != null) {
                if (ContactUtil.canReceiveVoipMessages(contactModel, blockedIdentitiesService) && ConfigUtils.isCallsEnabled()) {
                    boolean isVoipStateIdle = voipStateService.getCallState().isIdle();
                    logger.debug("updateVoipMenu showVoipCallMenuItem: {}", showVoipCallMenuItem);
                    logger.debug("updateVoipMenu isVoipStateIdle: {}", isVoipStateIdle);
                    callItem.setIcon(R.drawable.ic_phone_locked_outline);
                    callItem.setTitle(R.string.threema_call);
                    callItem.setVisible(showVoipCallMenuItem != null ? showVoipCallMenuItem : isVoipStateIdle);
                } else {
                    callItem.setVisible(false);
                }
            }
        });
    }

    @UiThread
    private void updateGroupCallMenuItem() {
        if (groupModel == null) {
            logger.warn("Group model is null");
            return;
        }
        if (groupService == null) {
            logger.warn("Group service is null");
            return;
        }

        if (isGroupChat && callItem != null) {
            GroupModelOld legacyGroupModel = groupService.getByGroupIdentity(groupModel.getGroupIdentity());

            if (legacyGroupModel != null && GroupCallUtil.qualifiesForGroupCalls(groupService, legacyGroupModel)) {
                GroupCallDescription call = groupCallManager.getCurrentChosenCall(groupModel);
                callItem.setIcon(R.drawable.ic_phone_locked_outline);
                callItem.setTitle(R.string.group_call);
                callItem.setVisible(call == null);
            } else {
                callItem.setVisible(false);
            }
        }
    }

    private Intent addExtrasToIntent(Intent intent, @NonNull MessageReceiver receiver) {
        switch (receiver.getType()) {
            case MessageReceiver.Type_GROUP:
                intent.putExtra(AppConstants.INTENT_DATA_GROUP_DATABASE_ID, groupDbId);
                break;
            case MessageReceiver.Type_DISTRIBUTION_LIST:
                intent.putExtra(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID, distributionListModel.getId());
                break;
            case MessageReceiver.Type_CONTACT:
            default:
                intent.putExtra(AppConstants.INTENT_DATA_CONTACT, identity);
        }
        return intent;
    }

    private void attachCamera() {
        Intent previewIntent = IntentDataUtil.addMessageReceiversToIntent(new Intent(activity, SendMediaActivity.class), new MessageReceiver[]{this.messageReceiver});
        if (this.actionBarTitleTextView != null && this.actionBarTitleTextView.getText() != null) {
            previewIntent.putExtra(AppConstants.INTENT_DATA_TEXT, this.actionBarTitleTextView.getText().toString());
        }
        previewIntent.putExtra(AppConstants.INTENT_DATA_PICK_FROM_CAMERA, true);
        activity.startActivityForResult(previewIntent, ThreemaActivity.ACTIVITY_ID_SEND_MEDIA);
    }

    private void showPermissionRationale(int stringResource) {
        ConfigUtils.showPermissionRationale(getContext(), coordinatorLayout, stringResource);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            if (activity != null && activity.getIntent() != null && activity.getIntent().hasExtra(EXTRA_OVERRIDE_BACK_TO_HOME_BEHAVIOR)) {
                activity.getOnBackPressedDispatcher().onBackPressed();
                return true;
            } else {
                logger.info("back button clicked, closing chat");
                NavigationUtil.navigateUpToHome(activity);
            }
        } else if (id == R.id.menu_search_messages) {
            logger.info("Search button clicked");
            searchActionMode = activity.startSupportActionMode(new SearchActionMode());
        } else if (id == R.id.menu_gallery) {
            logger.info("Gallery button clicked");
            Intent mediaGalleryIntent = new Intent(activity, MediaGalleryActivity.class);
            if (this.messageReceiver != null) {
                activity.startActivity(addExtrasToIntent(mediaGalleryIntent, this.messageReceiver));
            }
        } else if (id == R.id.menu_threema_call) {
            logger.info("Call button clicked");
            initiateCall();
        } else if (id == R.id.menu_wallpaper) {
            logger.info("Wallpaper button clicked");
            wallpaperService.selectWallpaper(this, this.wallpaperLauncher, this.messageReceiver, () -> RuntimeUtil.runOnUiThread(this::setBackgroundWallpaper));
        } else if (id == R.id.menu_disappearing_messages) {
            logger.info("Disappearing messages button clicked");
            showDisappearingMessagesPicker();
        } else if (id == R.id.menu_muted) {
            logger.info("Muting button clicked");
            if (!isDistributionListChat) {
                Intent intent;
                int[] location = new int[2];

                if (isGroupChat) {
                    intent = new Intent(activity, GroupNotificationsActivity.class);
                    intent.putExtra(AppConstants.INTENT_DATA_GROUP_DATABASE_ID, this.groupDbId);
                } else {
                    intent = new Intent(activity, ContactNotificationsActivity.class);
                    intent.putExtra(AppConstants.INTENT_DATA_CONTACT, this.identity);
                }
                if (messageReceiver != null) {
                    intent.putExtra(
                        AppConstants.INTENT_DATA_TEXT,
                        this.messageReceiver.getDisplayName(preferenceService.getContactNameFormat())
                    );
                }
                if (ToolbarUtil.getMenuItemCenterPosition(activity.getToolbar(), R.id.menu_muted, location)) {
                    intent.putExtra((AppConstants.INTENT_DATA_ANIM_CENTER), location);
                }
                activity.startActivity(intent);
            }
        } else if (id == R.id.menu_block_contact) {
            if (this.blockedIdentitiesService.isBlocked(contactModel.getIdentity())) {
                logger.info("Unblock button clicked");
                this.blockedIdentitiesService.unblockIdentity(contactModel.getIdentity(), getContext());
                updateBlockMenu();
            } else {
                logger.info("Block button clicked");
                GenericAlertDialog.newInstance(R.string.block_contact, R.string.really_block_contact, R.string.yes, R.string.no).setTargetFragment(this).show(getFragmentManager(), DIALOG_TAG_CONFIRM_BLOCK);
            }
        } else if (id == R.id.menu_delete_distribution_list) {
            logger.info("Delete distribution list button clicked, showing dialog");
            GenericAlertDialog.newInstance(R.string.really_delete_distribution_list,
                    R.string.really_delete_distribution_list_message,
                    R.string.ok,
                    R.string.cancel)
                .setTargetFragment(this)
                .setData(distributionListModel)
                .show(getFragmentManager(), CONFIRM_TAG_DELETE_DISTRIBUTION_LIST);
        } else if (id == R.id.menu_shortcut) {
            logger.info("Create shortcut button clicked");
            createShortcut();
        } else if (id == R.id.menu_empty_chat) {
            logger.info("Empty chat button clicked, showing dialog");
            GenericAlertDialog.newInstance(R.string.empty_chat_title,
                    R.string.empty_chat_confirm,
                    R.string.ok,
                    R.string.cancel)
                .setTargetFragment(this)
                .show(getFragmentManager(), DIALOG_TAG_EMPTY_CHAT);
        } else if (id == R.id.menu_ballot_window_show) {
            toggleOpenBallotNoticeViewVisibility();
        } else if (id == R.id.menu_ballot_show_all) {
            logger.info("Show ballots overview button clicked");
            Intent intent = new Intent(getContext(), BallotOverviewActivity.class);
            IntentDataUtil.addMessageReceiverToIntent(intent, messageReceiver);
            startActivity(intent);
        }
        return false;
    }

    private void toggleOpenBallotNoticeViewVisibility() {
        if (openBallotNoticeView.isShown()) {
            preferenceService.setBallotOverviewHidden(true);
            openBallotNoticeView.hide(true);
        } else {
            preferenceService.setBallotOverviewHidden(false);
            openBallotNoticeView.show(true);
        }
    }

    private void initiateCall() {
        if (isGroupChat) {
            GroupModelOld legacyGroupModel = groupService.getByGroupIdentity(groupModel.getGroupIdentity());
            if (legacyGroupModel != null) {
                GroupCallUtil.initiateCall(activity, legacyGroupModel);
            } else {
                logger.error("Could not get legacy group model to initiate the group call");
            }
        } else {
            VoipUtil.initiateCall(activity, contactModel, false, null);
        }
    }

    private void emptyChat() {
        if (messageReceiver != null) {
            logger.info("Empty chat with receiver {} (type={}).", messageReceiver.getUniqueIdString(), messageReceiver.getType());
        } else {
            logger.warn("Cannot empty chat, messageReceiver is null.");
        }
        new EmptyOrDeleteConversationsAsyncTask(
            EmptyOrDeleteConversationsAsyncTask.Mode.EMPTY,
            new MessageReceiver[]{messageReceiver},
            conversationService,
            distributionListService,
            groupModelRepository,
            groupFlowDispatcher,
            userService.getIdentity(),
            getParentFragmentManager(),
            null,
            () -> {
                if (isAdded() && messageReceiver != null) {
                    synchronized (messageValues) {
                        messageValues.clear();
                        composeMessageAdapter.notifyDataSetChanged();
                    }

                    draftManager.remove(messageReceiver.getUniqueIdString());
                    messageText.setText(null);

                    setCurrentPageReferenceId(null);
                    onRefresh();

                    ListenerManager.conversationListeners.handle(listener -> {
                        if (!isGroupChat) {
                            conversationService.reset();
                        }
                        listener.onModifiedAll();
                    });
                }
            }).execute();
    }

    private void createShortcut() {
        if (!this.isGroupChat &&
            !this.isDistributionListChat &&
            ContactUtil.canReceiveVoipMessages(contactModel, blockedIdentitiesService) &&
            ConfigUtils.isCallsEnabled()) {
            ArrayList<SelectorDialogItem> items = new ArrayList<>();
            items.add(new SelectorDialogItem(getString(R.string.prefs_header_chat), R.drawable.ic_outline_chat_bubble_outline));
            items.add(new SelectorDialogItem(getString(R.string.threema_call), R.drawable.ic_call_outline));
            SelectorDialog selectorDialog = SelectorDialog.newInstance(getString(R.string.shortcut_choice_title), items, getString(R.string.cancel));
            selectorDialog.setTargetFragment(this, 0);
            selectorDialog.show(getFragmentManager(), DIALOG_TAG_CHOOSE_SHORTCUT_TYPE);
        } else {
            RuntimeUtil.runOnWorkerThread(
                () -> ShortcutUtil.createPinnedShortcut(messageReceiver, TYPE_CHAT, preferenceService.getContactNameFormat())
            );
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 final Intent intent) {
        if (requestCode == ACTIVITY_ID_VOICE_RECORDER) {
            if (this.messagePlayerService != null) {
                logger.info("Voice recording received for attaching");
                this.messagePlayerService.resumeAll(getActivity(), messageReceiver, SOURCE_AUDIORECORDER);
            }
        }
        if (requestCode == ThreemaActivity.ACTIVITY_ID_ATTACH_MEDIA) {
            restoreMessageDraft(true);
            if (resultCode == Activity.RESULT_OK) {
                logger.info("Media file(s) received for attaching");
                this.lastMediaFilter = IntentDataUtil.getLastMediaFilterFromIntent(intent);
            }
        }
    }

    private final SearchView.OnQueryTextListener queryTextListener = new SearchView.OnQueryTextListener() {
        // listener for search bar on top
        @Override
        public boolean onQueryTextChange(String newText) {
            composeMessageAdapter.getFilter().filter(newText);
            return true;
        }

        @Override
        public boolean onQueryTextSubmit(String query) {
            composeMessageAdapter.nextMatchPosition();
            return true;
        }
    };

    @Override
    public void onClick(String tag, int which, Object data) {
        if (DIALOG_TAG_CHOOSE_SHORTCUT_TYPE.equals(tag)) {
            logger.info("Creating shortcut");
            final int shortcutType = which + 1;
            RuntimeUtil.runOnWorkerThread(
                () -> ShortcutUtil.createPinnedShortcut(messageReceiver, shortcutType, preferenceService.getContactNameFormat())
            );
        }
    }

    @Override
    public void onCancel(String tag) {
    }

    @Override
    public void onNo(String tag) {
    }

    //region VoipStatusDataChatListener
    @Override
    public void showDialog(String name) {
        GenericAlertDialog dialog = GenericAlertDialog.newInstance(
            R.string.threema_call,
            String.format(getContext().getString(R.string.voip_call_confirm), name),
            R.string.ok,
            R.string.cancel
        );
        dialog.setTargetFragment(this, 0);
        dialog.show(getFragmentManager(), ComposeMessageFragment.DIALOG_TAG_CONFIRM_CALL);
    }
    //endregion

    //region BallotChatListener
    @Override
    public void showSelectorDialog(
        ArrayList<Integer> action,
        String title,
        ArrayList<SelectorDialogItem> items,
        BallotModel ballotModel
    ) {
        SelectorDialog selectorDialog = SelectorDialog.newInstance(title, items, null, new SelectorDialog.SelectorDialogInlineClickListener() {
            @Override
            public void onClick(String tag, int which, Object data) {
                switch (action.get(which)) {
                    case ACTION_VOTE:
                        BallotUtil.openVoteDialog(getFragmentManager(), ballotModel);
                        break;
                    case ACTION_RESULTS:
                        BallotUtil.openMatrixActivity(getContext(), ballotModel);
                        break;
                    case ACTION_CLOSE:
                        BallotUtil.requestCloseBallot(ballotModel, ComposeMessageFragment.this, null);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
            }
        });
        selectorDialog.show(getFragmentManager(), "chooseAction");
    }

    @Override
    public void onLinkNeedsConfirmation(@NonNull String warning, @NonNull Uri uri) {
        GenericAlertDialog dialog = GenericAlertDialog.newInstance(R.string.url_warning_title, warning, R.string.ok, R.string.cancel);
        dialog.setData(uri);
        dialog.setTargetFragment(this, 0);
        dialog.show(getFragmentManager(), DIALOG_TAG_CONFIRM_LINK);
    }

    @Override
    public void showBottomSheetGridDialog(ArrayList<BottomSheetItem> items) {
        BottomSheetGridDialog dialog = BottomSheetGridDialog.newInstance(R.string.add_contact_in, items);
        dialog.setCallback((tag, data) -> LinkifyUtil.launchAddContactActivity(requireContext(), tag, data));
        dialog.show(getParentFragmentManager(), "bsh");
    }

    @Override
    public @NonNull MessagePlayer create(
        @NonNull AbstractMessageModel messageModel,
        @Nullable ListenableFuture<MediaController> mediaControllerFuture
    ) {
        return messagePlayerService.createPlayer(
            messageModel,
            new WeakReference<>(activity),
            messageReceiver,
            mediaControllerFuture
        );
    }

    @Override
    public void viewImage(AbstractMessageModel model) {
        Intent intent = new Intent(requireContext(), MediaViewerActivity.class);
        IntentDataUtil.append(model, intent);
        intent.putExtra(MediaViewerActivity.EXTRA_ID_REVERSE_ORDER, true);
        activity.startActivityForResult(intent, ThreemaActivity.ACTIVITY_ID_MEDIA_VIEWER);
    }

    @Override
    public void showPrepareDownloadDialog(Runnable onConfirmed) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download)
            .setMessage(R.string.send_as_files_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, id) -> onConfirmed.run())
            .show();
    }

    @Override
    public void interact() {
        ActivityService.activityUserInteract(activity);
    }

    @Override
    public void openDefaultActivity(BallotModel ballotModel, boolean canVote) {
        BallotUtil.openDefaultActivity(getContext(), getFragmentManager(), ballotModel, canVote);
    }
    //endregion

    public class ComposeMessageAction implements ActionMode.Callback {
        private final int position;
        private MenuItem quoteItem, forwardItem, saveItem, copyItem, shareItem, infoItem, editItem, starItem, unStarItem, pinItem, unPinItem, imageReplyItem, deleteItem;

        ComposeMessageAction(int position) {
            this.position = position;
            longClickItem = position;
        }

        private void updateActionMenu(Menu menu) {
            boolean isSingleMessage = selectedMessages.size() == 1;
            boolean isQuotable = isSingleMessage;
            boolean canShowInfo = isSingleMessage;
            boolean isForwardable = selectedMessages.size() <= MAX_FORWARDABLE_ITEMS;
            boolean isSaveable = !dependencies.getAppRestrictions().isShareMediaDisabled();
            boolean isCopyable = true;
            boolean isShareable = !dependencies.getAppRestrictions().isShareMediaDisabled();
            boolean isEditable = isSingleMessage
                && MessageUtilKt.canBeEdited(selectedMessages.get(0), isNotesGroupChat())
                && !isGroupChatWhereUserIsNotMemberOf();
            boolean canSendImageReply = isSingleMessage && MessageUtil.canSendImageReply(selectedMessages.get(0));
            boolean canStarMessage = isSingleMessage && MessageUtil.canStarMessage(selectedMessages.get(0));

            if (selectedMessages.stream().anyMatch(AbstractMessageModel::isDeleted)) {
                if (isSingleMessage) {
                    onlyShowItems(menu, R.id.menu_message_discard, R.id.menu_info);
                } else {
                    onlyShowItems(menu, R.id.menu_message_discard);
                }
                return;
            }

            for (AbstractMessageModel message : selectedMessages) {
                if (message == null) continue;
                isQuotable = isQuotable && isQuotable(message);
                isForwardable = isForwardable && isForwardable(message);
                isSaveable = isSaveable && isSaveable(message);
                isCopyable = isCopyable && isCopyable(message);
                isShareable = isShareable && isShareable(message);
            }

            // Sharing text message is only possible when there is exactly one selected message
            isShareable = isShareable && (isSingleMessage || !containsTextMessage(selectedMessages));

            quoteItem.setVisible(isQuotable);
            infoItem.setVisible(canShowInfo);
            forwardItem.setVisible(isForwardable);
            saveItem.setVisible(isSaveable);
            copyItem.setVisible(isCopyable);
            shareItem.setVisible(isShareable);
            editItem.setVisible(isEditable);
            imageReplyItem.setVisible(canSendImageReply);

            boolean isMessageCurrentlyStarred = !selectedMessages.isEmpty()
                && (selectedMessages.get(0).getDisplayTags() & DisplayTag.DISPLAY_TAG_STARRED) == DisplayTag.DISPLAY_TAG_STARRED;
            starItem.setVisible(canStarMessage && !isMessageCurrentlyStarred);
            unStarItem.setVisible(canStarMessage && isMessageCurrentlyStarred);

            // F1Whisper: pin/unpin (local-only; mirrors star/unstar logic above)
            boolean isMessageCurrentlyPinned = !selectedMessages.isEmpty()
                && (selectedMessages.get(0).getDisplayTags() & DISPLAY_TAG_PINNED) == DISPLAY_TAG_PINNED;
            pinItem.setVisible(isSingleMessage && !isMessageCurrentlyPinned);
            unPinItem.setVisible(isSingleMessage && isMessageCurrentlyPinned);

            deleteItem.setShowAsAction(isSingleMessage ? MenuItem.SHOW_AS_ACTION_IF_ROOM : MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        private void onlyShowItems(Menu menu, int... ids) {
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                boolean show = Arrays.stream(ids).anyMatch(id -> id == item.getItemId());
                item.setVisible(show);
            }
        }

        private boolean isQuotable(@NonNull AbstractMessageModel message) {
            if (messageReceiver == null) {
                return false;
            }
            boolean isValidReceiver = messageReceiver.validateSendingPermission().isValid();
            return isValidReceiver && QuoteUtil.isQuoteable(message);
        }

        /**
         * F1Whisper: a "listen once" voice message must not be forwarded, saved or shared. Otherwise
         * the recipient could exfiltrate it (or replay it) before playing it once, defeating the
         * single-playback guarantee. Mirrors Telegram, whose {@code canForwardMessage()} returns
         * false for view-once messages. Gated on the {@code "lo"} flag itself (set on incoming AND
         * the sender's own copy), independent of whether it has already burned.
         */
        private boolean isListenOnce(@NonNull AbstractMessageModel message) {
            return message.getFileData() != null && message.getFileData().isListenOnce();
        }

        private boolean isForwardable(@NonNull AbstractMessageModel message) {
            if (isListenOnce(message)) {                                // F1Whisper: never forward a listen-once message
                return false;
            }
            return message.isAvailable()                                // if the media is downloaded
                && !message.isStatusMessage()                           // and the message is not status message (unread or status)
                && message.getType() != MessageType.BALLOT              // and not a ballot
                && message.getType() != MessageType.VOIP_STATUS        // and not a voip status
                && message.getType() != MessageType.GROUP_CALL_STATUS;    // and not a group call status
        }

        private boolean isSaveable(@NonNull AbstractMessageModel message) {
            if (isListenOnce(message)) {                            // F1Whisper: never save a listen-once message
                return false;
            }
            return message.isAvailable()                            // if the message is available
                && (message.getType() == MessageType.IMAGE          // and it is an image
                || message.getType() == MessageType.VOICEMESSAGE    // or voice message
                || message.getType() == MessageType.VIDEO           // or video
                || message.getType() == MessageType.FILE);          // or file
        }

        private boolean isShareable(@NonNull AbstractMessageModel message) {
            if (isListenOnce(message)) {                    // F1Whisper: never share a listen-once message
                return false;
            }
            return message.isAvailable()                    // if the message is available
                && (message.getType() == MessageType.IMAGE  // and message is an image
                || message.getType() == MessageType.VIDEO   // or video
                || message.getType() == MessageType.FILE    // or voice message
                || message.getType() == MessageType.TEXT);  // or text message
        }

        private boolean isCopyable(@NonNull AbstractMessageModel message) {
            boolean isText = message.getType() == MessageType.TEXT && !message.isStatusMessage();
            boolean isFileWithCaption = message.getType() == MessageType.FILE
                && !TextUtils.isEmpty(message.getCaption());
            return isText || isFileWithCaption; // is text (not status) or a file with non-empty caption
        }

        private boolean containsTextMessage(@NonNull List<AbstractMessageModel> messages) {
            for (AbstractMessageModel message : messages) {
                if (message.getType() == MessageType.TEXT) {
                    return true;
                }
            }
            return false;
        }

        private boolean isMultiDeviceActive() {
            return multiDeviceManager.isMultiDeviceActive();
        }

        private boolean isDeletableRemotely(AbstractMessageModel message) {
            // check receiver support
            if (messageReceiver instanceof GroupMessageReceiver) {
                GroupModel groupModel = ((GroupMessageReceiver) messageReceiver).getGroupModel();
                if (groupModel == null || !groupModel.isMember()) {
                    return false;
                } else if (Boolean.TRUE.equals(groupModel.isNotesGroup()) && !isMultiDeviceActive()) {
                    // Notes group: remote delete does not make sense if multi device is not active
                    return false;
                } else if (Boolean.FALSE.equals(groupModel.isNotesGroup())) {
                    // Checking feature support only makes sense if it is not a notes group
                    GroupModelData groupModelData = groupModel.getData();
                    if (groupModelData == null) {
                        return false;
                    }
                    GroupFeatureSupport featureSupport = groupService.getFeatureSupport(groupModelData, ThreemaFeature.DELETE_MESSAGES);
                    if (featureSupport.getAdoptionRate() == GroupFeatureAdoptionRate.NONE) {
                        // no feature support in group
                        return false;
                    }
                }
            } else if (messageReceiver instanceof ContactMessageReceiver
                && !ThreemaFeature.canDeleteMessages(((ContactMessageReceiver) messageReceiver).getContact().getFeatureMask())
            ) {
                // no feature support in 1:1 chat
                return false;
            }
            // check message support
            return canDeleteRemotely(message, messageReceiver);
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (this.position == AbsListView.INVALID_POSITION) {
                return false;
            }

            if (convListView.getCheckedItemCount() < 1) {
                return false;
            }

            MenuInflater inflater = mode.getMenuInflater();
            if (inflater != null) {
                inflater.inflate(R.menu.action_compose_message, menu);
            }

            ConfigUtils.addIconsToOverflowMenu(menu);

            forwardItem = menu.findItem(R.id.menu_message_forward);
            saveItem = menu.findItem(R.id.menu_message_save);
            copyItem = menu.findItem(R.id.menu_message_copy);
            shareItem = menu.findItem(R.id.menu_share);
            quoteItem = menu.findItem(R.id.menu_message_quote);
            infoItem = menu.findItem(R.id.menu_info);
            editItem = menu.findItem(R.id.menu_message_edit);
            starItem = menu.findItem(R.id.menu_message_star);
            unStarItem = menu.findItem(R.id.menu_message_unstar);
            pinItem = menu.findItem(R.id.menu_message_pin);
            unPinItem = menu.findItem(R.id.menu_message_unpin);
            imageReplyItem = menu.findItem(R.id.menu_message_image_reply);
            deleteItem = menu.findItem(R.id.menu_message_discard);

            updateActionMenu(menu);

            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            final int checked = convListView.getCheckedItemCount();

            mode.setTitle(Integer.toString(checked));
            updateActionMenu(menu);

            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            // F1Whisper: drop any selected disappearing messages that have expired before acting on
            // them (forward / share / save / details), so an overdue message is never revived.
            selectedMessages.removeIf(DisappearingMessageService::enforceIfExpired);
            if (selectedMessages.isEmpty()) {
                mode.finish();
                return true;
            }

            final int id = item.getItemId();
            if (id == R.id.menu_message_copy) {
                logger.info("Action menu: copy clicked");
                copySelectedMessagesToClipboard();
                mode.finish();
            } else if (id == R.id.menu_message_discard) {
                if (selectedMessages.size() == 1 && isDeletableRemotely(selectedMessages.get(0))) {
                    logger.info("Action menu: delete message for all clicked");
                    showDeleteMessagesForAllDialog(selectedMessages.get(0));
                } else {
                    logger.info("Action menu: delete message(s) locally clicked");
                    showDeleteMessagesLocallyDialog();
                }
            } else if (id == R.id.menu_message_forward) {
                logger.info("Action menu: forward message clicked");
                startForwardMessage();
                mode.finish();
            } else if (id == R.id.menu_message_save) {
                logger.info("Action menu: save media clicked");
                if (ConfigUtils.requestWriteStoragePermissions(activity, ComposeMessageFragment.this, PERMISSION_REQUEST_SAVE_MESSAGE)) {
                    fileService.saveMedia(activity, coordinatorLayout, new CopyOnWriteArrayList<>(selectedMessages), false);
                }
                mode.finish();
            } else if (id == R.id.menu_share) {
                logger.info("Action menu: share messages clicked");
                shareMessages();
                mode.finish();
            } else if (id == R.id.menu_message_quote) {
                logger.info("Action menu: quote clicked");
                showQuotePopup(null, true);
                mode.finish();
            } else if (id == R.id.menu_info) {
                logger.info("Action menu: show message details clicked");
                showMessageDetailScreen(selectedMessages.get(0));
                mode.finish();
            } else if (id == R.id.menu_message_star || id == R.id.menu_message_unstar) {
                logger.info("Action menu: (un)star clicked");
                toggleStar(selectedMessages.get(0));
                mode.finish();
            } else if (id == R.id.menu_message_pin || id == R.id.menu_message_unpin) {
                // F1Whisper: local pin/unpin — no wire change
                logger.info("Action menu: (un)pin clicked");
                togglePin(selectedMessages.get(0));
                mode.finish();
            } else if (id == R.id.menu_message_edit) {
                logger.info("Action menu: edit clicked");
                tryEditingSelectedMessage();
                mode.finish();
            } else if (id == R.id.menu_message_image_reply) {
                logger.info("Action menu: reply clicked");
                sendImageReply();
                mode.finish();
            } else {
                return false;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            longClickItem = AbsListView.INVALID_POSITION;

            // handle done button
            convListView.clearChoices();
            convListView.requestLayout();
            convListView.post(() -> convListView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE));

            if (emojiReactionsPopup != null) {
                emojiReactionsPopup.dismiss();
            }

            // If the action mode has been left without clearing up the selected messages, we need
            // to trigger a refresh so that linkified links work again (selectedMessages will be cleared lazily)
            if (!selectedMessages.isEmpty() && composeMessageAdapter != null) {
                composeMessageAdapter.notifyDataSetChanged();
            }
        }
    }

    private void setMessageTextMaxLength(int max) {
        var filters = messageText.getFilters();
        for (int i = 0; i < filters.length; i++) {
            if (filters[i] instanceof InputFilter.LengthFilter) {
                filters[i] = new InputFilter.LengthFilter(max);
                // We need to re-apply the filters after modifying the array, otherwise the EditText won't pick up the new filter
                messageText.setFilters(filters);
                break;
            }
        }
    }

    private class EditMessageActionMode implements ActionMode.Callback {

        private final AbstractMessageModel messageModel;
        private boolean shouldRestoreQuotePanel;

        private final TextWatcher onEditMessageTextChangedListener = new SimpleTextWatcher() {
            @Override
            public void onTextChanged(@NonNull CharSequence charSequence, int i, int i1, int i2) {
                updateSendEditMessageButton(getEditableText(messageModel), charSequence.toString());
            }
        };

        public EditMessageActionMode(AbstractMessageModel messageModel) {
            this.messageModel = messageModel;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onCreateActionMode(@NonNull ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.action_edit_message, menu);

            mode.setTitle(R.string.edit_message);

            if (typingIndicatorTextWatcher != null) {
                messageText.removeTextChangedListener(typingIndicatorTextWatcher);
                typingIndicatorTextWatcher.stopSending();
            }
            if (draftUpdateTextWatcher != null) {
                messageText.removeTextChangedListener(draftUpdateTextWatcher);
            }

            updateMessageDraft();

            messageText.setText(getEditableText(messageModel));

            EditTextUtil.focusWindowAndShowSoftKeyboard(messageText);

            final @Nullable Editable messageTextEditable = messageText.getText();
            if (messageTextEditable != null) {
                messageText.setSelection(messageTextEditable.toString().length());
            }

            if (actionMode != null) {
                actionMode.finish();
            }

            ComposeJavaBridge.INSTANCE.setEditModeMessageBubble(
                editMessageBubbleComposeView,
                messageModel,
                preferenceService.getContactNameFormat(),
                ComposeMessageFragment.this
            );
            editMessageBubbleContainer.setVisibility(View.VISIBLE);

            if (isQuotePopupShown()) {
                shouldRestoreQuotePanel = true;
                dismissQuotePopup();
            }

            sendButton.setVisibility(View.GONE);
            attachButton.setVisibility(View.GONE);
            cameraButton.setVisibility(View.GONE);
            sendEditMessageButton.setVisibility(View.VISIBLE);

            dimBackground.setAlpha(0f);
            dimBackground.setVisibility(View.VISIBLE);
            dimBackground.animate().alpha(1f).setDuration(300).setListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(@NonNull Animator animation) {
                }

                @Override
                public void onAnimationEnd(@NonNull Animator animation) {
                    setupEditMessageTextActionListener(messageModel);
                    setupEditMessageButtonClickListener(messageModel);
                    messageText.addTextChangedListener(onEditMessageTextChangedListener);
                    final @Nullable Editable messageTextEditable = messageText.getText();
                    updateSendEditMessageButton(
                        getEditableText(messageModel),
                        (messageTextEditable != null) ? messageTextEditable.toString() : null
                    );
                }

                @Override
                public void onAnimationCancel(@NonNull Animator animation) {
                    if (editMessageActionMode != null) {
                        editMessageActionMode.finish();
                    }
                }

                @Override
                public void onAnimationRepeat(@NonNull Animator animation) {
                }
            });

            // usually messages are split into multiple messages if they are too long, but this is not possible when editing a message
            setMessageTextMaxLength(ProtocolDefines.MAX_TEXT_MESSAGE_LEN);

            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }

        private void setupEditMessageTextActionListener(@NonNull AbstractMessageModel messageModel) {
            messageText.setOnEditorActionListener(
                setupTextActionListener(
                    () -> {
                        final @Nullable Editable editedMessageTextEditable = messageText.getText();
                        if (editedMessageTextEditable == null) {
                            return;
                        }
                        final @Nullable String original = getEditableText(messageModel);
                        final @NonNull String edited = editedMessageTextEditable.toString();
                        if (canSendEditMessage(original, edited)) {
                            onSendEditMessage(messageModel, edited);
                        }
                    }
                )
            );
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void onDestroyActionMode(ActionMode mode) {

            if (messageReceiver != null) {
                // Restore a potential message draft from before editing
                final @Nullable MessageDraft messageDraft = draftManager.get(messageReceiver.getUniqueIdString());
                if (messageDraft != null) {
                    messageText.setText(messageDraft.getText());
                } else {
                    messageText.setText(null);
                }
                // Move the cursor to the end
                final @Nullable Editable messageTextEditable = messageText.getText();
                if (messageTextEditable != null) {
                    messageText.setSelection(messageTextEditable.toString().length());
                }
            }

            editMessageBubbleContainer.setVisibility(View.GONE);
            editMessageBubbleComposeView.disposeComposition();

            if (shouldRestoreQuotePanel) {
                showQuotePopup(messageModel, false);
            }

            sendEditMessageButton.setVisibility(View.GONE);

            messageText.removeTextChangedListener(onEditMessageTextChangedListener);

            setupSendMessageTextActionListener();

            dimBackground.animate().alpha(0f).setDuration(300).setListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(@NonNull Animator animation) {
                }

                @Override
                public void onAnimationEnd(@NonNull Animator animation) {
                    dimBackground.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationCancel(@NonNull Animator animation) {
                    dimBackground.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationRepeat(@NonNull Animator animation) {
                }
            });

            // restore default max length so long messages can be split again
            int defaultEditTextMaxLength = getResources().getInteger(R.integer.message_edittext_max_length);
            setMessageTextMaxLength(defaultEditTextMaxLength);

            if (typingIndicatorTextWatcher != null) {
                messageText.addTextChangedListener(typingIndicatorTextWatcher);
            }
            if (draftUpdateTextWatcher != null) {
                messageText.addTextChangedListener(draftUpdateTextWatcher);
            }

            editMessageActionMode = null;

            sendButton.setVisibility(View.VISIBLE);
            attachButton.setVisibility(View.VISIBLE);
            updateCameraButton();
        }

        /**
         * Get the currently stored text for the given message model. For a text message, this is just
         * the message's text. In case of a file message, the caption is returned. For other message
         * types, null is returned.
         */
        @Nullable
        private String getEditableText(@Nullable AbstractMessageModel messageModel) {
            if (messageModel == null) {
                return null;
            }
            if (messageModel.getType() == MessageType.TEXT) {
                return messageModel.getBody();
            } else if (messageModel.getType() == MessageType.FILE) {
                return messageModel.getCaption();
            }
            return null;
        }
    }

    private void showMessageDetailScreen(AbstractMessageModel messageModel) {
        // F1Whisper: if this disappearing message just expired, drop it instead of opening details on
        // an about-to-vanish message.
        if (DisappearingMessageService.enforceIfExpired(messageModel)) {
            Toast.makeText(getContext(), R.string.quoted_message_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        activity.startActivity(MessageDetailsActivity.createIntent(requireContext(), messageModel));
    }

    /**
     * Start the {@code ImagePaintActivity} to edit the (first) currently selected message.
     */
    private void sendImageReply() {
        final AbstractMessageModel messageModel = selectedMessages.get(0);
        if (messageModel == null || messageModel.getMessageContentsType() != MessageContentsType.IMAGE) {
            logger.error("Invalid message model: {}", messageModel);
            return;
        }
        fileService.loadDecryptedMessageFile(messageModel, new FileService.OnDecryptedFileComplete() {
            @Override
            public void complete(File decryptedFile) {
                if (messageModel.isAvailable()) {
                    Uri uri = null;
                    if (decryptedFile != null) {
                        uri = Uri.fromFile(decryptedFile);
                    }
                    if (uri == null) {
                        logger.error("Uri is null");
                        return;
                    }

                    Context context = getContext();
                    if (context == null) {
                        logger.error("Context is null");
                        return;
                    }

                    MediaItem mediaItem = MediaItem.getFromUri(uri, getContext(), false);

                    File outputFile;
                    try {
                        outputFile = fileService.createTempFile(".imageReply", ".png");
                    } catch (IOException e) {
                        logger.error("Couldn't create temporary file", e);
                        return;
                    }


                    Intent imageReplyIntent = ImagePaintActivity.getImageReplyIntent(context, mediaItem, outputFile, messageReceiver, groupModel);
                    IntentDataUtil.addMessageReceiverToIntent(imageReplyIntent, messageReceiver);

                    imageReplyLauncher.launch(imageReplyIntent);
                }
            }

            @Override
            public void error(String message) {
                logger.error("Could not load message file: {}", message);
                RuntimeUtil.runOnUiThread(() -> SingleToast.getInstance().showLongText(getString(R.string.an_error_occurred_during_send)));
            }
        });
    }

    public boolean onBackPressed() {
        logger.info("onBackPressed");
        if (isEmojiPickerShown()) {
            // dismiss emoji keyboard if it's showing instead of leaving activity
            emojiPicker.hide();
            addAllInsetsToInsetPaddingContainer();
            return true;
        } else {
            if (messageText != null && messageText.isMentionPopupShowing()) {
                dismissMentionPopup();
                return true;
            }
            dismissQuotePopup();
            if (editMessageActionMode != null) {
                editMessageActionMode.finish();
                return true;
            }
            if (searchActionMode != null) {
                searchActionMode.finish();
                return true;
            }
            if (actionMode != null) {
                actionMode.finish();
                return true;
            } else if (ConfigUtils.isTabletLayout()) {
                if (actionBar != null) {
                    actionBar.setDisplayUseLogoEnabled(true);
                    actionBar.setDisplayShowCustomEnabled(false);
                }
            }
            return false;
        }
    }

    private void preserveListInstanceValues() {
        listInstancePosition = AbsListView.INVALID_POSITION;

        if (!isHidden()) {
            if (convListView != null && composeMessageAdapter != null) {
                if (convListView.getLastVisiblePosition() != composeMessageAdapter.getCount() - 1) {
                    listInstancePosition = convListView.getFirstVisiblePosition();
                    View v = convListView.getChildAt(0);
                    listInstanceTop = (v == null) ? 0 : (v.getTop() - convListView.getPaddingTop());
                    if (messageReceiver != null) {
                        listInstanceReceiverId = messageReceiver.getUniqueIdString();
                    }
                }
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        logger.debug("onSaveInstanceState");

        // some phones destroy the retained fragment upon going in background so we have to persist some data
        outState.putParcelable(CAMERA_URI, cameraUri);
        outState.putLong(AppConstants.INTENT_DATA_GROUP_DATABASE_ID, this.groupDbId);
        outState.putLong(AppConstants.INTENT_DATA_DISTRIBUTION_LIST_ID, this.distributionListId);
        outState.putString(AppConstants.INTENT_DATA_CONTACT, this.identity);
        outState.putInt(BUNDLE_LIST_POSITION, this.listInstancePosition);
        outState.putString(BUNDLE_LIST_RECEIVER_ID, this.listInstanceReceiverId);
        outState.putInt(BUNDLE_LIST_TOP, this.listInstanceTop);
        outState.putInt(BUNDLE_LIST_LONG_CLICK_ITEM, this.longClickItem);

        super.onSaveInstanceState(outState);
    }

    private void setCurrentPageReferenceId(@Nullable Integer currentPageReferenceId) {
        this.currentPageReferenceId = currentPageReferenceId;
    }

    @Nullable
    private Integer getCurrentPageReferenceId() {
        return this.currentPageReferenceId;
    }

    private void configureSearchWidget(final MenuItem menuItem) {
        SearchView searchView = (SearchView) menuItem.getActionView();
        if (searchView != null) {
            searchView.setOnQueryTextListener(queryTextListener);
            searchView.setQueryHint(getString(R.string.hint_search_keyword));
            searchView.setIconified(false);
            searchView.setOnCloseListener(() -> {
                if (searchActionMode != null) {
                    searchActionMode.finish();
                }
                return false;
            });

            LinearLayout linearLayoutOfSearchView = (LinearLayout) searchView.getChildAt(0);
            if (linearLayoutOfSearchView != null) {
                linearLayoutOfSearchView.setGravity(Gravity.CENTER_VERTICAL);
                linearLayoutOfSearchView.setPadding(0, 0, 0, 0);

                searchCounter = (TextView) layoutInflater.inflate(R.layout.textview_search_action, null);
                linearLayoutOfSearchView.addView(searchCounter);

                FrameLayout searchPreviousLayout = (FrameLayout) layoutInflater.inflate(R.layout.button_search_action, null);
                searchPreviousButton = searchPreviousLayout.findViewById(R.id.search_button);
                searchPreviousButton.setScaleY(-1);
                searchPreviousButton.setOnClickListener(v -> composeMessageAdapter.previousMatchPosition());
                linearLayoutOfSearchView.addView(searchPreviousLayout);

                FrameLayout searchNextLayout = (FrameLayout) layoutInflater.inflate(R.layout.button_search_action, null);
                searchNextButton = searchNextLayout.findViewById(R.id.search_button);
                searchProgress = searchNextLayout.findViewById(R.id.next_progress);
                searchNextButton.setOnClickListener(v -> composeMessageAdapter.nextMatchPosition());
                linearLayoutOfSearchView.addView(searchNextLayout);
            }
        }
    }

    private class SearchActionMode implements ActionMode.Callback {

        @SuppressLint("StaticFieldLeak")
        @Override
        public boolean onCreateActionMode(ActionMode mode, final Menu menu) {
            composeMessageAdapter.clearFilter();

            activity.getMenuInflater().inflate(R.menu.action_compose_message_search, menu);

            final MenuItem item = menu.findItem(R.id.menu_action_search);
            final View actionView = item.getActionView();

            item.setActionView(R.layout.item_progress);
            item.expandActionView();

            if (bottomPanel != null) {
                bottomPanel.setVisibility(View.GONE);
            }

            hideEmojiPickerIfShown();
            dismissMentionPopup();
            dismissQuotePopup();

            // load all records
            new AsyncTask<Void, Void, Void>() {
                List<AbstractMessageModel> messageModels;

                @Override
                protected Void doInBackground(Void... params) {
                    messageModels = getAllRecords();

                    return null;
                }

                @Override
                protected void onPostExecute(Void result) {
                    if (messageModels != null && isAdded()) {
                        item.collapseActionView();
                        item.setActionView(actionView);
                        configureSearchWidget(menu.findItem(R.id.menu_action_search));

                        insertToList(messageModels, true, true, true);
                        convListView.setSelection(Integer.MAX_VALUE);
                    }
                }
            }.execute();


            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }


        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            searchCounter = null;
            searchActionMode = null;
            if (composeMessageAdapter != null) {
                composeMessageAdapter.clearFilter();
            }
            if (bottomPanel != null) {
                bottomPanel.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateToolBarTitleInUIThread() {
        RuntimeUtil.runOnUiThread(this::updateToolbarTitle);
    }

    @UiThread
    private void updateContactModelData(final ContactModel contactModel) {
        // Sanity check
        if (!contactModel.getIdentity().equals(identity)) {
            logger.warn("updateContactModelData was called for mismatching identity");
            return;
        }

        if (this.contactModel != contactModel) {
            // Update the contact model (and the receiver) to have the current setting for
            // sending messages (forward security). This needs to be done if the contact model
            // cache has been reset and therefore a new contact model object has been created.
            this.contactModel = contactModel;
            messageReceiver = this.contactService.createReceiver(this.contactModel);
        }

        // Update header containing contact information
        updateToolbarTitle();

        // Update toolbar/menu icon states
        updateMuteMenu();

        // Reset cache
        if (composeMessageAdapter != null) {
            composeMessageAdapter.resetCachedContactModelData(contactModel);
        }
    }

    final protected boolean requiredInstances() {
        if (!this.checkInstances()) {
            this.instantiate();
        }
        return this.checkInstances();
    }

    protected boolean checkInstances() {
        return preferenceService != null
            && userService != null
            && contactService != null
            && groupService != null
            && groupModelRepository != null
            && groupCallManager != null
            && groupFlowDispatcher != null
            && messageService != null
            && fileService != null
            && notificationService != null
            && distributionListService != null
            && messagePlayerService != null
            && blockedIdentitiesService != null
            && ballotService != null
            && conversationService != null
            && deviceService != null
            && wallpaperService != null
            && conversationCategoryService != null
            && ringtoneService != null
            && voipStateService != null
            && downloadService != null
            && licenseService != null
            && emojiReactionsRepository != null;
    }

    protected void instantiate() {
        ServiceManager serviceManager = ThreemaApplication.getServiceManager();
        if (serviceManager != null) {
            try {
                this.multiDeviceManager = serviceManager.getMultiDeviceManager();
                this.preferenceService = serviceManager.getPreferenceService();
                this.userService = serviceManager.getUserService();
                this.contactService = serviceManager.getContactService();
                this.groupService = serviceManager.getGroupService();
                this.groupModelRepository = serviceManager.getModelRepositories().getGroups();
                this.groupCallManager = serviceManager.getGroupCallManager();
                this.groupFlowDispatcher = serviceManager.getGroupFlowDispatcher();
                this.messageService = serviceManager.getMessageService();
                this.fileService = serviceManager.getFileService();
                this.notificationService = serviceManager.getNotificationService();
                this.distributionListService = serviceManager.getDistributionListService();
                this.messagePlayerService = serviceManager.getMessagePlayerService();
                this.blockedIdentitiesService = serviceManager.getBlockedIdentitiesService();
                this.ballotService = serviceManager.getBallotService();
                this.databaseService = serviceManager.getDatabaseService();
                this.conversationService = serviceManager.getConversationService();
                this.deviceService = serviceManager.getDeviceService();
                this.wallpaperService = serviceManager.getWallpaperService();
                this.wallpaperLauncher = wallpaperService.getWallpaperActivityResultLauncher(this, this::setBackgroundWallpaper, () -> this.messageReceiver);
                this.conversationCategoryService = serviceManager.getConversationCategoryService();
                this.ringtoneService = serviceManager.getRingtoneService();
                this.voipStateService = serviceManager.getVoipStateService();
                this.downloadService = serviceManager.getDownloadService();
                this.licenseService = serviceManager.getLicenseService();
                this.emojiReactionsRepository = serviceManager.getModelRepositories().getEmojiReaction();
                this.draftManager = KoinJavaComponent.get(DraftManager.class);
                this.soundEffectPlayer = KoinJavaComponent.get(SoundEffectPlayer.class);
            } catch (Exception e) {
                logger.error("Failed to instantiate dependencies", e);
                showToast(this, R.string.an_error_occurred);
            }
        }
    }

    // Dialog callbacks
    @Override
    public void onYes(@Nullable String tag, @Nullable Object data) {
        if (tag == null) {
            return;
        }
        switch (tag) {
            case CONFIRM_TAG_DELETE_DISTRIBUTION_LIST:
                logger.info("Deletion of distribution list confirmed");
                new EmptyOrDeleteConversationsAsyncTask(
                    EmptyOrDeleteConversationsAsyncTask.Mode.DELETE,
                    new MessageReceiver[]{messageReceiver},
                    conversationService,
                    distributionListService,
                    groupModelRepository,
                    groupFlowDispatcher,
                    userService.getIdentity(),
                    getParentFragmentManager(),
                    null,
                    this::finishActivity
                ).execute();
                break;
            case AppConstants.CONFIRM_TAG_CLOSE_BALLOT:
                logger.info("Closing ballot confirmed");
                BallotUtil.closeBallot((AppCompatActivity) requireActivity(), (BallotModel) data, ballotService, MessageId.random(), TriggerSource.LOCAL);
                break;
            case DIALOG_TAG_CONFIRM_CALL:
                VoipUtil.initiateCall((AppCompatActivity) requireActivity(), contactModel, false, null);
                break;
            case DIALOG_TAG_EMPTY_CHAT:
                logger.info("Emptying of chat confirmed");
                emptyChat();
                break;
            case DIALOG_TAG_CONFIRM_BLOCK:
                logger.info("Blocking confirmed");
                blockedIdentitiesService.toggleBlocked(contactModel.getIdentity(), getContext());
                updateBlockMenu();
                break;
            case DIALOG_TAG_CONFIRM_LINK:
                logger.info("Link confirmed");
                Uri uri = (Uri) data;
                LinkifyUtil.getInstance().openLink(uri, requireContext(), this);
                break;
            default:
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            switch (requestCode) {
                case PERMISSION_REQUEST_SAVE_MESSAGE:
                    logger.info("Permissions granted for saving media files");
                    fileService.saveMedia(activity, coordinatorLayout, new CopyOnWriteArrayList<>(selectedMessages), false);
                    break;
                case PERMISSION_REQUEST_ATTACH_VOICE_MESSAGE:
                    logger.info("Permissions granted for recording voice messages");
                    attachVoiceMessage();
                    break;
                case PERMISSION_REQUEST_ATTACH_CAMERA:
                    logger.info("Permissions granted for camera");
                    updateCameraButton();
                    attachCamera();
                    break;
            }
        } else {
            switch (requestCode) {
                case PERMISSION_REQUEST_SAVE_MESSAGE:
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        showPermissionRationale(R.string.permission_storage_required);
                    }
                    break;
                case PERMISSION_REQUEST_ATTACH_VOICE_MESSAGE:
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                        showPermissionRationale(R.string.permission_record_audio_required);
                    }
                    break;
                case PERMISSION_REQUEST_ATTACH_CAMERA:
                case PERMISSION_REQUEST_ATTACH_CAMERA_VIDEO:
                    preferenceService.setCameraPermissionRequestShown(true);
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        showPermissionRationale(R.string.permission_camera_photo_required);
                    }
                    updateCameraButton();
                    break;
            }
        }
    }

    /* properly dispose of popups */

    private void dismissMentionPopup() {
        if (messageText != null) {
            try {
                logger.info("Mention popup dismissed");
                messageText.dismissMentionPopup();
            } catch (Exception e) {
                logger.error("Error dismissing mention popup", e);
            }
        }
    }

    private void dismissTooltipPopup(TooltipPopup tooltipPopup, boolean immediate) {
        try {
            if (tooltipPopup != null) {
                tooltipPopup.dismiss(immediate);
            }
        } catch (final IllegalArgumentException e) {
            // whatever
        }
    }

    public void markAsRead() {
        if (messageReceiver != null) {
            try {
                @NonNull List<AbstractMessageModel> unreadMessages = messageReceiver.getUnreadMessages();
                new MarkAsReadRoutine(conversationService, messageService, notificationService)
                    .runAsync(unreadMessages, messageReceiver);
                notificationService.cancel(messageReceiver);
            } catch (SQLException e) {
                logger.error("Exception", e);
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        hideEmojiPickerIfShown();
        hideEmojiPopupIfShown();
        EditTextUtil.hideSoftKeyboard(this.messageText);
        reopenQuotePopup();
        dismissMentionPopup();
        dismissTooltipPopup(workTooltipPopup, true);
        workTooltipPopup = null;
        if (availabilityStatusTooltipPopup != null) {
            availabilityStatusTooltipPopup.dismissForever(true);
            availabilityStatusTooltipPopup = null;
        }

        if (ConfigUtils.isTabletLayout()) {
            // make sure layout changes after rotate are reflected in thumbnail size etc.
            updateMessageDraft();
            this.handleIntent(activity.getIntent());
        } else {
            if (isAdded()) {
                // refresh wallpaper to reflect orientation change
                this.wallpaperService.setupWallpaperBitmap(this.messageReceiver, this.wallpaperView, ConfigUtils.isLandscape(activity), ConfigUtils.isTheDarkSide(activity));
            }
        }

        setupMessageTextClickListener();
    }

    private void restoreMessageDraft(boolean force) {
        if (this.messageReceiver != null && this.messageText != null && (force || TestUtil.isBlankOrNull(this.messageText.getText()))) {
            MessageDraft messageDraft = draftManager.get(messageReceiver.getUniqueIdString());

            if (messageDraft != null) {
                this.messageText.setText("");
                this.messageText.append(messageDraft.getText());
                var quotedApiMessageId = messageDraft.getQuotedMessageId();
                if (quotedApiMessageId != null) {
                    AbstractMessageModel quotedMessageModel = messageService.getMessageModelByApiMessageIdAndReceiver(quotedApiMessageId.toString(), messageReceiver);
                    if (quotedMessageModel != null && QuoteUtil.isQuoteable(quotedMessageModel)) {
                        showQuotePopup(quotedMessageModel, false);
                    }
                }
                // If the draft is just "@", then dismiss the mention popup when restoring the draft
                if (messageDraft.getText().equals("@")) {
                    dismissMentionPopup();
                }
            } else {
                this.messageText.setText("");
            }
        }
    }

    private void updateMessageDraft() {
        if (messageReceiver != null && messageText.getText() != null) {
            draftManager.set(
                messageReceiver.getUniqueIdString(),
                this.messageText.getText().toString(),
                isQuotePopupShown() && quotePopup.getQuoteInfo().getMessageModel() != null
                    ? quotePopup.getQuoteInfo().getMessageModel().getMessageId()
                    : null
            );

            // At this point, we don't know whether the draft has changed, so we need to notify the listeners regardless.
            ListenerManager.conversationListeners.handle(ConversationListener::onModifiedAll);
        }
    }

    @Override
    public void onKeyboardHidden() {
        if (getActivity() != null && isAdded()) {
            dismissMentionPopup();
            dismissTooltipPopup(workTooltipPopup, false);
            workTooltipPopup = null;

            if (emojiPicker != null) {
                emojiPicker.onKeyboardHidden();
            }
        }
    }

    @Override
    public void onKeyboardShown() {
        if (emojiPicker != null) {
            if (isEmojiPickerShown()) {
                emojiPicker.onKeyboardShown();
            }
            if (isResumed() &&
                !emojiPicker.isShown() &&
                searchActionMode == null &&
                messageText != null &&
                !messageText.hasFocus()) {
                // In some cases when the activity is launched where the previous activity finished with
                // an open keyboard, the messageText does not have focus even if the keyboard is shown
                // Only request focus if the emoji picker is hidden and the search bar is not shown,
                // otherwise the keyboard is needed to search emojis or the chat.
                messageText.requestFocus();
            }
        }
    }

    @Override
    public void onReportSpamClicked(@NonNull final ContactModel spammerContactModel, boolean block) {
        logger.info("Report spam clicked");
        contactService.reportSpam(
            spammerContactModel.getIdentity(),
            unused -> {
                if (isAdded()) {
                    LongToast.makeText(getContext(), R.string.spam_successfully_reported, Toast.LENGTH_LONG).show();
                }

                final String spammerIdentity = spammerContactModel.getIdentity();
                if (block) {
                    blockedIdentitiesService.blockIdentity(spammerIdentity, null);
                    ThreemaApplication.requireServiceManager()
                        .getExcludedSyncIdentitiesService()
                        .excludeFromSync(spammerIdentity, TriggerSource.LOCAL);

                    if (messageReceiver != null) {
                        new EmptyOrDeleteConversationsAsyncTask(
                            EmptyOrDeleteConversationsAsyncTask.Mode.DELETE,
                            new MessageReceiver[]{messageReceiver},
                            conversationService,
                            distributionListService,
                            groupModelRepository,
                            groupFlowDispatcher,
                            userService.getIdentity(),
                            null,
                            null,
                            () -> {
                                ListenerManager.conversationListeners.handle(ConversationListener::onModifiedAll);
                                ListenerManager.contactListeners.handle(listener -> listener.onModified(spammerIdentity));
                                if (isAdded()) {
                                    finishActivity();
                                }
                            }).execute();
                    }
                } else {
                    reportSpamView.hide();
                    ListenerManager.contactListeners.handle(listener -> listener.onModified(spammerIdentity));
                }
            },
            message -> {
                if (isAdded()) {
                    LongToast.makeText(getContext(), requireContext().getString(R.string.spam_error_reporting, message), Toast.LENGTH_LONG).show();
                }
            }
        );
    }

    private void finishActivity() {
        if (activity != null) {
            activity.finish();
        }
    }

    /*--------------------------------------------------------------------------------------------*/

    private ListenableFuture<MediaController> mediaControllerFuture;

    private void initializeMedia3Controller() {
        SessionToken sessionToken = new SessionToken(getAppContext(), new ComponentName(getAppContext(), VoiceMessagePlayerService.class));

        mediaControllerFuture = new MediaController.Builder(getAppContext(), sessionToken).buildAsync();
    }

    @Nullable
    private MediaController getMedia3Controller() {
        if (mediaControllerFuture.isDone()) {
            try {
                return mediaControllerFuture.get();
            } catch (ExecutionException e) {
                logger.error("Media Controller exception", e);
            } catch (InterruptedException e) {
                logger.error("Media Controller interrupted exception", e);
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    private void releaseMedia3Controller() {
        MediaController mediaController = getMedia3Controller();
        if (mediaController != null) {
            mediaController.stop();
            mediaController.clearMediaItems();
            mediaController.release();
        }

        if (mediaControllerFuture != null) {
            MediaController.releaseFuture(mediaControllerFuture);
        }

        try {
            if (!getAppContext().stopService(new Intent(getAppContext(), VoiceMessagePlayerService.class))) {
                logger.debug("VoiceMessagePlayer already stopped.");
            }
        } catch (Exception e) {
            logger.error("Unable to stop VoiceMessagePlayer", e);
        }
    }
}

