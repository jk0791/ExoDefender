package com.jimjo.exodefender

import kotlin.math.exp
import kotlin.random.Random

//    Actor (abstract)
//    ├── ShipActor
//    └── SingleMuzzleActor (abstract)
//         ├── EnemyActor (abstract)
//         │    ├── FlyingEnemyActor
//         │    └── GroundEnemyActor
//         └── FriendlyActor (abstract)
//              └── GroundFriendlyActor


enum class ActorType {
    FRIENDLY, ENEMY, GROUND_FRIENDLY, EASY_GROUND_ENEMY, GROUND_ENEMY, EASY_FLYING_ENEMY, FLYING_ENEMY, ADV_FLYING_ENEMY, FRIENDLY_STRUCTURE,
}
abstract class Actor: GridActor {
    override abstract val instance: ModelInstance
    protected abstract val renderer: WireRenderer
    protected lateinit var parent: ModelParent
    lateinit var world: World
    lateinit var ship: ShipActor
    lateinit var log: ActorLog
    lateinit var laserBoltPool: SingleLaserBoltPool
    var explosion: Explosion? = null
    var explosionFlash: ExplosionFlashSystem? = null

    var drawEditorBounds = false

    val position = Vec3()
    var yawRad = 0.0   // was angleP
    var pitchRad = 0.0 // was angleE
    var rollRad = 0.0  // was angleB
    var weaponYawRad: Double = 0.0
    var weaponPitchRad: Double = 0.0

    protected val moveForward = Vec3()
    protected val moveRight   = Vec3()
    protected val moveUp      = Vec3()

    // Weapon basis (no roll)
    protected val weaponForward = Vec3()
    protected val weaponRight   = Vec3()
    protected val weaponUp      = Vec3()

    var playSoundWhenDestroyed = false

    val initialPosition = Vec3()
    var initialYawRad = 0.0
    protected open val randomInitialYaw = false
    var initialPitchRad = 0.0

    override var lastQueryId: Int = 0 // used in World when querying if Actor is within a cell

    override var active = true
    var actorIndex: Int = -1
    open val maxHitPoints = 0
    var hitPoints = maxHitPoints
    open val enableLogging: Boolean = true
    var firingEnabled = true

    var firing = 0
    var closeToShip = false
    var selected = false

    protected var spinRate = 0.0
    protected var spinActiveMaxDurMs = 0
    protected var spinStationaryMaxDurMs = 0
    private var spinDir = 0 // -1, 0, +1
    private var nextSpinStartMs = 0
    private var nextSpinStopMs = 0

    protected val tmpPosition = Vec3()
    protected val hitPosition = Vec3()

    abstract val continuous: Boolean
    abstract val qInterval: Int
    protected var msSinceLastEvent = 0


    open fun initialize(parent: ModelParent, world: World, log: ActorLog?, ship: ShipActor, laserBoltPool: SingleLaserBoltPool, explosion: Explosion?, explosionFlash: ExplosionFlashSystem?) {

        this.parent = parent
        this.world = world
        this.ship = ship
        this.laserBoltPool = laserBoltPool
        this.explosion = explosion
        this.explosionFlash = explosionFlash
        if (log != null) {
            this.log = log
        }
    }
    open fun reset() {
        resetRenderer()
        hitPoints = maxHitPoints
        active = true
        closeToShip = false
        resetPosition()
        resetSpin(0)
        drawEditorBounds = false
    }

    fun resetSpin(timeMs: Int) {
        if (spinRate != 0.0) {
            spinDir = 0
            nextSpinStartMs = timeMs + Random.nextInt(spinStationaryMaxDurMs)
        }
    }

    fun resetRenderer() {
        renderer.reset()
    }

    open fun select() {
        selected = true
        renderer.currentLineColor = renderer.highlightLineColor
        drawEditorBounds = true
    }

