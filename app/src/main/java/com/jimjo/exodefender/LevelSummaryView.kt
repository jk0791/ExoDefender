package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.jimjo.exodefender.ServerConfig.getHostServer


data class LevelSummaryModel(
    val levelId: Int,
    val levelTitle: String,        // "A3-5: Phantasm"
    val isLevelTypeScored: Boolean, // true only for MISSION
    val canPlayNextLevel: Boolean,
    val nextLevelType: Level.LevelType?,
    val isLastMilkrun: Boolean,
    val score: ScoreCalculatorV1.Breakdown?,             // null if level failed
    val previousBestScore: Int?,           // null if never completed successfully
    val previousBestLog: FlightLog?        // optional: for VIEW_BEST_RUN screen
)

class MissionSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs), NetworkResponseReceiver {

    private val mainActivity = context as MainActivity

    private lateinit var txtMissionTitle: TextView
    private lateinit var txtHeading: TextView
    private lateinit var txtFailureReason: TextView

    private lateinit var btnExitSmmary: ImageView
    private lateinit var btnWatchReplay: LinearLayout
    private lateinit var btnViewRankings: LinearLayout

    private lateinit var btnTryAgain: Button
    private lateinit var btnNextLevel: Button
    private lateinit var btnStart: Button

    private lateinit var linkMore: TextView


    // Row views (whatever you implement)
    private lateinit var rowScore: LabelValueRow
    private lateinit var rowDifficulty: LabelValueRow
    private lateinit var rowFriendlies: LabelValueRow
    private lateinit var rowEnemies: LabelValueRow
    private lateinit var rowIntegrity: LabelValueRow
    private lateinit var rowAccuracy: LabelValueRow
    private lateinit var rowTime: LabelValueRow

    private lateinit var rowPbValue: LabelValueRow
    private lateinit var rowPbDelta: LabelValueRow

    private lateinit var breakdownPopup: ScoreBreakdownPopupView
    private lateinit var playerRankingsView: PlayerRankingsView
    private lateinit var networkProgressLayout: FrameLayout

    private var currentFlightLog: FlightLog? = null
    lateinit var currentModel: LevelSummaryModel
    private var replayMode: Boolean = false

    private var lastBreakdown: ScoreCalculatorV1.Breakdown? = null


    init {
        // Inflate your internal layout into this view
        LayoutInflater.from(context).inflate(R.layout.mission_summary_view, this, true)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        txtMissionTitle = findViewById(R.id.txtMissionTitle)
        txtHeading = findViewById(R.id.txtHeading)
        txtFailureReason = findViewById(R.id.txtFailureReason)

        btnExitSmmary = findViewById(R.id.btnExitLevel)

        btnWatchReplay = findViewById(R.id.btnWatchReplay)
        btnViewRankings = findViewById(R.id.btnViewRankings)

        btnTryAgain = findViewById(R.id.btnTryAgain)
        btnNextLevel = findViewById(R.id.btnNextMission)
        btnStart = findViewById(R.id.btnStart)

        linkMore = findViewById(R.id.linkMore)

        playerRankingsView = findViewById(R.id.playerRankingsView)
        networkProgressLayout = findViewById(R.id.levelRankingNetworkProgress)

        rowScore = findViewById(R.id.rowScore)
        rowDifficulty = findViewById(R.id.rowDifficulty)
        rowFriendlies = findViewById(R.id.rowFriendlies)
        rowEnemies = findViewById(R.id.rowEnemiesDestroyed)
        rowIntegrity = findViewById(R.id.rowIntegrity)
        rowAccuracy = findViewById(R.id.rowAccuracy)
        rowTime = findViewById(R.id.rowTime)

        rowPbValue = findViewById(R.id.rowPbValue)
        rowPbDelta = findViewById(R.id.rowPbDelta)

        breakdownPopup = findViewById(R.id.scoreBreakdownPopup)

        // Buttons
        btnExitSmmary.setOnClickListener {
            if (replayMode) {
                this.visibility = INVISIBLE
            }
            else {
                mainActivity.exitLevel()
            }
        }

        btnTryAgain.setOnClickListener {
            val log = currentFlightLog ?: return@setOnClickListener
            if (!replayMode) {
                mainActivity.resetGame()
            }
            else {
                mainActivity.screenOverlay.replayController.resetReplayToStart(false)
            }
            visibility = GONE
        }

        btnNextLevel.setOnClickListener { nextLevelClicked() }

        btnStart.setOnClickListener {
            // used for VIEW_BEST_RUN pre-mission screen
            val model = currentModel ?: return@setOnClickListener
            mainActivity.openLevelById(model.levelId, false)
            visibility = GONE
        }

        linkMore.setOnClickListener {
            val b = lastBreakdown ?: return@setOnClickListener
//            val log = currentFlightLog ?: return@setOnClickListener
            val enemiesStart = currentFlightLog?.level?.objectiveSummary()?.enemiesStart ?: return@setOnClickListener
            breakdownPopup.show(b)
        }

        // rankings hook (only visible when scoring enabled)
        btnViewRankings.setOnClickListener {
            getPlayerRankings()
        }

        // watch replay hook (this is just a label in your mockups)
        btnWatchReplay.setOnClickListener {
            if (replayMode) mainActivity.resetGame() else mainActivity.replayLastFlight()
            visibility = GONE
        }
    }

