package com.jimjo.exodefender

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class BuildingBlockActor(
    val blockIndex: Int,
    override val instance: ModelInstance,
    public override val renderer: WireRenderer,
    val explosionPool: ExplosionPool,
    val halfExtents: Vec3,
    val landingPadTop: Boolean,
    var civilianCluster: CivilianClusterVisual? = null,
    val structure: FriendlyStructureActor,
) : FriendlyActor(instance, renderer) {

    override val replayPolicy = ReplayPolicy.DRIVEN_BY_PARENT

    override val maxHitPoints: Int = 1 // unused; structure owns HP
    override val continuous = false
    override val qInterval = 400
    override val enableLogging = false

    var landingPadRadius: Float = 0f   // XY radius in world units

    // for editor
    var relocateLocalBasePos = Vec3()
    var relocateLocalYaw: Double = 0.0

    fun padKey(): PadKey =
        PadKey(structureId = structure.templateId, blockIndex = blockIndex)

    override fun onHit(timeMs: Int, enemyHit: Boolean, hitPosition: Vec3) {
//        if (!world.replayActive) {
            renderer.flashLinesOnce(timeMs)
            explosionPool.activateSmall(hitPosition)
            explosionFlash?.spawnWorldLarge(hitPosition)

//            // shotsHit should be counted once — do it here
//            if (enemyHit) log.flightLog.shotsHit++

            structure.applyDamageFromEnemy(timeMs, hitPosition)
//        }
    }

    override fun toTemplate() = null
}


