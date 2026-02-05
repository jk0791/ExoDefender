package com.jimjo.exodefender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.max

enum class MarkerClickSide {
    CAMERA,       // right side (jumpTo)
    TRANSITION    // left side (transitionTo)
}
data class CameraMarker(
    val event: CameraEvent,
    var timeMs: Int,
    val cameraPos: CameraPos,
    val transitionType: CameraPosTransitionType?,
    val isSelected: Boolean = false,
    val isTransitionSelected: Boolean = false
)

class CameraTrackMarkerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // These will mirror the SeekBar's internal paddings/insets
    var trackInsetStartPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var trackInsetEndPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    // Full duration of the whole track (used for clamping / paging logic if needed)
    var totalDurationMs: Int = 1

    // Visible window
    var windowStartMs: Int = 0
        set(value) {
            field = value.coerceIn(0, totalDurationMs - windowDurationMs)
            invalidate()
        }

    var windowDurationMs: Int = 1
        set(value) {
            field = max(1, value)
            invalidate()
        }

    var markers: List<CameraMarker> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onMarkerClickListener: ((marker: CameraMarker, side: MarkerClickSide) -> Unit)? = null
    var onMarkerMovingListener: ((marker: CameraMarker, newTimeMs: Int) -> Unit)? = null
    var onMarkerMovedListener: ((marker: CameraMarker, oldTimeMs: Int, newTimeMs: Int) -> Unit)? = null

    // Load drawables
    private val chaseDrawable = context.getDrawable(R.drawable.ic_cam_chase)!!
    private val trackDrawable = context.getDrawable(R.drawable.ic_cam_track)!!
    private val fixedDrawable = context.getDrawable(R.drawable.ic_cam_fixed)!!

    private val linearDrawable = context.getDrawable(R.drawable.ic_trans_linear)!!
    private val easeInDrawable = context.getDrawable(R.drawable.ic_trans_ease_in)!!
    private val easeOutDrawable = context.getDrawable(R.drawable.ic_trans_ease_out)!!
    private val easeBothDrawable = context.getDrawable(R.drawable.ic_trans_ease_both)!!

    private val selectedHaloDrawable = context.getDrawable(R.drawable.ic_marker_selected_halo)!!

    private val density = resources.displayMetrics.density

//    private val markerLineHeightFraction = 0.6f   // 60% of view height
//    private val iconPaddingPx = dp(2f)
//    private val iconSizePx = dp(16f)              // tweak as you like

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private val camSizePx = dp(32f).toInt()
    private val transSizePx = dp(24f).toInt()
    private val gapPx = dp(-1f * density).toInt()
    private val haloSizePx = (40f * density).toInt()

    private val verticalOffsetPx = (-25f * density)      // negative = above SeekBar

    private val touchRadiusPx = 32f * density

