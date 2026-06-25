package ch.threema.app.adapters.decorators;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Toast;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.R;
import ch.threema.app.linkpreview.LinkPreviewValidator;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.messageplayer.FileMessagePlayer;
import ch.threema.app.services.messageplayer.MessagePlayer;
import ch.threema.app.ui.ControllerView;
import ch.threema.app.ui.DebouncedOnClickListener;
import ch.threema.app.ui.listitemholder.ComposeMessageHolder;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.IconUtil;
import ch.threema.app.utils.ImageViewUtil;
import ch.threema.app.utils.LinkifyUtil;
import ch.threema.app.utils.MediaSpoilerUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.domain.protocol.csp.messages.file.FileData;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.MessageState;
import ch.threema.storage.models.data.media.FileDataModel;

public class FileChatAdapterDecorator extends ChatAdapterDecorator {

    private static final String LISTENER_TAG = "FileChatDecorator";

    public interface DownloadAlertDialogListener {
        void showPrepareDownloadDialog(Runnable onConfirmed);
    }

    @NonNull
    private final DownloadAlertDialogListener downloadAlertDialogListener;

    @NonNull
    private final MessagePlayerFactory messagePlayerFactory;

    public FileChatAdapterDecorator(
        AbstractMessageModel messageModel,
        @NonNull ChatAdapterDecoratorListener chatAdapterDecoratorListener,
        @NonNull LinkifyUtil.LinkifyListener linkifyListener,
        @NonNull DownloadAlertDialogListener downloadAlertDialogListener,
        @NonNull MessagePlayerFactory messagePlayerFactory,
        Helper helper
    ) {
        super(messageModel, chatAdapterDecoratorListener, linkifyListener, helper);
        this.downloadAlertDialogListener = downloadAlertDialogListener;
        this.messagePlayerFactory = messagePlayerFactory;
    }

    @Override
    protected void configureChatMessage(final ComposeMessageHolder holder, Context context, final int position) {
        FileMessagePlayer fileMessagePlayer = (FileMessagePlayer) messagePlayerFactory.create(getMessageModel(), null);

        holder.messagePlayer = fileMessagePlayer;

        FileDataModel fileData = getMessageModel().getFileData();

        setThumbnail(holder, fileData, false);

        RuntimeUtil.runOnUiThread(() -> {
            setupResendStatus(holder);
            setControllerState(holder, fileData);
        });

        setControllerClickListener(fileMessagePlayer, fileData, holder);
        setOnClickListener(view -> {
            // F1Whisper: while an un-revealed image spoiler is shown, a tap reveals it instead of
            // opening / downloading the media.
            if (MediaSpoilerUtil.shouldObscure(getMessageModel())) {
                MediaSpoilerUtil.reveal(getMessageModel().getId());
                invalidate(holder, context, position);
                return;
            }
            if (
                getMessageModel().getState() != MessageState.FS_KEY_MISMATCH &&
                    getMessageModel().getState() != MessageState.SENDFAILED
            ) {
                prepareDownload(fileData, fileMessagePlayer);
            }
        }, holder.messageBlockView);

        // F1Whisper: the tap handler lives on the message block (reveal-if-spoiler, else open /
        // download). The attachment image must stay non-clickable so the tap reaches that handler;
        // setOnClickListener(null) forces clickable=true and would swallow the tap, so we also clear
        // clickable explicitly for recycled holders.
        if (holder.attachmentImage != null) {
            holder.attachmentImage.setOnClickListener(null);
            holder.attachmentImage.setClickable(false);
        }

        configureFileMessagePlayer(fileMessagePlayer, holder, fileData, context, position);
        configureBodyText(holder, fileData.getCaption());
        configureTertiaryText(holder, fileData);
        configureSecondaryText(holder, fileData);
        configureSizeText(holder, fileData);
        configureDateView(holder, fileData);
        configureLinkPreview(holder, fileData, context);
    }

