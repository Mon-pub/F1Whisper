package ch.threema.app.adapters.decorators;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.Spannable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.common.util.concurrent.ListenableFuture;

import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.media3.session.MediaController;
import ch.threema.app.R;
import ch.threema.app.cache.ThumbnailCache;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.DownloadService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.MessageService;
import ch.threema.app.services.UserService;
import ch.threema.app.services.ballot.BallotService;
import ch.threema.app.services.license.LicenseService;
import ch.threema.app.ui.DisappearingTimerBadgeView;
import ch.threema.app.ui.listitemholder.AbstractListItemHolder;
import ch.threema.app.ui.listitemholder.ComposeMessageHolder;
import ch.threema.app.utils.ImageViewUtil;
import ch.threema.app.utils.LinkifyUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.QuoteUtil;
import ch.threema.app.utils.StateBitmapUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.utils.TextExtensionsKt;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.data.DisplayTag;
import ch.threema.storage.models.group.GroupMessageModel;

import static ch.threema.app.utils.MessageUtilKt.getUiContentColor;
import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

abstract public class ChatAdapterDecorator extends AdapterDecorator implements LinkifyUtil.UnhandledClickHandler {
    private static final Logger logger = getThreemaLogger("ChatAdapterDecorator");

    public interface OnClickElement {
        void onClick(AbstractMessageModel messageModel);
    }

    public interface OnLongClickElement {
        void onLongClick(AbstractMessageModel messageModel);
    }

    public interface OnTouchElement {
        boolean onTouch(MotionEvent motionEvent, AbstractMessageModel messageModel);
    }

    private final AbstractMessageModel messageModel;
    protected final Helper helper;
    private final StateBitmapUtil stateBitmapUtil;

    protected OnClickElement onClickElement = null;
    // F1Whisper: a DEDICATED tap channel for the reply-quote header on a media bubble, kept separate
    // from onClickElement (the whole-bubble tap, which for media opens the attachment). Tapping the
    // quote strip jumps to the quoted message; tapping the media body opens it.
    private OnClickElement onClickQuoteElement = null;
    private OnLongClickElement onLongClickElement = null;
    private OnTouchElement onTouchElement = null;
    @NonNull
    protected final ChatAdapterDecoratorListener chatAdapterDecoratorListener;
    @NonNull
    protected final LinkifyUtil.LinkifyListener linkifyListener;
    private long durationS = 0;
    private CharSequence datePrefix = "";
    protected String dateContentDescriptionPrefix = "";

    private long groupId = 0L;
    protected Map<String, Integer> identityColors = null;
    @Nullable
    protected String filterString;

    // whether this message should be displayed as a continuation of a previous message by the same sender
    private boolean isGroupedMessage = true;

    public static class ContactCache {
        public String identity;
        public String displayName;
        public Bitmap avatar;
        public ContactModel contactModel;
    }

    public static class Helper {
        private final String myIdentity;
        private final MessageService messageService;
        private final UserService userService;
        private final ContactService contactService;
        private final FileService fileService;
        private final BallotService ballotService;
        private final ThumbnailCache thumbnailCache;
        private final PreferenceService preferenceService;
        private final DownloadService downloadService;
        private final LicenseService licenseService;
        private MessageReceiver messageReceiver;
        private int thumbnailWidth;
        protected int regularColor;
        private final Map<String, ContactCache> contacts = new HashMap<>();
        private final int maxBubbleTextLength;
        private final int maxQuoteTextLength;
        private final ListenableFuture<MediaController> mediaControllerFuture;

