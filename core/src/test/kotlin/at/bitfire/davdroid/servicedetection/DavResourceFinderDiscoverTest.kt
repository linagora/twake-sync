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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.xbill.DNS.DClass
import org.xbill.DNS.Name
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type
import java.net.URI
import java.net.UnknownHostException
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DavResourceFinderDiscoverTest {

    companion object {
        val xmlHeaders = headersOf(HttpHeaders.ContentType, "application/xml; charset=UTF-8")

        fun multistatus(href: String, props: String) =
            "<multistatus xmlns='DAV:'>" +
                    "<response><href>$href</href><propstat><prop>$props</prop><status>HTTP/1.1 200 OK</status></propstat></response>" +
                    "</multistatus>"
    }

    @Test
    fun discoverPrincipalUrl_triesDavPrefixedHostAfterEmailDomain() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.url.host}${request.url.encodedPath}"
            when {
                request.method == HttpMethod.Options && request.url.host == "dav.twake.app" ->
                    respond("", HttpStatusCode.OK, headersOf("DAV", "calendar-access"))
                request.method.value == "PROPFIND" &&
                    request.url.host == "dav.twake.app" &&
                    request.url.encodedPath == "/.well-known/caldav" ->
                    respond(
                        multistatus(
                            "/.well-known/caldav",
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>"
                        ),
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@twake.app"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(srv = emptyArray(), txt = emptyArray())
            )
            assertEquals(
                Url("https://dav.twake.app/principals/alice"),
                finder.discoverPrincipalUrl("twake.app", DavResourceFinder.Service.CALDAV)
            )
        }

        val hostsInOrder = requested.map { it.substringBefore("/") }.distinct()
        assertEquals(listOf("twake.app", "dav.twake.app"), hostsInOrder)
        val twakeAppPaths = requested.filter { it.startsWith("twake.app") }.map { it.removePrefix("twake.app") }
        assertEquals(listOf("/.well-known/caldav", "/"), twakeAppPaths)
    }

    @Test
    fun discoverPrincipalUrl_httpsLogin_doesNotTryDavPrefixedHost() = runTest {
        val requestedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            respond("", HttpStatusCode.NotFound)
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("https://cloud.example.com/remote.php/dav"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(srv = emptyArray(), txt = emptyArray())
            )
            assertNull(
                finder.discoverPrincipalUrl("cloud.example.com", DavResourceFinder.Service.CALDAV)
            )
        }

        assertTrue(requestedHosts.isNotEmpty())
        assertTrue(requestedHosts.all { it == "cloud.example.com" })
        assertTrue(requestedHosts.none { it == "dav.cloud.example.com" })
        assertTrue(requestedHosts.none { it == "dav.twake.app" })
    }

    @Test
    fun discoverPrincipalUrl_doesNotTryFallbackWhenFirstHostFindsPrincipal() = runTest {
        val requestedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            when {
                request.method == HttpMethod.Options && request.url.host == "twake.app" ->
                    respond("", HttpStatusCode.OK, headersOf("DAV", "calendar-access"))
                request.method.value == "PROPFIND" &&
                    request.url.host == "twake.app" &&
                    request.url.encodedPath == "/.well-known/caldav" ->
                    respond(
                        multistatus(
                            "/.well-known/caldav",
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>"
                        ),
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@twake.app"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(srv = emptyArray(), txt = emptyArray())
            )
            assertEquals(
                Url("https://twake.app/principals/alice"),
                finder.discoverPrincipalUrl("twake.app", DavResourceFinder.Service.CALDAV)
            )
        }

        assertTrue(requestedHosts.all { it == "twake.app" })
        assertTrue(requestedHosts.none { it == "dav.twake.app" })
    }

    @Test
    fun discoverPrincipalUrl_triesDavPrefixedHostAfterNonDavSrvTarget() = runTest {
        val requestedHosts = mutableListOf<String>()
        val srv = SRVRecord(
            Name.fromString("_caldavs._tcp.example.com."),
            DClass.IN, 3600, 0, 1, 443, Name.fromString("caldav.example.com.")
        )
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            when {
                request.method == HttpMethod.Options && request.url.host == "dav.example.com" ->
                    respond("", HttpStatusCode.OK, headersOf("DAV", "calendar-access"))
                request.method.value == "PROPFIND" &&
                    request.url.host == "dav.example.com" &&
                    request.url.encodedPath == "/.well-known/caldav" ->
                    respond(
                        multistatus(
                            "/.well-known/caldav",
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>"
                        ),
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@example.com"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(srv = arrayOf(srv), txt = emptyArray())
            )
            assertEquals(
                Url("https://dav.example.com/principals/alice"),
                finder.discoverPrincipalUrl("example.com", DavResourceFinder.Service.CALDAV)
            )
        }

        assertEquals(listOf("caldav.example.com", "dav.example.com"), requestedHosts.distinct())
    }

    @Test
    fun discoverPrincipalUrl_skipsRemainingPathsWhenHostUnreachable() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.url.host}${request.url.encodedPath}"
            if (request.url.host == "twake.app")
                throw UnknownHostException("twake.app")
            when {
                request.method == HttpMethod.Options && request.url.host == "dav.twake.app" ->
                    respond("", HttpStatusCode.OK, headersOf("DAV", "calendar-access"))
                request.method.value == "PROPFIND" &&
                    request.url.host == "dav.twake.app" &&
                    request.url.encodedPath == "/.well-known/caldav" ->
                    respond(
                        multistatus(
                            "/.well-known/caldav",
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>"
                        ),
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@twake.app"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(srv = emptyArray(), txt = emptyArray())
            )
            assertEquals(
                Url("https://dav.twake.app/principals/alice"),
                finder.discoverPrincipalUrl("twake.app", DavResourceFinder.Service.CALDAV)
            )
        }

        val twakeAppPaths = requested.filter { it.startsWith("twake.app") }.map { it.removePrefix("twake.app") }
        assertEquals(listOf("/.well-known/caldav"), twakeAppPaths)
        assertTrue(requested.any { it.startsWith("dav.twake.app") })
    }

    @Test
    fun discoverPrincipalUrl_doesNotTryDavDavWhenSrvAlreadyDavPrefixed() = runTest {
        val requestedHosts = mutableListOf<String>()
        val srv = SRVRecord(
            Name.fromString("_caldavs._tcp.example.com."),
            DClass.IN, 3600, 0, 1, 443, Name.fromString("dav.example.com.")
        )
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            respond("", HttpStatusCode.NotFound)
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@example.com"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(srv = arrayOf(srv), txt = emptyArray())
            )
            assertNull(finder.discoverPrincipalUrl("example.com", DavResourceFinder.Service.CALDAV))
        }

        assertEquals(listOf("dav.example.com", "dav.twake.app"), requestedHosts.distinct())
        assertTrue(requestedHosts.none { it == "dav.dav.example.com" })
    }

    @Test
    fun discoverPrincipalUrl_emailLogin_triesDavTwakeAppAfterCustomDavHost() = runTest {
        val requestedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            when {
                request.method == HttpMethod.Options && request.url.host == "dav.twake.app" ->
                    respond("", HttpStatusCode.OK, headersOf("DAV", "calendar-access"))
                request.method.value == "PROPFIND" &&
                    request.url.host == "dav.twake.app" &&
                    request.url.encodedPath == "/.well-known/caldav" ->
                    respond(
                        multistatus(
                            "/.well-known/caldav",
                            "<current-user-principal><href>/principals/alice</href></current-user-principal>"
                        ),
                        HttpStatusCode.MultiStatus,
                        xmlHeaders
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@vsora.com"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(srv = emptyArray(), txt = emptyArray())
            )
            assertEquals(
                Url("https://dav.twake.app/principals/alice"),
                finder.discoverPrincipalUrl("vsora.com", DavResourceFinder.Service.CALDAV)
            )
        }

        assertEquals(listOf("vsora.com", "dav.vsora.com", "dav.twake.app"), requestedHosts.distinct())
    }

    @Test
    fun discoverPrincipalUrl_keepsTxtBeforeWellKnownOnFirstHost() = runTest {
        val requested = mutableListOf<String>()
        val txt = TXTRecord(
            Name.fromString("_caldavs._tcp.twake.app."),
            DClass.IN, 3600, listOf("path=/calendars")
        )
        val engine = MockEngine { request ->
            requested += "${request.url.host}${request.url.encodedPath}"
            respond("", HttpStatusCode.NotFound)
        }

        HttpClient(engine).use { client ->
            val finder = DavResourceFinder(
                URI("mailto:alice@twake.app"),
                null,
                client,
                Logger.getLogger("test"),
                dnsResolver(
                    srv = emptyArray(),
                    txt = arrayOf(txt),
                    txtPaths = listOf("/calendars")
                )
            )
            assertNull(finder.discoverPrincipalUrl("twake.app", DavResourceFinder.Service.CALDAV))
        }

        val firstHostPaths = requested.filter { it.startsWith("twake.app") }.map { it.removePrefix("twake.app") }
        assertEquals(
            listOf("/calendars", "/.well-known/caldav", "/"),
            firstHostPaths
        )
        val fallbackPaths = requested.filter { it.startsWith("dav.twake.app") }.map { it.removePrefix("dav.twake.app") }
        assertEquals(
            listOf("/calendars", "/.well-known/caldav", "/"),
            fallbackPaths
        )
    }

    private fun dnsResolver(
        srv: Array<org.xbill.DNS.Record>,
        txt: Array<org.xbill.DNS.Record>,
        txtPaths: List<String> = emptyList()
    ): DnsRecordResolver {
        val dns = mockk<DnsRecordResolver>()
        coEvery { dns.resolve(any(), Type.SRV) } returns srv
        coEvery { dns.resolve(any(), Type.TXT) } returns txt
        every { dns.bestSRVRecord(any()) } answers {
            val records = firstArg<Array<org.xbill.DNS.Record>>()
            records.filterIsInstance<SRVRecord>().firstOrNull()
        }
        every { dns.pathsFromTXTRecords(any()) } returns txtPaths
        return dns
    }

}
