package com.jimjo.exodefender

class GlobalSettings {
    private var rawGlobalSettings: HashMap<String, String> = hashMapOf()
    var sendAllMissionFlightRecords = false
    var sendAllTrainingFlightRecords = false
    var logCameraTrackCreation = false
    var logReplayStarted = false
    var logStoryViewed = false
    var logOptionalTrainingOpened = false
    var logPlayerRankingRequest = false
    var logNoticeboardRequest = false
    var minimumAppVersionCode = -1L
    var appUrl = ""

    fun load(rawSettings: List<Networker.GlobalSetting>) {
        try {
            for (setting in rawSettings) {
                rawGlobalSettings[setting.key] = setting.value
            }
            updateProperites()
        }
        catch(e : Exception) {
            println("ERROR: reading global settings " + e.message)
        }
    }

    private fun updateProperites() {
        try { sendAllMissionFlightRecords = rawGlobalSettings["sendAllMissionFlightRecords"]!!.toBoolean() } catch(_ : Exception) {}
        try { sendAllTrainingFlightRecords = rawGlobalSettings["sendAllTrainingFlightRecords"]!!.toBoolean() } catch(_ : Exception) {}
        try { logCameraTrackCreation = rawGlobalSettings["logCameraTrackCreation"]!!.toBoolean() } catch(_ : Exception) {}
        try { logReplayStarted = rawGlobalSettings["logReplayStarted"]!!.toBoolean() } catch(_ : Exception) {}
        try { logStoryViewed = rawGlobalSettings["logStoryViewed"]!!.toBoolean() } catch(_ : Exception) {}
        try { logOptionalTrainingOpened = rawGlobalSettings["logOptionalTrainingOpened"]!!.toBoolean() } catch(_ : Exception) {}
        try { logPlayerRankingRequest = rawGlobalSettings["logPlayerRankingRequest"]!!.toBoolean() } catch(_ : Exception) {}
        try { logNoticeboardRequest = rawGlobalSettings["logNoticeboardRequest"]!!.toBoolean() } catch(_ : Exception) {}
        try { minimumAppVersionCode = rawGlobalSettings["minimumAppVersionCode"]!!.toLong() } catch(_ : Exception) {}
        try { appUrl = rawGlobalSettings["appUrl"]!! } catch(_ : Exception) {}
    }

    fun displayAll(): String {
        var display = ""

        display += "sendAllMissionFlightRecords = $sendAllMissionFlightRecords \n"
        display += "sendAllTrainingFlightRecords = $sendAllTrainingFlightRecords \n"
        display += "logCameraTrackCreation = $logCameraTrackCreation \n"
        display += "logReplayStarted = $logReplayStarted \n"
        display += "logStoryViewed = $logStoryViewed \n"
        display += "minimumAppVersionCode = $minimumAppVersionCode \n"
        display += "appUrl = $appUrl"

        return display
    }

}