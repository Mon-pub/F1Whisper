package ch.threema.app.utils

/**
 * F1Whisper: the single place that decides whether restricted message content may cross an output
 * boundary, and the pure text substitution the accessibility boundary needs.
 *
 * "Restricted" means the sender asked the app to withhold the content until the recipient asks for
 * it: an image or video marked as a spoiler, or a listen-once voice message. The chat bubble honours
 * that, because that is where the tap-to-reveal lives. Every *other* surface that can emit the same
 * content - a notification, the accessibility tree, the pinned-message banner - historically emitted
 * it in the clear, because each of them reads the model directly and none of them knew the flags
 * existed.
 *
 * Two properties this object exists to guarantee:
 *
 * 1. **One policy.** A new output surface asks here rather than re-deriving the rule, so a future
 *    surface cannot quietly disagree with the bubble about what is hidden.
 * 2. **Fail closed.** Every predicate is phrased as permission to *release*, so the answer to an
 *    unexpected input is "no". A boundary that cannot tell whether content is restricted must treat
 *    it as restricted; the cost is a missing thumbnail, and the cost of the other default is the
 *    content itself.
 *
 * Pure by construction - no Android imports, every input a parameter - so the policy is unit-tested
 * on the JVM instead of on a device.
 */
object OutputRestrictionPolicy {

    /**
     * May a media thumbnail for this message be attached to a notification?
     *
     * Reveal state is deliberately **not** an input. It lives in memory for the duration of a chat
     * visit ([MediaSpoilerUtil]), whereas a notification is built off the UI thread, is read on a
     * lock screen, and offers no way to reveal anything - so there is no state in which showing a
     * spoiler's thumbnail there is what the sender asked for. Feeding reveal state in would add a
     * race whose best outcome is leaking the image.
     *
     * @param isFileMessage whether the message is a FILE message. The restriction flags live in the
     *   file-data metadata, so a non-file message carries none and is released.
     * @param isSpoiler the spoiler metadata flag.
     * @param isListenOnce the listen-once metadata flag. Listen-once media is audio today and so
     *   carries no thumbnail, but the flag is free to honour here and this boundary should not have
     *   to be revisited if a future rendering type gains one.
     */
    @JvmStatic
    fun mayReleaseThumbnailToNotification(
        isFileMessage: Boolean,
        isSpoiler: Boolean,
        isListenOnce: Boolean,
    ): Boolean {
        if (!isFileMessage) {
            return true
        }
        return !isSpoiler && !isListenOnce
    }

    /**
     * May this message's decrypted media leave the app through a GENERIC output boundary - the media gallery, the generic
     * media viewer, save-to-device, share, or a chat export?
     *
     * F1Whisper (fourth fork review, F4-09). Listen-once enforcement lived entirely in the chat audio player, which claims
     * the message before releasing plaintext and burns it when playback ends. Every other route to the same file had no
     * idea the flag existed: the media gallery published the row like any other voice message, from where it could be
     * opened in the generic viewer (which prepares Media3 directly and seeks back to zero at the end, so it replays),
     * saved to the device, shared to another app, or written into a chat export. An unmodified client could therefore
     * replay incoming listen-once audio, or produce a permanent clear copy of it, without ever consuming it.
     *
     * The rule: an INCOMING listen-once file message is released to exactly one consumer, the claim/burn owner in the chat
     * player. Every generic boundary refuses it.
     *
     * The SENDER is never restricted. They chose the restriction, the flag describes what the recipient may do, and their
     * own copy is deleted by the burn that follows a successful send anyway.
     *
     * Spoilers are deliberately NOT an input. A spoiler is camouflage rather than non-downloadable content; it is ordinary
     * media that opens revealed, and restricting these boundaries for it is explicitly out of scope.
     *
     * @param isOutbox whether the message is this device's own.
     * @param isFileMessage whether the message is a FILE message. The listen-once flag lives in file-data metadata, so a
     *   non-file message carries none and is released.
     * @param isListenOnce the `lo` metadata flag.
     */
    @JvmStatic
    fun mayReleaseMediaToGenericOutput(
        isOutbox: Boolean,
        isFileMessage: Boolean,
        isListenOnce: Boolean,
    ): Boolean {
        if (isOutbox || !isFileMessage) {
            return true
        }
        return !isListenOnce
    }

    /**
     * Replace every given span range in [text] with [replacement], returning a plain [String] with
     * no spans attached.
     *
     * This is what an accessibility service should be handed for a message containing unrevealed
     * spoilers. The rendered `TextView` hides spoiler glyphs by painting them transparent, which
     * hides them from the eye and from nothing else: the characters are still in the view's text
     * buffer, so `AccessibilityNodeInfo.getText()` returns them and TalkBack reads them out. The
     * substitution has to happen on the *text*, not on the paint.
     *
     * Ranges arrive from a live `Spannable` via `getSpanStart`/`getSpanEnd`, which is a hostile
     * enough source to justify normalising rather than trusting: a removed span reports -1, a span
     * can be reported reversed, and ranges can overlap. Each range is clamped into the text, dropped
     * if it is empty after clamping, then sorted and merged, so overlapping spoilers produce one
     * replacement rather than a doubled one and a malformed range can never throw at a boundary
     * whose failure mode is announcing the secret.
     *
     * @param spanStarts inclusive start offsets; must be the same length as [spanEnds].
     * @param spanEnds exclusive end offsets.
     * @param replacement what each surviving range becomes, e.g. a localized "hidden".
     */
    @JvmStatic
    fun obscureSpans(
        text: CharSequence,
        spanStarts: IntArray,
        spanEnds: IntArray,
        replacement: CharSequence,
    ): String {
        if (spanStarts.isEmpty() || spanStarts.size != spanEnds.size) {
            return text.toString()
        }

        val ranges = ArrayList<IntArray>(spanStarts.size)
        for (i in spanStarts.indices) {
            val rawStart = minOf(spanStarts[i], spanEnds[i])
            val rawEnd = maxOf(spanStarts[i], spanEnds[i])
            val start = rawStart.coerceIn(0, text.length)
            val end = rawEnd.coerceIn(0, text.length)
            if (end > start) {
                ranges.add(intArrayOf(start, end))
            }
        }
        if (ranges.isEmpty()) {
            return text.toString()
        }
        ranges.sortWith(compareBy({ it[0] }, { it[1] }))

        val out = StringBuilder(text.length)
        var cursor = 0
        var i = 0
        while (i < ranges.size) {
            val start = ranges[i][0]
            var end = ranges[i][1]
            // Absorb every range that overlaps or abuts this one, so two spoilers that touch are
            // announced once rather than twice.
            while (i + 1 < ranges.size && ranges[i + 1][0] <= end) {
                end = maxOf(end, ranges[i + 1][1])
                i++
            }
            if (start > cursor) {
                out.append(text, cursor, start)
            }
            out.append(replacement)
            cursor = maxOf(cursor, end)
            i++
        }
        if (cursor < text.length) {
            out.append(text, cursor, text.length)
        }
        return out.toString()
    }
}
