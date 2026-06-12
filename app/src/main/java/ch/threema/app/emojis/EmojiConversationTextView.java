package ch.threema.app.emojis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.util.AttributeSet;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;

import java.util.Random;

import ch.threema.app.R;
import ch.threema.app.utils.ConfigUtils;

public class EmojiConversationTextView extends MaterialTextView {
    protected final EmojiMarkupUtil emojiMarkupUtil;
    private boolean isFade = false;
    private boolean ignoreMarkup = false;
    private int spoilerMessageId = 0;
    private boolean spoilerRevealed = false;

    // --- Animated spoiler obscuring dots (Telegram-style particle field) ---
    private static final int SPOILER_FRAME_DELAY_MS = 33; // ~30fps
    private static final float SPOILER_DOTS_PER_DP2 = 0.045f; // dot density per dp^2 of span area
    private final Paint spoilerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random spoilerRandom = new Random();
    private float spoilerDotRadiusPx;
    private final Runnable spoilerInvalidator = this::invalidate;

    public EmojiConversationTextView(Context context) {
        this(context, null);
    }

    public EmojiConversationTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EmojiConversationTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        emojiMarkupUtil = EmojiMarkupUtil.getInstance();
        spoilerDotPaint.setStyle(Paint.Style.FILL);
        spoilerDotRadiusPx = context.getResources().getDisplayMetrics().density * 0.9f;
    }

    @Override
    public void setText(@Nullable CharSequence text, BufferType type) {
        if (emojiMarkupUtil != null) {
            // Force SPANNABLE so the stored text stays mutable: revealing a spoiler re-attaches its
            // span (see revealSpoilers), which requires a mutable Spannable, not an immutable
            // SpannedString (which BufferType.NORMAL would produce for spanned text).
            super.setText(emojiMarkupUtil.addTextSpans(getContext(), text, this, this.ignoreMarkup, true, getSpoilerObscureColor(), this.spoilerRevealed), BufferType.SPANNABLE);
        } else {
            super.setText(text, type);
        }
    }

    @ColorInt
    private int getSpoilerObscureColor() {
        return ConfigUtils.getColorFromAttribute(getContext(), R.attr.colorOnSurfaceVariant);
    }

    /**
     * Provide the per-message spoiler reveal state before {@link #setText}. Must be called on every
     * bind (the view is recycled) so a revealed spoiler does not bleed into another message.
     */
    public void setSpoilerContext(int messageModelId) {
        this.spoilerMessageId = messageModelId;
        this.spoilerRevealed = SpoilerRevealState.getInstance().isRevealed(messageModelId);
    }

    /**
     * Reveal every spoiler in this message. Called from {@code LinkifyUtil}'s touch handler when a
     * tap lands on an unrevealed spoiler. That handler owns chat-bubble taps (exactly like links),
     * so consuming the tap there suppresses the row click that would otherwise open "Message details".
     */
    public void revealSpoilers() {
        if (spoilerRevealed || !(getText() instanceof Spannable)) {
            return;
        }
        SpoilerRevealState.getInstance().reveal(spoilerMessageId);
        this.spoilerRevealed = true;
        // Reveal every spoiler in this message in place, preserving the already-applied
        // mention/link spans (a full re-setText would drop them). Just flipping the span's internal
        // flag + invalidate() is NOT enough: the TextView caches the run appearance and never
        // re-queries updateDrawState. Re-attaching the span (remove + setSpan over the same range)
        // notifies the TextView's SpanWatcher, which re-renders that run with the now-visible glyphs.
        final Spannable buffer = (Spannable) getText();
        for (SpoilerSpan spoilerSpan : buffer.getSpans(0, buffer.length(), SpoilerSpan.class)) {
            final int start = buffer.getSpanStart(spoilerSpan);
            final int end = buffer.getSpanEnd(spoilerSpan);
            if (start < 0 || end <= start) {
                continue;
            }
            final int flags = buffer.getSpanFlags(spoilerSpan);
            spoilerSpan.setRevealed(true);
            buffer.removeSpan(spoilerSpan);
            buffer.setSpan(spoilerSpan, start, end, flags);
        }
        stopSpoilerAnimation();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isFade) {
            getPaint().clearShadowLayer();
            getPaint().setShader(
                new LinearGradient(0,
                    getHeight(),
                    0,
                    getHeight() - (getTextSize() * 3),
                    Color.TRANSPARENT,
                    ConfigUtils.getColorFromAttribute(getContext(), R.attr.colorOnBackground),
                    Shader.TileMode.CLAMP));
        }
        super.onDraw(canvas);

        drawSpoilerDots(canvas);
    }

    /**
     * Paints an animated field of small dots over every unrevealed {@link SpoilerSpan}'s glyph area
     * (the glyphs themselves are drawn transparent by the span). Drawing at the view level keeps
     * nested mention/link/emoji spans intact, and re-randomising the dots each frame gives the
     * Telegram-style shimmering particle look. Self-schedules the next frame while obscured and
     * stops when revealed or detached (see {@link #onDetachedFromWindow()}).
     */
    private void drawSpoilerDots(@NonNull Canvas canvas) {
        if (spoilerRevealed) {
            return;
        }
        final CharSequence text = getText();
        if (!(text instanceof Spanned)) {
            return;
        }
        final Layout layout = getLayout();
        if (layout == null) {
            return;
        }
        final Spanned spanned = (Spanned) text;
        final SpoilerSpan[] spans = spanned.getSpans(0, spanned.length(), SpoilerSpan.class);
        if (spans.length == 0) {
            return;
        }

        final int paddingLeft = getTotalPaddingLeft();
        final int paddingTop = getTotalPaddingTop();
        final float density = getResources().getDisplayMetrics().density;
        boolean drewAny = false;

        for (SpoilerSpan span : spans) {
            if (span.isRevealed()) {
                continue;
            }
            spoilerDotPaint.setColor(span.getObscureColor());

            final int spanStart = spanned.getSpanStart(span);
            final int spanEnd = spanned.getSpanEnd(span);
            if (spanStart < 0 || spanEnd <= spanStart) {
                continue;
            }

            final int startLine = layout.getLineForOffset(spanStart);
            final int endLine = layout.getLineForOffset(spanEnd);

            for (int line = startLine; line <= endLine; line++) {
                final int lineStart = Math.max(spanStart, layout.getLineStart(line));
                final int lineEnd = Math.min(spanEnd, layout.getLineEnd(line));
                if (lineEnd <= lineStart) {
                    continue;
                }

                final float left = paddingLeft + layout.getPrimaryHorizontal(lineStart);
                final float right = paddingLeft + layout.getPrimaryHorizontal(lineEnd);
                final float top = paddingTop + layout.getLineTop(line);
                final float bottom = paddingTop + layout.getLineBottom(line);

                final float width = Math.abs(right - left);
                final float height = bottom - top;
                if (width < 1f || height < 1f) {
                    continue;
                }

                final float areaDp2 = (width / density) * (height / density);
                int dots = (int) (areaDp2 * SPOILER_DOTS_PER_DP2);
                dots = Math.max(6, Math.min(dots, 240));

                final float runLeft = Math.min(left, right);
                for (int i = 0; i < dots; i++) {
                    final float dx = runLeft + spoilerRandom.nextFloat() * width;
                    final float dy = top + spoilerRandom.nextFloat() * height;
                    // Vary alpha per dot for a twinkling, layered look.
                    spoilerDotPaint.setAlpha(80 + spoilerRandom.nextInt(176));
                    canvas.drawCircle(dx, dy, spoilerDotRadiusPx, spoilerDotPaint);
                }
                drewAny = true;
            }
        }

        if (drewAny) {
            scheduleSpoilerFrame();
        }
    }

    private void scheduleSpoilerFrame() {
        removeCallbacks(spoilerInvalidator);
        postDelayed(spoilerInvalidator, SPOILER_FRAME_DELAY_MS);
    }

    private void stopSpoilerAnimation() {
        removeCallbacks(spoilerInvalidator);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpoilerAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable drawable) {
        if (drawable instanceof EmojiDrawable) {
            /* setHint() invalidates the view while invalidate() does not */
            setHint(getHint());
        } else {
            super.invalidateDrawable(drawable);
        }
    }

    public void setFade(boolean isFade) {
        if (this.isFade != isFade && isFade == false) {
            if (getPaint() != null) {
                getPaint().clearShadowLayer();
                getPaint().setShader(null);
            }
        }

        this.isFade = isFade;
    }

    public void setIgnoreMarkup(boolean ignoreMarkup) {
        this.ignoreMarkup = ignoreMarkup;
    }
}
