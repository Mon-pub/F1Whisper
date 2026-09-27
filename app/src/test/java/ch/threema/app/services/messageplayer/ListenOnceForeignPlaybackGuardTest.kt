package ch.threema.app.services.messageplayer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1Whisper (listen-once double burn): a player may only record playback of ITS OWN message.
 *
 * Every `AudioMessagePlayer` attaches to one shared `VoiceMessagePlayerService` controller, and media3 delivers every
 * controller event to every attached listener. A player that is stopped while its own item is still the controller's
 * item takes the no-op branch of `stop()` and therefore keeps its listener attached: an ORPHAN that goes on receiving
 * the next message's events.
 *
 * On 2026-09-20 that orphan destroyed a 210 second voice message one second into playback. The unguarded `isPlaying`
 * branch of `onIsPlayingChanged` recorded the NEW message's first audible frame as the ORPHAN's own playback and called
 * `makeResume`, which reaches `MessagePlayerServiceImpl.stopOtherPlayers` and stopped the message that was genuinely
 * playing. That victim then settled with `hasPlayed` still false, so it took `RELEASE_ONLY` and dropped its ownership
 * instead of burning, leaving a claimed-but-ownerless row. `AudioChatAdapterDecorator`'s abandoned-claim repair
 * destroyed it on the very next bind, while its audio kept running from the already-decrypted cache file.
 *
 * The pause branch of the same callback, `prepared()` and the idle transition all already carried this guard. The
 * positive branch and `markAsConsumed()` did not. That asymmetry WAS the defect.
 *
 * This cannot be a behavioural test: the trigger is a media3 `Player.Listener` callback on a real `MediaController`
 * inside a live `MediaSessionService`, and there is no Robolectric here. So it is a structural pin, written to the
 * standard a previous review set for this repo: comments are stripped BEFORE brace matching (a `{` inside a comment
 * has produced a real false pass here), string literals are blanked so prose in a log message cannot satisfy it, the
 * guard must be the branch CONDITION rather than merely present, and the side effects must sit INSIDE the guarded
 * branch with no unguarded duplicate elsewhere in the method.
 *
 * Red-probed against: deleting the conjunct, turning `&&` into `||`, moving `hasPlayed = true` out of the branch,
 * leaving the guard only in a comment, and calling the helper while ignoring its result. Each fails at an assertion,
 * none at a compile error.
 */
class ListenOnceForeignPlaybackGuardTest {

    private val source =
        strip(File("src/main/java/ch/threema/app/services/messageplayer/AudioMessagePlayer.java").readText())

    private val guard = "if(isPlaying&&playerMediaMatchesControllerMedia()){"

    @Test
    fun `the isPlaying branch is guarded by the media match`() {
        val body = bodyOf("public void onIsPlayingChanged(")
        assertTrue(
            body.contains(guard),
            "the positive branch of onIsPlayingChanged must be conditioned on this player owning the controller's " +
                "current item. Without the conjunction an orphaned listener records another message's first frame as " +
                "its own, which is what deleted a 210 s message one second in. Body was: $body",
        )
    }

    @Test
    fun `the playback side effects stay inside the guarded branch`() {
        val body = bodyOf("public void onIsPlayingChanged(")
        val branch = blockAfter(body, guard)

        assertTrue(
            branch.contains("hasPlayed=true;"),
            "hasPlayed must be set inside the guarded branch, not beside it. Branch was: $branch",
        )
        assertTrue(
            branch.contains("makeResume("),
            "makeResume must be called inside the guarded branch. Branch was: $branch",
        )

        // The whole point: neither may ALSO appear on a path the guard does not cover. A mutation that leaves the
        // guarded branch intact and adds an unguarded copy beside it would otherwise pass every other assertion here.
        val outside = body.replace(branch, "")
        assertTrue(
            !outside.contains("hasPlayed=true"),
            "hasPlayed must not be assigned anywhere the media match does not gate. Outside the branch was: $outside",
        )
        assertTrue(
            !outside.contains("makeResume("),
            "makeResume must not be reachable without the media match. Outside the branch was: $outside",
        )
    }

    @Test
    fun `only one path in the callback records playback`() {
        val body = bodyOf("public void onIsPlayingChanged(")
        assertEquals(
            1,
            occurrences(body, "hasPlayed=true"),
            "exactly one assignment of hasPlayed, so there is a single place the guard has to hold",
        )
        assertEquals(
            1,
            occurrences(body, "makeResume("),
            "exactly one makeResume, for the same reason",
        )
    }

    @Test
    fun `consumption is recorded only for this player's own message`() {
        val body = bodyOf("public void onPlaybackStateChanged(")
        assertTrue(
            body.contains("if(playerMediaMatchesControllerMedia()){markAsConsumed();}"),
            "an orphaned listener reaches STATE_READY when a DIFFERENT message becomes ready; unguarded it wrote " +
                "consumed state for its own unrelated message. Body was: $body",
        )
        assertEquals(
            1,
            occurrences(body, "markAsConsumed("),
            "one call only, so the guard above cannot be bypassed by a second unguarded call",
        )
    }

    @Test
    fun `the pause branch keeps the guard it always had`() {
        // F10-04 put the match check on the pause branch. The fix must not "tidy" it away while adding the positive
        // one: a foreign pause event acting on this player is the same defect in the other direction.
        val body = bodyOf("public void onIsPlayingChanged(")
        assertTrue(
            body.contains(
                "elseif(!isPlaying&&mediaController.getPlaybackState()!=Player.STATE_ENDED" +
                    "&&playerMediaMatchesControllerMedia()){",
            ),
            "the pause path must remain gated on the media match, and must not fire for a playing event. Body was: $body",
        )
        assertEquals(
            2,
            occurrences(body, "playerMediaMatchesControllerMedia()"),
            "both branches of the callback carry the guard: the positive one added here and the pause one from F10-04",
        )
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Comments removed first, then string literals blanked, then all whitespace dropped.
     *
     * Order matters. Stripping strings first would leave `//` sequences from inside literals behind; stripping comments
     * first removes any quote characters they contain, so the literal pass cannot then run away. Whitespace is dropped
     * last so a pin cannot be broken by reformatting, and cannot be satisfied by prose.
     */
    private fun strip(text: String): String =
        text
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex(""""(\\.|[^"\\])*""""), "\"\"")
            .replace(Regex("""\s+"""), "")

    /** The brace-matched body of the declaration starting with [signature], from the already-stripped source. */
    private fun bodyOf(signature: String): String {
        val needle = signature.replace(Regex("""\s+"""), "")
        val start = source.indexOf(needle)
        assertTrue(start >= 0, "signature no longer present, the pin is pointing at nothing: $signature")
        return blockFrom(source, source.indexOf('{', start))
    }

    /** The brace-matched block introduced by [opener], which must end with its `{`. */
    private fun blockAfter(body: String, opener: String): String {
        val at = body.indexOf(opener)
        if (at < 0) return ""
        return blockFrom(body, body.indexOf('{', at))
    }

    private fun blockFrom(text: String, open: Int): String {
        assertTrue(open >= 0, "no block found where one is required")
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, index)
            }
        }
        error("unbalanced braces")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }
}
