package com.jimjo.exodefender

import kotlin.random.Random

private const val PLAY_RETRY_MS: Int = 300
private const val MAX_RESERVED_RETRY_MS = 5000


sealed class RadioTrigger {
    data object EnemyKilled : RadioTrigger()
    data object FriendlyKilled : RadioTrigger()
    data object CasStart : RadioTrigger()
    data object DefendStart : RadioTrigger()
    data object EvacStart : RadioTrigger()
    data object StructureWarning : RadioTrigger()
    data object StructureDestroyed : RadioTrigger()
    data object EvacAll : RadioTrigger()
    data object EvacWarning : RadioTrigger()
    data object MissionComplete : RadioTrigger()
    data object ShipDestroyed : RadioTrigger()
}

enum class RadioVoiceVariant {
    A,
    B
}
enum class RadioCueType {
    CAS_STARTED,
    DEFEND_STARTED,
    EVAC_STARTED,

    SHIP_DESTROYED,
    GRATITUDE,

    STRUCTURE_WARNING,
    STRUCTURE_DESTROYED,
    EVAC_ALL,
    EVAC_WARNING,

    FRIENDLY_LOSS,
    FORWARD_PROGRESS,
}

data class RadioClip(
    val sound: AudioPlayer.Soundfile,
    val durationMs: Int
)

/**
 * Defines how a radio cue behaves once it has been requested.
 *
 * A RadioRequestProfile describes the selection rules, timing behaviour,
 * and playback constraints for a particular radio cue type.
 */
data class RadioRequestProfile(

    val priority: Int,
    // Priority used when selecting between multiple eligible requests.
    // Higher values are chosen first.

    val clips: List<RadioClip>,
    // Audio clips available for this cue. One is chosen randomly when played.

    val delayMs: Int = 0,
    // Minimum delay after the trigger before this request becomes eligible to play.

    val delayJitterMs: Int = 0,
    // maximum amount of random additional extra delay

    val chance: Float = 1f,
    // Probability (0.0–1.0) that a trigger will create a request for this cue.
    // Used to reduce chatter for frequently occurring events.

    val cooldownMs: Int = 0,
    // Minimum time that must pass before another request of the same cue type
    // can be created again.

    val expiresAfterMs: Int? = null,
    // Maximum time the request may remain queued before it becomes invalid.
    // If null, the request never expires.

    val avoidRecentCount: Int = 0,
    // Number of recently played clips of this cue type to avoid repeating,
    // helping reduce audible repetition.

    val repeatable: Boolean = true,
    // If false, this cue type can only be played once per mission.

    val blocksOthersUntilPlayed: Boolean = false,
    // If true, prevents other requests from being selected while this request
    // is waiting to be played (used for important announcements).

    val suppressLowerPriorityAfterPlayMs: Int = 0,
    // After this cue begins playback, lower-priority cues cannot be selected
    // for this many milliseconds.


    val closesRadioAfterPlay: Boolean = false,
    // If true, the radio system closes after this cue plays, preventing any
    // further triggers or requests for the remainder of the mission.
)

data class RadioRequest(
    val cueType: RadioCueType,
    val createdAtMs: Int,
    var earliestAtMs: Int,
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
    SUPPRESSED_BY_PRIORITY,
    NO_ELIGIBLE,
}