        public Helper(
            String myIdentity,
            MessageService messageService,
            UserService userService,
            ContactService contactService,
            FileService fileService,
            BallotService ballotService,
            ThumbnailCache thumbnailCache,
            PreferenceService preferenceService,
            DownloadService downloadService,
            LicenseService licenseService,
            MessageReceiver messageReceiver,
            int thumbnailWidth,
            int regularColor,
            int maxBubbleTextLength,
            int maxQuoteTextLength,
            ListenableFuture<MediaController> mediaControllerFuture) {
            this.myIdentity = myIdentity;
            this.messageService = messageService;
            this.userService = userService;
            this.contactService = contactService;
            this.fileService = fileService;
            this.ballotService = ballotService;
            this.thumbnailCache = thumbnailCache;
            this.preferenceService = preferenceService;
            this.downloadService = downloadService;
            this.licenseService = licenseService;
            this.messageReceiver = messageReceiver;
            this.thumbnailWidth = thumbnailWidth;
            this.regularColor = regularColor;
            this.maxBubbleTextLength = maxBubbleTextLength;
            this.maxQuoteTextLength = maxQuoteTextLength;
            this.mediaControllerFuture = mediaControllerFuture;
        }

        public int getThumbnailWidth() {
            return thumbnailWidth;
        }

        public ThumbnailCache getThumbnailCache() {
            return thumbnailCache;
        }

        public FileService getFileService() {
            return fileService;
        }

        public UserService getUserService() {
            return userService;
        }

        public ContactService getContactService() {
            return contactService;
        }

        public MessageService getMessageService() {
            return messageService;
        }

        public PreferenceService getPreferenceService() {
            return preferenceService;
        }

        public DownloadService getDownloadService() {
            return downloadService;
        }

        public LicenseService getLicenseService() {
            return licenseService;
        }

        public String getMyIdentity() {
            return myIdentity;
        }

        public BallotService getBallotService() {
            return ballotService;
        }

        public Map<String, ContactCache> getContactCache() {
            return contacts;
        }

        public MessageReceiver getMessageReceiver() {
            return messageReceiver;
        }

        public void setThumbnailWidth(int preferredThumbnailWidth) {
            thumbnailWidth = preferredThumbnailWidth;
        }

        public int getMaxBubbleTextLength() {
            return maxBubbleTextLength;
        }

        public int getMaxQuoteTextLength() {
            return maxQuoteTextLength;
        }

        public void setMessageReceiver(MessageReceiver messageReceiver) {
            this.messageReceiver = messageReceiver;
        }

        public ListenableFuture<MediaController> getMediaControllerFuture() {
            return this.mediaControllerFuture;
        }
    }

    public ChatAdapterDecorator(
        AbstractMessageModel messageModel,
        @NonNull ChatAdapterDecoratorListener chatAdapterDecoratorListener,
        @NonNull LinkifyUtil.LinkifyListener linkifyListener,
        Helper helper
    ) {
        this.messageModel = messageModel;
        this.chatAdapterDecoratorListener = chatAdapterDecoratorListener;
        this.linkifyListener = linkifyListener;
        this.helper = helper;
        stateBitmapUtil = StateBitmapUtil.getInstance();
    }

    public void setGroupMessage(long groupId, Map<String, Integer> identityColors) {
        this.groupId = groupId;
        this.identityColors = identityColors;
    }

    public void setOnClickElement(OnClickElement onClickElement) {
        this.onClickElement = onClickElement;
    }

    // F1Whisper: dedicated tap channel for the media reply-quote header (jump to quoted message).
    public void setOnClickQuoteElement(OnClickElement onClickQuoteElement) {
        this.onClickQuoteElement = onClickQuoteElement;
    }

    public void setOnLongClickElement(OnLongClickElement onClickElement) {
        onLongClickElement = onClickElement;
    }

    public void setOnTouchElement(OnTouchElement onTouchElement) {
        this.onTouchElement = onTouchElement;
    }

    @Override
    public void onUnhandledClick(@NonNull AbstractMessageModel messageModel) {
        if (onClickElement != null) {
            onClickElement.onClick(messageModel);
        }
    }

    final public void setFilter(@Nullable String filterString) {
        this.filterString = filterString;
    }

