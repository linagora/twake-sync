/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.servicedetection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI

abstract class TwakeSyncWellKnownCases {

    companion object {
        const val EMAIL_DOMAIN = "example.com"
        const val DAV_HOST = "dav.example.com"
        const val APP_ACCOUNTS = "https://sso.example.com/deviceaccounts"
        const val TWAKE_EMAIL_DOMAIN = "twake.app"
        const val TWAKE_DAV_HOST = "dav.twake.app"
        const val TWAKE_APP_ACCOUNTS = "https://sso.twake.app/deviceaccounts"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }

    @Test
    fun path_isTwakeConfiguration() {
        assertEquals("/.well-known/twake-configuration", TwakeSyncWellKnown.PATH)
    }

    @Test
    fun parse_httpsAbsoluteUrl_returnsUrl() {
        assertEquals(
            Url(APP_ACCOUNTS),
            TwakeSyncWellKnown.parse("""{"app_accounts_url":"$APP_ACCOUNTS"}""")
        )
    }

    @Test
    fun parse_trimsWhitespaceAroundUrl() {
        assertEquals(
            Url(APP_ACCOUNTS),
            TwakeSyncWellKnown.parse(
                """{"app_accounts_url":"  $APP_ACCOUNTS  "}"""
            )
        )
    }

    @Test
    fun parse_ignoresUnknownKeys_includingStagingTemplate() {
        assertEquals(
            Url("https://dav.twake.app/accounts"),
            TwakeSyncWellKnown.parse(
                """{"app_accounts_url":"https://dav.twake.app/accounts","extra":true}"""
            )
        )
        assertEquals(
            Url("https://sign-up.stg.lin-saas.com/manage-app-accounts"),
            TwakeSyncWellKnown.parse(
                """{"twake-flagship-login-uri":"https://manager-int.cozycloud.cc/linagora/twake_stg","app_accounts_url":"https://sign-up.stg.lin-saas.com/manage-app-accounts"}"""
            )
        )
    }

    @Test
    fun parse_keepsPathQueryAndPort() {
        assertEquals(
            Url("https://sso.example.com:8443/manage-app-accounts?next=/sync#accounts"),
            TwakeSyncWellKnown.parse(
                """{"app_accounts_url":"https://sso.example.com:8443/manage-app-accounts?next=/sync#accounts"}"""
            )
        )
    }

    @Test
    fun parse_emptyOrMissing_returnsNull() {
        assertNull(TwakeSyncWellKnown.parse("""{}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":""}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"   "}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":null}"""))
    }

    @Test
    fun parse_notJson_returnsNull() {
        assertNull(TwakeSyncWellKnown.parse("not-json"))
        assertNull(TwakeSyncWellKnown.parse(""))
        assertNull(TwakeSyncWellKnown.parse("[]"))
        assertNull(TwakeSyncWellKnown.parse("<html>nope</html>"))
    }

