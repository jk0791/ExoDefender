package com.jimjo.exodefender

import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

object ScoreCalculatorV1 {

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    private fun buildDetailsJson(
        b: Breakdown,
        shotsFired: Int,
        shotsHit: Int,
        shipIntegrity: Float
    ): String {
        val obj = buildJsonObject {
            put("difficulty_weight", JsonPrimitive(b.difficultyWeight.toDouble()))

            put("enemies_start", JsonPrimitive(b.enemiesStart))
            put("enemies_destroyed", JsonPrimitive(b.enemiesDestroyed))

            if (b.friendliesStart > 0) put("friendlies_start", JsonPrimitive(b.friendliesStart))
            if (b.friendliesSaved > 0 || b.friendliesStart > 0) put("friendlies_saved", JsonPrimitive(b.friendliesSaved))

            if (b.civiliansStart > 0) put("civilians_start", JsonPrimitive(b.civiliansStart))

            put("shots_fired", JsonPrimitive(shotsFired))
            put("shots_hit", JsonPrimitive(shotsHit))

            put("ship_integrity", JsonPrimitive(shipIntegrity.toDouble()))

            // store raw acc as fraction for correctness (UI formats)
            val fired = shotsFired.coerceAtLeast(1)
            val hit = shotsHit.coerceIn(0, fired)
            val acc = hit.toDouble() / fired.toDouble()
            put("accuracy", JsonPrimitive(acc))

            b.timeRemainingMs?.let { put("time_remaining_ms", JsonPrimitive(it)) }
        }
        return obj.toString()
    }

    private fun buildHighlightsJson(
        b: Breakdown,
        shipIntegrity: Float
    ): String {
        val obj = buildJsonObject {
            put("difficulty_weight", JsonPrimitive(b.difficultyWeight.toDouble()))
            put("ship_integrity", JsonPrimitive(shipIntegrity.toDouble()))
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
                    // Civilians are implied by success per your design; omit unless you want it shown.
                }
                else -> {}
            }

            b.timeRemainingMs?.let {
                // store ms; UI formats 0:12
                put("time_remaining_ms", JsonPrimitive(it))
            }
        }
        return obj.toString()
    }

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
        val total: Int,

        // ---- NEW: raw facts for UI (so MissionSummaryView doesn’t guess)
        val enemiesStart: Int = 0,
        val enemiesDestroyed: Int = 0,
        val friendliesStart: Int = 0,
        val friendliesSaved: Int = 0,
        val civiliansStart: Int = 0,
        val timeRemainingMs: Int? = null,   // DEFEND

        // ---- NEW: JSON payloads for server (Option 1 expects strings)
        val detailsJson: String? = null,
        val highlightsJson: String? = null
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

    fun score(log: FlightLog,): Breakdown {
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
                scoreCas(log, difficultyWeight, summary)

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

        val timeBonus = 0f

        val raw = base + savePoints + perf.healthPoints + perf.accuracyPoints + timeBonus
        val total = round(difficultyWeight * raw).toInt().coerceAtLeast(0)

        val b0 = Breakdown(
            objectiveType = Level.ObjectiveType.CAS,
            base = base,
            savePoints = savePoints,
            healthPoints = perf.healthPoints,
            accuracyPoints = perf.accuracyPoints,
            timeBonus = timeBonus,
            difficultyWeight = difficultyWeight,
            accuracyRating = perf.accuracyRating,
            total = total,
            enemiesStart = enemiesStart,
            enemiesDestroyed = log.enemiesDestroyed,
            friendliesStart = friendliesStart,
            friendliesSaved = friendliesEnd,
            civiliansStart = summary.civiliansStart.coerceAtLeast(0)
        )

        val detailsJson = buildDetailsJson(b0, log.shotsFired, log.shotsHit, log.healthRemaining)
        val highlightsJson = buildHighlightsJson(b0, log.healthRemaining)

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

        val friendliesStart = summary.friendliesStart.coerceAtLeast(1)
        val friendliesEnd = log.friendliesRemaining.coerceIn(0, friendliesStart)

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

        val b0 = Breakdown(
            objectiveType = Level.ObjectiveType.DEFEND,
            base = base,
            defendPoints = defendPoints,
            healthPoints = perf.healthPoints,
            accuracyPoints = perf.accuracyPoints,
            timeBonus = timeBonus,
            difficultyWeight = difficultyWeight,
            accuracyRating = perf.accuracyRating,
            total = total,
            enemiesStart = enemiesStart,
            enemiesDestroyed = log.enemiesDestroyed,
            friendliesStart = friendliesStart,
            friendliesSaved = friendliesEnd,
            civiliansStart = summary.civiliansStart.coerceAtLeast(0),
            timeRemainingMs = left
        )

        val detailsJson = buildDetailsJson(b0, log.shotsFired, log.shotsHit, log.healthRemaining)
        val highlightsJson = buildHighlightsJson(b0, log.healthRemaining)

        return b0.copy(detailsJson = detailsJson, highlightsJson = highlightsJson)
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

        val enemiesStart = summary.enemiesStart.coerceAtLeast(0)

        val b0 = Breakdown(
            objectiveType = Level.ObjectiveType.EVAC,
            base = 0f,
            evacPoints = evacPoints,
            killBonus = killBonus,
            healthPoints = perf.healthPoints,
            accuracyPoints = perf.accuracyPoints,
            timeBonus = timeBonus,
            difficultyWeight = difficultyWeight,
            accuracyRating = perf.accuracyRating,
            total = total,
            enemiesStart = enemiesStart,
            enemiesDestroyed = log.enemiesDestroyed,
            civiliansStart = civiliansStart
        )

        val detailsJson = buildDetailsJson(b0, log.shotsFired, log.shotsHit, log.healthRemaining)
        val highlightsJson = buildHighlightsJson(b0, log.healthRemaining)

        return b0.copy(detailsJson = detailsJson, highlightsJson = highlightsJson)

    }
}
