package com.jimjo.exodefender

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Message
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jimjo.exodefender.ServerConfig.getHostServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class UserManager(app: Application): NetworkResponseReceiver {

    private val appContext = app.applicationContext
    lateinit var mainActivity: MainActivity


    fun load(mainActivity: MainActivity) {
        this.mainActivity = mainActivity
    }
    fun loadUser() {

        val preferences = mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)

        // persist userid and callsign overrides from settings if present
        val overrideUserID = preferences.getInt(mainActivity.OVERRIDE_USERID, -1)
        if (overrideUserID != -1) {
            val overrideCallsign = preferences.getString(mainActivity.OVERRIDE_CALLSIGN, "")!!
            mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                putInt(USERID_KEY, overrideUserID)
                putString(CALLSIGN_KEY, overrideCallsign)
            }
            mainActivity.adminLogView.printout("Overriding user and callsign in preferences")
        }

        // load persisted user id values
        val userIdPrefValue = preferences.getInt(USERID_KEY, -1)
        val callSignPrefValue = preferences.getString(CALLSIGN_KEY, "")

        if (userIdPrefValue != -1 && callSignPrefValue != null && callSignPrefValue != "") {
            mainActivity.userId = userIdPrefValue
            mainActivity.callsign = callSignPrefValue

            mainActivity.adminLogView.printout("User ${mainActivity.callsign} (${mainActivity.userId!!}) loaded from preferences")
            sendEnvironmentDetails()

        } else {
            // either no userId and/or no callsign so enqueue to create a new user
            UserRegistrationObserver.observe(
                mainActivity,
                onSuccess = { loadUserOnServerResponse() },
                onFailure = { checkForNoUserNoNetwork() }
            )
            UserRegistrationScheduler.ensureScheduled(appContext)
        }
    }

    fun loadUserOnServerResponse() {
        mainActivity.adminLogView.printout("Server successfully created a new user, persisted to preferences")
        loadUser()
    }

    fun persistNewCallsign(newCallsign: String, logActivity: Boolean) {

        val oldCallsign = mainActivity.callsign

        // write callsign to persistence
        if (newCallsign != oldCallsign) {

            mainActivity.callsign = newCallsign
            mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                putString(CALLSIGN_KEY, newCallsign)
            }

            if (logActivity) {
                // log activity
                mainActivity.logMiscActivity(ActivityCode.USER_UPDATED, null, "Callsign changed ('$oldCallsign' -> '$newCallsign')")
            }
        }

    }

    fun sendEnvironmentDetails() {
//        val pInfo: PackageInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0)
//        appVersionCode = pInfo.longVersionCode
        val hardware = Build.MANUFACTURER.capitalize() + " " + Build.MODEL
        val sdk = Build.VERSION.SDK_INT
        val androidRelease = Build.VERSION.RELEASE_OR_CODENAME
        val platform = "$hardware android $androidRelease (sdk $sdk)"
//        val currentEnvSummary = "V$appVersionCode;$platform;"

        val preferences = mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        val lastAppVersionCode = preferences.getLong(mainActivity.APP_VERSION_CODE, 0L)
        val lastPlatform = preferences.getString(mainActivity.PLATFORM, "")

        // if either app version of platform has changed update in preferences
        if (mainActivity.appVersionCode != lastAppVersionCode || platform != lastPlatform) {
            mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                putLong(mainActivity.APP_VERSION_CODE, mainActivity.appVersionCode)
                putString(mainActivity.PLATFORM, platform)
            }

            // update the env details on the user record on the server
            if (mainActivity.userId != null) {
                Thread({ Networker(this, getHostServer(mainActivity)).updateEnvironment(mainActivity.userId!!, mainActivity.appVersionCode, platform) }).start()
            }
        }

        // if there's been changes to a previous recorded env log it
        if (lastAppVersionCode != 0L && lastAppVersionCode != mainActivity.appVersionCode) {
            mainActivity.logMiscActivity(ActivityCode.APP_VERSION_CHANGE, null, "$lastAppVersionCode -> ${mainActivity.appVersionCode}")
        }
        if (lastPlatform != "" && lastPlatform != platform) {
            mainActivity.logMiscActivity(ActivityCode.PLATFORM_CHANGE, null, "$lastPlatform -> $platform")
        }

    }

    fun checkForNoUserNoNetwork() {
        val preferences = mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)

        mainActivity.adminLogView.printout(preferences.getString(LAST_NET_ERROR, "")!!)

        if (preferences.getInt(USERID_KEY, -1) == -1) {

            mainActivity.adminLogView.printout("No user in preferences and no network, stopping app!")
            // show user message
            mainActivity.showInstallDialog(
                false,
                "Sorry. We couldn't contact the server to download the missions. Double-check the network and try restarting the app. You only need to do this once and then you can use the app offline!"
            )
        }
    }


    override fun handleNetworkMessage(msg: Message) {
        when (msg.what) {
            NetworkResponse.CREATE_USER.value -> {
                val newUser = msg.obj as Networker.CreateUserResponse
                persistNewCallsign( newUser.callSign, true)
                sendEnvironmentDetails()
            }
            -1, -2, -3-> {
                mainActivity.adminLogView.printout("Server error occured")
                mainActivity.adminLogView.printout("Error: [${msg.what}] ${msg.obj}")
            }
            -4 -> {
                mainActivity.adminLogView.printout("Error: Could not connect to server")
                checkForNoUserNoNetwork()
            }
        }
    }

}

