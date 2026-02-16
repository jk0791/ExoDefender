package com.jimjo.exodefender

// Stable identity for a landing pad block.
// structureId == FriendlyStructureActor.templateId (from FriendlyStructureTemplate.id in level JSON)
data class PadKey(
    val structureId: Int,
    val blockIndex: Int
)

sealed interface MissionEvent {
    val timeMs: Int
}

data class DestructStart(
    override val timeMs: Int,
    val durationMs: Int
) : MissionEvent

sealed interface PadLatchEvent : MissionEvent

data class PadLatchOn(
    override val timeMs: Int,
    val pad: PadKey
) : PadLatchEvent

data class PadLatchOff(
    override val timeMs: Int
) : PadLatchEvent

data class ShipOnboard(
    override val timeMs: Int,
    val count: Int
) : MissionEvent

data class PadWaiting(
    override val timeMs: Int,
    val pad: PadKey,
    val count: Int
) : MissionEvent

data class CountdownLookup(
    val active: Boolean,        // countdown exists and has started
    val beforeStart: Boolean,   // t < start
    val destroyed: Boolean,     // t >= end
    val remainingMs: Int,       // clamped >= 0
    val endTimeMs: Int          // start + duration (or -1 if none)
)

data class PadLatchLookup(
    val pad: PadKey?,          // null if not latched
    val changed: Boolean,      // different from last returned result
    val beforeFirst: Boolean,  // timeMs before first latch event
    val afterLast: Boolean     // timeMs after last latch event
)

data class ShipOnboardLookup(
    val count: Int,
    val changed: Boolean,
    val beforeFirst: Boolean,
    val afterLast: Boolean
)

data class PadWaitingLookup(
    val count: Int,
    val changed: Boolean,
    val beforeFirst: Boolean,
    val afterLast: Boolean
)

