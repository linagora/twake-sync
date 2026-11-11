/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */
package at.bitfire.davdroid.servicedetection

import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

internal object DavDiscoveryHosts {

    const val TWAKE_DAV_HOST = WellKnownHostPolicy.TwakeApp.DAV_HOST

    /** Domain of Twake SaaS; mail for its custom-domain tenants is exchanged by hosts under it. */
    const val TWAKE_DOMAIN = "twake.app"

    fun isEmailLogin(baseUri: URI): Boolean =
        baseUri.scheme.equals("mailto", ignoreCase = true)

    fun emailDomain(baseUri: URI): String? {
        if (!isEmailLogin(baseUri))
            return null
        val mailbox = baseUri.schemeSpecificPart
        val posAt = mailbox.lastIndexOf("@")
        if (posAt == -1)
            return null
        return mailbox.substring(posAt + 1).trim().takeIf { it.isNotEmpty() }
    }

    fun wellKnownHost(emailDomain: String): String {
        if (emailDomain.startsWith("dav.", ignoreCase = true))
            return emailDomain
        return "dav.$emailDomain"
    }

    /**
     * Returns `dav.<domain>` as an extra host of the email domain itself, or `null`.
     *
     * Only guessed when the domain publishes no SRV record: an SRV record is the domain owner's
     * explicit choice of DAV host, so the credentials must not go to a guessed sibling host as well.
     */
    fun fallbackHost(domain: String, emailLogin: Boolean, srvFound: Boolean): String? {
        if (!emailLogin || srvFound)
            return null
        if (domain.startsWith("dav.", ignoreCase = true))
            return null
        return "dav.$domain"
    }

    /**
     * Whether an MX target belongs to Twake SaaS, i.e. is [TWAKE_DOMAIN] or one of its subdomains.
     *
     * Matches whole DNS labels, so look-alikes such as `twake.app.example.com` or `eviltwake.app`
     * do not match.
     */
    fun isTwakeMailExchanger(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return normalized == TWAKE_DOMAIN || normalized.endsWith(".$TWAKE_DOMAIN")
    }

    fun twakeDavHost(alreadyTriedHosts: Collection<String>, emailLogin: Boolean): String? {
        if (!emailLogin)
            return null
        if (alreadyTriedHosts.any { it.equals(TWAKE_DAV_HOST, ignoreCase = true) })
            return null
        return TWAKE_DAV_HOST
    }

    fun isUnreachableHostError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is ConnectException,
                is UnresolvedAddressException -> return true
            }
            current = current.cause
        }
        return false
    }

}