object InstallId {
    private const val PREF = "install"
    private const val KEY = "install_id"

    fun get(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY, it).apply()
        }
    }
}

object UserRegistrationScheduler {
    private const val KEY_DONE = "user_registered"
    const val WORK_NAME = "register_user_once"

    fun ensureScheduled(context: Context) {
        val sp = context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_DONE, false)) return

        val req = OneTimeWorkRequestBuilder<RegisterUserWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req
        )
    }

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}

class RegisterUserWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val MAX_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        val installId = InstallId.get(applicationContext)
        val host = ServerConfig.getHostServer(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)

        fun retryOrFail(reason: String): Result {
            prefs.edit {
                putString(LAST_NET_ERROR, reason)
                putLong(LAST_NET_ERROR_AT, System.currentTimeMillis())
            }
            return if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                Networker(receiver = null, hostServer = host).createNewUserForWorker(installId)
            }

            when (result) {
                is Networker.CreateUserResult.Ok -> {
                    val r = result.response
                    if (r.success) {
                        prefs.edit()
                            .putInt(USERID_KEY, r.userId)
                            .putString(CALLSIGN_KEY, r.callSign)
                            .remove(LAST_NET_ERROR)
                            .remove(LAST_NET_ERROR_AT)
                            .apply()

                        UserRegistrationScheduler.markDone(applicationContext)
                        Result.success()
                    } else {
                        retryOrFail("Server replied success=false")
                    }
                }

                is Networker.CreateUserResult.HttpError -> {
                    val code = result.code
                    when {
                        code in 500..599 -> retryOrFail("HTTP $code")
                        code == 409 -> { // already exists (if you implement idempotency this way)
                            UserRegistrationScheduler.markDone(applicationContext)
                            Result.success()
                        }
                        else -> {
                            prefs.edit().putString(LAST_NET_ERROR, "HTTP $code").apply()
                            Result.failure() // 4xx treated as permanent
                        }
                    }
                }
            }
        } catch (e: IOException) {
            retryOrFail("Network: ${e.message}")
        } catch (e: Exception) {
            prefs.edit().putString(LAST_NET_ERROR, "Fatal: ${e.message}").apply()
            Result.failure()
        }
    }
}


object UserRegistrationObserver {

    private var failureNotifiedThisRun = false
    private const val RECENT_ERROR_WINDOW_MS = 10_000L

    fun observe(
        activity: AppCompatActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val prefs = activity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)

        WorkManager.getInstance(activity)
            .getWorkInfosForUniqueWorkLiveData(UserRegistrationScheduler.WORK_NAME)
            .observe(activity) { infos ->

                val info = infos.firstOrNull() ?: return@observe

                val hasUser = prefs.getInt(USERID_KEY, -1) != -1

                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        failureNotifiedThisRun = false
                        onSuccess()
                    }

                    WorkInfo.State.FAILED -> {
                        // terminal failure after MAX_ATTEMPTS
                        failureNotifiedThisRun = false
                        onFailure()
                    }

                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED -> {
                        if (!hasUser && info.runAttemptCount > 0 && !failureNotifiedThisRun) {
                            val lastAt = prefs.getLong(LAST_NET_ERROR_AT, 0L)
                            val now = System.currentTimeMillis()

                            // Only show "no network" if we *just* failed recently
                            if (lastAt != 0L && (now - lastAt) <= RECENT_ERROR_WINDOW_MS) {
                                failureNotifiedThisRun = true
                                onFailure()
                            }
                        }
                    }

                    else -> Unit
                }
            }
    }
}