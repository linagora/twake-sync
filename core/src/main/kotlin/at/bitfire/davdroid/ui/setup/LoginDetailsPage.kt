/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Opens the login type page to choose another login type, or `null` if that's not possible.
 * May be offered by a [LoginType.LoginScreen].
 */
val LocalOnChooseOtherLoginType = compositionLocalOf<(() -> Unit)?> { null }

@Composable
fun LoginDetailsPage(
    snackbarHostState: SnackbarHostState,
    model: LoginScreenViewModel = viewModel()
) {
    val uiState = model.loginDetailsUiState
    CompositionLocalProvider(
        LocalOnChooseOtherLoginType provides
                if (model.canChooseOtherLoginType) model::navToLoginTypePage else null
    ) {
        uiState.loginType.LoginScreen(
            snackbarHostState = snackbarHostState,
            initialLoginInfo = uiState.loginInfo,
            onLogin = { loginInfo ->
                model.updateLoginInfo(loginInfo)
                model.navToNextPage()
            }
        )
    }
}