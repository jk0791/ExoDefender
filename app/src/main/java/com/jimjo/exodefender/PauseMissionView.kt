package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout

class PauseMissionView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val parent = context as MainActivity

    init {

        inflate(context, R.layout.pause_mission, this)

        val resumeSettingsButton = this.findViewById<Button>(R.id.btnResume)
        resumeSettingsButton.setOnClickListener({ parent.closePauseMission() })

        val restartLevelButton = this.findViewById<Button>(R.id.btnPrev)
        restartLevelButton.setOnClickListener({
            parent.resetGame()
            parent.closePauseMission()
        })

        val exitLevel = this.findViewById<Button>(R.id.btnExitSummary)
        exitLevel.setOnClickListener({
//            parent.closePauseMission()
            parent.exitLevel()
        })

    }
}