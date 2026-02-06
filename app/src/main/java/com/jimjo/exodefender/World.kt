package com.jimjo.exodefender

import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.contains
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

const val MAP_GRID_SIZE = 500
const val MAP_GRID_SPACING = 10f
const val MAX_ALTITIUDE = 500f

data class CivilianTotals(
    val remaining: Int,
    val initial: Int,
    val saved: Int,
)

class World(val mapId: Int) {

//    val filename = "map_" + mapId.toString().padStart(3, '0') + ".dat"

    var renderer: GameGLRenderer? = null
    var civilianWireBatch: WireBatchRenderer? = null
    var gpuModelCache: GpuModelCache? = null
    var activeLevel: Level? = null

    val mapBounds = RectF(0f, 0f, MAP_GRID_SIZE * MAP_GRID_SPACING, MAP_GRID_SIZE * MAP_GRID_SPACING)
    val safeBounds = RectF(0f, 0f, (MAP_GRID_SIZE - 1) * MAP_GRID_SPACING, (MAP_GRID_SIZE - 1) * MAP_GRID_SPACING)

    val battleSpaceBounds = BattlespaceBounds(
        maxX = mapBounds.width(),
        maxY = mapBounds.height(),
        zCeil = MAX_ALTITIUDE
    )
    val cellSizeMeters: Float = 20f // tune: ~actor size or a bit larger
    private val enemyGrid = SpatialGrid2D(cellSizeMeters)
    private val friendlyGrid = SpatialGrid2D(cellSizeMeters)

    // Reusable list to avoid per-frame allocations during queries
//    private val queryScratch = ArrayList<AabbOld>(128)

    var shipStartDirection = 0.0
    val shipStartPosition = Vec3()

    var enemyThreatSum = 0f

    val proximity = ProximityTargeting(
        zWeight = 0.25f,
        retargetMs = 250
    )



    val actors = mutableListOf<Actor>()
    val friendlyActors = mutableListOf<FriendlyActor>()
    val enemyActors = mutableListOf<EnemyActor>()
    val activeFriendliesScratch = ArrayList<FriendlyActor>()
    val activeEnemiesScratch = ArrayList<EnemyActor>()

    val visuals = mutableListOf<VisualObject>()

    val heightMap: Array<Array<Float>> = Array(MAP_GRID_SIZE) { y -> Array(MAP_GRID_SIZE) { x -> 0f } }


    fun loadLevel(level: Level) {

        // create actors

        activeLevel = level

        shipStartPosition.set(level.shipPosition)
        shipStartDirection = level.shipDirection

        actors.clear()

        updateActorCounts()

    }

    fun reset() {
        friendlyActors.clear()
        enemyActors.clear()
        enemyThreatSum = 0f

        for ((i, actor) in actors.withIndex()) {
            actor.reset()
            actor.actorIndex = i

            // DEBUG:  Uncomment to disable all actor firing
            actor.firingEnabled = false

            when (actor) {
                is FriendlyActor -> friendlyActors.add(actor)
                is EnemyActor -> enemyActors.add(actor)
            }
        }

        resetVisualsForLevelStart()

        updateActorCounts()
        rebuildEnemyGrid()
        rebuildFriendlyGrid()
        proximity.build(actors)
    }

    fun resetVisualsForLevelStart() {
        for (v in visuals) {
            when (v) {
                is CivilianClusterVisual -> v.resetToInitial()
                // later: other visual types could be reset here too
            }
        }
    }

    fun updateActorCounts() {
        activeEnemiesScratch.clear()
        for (e in enemyActors) {
            if (e.active) {
                activeEnemiesScratch.add(e)
            }
        }

        activeFriendliesScratch.clear()
        for (f in friendlyActors) {
            if (f.active) {
                activeFriendliesScratch.add(f)
            }
        }
    }

