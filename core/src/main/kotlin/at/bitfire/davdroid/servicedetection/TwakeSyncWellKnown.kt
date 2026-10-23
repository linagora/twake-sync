/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */
package at.bitfire.davdroid.servicedetection

import at.bitfire.davdroid.network.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TwakeSyncWellKnownDocument(
    @SerialName("app_accounts_url") val appAccountsUrl: String? = null
)

internal object TwakeSyncWellKnown {

    const val PATH = "/.well-known/twake-configuration"

    fun parse(body: String): Url? {
        val document = try {
            lenientJson.decodeFromString<TwakeSyncWellKnownDocument>(body)
        } catch (_: Exception) {
            return null
        }
        val raw = document.appAccountsUrl?.trim().orEmpty()
        if (raw.isEmpty())
            return null
        val url = try {
            Url(raw)
        } catch (_: Exception) {
            return null
        }
        if (!url.protocol.name.equals("https", ignoreCase = true))
            return null
        if (url.host.isBlank())
            return null
        return url
    }

    suspend fun fetch(
        httpClient: HttpClient,
        emailDomain: String,
        hostPolicy: WellKnownHostPolicy = WellKnownHostPolicy.Current
    ): Url? {
        val host = hostPolicy.hostFor(emailDomain) ?: return null
        val requestUrl = URLBuilder(
            protocol = URLProtocol.HTTPS,
            host = host
        ).apply {
            encodedPath = PATH
        }.build()
        return try {
            val response = httpClient.get(requestUrl)
            if (response.status.value != 200)
                return null
            if (!response.call.request.url.host.equals(host, ignoreCase = true))
                return null
            parse(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

}
