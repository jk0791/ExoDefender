package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class LevelEditorToolbarView(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    val mainActivity = context as MainActivity
    lateinit var gLView: GameSurfaceView
    lateinit var level: Level
    lateinit var camera: Camera
    var opened = false
    val relocateActorButton: Button

    private val baseActorTypes = listOf(
        ActorType.GROUND_FRIENDLY.name,
        ActorType.EASY_GROUND_ENEMY.name,
        ActorType.GROUND_ENEMY.name,
        ActorType.EASY_FLYING_ENEMY.name,
        ActorType.FLYING_ENEMY.name,
        ActorType.ADV_FLYING_ENEMY.name,
        ActorType.FRIENDLY_STRUCTURE.name,
    )

    private companion object {
        const val PASTE_STRUCTURE_ITEM = "PASTE_STRUCTURE"
    }

    var actorTypeSpinner: Spinner

    init {

        inflate(context, R.layout.level_editor_toolbar, this)

        this.findViewById<Button>(R.id.editActorButton).apply {
            setOnClickListener { editActor() }
        }

        actorTypeSpinner = findViewById(R.id.addActorTypeSelector)
        this.findViewById<Button>(R.id.addActorButton).apply {
            setOnClickListener { addActor() }
        }

        this.findViewById<Button>(R.id.selectActorButton).apply {
            setOnClickListener { selectActor() }
        }

        relocateActorButton = this.findViewById(R.id.relocateButton)
        relocateActorButton.setOnClickListener({ relocateActor() })

        this.findViewById<Button>(R.id.removeButton).apply {
            setOnClickListener {
                val hasSelectedStructure =
                    level.world.actors.any { it is FriendlyStructureActor && it.selected }

                if (hasSelectedStructure) {
                    confirmRemoveStructure {
                        removeSelectedActorsConfirmed()
                    }
                } else {
                    removeSelectedActorsConfirmed()
                }
            }
        }

    }

    fun load(glView: GameSurfaceView, level: Level, camera: Camera) {
        this.gLView = glView
        this.level = level
        this.camera = camera

        refreshActorTypeSpinner()
        opened = true

        opened = true
    }

    fun refreshActorTypeSpinner() {
        val types = buildList {
            addAll(baseActorTypes)
            if (mainActivity.levelManager.structureClipboard != null) {
                add(PASTE_STRUCTURE_ITEM)
            }
        }

        val adapter = ArrayAdapter(context, R.layout.actortypes_spinner_item, types)
        actorTypeSpinner.adapter = adapter
    }
    fun addActor() {

        val selectedActorTypeString = actorTypeSpinner.selectedItem as String
        val isPaste = selectedActorTypeString == PASTE_STRUCTURE_ITEM

        var selectedActorType: ActorType? = null
        var actorOnGround = false
        when (selectedActorTypeString) {
            ActorType.GROUND_FRIENDLY.name -> {
                selectedActorType = ActorType.GROUND_FRIENDLY
                actorOnGround = true
            }
            ActorType.EASY_GROUND_ENEMY.name -> {
                selectedActorType = ActorType.EASY_GROUND_ENEMY
                actorOnGround = true
            }
            ActorType.GROUND_ENEMY.name -> {
                selectedActorType = ActorType.GROUND_ENEMY
                actorOnGround = true
            }
            ActorType.EASY_FLYING_ENEMY.name -> {
                selectedActorType = ActorType.EASY_FLYING_ENEMY
                actorOnGround = false
            }
            ActorType.FLYING_ENEMY.name -> {
                selectedActorType = ActorType.FLYING_ENEMY
                actorOnGround = false
            }
            ActorType.ADV_FLYING_ENEMY.name -> {
                selectedActorType = ActorType.ADV_FLYING_ENEMY
                actorOnGround = false
            }

            ActorType.FRIENDLY_STRUCTURE.name -> {
                selectedActorType = ActorType.FRIENDLY_STRUCTURE
                actorOnGround = true
            }
            PASTE_STRUCTURE_ITEM -> {
                selectedActorType = ActorType.FRIENDLY_STRUCTURE
                actorOnGround = true
            }
        }
        if (selectedActorType == null) return

        val spawnPoint = Vec3()
        val ok = level.editEngine.computeSpawnPointForNewActor(camera, actorOnGround, spawnPoint)
        if (!ok) {
            Toast.makeText(context, "Aim at terrain to place this actor", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboardTemplate = if (isPaste) mainActivity.levelManager.structureClipboard else null
        if (isPaste && clipboardTemplate == null) {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            refreshActorTypeSpinner() // removes paste option if it was stale
            return
        }

        if (actorOnGround) {
            gLView.queueEvent {
                if (isPaste) {
                    // Paste (GL thread). Deep copy again so clipboard stays immutable.
                    level.editEngine.pasteFriendlyStructureFromTemplate(clipboardTemplate!!.deepCopy(), spawnPoint)
                } else if (selectedActorType == ActorType.FRIENDLY_STRUCTURE) {
                    level.editEngine.addFriendlyStructure(spawnPoint)
                }
                else {
                    level.editEngine.addActorOnGround(selectedActorType, spawnPoint)
                }
                writeToFile()
            }
        }
        else {
            gLView.queueEvent {
                level.editEngine.addFlyingEnemy(selectedActorType, spawnPoint)
                writeToFile()
            }
        }
    }


    fun selectActor() {
        val shift = gLView.flightControls.isShiftHeld
        level.editEngine.selectUnderReticleWithStructureCycle(shiftHeld = shift)

        val selectedActor = level.editEngine.getFirstSelectedActor()
        if (selectedActor != null) {
            mainActivity.actorEditMetadataView.load(this, selectedActor)
        }
    }

    fun editActor() {
        val selectedActor = level.editEngine.getFirstSelectedActor()
        if (selectedActor != null) {
            mainActivity.actorEditMetadataView.load(this, selectedActor)
            mainActivity.actorEditMetadataView.visibility = VISIBLE
            mainActivity.actorEditMetadataView.bringToFront()
        }
    }

    fun relocateActor() {
        if (!level.editEngine.lbRelocatingShip && level.editEngine.relocatingActor == null && level.editEngine.relocatingStructure == null) {
            level.editEngine.startRelocatingSelectedActor()
            relocateActorButton.text = "Save Relocate"
        }
        else {
            relocateActorButton.text = "Relocate"
            level.editEngine.finishRelocatingActor(level.editEngine.relocatingStructure == null)
            writeToFile()
        }
    }

    fun removeSelectedActorsConfirmed() {
        level.editEngine.removeSelectedActors()
        relocateActorButton.text = "Relocate"
        writeToFile()
    }

    private fun confirmRemoveStructure(onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Delete Structure?")
            .setMessage("This will remove the entire structure and all of its blocks.")
            .setPositiveButton("Delete") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun writeToFile() {
        mainActivity.levelManager.writeLevelfile(level)
    }

}
