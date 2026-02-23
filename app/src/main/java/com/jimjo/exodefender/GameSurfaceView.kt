package com.jimjo.exodefender

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import androidx.core.view.MotionEventCompat
import kotlin.math.min

const val UPDATE_SCREEN = 0
const val UPDATE_REPLAY_SEEKBAR = 1
const val LEVEL_COMPLETE_LIVE_MODE = 2
const val LEVEL_COMPLETE_REPLAY_MODE = 3
const val SET_SCREEN_KEEP_ON = 4
const val LAST_FLIGHTLOG_SAVED = 5
const val RESET_GAME = 6
const val FIRST_LANDING = 7

class GameSurfaceView(context: Context) : GLSurfaceView(context), OnRendererReadyListener {

    val renderer: GameGLRenderer
    val mainActivity = context as MainActivity

    lateinit var screenOverlay: ScreenOverlay
    lateinit var handler: CustomHandler

    lateinit var level: Level
    lateinit var flightLog: FlightLog

    private var ranOnce = false

    val flightControls = FlightControls()
    private var maxTranslationControlDeflection = 0
    private var maxThrottleControlDeflection = 0

    var levelBuilderMode = false

    private var mDeltaTouchHorzLT = 0f
    private var mDeltaTouchVertLT = 0f
    private var mDeltaTouchHorzLB = 0f
    private var mDeltaTouchVertLB = 0f
    private var mDeltaTouchHorzRT = 0f
    private var mDeltaTouchVertRT = 0f
    private var mDeltaTouchHorzRB = 0f
    private var mDeltaTouchVertRB = 0f

    private var screenCenterX = 0
    private var leftVerticalDivider = 0
    private var rightVerticalDivider = 0

    private  var mPointerLB = -1
    private  var mPointerLT = -1
    private  var mPointerRB = -1
    private  var mPointerRT = -1

    private var mStartTouchXLB = 0f
    private var mStartTouchYLB = 0f
    private var mStartTouchXLT = 0f
    private var mStartTouchYLT = 0f
    private var mStartTouchXRT = 0f
    private var mStartTouchYRT = 0f
    private var mStartTouchXRB = 0f
    private var mStartTouchYRB = 0f

    private var mDownLT = false
    private var mDownLB = false
    private var mDownRT = false
    private var mDownRB = false

    private var startThrottle = 0f

    init {
        // Create an OpenGL ES 2.0 context
        setEGLContextClientVersion(2)
        renderer = GameGLRenderer()

        // Set the Renderer for drawing on the GLSurfaceView
        setRenderer(renderer)
    }