class FriendlyStructureActor(
    override val instance: ModelInstance,
    override val renderer: WireRenderer,
    override val maxHitPoints: Int = 8000,
    val initialDestructSeconds: Int? = null,
    val explosionPool: ExplosionPool,
) : FriendlyActor(instance, renderer) {

    override val replayPolicy = ReplayPolicy.ANIMATED_MISSION_LOG

    override val continuous = false
    override val qInterval = 400

    var templateId: Int = -1
    val blocks = mutableListOf<BuildingBlockActor>()

    // --- destruction state ---
    var destructEnabled = false
        private set

    /** "Zero moment" expressed in ms since level start (timeMs). */
    var destructEndMs: Int = 0
        private set

    /** Cinematic beat after zero before we explode + become destroyed (and thus fail DEFEND). */
    val destructPostZeroBeatMs: Int = 500

    val destructTotalMs: Int
        get() = if (!destructEnabled) 0
        else ((initialDestructSeconds ?: 0) * 1000) + destructPostZeroBeatMs

    /** Remaining time until FAIL moment (includes post-zero cinematic beat). */
    val destructRemainingToFailMs: Int
        get() = if (!destructEnabled) 0
        else (destructEndMs + destructPostZeroBeatMs - lastLevelTimeMs).coerceAtLeast(0)

    /** Remaining time until zero (not including post-zero beat). */
    val destructRemainingMs: Int
        get() = if (!destructEnabled || destroyed) 0 else (destructEndMs - lastLevelTimeMs).coerceAtLeast(0)

    var destroyed: Boolean = false
        private set

    /** Keep last seen level time to support destructRemainingMs. */
    private var lastLevelTimeMs: Int = 0

    data class ScheduledBurst(val timeMs: Int, val pos: Vec3, val large: Boolean)
    private val scheduledBursts = ArrayDeque<ScheduledBurst>()

    private var destructionStartedMs: Int = 0
    private var hideAtMs: Int? = null

    private var didFoundationFlash = false
    private var lastFlashMs = Int.MIN_VALUE
    private val flashCooldownMs = 120
    private var emittedBurstCount = 0

    override fun getDestructionSound(audio: AudioPlayer): AudioPlayer.Soundfile = audio.explosion4

    val boundsAabb = Aabb(Vec3(), Vec3())

    private var lastReplayTimeMs: Int = Int.MIN_VALUE

//    init {
//        playSoundWhenDestroyed = true
//    }

    private fun destructFailMs(): Int = destructEndMs + destructPostZeroBeatMs

    override fun reset() {
        super.reset()
        updateBoundsAabb()
        resetDestructionState()
    }

    /**
     * Call at level start / restart.
     * Assumes level time starts at 0.
     *
     * If you later start levels with timeMs != 0, change destructEndMs to (timeMs + s*1000).
     */
    fun resetDestructionState() {
        val s = initialDestructSeconds

        destructEnabled = (s != null && s > 0)
        destroyed = false

        destructEndMs = if (destructEnabled) (s!! * 1000) else 0
        lastLevelTimeMs = 0

        // VFX state reset
        didFoundationFlash = false
        lastFlashMs = Int.MIN_VALUE
        emittedBurstCount = 0
        hideAtMs = null
        scheduledBursts.clear()
    }

    /**
     * Optional helper if you need to stop destruction logic on success.
     * Usually you don't need this if the level ends quickly.
     */
    fun cancelDestruction() {
        destructEnabled = false
        destroyed = false
        hideAtMs = null
        scheduledBursts.clear()
        didFoundationFlash = false
        lastFlashMs = Int.MIN_VALUE
        emittedBurstCount = 0
    }

    fun applyDamageFromEnemy(timeMs: Int, hitPosition: Vec3) {

        if (!world.replayActive && hitPoints > 0) {
            renderer.flashLinesOnce(timeMs)
            hitPoints--

            if (hitPoints == 0) {
                active = false
                world.removeActorFromWorld(this)
                logEvent(timeMs, hit = 1, destroyed = 1)
//                parent.notifyActorDestroyed(playSoundWhenDestroyed, true)
                parent.notifyActorDestroyed(this)

                explosion?.activateLarge(hitPosition)
                explosionFlash?.spawnWorldLarge(hitPosition)

                for (b in blocks) {
                    b.active = false
                    world.removeActorFromWorld(b)
                }
            } else {
                logEvent(timeMs, hit = 1)
                explosion?.activateSmall(hitPosition)
                explosionFlash?.spawnWorldSmall(hitPosition)
            }
        }
    }

    override fun select() {
        super.select()
        updateBoundsAabb()
    }

    fun getCiviliansRemaining(): Int =
        blocks.sumOf { it.civilianCluster?.count ?: 0 }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {
        lastLevelTimeMs = timeMs

        updateDestruction(timeMs)
        updateDestructionVfx(timeMs)

        hideAtMs?.let {
            if (timeMs >= it) {
                world.removeActorFromWorld(this)
                hideAtMs = null
            }
        }
    }

    override fun replayUpdateMinimum(dt: Float, timeMs: Int) {
        lastLevelTimeMs = timeMs

        // If this level/mission doesn't have a destruct timeline, keep current visuals.
        val ds = world.flightLog?.missionLog?.getDestructStart() ?: run {
            lastReplayTimeMs = timeMs
            return
        }

        // Live structure uses destructEndMs as "zero moment" (ms since level start).
        // MissionLog durationMs is time-to-zero (not including post-zero beat).
        val zeroMs = ds.timeMs + ds.durationMs
        val failMs = zeroMs + destructPostZeroBeatMs
        val hideMs = failMs + 350  // match your onDestroyed() hide beat

        // Detect time direction change (scrub backwards).
        val wentBackwards = (lastReplayTimeMs != Int.MIN_VALUE && timeMs < lastReplayTimeMs)

        // Desired states at this time
        val shouldBeDestroyed = timeMs >= failMs
        val shouldBeActive = timeMs < hideMs

        if (wentBackwards) {
            // If we scrubbed back before destruction, restore "alive" visuals so it can be destroyed again.
            if (!shouldBeDestroyed) {
                destroyed = false
                hideAtMs = null

                // reset VFX state so crossing forward replays the sequence
                scheduledBursts.clear()
                explosionPool.cancelAll()
                didFoundationFlash = false
                lastFlashMs = Int.MIN_VALUE
                emittedBurstCount = 0
            }
        }

        // Rising-edge trigger: crossed fail moment going forward
        val crossedFailForward =
            (lastReplayTimeMs == Int.MIN_VALUE || lastReplayTimeMs < failMs) && timeMs >= failMs

        if (crossedFailForward) {
            destroyed = true
            updateBoundsAabb()

            // schedule bursts from the canonical fail time (not "now") so jumping ahead still looks right
            startDestructionVfx(failMs, boundsAabb)
            hideAtMs = hideMs

//            if (playSoundWhenDestroyed) {
//                parent.notifyActorDestroyed(true, false)
//            }
            parent.notifyActorDestroyed(this)
        }

        // Apply visibility without removing from world
        active = shouldBeActive
        for (b in blocks) b.active = shouldBeActive

        // Emit any bursts that are due at current replay time
        if (destroyed) {
            updateDestructionVfx(timeMs)
        }

        lastReplayTimeMs = timeMs
    }

    /**
     * Single-trigger model:
     * - At (destructEndMs + destructPostZeroBeatMs): mark destroyed and start VFX immediately.
     * - This allows DEFEND to fail at the same instant the explosions start.
     */
    private fun updateDestruction(timeMs: Int) {
        if (!destructEnabled || destroyed) return

        if (timeMs >= destructFailMs()) {
            destroyed = true
            onDestroyed(timeMs)
        }
    }

    private fun onDestroyed(timeMs: Int) {
        updateBoundsAabb()
        startDestructionVfx(timeMs, boundsAabb)

        // Let the initial boom read before disappearing.
        hideAtMs = timeMs + 350  // 350–600 feels good

//        parent.notifyActorDestroyed(playSoundWhenDestroyed, false)
        parent.notifyActorDestroyed(this)
    }

    private fun updateDestructionVfx(timeMs: Int) {
        while (scheduledBursts.isNotEmpty() && timeMs >= scheduledBursts.first().timeMs) {
            val b = scheduledBursts.removeFirst()

            // ---- explosions ----
            explosionPool.activateLarge(b.pos)

            // ---- flashes ----
            val flash = this.explosionFlash
            if (flash != null) {
                if (!didFoundationFlash) {
                    didFoundationFlash = true
                    lastFlashMs = timeMs
                    flash.spawnWorldExtraLarge(b.pos)
                    world.renderer?.screenShake?.start(4f, 1f)
                } else {
                    val shouldFlashThisBurst =
                        (emittedBurstCount % 3 == 0) && (timeMs - lastFlashMs >= flashCooldownMs)

                    if (shouldFlashThisBurst) {
                        lastFlashMs = timeMs
                        flash.spawnWorldExtraLarge(b.pos)
                    }
                }
            }

            emittedBurstCount++
        }
    }

    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        if (drawEditorBounds) {
            renderer.drawAabbWire(vpMatrix, boundsAabb, renderer.highlightLineColor)
        }
    }

    override fun toTemplate() = null

    fun updateBoundsAabb(): Boolean {
        boundsAabb.min.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        boundsAabb.max.set(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

        var any = false
        for (b in blocks) {
            if (!b.active) continue
            any = true

            val c = b.instance.position
            val h = b.halfExtents

            val x0 = c.x - h.x
            val y0 = c.y - h.y
            val z0 = c.z - h.z
            val x1 = c.x + h.x
            val y1 = c.y + h.y
            val z1 = c.z + h.z

            if (x0 < boundsAabb.min.x) boundsAabb.min.x = x0
            if (y0 < boundsAabb.min.y) boundsAabb.min.y = y0
            if (z0 < boundsAabb.min.z) boundsAabb.min.z = z0

            if (x1 > boundsAabb.max.x) boundsAabb.max.x = x1
            if (y1 > boundsAabb.max.y) boundsAabb.max.y = y1
            if (z1 > boundsAabb.max.z) boundsAabb.max.z = z1
        }

        if (!any) return false

        val pad = 2f
        boundsAabb.min.x -= pad; boundsAabb.min.y -= pad; boundsAabb.min.z -= pad
        boundsAabb.max.x += pad; boundsAabb.max.y += pad; boundsAabb.max.z += pad
        return true
    }

    fun startDestructionVfx(timeMs: Int, structureAabb: Aabb) {
        destructionStartedMs = timeMs
        scheduledBursts.clear()

        val center = structureAabb.center()

        val pts = sampleExplosionPointsFromBlocks(blocks, count = 16)
        val sorted = pts.sortedBy { it.z }  // bottom -> top

        val initialDelayMs = 0
        val stepMs = 70
        val jitterMs = 25

        for ((i, p) in sorted.withIndex()) {
            val t = timeMs + initialDelayMs + i * stepMs + kotlin.random.Random.nextInt(-jitterMs, jitterMs + 1)
            scheduledBursts.add(ScheduledBurst(t, p, large = true))
        }

        scheduledBursts.addFirst(ScheduledBurst(timeMs, Vec3(center.x, center.y, center.z), large = true))
    }

    fun sampleExplosionPointsFromBlocks(
        blocks: List<BuildingBlockActor>,
        count: Int,
        rng: Random = Random.Default,
        topBias: Float = 0.7f,
        jitterFracXY: Float = 0.08f,
        jitterFracZ: Float = 0.05f
    ): List<Vec3> {
        if (blocks.isEmpty() || count <= 0) return emptyList()

        fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
        fun rand(lo: Float, hi: Float) = lo + rng.nextFloat() * (hi - lo)

        val out = ArrayList<Vec3>(count)

        repeat(count) {
            val b = blocks[rng.nextInt(blocks.size)]
            val aabb = b.instance.worldAabb

            val w = max(0.001f, aabb.width())
            val d = max(0.001f, aabb.depth())
            val h = max(0.001f, aabb.height())

            val x = rand(aabb.min.x, aabb.max.x)
            val y = rand(aabb.min.y, aabb.max.y)

            val t = rng.nextFloat()
            val biasedT = t * t * (1f - topBias) + t * topBias
            val z = aabb.min.z + biasedT * h

            val jx = w * jitterFracXY
            val jy = d * jitterFracXY
            val jz = h * jitterFracZ

            out.add(
                Vec3(
                    clamp(x + rand(-jx, jx), aabb.min.x, aabb.max.x),
                    clamp(y + rand(-jy, jy), aabb.min.y, aabb.max.y),
                    clamp(z + rand(-jz, jz), aabb.min.z, aabb.max.z),
                )
            )
        }

        return out
    }
}