    fun unselect() {
        selected = false
        renderer.currentLineColor = renderer.normalLineColor
        drawEditorBounds = false
    }

    fun resetPosition() {
        if (randomInitialYaw) {
            initialYawRad = Random.nextDouble()
        }
        setPositionAndUpdate(initialPosition, initialYawRad, initialPitchRad)
    }
    fun setPosition(x: Float, y: Float, z: Float, yawRad: Double? = null, pitchRad: Double? = null, rollRad: Double? = null) {
        position.set(x, y, z)
        instance.setPosition(x, y, z)
        setDirection(yawRad, pitchRad, rollRad)
    }

    fun setPosition(newPosition: Vec3, yawRad: Double? = null, pitchRad: Double? = null, rollRad: Double? = null) {
        setPosition(newPosition.x, newPosition.y, newPosition.z, yawRad, pitchRad, rollRad)
    }

    fun setPositionAndUpdate(x: Float, y: Float, z: Float, yawRad: Double? = null, pitchRad: Double? = null) {
        setPosition(x, y, z, yawRad, pitchRad)
        instance.update()
    }

    fun setPositionAndUpdate(newPosition: Vec3, yawRad: Double? = null, pitchRad: Double? = null) {
        setPosition(newPosition, yawRad, pitchRad)
        instance.update()
    }

    fun setDirectionAndUpdate(yawRad: Double? = null, pitchRad: Double? = null) {
        setDirection(yawRad, pitchRad, rollRad)
        instance.update()
    }

    fun setDirection(yawRad: Double? = null, pitchRad: Double? = null, rollRad: Double? = null) {
        if (yawRad != null && pitchRad != null && rollRad != null) {
            instance.setDirection(yawRad, pitchRad, rollRad)
            this.yawRad = yawRad
            this.pitchRad = pitchRad
            this.rollRad = rollRad
        }
        if (yawRad != null && pitchRad != null) {
            instance.setDirection(yawRad, pitchRad)
            this.yawRad = yawRad
            this.pitchRad = pitchRad
        } else if (yawRad != null) {
            instance.setDirection(yawRad, instance.pitchRad)
            this.yawRad = yawRad
        } else if (pitchRad != null) {
            instance.setDirection(instance.yawRad, pitchRad)
            this.pitchRad = pitchRad
        }
    }

    open fun update(dt: Float, dtMs: Int, timeMs: Int) {

        if (!enableLogging) return

        if (log.recording) {
            if (firing == 1) {
                logEvent(timeMs, includeWeaponDirection = true)
                // reset to save space in log
            }
            else if (continuous) {
                msSinceLastEvent += dtMs
                if (msSinceLastEvent > qInterval) {
                    logEvent(timeMs, includeWeaponDirection = true)
                }
            }
        }
    }

    fun spinUpdateGetYaw(dt: Float, timeMs: Int): Double {
        if (spinRate != 0.0) {
            if (spinDir == 0) {
                // not spinning, check if we need to
                if (timeMs > nextSpinStartMs) {

                    // get direction and when to spin until
                    spinDir = rndSign()
                    nextSpinStopMs = timeMs + Random.nextInt(spinActiveMaxDurMs)
                }
            }
            else {
                // currently spinning
                if (timeMs < nextSpinStopMs) {
                    // continue spinning
                    return yawRad + spinRate * dt * spinDir

                }
                else {
                    // stop spinning and get when to start again
                    spinDir = 0
                    nextSpinStartMs = timeMs + Random.nextInt(spinStationaryMaxDurMs)
                }

            }
        }
        return yawRad
    }

