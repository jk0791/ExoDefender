package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class ShipHealthDisplay(context: Context, attrs: AttributeSet?=null) :
    View(context, attrs) {

    private val progressBounds = RectF()
    private val borderBounds = RectF()
    private val borderStrokePaint = Paint()
    private val progressStrokePaint = Paint()

    private val defaultColor = 0xff62ab45.toInt()
    private val warningColor = 0xffdd2222.toInt()

    val labelTextSize = dp(14f)
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = labelTextSize
        typeface = Typeface.DEFAULT
        color = defaultColor
    }

    val progressStrokeWidth = dp(10f)
    val progressPadding = progressStrokeWidth / 2
    val borderStrokeWidth = 1f
    val borderPadding = borderStrokeWidth / 2
    private var healthRemaining = 0f
    private val defaultAlpha = 0.75f
    private var barHeight = 0f
    private var fullBarLength = 0f
    private val labelWidth = dp(30f)

    var laidOutOnce = false
    private var flashingStartTimeMs = 0L
    private var flashingPaused = false

    init {

        setWillNotDraw(false)

        progressStrokePaint.style = Paint.Style.STROKE
        progressStrokePaint.color = defaultColor
        progressStrokePaint.strokeWidth = progressStrokeWidth

        borderStrokePaint.style = Paint.Style.STROKE
        borderStrokePaint.color = defaultColor
        borderStrokePaint.strokeWidth = 2f

        this.alpha = defaultAlpha
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!laidOutOnce) {

            barHeight = this.height * 0.4f
            fullBarLength = this.width - labelWidth
            progressBounds.set(
                progressPadding,
                progressPadding,
                width - progressPadding,
                height - progressPadding
            )

            borderBounds.set(
                borderPadding,
                borderPadding,
                width - borderPadding,
                height - borderPadding
            )

            laidOutOnce = true
        }
    }

    fun reset() {
        healthRemaining = 1f

        progressStrokePaint.color = defaultColor
        borderStrokePaint.color = defaultColor
        paintLabel.color = defaultColor
        stopFlashing()
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density


    fun update(healthRemaining: Float) {
        this.healthRemaining = healthRemaining

        if (this.healthRemaining < 0.3f) {
            progressStrokePaint.color = warningColor
            borderStrokePaint.color = warningColor
            paintLabel.color = warningColor

            if (this.healthRemaining < 0.2f) {
                if (flashingStartTimeMs == 0L) startFlashing()
            }
            else {
                stopFlashing()
            }
        }
        else {
            progressStrokePaint.color = defaultColor
            borderStrokePaint.color = defaultColor
            paintLabel.color = defaultColor
            stopFlashing()
        }

        postInvalidate()
    }

    fun startFlashing() {
        flashingStartTimeMs = System.currentTimeMillis()
        flashingPaused = false
    }

    fun stopFlashing() {
        flashingStartTimeMs = 0L
        alpha = defaultAlpha
        flashingPaused = false
    }

    fun pauseFlashing(pause: Boolean) {
        flashingPaused = pause
        alpha = defaultAlpha
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawArc(borderBounds, 0f, 360f, false, borderStrokePaint)
        canvas.drawArc(progressBounds, -90f, -360 * healthRemaining, false, progressStrokePaint)

        val labelText = (healthRemaining * 100).toInt().toString() + "%"
        canvas.drawText(labelText, width / 2f, (height + labelTextSize - 10f) / 2,paintLabel)

        if (flashingStartTimeMs != 0L && !flashingPaused) {
            val elapsedFlashingMs = (System.currentTimeMillis() - flashingStartTimeMs).toInt()

            val phase = (elapsedFlashingMs % 400) / 400f  // 0..1
            this.alpha = if (phase < 0.7f) defaultAlpha else 0f

            postInvalidateOnAnimation()
        }
    }

}