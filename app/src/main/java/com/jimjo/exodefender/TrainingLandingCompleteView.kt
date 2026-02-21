package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout

class TrainingLandingCompleteView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity

    init {

        inflate(context, R.layout.training_landing_complete, this)

        findViewById<View>(R.id.card).setOnClickListener { keepPracticing() }
        findViewById<ImageView>(R.id.btnContinue).setOnClickListener({ keepPracticing() })

        findViewById<Button>(R.id.btnKeepPracticing).setOnClickListener({
            mainActivity.exitLevel()
        })
        findViewById<Button>(R.id.btnRestartLevel).setOnClickListener({
            mainActivity.resetGame()
            mainActivity.closePauseMission()
        })
        findViewById<Button>(R.id.btnSettingsFromLevel).setOnClickListener({
            mainActivity.closePauseMission()
            mainActivity.showSettings()
        })

    }

    fun keepPracticing() {
        // TODO render button to allow continuing to next mission
        visibility = GONE
    }

}