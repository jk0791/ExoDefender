package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout

class ActorEditMetadataView(context: Context, attrs: AttributeSet? = null) :
    ConstraintLayout(context, attrs){

    val mainActivity = context as MainActivity
    lateinit var actor: Actor
    lateinit var parent: LevelEditorToolbarView

    var ignoreUiCallbacks = true
    private var pendingApply = false

    val displayActorType: TextView
    val displayActorPosition: TextView

    val flyingRadiusRow: View
    val antiClockwiseRow: View
    val editFlyingRadius: EditText
    val btnActorAnticlockwise: Switch


    val boundsRadiusXRow: View
    val editRadiusX: EditText
    val boundsRadiusYRow: View
    val editRadiusY: EditText
    val boundsRadiusZRow: View
    val editRadiusZ: EditText


    val structureYawRow: View
    val editStructureBaseZ: EditText
    val btnStructZMinus10: Button
    val btnStructZMinus1: Button
    val btnStructZPlus1: Button
    val btnStructZPlus10: Button


    val structureBaseZRow: View
    private val editStructureYaw: EditText
    private val btnStructureYawMinus10: Button
    private val btnStructureYawMinus1: Button
    private val btnStructureYawPlus1: Button
    private val btnStructureYawPlus10: Button


    val structureTimeRow: View
    val editStructureTimeSeconds: EditText

    val structureAddBlockRow: View
    val spinnerBlockShape: Spinner
    val btnAddBlock: Button

    // BLOCK EDIT CONTROLS
    private val blockHeaderRow: TextView
    private val blockPositionRow: View
    private val blockYawRow: View
    private val blockDimensionsRow: View

    // Block position edits
    private val editBlockX: EditText
    private val editBlockY: EditText
    private val editBlockZ: EditText

    // Block yaw edit (degrees in UI)
    private val editBlockYaw: EditText

    // Block dimension edits
    private val editDimW: EditText
    private val editDimD: EditText
    private val editDimH: EditText

    // Position nudge buttons
    private val btnBlockXMinus10: Button
    private val btnBlockXMinus1: Button
    private val btnBlockXPlus1: Button
    private val btnBlockXPlus10: Button

    private val btnBlockYMinus10: Button
    private val btnBlockYMinus1: Button
    private val btnBlockYPlus1: Button
    private val btnBlockYPlus10: Button

    private val btnBlockZMinus10: Button
    private val btnBlockZMinus1: Button
    private val btnBlockZPlus1: Button
    private val btnBlockZPlus10: Button

    // Yaw nudge buttons
    private val btnBlockYawMinus10: Button
    private val btnBlockYawMinus1: Button
    private val btnBlockYawPlus1: Button
    private val btnBlockYawPlus10: Button

    // Dimension nudge buttons
    private val btnDimWMinus10: Button
    private val btnDimWMinus1: Button
    private val btnDimWPlus1: Button
    private val btnDimWPlus10: Button

    private val btnDimDMinus10: Button
    private val btnDimDMinus1: Button
    private val btnDimDPlus1: Button
    private val btnDimDPlus10: Button

    private val btnDimHMinus10: Button
    private val btnDimHMinus1: Button
    private val btnDimHPlus1: Button
    private val btnDimHPlus10: Button


    val blockLandingPadRow: View
    val chkLandingPad: CheckBox
    val waitingAreaRow: View

    private val editCivilianInitialCount: EditText

    private val btnCiviliansMinus1: Button
    private val btnCiviliansPlus1: Button
    private val editWAPosX: EditText
    private val editWAPosY: EditText
    private val editWAPosZ: EditText

    private val btnWAPosXMinus10: Button
    private val btnWAPosXMinus1: Button
    private val btnWAPosXPlus1: Button
    private val btnWAPosXPlus10: Button
    private val btnWAPosYMinus10: Button
    private val btnWAPosYMinus1: Button
    private val btnWAPosYPlus1: Button
    private val btnWAPosYPlus10: Button
    private val btnWAPosZMinus10: Button
    private val btnWAPosZMinus1: Button
    private val btnWAPosZPlus1: Button
    private val btnWAPosZPlus10: Button


    val btnCopyStructure: Button
    val btnDuplicateBlock: Button



    init {

        inflate(context, R.layout.actor_edit_metadata, this)

        displayActorType = this.findViewById(R.id.displayActorType)
        displayActorPosition = this.findViewById(R.id.displayActorPosition)

        flyingRadiusRow = findViewById(R.id.flyingRadiusRow)
        antiClockwiseRow = findViewById(R.id.antiClockwiseRow)
        editFlyingRadius = this.findViewById(R.id.editActorFlyingRadius)
        btnActorAnticlockwise = this.findViewById(R.id.btnActorAnticlockwise)


        boundsRadiusXRow = findViewById(R.id.radiusXRow)
        editRadiusX = findViewById(R.id.editRadiusX)
        boundsRadiusYRow = findViewById(R.id.radiusYRow)
        editRadiusY = findViewById(R.id.editRadiusY)
        boundsRadiusZRow = findViewById(R.id.radiusZRow)
        editRadiusZ = findViewById(R.id.editRadiusZ)

        structureBaseZRow = findViewById(R.id.structureBaseZRow)
        editStructureBaseZ = findViewById(R.id.editStructureBaseZ)
        btnStructZMinus10 = findViewById(R.id.btnStructZMinus10)
        btnStructZMinus1 = findViewById(R.id.btnStructZMinus1)
        btnStructZPlus1 = findViewById(R.id.btnStructZPlus1)
        btnStructZPlus10 = findViewById(R.id.btnStructZPlus10)

        structureYawRow = findViewById(R.id.structureYawRow)
        editStructureYaw = findViewById(R.id.editStructureYaw)
        btnStructureYawMinus10 = findViewById(R.id.btnStructureYawMinus10)
        btnStructureYawMinus1 = findViewById(R.id.btnStructureYawMinus1)
        btnStructureYawPlus1 = findViewById(R.id.btnStructureYawPlus1)
        btnStructureYawPlus10 = findViewById(R.id.btnStructureYawPlus10)

        structureTimeRow = findViewById(R.id.structureTimeRow)
        editStructureTimeSeconds = findViewById(R.id.editStructureTimeSeconds)

        // Z nudges
        btnStructZMinus10.setOnClickListener { nudge(editStructureBaseZ, -10f); applyStructureEditsFromUi() }
        btnStructZMinus1.setOnClickListener { nudge(editStructureBaseZ, -1f); applyStructureEditsFromUi() }
        btnStructZPlus1.setOnClickListener { nudge(editStructureBaseZ, +1f); applyStructureEditsFromUi() }
        btnStructZPlus10.setOnClickListener { nudge(editStructureBaseZ, +10f); applyStructureEditsFromUi() }

        // Yaw nudges (degrees)
        btnStructureYawMinus10.setOnClickListener { nudge(editStructureYaw, -10f); applyStructureEditsFromUi() }
        btnStructureYawMinus1.setOnClickListener { nudge(editStructureYaw, -1f); applyStructureEditsFromUi() }
        btnStructureYawPlus1.setOnClickListener { nudge(editStructureYaw, +1f); applyStructureEditsFromUi() }
        btnStructureYawPlus10.setOnClickListener { nudge(editStructureYaw, +10f); applyStructureEditsFromUi() }

        structureAddBlockRow = findViewById(R.id.structureAddBlockRow)
        spinnerBlockShape = findViewById(R.id.spinnerBlockShape)
        btnAddBlock = findViewById(R.id.btnAddBlock)




        // BLOCK EDIT CONTROLS

        // Block rows
        blockHeaderRow = findViewById(R.id.blockHeaderRow)
        blockPositionRow = findViewById(R.id.blockPositionRow)
        blockYawRow = findViewById(R.id.blockYawRow)
        blockDimensionsRow = findViewById(R.id.blockDimensionsRow)

        // Edits
        editBlockX = findViewById(R.id.editBlockX)
        editBlockY = findViewById(R.id.editBlockY)
        editBlockZ = findViewById(R.id.editBlockZ)
        editBlockYaw = findViewById(R.id.editBlockYaw)

        editDimW = findViewById(R.id.editDimW)
        editDimD = findViewById(R.id.editDimD)
        editDimH = findViewById(R.id.editDimH)

        // Position buttons
        btnBlockXMinus10 = findViewById(R.id.btnBlockXMinus10)
        btnBlockXMinus1 = findViewById(R.id.btnBlockXMinus1)
        btnBlockXPlus1 = findViewById(R.id.btnBlockXPlus1)
        btnBlockXPlus10 = findViewById(R.id.btnBlockXPlus10)

        btnBlockYMinus10 = findViewById(R.id.btnBlockYMinus10)
        btnBlockYMinus1 = findViewById(R.id.btnBlockYMinus1)
        btnBlockYPlus1 = findViewById(R.id.btnBlockYPlus1)
        btnBlockYPlus10 = findViewById(R.id.btnBlockYPlus10)

        btnBlockZMinus10 = findViewById(R.id.btnBlockZMinus10)
        btnBlockZMinus1 = findViewById(R.id.btnBlockZMinus1)
        btnBlockZPlus1 = findViewById(R.id.btnBlockZPlus1)
        btnBlockZPlus10 = findViewById(R.id.btnBlockZPlus10)

        // Yaw buttons
        btnBlockYawMinus10 = findViewById(R.id.btnBlockYawMinus10)
        btnBlockYawMinus1 = findViewById(R.id.btnBlockYawMinus1)
        btnBlockYawPlus1 = findViewById(R.id.btnBlockYawPlus1)
        btnBlockYawPlus10 = findViewById(R.id.btnBlockYawPlus10)

        // Dimension buttons
        btnDimWMinus10 = findViewById(R.id.btnDimWMinus10)
        btnDimWMinus1 = findViewById(R.id.btnDimWMinus1)
        btnDimWPlus1 = findViewById(R.id.btnDimWPlus1)
        btnDimWPlus10 = findViewById(R.id.btnDimWPlus10)

        btnDimDMinus10 = findViewById(R.id.btnDimDMinus10)
        btnDimDMinus1 = findViewById(R.id.btnDimDMinus1)
        btnDimDPlus1 = findViewById(R.id.btnDimDPlus1)
        btnDimDPlus10 = findViewById(R.id.btnDimDPlus10)

        btnDimHMinus10 = findViewById(R.id.btnDimHMinus10)
        btnDimHMinus1 = findViewById(R.id.btnDimHMinus1)
        btnDimHPlus1 = findViewById(R.id.btnDimHPlus1)
        btnDimHPlus10 = findViewById(R.id.btnDimHPlus10)

        // LANDING PAD
        blockLandingPadRow = findViewById(R.id.blockLandingPadRow)
        chkLandingPad = findViewById(R.id.chkLandingPad)
        waitingAreaRow = findViewById(R.id.waitingAreaRow)

        // Edits

        editCivilianInitialCount = findViewById(R.id.editCivilianCount)
        btnCiviliansMinus1 = findViewById(R.id.btnCiviliansMinus1)
        btnCiviliansPlus1 = findViewById(R.id.btnCiviliansPlus1)

        editWAPosX = findViewById(R.id.editWAPosX)
        editWAPosY = findViewById(R.id.editWAPosY)
        editWAPosZ = findViewById(R.id.editWAPosZ)

        btnWAPosXMinus10 = findViewById(R.id.btnWAPosXMinus10)
        btnWAPosXMinus1 = findViewById(R.id.btnWAPosXMinus1)
        btnWAPosXPlus1 = findViewById(R.id.btnWAPosXPlus1)
        btnWAPosXPlus10 = findViewById(R.id.btnWAPosXPlus10)

        btnWAPosYMinus10 = findViewById(R.id.btnWAPosYMinus10)
        btnWAPosYMinus1 = findViewById(R.id.btnWAPosYMinus1)
        btnWAPosYPlus1 = findViewById(R.id.btnWAPosYPlus1)
        btnWAPosYPlus10 = findViewById(R.id.btnWAPosYPlus10)

        btnWAPosZMinus10 = findViewById(R.id.btnWAPosZMinus10)
        btnWAPosZMinus1 = findViewById(R.id.btnWAPosZMinus1)
        btnWAPosZPlus1 = findViewById(R.id.btnWAPosZPlus1)
        btnWAPosZPlus10 = findViewById(R.id.btnWAPosZPlus10)


        btnCopyStructure = findViewById(R.id.btnCopyStructure)
        btnDuplicateBlock = findViewById(R.id.btnDuplicateBlock)

        // Position nudges
        btnBlockXMinus10.setOnClickListener { nudge(editBlockX, -10f); applyBlockEditsFromUi() }
        btnBlockXMinus1.setOnClickListener { nudge(editBlockX, -1f); applyBlockEditsFromUi() }
        btnBlockXPlus1.setOnClickListener { nudge(editBlockX, +1f); applyBlockEditsFromUi() }
        btnBlockXPlus10.setOnClickListener { nudge(editBlockX, +10f); applyBlockEditsFromUi() }

        btnBlockYMinus10.setOnClickListener { nudge(editBlockY, -10f); applyBlockEditsFromUi() }
        btnBlockYMinus1.setOnClickListener { nudge(editBlockY, -1f); applyBlockEditsFromUi() }
        btnBlockYPlus1.setOnClickListener { nudge(editBlockY, +1f); applyBlockEditsFromUi() }
        btnBlockYPlus10.setOnClickListener { nudge(editBlockY, +10f); applyBlockEditsFromUi() }

        btnBlockZMinus10.setOnClickListener { nudge(editBlockZ, -10f); applyBlockEditsFromUi() }
        btnBlockZMinus1.setOnClickListener { nudge(editBlockZ, -1f); applyBlockEditsFromUi() }
        btnBlockZPlus1.setOnClickListener { nudge(editBlockZ, +1f); applyBlockEditsFromUi() }
        btnBlockZPlus10.setOnClickListener { nudge(editBlockZ, +10f); applyBlockEditsFromUi() }

        // Yaw nudges (degrees)
        btnBlockYawMinus10.setOnClickListener { nudge(editBlockYaw, -10f); applyBlockEditsFromUi() }
        btnBlockYawMinus1.setOnClickListener { nudge(editBlockYaw, -1f); applyBlockEditsFromUi() }
        btnBlockYawPlus1.setOnClickListener { nudge(editBlockYaw, +1f); applyBlockEditsFromUi() }
        btnBlockYawPlus10.setOnClickListener { nudge(editBlockYaw, +10f); applyBlockEditsFromUi() }

        // Dimension nudges
        btnDimWMinus10.setOnClickListener { nudge(editDimW, -10f); applyBlockEditsFromUi() }
        btnDimWMinus1.setOnClickListener { nudge(editDimW, -1f); applyBlockEditsFromUi() }
        btnDimWPlus1.setOnClickListener { nudge(editDimW, +1f); applyBlockEditsFromUi() }
        btnDimWPlus10.setOnClickListener { nudge(editDimW, +10f); applyBlockEditsFromUi() }

        btnDimDMinus10.setOnClickListener { nudge(editDimD, -10f); applyBlockEditsFromUi() }
        btnDimDMinus1.setOnClickListener { nudge(editDimD, -1f); applyBlockEditsFromUi() }
        btnDimDPlus1.setOnClickListener { nudge(editDimD, +1f); applyBlockEditsFromUi() }
        btnDimDPlus10.setOnClickListener { nudge(editDimD, +10f); applyBlockEditsFromUi() }

        btnDimHMinus10.setOnClickListener { nudge(editDimH, -10f); applyBlockEditsFromUi() }
        btnDimHMinus1.setOnClickListener { nudge(editDimH, -1f); applyBlockEditsFromUi() }
        btnDimHPlus1.setOnClickListener { nudge(editDimH, +1f); applyBlockEditsFromUi() }
        btnDimHPlus10.setOnClickListener { nudge(editDimH, +10f); applyBlockEditsFromUi() }


        chkLandingPad.setOnCheckedChangeListener { _, checked ->
            if (ignoreUiCallbacks) return@setOnCheckedChangeListener
            if (checked) {
                waitingAreaRow.visibility = VISIBLE
                if (editCivilianInitialCount.text.toString() == "") editCivilianInitialCount.setText("0")
                if (editWAPosX.text.toString() == "") editWAPosX.setText("0.0")
                if (editWAPosY.text.toString() == "") editWAPosY.setText("0.0")
                if (editWAPosZ.text.toString() == "") editWAPosZ.setText("0.0")
            }
            else {
                waitingAreaRow.visibility = GONE
            }
            applyBlockEditsFromUi()
        }

        btnCiviliansMinus1.setOnClickListener { nudgeInt(editCivilianInitialCount, -1); applyBlockEditsFromUi() }
        btnCiviliansPlus1.setOnClickListener { nudgeInt(editCivilianInitialCount, +1); applyBlockEditsFromUi() }

        btnWAPosXMinus10.setOnClickListener { nudge(editWAPosX, -10f); applyBlockEditsFromUi() }
        btnWAPosXMinus1.setOnClickListener { nudge(editWAPosX, -1f); applyBlockEditsFromUi() }
        btnWAPosXPlus1.setOnClickListener { nudge(editWAPosX, +1f); applyBlockEditsFromUi() }
        btnWAPosXPlus10.setOnClickListener { nudge(editWAPosX, +10f); applyBlockEditsFromUi() }

        btnWAPosYMinus10.setOnClickListener { nudge(editWAPosY, -10f); applyBlockEditsFromUi() }
        btnWAPosYMinus1.setOnClickListener { nudge(editWAPosY, -1f); applyBlockEditsFromUi() }
        btnWAPosYPlus1.setOnClickListener { nudge(editWAPosY, +1f); applyBlockEditsFromUi() }
        btnWAPosYPlus10.setOnClickListener { nudge(editWAPosY, +10f); applyBlockEditsFromUi() }

        btnWAPosZMinus10.setOnClickListener { nudge(editWAPosZ, -10f); applyBlockEditsFromUi() }
        btnWAPosZMinus1.setOnClickListener { nudge(editWAPosZ, -1f); applyBlockEditsFromUi() }
        btnWAPosZPlus1.setOnClickListener { nudge(editWAPosZ, +1f); applyBlockEditsFromUi() }
        btnWAPosZPlus10.setOnClickListener { nudge(editWAPosZ, +10f); applyBlockEditsFromUi() }


        btnDuplicateBlock.setOnClickListener {
            val src = actor as BuildingBlockActor
            val structureId = src.structure.templateId
            val blockIndex = src.blockIndex

            parent.gLView.queueEvent {
                val newBlock =
                    parent.level.editEngine
                        .duplicateBlockInFriendlyStructure(structureId, blockIndex)

                parent.mainActivity.runOnUiThread {
                    if (newBlock != null) {
                        load(parent, newBlock as Actor)   // rebind editor to NEW block
                    }
                    parent.writeToFile()
                }
            }
        }

        btnCopyStructure.setOnClickListener {
            val src = actor as? FriendlyStructureActor ?: return@setOnClickListener
            val templateId = src.templateId

            parent.gLView.queueEvent {
                // GL thread: safe to touch level + templateRepo
                val srcTemplate = parent.level.findFriendlyStructureTemplate(templateId) ?: return@queueEvent
                val copy = srcTemplate.deepCopy()

                // Still GL thread, but clipboard is just a plain var, so it's fine to set here
                parent.mainActivity.levelManager.structureClipboard = copy

                parent.mainActivity.runOnUiThread {
                    Toast.makeText(context, "Structure copied", Toast.LENGTH_SHORT).show()
                    parent.refreshActorTypeSpinner()
                }
            }
        }


        // APPLY CLOSE BUTTONS
        findViewById<Button>(R.id.applyEditActor).apply {
            setOnClickListener({ applyButtonClicked() })
        }

        findViewById<ImageView>(R.id.closeEditActor).apply {
            setOnClickListener({ closeButtonClicked() })
        }



    }

    fun updatePositionText() {
        displayActorPosition.text = "x=${df1.format(actor.position.x)}  y=${df1.format(actor.position.y)}  z=${df1.format(actor.position.z)}"
    }

    fun load(parent: LevelEditorToolbarView, actor: Actor) {

        this.parent = parent
        this.actor = actor
        hideAllNonCommonRows()
        btnCopyStructure.visibility = GONE
        btnDuplicateBlock.visibility = GONE
        ignoreUiCallbacks = true
        updatePositionText()


        if (actor is EasyFlyingEnemyActor) {
            this.parent.visibility = INVISIBLE
            displayActorType.text = "EasyFlyingEnemyActor"

            flyingRadiusRow.visibility = VISIBLE
            antiClockwiseRow.visibility = VISIBLE

            editFlyingRadius.setText(actor.flyingRadius.toString(), TextView.BufferType.EDITABLE)
            btnActorAnticlockwise.isChecked = actor.antiClockWise

            this.visibility = VISIBLE
            this.bringToFront()
        }
        else if (actor is FlyingEnemyActor) {
            this.parent.visibility = INVISIBLE
            displayActorType.text = "FlyingEnemyActor"

            flyingRadiusRow.visibility = VISIBLE
            antiClockwiseRow.visibility = VISIBLE

            editFlyingRadius.setText(actor.flyingRadius.toString(), TextView.BufferType.EDITABLE)
            btnActorAnticlockwise.isChecked = actor.antiClockWise

            this.visibility = VISIBLE
            this.bringToFront()
        }
        else if (actor is AdvFlyingEnemyActor) {
            this.parent.visibility = INVISIBLE
            displayActorType.text = "AdvFlyingEnemyActor"

            boundsRadiusXRow.visibility = VISIBLE
            boundsRadiusYRow.visibility = VISIBLE
            boundsRadiusZRow.visibility = VISIBLE

            editRadiusX.setText(actor.aabbHalfX.toString(), TextView.BufferType.EDITABLE)
            editRadiusY.setText(actor.aabbHalfY.toString(), TextView.BufferType.EDITABLE)
            editRadiusZ.setText(actor.aabbHalfZ.toString(), TextView.BufferType.EDITABLE)

            this.visibility = VISIBLE
            this.bringToFront()

        }
        else if (actor is FriendlyStructureActor) {
            this.parent.visibility = INVISIBLE
            displayActorType.text = "FriendlyStructureActor"

            btnCopyStructure.visibility = VISIBLE

            structureBaseZRow.visibility = VISIBLE
            structureYawRow.visibility = VISIBLE
            structureAddBlockRow.visibility = VISIBLE
            structureTimeRow.visibility = VISIBLE

            // baseZ semantics: position.z is baseZ
            editStructureBaseZ.setText(actor.position.z.toString(), TextView.BufferType.EDITABLE)

            // Yaw shown in degrees for humans
            editStructureYaw.setText(Math.toDegrees(actor.yawRad).toString(), TextView.BufferType.EDITABLE)

            editStructureTimeSeconds.setText((actor.initialDestructSeconds ?: "").toString())

            // spinner
            val shapes = BlockShape.values().toList()
            val adapter = ArrayAdapter(context, R.layout.spinner_item_white, shapes)
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_white)
            spinnerBlockShape.adapter = adapter

            // Add Block button (UI-first: we can stub engine call or wire it next)
            btnAddBlock.setOnClickListener {
                val shape = spinnerBlockShape.selectedItem as BlockShape

                parent.gLView.queueEvent {
                    parent.level.editEngine.addBlockToFriendlyStructure(actor.templateId, shape)

                    parent.mainActivity.runOnUiThread {
                        parent.writeToFile()
                        visibility = GONE
                        parent.visibility = VISIBLE
                    }
                }
            }

            this.visibility = VISIBLE
            this.bringToFront()
        }
        else if (actor is BuildingBlockActor) {
            this.parent.visibility = INVISIBLE
            displayActorType.text = "BuildingBlockActor"

            btnDuplicateBlock.visibility = VISIBLE

            // Show block UI groups
            blockHeaderRow.visibility = VISIBLE
            blockPositionRow.visibility = VISIBLE
            blockYawRow.visibility = VISIBLE
            blockDimensionsRow.visibility = VISIBLE

            // Resolve authoritative template
            val b = actor
            val structureId = b.structure.templateId
            val blockIndex = b.blockIndex

            val st = parent.level.findFriendlyStructureTemplate(structureId)

            if (st == null || blockIndex !in st.blocks.indices) {
                Toast.makeText(context, "Block template not found", Toast.LENGTH_SHORT).show()
                closeButtonClicked()
                return
            }

            val bt = st.blocks[blockIndex]

            // Header: show type
            blockHeaderRow.text = "Block: ${bt.shape}"

            // Populate fields (local coords)
            editBlockX.setText(bt.localBasePos.x.toString(), TextView.BufferType.EDITABLE)
            editBlockY.setText(bt.localBasePos.y.toString(), TextView.BufferType.EDITABLE)
            editBlockZ.setText(bt.localBasePos.z.toString(), TextView.BufferType.EDITABLE)

            // Yaw shown in degrees for humans
            editBlockYaw.setText(Math.toDegrees(bt.localYaw).toString(), TextView.BufferType.EDITABLE)

            // Dimensions (authoring)
            editDimW.setText(bt.dimensions.x.toString(), TextView.BufferType.EDITABLE)
            editDimD.setText(bt.dimensions.y.toString(), TextView.BufferType.EDITABLE)
            editDimH.setText(bt.dimensions.z.toString(), TextView.BufferType.EDITABLE)

            if (bt.shape == BlockShape.BOX || bt.shape == BlockShape.CYLINDER) {
                blockLandingPadRow.visibility = VISIBLE
                if (bt.landingPadTop) {
                    chkLandingPad.isChecked = true
                    waitingAreaRow.visibility = VISIBLE

                    if (bt.civilianSpec != null) {
                        editCivilianInitialCount.setText(bt.civilianSpec!!.initialCount.toString(), TextView.BufferType.EDITABLE)
                        editWAPosX.setText(bt.civilianSpec!!.waitingAreaLocal.x.toString(), TextView.BufferType.EDITABLE)
                        editWAPosY.setText(bt.civilianSpec!!.waitingAreaLocal.y.toString(), TextView.BufferType.EDITABLE)
                        editWAPosZ.setText(bt.civilianSpec!!.waitingAreaLocal.z.toString(), TextView.BufferType.EDITABLE)
                    }
                    else {
                        editCivilianInitialCount.setText(0.toString(), TextView.BufferType.EDITABLE)
                        editWAPosX.setText(0f.toString(), TextView.BufferType.EDITABLE)
                        editWAPosY.setText(0f.toString(), TextView.BufferType.EDITABLE)
                        editWAPosZ.setText(0f.toString(), TextView.BufferType.EDITABLE)
                    }
                }
                else {
                    chkLandingPad.isChecked = false
                    waitingAreaRow.visibility = GONE
                }
            }

            this.visibility = VISIBLE
            this.bringToFront()
        }
        ignoreUiCallbacks = false
    }

    fun hideAllNonCommonRows() {
        flyingRadiusRow.visibility = GONE
        antiClockwiseRow.visibility = GONE
        boundsRadiusXRow.visibility = GONE
        boundsRadiusYRow.visibility = GONE
        boundsRadiusZRow.visibility = GONE
        structureBaseZRow.visibility = GONE
        structureAddBlockRow.visibility = GONE
        flyingRadiusRow.visibility = GONE
        antiClockwiseRow.visibility = GONE
        boundsRadiusXRow.visibility = GONE
        boundsRadiusYRow.visibility = GONE
        boundsRadiusZRow.visibility = GONE

        structureBaseZRow.visibility = GONE
        structureYawRow.visibility = GONE
        structureAddBlockRow.visibility = GONE
        structureTimeRow.visibility = GONE

        blockHeaderRow.visibility = GONE
        blockPositionRow.visibility = GONE
        blockYawRow.visibility = GONE
        blockDimensionsRow.visibility = GONE
        blockLandingPadRow.visibility = GONE
    }

    fun applyButtonClicked() {

        if (actor is FriendlyStructureActor) {
            applyStructureEditsFromUi()
            toast("Structure changes applied")
            return
        }

        if (actor is BuildingBlockActor) {
            applyBlockEditsFromUi()
            toast("Block changes applied")
            return
        }


        if (actor is FlyingEnemyActor) {
            val flyingRadius = editFlyingRadius.text.toString().toFloatOrNull()
            if (flyingRadius == null) {
                Toast.makeText(context, "Invalid flying radius", Toast.LENGTH_SHORT).show()
                return
            }
            val anticlockwise = btnActorAnticlockwise.isChecked

            parent.gLView.queueEvent {
                val a = actor as FlyingEnemyActor
                a.flyingRadius = flyingRadius
                a.antiClockWise = anticlockwise

                parent.level.writeGameMapStateToLevel()
                // if writeToFile touches UI, keep it off GL thread
            }

            // File IO should usually be off GL thread; do it on UI or a background thread
            parent.mainActivity.runOnUiThread {
                parent.writeToFile()
            }

            visibility = GONE
            return
        }

        if (actor is AdvFlyingEnemyActor) {
            val x = editRadiusX.text.toString().toFloatOrNull()
            val y = editRadiusY.text.toString().toFloatOrNull()
            val z = editRadiusZ.text.toString().toFloatOrNull()
            if (x == null || y == null || z == null) {
                Toast.makeText(context, "Invalid bounds radius", Toast.LENGTH_SHORT).show()
                return
            }

            parent.gLView.queueEvent {
                val a = actor as AdvFlyingEnemyActor
                a.aabbHalfX = x
                a.aabbHalfY = y
                a.aabbHalfZ = z
                parent.level.writeGameMapStateToLevel()
            }

            parent.writeToFile()
            visibility = GONE
        }
    }

    private fun applyStructureEditsFromUi() {
        if (actor !is FriendlyStructureActor) return
        val s = actor as FriendlyStructureActor
        val templateId = s.templateId

        val z = editStructureBaseZ.text.toString().toFloatOrNull() ?: return toast("Invalid Z")
        val yawDeg = editStructureYaw.text.toString().toFloatOrNull() ?: return toast("Invalid yaw")
        val destructSeconds: Int?
        if (editStructureTimeSeconds.text.toString() == "") {
            destructSeconds = null
        }
        else {
            destructSeconds = editStructureTimeSeconds.text.toString().toIntOrNull() ?: return toast("Invalid destruct seconds")
        }

        // Debounce: coalesce multiple taps into one rebuild per frame-ish
        if (pendingApply) return
        pendingApply = true

        parent.gLView.queueEvent {
            pendingApply = false

            val st = parent.level.findFriendlyStructureTemplate(templateId) ?: return@queueEvent
            val newSt = st.copy()
            newSt.position.z = z
            newSt.yaw = Math.toRadians(yawDeg.toDouble())
            newSt.destructSeconds = destructSeconds

            parent.level.editEngine.rebuildFriendlyStructure(templateId, newSt, true)

            parent.mainActivity.runOnUiThread {
                parent.writeToFile() // now atomic/safe
            }
        }
        updatePositionText()
    }
    private fun applyBlockEditsFromUi() {
        if (actor !is BuildingBlockActor) return
        val b = actor as BuildingBlockActor
        val structureId = b.structure.templateId
        val blockIndex = b.blockIndex

        val x = editBlockX.text.toString().toFloatOrNull() ?: return toast("Invalid X")
        val y = editBlockY.text.toString().toFloatOrNull() ?: return toast("Invalid Y")
        val z = editBlockZ.text.toString().toFloatOrNull() ?: return toast("Invalid Z")
        val yawDeg = editBlockYaw.text.toString().toFloatOrNull() ?: return toast("Invalid yaw")
        val w = editDimW.text.toString().toFloatOrNull() ?: return toast("Invalid W")
        val d = editDimD.text.toString().toFloatOrNull() ?: return toast("Invalid D")
        val h = editDimH.text.toString().toFloatOrNull() ?: return toast("Invalid H")

        var cs: CivilianSpec? = null
        if (chkLandingPad.isChecked) {
            val cc = editCivilianInitialCount.text.toString().toIntOrNull() ?: return toast("Invalid Initial Civilian Count")
            val wax = editWAPosX.text.toString().toFloatOrNull() ?: return toast("Invalid Waiting Area Pos X")
            val way = editWAPosY.text.toString().toFloatOrNull() ?: return toast("Invalid Waiting Area Pos Y")
            val waz = editWAPosZ.text.toString().toFloatOrNull() ?: return toast("Invalid Waiting Area Pos Z")

            cs = CivilianSpec(cc, Vec3(wax, way, waz))

        }

        if (w <= 0f || d <= 0f || h <= 0f) return toast("Dims must be > 0")
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return toast("Invalid position")

        // Debounce: coalesce multiple taps into one rebuild per frame-ish
        if (pendingApply) return
        pendingApply = true

        parent.gLView.queueEvent {
            pendingApply = false

            val st = parent.level.findFriendlyStructureTemplate(structureId) ?: return@queueEvent
            if (blockIndex !in st.blocks.indices) return@queueEvent

            val oldBlock = st.blocks[blockIndex]
            val newBlock = oldBlock.copy(
                localBasePos = Vec3(x, y, z),
                localYaw = Math.toRadians(yawDeg.toDouble()),
                dimensions = Vec3(w, d, h),
                landingPadTop = chkLandingPad.isChecked,
                civilianSpec = cs,
            )

            val newSt = st.copy(blocks = st.blocks.toMutableList().apply { this[blockIndex] = newBlock })

            parent.level.editEngine.rebuildFriendlyStructure(structureId, newSt, false, reselectBlockIndex = blockIndex)

            parent.mainActivity.runOnUiThread {
                parent.writeToFile() // now atomic/safe
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun nudge(field: EditText, delta: Float) {
        val v = field.text.toString().toFloatOrNull() ?: 0f
        val nv = v + delta
        field.setText(nv.toString())
    }

    private fun nudgeInt(field: EditText, delta: Int) {
        val v = field.text.toString().toIntOrNull() ?: 0
        val nv = v + delta
        field.setText(nv.toString())
    }

    fun closeButtonClicked() {
        this.parent.visibility = VISIBLE
        visibility = GONE
    }
}
