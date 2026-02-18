package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout

class PauseMissionView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity

    init {

        inflate(context, R.layout.pause_mission, this)

        findViewById<View>(R.id.pauseLevelCard).setOnClickListener { mainActivity.closePauseMission() }
        findViewById<ImageView>(R.id.btnClosePauseDialog).setOnClickListener({ mainActivity.closePauseMission() })

        findViewById<Button>(R.id.btnExitLevel).setOnClickListener({
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
}