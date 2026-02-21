package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

class TrainingLandingCompleteView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity

    init {

        inflate(context, R.layout.training_landing_complete, this)

        findViewById<View>(R.id.card).setOnClickListener { keepPracticing() }

        findViewById<Button>(R.id.btnKeepPracticing).setOnClickListener({
            keepPracticing()
        })

        findViewById<Button>(R.id.btnNextMission).setOnClickListener({
            gotoNextMission()
        })

    }

    fun keepPracticing() {
        // TODO render button to allow going to next mission
        visibility = GONE
    }

    fun gotoNextMission() {

    }

}