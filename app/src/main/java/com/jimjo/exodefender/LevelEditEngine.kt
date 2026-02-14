package com.jimjo.exodefender

import kotlin.math.round
import kotlin.random.Random

class LevelEditEngine(val level: Level, val world: World) {

    var parentRenderer: GameGLRenderer? = null

    var lbRelocatingShip = false
    var relocatingActor: Actor? = null
    var relocatingStructure: FriendlyStructureActor? = null
    var structureRelocateStartBase = Vec3()      // base pos at start of relocate
    var cycleBlock: BuildingBlockActor? = null
    var cycleStage: Int = 0 // 0=none, 1=block, 2=structure
    var selectedBlock: BuildingBlockActor? = null
    var selectedStructure: FriendlyStructureActor? = null
    var vectorCameraToRelocatingActor = Vec3()

    val tmpDir = Vec3()
    val tmpHit = Vec3()
    private val tmpRot = Vec3()


    fun addActorOnGround(actorType: ActorType, target: Vec3): Boolean {

        if (parentRenderer != null) {
            var newActor: Actor? = null
            if (actorType == ActorType.GROUND_FRIENDLY) {
                newActor = world.spawnGroundFriendly(target.x, target.y, target.z)
            }
            else if (actorType == ActorType.EASY_GROUND_ENEMY) {
                newActor = world.spawnEasyGroundEnemy(target.x, target.y, target.z)
            }
            else if (actorType == ActorType.GROUND_ENEMY) {
                newActor = world.spawnGroundEnemy(target.x, target.y, target.z)
            }
            if (newActor != null) {
                val position = Vec3(target.x, target.y, target.z + newActor.instance.model.localAabb.height() / 2f)
                newActor.setPositionAndUpdate(position)

                val deconflictedPosition = getNonflictingActorPosition(newActor)
                if (deconflictedPosition != null) {
                    newActor.setPositionAndUpdate(deconflictedPosition)
                }
                level.writeGameMapStateToLevel()
                clearEditorSelection()
                newActor.select()
                return true
            }
        }
        return false
    }

    fun addFlyingEnemy(actorType: ActorType, target: Vec3) {

        var newActor: Actor? = null

        if (actorType == ActorType.EASY_FLYING_ENEMY) {
            newActor = world.spawnEasyFlyingEnemy(target.x, target.y, target.z, 50f, true)
        }
        else if (actorType == ActorType.FLYING_ENEMY) {
            newActor = world.spawnFlyingEnemy(target.x, target.y, target.z, 50f, true)
        }
        else if (actorType == ActorType.ADV_FLYING_ENEMY) {
            newActor = world.spawnAdvFlyingEnemy(target.x, target.y, target.z, 70f, 70f, 35f)
        }
        if (newActor != null) {
//            newActor.setPositionAndUpdate(Vec3(target.x, target.y, target.z))
            level.writeGameMapStateToLevel()
            clearEditorSelection()
            newActor.select()
        }
    }

    fun findFriendlyStructureTemplate(id: Int): FriendlyStructureTemplate? {
        return level.actorTemplates.firstOrNull { it is FriendlyStructureTemplate && it.id == id } as? FriendlyStructureTemplate
    }


    fun nextStructureTemplateId(): Int {
        val maxExisting = level.actorTemplates
            .filterIsInstance<FriendlyStructureTemplate>()
            .maxOfOrNull { it.id } ?: 0
        return maxExisting + 1
    }

    fun addFriendlyStructure(target: Vec3) {
        // For FriendlyStructure, position.z is BASE Z (bottom of the structure).
        val groundZ = world.terrainElevationAt(target.x, target.y) ?: run {
            println("[editor] addFriendlyStructure: no terrain at (${target.x}, ${target.y})")
            return
        }

        val id = nextStructureTemplateId()

        val structureBasePos = target.copy().apply {
            z = groundZ
        }

        // Starter block:
        // - localBasePos.z = 0 => block base sits on structure base plane
        // - dimensions = 20m cube (visible default)
        val starterBlock = BuildingBlockTemplate(
            localBasePos = Vec3(0f, 0f, 0f),
            dimensions = Vec3(20f, 20f, 20f),
            shape = BlockShape.BOX,
            landingPadTop = false
        )

        val t = FriendlyStructureTemplate(
            id = id,
            position = structureBasePos, // baseZ
            yaw = 0.0,
            hitpoints = 8000f,
            blocks = listOf(starterBlock)
        )

        // Spawn first so we don't persist a template if runtime creation fails.
        val runtime = world.spawnFriendlyStructure(t) ?: run {
            println("[editor] addFriendlyStructure: spawnFriendlyStructure returned null for id=$id")
            return
        }

        level.actorTemplates.add(t)

        clearEditorSelection()
        runtime.select()
        selectedStructure = runtime
    }

