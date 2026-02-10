package com.jimjo.exodefender

import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

object ScoreCalculatorV1 {

    @Serializable
    data class Breakdown(
        val objectiveType: Level.ObjectiveType,

        val base: Float,

        // CAS only
        val savePoints: Float = 0f,

        // DEFEND only
        val defendPoints: Float = 0f,

        // EVAC only
        val evacPoints: Float = 0f,
        val killBonus: Float = 0f,

        // Shared performance
        val healthPoints: Float,
        val accuracyPoints: Float,
        val timeBonus: Float,

        // Weights / display
        val difficultyWeight: Float,
        val accuracyRating: Int,
        val total: Int
    )

    private val ZERO = Breakdown(
        objectiveType = Level.ObjectiveType.UNKNOWN,
        base = 0f,
        healthPoints = 0f,
        accuracyPoints = 0f,
        timeBonus = 0f,
        difficultyWeight = 1f,
        accuracyRating = 0,
        total = 0
    )

    // --- Accuracy display (unchanged) ---

    fun displayAccuracyRating(shotsHit: Int, shotsFired: Int): Int {
        val fired = shotsFired.coerceAtLeast(1)
        val hit = shotsHit.coerceIn(0, fired)
        val acc = hit.toFloat() / fired.toFloat()
        return displayAccuracyRatingFromAcc(acc)
    }

    private const val LOW_KNEE = 0.12f
    private const val LOW_DISPLAY = 0.20f
    private const val HIGH_KNEE = 0.25f
    private const val HIGH_DISPLAY = 0.8f