class MissionLog(
    val flightLog: FlightLog,
    private val enableLogging: () -> Boolean = { true } // pass { enableLogging } if you have a global flag
) {
    var recording: Boolean = false

    // ---- Stored events (separate channels) ----
    private var destructStart: DestructStart? = null
    private val padLatchEvents = mutableListOf<PadLatchEvent>()
    private val shipOnboardEvents = mutableListOf<ShipOnboard>()
    private val padWaitingEventsByPad = mutableMapOf<PadKey, MutableList<PadWaiting>>()


    // state
    private var lastPadLatchLookupPad: PadKey? = null
    private var lastPadLatchLookupIdx: Int = -2  // -2 = “never looked up yet”

    private var lastShipOnboardLookupIdx: Int = -2
    private var lastShipOnboardLookupCount: Int = -1


    private val lastPadWaitingLookupIdx = mutableMapOf<PadKey, Int>()
    private val lastPadWaitingLookupCount = mutableMapOf<PadKey, Int>()


    // ---- Dedupe caches ----
    private var lastLatchedPad: PadKey? = null
    private var lastShipOnboard: Int? = null
    private val lastPadWaiting = mutableMapOf<PadKey, Int>()



    // -------------------------
    // Writers (capture events)
    // -------------------------

    fun logDestructStart(timeMs: Int, durationMs: Int) {
        if (!enableLogging() || !recording) return
        if (destructStart != null) return // only once
        destructStart = DestructStart(timeMs, durationMs)
    }

    fun logPadLatchOn(timeMs: Int, pad: PadKey) {
        if (!enableLogging() || !recording) return
        if (lastLatchedPad == pad) return
        padLatchEvents.add(PadLatchOn(timeMs, pad))
        lastLatchedPad = pad
    }

    fun logPadLatchOff(timeMs: Int) {
        if (!enableLogging() || !recording) return
        if (lastLatchedPad == null) return
        padLatchEvents.add(PadLatchOff(timeMs))
        lastLatchedPad = null
    }

    fun logShipOnboard(timeMs: Int, count: Int, firstTime: Boolean = false) {
        if (!enableLogging() || !recording) return
        if (lastShipOnboard == count && !firstTime) return
        shipOnboardEvents.add(ShipOnboard(timeMs, count))
        lastShipOnboard = count
    }

    fun logPadWaiting(timeMs: Int, pad: PadKey, count: Int) {
        if (!enableLogging() || !recording) return
        val last = lastPadWaiting[pad]
        if (last == count) return

        val list = padWaitingEventsByPad.getOrPut(pad) { mutableListOf() }
        list.add(PadWaiting(timeMs, pad, count))
        lastPadWaiting[pad] = count
    }

    fun copyFrom(src: MissionLog) {
        clear()
        recording = false

        destructStart = src.destructStart?.copy()

        padLatchEvents.addAll(src.padLatchEvents)

        shipOnboardEvents.addAll(src.shipOnboardEvents)

        for ((pad, list) in src.padWaitingEventsByPad) {
            padWaitingEventsByPad[pad] = list.toMutableList()
        }

        clearSamplerCaches()
    }

    fun clear() {
        // Stop recording while clearing
        recording = false

        // Clear stored events
        destructStart = null
        padLatchEvents.clear()
        shipOnboardEvents.clear()
        padWaitingEventsByPad.clear()

        clearSamplerCaches()

        // Clear dedupe caches
        lastLatchedPad = null
        lastShipOnboard = null
        lastPadWaiting.clear()
    }


    private fun clearSamplerCaches() {
        lastPadLatchLookupPad = null
        lastPadLatchLookupIdx = -2

        lastShipOnboardLookupIdx = -2
        lastShipOnboardLookupCount = -1

        lastPadWaitingLookupIdx.clear()
        lastPadWaitingLookupCount.clear()
    }


    // SAMPLING FUNCTIONS

    fun msLeftToZeroAt(timeMs: Int): Int {
        val ds = destructStart ?: return 0
        val end = ds.timeMs + ds.durationMs
        return (end - timeMs).coerceAtLeast(0)
    }
    fun countdownAt(timeMs: Int): CountdownLookup {
        val ds = destructStart
            ?: return CountdownLookup(
                active = false,
                beforeStart = true,
                destroyed = false,
                remainingMs = 0,
                endTimeMs = -1
            )

        val start = ds.timeMs
        val duration = ds.durationMs.coerceAtLeast(0)
        val end = start + duration

        if (timeMs < start) {
            // Not started yet (only really happens if you ever log start > 0)
            return CountdownLookup(
                active = false,
                beforeStart = true,
                destroyed = false,
                remainingMs = duration,
                endTimeMs = end
            )
        }

        val remaining = (end - timeMs).coerceAtLeast(0)
        val destroyed = (timeMs >= end)

        return CountdownLookup(
            active = true,
            beforeStart = false,
            destroyed = destroyed,
            remainingMs = remaining,
            endTimeMs = end
        )
    }

    fun padLatchedPadAt(timeMs: Int): PadLatchLookup {
        val events = padLatchEvents
        if (events.isEmpty()) {
            val changed = (lastPadLatchLookupIdx != -1 || lastPadLatchLookupPad != null)
            lastPadLatchLookupIdx = -1
            lastPadLatchLookupPad = null
            return PadLatchLookup(pad = null, changed = changed, beforeFirst = true, afterLast = false)
        }

        // binary search for last event with time <= query
        var lo = 0
        var hi = events.lastIndex
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeMs <= timeMs) {
                res = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        if (res == -1) {
            val changed = (lastPadLatchLookupIdx != -1 || lastPadLatchLookupPad != null)
            lastPadLatchLookupIdx = -1
            lastPadLatchLookupPad = null
            return PadLatchLookup(pad = null, changed = changed, beforeFirst = true, afterLast = false)
        }

        val ev = events[res]
        val pad = when (ev) {
            is PadLatchOn -> ev.pad
            is PadLatchOff -> null
        }

        val afterLast = (res == events.lastIndex && timeMs > events.last().timeMs)
        val changed = (res != lastPadLatchLookupIdx) || (pad != lastPadLatchLookupPad)

        lastPadLatchLookupIdx = res
        lastPadLatchLookupPad = pad

        return PadLatchLookup(pad = pad, changed = changed, beforeFirst = false, afterLast = afterLast)
    }


    fun shipOnboardAt(timeMs: Int): ShipOnboardLookup {
        val events = shipOnboardEvents
        if (events.isEmpty()) {
            val changed = (lastShipOnboardLookupIdx != -1 || lastShipOnboardLookupCount != 0)
            lastShipOnboardLookupIdx = -1
            lastShipOnboardLookupCount = 0
            return ShipOnboardLookup(count = 0, changed = changed, beforeFirst = true, afterLast = false)
        }

        // Binary search: last event with time <= query
        var lo = 0
        var hi = events.lastIndex
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeMs <= timeMs) { res = mid; lo = mid + 1 } else { hi = mid - 1 }
        }

        if (res == -1) {
            // Before first event: define baseline as 0
            val changed = (lastShipOnboardLookupIdx != -1 || lastShipOnboardLookupCount != 0)
            lastShipOnboardLookupIdx = -1
            lastShipOnboardLookupCount = 0
            return ShipOnboardLookup(count = 0, changed = changed, beforeFirst = true, afterLast = false)
        }

        val count = events[res].count
        val afterLast = (res == events.lastIndex && timeMs > events.last().timeMs)
        val changed = (res != lastShipOnboardLookupIdx) || (count != lastShipOnboardLookupCount)

        lastShipOnboardLookupIdx = res
        lastShipOnboardLookupCount = count

        return ShipOnboardLookup(count = count, changed = changed, beforeFirst = false, afterLast = afterLast)
    }


    fun padWaitingAt(timeMs: Int, pad: PadKey): PadWaitingLookup {
        val events = padWaitingEventsByPad[pad].orEmpty()

        if (events.isEmpty()) {
            val prevIdx = lastPadWaitingLookupIdx[pad]
            val prevCount = lastPadWaitingLookupCount[pad]
            val changed = (prevIdx != -1 || prevCount != 0)

            lastPadWaitingLookupIdx[pad] = -1
            lastPadWaitingLookupCount[pad] = 0

            return PadWaitingLookup(count = 0, changed = changed, beforeFirst = true, afterLast = false)
        }

        // Binary search: last event with time <= query
        var lo = 0
        var hi = events.lastIndex
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeMs <= timeMs) { res = mid; lo = mid + 1 } else { hi = mid - 1 }
        }

        if (res == -1) {
            val prevIdx = lastPadWaitingLookupIdx[pad]
            val prevCount = lastPadWaitingLookupCount[pad]
            val changed = (prevIdx != -1 || prevCount != 0)

            lastPadWaitingLookupIdx[pad] = -1
            lastPadWaitingLookupCount[pad] = 0

            return PadWaitingLookup(count = 0, changed = changed, beforeFirst = true, afterLast = false)
        }

        val count = events[res].count
        val afterLast = (res == events.lastIndex && timeMs > events.last().timeMs)

        val prevIdx = lastPadWaitingLookupIdx[pad]
        val prevCount = lastPadWaitingLookupCount[pad]
        val changed = (res != prevIdx) || (count != prevCount)

        lastPadWaitingLookupIdx[pad] = res
        lastPadWaitingLookupCount[pad] = count

        return PadWaitingLookup(count = count, changed = changed, beforeFirst = false, afterLast = afterLast)
    }


    // Convenience helpers
    fun countdownRemainingMsAt(timeMs: Int): Int = countdownAt(timeMs).remainingMs
    fun isDestroyedAt(timeMs: Int): Boolean = countdownAt(timeMs).destroyed
    fun padsWithWaitingEvents(): Set<PadKey> = padWaitingEventsByPad.keys



    // -------------------------
    // Serialization (stringify)
    // -------------------------

    fun stringify(): String {
        val sb = StringBuilder()
        sb.append("[Mission]\n")

        // Optional versioning line for future-proofing.
        sb.append("version=1\n")

        destructStart?.let { ds ->
            sb.append("destructStart=")
                .append(ds.timeMs).append(',')
                .append(ds.durationMs).append('\n')
        }

        // Latch events (as recorded order)
        for (e in padLatchEvents) {
            when (e) {
                is PadLatchOn -> sb.append("padLatchOn=")
                    .append(e.timeMs).append(',')
                    .append(e.pad.structureId).append(',')
                    .append(e.pad.blockIndex).append('\n')
                is PadLatchOff -> sb.append("padLatchOff=")
                    .append(e.timeMs).append('\n')
            }
        }

        // Ship onboard events
        for (e in shipOnboardEvents) {
            sb.append("shipOnboard=")
                .append(e.timeMs).append(',')
                .append(e.count).append('\n')
        }

        // Pad waiting events: write in deterministic pad-key order for stable diffs
        val padsSorted = padWaitingEventsByPad.keys.sortedWith(
            compareBy<PadKey> { it.structureId }.thenBy { it.blockIndex }
        )
        for (pad in padsSorted) {
            val list = padWaitingEventsByPad[pad].orEmpty()
            for (e in list) {
                sb.append("padWaiting=")
                    .append(e.timeMs).append(',')
                    .append(e.pad.structureId).append(',')
                    .append(e.pad.blockIndex).append(',')
                    .append(e.count).append('\n')
            }
        }

        return sb.toString()
    }

    fun parse(missionBlock: String) {
        clear()
        recording = false // parsed logs are frozen

        val lines = missionBlock
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        for (line in lines) {

            // Skip section header if included
            if (line.startsWith("[") && line.endsWith("]")) continue

            val eq = line.indexOf('=')
            if (eq <= 0) continue

            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)

            when (key) {

                "version" -> {
                    // ignore for now
                }

                "destructStart" -> {
                    val parts = value.split(',')
                    if (parts.size >= 2) {
                        val timeMs = parts[0].toInt()
                        val durationMs = parts[1].toInt()
                        destructStart = DestructStart(timeMs, durationMs)
                    }
                }

                "padLatchOn" -> {
                    val parts = value.split(',')
                    if (parts.size >= 3) {
                        val timeMs = parts[0].toInt()
                        val structureId = parts[1].toInt()
                        val blockIndex = parts[2].toInt()
                        val pad = PadKey(structureId, blockIndex)
                        padLatchEvents.add(PadLatchOn(timeMs, pad))
                    }
                }

                "padLatchOff" -> {
                    val timeMs = value.toInt()
                    padLatchEvents.add(PadLatchOff(timeMs))
                }

                "shipOnboard" -> {
                    val parts = value.split(',')
                    if (parts.size >= 2) {
                        val timeMs = parts[0].toInt()
                        val count = parts[1].toInt()
                        shipOnboardEvents.add(ShipOnboard(timeMs, count))
                    }
                }

                "padWaiting" -> {
                    val parts = value.split(',')
                    if (parts.size >= 4) {
                        val timeMs = parts[0].toInt()
                        val structureId = parts[1].toInt()
                        val blockIndex = parts[2].toInt()
                        val count = parts[3].toInt()

                        val pad = PadKey(structureId, blockIndex)
                        val list = padWaitingEventsByPad.getOrPut(pad) { mutableListOf() }
                        list.add(PadWaiting(timeMs, pad, count))
                    }
                }
            }
        }

        rebuildLastValueCaches()
        clearSamplerCaches()
    }

    private fun rebuildLastValueCaches() {
        // Ship
        lastShipOnboard = shipOnboardEvents.lastOrNull()?.count

        // Pads
        lastPadWaiting.clear()
        for ((pad, list) in padWaitingEventsByPad) {
            list.lastOrNull()?.let { lastPadWaiting[pad] = it.count }
        }

        // Latch
        lastLatchedPad = when (val last = padLatchEvents.lastOrNull()) {
            is PadLatchOn -> last.pad
            else -> null
        }
    }

    // -------------------------
    // Accessors (write-only phase)
    // -------------------------
    // These are just for debugging/tests now; sampling can come later.

    fun getDestructStart(): DestructStart? = destructStart
    fun getPadLatchEvents(): List<PadLatchEvent> = padLatchEvents
    fun getShipOnboardEvents(): List<ShipOnboard> = shipOnboardEvents
    fun getPadWaitingEvents(pad: PadKey): List<PadWaiting> = padWaitingEventsByPad[pad].orEmpty()
}