    private fun snap1m(v: Float): Float = round(v) // 1m grid
    fun addBlockToFriendlyStructure(structureId: Int, shape: BlockShape) {

        val st = level.findFriendlyStructureTemplate(structureId) ?: run {
            println("[editor] addBlockToFriendlyStructure: template not found id=$structureId")
            return
        }

        val runtime = world.findFriendlyStructureActor(structureId)

        // Decide spawn point in WORLD space
        val spawnWorld = Vec3()

        if (runtime != null) {
            // Ensure bounds are current (cheap + safe)
            val ok = runtime.updateBoundsAabb()
            if (!ok) {
                // fallback: use structure base
                spawnWorld.set(st.position.x + 5f, st.position.y, st.position.z)
            } else {
                val min = runtime.boundsAabb.min
                val max = runtime.boundsAabb.max
                spawnWorld.x = max.x + 5f
                spawnWorld.y = (min.y + max.y) * 0.5f
                val groundZ = world.terrainElevationAt(spawnWorld.x, spawnWorld.y)
                spawnWorld.z = if (groundZ != null && groundZ.isFinite()) groundZ else st.position.z
            }
        } else {
            // Fallback if runtime is missing (rare)
            spawnWorld.set(st.position.x + 5f, st.position.y, st.position.z)
        }

        // Convert WORLD spawn to LOCAL base pos relative to structure base
        val localBase = Vec3(
            spawnWorld.x - st.position.x,
            spawnWorld.y - st.position.y,
            spawnWorld.z - st.position.z
        )

        // snap to 1m grid
        localBase.x = snap1m(localBase.x)
        localBase.y = snap1m(localBase.y)
        localBase.z = snap1m(localBase.z)

        // Default authoring size (visible and consistent)
        val defaultDims = Vec3(20f, 20f, 20f)

        val newBlock = BuildingBlockTemplate(
            localBasePos = localBase,
            localYaw = 0.0,
            shape = shape,
            dimensions = defaultDims,
            style = "Default",
            landingPadTop = false
        )

        val newBlocks = st.blocks + newBlock
        val newSt = st.copy(blocks = newBlocks)

        // Rebuild from authoritative snapshot
        rebuildFriendlyStructure(structureId, newSt, false)

        // Select the new block (last index), not the structure
        val newIndex = newBlocks.size - 1
        clearEditorSelection()
        selectedStructure = null

        world.findFriendlyStructureActor(structureId)
            ?.blocks
            ?.getOrNull(newIndex)
            ?.select()
    }

    fun duplicateBlockInFriendlyStructure(structureId: Int, sourceIndex: Int): BuildingBlockActor? {
        val st = level.findFriendlyStructureTemplate(structureId) ?: return null
        if (sourceIndex !in st.blocks.indices) return null

        val src = st.blocks[sourceIndex]

        // Find runtime for bounds-based placement (prefer active)
        val runtime = world.findFriendlyStructureActor(structureId)

        // Decide spawn point in WORLD space (same rules as addBlock)
        val spawnWorld = Vec3()
        if (runtime != null) {
            runtime.updateBoundsAabb()

            val min = runtime.boundsAabb.min
            val max = runtime.boundsAabb.max

            spawnWorld.x = max.x + 5f
            spawnWorld.y = (min.y + max.y) * 0.5f

            val groundZ = world.terrainElevationAt(spawnWorld.x, spawnWorld.y)
            spawnWorld.z = groundZ ?: st.position.z
        } else {
            spawnWorld.set(st.position.x + 5f, st.position.y, st.position.z)
        }

        // Convert WORLD -> LOCAL
        val localBase = Vec3(
            spawnWorld.x - st.position.x,
            spawnWorld.y - st.position.y,
            spawnWorld.z - st.position.z
        )

        // snap to 1m grid
        localBase.x = snap1m(localBase.x)
        localBase.y = snap1m(localBase.y)
        localBase.z = snap1m(localBase.z)

        val dup = src.copy(localBasePos = localBase)

        val newBlocks = st.blocks + dup
        val newSt = st.copy(blocks = newBlocks)

        rebuildFriendlyStructure(structureId, newSt, false)

        // Select duplicated block (last)
        val newIndex = newBlocks.size - 1
        clearEditorSelection()
        selectedStructure = null
        world.findFriendlyStructureActor(structureId)
            ?.blocks
            ?.getOrNull(newIndex)
            ?.select()

        return world.findFriendlyStructureActor(structureId)
            ?.blocks
            ?.getOrNull(newIndex)
    }


