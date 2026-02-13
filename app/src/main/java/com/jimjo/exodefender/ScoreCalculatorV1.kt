package com.jimjo.exodefender

import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

object ScoreCalculatorV1 {

    private fun buildDetailsJson(
        b: Breakdown,
        shotsFired: Int,
        shotsHit: Int,
    ): String {
        val obj = buildJsonObject {
            put("difficulty_weight", JsonPrimitive(b.difficultyWeight.toDouble()))

            put("enemies_start", JsonPrimitive(b.enemiesStart))
            put("enemies_destroyed", JsonPrimitive(b.enemiesDestroyed))

            if (b.friendliesStart > 0) put("friendlies_start", JsonPrimitive(b.friendliesStart))
            if (b.friendliesStart > 0) put("friendlies_saved", JsonPrimitive(b.friendliesSaved))

            if (b.civiliansStart > 0) put("civilians_start", JsonPrimitive(b.civiliansStart))

            put("shots_fired", JsonPrimitive(shotsFired))
            put("shots_hit", JsonPrimitive(shotsHit))

            put("ship_integrity", JsonPrimitive(b.shipIntegrity.toDouble()))

            // store raw acc as fraction for correctness (UI formats)
            val fired = shotsFired.coerceAtLeast(1)
            val hit = shotsHit.coerceIn(0, fired)
            val acc = hit.toDouble() / fired.toDouble()
            put("accuracy_raw", JsonPrimitive(acc))

            // Optional: store which bonuses applied (handy for debugging)
            b.friendliesBonus?.let { put("friendlies_bonus", JsonPrimitive(it.toDouble())) }
            b.timeBonus?.let { put("time_bonus", JsonPrimitive(it.toDouble())) }
            b.enemiesBonus?.let { put("enemies_bonus", JsonPrimitive(it.toDouble())) }

            b.timeRemainingMs?.let { put("time_remaining_ms", JsonPrimitive(it)) }
        }
        return obj.toString()
    }

    private fun buildHighlightsJson(b: Breakdown): String {
        val obj = buildJsonObject {
            put("difficulty_weight", JsonPrimitive(b.difficultyWeight.toDouble()))
            put("ship_integrity", JsonPrimitive(b.shipIntegrity.toDouble()))
            put("accuracy_rating", JsonPrimitive(b.accuracyRating))

            when (b.objectiveType) {
                Level.ObjectiveType.CAS,
                Level.ObjectiveType.DEFEND -> {
                    put("friendlies_saved", JsonPrimitive(b.friendliesSaved))
                    put("friendlies_start", JsonPrimitive(b.friendliesStart))
                }
                Level.ObjectiveType.EVAC -> {
                    put("enemies_destroyed", JsonPrimitive(b.enemiesDestroyed))
                    put("enemies_start", JsonPrimitive(b.enemiesStart))
                }
                else -> {}
            }

            b.timeRemainingMs?.let { put("time_remaining_ms", JsonPrimitive(it)) }
        }
        return obj.toString()
    }

    @Serializable
    data class Breakdown(
        val objectiveType: Level.ObjectiveType,

        // all objectives
        val basePoints: Int,
        val healthBonus: Int,
        val accuracyBonus: Int,

        // objective-specific
        val friendliesBonus: Int? = null, // CAS, DEFEND only
        val timeBonus: Int? = null,       // DEFEND only
        val enemiesBonus: Int? = null,    // EVAC only

        // Weights / display
        val difficultyWeight: Float,
        val total: Int,

        val accuracyRating: Int,
        val shipIntegrity: Float,

        val enemiesStart: Int = 0,
        val enemiesDestroyed: Int = 0,
        val friendliesStart: Int = 0,
        val friendliesSaved: Int = 0,
        val civiliansStart: Int = 0,
        val timeRemainingMs: Int? = null,   // DEFEND only

        // JSON payloads for server
        val detailsJson: String? = null,
        val highlightsJson: String? = null
    )

