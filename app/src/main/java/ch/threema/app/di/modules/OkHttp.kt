package ch.threema.app.di.modules

import ch.threema.app.dev.hasDevFeatures
import ch.threema.app.di.Qualifiers
import ch.threema.app.net.DotPreferredResolver
import ch.threema.app.onprem.OnPremCertPinning
import ch.threema.app.utils.AppVersionProvider
import ch.threema.app.utils.ConfigUtils
import ch.threema.base.utils.getThreemaLogger
import ch.threema.common.Http
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.getUserAgent
import kotlin.time.Duration.Companion.seconds
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module

private val logger = getThreemaLogger("OkHttp")

val okHttpClientsModule = module {
    single<OkHttpClient>(qualifier = Qualifiers.okHttpBase) {
        buildBaseOkHttpClient()
    }
    single<OkHttpClient> {
        val baseClient = get<OkHttpClient>(qualifier = Qualifiers.okHttpBase)
        if (ConfigUtils.isOnPremBuild()) {
            OnPremCertPinning.createDefaultOkHttpClient(baseClient)
        } else {
            baseClient
        }
    }
}

private fun buildBaseOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .apply {
            connectTimeout(ProtocolDefines.CONNECT_TIMEOUT.seconds)
            writeTimeout(ProtocolDefines.WRITE_TIMEOUT.seconds)
            readTimeout(ProtocolDefines.READ_TIMEOUT.seconds)
            // Resolve names DoT-first (plain DNS is hijacked on the target networks). The
            // OPPF/directory/blob clients all derive from this base client, so they inherit it.
            dns(Dns { hostname -> DotPreferredResolver.resolve(hostname) })
            // F1Whisper: ensure every request through the shared base client carries our schema
            // User-Agent, including blob upload/download which otherwise falls back to OkHttp's own
            // "okhttp/x.y.z" default (blob has no server-side anti-lockout exemption). Applied as an
            // application interceptor so it runs before OkHttp's BridgeInterceptor: setting the
            // header here suppresses OkHttp's default (BridgeInterceptor only adds one when none is
            // present) and never duplicates it. Requests that already set User-Agent explicitly
            // (the per-request getUserAgent(version) in HttpRequester/BlobLoader/BlobUploader, and
            // the no-argument getUserAgent() in ThreemaSafeApiClient/OnPremConfigFetcher) keep
            // their value. The link-preview fetcher uses a SEPARATE OkHttpClient and is deliberately
            // NOT covered here (advertising product/version to third-party sites is a privacy leak).
            addInterceptor { chain ->
                val request = chain.request()
                val requestWithUserAgent = if (request.header(Http.Header.USER_AGENT) == null) {
                    request.newBuilder()
                        .header(Http.Header.USER_AGENT, getUserAgent(AppVersionProvider.appVersion))
                        .build()
                } else {
                    request
                }
                chain.proceed(requestWithUserAgent)
            }
            if (hasDevFeatures()) {
                val interceptor = HttpLoggingInterceptor(logger::debug)
                interceptor.setLevel(HttpLoggingInterceptor.Level.BASIC)
                addNetworkInterceptor(interceptor)
            }
        }
        .build()
