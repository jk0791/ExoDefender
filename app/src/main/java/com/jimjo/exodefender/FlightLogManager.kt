package com.jimjo.exodefender

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

interface WriteFileRequester {
    fun notifyWriteFileRequestOutcome(msg: Message)
}

class WriteFileHandler(looper: Looper, val requester: WriteFileRequester): Handler(looper) {
    override fun handleMessage(msg: Message) {
        super.handleMessage(msg)
        requester.notifyWriteFileRequestOutcome(msg)
    }
}
class FlightLogManager(context: Context) {

    val mainActivity = context as MainActivity
    val  flightLogsDirPath = context.getFilesDir().getAbsolutePath().toString() + File.separator + "flightlogs"
    val  savedReplaysDirPath = context.getFilesDir().getAbsolutePath().toString() + File.separator + "savedreplays"
    val lastFlightLogName = "system_last_flight.log"
    val flightLogsDir: File
    val savedReplaysDir: File

    init {
        flightLogsDir = File(flightLogsDirPath)
        if (!flightLogsDir.exists()) {
            flightLogsDir.mkdirs()
        }
        savedReplaysDir = File(savedReplaysDirPath)
        if (!savedReplaysDir.exists()) {
            savedReplaysDir.mkdirs()
        }
    }

    fun getUniqueFilename(flightLog: FlightLog): String {
        val levelId = flightLog.levelId
        val levelName = mainActivity.levelManager.levelIdLookup[levelId]
        val currentDateTime = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'-'HHmmss")
        val formattedString = currentDateTime.format(formatter)

        if (levelName != null) {
            val filename = levelName.name.replace(" ", "_") + "-" + formattedString + ".log"
            return filename
        }
        else {
            return "unnamed- " + formattedString + ".log"
        }
    }

    fun copyLastFlightLogAsBest(levelId: Int) {
        copyFileInInternalStorage(mainActivity, lastFlightLogName, "$levelId.log", flightLogsDir)
    }

    fun writeLastFlightLogfile(flightLog: FlightLog, requester: WriteFileRequester? = null) {
        writeFlightLogfile(flightLog, flightLogsDir, lastFlightLogName, requester)
    }

    fun writeSavedReplayFlightLogfile(flightLog: FlightLog, filename: String, requester: WriteFileRequester? = null) {
        writeFlightLogfile(flightLog, savedReplaysDir, filename, requester)
    }
    fun writeFlightLogfile(flightLog: FlightLog, dir: File, filename: String, requester: WriteFileRequester? = null) {
        // assign an id to this file
        flightLog.createNewId()
        val outputStreamWriter = OutputStreamWriter(FileOutputStream(File(dir, filename)))
        outputStreamWriter.write(flightLog.stringify())
        outputStreamWriter.close()

        if (requester != null) {
            val handler = WriteFileHandler(Looper.getMainLooper(), requester)
            val msg = handler.obtainMessage(0, filename)
            handler.sendMessage(msg)
        }
    }

    fun readLastFlightLogFile(): FlightLog? {
        return readFlightLogFile(lastFlightLogName, flightLogsDir)
    }

    fun readBestSuccessfulLog(levelId: Int): FlightLog? {
        return readFlightLogFile("$levelId.log", flightLogsDir)
    }


    fun readSavedReplayFlightLogFile(flightDataFilename: String): FlightLog? {
        return readFlightLogFile(flightDataFilename, savedReplaysDir)
    }

    fun readFlightLogFile(flightDataFilename: String, dir: File): FlightLog? {
        val flightDataFile = File(dir, flightDataFilename)
        return readFlightLogFile(flightDataFile)
    }

    fun readFlightLogFile(flightDataFile: File): FlightLog? {

        if (flightDataFile.exists()) {
            val input = flightDataFile.readText()
            return readFlightLog(input)
        }
        else return null
    }

    fun readFlightLog(flightData: String): FlightLog? {

        val flightLog = FlightLog()
        if (flightLog.parse(flightData)) {
            flightLog.prepareForPlayback()
            flightLog.cameraTrack = mainActivity.replayManager.readCameraTrackFile(flightLog.cameraTrackFilename())
            return flightLog
        }
        else {
            return null
        }
    }

}