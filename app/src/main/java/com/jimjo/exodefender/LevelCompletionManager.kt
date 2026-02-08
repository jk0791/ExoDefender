package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import com.jimjo.exodefender.ServerConfig.getHostServer
import kotlinx.serialization.json.Json

class LevelCompletionManager(context: Context): NetworkResponseReceiver {
    val mainActivity = context as MainActivity

    private var copyLastFlightLogToLevelId: Int? = null
    fun completeLevel(level: Level, flightLog: FlightLog, replayMode: Boolean) {

        if (!replayMode) {

            // determine activity code
            var activityCode: ActivityCode? = null
            if (flightLog.completionOutcome == CompletionOutcome.SUCCESS) {
                activityCode = ActivityCode.LEVEL_SUCCESS
            } else if (
                flightLog.completionOutcome == CompletionOutcome.FAILED_DESTROYED ||
                flightLog.completionOutcome == CompletionOutcome.FAILED_ZERO_FRIENDLIES
            ) {
                activityCode = ActivityCode.LEVEL_FAIL
            }

            if (activityCode != null) {

                // determine if flight record data should be logged with activity
                var flightLogToSend: FlightLog? = null
                if (
                    (level.type == Level.LevelType.TRAINING && mainActivity.globalSettings.sendAllTrainingFlightRecords) ||
                    ((level.type == Level.LevelType.MILKRUN || level.type == Level.LevelType.MISSION) && mainActivity.globalSettings.sendAllMissionFlightRecords)
                ) {
                    flightLogToSend = flightLog
                }

                // send to server
                if (mainActivity.userId != null) {
                    mainActivity.adminLogView.printout("Recording level outcome to server " + if (flightLogToSend == null) "(no flight log)" else "(with flight log)")
                    Thread({
                        Networker(mainActivity, getHostServer(mainActivity)).logActivity(
                            mainActivity.userId!!,
                            activityCode,
                            level.id,
                            null,
                            flightLogToSend
                        )
                    }).start()
                }
            }
        }

        // process completion
        if (level.type == Level.LevelType.TRAINING) {

            mainActivity.showTrainingOutcomeView()

        } else {

            // process score

            val scoreBreakdown = ScoreCalculatorV1.score(flightLog)
            var firstCompletion = false
            var previousBestScore: Int? = null

            if (!replayMode) {

                if (flightLog.completionOutcome == CompletionOutcome.SUCCESS) {
                    val result = mainActivity.levelManager.levelsProgress.registerLevelResult(level.id, scoreBreakdown.total)
                    previousBestScore = result.previousBestScore

                    if (result.newBestScore) {
                        copyLastFlightLogToLevelId = level.id
                    }

                    if (result.firstCompletion) {
                        firstCompletion = true
                        val nextLevelId = mainActivity.levelManager.getHighestUnlockedLevelId(level.type)
                        mainActivity.levelsView.loadLevels(level.type, nextLevelId)

                        // in-app review request
                        if (level.type == Level.LevelType.MISSION) {
                            mainActivity.inAppReview.maybeAskForReview(level.index, true)
                        }
                    }

                    // submit log mission score
                    if (level.type == Level.LevelType.MISSION && mainActivity.userId != null) {

                        // You want stable idempotency keys. Best is to store this in FlightLog.
                        // For now, generate it here.
                        val runId = flightLog.runId
                        val friendliesStart = flightLog.friendliesStart
                        val friendliesRemaining = flightLog.friendliesRemaining
                        val enemiesStart = flightLog.enemiesStart
                        val enemiesDestroyed = flightLog.enemiesDestroyed
                        val shotsFired = flightLog.shotsFired
                        val shotsHit = flightLog.shotsHit
                        val healthRemaining = flightLog.healthRemaining
                        val enemyThreatSum = flightLog.enemyThreatSum
                        val flightTimeMs = flightLog.flightTimeMs

                        // Breakdown string (optional). Keep it <= 2000 chars.
                        // If you already have score.breakdown available, stringify it here.
                        val json = Json {
                            encodeDefaults = false
                            explicitNulls = false
                            ignoreUnknownKeys = true
                        }
                        val breakdownJson = json.encodeToString(scoreBreakdown)

                        mainActivity.adminLogView.printout("Submitting mission score to server: levelId=${level.id} score=${scoreBreakdown.total}")

                        Thread({
                            Networker(this, getHostServer(mainActivity)).submitMissionScore(
                                userId = mainActivity.userId!!,
                                levelId = level.id,
                                runId = runId,
                                clientVersionCode = mainActivity.appVersionCode.toInt(),   // or BuildConfig.VERSION_CODE if you prefer
                                completionOutcome = flightLog.completionOutcome.value, // ensure this is 1 for SUCCESS
                                scoreVersion = 1,
                                scoreTotal = scoreBreakdown.total,
                                flightTimeMs = flightTimeMs,
                                friendliesStart = friendliesStart,
                                friendliesRemaining = friendliesRemaining,
                                enemiesStart = enemiesStart,
                                enemiesDestroyed = enemiesDestroyed,
                                shotsFired = shotsFired,
                                shotsHit = shotsHit,
                                healthRemaining = healthRemaining,
                                enemyThreatSum = enemyThreatSum,
                                breakdown = breakdownJson
                            )
                        }).start()
                    }
                }
            }

            val bestLog = if (firstCompletion) null else mainActivity.flightLogManager.readBestSuccessfulLog(level.id)
            val camapaignCode = if (level.type == Level.LevelType.MISSION) level.campaignCode + "-" else ""
            val nextLevel = mainActivity.levelManager.getNextLevel(level)

            val model = LevelSummaryModel(
                levelId = level.id,
                levelTitle = camapaignCode + "${level.index + 1}: ${level.name}",
                isLevelTypeScored = (level.type == Level.LevelType.MISSION),
                canPlayNextLevel = nextLevel != null && mainActivity.levelManager.checkIfLevelUnlocked(nextLevel),
                nextLevelType = nextLevel?.type,
                isLastMilkrun = level == mainActivity.levelManager.milkruns.lastOrNull(),
                score = scoreBreakdown,
                previousBestScore = previousBestScore,
                previousBestLog = bestLog
            )

            mainActivity.showMissionSummaryAfterMission(flightLog, replayMode, model)

        }
    }

    fun onLastFlightLogSaved() {
        if (copyLastFlightLogToLevelId != null) {

            mainActivity.flightLogManager.copyLastFlightLogAsBest(copyLastFlightLogToLevelId!!)
            mainActivity.adminLogView.printout("New best flight log saved")

            copyLastFlightLogToLevelId = null
        }
    }

    override fun handleNetworkMessage(msg: Message) {

        when (msg.what) {
            NetworkResponse.SUBMIT_MISSION_SCORE.value -> {
                val response = msg.obj as Networker.SubmitMissionScoreResponse
                mainActivity.adminLogView.printout("[accepted=${response.accepted};duplicateRun=${response.duplicateRun};rejectReason=${response.rejectReason};submissionId=${response.submissionId};newPersonalBest=${response.newPersonalBest};bestScoreTotal=${response.bestScoreTotal};]")
            }

            -1, -2, -3 -> {
                mainActivity.adminLogView.printout("Server Error: [${msg.what}] ${msg.obj}")
            }
            -4 -> {
                mainActivity.adminLogView.printout("Network error occured: [${msg.what}] ${msg.obj}")
            }

        }
    }

}