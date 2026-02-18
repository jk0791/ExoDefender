package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit

class TrainingOutcomeView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity
    private val homeButton: ImageView
    private val nextButton: Button
    val trainingSummary: TextView
    val actorsDiagram: ConstraintLayout

    var trainingLevelIndex = -1

    var manadatoryTrainingActive = false

    init {

        inflate(context, R.layout.training_outcome, this)

        homeButton = findViewById(R.id.btnHome)
        homeButton.setOnClickListener {
            mainActivity.exitLevel()
            mainActivity.closeTrainingOutcomeView()
            mainActivity.showTrainingView()
        }

        trainingSummary = findViewById(R.id.trainingSummary)
        actorsDiagram = findViewById(R.id.actorsDiagram)

        val prevButton = this.findViewById<Button>(R.id.btnRestartLevel)
        prevButton.setOnClickListener({
            mainActivity.closeTrainingOutcomeView()
            mainActivity.openLevelByIndex(Level.LevelType.TRAINING, 0)
        })

        nextButton = this.findViewById<Button>(R.id.btnNext).apply{
            setOnClickListener { nextButtonClicked() }
        }
    }

    fun nextButtonClicked() {
        when (trainingLevelIndex) {
            0 -> {
                mainActivity.closeTrainingOutcomeView()
                mainActivity.openLevelByIndex(Level.LevelType.TRAINING, 1)
            }
            1 -> {
                mainActivity.closeTrainingOutcomeView()
                mainActivity.showNewUserNotice()
            }
        }
    }

    fun configureForLevelIndex(trainingLevelIndex: Int, mandatoryTraining: Boolean) {

        this.trainingLevelIndex = trainingLevelIndex
        actorsDiagram.visibility = GONE

        when (this.trainingLevelIndex) {
            0 -> {
                if (mandatoryTraining) {
                    trainingSummary.text = "EXO-57 Striker flight controls training complete.\n\nProceed to target practice."
                }
                else {
                    trainingSummary.text = "EXO-57 Striker flight controls training complete."
                }
                mainActivity.audioPlayer.playAIVoiceOver(mainActivity.audioPlayer.ai_controls_trg_complete)
            }
            1 -> {
                if (mandatoryTraining) {
                    trainingSummary.text = "Training complete.\n\nProceed to live operations."
                }
                else {
                    trainingSummary.text = "Target practice session complete."
                }
            }
            else -> {
                trainingSummary.text = "Click Next to go on to the next step"
            }
        }

        if (mandatoryTraining) {
            homeButton.visibility = INVISIBLE
            nextButton.visibility = VISIBLE

            if (this.trainingLevelIndex == 1) {

                actorsDiagram.visibility = VISIBLE

                //mandatory training complete so set flag
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putBoolean(mainActivity.MANDATORY_TRAINING_COMPLETED, true)
                }
            }

        }
        else {
            homeButton.visibility = VISIBLE
            nextButton.visibility = INVISIBLE
        }
    }

//    fun showMandatoryTraining(show: Boolean) {
//        manadatoryTrainingActive = show
//        if (manadatoryTrainingActive) {
//            homeButton.visibility = GONE
//        }
//        else {
//            homeButton.visibility = VISIBLE
//        }
//    }

}