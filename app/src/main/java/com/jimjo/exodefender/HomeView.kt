package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout

class HomeView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity

    init {
        inflate(context, R.layout.home_view, this)

        findViewById<Button>(R.id.btnBackground).apply {
            setOnClickListener { mainActivity.showStoryView(false) }
        }
        findViewById<Button>(R.id.btnTraining).apply {
            setOnClickListener { mainActivity.showTrainingView() }
        }
        findViewById<Button>(R.id.btnMilkRuns).apply {
            setOnClickListener { mainActivity.showLevelsView(Level.LevelType.MILKRUN) }
        }
        findViewById<Button>(R.id.btnMissions).apply {
            setOnClickListener { mainActivity.showLevelsView(Level.LevelType.MISSION) }
        }
        findViewById<Button>(R.id.btnReplays).apply {
            setOnClickListener { mainActivity.showReplayManager(true) }
        }

    }

    fun load() {
        // TODO
    }
}
