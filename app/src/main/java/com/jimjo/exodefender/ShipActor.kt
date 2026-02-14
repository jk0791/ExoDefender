package com.jimjo.exodefender

import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


// tuning
const val COLLISION_EPS = 0.01f
private const val LEVEL_RATE = 2.0        // rad/sec response
private const val LEVEL_EPS  = 0.01        // ~0.6° "good enough"

data class CollisionInfo(
    var collided: Boolean = false,
    val normal: Vec3 = Vec3(),
    var actor: Actor? = null,

    var hasSupport: Boolean = false,
    val supportNormal: Vec3 = Vec3(),
    var supportActor: Actor? = null
)

class DebugLogger {
    var log = ""
    var currentTimeMs = 0

    fun reset() {
        log = ""
    }

    fun add(line: String, timeMs: Int) {
        if (log == "") currentTimeMs = timeMs

        if (timeMs == currentTimeMs) {
            log += "$timeMs; $line\n"
        }
    }

    fun printout() {
        println("-------------")
        println(log)

        reset()
    }

}

class ShipActor(
    val flightControls: FlightControls,
    val flightLog: FlightLog,
    override val instance: ModelInstance,
    override val renderer: WireRenderer
): Actor() {

    // Angular velocities (radians per second)
    var yawVel = 0.0   // was rvelocityP
    var pitchVel = 0.0 // was rvelocityE

    // Pitch clamp (±45° as in your original: 0.785 rad)
    private val maxPitch = 0.785

    // Roll behavior tuning
    private val maxBankRad = 1.4          // how far we can roll (approx 34°)

    val velocity = Vec3()


    // Tune these to match your old visuals:
    var muzzleSideOffset    = 2.5f
    var muzzleForwardOffset = 0f
    var muzzleUpOffset      = 0f
    override val maxHitPoints = 7


    // STATE
    var halfLength = 0f
    val replayVelocity = Vec3()
    var lastReplayTimeMs = 0

    lateinit var localCorners: Array<Vec3>
    val cornersShipWorld = Array(3) { Vec3() }
//    private val chaseFocalPointLocal: Vec3
    val chaseFocalPointWorld = Vec3()

    val smoothCam = SmoothTarget()

    private val candidateTargets = ArrayList<Actor>(128)

    var velocityF = 0f  // metres per second
    var velocityH = 0f
    var velocityV = 0f

    val desiredMove = Vec3()
    val actualMoveNoCollision = Vec3()
    val desiredPosition = Vec3()

    val collisionInfo = CollisionInfo()
    var isGrounded = false
    var isTerrainGrounded = false
    var groundNormal = Vec3(0f, 0f, 1f)
    var groundedOnLandingPad = false
    private var groundedUntilMs = 0
    private var lastPadBlock: BuildingBlockActor? = null

    // Local-space muzzle offsets (relative to ship/model origin)
    private val muzzleLocalL = Vec3()
    private val muzzleLocalR = Vec3()

    // Scratch for world positions
    private val muzzleWorldL = Vec3()
    private val muzzleWorldR = Vec3()
    // Rolled weapon basis for positions
    private val weaponRightRolled = Vec3()
    private val weaponUpRolled    = Vec3()
    protected lateinit var doubleLaserBoltPool: DoubleLaserBoltPool

    val maxForwardVelocity = 250f
    val maxTranslationalVelocity = 70f
    val forwardDamping = 1f
    val translationalDamping = 1f
    val rollDamping = 1.5f

    val stallMinPower = 0.2f // 0f // 0.2f
    val stallMaxSinkSpeed = 15f // units/sec downward at throttle==0
    val maxDiveVelocity: Float

    var shotsFired = 0

    override val continuous = true
    override val qInterval = 200

    private val tmpDisp = Vec3()
    private val tmpSweepMin = Vec3()
    private val tmpSweepMax = Vec3()
    private val tmpDir = Vec3()
    private val tmpN = Vec3()
    private val tmpBestN = Vec3()
    private val tmpSupportN = Vec3()
    private val tmpLastN = Vec3()

    private val tmpSpeed = FloatArray(1)
    private val tmpForward = FloatArray(1)

    var topZ: Float? = null

    private val supportRadiusZ = 1.5f  // start: shipHeight/2 ~ 1m

    private val landingFootprintRadius = sqrt(4.25f * 4.25f + 2.5f * 2.5f) * 2 / 3 // ~4.93m


    private var levelAtRest = false
    private var restLatched = false
    private var restTimer = 0f
    private var latchedYaw = 0.0
    private val latchedChase = Vec3()
    private var wasRestLatchedLastFrame = false


    private val rescueTransfer = RescueTransferController(this)
    var civiliansOnboard: Int = 0
    val carryingCapacity: Int = 2

    val debugLogger  = DebugLogger()

    init {

        muzzleLocalL.set(-muzzleSideOffset, muzzleForwardOffset, muzzleUpOffset)
        muzzleLocalR.set(+muzzleSideOffset, muzzleForwardOffset, muzzleUpOffset)
        halfLength = instance.model.localAabb.depth() / 2f

        maxDiveVelocity = maxForwardVelocity * stallMinPower / maxPitch.toFloat()
    }

    fun initialize(parent: ModelParent, world: World, log: ActorLog, laserBoltPool: DoubleLaserBoltPool, explosion: Explosion) {
        this.parent = parent
        this.world = world
        this.log = log
        this.doubleLaserBoltPool = laserBoltPool
        this.explosion = explosion
        localCorners = computeLocalBottomCornersFromModel()
    }

    override fun reset() {
        hitPoints = maxHitPoints
        active = true
//        rollingMovement.reset()
        shotsFired = 0
        renderer.reset()
        position.set(initialPosition)
        yawRad = initialYawRad
        pitchRad = initialPitchRad

        restTimer = 0f
        levelAtRest = false
        restLatched = false
        wasRestLatchedLastFrame = false

        rescueTransfer.reset()
        civiliansOnboard = 0
    }

    fun getHealth(): Float {
        return hitPoints.toFloat() / maxHitPoints
    }

    private val tmp = Vec3()

    fun shipForwardVector(out: Vec3 = tmp): Vec3 {
        out.set(moveForward)
        return out
    }

    fun shipRightVector(out: Vec3 = tmp): Vec3 {
        out.set(moveRight)
        return out
    }

    fun shipUpVector(out: Vec3 = tmp): Vec3 {
        out.set(moveUp)
        return out
    }

    fun calculateForwardVelocity(currentVelocity: Float, targetVelocity: Float, interval: Float, friction: Float = 1f): Float {
        val delta = targetVelocity - currentVelocity
        val momentumEffect =
            if (targetVelocity > currentVelocity) 1f
            else 0.5f
        val limitedDelta = forwardDamping * momentumEffect * delta * 3 * interval * friction
        if (delta.absoluteValue > 0.01) {
            return Math.min(currentVelocity + limitedDelta, maxForwardVelocity)
        } else {
            return targetVelocity
        }
    }

    fun calculateTranslationalVelocity(currentVelocity: Float, targetVelocity: Float, interval: Float, friction: Float = 1f): Float {
        val delta = targetVelocity - currentVelocity
        val limitedDelta = translationalDamping * delta * 3 * interval * friction
        if (delta.absoluteValue > 0.01) {
            return Math.min(Math.max(currentVelocity + limitedDelta, -maxTranslationalVelocity), maxTranslationalVelocity)
        } else {
            return targetVelocity
        }
    }

    fun calculateRoll(currentAngleB: Double, targetAngleB: Double, dtSec: Double, rollDampingEffective: Double): Double {
        val delta = targetAngleB - currentAngleB
        val limitedDelta = rollDampingEffective * delta * 3 * dtSec
        if (delta.absoluteValue > 0.01) {
            return Math.min(Math.max(currentAngleB + limitedDelta, -maxBankRad), maxBankRad)
        } else {
            return targetAngleB
        }
    }

    fun updateShipBottomCornersAt(pos: Vec3) {
        // localCorners[i] are in ship local space (triangle bottom)
        // Use modelInstance.rotateLocal() for rotation, then add desired pos.

        val lc0 = localCorners[0]
        val lc1 = localCorners[1]
        val lc2 = localCorners[2]

        instance.rotateLocal(lc0, cornersShipWorld[0])
        cornersShipWorld[0].addLocal(pos)

        instance.rotateLocal(lc1, cornersShipWorld[1])
        cornersShipWorld[1].addLocal(pos)

        instance.rotateLocal(lc2, cornersShipWorld[2])
        cornersShipWorld[2].addLocal(pos)
    }

    override fun onHit(timeMs: Int, enemyHit: Boolean, hitPosition: Vec3) {

        if (world.replayActive) return

        if (hitPoints == 0)  return

        hitPoints--

        if (hitPoints == 0) {
            active = false
            parent.shipHit(true)
            logEvent(timeMs, includeDirection = true, hit = 1, destroyed = 1)
            explosion?.activateLarge(instance.position)
        } else {
            parent.shipHit(false)
            renderer.flashLinesOnce(timeMs)
            explosion?.activateSmall(instance.position)
            logEvent(timeMs, includeDirection = true, hit = 1)
        }
    }

    private fun dampTo(current: Double, target: Double, rate: Double, dt: Double): Double {
        val a = 1.0 - kotlin.math.exp(-rate * dt)
        return current + (target - current) * a
    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

        val fwdPower = remapPiecewise01(flightControls.throttle, flightControls.throttleStallThreshold, stallMinPower)

        val pitchFactor = -min(0f, min(0f, pitchRad.toFloat()))
        val minVelocity = pitchFactor * maxDiveVelocity

        val targetVelocity = (fwdPower * maxForwardVelocity).coerceAtLeast(minVelocity)

        velocityF = calculateForwardVelocity(velocityF, targetVelocity, dt, if (levelAtRest) 0.8f else 1f)

        // --- v0 STALL TURN AUTHORITY (simple, replay-safe) ---
//        val threshold = flightControls.throttleStallThreshold

        // 0..1 : 0 at threshold, 1 at zero throttle
        val stallFactor =
            if (fwdPower < stallMinPower)
                ((stallMinPower - fwdPower) / stallMinPower).coerceIn(0f, 1f)
            else
                0f

        val isStalling = stallFactor > 0f

        // How much control you retain at full stall.
        val minAuthority = 0.2f

        // Authority factor 1..minAuthority
        val authority = 1f - stallFactor * (1f - minAuthority)

        val rotH = flightControls.rotationHorz * authority
        val rotV = flightControls.rotationVert * authority
        // --- end v0 STALL TURN AUTHORITY ---

        yawVel = rotH.toDouble() * 1.2f
        pitchVel = rotV.toDouble() * 1.2f

        velocityH = calculateTranslationalVelocity(velocityH, flightControls.translationHorz * 100f * authority, dt, if (levelAtRest) 0.1f else 1f)
        velocityV = calculateTranslationalVelocity(velocityV, -flightControls.translationVert * 100f * authority, dt)

        val dtSec = dt.toDouble()

        // 1) Update yaw & pitch from angular velocities
        if (!levelAtRest) {
            yawRad -= yawVel * dtSec
            pitchRad -= pitchVel * dtSec
            pitchRad = pitchRad.coerceIn(-maxPitch, maxPitch)

            // 2) Update roll based on horizontal input
            val rollTarget = -flightControls.rotationHorz.toDouble() * 1.4f
            val rollDampingEffective = rollDamping * authority.toDouble() // start with 2.0
            rollRad = calculateRoll(rollRad, rollTarget, dtSec, rollDampingEffective)
        }
        else {
            val friction = 0.95f
            velocityF *= friction
            velocityH *= friction
        }




        computeDesiredPosition(dt, authority)

        // --- v0 STALL SINK (linear, no smoothing) ---
        if (isStalling) {
//            val s = ((stallMinPower - fwdPower) / stallMinPower).coerceIn(0f, 1f) // 0..1
            val sinkVz = -stallMaxSinkSpeed * stallFactor                            // negative Z = down
            desiredMove.z += sinkVz * dt
        }
        // --- end v0 STALL SINK ---

        val cornersElevation = cornersShipWorld.map { corner ->
            world.terrainElevationAt(corner.x, corner.y)
        }
        var deepestCorner: Vec3? = null
        var penetration = 0f

        for ((i, corner) in cornersShipWorld.withIndex()) {
            val terrainZ = cornersElevation[i]
            if (terrainZ != null) {
                val cornerPenetration = terrainZ - corner.z
                if (cornerPenetration >= penetration) {
                    deepestCorner = corner
                    penetration = cornerPenetration
                }
            }
        }

        var terrainGrounded = false

        if (deepestCorner != null) {
            // at least one corner is below the terrain so adjust position

            val terrainNormal = world.terrainNormalAt(deepestCorner.x, deepestCorner.y)
            val correction = terrainNormal * penetration

            val normalOk = terrainNormal.z > 0.95f
            val notGoingUp = velocity.z <= 0.5f
            terrainGrounded = normalOk && notGoingUp

            val slide = if (terrainNormal.z > 0.99f) {
                // flat ground: no tangent component
                desiredMove
            } else {
                // slope: project movement onto plane
                desiredMove - terrainNormal * desiredMove.dot(terrainNormal)
            }
            actualMoveNoCollision.set(correction + slide)

        } else {
            // all corners are above the terrain
            actualMoveNoCollision.set(desiredMove)
        }

        if (dt != 0f) {
            velocity.set(actualMoveNoCollision.mulLocal(1 / dt))
        }

        val idleThrottle = fwdPower <= 0.01f

        // collision detection
        moveShipSlideOnCollide(
            position,
            velocity,
            dt,
            landingFootprintRadius,
            supportRadiusZ,
            COLLISION_EPS,
            world::queryEnemiesAndFriendliesForAabbInto,
            outCollisionInfo = collisionInfo
        )


        val block = collisionInfo.supportActor as? BuildingBlockActor
        val onTop = collisionInfo.hasSupport && collisionInfo.supportNormal.z > 0.95f

        if (onTop && block != null) {
            val topZ = block.instance.worldAabb.max.z

            // Measure deepest penetration of any support corner into the plane
            var deepestPen = 0f
            for (c in cornersShipWorld) {
                val pen = topZ - c.z
                if (pen > deepestPen) deepestPen = pen
            }

            // --- tuning knobs ---
            val deadband = 0.02f        // 2 cm: ignore tiny jitter
            val maxUpSpeed = 6.0f       // m/s, cap "push up" rate (try 4..10)
            val maxUpStep = maxUpSpeed * dt

            if (deepestPen > deadband) {
                val step = (deepestPen - deadband).coerceAtMost(maxUpStep)
                position.z += step
                if (velocity.z < 0f) velocity.z = 0f
            }

            // Optional settle-down (only if you still want pad to "snap" down)
            // Gate it hard so it doesn't fight the push-up:
            val settleDeadband = 0.03f  // 3 cm
            val maxDownSpeed = 2.0f     // m/s, slower than up
            val maxDownStep = maxDownSpeed * dt

            // Compute min clearance (how far above plane the lowest corner is)
            var minClear = Float.POSITIVE_INFINITY
            for (c in cornersShipWorld) {
                val clear = c.z - topZ
                if (clear < minClear) minClear = clear
            }

            if (minClear > settleDeadband) {
                val step = (minClear - settleDeadband).coerceAtMost(maxDownStep)
                position.z -= step
                // don't touch velocity.z here; let gravity / your physics handle it
            }
        }


        val onHorizontalSurface = collisionInfo.hasSupport && collisionInfo.supportNormal.z > 0.95f
        val groundedOnCollider = collisionInfo.hasSupport

        levelAtRest = idleThrottle && (onHorizontalSurface || terrainGrounded)

        val padBlock = (collisionInfo.supportActor as? BuildingBlockActor)

        val groundedOnPad = groundedOnCollider && (padBlock?.landingPadTop == true)

        val overlay = padBlock?.renderer?.landingPadOverlay
        val withinLandingPad =
            groundedOnPad &&
                    (overlay != null) &&
                    isShipWithinPad2D(
                        shipPos = position,
                        pad = if (overlay.isBox)
                            PadSpec(
                                shape = PadShape.BOX,
                                center = padBlock.position,
                                yawRad = padBlock.yawRad.toFloat(),
                                halfX = overlay.halfX,
                                halfY = overlay.halfY
                            )
                        else
                            PadSpec(
                                shape = PadShape.CIRCLE,
                                center = padBlock.position,
                                yawRad = 0f,
                                radius = overlay.radius - overlay.inset
                            ),
                        shipFootprintRadius = landingFootprintRadius,
                        margin = 0.25f
                    )

        val padConfirmed = withinLandingPad && abs(fwdPower) <= 0.01f

        // If we moved to a different support pad (or off a pad), clear the old one
        if (lastPadBlock != padBlock) {
            lastPadBlock?.renderer?.landingPadOverlay?.apply {
                isWithin = false
                isConfirmed = false
            }
            lastPadBlock = padBlock
        }

        // Apply state to the current pad overlay (only if current support is a pad)
        padBlock?.renderer?.landingPadOverlay?.apply {
            isWithin = withinLandingPad
            isConfirmed = padConfirmed && restLatched
        }

        // --- SIMPLE LATCH: time-based only ---
        if (!restLatched) {
            if (levelAtRest) {

                // Level toward flat
                pitchRad = dampTo(pitchRad, 0.0, LEVEL_RATE, dt.toDouble())
                rollRad  = dampTo(rollRad,  0.0, LEVEL_RATE, dt.toDouble())

                // Stop angular motion
//                pitchVel = 0.0
//                yawVel   = 0.0

                // Latch when basically level
                if (abs(pitchRad) < LEVEL_EPS && abs(rollRad)  < LEVEL_EPS) {

                    restLatched = true
                    latchedYaw = yawRad
                }

            }
        }

        val wantsTakeoff = fwdPower > 0.01f

        if (restLatched && wantsTakeoff) {
            restLatched = false
            restTimer = 0f
        }

        if (padConfirmed && restLatched) {
            yawRad = latchedYaw
            pitchRad = 0.0
            rollRad = 0.0

            velocity.set(0f, 0f, 0f)
            yawVel = 0.0
            pitchVel = 0.0

        }

        val deltaCiviliansOnboard = rescueTransfer.update(
            padConfirmed = padConfirmed,
            restLatched = restLatched,
            lastPadBlock = lastPadBlock,
            timeMs = timeMs
        )

        if (deltaCiviliansOnboard != 0) {
            parent.civiliansOnboardChanged(civiliansOnboard, deltaCiviliansOnboard)
        }


//        debugLogger.add("ship.position=${position}", timeMs)
//        debugLogger.add("levelAtRest=${levelAtRest}", timeMs)
//        debugLogger.add("pitchRad=${pitchRad}", timeMs)
//        debugLogger.add("rollRad=${rollRad}", timeMs)
//        debugLogger.add("pitchVel=${pitchVel}", timeMs)
//        debugLogger.add("restLatched=$restLatched", timeMs)

        // keep ship in bounds
        world.battleSpaceBounds.slideOnBounds(position, velocity, halfLength, COLLISION_EPS)

        // adjust chase camera focal point for movement
        val a = -(16f - 8f) / (250f - 50f)
        if (!restLatched) {
            chaseFocalPointWorld.set(
                position
                        + shipForwardVector().mulLocal(a * (velocityF - 50f) + 16f)
                        + shipRightVector().mulLocal(-velocityH / 30f)
                        + shipUpVector().mulLocal(-velocityV / 30f)
            )
        }
        else {
            chaseFocalPointWorld.set(position + shipForwardVector().mulLocal(a * (-50f) + 16f))
        }

        // set weapon directions inline with ship
        weaponYawRad = yawRad
        weaponPitchRad = pitchRad

        if (flightControls.firing) {
            firing = 1
            if (doubleLaserBoltPool.sinceLastFired == 0) {

                computeMuzzleWorld()

                doubleLaserBoltPool.activateNext(
                    muzzleWorldL,
                    muzzleWorldR,
                    weaponYawRad,
                    -weaponPitchRad
                )
                shotsFired += 2
                parent.audioPlayer.playSound(parent.audioPlayer.laser2)

//                debugLogger.printout()


            }
            doubleLaserBoltPool.sinceLastFired += dtMs

            val stallFirePenaltyMs = if (isStalling) 150 else 0
            if (doubleLaserBoltPool.sinceLastFired > doubleLaserBoltPool.maxIntervalMs + stallFirePenaltyMs) {
                doubleLaserBoltPool.sinceLastFired = 0
            }
        } else {
            firing = 0
        }

        renderer.nozzleGlow = engineGlowFromThrottle(fwdPower)

        instance.setPosition(position.x, position.y, position.z)
        instance.setDirection(yawRad, pitchRad, rollRad)
        instance.update()

        val log = log ?: return
        if (log.recording) {
            msSinceLastEvent += dtMs
            if (msSinceLastEvent > qInterval || firing == 1) {
                logEvent(timeMs, includeDirection = true)
            }
        }
    }

    fun computeDesiredPosition(dt: Float, authority: Float) {


        val dispF = velocityF * dt
        val dispH = velocityH * dt
        val dispV = velocityV * dt

        // Basis that ignores roll:
        computeBodyBasisNoRoll()

        desiredMove.set(0f, 0f, 0f)
        desiredMove.addScaled(moveForward, dispF)
        desiredMove.addScaled(moveRight,   dispH)
        desiredMove.addScaled(moveUp,      dispV)

        desiredPosition.set(position).addLocal(desiredMove)

        updateShipBottomCornersAt(desiredPosition)
    }



    override fun computeMuzzleWorld() {
        // 1) Base weapon basis from weaponYaw/weaponPitch
        computeWeaponBasis()

        // 2) Apply ship roll around weaponForward
        val cr = kotlin.math.cos(rollRad).toFloat()
        val sr = kotlin.math.sin(rollRad).toFloat()

        // right' = R * cosR + U * sinR
        weaponRightRolled.x = weaponRight.x * cr + weaponUp.x * sr
        weaponRightRolled.y = weaponRight.y * cr + weaponUp.y * sr
        weaponRightRolled.z = weaponRight.z * cr + weaponUp.z * sr

        // up' = -R * sinR + U * cosR
        weaponUpRolled.x = -weaponRight.x * sr + weaponUp.x * cr
        weaponUpRolled.y = -weaponRight.y * sr + weaponUp.y * cr
        weaponUpRolled.z = -weaponRight.z * sr + weaponUp.z * cr

        // 3) Transform both muzzles with the rolled weapon basis
        localToWorldWithBasis(
            muzzleLocalL,
            weaponRightRolled,
            weaponForward,
            weaponUpRolled,
            muzzleWorldL
        )

        localToWorldWithBasis(
            muzzleLocalR,
            weaponRightRolled,
            weaponForward,
            weaponUpRolled,
            muzzleWorldR
        )
    }


    enum class PadShape { CIRCLE, BOX }

    data class PadSpec(
        val shape: PadShape,
        val center: Vec3,
        val yawRad: Float,      // only used for BOX
        val radius: Float = 0f, // used for CIRCLE
        val halfX: Float = 0f,  // used for BOX (pre-yaw extents)
        val halfY: Float = 0f
    )

    fun isShipWithinPad2D(
        shipPos: Vec3,
        pad: PadSpec,
        shipFootprintRadius: Float,
        margin: Float
    ): Boolean {


        return when (pad.shape) {
            PadShape.CIRCLE -> {
                val r = shipFootprintRadius + margin
                val dx = shipPos.x - pad.center.x
                val dy = shipPos.y - pad.center.y
                val allowed = pad.radius - r
                if (allowed <= 0f) return false
                (dx * dx + dy * dy) <= (allowed * allowed)
            }

            PadShape.BOX -> {
                val r = shipFootprintRadius // - 3 * margin
                val dx = shipPos.x - pad.center.x
                val dy = shipPos.y - pad.center.y

                val c = kotlin.math.cos(-pad.yawRad)
                val s = kotlin.math.sin(-pad.yawRad)

                // rotate by -yaw: localX points along pad's local X axis
                val localX =  dx * c + dy * s
                val localY = -dx * s + dy * c

                val allowedX = pad.halfX - r
                val allowedY = pad.halfY - r
                if (allowedX <= 0f || allowedY <= 0f) return false

                abs(localX) <= allowedX && abs(localY) <= allowedY
            }
        }
    }


    /**
     * Continuous collision with sliding along AABB faces.
     * Mutates position/velocity. Returns true if any collision handling occurred this frame.
     *
     * Notes:
     * - Z is up in your world. To avoid fighting terrain, set horizontalOnly=true (default).
     *   That will zero the Z of the contact normal before sliding.
     */
    fun moveShipSlideOnCollide(
        position: Vec3,
        velocity: Vec3,
        dt: Float,
        radiusXY: Float,            // <---
        radiusZ: Float,             // <---
        epsilon: Float,
        queryEnemiesNearInto: (sweepMin: Vec3, sweepMax: Vec3, out: MutableList<Actor>) -> Unit,
        horizontalOnly: Boolean = false,   // <- important for Z-up worlds with terrain-following
        maxIters: Int = 4,                 // safety cap for edge/corner chains
        outCollisionInfo: CollisionInfo,
    ): Boolean {

        outCollisionInfo.collided = false
        outCollisionInfo.hasSupport = false
        outCollisionInfo.supportActor = null
        outCollisionInfo.supportNormal.set(0f, 0f, 0f)

        if (dt <= 0f) return false

        var collided = false
        var remaining = 1f   // we’ll consume [0,1] of the frame’s displacement
        var bestActor: Actor? = null
        var lastActor: Actor? = null
        var frameSupportActor: Actor? = null
        var frameSupportTopZ = 0f
        tmpSupportN.set(0f, 0f, 0f)
        tmpLastN.set(0f, 0f, 0f)   // add a scratch Vec3 tmpLastN



        // --- 0) Static depenetration once at frame start (resolves any start-inside states) ---
        candidateTargets.clear()

        // Query a small AABB around current position (not dependent on movement)
        tmpSweepMin.set(position.x - radiusXY, position.y - radiusXY, position.z - radiusZ)
        tmpSweepMax.set(position.x + radiusXY, position.y + radiusXY, position.z + radiusZ)
        queryEnemiesNearInto(tmpSweepMin, tmpSweepMax, candidateTargets)

        collided = staticDepenetrationWithCandidates(position, radiusXY, radiusZ, epsilon, candidateTargets, horizontalOnly) || collided

        // NOW it is safe to early out on zero velocity
        if (velocity.length2() == 0f) return collided

        var iter = 0
        while (iter < maxIters && remaining > 1e-4f) {
            // Displacement for the remaining slice of the frame
            tmpDisp.set(velocity).mulLocal(dt * remaining)
            val p0x = position.x
            val p0y = position.y
            val p0z = position.z
            val p1x = p0x + tmpDisp.x
            val p1y = p0y + tmpDisp.y
            val p1z = p0z + tmpDisp.z

            // Broad-phase
            tmpSweepMin.set(
                min(p0x, p1x) - radiusXY,
                min(p0y, p1y) - radiusXY,
                min(p0z, p1z) - radiusZ
            )
            tmpSweepMax.set(
                max(p0x, p1x) + radiusXY,
                max(p0y, p1y) + radiusXY,
                max(p0z, p1z) + radiusZ
            )

            candidateTargets.clear()
            queryEnemiesNearInto(tmpSweepMin, tmpSweepMax, candidateTargets)

            // No movement? done.
            if (tmpDisp.length2() <= 1e-12f) break

            // Ray dir for sweep
            tmpDir.set(p1x - p0x, p1y - p0y, p1z - p0z)

            // Find earliest hit among candidates
            var bestT = 1f
            var hit = false
            tmpN.set(0f, 0f, 0f)
            tmpBestN.set(0f, 0f, 0f) // you’ll need a scratch Vec3 tmpBestN

            for (a in candidateTargets) {

                if (!a.active) continue

                if (a === frameSupportActor) {
                    // position.z is ship center; if it's at/above the top plane (with a small tolerance), skip
                    if (position.z >= frameSupportTopZ - 0.05f) {
                        continue
                    }
                }

                val minx = a.instance.worldAabb.min.x - radiusXY
                val miny = a.instance.worldAabb.min.y - radiusXY
                val minz = a.instance.worldAabb.min.z - radiusZ
                val maxx = a.instance.worldAabb.max.x + radiusXY
                val maxy = a.instance.worldAabb.max.y + radiusXY
                val maxz = a.instance.worldAabb.max.z + radiusZ

                val inside =
                    p0x >= minx && p0x <= maxx &&
                            p0y >= miny && p0y <= maxy &&
                            p0z >= minz && p0z <= maxz

                if (inside) {
                    val bb = a as? BuildingBlockActor
                    if (bb != null) {
                        val realTopZ = bb.instance.worldAabb.max.z
                        if (p0z >= realTopZ - 0.05f) {
                            // treat as "support": force +Z normal, not sideways
                            val t = 0f
                            if (t < bestT) {
                                bestT = t
                                hit = true
                                bestActor = a
                                tmpBestN.set(0f, 0f, +1f)
                            }
                            continue
                        }
                    }
                }

                val t = rayAabbEnterT(
                    p0x, p0y, p0z,
                    tmpDir.x, tmpDir.y, tmpDir.z,
                    minx, miny, minz,
                    maxx, maxy, maxz,
                    tmpN
                )
                if (t >= 0f && t < bestT) {
                    bestT = t
                    hit = true
                    bestActor = a
                    tmpBestN.set(tmpN)   // <-- copy NOW, because tmpN will be overwritten by later candidates
                }
            }

            if (!hit) {
                // Free move for the remainder: p += disp
                position.mad(tmpDisp, 1f)
                break
            }

            val n = tmpBestN
            lastActor = bestActor
            tmpLastN.set(n)

            // If this hit is a "floor/support" hit, remember this actor as the support for this frame
            if (n.z > 0.95f) {
                frameSupportActor = bestActor
                // true (non-inflated) top plane:
                frameSupportTopZ = bestActor!!.instance.worldAabb.max.z
            }

            // Slide: remove only the *into-surface* component from velocity
            val vn = velocity.dot(n)
            if (vn < 0f) { // moving into the surface
                velocity.mad(n, -vn) // v = v - n*vn
            }

            // back off a bit
            position.mad(n, epsilon)

            // Move to contact
            position.mad(tmpDisp, bestT)

            // Record a "support" collision: something mostly upward-facing that we are pushing into
            if (n.z > 0.95f && vn < 0f) {
                tmpSupportN.set(n)

                outCollisionInfo.hasSupport = true
                outCollisionInfo.supportNormal.set(n)
                outCollisionInfo.supportActor = bestActor
            }

            collided = true

            // 5) Consume the time we’ve used and iterate with remaining
            val MIN_T = 1e-3f          // try 1e-3, if too aggressive try 1e-4
            val tUsed = max(bestT, MIN_T)
            remaining *= (1f - tUsed)

            // If velocity became tiny, we’re done
            if (velocity.length2() < 1e-8f) break

            iter++
        }

        if (collided) {
            outCollisionInfo.collided = true
            outCollisionInfo.normal.set(tmpLastN)     // last collision normal (or whatever you choose)
            outCollisionInfo.actor = lastActor
        }

        return collided
    }
    // ---------- Helpers ----------

    /** Resolve all overlaps (point vs expanded AABB) with smallest-penetration-first MTV. */
    private fun staticDepenetrationWithCandidates(
        position: Vec3,
        radiusXY: Float,
        radiusZ: Float,
        epsilon: Float,
        candidates: List<Actor>,
        horizontalOnly: Boolean
    ): Boolean {
        var moved = false
        val MAX_ITERS = 1
        var it = 0
        while (it < MAX_ITERS) {
            var bestPen = Float.POSITIVE_INFINITY
            var nx = 0f;
            var ny = 0f;
            var nz = 0f
            val px = position.x;
            val py = position.y;
            val pz = position.z

            var found = false
            for (a in candidates) {

                if (!a.active) continue   // <-- add this

                val minx = a.instance.worldAabb.min.x - radiusXY
                val miny = a.instance.worldAabb.min.y - radiusXY
                val minz = a.instance.worldAabb.min.z - radiusZ
                val maxx = a.instance.worldAabb.max.x + radiusXY
                val maxy = a.instance.worldAabb.max.y + radiusXY
                val maxz = a.instance.worldAabb.max.z + radiusZ

                if (px < minx || px > maxx || py < miny || py > maxy || pz < minz || pz > maxz) continue
                found = true

                // --- NEW: If we're above the real top face of a block, treat overlap as "support".
                // Prevent sideways depenetration that boots us off pad edges.
                val bb = a as? BuildingBlockActor
                if (bb != null && !horizontalOnly) {
                    val realTopZ = bb.instance.worldAabb.max.z   // true block top, not inflated
                    if (pz >= realTopZ - 0.05f) {                // small band near/above top (tune 0.02..0.10)
                        // Only allow +Z correction (dzMax) for this actor
                        val dzMax = maxz - pz
                        if (dzMax < bestPen) {
                            bestPen = dzMax
                            nx = 0f; ny = 0f; nz = +1f
                        }
                        continue
                    }
                }

                val dxMin = px - minx
                val dxMax = maxx - px
                val dyMin = py - miny
                val dyMax = maxy - py
                val dzMin = pz - minz
                val dzMax = maxz - pz

                // Correct outward normals (fixed signs)
                var pen = dxMin;
                var tx = -1f;
                var ty = 0f;
                var tz = 0f   // minX → -X
                fun pick(p: Float, xn: Float, yn: Float, zn: Float) {
                    if (p < pen) {
                        pen = p; tx = xn; ty = yn; tz = zn
                    }
                }
                pick(dxMax, +1f, 0f, 0f)  // maxX → +X
                pick(dyMin, 0f, -1f, 0f)  // minY → -Y
                pick(dyMax, 0f, +1f, 0f)  // maxY → +Y
//                pick(dzMin, 0f, 0f, -1f)  // minZ → -Z
//                pick(dzMax, 0f, 0f, +1f)  // maxZ → +Z
                if (!horizontalOnly) {
                    pick(dzMin, 0f, 0f, -1f)
                    pick(dzMax, 0f, 0f, +1f)
                }

                if (pen < bestPen) {
                    bestPen = pen; nx = tx; ny = ty; nz = tz
                }
            }

            if (!found) break

            if (horizontalOnly) {
                nz = 0f
                val l2 = nx * nx + ny * ny
                if (l2 > 0f) {
                    val inv = 1f / sqrt(l2)
                    nx *= inv; ny *= inv
                }
            }

            position.x += nx * (bestPen + epsilon)
            position.y += ny * (bestPen + epsilon)
            position.z += nz * (bestPen + epsilon)
            moved = true
            it++
        }
        return moved
    }

    /** Slab method: entry t in [0,1], -1 if none. Writes normal to outN. */
    private fun rayAabbEnterT(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        minx: Float, miny: Float, minz: Float,
        maxx: Float, maxy: Float, maxz: Float,
        outN: Vec3
    ): Float {
        if (dx == 0f && dy == 0f && dz == 0f) return -1f
        var tmin = 0f
        var tmax = 1f
        var nx = 0f;
        var ny = 0f;
        var nz = 0f

        // X
        if (dx == 0f) {
            if (ox < minx || ox > maxx) return -1f
        } else {
            val inv = 1f / dx
            var t0 = (minx - ox) * inv
            var t1 = (maxx - ox) * inv
            var nEnter = -1f  // minX → -X
            if (t0 > t1) {
                val tmp = t0; t0 = t1; t1 = tmp; nEnter = +1f
            } // maxX → +X
            if (t0 > tmin) {
                tmin = t0; nx = nEnter; ny = 0f; nz = 0f
            }
            if (t1 < tmax) tmax = t1
            if (tmin > tmax) return -1f
        }
        // Y
        if (dy == 0f) {
            if (oy < miny || oy > maxy) return -1f
        } else {
            val inv = 1f / dy
            var t0 = (miny - oy) * inv
            var t1 = (maxy - oy) * inv
            var nEnter = -1f  // minY → -Y
            if (t0 > t1) {
                val tmp = t0; t0 = t1; t1 = tmp; nEnter = +1f
            } // maxY → +Y
            if (t0 > tmin) {
                tmin = t0; nx = 0f; ny = nEnter; nz = 0f
            }
            if (t1 < tmax) tmax = t1
            if (tmin > tmax) return -1f
        }
        // Z
        if (dz == 0f) {
            if (oz < minz || oz > maxz) return -1f
        } else {
            val inv = 1f / dz
            var t0 = (minz - oz) * inv
            var t1 = (maxz - oz) * inv
            var nEnter = -1f  // minZ → -Z
            if (t0 > t1) {
                val tmp = t0; t0 = t1; t1 = tmp; nEnter = +1f
            } // maxZ → +Z
            if (t0 > tmin) {
                tmin = t0; nx = 0f; ny = 0f; nz = nEnter
            }
            if (t1 < tmax) tmax = t1
            if (tmin > tmax) return -1f
        }

        val inside =
            ox >= minx && ox <= maxx &&
                    oy >= miny && oy <= maxy &&
                    oz >= minz && oz <= maxz

        if (inside) {
            // pick the smallest penetration axis, like your static depenetration
            val dxMin = ox - minx
            val dxMax = maxx - ox
            val dyMin = oy - miny
            val dyMax = maxy - oy
            val dzMin = oz - minz
            val dzMax = maxz - oz

            var pen = dxMin; nx = -1f; ny = 0f; nz = 0f
            fun pick(p: Float, x: Float, y: Float, z: Float) {
                if (p < pen) { pen = p; nx = x; ny = y; nz = z }
            }
            pick(dxMax, +1f, 0f, 0f)
            pick(dyMin, 0f, -1f, 0f)
            pick(dyMax, 0f, +1f, 0f)
            pick(dzMin, 0f, 0f, -1f)
            pick(dzMax, 0f, 0f, +1f)

            outN.set(nx, ny, nz)
            return 0f
        }

        if (tmin < 0f || tmin > 1f) return -1f
        outN.set(nx, ny, nz)
        return tmin
    }

    val smoothForward = SmoothTarget(0f, -1f, 0f)
    override fun replayUpdate(flightLog: FlightLog, event: ActorLog.LogEvent?, dt: Float, dtMs: Int, timeMs: Int) {
        if (event != null) {

            setPosition(event.x, event.y, event.z, event.angleP, event.angleE, event.angleB)

            computeBodyBasisNoRoll()

            smoothForward.updateTowards(moveForward, dtMs, stiffness = 0.005f)

            if (!flightLog.replaySeeking && event.timeMs - lastReplayTimeMs < 1000 && !flightLog.shipSnapToOnNextReplayUpdate) {

                // not seeking so lag moveForward and spring focal point
                val camForward = smoothForward.pos.copy()
                if (camForward.length2() > 1e-6f) {
                    camForward.normalizeInPlace()
                } else {
                    camForward.set(0f, -1f, 0f) // fallback
                }

                computeCameraTargetFromVelocity(
                    position,
                    replayVelocity,
                    camForward,
                    chaseFocalPointWorld,
                    outSpeed = tmpSpeed,
                    outForwardSpeed = tmpForward,
                    )
                smoothCam.updateTowards(chaseFocalPointWorld, dtMs, stiffness = 0.3f)

            } else {
                renderer.reset()
                flightLog.shipSnapToOnNextReplayUpdate = false
                computeCameraTargetFromVelocity(
                    position,
                    replayVelocity,
                    moveForward,
                    chaseFocalPointWorld
                )
                smoothCam.snapTo(chaseFocalPointWorld)
            }

            chaseFocalPointWorld.set(
                smoothCam.pos.x,
                smoothCam.pos.y,
                smoothCam.pos.z
            )

            lastReplayTimeMs = event.timeMs


//            println(chaseFocalPointWorld)

            doubleLaserBoltPool.sinceLastFired += dtMs
            if (event.firing == 1 && !flightLog.replaySeeking && !flightLog.replayPaused) {
                if (doubleLaserBoltPool.sinceLastFired > doubleLaserBoltPool.maxIntervalMs) {
                    doubleLaserBoltPool.sinceLastFired = 0
                }
                if (doubleLaserBoltPool.sinceLastFired == 0) {

                    weaponYawRad = yawRad
                    weaponPitchRad = pitchRad
                    computeMuzzleWorld()

                    doubleLaserBoltPool.activateNext(
                        muzzleWorldL,
                        muzzleWorldR,
                        yawRad,
                        -pitchRad
                    )
                    parent.audioPlayer.playSound(parent.audioPlayer.laser2)
                }
            }


            val forward = tmpForward[0].coerceAtLeast(0f) // ignore reverse
            val throttle01 = ((forward - 5f) / (120f - 5f)).coerceIn(0f, 1f)
            renderer.nozzleGlow = engineGlowFromThrottle(throttle01)

            if (event.destroyed == 1) {
                active = false
                hitPoints = 0
                parent.shipHit(true)
                explosion?.activateLarge(position)
            } else if (event.hit == 1) {
                hitPoints--
                renderer.flashLinesOnce(timeMs)
                explosion?.activateSmall(position)
                parent.shipHit(false)
            }

            instance.setPosition(position.x, position.y, position.z)
            instance.setDirection(yawRad, pitchRad, rollRad)
            instance.update()

        }
    }

    fun computeCameraTargetFromVelocity(
        shipPos: Vec3,
        velocity: Vec3,
        shipForwardFromAngles: Vec3?,   // can be null
        outCam: Vec3,
        minLead: Float = 0f,
        maxLead: Float = 16f,
        speedScale: Float = 0.04f,
        wAlong: Float = 2f,
        wSide: Float = 1f,
        wUp: Float = 1f,
        outSpeed: FloatArray? = null,      // size 1
        outForwardSpeed: FloatArray? = null // size 1 (vAlong)
    ) {
        val len2 = velocity.length2()
        val worldUp = Vec3(0f, 0f, 1f)

        val baseFwd =
            if (shipForwardFromAngles != null && shipForwardFromAngles.length2() > 1e-6f)
                shipForwardFromAngles.copy().normalizeInPlace()
            else
                Vec3(0f, -1f, 0f) // in our world -y is forward


        if (len2 < 1e-6f) {
            // Stationary → look ahead along facing if available, else some default.

            outCam.set(
                shipPos.x + baseFwd.x * maxLead,
                shipPos.y + baseFwd.y * maxLead,
                shipPos.z + baseFwd.z * maxLead
            )
            return
        }

        // Velocity direction
        val vel = velocity.copy()
        val velDir = vel.copy().normalizeInPlace()

        // Choose forward basis:
        // - Prefer shipForwardFromAngles for "flavor"
        // - Fallback to velDir if no valid forward from angles
        val fwdBasis =
            if (shipForwardFromAngles != null && shipForwardFromAngles.length2() > 1e-6f)
                shipForwardFromAngles.copy().normalizeInPlace()
            else
                velDir

        // Build right/up from forward basis
        var right = fwdBasis * worldUp
        if (right.length2() < 1e-6f) {
            right = Vec3(1f, 0f, 0f)
        } else {
            right.normalizeInPlace()
        }
        val up = (right * fwdBasis).normalizeInPlace()

        // Decompose actual velocity in this basis
        val vAlong = velocity.dot(fwdBasis)
        val vSide = velocity.dot(right)
        val vVert = velocity.dot(up)

        // Build biased direction
        val biasedX =
            fwdBasis.x * (vAlong * wAlong) +
                    right.x * (vSide * wSide) +
                    up.x * (vVert * wUp)

        val biasedY =
            fwdBasis.y * (vAlong * wAlong) +
                    right.y * (vSide * wSide) +
                    up.y * (vVert * wUp)

        val biasedZ =
            fwdBasis.z * (vAlong * wAlong) +
                    right.z * (vSide * wSide) +
                    up.z * (vVert * wUp)

        val biased = Vec3(biasedX, biasedY, biasedZ)
        val bl2 = biased.length2()

        val dir =
            if (bl2 < 1e-6f) velDir   // fallback if everything cancels
            else biased.normalizeInPlace()

        val speed = sqrt(len2.toDouble()).toFloat()

        outSpeed?.set(0, speed)
        outForwardSpeed?.set(0, vAlong)

        val lead = speed * speedScale

        outCam.set(
            shipPos.x + baseFwd.x * maxLead - dir.x * lead,
            shipPos.y + baseFwd.y * maxLead - dir.y * lead,
            shipPos.z + baseFwd.z * maxLead - dir.z * lead
        )
    }

    fun computeLocalBottomCornersFromModel(): Array<Vec3> {
        val la = instance.model.localAabb
        val z = la.min.z // bottom surface

        val rearY = la.min.y
        val frontY = la.max.y
        val leftX = la.min.x
        val rightX = la.max.x
        val midX = (leftX + rightX) * 0.5f

        return arrayOf(
            Vec3(leftX,  rearY,  z), // rear-left
            Vec3(rightX, rearY,  z), // rear-right
            Vec3(midX,   frontY, z)  // nose/front apex
        )
    }

    private fun engineGlowFromThrottle(fwdPower: Float): Float {
        // Make low throttle visible, high throttle really pop.
        val t = smooth01(fwdPower)
        return 0.15f + 1.85f * (t * t)   // range ~0.15 .. 2.0
    }

    private fun throttleFromForwardSpeed(speed: Float): Float {
        val vIdle = 8f     // tweak
        val vMax  = 120f   // tweak
        return ((speed - vIdle) / (vMax - vIdle)).coerceIn(0f, 1f)
    }


    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        renderer.draw(vpMatrix, timeMs)

//        renderer.drawCircleWireSwapYZHorizontal(
//            vpMatrix = vpMatrix,
//            centerWorldX = position.x,
//            centerWorldY = position.y,
//            centerWorldZ = position.z,
//            radius = landingFootprintRadius,
//            segments = 48,
//            color = renderer.highlightLineColor
//        )

//        renderer.drawAabbWire(vpMatrix, instance.worldAabb, floatArrayOf(0f, 1f, 1f, 1f))
    }

}