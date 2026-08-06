package ch.threema.app.voip

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F1Whisper: regression tests for [IncomingCallSliderGeometry], the incoming-call slider's direction handling.
 *
 * The shipped handler hard-coded the LTR arrangement (decline at the low x, answer at the high x) while the layout anchors
 * both targets with `alignParentStart`/`alignParentEnd`, which `RelativeLayout` mirrors in RTL. In Arabic, Persian, Urdu
 * and Uyghur the thumb was therefore clamped onto the **red** decline target as soon as the finger moved, and any release
 * at all — including a plain tap that never left the centre — answered the call. Rejecting from the full-screen call
 * screen was impossible, and answering by accident was one stray touch away.
 *
 * [legacyLtrOnlyRulesAnswerFromTheRedTargetInRtl] is the control. It writes the old comparisons out **inline** so it does
 * not call production code: routed through the fix it would stop reproducing and become a test that cannot fail. Every
 * other case is the treatment.
 *
 * Coordinates below use a 600 px wide track with 60 px targets, so LTR puts decline at 5 and answer at 535, and RTL is
 * that mirrored: answer at 5, decline at 535. The thumb rests in the centre, 270.
 */
class IncomingCallSliderGeometryTest {

    private val ltrDecline = 5f
    private val ltrAnswer = 535f
    private val rtlDecline = 535f
    private val rtlAnswer = 5f
    private val rest = 270f

    // region The control: what the old LTR-only rules did once the layout mirrored

    @Test
    fun legacyLtrOnlyRulesAnswerFromTheRedTargetInRtl() {
        // The shipped release test, verbatim: "past the answer anchor on the high side means answer".
        fun legacyReleaseAnswers(x: Float, answerAnchorX: Float): Boolean = x > answerAnchorX

        // In LTR it is right: resting in the centre is not a decision, and only reaching the far side answers.
        assertEquals(false, legacyReleaseAnswers(rest, ltrAnswer), "LTR: a resting thumb must not answer")
        assertEquals(true, legacyReleaseAnswers(ltrAnswer + 1f, ltrAnswer), "LTR: reaching the answer target answers")

        // In RTL the same rule answers from everywhere, because the answer anchor moved to the low side.
        assertEquals(true, legacyReleaseAnswers(rest, rtlAnswer), "the defect: a tap that never moved answered the call")
        assertEquals(
            true,
            legacyReleaseAnswers(rtlDecline, rtlAnswer),
            "the defect: dragging onto the RED decline target answered the call",
        )
    }

    @Test
    fun legacyLtrOnlyClampPinsTheThumbToTheDeclineTargetInRtl() {
        // The shipped low-side clamp, verbatim: "below the decline anchor snaps to the decline anchor".
        fun legacyClampLow(x: Float, declineAnchorX: Float): Float = if (x < declineAnchorX) declineAnchorX else x

        assertEquals(ltrDecline, legacyClampLow(0f, ltrDecline), "LTR: overshooting left snaps back to decline")
        assertEquals(rest, legacyClampLow(rest, ltrDecline), "LTR: the middle of the track is left alone")

        // In RTL the decline anchor is the high side, so the test is true across the whole track.
        assertEquals(
            rtlDecline,
            legacyClampLow(rest, rtlDecline),
            "the defect: touching the thumb flung it onto the RED decline target",
        )
    }

    // endregion

    // region Release verdicts