    open fun onHit(timeMs: Int, enemyHit: Boolean, hitPosition: Vec3) {
        if (!log.flightLog.replayActive)  {
            if (enemyHit) {
                log.flightLog.shotsHit++
            }
            if (hitPoints > 0){
                renderer.flashLinesOnce(timeMs)
                hitPoints--
                if (hitPoints == 0) {
                    active = false
                    world.removeActorFromWorld(this)
                    logEvent(timeMs, hit = 1, destroyed = 1)
                    parent.notifyActorDestroyed(playSoundWhenDestroyed, this is FriendlyActor)
                    explosion?.activateLarge(position)
                    explosionFlash?.spawnWorldLarge(position)
                } else {
                    logEvent(timeMs, hit = 1)
                    explosion?.activateSmall(position)
                    explosionFlash?.spawnWorldSmall(position)
                }

            }
        }
    }

    open fun replayUpdate(flightLog: FlightLog, event: ActorLog.LogEvent?, dt: Float, dtMs: Int, timeMs: Int) {
        if (event != null) {
            if (continuous) {
                tmpPosition.set(event.x, event.y, event.z)
                setPositionAndUpdate(tmpPosition, yawRad = spinUpdateGetYaw(dt, timeMs))
                if (flightLog.replaySeeking) {
                    renderer.reset()
                }
            }

            if (event.destroyed == 1) {
                active = false
                parent.notifyActorDestroyed(playSoundWhenDestroyed && !flightLog.replaySeeking, this is FriendlyActor)
                explosion?.activateLarge(position)
                explosionFlash?.spawnWorldLarge(position)
            } else if (event.hit == 1) {
                if (!flightLog.replaySeeking) renderer.flashLinesOnce(timeMs)
                explosion?.activateSmall(position)
                explosionFlash?.spawnWorldSmall(position)
            }
        }
    }

    fun replayUpdateMinimum(dt: Float, timeMs: Int) {
        setDirectionAndUpdate(yawRad = spinUpdateGetYaw(dt, timeMs))
    }

    fun logEvent(flightTimeMs: Int, hit: Int = 0, destroyed: Int = 0, includeDirection: Boolean = false, includeWeaponDirection: Boolean = false) {

        if (!enableLogging) return

        log.events.add(
            ActorLog.LogEvent(
                timeMs = flightTimeMs,
                x = position.x,
                y = position.y,
                z = position.z,
                angleP = if (includeDirection) yawRad else 0.0,
                angleE = if (includeDirection) pitchRad else 0.0,
                angleB = if (includeDirection) rollRad else 0.0,
                weaponAngleP = if (includeWeaponDirection && firing == 1) weaponYawRad else 0.0,
                weaponAngleE = if (includeWeaponDirection && firing == 1) weaponPitchRad else 0.0,
                firing = firing,
                hit = hit,
                destroyed = destroyed,
            )
        )
        if (destroyed == 1) log.actorDestroyed = true
        msSinceLastEvent = 0
    }


    open fun computeMuzzleWorld() {}

    protected fun buildBasisFromYawPitch(
        yawRad: Double,
        pitchRad: Double,
        outRight: Vec3,
        outForward: Vec3,
        outUp: Vec3
    ) {
        val cy = kotlin.math.cos(yawRad).toFloat()
        val sy = kotlin.math.sin(yawRad).toFloat()
        val cp = kotlin.math.cos(pitchRad).toFloat()
        val sp = kotlin.math.sin(pitchRad).toFloat()

        // Right (1,0,0)
        outRight.x = cy
        outRight.y = sy
        outRight.z = 0f

        // Forward (0,1,0)
        outForward.x = -sy * cp
        outForward.y =  cy * cp
        outForward.z =  sp

        // Up (0,0,1) -> version that matched your visuals:
        outUp.x =  sy * sp
        outUp.y = -cy * sp
        outUp.z =  cp

        // Legacy forward = -Y flip
        outRight.y   = -outRight.y
        outForward.y = -outForward.y
        outUp.y      = -outUp.y
    }

    protected fun computeBodyBasisNoRoll() {
        buildBasisFromYawPitch(
            yawRad,
            pitchRad,
            moveRight,
            moveForward,
            moveUp
        )
    }

