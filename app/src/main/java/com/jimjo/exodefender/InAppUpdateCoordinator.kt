package com.jimjo.exodefender

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.install.model.AppUpdateType
import java.util.concurrent.TimeUnit

class InAppUpdateCoordinator(
    private val activity: Activity,
    private val updateFlowLauncher: ActivityResultLauncher<IntentSenderRequest>
) {

    /**
     * Your policy hook. Decide per release/state whether you want IMMEDIATE or FLEXIBLE.
     * For most games, FLEXIBLE is kinder; IMMEDIATE for "must update to continue".
     */
    enum class Policy { FLEXIBLE_PREFERRED, IMMEDIATE_ONLY }


    private val mainActivity = activity as MainActivity
    private val prefs: SharedPreferences =
        activity.getSharedPreferences("in_app_update", Context.MODE_PRIVATE)

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private var installListener: InstallStateUpdatedListener? = null

    // Prevent hammering Play services during quick Activity churn.
    private var lastCheckElapsedMs: Long = 0L

    // Optional: show your own "Restart to finish update" UI when true.
    @Volatile var flexibleUpdateDownloaded: Boolean = false
        private set

    /**
     * Call from onPostResume() (recommended) or onResume().
     * Safe to call repeatedly.
     */
    fun onPostResumeCheck(policy: Policy) {
        // Throttle checks (e.g. once per 10 minutes)
        val now = SystemClock.elapsedRealtime()
        val mandatory = policy == Policy.IMMEDIATE_ONLY
        if (!mandatory && now - lastCheckElapsedMs < TimeUnit.MINUTES.toMillis(10)) return
        lastCheckElapsedMs = now

        // If a flexible update already downloaded, you can complete it whenever you want.
        if (flexibleUpdateDownloaded) return

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                val availability = info.updateAvailability()
                mainActivity.log.printout(
                    "InAppUpdate: availability=$availability " +
                            "flexibleAllowed=${info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)} " +
                            "immediateAllowed=${info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)}"
                )

                // If an update flow was started before and the user left/re-entered the app,
                // you should resume IMMEDIATE developer-triggered updates.
                val isDevTriggeredImmediateInProgress =
                    availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS

                val wantsImmediate = policy == Policy.IMMEDIATE_ONLY || isDevTriggeredImmediateInProgress

                val updateType = when {
                    wantsImmediate && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                        AppUpdateType.IMMEDIATE

                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        AppUpdateType.FLEXIBLE

                    // Fallback if flexible isn't allowed but immediate is:
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                        AppUpdateType.IMMEDIATE

                    else -> null
                }

                val updateAvailable = availability == UpdateAvailability.UPDATE_AVAILABLE ||
                        isDevTriggeredImmediateInProgress

                if (updateAvailable && updateType != null) {
                    startUpdateFlow(info, updateType)
                }
            }
            .addOnFailureListener { e ->
                mainActivity.log.printout("InAppUpdate: appUpdateInfo failed: ${e.javaClass.simpleName}: ${e.message}")
            }
    }

    /**
     * For FLEXIBLE, call this when the user taps your “Restart to apply update” button.
     */
    fun completeFlexibleUpdateIfReady() {
        if (!flexibleUpdateDownloaded) return
        mainActivity.log.printout("InAppUpdate: Completing flexible update...")
        appUpdateManager.completeUpdate()
    }

    fun dispose() {
        installListener?.let { appUpdateManager.unregisterListener(it) }
        installListener = null
    }

    private fun startUpdateFlow(info: com.google.android.play.core.appupdate.AppUpdateInfo, updateType: Int) {
        mainActivity.log.printout("InAppUpdate: Starting flow type=${typeName(updateType)}")

        if (updateType == AppUpdateType.FLEXIBLE) {
            // Register listener once.
            if (installListener == null) {
                installListener = InstallStateUpdatedListener { state ->
                    if (state.installStatus() == InstallStatus.DOWNLOADED) {
                        flexibleUpdateDownloaded = true
                        mainActivity.log.printout("InAppUpdate: Flexible update downloaded (ready to complete).")
                        // You can now show an in-game banner/button:
                        // "Update ready — Restart to apply"
                    }
                }
                appUpdateManager.registerListener(installListener!!)
            }
        }

        val options = AppUpdateOptions.newBuilder(updateType).build()

        try {
            appUpdateManager.startUpdateFlowForResult(
                info,
                updateFlowLauncher,
                options
            )
        } catch (e: Exception) {
            // Extremely rare, but safe to log
            mainActivity.log.printout("InAppUpdate: startUpdateFlow exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun typeName(t: Int) = when (t) {
        AppUpdateType.FLEXIBLE -> "FLEXIBLE"
        AppUpdateType.IMMEDIATE -> "IMMEDIATE"
        else -> "UNKNOWN"
    }
}
