package com.jimjo.exodefender

import android.view.View.GONE
import android.view.View.VISIBLE

class ScreenOverlayReplayController(val screenOverlay: ScreenOverlay) {

    // for managing seek bar fade
    val autoHideDelayMs = 1400L
    val fadeDurationMs = 350L

    // show seek bar + fade out
    fun scheduleAutoHide() {
        if (screenOverlay.replayEditorVisible) return

        screenOverlay.mapReplaySeekBarLayout.removeCallbacks(autoHideRunnable)
        screenOverlay.mapReplaySeekBarLayout.postDelayed(autoHideRunnable, autoHideDelayMs)
    }

    val autoHideRunnable = Runnable {
        // only hide if user isn’t currently scrubbing
        if (!screenOverlay.gLView.renderer.flightLog.replaySeeking && !screenOverlay.replayEditorVisible) {
            hideReplayControls()
        }
    }

    fun showReplayControls(immediate: Boolean = false) {
        if (screenOverlay.replayEditorVisible || !screenOverlay.replayMode) return

        showChrome(screenOverlay.mapReplaySeekBarLayout, immediate)
        showChrome(screenOverlay.cameraModeLayout, immediate)   // <-- add this

        scheduleAutoHide()
    }

    fun hideReplayControls() {
        if (screenOverlay.replayEditorVisible || !screenOverlay.replayMode) return

        hideChrome(screenOverlay.mapReplaySeekBarLayout)
        hideChrome(screenOverlay.cameraModeLayout)              // <-- add this
    }

    private var lastControlsRevealMs = 0L
    private val revealCooldownMs = 200L

    fun revealControlsFromAnyTouch() {
        if (!screenOverlay.replayMode) return
        if (screenOverlay.replayEditorVisible) return
        // If you also hide controls when paused/ended, gate it here if desired.

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastControlsRevealMs < revealCooldownMs) return
        lastControlsRevealMs = now

        showReplayControls()   // your existing helper that sets visible + schedules auto-hide
    }

    private fun showChrome(v: android.view.View, immediate: Boolean = false) {
        v.clearAnimation()
        v.animate().cancel()

        v.visibility = VISIBLE
        v.alpha = if (immediate) 1f else v.alpha

        v.animate()
            .alpha(1f)
            .setDuration(if (immediate) 0L else 150L)
            .start()
    }

    private fun hideChrome(v: android.view.View) {
        v.clearAnimation()
        v.animate().cancel()

        v.animate()
            .alpha(0f)
            .setDuration(fadeDurationMs)
            .withEndAction {
                v.visibility = GONE // important: don’t intercept touches
            }
            .start()
    }


    fun resetReplayToStart(pauseAtStart: Boolean = true) {
        if (!screenOverlay.replayMode) return


        // Stop playback while we reposition (optional but feels clean)
        screenOverlay.gLView.renderer.flightLog.replaySeeking = true
        screenOverlay.gLView.setPause(true)

        screenOverlay.gLView.renderer.resetGame()

        // Reset zoom/window to full timeline
        screenOverlay.windowStartMs = 0
        screenOverlay.windowDurationMs = screenOverlay.timelineTotalMs

        screenOverlay.markerOverlay.totalDurationMs = screenOverlay.timelineTotalMs
        screenOverlay.markerOverlay.windowDurationMs = screenOverlay.windowDurationMs
        screenOverlay.markerOverlay.windowStartMs = screenOverlay.windowStartMs

        // Clear marker selection state (optional)
        screenOverlay.lastClickedEvent = null
        screenOverlay.lastClickedSide = null
        // If you want: refreshMarkers(null)

        // Jump playhead + UI to start
        screenOverlay.setSeekBarToTimeInWindowWithoutListener(0)
        screenOverlay.gLView.renderer.replaySeekBarChanged(0, seekingFinished = true)

        // Set play/pause button + paused state
        screenOverlay.setReplayPause(pauseAtStart)

        // Done seeking
        screenOverlay.gLView.renderer.flightLog.replaySeeking = false

        showReplayControls(immediate = true)
        scheduleAutoHide()
        screenOverlay.updateZoomLabel()
        screenOverlay.updateScreenDisplay()

        // If replay had ended, un-finalize everything
        screenOverlay.setReplayEndState(false)
    }

}