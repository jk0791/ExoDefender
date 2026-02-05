package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class ActorStatsDisplay(context: Context, attrs: AttributeSet?=null) :
    View(context, attrs) {

    private val fillPaintFriendly = Paint()
    private val fillPaintEnemy = Paint()
    private val strokePaintFriendly = Paint()
    private val strokePaintEnemy = Paint()

    private val friendlyColor = 0xff62ab45.toInt()
    private val enemyColor = 0xFFBBBBBB.toInt()

    val labelTextSize = dp(22f)
    private val paintFRemainingtext = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = labelTextSize
        typeface = Typeface.DEFAULT
        color = friendlyColor
    }

    private val paintERemainingtext = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = labelTextSize
        typeface = Typeface.DEFAULT
        color = Color.WHITE
    }

    val padding = dp(2f)
    val strokeWidth = 2f // dp(1f)

    private var fTotal = 0
    private var fRemaining = 0
    private var eTotal = 0
    private var eRemaining = 0
    private val defaultAlpha = 0.75f

    private var barHeight = 0f
    private var fullBarLength = 0f
    private val labelWidth = dp(30f)

    private var displayFriendlyStats = true

    var laidOutOnce = false

    init {

        setWillNotDraw(false)

        fillPaintFriendly.style = Paint.Style.FILL
        fillPaintFriendly.color = friendlyColor

        strokePaintFriendly.style = Paint.Style.STROKE
        strokePaintFriendly.color = friendlyColor
        strokePaintFriendly.strokeWidth = strokeWidth

        fillPaintEnemy.style = Paint.Style.FILL
        fillPaintEnemy.color = enemyColor

        strokePaintEnemy.style = Paint.Style.STROKE
        strokePaintEnemy.color = enemyColor
        strokePaintEnemy.strokeWidth = strokeWidth

        this.alpha = defaultAlpha
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!laidOutOnce) {

            barHeight = this.height * 0.4f
            fullBarLength = this.width - labelWidth

            laidOutOnce = true
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    fun showFriendlyStats(on: Boolean) {
        displayFriendlyStats = on
    }


    fun update(fRemaining: Int, fTotal: Int, eRemaining: Int, eTotal: Int) {
        this.fRemaining = fRemaining
        this.fTotal = fTotal
        this.eRemaining = eRemaining
        this.eTotal = eTotal

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (displayFriendlyStats) {
            val friendlyBarWidth = fullBarLength * fRemaining.toFloat() / fTotal
            canvas.drawRect(
                labelWidth,
                0f,
                friendlyBarWidth + labelWidth,
                barHeight,
                fillPaintFriendly
            )
            canvas.drawRect(
                labelWidth,
                0f,
                fullBarLength + labelWidth,
                barHeight,
                strokePaintFriendly
            )
            canvas.drawText(
                fRemaining.toString(),
                labelWidth - padding,
                barHeight - padding,
                paintFRemainingtext
            )
        }

        val enemyBarWidth = fullBarLength * eRemaining.toFloat() / eTotal
        canvas.drawRect(  labelWidth, height.toFloat() - barHeight, enemyBarWidth + labelWidth, height.toFloat(), fillPaintEnemy)
        canvas.drawRect(  labelWidth, height.toFloat() - barHeight, fullBarLength + labelWidth, height.toFloat(), strokePaintEnemy)
        canvas.drawText(eRemaining.toString(), labelWidth - padding, height.toFloat() - padding,paintERemainingtext)
    }

}