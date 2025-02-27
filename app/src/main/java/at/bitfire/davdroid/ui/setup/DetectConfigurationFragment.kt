/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package at.bitfire.davdroid.ui.setup

import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import at.bitfire.davdroid.R
import at.bitfire.davdroid.db.Credentials
import at.bitfire.davdroid.log.Logger
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.ui.DebugInfoActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import java.net.URI
import java.util.logging.Level
import kotlin.concurrent.thread

class DetectConfigurationFragment: Fragment() {

    private val loginModel by activityViewModels<LoginModel>()
    private val model by viewModels<DetectConfigurationModel>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val baseURI = loginModel.baseURI ?: return
        val credentials = loginModel.credentials ?: return

        model.detectConfiguration(baseURI, credentials).observe(this) { result ->
            // save result for next step
            loginModel.configuration = result

            // remove "Detecting configuration" fragment, it shouldn't come back
            parentFragmentManager.popBackStack()

            if (result.calDAV != null || result.cardDAV != null)
                parentFragmentManager.beginTransaction()
                        .replace(android.R.id.content, AccountDetailsFragment())
                        .addToBackStack(null)
                        .commit()
            else
                parentFragmentManager.beginTransaction()
                        .add(NothingDetectedFragment(), null)
                        .commit()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.detect_configuration, container, false)!!


    class DetectConfigurationModel(application: Application): AndroidViewModel(application) {

        private var detectionThread: WeakReference<Thread>? = null
        private var result = MutableLiveData<DavResourceFinder.Configuration>()

        fun detectConfiguration(baseURI: URI, credentials: Credentials): LiveData<DavResourceFinder.Configuration> {
            synchronized(result) {
                if (detectionThread != null)
                    // detection already running
                    return result
            }

            thread {
                synchronized(result) {
                    detectionThread = WeakReference(Thread.currentThread())
                }

                try {
                    DavResourceFinder(getApplication(), baseURI, credentials).use { finder ->
                        result.postValue(finder.findInitialConfiguration())
                    }
                } catch(e: Exception) {
                    // exception, shouldn't happen
                    Logger.log.log(Level.SEVERE, "Internal resource detection error", e)
                }
            }
            return result
        }

        override fun onCleared() {
            synchronized(result) {
                detectionThread?.get()?.let { thread ->
                    Logger.log.info("Aborting resource detection")
                    thread.interrupt()
                }
                detectionThread = null
            }
        }
    }


    class NothingDetectedFragment: DialogFragment() {

        val model by activityViewModels<LoginModel>()

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            var message = getString(R.string.login_no_caldav_carddav)
            if (model.configuration?.encountered401 == true)
                message += "\n\n" + getString(R.string.login_username_password_wrong)

            return MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(R.string.login_configuration_detection)
                    .setIcon(R.drawable.ic_error)
                    .setMessage(message)
                    .setNeutralButton(R.string.login_view_logs) { _, _ ->
                        val intent = DebugInfoActivity.IntentBuilder(requireActivity())
                            .withLogs(model.configuration?.logs)
                            .build()
                        startActivity(intent)
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        // just dismiss
                    }
                    .create()
        }

    }

}