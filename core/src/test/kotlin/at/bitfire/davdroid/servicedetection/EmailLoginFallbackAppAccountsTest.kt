/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.servicedetection

import at.bitfire.davdroid.network.DnsRecordResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.xbill.DNS.DClass
import org.xbill.DNS.Name
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Type
import java.net.URI
import java.net.UnknownHostException
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class EmailLoginFallbackAppAccountsTest {

    companion object {
        const val EMAIL_DOMAIN = "twake.app"
        const val FALLBACK_HOST = "dav.twake.app"
        const val APP_ACCOUNTS = "https://sso.twake.app/deviceaccounts"
        const val CUSTOM_EMAIL_DOMAIN = "example.com"
        const val CUSTOM_FALLBACK_HOST = "dav.example.com"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val unauthorizedHeaders = headersOf(HttpHeaders.WWWAuthenticate, """Basic realm="dav"""")
    }

    @Test
    fun shouldFetchTwakeWellKnown_onlyOnTwakeDav401WhenNothingFound() {
        fun config(
            encountered401: Boolean = false,
            encounteredUnreachableHost: Boolean = false,
            encountered401OnTwakeDav: Boolean = false,
            hasCalDav: Boolean = false
        ) = DavResourceFinder.Configuration(
            cardDAV = null,
            calDAV = if (hasCalDav) DavResourceFinder.Configuration.ServiceInfo() else null,
            encountered401 = encountered401,
            encounteredUnreachableHost = encounteredUnreachableHost,
            encountered401OnTwakeDav = encountered401OnTwakeDav
        )

        assertTrue(config(encountered401OnTwakeDav = true).shouldFetchTwakeWellKnown())
        assertFalse(config(encountered401 = true).shouldFetchTwakeWellKnown())
        assertFalse(config(encounteredUnreachableHost = true).shouldFetchTwakeWellKnown())
        assertFalse(config().shouldFetchTwakeWellKnown())
        assertFalse(config(encountered401OnTwakeDav = true, hasCalDav = true).shouldFetchTwakeWellKnown())
    }

    @Test
    fun emailLogin_fallback401_keepsSameSiteAppAccountsUrl() = runTest {
        val requested = mutableListOf<String>()
        val authOnWellKnown = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val line = "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            requested += line
            if (request.url.encodedPath == TwakeSyncWellKnown.PATH)
                authOnWellKnown += request.headers[HttpHeaders.Authorization]
            when {
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method.value == "PROPFIND" && request.url.host == FALLBACK_HOST ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine)

        assertEquals(Url(APP_ACCOUNTS), config.appAccountsUrl)
        assertTrue(config.encountered401)
        assertTrue(config.encountered401OnTwakeDav)
        assertNull(config.calDAV)
        assertNull(config.cardDAV)
        val wellKnown = "GET $FALLBACK_HOST${TwakeSyncWellKnown.PATH}"
        assertTrue(requested.contains(wellKnown))
        assertTrue(requested.indexOf(wellKnown) > 0)
        assertTrue(authOnWellKnown.all { it.isNullOrEmpty() })
        val discovery = requested.filterNot { it == wellKnown }
        val discoveryHosts = discovery.map { it.substringAfter(' ').substringBefore('/') }.distinct()
        assertEquals(listOf(EMAIL_DOMAIN, FALLBACK_HOST), discoveryHosts)
        assertTrue(discovery.any { it.startsWith("PROPFIND $FALLBACK_HOST") })
    }

    @Test
    fun emailLogin_wellKnown401_fallback401_hasNoAppAccountsUrl() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                request.method.value == "PROPFIND" && request.url.host == FALLBACK_HOST ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine)

        assertNull(config.appAccountsUrl)
        assertTrue(config.encountered401)
    }

    @Test
    fun emailLogin_fallbackFindsPrincipal_skipsWellKnown() = runTest {
        val requested = mutableListOf<String>()
        val xmlHeaders = headersOf(HttpHeaders.ContentType, "application/xml; charset=UTF-8")
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method == HttpMethod.Options && request.url.host == FALLBACK_HOST ->
                    respond("", HttpStatusCode.OK, headersOf("DAV", "calendar-access, addressbook"))
                request.method.value == "PROPFIND" &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == "/.well-known/caldav" ->
                    respond(
                        "<multistatus xmlns='DAV:'>" +
                            "<response><href>/.well-known/caldav</href><propstat><prop>" +
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>" +
                            "</prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>",
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                request.method.value == "PROPFIND" &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == "/.well-known/carddav" ->
                    respond(
                        "<multistatus xmlns='DAV:'>" +
                            "<response><href>/.well-known/carddav</href><propstat><prop>" +
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>" +
                            "</prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>",
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine)

        assertNull(config.appAccountsUrl)
        assertFalse(config.encountered401)
        assertEquals(Url("https://$FALLBACK_HOST/principals/alice"), config.calDAV?.principal)
        assertTrue(requested.none { it.contains(TwakeSyncWellKnown.PATH) })
    }

    @Test
    fun emailLogin_fallback404_not401_skipsWellKnown() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            respond("", HttpStatusCode.NotFound)
        }

        val config = detectEmailLogin(engine)

        assertNull(config.appAccountsUrl)
        assertFalse(config.encountered401)
        assertFalse(config.encounteredUnreachableHost)
        assertNull(config.calDAV)
        assertNull(config.cardDAV)
        assertTrue(requested.none { it.contains(TwakeSyncWellKnown.PATH) })
    }

    @Test
    fun emailLogin_davFallbackUnknownHost_twakeDav401_fetchesWellKnown() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.url.host == CUSTOM_FALLBACK_HOST ->
                    throw UnknownHostException(CUSTOM_FALLBACK_HOST)
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method.value == "PROPFIND" && request.url.host == FALLBACK_HOST ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine, emailDomain = CUSTOM_EMAIL_DOMAIN)

        assertEquals(Url(APP_ACCOUNTS), config.appAccountsUrl)
        assertTrue(config.encountered401)
        assertTrue(config.encountered401OnTwakeDav)
        assertTrue(config.encounteredUnreachableHost)
        assertNull(config.calDAV)
        assertNull(config.cardDAV)
        val wellKnown = "GET $FALLBACK_HOST${TwakeSyncWellKnown.PATH}"
        assertTrue(requested.contains(wellKnown))
        assertTrue(requested.any { it.startsWith("PROPFIND $CUSTOM_FALLBACK_HOST") })
        assertTrue(requested.any { it.startsWith("PROPFIND $FALLBACK_HOST") })
        assertTrue(requested.none { it == "GET $CUSTOM_FALLBACK_HOST${TwakeSyncWellKnown.PATH}" })
    }

    @Test
    fun emailLogin_twakeDavUnknownHost_skipsWellKnown() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath != TwakeSyncWellKnown.PATH ->
                    throw UnknownHostException(FALLBACK_HOST)
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine, emailDomain = CUSTOM_EMAIL_DOMAIN)

        assertNull(config.appAccountsUrl)
        assertFalse(config.encountered401OnTwakeDav)
        assertTrue(config.encounteredUnreachableHost)
        assertTrue(requested.none { it.contains(TwakeSyncWellKnown.PATH) })
        assertTrue(requested.any { it.startsWith("PROPFIND $FALLBACK_HOST") })
    }

    @Test
    fun emailLogin_emailDomainUnknown_davFallbackFindsPrincipal_skipsWellKnown() = runTest {
        val requested = mutableListOf<String>()
        val xmlHeaders = headersOf(HttpHeaders.ContentType, "application/xml; charset=UTF-8")
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.url.host == CUSTOM_EMAIL_DOMAIN ->
                    throw UnknownHostException(CUSTOM_EMAIL_DOMAIN)
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method == HttpMethod.Options && request.url.host == CUSTOM_FALLBACK_HOST ->
                    respond("", HttpStatusCode.OK, headersOf("DAV", "calendar-access, addressbook"))
                request.method.value == "PROPFIND" &&
                    request.url.host == CUSTOM_FALLBACK_HOST &&
                    (request.url.encodedPath == "/.well-known/caldav" ||
                        request.url.encodedPath == "/.well-known/carddav") ->
                    respond(
                        "<multistatus xmlns='DAV:'>" +
                            "<response><href>${request.url.encodedPath}</href><propstat><prop>" +
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>" +
                            "</prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>",
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine, emailDomain = CUSTOM_EMAIL_DOMAIN)

        assertNull(config.appAccountsUrl)
        assertTrue(config.encounteredUnreachableHost)
        assertEquals(Url("https://$CUSTOM_FALLBACK_HOST/principals/alice"), config.calDAV?.principal)
        assertTrue(requested.none { it.contains(TwakeSyncWellKnown.PATH) })
    }

    @Test
    fun emailLogin_httpsAppAccountsUrl_keptEvenWhenFallback401() = runTest {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"https://phish.example/login"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method.value == "PROPFIND" && request.url.host == FALLBACK_HOST ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine)

        assertEquals(Url("https://phish.example/login"), config.appAccountsUrl)
        assertTrue(config.encountered401)
    }

    @Test
    fun emailLogin_wellKnown404_after401_hasNoAppAccountsUrl() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond("", HttpStatusCode.NotFound)
                request.method.value == "PROPFIND" && request.url.host == FALLBACK_HOST ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine)

        assertNull(config.appAccountsUrl)
        assertTrue(config.encountered401)
        assertTrue(requested.contains("GET $FALLBACK_HOST${TwakeSyncWellKnown.PATH}"))
        assertTrue(requested.indexOf("GET $FALLBACK_HOST${TwakeSyncWellKnown.PATH}") > 0)
    }

    @Test
    fun emailLogin_srv401_fetchesWellKnownOnDavEmailDomainNotSrvTarget() = runTest {
        val requested = mutableListOf<String>()
        val srv = SRVRecord(
            Name.fromString("_caldavs._tcp.twake.app."),
            DClass.IN, 3600, 0, 1, 443, Name.fromString("caldav.twake.app.")
        )
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method.value == "PROPFIND" ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine, dns = dnsResolver(srv = arrayOf(srv)))

        assertEquals(Url(APP_ACCOUNTS), config.appAccountsUrl)
        assertTrue(config.encountered401)
        val wellKnown = "GET $FALLBACK_HOST${TwakeSyncWellKnown.PATH}"
        assertTrue(requested.contains(wellKnown))
        assertTrue(requested.indexOf(wellKnown) > 0)
        assertTrue(requested.none { it == "GET caldav.twake.app${TwakeSyncWellKnown.PATH}" })
        assertTrue(requested.any { it.startsWith("PROPFIND caldav.twake.app") })
    }

    @Test
    fun emailLogin_customDomain401_fetchesDavTwakeAppAndKeepsJson() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method.value == "PROPFIND" &&
                    (request.url.host == CUSTOM_FALLBACK_HOST || request.url.host == FALLBACK_HOST) ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine, emailDomain = CUSTOM_EMAIL_DOMAIN)

        assertEquals(Url(APP_ACCOUNTS), config.appAccountsUrl)
        assertTrue(config.encountered401)
        assertTrue(config.encountered401OnTwakeDav)
        val wellKnown = "GET $FALLBACK_HOST${TwakeSyncWellKnown.PATH}"
        assertTrue(requested.contains(wellKnown))
        assertTrue(requested.none { it == "GET $CUSTOM_FALLBACK_HOST${TwakeSyncWellKnown.PATH}" })
        assertTrue(requested.any { it.startsWith("PROPFIND $CUSTOM_FALLBACK_HOST") })
        assertTrue(requested.any { it.startsWith("PROPFIND $FALLBACK_HOST") })
    }

    @Test
    fun emailLogin_customDomain401_twakeDav404_skipsWellKnown() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.method == HttpMethod.Get &&
                    request.url.host == FALLBACK_HOST &&
                    request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                request.method.value == "PROPFIND" && request.url.host == CUSTOM_FALLBACK_HOST ->
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val config = detectEmailLogin(engine, emailDomain = CUSTOM_EMAIL_DOMAIN)

        assertNull(config.appAccountsUrl)
        assertTrue(config.encountered401)
        assertFalse(config.encountered401OnTwakeDav)
        assertTrue(requested.none { it.contains(TwakeSyncWellKnown.PATH) })
        assertTrue(requested.any { it.startsWith("PROPFIND $FALLBACK_HOST") })
    }

    @Test
    fun httpsLogin_401_skipsWellKnownEvenWhenJsonIsServed() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == TwakeSyncWellKnown.PATH ->
                    respond(
                        """{"app_accounts_url":"$APP_ACCOUNTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders
                    )
                else -> respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
            }
        }

        HttpClient(engine).use { client ->
            val baseUri = URI("https://cloud.example.com/dav")
            val finder = DavResourceFinder(
                baseUri,
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver()
            )
            val detected = finder.findInitialConfiguration()
            val appAccountsUrl = if (detected.shouldFetchTwakeWellKnown())
                DavDiscoveryHosts.emailDomain(baseUri)?.let { TwakeSyncWellKnown.fetch(client, it) }
            else
                null
            assertTrue(detected.encountered401)
            assertNull(appAccountsUrl)
        }

        assertTrue(requested.none { it.contains(TwakeSyncWellKnown.PATH) })
        assertTrue(requested.none { it.contains("dav.cloud.example.com") })
        assertTrue(requested.none { it.contains("dav.example.com") })
        assertTrue(requested.none { it.contains("dav.twake.app") })
    }

    @Test
    fun httpsLogin_skipsWellKnownAndDavFallback() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            respond("", HttpStatusCode.NotFound)
        }

        HttpClient(engine).use { client ->
            val domain = DavDiscoveryHosts.emailDomain(URI("https://cloud.example.com/dav"))
            assertNull(domain)
            val finder = DavResourceFinder(
                URI("https://cloud.example.com/dav"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver()
            )
            val detected = finder.findInitialConfiguration()
            val config = detected.copy(
                appAccountsUrl = if (detected.shouldFetchTwakeWellKnown())
                    domain?.let { TwakeSyncWellKnown.fetch(client, it) }
                else
                    null
            )
            assertNull(config.appAccountsUrl)
            assertFalse(config.encountered401)
        }

        assertTrue(requested.none { it.contains(TwakeSyncWellKnown.PATH) })
        assertTrue(requested.none { it.contains("dav.cloud.example.com") })
        assertTrue(requested.none { it.contains("dav.example.com") })
        assertTrue(requested.none { it.contains("dav.twake.app") })
    }

    private suspend fun detectEmailLogin(
        engine: MockEngine,
        dns: DnsRecordResolver = dnsResolver(),
        emailDomain: String = EMAIL_DOMAIN
    ): DavResourceFinder.Configuration {
        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@$emailDomain"),
                null,
                client,
                Logger.getLogger("test"),
                dns
            )
            val detected = finder.findInitialConfiguration()
            val appAccountsUrl = if (detected.shouldFetchTwakeWellKnown())
                TwakeSyncWellKnown.fetch(client, emailDomain)
            else
                null
            return detected.copy(appAccountsUrl = appAccountsUrl)
        }
    }

    private fun dnsResolver(srv: Array<org.xbill.DNS.Record> = emptyArray()): DnsRecordResolver {
        val dns = mockk<DnsRecordResolver>()
        coEvery { dns.resolve(any(), Type.SRV) } returns srv
        coEvery { dns.resolve(any(), Type.TXT) } returns emptyArray()
        every { dns.bestSRVRecord(any()) } answers {
            firstArg<Array<org.xbill.DNS.Record>>().filterIsInstance<SRVRecord>().firstOrNull()
        }
        every { dns.pathsFromTXTRecords(any()) } returns emptyList()
        return dns
    }

}
