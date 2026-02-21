package com.jimjo.exodefender

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Message
import android.view.Surface
import kotlin.math.atan2
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.contains
import androidx.core.view.isVisible
import com.jimjo.exodefender.ServerConfig.getHostServer
import kotlin.apply

const val PREFERENCES_KEY = "settings"
const val OVERRIDE_HOST_SERVER = "override_host_server"
const val USERID_KEY = "userid"
const val CALLSIGN_KEY = "callsign"
const val LAST_NET_ERROR = "last_net_error"
const val LAST_NET_ERROR_AT = "last_net_error_at"

enum class Feature {
    HOME,
    MILKRUNS,
    MISSIONS,
    REPLAYS,
    ADMIN_LEVELS,
    STORY,
    TRAINING,
    SETTINGS,
    ADMIN,
    LEVEL_TESTING,
    LEVEL_BUILDER,
    UNKNOWN,
}

class MainActivity : AppCompatActivity(), SensorEventListener, NetworkResponseReceiver {

    // DEBUG SETTINGS
//    val defaultHostServer = "http://192.168.0.15:7139" // live server
    var unlockAllLevels = false // false for release


    val APP_VERSION_CODE = "app_version_code"
    val PLATFORM = "platform"
    val OVERRIDE_USERID = "override_userId"
    val OVERRIDE_CALLSIGN = "override_callsign"
    val MANDATORY_TRAINING_COMPLETED = "mandatory_training_completed"
    val TRAN_SENSITIVITY_SETTING = "tran_sensitivity_setting"
    val ROT_SENSITIVITY_SETTING = "rot_sensitivity_setting"
    val EFFECTS_VOLUME_SETTING = "effects_volume_setting"
    val MUSIC_VOLUME_SETTING = "music_volume_setting"
    val VIBRATION_EFFECT = "vibration_effect"
    val RADIO_ENABLED = "radio_enabled"
    val LAST_USED_FEATURE = "last_used_feature" // "home", "milkruns", "missions"
    val LAST_OPENED_MISSION_LEVEL_ID = "last_opened_mission_level_id"
    val ADMIN_MODE = "admin_mode"
    val ADMIN_CURRENT_CAMPAIGN_CODE = "admin_current_campaign_code"
    val  STARTUP_NOTICE_ACCEPTED = "startup_notice_accepted"
    val  DEFEND_PREAMBLE_SHOWN = "defend_preamble_shown"
    val  EVAC_PREAMBLE_SHOWN = "evac_preamble_shown"
    val  LANDING_TRAINING_COMPLETED = "landing_training_completed"




    var currentLevel: Level? = null

    private lateinit var mainLayout: FrameLayout
    lateinit var insetContentBySidebar: ConstraintLayout
    lateinit var homeView: HomeView
    lateinit var startupNoticeView: StartupNoticeView
    lateinit var levelsView: LevelsView
    lateinit var storyView: StoryView
    lateinit var replayManager: ReplayManager
    lateinit var trainingView: TrainingView
    lateinit var levelEditorView: LevelEditorView
    lateinit var levelBuilderToolbar: LevelEditorToolbarView
    lateinit var levelEditorMetadataView: LevelEditorMetadataView
    lateinit var actorEditMetadataView: ActorEditMetadataView
    lateinit var adminView: AdminView
    lateinit var adminLogView: AdminLog
    lateinit var levelSummaryView: MissionSummaryView
    lateinit var trainingOutcomeView: TrainingOutcomeView
    lateinit var levelPrologueView: LevelPrologueView
    lateinit var pauseMissionView: PauseMissionView
    lateinit var trainingLandingCompleteView: TrainingLandingCompleteView
    lateinit var settingsView: SettingsView
    var newUserNotice: NewUserNotice? = null
    var installDialog: InstallDialogView? = null
    var editCallSignView: EditCallSignView? = null
    private lateinit var settingsButton: ImageView

    lateinit var screenOverlay: ScreenOverlay
    private var gLView: GameSurfaceView? = null
    private lateinit var sensorManager: SensorManager
    private var currentReplayFlightLog: FlightLog? = null

    var currentlyUsedFeature = Feature.UNKNOWN

    var appVersionCode: Long = 0
    var appVersionName = ""
    private var mustUpdateFromServer = false
    private var lastMinVersionSeen: Long = Long.MIN_VALUE

    var userId: Int? = null
    var callsign: String? = null
    val globalSettings = GlobalSettings()
    lateinit var userManager: UserManager

    val audioPlayer = AudioPlayer(this)
    lateinit var haptics: Haptics