    fun initialize(screenOverlay: ScreenOverlay, modelManager: ModelManager, level: Level, flightLog: FlightLog, savedFlightLogFilename: String?, levelBuilderMode: Boolean, downloadedReplay: Boolean) {
        this.screenOverlay = screenOverlay
        handler = CustomHandler(Looper.getMainLooper(), this)
        this.level = level
        this.flightLog = flightLog
        this.levelBuilderMode = levelBuilderMode
        renderer.parent = this
        renderer.modelManager = modelManager
        flightControls.gyroMode = !flightLog.replayActive && !levelBuilderMode
        renderer.flightControls = flightControls
        flightControls.receiver = renderer
        this.level.world.flightLog = flightLog
        renderer.level = this.level
        renderer.flightLog = this.flightLog

        renderer.level.initialize(renderer)
        renderer.textAtlas = TextAtlas(context)
        renderer.initialize(context)

        screenOverlay.initialize(this, level, levelBuilderMode, savedFlightLogFilename, downloadedReplay)

        if (false) { queueEvent { renderer.enableCapture(context) } }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!ranOnce) {
            screenCenterX = this.width / 2
            leftVerticalDivider = (this.height * 0.2f).toInt()
            rightVerticalDivider = (this.height * 0.7f).toInt()

            val preferences = mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
            updateTranSensitivity(preferences.getInt(mainActivity.TRAN_SENSITIVITY_SETTING, 5))
            maxThrottleControlDeflection = (mainActivity.densityDpi * 0.8f).toInt()
            ranOnce = true
        }

    }

    override fun onRendererReady() {
        println("UI notified renderer ready...")
        screenOverlay.onRendererReady()
    }


    fun updateTranSensitivity(value: Int) {
        val factor = mainActivity.getSensitivityFactor(value, 1f, 0.05f)
        maxTranslationControlDeflection = (mainActivity.densityDpi * factor).toInt()
    }

    fun reset() {
        flightControls.reset(
            if (levelBuilderMode || flightLog.replayActive)
                FlightControls.ThrottleMode.CAMERA_CONTROL
            else
                FlightControls.ThrottleMode.GAME
        )
        screenOverlay.throttle.update()
        startThrottle = flightControls.throttle
        screenOverlay.reset(levelBuilderMode)

        mDeltaTouchHorzLT = 0f
        mDeltaTouchVertLT = 0f
        mDeltaTouchHorzLB = 0f
        mDeltaTouchVertLB = 0f
        mDeltaTouchHorzRT = 0f
        mDeltaTouchVertRT = 0f
        mDeltaTouchHorzRB = 0f
        mDeltaTouchVertRB = 0f

        mStartTouchXLB = 0f
        mStartTouchYLB = 0f
        mStartTouchXLT = 0f
        mStartTouchYLT = 0f
        mStartTouchXRT = 0f
        mStartTouchYRT = 0f
        mStartTouchXRB = 0f
        mStartTouchYRB = 0f

        mDownLT = false
        mDownRT = false
        mDownRB = false
    }

    fun setPause(pause: Boolean) {
        screenOverlay.setDisplayFrozen(pause)
        renderer.setPause(pause)

        val msg: Message

        if (pause) {
            msg = handler.obtainMessage(SET_SCREEN_KEEP_ON, 0)
            renderMode = RENDERMODE_WHEN_DIRTY
        } else {
            msg = handler.obtainMessage(SET_SCREEN_KEEP_ON, 1)
            renderMode = RENDERMODE_CONTINUOUSLY
        }
        handler.sendMessage(msg)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN  -> {
                actionDown(event)
            }
            MotionEvent.ACTION_MOVE -> {

                actionMove(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                actionUp(event)
            }
        }
//        println(flightControls)
        return true
    }

    fun actionDown(event: MotionEvent) {
        val x = MotionEventCompat.getX(event, event.actionIndex)
        val y = MotionEventCompat.getY(event, event.actionIndex)

        if (x < screenCenterX) {
            if (y < leftVerticalDivider) {
                mStartTouchXLT = x
                mStartTouchYLT = y
                mDownLT = true
                mPointerLT = event.getPointerId(event.actionIndex)
            } else {
                mStartTouchXLB = x
                mStartTouchYLB = y
                mDownLB = true
                mPointerLB = event.getPointerId(event.actionIndex)
            }
        }
        else {
            if (y < rightVerticalDivider) {
                mStartTouchXRT = x
                mStartTouchYRT = y
                mDownRT = true
                mPointerRT = event.getPointerId(event.actionIndex)
            }
            else {
                mStartTouchXRB = x
                mStartTouchYRB = y
                mDownRB = true
                startThrottle = flightControls.throttle
                mPointerRB = event.getPointerId(event.actionIndex)
            }
        }
//        println("ACTION_DOWN mDownRB=$mDownRB mDownRT=$mDownRT")
    }

    fun actionMove(event: MotionEvent) {
        val x1 = event.getX()
        val y1 = event.getY()

        if (x1 < screenCenterX) {
            if (y1 < leftVerticalDivider && mDownLT) {
                mDeltaTouchHorzLT = x1 - mStartTouchXLT
                mDeltaTouchVertLT = y1 - mStartTouchYLT
            }
            else if (mDownLB) {
                mDeltaTouchHorzLB = x1 - mStartTouchXLB
                mDeltaTouchVertLB = y1 - mStartTouchYLB
            }
        }
        else {
            if (y1 < rightVerticalDivider) {
                mDeltaTouchHorzRT = x1 - mStartTouchXRT
                mDeltaTouchVertRT = y1 - mStartTouchYRT
            }
            else {
                mDeltaTouchHorzRB = x1 - mStartTouchXRB
                mDeltaTouchVertRB = y1 - mStartTouchYRB
            }
            if (mDownRB) {
                mDeltaTouchHorzRB = x1 - mStartTouchXRB
                mDeltaTouchVertRB = y1 - mStartTouchYRB
            }
        }

        if (event.pointerCount == 2) {

            val x2 = event.getX(1)
            val y2 = event.getY(1)

            if (x2 < screenCenterX) {
                if (y2 < leftVerticalDivider && mDownLT) {
                    mDeltaTouchHorzLT = x2 - mStartTouchXLT
                    mDeltaTouchVertLT = y2 - mStartTouchYLT
                }
                else if (mDownLB) {
                    mDeltaTouchHorzLB = x2 - mStartTouchXLB
                    mDeltaTouchVertLB = y2 - mStartTouchYLB
                }
            }
            else {
                if (y2 < rightVerticalDivider) {
                    mDeltaTouchHorzRT = x2 - mStartTouchXRT
                    mDeltaTouchVertRT = y2 - mStartTouchYRT
                }
//                else {
//                    mDeltaTouchHorzRB = x2 - mStartTouchXRB
//                    mDeltaTouchVertRB = y2 - mStartTouchYRB
//                }
                else if (mDownRB) {
                    mDeltaTouchHorzRB = x2 - mStartTouchXRB
                    mDeltaTouchVertRB = y2 - mStartTouchYRB
                }
            }
        }

        if (event.pointerCount == 3) {

            val x3 = event.getX(2)
            val y3 = event.getY(2)

            if (x3 < screenCenterX) {
                if (y3 < leftVerticalDivider && mDownLT) {
                    mDeltaTouchHorzLT = x3 - mStartTouchXLT
                    mDeltaTouchVertLT = y3 - mStartTouchYLT
                }
                else if (mDownLB) {
                    mDeltaTouchHorzLB = x3 - mStartTouchXLB
                    mDeltaTouchVertLB = y3 - mStartTouchYLB
                }
            }
            else {
                if (y3 < rightVerticalDivider) {
                    mDeltaTouchHorzRT = x3 - mStartTouchXRT
                    mDeltaTouchVertRT = y3 - mStartTouchYRT
                }
//                else {
//                    mDeltaTouchHorzRB = x3 - mStartTouchXRB
//                    mDeltaTouchVertRB = y3 - mStartTouchYRB
//                }
                else if (mDownRB) {
                    mDeltaTouchHorzRB = x3 - mStartTouchXRB
                    mDeltaTouchVertRB = y3 - mStartTouchYRB
                }
            }
        }

        flightControls.translationHorz = Math.max(Math.min(mDeltaTouchHorzLB / maxTranslationControlDeflection, 1f), -1f)
        flightControls.translationVert = Math.max(Math.min(mDeltaTouchVertLB / maxTranslationControlDeflection, 1f), -1f)

        if (flightControls.gyroMode) {
            if (mDownRT && !flightControls.firing) flightControls.firingStarted()
            flightControls.firing = mDownRT
        }
        else {
            flightControls.rotationHorz = Math.max(Math.min(mDeltaTouchHorzRT / maxTranslationControlDeflection, 1f), -1f)
            flightControls.rotationVert = Math.max(Math.min(mDeltaTouchVertRT / maxTranslationControlDeflection, 1f), -1f)
        }

        if (mDownRB) {
            flightControls.throttle = Math.max(Math.min(startThrottle + mDeltaTouchHorzRB / maxThrottleControlDeflection, 1f), flightControls.minThrottle)
            screenOverlay.throttle.update()
        }
        if (mDownLB) {
            screenOverlay.tranDisplay.update(flightControls.translationHorz, flightControls.translationVert)
        }
    }

    fun actionUp(event: MotionEvent) {

        val pointerId = event.getPointerId(event.actionIndex)

//        println("ACTION_UP event.actionIndex=${event.actionIndex} pointerId=$pointerId mPointerLB=$mPointerLB mPointerRB=$mPointerRB")

        if (pointerId == mPointerLB) {
            mDownLB = false
            mDeltaTouchHorzLB = 0f
            mDeltaTouchVertLB = 0f
            flightControls.translationHorz = 0f
            flightControls.translationVert = 0f
            screenOverlay.tranDisplay.reset()
            mPointerLB = -1
        }
        else if (pointerId == mPointerLT) {
            mDownLT = false
            mPointerLT = -1
        }
        else if (pointerId == mPointerRB) {
            mDownRB = false
            mDeltaTouchHorzRB = 0f
            mDeltaTouchVertRB = 0f
            mPointerRB = -1

            // sptring throttle and update throttle display on HUD
            flightControls.springThrottleBack()
            screenOverlay.throttle.update()
        }
        else if (pointerId == mPointerRT) {
            mDownRT = false
            if (!flightControls.gyroMode) {
                flightControls.rotationHorz = 0f
                flightControls.rotationVert = 0f
            }
            flightControls.firing = false
            renderer.shipLaserBoltPool.sinceLastFired = 0
            mPointerRT = -1
        }

        mDeltaTouchHorzRT = 0f
        mDeltaTouchVertRT = 0f
    }
}

