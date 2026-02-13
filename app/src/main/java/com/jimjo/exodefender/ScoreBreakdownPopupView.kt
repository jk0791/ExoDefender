package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout

class ScoreBreakdownPopupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    private val rowsContainer: LinearLayout
    private val finalRow: LabelValueRow

    init {
        LayoutInflater.from(context).inflate(R.layout.view_score_breakdown_popup, this, true)
        visibility = View.GONE

        isClickable = true
        isFocusable = true

        rowsContainer = findViewById(R.id.rows)
        finalRow = findViewById(R.id.finalRow)

        // Tap anywhere to close (overlay or card)
        setOnClickListener { hide() }
        findViewById<View>(R.id.card).setOnClickListener { hide() }
    }

    fun show(b: ScoreCalculatorV1.Breakdown) {
        bind(b)
        visibility = VISIBLE
        bringToFront()
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun bind(b: ScoreCalculatorV1.Breakdown) {
        rowsContainer.removeAllViews()

        fun addRow(label: String, value: String, detail: String? = null, emphasize: Boolean = false) {
            val row = LabelValueRow(context)
            row.set(label, value, detail, emphasize)
            rowsContainer.addView(row)
        }

        fun addDivider() {
            val v = View(context)
            v.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
            v.setBackgroundColor(0x995ADC00.toInt())
            rowsContainer.addView(v)
        }

        val base = b.basePoints
        val friendliesBonus = b.friendliesBonus
        val enemiesBonus = b.enemiesBonus
        val hp = b.healthBonus
        val acc = b.accuracyBonus
        val timeBonus = b.timeBonus

        val baseDetails = when (b.objectiveType) {
            Level.ObjectiveType.CAS -> "${b.enemiesStart} Enemies"
            Level.ObjectiveType.DEFEND -> "Structure + ${b.enemiesStart} Enemies"
            Level.ObjectiveType.EVAC -> "${b.civiliansStart} Civilians"
            else -> null
        }

        addRow("Base", fmtInt(base), detail = baseDetails)

        // CAS/DEFEND only: show if applicable (non-null), even if 0
        friendliesBonus?.let {
            addRow(
                "Friendlies Saved",
                fmtSigned(it),
                detail = "${b.friendliesSaved}/${b.friendliesStart}"
            )
        }

        // EVAC only: show if applicable (non-null), even if 0
        enemiesBonus?.let {
            addRow(
                "Enemies Destroyed",
                fmtSigned(it),
                detail = "${b.enemiesDestroyed}/${b.enemiesStart}"
            )
        }

        addRow("Ship Integrity", fmtSigned(hp), detail = "${(b.shipIntegrity * 100f).toInt()}%")
        addRow("Accuracy", fmtSigned(acc), detail = "${b.accuracyRating}%")

        // DEFEND only: show if applicable (non-null), even if 0
        timeBonus?.let {
            addRow(
                "Time Bonus",
                fmtSigned(it),
                detail = fmtTime(b.timeRemainingMs ?: 0)
            )
        }

        addDivider()

        // Subtotal includes only applicable bonuses
        val subtotal =
            base +
                    (friendliesBonus ?: 0) +
                    (enemiesBonus ?: 0) +
                    hp + acc +
                    (timeBonus ?: 0)

        addRow("Subtotal", fmtInt(subtotal))
        addRow("Difficulty Multiplier", "x${fmt2(b.difficultyWeight)}")

        finalRow.set("Final Score", fmtInt(b.total), emphasize = true)
    }


    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
