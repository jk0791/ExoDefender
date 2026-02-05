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
    var mapSpinner: Spinner
    var typeSpinner: Spinner
    val levelTypes = listOf(
        Level.LevelType.DEVELOPMENT.name,
        Level.LevelType.TRAINING.name,
        Level.LevelType.MILKRUN.name,
        Level.LevelType.MISSION.name,
        )
    val saveButton: Button
    val closeCancelButton: Button

    val hostnameLabel: TextView
    val uploadLevelButton: Button
    val networkProgress: ProgressBar

    var loadingView = false
    private var typeSpinnerChangedOnce = false

    var textWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            // this function is called before text is edited
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (!loadingView) {
                writeToFile()
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

        mapSpinner = findViewById(R.id.mapSpinner)
        typeSpinner = findViewById(R.id.typeSpinner)

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?, position: Int, id: Long
            ) {
                if (typeSpinnerChangedOnce) {
                    typeSpinnerChanged(levelTypes[position])
                }
                else {
                    typeSpinnerChangedOnce = true
                }
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
    }

    fun load(levelManager: LevelManager, level: Level?) {

        loadingView = true
        typeSpinnerChangedOnce = false

        hostnameLabel.text = getHostServer(mainActivity)

        this.levelManager = levelManager
        this.level = level

        // load map id spinner
        val maps = mutableListOf<Int>()
        for (mapFile in levelManager.worldManager.mapFiles) {
            maps.add(mapFile.second)
        }
        val mapsArrayAdapter = ArrayAdapter(context,  R.layout.settings_spinner_item, maps)
        mapSpinner.adapter = mapsArrayAdapter

        // load map id spinner
        val typesArrayAdapter = ArrayAdapter(context,  R.layout.settings_spinner_item, levelTypes)
        typeSpinner.adapter = typesArrayAdapter

        if (level != null) {
            saveButton.visibility = GONE
            closeCancelButton.text = "Close"
            displayId.text = level.id.toString()
            editName.setText(level.name, TextView.BufferType.EDITABLE)
            mapSpinner.setSelection(maps.indexOf(level.world.mapId))
            mapSpinner.isEnabled = false
            typeSpinner.setSelection(levelTypes.indexOf(level.type.name))
            typeSpinner.isEnabled = true
            editOrder.setText(level.order.toString(), TextView.BufferType.EDITABLE)
            uploadLevelButton.isEnabled = true
            editName.addTextChangedListener(textWatcher)
            editOrder.addTextChangedListener(textWatcher)

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
            editOrder.setText("1", TextView.BufferType.EDITABLE)
        }

        updateCampaignCode()
        loadingView = false
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

            val levelType = levelManager.getLevelTypeFromString(typeSpinner.selectedItem as String)

            if (levelType != null && newOrder != null && levelManager.createBlankLevel(newLevelId, editName.text.toString(), newOrder, mapId)) {
                levelManager.loadLevelsFromInternalStorage()
                mainActivity.closeLevelBuilderMetadata()
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

            mainActivity.log.printout("Sending levels update to server $displayIds...")
            Thread({Networker(this, getHostServer(mainActivity)).upsertLevels(levels) }).start()
        }



    }

    fun writeToFile(): Boolean {
        if (level != null) {
            level!!.name = editName.text.toString()

            try {
                level!!.order = editOrder.text.toString().toInt()
            }
            catch(e: Exception) {
                Toast.makeText(context, "Invalid order number", Toast.LENGTH_SHORT).show()
                return false
            }

            mainActivity.levelManager.writeLevelfile(level!!)
            levelManager.loadLevelsFromInternalStorage()
        }
        return true
    }


    override fun handleNetworkMessage(msg: Message) {
        when (msg.what) {
            NetworkResponse.UPSERT_LEVELS.value -> {
                val upsertLevelsResponse = msg.obj as Networker.UpsertLevelsResponse
                val message = "Levels inserted: ${upsertLevelsResponse.insertCount}; updated: ${upsertLevelsResponse.updateCount}; failed: ${upsertLevelsResponse.failureCount}"
                mainActivity.log.printout(message)
                Toast.makeText(mainActivity, message, Toast.LENGTH_SHORT).show()
            }
            -1, -2, -3 -> {
                mainActivity.log.printout("Server Error: [${msg.what}] ${msg.obj}")
            }
            -4 -> {
                mainActivity.log.printout("Network error occured: [${msg.what}] ${msg.obj}")
            }
        }
        networkProgress.visibility = GONE
    }
}
