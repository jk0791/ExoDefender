package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import android.util.AttributeSet
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import android.os.Handler
import android.os.Looper


enum class PausePlayButtonState {PLAY, PAUSE, RESET}

class ScreenOverlay(context: Context, attrs: AttributeSet? = null) :
    ConstraintLayout(context, attrs), JoystickDisplaylListener, OnRendererReadyListener, WriteFileRequester {

    private val mainActivity = context as MainActivity
    val replayController = ScreenOverlayReplayController(this)
    lateinit var gLView: GameSurfaceView
    lateinit private var currentLevel: Level
    private val uiHandler = Handler(Looper.getMainLooper())
    private var countdownRunning = false


    val throttle: ThrottleDisplay
    val tranDisplay: JoystickDisplay
    val attitudeDisplay: JoystickDisplay

    private var homeButton: ImageView

    private val levelBuilderToolbarButton: ImageView
    private val actorStatsDisplay: ActorStatsDisplay
    private val shipHealthDisplay: ShipHealthDisplay
    private val civiliansOnboardDisplay: CiviliansOnboardDisplay

    private var gameHUDShowing = true

    val structureCountdownDisplay: TimerCountdownDisplay
    val cameraModeLayout: LinearLayout
    private val chaseModeButton: ImageView
    private val trackModeButton: ImageView
    private val fixedModeButton: ImageView
    private val replayEditorButton: ImageView
    private val addCameraButton: ImageView
    private val zoomOutButton: ImageView
    private val zoomInButton: ImageView
    private var zoomLevelText: TextView
    private val saveFlightLogButton: ImageView
    val mapReplaySeekBarLayout: ConstraintLayout
    private val mapReplaySeekBar: SeekBar
    val markerOverlay: CameraTrackMarkerView
    private val pausePlayButton: ImageView
    private var pausePlayButtonState = PausePlayButtonState.PAUSE
    private var isProgrammaticSeekbarChange = true
    private var isReplayEnded = false

    var replayMode = false
    var levelBuilderMode = false
    var replayStudioEnabled = false
    var replayEditorVisible = false
    var downloadedReplay = false

    private var savedFlightLogFilename: String? = null
    var screenAnnotationsActive = false
    var manadatoryTrainingActive = false
    private val screenAnnotations: ScreenAnnotations

    private var hasShownReplayHintThisSession = false


    // Full replay duration (unchanged)
    var timelineTotalMs = 1

    // Visible window over that full timeline
    var windowStartMs: Int = 0
    var windowDurationMs: Int = timelineTotalMs   // start fully zoomed out

    // Limits for zoom
    private val minWindowDurationMs = 2_000   // e.g. 2 seconds for fine detail
    private val maxWindowDurationMs get() = timelineTotalMs

    var lastClickedEvent: CameraEvent? = null
    var lastClickedSide: MarkerClickSide? = null

    init {
        inflate(context, R.layout.screen_overlay, this)

//        setWillNotDraw(false)

        throttle = findViewById(R.id.throttle)
        tranDisplay = findViewById(R.id.tranDisplay)
        attitudeDisplay = findViewById(R.id.attitudeDisplay)

        homeButton = findViewById(R.id.homeButton)
        homeButton.setOnClickListener {
            mainActivity.showPauseMission()
        }

        levelBuilderToolbarButton = findViewById(R.id.btnLevelBuilderToolbar)
        levelBuilderToolbarButton.visibility = GONE
        levelBuilderToolbarButton.setOnClickListener({
            if (!mainActivity.levelBuilderToolbar.opened) {
                mainActivity.showLevelBuilderToolbar()
            }
            else {
                mainActivity.closeLevelBuilderToolbar()
            }
        })

        actorStatsDisplay = findViewById(R.id.actorStatsDisplay)
        shipHealthDisplay = findViewById(R.id.shipHealthDisplay)
        civiliansOnboardDisplay = findViewById(R.id.civiliansOnboardDisplay)

        mapReplaySeekBarLayout = findViewById(R.id.mapReplaySeekBarLayout)
        mapReplaySeekBar = findViewById(R.id.mapReplaySeekBar)
        mapReplaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                if (!isProgrammaticSeekbarChange) {
                    gLView.renderer.flightLog.pausedBeforeSeeking = gLView.renderer.paused && !isReplayEnded
                    gLView.renderer.flightLog.replaySeeking = true

                    setReplayEndState(false)
                    gLView.setPause(false)
                    setDisplayFrozen(false)
                    gLView.renderer.replaySeekBarStarted()
                    replayController.showReplayControls(immediate = true)

                    mainActivity.closeMissionSummary()
                }
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!isProgrammaticSeekbarChange) {

                    val renderer = gLView.renderer
                    gLView.queueEvent {
                        renderer.replaySeekBarChanged(timeFromSeekbarProgress(progress), false)
                    }
                    replayController.showReplayControls()
                    updateScreenDisplay()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {

                if (!isProgrammaticSeekbarChange) {

                    val renderer = gLView.renderer
                    gLView.queueEvent {
                        renderer.replaySeekBarChanged(timeFromSeekbarProgress(mapReplaySeekBar.progress), true)
                    }

                    // return to pre-seek pause state
                    gLView.setPause(gLView.renderer.flightLog.pausedBeforeSeeking)
                    gLView.renderer.flightLog.replaySeeking = false
                    replayController.scheduleAutoHide()
                }
            }
        })

        structureCountdownDisplay = findViewById(R.id.structureCountdownDisplay)
        cameraModeLayout = findViewById(R.id.cameraModeLayout)

        pausePlayButton = findViewById(R.id.mapReplayPlayPauseButton)
        pausePlayButton.setImageResource(R.drawable.map_pause_button)
        pausePlayButtonState = PausePlayButtonState.PAUSE
        pausePlayButton.setOnClickListener({
            setReplayPause(pausePlayButtonState == PausePlayButtonState.PAUSE)
        })

        addCameraButton = findViewById<ImageView>(R.id.addCameraEvent).apply { setOnClickListener { addCameraButtonClicked() }}

        zoomOutButton = findViewById<ImageView>(R.id.zoomOutButton).apply { setOnClickListener { zoomOut() }}
        zoomInButton = findViewById<ImageView>(R.id.zoomInButton).apply { setOnClickListener { zoomIn() }}
        zoomLevelText = findViewById(R.id.zoomLevelText)

        saveFlightLogButton = findViewById<ImageView>(R.id.saveFlightLogButton).apply {
            setOnClickListener { saveFlightLogButtonClicked() }
        }

        chaseModeButton = findViewById<ImageView>(R.id.chaseModeButton).apply {
            setOnClickListener {
                isSelected = true
                trackModeButton.isSelected = false
                fixedModeButton.isSelected = false
                replayEditorButton.isSelected = false
                showGameHUD(true)
                cameraModeClicked(CameraMode.CHASE)
            }
            isSelected = false
        }
        trackModeButton = findViewById<ImageView>(R.id.trackModeButton).apply {
            setOnClickListener {
                isSelected = true
                chaseModeButton.isSelected = false
                fixedModeButton.isSelected = false
                replayEditorButton.isSelected = false
                showGameHUD(false)
                cameraModeClicked(CameraMode.TRACK)
            }
            isSelected = false
        }
        fixedModeButton = findViewById<ImageView>(R.id.fixedModeButton).apply {
            setOnClickListener {
                isSelected = true
                chaseModeButton.isSelected = false
                trackModeButton.isSelected = false
                replayEditorButton.isSelected = false
                showGameHUD(false)
                cameraModeClicked(CameraMode.FIXED)
            }
            isSelected = false
        }
        replayEditorButton = findViewById<ImageView>(R.id.replayStudioButton).apply {
            setOnClickListener {
                isSelected = !isSelected
                chaseModeButton.isSelected = false
                trackModeButton.isSelected = false
                fixedModeButton.isSelected = false
                showReplayEditorOverlay(isSelected && !downloadedReplay)
            }
        }

        screenAnnotations = findViewById(R.id.screenAnnotations)
        screenAnnotations.visibility = GONE

        markerOverlay = findViewById(R.id.markerOverlay)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        isProgrammaticSeekbarChange = false


        mapReplaySeekBar.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    mapReplaySeekBar.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    // Align overlay track to SeekBar's visual track
                    markerOverlay.trackInsetStartPx = mapReplaySeekBar.paddingLeft.toFloat()
                    markerOverlay.trackInsetEndPx = mapReplaySeekBar.paddingRight.toFloat()
                }
            }
        )

    }


    private val countdownTick = object : Runnable {
        override fun run() {
            updateStructureCountdown()
            if (countdownRunning) {
                uiHandler.postDelayed(this, 250L) // 4 Hz is plenty
            }
        }
    }

    fun initialize(gLView: GameSurfaceView, currentLevel: Level, levelBuilderMode: Boolean, savedFlightLogFilename: String?, downloadedReplay:Boolean) {
        this.gLView = gLView
        this.currentLevel = currentLevel
        this.replayMode = gLView.flightLog.replayActive

        this.savedFlightLogFilename = savedFlightLogFilename

        tranDisplay.initialize(this, 0.4f, 0.6f, true, false)
        attitudeDisplay.initialize(this, 0.4f, 1f, false, true)
        throttle.flightControls = gLView.flightControls
        addCameraButton.visibility = GONE
        replayEditorVisible = false
        markerOverlay.visibility = GONE
        zoomInButton.visibility = GONE
        zoomOutButton.visibility = GONE
        zoomLevelText.visibility = GONE
        showGameHUD(true)
        setDisplayFrozen(false)

        reset(levelBuilderMode)

        if (currentLevel.type == Level.LevelType.TRAINING) {
            actorStatsDisplay.showFriendlyStats(false)
            shipHealthDisplay.visibility = INVISIBLE
        }
        else {
            actorStatsDisplay.showFriendlyStats(true)
            shipHealthDisplay.visibility = VISIBLE
        }

        if (!replayMode) {
            mapReplaySeekBarLayout.visibility = GONE
            tranDisplay.visibility = VISIBLE
            attitudeDisplay.visibility = VISIBLE
            cameraModeLayout.visibility = INVISIBLE
            throttle.defaultAlpha = 0.25f
        }
        else {
            this.downloadedReplay = downloadedReplay
            isProgrammaticSeekbarChange = true
            chaseModeButton.isSelected = false
            trackModeButton.isSelected = false
            fixedModeButton.isSelected = false
            replayEditorButton.isSelected = false
            mapReplaySeekBar.max = gLView.flightLog.flightTimeMs
            mapReplaySeekBar.progress = 0
            mapReplaySeekBarLayout.visibility = VISIBLE
            throttle.defaultAlpha = 0f
            tranDisplay.visibility = INVISIBLE
            attitudeDisplay.visibility = INVISIBLE
            cameraModeLayout.visibility = VISIBLE

            timelineTotalMs = maxOf(1, gLView.flightLog.flightTimeMs)
            windowStartMs = 0
            windowDurationMs = timelineTotalMs
            markerOverlay.totalDurationMs = timelineTotalMs
            markerOverlay.windowDurationMs = windowDurationMs  // this must be assigned BEFORE markerOverlay.windowDurationMs due to coersion in the setter
            markerOverlay.windowStartMs = windowStartMs

            if (downloadedReplay) {
                saveFlightLogButton.visibility = GONE
                replayEditorButton.visibility = VISIBLE
                replayEditorButton.isSelected = true
            }

            if (!replayEditorVisible) {
                // Start hidden
                mapReplaySeekBarLayout.alpha = 0f
                mapReplaySeekBarLayout.visibility = GONE
                cameraModeLayout.alpha = 0f
                cameraModeLayout.visibility = INVISIBLE

                if (!hasShownReplayHintThisSession) {
                    hasShownReplayHintThisSession = true
                    // Show briefly like a hint, then fade away
                    post {
                        replayController.showReplayControls(immediate = true)
                        // Let it sit visible a moment, then hide
                        mapReplaySeekBarLayout.removeCallbacks(replayController.autoHideRunnable)
                        mapReplaySeekBarLayout.postDelayed({ replayController.hideReplayControls() }, 1600L)
                    }
                }
            }

        }
        if (levelBuilderMode) {
            levelBuilderToolbarButton.visibility = VISIBLE
        }
        else {
            levelBuilderToolbarButton.visibility = GONE
        }
    }

    fun showGameHUD(show: Boolean) {
        val visibility = if (show) VISIBLE else INVISIBLE
        gameHUDShowing = show

        actorStatsDisplay.visibility = visibility
        shipHealthDisplay.visibility = visibility
        civiliansOnboardDisplay.visibility = visibility
        structureCountdownDisplay.visibility = visibility
    }

    fun updateScreenDisplay() {
        actorStatsDisplay.update(
            currentLevel.world.numOfActiveNonStructFriendlies,
            currentLevel.world.numOfStartNonStructFriendlies,
            currentLevel.world.activeEnemiesScratch.size,
            currentLevel.world.enemyActors.size,
        )
        shipHealthDisplay.update(gLView.renderer.ship.getHealth())
        civiliansOnboardDisplay.update(gLView.renderer.ship.civiliansOnboard)

    }

    fun startCountdownTicker() {
        if (countdownRunning) return
        countdownRunning = true
        uiHandler.post(countdownTick)
    }

    fun stopCountdownTicker() {
        countdownRunning = false
        uiHandler.removeCallbacks(countdownTick)
    }

    private fun updateStructureCountdown() {
        if (!levelBuilderMode) {
            val s = currentLevel.world.destructibleStructure
            if (s != null && s.destructEnabled && !s.destroyed && gameHUDShowing) {
                val timeMs = gLView.renderer.flightTimeMs

                // Cinematic: countdown hits 0 at "zero" and stays at 0 during the post-zero beat.
//                val msLeftToZero = (s.destructEndMs - timeMs).coerceAtLeast(0)


                val msLeftToZero =
                    if (replayMode) {
                        currentLevel.world.flightLog?.missionLog?.msLeftToZeroAt(timeMs) ?: 0
                    } else {
                        (s.destructEndMs - timeMs).coerceAtLeast(0)
                    }

                val secondsLeft = (msLeftToZero + 999) / 1000  // ceil

                structureCountdownDisplay.visibility = VISIBLE
                structureCountdownDisplay.update(secondsLeft)

            } else {
                structureCountdownDisplay.visibility = GONE

            }
        }
    }

    fun reset(levelBuilderMode: Boolean) {
        this.levelBuilderMode = levelBuilderMode

        setDisplayFrozen(false)

        val destructibleStructure = currentLevel.world.destructibleStructure
        if (destructibleStructure != null && !levelBuilderMode) {
            structureCountdownDisplay.visibility = VISIBLE

            destructibleStructure.initialDestructSeconds?.let {
                structureCountdownDisplay.setInitial(it)
                startCountdownTicker()
            }
        }
        else {
            structureCountdownDisplay.visibility = GONE
        }
    }

    override fun joystickDisplayClicked(caller: JoystickDisplay) {
        mainActivity.setNeutral()
    }



    override fun onRendererReady() {
        if (replayMode) {
            enableReplayStudio(savedFlightLogFilename != null)
        }
    }

    fun cameraModeClicked(mode: CameraMode) {
        if (replayMode) {
            gLView.renderer.camera.posedByTrack = false
            gLView.renderer.camera.setCameraMode(mode)
        }
    }

    fun setReplayPause(pause: Boolean) {
        gLView.renderer.setReplayPause(pause)
        setDisplayFrozen(pause)
        if (pause) {
            pausePlayButtonState = PausePlayButtonState.PLAY
            pausePlayButton.setImageResource(R.drawable.map_play_button)
        }
        else {
            pausePlayButtonState = PausePlayButtonState.PAUSE
            pausePlayButton.setImageResource(R.drawable.map_pause_button)
        }
    }

    fun setReplayEndState(ended: Boolean) {
        gLView.setPause(ended)
        isReplayEnded = ended
        setDisplayFrozen(ended)
        if (!ended) {
            gLView.renderer.resetFinalizedState()
            mainActivity.closeMissionSummary()
        }
    }

    fun showScreenAnnotations(show: Boolean, levelIndex: Int? = null) {
        screenAnnotationsActive = show
        if (show) {
            levelIndex?.let {
                screenAnnotations.currentLevelIndex = it
            }
            if (screenAnnotations.currentLevelIndex != null) {
                screenAnnotations.bringToFront()
                screenAnnotations.visibility = VISIBLE
                screenAnnotations.start()
                actorStatsDisplay.visibility = INVISIBLE
                shipHealthDisplay.visibility = INVISIBLE

                return
            }
        }

        screenAnnotations.visibility = GONE
        actorStatsDisplay.visibility = VISIBLE
        shipHealthDisplay.visibility = VISIBLE
    }
    fun setMandatoryTraining(show: Boolean) {
        manadatoryTrainingActive = show
        if (manadatoryTrainingActive) {
            homeButton.visibility = GONE
        }
        else {
            homeButton.visibility = VISIBLE
        }
    }


    fun setDisplayFrozen(frozen: Boolean) {
        shipHealthDisplay.pauseFlashing(frozen)
        structureCountdownDisplay.pauseIfFlashing(frozen)
        attitudeDisplay.setDisplayState(frozen)
    }

    // REPLAY EDITOR

    fun enableReplayStudio(enable: Boolean) {
        if (enable) {
            if (replayMode && savedFlightLogFilename != null) {

                gLView.flightLog.cameraTrack = mainActivity.replayManager.readCameraTrackFile(gLView.flightLog.cameraTrackFilename())
                if (gLView.flightLog.cameraTrack == null) {
                    gLView.flightLog.cameraTrack = CameraTrack()
                }

                saveFlightLogButton.visibility = GONE
                replayEditorButton.visibility = VISIBLE
                replayStudioEnabled = true

                showReplayEditorOverlay(false)

            } else {
                mainActivity.adminLogView.printout("ERROR: attempted to enable replay studio but either not in replayMode or no savedFlightLogFilename")
            }
        }
        else {
            replayStudioEnabled = false
            if (downloadedReplay) {
                saveFlightLogButton.visibility = GONE
                replayEditorButton.visibility = VISIBLE
            }
            else {
                saveFlightLogButton.visibility = VISIBLE
                replayEditorButton.visibility = GONE
            }
        }
    }



    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // Observe the touch, but do NOT consume it.
                replayController.revealControlsFromAnyTouch()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun showReplayEditorOverlay(show: Boolean) {

        gLView.renderer.camera.posedByTrack = true

        if (show) {
            if (replayMode && replayStudioEnabled && savedFlightLogFilename != null) {
                addCameraButton.visibility = VISIBLE
                saveFlightLogButton.visibility = GONE
                replayEditorButton.isSelected = true

                cameraModeLayout.visibility = VISIBLE
                cameraModeLayout.alpha = 1f
                mapReplaySeekBarLayout.visibility = VISIBLE
                mapReplaySeekBarLayout.alpha = 1f

                replayEditorVisible = true
                zoomInButton.visibility = VISIBLE
                zoomOutButton.visibility = VISIBLE
                zoomLevelText.visibility = VISIBLE
                updateZoomLabel()
                if (gLView.flightLog.cameraTrack != null) {
                    markerOverlay.visibility = VISIBLE
                    drawReplayStudioEvents()
                    refreshMarkers(lastClickedEvent)
                }
            } else {
                mainActivity.adminLogView.printout("ERROR: attempted to show replay studio but either not in replayMode or replayStudio not enabled or no savedFlightLogFilename")
            }
        }
        else {
            addCameraButton.visibility = GONE
            replayEditorButton.isSelected = false
            replayEditorVisible = false
            zoomInButton.visibility = GONE
            zoomOutButton.visibility = GONE
            zoomLevelText.visibility = GONE
            markerOverlay.visibility = GONE
            replayController.showReplayControls(true)
            zoomOutMax()
        }
    }

    fun saveFlightLogButtonClicked() {
        if (replayMode) {
            val newCameraTrackFilename = mainActivity.flightLogManager.getUniqueFilename(gLView.flightLog)
            saveFlightLogButton.visibility = GONE

            Thread({
                FlightLogManager(mainActivity as Context).writeSavedReplayFlightLogfile(
                    gLView.flightLog,
                    newCameraTrackFilename,
                    this
                )
            }).start()
        }
    }

    fun addCameraButtonClicked() {
        addCurrentCameraEvent(gLView.renderer.flightTimeMs)
    }

    override fun notifyWriteFileRequestOutcome(msg: Message) {
        when (msg.what) {
            0 -> {
                val filename = msg.obj as String
                this.savedFlightLogFilename = filename
                enableReplayStudio(true)
                showReplayEditorOverlay(true)            }
        }
    }

    fun setSeekBarToTimeInWindowWithoutListener(timeMs: Int) {
        isProgrammaticSeekbarChange = true

        val clamped = timeMs.coerceIn(windowStartMs, windowStartMs + windowDurationMs)
        val fraction = (clamped - windowStartMs).toFloat() / windowDurationMs.toFloat()
        mapReplaySeekBar.progress = (fraction * mapReplaySeekBar.max).toInt()

        isProgrammaticSeekbarChange = false
    }

    private fun setSeekBarToTimeInWindow(timeMs: Int) {
        gLView.renderer.replaySeekBarChanged(timeMs, seekingFinished = false)
        setSeekBarToTimeInWindowWithoutListener(timeMs)
    }

    private fun timeFromSeekbarProgress(progress: Int): Int {
        val fraction = progress.toFloat() / mapReplaySeekBar.max.toFloat()
        val timeInWindow = (windowStartMs + fraction * windowDurationMs).toInt()
        // Clamp to full replay duration just in case
        return timeInWindow.coerceIn(0, timelineTotalMs)
    }

    fun updateReplaySeekbar() {
        val t = gLView.renderer.flightTimeMs
        updateWindowForPlayback()
        setSeekBarToTimeInWindowWithoutListener(t)
    }

    private fun updateWindowForPlayback() {
        val t = gLView.renderer.flightTimeMs
        val windowEndMs = windowStartMs + windowDurationMs
        val margin = (windowDurationMs * 0.1f).toInt()  // windowDurationMs * (% from edge)

        if (t > windowEndMs - margin || t < windowStartMs + margin) {
            setWindowSegmentForTime(t)
        }
    }

    private fun setWindowSegmentForTime(centerTimeMs: Int) {
        val maxDur = maxOf(minWindowDurationMs, timelineTotalMs)
        val duration = windowDurationMs.coerceIn(minWindowDurationMs, maxDur)

        // Which segment does this time belong to?
        val segIndex = centerTimeMs / duration
        var start = segIndex * duration

        if (start < 0) start = 0
        if (start + duration > timelineTotalMs) {
            start = (timelineTotalMs - duration).coerceAtLeast(0)
        }

        windowStartMs = start
        windowDurationMs = duration

        markerOverlay.totalDurationMs = timelineTotalMs
        markerOverlay.windowDurationMs = windowDurationMs  // this must be assigned BEFORE markerOverlay.windowDurationMs due to coersion in the setter
        markerOverlay.windowStartMs = windowStartMs

        val clamped = centerTimeMs.coerceIn(windowStartMs, windowStartMs + windowDurationMs)
        setSeekBarToTimeInWindowWithoutListener(clamped)

        updateZoomLabel()
    }

    private fun setWindowAroundTime(centerTimeMs: Int, newDurationMs: Int) {
        val duration = newDurationMs.coerceIn(minWindowDurationMs, maxWindowDurationMs)
        val half = duration / 2

        var start = centerTimeMs - half
        if (start < 0) start = 0
        if (start + duration > timelineTotalMs) start = timelineTotalMs - duration

        windowStartMs = start
        windowDurationMs = duration

        markerOverlay.totalDurationMs = timelineTotalMs
        markerOverlay.windowDurationMs = windowDurationMs  // this must be assigned BEFORE markerOverlay.windowDurationMs due to coersion in the setter
        markerOverlay.windowStartMs = windowStartMs

        // IMPORTANT: only update the UI, don't rewrite flightTimeMs here
        val clamped = centerTimeMs.coerceIn(windowStartMs, windowStartMs + windowDurationMs)
        setSeekBarToTimeInWindowWithoutListener(clamped)

        updateZoomLabel()

    }

    fun zoomIn() {
        val newDuration = (windowDurationMs / 2).coerceAtLeast(minWindowDurationMs)
        setWindowAroundTime(gLView.renderer.flightTimeMs, newDuration)
    }

    fun zoomOut() {
        val newDuration = (windowDurationMs * 2).coerceAtMost(maxWindowDurationMs)
        setWindowAroundTime(gLView.renderer.flightTimeMs, newDuration)
    }

    fun zoomOutMax() {
        setWindowAroundTime(gLView.renderer.flightTimeMs, maxWindowDurationMs)
    }

    fun updateZoomLabel() {
        if (timelineTotalMs <= 0 || windowDurationMs <= 0) {
            zoomLevelText.text = "-"
            return
        }

        // Actual zoom factor (physics)
        val actualZoom = timelineTotalMs.toFloat() / windowDurationMs.toFloat()

        // Max possible zoom factor for this replay, given fixed 2s min window
        val maxActualZoom = timelineTotalMs.toFloat() / minWindowDurationMs.toFloat()

        // Map [1 .. maxActualZoom] -> [1 .. 16]
        val normalizedZoom = if (maxActualZoom <= 1.0001f) {
            1f // tiny timelines: treat as unzoomable
        } else {
            val ratio = ((actualZoom - 1f) / (maxActualZoom - 1f))
                .coerceIn(0f, 1f)  // safety clamp

            // 1x at min zoom, 16x at max zoom
            1f + ratio * (16f - 1f)
        }

        zoomLevelText.text = String.format("%.1fx", normalizedZoom)
    }

    fun drawReplayStudioEvents() {
        val cameraTrack = gLView.flightLog.cameraTrack
        if (cameraTrack != null) {
            markerOverlay.markers = cameraTrack.events.mapIndexed { index, e ->
                CameraMarker(
                    event = e,
                    timeMs = e.timeMs,
                    cameraPos = e.jumpTo,
                    transitionType = e.transitionType,
                    isSelected = false // (e === selectedEvent) // however you track it
                )
            }


            markerOverlay.onMarkerClickListener = { marker, side ->
                val event = marker.event

                // Was this marker already selected before this click?
                // Is this the same marker + side as last click?
                val sameMarker = (lastClickedEvent === event)
                val sameSide = (lastClickedSide == side)
                val twoClicksInARow = sameMarker && sameSide

                if (!twoClicksInARow) {
                    // First click: select + move playhead, but NO popup yet

//                    setSeekBarToTimeInWindow(marker.timeMs)

                    // 2) Update selection visuals: select this marker + side,
                    //    deselect all others
                    markerOverlay.markers = markerOverlay.markers.map {
                        if (it.event === event) {
                            it.copy(
                                isSelected = true,
                                isTransitionSelected = (side == MarkerClickSide.TRANSITION)
                            )
                        } else {
                            it.copy(
                                isSelected = false,
                                isTransitionSelected = false
                            )
                        }
                    }
                } else {
                    // Second (or subsequent) click on already-selected marker → show popup
                    showMarkerOptionsDialog(marker, side)
                }

                // Remember this click for next time
                lastClickedEvent = event
                lastClickedSide = side

                // set Seekbar progress and renderer to timeMs
                if (lastClickedSide == MarkerClickSide.TRANSITION) {
                    setSeekBarToTimeInWindow(marker.timeMs - 10)
                }
                else {
                    setSeekBarToTimeInWindow(marker.timeMs)
                }

            }

            markerOverlay.onMarkerMovingListener = { marker, newTime ->
                val event = marker.event
                event.timeMs = newTime
                cameraTrack.events.sortBy { it.timeMs }
                (markerOverlay.markers as MutableList).sortBy { it.timeMs }
                markerOverlay.invalidate()

                if (lastClickedSide == MarkerClickSide.TRANSITION) {
                    setSeekBarToTimeInWindow(newTime - 10)
                }
                else {
                    setSeekBarToTimeInWindow(newTime)
                }
            }


            // Respond to marker drag
            markerOverlay.onMarkerMovedListener = { marker, oldTime, newTime ->
                // Update real event
                marker.event.timeMs = newTime

                // Sort CameraTrack
                cameraTrack.events.sortBy { it.timeMs }

                // Rebuild markers list from events (same timelineDurationMs)
                markerOverlay.markers = cameraTrack.events.map { e ->
                    CameraMarker(
                        event = e,
                        timeMs = e.timeMs,
                        cameraPos = e.jumpTo,
                        transitionType = e.transitionType,
                        isSelected = (e === marker.event)
                    )
                }
                // update SeekBar + replay time to follow dragged marker
//                setSeekBarToMarkerTime(marker.timeMs)
                if (lastClickedSide == MarkerClickSide.TRANSITION) {
                    setSeekBarToTimeInWindow(newTime - 10)
                }
                else {
                    setSeekBarToTimeInWindow(newTime)
                }

                mainActivity.replayManager.writeCameraTrackToFile(cameraTrack, gLView.flightLog.cameraTrackFilename())
            }

        }
    }

    private fun showMarkerOptionsDialog(marker: CameraMarker, side: MarkerClickSide) {
        val options = arrayOf(
            "Add / edit transition",
            "Remove transition",
            "Delete event"
        )

        AlertDialog.Builder(context)
            .setTitle("Camera keyframe")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onAddOrEditTransition(marker)
                    1 -> onRemoveTransition(marker)
                    2 -> onDeleteEvent(marker)
                }
            }
            .show()
    }

    private fun onAddOrEditTransition(marker: CameraMarker) {

        val cameraTrack = gLView.flightLog.cameraTrack
        if (cameraTrack != null) {
            val event = marker.event

            val types = CameraPosTransitionType.values()
            val labels = arrayOf("Linear", "Ease in", "Ease out", "Ease in/out")

            // Pre-select current type if any
            val currentIndex = event.transitionType?.ordinal ?: -1

            AlertDialog.Builder(context) // No value passed for parameter 'provider'.
                .setTitle("Transition type")
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    val chosenType = types[which]

                    // Decide what transitionTo should be
                    if (event.transitionTo == null) {
//                        event.transitionTo = event.jumpTo.copy()
                        event.transitionTo = getCurrentCameraPos()
                    }
                    event.transitionType = chosenType

                    // Rebuild / resort markers to reflect this
                    refreshMarkers(selectedEvent = event)
                    dialog.dismiss()

                    // You can call your "commit" logic or defer it:
                    mainActivity.replayManager.writeCameraTrackToFile(cameraTrack, gLView.flightLog.cameraTrackFilename())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onRemoveTransition(marker: CameraMarker) {

        val cameraTrack = gLView.flightLog.cameraTrack
        if (cameraTrack != null) {

            val event = marker.event

            event.transitionType = null
            event.transitionTo = null

            refreshMarkers(selectedEvent = event)

            // commit:
            mainActivity.replayManager.writeCameraTrackToFile(cameraTrack, gLView.flightLog.cameraTrackFilename())
        }
    }

    private fun onDeleteEvent(marker: CameraMarker) {

        val cameraTrack = gLView.flightLog.cameraTrack
        if (cameraTrack != null) {

            val event = marker.event
            AlertDialog.Builder(context)
                .setTitle("Delete keyframe")
                .setMessage("Remove this camera event?")
                .setPositiveButton("Delete") { _, _ ->
                    cameraTrack.events.remove(event)
                    cameraTrack.events.sortBy { it.timeMs }

                    refreshMarkers(selectedEvent = null)

                    // commit:
                    mainActivity.replayManager.writeCameraTrackToFile(cameraTrack, gLView.flightLog.cameraTrackFilename())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addCurrentCameraEvent(timeMs: Int) {
        val cameraTrack = gLView.flightLog.cameraTrack
        if (cameraTrack != null) {

            val newEvent = CameraEvent(timeMs, getCurrentCameraPos(), null, null)
            cameraTrack.events.add(newEvent)
            refreshMarkers(newEvent)

            mainActivity.replayManager.writeCameraTrackToFile(cameraTrack, gLView.flightLog.cameraTrackFilename())
        }
    }
    
    private fun getCurrentCameraPos(): CameraPos {
        when (gLView.renderer.camera.mode) {
            CameraMode.CHASE -> {
                return ChaseCameraPos(gLView.renderer.camera.distanceToFocalPoint)
            }
            CameraMode.TRACK -> {
                return TrackCameraPos(gLView.renderer.camera.position.copy())
            }
            CameraMode.FIXED -> {
                return FixedCameraPos(gLView.renderer.camera.position.copy(), gLView.renderer.camera.angleP, gLView.renderer.camera.angleE)
            }
        }
    }

    private fun refreshMarkers(selectedEvent: CameraEvent?) {

        val cameraTrack = gLView.flightLog.cameraTrack
        if (cameraTrack != null) {
            // Rebuild markers from track
            val newMarkers = cameraTrack.events.map { e ->
                CameraMarker(
                    event = e,
                    timeMs = e.timeMs,
                    cameraPos = e.jumpTo,
                    transitionType = e.transitionType,
                    isSelected = (e === selectedEvent),
                    isTransitionSelected = lastClickedSide == MarkerClickSide.TRANSITION
                )
            }
            markerOverlay.markers = newMarkers
        }
    }
}

