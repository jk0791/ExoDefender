package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout

class TrainingView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity

    init {
        inflate(context, R.layout.training_view, this)

        findViewById<ImageView>(R.id.btnHome).apply {
            setOnClickListener {
                mainActivity.showHomeView()
            }
        }
        findViewById<Button>(R.id.btnBasicControls).apply {
            setOnClickListener {
                mainActivity.openLevelByGlobalIndex(Level.LevelType.TRAINING, 0, false)
            }
        }
        findViewById<Button>(R.id.btnTargetPractice).apply {
            setOnClickListener {
                mainActivity.openLevelByGlobalIndex(Level.LevelType.TRAINING, 1, false)
            }
        }
        findViewById<Button>(R.id.btnLandingPractice).apply {
            setOnClickListener {
                mainActivity.openLevelByGlobalIndex(Level.LevelType.TRAINING, 2, false)
            }
        }
    }

    fun load() {
        // TODO
    }
}
