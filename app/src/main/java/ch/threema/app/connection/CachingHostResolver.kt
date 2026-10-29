package ch.threema.app.connection

import ch.threema.domain.protocol.connection.csp.socket.HostResolver
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * F1Whisper: the chat server connection's resolver. Resolves through [CachingDnsResolver] (system resolver, DoT fallback,
 * then the last-good address cache), so a reconnect during a Doze window where name resolution fails can still reach the
 * server. [CachingDnsResolver] never returns an empty answer; it throws instead, like the upstream resolver.
 */
class CachingHostResolver : HostResolver() {
    @Throws(UnknownHostException::class)
    override fun getAllByName(name: String): Array<InetAddress> =
        CachingDnsResolver.getAllByName(name)
}