interface FlightControlReceiver{
    fun firingStarted()
}
class FlightControls(
    var gyroMode: Boolean = true,
    var throttle: Float = 0f,
    var rotationHorz: Float = 0f,
    var rotationVert: Float = 0f,
    var translationHorz: Float = 0f,
    var translationVert: Float = 0f,
    var firing: Boolean = false,
    var setDeviceNeutral: Boolean = false,
) {

    enum class ThrottleMode {CAMERA_CONTROL, GAME}

    var minThrottle = 0f
    val throttleCenter = 0.5f
    var receiver: FlightControlReceiver? = null

    var throttleMode = ThrottleMode.CAMERA_CONTROL

    fun firingStarted() {
        if (receiver != null) receiver!!.firingStarted()
    }

    fun reset(throttleMode: ThrottleMode) {
        this.throttleMode = throttleMode
        throttle = throttleCenter
        rotationHorz = 0f
        rotationVert = 0f
        translationHorz = 0f
        translationVert = 0f
        firing = false
        setDeviceNeutral = false
    }

    // springs throttle as required
    fun springThrottleBack() {

        when (throttleMode) {

            ThrottleMode.CAMERA_CONTROL ->
                throttle = throttleCenter

            ThrottleMode.GAME -> {
                throttle =
                    min(throttle, throttleCenter)
                if (throttle < throttleCenter && throttle > 0.38f) throttle = throttleCenter
                else if (throttle < 0.05f) throttle = 0f
            }
        }
    }

    override fun toString(): String {
        return "th=${df1.format(throttle)} trh=${df1.format(translationHorz)} trv= ${df1.format(translationVert)} rh=${df1.format(rotationHorz)} rv=${df1.format(rotationVert)} firing=$firing"
    }
}

