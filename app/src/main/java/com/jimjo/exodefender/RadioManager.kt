package com.jimjo.exodefender

import kotlin.math.max
import kotlin.random.Random

enum class RadioType { CHECK_IN, FRIENDLY_LOSS, FORWARD_PROGRESS, SHIP_DESTROYED, GRATITUDE }

private sealed class RadioEvent(val atMs: Long) {

    class EnemyKilled(atMs: Long) : RadioEvent(atMs)
    class FriendlyKilled(atMs: Long) : RadioEvent(atMs)

    class MissionStart(atMs: Long) : RadioEvent(atMs)
    class MissionComplete(atMs: Long) : RadioEvent(atMs)

    class ShipDestroyed(atMs: Long) : RadioEvent(atMs)
}
data class TypeTuning(
    var chance: Float = 1f,
    var cooldownMs: Long = 0L,
    var durationMs: Long = 1000L,
    var enabled: Boolean = true
)
class RadioManager(
    private val playClip: (AudioPlayer.Soundfile) -> Boolean,
    private val scheduleOnce: (Long, () -> Unit) -> Unit
) {

    private val pendingEvents = ArrayDeque<RadioEvent>()


    // Context snapshot updated each tick
    private var enemyRatio: Float = 1f
    private var friendlyRatio: Float = 1f



    // Tunables
    var globalMinGapMs: Long = 900L
    private val radioTailMs: Long = 1000L

    var avoidRecentPerType: Int = 2
    private var hasSpokenLossThisMission = false
    private var checkInDone = false

    private val tuning = hashMapOf(
        RadioType.CHECK_IN to TypeTuning(chance = 1f, cooldownMs = 0L, durationMs = 2500L),
        RadioType.FRIENDLY_LOSS to TypeTuning(chance = 0.40f, cooldownMs = 0L),
        RadioType.FORWARD_PROGRESS to TypeTuning(chance = 0.40f, cooldownMs = 0L),
        RadioType.SHIP_DESTROYED to TypeTuning(chance = 0.35f, cooldownMs = 60000L),
        RadioType.GRATITUDE to TypeTuning(chance = 1f, cooldownMs = 0L),
    )

    private val loggingEnabled = false


    // Internal state
    var enabled: Boolean = true
        private set
    private val clips = HashMap<RadioType, MutableList<AudioPlayer.Soundfile>>()
    private val recentByType = HashMap<RadioType, ArrayDeque<Int>>() // indexes into list
    private val lastTypeMs = HashMap<RadioType, Long>()
    private var lastRadioMs: Long = -1L
    private var radioBusyUntilMs: Long = -1L
    private var suppressUntilMs: Long = 0L  // suppression window (silence tail)


    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            // Clear internal queues so nothing “fires later”
            pendingEvents.clear()
            suppressUntilMs = 0L
            checkInDone = false
            // Optional: reset gating so when re-enabled it starts clean
            lastRadioMs = -1L
            radioBusyUntilMs = -1L
        }
    }

    fun clear() {
        pendingEvents.clear()
        suppressUntilMs = 0L
        hasSpokenLossThisMission = false
        checkInDone = false

        // Reset gating
        lastRadioMs = -1L
        radioBusyUntilMs = -1L
        lastTypeMs.clear()
        recentByType.clear()

        // Reset snapshot (optional)
        enemyRatio = 1f
        friendlyRatio = 1f
    }

    fun log(msg: String) {
        if (loggingEnabled) println("[radio] " + msg)
    }

    fun addAll(type: RadioType, list: List<AudioPlayer.Soundfile>) {
        val dst = clips.getOrPut(type) { mutableListOf() }
        dst.addAll(list)
    }

    fun tick(missionElapsedMs: Long) {

        if (!enabled) return;

        var enemyKills = 0
        var friendlyDeaths = 0
        var hasShipDestroyed = false
        var hasMissionComplete = false
        var hasMissionStart = false

        if (pendingEvents.isNotEmpty()) {
            while (pendingEvents.isNotEmpty()) {
                when (pendingEvents.removeFirst()) {
                    is RadioEvent.EnemyKilled -> enemyKills++
                    is RadioEvent.FriendlyKilled -> friendlyDeaths++
                    is RadioEvent.ShipDestroyed -> hasShipDestroyed = true
                    is RadioEvent.MissionComplete -> hasMissionComplete = true
                    is RadioEvent.MissionStart -> hasMissionStart = true
                }
            }
        }

        if (hasShipDestroyed) {
            log("tick hasShipDestroyed")
            tryPlay(RadioType.SHIP_DESTROYED, missionElapsedMs)
            suppressUntilMs = maxOf(suppressUntilMs, missionElapsedMs + 2500L)
            return
        }

        if (hasMissionComplete) {
            log("TICK hasMissionComplete")

            val atMs = missionElapsedMs + 1200L
            scheduleOnce(atMs) {
                forcePlay(RadioType.GRATITUDE, atMs)
            }

            // Reserve the channel until the gratitude lands
            suppressUntilMs = maxOf(suppressUntilMs, atMs)
            return
        }

        if (missionElapsedMs < suppressUntilMs) return

        if (hasMissionStart) {
            log("TICK hasMissionStart")

            scheduleCheckIn(missionElapsedMs)

        }

        if (friendlyDeaths > 0) {
            log("TICK friendlyDeaths > 0")
            tryPlay(RadioType.FRIENDLY_LOSS, missionElapsedMs)
        }

        if (enemyKills > 0) {
            log("TICK enemyKills > 0")
            tryPlay(RadioType.FORWARD_PROGRESS, missionElapsedMs)
        }
    }


    private fun scheduleCheckIn(startMs: Long) {
        if (checkInDone) return
        val atMs = startMs + 1500L
        scheduleOnce(atMs) { attemptCheckIn(atMs) }
    }

    private fun attemptCheckIn(atMs: Long) {
        if (checkInDone) return

        // If something important is reserving airtime, wait.
        if (atMs < suppressUntilMs) {
            scheduleOnce(suppressUntilMs + 400L) { attemptCheckIn(suppressUntilMs + 400L) }
            return
        }

        // Force so it doesn't randomly miss, but still respects global gap + non-recent.
        if (forcePlay(RadioType.CHECK_IN, atMs)) {
            checkInDone = true
        } else {
            // Channel busy (global gap). Retry once shortly.
            val retryAt = atMs + 700L
            scheduleOnce(retryAt) { attemptCheckIn(retryAt) }
        }
    }


    private fun isCriticalFriendlyLoss(lossesThisDrain: Int): Boolean {
        if (lossesThisDrain >= 2) return true
        // If we're already in trouble, treat even one as critical
        return friendlyRatio <= 0.3f
    }

    fun tryPlay(type: RadioType, nowMs: Long): Boolean =
        tryPlayInternal(type, nowMs, scale = 1f)

    private fun tryPlayScaled(type: RadioType, nowMs: Long, scale: Float): Boolean =
        tryPlayInternal(type, nowMs, scale = scale)

    private fun tryPlayInternal(type: RadioType, nowMs: Long, scale: Float): Boolean {
        val t = tuning[type] ?: return false

        val s = scale.coerceIn(0f, 1f)
        val chance = (t.chance * s).coerceIn(0f, 1f)

        if (!t.enabled) {
            log(" blocked $type: disabled")
            return false
        }

        if (!roll(chance)) {
            // Helpful to see the scaled chance while debugging
            log(" blocked $type: chance (p=$chance, base=${t.chance}, scale=$s)")
            return false
        }

        if (!cooldownOk(type, nowMs, t.cooldownMs)) {
            log(" blocked $type: cooldown")
            return false
        }

        val ok = play(type, nowMs)
        if (!ok) log(" blocked $type: play()") else log(" played $type")
        return ok
    }

    fun forcePlay(type: RadioType, nowMs: Long): Boolean {
        log(" forcePlay() type=$type")
        val t = tuning[type] ?: return false
        if (!t.enabled) return false
        log(" calling play() $type")
        return play(type, nowMs) // keeps global gap + avoid-recent
    }

    fun play(type: RadioType, nowMs: Long): Boolean {
        if (!canPlayNotBusy(nowMs)) return false
        val list = clips[type] ?: return false
        if (list.isEmpty()) return false

        val idx = pickNonRecentIndex(type, list.size) ?: return false
        val sf = list[idx]

        val started = playClip(sf)
        if (!started) {
            log(" play blocked $type: SoundPool refused playback")
            return false
        }

        val dur = (tuning[type]?.durationMs ?: 1800L)
        lastRadioMs = nowMs
        radioBusyUntilMs = nowMs + dur + radioTailMs

        markPlayed(type, nowMs, idx)
        return true
    }

    private fun cooldownOk(type: RadioType, nowMs: Long, cooldownMs: Long): Boolean {
        val last = lastTypeMs[type] ?: -1L
        return (last < 0L) || (nowMs - last >= cooldownMs)
    }

    private fun canPlayNotBusy(nowMs: Long): Boolean {
        if (radioBusyUntilMs >= 0L && nowMs < radioBusyUntilMs) {
            log(" blocked: busy")
            return false
        }
        if (lastRadioMs < 0L) return true
        return (nowMs - lastRadioMs) >= globalMinGapMs
    }

    private fun markPlayed(type: RadioType, nowMs: Long, idx: Int) {
        log(" MARK_PLAYED type=$type now=$nowMs")
        lastRadioMs = nowMs
        lastTypeMs[type] = nowMs
        val q = recentByType.getOrPut(type) { ArrayDeque() }
        q.addLast(idx)
        while (q.size > max(0, avoidRecentPerType)) q.removeFirst()
    }

    private fun pickNonRecentIndex(type: RadioType, size: Int): Int? {
        if (size <= 0) return null
        if (size == 1) return 0

        val recentSet = recentByType[type]?.toHashSet() ?: emptySet()

        // Try a few random picks that are not in recent
        repeat(6) {
            val idx = Random.nextInt(size)
            if (!recentSet.contains(idx)) return idx
        }

        // Fallback: pick the first non-recent
        for (i in 0 until size) {
            if (!recentSet.contains(i)) return i
        }

        // If everything is "recent" (tiny list), allow any
        return Random.nextInt(size)
    }

    private fun roll(chance: Float): Boolean {
        if (chance <= 0f) return false
        if (chance >= 1f) return true
        return Random.nextFloat() < chance
    }

    fun onEnemyKilled(atMs: Long) { if (!enabled) return; pendingEvents.add(RadioEvent.EnemyKilled(atMs)) }
    fun onFriendlyKilled(atMs: Long) { if (!enabled) return; pendingEvents.add(RadioEvent.FriendlyKilled(atMs)) }
    fun onMissionStart(atMs: Long) { if (!enabled) return; pendingEvents.add(RadioEvent.MissionStart(atMs)) }
    fun onMissionComplete(atMs: Long) { if (!enabled) return; pendingEvents.add(RadioEvent.MissionComplete(atMs)) }
    fun onShipDestroyed(atMs: Long) { if (!enabled) return; pendingEvents.add(RadioEvent.ShipDestroyed(atMs)) }
}
