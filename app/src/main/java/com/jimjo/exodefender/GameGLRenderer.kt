package com.jimjo.exodefender

import android.content.Context
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Looper
import android.os.Message
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale

interface ModelParent {
    val audioPlayer: AudioPlayer
    fun shipHit(destroyed: Boolean)
    fun notifyActorDestroyed(playSound: Boolean, friendly: Boolean)

    fun civiliansOnboardChanged(
        newCount: Int,
        delta: Int
    )
}

interface OnRendererReadyListener {
    fun onRendererReady()
}

class GameGLRenderer : GLSurfaceView.Renderer, ModelParent, WriteFileRequester, FlightControlReceiver {

    lateinit var handler: CustomHandler
    lateinit var parent: GameSurfaceView

    lateinit var modelManager: ModelManager
    private lateinit var gpuModelCache: GpuModelCache

    private val vPWorld = FloatArray(16)
    private val worldProjectionMatrix = FloatArray(16)
    private val skyboxProjectionMatrix = FloatArray(16)
    private val viewBase = FloatArray(16)
    private val viewShaken = FloatArray(16)
    private val vPShip = FloatArray(16)

    val screenShake = ScreenShake()

    var mProgram: Int = -1
    private var vPMatrixHandle: Int = -1
    private var positionHandle: Int = -1
    private var mColorHandle: Int = -1
    var glowProgram = 0
    private var glowPosHandle = 0
    private var glowMvpHandle = 0
    private var glowGlowHandle = 0
    private var glowTintHandle = 0


    // CAPTURE SETTINGS
    @Volatile private var captureEnabled = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val captureFps = 60
    private var captureRemainder = 0
    private fun nextCaptureIntervalMs(): Int {
        captureRemainder += 1000
        val step = captureRemainder / captureFps   // 16 most frames, 17 occasionally
        captureRemainder -= step * captureFps
        return step
    }
    private var capW = 0
    private var capH = 0
    private var capBuf: ByteBuffer? = null
    private val CAPTURE_MAX_FRAMES = 60 * 120  // 120 seconds @ 60fps
    private var capFrameIndex = 0
    private lateinit var capDir: File




    var paused = false
    var evacCompletionArmed = false
    private lateinit var skybox: Skybox
    lateinit var textAtlas: TextAtlas
    private val debugLabel = ScreenLabel(24)
    lateinit var flightControls: FlightControls
    lateinit var camera: Camera
    lateinit var ship: ShipActor
    lateinit var shipExplosion: Explosion
    lateinit var enemyExplosion: Explosion
    lateinit var friendlyExplosion: Explosion
    lateinit var structureExplosionPool: ExplosionPool
    private val explosionFlash = ExplosionFlashSystem(maxFlashes = 64)

    lateinit var flightLog: FlightLog
    private val cameraSample = CameraSample()

    val shipLaserBoltPool = DoubleLaserBoltPool(60)
    val enemyLaserBoltPool = SingleLaserBoltPool(LaserBolt.SourceType.ENEMY, 40, 10f)
    val friendlyLaserBoltPool = SingleLaserBoltPool(LaserBolt.SourceType.FRIENDLY, 40, 5f)

    lateinit var level: Level

    private val mapGrid = MapGrid()
    override val audioPlayer: AudioPlayer
        get() = parent.mainActivity.audioPlayer

    private var currentMillis: Long = System.currentTimeMillis()
    private var intervalMs = 0
    private var interval = 0f
    private var lastTimeStartMs: Long = 0
    var flightTimeMs = 0
    var flightTimeBeforeLastPauseMs = 0

    var fps = 0
    var frameCount = 0
    var flightTimeMsLastReplayUpdate = 0
    var flightTimeMsLastFrameCount = 0

    private var flash = false
    private var flashLong = false
    private var flashFrameCountdown = 10

    private var endOfFrameChecksScheduled = false

    private var liveLevelCompleted = false
    private var levelCompletedCountdownMs = 0
    private var finalized = false

    fun initialize(context: Context) {
        handler = CustomHandler(Looper.getMainLooper(), parent)
    }