class CustomHandler(looper: Looper, val gLView: GameSurfaceView): Handler(looper) {
    override fun handleMessage(msg: Message) {
        super.handleMessage(msg)
        when (msg.what) {
            RESET_GAME -> gLView.reset()
            UPDATE_SCREEN -> gLView.screenOverlay.updateScreenDisplay()
            UPDATE_REPLAY_SEEKBAR -> gLView.screenOverlay.updateReplaySeekbar()
            LEVEL_COMPLETE_LIVE_MODE -> {
                gLView.screenOverlay.setDisplayFrozen(true)
                gLView.screenOverlay.stopCountdownTicker()
                gLView.mainActivity.completeLevel(false)
            }
            LEVEL_COMPLETE_REPLAY_MODE -> {
                gLView.screenOverlay.setReplayEndState(true)
//                gLView.screenOverlay.stopCountdownTicker()
                if (!gLView.screenOverlay.replayEditorVisible) {
                    gLView.mainActivity.completeLevel(true)
                }
            }
            FIRST_LANDING -> {
                if (gLView.level.type == Level.LevelType.TRAINING && gLView.level.index == 2) {
                    gLView.mainActivity.landingTrainingCompleted()
                }
            }
            SET_SCREEN_KEEP_ON -> {
                if ((msg.obj as Int) == 1) {
                    gLView.mainActivity.setKeepScreenOn(true)
                }
                else {
                    gLView.mainActivity.setKeepScreenOn(false)
                }
            }
            LAST_FLIGHTLOG_SAVED -> {
                gLView.mainActivity.levelCompletionManager.onLastFlightLogSaved()
            }
        }
    }
}
