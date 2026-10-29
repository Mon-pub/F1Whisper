package ch.threema.domain.protocol.connection.csp.socket

import java.net.InetAddress
import java.net.UnknownHostException

// F1-PATCH: open (upstream: final) so the app can install its resolver with a DNS fallback for the chat server
// connection. The default behaviour, including the throw on an empty answer, is unchanged.
open class HostResolver {
    @Throws(UnknownHostException::class)
    open fun getAllByName(name: String): Array<InetAddress> =
        InetAddress.getAllByName(name)
            .ifEmpty {
                throw UnknownHostException()
            }
}
