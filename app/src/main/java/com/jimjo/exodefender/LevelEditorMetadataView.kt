package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.jimjo.exodefender.ServerConfig.getHostServer
import android.os.Handler
import android.os.Looper

class LevelEditorMetadataView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), NetworkResponseReceiver {

    val mainActivity = context as MainActivity
    var level: Level? = null
    lateinit var levelManager: LevelManager

    val displayId: TextView
    val displayCampaignCode: TextView

    private var newLevelId = 0
    val editName: EditText
    val editOrder: EditText
    val editDifficultyWeight: EditText

    private val uiHandler = Handler(Looper.getMainLooper())
    private val WRITE_DEBOUNCE_MS = 250L

    var mapSpinner: Spinner
    var typeSpinner: Spinner
    var objectiveTypeSpinner: Spinner

    private var suppressNextTypeEvent = false
    private var suppressNextObjectiveEvent = false

    private var watchersAttached = false

    val levelTypes = listOf(
        Level.LevelType.DEVELOPMENT.name,
        Level.LevelType.TRAINING.name,
        Level.LevelType.MILKRUN.name,
        Level.LevelType.MISSION.name,
        )

    val objectiveTypeNames = Level.ObjectiveType.entries.map { it.name }

    val saveButton: Button
    val closeCancelButton: Button

    val hostnameLabel: TextView
    val uploadLevelButton: Button
    val networkProgress: ProgressBar
    var ignoreUiCallbacks = true

    private val writeRunnable = Runnable {
        if (!ignoreUiCallbacks) writeToFile()
    }

    private fun scheduleWriteToFile() {
        uiHandler.removeCallbacks(writeRunnable)
        uiHandler.postDelayed(writeRunnable, WRITE_DEBOUNCE_MS)
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (!ignoreUiCallbacks) {
                scheduleWriteToFile()
            }
        }

