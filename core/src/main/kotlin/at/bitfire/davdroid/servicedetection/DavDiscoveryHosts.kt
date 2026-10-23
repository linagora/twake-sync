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

    fun fallbackHost(domain: String, alreadyTriedHost: String, emailLogin: Boolean): String? {
        if (!emailLogin)
            return null
        if (domain.startsWith("dav.", ignoreCase = true))
            return null
        val davHost = "dav.$domain"
        if (alreadyTriedHost.equals(davHost, ignoreCase = true))
            return null
        return davHost
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
