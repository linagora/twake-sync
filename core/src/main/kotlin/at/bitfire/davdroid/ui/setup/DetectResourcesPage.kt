/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bitfire.davdroid.R
import at.bitfire.davdroid.log.DebugDirectory
import at.bitfire.davdroid.ui.DebugInfoActivity
import at.bitfire.davdroid.ui.ExternalUris
import at.bitfire.davdroid.ui.ExternalUris.withStatParams
import at.bitfire.davdroid.ui.UiUtils.toAnnotatedString
import at.bitfire.davdroid.ui.composable.ActionCard
import at.bitfire.davdroid.ui.composable.IconCard
import at.bitfire.davdroid.ui.composable.ProgressBar
import at.bitfire.davdroid.ui.composable.appPasswordHelpUrl

@Composable
fun DetectResourcesPage(
    model: LoginScreenViewModel = viewModel()
) {
    val uiState = model.detectResourcesUiState
    DetectResourcesPageContent(
        loading = uiState.loading,
        foundNothing = uiState.foundNothing,
        encountered401 = uiState.encountered401,
        appAccountsUrl = uiState.appAccountsUrl,
        loginValidationFailed = uiState.loginValidationFailed,
        debugLogFileName = uiState.debugLogFileName
    )
}

@Composable
fun DetectResourcesPageContent(
    loading: Boolean,
    foundNothing: Boolean,
    encountered401: Boolean,
    appAccountsUrl: String? = null,
    loginValidationFailed: Boolean,
    debugLogFileName: DebugDirectory.FileName?
) {
    Column(Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
    ) {
        if (loading)
            DetectResourcesPageContent_InProgress()
        else if (loginValidationFailed)
            DetectResourcesPageContent_LoginValidationFailed()
        else if (foundNothing)
            DetectResourcesPageContent_NothingFound(
                encountered401 = encountered401,
                appAccountsUrl = appAccountsUrl,
                debugLogFileName = debugLogFileName
            )
    }
}

@Composable
@Preview
fun DetectResourcesPageContent_InProgress() {
    Column(Modifier.fillMaxWidth()) {
        ProgressBar(
            //color = MaterialTheme.colors.secondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp))

        Column(Modifier.padding(8.dp)) {
            Text(
                stringResource(R.string.login_configuration_detection),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                stringResource(R.string.login_querying_server),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun DetectResourcesPageContent_NothingFound(
    encountered401: Boolean,
    appAccountsUrl: String? = null,
    debugLogFileName: DebugDirectory.FileName?
) {
    val context = LocalContext.current

    val showAppAccounts = appAccountsUrl != null

    Column(Modifier.padding(8.dp)) {
        Text(
            stringResource(R.string.login_configuration_detection),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .testTag("login_no_service")
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        stringResource(R.string.login_no_service),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    stringResource(R.string.login_no_service_info),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        if (showAppAccounts)
            ActionCard(
                icon = Icons.Default.Key,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("manage_application_accounts"),
                actionText = stringResource(R.string.login_manage_application_accounts),
                onAction = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, appAccountsUrl.toUri()))
                }
            ) {
                Column {
                    Text(
                        stringResource(R.string.login_use_applicative_account),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        stringResource(R.string.login_use_applicative_account_info),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        else if (encountered401)
            IconCard(
                icon = Icons.Default.Key,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("login_generic_credentials")
            ) {
                Text(
                    AnnotatedString.fromHtml(
                        stringResource(
                            R.string.login_check_credentials,
                            appPasswordHelpUrl().toString()
                        )
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

        val urlServices = ExternalUris.Homepage.baseUrl.buildUpon()
            .appendPath(ExternalUris.Homepage.PATH_TESTED_SERVICES)
            .withStatParams(context, "DetectResourcesPage")
            .build()
        IconCard(
            icon = Icons.Default.Info,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .testTag("login_tested_services")
        ) {
            Text(
                AnnotatedString.fromHtml(
                    stringResource(
                        R.string.login_see_tested_services,
                        urlServices
                    )
                ),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        if (debugLogFileName != null)
            ActionCard(
                icon = Icons.Default.BugReport,
                modifier = Modifier.testTag("login_view_logs"),
                actionText = stringResource(R.string.login_view_logs),
                onAction = {
                    val intent = DebugInfoActivity.IntentBuilder(context)
                        .withDebugLogFile(debugLogFileName)
                        .build()
                    context.startActivity(intent)
                }
            ) {
                Text(
                    stringResource(R.string.login_logs_available),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
    }
}

@Composable
fun DetectResourcesPageContent_LoginValidationFailed() {
    Column(Modifier.padding(8.dp)) {
        Text(
            stringResource(R.string.login_configuration_detection),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        stringResource(R.string.login_invalid_use_case),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    stringResource(R.string.login_invalid_use_case_info, "(${stringResource(R.string.app_name)})"),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )

                val context = LocalContext.current
                val urlDownload = ExternalUris.Homepage.baseUrl.buildUpon()
                    .appendPath(ExternalUris.Homepage.PATH_DOWNLOAD)
                    .withStatParams(context, "LoginValidationFailedPage")
                    .build()
                Text(
                    text = HtmlCompat.fromHtml(
                        stringResource(R.string.login_download_regular_davx5, urlDownload),
                        HtmlCompat.FROM_HTML_MODE_COMPACT
                    ).toAnnotatedString(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, urlDownload.toString().toUri()))
                }) {
                    Text(stringResource(R.string.login_download_button))
                }
            }
        }
    }
}

@Composable
@Preview
fun DetectResourcesPageContent_NothingFound() {
    DetectResourcesPageContent_NothingFound(
        encountered401 = false,
        debugLogFileName = DebugDirectory.FileName("davdroid-detection.log")
    )
}

@Composable
@Preview
fun DetectResourcesPage_NothingFound_401() {
    DetectResourcesPageContent_NothingFound(
        encountered401 = true,
        debugLogFileName = null
    )
}

@Composable
@Preview
fun DetectResourcesPage_NothingFound_401_WithLogs() {
    DetectResourcesPageContent_NothingFound(
        encountered401 = true,
        debugLogFileName = DebugDirectory.FileName("davdroid-detection.log")
    )
}

@Composable
@Preview
fun DetectResourcesPage_NothingFound_401_AppAccounts() {
    DetectResourcesPageContent_NothingFound(
        encountered401 = true,
        appAccountsUrl = "https://sso.twake.app/deviceaccounts",
        debugLogFileName = DebugDirectory.FileName("davdroid-detection.log")
    )
}

@Composable
@Preview
fun DetectResourcesPage_NothingFound_UnreachableHost_AppAccounts() {
    DetectResourcesPageContent_NothingFound(
        encountered401 = false,
        appAccountsUrl = "https://sso.twake.app/deviceaccounts",
        debugLogFileName = DebugDirectory.FileName("davdroid-detection.log")
    )
}

@Composable
@Preview
fun DetectResourcesPage_ValidationFailed() {
    DetectResourcesPageContent_LoginValidationFailed()
}
