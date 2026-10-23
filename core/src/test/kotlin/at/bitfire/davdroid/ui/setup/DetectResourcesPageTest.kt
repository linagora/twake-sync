/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import at.bitfire.davdroid.log.DebugDirectory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DetectResourcesPageTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nothingFound_not401_matchesUpstream() {
        setNothingFound(encountered401 = false, appAccountsUrl = null, hasLogs = true)

        composeRule.onNodeWithTag("login_no_service").assertIsDisplayed()
        composeRule.onNodeWithTag("login_tested_services").assertIsDisplayed()
        composeRule.onNodeWithTag("login_view_logs").assertIsDisplayed()
        composeRule.onNodeWithTag("login_generic_credentials").assertDoesNotExist()
        composeRule.onNodeWithTag("manage_application_accounts").assertDoesNotExist()
        composeRule.onNodeWithText("Please use an applicative account").assertDoesNotExist()
    }

    @Test
    fun generic401_matchesUpstreamFourCards() {
        setNothingFound(encountered401 = true, appAccountsUrl = null, hasLogs = true)

        composeRule.onNodeWithTag("login_no_service").assertIsDisplayed()
        composeRule.onNodeWithTag("login_generic_credentials").assertIsDisplayed()
        composeRule.onNodeWithTag("login_tested_services").assertIsDisplayed()
        composeRule.onNodeWithTag("login_view_logs").assertIsDisplayed()
        composeRule.onNodeWithTag("manage_application_accounts").assertDoesNotExist()
        composeRule.onNodeWithText("Please use an applicative account").assertDoesNotExist()
        composeRule.onNodeWithText("Couldn't find CalDAV or CardDAV service.").assertIsDisplayed()
        composeRule.onNodeWithText("The server rejected the login", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("our list of tested services", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("View logs").assertIsDisplayed()
    }

    @Test
    fun applicative401_showsUpstreamCardsAndApplicative() {
        setNothingFound(
            encountered401 = true,
            appAccountsUrl = "https://sso.example.com/deviceaccounts",
            hasLogs = true
        )

        composeRule.onNodeWithTag("login_no_service").assertIsDisplayed()
        composeRule.onNodeWithTag("manage_application_accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("login_tested_services").assertIsDisplayed()
        composeRule.onNodeWithTag("login_view_logs").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't find CalDAV or CardDAV service.").assertIsDisplayed()
        composeRule.onNodeWithText("Please use an applicative account").assertIsDisplayed()
        composeRule.onNodeWithText("Manage application accounts").assertIsDisplayed()
        composeRule.onNodeWithText("our list of tested services", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("login_view_logs").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("View logs").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login_generic_credentials").assertDoesNotExist()
        composeRule.onNodeWithText("The server rejected the login", substring = true).assertDoesNotExist()
    }

    @Test
    fun appAccountsUrlWithout401_showsApplicativeCard() {
        setNothingFound(
            encountered401 = false,
            appAccountsUrl = "https://sso.example.com/deviceaccounts",
            hasLogs = true
        )

        composeRule.onNodeWithTag("login_no_service").assertIsDisplayed()
        composeRule.onNodeWithTag("manage_application_accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("login_tested_services").assertIsDisplayed()
        composeRule.onNodeWithTag("login_view_logs").assertIsDisplayed()
        composeRule.onNodeWithText("Please use an applicative account").assertIsDisplayed()
        composeRule.onNodeWithText("Manage application accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("login_generic_credentials").assertDoesNotExist()
        composeRule.onNodeWithText("The server rejected the login", substring = true).assertDoesNotExist()
    }

    private fun setNothingFound(
        encountered401: Boolean,
        appAccountsUrl: String?,
        hasLogs: Boolean
    ) {
        composeRule.setContent {
            MaterialTheme {
                DetectResourcesPageContent(
                    loading = false,
                    foundNothing = true,
                    encountered401 = encountered401,
                    appAccountsUrl = appAccountsUrl,
                    loginValidationFailed = false,
                    debugLogFileName = if (hasLogs)
                        DebugDirectory.FileName("davdroid-detection.log")
                    else
                        null
                )
            }
        }
    }

}