    fun removeFriendlyStructureTemplate(id: Int) {
        level.actorTemplates.removeIf { it is FriendlyStructureTemplate && it.id == id }
    }

    fun removeSelectedFriendlyStructures() {
        // Collect selected structures first (avoid mutating while iterating)
        val selectedStructures = world.actors
            .filterIsInstance<FriendlyStructureActor>()
            .filter { it.selected }

        if (selectedStructures.isEmpty()) return

        for (s in selectedStructures) {
            s.unselect()

            // Deactivate the structure (and ideally its block actors too).
            // If FriendlyStructure owns its block actors, add a helper on FriendlyStructure to deactivate children.
            s.active = false

            // If you have access to child block actors, deactivate them too:
             for (b in s.blocks) { b.active = false }   // adjust to your actual field name

            // Template removal: structure actor should know its template id
            removeFriendlyStructureTemplate(s.templateId) // <-- change if runtime field differs (e.g. s.templateId)
        }

        // Clear editor state to avoid relocation trying to operate on a now-inactive structure
        selectedStructure = null
        relocatingActor = null

        world.updateActorCounts()
    }

    fun upsertFriendlyStructureTemplate(snapshot: FriendlyStructureTemplate) {
        val idx = level.actorTemplates.indexOfFirst {
            it is FriendlyStructureTemplate && it.id == snapshot.id
        }
        if (idx >= 0) level.actorTemplates[idx] = snapshot
        else level.actorTemplates.add(snapshot)
    }

    fun rebuildFriendlyStructure(id: Int, snapshot: FriendlyStructureTemplate, reselectStructure: Boolean, reselectBlockIndex: Int? = null) {
        // 1) update level data
        upsertFriendlyStructureTemplate(snapshot)

        // 2) hard purge all runtime actors and visuals related to this structure id
        // (blocks AND structure actor), not just old.blocks
        val toRemove = world.actors.filter { a ->
            when (a) {
                is BuildingBlockActor -> a.structure.templateId == id
                is FriendlyStructureActor -> a.templateId == id   // or whatever field you use
                else -> false
            }
        }

        world.removeCivilianVisualsForStructure(id)

        for (a in toRemove) {
            a.active = false
            world.removeActorFromWorld(a)
        }

        // 3) spawn new runtime from snapshot
        val rebuilt = world.spawnFriendlyStructure(snapshot) ?: return

        // 4) selection
        clearEditorSelection()

        if (reselectBlockIndex != null) {
            rebuilt.blocks.getOrNull(reselectBlockIndex)?.select()
        } else if (reselectStructure) {
            rebuilt.select()
            selectedStructure = rebuilt
        }
    }


    fun setFriendlyStructureBaseZ(id: Int, newBaseZ: Float) {
        val t = level.actorTemplates
            .filterIsInstance<FriendlyStructureTemplate>()
            .firstOrNull { it.id == id } ?: return

        val newT = t.copy(position = t.position.copy().apply { z = newBaseZ })
        rebuildFriendlyStructure(id, newT, true)
    }

    fun removeSelectedActors() {
        if (parentRenderer == null) return

        // 0) Remove selected building blocks (template-backed)
        val removedBlocks = removeSelectedBuildingBlocks()

        // 1) remove selected structures (template-backed)
        val hadSelectedStructure = world.actors.any { it is FriendlyStructureActor && it.selected && it.active}
        if (hadSelectedStructure) {
            removeSelectedFriendlyStructures()
        }

        // 2) Then remove other selected runtime-backed actors
        var removedNonStructure = false
        for (actor in world.actors) {
            if (actor.selected && actor !is FriendlyStructureActor) {
                actor.unselect()
                actor.active = false
                relocatingActor = null
                removedNonStructure = true
            }
        }

        world.updateActorCounts()

        // Only regenerate templates from runtime state if we removed runtime-backed actors.
        if (removedNonStructure) {
            level.writeGameMapStateToLevel()
        }
    }