    fun resetGame() {
        level.reset()
        ship.reset()

        evacCompletionArmed = false

        camera.setCameraMode(CameraMode.CHASE)
        camera.updateForChase()

        if (flightLog.cameraTrack != null) {
            camera.posedByTrack = true
        }

        shipExplosion.active  = false
        enemyExplosion.active = false
        friendlyExplosion.active = false

        flash = false
        flashLong = false
        flashFrameCountdown = 3

        currentMillis = System.currentTimeMillis()
        lastTimeStartMs = System.currentTimeMillis()
        flightTimeMs = 0
        flightTimeMsLastReplayUpdate = 0
        flightTimeMsLastFrameCount = 0
        liveLevelCompleted = false
        levelCompletedCountdownMs = 0
        resetFinalizedState()

        endOfFrameChecksScheduled = false
        handler.sendEmptyMessage(RESET_GAME)
        parent.screenOverlay.throttle.update(flightControls.throttle)

        if (flightLog.replayActive) {
            flightLog.reset()
            ship.setPosition(ship.initialPosition)
            handler.sendEmptyMessage(UPDATE_REPLAY_SEEKBAR)

        }
        else if (parent.levelBuilderMode) {
            ship.chaseFocalPointWorld.set(ship.position)
            camera.updateForChase()
        }
        else {
            flightLog.clear()
            flightLog.enemyThreatSum = level.getThreatSum()
            flightLog.enemiesStart = level.world.enemyActors.size
            flightLog.friendliesStart = level.world.friendlyActors.size
            flightLog.startRecording()
        }
        scheduleEndOfFrameChecks()

        // radio
        audioPlayer.resetRadioSchedule()
        val shouldEnableRadioForThisLevel =
            (level.type == Level.LevelType.MISSION || level.type == Level.LevelType.MILKRUN) &&
            parent.mainActivity.isRadioSettingEnabled()
        audioPlayer.setRadioEnabled(shouldEnableRadioForThisLevel)
        audioPlayer.radio.onMissionStart(flightTimeMs.toLong())

        parent.setPause(paused)

    }

    // do not call this directly, instead call GameSurfaceView.setPause()
    fun setPause(pause: Boolean) {
        if (pause) {
            flightTimeBeforeLastPauseMs = flightTimeMs
            flightLog.pauseRecording()
            paused = true

        } else {
            paused = false
            lastTimeStartMs = System.currentTimeMillis()
            currentMillis = System.currentTimeMillis()
            intervalMs = 0
            flightLog.unpauseRecording()
        }
    }

    fun setReplayPause(pause: Boolean) {
        if (pause) {
            flightTimeBeforeLastPauseMs = flightTimeMs
            flightLog.replayPaused = true

        } else {
            flightLog.replayPaused = false
            lastTimeStartMs = System.currentTimeMillis()
            currentMillis = System.currentTimeMillis()
            intervalMs = 0
        }
    }

    override fun shipHit(destroyed: Boolean) {

        flash = true
        if (destroyed) {
            parent.mainActivity.audioPlayer.playSound(parent.mainActivity.audioPlayer.explosion2)
            flashLong = true
            flashFrameCountdown = 40
            if (!flightLog.replayActive) {
                liveLevelCompleted = true
                parent.mainActivity.haptics.crash()
                audioPlayer.radio.onShipDestroyed(flightTimeMs.toLong())
            }
            flightLog.completionOutcome = CompletionOutcome.FAILED_DESTROYED
            levelCompletedCountdownMs = 1200
        }
        else {
            parent.mainActivity.audioPlayer.playSound(parent.mainActivity.audioPlayer.explosion3)
            if (!flightLog.replayActive) {
                parent.mainActivity.haptics.hit()
            }
        }

        if (!flightLog.replaySeeking) scheduleEndOfFrameChecks()

    }

    override fun notifyActorDestroyed(playSound: Boolean, friendly: Boolean) {
        if (playSound) {
            parent.mainActivity.audioPlayer.playSound(parent.mainActivity.audioPlayer.explosion1)
        }

        if (friendly) {
            audioPlayer.radio.onFriendlyKilled(flightTimeMs.toLong())
        }
        else {
            audioPlayer.radio.onEnemyKilled(flightTimeMs.toLong())

        }

        if (!flightLog.replaySeeking) scheduleEndOfFrameChecks()
    }

    override fun civiliansOnboardChanged(
        newCount: Int,
        delta: Int
    ) {
        // TODO sounds and radio calls for boarding/disembarking

        scheduleEndOfFrameChecks()
    }

    fun scheduleEndOfFrameChecks() {
        endOfFrameChecksScheduled = true
    }