    private fun nextLevelClicked() {
        val currentLevel = mainActivity.currentLevel ?: run {
            mainActivity.exitLevel()
            return
        }
        if (currentModel.isLastMilkrun) {
            if (mainActivity.levelManager.missions.isNotEmpty()) {
                visibility = GONE
                mainActivity.openLevelByIndex(Level.LevelType.MISSION, 0)
            }
            else {
                mainActivity.exitLevel()
            }
            return
        }

        val nextIndex = mainActivity.levelManager.getNextLevelIndex(currentLevel)
        if (nextIndex != null) {
            visibility = GONE
            mainActivity.openLevelByIndex(currentLevel.type, nextIndex)
        } else {
            mainActivity.exitLevel()
        }
    }

    fun loadAfterMission(
        flightLog: FlightLog,
        replayMode: Boolean,
        model: LevelSummaryModel,
        parTimeMs: Int? = null
    ) {
        this.currentFlightLog = flightLog
        this.currentModel = model
        this.replayMode = replayMode

        txtMissionTitle.text = model.levelTitle

        val success = flightLog.completionOutcome == CompletionOutcome.SUCCESS

        // Default visibility
        txtFailureReason.visibility = GONE
        btnStart.visibility = GONE
        btnTryAgain.visibility = VISIBLE
        btnNextLevel.visibility = GONE

        btnViewRankings.visibility = if (model.isLevelTypeScored && success) VISIBLE else GONE

        // Hide stats by default; we’ll enable if applicable
        setStatsVisible(false)
        linkMore.visibility = GONE
        rowPbValue.visibility = GONE
        rowPbDelta.visibility = GONE
        lastBreakdown = null

        networkProgressLayout.visibility = GONE

        btnTryAgain.text =
            if (replayMode) "Play Again" else "Try Again"

        if (!success) {
            // FAILED state
            txtHeading.text = "Mission Failed"
            txtFailureReason.visibility = VISIBLE
            txtFailureReason.text = when (flightLog.completionOutcome) {
                CompletionOutcome.FAILED_DESTROYED -> "Ship destroyed"
                CompletionOutcome.FAILED_ZERO_FRIENDLIES -> "All friendlies lost"
                else -> "Mission failed"
            }

            // Next Mission only if previously completed (your design)
            val previouslyCompleted = model.previousBestScore != null
            btnNextLevel.visibility =
                if (!replayMode && previouslyCompleted && model.canPlayNextLevel) VISIBLE else GONE

            return
        }

        // SUCCESS state
        txtHeading.text = if (replayMode) "Mission Replay" else "Mission Completed!"
        setStatsVisible(true)

        // Only score + breakdown for scored missions (MISSION levels by your plan)
        if (model.isLevelTypeScored) {
            val breakdown = ScoreCalculatorV1.score(flightLog)
            lastBreakdown = breakdown

            bindScoredSummaryRows(flightLog, breakdown)

            linkMore.visibility = VISIBLE

            if (!replayMode && model.previousBestScore != null) {
                val delta = breakdown.total - model.previousBestScore
                val bestLabel: String
                if (delta > 0) {
                    txtHeading.text = "New Personal Best!"
                    bestLabel = "Previous Best"
                }
                else {
                    txtHeading.text = "Mission Completed"
                    bestLabel = "Personal Best"
                }

                rowPbValue.visibility = VISIBLE
                rowPbDelta.visibility = VISIBLE
                rowPbValue.set(bestLabel, fmtInt(model.previousBestScore), emphasize = true)
                rowPbDelta.set("", "(${fmtSigned(delta)})", size = RowSize.SMALL)

            }
        } else {
            // Unscored success (TRAINING/MILKRUN etc.) – still show “human stats”
            rowScore.visibility = GONE
            rowDifficulty.visibility = GONE
            rowTime.visibility = GONE

            rowFriendlies.set("Friendlies Saved", "${flightLog.friendliesRemaining} / ${flightLog.level?.objectiveSummary()?.friendliesStart ?: 0}")
            rowIntegrity.set("Ship Integrity", "${(flightLog.healthRemaining * 100f).toInt()}%")
            rowAccuracy.set("Accuracy Rating", if (model.score != null) "${model.score.accuracyRating}%" else "-")
//            rowTime.set("Time", fmtTime(flightLog.flightTimeMs))
        }

        // Next level navigation rules
        if (!replayMode && model.canPlayNextLevel) {
            btnNextLevel.visibility = VISIBLE
            btnNextLevel.text =
                if (model.nextLevelType == Level.LevelType.MISSION) {
                    if (model.isLastMilkrun) {
                        "Proceed to Missions >"
                    }
                    else {
                        "Next Mission >"
                    }
                }
                else if (model.nextLevelType == Level.LevelType.MILKRUN) {
                    "Next Op >"
                }
                else "Next >"
        }
        else {
            btnNextLevel.visibility = GONE
        }
    }

