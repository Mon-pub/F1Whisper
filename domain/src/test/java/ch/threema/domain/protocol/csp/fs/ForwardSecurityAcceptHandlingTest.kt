package ch.threema.domain.protocol.csp.fs

import ch.threema.domain.fs.DHSession
import ch.threema.domain.fs.DHSessionId
import ch.threema.domain.helpers.DummyUsers
import ch.threema.domain.helpers.ForwardSecurityMessageProcessorWrapper
import ch.threema.domain.helpers.InMemoryContactStore
import ch.threema.domain.helpers.InMemoryDHSessionStore
import ch.threema.domain.helpers.ServerAckTaskCodec
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityDataAccept
import ch.threema.domain.testhelpers.TestHelpers
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A peer that does not see our reply resends its accept. The first one destroys the ephemeral
 * private key, so the second cannot be processed and used to escape as an error. These cover that
 * the duplicate is now absorbed without losing the established session, and that the same missing
 * key in a session that has NOT been established still fails.
 */
class ForwardSecurityAcceptHandlingTest {
    private val aliceIdentityStore = DummyUsers.getIdentityStoreForUser(DummyUsers.ALICE)
    private val bobIdentityStore = DummyUsers.getIdentityStoreForUser(DummyUsers.BOB)
    private val aliceContact = DummyUsers.getContactForUser(DummyUsers.ALICE)
    private val bobContact = DummyUsers.getContactForUser(DummyUsers.BOB)

    private val sessionStore = InMemoryDHSessionStore()
    private val statusListener = mockk<ForwardSecurityStatusListener>(relaxed = true)
    private val handle = ServerAckTaskCodec()

    private val processor = ForwardSecurityMessageProcessorWrapper(
        ForwardSecurityMessageProcessor(
            sessionStore,
            InMemoryContactStore().apply {
                addContact(aliceContact)
                addContact(bobContact)
            },
            aliceIdentityStore,
            TestHelpers.noopNonceFactory,
            statusListener,
        ),
    )

    /**
     * Alice initiates, Bob replies, Alice processes the accept: a 4DH session with the ephemeral
     * private key destroyed, which is the state every duplicate accept arrives into.
     */
    private fun establishedAliceSession(): Pair<DHSession, ForwardSecurityDataAccept> {
        val aliceSession = DHSession(bobContact, aliceIdentityStore)
        val bobSession = DHSession(
            aliceSession.id,
            DHSession.getSupportedVersionRange(),
            aliceSession.myEphemeralPublicKey,
            aliceContact,
            bobIdentityStore,
        )
        sessionStore.storeDHSession(aliceSession)

        val accept = ForwardSecurityDataAccept(
            aliceSession.id,
            DHSession.getSupportedVersionRange(),
            bobSession.myEphemeralPublicKey,
        )
        processor.processAccept(bobContact, accept, handle)

        val established = sessionStore.getDHSession(
            aliceIdentityStore.getIdentityString(),
            bobContact.identity,
            aliceSession.id,
            handle,
        )
        assertNotNull(established)
        assertNull(established.myEphemeralPrivateKey, "the first accept must destroy the ephemeral private key")
        assertEquals(DHSession.State.RL44, established.state)
        return established to accept
    }

    @Test
    fun `a duplicate accept for an established session is absorbed`() {
        val (established, accept) = establishedAliceSession()

        processor.processAccept(bobContact, accept, handle)

        val afterDuplicate = sessionStore.getDHSession(
            aliceIdentityStore.getIdentityString(),
            bobContact.identity,
            established.id,
            handle,
        )
        assertNotNull(afterDuplicate)
        assertEquals(DHSession.State.RL44, afterDuplicate.state)
    }

    @Test
    fun `a duplicate accept keeps the ratchets and the negotiated versions`() {
        val (established, accept) = establishedAliceSession()
        val myRatchetBefore = established.myRatchet4DH
        val peerRatchetBefore = established.peerRatchet4DH
        val versionsBefore = established.current4DHVersions
        assertNotNull(myRatchetBefore)
        assertNotNull(peerRatchetBefore)

        processor.processAccept(bobContact, accept, handle)

        val afterDuplicate = sessionStore.getDHSession(
            aliceIdentityStore.getIdentityString(),
            bobContact.identity,
            established.id,
            handle,
        )
        assertNotNull(afterDuplicate)
        assertEquals(myRatchetBefore, afterDuplicate.myRatchet4DH)
        assertEquals(peerRatchetBefore, afterDuplicate.peerRatchet4DH)
        assertEquals(versionsBefore, afterDuplicate.current4DHVersions)
    }

    /**
     * Re-announcing establishment would tell the UI a session was just set up that has been carrying
     * messages for a while.
     */
    @Test
    fun `a duplicate accept does not re-announce that the session was established`() {
        val (_, accept) = establishedAliceSession()

        processor.processAccept(bobContact, accept, handle)

        verify(exactly = 1) { statusListener.initiatorSessionEstablished(any(), any()) }
    }

    /**
     * A ratchet already turned by traffic must not be reset: that would silently discard the
     * position both sides agreed on.
     */
    @Test
    fun `a duplicate accept does not rewind a ratchet that has already turned`() {
        val (established, accept) = establishedAliceSession()
        established.myRatchet4DH!!.turnUntil(7)
        established.peerRatchet4DH!!.turnUntil(4)
        sessionStore.storeDHSession(established)

        processor.processAccept(bobContact, accept, handle)

        val afterDuplicate = sessionStore.getDHSession(
            aliceIdentityStore.getIdentityString(),
            bobContact.identity,
            established.id,
            handle,
        )
        assertNotNull(afterDuplicate)
        assertEquals(7, afterDuplicate.myRatchet4DH!!.counter)
        assertEquals(4, afterDuplicate.peerRatchet4DH!!.counter)
    }

    /**
     * The narrow part of the fix: an L20 session is still AWAITING its first accept, so the
     * ephemeral private key must be there. Missing it is a broken local session, not a duplicate,
     * and has to keep failing.
     */
    @Test
    fun `a missing ephemeral key in an unestablished session still fails`() {
        val sessionId = DHSessionId()
        val template = DHSession(bobContact, aliceIdentityStore)
        val brokenSession = DHSession(
            sessionId,
            aliceIdentityStore.getIdentityString()!!,
            bobContact.identity,
            null,
            template.myEphemeralPublicKey,
            null,
            0L,
            template.myRatchet2DH,
            null,
            null,
            null,
        )
        assertEquals(DHSession.State.L20, brokenSession.state)
        sessionStore.storeDHSession(brokenSession)

        val bobSession = DHSession(
            sessionId,
            DHSession.getSupportedVersionRange(),
            template.myEphemeralPublicKey,
            aliceContact,
            bobIdentityStore,
        )
        val accept = ForwardSecurityDataAccept(
            sessionId,
            DHSession.getSupportedVersionRange(),
            bobSession.myEphemeralPublicKey,
        )

        assertFailsWith<DHSession.MissingEphemeralPrivateKeyException> {
            processor.processAccept(bobContact, accept, handle)
        }
    }
}