    protected fun computeWeaponBasis() {
        buildBasisFromYawPitch(
            weaponYawRad,
            weaponPitchRad,
            weaponRight,
            weaponForward,
            weaponUp
        )
    }

    protected fun localToWorldWithBasis(
        local: Vec3,
        right: Vec3,
        fwd: Vec3,
        up: Vec3,
        out: Vec3
    ) {
        out.x = position.x +
                right.x * local.x +
                fwd.x   * local.y +
                up.x    * local.z

        out.y = position.y +
                right.y * local.x +
                fwd.y   * local.y +
                up.y    * local.z

        out.z = position.z +
                right.z * local.x +
                fwd.z   * local.y +
                up.z    * local.z
    }

    protected fun interceptTime(
        sx: Float, sy: Float, sz: Float,
        px: Float, py: Float, pz: Float,
        vx: Float, vy: Float, vz: Float,
        projSpeed: Float
    ): Float {
        val rx = px - sx
        val ry = py - sy
        val rz = pz - sz

        val vv = vx*vx + vy*vy + vz*vz
        val rr = rx*rx + ry*ry + rz*rz
        val rv = rx*vx + ry*vy + rz*vz
        val ss = projSpeed * projSpeed

        // Solve (v·v - s²)t² + 2(r·v)t + (r·r) = 0
        val a = vv - ss
        val b = 2f * rv
        val c = rr

        // If a ~ 0, fall back to linear
        if (kotlin.math.abs(a) < 1e-6f) {
            // t = -c / b (but we want positive)
            if (kotlin.math.abs(b) < 1e-6f) return 0f
            val t = -c / b
            return if (t > 0f) t else 0f
        }

        val disc = b*b - 4f*a*c
        if (disc < 0f) return 0f

        val sqrtDisc = kotlin.math.sqrt(disc)
        val t1 = (-b - sqrtDisc) / (2f * a)
        val t2 = (-b + sqrtDisc) / (2f * a)

        // choose smallest positive time
        var t = Float.POSITIVE_INFINITY
        if (t1 > 0f && t1 < t) t = t1
        if (t2 > 0f && t2 < t) t = t2
        return if (t.isFinite()) t else 0f
    }
    abstract fun draw(vpMatrix: FloatArray, timeMs: Int)

    open fun toTemplate(): ActorTemplate? = null
}

abstract class SingleMuzzleActor : Actor() {

    // weapon orientation already in Actor:
    // var weaponYawRad, weaponPitchRad
    // val weaponRight, weaponForward, weaponUp
    // fun computeWeaponBasis()
    // fun localToWorldWithBasis(...)

    protected val muzzleLocal = Vec3()
    val muzzleWorld = Vec3()

    var muzzleSideOffset    = 0f
    var muzzleForwardOffset = 0f
    var muzzleUpOffset      = 0f

    override fun computeMuzzleWorld() {
        computeWeaponBasis()

        muzzleLocal.x = muzzleSideOffset
        muzzleLocal.y = muzzleForwardOffset
        muzzleLocal.z = muzzleUpOffset

        localToWorldWithBasis(
            muzzleLocal,
            weaponRight,
            weaponForward,
            weaponUp,
            muzzleWorld
        )
    }

    override fun replayUpdate(flightLog: FlightLog, event: ActorLog.LogEvent?, dt: Float, dtMs: Int, timeMs: Int) {
        super.replayUpdate(flightLog, event, dt, dtMs, timeMs)

        if (event != null) {
            if (event.firing == 1 && !flightLog.replaySeeking) {

                computeMuzzleWorld()
                laserBoltPool.activateNext(muzzleWorld, event.weaponAngleP, event.weaponAngleE)
            }
        }
    }
}