    private val ZERO = Breakdown(
        objectiveType = Level.ObjectiveType.UNKNOWN,
        basePoints = 0,
        healthBonus = 0,
        accuracyBonus = 0,
        difficultyWeight = 1f,
        total = 0,
        accuracyRating = 0,
        shipIntegrity = 0f
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

    private const val BASE_PER_ENEMY = 180

    // CAS
    private const val CAS_SAVE_MAX = 3000

    // Shared
    private const val HEALTH_MAX = 1500
    private const val ACC_MAX = 2000

    // DEFEND
    private const val STRUCTURE_SAVE_POINTS = 2000
    private const val DEFEND_TIME_BONUS_MAX = 1500
    private const val DEFEND_FRIENDLIES_SAVE_MAX = 2000

    // EVAC
    private const val EVAC_POINTS_PER_CIVILIAN = 1000
    private const val EVAC_KILL_BONUS_PER_ENEMY = 240

    // --- Public entry ---

    fun score(log: FlightLog): Breakdown {
        val lvl = log.level
        val obj = lvl?.objectiveType ?: Level.ObjectiveType.UNKNOWN
        val difficultyWeight = (lvl?.difficultyWeight ?: 1.0f).takeIf { it > 0f } ?: 1.0f

        // Only rank successful runs
        if (log.completionOutcome != CompletionOutcome.SUCCESS) {
            return ZERO.copy(objectiveType = obj, difficultyWeight = difficultyWeight)
        }

        val summary = lvl?.objectiveSummary()
            ?: return ZERO.copy(objectiveType = obj, difficultyWeight = difficultyWeight)

        return when (obj) {
            Level.ObjectiveType.CAS -> scoreCas(log, difficultyWeight, summary)
            Level.ObjectiveType.DEFEND -> scoreDefend(log, difficultyWeight, summary)
            Level.ObjectiveType.EVAC -> scoreEvac(log, difficultyWeight, summary)
            else -> ZERO.copy(objectiveType = obj, difficultyWeight = difficultyWeight)
        }
    }

    // --- Shared perf ---

    private data class SharedPerf(
        val healthPoints: Int,
        val accuracyPoints: Int,
        val accuracyRating: Int
    )

    private fun sharedHealthAndAccuracy(log: FlightLog): SharedPerf {
        val hpFrac = log.healthRemaining.coerceIn(0f, 1f)
        val healthPoints = (HEALTH_MAX * hpFrac).roundToInt()

        val fired = log.shotsFired.coerceAtLeast(1)
        val hit = log.shotsHit.coerceIn(0, fired)
        val acc = hit.toFloat() / fired.toFloat()
        val accuracyPoints = (ACC_MAX * sqrt(acc)).roundToInt()
        val accuracyRating = displayAccuracyRatingFromAcc(acc)

        return SharedPerf(
            healthPoints = healthPoints,
            accuracyPoints = accuracyPoints,
            accuracyRating = accuracyRating
        )
    }

    private fun calcFriendliesSavedPoints(start: Int, remaining: Int, maxBonus: Int): Int {
        if (start <= 0 || maxBonus <= 0) return 0
        val clampedRemaining = remaining.coerceIn(0, start)
        val savedFrac = clampedRemaining.toFloat() / start.toFloat()
        return (maxBonus * savedFrac.coerceIn(0f, 1f)).roundToInt()
    }

    // --- CAS ---

    private fun scoreCas(
        log: FlightLog,
        difficultyWeight: Float,
        summary: Level.ObjectiveSummary
    ): Breakdown {
        val enemiesStart = summary.enemiesStart.coerceAtLeast(0)
        if (enemiesStart <= 0) return ZERO.copy(objectiveType = Level.ObjectiveType.CAS, difficultyWeight = difficultyWeight)

        // Require all enemies destroyed
        if (log.enemiesDestroyed < enemiesStart) {
            return ZERO.copy(objectiveType = Level.ObjectiveType.CAS, difficultyWeight = difficultyWeight)
        }

        val base = BASE_PER_ENEMY * enemiesStart

        val friendliesStart = summary.friendliesStart.coerceAtLeast(0)
        val friendliesRemaining = log.friendliesRemaining.coerceIn(0, friendliesStart)
        val friendliesPoints = calcFriendliesSavedPoints(friendliesStart, friendliesRemaining, CAS_SAVE_MAX)

        val perf = sharedHealthAndAccuracy(log)

        val raw = base + friendliesPoints + perf.healthPoints + perf.accuracyPoints
        val total = (difficultyWeight * raw).roundToInt().coerceAtLeast(0)

        val b0 = Breakdown(
            objectiveType = Level.ObjectiveType.CAS,
            basePoints = base,
            healthBonus = perf.healthPoints,
            accuracyBonus = perf.accuracyPoints,
            friendliesBonus = friendliesPoints,
            timeBonus = null,
            enemiesBonus = null,
            difficultyWeight = difficultyWeight,
            total = total,
            accuracyRating = perf.accuracyRating,
            shipIntegrity = log.healthRemaining,
            enemiesStart = enemiesStart,
            enemiesDestroyed = log.enemiesDestroyed,
            friendliesStart = friendliesStart,
            friendliesSaved = friendliesRemaining,
            civiliansStart = summary.civiliansStart.coerceAtLeast(0)
        )

        val detailsJson = buildDetailsJson(b0, log.shotsFired, log.shotsHit)
        val highlightsJson = buildHighlightsJson(b0)

        return b0.copy(detailsJson = detailsJson, highlightsJson = highlightsJson)
    }

    // --- DEFEND ---

    private fun scoreDefend(
        log: FlightLog,
        difficultyWeight: Float,
        summary: Level.ObjectiveSummary
    ): Breakdown {
        val enemiesStart = summary.enemiesStart.coerceAtLeast(0)
        if (enemiesStart <= 0) return ZERO.copy(objectiveType = Level.ObjectiveType.DEFEND, difficultyWeight = difficultyWeight)

        // Require all enemies destroyed
        if (log.enemiesDestroyed < enemiesStart) {
            return ZERO.copy(objectiveType = Level.ObjectiveType.DEFEND, difficultyWeight = difficultyWeight)
        }

        val base = BASE_PER_ENEMY * enemiesStart + STRUCTURE_SAVE_POINTS

        val friendliesStart = summary.friendliesStart.coerceAtLeast(0)
        val friendliesRemaining = log.friendliesRemaining.coerceIn(0, friendliesStart)
        val friendliesPoints = calcFriendliesSavedPoints(friendliesStart, friendliesRemaining, DEFEND_FRIENDLIES_SAVE_MAX)

        val duration = (summary.defendClockDurationMs ?: 1).coerceAtLeast(1)
        val left = (log.clockRemainingMsAtLastKill ?: 0).coerceIn(0, duration)
        val timeFrac = left.toFloat() / duration.toFloat()
        val timeBonus = (DEFEND_TIME_BONUS_MAX * timeFrac).roundToInt()

        val perf = sharedHealthAndAccuracy(log)

        val raw = base + friendliesPoints + perf.healthPoints + perf.accuracyPoints + timeBonus
        val total = (difficultyWeight * raw).roundToInt().coerceAtLeast(0)

        val b0 = Breakdown(
            objectiveType = Level.ObjectiveType.DEFEND,
            basePoints = base,
            healthBonus = perf.healthPoints,
            accuracyBonus = perf.accuracyPoints,
            friendliesBonus = friendliesPoints,
            timeBonus = timeBonus,
            enemiesBonus = null,
            difficultyWeight = difficultyWeight,
            total = total,
            accuracyRating = perf.accuracyRating,
            shipIntegrity = log.healthRemaining,
            enemiesStart = enemiesStart,
            enemiesDestroyed = log.enemiesDestroyed,
            friendliesStart = friendliesStart,
            friendliesSaved = friendliesRemaining,
            civiliansStart = summary.civiliansStart.coerceAtLeast(0),
            timeRemainingMs = left
        )

        val detailsJson = buildDetailsJson(b0, log.shotsFired, log.shotsHit)
        val highlightsJson = buildHighlightsJson(b0)

        return b0.copy(detailsJson = detailsJson, highlightsJson = highlightsJson)
    }

    // --- EVAC ---

    private fun scoreEvac(
        log: FlightLog,
        difficultyWeight: Float,
        summary: Level.ObjectiveSummary
    ): Breakdown {
        val civiliansStart = summary.civiliansStart.coerceAtLeast(0)
        val base = EVAC_POINTS_PER_CIVILIAN * civiliansStart

        val killBonus = EVAC_KILL_BONUS_PER_ENEMY * log.enemiesDestroyed

        val perf = sharedHealthAndAccuracy(log)

        val raw = base + killBonus + perf.healthPoints + perf.accuracyPoints
        val total = (difficultyWeight * raw).roundToInt().coerceAtLeast(0)

        val enemiesStart = summary.enemiesStart.coerceAtLeast(0)

        val b0 = Breakdown(
            objectiveType = Level.ObjectiveType.EVAC,
            basePoints = base,
            healthBonus = perf.healthPoints,
            accuracyBonus = perf.accuracyPoints,
            friendliesBonus = null,
            timeBonus = null,
            enemiesBonus = killBonus,
            difficultyWeight = difficultyWeight,
            total = total,
            accuracyRating = perf.accuracyRating,
            shipIntegrity = log.healthRemaining,
            enemiesStart = enemiesStart,
            enemiesDestroyed = log.enemiesDestroyed,
            civiliansStart = civiliansStart
        )

        val detailsJson = buildDetailsJson(b0, log.shotsFired, log.shotsHit)
        val highlightsJson = buildHighlightsJson(b0)

        return b0.copy(detailsJson = detailsJson, highlightsJson = highlightsJson)
    }
}