    fun removeSelectedBuildingBlocks(): Boolean {
        val selectedBlocks = world.actors.filterIsInstance<BuildingBlockActor>()
            .filter { it.selected }

        if (selectedBlocks.isEmpty()) return false

        // If multiple are selected, group by structureId so we rebuild once per structure
        val byStructure = selectedBlocks.groupBy { it.structure.templateId }

        for ((structureId, blocks) in byStructure) {
            val st = level.findFriendlyStructureTemplate(structureId) ?: continue

            // IMPORTANT: blockIndex is fragile if multiple removals happen.
            // Safer: remove by runtime reference mapping to index at time of removal.
            // For now, compute indices and remove descending.
            val indicesToRemove = blocks.mapNotNull { b ->
                val idx = b.blockIndex
                if (idx in st.blocks.indices) idx else null
            }.distinct().sortedDescending()

            if (indicesToRemove.isEmpty()) continue

            val newBlocks = st.blocks.toMutableList().apply {
                for (idx in indicesToRemove) removeAt(idx)
            }

            val newStructure = st.copy(blocks = newBlocks)

            rebuildFriendlyStructure(structureId, newStructure, false)
        }

        return true
    }


    fun getFirstSelectedActor(): Actor? {
        if (parentRenderer != null) {

            if (parentRenderer!!.ship.selected) return parentRenderer!!.ship

            for (actor in world.actors) {
                if (actor.selected) return actor
            }
        }
        return null
    }

    // returns the BOTTOM center of the viable bounding box
    fun getNonflictingActorPosition(actor: Actor): Vec3? {
        val candidateAabb = Aabb().apply { copy(actor.instance.worldAabb) }

        while (true) {

            val position = candidateAabb.center()
            val newTargetZ = world.terrainElevationAt(position.x, position.y)
            if (newTargetZ != null) {

                candidateAabb.repositionCenter(Vec3(position.x, position.y, newTargetZ))

                val conflictingActor = world.findIntersectingActor(actor, candidateAabb)
                if (conflictingActor != null) {
                    val newTarget = getNearbyPoint(candidateAabb.center(), conflictingActor.instance.worldAabb.center(), 2 * (conflictingActor.instance.worldAabb.width() + candidateAabb.width()))
                    newTarget.z = newTargetZ
                    candidateAabb.repositionCenter(newTarget)
                } else {
                    return candidateAabb.center() + Vec3(0f, 0f, actor.instance.model.localAabb.height() / 2f)
                }
            }
            else {
                return null
            }
        }
    }


    fun getNearbyPoint(targetPoint :Vec3, conflictingPoint: Vec3, distance: Float): Vec3 {

        val tpoint = Vec3(targetPoint.x, targetPoint.y, targetPoint.z)

        // random decider as to what direction to go if target and conflicting are in the same x-y location
        if (tpoint.x == conflictingPoint.x) {
            tpoint.x += Random.nextFloat() - 0.5f
        }
        if (tpoint.y == conflictingPoint.y) {
            tpoint.y += Random.nextFloat() - 0.5f
        }

        val direction = (tpoint - conflictingPoint).normalizeInPlace().mulLocal(distance)
        return conflictingPoint + direction
    }

    fun startRelocatingSelectedActor() {
        if (parentRenderer == null) return

        if (parentRenderer!!.ship.selected) {
            startRelocatingActor(true, null)
            return
        }

        // STRUCTURE
        selectedStructure?.let { s ->
            startRelocatingStructure(s)
            return
        }

        // OTHER ACTORS
        for (actor in world.actors) {
            if (actor.active && actor.selected) {
                startRelocatingActor(false, actor)
                return
            }
        }
    }