    fun civilianTotals(): CivilianTotals {
        var remaining = 0
        var initial = 0
        var saved = 0

        for (v in visuals) {
            if (v is CivilianClusterVisual) {
                if (v.initialCount > 0) {
                    // Original civilian sources
                    remaining += v.count
                    initial += v.initialCount
                } else {
                    // Structures that started with zero civilians:
                    // anything present here must have been saved
                    saved += v.count
                }
            }
        }

        return CivilianTotals(
            remaining = remaining,
            initial = initial,
            saved = saved
        )
    }

    fun getEnemyRatio() = activeEnemiesScratch.size / enemyActors.size.toFloat()
    fun getFriendlyRatio() = activeFriendliesScratch.size / friendlyActors.size.toFloat()

    private fun <T> pickRandomFromActive(list: List<T>): T? {
        if (list.isEmpty()) return null
        val i = (Math.random() * list.size).toInt()
        return list[i]
    }

    fun pickRandomActiveActor(type: ActorType): Actor? {
        return when (type) {
            ActorType.FRIENDLY -> pickRandomFromActive(activeFriendliesScratch)
            ActorType.ENEMY    -> pickRandomFromActive(activeEnemiesScratch)
            else -> pickRandomFromActive(activeEnemiesScratch)
        }
    }

    fun spawnGroundFriendly(x: Float, y: Float, z: Float): GroundFriendlyActor? {
        if (renderer != null && gpuModelCache != null) {
            val model = gpuModelCache!!.getModel("models/ground_friendly.obj")
            val instance = ModelInstance(model, isStatic = true).apply {
                setPosition(x, y, z)
                update()
            }

            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0f, 0.9f, 0f, 1f)
                signalLineColor = floatArrayOf(0.9f, 0.9f, 0.9f, 1f)
            }

            val actor = GroundFriendlyActor(instance, renderer)
            actor.initialPosition.set(instance.position)

