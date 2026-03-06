package com.jimjo.exodefender

import kotlin.random.Random

sealed class RadioTrigger {
    data object EnemyKilled : RadioTrigger()
    data object FriendlyKilled : RadioTrigger()
    data object CasStart : RadioTrigger()
    data object DefendStart : RadioTrigger()
    data object EvacStart : RadioTrigger()
    data object StructureWarning : RadioTrigger()
    data object EvacAll : RadioTrigger()
    data object EvacWarning : RadioTrigger()
    data object MissionComplete : RadioTrigger()
    data object ShipDestroyed : RadioTrigger()
}

enum class RadioCueType {
    CAS_STARTED,
    DEFEND_STARTED,
    EVAC_STARTED,

    SHIP_DESTROYED,
    GRATITUDE,

    STRUCTURE_WARNING,
    EVAC_ALL,
    EVAC_WARNING,

    FRIENDLY_LOSS,
    FORWARD_PROGRESS,
}

data class RadioClip(
    val sound: AudioPlayer.Soundfile,
    val durationMs: Int
)

data class RadioRequestProfile(
    val priority: Int,
    val clips: List<RadioClip>,

    val delayMs: Int = 0,
    val chance: Float = 1f,
    val cooldownMs: Int = 0,
    val expiresAfterMs: Int? = null,
    val avoidRecentCount: Int = 0,
    val repeatable: Boolean = true,

    val blocksOthersUntilPlayed: Boolean = false,
    val closesRadioAfterPlay: Boolean = false
)

data class RadioRequest(
    val cueType: RadioCueType,
    val createdAtMs: Int,
    val earliestAtMs: Int,
    val expiresAtMs: Int? = null,
    val force: Boolean = false
) {
    fun debugString(nowMs: Int): String {
        val waitMs = earliestAtMs - nowMs
        val expMs = expiresAtMs?.minus(nowMs)

        return buildString {
            append(cueType)
            append(" wait=")
            append(waitMs)
            append("ms")
            if (expMs != null) {
                append(" exp=")
                append(expMs)
                append("ms")
            }
            if (force) append(" force")
        }
    }
}

private enum class WaitReason {
    NONE,
    BUSY,
    BLOCKED_BY_RESERVED,
    NO_ELIGIBLE
}