    fun startRelocatingActor(ship: Boolean, actor: Actor?) {

        if (parentRenderer != null) {
            if (ship) {
                lbRelocatingShip = true
                relocatingActor = null
                if (parentRenderer!!.ship.selected) {
                    vectorCameraToRelocatingActor.set(parentRenderer!!.ship.position - parentRenderer!!.camera.position)


                }
            }
            else if (actor != null) {
                lbRelocatingShip = false
                relocatingActor = actor
                vectorCameraToRelocatingActor.set(actor.position - parentRenderer!!.camera.position)
            }
        }
    }

    fun startRelocatingStructure(structure: FriendlyStructureActor) {
        if (parentRenderer == null) return

        lbRelocatingShip = false
        relocatingActor = null
        relocatingStructure = structure

        val cam = parentRenderer!!.camera

        // XY offset only
        vectorCameraToRelocatingActor.set(
            structure.position.x - cam.position.x,
            structure.position.y - cam.position.y,
            0f
        )

        // Snapshot locals based on current world state
        val base = structure.position
        val yaw = structure.yawRad

        val c = kotlin.math.cos(-yaw).toFloat()
        val s = kotlin.math.sin(-yaw).toFloat()

        for (b in structure.blocks) {
            val dx = b.position.x - base.x
            val dy = b.position.y - base.y

            val lx = c * dx - s * dy
            val ly = s * dx + c * dy

            val localBaseZ = (b.position.z - base.z) - b.halfExtents.z

            b.relocateLocalBasePos.set(lx, ly, localBaseZ)
            b.relocateLocalYaw = b.yawRad - yaw
        }
    }


    fun applyStructureRelocateInPlace(newBasePos: Vec3) {
        val s = relocatingStructure ?: return

        newBasePos.z = s.position.z

        val dx = newBasePos.x - s.position.x
        val dy = newBasePos.y - s.position.y

        s.setPositionAndUpdate(newBasePos)


        // Recompute each block from snapped locals
        for (b in s.blocks) {
            if (!b.active) continue

            b.setPositionAndUpdate(b.position.x + dx, b.position.y + dy, b.position.z)
        }

        if (s.drawEditorBounds) s.updateBoundsAabb()
    }

//    fun applyStructureRelocateRotateInPlace(s: FriendlyStructureActor, newBasePos: Vec3, newYawRad: Double, lockZ: Boolean = false) {
//
//        // Lock Z for now (your plan)
//        if (lockZ) newBasePos.z = s.position.z
//
//        // Move + yaw
//        s.setPositionAndUpdate(newBasePos, yawRad = newYawRad)
//
//        // Recompute each block from snapped locals
//        for (b in s.blocks) {
//            if (!b.active) continue
//
//            rotateYaw(b.relocateLocalBasePos, -newYawRad, tmpRot)
//
//
//            val wx = newBasePos.x + tmpRot.x
//            val wy = newBasePos.y + tmpRot.y
//            val wz = newBasePos.z + tmpRot.z + b.halfExtents.z
//
//            val blockYaw = newYawRad + b.relocateLocalYaw
//
//            b.setPositionAndUpdate(wx, wy, wz, yawRad = blockYaw)
//        }
//
//        if (s.drawEditorBounds) s.updateEditorBoundsAabb()
//    }




    fun finishRelocatingActor(writeGameState: Boolean) {

        // Commit structure relocate if needed
        relocatingStructure?.let { s ->
            commitStructureBaseToTemplate(s)
        }

        relocatingActor = null
        relocatingStructure = null
        lbRelocatingShip = false

        if (writeGameState) {
            level.writeGameMapStateToLevel()
        }
    }


    fun commitStructureBaseToTemplate(s: FriendlyStructureActor) {
        val t = level.actorTemplates
            .firstOrNull { it is FriendlyStructureTemplate && it.id == s.templateId }
                as? FriendlyStructureTemplate
            ?: return

        t.position.set(s.position)
        t.yaw = s.yawRad
    }



