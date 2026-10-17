/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.Intent
import android.net.Uri
import at.bitfire.davdroid.ui.setup.LoginTypesProvider.LoginAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class StandardLoginTypesProviderTest {

    private val provider = StandardLoginTypesProvider(Logger.getGlobal())

    @Test
    fun selectableLoginTypes_genericOnly() {
        assertEquals(listOf(UrlLogin, EmailLogin, AdvancedLogin), StandardLoginTypesProvider.selectableLoginTypes)
        for (type in StandardLoginTypesProvider.specificLoginTypes)
            assertTrue(type !in StandardLoginTypesProvider.selectableLoginTypes)
    }

    @Test
    fun intentToInitialLoginType_noData() {
        assertEquals(LoginAction(EmailLogin, true), provider.intentToInitialLoginType(Intent()))
    }

    @Test
    fun intentToInitialLoginType_loginFlow() {
        val intent = Intent().apply {
            data = Uri.parse("https://example.com/nextcloud")
            putExtra(LoginActivity.EXTRA_LOGIN_FLOW, 1)
        }
        assertEquals(LoginAction(NextcloudLogin, true), provider.intentToInitialLoginType(intent))
    }

    @Test
    fun intentToInitialLoginType_mailto() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:user@example.com"))
        assertEquals(LoginAction(EmailLogin, true), provider.intentToInitialLoginType(intent))
    }

    @Test
    fun intentToInitialLoginType_http() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://bob:secret@10.0.2.2:18990/"))
        assertEquals(LoginAction(UrlLogin, true), provider.intentToInitialLoginType(intent))
    }

}