class RadioManager(
    private val playClip: (AudioPlayer.Soundfile) -> Boolean,
    private val profilesByType: Map<RadioCueType, RadioRequestProfile>
) {

    var loggingEnabled = true

    // Incoming gameplay events.
    private val triggerQueue = ArrayDeque<Pair<RadioTrigger, Int>>()

    // Pending radio requests.
    private val requestQueue = mutableListOf<RadioRequest>()

    // One-shot cue tracking.
    private val usedCueTypes = mutableSetOf<RadioCueType>()

    // Per-cue cooldown tracking.
    private val lastPlayedMsByCueType = mutableMapOf<RadioCueType, Int>()

    // Recent clip indexes per cue type.
    private val recentClipIndexesByCueType = mutableMapOf<RadioCueType, ArrayDeque<Int>>()

    // Channel / mission state.
    private var busyUntilMs: Int = 0
    private var radioClosed: Boolean = false

    private var lastWaitReason = WaitReason.NONE
    private var lastWaitCueType: RadioCueType? = null
    private var lastWaitUntilMs: Int = Int.MIN_VALUE

    // Safety gap after each line.
    private val radioTailMs: Int = 250

    fun setEnabled(on: Boolean) {
        if (!on) {
            clear()
            radioClosed = false
        }
    }

    fun clear() {
        triggerQueue.clear()
        requestQueue.clear()
        usedCueTypes.clear()
        lastPlayedMsByCueType.clear()
        recentClipIndexesByCueType.clear()
        busyUntilMs = 0
        radioClosed = false
    }

    fun closeRadio(nowMs: Int) {
        requestQueue.clear()
        radioClosed = true
        logAt(nowMs, "radio closed")
    }

    fun post(trigger: RadioTrigger, nowMs: Int) {
        if (radioClosed) {
            logAt(nowMs, "ignore trigger $trigger (radio closed)")
            return
        }
        triggerQueue.addLast(trigger to nowMs)
        logAt(nowMs, "trigger $trigger")
    }

    private fun logWaitState(
        nowMs: Int,
        reason: WaitReason,
        cueType: RadioCueType? = null,
        untilMs: Int? = null,
        msg: () -> String
    ) {
        val normalizedUntilMs = untilMs ?: Int.MIN_VALUE

        val changed =
            reason != lastWaitReason ||
                    cueType != lastWaitCueType ||
                    normalizedUntilMs != lastWaitUntilMs

        if (changed) {
            logAt(nowMs, msg())
            lastWaitReason = reason
            lastWaitCueType = cueType
            lastWaitUntilMs = normalizedUntilMs
        }
    }

    private fun clearWaitState() {
        lastWaitReason = WaitReason.NONE
        lastWaitCueType = null
        lastWaitUntilMs = Int.MIN_VALUE
    }

    fun tick(nowMs: Int) {
        removeExpiredRequests(nowMs)

        if (radioClosed) {
            if (triggerQueue.isNotEmpty()) {
                logAt(nowMs, "discard ${triggerQueue.size} trigger(s) (radio closed)")
                triggerQueue.clear()
            }
            return
        }

        drainTriggersIntoRequests(nowMs)
        removeExpiredRequests(nowMs)

        if (nowMs < busyUntilMs) {
            logWaitState(
                nowMs,
                WaitReason.BUSY,
                untilMs = busyUntilMs
            ) { "busy ${busyUntilMs - nowMs}ms" }
            return
        }

        val blockingRequest = findBlockingRequest()
        if (blockingRequest != null && nowMs < blockingRequest.earliestAtMs) {
            logWaitState(
                nowMs,
                WaitReason.BLOCKED_BY_RESERVED,
                cueType = blockingRequest.cueType,
                untilMs = blockingRequest.earliestAtMs
            ) {
                "blocked by ${blockingRequest.cueType} for ${blockingRequest.earliestAtMs - nowMs}ms"
            }
            return
        }

        val eligible = requestQueue
            .filter { isEligible(it, nowMs) }
            .sortedWith(
                compareByDescending<RadioRequest> { profileOf(it).priority }
                    .thenBy { it.createdAtMs }
            )

        if (eligible.isEmpty()) {
            if (requestQueue.isEmpty()) {
                clearWaitState()
                return
            }

            logWaitState(nowMs, WaitReason.NO_ELIGIBLE) { "no eligible requests" }
            return
        }

        playRequest(eligible.first(), nowMs)
    }

    private fun drainTriggersIntoRequests(nowMs: Int) {
        if (triggerQueue.isEmpty()) return

        var enemyKills = 0
        var friendlyKills = 0

        var hasCasStart = false
        var hasDefendStart = false
        var hasEvacStart = false
        var hasStructureWarning = false
        var hasEvacAll = false
        var hasEvacWarning = false
        var hasMissionComplete = false
        var hasShipDestroyed = false

        while (triggerQueue.isNotEmpty()) {
            when (triggerQueue.removeFirst().first) {
                RadioTrigger.EnemyKilled -> enemyKills++
                RadioTrigger.FriendlyKilled -> friendlyKills++
                RadioTrigger.CasStart -> hasCasStart = true
                RadioTrigger.DefendStart -> hasDefendStart = true
                RadioTrigger.EvacStart -> hasEvacStart = true
                RadioTrigger.StructureWarning -> hasStructureWarning = true
                RadioTrigger.EvacAll -> hasEvacAll = true
                RadioTrigger.EvacWarning -> hasEvacWarning = true
                RadioTrigger.MissionComplete -> hasMissionComplete = true
                RadioTrigger.ShipDestroyed -> hasShipDestroyed = true
            }
        }

        if (hasShipDestroyed) {
            enqueueCue(RadioCueType.SHIP_DESTROYED, nowMs, force = true)
        }

        if (hasMissionComplete) {
            enqueueCue(RadioCueType.GRATITUDE, nowMs, force = true)
        }

        if (hasCasStart) {
            enqueueCue(RadioCueType.CAS_STARTED, nowMs, force = true)
        }
        if (hasDefendStart) {
            enqueueCue(RadioCueType.DEFEND_STARTED, nowMs, force = true)
        }
        if (hasEvacStart) {
            enqueueCue(RadioCueType.EVAC_STARTED, nowMs, force = true)
        }

        if (hasStructureWarning) {
            enqueueCue(RadioCueType.STRUCTURE_WARNING, nowMs, force = true)
        }
        if (hasEvacAll) {
            enqueueCue(RadioCueType.EVAC_ALL, nowMs, force = true)
        }
        if (hasEvacWarning) {
            enqueueCue(RadioCueType.EVAC_WARNING, nowMs, force = true)
        }

        if (friendlyKills > 0) {
            enqueueCue(RadioCueType.FRIENDLY_LOSS, nowMs, force = false)
        }

        if (enemyKills > 0) {
            enqueueCue(RadioCueType.FORWARD_PROGRESS, nowMs, force = false)
        }
    }

    private fun enqueueCue(
        cueType: RadioCueType,
        nowMs: Int,
        force: Boolean
    ) {
        val profile = profilesByType[cueType] ?: run {
            logAt(nowMs, "no profile for $cueType")
            return
        }

        if (!profile.repeatable && cueType in usedCueTypes) {
            logAt(nowMs, "skip $cueType (already used)")
            return
        }

        if (!force && !roll(profile.chance)) {
            logAt(nowMs, "skip $cueType (chance ${profile.chance})")
            return
        }

        val req = RadioRequest(
            cueType = cueType,
            createdAtMs = nowMs,
            earliestAtMs = nowMs + profile.delayMs,
            expiresAtMs = profile.expiresAfterMs?.let { nowMs + it },
            force = force
        )

        requestQueue.add(req)

        if (!profile.repeatable) {
            usedCueTypes.add(cueType)
        }

        logAt(
            nowMs,
            "request $cueType delay=${profile.delayMs}ms exp=${profile.expiresAfterMs ?: "∞"}"
        )
        dumpQueue(nowMs)
    }

    private fun removeExpiredRequests(nowMs: Int) {
        val it = requestQueue.iterator()
        while (it.hasNext()) {
            val req = it.next()
            val exp = req.expiresAtMs
            if (exp != null && nowMs > exp) {
                logAt(nowMs, "expired ${req.cueType}")
                it.remove()
            }
        }
    }

    private fun findBlockingRequest(): RadioRequest? =
        requestQueue
            .filter { profileOf(it).blocksOthersUntilPlayed }
            .minByOrNull { it.createdAtMs }

    private fun isEligible(req: RadioRequest, nowMs: Int): Boolean {
        if (nowMs < req.earliestAtMs) return false
        if (req.expiresAtMs != null && nowMs > req.expiresAtMs) return false

        val profile = profileOf(req)
        val lastPlayed = lastPlayedMsByCueType[req.cueType]
        if (lastPlayed != null && profile.cooldownMs > 0L) {
            if (nowMs - lastPlayed < profile.cooldownMs) {
                return false
            }
        }

        return true
    }

    private fun playRequest(req: RadioRequest, nowMs: Int) {
        val profile = profileOf(req)
        val clipIndex = pickClipIndex(req.cueType, profile) ?: run {
            logAt(nowMs, "no clip available for ${req.cueType}")
            requestQueue.remove(req)
            return
        }

        val clip = profile.clips[clipIndex]
        logAt(nowMs, "selected ${req.cueType}")
        val started = playClip(clip.sound)

        if (!started) {
            logAt(nowMs, "play refused ${req.cueType}")
            return
        }

        logAt(nowMs, "play ${req.cueType} clip#$clipIndex")

        requestQueue.remove(req)
        lastPlayedMsByCueType[req.cueType] = nowMs
        markRecentClip(req.cueType, clipIndex, profile.avoidRecentCount)
        busyUntilMs = nowMs + clip.durationMs + radioTailMs
        clearWaitState()

        if (profile.closesRadioAfterPlay) {
            requestQueue.clear()
            radioClosed = true
            logAt(nowMs, "radio closed by ${req.cueType}")
        }
    }

    private fun profileOf(req: RadioRequest): RadioRequestProfile =
        profilesByType[req.cueType]
            ?: error("Missing RadioRequestProfile for ${req.cueType}")

    private fun pickClipIndex(
        cueType: RadioCueType,
        profile: RadioRequestProfile
    ): Int? {
        val clips = profile.clips
        if (clips.isEmpty()) return null
        if (clips.size == 1) return 0

        val recent = recentClipIndexesByCueType[cueType]?.toSet() ?: emptySet()

        repeat(6) {
            val i = Random.nextInt(clips.size)
            if (i !in recent) return i
        }

        for (i in clips.indices) {
            if (i !in recent) return i
        }

        return Random.nextInt(clips.size)
    }

    private fun markRecentClip(
        cueType: RadioCueType,
        clipIndex: Int,
        keepCount: Int
    ) {
        if (keepCount <= 0) return

        val q = recentClipIndexesByCueType.getOrPut(cueType) { ArrayDeque() }
        q.addLast(clipIndex)
        while (q.size > keepCount) {
            q.removeFirst()
        }
    }

    private fun roll(chance: Float): Boolean {
        if (chance <= 0f) return false
        if (chance >= 1f) return true
        return Random.nextFloat() < chance
    }

    private fun timeTag(nowMs: Int): String {
        val seconds = nowMs / 1000
        val tenths = (nowMs % 1000) / 100
        return "%4d.%1ds".format(seconds, tenths)
    }

    fun log(msg: String) {
        if (loggingEnabled) println("[radio] $msg")
    }

    private fun logAt(nowMs: Int, msg: String) {
        if (loggingEnabled) println("[radio] ${timeTag(nowMs)}  $msg")
    }

    private fun dumpQueue(nowMs: Int) {
        if (!loggingEnabled) return

        if (requestQueue.isEmpty()) {
            logAt(nowMs, "queue: <empty>")
            return
        }

        logAt(nowMs, "queue:")
        requestQueue.forEachIndexed { index, req ->
            logAt(nowMs, "  [$index] ${req.debugString(nowMs)}")
        }
    }
}