package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ThrottleDisplay(context: Context, attrs: AttributeSet?=null) :
    View(context, attrs) {

    private val strokePaint = Paint()
    private val fillPaint = Paint()
    private var factor = 0.5f
    var defaultAlpha = 0.25f
    private val activeAlpha = 0.7f

    private val alphaCountdownStart = 10
    private val alphaIncrement = (activeAlpha - defaultAlpha) / alphaCountdownStart
    private var alphaCountdown = 0

    init {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 5f

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.WHITE

        this.alpha = defaultAlpha
    }

    fun update(factor: Float) {
        this.factor = factor
        this.alpha = activeAlpha
        alphaCountdown = alphaCountdownStart
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, this.width.toFloat(), this.height.toFloat(), strokePaint)
        canvas.drawRect(15f, 15f, (this.width.toFloat() - 30f) * factor + 15f, this.height.toFloat() - 15f, fillPaint)

        if (alphaCountdown > 0) {
            alphaCountdown--
            this.alpha = alphaCountdown * alphaIncrement + defaultAlpha
            if (alphaCountdown == 0) {
                this.alpha = defaultAlpha
            }
            postInvalidate()
        }
    }

}