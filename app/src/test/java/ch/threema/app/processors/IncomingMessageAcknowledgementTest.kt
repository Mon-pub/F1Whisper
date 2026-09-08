package ch.threema.app.processors

import ch.threema.app.managers.ServiceManager
import ch.threema.app.multidevice.MultiDeviceManager
import ch.threema.app.protocolsteps.IdentityBlockedSteps
import ch.threema.app.protocolsteps.ValidContactsLookupSteps
import ch.threema.app.services.ContactService
import ch.threema.app.services.MessageService
import ch.threema.app.test.koinTestModuleRule
import ch.threema.base.crypto.HashedNonce
import ch.threema.base.crypto.Nonce
import ch.threema.base.crypto.NonceFactory
import ch.threema.base.crypto.NonceScope
import ch.threema.base.crypto.NonceStore
import ch.threema.data.repositories.ContactModelRepository
import ch.threema.domain.fs.DHSessionId
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.coders.MessageBox
import ch.threema.domain.protocol.csp.fs.ForwardSecurityMessageProcessor
import ch.threema.domain.protocol.csp.fs.PeerRatchetIdentifier
import ch.threema.domain.stores.ContactStore
import ch.threema.domain.stores.IdentityStore
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.ConnectionStoppedException
import ch.threema.protobuf.csp.e2e.fs.Encapsulated.DHType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Rule

private const val STORE_NONCE = "store-nonce"
private const val COMMIT_RATCHET = "commit-ratchet"
private const val SEND_ACK = "send-ack"

/**
 * The acknowledgement is what lets the server forget a message, so everything that has to outlive
 * the server's copy is written before it goes out. These cover that order, the guard that keeps the
 * ack going out anyway when persistence fails (otherwise one message wedges the whole queue), and
 * the two opposite outcomes [NonceFactory.store] reports through a single boolean.
 */
class IncomingMessageAcknowledgementTest {
    /**
     * Records what happened in the order it happened. The ordering IS the fix, so it is asserted
     * directly rather than through a verification DSL.
     */
    private val events = mutableListOf<String>()

    private class FakeNonceStore(
        private val events: MutableList<String>,
    ) : NonceStore {
        var storeResult: Boolean = true
        var existsResult: Boolean = false
        var storeFailure: Throwable? = null
        val storedNonces = mutableListOf<Nonce>()
        val existsQueries = mutableListOf<Nonce>()

        override fun exists(scope: NonceScope, nonce: Nonce): Boolean {
            existsQueries += nonce
            return existsResult
        }

        override fun store(scope: NonceScope, nonce: Nonce): Boolean {
            events += STORE_NONCE
            storeFailure?.let { throw it }
            storedNonces += nonce
            return storeResult
        }

        override fun getCount(scope: NonceScope): Long = storedNonces.size.toLong()

        override fun getAllHashedNonces(scope: NonceScope): List<HashedNonce> = emptyList()

        override fun addHashedNoncesChunk(
            scope: NonceScope,
            chunkSize: Int,
            offset: Int,
            hashedNonces: MutableList<HashedNonce>,
        ) = Unit

        override fun insertHashedNonces(scope: NonceScope, nonces: List<HashedNonce>): Boolean = true
    }

    private val nonceStore = FakeNonceStore(events)
    private val nonceFactory = NonceFactory(nonceStore)
    private val forwardSecurityMessageProcessor = mockk<ForwardSecurityMessageProcessor> {
        every { commitPeerRatchet(any(), any()) } answers { events += COMMIT_RATCHET }
    }
    private val handle = mockk<ActiveTaskCodec> {
        coEvery { write(any()) } answers { events += SEND_ACK }
    }
    private val serviceManager = mockk<ServiceManager>(relaxed = true)

    @get:Rule
    val koinRule = koinTestModuleRule {
        single { nonceFactory }
        single { forwardSecurityMessageProcessor }
        single { mockk<ContactService>(relaxed = true) }
        single { mockk<ContactModelRepository>(relaxed = true) }
        single { mockk<ContactStore>(relaxed = true) }
        single { mockk<IdentityStore>(relaxed = true) }
        single { mockk<MessageService>(relaxed = true) }
        single { mockk<MultiDeviceManager>(relaxed = true) }
        single { mockk<IdentityBlockedSteps>(relaxed = true) }
        single { mockk<ValidContactsLookupSteps>(relaxed = true) }
    }

    private val ratchetIdentifier = PeerRatchetIdentifier(DHSessionId(), "0TESTID1", DHType.FOURDH)