    fun loadBestRunBeforeStart(model: LevelSummaryModel) {
        // This is your “Open previously completed level” screen
        this.currentModel = model
        this.currentFlightLog = model.previousBestLog
        this.replayMode = false

        txtMissionTitle.text = model.levelTitle
        txtHeading.text = "Your Best Run"
        txtFailureReason.visibility = GONE

        // Show Start button instead of Try Again / Next
        btnTryAgain.visibility = GONE
        btnNextLevel.visibility = GONE
        btnStart.visibility = VISIBLE

        // Show rankings if this is scored
        btnViewRankings.visibility = if (model.isLevelTypeScored) VISIBLE else GONE

        setStatsVisible(true)
        linkMore.visibility = GONE
        rowPbValue.visibility = GONE
        rowPbDelta.visibility = GONE

        networkProgressLayout.visibility = GONE

        val best = model.previousBestLog
        val objSummary = best?.level?.objectiveSummary()
        if (best != null && objSummary != null) {
            if (model.isLevelTypeScored) {
                // difficulty from best breakdown if you store it; else recompute
                val breakdown = ScoreCalculatorV1.score(best)
                lastBreakdown = breakdown
                bindScoredSummaryRows(best, breakdown, scoreLabel = "Personal Best")
                linkMore.visibility = VISIBLE

            }
            else {
                // Unscored success (TRAINING/MILKRUN etc.) – still show “human stats”
                rowScore.visibility = GONE
                rowDifficulty.visibility = GONE
                rowTime.visibility = GONE

                rowFriendlies.set("Friendlies Saved", "${best.friendliesRemaining} / ${objSummary.friendliesStart}")
                rowIntegrity.set("Ship Integrity", "${(best.healthRemaining * 100f).toInt()}%")
                rowAccuracy.set("Accuracy", pct(best.shotsHit, best.shotsFired))
                linkMore.visibility = GONE
            }
        } else {
            // fallback
            setStatsVisible(false)
        }
    }

    private fun bindScoredSummaryRows(
        flightLog: FlightLog,
        b: ScoreCalculatorV1.Breakdown,
        scoreLabel: String = "Score"
    ) {
        rowScore.set(scoreLabel, fmtInt(b.total), emphasize = true, size = RowSize.LARGE)
        rowDifficulty.set("Difficulty", "x${fmt2(b.difficultyWeight)}")
        rowIntegrity.set("Ship Integrity", "${(flightLog.healthRemaining * 100f).toInt()}%")
        rowAccuracy.set("Accuracy Rating", "${b.accuracyRating}%")

        // Friendlies row
        if (b.friendliesStart > 0) {
            rowFriendlies.visibility = VISIBLE
            rowFriendlies.set("Friendlies Saved", "${b.friendliesSaved} / ${b.friendliesStart}")
        } else {
            rowFriendlies.visibility = GONE
        }

        // Enemies row: only show when performance-based (EVAC only)
        val enemiesBonus = b.enemiesBonus
        if (enemiesBonus != null) {
            rowEnemies.visibility = VISIBLE
            val start = b.enemiesStart.coerceAtLeast(0)
            val value = if (start > 0) "${b.enemiesDestroyed} / $start" else "${b.enemiesDestroyed}"
            rowEnemies.set("Enemies Destroyed", value)
        } else {
            rowEnemies.visibility = GONE
        }

        // Time row: show remaining if present
        val remaining = b.timeRemainingMs
        if (remaining != null) {
            rowTime.visibility = VISIBLE
            rowTime.set("Time remaining", fmtTime(remaining))
        } else {
            rowTime.visibility = GONE
        }
    }

