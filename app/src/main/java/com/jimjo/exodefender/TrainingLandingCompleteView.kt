package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

class TrainingLandingCompleteView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity
    var requestedLevel: Level? = null
    val txtHeading: TextView
    val btnKeepPracticing: Button
    val btnNextMission: Button

    val cardView: View

    init {

        inflate(context, R.layout.training_landing_complete, this)

//        findViewById<View>(R.id.backdrop).setOnClickListener { keepPracticing() }

        cardView = findViewById<View>(R.id.card)
//        cardView.setOnClickListener { keepPracticing() }

        txtHeading = findViewById<TextView>(R.id.heading)

        btnKeepPracticing = findViewById<Button>(R.id.btnKeepPracticing)
        btnKeepPracticing.setOnClickListener({
            keepPracticing()
        })

        btnNextMission = findViewById<Button>(R.id.btnNextMission)
        btnNextMission.setOnClickListener({
            gotoNextMission()
        })

    }

    fun load() {
        btnNextMission.visibility = if (requestedLevel != null) VISIBLE else INVISIBLE
        txtHeading.visibility = VISIBLE
        btnKeepPracticing.visibility = VISIBLE
        cardView.setBackgroundColor(Color(0, 0, 0, 187).toArgb())
        cardView.translationX = 0f
        cardView.translationY = 0f

    }

    fun keepPracticing() {

        txtHeading.visibility = GONE
        btnKeepPracticing.visibility = GONE
        cardView.setBackgroundColor(Color(0, 0, 0, 100).toArgb())

        cardView.post {

            val parent = cardView.parent as View

            val marginX = 100f
            val marginY = 40f

            val targetX = parent.width - cardView.width - marginX
            val targetY = marginY

            val tx = targetX - cardView.left
            val ty = targetY - cardView.top

            cardView.animate()
                .translationX(tx)
                .translationY(ty)
                .setDuration(400)
                .start()
        }
    }

    fun gotoNextMission() {
        visibility = GONE
        requestedLevel?.let {
            mainActivity.openLevelById(it.id, false)
        }
        requestedLevel = null
    }

}