            actor.resetPosition()
            actors.add(actor)
            friendlyActors.add(actor)
            return actor
        }
        return null
    }

    fun spawnGroundTarget(x: Float, y: Float, z: Float, yaw: Double): GroundTrainingTargetActor? {
        if (renderer != null && gpuModelCache != null) {
            val model = gpuModelCache!!.getModel("models/ground_target.obj")
            val instance = ModelInstance(model, isStatic = true).apply {
                setPosition(x, y, z)
                update()
            }

            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1.0f)
            }
            val actor = GroundTrainingTargetActor(instance, renderer)
            actor.initialPosition.set(instance.position)
            actor.initialYawRad = yaw

            actor.resetPosition()
            actors.add(actor)
            enemyActors.add(actor)
            return actor
        }
        return null
    }

    fun spawnGroundEnemy(x: Float, y: Float, z: Float): GroundEnemyActor? {
        if (renderer != null && gpuModelCache != null) {
            val model = gpuModelCache!!.getModel("models/ground_enemy.obj")
            val instance = ModelInstance(model, isStatic = true).apply {
                setPosition(x, y, z)
                update()
            }

            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1.0f)
            }

            val actor = GroundEnemyActor(instance, renderer)
            actor.initialPosition.set(instance.position)

            actor.resetPosition()
            actors.add(actor)
            enemyActors.add(actor)
            return actor
        }
        return null
    }

    fun spawnEasyGroundEnemy(x: Float, y: Float, z: Float): EasyGroundEnemyActor? {
        if (renderer != null && gpuModelCache != null) {
            val model = gpuModelCache!!.getModel("models/easy_ground_enemy.obj")
            val instance = ModelInstance(model, isStatic = true).apply {
                setPosition(x, y, z)
                update()
            }

            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1.0f)
            }

            val actor = EasyGroundEnemyActor(instance, renderer)
            actor.initialPosition.set(instance.position)

            actor.resetPosition()
            actors.add(actor)
            enemyActors.add(actor)
            return actor
        }
        return null
    }

    fun spawnEasyFlyingEnemy(x: Float, y: Float, z: Float, flyingRadius: Float, antiClockwise: Boolean): EasyFlyingEnemyActor? {
        if (renderer != null && gpuModelCache != null) {
            val model = gpuModelCache!!.getModel("models/easy_flying_enemy.obj")
            val instance = ModelInstance(model, isStatic = false).apply {
                setPosition(x, y, z)
                update()
            }

            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1.0f)
            }

            val actor = EasyFlyingEnemyActor(instance, renderer)
            actor.initialPosition.set(instance.position)
            actor.flyingRadius = flyingRadius
            actor.antiClockWise = antiClockwise

            actor.resetPosition()
            actors.add(actor)
            enemyActors.add(actor)
            return actor
        }
        return null
    }
    fun spawnFlyingEnemy(x: Float, y: Float, z: Float, flyingRadius: Float, antiClockwise: Boolean): FlyingEnemyActor? {
        if (renderer != null && gpuModelCache != null) {
            val model = gpuModelCache!!.getModel("models/flying_enemy.obj")
            val instance = ModelInstance(model, isStatic = false).apply {
                setPosition(x, y, z)
                update()
            }

            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1.0f)
            }

            val actor = FlyingEnemyActor(instance, renderer)
            actor.initialPosition.set(instance.position)
            actor.flyingRadius = flyingRadius
            actor.antiClockWise = antiClockwise

            actor.resetPosition()
            actors.add(actor)
            enemyActors.add(actor)
            return actor
        }
        return null
    }
    fun spawnAdvFlyingEnemy(x: Float, y: Float, z: Float, aabbHalfX: Float, aabbHalfY: Float, aabbHalfZ: Float): AdvFlyingEnemyActor? {
        if (renderer != null && gpuModelCache != null) {
            val model = gpuModelCache!!.getModel("models/adv_flying_enemy.obj")
            val instance = ModelInstance(model, isStatic = false).apply {
                setPosition(x, y, z)
                update()
            }

            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1.0f)
            }

            val actor = AdvFlyingEnemyActor(instance, renderer)
            actor.initialPosition.set(instance.position)
            actor.aabbHalfX= aabbHalfX
            actor.aabbHalfY = aabbHalfY
            actor.aabbHalfZ = aabbHalfZ

            actor.resetPosition()
            actors.add(actor)
            enemyActors.add(actor)
            return actor
        }
        return null
    }

    fun spawnFriendlyStructure(t: FriendlyStructureTemplate): FriendlyStructureActor? {
        if (renderer == null || gpuModelCache == null) return null

        // --- Models (swap these paths to real primitive meshes when you add them) ---
        fun modelForShape(shape: BlockShape): Model {
            // Temporary: reuse an existing model so you can test collision/landing immediately.
            // Replace with: "models/block_box.obj", "models/block_pyramid.obj", etc later.
            return when (shape) {
                BlockShape.BOX -> gpuModelCache!!.getModel("models/block_box.obj")
                BlockShape.PYRAMID -> gpuModelCache!!.getModel("models/block_box.obj")
                BlockShape.CYLINDER -> gpuModelCache!!.getModel("models/block_cylinder.obj")
            }
        }

        val tmp = Vec3()

        // --- 1) Structure Actor (logic + HP; not used for collision) ---
        val structureModel = modelForShape(BlockShape.BOX)
        val structureInstance = ModelInstance(structureModel, isStatic = true).apply {
            setPosition(t.position.x, t.position.y, t.position.z)
            update()
        }

        val structureRenderer = WireRenderer(
            structureInstance,
            renderer!!.mProgram,
            renderer!!.glowProgram
        ).apply {
            // Keep it effectively invisible; blocks are the visible geometry.
            // If alpha isn't respected, you can still leave it un-added to the main draw list later.
            normalFillColor = floatArrayOf(0f, 0f, 0f, 0f)
            normalLineColor = floatArrayOf(0f, 0f, 0f, 0f)
            signalLineColor = floatArrayOf(0f, 0f, 0f, 0f)
        }

        val structure = FriendlyStructureActor(
            instance = structureInstance,
            renderer = structureRenderer,
            maxHitPoints = t.hitpoints.toInt(),
            initialDestructSeconds = t.destructSeconds,
            explosionPool = renderer!!.structureExplosionPool,
        ).apply {
            templateId = t.id
            hitPoints = t.hitpoints.toInt()
            initialPosition.set(structureInstance.position)
            initialYawRad = t.yaw
        }

        // Register structure (consistent with other actor types)
        structure.resetPosition()
        actors.add(structure)
        friendlyActors.add(structure)
        updateFriendlyInGrid(structure)

        // --- 2) Blocks (collision + visuals; forward hits to structure) ---
        for ((blockIndex, b) in t.blocks.withIndex()) {
            val model = modelForShape(b.shape)

            val halfExtents = Vec3(
                b.dimensions.x * 0.5f,
                b.dimensions.y * 0.5f,
                b.dimensions.z * 0.5f
            )

            rotateYaw(b.localBasePos, -t.yaw, tmp)

            val wx = t.position.x + tmp.x
            val wy = t.position.y + tmp.y
            val wz = t.position.z + tmp.z + halfExtents.z   // bottom → center

            val instance = ModelInstance(model, isStatic = true).apply {
                setPosition(wx, wy, wz)
                setSize(
                    widthX  = b.dimensions.x,
                    depthY  = b.dimensions.y,
                    heightZ = b.dimensions.z
                )
                update()
            }


            var landingPadRadius = 0f
            val renderer = WireRenderer(instance, renderer!!.mProgram, renderer!!.glowProgram).apply {
                normalFillColor = floatArrayOf(0f, 0f, 0f, 1f)
                normalLineColor = floatArrayOf(0.2f, 0.8f, 1.0f, 1f)
                signalLineColor = floatArrayOf(0.9f, 0.9f, 0.9f, 1f)

                // landing pad overlay
                if (b.shape == BlockShape.CYLINDER && b.landingPadTop) {
                    landingPadRadius = halfExtents.x      // per your choice: halfXExtent
                    landingPadOverlay = LandingPadOverlay(
                        enabled = true,
                        radius = landingPadRadius,
                        halfHeight = halfExtents.z,
                        color = floatArrayOf(0.95f, 0.95f, 0.95f, 1f),
                    )
                } else if (b.shape == BlockShape.BOX && b.landingPadTop) {
                    landingPadOverlay = LandingPadOverlay(
                        enabled = true,
                        isBox = true,

                        // Pre-yaw (local) half extents
                        halfX = halfExtents.x,
                        halfY = halfExtents.y,
                        halfHeight = halfExtents.z,

                        // Optional: keep radius set too (harmless, might help if you reuse it elsewhere)
                        radius = halfExtents.x,

                        color = floatArrayOf(0.95f, 0.95f, 0.95f, 1f),

                        // Optional tuning (recommended so inset never eats the whole pad)
                        inset = minOf(1.5f, 0.18f * minOf(halfExtents.x, halfExtents.y))
                    )
                } else {
                    landingPadOverlay = null
                }

            }

            val blockActor = BuildingBlockActor(
                blockIndex = blockIndex,
                instance = instance,
                renderer = renderer,
                halfExtents = halfExtents,
                landingPadTop = b.landingPadTop,
                structure = structure,
            ).apply {
                this.landingPadRadius = landingPadRadius
                initialPosition.set(instance.position)
                initialYawRad = t.yaw + b.localYaw

                val modelCache = requireNotNull(gpuModelCache) {
                    "gpuModelCache must be initialized before spawning civilian clusters"
                }
                val wireBatch = requireNotNull(civilianWireBatch) {
                    "civilianWireBatch must be initialized before spawning civilian clusters"
                }

                // waitingAreaLocal.z is authored relative to block z-base,
                // so convert to instance-local space (pivot at center) here.

                b.civilianSpec?.let { spec ->

                    val SAFE_PAD_CAPACITY = 50
                    val maxSlots = if (spec.isSafePad()) SAFE_PAD_CAPACITY else spec.initialCount
                    val waitingAreaLocalAdjusted = Vec3(spec.waitingAreaLocal.x, spec.waitingAreaLocal.y, spec.waitingAreaLocal.z - this.halfExtents.z)

                    val cluster = CivilianClusterVisual(
                        padInstance = instance,
                        civilianModel = modelCache.getModel("models/person.obj"),
                        waitingAreaLocal = waitingAreaLocalAdjusted,
                        initialCount = spec.initialCount,
                        maxSlots = maxSlots,
                        structureTemplateId = structure.templateId,
                        seed = structure.templateId * 1337,
                        wire = wireBatch
                    )
                    civilianCluster = cluster
                    visuals.add(cluster)
                }

            }

            blockActor.resetPosition()
            actors.add(blockActor)
            friendlyActors.add(blockActor)
            updateFriendlyInGrid(blockActor)

            structure.blocks.add(blockActor)
        }
        return structure
    }

    fun removeCivilianVisualsForStructure(structureTemplateId: Int): Int {
        var removed = 0

        val it = visuals.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (v is CivilianClusterVisual && v.structureTemplateId == structureTemplateId) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    fun removeFriendlyActorFromWorld(friendlyActor: FriendlyActor) {
        friendlyActor.active = false
        updateFriendlyInGrid(friendlyActor)
    }

    fun removeEnemyActorFromWorld(enemyActor: EnemyActor) {
        enemyActor.active = false
        updateEnemyInGrid(enemyActor)
    }
    fun removeActorFromWorld(actor: Actor) {

        if (actor is BuildingBlockActor) {
            actor.civilianCluster?.let { cluster ->
                cluster.active = false
//                visuals.remove(cluster)
//                actor.civilianCluster = null
            }
        }

        if (actor is FriendlyStructureActor) {
            for (b in actor.blocks) {
                removeActorFromWorld(b)
            }
        }

        if (actor is FriendlyActor) {
            removeFriendlyActorFromWorld(actor)
        }
        if (actor is EnemyActor) {
            removeEnemyActorFromWorld(actor)
        }
    }

    fun getNearestActor(location: Vec3): Actor? {
        var minDistanceAwaySquared = Float.POSITIVE_INFINITY
        var nearestActor: Actor? = null
        for (actor in actors) {
            if (actor.active) {
                val distanceAwaySquared = location.distance2(actor.position)
                if (distanceAwaySquared < minDistanceAwaySquared) {
                    minDistanceAwaySquared = distanceAwaySquared
                    nearestActor = actor
                }
            }
        }
        return nearestActor
    }
    fun findFriendlyStructureActor(id: Int): FriendlyStructureActor? =
        friendlyActors.firstOrNull { it is FriendlyStructureActor && it.templateId == id && it.active} as? FriendlyStructureActor

    fun findIntersectingActor(currentActor: Actor, aabb: Aabb): Actor? {
        for (actor in actors) {
            if (actor.active && actor !== currentActor && actor.instance.worldAabb.intersects(aabb)) {
                return actor
            }
        }
        return null
    }

    fun unselectAllActors() {
        for (actor in actors) {
            actor.unselect()
        }
        if (renderer != null) renderer!!.ship.unselect()
    }

    /** Call after spawning/removing enemies or when an enemy moves significantly. */
    fun rebuildEnemyGrid() = enemyGrid.rebuild(enemyActors)
    fun rebuildFriendlyGrid() = friendlyGrid.rebuild(friendlyActors)

    /** If an enemy's AABB has changed (moved), call this to update just that one. */
    fun updateEnemyInGrid(enemy: Actor) = enemyGrid.upsert(enemy)
    fun updateFriendlyInGrid(friendly: Actor) = friendlyGrid.upsert(friendly)

    fun queryEnemyForAabbInto(sweepMin: Vec3, sweepMax: Vec3, out: MutableList<Actor>) =
        enemyGrid.queryActorAabbsXY(sweepMin, sweepMax, out)

    fun queryFriendlyForAabbInto(sweepMin: Vec3, sweepMax: Vec3, out: MutableList<Actor>) =
        friendlyGrid.queryActorAabbsXY(sweepMin, sweepMax, out)

    fun queryEnemyForSegmentInto(
        p0: Vec3, p1: Vec3, radius: Float, out: MutableList<Actor>
    ) {
        // Use segment’s own z range for culling; grid method inflates sensibly
        enemyGrid.queryActorsForSegmentXY(p0, p1, radius, zMin = minOf(p0.z, p1.z), zMax = maxOf(p0.z, p1.z), out = out)
    }

    fun queryEnemiesAndFriendliesForAabbInto(
        sweepMin: Vec3, sweepMax: Vec3, out: MutableList<Actor>
    ) {
        // DO NOT clear 'out' here; moveShipSlideOnHit already clears.
        enemyGrid.queryActorAabbsXY(sweepMin, sweepMax, out)
        friendlyGrid.queryActorAabbsXY(sweepMin, sweepMax, out)
    }

    fun getElevationAverage(position2d: PointF): Float? {
        if (mapBounds.contains(position2d)) {
            val total = heightMap[getIndexBefore(position2d.y)][getIndexBefore(position2d.x)] +
                    heightMap[getIndexAfter(position2d.y)][getIndexBefore(position2d.x)] +
                    heightMap[getIndexBefore(position2d.y)][getIndexAfter(position2d.x)] +
                    heightMap[getIndexAfter(position2d.y)][getIndexAfter(position2d.x)]

            return total / 4f
        }
        else {
            return null
        }
    }

    fun getElevationBefore(position2d: PointF): Float? {
        if (mapBounds.contains(position2d)) {
            return heightMap[getIndexBefore(position2d.y)][getIndexBefore(position2d.x)]
        }
        else {
            return null
        }
    }

    fun terrainElevationAt(x: Float, y: Float): Float? {
        if (safeBounds.contains(x, y)) {
            val gridX = getIndexBefore(x) // floor(x).toInt()
            val gridY = getIndexBefore(y) // floor(y).toInt()

            // fractional position inside the current cell
            val xCoord = x / MAP_GRID_SPACING - gridX
            val yCoord = y / MAP_GRID_SPACING - gridY

            val z00 = heightMap[gridY][gridX]         // (0,0)
            val z10 = heightMap[gridY][gridX + 1]     // (1,0)
            val z01 = heightMap[gridY + 1][gridX]     // (0,1)
            val z11 = heightMap[gridY + 1][gridX + 1] // (1,1)

            if (yCoord < 1f - xCoord) {
                // lower-left triangle: (0,0), (0,1), (1,0)
                return barycentricHeight(
                        Vec3(0f, 0f, z00),
                        Vec3(0f, 1f, z01),
                        Vec3(1f, 0f, z10),
                        PointF(xCoord, yCoord)
                    )
            } else {
                // upper-right triangle: (1,0), (0,1), (1,1)
                return barycentricHeight(
                    Vec3(1f, 0f, z10),
                    Vec3(0f, 1f, z01),
                    Vec3(1f, 1f, z11),
                    PointF(xCoord, yCoord)
                )
            }
        }
        else {
            return null
        }
    }

    // approximate normal using "central differences"
    fun terrainNormalAt(x: Float, y: Float): Vec3 {
        val ix = getIndexBefore(x).coerceIn(1, MAP_GRID_SIZE - 2)
        val iy = getIndexBefore(y).coerceIn(1, MAP_GRID_SIZE - 2)

        val dzdx = (heightMap[iy][ix + 1] - heightMap[iy][ix - 1]) * 0.5f
        val dzdy = (heightMap[iy + 1][ix] - heightMap[iy - 1][ix]) * 0.5f

        // z is elevation, so the normal is perpendicular to the slope
        return Vec3(-dzdx, -dzdy, 1f).normalizeInPlace()
    }
}