class RadioManager(
    private val playClip: (AudioPlayer.Soundfile) -> Boolean,
    private var profilesByType: Map<RadioCueType, RadioRequestProfile>
) {

    var loggingEnabled = false

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
    private var enabled = true
    private var busyUntilMs: Int = 0
    private var radioClosed: Boolean = false

    private var lastWaitReason = WaitReason.NONE
    private var lastWaitCueType: RadioCueType? = null
    private var lastWaitUntilMs: Int = Int.MIN_VALUE
    private var lowerPrioritySuppressedUntilMs = 0
    private var suppressionPriority = Int.MIN_VALUE
    private var lastRetryCueType: RadioCueType? = null

    // Safety gap after each line.
    private val radioTailMs: Int = 250

    fun setEnabled(on: Boolean) {
        enabled = on

        if (!enabled) {
            clear()
            log("radio disabled")
        } else {
            log("radio enabled")
        }
    }

    fun setProfiles(profiles: Map<RadioCueType, RadioRequestProfile>) {
        profilesByType = profiles
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

    fun closeRadio(nowMs: Int, reason: String? = null) {
        requestQueue.clear()
        radioClosed = true

        val r = if (reason != null) " ($reason)" else ""

        logAt(nowMs, "radio closed" + r)
    }

    fun post(trigger: RadioTrigger, nowMs: Int) {

        if (!enabled) return

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

        if (!enabled) return

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

        // If a reserved request is now due, it must go next.
        if (blockingRequest != null) {
            playRequest(blockingRequest, nowMs)
            return
        }

        val eligibleBase = requestQueue.filter { isEligible(it, nowMs) }

        if (eligibleBase.isEmpty()) {
            if (requestQueue.isEmpty()) {
                clearWaitState()
                return
            }

            logWaitState(nowMs, WaitReason.NO_ELIGIBLE) { "no eligible requests" }
            return
        }

        val suppressionActive = nowMs < lowerPrioritySuppressedUntilMs

        val eligible = if (!suppressionActive) {
            eligibleBase
        } else {
            val filtered = eligibleBase.filter {
                profileOf(it).priority >= suppressionPriority
            }

            if (filtered.isEmpty()) {
                logWaitState(
                    nowMs,
                    WaitReason.SUPPRESSED_BY_PRIORITY,
                    untilMs = lowerPrioritySuppressedUntilMs
                ) {
                    "suppressed (<$suppressionPriority) for ${lowerPrioritySuppressedUntilMs - nowMs}ms"
                }
                return
            }

            filtered
        }.sortedWith(
            compareByDescending<RadioRequest> { profileOf(it).priority }
                .thenBy { it.createdAtMs }
        )

        clearWaitState()
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
        var hasStructureDestroyed = false
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
                RadioTrigger.StructureDestroyed -> hasStructureDestroyed = true
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

        if (hasStructureDestroyed) {
            enqueueCue(RadioCueType.STRUCTURE_DESTROYED, nowMs, force = true)
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

        val jitter = Random.nextInt(profile.delayJitterMs + 1)

        val req = RadioRequest(
            cueType = cueType,
            createdAtMs = nowMs,
            earliestAtMs = nowMs + profile.delayMs + jitter,
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

        val started = playClip(clip.sound)

        if (!started) {
            if (profile.blocksOthersUntilPlayed) {

                val retryAge = nowMs - req.createdAtMs

                if (retryAge > MAX_RESERVED_RETRY_MS) {
                    logAt(nowMs,
                        "⚠ radio: failed to play reserved cue ${req.cueType} after ${retryAge}ms — dropping")
                    requestQueue.remove(req)
                    lastRetryCueType = null
                    return
                }

                if (lastRetryCueType != req.cueType) {
                    logAt(nowMs, "play refused ${req.cueType}, retrying...")
                    lastRetryCueType = req.cueType
                }

                req.earliestAtMs = nowMs + PLAY_RETRY_MS
            } else {
                logAt(nowMs, "play refused ${req.cueType}, dropping")
                requestQueue.remove(req)
            }

            return
        }

        lastRetryCueType = null
        logAt(nowMs, "selected ${req.cueType}")


        requestQueue.remove(req)
        lastPlayedMsByCueType[req.cueType] = nowMs
        markRecentClip(req.cueType, clipIndex, profile.avoidRecentCount)
        busyUntilMs = nowMs + clip.durationMs + radioTailMs
        clearWaitState()

        logAt(nowMs, "play ${req.cueType} clip#$clipIndex")

        if (profile.suppressLowerPriorityAfterPlayMs > 0) {
            lowerPrioritySuppressedUntilMs =
                nowMs + profile.suppressLowerPriorityAfterPlayMs
            suppressionPriority = profile.priority

            logAt(
                nowMs,
                "suppress lower priorities (<${profile.priority}) for ${profile.suppressLowerPriorityAfterPlayMs}ms"
            )
        }

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