    @Test
    fun ltrReleaseIsUnchanged() {
        assertEquals(SliderReleaseVerdict.ANSWER, IncomingCallSliderGeometry.releaseAt(ltrAnswer + 1f, ltrDecline, ltrAnswer))
        assertEquals(SliderReleaseVerdict.REJECT, IncomingCallSliderGeometry.releaseAt(ltrDecline - 1f, ltrDecline, ltrAnswer))
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(rest, ltrDecline, ltrAnswer))
    }

    @Test
    fun rtlReleaseIsTheMirrorImage() {
        assertEquals(
            SliderReleaseVerdict.ANSWER,
            IncomingCallSliderGeometry.releaseAt(rtlAnswer - 1f, rtlDecline, rtlAnswer),
            "reaching the answer target, which RTL puts on the low side, must answer",
        )
        assertEquals(
            SliderReleaseVerdict.REJECT,
            IncomingCallSliderGeometry.releaseAt(rtlDecline + 1f, rtlDecline, rtlAnswer),
            "reaching the decline target, which RTL puts on the high side, must reject",
        )
    }

    @Test
    fun theRedTargetNeverAnswers() {
        // The reported symptom, stated directly: landing on decline rejects in both writing directions.
        assertEquals(SliderReleaseVerdict.REJECT, IncomingCallSliderGeometry.releaseAt(ltrDecline - 1f, ltrDecline, ltrAnswer))
        assertEquals(SliderReleaseVerdict.REJECT, IncomingCallSliderGeometry.releaseAt(rtlDecline + 1f, rtlDecline, rtlAnswer))
    }

    @Test
    fun aRestingThumbDecidesNothingInEitherDirection() {
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(rest, ltrDecline, ltrAnswer))
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(rest, rtlDecline, rtlAnswer))
    }

    @Test
    fun landingExactlyOnAnAnchorIsNotYetReachingIt() {
        // Strict comparisons, preserving the shipped LTR behaviour rather than widening it.
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(ltrAnswer, ltrDecline, ltrAnswer))
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(ltrDecline, ltrDecline, ltrAnswer))
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(rtlAnswer, rtlDecline, rtlAnswer))
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(rtlDecline, rtlDecline, rtlAnswer))
    }

    @Test
    fun anUnlaidOutSliderDecidesNothing() {
        // Both anchors read 0 before layout. Answering a ringing call off that would be a coin flip.
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(0f, 0f, 0f))
        assertEquals(SliderReleaseVerdict.RETURN_TO_REST, IncomingCallSliderGeometry.releaseAt(rest, 0f, 0f))
    }

    // endregion

    // region Clamping

    @Test
    fun clampKeepsTheThumbOnTheTrackInBothDirections() {
        assertEquals(ltrDecline, IncomingCallSliderGeometry.clampToTrack(-100f, ltrDecline, ltrAnswer))
        assertEquals(ltrAnswer, IncomingCallSliderGeometry.clampToTrack(9000f, ltrDecline, ltrAnswer))
        assertEquals(rtlAnswer, IncomingCallSliderGeometry.clampToTrack(-100f, rtlDecline, rtlAnswer))
        assertEquals(rtlDecline, IncomingCallSliderGeometry.clampToTrack(9000f, rtlDecline, rtlAnswer))
    }

    @Test
    fun clampLeavesAThumbOnTheTrackAlone() {
        assertEquals(rest, IncomingCallSliderGeometry.clampToTrack(rest, ltrDecline, ltrAnswer))
        assertEquals(
            rest,
            IncomingCallSliderGeometry.clampToTrack(rest, rtlDecline, rtlAnswer),
            "the defect was here: the thumb used to be flung onto the decline target instead",
        )
    }

    @Test
    fun aClampedThumbNeverDecidesOnItsOwn() {
        // The two functions have to agree: a drag that only ever gets clamped must still need a deliberate overshoot to
        // resolve, otherwise merely dragging to the end of the track would fire a verdict.
        for (anchors in listOf(ltrDecline to ltrAnswer, rtlDecline to rtlAnswer)) {
            val (decline, answer) = anchors
            for (raw in listOf(-9000f, -1f, rest, 9000f)) {
                val clamped = IncomingCallSliderGeometry.clampToTrack(raw, decline, answer)
                assertEquals(
                    SliderReleaseVerdict.RETURN_TO_REST,
                    IncomingCallSliderGeometry.releaseAt(clamped, decline, answer),
                    "a clamped position must never decide by itself (raw=$raw, decline=$decline, answer=$answer)",
                )
            }
        }
    }

    // endregion
}
