/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import at.bitfire.davdroid.settings.SettingsManager
import at.bitfire.davdroid.ui.setup.LoginScreenViewModel.Page
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Optional
import java.util.logging.Logger

@OptIn(ExperimentalCoroutinesApi::class)
class LoginScreenViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createModel(
        initialLoginType: LoginType,
        skipLoginTypePage: Boolean,
        loginTypesProvider: LoginTypesProvider = StandardLoginTypesProvider(Logger.getGlobal())
    ) = LoginScreenViewModel(
        initialLoginType = initialLoginType,
        skipLoginTypePage = skipLoginTypePage,
        initialLoginInfo = LoginInfo(),
        accountRepository = mockk(relaxed = true),
        context = mockk(relaxed = true),
        debugDirectory = mockk(relaxed = true),
        httpClientBuilder = mockk(relaxed = true),
        ioDispatcher = Dispatchers.Unconfined,
        logger = Logger.getGlobal(),
        loginTypesProvider = loginTypesProvider,
        resourceFinderFactory = mockk(relaxed = true),
        settingsManager = mockk {
            every { getStringFlow(any()) } returns flowOf(null)
        },
        loginValidator = Optional.empty()
    )


    @Test
    fun navBack_fromSkippedLoginTypePage_finishes() {
        val model = createModel(EmailLogin, skipLoginTypePage = true)
        assertEquals(Page.LoginDetails, model.page)

        model.navBack()
        assertTrue(model.finish)
    }

    @Test
    fun navBack_fromUrlIntent_finishes() {
        val model = createModel(UrlLogin, skipLoginTypePage = true)
        model.navBack()
        assertTrue(model.finish)
    }

    @Test
    fun navToLoginTypePage_chooseOtherType_navBackReturnsStepByStep() {
        val model = createModel(EmailLogin, skipLoginTypePage = true)
        assertTrue(model.canChooseOtherLoginType)

        // "Other login methods" opens the login type page
        model.navToLoginTypePage()
        assertEquals(Page.LoginType, model.page)
        assertFalse(model.canChooseOtherLoginType)

        // choose URL login and continue
        model.selectLoginType(UrlLogin)
        model.navToNextPage()
        assertEquals(Page.LoginDetails, model.page)
        assertEquals(UrlLogin, model.loginDetailsUiState.loginType)
        assertFalse(model.canChooseOtherLoginType)

        // back → login type page
        model.navBack()
        assertEquals(Page.LoginType, model.page)
        assertFalse(model.finish)

        // back → email login page it has been opened from
        model.navBack()
        assertEquals(Page.LoginDetails, model.page)
        assertEquals(EmailLogin, model.loginDetailsUiState.loginType)
        assertEquals(EmailLogin, model.loginTypeUiState.loginType)
        assertFalse(model.finish)
        assertTrue(model.canChooseOtherLoginType)

        // back → closes the login
        model.navBack()
        assertTrue(model.finish)
    }

    @Test
    fun navToLoginTypePage_navBackWithoutChoosing_returnsToEmailLogin() {
        val model = createModel(EmailLogin, skipLoginTypePage = true)
        model.navToLoginTypePage()
        model.selectLoginType(AdvancedLogin)   // selected, but not continued

        model.navBack()
        assertEquals(Page.LoginDetails, model.page)
        assertEquals(EmailLogin, model.loginDetailsUiState.loginType)
        assertFalse(model.finish)
    }

    @Test
    fun navFromLoginTypePage_doubleTap_staysOnLoginDetails() {
        val model = createModel(EmailLogin, skipLoginTypePage = true)
        model.navToLoginTypePage()
        model.selectLoginType(UrlLogin)

        // two taps on "Continue" before the page has changed
        model.navFromLoginTypePage()
        model.navFromLoginTypePage()
        assertEquals(Page.LoginDetails, model.page)
        assertEquals(UrlLogin, model.loginDetailsUiState.loginType)
    }

    @Test
    fun navToLoginTypePage_chooseSameType_collapsesPath() {
        val model = createModel(EmailLogin, skipLoginTypePage = true)
        model.navToLoginTypePage()

        // choose the email login again
        model.selectLoginType(EmailLogin)
        model.navFromLoginTypePage()
        assertEquals(Page.LoginDetails, model.page)
        assertEquals(EmailLogin, model.loginDetailsUiState.loginType)

        // behaves like the start page: "Other login methods" offered, Back closes the login
        assertTrue(model.canChooseOtherLoginType)
        model.navBack()
        assertTrue(model.finish)
    }

    @Test
    fun navBack_throughDetectResourcesAndAccountDetails_onChooserPath() {
        val model = createModel(EmailLogin, skipLoginTypePage = true)
        model.navToLoginTypePage()
        model.selectLoginType(UrlLogin)
        model.navFromLoginTypePage()

        // login → resource detection (no base URI: detection doesn't start)
        model.navToNextPage()
        assertEquals(Page.DetectResources, model.page)
        model.navBack()
        assertEquals(Page.LoginDetails, model.page)
        assertEquals(UrlLogin, model.loginDetailsUiState.loginType)

        // resource detection → account details
        model.navToNextPage()
        model.navToNextPage()
        assertEquals(Page.AccountDetails, model.page)
        model.navBack()
        assertEquals(Page.LoginDetails, model.page)

        // then back along the chooser path: chooser, email login, closed
        model.navBack()
        assertEquals(Page.LoginType, model.page)
        model.navBack()
        assertEquals(Page.LoginDetails, model.page)
        assertEquals(EmailLogin, model.loginDetailsUiState.loginType)
        model.navBack()
        assertTrue(model.finish)
    }

    @Test
    fun navToLoginTypePage_notSkipped_notOffered() {
        val model = createModel(UrlLogin, skipLoginTypePage = false)
        assertEquals(Page.LoginType, model.page)

        model.navToNextPage()
        assertFalse(model.canChooseOtherLoginType)
        model.navToLoginTypePage()      // no-op
        assertEquals(Page.LoginDetails, model.page)

        // upstream behavior: back goes to the login type page, then closes
        model.navBack()
        assertEquals(Page.LoginType, model.page)
        model.navBack()
        assertTrue(model.finish)
    }

    @Test
    fun navToLoginTypePage_maybeNonInteractive_notOffered() {
        val provider = mockk<LoginTypesProvider> {
            every { maybeNonInteractive } returns true
        }
        val model = createModel(EmailLogin, skipLoginTypePage = true, loginTypesProvider = provider)
        assertFalse(model.canChooseOtherLoginType)

        model.navToLoginTypePage()      // no-op
        assertEquals(Page.LoginDetails, model.page)
        model.navBack()
        assertTrue(model.finish)
    }

}