// ----------------------------
// Enemy base
// ----------------------------
abstract class EnemyActor(
    override val instance: ModelInstance,
    override val renderer: WireRenderer
) : SingleMuzzleActor() {

    override val maxHitPoints = 5
    open var aggressionFactor = 0.2f // effectively average number of shots fired per second
    open val skillFactor = 0.8f // 0f - 1f (1f maximum predictive accuracy)
    open val reactionTimeMs = 150 // lower the quicker the reaction time (probably 100 - 250)

    open val targetOnlyShip = false
    var shipInRange = false

//    val weaponLocalOffset = Vec3(0f, 0f, 0f)
    private val targetPosition = Vec3()
    private val sqTargetingDistanceNearest = 120f * 120f
    private val sqTargetingDistanceIntermediate = 180f * 180f
    private val sqTargetingDistanceFurthest = 400f * 400f
    private val sqTargetingRange = sqTargetingDistanceIntermediate - sqTargetingDistanceNearest
    private var sqDistanceToShip = 0f

    var nextAimUpdateMs = 0
    val cachedAimVelocity = Vec3()


    init {
        playSoundWhenDestroyed = true
    }

    override fun reset() {
        super.reset()
        shipInRange = false
    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

        if (firingEnabled) {
            val p: Float
            if (closeToShip) {
                p = 1f - exp(-4f * dt)
                closeToShip = false // reset
            }
            else {
                p = 1f - exp(-aggressionFactor * dt)
            }
            if (Random.nextFloat() < p) {

                firing = 1

                computeMuzzleWorld()

                val deltaXtoShip = ship.position.x - muzzleWorld.x
                val deltaYtoShip = ship.position.y - muzzleWorld.y
                val deltaZtoShip = ship.position.z - muzzleWorld.z

                sqDistanceToShip = deltaXtoShip * deltaXtoShip + deltaYtoShip * deltaYtoShip + deltaZtoShip * deltaZtoShip

                val nearestFriendly =
                    if (world.destructibleStructure == null)
                        world.proximity.nearestFriendlyForEnemy(this, actorIndex, timeMs)
                    else
                        null
                val anyFriendliesAlive = (nearestFriendly != null)

                val shipTargeted: Boolean
                val friendlyTargeted: Boolean
                if (ship.active) {

                    shipInRange = sqDistanceToShip < sqTargetingDistanceFurthest

                    if (!shipInRange) {
                        shipTargeted = false
                    }
                    else if (sqDistanceToShip < sqTargetingDistanceNearest) {
                        shipTargeted = true
                    } else {

                        // between sqTargetingDistanceNearest and sqTargetingDistanceFurthest
                        if (!anyFriendliesAlive || targetOnlyShip) {
                            shipTargeted = true
                        } else {

                            // roll the dice to decide who to target

                            val weighting: Float // 0.5f - 1.0f
                            if (sqDistanceToShip > sqTargetingDistanceIntermediate) {
                                weighting = 0.5f
                            } else {
                                weighting =
                                    ((sqTargetingDistanceIntermediate - sqDistanceToShip) / sqTargetingRange + 1) / 2f
                            }
                            if (Random.nextFloat() < weighting) {
                                shipTargeted = true
                            } else {
                                shipTargeted = false
                            }
                        }
//                println("weighting=${df2.format(weighting)} distanceToShip=${Math.sqrt(sqDistanceToShip.toDouble()).toInt()}")
                    }
                } else {
                    shipTargeted = false
                }

                if (shipTargeted) {
                    friendlyTargeted = false
                    targetPosition.set(ship.position)

                    if (timeMs >= nextAimUpdateMs) {
                        cachedAimVelocity.set(ship.velocity)
                        nextAimUpdateMs = timeMs + reactionTimeMs
                    }

                    val vx = cachedAimVelocity.x
                    val vy = cachedAimVelocity.y
                    val vz = cachedAimVelocity.z


                    val t = interceptTime(
                        muzzleWorld.x, muzzleWorld.y, muzzleWorld.z,
                        ship.position.x, ship.position.y, ship.position.z,
                        vx, vy, vz,
                        laserBoltPool.boltVelocity
                    ).coerceAtMost(5.0f) * skillFactor


                    targetPosition.x += vx * t
                    targetPosition.y += vy * t
                    targetPosition.z += vz * t

                } else {
                    // target a non-ship friendly
                    val friendlyTarget: FriendlyActor?
                    if (world.destructibleStructure != null) {
                        friendlyTarget = world.destructibleStructure
                    }
                    else {
                        friendlyTarget = nearestFriendly
                    }
                    if (friendlyTarget != null) {

                        friendlyTargeted = true
                        targetPosition.set(friendlyTarget.position)

                        // add some random innacuracy
                        targetPosition.x += Random.nextFloat() * 4 - 2f
                        targetPosition.y += Random.nextFloat() * 4 - 2f
                        targetPosition.z += Random.nextFloat() * 2f
                    }
                    else {
                        friendlyTargeted = false
                    }
                }

                if (shipTargeted || friendlyTargeted) {

                    weaponYawRad = getPlanAngle(muzzleWorld, targetPosition)
                    weaponPitchRad = getElevationAngle(weaponYawRad,
                        (targetPosition.y - muzzleWorld.y).toDouble(),
                        (targetPosition.z - muzzleWorld.z).toDouble())

//            println("anglePtoDart=${df1.format(anglePtoDart)} anglePtoDart=${df1.format(angleEtoDart)}")
                    laserBoltPool.activateNext(muzzleWorld, weaponYawRad, weaponPitchRad)


                } else {
                    firing = 0
                }
            } else {
                firing = 0
            }
        }
        super.update(dt, dtMs, timeMs) // for logging
    }


    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        renderer.draw(vpMatrix, timeMs)
//        renderer.drawAabbWire(vpMatrix, instance.worldAabb, floatArrayOf(0f, 1f, 1f, 1f))

    }
}