    //Computes a spawn point for a new actor.
    fun computeSpawnPointForNewActor(
        camera: Camera,
        isGroundActor: Boolean,
        outPos: Vec3
    ): Boolean {
        camera.forwardWorld(tmpDir)

        if (isGroundActor) {
            val hit = raycastTerrainZUp(
                origin = camera.position,
                dir = tmpDir,
                maxDist = 3000f,
                step = 10f,
                outHit = tmpHit
            )

            if (!hit) {
                // no terrain hit => caller must handle (e.g. Toast) and do not spawn.
                return false
            }

            outPos.set(tmpHit)

            // Optional safety: ensure Z comes from terrain function if you want to be extra strict.
            // (Usually tmpHit.z is already correct if raycastTerrainZUp hits terrain.)
            // world.terrainElevationAt(outPos.x, outPos.y)?.let { outPos.z = it }

            return true
        }

        // Flying actors: keep existing behaviour and always succeed.
        placeFlyingAlongRay(
            camera = camera,
            world = world,
            distance = 75f,
            minClearance = 10f,
            outPos = outPos
        )
        return true
    }


    private fun raySphereHitT(origin: Vec3, dir: Vec3, center: Vec3, radius: Float): Float? {
        // Assumes dir normalized.
        val ox = origin.x - center.x
        val oy = origin.y - center.y
        val oz = origin.z - center.z

        val b = ox * dir.x + oy * dir.y + oz * dir.z
        val c = ox*ox + oy*oy + oz*oz - radius * radius
        val disc = b*b - c
        if (disc < 0f) return null

        val s = kotlin.math.sqrt(disc)

        // Near intersection
        val t0 = -b - s
        if (t0 >= 0f) return t0

        // Far intersection (ray starts inside sphere or near hit is behind origin)
        val t1 = -b + s
        return if (t1 >= 0f) t1 else null
    }


    private fun rayAabbHitT(origin: Vec3, dir: Vec3, center: Vec3, half: Vec3): Float? {
        var tmin = 0f
        var tmax = 1e9f

        fun slab(o: Float, d: Float, c: Float, h: Float): Boolean {
            val minV = c - h
            val maxV = c + h
            if (kotlin.math.abs(d) < 1e-6f) return o in minV..maxV

            val inv = 1f / d
            var t1 = (minV - o) * inv
            var t2 = (maxV - o) * inv
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }

            tmin = kotlin.math.max(tmin, t1)
            tmax = kotlin.math.min(tmax, t2)
            return tmin <= tmax
        }

