package ch.threema.app.voip

/**
 * What releasing the incoming-call slider thumb means.
 *
 * [RETURN_TO_REST] is the safe verdict: it neither answers nor rejects, so it is also the correct fallback for any
 * position the geometry cannot interpret.
 */
enum class SliderReleaseVerdict {
    ANSWER,
    REJECT,
    RETURN_TO_REST,
}

/**
 * F1Whisper: the incoming-call slider's geometry, decided from the buttons' **measured positions** instead of from an
 * assumed left-to-right layout.
 *
 * ## The defect this exists to remove
 *
 * `call_answer_indicator.xml` anchors the two targets with `layout_alignParentStart` (decline) and `layout_alignParentEnd`
 * (answer). Those are *writing-direction* anchors, so `RelativeLayout` mirrors them in every RTL locale — Arabic, Persian,
 * Urdu, Uyghur, Hebrew — and in RTL the decline button sits at the **larger** x and the answer button at the **smaller** one.
 *
 * The touch handler in `CallActivity`, however, used to hard-code the LTR arrangement: it clamped a drag with
 * `newX < declineX` on the low side and `newX > answerX` on the high side, and on release read `newX > answerX` as "answer".
 * Under RTL both comparisons invert, and the consequences are not subtle:
 *
 * - **On drag**, the low-side clamp `newX < declineX` is true almost everywhere (`declineX` is now the *right* edge), so the
 *   thumb was snapped straight onto the red decline button the moment the finger moved.
 * - **On release**, `newX > answerX` is true almost everywhere (`answerX` is now the *left* edge), so releasing anywhere at
 *   all — including a plain tap that never left the centre — answered the call.
 *
 * Together those two produce exactly the reported symptom: in an RTL locale the thumb flies to the **red** target and the
 * call is answered from there, so the button a user must reach to accept a call is the one coloured for rejecting it. Worse
 * than confusing, it means an RTL user cannot reject a call from the full-screen screen at all, and can answer one by
 * accident with a stray tap on a ringing lock screen.
 *
 * ## The fix
 *
 * Never assume which side is which. Read both anchors, derive the direction of travel from them, and express both the clamp
 * and the verdict relative to that direction. In LTR the result is byte-for-byte the previous behaviour; in RTL it is its
 * mirror image, which is what the layout was already drawing.
 *
 * No Android imports, so the geometry is directly JVM-testable (see `IncomingCallSliderGeometryTest`) without an RTL device
 * — the same pattern as [ch.threema.app.services.DisappearingFreezeDecision].
 *
 * All coordinates are x positions in the slider container's parent space, and all three (thumb, decline, answer) are the
 * same size, so an anchor's x is reached exactly when the thumb covers that target.
 */
object IncomingCallSliderGeometry {

    /**
     * Confine a dragged thumb to the track between the two targets, whichever way round they are laid out.
     *
     * @param x the thumb's would-be x position.
     * @param declineAnchorX x of the decline target.
     * @param answerAnchorX x of the answer target.
     */
    @JvmStatic
    fun clampToTrack(x: Float, declineAnchorX: Float, answerAnchorX: Float): Float =
        x.coerceIn(minOf(declineAnchorX, answerAnchorX), maxOf(declineAnchorX, answerAnchorX))

    /**
     * Decide what a release at [x] means.
     *
     * The comparison is expressed relative to the direction the answer target lies in, so it holds in both writing
     * directions. Reaching a target is strict — the thumb must pass the anchor — which preserves the previous LTR
     * behaviour exactly.
     *
     * When the two anchors coincide there is no track and therefore no direction: that only happens before the views have
     * been laid out (both x are 0), and answering or rejecting on the strength of an unlaid-out layout would be a coin
     * flip on a ringing phone. It returns [SliderReleaseVerdict.RETURN_TO_REST].
     */
    @JvmStatic
    fun releaseAt(x: Float, declineAnchorX: Float, answerAnchorX: Float): SliderReleaseVerdict {
        if (declineAnchorX == answerAnchorX) {
            return SliderReleaseVerdict.RETURN_TO_REST
        }
        val answerLiesTowardsHigherX = answerAnchorX > declineAnchorX
        val reachedAnswer = if (answerLiesTowardsHigherX) x > answerAnchorX else x < answerAnchorX
        if (reachedAnswer) {
            return SliderReleaseVerdict.ANSWER
        }
        val reachedDecline = if (answerLiesTowardsHigherX) x < declineAnchorX else x > declineAnchorX
        if (reachedDecline) {
            return SliderReleaseVerdict.REJECT
        }
        return SliderReleaseVerdict.RETURN_TO_REST
    }
}