        override fun afterTextChanged(s: Editable) {}
    }


    init {

        inflate(context, R.layout.level_edit_metadata, this)

        displayId = this.findViewById(R.id.displayLevelId)
        displayCampaignCode = this.findViewById(R.id.displayCampaignCode)

        editName = this.findViewById(R.id.editLevelName)

        editOrder = this.findViewById(R.id.editLevelOrder)
        editDifficultyWeight = this.findViewById(R.id.editDifficultyWeight)

        typeSpinner = findViewById(R.id.typeSpinner)
        objectiveTypeSpinner = findViewById(R.id.objectiveSpinner)
        mapSpinner = findViewById(R.id.mapSpinner)

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?, position: Int, id: Long
            ) {
                if (ignoreUiCallbacks || suppressNextTypeEvent) {
                    suppressNextTypeEvent = false
                    return
                }
                typeSpinnerChanged(levelTypes[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // write code to perform some action
                println("nothing selected")
            }
        }

        objectiveTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?, position: Int, id: Long
            ) {
                if (ignoreUiCallbacks || suppressNextObjectiveEvent) {
                    suppressNextObjectiveEvent = false
                    return
                }
                objectiveTypeSpinnerChanged(objectiveTypeNames[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // write code to perform some action
                println("nothing selected")
            }
        }

        hostnameLabel = this.findViewById(R.id.hostNameLabel)

        uploadLevelButton = this.findViewById<Button>(R.id.btnUploadLevel).apply{
            setOnClickListener({ uploadLevel() })
        }
        networkProgress = findViewById(R.id.networkProgress)
        networkProgress.visibility = GONE


        saveButton = this.findViewById(R.id.saveCreateLevelButton)
        saveButton.setOnClickListener({ createNewLevel() })

        closeCancelButton = this.findViewById(R.id.closeCencelCreateLevelButton)
        closeCancelButton.setOnClickListener({ mainActivity.closeLevelBuilderMetadata() })

        attachWatchersOnce()
    }


    private fun attachWatchersOnce() {

        if (watchersAttached) return
        watchersAttached = true

        editName.addTextChangedListener(textWatcher)
        editOrder.addTextChangedListener(textWatcher)
        editDifficultyWeight.addTextChangedListener(textWatcher)
    }

    fun load(levelManager: LevelManager, level: Level?) {

        ignoreUiCallbacks = true


        hostnameLabel.text = getHostServer(mainActivity)

        this.levelManager = levelManager
        this.level = level

        // load level type spinner
        val levelTypesArrayAdapter = ArrayAdapter(context,  R.layout.settings_spinner_item, levelTypes)
        typeSpinner.adapter = levelTypesArrayAdapter

        // load objective type spinner
        val objectiveTypesArrayAdapter = ArrayAdapter(context,  R.layout.settings_spinner_item, objectiveTypeNames)
        objectiveTypeSpinner.adapter = objectiveTypesArrayAdapter

        // load map id spinner
        val maps = mutableListOf<Int>()
        for (mapFile in levelManager.worldManager.mapFiles) {
            maps.add(mapFile.second)
        }
        val mapIdsArrayAdapter = ArrayAdapter(context,  R.layout.settings_spinner_item, maps)
        mapSpinner.adapter = mapIdsArrayAdapter

        if (level != null) {
            saveButton.visibility = GONE
            closeCancelButton.text = "Close"
            displayId.text = level.id.toString()
            editName.setText(level.name, TextView.BufferType.EDITABLE)
            mapSpinner.setSelection(maps.indexOf(level.world.mapId))
            mapSpinner.isEnabled = false

            val typeIndex = levelTypes.indexOf(level.type.name).let { if (it >= 0) it else 0 }
            suppressNextTypeEvent = true
            typeSpinner.setSelection(typeIndex)
            typeSpinner.isEnabled = true

            val objIndex = objectiveTypeNames.indexOf(level.objectiveType.name).let { if (it >= 0) it else 0 }
            suppressNextObjectiveEvent = true
            objectiveTypeSpinner.setSelection(objIndex)

            editOrder.setText(level.order.toString(), TextView.BufferType.EDITABLE)
            editDifficultyWeight.setText(df2.format(level.difficultyWeight), TextView.BufferType.EDITABLE)
            uploadLevelButton.isEnabled = true

//            editName.addTextChangedListener(textWatcher)
//            editOrder.addTextChangedListener(textWatcher)
//            editDifficultyWeight.addTextChangedListener(textWatcher)

        }
        else {
            newLevelId = levelManager.generateNewLevelId()

            saveButton.visibility = VISIBLE
            uploadLevelButton.isEnabled = false
            closeCancelButton.text = "Cancel"
            displayId.text = newLevelId.toString()
            editName.setText("<level name>", TextView.BufferType.EDITABLE)
            mapSpinner.isEnabled = true
            typeSpinner.setSelection(levelTypes.indexOf(Level.LevelType.DEVELOPMENT.name))
            typeSpinner.isEnabled = false
            objectiveTypeSpinner.setSelection(objectiveTypeNames.indexOf(Level.ObjectiveType.UNKNOWN.name))
            editOrder.setText("1", TextView.BufferType.EDITABLE)
            editDifficultyWeight.setText("1.00", TextView.BufferType.EDITABLE)
        }

        updateCampaignCode()
        post { ignoreUiCallbacks = false }
    }

    fun typeSpinnerChanged(newLevelTypeName: String) {
        if (level != null) {
            val levelType = levelManager.getLevelTypeFromString(newLevelTypeName)
            if (levelType != null) {

                level!!.type = levelType
                updateCampaignCode()
                writeToFile()
            }
        }
    }

    fun objectiveTypeSpinnerChanged(newObjectiveTypeName: String) {
        if (level != null) {

            val newObjectiveType = levelManager.getObjectiveFromName(newObjectiveTypeName)
            if (newObjectiveType != null) {

                level!!.objectiveType = newObjectiveType
                updateCampaignCode()
                writeToFile()
            }
        }
    }

    fun updateCampaignCode() {
        if (level != null) {
            if (level!!.type == Level.LevelType.MISSION) {
                if (level!!.campaignCode == null) {
                    level!!.campaignCode = mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getString(
                        mainActivity.ADMIN_CURRENT_CAMPAIGN_CODE, "Z5"
                    )
                }
            }
            else {
                level!!.campaignCode = null
            }

            // update display
            if (level!!.campaignCode != null) {
                displayCampaignCode.text = level!!.campaignCode
            }
            else {
                displayCampaignCode.text = "<null>"
            }
        }
        else {
            displayCampaignCode.text = "<null>"
        }
    }

    fun createNewLevel() {

        val mapId = mapSpinner.selectedItem as Int?
        if (mapId != null) {
            var newOrder: Int? = null
            try {
                newOrder = editOrder.text.toString().toInt()
            }
            catch (e: Exception) {
                Toast.makeText(context, "Invalid order number", Toast.LENGTH_SHORT).show()
            }

            var newDifficultyWeight: Float? = null
            try {
                newDifficultyWeight = editDifficultyWeight.text.toString().toFloat()
            }
            catch (e: Exception) {
                Toast.makeText(context, "Invalid difficulty weight", Toast.LENGTH_SHORT).show()
            }

            val levelType = levelManager.getLevelTypeFromString(typeSpinner.selectedItem as String)
            val objectiveType = levelManager.getObjectiveFromName(objectiveTypeSpinner.selectedItem as String)

            if (levelType != null && objectiveType != null && newOrder != null && newDifficultyWeight != null) {

                if (levelManager.createBlankLevel(newLevelId, editName.text.toString(), newOrder, objectiveType, mapId, newDifficultyWeight)) {
                    levelManager.loadLevelsFromInternalStorage()
                    mainActivity.closeLevelBuilderMetadata()
                }
            }
        }

    }

    fun uploadLevel() {
        if (level != null) {

            networkProgress.visibility = VISIBLE

            var levels = mutableListOf(level!!)
            val displayIds = levels.joinToString(separator = ",") { it.id.toString() }

            // DEBUG: uncomment to upload all levels to server!
//            levels = levelManager.levels

            mainActivity.adminLogView.printout("Sending levels update to server $displayIds...")
            Thread({Networker(this, getHostServer(mainActivity)).upsertLevels(levels) }).start()
        }



    }

    fun writeToFile(): Boolean {
        val lvl = level ?: return false

        lvl.name = editName.text.toString()
        lvl.order = editOrder.text.toString().toIntOrNull()
            ?: run {
                Toast.makeText(context, "Invalid number, defaulting to 0", Toast.LENGTH_SHORT)
                    .show()
                return false
            }
        lvl.difficultyWeight = editDifficultyWeight.text.toString().toFloatOrNull()
            ?: run {
                Toast.makeText(context, "Invalid difficulty weight", Toast.LENGTH_SHORT).show()
                return false
            }

        mainActivity.levelManager.writeLevelfile(lvl)
        levelManager.loadLevelsFromInternalStorage()
        return true
    }


    override fun handleNetworkMessage(msg: Message) {
        when (msg.what) {
            NetworkResponse.UPSERT_LEVELS.value -> {
                val upsertLevelsResponse = msg.obj as Networker.UpsertLevelsResponse
                val message = "Levels inserted: ${upsertLevelsResponse.insertCount}; updated: ${upsertLevelsResponse.updateCount}; failed: ${upsertLevelsResponse.failureCount}"
                mainActivity.adminLogView.printout(message)
                Toast.makeText(mainActivity, message, Toast.LENGTH_SHORT).show()
            }
            -1, -2, -3 -> {
                mainActivity.adminLogView.printout("Server Error: [${msg.what}] ${msg.obj}")
            }
            -4 -> {
                mainActivity.adminLogView.printout("Network error occured: [${msg.what}] ${msg.obj}")
            }
        }
        networkProgress.visibility = GONE
    }
}
