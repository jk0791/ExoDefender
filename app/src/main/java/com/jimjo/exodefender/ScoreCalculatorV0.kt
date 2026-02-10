package com.jimjo.exodefender

import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

//object ScoreCalculatorV0 {
//
//    @Serializable
//    data class Breakdown(
//        val base: Float,
//        val savePoints: Float,
//        val healthPoints: Float,
//        val accuracyPoints: Float,
//        val timeBonus: Float,
//        val ratioWeight: Float,
//        val mixWeight: Float,
//        val difficultyWeight: Float,
//        val accuracyRating: Int,
//        val total: Int
//    )
//
//    /**
//     * Level/run-independent inputs used to compute difficulty weights.
//     * This lets you show weights in admin UI without needing a FlightLog.
//     */
//    data class DifficultyParameters(
//        val friendliesStart: Int,
//        val enemiesStart: Int,
//        val enemyThreatSum: Float
//    )
//
//    private val ZERO = Breakdown(0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 0, 0)
//
//    /**
//     * Pure function: compute (ratioWeight, mixWeight, difficultyWeight) from inputs.
//     * Safe to call from admin UI / level browser without playing the level.
//     */
//    fun difficultyWeightFrom(input: DifficultyParameters): Triple<Float, Float, Float> {
//        val E0 = input.enemiesStart.coerceAtLeast(0)
//        val F0 = input.friendliesStart.coerceAtLeast(1)
//
//        // No enemies => no meaningful difficulty multiplier.
//        if (E0 <= 0) return Triple(1f, 1f, 1f)
//
//        // Ratio (more enemies per friendly => harder)
//        val r = E0.toFloat() / F0.toFloat()
//        val ratioWeight = (0.8f + 0.6f * r).coerceIn(0.8f, 2.2f)
//
//        // Threat mix (avg threat per enemy). If not supplied, fall back to "1 threat each".
//        val threatSum = input.enemyThreatSum.takeIf { it > 0f } ?: E0.toFloat()
//        val avgThreat = threatSum / E0.toFloat()
//        val mixWeight = avgThreat.coerceIn(1.0f, 2.0f)
//
//        val difficultyWeight = ratioWeight * mixWeight
//        return Triple(ratioWeight, mixWeight, difficultyWeight)
//    }
//
//    /**
//     * Convenience for admin UI: just return the final weight.
//     */
//    fun difficultyWeightOnly(input: DifficultyParameters): Float =
//        difficultyWeightFrom(input).third
//
//
//    /** Player-facing "Accuracy Rating" in 0..100 derived from raw accuracy (0..1). */
//    fun displayAccuracyRating(shotsHit: Int, shotsFired: Int): Int {
//        val fired = shotsFired.coerceAtLeast(1)
//        val hit = shotsHit.coerceIn(0, fired)
//        val acc = hit.toFloat() / fired.toFloat()
//        return displayAccuracyRatingFromAcc(acc)
//    }
//
//    private const val LOW_KNEE = 0.12f
//    private const val LOW_DISPLAY = 0.20f    // display value at LOW_KNEE
//    private const val HIGH_KNEE = 0.25f
//    private const val HIGH_DISPLAY = 0.8f   // display value at HIGH_KNEE
//
//    fun displayAccuracyRatingFromAcc(acc: Float): Int {
//
//        require(LOW_KNEE > 0f && HIGH_KNEE > LOW_KNEE && HIGH_KNEE < 1f)
//
//        val a = acc.coerceIn(0f, 1f)
//
//        val d = when {
//            a <= 0f -> 0f
//
//            a < LOW_KNEE -> {
//                // 0 -> 0, LOW_KNEE -> LOW_DISPLAY
//                lerp(0.0f, LOW_DISPLAY, a / LOW_KNEE)
//            }
//
//            a < HIGH_KNEE -> {
//                // LOW_KNEE -> LOW_DISPLAY, HIGH_KNEE -> HIGH_DISPLAY
//                lerp(
//                    LOW_DISPLAY,
//                    HIGH_DISPLAY,
//                    (a - LOW_KNEE) / (HIGH_KNEE - LOW_KNEE)
//                )
//            }
//
//            else -> {
//                // HIGH_KNEE -> HIGH_DISPLAY, 1.0 -> 1.0
//                lerp(
//                    HIGH_DISPLAY,
//                    1.0f,
//                    (a - HIGH_KNEE) / (1.0f - HIGH_KNEE)
//                )
//            }
//        }
//
//        return (d * 100f).roundToInt()
//    }
//
//    private fun lerp(a: Float, b: Float, t: Float): Float =
//        a + (b - a) * t.coerceIn(0f, 1f)
//
//    /**
//     * Main scoring function (run-based). Still uses FlightLog to score performance,
//     * but computes difficulty weights via difficultyWeightFrom(...) so the algorithm
//     * is shared with admin tooling.
//     */
//    fun score(
//        log: FlightLog,
//        parTimeMs: Int? = null
//    ): Breakdown {
//        // Only rank successful runs; also require all enemies destroyed.
//        if (log.completionOutcome != CompletionOutcome.SUCCESS || log.enemiesDestroyed < log.enemiesStart) {
//            return ZERO
//        }
//
//        val E0 = log.enemiesStart.coerceAtLeast(0)
//        if (E0 <= 0) return ZERO
//
//        val F0 = log.friendliesStart.coerceAtLeast(1)
//        val Fend = log.friendliesRemaining.coerceIn(0, F0)
//
//        val base = 1000f * E0
//
//        val savedFrac = Fend.toFloat() / F0.toFloat()
//        val savePoints = 3000f * savedFrac
//
//        val hpFrac = log.healthRemaining.coerceIn(0f, 1f)
//        val healthPoints = 1500f * hpFrac
//
//        val fired = log.shotsFired.coerceAtLeast(1)
//        val hit = log.shotsHit.coerceIn(0, fired)
//        val acc = hit.toFloat() / fired.toFloat()
//        val accuracyPoints = 2000f * sqrt(acc)
//        val accuracyRating = displayAccuracyRatingFromAcc(acc)
//
//        val timeBonus = if (parTimeMs != null && parTimeMs > 0) {
//            val frac = (parTimeMs - log.flightTimeMs).toFloat() / parTimeMs.toFloat()
//            val clamped = frac.coerceIn(-0.25f, 0.25f)
//            500f * clamped
//        } else 0f
//
//        // Difficulty weights via shared helper (so admin UI can use same logic)
//        val input = DifficultyParameters(
//            enemiesStart = E0,
//            friendliesStart = F0,
//            enemyThreatSum = log.enemyThreatSum
//        )
//        val (ratioWeight, mixWeight, difficultyWeight) = difficultyWeightFrom(input)
//
//        val raw = base + savePoints + healthPoints + accuracyPoints + timeBonus
//        val total = round(difficultyWeight * raw).toInt().coerceAtLeast(0)
//
//        return Breakdown(
//            base = base,
//            savePoints = savePoints,
//            healthPoints = healthPoints,
//            accuracyPoints = accuracyPoints,
//            timeBonus = timeBonus,
//            ratioWeight = ratioWeight,
//            mixWeight = mixWeight,
//            difficultyWeight = difficultyWeight,
//            accuracyRating = accuracyRating,
//            total = total
//        )
//    }
//}
