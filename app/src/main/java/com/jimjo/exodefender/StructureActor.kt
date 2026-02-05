package com.jimjo.exodefender

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class BuildingBlockActor(
    val blockIndex: Int,
    override val instance: ModelInstance,
    public override val renderer: WireRenderer,
    val halfExtents: Vec3,
    val landingPadTop: Boolean,
    var civilianCluster: CivilianClusterVisual? = null,
    val structure: FriendlyStructureActor,
) : FriendlyActor(instance, renderer) {

    override val maxHitPoints: Int = 1 // unused; structure owns HP
    override val continuous = false
    override val qInterval = 400
    override val enableLogging = false

    var landingPadRadius: Float = 0f   // XY radius in world units

    // for editor
    var relocateLocalBasePos = Vec3()
    var relocateLocalYaw: Double = 0.0

    override fun onHit(timeMs: Int, enemyHit: Boolean, hitPosition: Vec3) {
        if (!log.flightLog.replayActive) {
            renderer.flashLinesOnce(timeMs)
            explosion?.activateSmall(hitPosition)
            explosionFlash?.spawnWorldSmall(hitPosition)

//            // shotsHit should be counted once — do it here
//            if (enemyHit) log.flightLog.shotsHit++

            structure.applyDamageFromEnemy(timeMs, hitPosition)
        }
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

    override val continuous = false
    override val qInterval = 400

    var templateId: Int = -1
    val blocks = mutableListOf<BuildingBlockActor>()
    var destructEnabled = false
    var destructEndMs = 0
    var destroyed = false
        private set
    val destructRemainingMs: Int
        get() = if (!destructEnabled || destroyed) 0 else (destructEndMs - currentTimeMs).coerceAtLeast(0)
    private var currentTimeMs: Int = 0

    data class ScheduledBurst(val timeMs: Int, val pos: Vec3, val large: Boolean)

    private val scheduledBursts = ArrayDeque<ScheduledBurst>()
    private var destructionStartedMs: Int = 0

    val editorBoundsAabb = Aabb(Vec3(), Vec3())

    override fun reset() {
        super.reset()

        val s = initialDestructSeconds
        destructEnabled = (s != null && s > 0)
        destroyed = false
        destructEndMs = if (destructEnabled) s!! * 1000 else 0
        currentTimeMs = 0
    }

    fun applyDamageFromEnemy(timeMs: Int, hitPosition: Vec3) {
        if (!log.flightLog.replayActive && hitPoints > 0) {
            renderer.flashLinesOnce(timeMs)
            hitPoints--

            if (hitPoints == 0) {
                active = false
                world.removeActorFromWorld(this)
                logEvent(timeMs, hit = 1, destroyed = 1)
                parent.notifyActorDestroyed(playSoundWhenDestroyed, true)

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

    fun updateEditorBoundsAabb(): Boolean {
        editorBoundsAabb.min.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        editorBoundsAabb.max.set(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

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

            if (x0 < editorBoundsAabb.min.x) editorBoundsAabb.min.x = x0
            if (y0 < editorBoundsAabb.min.y) editorBoundsAabb.min.y = y0
            if (z0 < editorBoundsAabb.min.z) editorBoundsAabb.min.z = z0

            if (x1 > editorBoundsAabb.max.x) editorBoundsAabb.max.x = x1
            if (y1 > editorBoundsAabb.max.y) editorBoundsAabb.max.y = y1
            if (z1 > editorBoundsAabb.max.z) editorBoundsAabb.max.z = z1
        }

        if (!any) return false

        val pad = 2f
        editorBoundsAabb.min.x -= pad; editorBoundsAabb.min.y -= pad; editorBoundsAabb.min.z -= pad
        editorBoundsAabb.max.x += pad; editorBoundsAabb.max.y += pad; editorBoundsAabb.max.z += pad
        return true
    }



    override fun select() {
        super.select()
        updateEditorBoundsAabb()
    }

    fun updateDestruction(timeMs: Int) {
        if (!destructEnabled || destroyed) return

        if (timeMs >= destructEndMs) {
            destroyed = true
            onDestroyed(timeMs)
        }
    }

    fun updateDestructionVfx(timeMs: Int) {
        while (scheduledBursts.isNotEmpty() && timeMs >= scheduledBursts.first().timeMs) {
            val b = scheduledBursts.removeFirst()
            if (b.large) explosionPool.activateLarge(b.pos) else explosionPool.activateSmall(b.pos)
        }
    }

    fun onDestroyed(timeMs: Int) {
        println("Structure Destroyed!")
        updateEditorBoundsAabb()
        startDestructionVfx(timeMs, editorBoundsAabb)
    }

    override fun update(dt: Float, dtMs: Int, timeMs: Int) {
        updateDestruction(timeMs)

        // If you set destroyed=true and call onDestroyed(), ensure you also start VFX there.
        updateDestructionVfx(timeMs)
    }

    override fun draw(vpMatrix: FloatArray, timeMs: Int) {
        // Do NOT call super.draw() if structure has no visible geometry
        // (If it does have geometry later, you can add it back.)

        if (drawEditorBounds) {
            renderer.drawAabbWire(
                vpMatrix,
                editorBoundsAabb,
                renderer.highlightLineColor
            )
        }
    }

    override fun toTemplate() = null

    fun startDestructionVfx(timeMs: Int, structureAabb: Aabb) {
        destructionStartedMs = timeMs
        scheduledBursts.clear()

        val center = structureAabb.center()

        // Your existing block-based sampler
        val pts = sampleExplosionPointsFromBlocks(blocks, count = 16)

        // Sort center-out
        val sorted = pts.sortedBy { p ->
            val dx = p.x - center.x
            val dy = p.y - center.y
            val dz = p.z - center.z
            dx*dx + dy*dy + dz*dz
        }

        // Timing knobs
        val initialDelayMs = 0          // 0..100 if you want a tiny pause
        val stepMs = 70                 // 50..120 feels good
        val jitterMs = 25               // randomness stops it looking “metronomic”

        for ((i, p) in sorted.withIndex()) {
            val t = timeMs + initialDelayMs + i * stepMs + kotlin.random.Random.nextInt(-jitterMs, jitterMs + 1)
            scheduledBursts.add(ScheduledBurst(t, p, large = true))
        }

        // Optional: add 1–2 anchor blasts at center immediately (reads “major event”)
        scheduledBursts.addFirst(ScheduledBurst(timeMs, Vec3(center.x, center.y, center.z), large = true))
    }
    fun sampleExplosionPointsFromBlocks(
        blocks: List<BuildingBlockActor>,
        count: Int,
        rng: Random = Random.Default,
        topBias: Float = 0.7f,          // 0..1, higher = more explosions near top faces
        jitterFracXY: Float = 0.08f,    // jitter relative to block size
        jitterFracZ: Float = 0.05f
    ): List<Vec3> {
        if (blocks.isEmpty() || count <= 0) return emptyList()

        fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
        fun rand(lo: Float, hi: Float) = lo + rng.nextFloat() * (hi - lo)

        val out = ArrayList<Vec3>(count)

        repeat(count) {
            val b = blocks[rng.nextInt(blocks.size)]
            val aabb = b.instance.worldAabb // <-- adapt to your real property/method

            val w = max(0.001f, aabb.width())
            val d = max(0.001f, aabb.depth())
            val h = max(0.001f, aabb.height())

            // Pick a point inside the block AABB, but bias Z toward the top.
            val x = rand(aabb.min.x, aabb.max.x)
            val y = rand(aabb.min.y, aabb.max.y)

            val t = rng.nextFloat()
            val biasedT = t * t * (1f - topBias) + t * topBias
            val z = aabb.min.z + biasedT * h

            // Jitter within a fraction of the block’s size so it doesn’t look “grid aligned”
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