    fun endOfFrameChecks() {

        if (liveLevelCompleted) return

        if (level.objectiveType == Level.ObjectiveType.CAS || level.objectiveType == Level.ObjectiveType.UNKNOWN) {
            if (level.world.activeFriendliesScratch.size == 0 && level.world.friendlyActors.size != 0) {
                if (!flightLog.replayActive) {
                    liveLevelCompleted = true
                    flightLog.completionOutcome = CompletionOutcome.FAILED_ZERO_FRIENDLIES
                    levelCompletedCountdownMs = 500
                }
            } else if (level.world.activeEnemiesScratch.size == 0 && level.world.enemyActors.size != 0 && ship.active) {

                // mission completion
                for (friendlyActor in level.world.activeFriendliesScratch) {
                    friendlyActor.startFlashSignal(flightTimeMs)
                }
                if (!flightLog.replayActive) {
                    liveLevelCompleted = true
                    flightLog.completionOutcome = CompletionOutcome.SUCCESS
                    levelCompletedCountdownMs = 4000

                    audioPlayer.radio.onMissionComplete(flightTimeMs.toLong())
                }
            }
        }
        else if (level.objectiveType == Level.ObjectiveType.EVAC) {
            val d = level.world.destructibleStructure
            if (d != null) {

                val remaining = d.getCiviliansRemaining()

                if (d.destroyed && remaining > 0) {
                    if (!flightLog.replayActive) {
                        liveLevelCompleted = true
                        flightLog.completionOutcome = CompletionOutcome.FAILED_CIVILIANS_NOT_RESCUED
                        levelCompletedCountdownMs = 2000
                    }
                }
                else if (remaining == 0) {
                    // mission completion
                    evacCompletionArmed = true
                }
            }
        }
        else if (level.objectiveType == Level.ObjectiveType.DEFEND) {
            val d = level.world.destructibleStructure
            if (d != null) {

                if (d.destroyed) {
                    if (!flightLog.replayActive) {
                        liveLevelCompleted = true
                        flightLog.completionOutcome = CompletionOutcome.FAILED_STRUCTURE_DESTROYED
                        levelCompletedCountdownMs = 2000
                    }
                }
                else if (!d.destructionTriggered &&
                    level.world.activeEnemiesScratch.size == 0 &&
                    level.world.enemyActors.size != 0 &&
                    ship.active) {

                    // mission completion

                    d.cancelDestruction()

                    for (friendlyActor in level.world.activeFriendliesScratch) {
                        friendlyActor.startFlashSignal(flightTimeMs)
                    }

                    if (!flightLog.replayActive) {
                        liveLevelCompleted = true
                        flightLog.completionOutcome = CompletionOutcome.SUCCESS
                        levelCompletedCountdownMs = 4000

                        audioPlayer.radio.onMissionComplete(flightTimeMs.toLong())
                    }
                }
            }
        }


        handler.sendEmptyMessage(UPDATE_SCREEN)
        endOfFrameChecksScheduled = false
    }

    fun checkIfArmedExacComplete() {
        val d = level.world.destructibleStructure
        if (d != null && ship.active) {

            val enemyWithinRadius = level.world.hasEnemyWithinRadius(ship.position, 500f)

            if (!enemyWithinRadius) {

                for (friendlyActor in level.world.activeFriendliesScratch) {
                    friendlyActor.startFlashSignal(flightTimeMs)
                }
                if (!flightLog.replayActive) {
                    liveLevelCompleted = true
                    flightLog.completionOutcome = CompletionOutcome.SUCCESS
                    levelCompletedCountdownMs = 2000
                }
                evacCompletionArmed = false
                handler.sendEmptyMessage(UPDATE_SCREEN)

            }
        }
    }

    fun loadActor(actorTemplate: ActorTemplate) {

        var actor: Actor? = null
        var laserBoltPool: SingleLaserBoltPool? = null
        var explosion: Explosion? = null
        var actorLog: ActorLog? = null


        if (actorTemplate is GroundFriendlyTemplate) {
            actor = level.world.spawnGroundFriendly(actorTemplate.position.x, actorTemplate.position.y, actorTemplate.position.z)
            laserBoltPool = friendlyLaserBoltPool
            explosion = friendlyExplosion
        }
        else if (actorTemplate is FriendlyStructureTemplate) {
            actor = level.world.spawnFriendlyStructure(actorTemplate)
            laserBoltPool = friendlyLaserBoltPool

            // set first destrucutible structure as THE only destructible structure
            if (level.world.destructibleStructure == null && actorTemplate.destructSeconds != null) {
                level.world.destructibleStructure = actor
            }

            if (actor != null) {
                for (blockActor in actor.blocks) {
                    if (flightLog.replayActive) {

                        // TODO load structure for replay!

                    }
                    else {
                        val blockLog = flightLog.createActorLog(blockActor, actorTemplate)
                        blockActor.initialize(this, level.world, blockLog, ship, laserBoltPool, null, explosionFlash)
                    }
                }
            }
        }
        else if (actorTemplate is EnemyTemplate) {
            laserBoltPool = enemyLaserBoltPool
            explosion = enemyExplosion
            when (actorTemplate) {
                is GroundTargetTemplate ->
                    actor = level.world.spawnGroundTarget(
                        actorTemplate.position.x,
                        actorTemplate.position.y,
                        actorTemplate.position.z,
                        actorTemplate.yaw
                    )

                is EasyGroundEnemyTemplate ->
                    actor = level.world.spawnEasyGroundEnemy(actorTemplate.position.x, actorTemplate.position.y, actorTemplate.position.z)

                is GroundEnemyTemplate ->
                    actor = level.world.spawnGroundEnemy(actorTemplate.position.x, actorTemplate.position.y, actorTemplate.position.z)

                is EasyFlyingEnemyTemplate -> {
                    val flyingRadius = if (actorTemplate.flyingRadius != 0f) actorTemplate.flyingRadius else 50f
                    actor = level.world.spawnEasyFlyingEnemy(
                        actorTemplate.position.x,
                        actorTemplate.position.y,
                        actorTemplate.position.z,
                        flyingRadius,
                        actorTemplate.antiClockwise
                    )
                }

                is FlyingEnemyTemplate -> {
                    val flyingRadius = if (actorTemplate.flyingRadius != 0f) actorTemplate.flyingRadius else 50f
                    actor = level.world.spawnFlyingEnemy(
                        actorTemplate.position.x,
                        actorTemplate.position.y,
                        actorTemplate.position.z,
                        flyingRadius,
                        actorTemplate.antiClockwise
                    )
                }

                is AdvFlyingEnemyTemplate -> {
                    actor = level.world.spawnAdvFlyingEnemy(
                        actorTemplate.position.x,
                        actorTemplate.position.y,
                        actorTemplate.position.z,
                        actorTemplate.aabbHalfX,
                        actorTemplate.aabbHalfY,
                        actorTemplate.aabbHalfZ
                    )
                }
            }
        }

        if (actor != null && laserBoltPool != null) {
            if (flightLog.replayActive) {
                // replay so get log from actor template
                if (actorTemplate.log != null) {
                    actorLog = actorTemplate.log!!.apply { parent = actor }
                }
            }
            else {
                // live game so create new actor log
                actorLog = flightLog.createActorLog(actor, actorTemplate)
            }
            actor.initialize(this, level.world, actorLog, ship, laserBoltPool, explosion, explosionFlash)
        }

    }