class GroundTrainingTargetActor(
    instance: ModelInstance,
    renderer: WireRenderer
) : EnemyActor(instance, renderer) {

    override val continuous = false
    override val maxHitPoints = 1
    override val qInterval = 400

    init {
        firingEnabled = false
    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

        instance.update()
        super.update(dt, dtMs, timeMs) // for firing and logging
    }
    override fun toTemplate(): ActorTemplate =
        GroundTargetTemplate(
            position = position,
            yaw = yawRad
        )
}

class EasyGroundEnemyActor(
    instance: ModelInstance,
    renderer: WireRenderer
) : EnemyActor(instance, renderer) {

    override var aggressionFactor = 0.15f
    override val maxHitPoints = 1
    override val skillFactor = 0.6f // 0f - 1f (1f maximum predictive accuracy)
    override val reactionTimeMs = 200 // lower the quicker the reaction time (probably 100 - 250)
    override val randomInitialYaw = true

    override val continuous = false
    override val qInterval = 400

    init {
        muzzleUpOffset = 1.5f

        spinRate = 0.5
        spinActiveMaxDurMs = 1000
        spinStationaryMaxDurMs = 4000

    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

//        instance.update()
        setDirectionAndUpdate(yawRad = spinUpdateGetYaw(dt, timeMs))
        super.update(dt, dtMs, timeMs) // for logging
    }

    override fun toTemplate(): ActorTemplate =
        EasyGroundEnemyTemplate(
            position = position,
            yaw = yawRad
        )
}

class GroundEnemyActor(
    instance: ModelInstance,
    renderer: WireRenderer
) : EnemyActor(instance, renderer) {

    override val continuous = false
    override val qInterval = 400
    override val randomInitialYaw = true

    init {
        muzzleUpOffset = 1.5f

        spinRate = 0.5
        spinActiveMaxDurMs = 1000
        spinStationaryMaxDurMs = 4000

    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

//        instance.update()
        setDirectionAndUpdate(yawRad = spinUpdateGetYaw(dt, timeMs))
        super.update(dt, dtMs, timeMs) // for logging
    }

    override fun toTemplate(): ActorTemplate =
        GroundEnemyTemplate(
            position = position,
            yaw = yawRad
        )
}