//    val paint = Paint().apply {
//        color = 0x40FFFFFF
//        strokeWidth = 2f * density
//        isAntiAlias = true
//    }

    // Full duration of the whole track (used for clamping / paging logic if needed)

    private var activeMarker: CameraMarker? = null
    private var activeMarkerOriginalTime: Int = 0
    private var isDragging = false

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    private val markerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)         // adjust thickness if you like
        color = Color.WHITE          // or your neon green
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (markers.isEmpty()) return

        val trackLeft = paddingLeft.toFloat() + trackInsetStartPx
        val trackRight = (width - paddingRight).toFloat() - trackInsetEndPx
        val trackWidth = trackRight - trackLeft
        val centerY = height / 2f + verticalOffsetPx

        val windowEndMs = windowStartMs + windowDurationMs

        for (marker in markers) {
            if (marker.timeMs < windowStartMs || marker.timeMs > windowEndMs) {
                continue // markers off-window not drawn
            }

            val t = (marker.timeMs - windowStartMs).toFloat() / windowDurationMs.toFloat()
            val clampedT = t.coerceIn(0f, 1f)

            val x = trackLeft + trackWidth * clampedT
            drawMarker(canvas, x, centerY, marker)
        }
    }


    private fun drawMarker(canvas: Canvas, x: Float, centerY: Float, marker: CameraMarker) {
        val hasTransition = marker.transitionType != null

        // --- 1) Vertical line at marker time ---
        val lineHeight = max(camSizePx, transSizePx) * 1.2f  // a bit taller than icons
        val lineTop = centerY - lineHeight / 3f
        val lineBottom = centerY + lineHeight * 2f / 2f

        canvas.drawLine(x, lineTop, x, lineBottom, markerLinePaint)

        // --- 2) Camera type icon (RIGHT of the line) ---

        val camDrawable = when (marker.cameraPos) {
            is ChaseCameraPos -> chaseDrawable
            is TrackCameraPos -> trackDrawable
            is FixedCameraPos -> fixedDrawable
        }

        // Center camera icon horizontally to the right of the line
        val camHalf = camSizePx / 2f
        val camCenterX = x + camHalf + gapPx
        val camLeft = camCenterX - camHalf
        val camRight = camCenterX + camHalf
        val camTop = centerY - camHalf
        val camBottom = centerY + camHalf

        // Transition icon (LEFT of line), if any
        var transCenterX: Float? = null
        var transLeft = 0f
        var transRight = 0f
        var transTop = 0f
        var transBottom = 0f
        var transDrawable: Drawable? = null

        if (hasTransition) {
            transDrawable = when (marker.transitionType) {
                CameraPosTransitionType.LINEAR     -> linearDrawable
                CameraPosTransitionType.EASE_IN    -> easeInDrawable
                CameraPosTransitionType.EASE_OUT   -> easeOutDrawable
                CameraPosTransitionType.EASE_BOTH  -> easeBothDrawable
                null                               -> null
            }

            transDrawable?.let {
                val transHalf = transSizePx / 2f
                val centerX = x - gapPx - transHalf
                transCenterX = centerX
                transLeft = centerX - transHalf
                transRight = centerX + transHalf
                transTop = centerY - transHalf
                transBottom = centerY + transHalf
            }
        }

        // --- 3) Selection halo (around camera OR transition) ---

        if (marker.isSelected) {
            val halfHalo = haloSizePx / 2f

            val haloCenterX =
                if (marker.isTransitionSelected && hasTransition && transCenterX != null) {
                    transCenterX
                } else {
                    camCenterX
                }

            selectedHaloDrawable.setBounds(
                (haloCenterX - halfHalo).toInt(),
                (centerY - halfHalo).toInt(),
                (haloCenterX + halfHalo).toInt(),
                (centerY + halfHalo).toInt()
            )
            selectedHaloDrawable.draw(canvas)
        }

        // --- 4) Draw camera icon (right) ---

        camDrawable.setBounds(
            camLeft.toInt(),
            camTop.toInt(),
            camRight.toInt(),
            camBottom.toInt()
        )
        camDrawable.draw(canvas)

        // --- 5) Draw transition icon (left), if present ---

        transDrawable?.let { d ->
            d.setBounds(
                transLeft.toInt(),
                transTop.toInt(),
                transRight.toInt(),
                transBottom.toInt()
            )
            d.draw(canvas)
        }

    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (markers.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                val candidate = findNearestMarker(x, y) ?: return false

                val markerX = markerX(candidate)
                val markerY = markerCenterY()
                val dx = markerX - x
                val dy = markerY - y
                val distSq = dx * dx + dy * dy

                if (distSq <= touchRadiusPx * touchRadiusPx) {
                    // Potential click / drag start
                    downX = x
                    downY = y
                    activeMarker = candidate
                    activeMarkerOriginalTime = candidate.timeMs
                    isDragging = false  // important: we have NOT started dragging yet
                    // Don’t disallow intercept yet; only when we actually start dragging
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val marker = activeMarker ?: return false

                val x = event.x
                val y = event.y

                val dxFromDown = x - downX
                val dyFromDown = y - downY
                val distSqFromDown = dxFromDown * dxFromDown + dyFromDown * dyFromDown

                if (!isDragging) {
                    // Check if we've moved far enough to start a drag
                    if (distSqFromDown <= (touchSlop * touchSlop)) {
                        // Not enough movement yet → ignore, still a potential click
                        return true
                    }

                    // Now we officially start dragging
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                // We are dragging: update marker time + live preview
                val newTime = xToTime(x)
                if (newTime != marker.timeMs) {
                    marker.timeMs = newTime
                    // Your in-drag logic:
                    onMarkerMovingListener?.invoke(marker, newTime)
                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val marker = activeMarker

                if (marker == null) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }

                val wasDragging = isDragging

                isDragging = false
                activeMarker = null
                parent?.requestDisallowInterceptTouchEvent(false)

                if (!wasDragging) {
                    // Never moved past touch slop → treat as click
//                    onMarkerClickListener?.invoke(marker)
                    handleMarkerClick(marker, event.x)
                } else {
                    // Drag finished → commit
                    val oldTime = activeMarkerOriginalTime
                    val newTime = marker.timeMs
                    if (oldTime != newTime) {
                        onMarkerMovedListener?.invoke(marker, oldTime, newTime)
                    }
                }

                return true
            }
        }

        return false
    }

    private fun findNearestMarker(x: Float, y: Float): CameraMarker? {
        val centerY = markerCenterY()
        return markers.minByOrNull { m ->
            val mx = markerX(m)
            val dx = mx - x
            val dy = centerY - y
            dx * dx + dy * dy
        }
    }

    // helper functions
    private fun handleMarkerClick(marker: CameraMarker, touchX: Float) {
        val markerCenterX = markerX(marker)

        val side = if (marker.transitionType != null && touchX < markerCenterX) {
            MarkerClickSide.TRANSITION
        } else {
            // either right side or no transition at all → treat as camera
            MarkerClickSide.CAMERA
        }

        onMarkerClickListener?.invoke(marker, side)
    }

    private fun markerCenterY(): Float {
        val centerY = height / 2f + verticalOffsetPx
        return centerY
    }

    private fun markerX(marker: CameraMarker): Float {
        val trackLeft = paddingLeft.toFloat() + trackInsetStartPx
        val trackRight = (width - paddingRight).toFloat() - trackInsetEndPx
        val trackWidth = trackRight - trackLeft

        // Only map markers inside or near the window
        val t = ((marker.timeMs - windowStartMs).toFloat() / windowDurationMs.toFloat())
        val clampedT = t.coerceIn(0f, 1f)

        return trackLeft + trackWidth * clampedT
    }

    private fun xToTime(x: Float): Int {
        val trackLeft = paddingLeft.toFloat() + trackInsetStartPx
        val trackRight = (width - paddingRight).toFloat() - trackInsetEndPx
        val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)

        val clampedX = x.coerceIn(trackLeft, trackRight)
        val fraction = (clampedX - trackLeft) / trackWidth

        val windowTime = (windowStartMs + fraction * windowDurationMs).toInt()
        return windowTime.coerceIn(0, totalDurationMs)
    }

}
