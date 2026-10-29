package ch.threema.app.emojis;

import android.graphics.Color;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

/**
 * Obscures spoiler text ({@code ||hidden||}) until the user taps it.
 * <p>
 * Reveal state is in-memory only and client-side (see {@link SpoilerRevealState}); the underlying
 * message body always keeps the literal {@code ||...||} markers, so this is a pure render-time,
 * best-effort affordance. While unrevealed, the glyphs are painted transparent so they are hidden;
 * the animated particle/dot overlay that visually covers the gap is drawn by
 * {@link EmojiConversationTextView} (which knows every span's bounds), not by the span itself. Once
 * revealed the text is drawn normally.
 */
public class SpoilerSpan extends CharacterStyle implements UpdateAppearance {

    private boolean revealed;
    @ColorInt
    private final int obscureColor;

    public SpoilerSpan(@ColorInt int obscureColor) {
        this(obscureColor, false);
    }

    public SpoilerSpan(@ColorInt int obscureColor, boolean revealed) {
        this.obscureColor = obscureColor;
        this.revealed = revealed;
    }

    @Override
    public void updateDrawState(@NonNull TextPaint tp) {
        if (!revealed) {
            // Hide the glyphs by painting them fully transparent; the view paints the animated dot
            // field over the now-empty span area (see EmojiConversationTextView#onDraw).
            tp.bgColor = Color.TRANSPARENT;
            tp.setColor(Color.TRANSPARENT);
        }
    }

    /**
     * Colour used for the animated obscuring dots (theme-aware, supplied at construction time).
     */
    @ColorInt
    public int getObscureColor() {
        return obscureColor;
    }

    public boolean isRevealed() {
        return revealed;
    }

    public void setRevealed(boolean revealed) {
        this.revealed = revealed;
    }
}
