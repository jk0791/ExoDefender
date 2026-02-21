package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class StoryView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity
    val heading: TextView
    val longFormTextView: TextView
    val shortFormTextView: TextView
    val btnTraining: Button
    val homeButton: ImageView

    init {
        inflate(context, R.layout.story, this)

        homeButton = findViewById(R.id.btnHome)
        homeButton.setOnClickListener({
            mainActivity.showHomeView()
        })

        heading = findViewById(R.id.storyHeading)
        longFormTextView = findViewById(R.id.longForm)
        shortFormTextView = findViewById(R.id.shortForm)
        btnTraining = findViewById(R.id.btnTraining)
        btnTraining.setOnClickListener {
            this.visibility = GONE
            mainActivity.audioPlayer.startMusic(1)
            mainActivity.openLevelByGlobalIndex(Level.LevelType.TRAINING, 0, false)
        }

    }

    fun load(playIntro: Boolean) {

        if (playIntro) {
            heading.text = "Exo Striker"
            shortFormTextView.visibility = VISIBLE
            longFormTextView.visibility = GONE
            homeButton.visibility = INVISIBLE

            btnTraining.visibility = INVISIBLE
            btnTraining.postDelayed({
                btnTraining.visibility = VISIBLE
                btnTraining.alpha = 0f
                btnTraining.animate()
                    .alpha(1f)
                    .setDuration(400L)
                    .start()
            }, 600L)
        }
        else {
            heading.text = "Background"
            btnTraining.visibility = GONE
            longFormTextView.visibility = VISIBLE
            shortFormTextView.visibility = GONE
            homeButton.visibility = VISIBLE
        }
    }
}