interface GridActor {
    val active: Boolean
    val instance: ModelInstance
    var lastQueryId: Int
}

private class GridEntry(val actor: Actor)

class SpatialGrid2D(private val cellSize: Float) {

    private fun key(ix: Int, iy: Int): Long =
        (ix.toLong() shl 32) xor (iy.toLong() and 0xffffffffL)

    private val cells = HashMap<Long, MutableList<GridEntry>>()
    private val where = HashMap<Actor, MutableList<Long>>() // <-- Actor now
    private var queryId = 0

    private fun toCell(x: Float): Int = floor(x / cellSize).toInt()

    fun upsert(actor: Actor) {
        remove(actor)

        if (actor.active) {
            val a = actor.instance.worldAabb
            val ix0 = toCell(a.min.x); val ix1 = toCell(a.max.x)
            val iy0 = toCell(a.min.y); val iy1 = toCell(a.max.y)

            val occupied = mutableListOf<Long>()
            for (ix in ix0..ix1) for (iy in iy0..iy1) {
                val k = key(ix, iy)
                cells.getOrPut(k) { mutableListOf() }.add(GridEntry(actor))
                occupied.add(k)
            }
            where[actor] = occupied
        }
    }

    fun remove(actor: Actor) {
        val old = where.remove(actor) ?: return
        for (k in old) {
            val list = cells[k] ?: continue
            var i = 0
            while (i < list.size) {
                if (list[i].actor === actor) list.removeAt(i) else i++
            }
            if (list.isEmpty()) cells.remove(k)
        }
    }

