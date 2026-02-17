package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class TimerCountdownDisplay(context: Context, attrs: AttributeSet?=null) :
    View(context, attrs) {

    private val progressBounds = RectF()
    private val borderBounds = RectF()
    private val borderStrokePaint = Paint()
    private val progressStrokePaint = Paint()

//    private val defaultColor = 0xff62ab45.toInt()
    private val defaultColor = 0xffccbe35.toInt()
    private val warningColor = 0xffee3333.toInt()

    val headingLabelTextSize = dp(14f)
    val timerLabelTextSize = dp(23f)

    private val paintHeadingLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = headingLabelTextSize
        typeface = Typeface.DEFAULT
        color = defaultColor
    }
    private val paintTimeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = timerLabelTextSize
        typeface = Typeface.DEFAULT
        color = defaultColor
    }

    val progressStrokeWidth = dp(5f)
    val progressPadding = progressStrokeWidth / 2
    val borderStrokeWidth = 2f
    val borderPadding = borderStrokeWidth / 2

    private var initialSeconds = 1
    private var remainingSeconds = 0

    private var fractionRemaining = 0f
    private val defaultAlpha = 0.75f
    private val warningAlpha = 1f

    // dimensions calculated upon layout
    private var headingLabelHeight = 0f
    private var headingLabelX = 0f
    private var headingLabelY = 0f
    private var timeLabelX = 0f
    private var timeLabelY = 0f
    private var diameter = 0f

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


            headingLabelHeight = headingLabelTextSize + 10f

            diameter = this.height - headingLabelHeight

            val extraHorzSpace =  (this.width - diameter) / 2

            progressBounds.set(
                progressPadding + extraHorzSpace,
                headingLabelHeight + progressPadding,
                diameter - progressPadding + extraHorzSpace,
                diameter + headingLabelHeight - progressPadding
            )

            borderBounds.set(
                borderPadding + extraHorzSpace,
                headingLabelHeight + borderPadding,
                diameter - borderPadding + extraHorzSpace,
                diameter + headingLabelHeight - borderPadding
            )

            timeLabelX = this.width / 2f
            timeLabelY = diameter / 2 + headingLabelHeight + timerLabelTextSize / 3

            headingLabelX = this.width / 2f
            headingLabelY = headingLabelTextSize

            laidOutOnce = true
        }
    }

    fun isFlashing() = flashingStartTimeMs != 0L

    fun setInitial(initialSeconds: Int) {
        this.initialSeconds = initialSeconds
        reset()
    }

    private fun updatePctRemaining() {
        fractionRemaining = remainingSeconds / initialSeconds.toFloat()
    }

    fun reset() {

        remainingSeconds = initialSeconds
        updatePctRemaining()

        progressStrokePaint.color = defaultColor
        borderStrokePaint.color = defaultColor
        paintHeadingLabel.color = defaultColor
        paintTimeLabel.color = defaultColor
        stopFlashing()
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density


    fun update(msRemaining: Int) {
        this.remainingSeconds = msRemaining
        updatePctRemaining()

        if (this.remainingSeconds < 15) {
            progressStrokePaint.color = warningColor
            borderStrokePaint.color = warningColor
            paintHeadingLabel.color = warningColor
            paintTimeLabel.color = warningColor

            if (this.remainingSeconds < 9) {
                if (flashingStartTimeMs == 0L) startFlashing()
            }
            else {
                stopFlashing()
            }
        }
        else {
            progressStrokePaint.color = defaultColor
            borderStrokePaint.color = defaultColor
            paintHeadingLabel.color = defaultColor
            paintTimeLabel.color = defaultColor
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

    fun pauseIfFlashing(pause: Boolean) {
        flashingPaused = pause
        if (isFlashing()) {
            alpha = warningAlpha
        }
        else {
            alpha = defaultAlpha
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawArc(borderBounds, 0f, 360f, false, borderStrokePaint)
        canvas.drawArc(progressBounds, -90f, -360 * fractionRemaining, false, progressStrokePaint)

        val labelText = formatMinutesSeconds(remainingSeconds)
        canvas.drawText(labelText, timeLabelX, timeLabelY, paintTimeLabel)
        canvas.drawText("STRUCTURE", headingLabelX, headingLabelY, paintHeadingLabel)

        if (isFlashing() && !flashingPaused) {
            val elapsedFlashingMs = (System.currentTimeMillis() - flashingStartTimeMs).toInt()

            val phase = (elapsedFlashingMs % 400) / 400f  // 0..1
            this.alpha = if (phase < 0.7f) warningAlpha else 0f

            postInvalidateOnAnimation()
        }
    }

}