class EasyFlyingEnemyActor(
    instance: ModelInstance,
    renderer: WireRenderer
) : EnemyActor(instance, renderer) {

    override var aggressionFactor = 0.15f
    override val maxHitPoints = 1
    override val skillFactor = 0.6f // 0f - 1f (1f maximum predictive accuracy)
    override val reactionTimeMs = 200 // lower the quicker the reaction time (probably 100 - 250)
    override val randomInitialYaw = true

    override val continuous = true

    // ---- Tuning knobs ----
    var flyingRadius = 20f // 50f
    val tangentialSpeed = 25f
    var antiClockWise = false

    override val qInterval = 400
    var timeCounter = 0f
    var direction = 1
    val debugBoundsCenter = Vec3()

    init {
        muzzleUpOffset = 1.5f

        initialPosition.set(instance.position)
        timeCounter += Random.nextFloat() * 4f

        spinRate = 4.0
        spinActiveMaxDurMs = 500
        spinStationaryMaxDurMs = 3000

    }

    override fun reset() {
        super.reset()
        timeCounter += Random.nextFloat() * 4f
        direction = if (antiClockWise) -1 else 1

    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

        // Angular speed chosen so linear speed stays constant:
        // v = ω * r  =>  ω = v / r
        val radius = flyingRadius.coerceAtLeast(0.001f)
        val angularSpeed = tangentialSpeed / radius

        // Advance angle (timeCounter is now angle in radians)
        timeCounter += angularSpeed * dt * direction

        val dx = radius * kotlin.math.cos(timeCounter)
        val dy = radius * kotlin.math.sin(timeCounter)

        setPositionAndUpdate(
            initialPosition.x + dx,
            initialPosition.y + dy,
            initialPosition.z,
            yawRad = spinUpdateGetYaw(dt, timeMs)
        )

        world.updateEnemyInGrid(this)
        super.update(dt, dtMs, timeMs)
    }

    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        super.draw(vpMatrix, timeMs)
        if (drawEditorBounds) {
            debugDrawCircularPath(vpMatrix, floatArrayOf(1f, 0f, 0f, 1f), false)
        }
    }

    fun debugDrawCircularPath(vpMatrix: FloatArray, color: FloatArray, fixed: Boolean) {

        if (fixed) debugBoundsCenter.set(initialPosition) else debugBoundsCenter.set(position)

        renderer.drawCircleWireSwapYZHorizontal(
            vpMatrix = vpMatrix,
            centerWorldX = debugBoundsCenter.x,
            centerWorldY = debugBoundsCenter.y,
            centerWorldZ = debugBoundsCenter.z,
            radius = flyingRadius,
            segments = 48,
            color = color
        )
    }

    override fun toTemplate(): ActorTemplate =
        EasyFlyingEnemyTemplate(
            position = position,
            yaw = yawRad,
            flyingRadius = flyingRadius,
            antiClockwise = antiClockWise
        )
}

