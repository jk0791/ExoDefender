package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit

class LevelPrologueView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    object LevelIds {
        const val DEFEND_PREAMBLE = 267
        const val LANDING_TRAINING = 278
        const val EVAC_PREAMBLE = 278

        private val APPLICABLE = setOf(
            DEFEND_PREAMBLE,
            LANDING_TRAINING,
            EVAC_PREAMBLE,
        )

        fun isApplicable(id: Int) = id in APPLICABLE
    }

    val mainActivity = context as MainActivity
    private val homeButton: ImageView
    private val nextButton: Button
    val prologueSummary: TextView

    lateinit var requestedLevel: Level

//    val DEFEND_PREAMBLE_LEVEL_ID = 267
//    val LANDING_TRAINING_LEVEL_ID = 278
//
//    val applicableLevelIds = mutableListOf(
//        DEFEND_PREAMBLE_LEVEL_ID,
//        LANDING_TRAINING_LEVEL_ID,
//    )

    init {

        inflate(context, R.layout.level_prologue, this)

        homeButton = findViewById(R.id.btnHome)
        homeButton.setOnClickListener {
            visibility = GONE
        }

        prologueSummary = findViewById(R.id.prologueSummary)

        nextButton = this.findViewById<Button>(R.id.btnNext).apply{
            setOnClickListener { nextButtonClicked() }
        }
    }

    fun load(requestedLevel: Level): Boolean {

        this.requestedLevel = requestedLevel

        if (requestedLevel.id == LevelIds.LANDING_TRAINING && !mainActivity.isLandingTrainingComplete()) {
            prologueSummary.text =
                "Next missions may involve landing the EXO-57 Striker.\n\nYou are required to complete a short landing training exercise."
            nextButton.text = "Landing Training >"
            return true
        }

        when (requestedLevel.id) {
            LevelIds.DEFEND_PREAMBLE -> {
                if (mainActivity.isDefendPreambleShown()) return false

                prologueSummary.text =
                    "Excellent work on your close air support missions commander.\n\nThe next mission requires you to defend a structure before it is destroyed by the enemy."
                nextButton.text = "Continue >"
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putBoolean(mainActivity.DEFEND_PREAMBLE_SHOWN, true)
                }
            }
            LevelIds.EVAC_PREAMBLE -> {
                if (mainActivity.isEvacPreambleShown()) return false

                prologueSummary.text =
                    "Next mission, the structure cannot be saved. Land and evacuate civilians to a safe distance away from the battle."
                nextButton.text = "Continue >"
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putBoolean(mainActivity.EVAC_PREAMBLE_SHOWN, true)
                }
            }
        }
        return true
    }

    fun loadLandingTraining(requestedLevel: Level): Boolean {

        if (mainActivity.isMandatoryTrainingComplete()) return false

        prologueSummary.text =
            "Next missions may involve landing the EXO-57 Striker.\n\nYou are required to complete a short landing training exercise."
        nextButton.text = "Landing Training >"

        this.requestedLevel = requestedLevel
        return true
    }

    fun nextButtonClicked() {

        if (requestedLevel.id == LevelIds.LANDING_TRAINING && !mainActivity.isLandingTrainingComplete()) {
            mainActivity.trainingLandingCompleteView.requestedLevel = requestedLevel
            visibility = GONE
            mainActivity.openLevelByGlobalIndex(Level.LevelType.TRAINING, 2)
            return
        }

        visibility = GONE
        mainActivity.openLevelById(requestedLevel.id, false)
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