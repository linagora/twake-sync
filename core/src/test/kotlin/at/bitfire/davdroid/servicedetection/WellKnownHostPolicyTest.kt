/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.servicedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WellKnownHostPolicyTest {

    @Test
    fun current_isTwakeApp() {
        assertSame(WellKnownHostPolicy.TwakeApp, WellKnownHostPolicy.Current)
    }

    @Test
    fun twakeApp_anyEmailDomain_usesDavTwakeApp() {
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("twake.app"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("TWAKE.APP"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("dav.twake.app"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("mail.twake.app"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("example.com"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("linagora.com"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("stg.lin-saas.com"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("twake.app.evil.com"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("nottwake.app"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.TwakeApp.hostFor("dav.example.com"))
    }

    @Test
    fun emailDomainDav_prefixesDavUnlessAlreadyPrefixed() {
        assertEquals("dav.twake.app", WellKnownHostPolicy.EmailDomainDav.hostFor("twake.app"))
        assertEquals("dav.twake.app", WellKnownHostPolicy.EmailDomainDav.hostFor("dav.twake.app"))
        assertEquals("dav.example.com", WellKnownHostPolicy.EmailDomainDav.hostFor("example.com"))
        assertEquals("dav.stg.lin-saas.com", WellKnownHostPolicy.EmailDomainDav.hostFor("stg.lin-saas.com"))
        assertEquals("dav.linagora.com", WellKnownHostPolicy.EmailDomainDav.hostFor("linagora.com"))
    }

}