    fun resetFinalizedState() {
        finalized = false
    }

    fun finalizeLevel(replay: Boolean) {
        if (!replay) {
            parent.setPause(true)
            flightLog.friendliesRemaining = level.world.activeFriendliesScratch.size
            flightLog.enemiesDestroyed = level.world.enemyActors.size - level.world.activeEnemiesScratch.size
            flightLog.shotsFired = ship.shotsFired
            flightLog.healthRemaining = ship.hitPoints / ship.maxHitPoints.toFloat()

            flightLog.stopRecording()
            handler.sendEmptyMessage(LEVEL_COMPLETE_LIVE_MODE)

            val flightLogCopy = flightLog.createCopy()

            parent.mainActivity.log.printout("Saving flight log to internal storage...")
            Thread({
                FlightLogManager(parent.mainActivity).writeLastFlightLogfile(
                    flightLogCopy,
                    this
                )
            }).start()
        }
        else {
            handler.sendEmptyMessage(LEVEL_COMPLETE_REPLAY_MODE)
        }
        finalized = true
    }

    fun replaySeekBarStarted() {
        ship.resetRenderer()
        ship.active = true
        endOfFrameChecksScheduled = false

    }

    fun replaySeekBarChanged(newFlightTimeMs: Int, seekingFinished: Boolean) {
        flightTimeMs = newFlightTimeMs
        flightTimeBeforeLastPauseMs = flightTimeMs
        lastTimeStartMs = System.currentTimeMillis()
        flightTimeMsLastReplayUpdate = flightTimeMs
        flightTimeMsLastFrameCount = flightTimeMs
        intervalMs = 0
        ship.hitPoints = ship.maxHitPoints - flightLog.shipLog.hitCounter.countHitsBefore(newFlightTimeMs)

        updateActors()

        if (seekingFinished) {
            flightLog.shipSnapToOnNextReplayUpdate = true
            for (actor in level.world.actors) {
                if (actor.active) {
                    actor.resetSpin(flightTimeMs)
                    actor.resetRenderer()
                }
            }
        }
    }

    fun updateActors() {

        for (actor in level.world.actors) {
            if (actor.active && !flightLog.replayActive && !parent.levelBuilderMode) {
                // live game
                actor.update(interval, intervalMs, flightTimeMs)
            }
            else if (flightLog.replayActive) {
                // replay
                val event: ActorLog.LogEvent?
                var updatedRequired = true
                if (actor.continuous) {
                    event = actor.log.interpolateEventAtTime(flightTimeMs, null)

                    // check if actor should be deactivated or activated
                    if ((event == null) != (!actor.active)) {
                        actor.active = (event != null)
                    }
                }
                else {
                    val cursorEvent = actor.log.cursor.get(flightTimeMs)
                    event = cursorEvent.event
                    if (cursorEvent.changed) {
                        if (cursorEvent.beforeFirst) {
                            actor.resetPosition()
                        }
                        else {
                            actor.active = !cursorEvent.afterLast || !cursorEvent.lastDestroyed
                            if (actor.active) actor.resetRenderer()
                        }
                    }
                    else {
                        updatedRequired = false
                        actor.replayUpdateMinimum(interval, flightTimeMs)
                    }
                }

                if (updatedRequired) {
                    actor.replayUpdate(flightLog, event, interval, intervalMs, flightTimeMs)
                }
            }
        }

        if (evacCompletionArmed) checkIfArmedExacComplete()
    }

