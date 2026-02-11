package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.jimjo.exodefender.ServerConfig.getHostServer

class AdminView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), NetworkResponseReceiver {

    val mainActivity = context as MainActivity
    var currentCampaignSpinner: Spinner
    private var currentCampaignSpinnerChangedOnce = false

    private var activityIdTxt: EditText
    private var flightLogEntryIdTxt: EditText
    private var replayActivityButton: Button
    private var downloadFlightLogButton: Button
    val networkProgress: ProgressBar

    val closeButton: Button

    var loadingView = false

    var textWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            // this function is called before text is edited
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (!loadingView) {
                // TODO
            }
        }
        override fun afterTextChanged(s: Editable) {}
    }


    init {

        inflate(context, R.layout.admin, this)


        currentCampaignSpinner = findViewById(R.id.currentCampaignCode)
        currentCampaignSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?, position: Int, id: Long
            ) {
                if (currentCampaignSpinnerChangedOnce) {
                    val newCurrentCampaignCode = currentCampaignSpinner.selectedItem as String

                    mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                        putString(mainActivity.ADMIN_CURRENT_CAMPAIGN_CODE, newCurrentCampaignCode)
                    }
                }
                else {
                    currentCampaignSpinnerChangedOnce = true
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // write code to perform some action
                println("nothing selected")
            }
        }

        networkProgress = findViewById(R.id.networkProgress)
        networkProgress.visibility = GONE

        findViewById<Button>(R.id.btnResetUser).apply {
            setOnClickListener {
                showConfirm(
                    context = context,
                    title = "Reset User?",
                    message = "This will set userid and callsign to null.",
                ) {
                    mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                        remove(USERID_KEY)
                        remove(CALLSIGN_KEY)
                        remove(mainActivity.MANDATORY_TRAINING_COMPLETED)
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnClearMandTrngCompFlag).apply {
            setOnClickListener {
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    remove(mainActivity.MANDATORY_TRAINING_COMPLETED)
                }
            }
        }


        findViewById<Button>(R.id.btnFillMandTrngCompFlag).apply {
            setOnClickListener {
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putBoolean(mainActivity.MANDATORY_TRAINING_COMPLETED, true)
                }
            }
        }


        findViewById<Button>(R.id.btnClearStartupNoticeAcceptance).apply {
            setOnClickListener {
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    remove(mainActivity.STARTUP_NOTICE_ACCEPTED)
                }
            }
        }

        findViewById<Button>(R.id.btnViewLog).apply {
            setOnClickListener { mainActivity.showAdminLogView() }
        }

        activityIdTxt = this.findViewById(R.id.flightActivityIdTxt)
        flightLogEntryIdTxt = this.findViewById(R.id.flightLogEntryIdTxt)

        replayActivityButton = this.findViewById(R.id.replayActivityButton)
        replayActivityButton.setOnClickListener({
            networkProgress.visibility = VISIBLE
            replayActivityButton.visibility = GONE
            getActivityFlightLog()
        })

        downloadFlightLogButton = this.findViewById(R.id.downloadFlightLogButton)
        downloadFlightLogButton.setOnClickListener({
            networkProgress.visibility = VISIBLE
            replayActivityButton.visibility = GONE
            downloadFlightLog()
        })

        findViewById<Button>(R.id.btnLevelEditor).apply {
            setOnClickListener({ mainActivity.openLevelEditorView() })
        }


        val unlockAllLevelsSwitch = this.findViewById(R.id.unlockAllLevelsSwitch) as Switch
        unlockAllLevelsSwitch.isChecked = mainActivity.unlockAllLevels
        unlockAllLevelsSwitch.setOnCheckedChangeListener { _, isChecked ->
            mainActivity.unlockAllLevels = isChecked
            mainActivity.refreshCurrentLevelsView()
        }

        findViewById<Button>(R.id.btnResetAllLevels).apply {
            setOnClickListener {
                showConfirm(
                    context = context,
                    title = "Reset Progress?",
                    message = "This will erase all saved data.",
                ) {
                    mainActivity.levelManager.levelsProgress.resetAll()
                    mainActivity.refreshCurrentLevelsView()
                }
            }
        }

        findViewById<Button>(R.id.btnSyncLatestLevels).apply {
            setOnClickListener {
                showConfirm(
                    context = context,
                    title = "Sync Latest Levels?",
                    message = "This will overwrite all levels (including in dev) with what's on the server.",
                ) {
                    mainActivity.levelManager.getLatestSyncManifest(true)
                }
            }
        }

        findViewById<Button>(R.id.btnCallMaybeAppReview).apply {
            setOnClickListener {
                mainActivity.inAppReview.maybeAskForReview(4, true, "trigger=admin_button", true)
            }
        }


        closeButton = this.findViewById<Button>(R.id.closeAdminButton).apply {
            setOnClickListener({ mainActivity.closeAdminView() })
        }
    }

    fun load() {

        loadingView = true
        currentCampaignSpinnerChangedOnce = false

        // load current campaign code spinner
        val campaignCodes = mutableListOf<String>()
        for (c1 in "ABCDEFGHIJ") {
            for (c2 in "12345"){
                campaignCodes.add(c1.toString() + c2.toString())
            }
        }
        val campaignCodesArrayAdapter = ArrayAdapter(context,  R.layout.settings_spinner_item, campaignCodes)
        currentCampaignSpinner.adapter = campaignCodesArrayAdapter

        val preferences = mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        val savedCurrentCampaignCode = preferences.getString(mainActivity.ADMIN_CURRENT_CAMPAIGN_CODE, "")

        val indexOfSavedCampaignCode = campaignCodes.indexOf(savedCurrentCampaignCode)
        if (indexOfSavedCampaignCode != -1) {
            currentCampaignSpinner.setSelection(indexOfSavedCampaignCode)
        }
        else {
            currentCampaignSpinner.setSelection(campaignCodes.lastIndex)
        }

        networkProgress.visibility = GONE
        replayActivityButton.visibility = VISIBLE

        loadingView = false
    }

    fun getActivityFlightLog() {
        try {
            val activityId = activityIdTxt.text.toString().toInt()
            Thread({ Networker(this, getHostServer(mainActivity)).getActivityFlightLog(activityId) }).start()
        }
        catch(_ : Exception) {}

    }
    fun downloadFlightLog() {
        try {
            val entryId = flightLogEntryIdTxt.text.toString().toInt()
            Thread({ Networker(this, getHostServer(mainActivity)).downloadFlightLog(entryId) }).start()
        }
        catch(_ : Exception) {}

    }
    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        onYes: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    override fun handleNetworkMessage(msg: Message) {

        when (msg.what) {
//            NetworkResponse.GET_CALLSIGN.value -> {
//                val responseText = msg.obj as String
//                val callsign = responseText
//                updateOverrideUser(callsign)
//
//            }
            NetworkResponse.GET_ACTIVITY_FLIGHT_LOG.value -> {
                val response = msg.obj as Networker.ActivityFlightLogResponse
                val flightLog = FlightLog()
                val displayCallSign: String?
                if (response.callSign != "") {
                    displayCallSign = response.callSign
                }
                else {
                    displayCallSign = response.userId.toString()
                }
                if (flightLog.parse(response.flightLog)) {
                    mainActivity.hideKeyboard(this)
                    mainActivity.closeAdminView()
                    mainActivity.closeSettings()
                    mainActivity.replayDownloadedFlightLog(flightLog, displayCallSign, true)
                } else {
                    mainActivity.adminLogView.printout("Error: could not parse flight record data")
                }
            }

            NetworkResponse.DOWNLOAD_FLIGHT_LOG.value -> {
                val response = msg.obj as Networker.DownFlightLogResponse
                val flightLog = FlightLog()
                if (flightLog.parse(response.flightLog)) {
                    mainActivity.hideKeyboard(this)
                    mainActivity.closeAdminView()
                    mainActivity.closeSettings()
                    mainActivity.replayDownloadedFlightLog(flightLog, null, true)
                } else {
                    mainActivity.adminLogView.printout("Error: could not parse flight record data")
                }
            }

            -1, -2, -3 -> {
                mainActivity.adminLogView.printout("Error: [${msg.what}] ${msg.obj}")
                Toast.makeText(context, "Server error", Toast.LENGTH_SHORT).show()
            }
            -4 -> {
                mainActivity.adminLogView.printout("Error: [${msg.what}] ${msg.obj}")
                Toast.makeText(context, "Network error", Toast.LENGTH_SHORT).show()
            }
        }
        networkProgress.visibility = GONE
        replayActivityButton.visibility = VISIBLE

    }

}
