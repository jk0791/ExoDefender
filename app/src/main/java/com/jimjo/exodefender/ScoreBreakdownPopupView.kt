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

    fun show(b: ScoreCalculatorV1.Breakdown, enemiesStart: Int) {
        bind(b, enemiesStart)
        visibility = View.VISIBLE
        bringToFront()
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun bind(b: ScoreCalculatorV1.Breakdown, enemiesStart: Int) {
        rowsContainer.removeAllViews()

        // Helper to add a row using LabelValueRow
        fun addRow(label: String, value: String, emphasize: Boolean = false) {
            val row = LabelValueRow(context)
            row.set(label, value, emphasize)
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
            v.setBackgroundColor(0x995ADC00.toInt()) // faint green line
            rowsContainer.addView(v)
        }

        // Build values
        val base = b.base
        val save = b.savePoints
        val hp = b.healthPoints
        val acc = b.accuracyPoints
        val time = b.timeBonus

        addRow("Base ($enemiesStart Enemies)", fmtInt(base), emphasize = false)
        addRow("Friendlies Saved", fmtSigned(save))
        addRow("Ship Integrity", fmtSigned(hp))
        addRow("Accuracy", fmtSigned(acc))
        if (time != 0f) addRow("Time Bonus", fmtSigned(time))

        addDivider()

        val subtotal = base + save + hp + acc + time
        // You can choose whether to emphasize subtotal; I usually keep it green.
        addRow("Subtotal", fmtInt(subtotal), emphasize = false)
        addRow("Difficulty Multiplier", "x${fmt2(b.difficultyWeight)}", emphasize = false)

        // Final score row at bottom (emphasized = white)
        finalRow.set("Final Score", fmtInt(b.total.toFloat()), emphasize = true)
    }

    private fun fmtSigned(x: Float): String =
        if (x >= 0f) "+${fmtInt(x)}" else "-${fmtInt(-x)}"

    private fun fmtInt(x: Float): String =
        "%,d".format(x.toInt())

    private fun fmt2(x: Float): String =
        "%.2f".format(x)

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
