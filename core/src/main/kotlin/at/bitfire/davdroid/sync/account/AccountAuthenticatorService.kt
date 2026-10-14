/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */
package at.bitfire.davdroid.sync.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.accounts.LegacyAccount
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.resource.local.LocalAddressBookStore
import dagger.hilt.InstallIn
import dagger.hilt.android.EarlyEntryPoint
import dagger.hilt.android.EarlyEntryPoints
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.util.logging.Level
import java.util.logging.Logger


/**
 * Account authenticator for the DAVx5 account type.
 */
class AccountAuthenticatorService: Service() {

    /**
     * We don't use @AndroidEntryPoint / @Inject because the authenticator may be bound
     * before Hilt is initialized by the HiltTestRunner in instrumented tests.
     */
    @EarlyEntryPoint
    @InstallIn(SingletonComponent::class)
    interface AccountAuthenticatorEntryPoint {
        fun localAddressBookStore(): LocalAddressBookStore
        fun logger(): Logger
        fun serviceRepository(): DavServiceRepository
    }

    private lateinit var accountAuthenticator: AccountAuthenticator

    override fun onCreate() {
        accountAuthenticator = AccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?) =
        accountAuthenticator.iBinder.takeIf { intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT }


    private class AccountAuthenticator(
        val context: Context
    ): AbstractAccountAuthenticator(context) {

        override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<String>?, options: Bundle?) =
            Bundle().apply {
                putParcelable(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
                putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
                putString(AccountManager.KEY_ERROR_MESSAGE, context.getString(R.string.account_prefs_use_app))
            }

        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?) = null
        override fun getAuthTokenLabel(p0: String?) = null
        override fun confirmCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Bundle?) = null
        override fun updateCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
        override fun getAuthToken(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
        override fun hasFeatures(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Array<out String>?) = null

        /**
         * Called by the system before an account is removed (for instance from the Android settings),
         * but not when it's removed explicitly by ourselves (see `AccountRepository.delete`).
         *
         * Address book accounts are separate system accounts that would otherwise stay behind,
         * so we remove them (and the account's services in the database) together with the account.
         */
        override fun getAccountRemovalAllowed(response: AccountAuthenticatorResponse?, account: Account?): Bundle {
            if (account != null) {
                val entryPoint = EarlyEntryPoints.get(context.applicationContext, AccountAuthenticatorEntryPoint::class.java)
                val accountId = LegacyAccount(account)
                try {
                    entryPoint.localAddressBookStore().deleteByAccount(accountId)
                    runBlocking {
                        entryPoint.serviceRepository().deleteByAccount(accountId)
                    }
                } catch (e: Exception) {
                    entryPoint.logger().log(Level.WARNING, "Couldn't clean up data of removed account $account", e)
                }
            }

            return Bundle().apply {
                putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true)
            }
        }

    }

}