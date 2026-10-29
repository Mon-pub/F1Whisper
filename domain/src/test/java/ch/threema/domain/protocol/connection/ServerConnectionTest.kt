package ch.threema.domain.protocol.connection

import app.cash.turbine.test
import ch.threema.base.crypto.NaCl
import ch.threema.base.crypto.NonceCounter
import ch.threema.common.emptyByteArray
import ch.threema.domain.protocol.ServerAddressProvider
import ch.threema.libthreema.blake2bMac256
import ch.threema.testhelpers.expectItem
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

internal abstract class ServerConnectionTest {
    private companion object {
        private val skPublicPrimary = ByteArray(NaCl.PUBLIC_KEY_BYTES)
        private val skSecretPrimary = ByteArray(NaCl.SECRET_KEY_BYTES)
        private val skPublicAlt = ByteArray(NaCl.PUBLIC_KEY_BYTES)
        private val skSecretAlt = ByteArray(NaCl.SECRET_KEY_BYTES)
        private val random = SecureRandom()

        init {
            NaCl.generateKeypairInPlace(skPublicPrimary, skSecretPrimary)
            NaCl.generateKeypairInPlace(skPublicAlt, skSecretAlt)
            NaCl.generateKeypairInPlace(TestIdentityStore.ckPublic, TestIdentityStore.ckSecret)
        }
    }

    protected lateinit var testSocket: TestSocket
    protected val serverAddressProvider = TestServerAddressProvider(skPublicPrimary, skPublicAlt)

    private lateinit var skSecret: ByteArray
    private lateinit var skPublic: ByteArray

    private lateinit var tskSecret: ByteArray
    private lateinit var tskPublic: ByteArray
    private lateinit var sck: ByteArray
    private lateinit var serverNonce: NonceCounter

    private lateinit var tckPublic: ByteArray
    private lateinit var cck: ByteArray
    private lateinit var clientNonce: NonceCounter

    private lateinit var kClientServer: NaCl

    /**
     * Create a [ServerConnection] which would be used to connect to the chatserver and uses [testSocket] as the server Socket.
     * [serverAddressProvider] must be used as the [ServerAddressProvider].
     */
    abstract fun createChatServerConnection(): ServerConnection

    @BeforeTest
    @Throws(Exception::class)
    fun setUp() {
        testSocket = TestSocket()
    }

