package com.jimjo.exodefender

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

class LevelProgressManager(
    context: Context,
    prefsName: String
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // This is obfuscation, not true security. Make it non-obvious.
    private val secret = "ExoDefender#L3v3l_Sig_IDv1"

    private val KEY_PAYLOAD = "levels_payload"
    private val KEY_SIG = "levels_sig"

    // In-memory state: levelId -> LevelStats
    private var cache: MutableMap<Int, LevelStats>? = null

    data class LevelStats(
        var completed: Boolean,
        var bestScore: Int   // -1 = no score yet
    )

    // -------------- Public API --------------

    data class LevelResultChange(
        val firstCompletion: Boolean,
        val newBestScore: Boolean,
        val previousBestScore: Int? = null
    )

    fun registerLevelResult(levelId: Int, score: Int): LevelResultChange {
        if (score < 0) return LevelResultChange(firstCompletion = false, newBestScore = false)

        val state = loadState()
        val stats = state.getOrPut(levelId) { LevelStats(completed = false, bestScore = -1) }

        var firstCompletion = false
        var newBest = false
        var previousBestScore: Int? = null

        if (!stats.completed) {
            stats.completed = true
            firstCompletion = true
        }
        else {
            previousBestScore = stats.bestScore
        }

        if (score > stats.bestScore) {
            stats.bestScore = score
            newBest = true
        }

        if (firstCompletion || newBest) {
            saveState(state)
        }

        return LevelResultChange(firstCompletion, newBest, previousBestScore)
    }

    /** Has this level ever been completed? */
    fun isLevelCompleted(levelId: Int): Boolean {
        val state = loadState()
        val stats = state[levelId] ?: return false
        return stats.completed
    }

    /** Best score for this level, or null if never played / no score. */
    fun getBestScore(levelId: Int): Int? {
        val state = loadState()
        val stats = state[levelId] ?: return null
        return if (stats.bestScore >= 0) stats.bestScore else null
    }

    /** Admin: reset all progress and scores for this level set. */
    fun resetAll() {
        cache = mutableMapOf()
        saveState(mutableMapOf())
    }

    // -------------- Internal state --------------

    private fun loadState(): MutableMap<Int, LevelStats> {
        cache?.let { return it }

        val payload = prefs.getString(KEY_PAYLOAD, null)
        val sig = prefs.getString(KEY_SIG, null)

        if (payload == null || sig == null) {
            val empty = mutableMapOf<Int, LevelStats>()
            cache = empty
            return empty
        }

        val expectedSig = signPayload(payload)
        if (sig != expectedSig) {
            // Tampered or corrupted -> reset to empty
            val empty = mutableMapOf<Int, LevelStats>()
            cache = empty
            return empty
        }

        val decoded = decodePayload(payload)
        cache = decoded
        return decoded
    }

    private fun saveState(state: MutableMap<Int, LevelStats>) {
        val payload = encodePayload(state)
        val sig = signPayload(payload)

        // Deep-ish copy for cache
        cache = state.mapValues { (_, v) ->
            LevelStats(v.completed, v.bestScore)
        }.toMutableMap()

        prefs.edit()
            .putString(KEY_PAYLOAD, payload)
            .putString(KEY_SIG, sig)
            .apply()
    }

    // -------------- Encoding / decoding / signing --------------

    /**
     * Encode as:
     *   "levelId:completedFlag:score|levelId2:completedFlag:score2|..."
     *
     * where:
     *   completedFlag = '0' or '1'
     *   score = int (or -1 if none)
     *
     * Example:
     *   "101:1:5320|205:0:-1|300:1:6100"
     */
    private fun encodePayload(state: Map<Int, LevelStats>): String {
        if (state.isEmpty()) return ""

        // Sort by levelId so encoding is stable (important for signatures)
        val sortedIds = state.keys.sorted()

        val sb = StringBuilder()
        var first = true

        for (id in sortedIds) {
            val stats = state[id] ?: continue
            if (!first) sb.append('|')
            first = false

            sb.append(id)
            sb.append(':')
            sb.append(if (stats.completed) '1' else '0')
            sb.append(':')
            sb.append(stats.bestScore)
        }

        return sb.toString()
    }

    private fun decodePayload(payload: String): MutableMap<Int, LevelStats> {
        val map = mutableMapOf<Int, LevelStats>()
        if (payload.isEmpty()) return map

        val parts = payload.split('|')
        for (part in parts) {
            if (part.isEmpty()) continue
            val pieces = part.split(':')

            if (pieces.size != 3) continue

            val id = pieces[0].toIntOrNull() ?: continue
            val completedFlag = pieces[1]
            val scoreStr = pieces[2]

            val completed = completedFlag == "1"
            val bestScore = scoreStr.toIntOrNull() ?: -1

            map[id] = LevelStats(
                completed = completed,
                bestScore = bestScore
            )
        }

        return map
    }

    private fun signPayload(payload: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = (payload + secret).toByteArray(Charsets.UTF_8)
        val digest = md.digest(bytes)
        // Truncate to 8 bytes (16 hex chars)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}