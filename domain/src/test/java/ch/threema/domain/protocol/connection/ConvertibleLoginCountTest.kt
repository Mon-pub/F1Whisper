package ch.threema.domain.protocol.connection

import ch.threema.common.stateFlowOf
import io.mockk.every
import io.mockk.mockk
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * F1Whisper: the app holds the [ConvertibleServerConnection] wrapper, so the login count must reach it from the wrapped
 * connection. Falling back to the interface default would report 0 forever and silently stop every per-login job.
 */
class ConvertibleLoginCountTest {
    @Test
    fun `the wrapper reports the wrapped connection's login count`() {
        val logins = MutableStateFlow(0L)
        val wrapped = mockk<ServerConnection>(relaxed = true) {
            every { isRunning } returns false
            every { connectionState } returns ConnectionState.DISCONNECTED
            every { watchConnectionState() } returns stateFlowOf(ConnectionState.DISCONNECTED)
            every { watchLoginCount() } returns logins
        }
        val convertible = ConvertibleServerConnection(Supplier { wrapped })
        assertEquals(0L, convertible.watchLoginCount().value, "no connection yet")

        convertible.start()
        logins.value = 3L

        assertEquals(3L, convertible.watchLoginCount().value)
    }
}