    fun getPlayerRankings() {

        networkProgressLayout.visibility = VISIBLE

        if (mainActivity.userId != null) {
            Thread({ Networker(this, getHostServer(mainActivity)).getMissionRankings(currentModel.levelId, mainActivity.userId!!) }).start()
        }
    }

    fun showPlayerRankings(response: Networker.MissionRankingsResponse) {
        mainActivity.adminLogView.printout("Retrieving user rankings levelid ${response.levelId}")
        val model = PlayerRankingsView.Model(currentModel.levelTitle, response, 10)
        playerRankingsView.show(model)
    }

    private fun setStatsVisible(visible: Boolean) {
        val v = if (visible) VISIBLE else GONE
        rowScore.visibility = v
        rowDifficulty.visibility = v
        rowFriendlies.visibility = v
        rowEnemies.visibility = v
        rowIntegrity.visibility = v
        rowAccuracy.visibility = v
        rowTime.visibility = v
    }


    override fun handleNetworkMessage(msg: Message) {

        networkProgressLayout.visibility = GONE
        when (msg.what) {
            NetworkResponse.GET_MISSION_RANKINGS.value -> {
                val missionRankingsResponse = msg.obj as Networker.MissionRankingsResponse
                showPlayerRankings(missionRankingsResponse)
            }
            -1, -2, -3 -> {
                mainActivity.adminLogView.printout("Server Error: [${msg.what}] ${msg.obj}")
                Toast.makeText(context, "Server error retrieving rankings", Toast.LENGTH_SHORT).show()
            }
            -4 -> {
                mainActivity.adminLogView.printout("Network error occured: [${msg.what}] ${msg.obj}")
                Toast.makeText(context, "Network error retrieving rankings", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

enum class RowSize {
    SMALL,
    DEFAULT,
    LARGE
}
class LabelValueRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    private val labelView: TextView
    private val detailView: TextView
    private val valueView: TextView

    private val defaultGreen = 0xFF5ADC00.toInt()
    private val emphasisWhite = 0xFFFFFFFF.toInt()

    // Baseline text sizes (sp)
    private val labelSizeDefaultSp = 22f
    private val valueSizeDefaultSp = 22f

    private val labelSizeSmallSp = 18f
    private val valueSizeSmallSp = 18f

    private val labelSizeLargeSp = 26f
    private val valueSizeLargeSp = 26f

    init {
        // THIS is the missing piece
        LayoutInflater.from(context)
            .inflate(R.layout.label_value_row, this, true)

        labelView = findViewById(R.id.label)
        detailView = findViewById(R.id.detail)
        valueView = findViewById(R.id.value)

        labelView.setTextColor(defaultGreen)
        valueView.setTextColor(defaultGreen)
        applySize(RowSize.DEFAULT)
    }

    fun set(
        label: String,
        value: String,
        detailText: String? = null,
        emphasize: Boolean = false,
        size: RowSize = RowSize.DEFAULT
    ) {
        labelView.text = label
        valueView.text = value


        if (detailText.isNullOrBlank()) {
            detailView.visibility = GONE
        } else {
            detailView.visibility = VISIBLE
            detailView.text = detailText
        }

        setEmphasis(emphasize)
        applySize(size)
    }

    fun setEmphasis(emphasize: Boolean) {
        val color = if (emphasize) emphasisWhite else defaultGreen
        labelView.setTextColor(color)
        detailView.setTextColor(color)
        valueView.setTextColor(color)
    }

    private fun applySize(size: RowSize) {
        when (size) {
            RowSize.SMALL -> {
                labelView.textSize = labelSizeSmallSp
                valueView.textSize = valueSizeSmallSp
            }
            RowSize.DEFAULT -> {
                labelView.textSize = labelSizeDefaultSp
                valueView.textSize = valueSizeDefaultSp
            }
            RowSize.LARGE -> {
                labelView.textSize = labelSizeLargeSp
                valueView.textSize = valueSizeLargeSp
            }
        }
    }
}


