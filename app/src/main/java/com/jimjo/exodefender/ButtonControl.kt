package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ButtonControl(context: Context, attrs: AttributeSet?=null) :
    View(context, attrs) {
    lateinit var parent: ButtonControlListener
    var label = ""
    private val strokePaint = Paint()
    private val labelPaint = Paint()
    private val defaultAlpha = 0.25f
    private val activeAlpha = 0.7f

    private val alphaCountdownStart = 10
    private val alphaIncrement = (activeAlpha - defaultAlpha) / alphaCountdownStart
    private var alphaCountdown = 0

    init {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 5f

        labelPaint.style = Paint.Style.FILL
        labelPaint.color = Color.WHITE
        labelPaint.typeface = resources.getFont(R.font.consola)
        labelPaint.textSize = 30f
        labelPaint.setTextAlign(Paint.Align.CENTER)

        this.alpha = defaultAlpha
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.buttonControlClicked(this)
                this.alpha = activeAlpha
                alphaCountdown = alphaCountdownStart
                postInvalidate()
            }
        }
        return true
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawArc(2f, 2f, this.width.toFloat() - 2f, this.height.toFloat() - 2f, 0f, 360f, false, strokePaint)
        canvas.drawText(label, width / 2f, height / 2f, labelPaint)

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