    private lateinit var inAppUpdate: InAppUpdateCoordinator
    private var updatePromptShownThisPassiveSession = false
    lateinit var inAppReview: InAppReviewCoordinator
    private val updateFlowLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // Optional logging
            // handleUpdateResult(result) // if you want
        }

    lateinit var modelManager: ModelManager
    lateinit var levelManager: LevelManager
    lateinit var flightLogManager: FlightLogManager

    val levelCompletionManager = LevelCompletionManager(this)

    var levelOpen = false
    var levelActive = false
    private var sensor: Sensor? = null
    var neutralDeviceValue = 5f
    private var lastRotation: Int = -1
    var rotationalFactor = 3f
    private var neutralPitchRad = 0f
    private var neutralRollRad = 0f
    var levelEditorMode = false

    val onBackPressedCallback = OnBackPressedCallback(this)

    var screenWidthInches = 0f
    var screenWidth = 0
    var screenHeight = 0
    var densityDpi = 0
    var screenScale = 1.0f
    var resolutionScale = 1.0f

    fun setScreenWidth() {
        val displayMetrics = resources.displayMetrics
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            screenWidthInches = displayMetrics.widthPixels / displayMetrics.densityDpi.toFloat()
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        } else {
            screenWidthInches = displayMetrics.heightPixels / displayMetrics.densityDpi.toFloat()
            screenWidth = displayMetrics.heightPixels
            screenHeight = displayMetrics.widthPixels
        }

        val REFERENCE_WIDTH_DP = 150f
        resolutionScale = displayMetrics.densityDpi / REFERENCE_WIDTH_DP
        densityDpi = displayMetrics.densityDpi
        val refWidth = 1920f
        screenScale = screenWidth / refWidth
    }

    fun setKeepScreenOn(set: Boolean) {
//        println("setKeepScreenOn($set)")
        if (set) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = taskId
        val instState = savedInstanceState != null
        val isChanging = isChangingConfigurations
        val hash = System.identityHashCode(this)

        println("LIFECYCLE, MainActivity.onCreate hash=$hash task=$taskId saved=$instState changing=$isChanging intent=${intent?.action} data=${intent?.data}")

        enableEdgeToEdge()
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

        window.setNavigationBarContrastEnforced(false)

        setContentView(R.layout.main_activity)

        setScreenWidth()

        audioPlayer.initialize()
        haptics = Haptics(this)

        levelManager = LevelManager(this)

        flightLogManager = FlightLogManager(this)

        modelManager = ModelManager(assets)

        // TODO support levels and maps stored in database
        // DEEBUG: uncomment to copy levels and maps from raw
//        levelManager.copyLevelsFromRaw()
        levelManager.loadLevelsFromInternalStorage()

        mainLayout = this.findViewById(R.id.mainLayout)
        val mainFrameLayoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        mainLayout.layoutParams = mainFrameLayoutParams

        insetContentBySidebar = findViewById(R.id.insetContentBySidebar)
        homeView = findViewById(R.id.homeView)
        storyView = findViewById(R.id.storyView)
        replayManager = findViewById(R.id.replayManager)
        trainingView = findViewById(R.id.trainingView)
        trainingOutcomeView = findViewById(R.id.trainingOutcomeView)
        trainingOutcomeView.visibility = GONE
        levelPrologueView = findViewById(R.id.levelPrologueView)
        levelPrologueView.visibility = GONE

        startupNoticeView = findViewById(R.id.startupNoticeView)
        startupNoticeView.visibility = GONE

        levelSummaryView = findViewById(R.id.missionSummaryView)
        levelSummaryView.visibility = GONE

        pauseMissionView = findViewById(R.id.pauseMissionView)
        pauseMissionView.visibility = GONE

        trainingLandingCompleteView = findViewById(R.id.trainingLandingCompleteView)
        trainingLandingCompleteView.visibility = GONE

        settingsView = findViewById(R.id.settingsView)
        settingsView.visibility = GONE


        levelsView = findViewById(R.id.levelsView)
        levelsView.initialize(levelManager)
//        missionsView.loadLevels()

        levelEditorView = findViewById(R.id.levelBuilder)
        levelEditorView.initialize(levelManager, true)
        levelEditorView.visibility = GONE

        levelBuilderToolbar = findViewById(R.id.levelBuilderToolbarView)
        levelBuilderToolbar.visibility = GONE

        levelEditorMetadataView = findViewById(R.id.levelBuilderMetadataView)
        levelEditorMetadataView.visibility = GONE

        actorEditMetadataView = findViewById(R.id.actorEditMetadataView)
        actorEditMetadataView.visibility = GONE

        adminView = findViewById<AdminView>(R.id.adminView).apply { visibility = GONE }
        adminLogView = findViewById<AdminLog>(R.id.adminLogView).apply { visibility = GONE }

        settingsButton = findViewById(R.id.settingsButton)
        settingsButton.setOnClickListener({
            showSettings()
        })

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        screenOverlay = findViewById(R.id.screenOverlay)


        insetContentBySidebar.applySystemBarInsets(true, true, true, true)
        startupNoticeView.applySystemBarInsets(true, false, true, true)
        screenOverlay.applySystemBarInsets(true, true, true, true)
        levelSummaryView.applySystemBarInsets(true, false, true, true)
        settingsView.applySystemBarInsets(true, false, true, true)
        adminView.applySystemBarInsets(true, false, true, true)
        levelEditorView.applySystemBarInsets(true, false, true, true)
        adminLogView.applySystemBarInsets(true, false, true, false)


        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        // load preferences
        val preferences = getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        updateRotSensitivity(preferences.getInt(ROT_SENSITIVITY_SETTING, 5))
        audioPlayer.setEffectsVolume(preferences.getFloat(EFFECTS_VOLUME_SETTING, 0.5f))
        audioPlayer.setMusicVolume(preferences.getFloat(MUSIC_VOLUME_SETTING, 0.5f))
        haptics.enabled = preferences.getBoolean(VIBRATION_EFFECT, true)
        audioPlayer.setRadioEnabled(isRadioSettingEnabled())

        getAppVersion()

        adminLogView.printout("Connecting to ${getHostServer(this)}...")
        Thread({ Networker(this, getHostServer(this)).testConn() }).start()

        adminLogView.printout("Getting globalsettings...")
        Thread({ Networker(this, getHostServer(this)).getGlobalSettings() }).start()

        userManager = UserManager(application)
        userManager.load(this)
        userManager.loadUser()

        levelManager.getLatestSyncManifest(false)

        inAppUpdate = InAppUpdateCoordinator(this, updateFlowLauncher)

        inAppReview = InAppReviewCoordinator(this)

        // START GAME

        // COMMENT OUT IF START-UP MESSAGE IS NOT REQUIRED FOR THIS VERSION
        if (!isStartupNoticeAccepted()) {
            showStartupNotice()
            return
        }

        // DEBUG uncomment to force intitial training screens
//        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit { remove(MANDATORY_TRAINING_COMPLETED) }

        startGameNormally()
    }

    fun isStartupNoticeAccepted() =
        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(STARTUP_NOTICE_ACCEPTED, false)
    fun isMandatoryTrainingComplete() =
        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(MANDATORY_TRAINING_COMPLETED, false)
    fun isLandingTrainingComplete() =
        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(LANDING_TRAINING_COMPLETED, false)
    fun isDefendPreambleShown() =
        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(DEFEND_PREAMBLE_SHOWN, false)
    fun isEvacPreambleShown() =
        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(EVAC_PREAMBLE_SHOWN, false)

    fun isRadioSettingEnabled() =
        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(RADIO_ENABLED, true)

    private fun showStartupNotice() {
        startupNoticeView.load(
            "Open Testing Notice",
            "This is a beta version of Exo Defender.\n" +
                    "Difficulty, levels, and balance may change during testing.\n" +
                    "Leaderboards and personal bests will reset at full release."
        )
        startupNoticeView.visibility = View.VISIBLE
        startupNoticeView.bringToFront()

        startupNoticeView.setOnOkListener {
            // persist that button has been clicked
            getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                putBoolean(STARTUP_NOTICE_ACCEPTED, true)
            }
            startupNoticeView.visibility = GONE
            startGameNormally()
        }
    }

    private fun startGameNormally() {
        if (!isMandatoryTrainingComplete()) {
            showStoryView(true)
            audioPlayer.startMusic(0)
            return
        }

        val preferences = getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        val lastUsedFeature = runCatching {
            Feature.valueOf(preferences.getString(LAST_USED_FEATURE, Feature.UNKNOWN.name)!!)
        }.getOrDefault(Feature.UNKNOWN)

        when (lastUsedFeature) {
            Feature.MILKRUNS -> showLevelsView(Level.LevelType.MILKRUN)
            Feature.MISSIONS -> {
                val latestMissionId = levelManager.getHighestUnlockedLevelId(Level.LevelType.MISSION)
                showLevelsView(Level.LevelType.MISSION, latestMissionId)
            }
            else -> showHomeView()
        }
    }

    fun View.applySystemBarInsets(
        left: Boolean = true,
        top: Boolean = true,
        right: Boolean = true,
        bottom: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                if (left) sys.left else 0,
                if (top) sys.top else 0,
                if (right) sys.right else 0,
                if (bottom) sys.bottom else 0
            )
            insets
        }
    }
    fun getAppVersion() {
        val pInfo: PackageInfo = getPackageManager().getPackageInfo(getPackageName(), 0)
        appVersionCode = pInfo.longVersionCode
        if (pInfo.versionName != null) {
            appVersionName = pInfo.versionName!!
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onSaveInstanceState(outState: Bundle) {
        // Do not call super to prevent the framework from saving view hierarchy state
        // super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // Do not call super to prevent the framework from trying to restore view hierarchy state
        // super.onRestoreInstanceState(savedInstanceState)
    }


    private fun onEnteredPassiveMode() {
        // Passive mode == no GL view
        if (gLView != null) return

        if (isInstalledFromPlayStore()) {
            val policy =
                if (mustUpdateFromServer) InAppUpdateCoordinator.Policy.IMMEDIATE_ONLY
                else InAppUpdateCoordinator.Policy.FLEXIBLE_PREFERRED

            inAppUpdate.onPostResumeCheck(policy)
        }

        // If a flexible update is already downloaded, offer restart
        maybeShowUpdateReadyUi()
    }


    private fun onEnteredGameplayMode() {
        updatePromptShownThisPassiveSession = false
    }

    fun openLevelById(levelId: Int, levelBuilderMode: Boolean) {

        if (levelEditorMode) {
            levelManager.loadLevelsFromInternalStorage()
            if (levelBuilderMode) {
                setCurrentFeature(Feature.LEVEL_BUILDER)
            }
            else {
                setCurrentFeature(Feature.LEVEL_TESTING)
            }
        }

        val requestedLevel = levelManager.levelIdLookup[levelId]

        if (requestedLevel == null) return

        // divert to pre-level messages or training if required
        if (maybeShowPreLevelSequence(requestedLevel)) return

        openLevel(requestedLevel, false, null, null, levelBuilderMode, false)
    }

    fun openLevelByGlobalIndex(filter: Level.LevelType?, levelIndex: Int, levelBuilderMode: Boolean = false) {

        fun getRequestedLevel(levelList: List<Level>): Level? {

            if (levelIndex < levelList.size) return levelList[levelIndex]
            return null
        }

        val requestedLevel =
            if (filter != null) {
                when (filter) {
                    Level.LevelType.MISSION -> getRequestedLevel(levelManager.missions)
                    Level.LevelType.MILKRUN -> getRequestedLevel(levelManager.milkruns)
                    Level.LevelType.TRAINING -> getRequestedLevel(levelManager.training)
                    Level.LevelType.DEVELOPMENT -> getRequestedLevel(levelManager.development)
                }
            } else {
                getRequestedLevel(levelManager.allLevels)
            }

        if (requestedLevel == null) {
            println("ERROR: invalid index for $filter, $levelIndex")
            unloadGLView()
            return
        }

        // divert to pre-level messages or training if required
        if (maybeShowPreLevelSequence(requestedLevel)) return

        if (levelEditorMode) levelManager.loadLevelsFromInternalStorage()

        if (levelEditorMode || requestedLevel.unlocked) {
            openLevel(requestedLevel, false, null, null, levelBuilderMode, false)
        }
    }

    fun openLevelFromLevelsView(level: Level) {

        // divert to pre-level messages or training if required
        if (maybeShowPreLevelSequence(level)) return

        val bestScore = levelManager.levelsProgress.getBestScore(level.id)
        val bestLog = flightLogManager.readBestSuccessfulLog(level.id)

        if (bestScore == null || bestLog == null) {
            openLevel(level, false, null, null, false, false)
        }
        else {
            showLevelSummaryBeforeStart(level, bestScore, bestLog)
        }
    }

    fun openLevel(level: Level, replayMode: Boolean, replayFlightLog: FlightLog?, savedFlightLofFilename: String?, levelBuilderMode: Boolean, downloadedReplay: Boolean) {

        unloadGLView()

        currentLevel = level
        level.loadGameMap()
        gLView = GameSurfaceView(this)
        gLView!!.onResume()
        mainLayout.addView(gLView!!)

        pauseMissionView.bringToFront()
        settingsButton.bringToFront()
        settingsView.bringToFront()

        screenOverlay.visibility = VISIBLE
        screenOverlay.bringToFront()

        val flightLog: FlightLog
        if (replayMode && replayFlightLog != null) {
            flightLog = replayFlightLog
            flightLog.playback()
            currentReplayFlightLog = flightLog
        } else {

            // training settings
            val showScreenAnnotations = level.type == Level.LevelType.TRAINING && (level.index == 0 || level.index == 2 )
            screenOverlay.showScreenAnnotations(showScreenAnnotations, level.index)
            screenOverlay.setMandatoryTraining(level.type == Level.LevelType.TRAINING && !isMandatoryTrainingComplete())

            // create flight log
            flightLog = FlightLog()
            flightLog.levelId = level.id
            getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                putInt(LAST_OPENED_MISSION_LEVEL_ID, level.id)
            }
        }

        gLView!!.flightControls.setDeviceNeutral = true

        gLView!!.initialize(screenOverlay, modelManager, level, flightLog, savedFlightLofFilename, levelBuilderMode, downloadedReplay)
        gLView!!.setPause(false)
        levelOpen = true
        levelActive = true
        onEnteredGameplayMode()

        setKeepScreenOn(true)
        levelEditorView.visibility = GONE
        audioPlayer.startMusic(1)

    }

    fun showMissionSummaryAfterMission(
        flightLog: FlightLog,
        replayMode: Boolean,
        model: LevelSummaryModel
    ) {
        levelActive = false

        levelSummaryView.loadAfterMission(
            flightLog = flightLog,
            replayMode = replayMode,
            model = model,
            parTimeMs = null   // or null if you don't use it yet
        )

        levelSummaryView.visibility = VISIBLE
        levelSummaryView.isClickable = !replayMode
        levelSummaryView.bringToFront()
    }

    fun closeMissionSummary() {
        levelSummaryView.visibility = GONE
    }


    fun showLevelSummaryBeforeStart(level: Level, bestScore: Int, bestLog: FlightLog) {

        val camapaignCode = if (level.type == Level.LevelType.MISSION) level.campaignCode + "-" else ""

        val model = LevelSummaryModel(
            levelId = level.id,
            levelTitle = camapaignCode + "${level.index + 1}: ${level.name}",
            isLevelTypeScored = (level.type == Level.LevelType.MISSION),
            canPlayNextLevel = false,
            nextLevelType = null,
            isLastMilkrun = false,
            score = null,
            previousBestLog = bestLog,
            previousBestScore = null
        )

        levelSummaryView.loadBestRunBeforeStart(model)

        levelSummaryView.visibility = VISIBLE
        levelSummaryView.isClickable = true
        levelSummaryView.bringToFront()
    }

    fun maybeShowPreLevelSequence(requestedLevel: Level): Boolean {

        if (!LevelPrologueView.LevelIds.isApplicable(requestedLevel.id)) return false

//            // TODO uncomment to check if landing training completed
//            if (requestedLevel.id == LevelPrologueView.LevelIds.LANDING_TRAINING) {
//                levelPrologueView.apply {
//                    if (loadLandingTraining(requestedLevel)) {
//                        levelPrologueView.bringToFront()
//                        levelPrologueView.visibility = VISIBLE
//                    }
//                }
//                return true
//            }

        levelPrologueView.apply {
            if (load(requestedLevel)) {
                bringToFront()
                visibility = VISIBLE
                return true
            }
        }
        return false
    }

    fun replayLastFlight() {
        val flightLog = flightLogManager.readLastFlightLogFile()

        // DEBUG: unncomment to debug mission summary
//        currentLevel = levelManager.levelIdLookup[57]!!
//        showMissionSummaryAfterMission(flightLog!!, false)
//        return

        if (flightLog != null) {
            val replayLevel = levelManager.levelIdLookup[flightLog.levelId]

            if (replayLevel != null) {
                replayLevel.reset()
                currentLevel = replayLevel // Level(-1, null, Level.LevelType.MISSION, -1, -1, replayLevel.world)
                openLevel(currentLevel!!, true, flightLog, null, false, false)

                if (globalSettings.logReplayStarted) {
                    logMiscActivity(ActivityCode.REPLAY_STARTED, flightLog.levelId, "Last flight")
                }
            }
        }
        else {
            adminLogView.printout("ERROR: No such flight log found")
        }
    }

    fun replaySavedFlightLog(filename: String) {
        showReplayManager(false)
        val flightLog = flightLogManager.readSavedReplayFlightLogFile(filename)

        if (flightLog != null) {
            val replayLevel = levelManager.levelIdLookup[flightLog.levelId]

            if (replayLevel != null) {
                replayLevel.reset()
                currentLevel = replayLevel // Level(-1, null, Level.LevelType.MISSION, -1, -1, replayLevel.world)
                openLevel(currentLevel!!, true, flightLog, filename, false, false)

                if (globalSettings.logReplayStarted) {
                    logMiscActivity(ActivityCode.REPLAY_STARTED, flightLog.levelId, "Saved replay")
                }
            }
        }
        else {
            adminLogView.printout("ERROR: No such flight log found")
        }
    }

    fun replayDownloadedFlightLog(flightLog: FlightLog, callSign: String?, admin: Boolean) {
        val replayLevel = levelManager.levelIdLookup[flightLog.levelId]

        if (replayLevel != null) {
            replayLevel.reset()
            currentLevel = replayLevel // Level(-1, null, Level.LevelType.MISSION, -1, -1, replayLevel.world)
            openLevel(currentLevel!!, true, flightLog, null, false, true)

            if (!admin && globalSettings.logReplayStarted) {
                logMiscActivity(ActivityCode.REPLAY_STARTED, flightLog.levelId, "Downloaded replay")
            }
        }
    }

    fun unloadGLView() {
        if (gLView != null) {
            gLView!!.onPause()
            mainLayout.removeView(gLView!!)
            gLView!!.renderer.onDestroyRenderer()
            gLView = null
        }
//        if (mainLayout.contains(screenOverlay)) {
//            mainLayout.removeView(screenOverlay)
//        }
        screenOverlay.visibility = GONE
        screenOverlay.stopCountdownTicker()
        screenOverlay.showScreenAnnotations(false)
        levelBuilderToolbar.visibility = GONE
        currentReplayFlightLog = null

        setKeepScreenOn(false)
    }

    fun exitLevel() {

        unloadGLView()

        levelActive = false
        levelOpen = false
        hideAllViews()

        if (!levelEditorMode) {

            when (currentlyUsedFeature) {
                Feature.MISSIONS -> showLevelsView(Level.LevelType.MISSION)
                Feature.MILKRUNS -> showLevelsView(Level.LevelType.MILKRUN)
                Feature.REPLAYS -> showReplayManager(true)
                else -> showHomeView()
            }
        } else {
            levelManager.loadLevelsFromInternalStorage()
            levelEditorView.bringToFront()
            levelEditorView.visibility = VISIBLE
            setCurrentFeature(Feature.ADMIN_LEVELS)
        }

        onEnteredPassiveMode()
    }


    fun completeLevel(replayMode: Boolean) {
        if (!levelEditorMode && gLView != null && currentLevel != null) {
            levelCompletionManager.completeLevel(currentLevel!!, gLView!!.flightLog, replayMode)
        }
    }

    private fun wrapPi(a: Float): Float {
        var x = a
        val pi = Math.PI.toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()
        while (x > pi) x -= twoPi
        while (x < -pi) x += twoPi
        return x
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_GRAVITY) return
        if (gLView == null || !gLView!!.flightControls.gyroMode) return

        var gx = e.values[0]
        var gy = e.values[1]
        val gz = e.values[2]

        // Keep controls consistent across the two landscape rotations
        val rot = this.display?.rotation ?: Surface.ROTATION_0
        if (rot == Surface.ROTATION_270) {
            gx = -gx
            gy = -gy
        }

        // Compute "tilt angles" from gravity vector
        // Pitch-like angle (rotation around device Y-ish axis, using X vs Z)
        val pitchRad = atan2(gx, gz)

        // Roll-like angle (rotation around device X-ish axis, using Y vs Z)
        val rollRad = atan2(gy, gz)

        if (gLView!!.flightControls.setDeviceNeutral) {
            neutralPitchRad = pitchRad
            neutralRollRad = rollRad
            gLView!!.flightControls.setDeviceNeutral = false
        }

        // Delta angles relative to neutral (wrapped so crossing ±π doesn’t jump)
        val dPitch = wrapPi(pitchRad - neutralPitchRad)
        val dRoll  = wrapPi(rollRad  - neutralRollRad)

        // Map angle deltas to [-1, 1]
        val maxTiltRad = Math.toRadians(45.0).toFloat() // tune: 30–60° feels common
        gLView!!.flightControls.rotationVert =
            (-dPitch / maxTiltRad * rotationalFactor).coerceIn(-1f, 1f)

        gLView!!.flightControls.rotationHorz =
            (dRoll / maxTiltRad * rotationalFactor).coerceIn(-1f, 1f)

        screenOverlay.attitudeDisplay.update(
            gLView!!.flightControls.rotationHorz,
            gLView!!.flightControls.rotationVert
        )
    }


    fun updateRotSensitivity(value: Int) {
        rotationalFactor = getSensitivityFactor(value, 0.5f, 3f)
//        println("value=$value -> rotFactor=$rotationalFactor")
    }

    fun getSensitivityFactor(value: Int, minSensitivity: Float, maxSensitivity: Float): Float {
        val minValue = 1
        val maxValue = 9

        val gradient = (maxSensitivity - minSensitivity) / (maxValue - minValue)
        val intercept = minSensitivity - minValue * gradient
        return gradient * value + intercept
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun setNeutral() {
        if (gLView != null) {
            gLView!!.flightControls.setDeviceNeutral = true
            Toast.makeText(this, "Neutral pitch set", Toast.LENGTH_SHORT).show()
        }
    }


    fun resetGame() {
        if (gLView != null) {
            if (screenOverlay.screenAnnotationsActive) {
                screenOverlay.showScreenAnnotations(true)
            }
            gLView!!.renderer.resetGame()
            gLView!!.setPause(false)
        }
    }

    fun showHomeView() {
        hideAllViews()
        audioPlayer.startMusic(0)
        homeView.visibility = VISIBLE
        homeView.bringToFront()
        setCurrentFeature(Feature.HOME)
    }

    fun setCurrentFeature(currentFeature: Feature) {
        this.currentlyUsedFeature = currentFeature
        getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
            putString(LAST_USED_FEATURE, currentFeature.name)
        }
        if (currentFeature == Feature.HOME) {
            onBackPressedCallback.isEnabled = false
        }
        else {
            onBackPressedCallback.isEnabled = true
        }
    }

    fun showStoryView(playIntro: Boolean) {
        hideAllViews()
        storyView.load(playIntro)
        storyView.visibility = VISIBLE
        storyView.bringToFront()

        if (playIntro && globalSettings.logStoryViewed) {
            logMiscActivity(ActivityCode.STORY_VIEWED, null, "")
        }

        setCurrentFeature(Feature.STORY)

    }

    fun showTrainingView() {
        hideAllViews()
        trainingView.visibility = VISIBLE
        trainingView.bringToFront()

        setCurrentFeature(Feature.TRAINING)
    }

    fun showReplayManager(show: Boolean) {
        if (show) {
            hideAllViews()
            replayManager.load()
            replayManager.visibility = VISIBLE
            replayManager.bringToFront()
            setCurrentFeature(Feature.REPLAYS)
        }
        else {
            replayManager.visibility = INVISIBLE
        }
    }

    fun showTrainingOutcomeView() {
        setPauseGame(true)

        trainingOutcomeView.configureForLevelIndex(
            if (currentLevel != null) currentLevel!!.index else -1,
            !isMandatoryTrainingComplete()
        )
        trainingOutcomeView.visibility = VISIBLE
        trainingOutcomeView.bringToFront()
    }

    fun closeTrainingOutcomeView() {
        trainingOutcomeView.visibility = GONE
    }

    fun refreshAllLevels() {
        levelsView.loadLevels(Level.LevelType.MILKRUN)
        levelsView.loadLevels(Level.LevelType.MISSION)
    }

    fun refreshCurrentLevelsView() {
        if (currentlyUsedFeature == Feature.MILKRUNS) {
            levelsView.loadLevels(Level.LevelType.MILKRUN)
        }
        else if (currentlyUsedFeature == Feature.MISSIONS) {
            levelsView.loadLevels(Level.LevelType.MISSION)
        }
    }

    fun landingTrainingCompleted() {
        println("landing training requirement complete")

        if (trainingLandingCompleteView.requestedLevel != null) {

            // flag mandatory landing training completed
            getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                putBoolean(LANDING_TRAINING_COMPLETED, true)
            }

            trainingLandingCompleteView.postDelayed({
                trainingLandingCompleteView.load()
                trainingLandingCompleteView.bringToFront()
                trainingLandingCompleteView.visibility = View.VISIBLE
            }, 1000)

        }
    }

    fun showLevelsView(levelType: Level.LevelType, gotoMissionLevelId: Int? = null) {
        levelsView.loadLevels(levelType, gotoMissionLevelId = gotoMissionLevelId)
        hideAllViews()
        levelsView.visibility = VISIBLE
        levelsView.bringToFront()

        if (audioPlayer.currentMusicIndex == null) audioPlayer.startMusic(0)

        if (levelType == Level.LevelType.MISSION) {
            setCurrentFeature(Feature.MISSIONS)
        } else if (levelType == Level.LevelType.MILKRUN) {
            setCurrentFeature(Feature.MILKRUNS)
        }
    }

    fun refreshAllLevelLists() {
        levelsView.loadLevels(Level.LevelType.MISSION)
        levelsView.loadLevels(Level.LevelType.MILKRUN)
        levelsView.loadLevels(Level.LevelType.TRAINING)
    }

    fun setPauseGame(pause: Boolean) {
        if (gLView != null) {
            levelActive = !pause
            gLView!!.setPause(pause)
        }
    }

    fun openLevelEditorView() {
        levelEditorMode = true
        levelManager.loadLevelsFromInternalStorage()
        levelEditorView.loadLevels()
        hideAllViews()
        levelEditorView.visibility = VISIBLE
        levelEditorView.bringToFront()
        setCurrentFeature(Feature.ADMIN_LEVELS)
    }

    fun closeLevelEditorView() {
        levelEditorView.visibility = GONE
        levelEditorMode = false
        homeView.visibility = VISIBLE
        showAdminView()
    }

    fun showLevelBuilderToolbar() {
        if (gLView != null) {
            levelBuilderToolbar.visibility = VISIBLE
            levelBuilderToolbar.load(gLView!!, gLView!!.level, gLView!!.renderer.camera)
            levelBuilderToolbar.bringToFront()
        }
    }

    fun closeLevelBuilderToolbar() {
        levelBuilderToolbar.opened = false
        levelBuilderToolbar.visibility = GONE
    }

    fun showLevelBuilderMetadata(level: Level?) {
        levelEditorMetadataView.load(levelManager, level)
        levelEditorMetadataView.visibility = VISIBLE
        levelEditorMetadataView.bringToFront()
    }


    fun closeLevelBuilderMetadata() {
        setPauseGame(false)
        levelEditorView.loadLevels()
        levelEditorMetadataView.visibility = GONE
    }


    fun showSettings() {
        setPauseGame(true)
        settingsView.loadScreen(gLView)
        settingsView.bringToFront()
        settingsView.visibility = VISIBLE
        setCurrentFeature(Feature.SETTINGS)
    }

    fun closeSettings() {
        settingsView.visibility = GONE
        setPauseGame(false)
    }

    fun showAdminView() {
        adminView.load()
        adminView.visibility = VISIBLE
        adminView.bringToFront()
        setCurrentFeature(Feature.ADMIN)
    }

    fun closeAdminView() {
        adminView.visibility = GONE
        setCurrentFeature(Feature.SETTINGS)
    }

    fun showAdminLogView() {
        adminLogView.load()
        adminLogView.visibility = VISIBLE
        adminLogView.bringToFront()
    }

    fun closeAdminLogView() {
        adminLogView.visibility = GONE
    }


    fun showPauseMission() {
        if (gLView != null) {
            gLView!!.setPause(true)
            levelActive = false
            pauseMissionView.bringToFront()
            pauseMissionView.visibility = VISIBLE

            // DEBUG: uncomment to write to log file on pause
//        if (!gLView!!.renderer.ship.flightLog.replayActive) {
//            gLView!!.renderer.ship.flightLog.stopRecording()
//            gLView!!.renderer.ship.flightLog.endTime = Calendar.getInstance().time
//            flightLogManager.writeFlightLogfile(gLView!!.renderer.ship.flightLog)
//        }
        }
    }

    fun closePauseMission() {
        if (gLView != null) {
            pauseMissionView.visibility = GONE
            levelActive = true
            gLView!!.setPause(false)
            if (levelEditorMode) {
                levelEditorView.loadLevels()
                if (currentLevel != null) {
                    levelsView.loadLevels(currentLevel!!.type)
                } else {
                    adminLogView.printout("ERROR: could not determine current level type (currentLevel is null)")
                    showHomeView()
                }
            }
        }
    }

    fun showNewUserNotice() {
        if (newUserNotice == null) {
            newUserNotice = NewUserNotice(this)
        }
        val createNewUserNoticeParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.MATCH_PARENT)
        newUserNotice!!.layoutParams = createNewUserNoticeParams
        newUserNotice!!.load()

        if (!mainLayout.contains(newUserNotice!!)) {
            mainLayout.addView(newUserNotice!!)
        }
    }
    fun closeNewUserNotice() {
        if (newUserNotice != null) {
            mainLayout.removeView(newUserNotice)
            newUserNotice = null
        }
    }

    fun openEditCallSignView(caller: EditCallSignCaller) {
        if (editCallSignView == null) {
            editCallSignView = EditCallSignView(this)
        }
        if (!mainLayout.contains(editCallSignView!!)) {
            val createEditCallsignParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.MATCH_PARENT)
            editCallSignView!!.layoutParams = createEditCallsignParams
            mainLayout.addView(editCallSignView!!)
        }
        editCallSignView!!.load(caller)
    }

    fun closeEditCallSignView() {
        if (editCallSignView != null) {
            mainLayout.removeView(editCallSignView)
            editCallSignView = null
        }
    }

    fun showInstallDialog(closeable: Boolean, message: String) {
        if (installDialog == null) {
            installDialog = InstallDialogView(this)
        }
        val installDialogNoticeParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.MATCH_PARENT)
        installDialog!!.layoutParams = installDialogNoticeParams
        installDialog!!.load(closeable, message)

        if (!mainLayout.contains(installDialog!!)) {
            mainLayout.addView(installDialog!!)
        }
    }
    fun closeInstallDialog() {
        if (installDialog != null) {
            mainLayout.removeView(installDialog)
            installDialog = null
        }
    }

    private fun maybeShowUpdateReadyUi() {
        if (gLView != null) return
        if (!inAppUpdate.flexibleUpdateDownloaded) return
        if (updatePromptShownThisPassiveSession) return

        updatePromptShownThisPassiveSession = true

        // simplest: AlertDialog
        AlertDialog.Builder(this)
            .setTitle("Update ready")
            .setMessage("A new version has been downloaded. Restart to apply it.")
            .setPositiveButton("Restart") { _, _ ->
                inAppUpdate.completeFlexibleUpdateIfReady()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    fun hideAllViews() {
        pauseMissionView.visibility = GONE
        trainingLandingCompleteView.visibility = GONE
        adminView.visibility = GONE
        adminLogView.visibility = GONE
        levelEditorMetadataView.visibility = GONE
        levelBuilderToolbar.visibility = GONE
        settingsView.visibility = GONE
        trainingOutcomeView.visibility = GONE
        trainingView.visibility = INVISIBLE
        levelsView.visibility = INVISIBLE
        screenOverlay.visibility = GONE
        homeView.visibility = INVISIBLE
        storyView.visibility = INVISIBLE
        replayManager.visibility = GONE
        levelSummaryView.visibility = GONE
    }

    fun logMiscActivity(activityCode: ActivityCode, levelId: Int?, data: String) {
        val currentUserId: Int
        if (userId != null) {
            currentUserId = userId!!
        }
        else {
            currentUserId = -1
        }
        Thread({ Networker(this, getHostServer(this)).logActivity(currentUserId, activityCode, levelId, data, null) }).start()
    }

    override fun onPause() {
        super.onPause()
        if (gLView != null) {
            gLView!!.setPause(true)
        }
        if (audioPlayer.player.isPlaying) {
            audioPlayer.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (gLView != null) {

            if (levelActive) {
                gLView!!.setPause(false)
            } else {
                gLView!!.requestRender()
            }
        }
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (audioPlayer.paused) {
            audioPlayer.startMusic()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (gLView == null && !levelOpen && !levelActive) {
            onEnteredPassiveMode()
        }
    }

    override fun onDestroy() {

        println("LIFECYCLE, MainActivity.onDestroy hash=${System.identityHashCode(this)} changing=$isChangingConfigurations")
        inAppUpdate.dispose()
        super.onDestroy()
    }

    override fun handleNetworkMessage(msg: Message) {
        when (msg.what) {
            NetworkResponse.TEST_OK.value -> {
                val testConnectionResponse = msg.obj as Networker.TestConnectionResponse
                adminLogView.printout("Server '${testConnectionResponse.exoServerVersion}' available; database '${testConnectionResponse.database}' available")
            }
            NetworkResponse.GET_GLOBAL_SETTINGS.value -> {
                val rawGlobalSettings = msg.obj as List<Networker.GlobalSetting>
                globalSettings.load(rawGlobalSettings)
                adminLogView.printout("Global settings retrieved:")
                adminLogView.printout(globalSettings.displayAll())

                // Decide whether this install is below minimum
                val minVc = globalSettings.minimumAppVersionCode

                // Only act if the server actually sent a sensible value
                if (minVc > 0 && minVc != lastMinVersionSeen) {
                    lastMinVersionSeen = minVc
                    mustUpdateFromServer = appVersionCode < minVc

                    adminLogView.printout("Current app version code = $appVersionCode")
                    if (mustUpdateFromServer) {
                        adminLogView.printout("Immediate update required")
                    }
                }

            }

            -1, -2, -3 -> {
                adminLogView.printout("Server Error: [${msg.what}] ${msg.obj}")
            }
            -4 -> {
                adminLogView.printout("Network error occured: [${msg.what}] ${msg.obj}")
            }
        }

    }

    fun hideKeyboard(view: View) {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun isInstalledFromPlayStore(): Boolean {
        return try {
            val pm = packageManager
            val pkg = packageName

            val installer = if (android.os.Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }

            installer == "com.android.vending"
        } catch (_: Exception) {
            false
        }
    }

}

class OnBackPressedCallback(val mainActivity: MainActivity):  OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
        when (mainActivity.currentlyUsedFeature) {
            Feature.TRAINING,
            Feature.STORY,
            Feature.SETTINGS -> mainActivity.showHomeView()
            Feature.MISSIONS,
            Feature.MILKRUNS,
            Feature.LEVEL_BUILDER,
            Feature.LEVEL_TESTING,
            Feature.REPLAYS -> {
                if (mainActivity.levelOpen) {
                    if (mainActivity.pauseMissionView.isVisible) {
                        mainActivity.closePauseMission()
                    }
                    else {
                        mainActivity.showPauseMission()
                    }
                } else {
                    mainActivity.showHomeView()
                }
            }
            Feature.ADMIN -> mainActivity.closeAdminView()
            Feature.ADMIN_LEVELS -> mainActivity.closeLevelEditorView()
            else -> {}
        }
    }

}


enum class ActivityCode(val value: Int) {
    LEVEL_FAIL(0),
    LEVEL_SUCCESS(1),
    USER_UPDATED(2),
    REPLAY_STARTED(3),
    CAMERA_TRACK_CREATED(4),
    APP_REVIEW_REQUESTED(5),
    PLAYER_RANKING_REQUESTED(7),
    NOTICEBOARD_REQUESTED(8),
    STORY_VIEWED(10),
    APP_VERSION_CHANGE(11),
    PLATFORM_CHANGE(12),
}