    /**
     * Is necessary because depending on the message model, we have to use a different color state list
     *
     * @param contentColor The color-state-list that will be applied to all {@code TextView} instances in {@code ComposeMessageHolder}
     */
    @MustBeInvokedByOverriders
    protected void applyContentColor(
        final @NonNull ComposeMessageHolder viewHolder,
        final @NonNull ColorStateList contentColor
    ) {
        if (viewHolder.bodyTextView != null) {
            viewHolder.bodyTextView.setTextColor(contentColor);
        }
        if (viewHolder.secondaryTextView != null) {
            viewHolder.secondaryTextView.setTextColor(contentColor);
        }
        if (viewHolder.tertiaryTextView != null) {
            viewHolder.tertiaryTextView.setTextColor(contentColor);
        }
        if (viewHolder.size != null) {
            viewHolder.size.setTextColor(contentColor);
        }
        if (viewHolder.senderName != null) {
            viewHolder.senderName.setTextColor(contentColor);
        }
        if (viewHolder.dateView != null) {
            viewHolder.dateView.setTextColor(contentColor);
        }
        if (viewHolder.editedText != null) {
            viewHolder.editedText.setTextColor(contentColor);
        }
    }

    @Override
    final protected void configure(final AbstractListItemHolder abstractViewHolder, Context context, int position) {
        if (!(abstractViewHolder instanceof ComposeMessageHolder) || abstractViewHolder.position != position) {
            return;
        }

        // F1Whisper: belt-and-suspenders disappearing-messages enforcement at decorate time. As in
        // ComposeMessageAdapter.getView, this runs during the bind/layout pass, so we only CHECK here
        // (pure predicate) and DEFER the hard-delete to after layout via a main-thread post — deleting
        // inline would mutate the adapter's backing list mid-bind and crash the neighbor reads.
        if (ch.threema.app.services.DisappearingMessageService.isExpired(getMessageModel())) {
            final ch.threema.storage.models.AbstractMessageModel doomed = getMessageModel();
            new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> ch.threema.app.services.DisappearingMessageService.enforceIfExpired(doomed));
        }

        boolean isUserMessage = !getMessageModel().isStatusMessage()
            && getMessageModel().getType() != MessageType.STATUS
            && getMessageModel().getType() != MessageType.GROUP_CALL_STATUS;

        String identity = messageModel.isOutbox()
            ? helper.getMyIdentity()
            : messageModel.getIdentity();

        final @NonNull ComposeMessageHolder viewHolder = (ComposeMessageHolder) abstractViewHolder;

        applyContentColor(viewHolder, getUiContentColor(getMessageModel(), context));

        //configure the chat message
        configureChatMessage(viewHolder, context, position);

        // F1Whisper: show the "Forwarded" header for media/file/voice messages carrying the "fwd"
        // metadata flag (set on forward; rides the E2E file metadata so the recipient sees it too).
        // Plain text/location have no metadata carrier and are never marked. Toggled here in the base
        // so every file-type bubble gets it without per-decorator duplication.
        if (viewHolder.forwardedLabelView != null) {
            final boolean isForwarded = getMessageModel().getType() == MessageType.FILE
                && getMessageModel().getFileData() != null
                && getMessageModel().getFileData().isForwarded();
            viewHolder.forwardedLabelView.setVisibility(isForwarded ? View.VISIBLE : View.GONE);
        }

        // F1Whisper: generalized reply-quote header for non-text bubbles (photo/video/file/voice).
        // TEXT keeps its own dedicated quote layout (+ configureQuote); this covers everything else.
        configureQuoteHeader(viewHolder, context);