    @Test
    fun parse_nonStringAppAccountsUrl_returnsNull() {
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":true}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":123}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":{"href":"$APP_ACCOUNTS"}}"""))
    }

    @Test
    fun parse_rejectsNonHttpsSchemes() {
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"http://sso.example.com/x"}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"javascript:alert(1)"}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"data:text/html,hi"}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"ftp://sso.example.com/x"}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"file:///etc/passwd"}"""))
    }

    @Test
    fun parse_rejectsRelativeAndHostlessUrls() {
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"/manage-app-accounts"}"""))
        assertNull(TwakeSyncWellKnown.parse("""{"app_accounts_url":"sso.example.com/manage"}"""))
    }

    @Test
    fun parse_keepsHttpsUrlOnAnyHost() {
        assertEquals(
            Url("https://phish.example/login"),
            TwakeSyncWellKnown.parse("""{"app_accounts_url":"https://phish.example/login"}""")
        )
        assertEquals(
            Url("https://sign-up.stg.lin-saas.com/manage-app-accounts"),
            TwakeSyncWellKnown.parse(
                """{"app_accounts_url":"https://sign-up.stg.lin-saas.com/manage-app-accounts"}"""
            )
        )
        assertEquals(
            Url("https://dav.example.com/accounts"),
            TwakeSyncWellKnown.parse("""{"app_accounts_url":"https://dav.example.com/accounts"}""")
        )
    }

    @Test
    fun fetch_twakeApp_usesDavTwakeAppWithoutAuthorization() = runTest {
        val requested = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            auth += request.headers[HttpHeaders.Authorization]
            respond("""{"app_accounts_url":"$TWAKE_APP_ACCOUNTS"}""", HttpStatusCode.OK, jsonHeaders)
        }
        HttpClient(engine).use { client ->
            assertEquals(Url(TWAKE_APP_ACCOUNTS), TwakeSyncWellKnown.fetch(client, TWAKE_EMAIL_DOMAIN))
        }
        assertEquals(listOf("GET $TWAKE_DAV_HOST${TwakeSyncWellKnown.PATH}"), requested)
        assertTrue(auth.all { it.isNullOrEmpty() })
    }

    @Test
    fun fetch_davTwakeAppEmail_doesNotDoublePrefix() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.url.host}${request.url.encodedPath}"
            respond(
                """{"app_accounts_url":"https://dav.twake.app/accounts"}""",
                HttpStatusCode.OK,
                jsonHeaders
            )
        }
        HttpClient(engine).use { client ->
            assertEquals(
                Url("https://dav.twake.app/accounts"),
                TwakeSyncWellKnown.fetch(client, TWAKE_DAV_HOST)
            )
        }
        assertEquals(listOf("$TWAKE_DAV_HOST${TwakeSyncWellKnown.PATH}"), requested)
        assertTrue(requested.none { it.startsWith("dav.dav.") })
    }

    @Test
    fun fetch_anyEmailDomain_usesDavTwakeApp() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.url.host}${request.url.encodedPath}"
            respond("""{"app_accounts_url":"$TWAKE_APP_ACCOUNTS"}""", HttpStatusCode.OK, jsonHeaders)
        }
        HttpClient(engine).use { client ->
            assertEquals(Url(TWAKE_APP_ACCOUNTS), TwakeSyncWellKnown.fetch(client, EMAIL_DOMAIN))
            assertEquals(Url(TWAKE_APP_ACCOUNTS), TwakeSyncWellKnown.fetch(client, "linagora.com"))
            assertEquals(Url(TWAKE_APP_ACCOUNTS), TwakeSyncWellKnown.fetch(client, "stg.lin-saas.com"))
        }
        assertEquals(
            List(3) { "$TWAKE_DAV_HOST${TwakeSyncWellKnown.PATH}" },
            requested
        )
    }

    @Test
    fun fetch_emailDomainDavPolicy_usesDavPrefixedHost() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            respond("""{"app_accounts_url":"$APP_ACCOUNTS"}""", HttpStatusCode.OK, jsonHeaders)
        }
        HttpClient(engine).use { client ->
            assertEquals(
                Url(APP_ACCOUNTS),
                TwakeSyncWellKnown.fetch(client, EMAIL_DOMAIN, WellKnownHostPolicy.EmailDomainDav)
            )
        }
        assertEquals(listOf("GET $DAV_HOST${TwakeSyncWellKnown.PATH}"), requested)
    }

    @Test
    fun fetch_401_returnsNull() = runTest {
        assertNull(fetchStatus(HttpStatusCode.Unauthorized))
    }

    @Test
    fun fetch_404_returnsNull() = runTest {
        assertNull(fetchStatus(HttpStatusCode.NotFound))
    }

    @Test
    fun fetch_204_returnsNull() = runTest {
        assertNull(fetchStatus(HttpStatusCode.NoContent))
    }

    @Test
    fun fetch_500_returnsNull() = runTest {
        assertNull(fetchStatus(HttpStatusCode.InternalServerError))
    }

    @Test
    fun fetch_302WithoutFollow_returnsNull() = runTest {
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, APP_ACCOUNTS)
            )
        }
        HttpClient(engine) { followRedirects = false }.use { client ->
            assertNull(TwakeSyncWellKnown.fetch(client, TWAKE_EMAIL_DOMAIN))
        }
    }

    @Test
    fun fetch_redirectOffHost_returnsNull() = runTest {
        val requestedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            if (request.url.host == TWAKE_DAV_HOST)
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://phish.example/login")
                )
            else
                respond("""{"app_accounts_url":"$TWAKE_APP_ACCOUNTS"}""", HttpStatusCode.OK, jsonHeaders)
        }
        HttpClient(engine).use { client ->
            assertNull(TwakeSyncWellKnown.fetch(client, TWAKE_EMAIL_DOMAIN))
        }
        assertTrue(requestedHosts.first() == TWAKE_DAV_HOST)
    }

    @Test
    fun fetch_200HttpsUrl_returnsUrlForAnyHost() = runTest {
        val engine = MockEngine {
            respond(
                """{"app_accounts_url":"https://phish.example/login"}""",
                HttpStatusCode.OK,
                jsonHeaders
            )
        }
        HttpClient(engine).use { client ->
            assertEquals(
                Url("https://phish.example/login"),
                TwakeSyncWellKnown.fetch(client, TWAKE_EMAIL_DOMAIN)
            )
            assertEquals(
                Url("https://phish.example/login"),
                TwakeSyncWellKnown.fetch(client, "evil.com")
            )
        }
    }

    @Test
    fun fetch_200InvalidJson_returnsNull() = runTest {
        val engine = MockEngine {
            respond("not-json", HttpStatusCode.OK, jsonHeaders)
        }
        HttpClient(engine).use { client ->
            assertNull(TwakeSyncWellKnown.fetch(client, TWAKE_EMAIL_DOMAIN))
        }
    }

    @Test
    fun fetch_200MissingKey_returnsNull() = runTest {
        val engine = MockEngine {
            respond("""{"twake-flagship-login-uri":"https://sso.example.com"}""", HttpStatusCode.OK, jsonHeaders)
        }
        HttpClient(engine).use { client ->
            assertNull(TwakeSyncWellKnown.fetch(client, TWAKE_EMAIL_DOMAIN))
        }
    }

    @Test
    fun fetch_networkError_returnsNull() = runTest {
        val engine = MockEngine {
            throw IOException("unreachable")
        }
        HttpClient(engine).use { client ->
            assertNull(TwakeSyncWellKnown.fetch(client, TWAKE_EMAIL_DOMAIN))
        }
    }

    @Test
    fun fetch_urlLoginHasNoEmailDomain() {
        assertNull(DavDiscoveryHosts.emailDomain(URI("https://cloud.example.com/dav")))
        assertNull(DavDiscoveryHosts.emailDomain(URI("http://dav.example.com/")))
        assertEquals(EMAIL_DOMAIN, DavDiscoveryHosts.emailDomain(URI("mailto:alice@$EMAIL_DOMAIN")))
    }

    private suspend fun fetchStatus(status: HttpStatusCode): Url? {
        val engine = MockEngine { respond("", status) }
        return HttpClient(engine).use { TwakeSyncWellKnown.fetch(it, TWAKE_EMAIL_DOMAIN) }
    }

}
