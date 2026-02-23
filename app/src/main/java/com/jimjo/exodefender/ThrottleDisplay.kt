package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ThrottleDisplay(context: Context, attrs: AttributeSet?=null) :
    View(context, attrs) {

    var flightControls: FlightControls? = null

    var mode = FlightControls.ThrottleMode.GAME
    private val defaultStrokePaint = Paint()
    private val warningStrokePaint = Paint()
    private var currentStrokePaint = defaultStrokePaint
    private val defaultFillPaint = Paint()
    private val warningFillPaint = Paint()
    private var currentFillPaint = defaultFillPaint
    private var factor = 0.5f
    var defaultAlpha = 0.25f
    private val activeAlpha = 0.7f

    private val alphaCountdownStart = 15
    private val alphaIncrement = (activeAlpha - defaultAlpha) / alphaCountdownStart
    private var alphaCountdown = 0

    private var fadeOnRelease = true

    init {

        val defaultColor = Color.WHITE
        val warningColor = Color.argb(255, 255, 50, 0)

        defaultStrokePaint.style = Paint.Style.STROKE
        defaultStrokePaint.color = defaultColor
        defaultStrokePaint.strokeWidth = 5f

        warningStrokePaint.style = Paint.Style.STROKE
        warningStrokePaint.color = warningColor
        warningStrokePaint.strokeWidth = 5f

        defaultFillPaint.style = Paint.Style.FILL
        defaultFillPaint.color = defaultColor

        warningFillPaint.style = Paint.Style.FILL
        warningFillPaint.color = warningColor

        this.alpha = defaultAlpha
    }

    fun update() {
        flightControls?.let { fc ->
            this.factor = fc.throttle
            this.alpha = activeAlpha
            alphaCountdown = alphaCountdownStart
            fadeOnRelease = true

            if (fc.throttleMode == FlightControls.ThrottleMode.GAME) {
                if (fc.throttle < fc.throttleCenter) {
                    currentStrokePaint = warningStrokePaint
                    currentFillPaint = warningFillPaint
//                    if (fc.throttle == 0f) {
                        fadeOnRelease = false
//                    }
                }
                else {
                    currentStrokePaint = defaultStrokePaint
                    currentFillPaint = defaultFillPaint
                }
            }

            postInvalidate()

        }

    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, this.width.toFloat(), this.height.toFloat(), currentStrokePaint)
        canvas.drawRect(15f, 15f, (this.width.toFloat() - 30f) * factor + 15f, this.height.toFloat() - 15f, currentFillPaint)

        if (alphaCountdown > 0 && fadeOnRelease) {
            alphaCountdown--
            this.alpha = alphaCountdown * alphaIncrement + defaultAlpha
            this.alpha = alphaCountdown * alphaIncrement + defaultAlpha
            if (alphaCountdown == 0) {
                this.alpha = defaultAlpha
            }
            postInvalidate()
        }
    }

}