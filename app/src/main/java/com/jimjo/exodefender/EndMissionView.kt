package com.jimjo.exodefender

//import android.content.Context
//import android.util.AttributeSet
//import android.view.View
//import android.widget.Button
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.TableLayout
//import android.widget.TextView
//
//class EndMissionView(context: Context, attrs: AttributeSet? = null) :
//    LinearLayout(context, attrs) {
//
//    val mainActivity = context as MainActivity
//    var flightLog = FlightLog()
//    val outcomeHeading: TextView
//    val failureSummary: TextView
//    var successOutcome = false
//    val scoreCalculator = ScoreCalculator()
//    var replayMode = false
//
//    val playNextMission: Button
//    val retryLevelButton: Button
//    val scoreTable: TableLayout
//    val missionCompletionBonus: TextView
//    val friendliesSavedBonus: TextView
//    val enemiesDestroyedBonus: TextView
//    val skillBonus: TextView
//    val totalScore: TextView
//
//    var nextLevelIndex: Int? = null
//
//    init {
//
//        View.inflate(context, R.layout.end_mission, this)
//
//        outcomeHeading = this.findViewById<Button>(R.id.outcomeHeading)
//        failureSummary = this.findViewById<Button>(R.id.failureSummary)
//
//        scoreTable = this   .findViewById(R.id.scoreTable)
//        missionCompletionBonus = this.findViewById(R.id.missionCompletionBonus)
//        friendliesSavedBonus = this.findViewById(R.id.friendliesSavedBonus)
//        enemiesDestroyedBonus = this.findViewById(R.id.enemiesDestroyedBonus)
//        skillBonus = this.findViewById(R.id.skillBonus)
//        totalScore = this.findViewById(R.id.totalScore)
//
//        val exitHome = this.findViewById<ImageView>(R.id.btnExitHome)
//        exitHome.setOnClickListener({
////            mainActivity.closeEndMission()
//            mainActivity.exitLevel()
//        })
//
//        val replayMission = this.findViewById<ImageView>(R.id.btnReplayMission)
//        replayMission.setOnClickListener({
//            if (replayMode) {
//                mainActivity.resetGame()
//            }
//            else {
//                mainActivity.replayLastFlight()
//            }
//            this.visibility = GONE
//        })
//
//        retryLevelButton = this.findViewById(R.id.btnPrev)
//        retryLevelButton.setOnClickListener({
//            if (!replayMode) {
//                mainActivity.resetGame()
//            }
//            else {
//                mainActivity.openLevelById(flightLog.levelId, false)
//            }
//            this.visibility = GONE
//        })
//
//        playNextMission = this.findViewById<Button>(R.id.btnPlayNextMission)
//        playNextMission.setOnClickListener({
//            nextButtonClicked()
//        })
//    }
//
//    fun nextButtonClicked() {
//        val currentLevel = mainActivity.currentLevel
//
//        if (currentLevel != null) {
//            val nextIndex = mainActivity.levelManager.getNextLevelIndex(currentLevel)
//            if (nextIndex != null) {
//                this.visibility = GONE
//                mainActivity.openLevelByIndex(currentLevel.type, nextIndex)
//            }
//            else {
////                mainActivity.showHomeView()
//                mainActivity.exitLevel()
//            }
//        }
//    }
//
//    fun load(flightLog: FlightLog, replayMode: Boolean, from: Feature) {
//        this.flightLog = flightLog
//        this.replayMode = replayMode
//
//        when (flightLog.completionOutcome) {
//            CompletionOutcome.SUCCESS -> {
//                if (replayMode) {
//                    outcomeHeading.text = "Mission Replay"
//                }
//                else {
//                    outcomeHeading.text = "Mission Accomplished!"
//                }
//                successOutcome = true
//            }
//            CompletionOutcome.FAILED_DESTROYED -> {
//                outcomeHeading.text = "Mission Failed"
//                failureSummary.text = "Your ship was destroyed"
//                successOutcome = false
//            }
//            CompletionOutcome.FAILED_ZERO_FRIENDLIES -> {
//                outcomeHeading.text = "Mission Failed"
//                failureSummary.text = "All your ground forces have been lost"
//                successOutcome = false
//            }
//            else -> outcomeHeading.text = "Unknown outcome"
//        }
//
//        val currentLevel = mainActivity.currentLevel
//
//        if (currentLevel != null) {
//
//            if (successOutcome) {
//                scoreCalculator.calculate(flightLog)
//                missionCompletionBonus.text = scoreCalculator.bonusLevelCompletion.toString()
//                friendliesSavedBonus.text = scoreCalculator.friendliesSavedScore.toString()
//                enemiesDestroyedBonus.text = scoreCalculator.enemiesDestroyedScore.toString()
//                skillBonus.text = scoreCalculator.skillScore.toString()
//                totalScore.text = scoreCalculator.totalScore.toString()
//                scoreTable.visibility = VISIBLE
//                failureSummary.visibility = GONE
//
//                // record first/best score if necessary
//                if (!replayMode) {
//                    val result = mainActivity.levelManager.levelsProgress.registerLevelResult(currentLevel.id, scoreCalculator.totalScore)
//                    // TODO notify user if they beat their own best score (result.newBestScore == true)
//
//                    if (result.firstCompletion) {
//                        val nextLevelId = mainActivity.levelManager.getHighestUnlockedLevelId(currentLevel.type)
//                        mainActivity.levelsView.loadLevels(currentLevel.type, nextLevelId)
//
//                        // in-app review request
//                        if (currentLevel.type == Level.LevelType.MISSION) {
//                            mainActivity.inAppReview.maybeAskForReview(currentLevel.index, true)
//                        }
//                    }
//
//                }
//            } else {
//                failureSummary.visibility = VISIBLE
//                scoreTable.visibility = GONE
//                successOutcome = false
//            }
//
//            // determine what navigation buttons are available
//            if (!replayMode) {
//                if (mainActivity.levelManager.checkIfNextLevelIsUnlocked(currentLevel)) {
//                    playNextMission.visibility = VISIBLE
//                } else {
//                    playNextMission.visibility = INVISIBLE
//                }
//            }
//            else {
//                playNextMission.visibility = INVISIBLE
//                retryLevelButton.visibility = INVISIBLE
//            }
//        }
//    }
//}