    fun displayAccuracyRatingFromAcc(acc: Float): Int {
        require(LOW_KNEE > 0f && HIGH_KNEE > LOW_KNEE && HIGH_KNEE < 1f)

        val a = acc.coerceIn(0f, 1f)
        val d = when {
            a <= 0f -> 0f
            a < LOW_KNEE -> lerp(0.0f, LOW_DISPLAY, a / LOW_KNEE)
            a < HIGH_KNEE -> lerp(LOW_DISPLAY, HIGH_DISPLAY, (a - LOW_KNEE) / (HIGH_KNEE - LOW_KNEE))
            else -> lerp(HIGH_DISPLAY, 1.0f, (a - HIGH_KNEE) / (1.0f - HIGH_KNEE))
        }
        return (d * 100f).roundToInt()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t.coerceIn(0f, 1f)

    // --- Tunables ---

    private const val BASE_PER_ENEMY = 1000f

    // CAS
    private const val CAS_SAVE_MAX = 3000f

    // Shared
    private const val HEALTH_MAX = 1500f
    private const val ACC_MAX = 2000f

    // DEFEND
    private const val DEFEND_POINTS = 2000f
    private const val DEFEND_TIME_BONUS_MAX = 1500f

    // EVAC
    private const val EVAC_POINTS_PER_CIVILIAN = 500f
    private const val EVAC_KILL_BONUS_PER_ENEMY = 40f
    private const val EVAC_KILL_BONUS_CAP = 1200f

    // --- Public entry ---

    fun score(
        log: FlightLog,
        parTimeMs: Int? = null
    ): Breakdown {
        val lvl = log.level
        val obj = lvl?.objectiveType ?: Level.ObjectiveType.UNKNOWN
        val difficultyWeight = (lvl?.difficultyWeight ?: 1.0f).takeIf { it > 0f } ?: 1.0f

        // Only rank successful runs (as before)
        if (log.completionOutcome != CompletionOutcome.SUCCESS) {
            return ZERO.copy(objectiveType = obj, difficultyWeight = difficultyWeight)
        }

        // Require embedded level config to derive objective facts.
        val summary = lvl?.objectiveSummary()
            ?: return ZERO.copy(objectiveType = obj, difficultyWeight = difficultyWeight)

        return when (obj) {
            Level.ObjectiveType.CAS ->
                scoreCas(log, parTimeMs, difficultyWeight, summary)

            Level.ObjectiveType.DEFEND ->
                scoreDefend(log, difficultyWeight, summary)

            Level.ObjectiveType.EVAC ->
                scoreEvac(log, difficultyWeight, summary)

            else ->
                ZERO.copy(objectiveType = obj, difficultyWeight = difficultyWeight)
        }
    }

    // --- Shared perf ---

    private data class SharedPerf(
        val healthPoints: Float,
        val accuracyPoints: Float,
        val accuracyRating: Int
    )

    private fun sharedHealthAndAccuracy(log: FlightLog): SharedPerf {
        val hpFrac = log.healthRemaining.coerceIn(0f, 1f)
        val healthPoints = HEALTH_MAX * hpFrac

        val fired = log.shotsFired.coerceAtLeast(1)
        val hit = log.shotsHit.coerceIn(0, fired)
        val acc = hit.toFloat() / fired.toFloat()
        val accuracyPoints = ACC_MAX * sqrt(acc)
        val accuracyRating = displayAccuracyRatingFromAcc(acc)

        return SharedPerf(
            healthPoints = healthPoints,
            accuracyPoints = accuracyPoints,
            accuracyRating = accuracyRating
        )
    }

    // --- CAS ---

    private fun scoreCas(
        log: FlightLog,
        parTimeMs: Int?,
        difficultyWeight: Float,
        summary: Level.ObjectiveSummary
    ): Breakdown {
        val enemiesStart = summary.enemiesStart.coerceAtLeast(0)
        if (enemiesStart <= 0) return ZERO.copy(objectiveType = Level.ObjectiveType.CAS, difficultyWeight = difficultyWeight)

        // Require all enemies destroyed (rule)
        if (log.enemiesDestroyed < enemiesStart) {
            return ZERO.copy(objectiveType = Level.ObjectiveType.CAS, difficultyWeight = difficultyWeight)
        }

        val friendliesStart = summary.friendliesStart.coerceAtLeast(1)
        val friendliesEnd = log.friendliesRemaining.coerceIn(0, friendliesStart)

        val base = BASE_PER_ENEMY * enemiesStart

        val savedFrac = friendliesEnd.toFloat() / friendliesStart.toFloat()
        val savePoints = CAS_SAVE_MAX * savedFrac

        val perf = sharedHealthAndAccuracy(log)

        val timeBonus = if (parTimeMs != null && parTimeMs > 0) {
            val frac = (parTimeMs - log.flightTimeMs).toFloat() / parTimeMs.toFloat()
            val clamped = frac.coerceIn(-0.25f, 0.25f)
            500f * clamped
        } else 0f

        val raw = base + savePoints + perf.healthPoints + perf.accuracyPoints + timeBonus
        val total = round(difficultyWeight * raw).toInt().coerceAtLeast(0)

        return Breakdown(
            objectiveType = Level.ObjectiveType.CAS,
            base = base,
            savePoints = savePoints,
            healthPoints = perf.healthPoints,
            accuracyPoints = perf.accuracyPoints,
            timeBonus = timeBonus,
            difficultyWeight = difficultyWeight,
            accuracyRating = perf.accuracyRating,
            total = total
        )
    }

    // --- DEFEND ---

    private fun scoreDefend(
        log: FlightLog,
        difficultyWeight: Float,
        summary: Level.ObjectiveSummary
    ): Breakdown {
        val enemiesStart = summary.enemiesStart.coerceAtLeast(0)
        if (enemiesStart <= 0) return ZERO.copy(objectiveType = Level.ObjectiveType.DEFEND, difficultyWeight = difficultyWeight)

        // Still require all enemies destroyed (rule)
        if (log.enemiesDestroyed < enemiesStart) {
            return ZERO.copy(objectiveType = Level.ObjectiveType.DEFEND, difficultyWeight = difficultyWeight)
        }

        val base = BASE_PER_ENEMY * enemiesStart
        val defendPoints = DEFEND_POINTS

        // Duration derived from level config; clamp to 1 to avoid divide-by-zero.
        val duration = (summary.defendClockDurationMs ?: 1).coerceAtLeast(1)

        // Remaining time snapshot logged at last kill (authoritative, gameplay-semantic)
        val left = (log.clockRemainingMsAtLastKill ?: 0).coerceIn(0, duration)
        val timeFrac = left.toFloat() / duration.toFloat()
        val timeBonus = DEFEND_TIME_BONUS_MAX * timeFrac

        val perf = sharedHealthAndAccuracy(log)

        val raw = base + defendPoints + perf.healthPoints + perf.accuracyPoints + timeBonus
        val total = round(difficultyWeight * raw).toInt().coerceAtLeast(0)

        return Breakdown(
            objectiveType = Level.ObjectiveType.DEFEND,
            base = base,
            defendPoints = defendPoints,
            healthPoints = perf.healthPoints,
            accuracyPoints = perf.accuracyPoints,
            timeBonus = timeBonus,
            difficultyWeight = difficultyWeight,
            accuracyRating = perf.accuracyRating,
            total = total
        )
    }

    // --- EVAC ---

    private fun scoreEvac(
        log: FlightLog,
        difficultyWeight: Float,
        summary: Level.ObjectiveSummary
    ): Breakdown {
        val civiliansStart = summary.civiliansStart.coerceAtLeast(0)
        val evacPoints = EVAC_POINTS_PER_CIVILIAN * civiliansStart

        val killBonus = (EVAC_KILL_BONUS_PER_ENEMY * log.enemiesDestroyed)
            .coerceAtMost(EVAC_KILL_BONUS_CAP)

        val perf = sharedHealthAndAccuracy(log)
        val timeBonus = 0f

        val raw = evacPoints + killBonus + perf.healthPoints + perf.accuracyPoints + timeBonus
        val total = round(difficultyWeight * raw).toInt().coerceAtLeast(0)

        return Breakdown(
            objectiveType = Level.ObjectiveType.EVAC,
            base = 0f,
            evacPoints = evacPoints,
            killBonus = killBonus,
            healthPoints = perf.healthPoints,
            accuracyPoints = perf.accuracyPoints,
            timeBonus = timeBonus,
            difficultyWeight = difficultyWeight,
            accuracyRating = perf.accuracyRating,
            total = total
        )
    }
}