        if (isUserMessage) {
            if (!messageModel.isOutbox() && groupId > 0) {

                ContactCache contactCache = helper.getContactCache().get(identity);
                if (contactCache == null) {
                    ContactModel contactModel = helper.getContactService().getByIdentity(messageModel.getIdentity());
                    contactCache = new ContactCache();
                    contactCache.displayName = NameUtil.getContactDisplayNameOrNickname(
                        contactModel,
                        true,
                        helper.preferenceService.getContactNameFormat()
                    );
                    contactCache.avatar = helper.getContactService().getAvatar(messageModel.getIdentity(), false);

                    contactCache.contactModel = contactModel;
                    helper.getContactCache().put(identity, contactCache);
                }

                if (viewHolder.senderView != null) {
                    if (isGroupedMessage) {
                        viewHolder.senderView.setVisibility(View.VISIBLE);
                        viewHolder.senderName.setText(contactCache.displayName);

                        if (identityColors != null && identityColors.containsKey(identity)) {
                            viewHolder.senderName.setTextColor(identityColors.get(identity));
                        } else {
                            viewHolder.senderName.setTextColor(helper.regularColor);
                        }
                    } else {
                        // hide sender name in grouped messages
                        viewHolder.senderView.setVisibility(View.GONE);
                    }
                }

                if (viewHolder.avatarView != null) {
                    if (isGroupedMessage) {
                        viewHolder.avatarView.setImageBitmap(contactCache.avatar);
                        viewHolder.avatarView.setVisibility(View.VISIBLE);
                        if (contactCache.contactModel != null) {
                            viewHolder.avatarView.setWorkBadgeVisible(helper.getContactService().showBadge(contactCache.contactModel));
                        }
                    } else {
                        // hide avatar in grouped messages
                        viewHolder.avatarView.setVisibility(View.INVISIBLE);
                    }
                }
            } else {
                if (viewHolder.avatarView != null) {
                    viewHolder.avatarView.setVisibility(View.GONE);
                }
                if (viewHolder.senderView != null) {
                    viewHolder.senderView.setVisibility(View.GONE);
                }
            }

            @Nullable CharSequence displayDate = MessageUtil.getDisplayDate(
                context,
                messageModel.getPostedAt(),
                messageModel.isOutbox(),
                messageModel.getModifiedAt(),
                true
            );
            if (displayDate == null) {
                displayDate = "";
            }

            CharSequence contentDescription;

            if (!TestUtil.isBlankOrNull(datePrefix)) {
                contentDescription = context.getString(R.string.state_dialog_modified) + ": " + displayDate;
                if (messageModel.isOutbox()) {
                    displayDate = TextUtils.concat(datePrefix, " | " + displayDate);
                } else {
                    displayDate = TextUtils.concat(displayDate + " | ", datePrefix);
                }
            } else {
                contentDescription = displayDate;
            }
            if (viewHolder.dateView != null) {
                viewHolder.dateView.setText(displayDate);
                viewHolder.dateView.setContentDescription(contentDescription);
            }

            if (viewHolder.datePrefixIcon != null) {
                viewHolder.datePrefixIcon.setVisibility(durationS > 0L ? View.VISIBLE : View.GONE);
            }

            if (viewHolder.starredIcon != null) {
                viewHolder.starredIcon.setVisibility((messageModel.getDisplayTags() & DisplayTag.DISPLAY_TAG_STARRED) == DisplayTag.DISPLAY_TAG_STARRED ? View.VISIBLE : View.GONE);
            }

            // F1Whisper: animated disappearing-messages countdown clock, mirroring the starred badge
            // style/placement. Three states, re-derived on every bind (this decorator is the ListView
            // recycle hook, so we ALWAYS stop the previous countdown first, then restart it only if
            // still running -- otherwise a recycled row would keep a stale countdown ticking):
            //   - not disappearing            -> GONE
            //   - timer frozen but not started -> static full-disc frame (incoming unread), no ticking
            //   - countdown running            -> setExpirationTime(...) + start the frame animation
            if (viewHolder.disappearingIcon != null) {
                final DisappearingTimerBadgeView disappearingIcon = viewHolder.disappearingIcon;
                // Always tear down any pending countdown from this row's previous binding first.
                disappearingIcon.stopAnimation();

                if (!messageModel.isDisappearing()) {
                    disappearingIcon.setVisibility(View.GONE);
                } else {
                    final Long expireStartedAt = messageModel.getExpireStartedAt();
                    final Integer timerSeconds = messageModel.getDisappearingTimerSeconds();
                    disappearingIcon.setVisibility(View.VISIBLE);
                    if (expireStartedAt != null && timerSeconds != null && timerSeconds > 0) {
                        // Running countdown: bind the window and animate down to zero.
                        disappearingIcon.setExpirationTime(expireStartedAt, timerSeconds * 1000L);
                        disappearingIcon.startAnimation();
                    } else {
                        // Frozen but not yet started (typically an incoming unread message): show the
                        // full clock face with no animation until the countdown begins on read. The
                        // preceding stopAnimation() has already cancelled any pending tick from this
                        // row's previous binding and cleared its startedAt/expiresIn, so nothing can
                        // repaint a prior message's countdown frame over this static full disc.
                        disappearingIcon.setPercentComplete(0f);
                    }
                }
            }

            if (viewHolder.deliveredIndicator != null) {
                stateBitmapUtil.setStateDrawable(context, messageModel, viewHolder.deliveredIndicator, null);
            }

            if (viewHolder.editedText != null) {
                viewHolder.editedText.setVisibility(messageModel.getEditedAt() != null ? View.VISIBLE : View.GONE);
            }

            if (viewHolder.controller != null) {
                viewHolder.controller.setIsUsedForOutboxMessage(getMessageModel().isOutbox());
            }
        }
    }

    /**
     * F1Whisper: bind the generalized reply-quote header for a non-text bubble that answers a quoted
     * message (photo/video/file/voice carrying a "qi" reference in its FileData). Reuses the existing
     * type-agnostic {@link QuoteUtil#getQuoteContent} resolver, so a media quote renders identically
     * to a text quote (sender + snippet + type-icon + thumbnail). Runs once per bind in the base so
     * every file-type decorator gets it without duplication; TEXT keeps its own dedicated quote layout
     * and is excluded here (no double render). The header is hidden (GONE) for every other case, so a
     * recycled holder never keeps a stale header.
     */
    private void configureQuoteHeader(@NonNull ComposeMessageHolder viewHolder, @NonNull Context context) {
        if (viewHolder.quoteHeaderContainer == null) {
            // Layout has no quote-header slot (non-user-message layout) - nothing to do.
            return;
        }

        final AbstractMessageModel model = getMessageModel();
        // Gate: only non-text, non-deleted file-type bubbles with a resolvable V2 quote reference.
        // - TEXT keeps its dedicated quote layout + configureQuote (byte-identical, no double render).
        // - isDeleted(): a deleted FILE keeps type==FILE and its quotedMessageId column, but its
        //   tombstone must NOT show a quote strip (getFileData() is also null once body is nulled).
        // - getFileData()!=null: media/file/voice only (LOCATION/BALLOT quote-answers are deferred).
        final boolean showQuoteHeader = model.getType() != MessageType.TEXT
            && !model.isDeleted()
            && model.getFileData() != null
            && QuoteUtil.getQuoteType(model) == QuoteUtil.QUOTE_TYPE_V2;
        if (!showQuoteHeader) {
            hideQuoteHeader(viewHolder);
            return;
        }

        final @Nullable QuoteUtil.QuoteContent content = QuoteUtil.getQuoteContent(
            model,
            helper.getMessageReceiver(),
            false,
            helper.getThumbnailCache(),
            context,
            helper.getMessageService(),
            helper.getUserService(),
            helper.getFileService(),
            helper.getPreferenceService().getContactNameFormat()
        );
        if (content == null) {
            hideQuoteHeader(viewHolder);
            return;
        }

        // Sender name - hidden for a deleted/not-found target (identity == null there).
        final @Nullable ContactModel quotedContact = content.identity != null
            ? helper.getContactService().getByIdentity(content.identity)
            : null;
        if (viewHolder.quoteHeaderSender != null) {
            if (quotedContact != null) {
                viewHolder.quoteHeaderSender.setText(NameUtil.getQuoteName(
                    quotedContact,
                    helper.getUserService(),
                    helper.getPreferenceService().getContactNameFormat()
                ));
                viewHolder.quoteHeaderSender.setVisibility(View.VISIBLE);
            } else {
                viewHolder.quoteHeaderSender.setVisibility(View.GONE);
            }
        }

        // Snippet - the resolved one-line preview, or the deleted/not-found placeholder.
        if (viewHolder.quoteHeaderSnippet != null) {
            viewHolder.quoteHeaderSnippet.setText(content.quotedText);
        }

        // Type icon - GONE when the quoted type has no icon.
        if (viewHolder.quoteHeaderTypeImage != null) {
            if (content.icon != null) {
                viewHolder.quoteHeaderTypeImage.setImageResource(content.icon);
                viewHolder.quoteHeaderTypeImage.setVisibility(View.VISIBLE);
            } else {
                viewHolder.quoteHeaderTypeImage.setVisibility(View.GONE);
            }
        }

        // Thumbnail - GONE for voice (suppressed in extractQuoteV2) and when absent.
        if (viewHolder.quoteHeaderThumbnail != null) {
            if (content.thumbnail != null) {
                viewHolder.quoteHeaderThumbnail.setImageBitmap(content.thumbnail);
                viewHolder.quoteHeaderThumbnail.setVisibility(View.VISIBLE);
            } else {
                viewHolder.quoteHeaderThumbnail.setVisibility(View.GONE);
            }
        }

        // Accent bar - identity color (group member / contact), else the default quote-bar color.
        if (viewHolder.quoteHeaderBar != null) {
            @NonNull ColorStateList barColor = context.getColorStateList(R.color.bubble_quote_bar_default_colorstatelist);
            if (content.identity != null && !helper.getMyIdentity().equals(content.identity)) {
                if (model instanceof GroupMessageModel) {
                    if (this.identityColors != null && this.identityColors.containsKey(content.identity)) {
                        final @Nullable @ColorInt Integer identityColor = this.identityColors.get(content.identity);
                        if (identityColor != null) {
                            barColor = ColorStateList.valueOf(identityColor);
                        }
                    }
                } else if (quotedContact != null) {
                    barColor = ColorStateList.valueOf(quotedContact.getIdColor().getThemedColor(context));
                }
            }
            viewHolder.quoteHeaderBar.setBackgroundTintList(barColor);
        }

        viewHolder.quoteHeaderContainer.setVisibility(View.VISIBLE);
        // Tap the quote header -> jump to the quoted message via the DEDICATED quote channel (NOT the
        // whole-bubble onClick, which for a media bubble opens the attachment). onClickQuoteElement
        // routes to ComposeMessageFragment.jumpToQuotedMessage(), which runs the existing searchV2Quote
        // paging jump (or the "message deleted" toast for a missing target).
        viewHolder.quoteHeaderContainer.setOnClickListener(v -> {
            if (onClickQuoteElement != null) {
                onClickQuoteElement.onClick(getMessageModel());
            }
        });
        // Long-press the quote header -> forward to the bubble's long-click (selection / context menu),
        // consuming it so the parent messageBlockView does not ALSO fire (avoids a double selection
        // toggle). Without this the clickable strip would swallow the long-press entirely.
        viewHolder.quoteHeaderContainer.setOnLongClickListener(v -> {
            if (onLongClickElement != null) {
                onLongClickElement.onLongClick(getMessageModel());
                return true;
            }
            return false;
        });
    }

    /**
     * F1Whisper: hide the reply-quote header and clear its tap/long-press listeners so a recycled row
     * never keeps a stale target (the strip is clickable only while an actual quote is shown).
     */
    private void hideQuoteHeader(@NonNull ComposeMessageHolder viewHolder) {
        if (viewHolder.quoteHeaderContainer == null) {
            return;
        }
        viewHolder.quoteHeaderContainer.setVisibility(View.GONE);
        viewHolder.quoteHeaderContainer.setOnClickListener(null);
        viewHolder.quoteHeaderContainer.setOnLongClickListener(null);
        viewHolder.quoteHeaderContainer.setClickable(false);
        viewHolder.quoteHeaderContainer.setLongClickable(false);
    }

    public Spannable highlightMatches(@NonNull Context context, @Nullable CharSequence fullText, @Nullable String filterText) {
        return TextExtensionsKt.highlightMatches(
            fullText,
            context,
            filterText,
            true,
            false
        );
    }

    private CharSequence formatTextString(@NonNull Context context, @Nullable String string, String filterString) {
        return formatTextString(context, string, filterString, -1);
    }

    protected CharSequence formatTextString(@NonNull Context context, @Nullable String string, String filterString, int maxLength) {
        if (TextUtils.isEmpty(string)) {
            return "";
        }

        if (maxLength > 0 && string.length() > maxLength) {
            return highlightMatches(context, string.substring(0, maxLength - 1), filterString);
        }
        return highlightMatches(context, string, filterString);
    }

    protected void configureChatMessage(final ComposeMessageHolder holder, Context context, int position) {
        if (holder.attachmentImage instanceof ShapeableImageView) {
            ShapeAppearanceModel shapeAppearanceModel = new ShapeAppearanceModel.Builder()
                .setAllCornerSizes(ImageViewUtil.getCornerRadius(context))
                .build();
            ((ShapeableImageView) holder.attachmentImage).setShapeAppearanceModel(shapeAppearanceModel);
        }
    }

    protected void setDatePrefix(String prefix) {
        datePrefix = prefix;
    }

    protected void setDuration(long durationS) {
        this.durationS = durationS;
    }

    protected MessageService getMessageService() {
        return helper.getMessageService();
    }

    protected FileService getFileService() {
        return helper.getFileService();
    }

    protected int getThumbnailWidth() {
        return helper.getThumbnailWidth();
    }

    /**
     * Tag used to look up / deduplicate the single spoiler overlay child added programmatically to a
     * media bubble's content block. Because list-item holders are recycled, the overlay must be
     * deduplicated and explicitly removed when the bubble is no longer a (un-revealed) spoiler.
     */
    private static final String SPOILER_OVERLAY_TAG = "spoiler_overlay";

    /**
     * Show or hide the F1Whisper "spoiler" tap-to-reveal overlay on a media bubble's content block.
     * Always call with {@code show = false} for non-spoiler / revealed bubbles so a recycled holder
     * does not keep a stale overlay.
     *
     * @param holder the media bubble holder (its {@code contentView} is the FrameLayout content block)
     * @param show   {@code true} to add the overlay, {@code false} to remove it
     */
    protected void showSpoilerOverlay(@NonNull ComposeMessageHolder holder, boolean show) {
        final ViewGroup parent = holder.contentView;
        if (parent == null) {
            return;
        }
        final View existing = parent.findViewWithTag(SPOILER_OVERLAY_TAG);
        if (!show) {
            if (existing != null) {
                parent.removeView(existing);
            }
            return;
        }
        if (existing == null) {
            final View overlay = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.spoiler_overlay, parent, false);
            overlay.setTag(SPOILER_OVERLAY_TAG);
            parent.addView(overlay);
        }
    }

    protected ThumbnailCache getThumbnailCache() {
        return helper.getThumbnailCache();
    }

    protected AbstractMessageModel getMessageModel() {
        return messageModel;
    }

    protected PreferenceService getPreferenceService() {
        return helper.getPreferenceService();
    }

    protected UserService getUserService() {
        return helper.getUserService();
    }

    protected ContactService getContactService() {
        return helper.getContactService();
    }

    protected void setOnClickListener(final View.OnClickListener onViewClickListener, View view) {
        if (view != null) {
            view.setOnClickListener(v -> {
                if (onViewClickListener != null && !chatAdapterDecoratorListener.isActionModeEnabled()) {
                    // do not propagate click if actionMode (selection mode) is enabled in parent
                    onViewClickListener.onClick(v);
                }
                if (onClickElement != null) {
                    //propagate event to parents
                    onClickElement.onClick(getMessageModel());
                }
            });

            // propagate long click listener
            view.setOnLongClickListener(v -> {
                if (onLongClickElement != null) {
                    onLongClickElement.onLongClick(getMessageModel());
                }
                return false;
            });

            // propagate touch listener
            view.setOnTouchListener((arg0, event) -> {
                if (onTouchElement != null) {
                    return onTouchElement.onTouch(event, getMessageModel());
                }
                return false;
            });
        }
    }

    void setStickerBackground(ComposeMessageHolder holder) {
        holder.messageBlockView.setCardBackgroundColor(AppCompatResources.getColorStateList(holder.messageBlockView.getContext(), R.color.bubble_sticker_colorstatelist));
    }

    void setDefaultBackground(ComposeMessageHolder holder) {
        if (holder.messageBlockView.getCardBackgroundColor().getDefaultColor() == Color.TRANSPARENT) {
            int colorStateListRes;

            if (getMessageModel().isOutbox() && !(getMessageModel() instanceof DistributionListMessageModel)) {
                // outgoing
                colorStateListRes = R.color.bubble_send_colorstatelist;
            } else {
                // incoming
                colorStateListRes = R.color.bubble_receive_colorstatelist;
            }
            holder.messageBlockView.setCardBackgroundColor(AppCompatResources.getColorStateList(holder.messageBlockView.getContext(), colorStateListRes));

            logger.debug("*** setDefaultBackground");
        }
    }

    /**
     * Set whether this message should be displayed as the continuation of a previous message by the same sender
     *
     * @param grouped If this is a grouped message, following another message by the same sender
     */
    public void setGroupedMessage(boolean grouped) {
        isGroupedMessage = grouped;
    }

    /**
     * Setup "Tap to resend" UI
     *
     * @param holder ComposeMessageHolder
     */
    protected void setupResendStatus(ComposeMessageHolder holder) {
        if (holder.tapToResend != null) {
            if (getMessageModel() != null &&
                getMessageModel().isOutbox() &&
                (getMessageModel().getState() == MessageState.FS_KEY_MISMATCH ||
                    getMessageModel().getState() == MessageState.SENDFAILED)) {
                holder.tapToResend.setVisibility(View.VISIBLE);
                holder.dateView.setVisibility(View.GONE);
            } else {
                holder.tapToResend.setVisibility(View.GONE);
                holder.dateView.setVisibility(View.VISIBLE);
            }
        }
    }

    protected void configureBodyText(@NonNull ComposeMessageHolder holder, @Nullable String caption) {
        if (!TestUtil.isEmptyOrNull(caption)) {
            holder.bodyTextView.setText(formatTextString(holder.bodyTextView.getContext(), caption, filterString));
            // remove movement method. Otherwise clicks on the text are not handled correctly
            holder.bodyTextView.setMovementMethod(null);
            LinkifyUtil.getInstance().linkify(
                holder.bodyTextView,
                getMessageModel(),
                /* includePhoneNumbers = */ true,
                /* unhandledClickHandler = */ this,
                /* linkifyListener = */ linkifyListener
            );

            showHide(holder.bodyTextView, true);
        } else {
            showHide(holder.bodyTextView, false);
        }
    }

    protected void propagateControllerRetryClickToParent() {
        if (
            getMessageModel().getState() == MessageState.FS_KEY_MISMATCH ||
                getMessageModel().getState() == MessageState.SENDFAILED
        ) {
            propagateControllerClickToParent();
        }
    }

    protected void propagateControllerClickToParent() {
        if (onClickElement != null) {
            onClickElement.onClick(getMessageModel());
        }
    }

    @Override
    protected boolean isInChoiceMode() {
        return chatAdapterDecoratorListener.isInChoiceMode();
    }
}
