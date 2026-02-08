package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.jimjo.exodefender.ServerConfig.getHostServer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class ReplayManager(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), NetworkResponseReceiver {

    val mainActivity = context as MainActivity

    val cameraTracksDirPath: String
    val cameraTracksDir: File

    val replaysScrollView: ScrollView
    val savedReplaysTable: TableLayout
    val homeButton: ImageView

    val btnRenameFlightLog: Button
    val btnDeleteFlightLog: Button
    val btnUploadFlightLog: Button
    val btnCopyFlightLog: Button
    val networkProgressIndicator: ProgressBar

    var selectedDisplayFilename: String? = null

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

//    val flightLogFilenames = mutableListOf<String>()

    init {
        inflate(context, R.layout.replay_manager_view, this)

        homeButton = findViewById(R.id.btnHome)
        homeButton.setOnClickListener({
            mainActivity.showHomeView()
        })

        replaysScrollView = findViewById(R.id.centerLayout)
        savedReplaysTable = findViewById(R.id.savedReplaysTable)
        btnRenameFlightLog = findViewById<Button>(R.id.btnRenameFlightLog).apply { setOnClickListener { onRenameFlightLog() } }
        btnDeleteFlightLog = findViewById<Button>(R.id.btnDeleteFlightLog).apply { setOnClickListener { onDeleteFlightLog() } }
        btnCopyFlightLog = findViewById<Button>(R.id.copyFlightLogButton).apply { setOnClickListener { onCopyFlightLog() } }
        btnUploadFlightLog = findViewById<Button>(R.id.btnUploadFlightLog).apply { setOnClickListener { onUploadFlightLog() } }

        networkProgressIndicator = findViewById<ProgressBar>(R.id.networkProgressIndicator)

        cameraTracksDirPath = mainActivity.getFilesDir().getAbsolutePath().toString() + File.separator + "cameratracks"

        cameraTracksDir = File(cameraTracksDirPath)
        if (!cameraTracksDir.exists()) {
            cameraTracksDir.mkdirs()
        }
    }

    fun getDisplayFilename(realFilename: String) = if (realFilename.endsWith(".log")) realFilename.substringBeforeLast(".log") else realFilename
    fun getRealFilename(displayFilename: String) = if (!displayFilename.endsWith(".log")) displayFilename + ".log" else displayFilename

    fun writeCameraTrackToFile(cameraTrack: CameraTrack, filename: String) {

        val cameraTrackFile = File(cameraTracksDir, filename)
        val existed = cameraTrackFile.exists()

        cameraTrack.sortEvents()

        FileOutputStream(cameraTrackFile).use { fos ->
            OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                writer.write(cameraTrack.stringify())
            }
        }

        if (!existed && mainActivity.globalSettings.logCameraTrackCreation) {
            mainActivity.logMiscActivity(ActivityCode.CAMERA_TRACK_CREATED, null, "")
        }
    }

    fun readCameraTrackFile(cameraTrackFilename: String): CameraTrack? {
        val cameraTrackFile = File(cameraTracksDir, cameraTrackFilename)

        if (cameraTrackFile.exists()) {
            val data = cameraTrackFile.readText()
            val cameraTrack = Json.decodeFromString(CameraTrack.serializer(), data)
            cameraTrack.sortEvents()
            return cameraTrack

        }
        else return null

    }

    fun copyCameraTrackFile(srcCameraTrackFilename: String, dstCameraTrackFilename: String): Boolean {
        val srcFile = File(cameraTracksDir, srcCameraTrackFilename)
        if (srcFile.exists()) {
            val dstFile = File(cameraTracksDir, dstCameraTrackFilename)
            try {
                // Copy without overwrite
                srcFile.copyTo(dstFile, overwrite = false)
            } catch (e: Exception) {
                mainActivity.adminLogView.printout("ERROR copying camera track: ${e.message}")
                return false
            }
        }
        return true
    }

    fun load() {
        networkProgressIndicator.visibility = GONE
        refreshTable()
        if (mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(mainActivity.ADMIN_MODE, false)) {
            btnUploadFlightLog.visibility = VISIBLE
        }
        else {
            btnUploadFlightLog.visibility = GONE
        }

    }

    fun refreshTable() {

        val scrollY = replaysScrollView.scrollY

        savedReplaysTable.removeAllViews()
        try {
            val files = mainActivity.flightLogManager.savedReplaysDir.listFiles()

            if (files != null) {
                files.sort()
                for (file in files) {
                    if (file.name.endsWith(".log") && file.name != mainActivity.flightLogManager.lastFlightLogName) {
                        addRowToTable(getDisplayFilename(file.name), 25f)
                    }
                }
            }
            replaysScrollView.post { // Use post to ensure it runs after layout
                replaysScrollView.scrollTo(0, scrollY)
            }
        }
        catch (e: Exception) {
            println("ERROR loading levels, " + e.message)
        }

        btnDeleteFlightLog.isEnabled = selectedDisplayFilename != null
        btnRenameFlightLog.isEnabled = selectedDisplayFilename != null
        btnUploadFlightLog.isEnabled = selectedDisplayFilename != null
        btnCopyFlightLog.isEnabled = selectedDisplayFilename != null
    }


    fun addRowToTable(displayFilename: String, textSize: Float) {

        val tableRow = TableRow(context)
        val tableRowLayoutParams = TableLayout.LayoutParams(TableLayout.LayoutParams.WRAP_CONTENT, TableLayout.LayoutParams.WRAP_CONTENT)
        tableRowLayoutParams.setMargins(0, dp(15f).toInt(), 0 ,0)
        tableRow.layoutParams = tableRowLayoutParams

        val selectImageView = ImageView(context)
        selectImageView.setOnClickListener(object : OnClickListener {
            override fun onClick(view: View?) {
                select(displayFilename)
            }
        })
        val selectImageViewParams = TableRow.LayoutParams(dp(40f).toInt(), dp(40f).toInt())
        selectImageViewParams.setMargins(0, 0, dp(30f).toInt(), 0)
        selectImageViewParams.gravity = Gravity.CENTER_VERTICAL
        selectImageView.layoutParams = selectImageViewParams
        if (selectedDisplayFilename == displayFilename) {
            selectImageView.setBackgroundColor(Color.Green.toArgb())
        }
        else {
            selectImageView.setBackgroundColor(Color.DarkGray.toArgb())
        }
        tableRow.addView(selectImageView)

        val filenameTextView = TextView(context)
        filenameTextView.setTextColor(Color.White.toArgb())
        filenameTextView.setOnClickListener(object : OnClickListener {
            override fun onClick(view: View?) {
                mainActivity.replaySavedFlightLog(getRealFilename(filenameTextView.text.toString()))
            }
        })

        filenameTextView.text = displayFilename
        filenameTextView.textSize = textSize
        val filenameTextViewParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        filenameTextViewParams.setMargins(0, 0, 10, 10)
        filenameTextViewParams.gravity = Gravity.CENTER_VERTICAL
        filenameTextView.layoutParams = filenameTextViewParams
        tableRow.addView(filenameTextView)

        savedReplaysTable.addView(tableRow)
    }

    fun select(displayFilename: String){
        if (selectedDisplayFilename != displayFilename) {
            selectedDisplayFilename = displayFilename
        }
        else {
            selectedDisplayFilename = null
        }

        refreshTable()
    }

    fun onRenameFlightLog() {
        if (selectedDisplayFilename != null) {
            showRenameDialog(
                this.context,
                currentDisplayFilename = selectedDisplayFilename!!,
                fileExists = { name -> File(mainActivity.flightLogManager.savedReplaysDir, name).exists() },
                title = "Rename Replay"
            ) { currentRealFilename, newDisplayFilename ->
                renameFile(selectedDisplayFilename!!, newDisplayFilename)
            }
        }
    }

    fun onCopyFlightLog() {
        val srcDisplay = selectedDisplayFilename ?: return

        // We want to copy the .log file in savedReplaysDir
        val srcReal = getRealFilename(srcDisplay)
        val srcFile = File(mainActivity.flightLogManager.savedReplaysDir, srcReal)

        if (!srcFile.exists()) {
            Toast.makeText(context, "Replay not found", Toast.LENGTH_SHORT).show()
            return
        }

        showRenameDialog(
            context = this.context,
            currentDisplayFilename = srcDisplay, // starts with same name; user must change it
            fileExists = { proposedDisplayName ->
                val proposedReal = getRealFilename(proposedDisplayName)
                File(mainActivity.flightLogManager.savedReplaysDir, proposedReal).exists()
            },
            title = "Copy Replay",
            positiveLabel = "Copy",
            onValidatedRename = { _, newDisplayFilename ->
                val dstReal = getRealFilename(newDisplayFilename)

                try {
                    // Copy without overwrite
                    val srcFlightLog = mainActivity.flightLogManager.readFlightLogFile(srcFile)
                    val dstFlightLog = srcFlightLog!!.createCopy()

                    if (copyCameraTrackFile(
                            srcFlightLog.cameraTrackFilename(),
                            dstFlightLog.cameraTrackFilename())
                        )
                    {
                        mainActivity.flightLogManager.writeSavedReplayFlightLogfile(dstFlightLog, dstReal)
                    }

                    selectedDisplayFilename = newDisplayFilename
                    refreshTable()
                    Toast.makeText(context, "Replay copied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    mainActivity.adminLogView.printout("ERROR copying replay: ${e.message}")
                    Toast.makeText(context, "Copy failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun onUploadFlightLog() {
        if (selectedDisplayFilename != null && mainActivity.userId != null) {

            val flightLog = mainActivity.flightLogManager.readSavedReplayFlightLogFile(getRealFilename(selectedDisplayFilename!!))

            if (flightLog == null) {
                mainActivity.adminLogView.printout("ERROR: Unable to find flight log")
                return
            }

            networkProgressIndicator.visibility = VISIBLE
            Thread({
                Networker(
                    this,
                    getHostServer(mainActivity)
                ).uploadFlightLog(mainActivity.userId!!, flightLog)
            }).start()
        }
    }

    fun renameFile(oldDisplayFilename:String, newDisplayFilename: String) {
        println("$oldDisplayFilename -> $newDisplayFilename")
//        selectedDisplayFilename = newName

        val oldFile = File(mainActivity.flightLogManager.savedReplaysDir, getRealFilename(oldDisplayFilename))
        val newFile = File(mainActivity.flightLogManager.savedReplaysDir, getRealFilename(newDisplayFilename))
        val success = oldFile.renameTo(newFile)

        selectedDisplayFilename = newDisplayFilename

        refreshTable()
    }

    fun onDeleteFlightLog() {
        if (selectedDisplayFilename != null) AlertDialog.Builder(context)
            .setTitle("Delete Replay")
            .setMessage("Delete this replay?")
            .setPositiveButton("Delete") { _, _ ->

                val flightLogFilename = getRealFilename(selectedDisplayFilename!!)
                val flightLogFile = File(mainActivity.flightLogManager.savedReplaysDir, flightLogFilename)
                if (flightLogFile.exists()) {

                    val flightLog = mainActivity.flightLogManager.readFlightLogFile(flightLogFile)
                    if (flightLog != null) {
                        val cameraTrackFileName = flightLog.cameraTrackFilename()
                        val cameraTrackFile = File(cameraTracksDir, cameraTrackFileName)
                        if (cameraTrackFile.exists()) cameraTrackFile.delete()
                    }
                    flightLogFile.delete()
                }

                selectedDisplayFilename = null
                refreshTable()

            }
            .setNegativeButton("Cancel", null)
            .show()
    }



    private fun showRenameDialog(
        context: Context,
        currentDisplayFilename: String,
        fileExists: (String) -> Boolean,
        title: String = "Rename File",
        positiveLabel: String = "OK",
        onValidatedRename: (String, String) -> Unit
    ) {
        val input = EditText(context).apply {
            setText(currentDisplayFilename)
            setSelection(currentDisplayFilename.length)
            hint = "Enter new name"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positiveLabel, null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            okButton.setOnClickListener {
                val raw = input.text.toString()

                // Trim ONLY at validation time, not in the input field
                val newRealFilename = raw.trim()

                // 0. Must change the name (useful for "Copy")
                if (newRealFilename == currentDisplayFilename) {
                    input.error = "Please choose a different name"
                    return@setOnClickListener
                }

                // 1. Empty or whitespace-only
                if (newRealFilename.isEmpty()) {
                    input.error = "Name cannot be empty"
                    return@setOnClickListener
                }

                // 2. Invalid filename characters
                val invalidChars = Regex("""[\\/:*?"<>|]""")
                if (invalidChars.containsMatchIn(newRealFilename)) {
                    input.error = "Name contains invalid characters"
                    return@setOnClickListener
                }

                // 3. Leading or trailing whitespace
                if (raw != newRealFilename) {
                    input.error = "Cannot start or end with spaces"
                    return@setOnClickListener
                }

                // 4. Already exists
                if (fileExists(newRealFilename)) {
                    input.error = "A file with this name already exists"
                    return@setOnClickListener
                }

                // Name is valid
                input.error = null
                dialog.dismiss()
                onValidatedRename(currentDisplayFilename, newRealFilename)
            }
        }

        dialog.show()
    }


    fun renameAllFilesRaw() {
        val files = mainActivity.flightLogManager.savedReplaysDir.listFiles()

        if (files != null) {

            val oldFilenames = mutableListOf<String>()

            for (file in files) {
                oldFilenames.add(file.name)
            }

            for (oldFilename in oldFilenames) {
                val newFilename = oldFilename.substringBefore(".") + ".log"
                val oldFile = File(mainActivity.flightLogManager.savedReplaysDir, oldFilename)
                val newFile = File(mainActivity.flightLogManager.savedReplaysDir, newFilename)
                val success = oldFile.renameTo(newFile)
            }
        }
    }

    override fun handleNetworkMessage(msg: Message) {

        networkProgressIndicator.visibility = GONE
        when (msg.what) {
            NetworkResponse.UPLOAD_FLIGHT_LOG.value -> {
                Toast.makeText(context, "Flight log uploaded", Toast.LENGTH_SHORT).show()
            }
            -1, -2, -3 -> {
                mainActivity.adminLogView.printout("Server Error uploading flight log: [${msg.what}] ${msg.obj}")
                Toast.makeText(context, "Server error", Toast.LENGTH_SHORT).show()
            }
            -4 -> {
                mainActivity.adminLogView.printout("Network error occured uploading flight log: [${msg.what}] ${msg.obj}")
                Toast.makeText(context, "Network error", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
