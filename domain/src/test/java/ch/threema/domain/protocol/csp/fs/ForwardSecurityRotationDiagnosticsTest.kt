package ch.threema.domain.protocol.csp.fs

import ch.threema.domain.fs.KDFRatchet
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `turnUntil` refuses for two opposite reasons: the key is already consumed, or catching up would
 * cost more turns than we are willing to spend. Both end the session, but a report that calls every
 * refusal "out of order" points at the wrong upstream problem.
 */
class ForwardSecurityRotationDiagnosticsTest {
    @Test
    fun `the two refusals are distinguishable from the exception alone`() {
        val ratchet = KDFRatchet(10, ByteArray(32))

        val behind = assertFailsWith<KDFRatchet.RatchetRotationException> { ratchet.turnUntil(9) }
        val tooFarAhead = assertFailsWith<KDFRatchet.RatchetRotationException> { ratchet.turnUntil(10 + 25_001) }

        assertSame(KDFRatchet.RatchetRotationException.Cause.COUNTER_BEHIND, behind.rotationCause)
        assertSame(KDFRatchet.RatchetRotationException.Cause.TARGET_TOO_FAR_AHEAD, tooFarAhead.rotationCause)
    }

    @Test
    fun `the rotation failure branch names which of the two refusals fired`() {
        val source = File("src/main/java/ch/threema/domain/protocol/csp/fs/ForwardSecurityMessageProcessor.kt").readText()

        assertTrue(
            source.contains("KDFRatchet.RatchetRotationException.Cause.COUNTER_BEHIND ->") &&
                source.contains("KDFRatchet.RatchetRotationException.Cause.TARGET_TOO_FAR_AHEAD ->"),
            "The rotation failure log must distinguish a consumed key from a target too far ahead " +
                "instead of labelling both 'out of order'",
        )
    }

    /**
     * The ordinary gap is NOT this branch: a forward skip within the limit is absorbed, reported as
     * skipped messages, and decryption continues. Only a refusal ends the session, so the two must
     * not be confused when reading a session teardown.
     */
    @Test
    fun `a forward skip within the limit is not a refusal`() {
        val ratchet = KDFRatchet(10, ByteArray(32))

        val turns = ratchet.turnUntil(10 + 25_000)

        assertEquals(25_000, turns)
    }
}
