package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

interface JoystickDisplaylListener {
    fun joystickDisplayClicked(caller: JoystickDisplay)
}

class JoystickDisplay(context: Context, attrs: AttributeSet?=null) :
    View(context, attrs) {

    lateinit private var parent: ScreenOverlay
    private val strokePaint = Paint()
    private val fillPaint = Paint()

    val border = RectF()

    private val pointerRadius = 15f
    val cornerRadius = 20f
    val pointerCornerRadius = 10f

    private var clickable = false

    private var xFactor = 0f
    private var yFactor = 0f
    private var defaultAlpha = 0.2f // 0.25f
    private var activeAlpha = 0.6f // 0.7f

    private var brightenOnUpdate = false

    private var alphaCountdownStart = 20
    private val alphaIncrement = (activeAlpha - defaultAlpha) / alphaCountdownStart
    private var alphaCountdown = 0

    private var firstTimeLayout = true
    private var displayRange = 0f
    private var center = 0f

    private var displayFrozen = false

    init {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 5f

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.LTGRAY

        this.alpha = defaultAlpha
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (clickable) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.joystickDisplayClicked(this)
                    this.alpha = activeAlpha
                    alphaCountdown = alphaCountdownStart
                    postInvalidate()
                }
            }
        }
        return true
    }

    fun initialize(parent: ScreenOverlay, defaultAlpha: Float, activeAlpha: Float, brightenOnUpdate: Boolean, clickable: Boolean) {
        this.parent = parent
        this.defaultAlpha = defaultAlpha
        this.activeAlpha = activeAlpha

        alphaCountdownStart = ((activeAlpha - defaultAlpha) * 70).toInt()

        this.brightenOnUpdate = brightenOnUpdate
        this.clickable = clickable
        this.alpha = defaultAlpha
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (firstTimeLayout) {
            displayRange = this.width / 2f - pointerRadius
            center = this.width / 2f
            firstTimeLayout = false
            border.set(0f, 0f, this.width.toFloat(), this.height.toFloat())
        }
    }

    fun reset() {
        this.xFactor = 0f
        this.yFactor = 0f
        displayFrozen = false
    }

    fun setDisplayState(frozen: Boolean) {
        displayFrozen = frozen

    }

    fun update(xFactor: Float, yFactor: Float) {
        if (!displayFrozen) {
            this.xFactor = xFactor
            this.yFactor = yFactor

            if (brightenOnUpdate) {
                alphaCountdown = alphaCountdownStart
                this.alpha = activeAlpha
            }
            postInvalidate()
        }
    }



    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val x = center + displayRange * xFactor
        val y = center + displayRange * yFactor

        canvas.drawRoundRect(border, cornerRadius, cornerRadius, strokePaint)
        if (!brightenOnUpdate || alphaCountdown != 0) {
//            canvas.drawRect(x - pointerRadius, y - pointerRadius, x + pointerRadius, y + pointerRadius, strokePaint)
            canvas.drawRoundRect(
                x - pointerRadius,
                y - pointerRadius,
                x + pointerRadius,
                y + pointerRadius,
                pointerCornerRadius,
                pointerCornerRadius,
                fillPaint
            )
        }

//        if (brightenOnUpdate) {
            if (alphaCountdown > 0) {
                alphaCountdown--
                this.alpha = alphaCountdown * alphaIncrement + defaultAlpha
                if (alphaCountdown == 0) {
                    this.alpha = defaultAlpha
                }
                postInvalidate()
            }
//        }
    }

}