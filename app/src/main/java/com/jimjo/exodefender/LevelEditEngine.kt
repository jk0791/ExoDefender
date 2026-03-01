package com.jimjo.exodefender

import kotlin.math.round
import kotlin.random.Random

enum class NudgeField { POS_X, POS_Y, POS_Z, YAW, DIM_W, DIM_D, DIM_H }

class LevelEditEngine(val level: Level, val world: World) {

    var parentRenderer: GameGLRenderer? = null

    var lbRelocatingShip = false
    var relocatingActor: Actor? = null
    var relocatingStructure: FriendlyStructureActor? = null
    var structureRelocateStartBase = Vec3()      // base pos at start of relocate
    var cycleBlock: BuildingBlockActor? = null
    var cycleStage: Int = 0 // 0=none, 1=structure, 2=block
//    var selectedBlock: BuildingBlockActor? = null
    var selectedStructure: FriendlyStructureActor? = null
    var vectorCameraToRelocatingActor = Vec3()

    var multiStructureId: Int? = null
    val multiSelectedBlocks = mutableSetOf<PadKey>()

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
                clearSelectionVisuals()
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
            clearSelectionVisuals()
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

    fun pasteFriendlyStructureFromTemplate(src: FriendlyStructureTemplate, spawnPoint: Vec3) {
        // For FriendlyStructure, position.z is BASE Z (bottom of the structure).
        val groundZ = world.terrainElevationAt(spawnPoint.x, spawnPoint.y) ?: run {
            println("[editor] pasteFriendlyStructure: no terrain at (${spawnPoint.x}, ${spawnPoint.y})")
            return
        }

        val newId = nextStructureTemplateId()

        val structureBasePos = spawnPoint.copy().apply { z = groundZ }

        val pasted = src.deepCopy(newId = newId, newPosition = structureBasePos)

        val runtime = world.spawnFriendlyStructure(pasted) ?: run {
            println("[editor] pasteFriendlyStructure: spawnFriendlyStructure returned null for id=$newId")
            return
        }

        level.actorTemplates.add(pasted)

        clearEditorSelection()
        runtime.select()
        selectedStructure = runtime
    }


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
        val newIndex = newBlocks.size - 1