    fun rebuild(actors: List<Actor>) {
        cells.clear()
        where.clear()
        for (a in actors) if (a.active) upsert(a)
    }

    fun queryActorAabbsXY(minSweep: Vec3, maxSweep: Vec3, out: MutableList<Actor>) {
        queryId++

        val ix0 = toCell(minSweep.x); val ix1 = toCell(maxSweep.x)
        val iy0 = toCell(minSweep.y); val iy1 = toCell(maxSweep.y)

        val zMin = minSweep.z - cellSize
        val zMax = minSweep.z + cellSize

        for (ix in ix0..ix1) for (iy in iy0..iy1) {
            val list = cells[key(ix, iy)] ?: continue
            for (entry in list) {
                val actor = entry.actor

                if (actor.lastQueryId == queryId) continue
                if (actor.instance.position.z < zMin || actor.instance.position.z > zMax) continue

                actor.lastQueryId = queryId
                out.add(actor)
            }
        }
    }

    fun queryActorsForSegmentXY(
        p0: Vec3, p1: Vec3, radius: Float,
        zMin: Float, zMax: Float,
        out: MutableList<Actor>
    ) {
        queryId++

        val minx = min(p0.x, p1.x) - radius
        val miny = min(p0.y, p1.y) - radius
        val maxx = max(p0.x, p1.x) + radius
        val maxy = max(p0.y, p1.y) + radius

        val ix0 = toCell(minx); val ix1 = toCell(maxx)
        val iy0 = toCell(miny); val iy1 = toCell(maxy)

        for (ix in ix0..ix1) for (iy in iy0..iy1) {
            val list = cells[key(ix, iy)] ?: continue
            for (entry in list) {
                val actor = entry.actor
                if (actor.lastQueryId == queryId) continue

                actor.lastQueryId = queryId
                out.add(actor)
            }
        }
    }
}
class BattlespaceBounds(
    private val minX: Float = 0f,
    private val minY: Float = 0f,
    private val maxX: Float = 5000f,
    private val maxY: Float = 5000f,
    private val zCeil: Float = 500f,
) {
    fun slideOnBounds(pos: Vec3, vel: Vec3, radius: Float, eps: Float) {
        val minXc = minX + radius
        val maxXc = maxX - radius
        val minYc = minY + radius
        val maxYc = maxY - radius
        val maxZc = zCeil - radius

        if (pos.x < minXc) { pos.x = minXc + eps; if (vel.x < 0f) vel.x = 0f }
        else if (pos.x > maxXc) { pos.x = maxXc - eps; if (vel.x > 0f) vel.x = 0f }

        if (pos.y < minYc) { pos.y = minYc + eps; if (vel.y < 0f) vel.y = 0f }
        else if (pos.y > maxYc) { pos.y = maxYc - eps; if (vel.y > 0f) vel.y = 0f }

        if (pos.z > maxZc) { pos.z = maxZc - eps; if (vel.z > 0f) vel.z = 0f }
    }
}