    private fun updateWorld() {

        // UPDATE ACTORS
        if (parent.levelBuilderMode) {

            // playing level builder
            camera.updateDirectly(interval)

            // relocate actor is necessary
            if (level.editEngine.lbRelocatingShip || level.editEngine.relocatingActor != null) {

                val newPosition = camera.position + level.editEngine.vectorCameraToRelocatingActor
                val terrainZ = level.world.terrainElevationAt(newPosition.x, newPosition.y)

                if (level.editEngine.lbRelocatingShip ||
                    level.editEngine.relocatingActor!! is EasyFlyingEnemyActor ||
                    level.editEngine.relocatingActor!! is FlyingEnemyActor ||
                    level.editEngine.relocatingActor!! is AdvFlyingEnemyActor) {
                    val minAGL = 5f
                    if (terrainZ != null && newPosition.z < terrainZ + minAGL) {
                        newPosition.z = terrainZ + minAGL
                    }
                }
                else if (terrainZ != null) { // ground-based actor
                    newPosition.z = terrainZ + level.editEngine.relocatingActor!!.instance.model.localAabb.height() / 2
                }

                if (level.editEngine.lbRelocatingShip) {
                    ship.setPositionAndUpdate(newPosition, yawRad = camera.angleP)
                } else { // non-ship actor

                    if (level.editEngine.relocatingActor!! is GroundTrainingTargetActor) {
                        level.editEngine.relocatingActor!!.setPositionAndUpdate(newPosition, yawRad = camera.angleP)
                    }
                    else {
                        level.editEngine.relocatingActor!!.setPositionAndUpdate(newPosition)
                    }
                }
            }
            else if (level.editEngine.relocatingStructure != null) {
                if (level.editEngine.relocatingStructure != null) {
                    val newBase = camera.position + level.editEngine.vectorCameraToRelocatingActor

                    // lock Z
                    newBase.z = level.editEngine.structureRelocateStartBase.z

                    level.editEngine.applyStructureRelocateInPlace(newBase)

                }
            }
        }
        else if (flightLog.replayActive) {

            val event = ship.log.interpolateEventAtTime(flightTimeMs, ship.replayVelocity)
            ship.replayUpdate(flightLog, event, interval, intervalMs, flightTimeMs)

            if (camera.posedByTrack && flightLog.cameraTrack != null && flightLog.cameraTrack!!.events.isNotEmpty()) {

                flightLog.cameraTrack!!.evalInto(flightTimeMs, cameraSample)
                camera.updateReplayPosed(cameraSample)
            }
            else {
                camera.updateReplay(interval)
            }

            if (flightTimeMs > flightLog.flightTimeMs && !flightLog.replaySeeking && !finalized) {
                println(flightTimeMs)
                finalizeLevel(true)
            }

        } else {

            // playing live game
            ship.update(interval, intervalMs, flightTimeMs)
            camera.updateForChase()
        }

        if (!flightLog.replayPaused) {

            // update non-ship actors
            updateActors()

            // update laserbolts and exposions
            shipLaserBoltPool.update(interval, flightTimeMs, level.world, ship)
            enemyLaserBoltPool.update(interval, flightTimeMs, level.world, ship)
            friendlyLaserBoltPool.update(interval, flightTimeMs, level.world, ship)

            shipExplosion.update(interval, level.world)
            enemyExplosion.update(interval, level.world)
            friendlyExplosion.update(interval, level.world)
            structureExplosionPool.update(interval)

            explosionFlash.update(interval)
        }
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {

        println("onSurfaceCreated()")

        gpuModelCache = GpuModelCache(modelManager)
        level.world.renderer = this
        level.world.gpuModelCache = gpuModelCache

        loadGLProgram()
        loadGlowProgram()

        level.world.civilianWireBatch = WireBatchRenderer(mProgram)
        level.world.visuals.clear()

        skybox = Skybox(parent.context)
        skybox.init(arrayOf(
            "skybox_right1.png","skybox_left2.png",
            "skybox_top3.png", "skybox_bottom4.png",
            "skybox_front5.png", "skybox_back6.png"
        ))

        ship = spawnShip(level.shipPosition.x, level.shipPosition.y, level.shipPosition.z, level.shipDirection, 0.0)
        flightLog.shipLog.parent = ship
        flightLog.shipLog.template = ShipTemplate(level.shipPosition, level.shipDirection)

        camera = Camera(flightControls, ship,level.world)

        shipExplosion = Explosion(level.world, 1f, floatArrayOf(0.314f, 0.824f, 0.0f, 1.0f))
        enemyExplosion = Explosion(level.world, 1f, floatArrayOf(0.9f, 0.9f, 0.9f, 1.0f))
        friendlyExplosion = Explosion(level.world, 0.5f, floatArrayOf(0.314f, 0.824f, 0.0f, 1.0f))
        structureExplosionPool = ExplosionPool(level.world, 8, 3f, floatArrayOf(0.2f, 0.8f, 1.0f, 1f))

        shipExplosion.load()
        enemyExplosion.load()
        friendlyExplosion.load()
        structureExplosionPool.load()
        explosionFlash.initGl()

        flightControls.setDeviceNeutral = true

        ship.initialize(this, level.world, flightLog.shipLog, shipLaserBoltPool, shipExplosion)
        if (flightLog.replayActive) {
            level.world.shipStartPosition.set(flightLog.shipInitialPosition)
            level.world.shipStartDirection = flightLog.shipInitialDirection

            flightLog.actorLogs
                .mapNotNull { it.template }
                .forEach { loadActor(it) }

        }
        else {
            // live game or level builder
            level.world.shipStartPosition.set(level.shipPosition)
            level.world.shipStartDirection = level.shipDirection

            for (actorTemplate in level.actorTemplates) {
                loadActor(actorTemplate)
            }
        }


        ship.initialPosition.set(level.world.shipStartPosition)

        debugLabel.initialize(textAtlas)

        camera.orientationUp.set(0f,1f, 0f)
        camera.updateForChase()

        shipLaserBoltPool.fillPool(mProgram, vPMatrixHandle, positionHandle, mColorHandle)
        enemyLaserBoltPool.fillPool(mProgram, vPMatrixHandle, positionHandle, mColorHandle)
        friendlyLaserBoltPool.fillPool(mProgram, vPMatrixHandle, positionHandle, mColorHandle)

        mapGrid.load(level.world, mProgram, vPMatrixHandle, positionHandle, mColorHandle)

        resetGame()

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        parent.post { parent.onRendererReady() }

    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {

        viewportWidth = width
        viewportHeight = height

//        println("Viewport = $viewportWidth x $viewportHeight")

        GLES20.glViewport(0, 0, width, height)

        buildProjections(width, height)

        debugLabel.position(20f, height - 20f, 0.4f, width, height)
        debugLabel.set("FPS -")
    }

    // NOTE: Coordinate system wart (temporary):
// - Game simulation treats (x, y, z) with z = up.
// - Legacy rendering path uses a swizzle to map world -> GL:
//       glPosition = (world.x, world.z, world.y)
// - New models are authored to match this convention.
// - DO NOT "fix" this in one random place; when we clean it up,
//   we will remove the swizzle everywhere in a controlled pass.

    private fun buildProjections(w: Int, h: Int) {
        val aspect = w.toFloat() / h.toFloat()

        // worldProjectionMatrix
        Matrix.perspectiveM(worldProjectionMatrix, 0, 60f, aspect, 2f, 10000f)

        // skyboxProjectionMatrix (often same fov/aspect but different near/far)
        Matrix.perspectiveM(skyboxProjectionMatrix, 0, 60f, aspect, 0.1f, 10000f)
    }



    override fun onDrawFrame(unused: GL10) {

        // UPDATE CLOCKS
        if (!paused) {

            // update interval
            if (!flightLog.replayPaused) {
                val oldFlightTimeMs = flightTimeMs


                if (captureEnabled) {
                    // Fixed-step time for offline capture/export
                    intervalMs = nextCaptureIntervalMs()
                    flightTimeMs = oldFlightTimeMs + intervalMs
                    interval = intervalMs / 1000f

                } else {
                    currentMillis = System.currentTimeMillis()
                    flightTimeMs = flightTimeBeforeLastPauseMs + (currentMillis - lastTimeStartMs).toInt()
                    intervalMs = flightTimeMs - oldFlightTimeMs

                    // DEBUG: uncomment for correction for long interval when debugging
                    if (intervalMs > 2000) {
                        intervalMs = 10
                        flightTimeMs = oldFlightTimeMs + intervalMs
                    }

                    interval = intervalMs / 1000f
                }

                frameCount++
                if (!captureEnabled && flightTimeMs > flightTimeMsLastFrameCount + 1000) {
                    fps = frameCount
                    frameCount = 0
                    flightTimeMsLastFrameCount = flightTimeMs
                }

                if (!captureEnabled && flightLog.replayActive && flightTimeMs > flightTimeMsLastReplayUpdate + 250) {
                    handler.sendEmptyMessage(UPDATE_REPLAY_SEEKBAR)
                    flightTimeMsLastReplayUpdate = flightTimeMs
                }
            }

            if (liveLevelCompleted) {
                levelCompletedCountdownMs -= intervalMs
                if (levelCompletedCountdownMs < 0 && !finalized) {
                    finalizeLevel(false)
                }
            }

            // UPDATE WORLD
            updateWorld()

            screenShake.update(interval)



            // UPDATE COUNTS AND GAME STATE
            level.world.updateActorCounts()
            if (endOfFrameChecksScheduled) {
                endOfFrameChecks()
            }

            audioPlayer.updateRadio(flightTimeMs.toLong())
        }

        if (false) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glClearColor(1f, 0f, 1f, 1f) // magenta
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        else {
            // DRAW WORLD
            renderWorld()

        }


        // CAPTURE (last thing, before returning)
        if (captureEnabled) {
            captureFrame()
        }
    }

    private fun renderWorld() {

        if (flash) {
            renderFlash()
        }
        else {

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)


            // Set the camera position (View matrix)
            Matrix.setLookAtM(
                viewBase, 0,
                camera.position.x,
                camera.position.z, // swap y and z
                camera.position.y,
                camera.focalPoint.x,
                camera.focalPoint.z, // swap y and z
                camera.focalPoint.y,
                camera.orientationUp.x, camera.orientationUp.y, camera.orientationUp.z
            )

            val o = screenShake.getOffset()

            // copy base -> shaken
            System.arraycopy(viewBase, 0, viewShaken, 0, 16)

            // apply shake (world shake) after lookAt
            Matrix.translateM(viewShaken, 0, o.x, o.z, o.y)

            skybox.draw(viewBase, skyboxProjectionMatrix)

            // Calculate the projection and view transformation
            Matrix.multiplyMM(vPWorld, 0, worldProjectionMatrix, 0, viewShaken, 0)
            mapGrid.draw(vPWorld)


        }

        // draw game objects
        if (ship.active) {
            Matrix.multiplyMM(vPShip, 0, worldProjectionMatrix, 0, viewBase, 0)
            ship.draw(vPShip, flightTimeMs)
        }

        for (actor in level.world.actors) {
            if (actor.active) actor.draw(vPWorld, flightTimeMs)
        }

        for (v in level.world.visuals) {
            if (v.active) v.draw(vPWorld, flightTimeMs)
        }


        shipLaserBoltPool.draw(vPWorld)
        enemyLaserBoltPool.draw(vPWorld)
        friendlyLaserBoltPool.draw(vPWorld)

        shipExplosion.draw(vPWorld)
        enemyExplosion.draw(vPWorld)
        friendlyExplosion.draw(vPWorld)
        structureExplosionPool.draw(vPWorld)
        explosionFlash.draw(vPWorld, viewBase)

        if (parent.levelBuilderMode) {
//            debugLabel.set(dart.position.x.toInt().toString() + " " + dart.position.y.toInt().toString() + " " + dart.position.z.toInt().toString())
            debugLabel.set(camera.position.x.toInt().toString() + " " + camera.position.y.toInt().toString() + " " + camera.position.z.toInt().toString())
            debugLabel.draw()

        }

    }

    private fun renderFlash() {
        GLES20.glClearColor(1.0f, 1.0f, 1.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        flashFrameCountdown--
        if (flashFrameCountdown == 0) {
            flash = false
            flashFrameCountdown = 3
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    fun enableCapture(context: Context) {
        if (captureEnabled) return

        try {
            capDir = createCaptureSessionDir(context.applicationContext)
            File(capDir, "CAPTURE_STARTED.txt").writeText("started at ${System.currentTimeMillis()}\n")
            captureRemainder = 0
            capFrameIndex = 0
            captureEnabled = true
            Log.i("Capture", "Capture ENABLED")
        } catch (t: Throwable) {
            captureEnabled = false
            Log.e("Capture", "Failed to enable capture", t)
        }
    }

    fun disableCapture() {
        if (!captureEnabled) return
        captureEnabled = false
        Log.i("Capture", "Capture disabled. Frames written: $capFrameIndex")
    }
    private fun createCaptureSessionDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir  // fallback
        val root = File(base, "capture")

        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("Failed to create capture root: ${root.absolutePath}")
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(java.util.Date())
        val session = File(root, "session_$stamp")

        if (!session.exists() && !session.mkdirs()) {
            throw IllegalStateException("Failed to create capture session: ${session.absolutePath}")
        }

        Log.i("Capture", "Capture dir: ${session.absolutePath}")
        return session
    }

    private val tmpViewport = IntArray(4)
    private fun captureFrame() {

        if (capFrameIndex >= CAPTURE_MAX_FRAMES) {
            captureEnabled = false
            Log.i("Capture", "Capture complete: ${capFrameIndex} frames")
            return
        }

        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, tmpViewport, 0)
        val w = tmpViewport[2]
        val h = tmpViewport[3]

        if (capFrameIndex == 0) {
            File(capDir, "capture_info.txt").writeText("w=$w\nh=$h\nfmt=RGBA8888\n")
            Log.i("Capture", "Capture size = ${w}x${h}")
        }

        ensureCaptureBuffers(w, h)

        val src = capBuf!!
        val dst = capFlipBuf!!

        src.position(0)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, src)

        flipVerticalRGBA(w, h, src, dst)

        // Write one file per frame (simple)
        val file = File(capDir, String.format("frame_%06d.rgba", capFrameIndex++))
        FileOutputStream(file).channel.use { ch ->
            dst.position(0)
            ch.write(dst)
        }
    }

    private var capFlipBuf: ByteBuffer? = null

    private fun ensureCaptureBuffers(w: Int, h: Int) {
        if (capBuf == null || capW != w || capH != h) {
            capW = w
            capH = h

            val bytes = w * h * 4
            capBuf = ByteBuffer
                .allocateDirect(bytes)
                .order(ByteOrder.nativeOrder())

            capFlipBuf = ByteBuffer
                .allocateDirect(bytes)
                .order(ByteOrder.nativeOrder())
        }
    }

    private var capRow: ByteArray? = null

    private fun ensureRowBuffer(w: Int) {
        val stride = w * 4
        if (capRow == null || capRow!!.size != stride) {
            capRow = ByteArray(stride)
        }
    }

    private fun flipVerticalRGBA(w: Int, h: Int, src: ByteBuffer, dst: ByteBuffer) {
        val stride = w * 4
        ensureRowBuffer(w)
        val row = capRow!!

        for (y in 0 until h) {
            val srcPos = y * stride
            val dstPos = (h - 1 - y) * stride

            src.position(srcPos)
            src.get(row, 0, stride)

            dst.position(dstPos)
            dst.put(row, 0, stride)
        }

        dst.position(0)
    }




    fun onDestroyRenderer() {
        // Call when GLSurfaceView / context is going away
        gpuModelCache.clear()
    }

    fun loadGLProgram() {

        val vertexShaderCode =
        // This matrix member variable provides a hook to manipulate
            // the coordinates of the objects that use this vertex shader
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 vPosition;" +
                    "void main() {" +
                    "  gl_Position = uMVPMatrix * vPosition;" +
                    "}"

        val fragmentShaderCode =
            "precision mediump float;" +
                    "uniform vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
                    "}"


        val vertexShader: Int = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader: Int = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        mProgram = GLES20.glCreateProgram()                 // create OpenGL ES Program
        GLES20.glAttachShader(mProgram, vertexShader)       // add the vertex shader to program
        GLES20.glAttachShader(mProgram, fragmentShader)     // add the fragment shader to program
        GLES20.glLinkProgram(mProgram)                      // creates OpenGL ES program executables

        positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
        vPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor")

    }

    fun loadGlowProgram() {

        val vertexShaderCode =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 vPosition;" +
                    "void main() {" +
                    "  gl_Position = uMVPMatrix * vPosition;" +
                    "}"

        val fragmentShaderCode =
            "precision mediump float;" +
                    "uniform float uGlow;" +
                    "uniform vec3 uTint;" +
                    "void main() {" +
                    "  gl_FragColor = vec4(uTint * uGlow, 1.0);" +
                    "}"

        val vertexShader: Int = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader: Int = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        glowProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(glowProgram, vertexShader)
        GLES20.glAttachShader(glowProgram, fragmentShader)
        GLES20.glLinkProgram(glowProgram)

        // Same attribute/uniform naming as your main program
        glowPosHandle = GLES20.glGetAttribLocation(glowProgram, "vPosition")
        glowMvpHandle = GLES20.glGetUniformLocation(glowProgram, "uMVPMatrix")
        glowGlowHandle = GLES20.glGetUniformLocation(glowProgram, "uGlow")
        glowTintHandle = GLES20.glGetUniformLocation(glowProgram, "uTint")
    }


    fun spawnShip(x: Float, y: Float, z: Float, yawRad: Double, pitchRad: Double): ShipActor {
//        val model = modelManager.getModel("models/ship.obj")
        val model = gpuModelCache.getModel("models/ship.obj")
        val instance = ModelInstance(model, isStatic = false).apply {
            setPosition(x, y, z)
            setDirection(yawRad, pitchRad)
            update()
        }

        val renderer = WireRenderer(instance, mProgram, glowProgram).apply {
            normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
            normalLineColor = floatArrayOf(0f, 1f, 0f, 1f)
        }

        return ShipActor(flightControls, flightLog, instance, renderer).apply {
            initialPosition.set(instance.position)
            initialYawRad = instance.yawRad
            initialPitchRad = instance.pitchRad
        }
    }

    override fun firingStarted() {
//        if (parent.levelBuilderMode) {
//            level.finishRelocatingActor(false)
//
//            val actor = level.world.getNearestActor(camera.position)
//            if (actor != null && camera.position.distance2(actor.position) < camera.position.distance2(ship.position)) {
//                val actorSelected = actor.selected
//                level.world.unselectAllActors()
//                ship.unselect()
//                if (actorSelected) actor.unselect() else actor.select()
//            }
//            else {
//                if (!ship.selected) {
//                    level.world.unselectAllActors()
//                    ship.select()
//                }
//                else {
//                    ship.unselect()
//                }
//            }
//        }
    }

    override fun notifyWriteFileRequestOutcome(msg: Message) {
        when (msg.what) {
            0 -> {
                handler.sendEmptyMessage(LAST_FLIGHTLOG_SAVED)
            }
        }
    }

}

class ScreenShake {

    private var timeLeft = 0f
    private var amplitude = 0f
    private var frequency = 0f
    private var seed = 0f

    fun start(strength: Float, duration: Float, freq: Float = 100f) {
        amplitude = strength
        timeLeft = duration
        frequency = freq
        seed = kotlin.random.Random.nextFloat() * 1000f
    }

    fun update(dt: Float) {
        if (timeLeft > 0f) {
            timeLeft -= dt
            if (timeLeft < 0f) timeLeft = 0f
        }
    }

    fun getOffset(): Vec3 {
        if (timeLeft <= 0f) return Vec3()

        val t = timeLeft
        val decay = t * t  // quadratic falloff feels good

        val x = (kotlin.math.sin(seed + t * frequency) * amplitude * decay)
        val y = (kotlin.math.sin(seed * 1.3f + t * frequency * 1.1f) * amplitude * decay)
        val z = (kotlin.math.sin(seed * 0.7f + t * frequency * 0.9f) * amplitude * decay)

        return Vec3(x, y, z)
    }
}
