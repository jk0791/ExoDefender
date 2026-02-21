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

        private val APPLICABLE = setOf(
            DEFEND_PREAMBLE,
            LANDING_TRAINING
        )

        fun isApplicable(id: Int) = id in APPLICABLE
    }

    val mainActivity = context as MainActivity
    private val homeButton: ImageView
    private val nextButton: Button
    val prologueSummary: TextView

    lateinit var requestedLevel: Level

    val DEFEND_PREAMBLE_LEVEL_ID = 267
    val LANDING_TRAINING_LEVEL_ID = 278

    val applicableLevelIds = mutableListOf(
        DEFEND_PREAMBLE_LEVEL_ID,
        LANDING_TRAINING_LEVEL_ID,
    )

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

    fun load(requestedLevel: Level) {

        this.requestedLevel = requestedLevel

        when (requestedLevel.id) {
            DEFEND_PREAMBLE_LEVEL_ID -> {
                prologueSummary.text =
                    "Excellent work on your close air support missions commander.\n\nThe next mission requires you to defend a structure before it is destroyed by the enemy."
                nextButton.text = "Continue >"
            }
            LANDING_TRAINING_LEVEL_ID -> {
                prologueSummary.text =
                    "Some of the next missions will involve landing the EXO-57 Striker.\n\nYou are required to complete a short landing training exercise."
                nextButton.text = "Landing Training >"
            }
        }
    }

    fun nextButtonClicked() {
        when (requestedLevel.id) {
            LevelIds.DEFEND_PREAMBLE -> {
                visibility = GONE
                mainActivity.openLevelById(requestedLevel.id, false)
            }
            LevelIds.LANDING_TRAINING -> {
                visibility = GONE
                mainActivity.openLevelByIndex(Level.LevelType.TRAINING, 2)
            }
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