    @Test
    fun `test initial connection state is DISCONNECTED`() {
        val connection = createChatServerConnection()
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState)
    }

    @Test
    fun testChatServerConnection() = runTest(timeout = 1.seconds) {
        prepareServerKeys(skPublicPrimary, skSecretPrimary)

        // F1Whisper: no inbound activity has been recorded before the connection reaches LOGGED_IN.
        val connection = createChatServerConnection()
        assertEquals(0L, connection.getLastInboundActivityAtMillis())
        val loginsBefore = connection.watchLoginCount().value
        val beforeHandshake = System.currentTimeMillis()

        connection.watchConnectionState().test {
            expectItem(ConnectionState.DISCONNECTED)

            connection.start()

            expectItem(ConnectionState.CONNECTING)
            expectItem(ConnectionState.CONNECTED)
            assertHandshake()

            expectItem(ConnectionState.LOGGED_IN)

            // F1Whisper: reaching LOGGED_IN seeds the last-inbound-activity timestamp with a recent
            // wall-clock value, so the foreground staleness gate never falsely flags a fresh login.
            // The stamp is written before the state flips, so it must be visible once LOGGED_IN has
            // been observed.
            assertEquals(loginsBefore + 1, connection.watchLoginCount().value, "the login is counted by the time LOGGED_IN is seen")

            val lastInboundActivity = connection.getLastInboundActivityAtMillis()
            assertTrue(
                lastInboundActivity >= beforeHandshake,
                "Expected last inbound activity ($lastInboundActivity) to be seeded at/after login start ($beforeHandshake)",
            )
            assertTrue(
                lastInboundActivity <= System.currentTimeMillis(),
                "Expected last inbound activity ($lastInboundActivity) not to be in the future",
            )

            connection.stop()

            expectItem(ConnectionState.DISCONNECTED)
            assertEquals(loginsBefore + 1, connection.watchLoginCount().value, "a disconnect does not reset the count")
            assertEquals(
                loginsBefore + 1,
                createChatServerConnection().watchLoginCount().value,
                "a replacement connection continues the count instead of starting again",
            )
            assertEquals(1, serverAddressProvider.keyFetchCount)
            assertEquals(0, serverAddressProvider.altKeyFetchCount)

            cancel()
        }
    }

    @Test
    fun testChatServerConnectionAltKey() = runTest {
        prepareServerKeys(skPublicAlt, skSecretAlt)

        val connection = createChatServerConnection()

        connection.watchConnectionState().test {
            expectItem(ConnectionState.DISCONNECTED)

            connection.start()

            expectItem(ConnectionState.CONNECTING)
            expectItem(ConnectionState.CONNECTED)
            assertHandshake()

            connection.stop()

            expectItem(ConnectionState.LOGGED_IN)
            expectItem(ConnectionState.DISCONNECTED)
            assertEquals(1, serverAddressProvider.keyFetchCount)
            assertEquals(1, serverAddressProvider.altKeyFetchCount)

            cancel()
        }
    }

    private fun assertHandshake() {
        expectClientHello()
        sendServerHello()

        expectClientLogin()
        sendLoginAck()

        // Wait some time until the connection has processed the reservedBox response
        Thread.sleep(100)
    }

    private fun expectClientHello() {
        tckPublic = testSocket.read(32)
        cck = testSocket.read(16)
        clientNonce = NonceCounter(cck)
        kClientServer = NaCl(tskSecret, tckPublic)
    }

    private fun sendServerHello() {
        val serverHelloNaCl = NaCl(skSecret, tckPublic)
        val serverHello = serverHelloNaCl.encrypt(
            data = tskPublic + cck,
            nonce = serverNonce.nextNonce(),
        )
        testSocket.write(sck + serverHello)
    }

    private fun expectClientLogin() {
        val extensionLength = expectLoginBox()
        expectExtensionBox(extensionLength)
    }

    private fun expectLoginBox(): Int {
        // expect `login`
        val loginBox = testSocket.read(144)
        val loginBoxDecrypted = kClientServer.decrypt(
            data = loginBox,
            nonce = clientNonce.nextNonce(),
        )
        assertNotNull(loginBoxDecrypted)
        val decryptedLoginStream = ByteArrayInputStream(loginBoxDecrypted)

        // read identity
        val clientIdentity = decryptedLoginStream.readNBytes(8).decodeToString()
        assertEquals(TestIdentityStore.CLIENT_IDENTITY, clientIdentity)

        // read extension-indicator
        val extensionIndicator = decryptedLoginStream.readNBytes(30).decodeToString()
        assertEquals("threema-clever-extension-field", extensionIndicator)
        val bb = ByteBuffer.wrap(decryptedLoginStream.readNBytes(2))
        bb.order(ByteOrder.LITTLE_ENDIAN)
        val extensionLength = bb.short.toUShort().toInt()

        // read repeated sck
        val repeatedSck = decryptedLoginStream.readNBytes(16)
        assertContentEquals(sck, repeatedSck)

        // read reserved1
        val reserved1 = decryptedLoginStream.readNBytes(24)
        assertContentEquals(ByteArray(24), reserved1)

        // read vouch
        val ss1 = NaCl(skSecret, TestIdentityStore.ckPublic).sharedSecret
        val ss2 = NaCl(tskSecret, TestIdentityStore.ckPublic).sharedSecret
        val vouchKey = blake2bMac256(
            key = ss1 + ss2,
            personal = "3ma-csp".encodeToByteArray(),
            salt = "v2".encodeToByteArray(),
            data = emptyByteArray(),
        )
        val expectedVouch = blake2bMac256(
            key = vouchKey,
            personal = emptyByteArray(),
            salt = emptyByteArray(),
            data = sck + tckPublic,
        )
        val vouch = decryptedLoginStream.readNBytes(32)
        assertContentEquals(expectedVouch, vouch)

        // read reserved2
        val reserved2 = decryptedLoginStream.readNBytes(16)
        assertContentEquals(ByteArray(16), reserved2)

        // assert login data consumed
        assertEquals(0, decryptedLoginStream.available())

        return extensionLength
    }

    private fun expectExtensionBox(length: Int) {
        // read extension box
        val extensionBox = testSocket.read(length)
        val extensionBoxDecrypted = kClientServer.decrypt(
            data = extensionBox,
            nonce = clientNonce.nextNonce(),
        )
        assertNotNull(extensionBoxDecrypted)
    }

    private fun sendLoginAck() {
        val reservedBox = kClientServer.encrypt(
            data = ByteArray(16),
            nonce = serverNonce.nextNonce(),
        )
        testSocket.write(reservedBox)
    }

    private fun prepareServerKeys(skPublic: ByteArray, skSecret: ByteArray) {
        this.skPublic = skPublic
        this.skSecret = skSecret
        sck = ByteArray(16)
        random.nextBytes(sck)
        tskPublic = ByteArray(NaCl.PUBLIC_KEY_BYTES)
        tskSecret = ByteArray(NaCl.SECRET_KEY_BYTES)
        NaCl.generateKeypairInPlace(tskPublic, tskSecret)
        serverNonce = NonceCounter(sck)
    }
}
