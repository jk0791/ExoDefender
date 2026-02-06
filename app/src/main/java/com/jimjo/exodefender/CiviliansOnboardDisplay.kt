package com.jimjo.exodefender

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.max

class CiviliansOnboardDisplay(context: Context, attrs: AttributeSet?=null) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeWidth = 4f
    private val friendlyColor = 0xff62ab45.toInt()

    private var onboardCount = 0
    private val defaultAlpha = 0.75f

    val border = RectF()
    val person1Rect = RectF()
    val person2Rect = RectF()

    val personBitmap: Bitmap
    val cornerRadius = 20f
    private val paddingV = 15f
    private val paddingH = 20f

    var laidOutOnce = false

    // --- animation state ---
    private var s1 = 0f
    private var s2 = 0f
    private var a1: ValueAnimator? = null
    private var a2: ValueAnimator? = null

    // tweak to taste
    private val showDurationMs = 220L
    private val hideDurationMs = 160L
    private val showInterp = OvershootInterpolator(1.15f) // nice “pop”
    private val hideInterp = AccelerateDecelerateInterpolator()
    private val drawEps = 0.01f

    init {
        setWillNotDraw(false)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = friendlyColor
        strokePaint.strokeWidth = strokeWidth

        this.alpha = defaultAlpha

        personBitmap = BitmapFactory.decodeResource(resources, R.drawable.person_icon)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        a1?.cancel(); a2?.cancel()
        a1 = null; a2 = null
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!laidOutOnce) {
            border.set(
                strokeWidth / 2,
                strokeWidth / 2,
                width.toFloat() - strokeWidth / 2,
                height.toFloat() - strokeWidth / 2
            )
            person1Rect.set(
                border.left + paddingH,
                border.top + paddingV,
                border.centerX() - paddingH / 2,
                border.bottom - paddingV
            )
            person2Rect.set(
                border.centerX() + paddingH / 2,
                border.top + paddingV,
                border.right - paddingH,
                border.bottom - paddingV
            )

            // if we were already showing people before first layout, reflect that
            s1 = if (onboardCount >= 1) 1f else 0f
            s2 = if (onboardCount >= 2) 1f else 0f

            laidOutOnce = true
        }
    }

    fun update(count: Int) {
        val o = count.coerceAtLeast(0)
        if (o == onboardCount) return

        onboardCount = o

        val t1 = if (onboardCount >= 1) 1f else 0f
        val t2 = if (onboardCount >= 2) 1f else 0f

        animateScale(slot = 1, target = t1)
        animateScale(slot = 2, target = t2)

        // Border visibility is “anyone onboard” — redraw during animations too
        postInvalidateOnAnimation()
    }

    private fun animateScale(slot: Int, target: Float) {
        if (!laidOutOnce) {
            // not laid out yet: just snap; layout will fix too
            if (slot == 1) s1 = target else s2 = target
            invalidate()
            return
        }

        val current = if (slot == 1) s1 else s2
        if (kotlin.math.abs(current - target) < 0.001f) return

        val animatorRef = if (slot == 1) a1 else a2
        animatorRef?.cancel()

        val dur = if (target > current) showDurationMs else hideDurationMs
        val interp = if (target > current) showInterp else hideInterp

        val anim = ValueAnimator.ofFloat(current, target).apply {
            duration = dur
            interpolator = interp
            addUpdateListener {
                val v = it.animatedValue as Float
                if (slot == 1) s1 = v else s2 = v
                postInvalidateOnAnimation()
            }
        }

        if (slot == 1) a1 = anim else a2 = anim
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Show border if either slot is visible (during anims too)
        val anyVisible = (s1 > drawEps) || (s2 > drawEps)
        if (!anyVisible) return

        canvas.drawRoundRect(border, cornerRadius, cornerRadius, strokePaint)

        if (s1 > drawEps) drawPersonScaled(canvas, person1Rect, s1)
        if (s2 > drawEps) drawPersonScaled(canvas, person2Rect, s2)
    }

    private fun drawPersonScaled(canvas: Canvas, dst: RectF, scale: Float) {
        val cx = dst.centerX()
        val cy = dst.centerY()

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawBitmap(personBitmap, null, dst, null)
        canvas.restore()
    }
}