class FlyingEnemyActor(
    instance: ModelInstance,
    renderer: WireRenderer
) : EnemyActor(instance, renderer) {

    override val continuous = true
    override val randomInitialYaw = true

    // ---- Tuning knobs ----
    var flyingRadius = 20f // 50f
    val tangentialSpeed = 25f
    var antiClockWise = false

    override val qInterval = 400
    var timeCounter = 0f
    var direction = 1
    val debugBoundsCenter = Vec3()

    init {
        muzzleUpOffset = 1.5f

        initialPosition.set(instance.position)
        timeCounter += Random.nextFloat() * 4f

        spinRate = 4.0
        spinActiveMaxDurMs = 500
        spinStationaryMaxDurMs = 3000

    }

    override fun reset() {
        super.reset()
        timeCounter += Random.nextFloat() * 4f
        direction = if (antiClockWise) -1 else 1

    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

        // Angular speed chosen so linear speed stays constant:
        // v = ω * r  =>  ω = v / r
        val radius = flyingRadius.coerceAtLeast(0.001f)
        val angularSpeed = tangentialSpeed / radius

        // Advance angle (timeCounter is now angle in radians)
        timeCounter += angularSpeed * dt * direction

        val dx = radius * kotlin.math.cos(timeCounter)
        val dy = radius * kotlin.math.sin(timeCounter)

        setPositionAndUpdate(
            initialPosition.x + dx,
            initialPosition.y + dy,
            initialPosition.z,
            yawRad = spinUpdateGetYaw(dt, timeMs)
        )

        world.updateEnemyInGrid(this)
        super.update(dt, dtMs, timeMs)
    }

    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        super.draw(vpMatrix, timeMs)
        if (drawEditorBounds) {
            debugDrawCircularPath(vpMatrix, floatArrayOf(1f, 0f, 0f, 1f), false)
        }
    }

    fun debugDrawCircularPath(vpMatrix: FloatArray, color: FloatArray, fixed: Boolean) {

        if (fixed) debugBoundsCenter.set(initialPosition) else debugBoundsCenter.set(position)

        renderer.drawCircleWireSwapYZHorizontal(
            vpMatrix = vpMatrix,
            centerWorldX = debugBoundsCenter.x,
            centerWorldY = debugBoundsCenter.y,
            centerWorldZ = debugBoundsCenter.z,
            radius = flyingRadius,
            segments = 48,
            color = color
        )
    }
    override fun toTemplate(): ActorTemplate =
        FlyingEnemyTemplate(
            position = position,
            yaw = yawRad,
            flyingRadius = flyingRadius,
            antiClockwise = antiClockWise
        )
}

// ----------------------------
// Friendlies
// ----------------------------
abstract class FriendlyActor(
    override val instance: ModelInstance,
    override val renderer: WireRenderer
) : SingleMuzzleActor() {

    override val maxHitPoints = 3

    fun startFlashSignal(timeMs: Int) {
        renderer.startFlashing(timeMs, Random.nextInt(150))
    }

    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        renderer.draw(vpMatrix, timeMs)
    }
}

class GroundFriendlyActor(
    instance: ModelInstance,
    renderer: WireRenderer
) : FriendlyActor(instance, renderer) {

    override val continuous = false
    override val qInterval = 400
    override val randomInitialYaw = true

    init {
        spinRate = 0.5
        spinActiveMaxDurMs = 1000
        spinStationaryMaxDurMs = 4000
    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {
        // Example: patrol, follow player, repair station, etc.

        if (firingEnabled && world.activeEnemiesScratch.size != 0 && Random.nextInt(400) == 0) {

            val enemyTarget = world.pickRandomActiveActor(ActorType.ENEMY)
            if (enemyTarget != null) {
                firing = 1

                weaponYawRad = getPlanAngle(instance.position, enemyTarget.instance.position)
                weaponPitchRad = getElevationAngle(weaponYawRad,
                        (enemyTarget.instance.position.y - instance.position.y).toDouble(),
                        (enemyTarget.instance.position.z - instance.position.z).toDouble())

//            println("anglePtoDart=${df1.format(anglePtoDart)} anglePtoDart=${df1.format(angleEtoDart)}")
                laserBoltPool.activateNext(position, weaponYawRad, weaponPitchRad)
            }
            else {
                firing = 0
            }
        }
        else {
            firing = 0
        }

        instance.update()
        super.update(dt, dtMs, timeMs) // for logging
    }
    override fun toTemplate(): ActorTemplate =
        GroundFriendlyTemplate(
            position = position,
            yaw = yawRad
        )
}




