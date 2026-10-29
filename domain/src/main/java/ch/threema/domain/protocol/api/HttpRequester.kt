package ch.threema.domain.protocol.api

import ch.threema.common.Http
import ch.threema.common.buildNew
import ch.threema.common.buildRequest
import ch.threema.common.execute
import ch.threema.common.getSuccessBodyOrThrow
import ch.threema.domain.protocol.Version
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.getUserAgent
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class HttpRequester(
    okHttpClient: OkHttpClient,
    private val authenticator: APIAuthenticator?,
    private val language: String? = null,
    private val version: Version,
) {
    private val okHttpClient = okHttpClient.buildNew {
        connectTimeout(ProtocolDefines.API_REQUEST_TIMEOUT.seconds)
        readTimeout(ProtocolDefines.API_REQUEST_TIMEOUT.seconds)
    }

    @Throws(IOException::class)
    fun get(url: String): HttpRequesterResult =
        sendRequest(url) {
            get()
        }

    @Throws(IOException::class)
    fun post(url: String, body: JSONObject): HttpRequesterResult =
        sendRequest(url) {
            post(body.toString().toRequestBody("application/json".toMediaType()))
        }

    @Throws(IOException::class)
    private fun sendRequest(url: String, buildRequest: Request.Builder.() -> Unit): HttpRequesterResult {
        val request = buildRequest {
            url(url)
            header(Http.Header.USER_AGENT, getUserAgent(version))
            if (language != null) {
                header(Http.Header.ACCEPT_LANGUAGE, language)
            }
            authenticator?.getCredentials()?.let { credentials ->
                header(Http.Header.AUTHORIZATION, Credentials.basic(credentials.username, credentials.password))
            }
            buildRequest()
        }

        okHttpClient.execute(request).use { response ->
            // F1Whisper: harvest server UTC from the standard `Date:` response header on every
            // successful response from our cert-pinned OnPrem API. This is the single trustworthy
            // time source (do NOT harvest from the unpinned link-preview fetcher). Feeds the
            // app-side TrustedClock so outgoing message timestamps are cross-device-comparable even
            // when the phone's own clock is wrong.
            if (response.isSuccessful) {
                response.headers.getDate("Date")?.let { serverDate ->
                    ServerTimeReporter.report(serverDate.time, System.currentTimeMillis())
                }
                return HttpRequesterResult.Success(response.getSuccessBodyOrThrow().string())
            }
            return HttpRequesterResult.Error(response.code)
        }
    }
}
