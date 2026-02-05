package com.jimjo.exodefender

import kotlin.math.sqrt
import kotlin.random.Random

class AdvFlyingEnemyActor(
    instance: ModelInstance,
    renderer: WireRenderer
) : EnemyActor(instance, renderer) {

    override val continuous = true
    override val qInterval = 400

    override var aggressionFactor = 1f
    override val skillFactor = 0.8f
    override val reactionTimeMs = 200
    override val targetOnlyShip = true

    // ---- Tuning knobs (default values)----

    var aabbHalfX = 70f
    var aabbHalfY = 70f
    var aabbHalfZ = 30f

    var idleSpeed = 10f
    var attackSpeed = 30f

    var segMinDur = 0.8f
    var segMaxDur = 2.5f

    var lockAltitude = false

    /** How long to blend direction when starting a new segment (seconds). */
    var turnBlendDurSec = 0.18f  // try 0.12–0.25

    // ---- Internal state ----

    private var segTimeLeft = 0f

    // Current direction (unit)
    private var dirX = 1f
    private var dirY = 0f
    private var dirZ = 0f

    // Turn blending state
    private var turning = false
    private var turnT = 0f // 0..1 progress
    private var turnFromX = 1f
    private var turnFromY = 0f
    private var turnFromZ = 0f
    private var turnToX = 1f
    private var turnToY = 0f
    private var turnToZ = 0f

    // Cached bounds
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var minZ = 0f
    private var maxZ = 0f


    private val debugBoundsCenter = Vec3()
    private val debugFlyingBoundsAabb = Aabb()

    init {
        muzzleUpOffset = 1.5f
        initialPosition.set(instance.position)

        segTimeLeft = 0f
        rebuildBounds()
        pickNewSegment()

        spinRate = 8.0
        spinActiveMaxDurMs = 250
        spinStationaryMaxDurMs = 2000
    }

    override fun reset() {
        super.reset()
        rebuildBounds()
        pickNewSegment()
    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {

        // Segment timer
        segTimeLeft -= dt
        if (segTimeLeft <= 0f) {
            pickNewSegment()
        }

        // If currently blending direction, advance blend and update dir*
        updateTurnBlend(dt)

        // ship speed
        val currentSpeed = if (shipInRange) attackSpeed else idleSpeed

        // Proposed next position
        var nx = position.x + dirX * currentSpeed * dt
        var ny = position.y + dirY * currentSpeed * dt
        var nz = if (lockAltitude) initialPosition.z else (position.z + dirZ * currentSpeed * dt)

        // Bounce/reflect off AABB edges
        var bounced = false

        if (nx < minX) {
            nx = minX
            dirX = -dirX
            bounced = true
        } else if (nx > maxX) {
            nx = maxX
            dirX = -dirX
            bounced = true
        }

        if (ny < minY) {
            ny = minY
            dirY = -dirY
            bounced = true
        } else if (ny > maxY) {
            ny = maxY
            dirY = -dirY
            bounced = true
        }

        if (!lockAltitude) {
            if (nz < minZ) {
                nz = minZ
                dirZ = -dirZ
                bounced = true
            } else if (nz > maxZ) {
                nz = maxZ
                dirZ = -dirZ
                bounced = true
            }
        }

        // If we bounced, encourage a new segment soon so it doesn't scrape along edges
        if (bounced) {
            segTimeLeft = minOf(segTimeLeft, 0.25f)
            // Also cancel any ongoing blend so reflection is immediate and readable
            turning = false
        }

        setPositionAndUpdate(
            nx, ny, nz,
            yawRad = spinUpdateGetYaw(dt, timeMs)
        )

        world.updateEnemyInGrid(this)
        super.update(dt, dtMs, timeMs)
    }

    private fun rebuildBounds() {
        minX = initialPosition.x - aabbHalfX
        maxX = initialPosition.x + aabbHalfX
        minY = initialPosition.y - aabbHalfY
        maxY = initialPosition.y + aabbHalfY

        if (lockAltitude) {
            minZ = initialPosition.z
            maxZ = initialPosition.z
        } else {
            minZ = initialPosition.z - aabbHalfZ
            maxZ = initialPosition.z + aabbHalfZ
        }
    }

    private fun pickNewSegment() {
        // Duration
        val minD = segMinDur.coerceAtLeast(0.05f)
        val maxD = segMaxDur.coerceAtLeast(minD)
        segTimeLeft = minD + Random.nextFloat() * (maxD - minD)

        // Choose a new target direction
        val (nx, ny, nz) = randomUnitDirection()

        // Bias inward if near edges (prevents instant bounce spam)
        var tx = nx
        var ty = ny
        var tz = nz

        val marginX = aabbHalfX * 0.10f
        val marginY = aabbHalfY * 0.10f

        if (position.x < minX + marginX && tx < 0f) tx = -tx
        if (position.x > maxX - marginX && tx > 0f) tx = -tx
        if (position.y < minY + marginY && ty < 0f) ty = -ty
        if (position.y > maxY - marginY && ty > 0f) ty = -ty

        // Start a smooth turn from current dir -> target dir
        startTurnBlend(tx, ty, if (lockAltitude) 0f else tz)
    }

    private fun startTurnBlend(toX: Float, toY: Float, toZ: Float) {
        // Current dir becomes "from"
        turnFromX = dirX
        turnFromY = dirY
        turnFromZ = dirZ

        // Normalize target (safety)
        val (nx, ny, nz) = normalize3(toX, toY, toZ)
        turnToX = nx
        turnToY = ny
        turnToZ = nz

        val dur = turnBlendDurSec
        if (dur <= 0.0001f) {
            // No blending
            dirX = turnToX
            dirY = turnToY
            dirZ = turnToZ
            turning = false
            return
        }

        turnT = 0f
        turning = true
    }

    private fun updateTurnBlend(dt: Float) {
        if (!turning) return

        val dur = turnBlendDurSec.coerceAtLeast(0.0001f)
        turnT += dt / dur
        if (turnT >= 1f) {
            dirX = turnToX
            dirY = turnToY
            dirZ = turnToZ
            turning = false
            return
        }

        // Smoothstep reduces “mechanical” feel vs linear blend
        val t = smoothstep01(turnT)

        // Lerp then renormalize (keeps constant-speed motion)
        val bx = turnFromX + (turnToX - turnFromX) * t
        val by = turnFromY + (turnToY - turnFromY) * t
        val bz = turnFromZ + (turnToZ - turnFromZ) * t
        val (nx, ny, nz) = normalize3(bx, by, bz)

        dirX = nx
        dirY = ny
        dirZ = nz
    }

    private fun randomUnitDirection(): Triple<Float, Float, Float> {
        return if (lockAltitude) {
            // Unit direction in XY
            var x: Float
            var y: Float
            while (true) {
                x = Random.nextFloat() * 2f - 1f
                y = Random.nextFloat() * 2f - 1f
                val l2 = x * x + y * y
                if (l2 > 1e-6f && l2 <= 1f) {
                    val inv = 1f / sqrt(l2)
                    return Triple(x * inv, y * inv, 0f)
                }
            }
            // unreachable
            Triple(1f, 0f, 0f)
        } else {
            // Unit direction in 3D
            var x: Float
            var y: Float
            var z: Float
            while (true) {
                x = Random.nextFloat() * 2f - 1f
                y = Random.nextFloat() * 2f - 1f
                z = Random.nextFloat() * 2f - 1f
                val l2 = x * x + y * y + z * z
                if (l2 > 1e-6f && l2 <= 1f) {
                    val inv = 1f / sqrt(l2)
                    return Triple(x * inv, y * inv, z * inv)
                }
            }
            // unreachable
            Triple(1f, 0f, 0f)
        }
    }

    private fun normalize3(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        val l2 = x * x + y * y + z * z
        if (l2 <= 1e-12f) return Triple(1f, 0f, 0f)
        val inv = 1f / sqrt(l2)
        return Triple(x * inv, y * inv, z * inv)
    }

    private fun smoothstep01(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        super.draw(vpMatrix, timeMs)
        if (drawEditorBounds) {
            debugDrawFlyingBounds(vpMatrix,  renderer.highlightLineColor, false)
        }
    }

    fun debugDrawFlyingBounds(vpMatrix: FloatArray, color: FloatArray, fixed: Boolean, zThicknessIfLocked: Float = 0.5f) {
        // If altitude is locked, give it a tiny thickness so the wire box is visible
        val hz = if (lockAltitude) zThicknessIfLocked else aabbHalfZ

        if (fixed) debugBoundsCenter.set(initialPosition) else debugBoundsCenter.set(position)

        debugFlyingBoundsAabb.min.set(
            debugBoundsCenter.x - aabbHalfX,
            debugBoundsCenter.y - aabbHalfY,
            debugBoundsCenter.z - hz
        )
        debugFlyingBoundsAabb.max.set(
            debugBoundsCenter.x + aabbHalfX,
            debugBoundsCenter.y + aabbHalfY,
            debugBoundsCenter.z + hz
        )

        // Your existing debug drawer
        renderer.drawAabbWire(vpMatrix, debugFlyingBoundsAabb, color)
    }
}