    /**
     * F1Whisper: render the Signal-style link-preview text block (title / description / domain) under
     * the og:image of a previewed message, and route a tap on the bubble to the URL instead of the
     * media viewer. The card is only shown when the message carries a valid preview URL that also
     * appears in the caption (receiver-side re-validation: blocks a spoofed/injected card for a URL
     * the user never sent). For non-preview file messages the block stays hidden.
     */
    private void configureLinkPreview(
        @NonNull ComposeMessageHolder holder,
        @NonNull FileDataModel fileData,
        Context context
    ) {
        if (holder.linkPreviewInfo == null) {
            // Not a media item layout (the card views only exist there).
            return;
        }

        final String url = fileData.getLinkPreviewUrl();
        final String caption = fileData.getCaption();
        final boolean valid = fileData.isLinkPreview()
            && LinkPreviewValidator.isValidPreviewUrl(url)
            && captionReferencesUrl(caption, url);

        if (!valid) {
            holder.linkPreviewInfo.setVisibility(View.GONE);
            return;
        }

        holder.linkPreviewInfo.setVisibility(View.VISIBLE);

        if (holder.linkPreviewTitle != null) {
            final String title = fileData.getLinkPreviewTitle();
            if (title != null && !title.isBlank()) {
                holder.linkPreviewTitle.setText(title);
                holder.linkPreviewTitle.setVisibility(View.VISIBLE);
            } else {
                holder.linkPreviewTitle.setVisibility(View.GONE);
            }
        }

        if (holder.linkPreviewDescription != null) {
            final String description = fileData.getLinkPreviewDescription();
            if (description != null && !description.isBlank()) {
                holder.linkPreviewDescription.setText(description);
                holder.linkPreviewDescription.setVisibility(View.VISIBLE);
            } else {
                holder.linkPreviewDescription.setVisibility(View.GONE);
            }
        }

        if (holder.linkPreviewDomain != null) {
            final String host = android.net.Uri.parse(url).getHost();
            holder.linkPreviewDomain.setText(host != null ? host : url);
        }

        // Tap anywhere on the card opens the link (via the safe opener), not the image viewer.
        final LinkifyUtil linkifyUtil = LinkifyUtil.getInstance();
        setOnClickListener(view -> linkifyUtil.openLink(android.net.Uri.parse(url), context, null),
            holder.messageBlockView);
        if (holder.controller != null) {
            holder.controller.setHidden();
        }
    }

    /**
     * @return {@code true} if the visible caption text references the preview URL (the URL string or
     * at least its host appears in the caption). Mirrors Signal's "URL present in body" receiver
     * check so a sender cannot attach a preview for a URL the recipient never saw.
     */
    private static boolean captionReferencesUrl(@Nullable String caption, @NonNull String url) {
        if (caption == null || caption.isEmpty()) {
            return false;
        }
        final String haystack = caption.toLowerCase();
        if (haystack.contains(url.toLowerCase())) {
            return true;
        }
        final String host = android.net.Uri.parse(url).getHost();
        return host != null && !host.isEmpty() && haystack.contains(host.toLowerCase());
    }

    private void configureDateView(
        @NonNull ComposeMessageHolder holder,
        @NonNull FileDataModel fileData
    ) {
        if (holder.dateView != null) {
            setDatePrefix(
                FileUtil.getFileMessageDatePrefix(holder.dateView.getContext(),
                    getMessageModel(),
                    FileUtil.isImageFile(fileData) ? holder.dateView.getContext().getString(R.string.image_placeholder) : null)
            );
        }
    }

    private void configureSizeText(
        @NonNull ComposeMessageHolder holder,
        @NonNull FileDataModel fileData
    ) {
        showHide(holder.size, true);
        if (holder.size != null) {
            long size = fileData.getFileSize();
            if (size > 0) {
                holder.size.setText(Formatter.formatShortFileSize(holder.size.getContext(), fileData.getFileSize()));
            }
        }
    }

    private void configureSecondaryText(
        @NonNull ComposeMessageHolder holder,
        @NonNull FileDataModel fileData
    ) {
        showHide(holder.secondaryTextView, true);
        if (holder.secondaryTextView != null) {
            String mimeString = fileData.getMimeType();
            if (holder.secondaryTextView != null) {
                if (!TestUtil.isEmptyOrNull(mimeString)) {
                    holder.secondaryTextView.setText(MimeUtil.getMimeDescription(holder.secondaryTextView.getContext(), fileData.getMimeType()));
                } else {
                    holder.secondaryTextView.setText("");
                }
            }
        }
    }

    private void configureTertiaryText(
        @NonNull ComposeMessageHolder holder,
        @NonNull FileDataModel fileData
    ) {
        showHide(holder.tertiaryTextView, true);
        if (holder.tertiaryTextView != null) {
            String fileName = fileData.getFileName();
            if (!TestUtil.isEmptyOrNull(fileName)) {
                holder.tertiaryTextView.setText(highlightMatches(holder.tertiaryTextView.getContext(), fileName, filterString));
            } else {
                holder.tertiaryTextView.setText(R.string.no_filename);
            }
        }
    }

