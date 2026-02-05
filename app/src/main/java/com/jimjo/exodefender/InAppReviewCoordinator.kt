package com.jimjo.exodefender

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

class InAppReviewCoordinator(
    private val activity: Activity
) {

    private val mainActivity = activity as MainActivity
    private val prefs: SharedPreferences =
        activity.getSharedPreferences("in_app_review", Context.MODE_PRIVATE)

    private val manager: ReviewManager = ReviewManagerFactory.create(activity)

    // Your milestones (map your completedLevelIndex to this list)
    private val milestoneIndices = setOf(4, 9, 14, 19, 29, 39, 49, 59, 69, 79, 89)

    // Your own cooldowns (Play has its own quota too; this avoids pointless calls).
    private val minDaysBetweenAttempts = 30L
    private val minMinutesAfterAppStart = 2L

    private val appStartElapsedMs: Long = SystemClock.elapsedRealtime()

    fun maybeAskForReview(completedLevelIndex: Int, firstTimeClear: Boolean, extraLogData: String = "", bypassGuards: Boolean = false) {

        if (!bypassGuards) {
            if (!firstTimeClear) return
            if (!milestoneIndices.contains(completedLevelIndex)) return
            if (!cooldownOk()) return
            if (!afterAppStartOk()) return
        }

        // Mark attempt before calling (so crashes / re-entries don’t spam attempts).
        markAttempt()

        mainActivity.log.printout("InAppReview: Requesting review. levelIndex=$completedLevelIndex $extraLogData")

        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                val ex = task.exception
                mainActivity.log.printout("InAppReview: requestReviewFlow failed: ${ex?.javaClass?.simpleName}: ${ex?.message}")
                return@addOnCompleteListener
            }

            val reviewInfo = task.result
            val flow = manager.launchReviewFlow(activity, reviewInfo)
            flow.addOnCompleteListener { flowTask ->
                // This does NOT tell you whether a review was shown/submitted; just that flow finished.
                mainActivity.logMiscActivity(ActivityCode.APP_REVIEW_REQUESTED, null, "InAppReview: launchReviewFlow finished. success=${flowTask.isSuccessful}")
            }
        }
    }

    private fun cooldownOk(): Boolean {
        val lastAttemptUtcMs = prefs.getLong(KEY_LAST_ATTEMPT_UTC_MS, 0L)
        if (lastAttemptUtcMs == 0L) return true

        val nowUtcMs = System.currentTimeMillis()
        val deltaMs = nowUtcMs - lastAttemptUtcMs
        val minMs = TimeUnit.DAYS.toMillis(minDaysBetweenAttempts)
        return deltaMs >= minMs
    }

    private fun afterAppStartOk(): Boolean {
        val elapsed = SystemClock.elapsedRealtime() - appStartElapsedMs
        return elapsed >= TimeUnit.MINUTES.toMillis(minMinutesAfterAppStart)
    }

    private fun markAttempt() {
        prefs.edit { putLong(KEY_LAST_ATTEMPT_UTC_MS, System.currentTimeMillis()) }
    }

    companion object {
        private const val KEY_LAST_ATTEMPT_UTC_MS = "last_attempt_utc_ms"
    }
}
