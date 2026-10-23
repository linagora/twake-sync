/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.servicedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

class DavDiscoveryHostsTest {

    @Test
    fun isEmailLogin_mailto_returnsTrue() {
        assertTrue(DavDiscoveryHosts.isEmailLogin(URI("mailto:alice@twake.app")))
        assertTrue(DavDiscoveryHosts.isEmailLogin(URI("MAILTO:alice@twake.app")))
    }

    @Test
    fun isEmailLogin_https_returnsFalse() {
        assertFalse(DavDiscoveryHosts.isEmailLogin(URI("https://cloud.example.com/remote.php/dav")))
        assertFalse(DavDiscoveryHosts.isEmailLogin(URI("http://cloud.example.com/")))
    }

    @Test
    fun fallbackHost_emailDomainWithoutSrv_returnsDavPrefixedHost() {
        assertEquals(
            "dav.twake.app",
            DavDiscoveryHosts.fallbackHost("twake.app", "twake.app", emailLogin = true)
        )
    }

    @Test
    fun fallbackHost_httpsLogin_returnsNull() {
        assertNull(
            DavDiscoveryHosts.fallbackHost("cloud.example.com", "cloud.example.com", emailLogin = false)
        )
    }

    @Test
    fun fallbackHost_srvAlreadyDavPrefixed_returnsNull() {
        assertNull(
            DavDiscoveryHosts.fallbackHost("linagora.com", "dav.linagora.com", emailLogin = true)
        )
    }

    @Test
    fun fallbackHost_domainAlreadyDavPrefixed_returnsNull() {
        assertNull(
            DavDiscoveryHosts.fallbackHost("dav.twake.app", "dav.twake.app", emailLogin = true)
        )
    }

    @Test
    fun fallbackHost_comparesHostsCaseInsensitively() {
        assertNull(
            DavDiscoveryHosts.fallbackHost("linagora.com", "DAV.LINAGORA.COM", emailLogin = true)
        )
        assertNull(
            DavDiscoveryHosts.fallbackHost("DAV.twake.app", "ignored.example", emailLogin = true)
        )
        assertEquals(
            "dav.Example.COM",
            DavDiscoveryHosts.fallbackHost("Example.COM", "Example.COM", emailLogin = true)
        )
    }

    @Test
    fun emailDomain_mailto_returnsDomain() {
        assertEquals("twake.app", DavDiscoveryHosts.emailDomain(URI("mailto:alice@twake.app")))
        assertNull(DavDiscoveryHosts.emailDomain(URI("https://dav.twake.app/")))
        assertNull(DavDiscoveryHosts.emailDomain(URI("mailto:no-at-sign")))
    }

    @Test
    fun twakeDavHost_emailLogin_skipsWhenAlreadyTried() {
        assertEquals(
            "dav.twake.app",
            DavDiscoveryHosts.twakeDavHost(listOf("vsora.com", "dav.vsora.com"), emailLogin = true)
        )
        assertNull(
            DavDiscoveryHosts.twakeDavHost(listOf("twake.app", "dav.twake.app"), emailLogin = true)
        )
        assertNull(
            DavDiscoveryHosts.twakeDavHost(listOf("DAV.TWAKE.APP"), emailLogin = true)
        )
        assertNull(
            DavDiscoveryHosts.twakeDavHost(listOf("vsora.com"), emailLogin = false)
        )
    }

    @Test
    fun wellKnownHost_prefixesDavUnlessAlreadyPrefixed() {
        assertEquals("dav.twake.app", DavDiscoveryHosts.wellKnownHost("twake.app"))
        assertEquals("dav.twake.app", DavDiscoveryHosts.wellKnownHost("dav.twake.app"))
        assertEquals(
            "dav.testcalendar2.twake-demo.app",
            DavDiscoveryHosts.wellKnownHost("testcalendar2.twake-demo.app")
        )
    }

    @Test
    fun isUnreachableHostError_unknownHostAndConnect() {
        assertTrue(DavDiscoveryHosts.isUnreachableHostError(UnknownHostException("dav.example.com")))
        assertTrue(DavDiscoveryHosts.isUnreachableHostError(ConnectException("refused")))
        assertTrue(DavDiscoveryHosts.isUnreachableHostError(UnresolvedAddressException()))
        assertTrue(
            DavDiscoveryHosts.isUnreachableHostError(
                IOException("wrapped", UnknownHostException("dav.example.com"))
            )
        )
        assertFalse(DavDiscoveryHosts.isUnreachableHostError(SocketTimeoutException("timeout")))
        assertFalse(DavDiscoveryHosts.isUnreachableHostError(IOException("generic")))
    }

}