    private void configureFileMessagePlayer(
        @NonNull FileMessagePlayer fileMessagePlayer,
        @NonNull ComposeMessageHolder holder,
        @NonNull FileDataModel fileData,
        Context context,
        int position
    ) {
        Context applicationContext = context.getApplicationContext();
        fileMessagePlayer
            .addListener(LISTENER_TAG, new MessagePlayer.PlaybackListener() {
                @Override
                public void onPlay(AbstractMessageModel messageModel, boolean autoPlay) {
                    invalidate(holder, context, position);
                }

                @Override
                public void onPause(AbstractMessageModel messageModel) {
                }

                @Override
                public void onStatusUpdate(AbstractMessageModel messageModel, int position) {
                }

                @Override
                public void onStop(AbstractMessageModel messageModel) {
                }
            })
            .addListener(LISTENER_TAG, new MessagePlayer.DecryptionListener() {
                @Override
                public void onStart(AbstractMessageModel messageModel) {
                    RuntimeUtil.runOnUiThread(() -> holder.controller.setProgressing(false));
                }

                @Override
                public void onEnd(AbstractMessageModel messageModel, final boolean success, final String message, File decryptedFile) {
                    RuntimeUtil.runOnUiThread(() -> {
                        if (!success) {
                            holder.controller.setReadyToDownload();
                            if (!TestUtil.isEmptyOrNull(message)) {
                                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show();
                            }
                        } else {
                            holder.controller.setHidden();
                        }
                    });
                }
            })
            .addListener(LISTENER_TAG, new MessagePlayer.DownloadListener() {
                @Override
                public void onStart(AbstractMessageModel messageModel) {
                    RuntimeUtil.runOnUiThread(() -> holder.controller.setProgressingDeterminate(100));
                }

                @Override
                public void onStatusUpdate(AbstractMessageModel messageModel, final int progress) {
                    RuntimeUtil.runOnUiThread(() -> holder.controller.setProgress(progress));
                }

                @Override
                public void onUnknownProgress(AbstractMessageModel messageModel) {
                    RuntimeUtil.runOnUiThread(() -> holder.controller.setProgressing());
                }

                @Override
                public void onEnd(AbstractMessageModel messageModel, final boolean success, final String message) {
                    RuntimeUtil.runOnUiThread(() -> {
                        if (success) {
                            if (FileUtil.isImageFile(fileData) && (fileData.getRenderingType() == FileData.RENDERING_STICKER || fileData.getRenderingType() == FileData.RENDERING_MEDIA)) {
                                holder.controller.setHidden();
                            } else {
                                holder.controller.setNeutral();
                                setThumbnail(holder, fileData, false);
                            }
                        } else {
                            holder.controller.setReadyToDownload();
                            if (!TestUtil.isEmptyOrNull(message)) {
                                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            });
    }

    private void setControllerClickListener(
        @NonNull FileMessagePlayer fileMessagePlayer,
        @NonNull FileDataModel fileData,
        @NonNull ComposeMessageHolder holder
    ) {
        if (holder.controller != null) {
            holder.controller.setOnClickListener(new DebouncedOnClickListener(500) {
                @Override
                public void onDebouncedClick(View v) {
                    int status = holder.controller.getStatus();

                    switch (status) {
                        case ControllerView.STATUS_READY_TO_RETRY:
                            propagateControllerRetryClickToParent();
                            break;
                        case ControllerView.STATUS_READY_TO_PLAY:
                        case ControllerView.STATUS_READY_TO_DOWNLOAD:
                        case ControllerView.STATUS_NONE:
                            prepareDownload(fileData, fileMessagePlayer);
                            break;
                        case ControllerView.STATUS_PROGRESSING:
                            if (MessageUtil.isFileMessageBeingSent(getMessageModel())) {
                                getMessageService().cancelMessageUpload(getMessageModel());
                            } else {
                                fileMessagePlayer.cancel();
                            }
                            break;
                        default:
                            // no action taken for other statuses
                            break;
                    }
                }
            });
        }
    }

    private void prepareDownload(final FileDataModel fileData, final FileMessagePlayer fileMessagePlayer) {
        if (fileData != null && fileMessagePlayer != null) {
            if (fileData.isDownloaded()) {
                fileMessagePlayer.open();
            } else {
                final PreferenceService preferenceService = getPreferenceService();

                if (preferenceService != null && !preferenceService.getFileSendInfoShown()) {
                    downloadAlertDialogListener.showPrepareDownloadDialog(() -> {
                        preferenceService.setFileSendInfoShown(true);
                        fileMessagePlayer.open();
                    });
                } else {
                    fileMessagePlayer.open();
                }
            }
        }
    }

    private void setThumbnail(
        ComposeMessageHolder holder,
        FileDataModel fileData,
        final boolean updateBitmap
    ) {
        Bitmap thumbnail = null;
        try {
            thumbnail = getFileService().getMessageThumbnailBitmap(getMessageModel(),
                updateBitmap ? null : getThumbnailCache());
        } catch (Exception e) {
            //
        }

        if (FileUtil.isImageFile(fileData) && (fileData.getRenderingType() == FileData.RENDERING_STICKER || fileData.getRenderingType() == FileData.RENDERING_MEDIA)) {
            // F1Whisper: blur the thumbnail and show a tap-to-reveal overlay for un-revealed image
            // spoilers. Only image file messages are obscured here (non-image files never are).
            final boolean obscure = MediaSpoilerUtil.shouldObscure(getMessageModel());
            if (obscure && thumbnail != null) {
                thumbnail = MediaSpoilerUtil.obscure(thumbnail, holder.attachmentImage.getContext());
            }

            ImageViewUtil.showRoundedBitmapOrImagePlaceholder(
                holder.contentView,
                holder.attachmentImage,
                thumbnail,
                helper.getThumbnailWidth()
            );
            holder.bodyTextView.setWidth(helper.getThumbnailWidth());
            if (holder.attachmentImage != null) {
                boolean hasDrawable = holder.attachmentImage.getDrawable() != null;
                holder.attachmentImage.setVisibility(hasDrawable ? View.VISIBLE : View.GONE);
                holder.attachmentImage.setContentDescription(holder.attachmentImage.getContext().getString(
                    obscure ? R.string.media_spoiler_badge_content_description : R.string.image_placeholder));
            }

            if (obscure) {
                showSpoilerOverlay(holder, true);
                if (holder.controller != null) {
                    holder.controller.setHidden();
                }
            } else {
                showSpoilerOverlay(holder, false);
            }

            if (fileData.getRenderingType() == FileData.RENDERING_STICKER) {
                setStickerBackground(holder);
            } else {
                setDefaultBackground(holder);
            }
        } else {
            showSpoilerOverlay(holder, false);
            if (thumbnail != null) {
                if (holder.controller != null) {
                    holder.controller.setBackgroundImage(thumbnail);
                }
            } else {
                @Nullable final MessageState state = getMessageModel().getState();
                if (holder.controller != null && !usesUploadProgress(state)) {
                    holder.controller.setIconResource(IconUtil.getMimeIcon(fileData.getMimeType()));
                }
            }

            if (holder.attachmentImage != null) {
                holder.attachmentImage.setVisibility(View.GONE);
            }

            setDefaultBackground(holder);
        }
    }

    private void setControllerState(
        @NonNull ComposeMessageHolder holder,
        @NonNull FileDataModel fileData
    ) {
        @Nullable final MessageState state = getMessageModel().getState();
        if (fileData.isDownloaded()) {
            if (!usesUploadProgress(state)) {
                if (FileUtil.isImageFile(fileData) && (fileData.getRenderingType() == FileData.RENDERING_MEDIA || fileData.getRenderingType() == FileData.RENDERING_STICKER)) {
                    holder.controller.setHidden();
                } else {
                    holder.controller.setNeutral();
                }
            }
        } else {
            holder.controller.setReadyToDownload();
        }

        if (holder.messagePlayer != null) {
            switch (holder.messagePlayer.getState()) {
                case MessagePlayer.State_DOWNLOADING:
                    holder.controller.setProgressingDeterminate(100);
                    holder.controller.setProgress(holder.messagePlayer.getDownloadProgress());
                    break;
                case MessagePlayer.State_DECRYPTING:
                    holder.controller.setProgressing();
                    break;
            }
        }

        if (state == null) {
            return;
        }

        switch (state) {
            case TRANSCODING:
                holder.controller.setTranscoding();
                if (holder.transcoderView != null) {
                    holder.transcoderView.setProgress(holder.messagePlayer.getTranscodeProgress());
                }
                break;
            case PENDING:
                setThumbnail(holder, fileData, true);
                // fallthrough
            case SENDING:
            case UPLOADING:
                holder.controller.setProgressing();
                break;
            case SENDFAILED:
            case FS_KEY_MISMATCH:
                holder.controller.setRetry();
                break;
        }
    }

    private static boolean usesUploadProgress(MessageState state) {
        return state == MessageState.PENDING || state == MessageState.SENDING || state == MessageState.UPLOADING;
    }
}
