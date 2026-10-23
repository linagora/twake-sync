/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */
package at.bitfire.davdroid.servicedetection

/**
 * Chooses the host for `GET /.well-known/twake-configuration`.
 *
 * Production is [TwakeApp]: email-login GETs `dav.twake.app` after a 401 from
 * DAV login on `dav.twake.app`.
 * Swap [Current] to [EmailDomainDav] when custom-domain tenants serve the same file on
 * `dav.<email-domain>`.
 */
internal sealed interface WellKnownHostPolicy {

    fun hostFor(emailDomain: String): String?

    data object TwakeApp : WellKnownHostPolicy {
        const val DAV_HOST = "dav.twake.app"

        override fun hostFor(emailDomain: String): String = DAV_HOST
    }

    data object EmailDomainDav : WellKnownHostPolicy {
        override fun hostFor(emailDomain: String): String =
            DavDiscoveryHosts.wellKnownHost(emailDomain)
    }

    companion object {
        val Current: WellKnownHostPolicy = TwakeApp
    }

}