        rebuildFriendlyStructure(structureId, newSt, false, postRebuild = { rebuilt ->
            clearSelectionVisuals()
            selectedStructure = null
            rebuilt.blocks.getOrNull(newIndex)?.select()
        })

//        // Rebuild from authoritative snapshot
//        rebuildFriendlyStructure(structureId, newSt, false)
//
//        // Select the new block (last index), not the structure
//        val newIndex = newBlocks.size - 1
//        clearEditorSelection()
//        selectedStructure = null
//
//        world.findFriendlyStructureActor(structureId)
//            ?.blocks
//            ?.getOrNull(newIndex)
//            ?.select()
    }

    fun duplicateSelectedClusterInFriendlyStructure(
        structureId: Int,
        fallbackSourceIndex: Int,
        onDone: (BuildingBlockActor?) -> Unit
    ) {

        val st = level.findFriendlyStructureTemplate(structureId) ?: return

        // Indices to duplicate (multi if active for this structure, else single)
        val indices: List<Int> =
            if (multiStructureId == structureId && multiSelectedBlocks.isNotEmpty()) {
                multiSelectedBlocks.map { it.blockIndex }.distinct().sorted()
            } else {
                listOf(fallbackSourceIndex)
            }

        val sel = indices.filter { it in st.blocks.indices }
        if (sel.isEmpty()) return

        // ---- Anchor (stable) ----
        val anchorIndex = sel.first()
        val anchorPos = st.blocks[anchorIndex].localBasePos

        // ---- Compute abutting delta in LOCAL space (Option 2) ----
        val aabb = selectedGroupLocalAabb(st, sel)
        val centerLocal = aabb.center()
        val camWorld = world.renderer?.camera?.position ?: return
        val camLocal = cameraPosLocal(st.position, st.yaw, camWorld)

        val dir = chooseOffsetDir(centerLocal, camLocal)
        var deltaLocal = computeAbutOffset(aabb, dir, gap = 0f)
        deltaLocal = snapOffsetNonOverlapping(deltaLocal)

        // New anchor destination (LOCAL)
        val newAnchor = Vec3(
            anchorPos.x + deltaLocal.x,
            anchorPos.y + deltaLocal.y,
            anchorPos.z + deltaLocal.z
        ).apply {
            x = snap1m(x)
            y = snap1m(y)
            z = snap1m(z)
        }

        // ---- Offsets from anchor in LOCAL space ----
        data class DupItem(val srcIndex: Int, val offset: Vec3)
        val items = sel.map { idx ->
            val p = st.blocks[idx].localBasePos
            DupItem(
                srcIndex = idx,
                offset = Vec3(p.x - anchorPos.x, p.y - anchorPos.y, p.z - anchorPos.z)
            )
        }

        // ---- Create duplicates appended ----
        val newBlocks = st.blocks.toMutableList()
        val newIndices = mutableListOf<Int>()

        for (it in items) {
            val src = st.blocks[it.srcIndex]

            val newPos = Vec3(
                newAnchor.x + it.offset.x,
                newAnchor.y + it.offset.y,
                newAnchor.z + it.offset.z
            ).apply {
                x = snap1m(x)
                y = snap1m(y)
                z = snap1m(z)
            }

            val dup = src.copy(localBasePos = newPos)
            newBlocks.add(dup)
            newIndices.add(newBlocks.lastIndex)
        }

        val newSt = st.copy(blocks = newBlocks)
        rebuildFriendlyStructure(structureId, newSt, false)

        // ---- Selection: new cluster becomes current multi-selection ----
        clearSelectionVisuals()
        selectedStructure = null

        multiSelectedBlocks.clear()
        multiStructureId = structureId
        for (idx in newIndices) multiSelectedBlocks.add(PadKey(structureId, idx))

        refreshMultiSelectionHighlights()

        // Return last duplicated block for metadata view focus
        val lastNewIndex = newIndices.last()
        rebuildFriendlyStructure(structureId, newSt, false, postRebuild = { rebuilt ->
            clearSelectionVisuals()
            selectedStructure = null

            multiSelectedBlocks.clear()
            multiStructureId = structureId
            for (idx in newIndices) multiSelectedBlocks.add(PadKey(structureId, idx))
            refreshMultiSelectionHighlights()

            onDone(rebuilt.blocks.getOrNull(lastNewIndex))
        })
    }

    fun pasteBlocksIntoFriendlyStructure(
        destStructureId: Int,
        clipboardBlocks: List<BuildingBlockTemplate>,
        onDone: (BuildingBlockActor?) -> Unit
    ) {

        if (clipboardBlocks.isEmpty()) return

        val st = level.findFriendlyStructureTemplate(destStructureId) ?: return
        val camWorld = world.renderer?.camera?.position ?: return

        // Camera position expressed in STRUCTURE-LOCAL coords (uses structure yaw)
        val camLocal = cameraPosLocal(st.position, st.yaw, camWorld)

        // Spawn point in LOCAL space (XY near camera; Z is forced to base plane)
        val spawnX = snap1m(camLocal.x)
        val spawnY = snap1m(camLocal.y)

        // Center the pasted cluster around spawnX/spawnY
        val srcCenter = blocksCentroid(clipboardBlocks)

        // Force pasted cluster lowest point to sit on structure localZ = 0
        val srcMinZ = blocksMinLocalZ(clipboardBlocks)

        val delta = Vec3(
            spawnX - srcCenter.x,
            spawnY - srcCenter.y,
            -srcMinZ
        )

        val newBlocks = st.blocks.toMutableList()
        val newIndices = mutableListOf<Int>()

        for (b in clipboardBlocks) {
            val newPos = Vec3(
                snap1m(b.localBasePos.x + delta.x),
                snap1m(b.localBasePos.y + delta.y),
                snap1m(b.localBasePos.z + delta.z)
            )

            newBlocks.add(b.copy(localBasePos = newPos))
            newIndices.add(newBlocks.lastIndex)
        }

        val newSt = st.copy(blocks = newBlocks)
        val lastIndex = newIndices.lastOrNull() ?: run { onDone(null); return }

        rebuildFriendlyStructure(destStructureId, newSt, false, postRebuild = { rebuilt ->
            clearSelectionVisuals()
            selectedStructure = null

            multiSelectedBlocks.clear()
            multiStructureId = destStructureId
            for (idx in newIndices) multiSelectedBlocks.add(PadKey(destStructureId, idx))
            refreshMultiSelectionHighlights()

            onDone(rebuilt.blocks.getOrNull(lastIndex))
        })
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

    fun rebuildFriendlyStructure(
        id: Int,
        snapshot: FriendlyStructureTemplate,
        reselectStructure: Boolean,
        reselectBlockIndex: Int? = null,
        postRebuild: ((FriendlyStructureActor) -> Unit)? = null
    ) {
        world.enqueueWorldEdit {
            rebuildFriendlyStructureNow(id, snapshot, reselectStructure, reselectBlockIndex, postRebuild)
        }
    }

    private fun rebuildFriendlyStructureNow(
        id: Int,
        snapshot: FriendlyStructureTemplate,
        reselectStructure: Boolean,
        reselectBlockIndex: Int? = null,
        postRebuild: ((FriendlyStructureActor) -> Unit)? = null
    ) {
        upsertFriendlyStructureTemplate(snapshot)

        val toRemove = world.actors.filter { a ->
            when (a) {
                is BuildingBlockActor -> a.structure.templateId == id
                is FriendlyStructureActor -> a.templateId == id
                else -> false
            }
        }

        world.removeCivilianVisualsForStructure(id)

        for (a in toRemove) {
            a.active = false
            world.removeActorFromWorld(a)
        }

        val rebuilt = world.spawnFriendlyStructure(snapshot) ?: return

        parentRenderer?.let { renderer ->
            rebuilt.initialize(
                renderer,
                level.world,
                null,
                renderer.ship,
                renderer.friendlyLaserBoltPool,
                renderer.friendlyExplosion,
                renderer.explosionFlash
            )
            for (blockActor in rebuilt.blocks) {
                blockActor.initialize(
                    renderer,
                    level.world,
                    null,
                    renderer.ship,
                    renderer.friendlyLaserBoltPool,
                    renderer.friendlyExplosion,
                    renderer.explosionFlash
                )
            }
        }

        // Visual selection clear
        clearSelectionVisuals()

        // Your old reselect behavior
        if (reselectBlockIndex != null) {
            rebuilt.blocks.getOrNull(reselectBlockIndex)?.select()
        } else if (reselectStructure) {
            rebuilt.select()
            selectedStructure = rebuilt
        }

        // NEW: let caller do additional selection (multi-select etc)
        postRebuild?.invoke(rebuilt)
    }

//    fun rebuildFriendlyStructure(id: Int, snapshot: FriendlyStructureTemplate, reselectStructure: Boolean, reselectBlockIndex: Int? = null) {
//        // 1) update level data
//        upsertFriendlyStructureTemplate(snapshot)
//
//        // 2) hard purge all runtime actors and visuals related to this structure id
//        // (blocks AND structure actor), not just old.blocks
//        val toRemove = world.actors.filter { a ->
//            when (a) {
//                is BuildingBlockActor -> a.structure.templateId == id
//                is FriendlyStructureActor -> a.templateId == id   // or whatever field you use
//                else -> false
//            }
//        }
//
//        world.removeCivilianVisualsForStructure(id)
//
//        for (a in toRemove) {
//            a.active = false
//            world.removeActorFromWorld(a)
//        }
//
//        // 3) spawn new runtime from snapshot
//        val rebuilt = world.spawnFriendlyStructure(snapshot) ?: return
//
//        // 4) selection
//        clearSelectionVisuals()
//
//        if (reselectBlockIndex != null) {
//            rebuilt.blocks.getOrNull(reselectBlockIndex)?.select()
//        } else if (reselectStructure) {
//            rebuilt.select()
//            selectedStructure = rebuilt
//        }
//    }


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

    fun applyNudgeToMultiSelectedBlocks(fieldKind: NudgeField, delta: Float) {
        val structureId = multiStructureId ?: return
        if (multiSelectedBlocks.isEmpty()) return

        val st = level.findFriendlyStructureTemplate(structureId) ?: return

        val newBlocks = st.blocks.toMutableList()

        for (k in multiSelectedBlocks) {
            val i = k.blockIndex
            if (i !in newBlocks.indices) continue

            val old = newBlocks[i]

            val updated = when (fieldKind) {
                NudgeField.POS_X -> old.copy(localBasePos = old.localBasePos.copy(x = old.localBasePos.x + delta))
                NudgeField.POS_Y -> old.copy(localBasePos = old.localBasePos.copy(y = old.localBasePos.y + delta))
                NudgeField.POS_Z -> old.copy(localBasePos = old.localBasePos.copy(z = old.localBasePos.z + delta))

                NudgeField.YAW -> {
                    // UI is degrees; your template stores radians
                    val dr = Math.toRadians(delta.toDouble())
                    old.copy(localYaw = old.localYaw + dr)
                }

                NudgeField.DIM_W -> old.copy(dimensions = old.dimensions.copy(x = (old.dimensions.x + delta).coerceAtLeast(1f)))
                NudgeField.DIM_D -> old.copy(dimensions = old.dimensions.copy(y = (old.dimensions.y + delta).coerceAtLeast(1f)))
                NudgeField.DIM_H -> old.copy(dimensions = old.dimensions.copy(z = (old.dimensions.z + delta).coerceAtLeast(1f)))
            }

            newBlocks[i] = updated
        }

        val newSt = st.copy(blocks = newBlocks)

        rebuildFriendlyStructure(structureId, newSt, false, postRebuild = {
            refreshMultiSelectionHighlights()
        })
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
            val indicesToRemove = blocks.mapNotNull { b ->
                val idx = b.blockIndex
                if (idx in st.blocks.indices) idx else null
            }.distinct().sortedDescending()

            if (indicesToRemove.isEmpty()) continue

            // We are about to mutate this structure's block list -> invalidate multi-select for it.
            if (multiStructureId == structureId) {
                multiSelectedBlocks.clear()
                multiStructureId = null
            }
            // Also kill the cycle/structure selection so nothing "sticks"
            cycleBlock = null
            cycleStage = 0
            selectedStructure?.unselect()
            selectedStructure = null

            val newBlocks = st.blocks.toMutableList().apply {
                for (idx in indicesToRemove) removeAt(idx)
            }

            val newStructure = st.copy(blocks = newBlocks)
            rebuildFriendlyStructure(structureId, newStructure, false)
        }

        // ensure visuals are consistent (esp. if we have leftover block selects elsewhere)
        clearEditorSelection()

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


    private fun toggleMultiBlock(hitBlock: BuildingBlockActor) {

        // If we're starting a multi-selection, seed it with the current single-selected block (if any)
        if (multiSelectedBlocks.isEmpty()) {
            val preSelected = world.actors
                .filterIsInstance<BuildingBlockActor>()
                .firstOrNull { it.selected }

            if (preSelected != null) {
                // Enforce single-structure rule
                val sid = preSelected.structure.templateId
                multiStructureId = sid
                multiSelectedBlocks.add(preSelected.padKey())
            }
        }

        val sidHit = hitBlock.structure.templateId

        // Enforce single-structure rule
        if (multiSelectedBlocks.isEmpty()) {
            multiStructureId = sidHit
        } else if (multiStructureId != sidHit) {
            // Replace selection with this structure (or ignore; replacement feels better)
            multiSelectedBlocks.clear()
            multiStructureId = sidHit
        }

        val key = hitBlock.padKey()

        if (!multiSelectedBlocks.add(key)) {
            multiSelectedBlocks.remove(key)
        }

        // Make visuals match the set (prevents "phantom selected" blocks)
        refreshMultiSelectionHighlights()

        // Clear structure selection / cycle state if you want
        selectedStructure?.unselect()
        selectedStructure = null
        cycleBlock = null
        cycleStage = 0

        if (multiSelectedBlocks.isEmpty()) multiStructureId = null
    }

    fun refreshMultiSelectionHighlights() {

        if (multiSelectedBlocks.isEmpty()) return

        for (a in world.actors) {
            if (a is BuildingBlockActor) {
                if (multiSelectedBlocks.contains(a.padKey())) {
                    a.select()
                } else {
                    a.unselect()
                }
            }
        }
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
        val structurePickRadius = 50f
        val shipPickRadius = 35f   // tune

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
            clearSelectionVisuals()
            if (wasSelected) best.unselect() else best.select()
        } else {
            clearSelectionVisuals()
        }
    }


    fun selectUnderReticleWithStructureCycle(shiftHeld: Boolean) {

        val hitBlock = pickBlockUnderReticle()

        // -------------------------------------------------
        // SHIFT HELD → additive block selection
        // -------------------------------------------------
        if (shiftHeld) {
            if (hitBlock != null) {
                toggleMultiBlock(hitBlock)
            }
            return
        }

        // -------------------------------------------------
        // NO SHIFT → normal single selection
        // -------------------------------------------------

        // NON-SHIFT = single selection => cancel multi selection
        clearMultiSelection()

        if (hitBlock != null) {
            cycleSelectStructureBlockNone(hitBlock)
            return
        }

        // Nothing block-related hit → normal selection
        cycleBlock = null
        cycleStage = 0
        selectedStructure = null

        selectActorUnderReticle()
    }

    private fun clearMultiSelection() {
        multiSelectedBlocks.clear()
        multiStructureId = null
        // optionally unselect all blocks visually, or call refresh after
//        clearEditorSelection()
    }
    fun clearSelectionVisuals() {
        world.unselectAllActors()
        selectedStructure = null
    }

    fun clearEditorSelection() {
        world.unselectAllActors()
        selectedStructure = null
        multiSelectedBlocks.clear()
        multiStructureId = null
        cycleBlock = null
        cycleStage = 0
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

        clearSelectionVisuals()

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


    private fun selectedGroupLocalAabb(st: FriendlyStructureTemplate, indices: List<Int>): Aabb {
        val aabb = Aabb(Vec3(), Vec3())
        aabb.setEmpty()

        for (i in indices) {
            val b = st.blocks[i] as BuildingBlockTemplate
            val hw = b.dimensions.x * 0.5f
            val hd = b.dimensions.y * 0.5f
            val hh = b.dimensions.z * 0.5f

            val c = kotlin.math.cos(b.localYaw)
            val s = kotlin.math.sin(b.localYaw)
            val hx = (kotlin.math.abs(c) * hw + kotlin.math.abs(s) * hd).toFloat()
            val hy = (kotlin.math.abs(s) * hw + kotlin.math.abs(c) * hd).toFloat()

            val cx = b.localBasePos.x
            val cy = b.localBasePos.y
            val cz = b.localBasePos.z + hh

            aabb.include(
                cx - hx, cy - hy, cz - hh,
                cx + hx, cy + hy, cz + hh
            )
        }
        return aabb
    }

    private fun blocksMinLocalZ(blocks: List<BuildingBlockTemplate>): Float {
        var minZ = Float.POSITIVE_INFINITY
        for (b in blocks) {
            if (b.localBasePos.z < minZ) minZ = b.localBasePos.z
        }
        return if (minZ.isFinite()) minZ else 0f
    }

    private fun blocksCentroid(blocks: List<BuildingBlockTemplate>): Vec3 {
        var sx = 0f; var sy = 0f; var sz = 0f
        for (b in blocks) {
            sx += b.localBasePos.x
            sy += b.localBasePos.y
            sz += b.localBasePos.z
        }
        val n = blocks.size.toFloat()
        return Vec3(sx / n, sy / n, sz / n)
    }

    private fun cameraPosLocal(structPos: Vec3, structYawRad: Double, cameraWorld: Vec3): Vec3 {
        val dx = cameraWorld.x - structPos.x
        val dy = cameraWorld.y - structPos.y
        val dz = cameraWorld.z - structPos.z

        val c = kotlin.math.cos(structYawRad)
        val s = kotlin.math.sin(structYawRad)

        // Inverse yaw: R(-yaw) * (world - pos)
        val lx = (dx * c + dy * s).toFloat()
        val ly = (-dx * s + dy * c).toFloat()
        return Vec3(lx, ly, dz.toFloat())
    }

    private fun isLowAngleToHorizontal(v: Vec3, thresholdDeg: Float = 45f): Boolean {
        val horiz = kotlin.math.sqrt(v.x * v.x + v.y * v.y)
        if (horiz <= 1e-4f) return true // straight above/below -> treat as low-angle safe
        val angleRad = kotlin.math.atan2(kotlin.math.abs(v.z), horiz)
        val angleDeg = (angleRad * 180.0 / Math.PI).toFloat()
        return angleDeg < thresholdDeg
    }

    private enum class OffsetDir { POS_X, NEG_X, POS_Y, NEG_Y, POS_Z }

    private fun chooseOffsetDir(
        groupCenterLocal: Vec3,
        cameraLocal: Vec3
    ): OffsetDir {
        val v = Vec3(
            cameraLocal.x - groupCenterLocal.x,
            cameraLocal.y - groupCenterLocal.y,
            cameraLocal.z - groupCenterLocal.z
        )

        // Your rule #1
        if (isLowAngleToHorizontal(v, 45f)) return OffsetDir.POS_Z

        // Your rule #2: which side camera is "in front" of
        // (Assumption: "in front" = dominant horizontal component toward camera)
        return if (kotlin.math.abs(v.x) >= kotlin.math.abs(v.y)) {
            if (v.x >= 0f) OffsetDir.POS_X else OffsetDir.NEG_X
        } else {
            if (v.y >= 0f) OffsetDir.POS_Y else OffsetDir.NEG_Y
        }
    }

    private fun computeAbutOffset(aabb: Aabb, dir: OffsetDir, gap: Float = 0f): Vec3 {
        val spanX = aabb.width()
        val spanY = aabb.depth()
        val spanZ = aabb.height()

        return when (dir) {
            OffsetDir.POS_X -> Vec3(spanX + gap, 0f, 0f)
            OffsetDir.NEG_X -> Vec3(-(spanX + gap), 0f, 0f)
            OffsetDir.POS_Y -> Vec3(0f, spanY + gap, 0f)
            OffsetDir.NEG_Y -> Vec3(0f, -(spanY + gap), 0f)
            OffsetDir.POS_Z -> Vec3(0f, 0f, spanZ + gap)
        }
    }

    private fun snap1m(v: Float): Float = round(v) // 1m grid
    private fun snap1mCeil(v: Float): Float = kotlin.math.ceil(v).toFloat()
    private fun snap1mFloor(v: Float): Float = kotlin.math.floor(v).toFloat()

    private fun snapOffsetNonOverlapping(delta: Vec3): Vec3 {
        fun snapAxis(x: Float): Float =
            if (x >= 0f) snap1mCeil(x) else snap1mFloor(x)

        return Vec3(snapAxis(delta.x), snapAxis(delta.y), snapAxis(delta.z))
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