        if (!slab(origin.x, dir.x, center.x, half.x)) return null
        if (!slab(origin.y, dir.y, center.y, half.y)) return null
        if (!slab(origin.z, dir.z, center.z, half.z)) return null
        return tmin
    }

    fun selectUnderReticleWithStructureCycle() {
        // If a block is under reticle, use cycle behavior
        val hitBlock = pickBlockUnderReticle()
        if (hitBlock != null) {
            cycleSelectStructureBlockNone(hitBlock)
            return
        }

        // Otherwise use normal reticle selection for everything else
        // Also reset cycle state so it doesn't "stick"
        cycleBlock = null
        cycleStage = 0
        selectedBlock = null
        selectedStructure = null

        selectActorUnderReticle()   // your existing one
    }

    fun selectActorUnderReticle() {
        if (world.renderer == null) return

        val camera = world.renderer!!.camera
        val ship = world.renderer!!.ship

        if (relocatingActor != null) {
            finishRelocatingActor(true)
        }

        val origin = camera.position
        camera.forwardWorld(tmpDir)
        tmpDir.normalizeInPlace()

        val maxDist = 5000f
        var best: Actor? = null
        var bestT = maxDist

        val defaultPickRadius = 35f
        val structurePickRadius = 120f
        val shipPickRadius = 60f   // tune

        // 1) Pick among world actors
        for (a in world.actors) {
            if (!a.active) continue

            val tHit: Float? = when (a) {
                is BuildingBlockActor ->
                    rayAabbHitT(origin, tmpDir, a.instance.position, a.halfExtents)

                is FriendlyStructureActor ->
                    raySphereHitT(origin, tmpDir, a.position, structurePickRadius)

                else ->
                    raySphereHitT(origin, tmpDir, a.position, defaultPickRadius)
            }

            if (tHit != null && tHit >= 0f && tHit < bestT) {
                bestT = tHit
                best = a
            }
        }

        // 2) Also test ship (even if it's not in world.actors)
        if (ship.active) {
            val tShip = raySphereHitT(origin, tmpDir, ship.position, shipPickRadius)
            if (tShip != null && tShip >= 0f && tShip < bestT) {
                bestT = tShip
                best = ship
            }
        }

        // 3) Apply selection
        if (best != null) {
            val wasSelected = best.selected
            clearEditorSelection()
            if (wasSelected) best.unselect() else best.select()
        } else {
            clearEditorSelection()
        }
    }

    fun clearEditorSelection() {
        world.unselectAllActors()
        selectedBlock = null
        selectedStructure = null
    }

    private fun pickBlockUnderReticle(): BuildingBlockActor? {
        if (world.renderer == null) return null

        val camera = world.renderer!!.camera
        val origin = camera.position
        camera.forwardWorld(tmpDir)
        tmpDir.normalizeInPlace()

        val maxDist = 5000f
        var best: BuildingBlockActor? = null
        var bestT = maxDist

        for (a in world.actors) {
            if (!a.active) continue
            if (a is BuildingBlockActor) {
                val t = rayAabbHitT(origin, tmpDir, a.instance.position, a.halfExtents)
                if (t != null && t >= 0f && t < bestT) {
                    bestT = t
                    best = a
                }
            }
        }
        return best
    }


    fun cycleSelectStructureBlockNone(hitBlock: BuildingBlockActor) {
        val sameFocus = (cycleBlock === hitBlock)

        if (!sameFocus) {
            cycleBlock = hitBlock
            cycleStage = 1 // STRUCTURE
        } else {
            cycleStage = (cycleStage + 1) % 3 // 1->2->0
        }

        clearEditorSelection()

        when (cycleStage) {
            1 -> {
                // STRUCTURE selected (AABB shown via drawEditorBounds)
                hitBlock.structure.select()
                selectedStructure = hitBlock.structure
            }

            2 -> {
                // BLOCK selected
                hitBlock.select()
                selectedStructure = hitBlock.structure
            }

            else -> {
                // NONE
                cycleBlock = null
                selectedStructure = null
            }
        }
    }


    private fun rotateYaw(v: Vec3, yaw: Double, out: Vec3): Vec3 {
        val c = kotlin.math.cos(yaw).toFloat()
        val s = kotlin.math.sin(yaw).toFloat()
        out.set(
            c * v.x - s * v.y,
            s * v.x + c * v.y,
            v.z
        )
        return out
    }

    fun placeFlyingAlongRay(
        camera: Camera,
        world: World,
        distance: Float,
        minClearance: Float,
        outPos: Vec3
    ) {
        camera.forwardWorld(tmpDir)

        val x = camera.position.x + tmpDir.x * distance
        val y = camera.position.y + tmpDir.y * distance
        var z = camera.position.z + tmpDir.z * distance

        val groundZ = world.terrainElevationAt(x, y)
        if (groundZ != null && z < groundZ + minClearance) {
            z = groundZ + minClearance
        }

        outPos.set(x, y, z)
    }


    fun raycastTerrainZUp(
        origin: Vec3,
        dir: Vec3,            // normalized
        maxDist: Float,
        step: Float,
        outHit: Vec3
    ): Boolean {

        // Only when facing ground
        if (dir.z >= 0f) return false

        val startGroundZ = world.terrainElevationAt(origin.x, origin.y)
            ?: return false

        var t0 = 0f
        var above0 = (origin.z - startGroundZ) >= 0f

        var t = step
        while (t <= maxDist) {
            val x = origin.x + dir.x * t
            val y = origin.y + dir.y * t
            val z = origin.z + dir.z * t

            val groundZ = world.terrainElevationAt(x, y)
                ?: return false   // left terrain bounds → abort

            val above = (z - groundZ) >= 0f

            if (above0 && !above) {
                // refine intersection with binary search
                var lo = t0
                var hi = t
                repeat(12) {
                    val mid = (lo + hi) * 0.5f
                    val mx = origin.x + dir.x * mid
                    val my = origin.y + dir.y * mid
                    val mz = origin.z + dir.z * mid

                    val g = world.terrainElevationAt(mx, my)
                        ?: return false

                    if ((mz - g) >= 0f) lo = mid else hi = mid
                }

                val thit = hi
                val hx = origin.x + dir.x * thit
                val hy = origin.y + dir.y * thit
                val hz = world.terrainElevationAt(hx, hy) ?: return false

                outHit.set(hx, hy, hz)
                return true
            }

            t0 = t
            above0 = above
            t += step
        }

        return false
    }


}