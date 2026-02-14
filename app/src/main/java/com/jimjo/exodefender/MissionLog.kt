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

sealed interface LatchEvent : MissionEvent

data class LatchOn(
    override val timeMs: Int,
    val pad: PadKey
) : LatchEvent

data class LatchOff(
    override val timeMs: Int
) : LatchEvent

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

class MissionLog(
    val flightLog: FlightLog,
    private val enableLogging: () -> Boolean = { true } // pass { enableLogging } if you have a global flag
) {
    var recording: Boolean = false

    // ---- Stored events (separate channels) ----
    private var destructStart: DestructStart? = null
    private val latchEvents = mutableListOf<LatchEvent>()
    private val shipOnboardEvents = mutableListOf<ShipOnboard>()
    private val padWaitingEventsByPad = mutableMapOf<PadKey, MutableList<PadWaiting>>()

    // ---- Dedupe caches ----
    private var lastLatchedPad: PadKey? = null
    private var lastShipOnboard: Int? = null
    private val lastPadWaiting = mutableMapOf<PadKey, Int>()

    fun resetRecordingCaches() {
        // Call at the start of a new recording run (or after loading).
        lastLatchedPad = null
        lastShipOnboard = null
        lastPadWaiting.clear()
    }

    // -------------------------
    // Writers (capture events)
    // -------------------------

    fun logDestructStart(timeMs: Int, durationMs: Int) {
        if (!enableLogging() || !recording) return
        if (destructStart != null) return // only once
        destructStart = DestructStart(timeMs, durationMs)
    }

    fun logLatchOn(timeMs: Int, pad: PadKey) {
        if (!enableLogging() || !recording) return
        if (lastLatchedPad == pad) return
        latchEvents.add(LatchOn(timeMs, pad))
        lastLatchedPad = pad
    }

    fun logLatchOff(timeMs: Int) {
        if (!enableLogging() || !recording) return
        if (lastLatchedPad == null) return
        latchEvents.add(LatchOff(timeMs))
        lastLatchedPad = null
    }

    fun logShipOnboard(timeMs: Int, count: Int) {
        if (!enableLogging() || !recording) return
        if (lastShipOnboard == count) return
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

        destructStart = src.destructStart // data class; safe to share or copy

        latchEvents.addAll(src.latchEvents)

        shipOnboardEvents.addAll(src.shipOnboardEvents)

        for ((pad, list) in src.padWaitingEventsByPad) {
            padWaitingEventsByPad[pad] = list.toMutableList()
        }
    }

    fun clear() {
        // Stop recording while clearing
        recording = false

        // Clear stored events
        destructStart = null
        latchEvents.clear()
        shipOnboardEvents.clear()
        padWaitingEventsByPad.clear()

        // Clear dedupe caches
        lastLatchedPad = null
        lastShipOnboard = null
        lastPadWaiting.clear()
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

    // Convenience helpers
    fun countdownRemainingMsAt(timeMs: Int): Int = countdownAt(timeMs).remainingMs
    fun isDestroyedAt(timeMs: Int): Boolean = countdownAt(timeMs).destroyed






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
        for (e in latchEvents) {
            when (e) {
                is LatchOn -> sb.append("latchOn=")
                    .append(e.timeMs).append(',')
                    .append(e.pad.structureId).append(',')
                    .append(e.pad.blockIndex).append('\n')
                is LatchOff -> sb.append("latchOff=")
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

                "latchOn" -> {
                    val parts = value.split(',')
                    if (parts.size >= 3) {
                        val timeMs = parts[0].toInt()
                        val structureId = parts[1].toInt()
                        val blockIndex = parts[2].toInt()
                        val pad = PadKey(structureId, blockIndex)
                        latchEvents.add(LatchOn(timeMs, pad))
                    }
                }

                "latchOff" -> {
                    val timeMs = value.toInt()
                    latchEvents.add(LatchOff(timeMs))
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
        lastLatchedPad = when (val last = latchEvents.lastOrNull()) {
            is LatchOn -> last.pad
            else -> null
        }
    }

    // -------------------------
    // Accessors (write-only phase)
    // -------------------------
    // These are just for debugging/tests now; sampling can come later.

    fun getDestructStart(): DestructStart? = destructStart
    fun getLatchEvents(): List<LatchEvent> = latchEvents
    fun getShipOnboardEvents(): List<ShipOnboard> = shipOnboardEvents
    fun getPadWaitingEvents(pad: PadKey): List<PadWaiting> = padWaitingEventsByPad[pad].orEmpty()
}
