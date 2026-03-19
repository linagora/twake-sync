/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.ExternalUris.Homepage
import javax.inject.Inject

open class OseAccountsDrawerHandler @Inject constructor(): AccountsDrawerHandler() {

    @Composable
    override fun MenuEntries(
        snackbarHostState: SnackbarHostState
    ) {
        val uriHandler = LocalUriHandler.current

        // Most important entries
        ImportantEntries(snackbarHostState)

        // External links
        MenuHeading(R.string.navigation_drawer_external_links)
        MenuEntry(
            icon = Icons.Default.CloudOff,
            title = stringResource(R.string.navigation_drawer_privacy_policy),
            onClick = {
                uriHandler.openUri(
                    Homepage.baseUrl.buildUpon()
                        .appendPath(Homepage.PATH_PRIVACY)
                        .build().toString()
                )
            }
        )
    }

    @Composable
    @Preview
    fun MenuEntries_Standard_Preview() {
        Column {
            MenuEntries(SnackbarHostState())
        }
    }


    @Composable
    open fun Contribute(onContribute: () -> Unit) {
        MenuEntry(
            icon = Icons.Default.VolunteerActivism,
            title = stringResource(R.string.navigation_drawer_contribute),
            onClick = onContribute
        )
    }

}