    private fun messageBox(flags: Int = 0, nonce: ByteArray = ByteArray(24) { 7 }): MessageBox =
        MessageBox().apply {
            fromIdentity = "0TESTID1"
            toIdentity = "0TESTID2"
            messageId = MessageId.random()
            date = Date()
            this.flags = flags
            pushFromName = ""
            setNonce(nonce)
            box = ByteArray(16)
        }

    private fun task(messageBox: MessageBox) = IncomingMessageTask(messageBox, serviceManager)

    @Test
    fun `the nonce and the peer ratchet are persisted before the server is told to forget the message`() = runTest {
        val box = messageBox()

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)

        assertEquals(listOf(STORE_NONCE, COMMIT_RATCHET, SEND_ACK), events)
        assertEquals(listOf(Nonce(box.nonce)), nonceStore.storedNonces)
    }

    @Test
    fun `a failing nonce store still acknowledges, so the queue is not wedged behind one message`() = runTest {
        val box = messageBox()
        nonceStore.storeFailure = IllegalStateException("database is locked")

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)

        assertEquals(listOf(STORE_NONCE, SEND_ACK), events)
    }

    @Test
    fun `a failing peer ratchet commit still acknowledges`() = runTest {
        val box = messageBox()
        every { forwardSecurityMessageProcessor.commitPeerRatchet(any(), any()) } throws
            IllegalStateException("session store unavailable")

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)

        assertEquals(listOf(STORE_NONCE, SEND_ACK), events)
    }

    /**
     * A lost connection is the one failure where NOT acknowledging is right: the ack could not be
     * delivered anyway, and the task manager reconnects and receives the message again.
     */
    @Test
    fun `a lost connection propagates and does not acknowledge`() = runTest {
        val box = messageBox()
        nonceStore.storeFailure = ConnectionStoppedException()

        assertFailsWith<ConnectionStoppedException> {
            task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)
        }

        assertEquals(listOf(STORE_NONCE), events)
    }

    /**
     * [NonceFactory.store] answers false both when the nonce was already there and when the insert
     * failed. Only the second means the message is unprotected, so the two are told apart instead of
     * both passing unremarked.
     */
    @Test
    fun `a nonce that was already stored is checked against the store rather than assumed lost`() = runTest {
        val box = messageBox()
        nonceStore.storeResult = false
        nonceStore.existsResult = true

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)

        assertEquals(listOf(Nonce(box.nonce)), nonceStore.existsQueries)
        assertEquals(listOf(STORE_NONCE, COMMIT_RATCHET, SEND_ACK), events)
    }

    @Test
    fun `a nonce that could not be stored is checked against the store before being reported`() = runTest {
        val box = messageBox()
        nonceStore.storeResult = false
        nonceStore.existsResult = false

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)

        assertEquals(listOf(Nonce(box.nonce)), nonceStore.existsQueries)
        assertEquals(listOf(STORE_NONCE, COMMIT_RATCHET, SEND_ACK), events)
    }

    @Test
    fun `a message not protected against replay does not store its nonce`() = runTest {
        val box = messageBox()

        task(box).acknowledgeMessage(box, protectAgainstReplay = false, ratchetIdentifier, handle)

        assertEquals(listOf(COMMIT_RATCHET, SEND_ACK), events)
    }

    @Test
    fun `a message without a peer ratchet does not turn one`() = runTest {
        val box = messageBox()

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, peerRatchetIdentifier = null, handle)

        assertEquals(listOf(STORE_NONCE, SEND_ACK), events)
    }

    /**
     * The no-server-ack flag suppresses only the ack. What has to be persisted still is.
     */
    @Test
    fun `the no-server-ack flag suppresses the ack but not the persistence`() = runTest {
        val box = messageBox(flags = ProtocolDefines.MESSAGE_FLAG_NO_SERVER_ACK)

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)

        assertEquals(listOf(STORE_NONCE, COMMIT_RATCHET), events)
    }

    /**
     * An unusable nonce cannot be stored, but it must not take the ratchet commit or the ack down
     * with it.
     */
    @Test
    fun `an invalid nonce does not prevent the ratchet commit or the ack`() = runTest {
        val box = messageBox(nonce = ByteArray(8))

        task(box).acknowledgeMessage(box, protectAgainstReplay = true, ratchetIdentifier, handle)

        assertEquals(listOf(COMMIT_RATCHET, SEND_ACK), events)
    }
}
