package com.jimjo.exodefender

import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.time.Instant

enum class CompletionOutcome(val value: Int) {
    SUCCESS(1),
    FAILED_DESTROYED(2),
    FAILED_ZERO_FRIENDLIES(3),
    FAILED_CIVILIANS_NOT_RESCUED(5),
    INCOMPLETE(4);

    private val intValue = value

    fun toInt(): Int {
        return intValue
    }
    companion object {
        fun fromInt(value: Int): CompletionOutcome =
            entries.firstOrNull { it.value == value } ?: INCOMPLETE
    }

}


class FlightLog(
    var levelId: Int = -1,
    var startTime: Date = Calendar.getInstance().time,
    var endTime: Date = Calendar.getInstance().time,
    ) {

    enum class FlightLogSection {
        NULL, LOG_DATA, ACTOR_DATA, EVENTS
    }

    var id: Long? = null
    var runId = UUID.randomUUID().toString()
    var completionOutcome = CompletionOutcome.INCOMPLETE

    var flightTimeMs = 0

    var friendliesStart = 0
    var friendliesRemaining = 0

    var enemiesStart = 0
    var enemiesDestroyed = 0

    var shotsFired = 0
    var shotsHit = 0
    var healthRemaining = 1f

    var enemyThreatSum = 0f
    var scoreVersion = 1
    var replayActive = false
    var replaySeeking = false
    var replayPaused = false
    var pausedBeforeSeeking = false

    val shipInitialPosition = Vec3()
    var shipInitialDirection = 0.0
    val shipLog = ActorLog(this)
    val replayActorTemplates = mutableListOf<ActorTemplate>()
    val actorLogs = mutableListOf<ActorLog>()

    var cameraTrack: CameraTrack? = null
    var shipSnapToOnNextReplayUpdate = false
    var recordingActive = false

    fun createActorLog(parent: Actor?, template: ActorTemplate?): ActorLog {
        val actorLog = ActorLog(this)
        actorLog.parent = parent
        actorLog.template = template
        actorLogs.add(actorLog)
        return actorLog
    }

    fun clear() {
        runId = UUID.randomUUID().toString()
        flightTimeMs = 0
        friendliesRemaining = 0
        enemiesDestroyed = 0
        shotsFired = 0
        shotsHit = 0
        healthRemaining = 1f
        shipLog.events.clear()
        for (log in actorLogs) {
            log.events.clear()
        }
        if (cameraTrack != null) cameraTrack!!.clear()
        reset()
    }

    fun createNewId() {
        id = Instant.now().epochSecond
    }

    fun reset() {
        shipSnapToOnNextReplayUpdate = true
        replaySeeking = false
        replayPaused = false
        shipLog.reset()
        for (log in actorLogs) {
            log.reset()
        }
    }

    fun prepareForPlayback() {
        shipLog.prepare()
        if (!shipLog.events.isEmpty()) {
            shipLog.hitCounter = ActorLog.HitCounter(shipLog.events)
            shipInitialPosition.set(
                shipLog.events.first().x,
                shipLog.events.first().y,
                shipLog.events.first().z,
            )
            shipInitialDirection = shipLog.events.first().angleP
        }

        for (log in actorLogs) {
            log.prepare()
        }
    }

    fun postRecordingProcessing() {
        shipLog.hitCounter = ActorLog.HitCounter(shipLog.events)
        shipLog.trimIfDestroyed()

    }

    fun createCopy(): FlightLog {
        val newFlightLog = FlightLog(levelId, startTime, endTime)
        newFlightLog.createNewId()
        newFlightLog.runId = runId
        newFlightLog.completionOutcome = completionOutcome
        newFlightLog.flightTimeMs = flightTimeMs
        newFlightLog.friendliesStart = friendliesStart
        newFlightLog.friendliesRemaining = friendliesRemaining
        newFlightLog.enemiesStart = enemiesStart
        newFlightLog.enemiesDestroyed = enemiesDestroyed
        newFlightLog.shotsFired = shotsFired
        newFlightLog.shotsHit = shotsHit
        newFlightLog.healthRemaining = healthRemaining
        newFlightLog.enemyThreatSum = enemyThreatSum
        newFlightLog.scoreVersion = scoreVersion

        newFlightLog.shipLog.copy(shipLog)
        for (actorLog in actorLogs) {
            newFlightLog.actorLogs.add(actorLog.createCopy(newFlightLog))
        }
        if (cameraTrack != null) {
            newFlightLog.cameraTrack = CameraTrack().apply { copyFrom(cameraTrack!!) }
        }

        return(newFlightLog)
    }

    fun playback() {
        shipLog.prepare()
        for (log in actorLogs) {
            log.prepare()
        }
        replayActive = true
    }

    fun cancelPlayback() {
        replayActive = false
    }

    fun startRecording() {
        clear()
        startTime = Calendar.getInstance().time
        recordingActive = true
        setRecordingState(true)

    }

    fun stopRecording() {
        endTime = Calendar.getInstance().time
        if (!shipLog.events.isEmpty()) {
            flightTimeMs = shipLog.events.last().timeMs
        }
        recordingActive = false
        setRecordingState(false)
        postRecordingProcessing()
    }

    fun pauseRecording() {
        if (recordingActive) {
            setRecordingState(false)
        }
    }

    fun unpauseRecording() {
        if (recordingActive) {
            setRecordingState(true)
        }
    }

    fun setRecordingState(active: Boolean) {
        shipLog.recording = active
        for (log in actorLogs) {
            log.recording = active
        }
    }

    fun cameraTrackFilename() = id.toString() + ".trk"

    fun stringify() : String {
        var output = ""
        output += "[LogData]\n"
        output += "id=$id\n"
        output += "levelId=$levelId\n"
        output += "startTime=${dateTimeFormat.format(startTime)}\n"
        output += "endTime=${dateTimeFormat.format(endTime)}\n"
        output += "runId=$runId\n"
        output += "completionOutcome=$completionOutcome\n"
        output += "flightTime=$flightTimeMs\n"
        output += "friendliesStart=$friendliesStart\n"
        output += "friendliesRemaining=$friendliesRemaining\n"
        output += "enemiesStart=$enemiesStart\n"
        output += "enemiesDestroyed=$enemiesDestroyed\n"
        output += "shotsFired=$shotsFired\n"
        output += "shotsHit=$shotsHit\n"
        output += "healthRemaining=$healthRemaining\n"
        output += "enemyThreatSum=$enemyThreatSum\n"
        output += "scoreVersion=$scoreVersion\n"
        if (cameraTrack != null) output += "cameraTrack=${cameraTrack!!.stringify()}\n"
        output += shipLog.stringify()
        for (log in actorLogs) {
            output += log.stringify()
        }

        return output
    }


    fun parse(input: String): Boolean {
        replayActorTemplates.clear()
        var sectionType = FlightLogSection.NULL
        var currentActorTemplate: ActorTemplate? = null
        var currentActorTypeText:String? = null
        var currentActorInitialPosition:Vec3? = null
        var currentActorInitialYaw:Double? = null
        var rowIndex = 0
        var rowText = ""

        try {

            val inputRows = input.split("\n")
            while (rowIndex < inputRows.size) {
                rowText = inputRows[rowIndex].trim()
                if (rowText != "") {

                    if (rowText.startsWith("[")) {
                        if (rowText.startsWith("[LogData]")) {
                            sectionType = FlightLogSection.LOG_DATA
                        } else if (rowText.startsWith("[ActorData]")) {
                            sectionType = FlightLogSection.ACTOR_DATA
                            currentActorTemplate = null
                            currentActorTypeText = null
                            currentActorInitialPosition = null
                            currentActorInitialYaw = null
                        } else if (rowText.startsWith("[Events]")) {
                            sectionType = FlightLogSection.EVENTS
                        }
                    } else {
                        when (sectionType) {
                            FlightLogSection.LOG_DATA -> {
                                val dataParameterName = rowText.substringBefore("=").trim()
                                val dataParameterText = rowText.substringAfter("=").trim()
                                when (dataParameterName) {
                                    "id" -> id = if (dataParameterText == "null") null else dataParameterText.toLong()
                                    "levelId" -> levelId = dataParameterText.toInt()
                                    "startTime" -> {
                                        try {
                                            startTime = dateTimeFormat.parse(dataParameterText)!!
                                        } catch (_: Exception) {}
                                    }
                                    "endTime" -> {
                                        try {
                                            endTime = dateTimeFormat.parse(dataParameterText)!!
                                        } catch (_: Exception) {}
                                    }
                                    "runId" -> runId = dataParameterText
                                    "completionOutcome" -> completionOutcome = enumValueOf(dataParameterText)
                                    "flightTime" -> flightTimeMs = dataParameterText.toInt()
                                    "friendliesStart" -> friendliesStart = dataParameterText.toInt()
                                    "friendliesRemaining" -> friendliesRemaining = dataParameterText.toInt()
                                    "enemiesStart" -> enemiesStart = dataParameterText.toInt()
                                    "enemiesDestroyed" -> enemiesDestroyed = dataParameterText.toInt()
                                    "shotsFired" -> shotsFired = dataParameterText.toInt()
                                    "shotsHit" -> shotsHit = dataParameterText.toInt()
                                    "healthRemaining" -> healthRemaining = dataParameterText.toFloat()
                                    "enemyThreatSum" -> enemyThreatSum = dataParameterText.toFloat()
                                    "scoreVersion" -> scoreVersion = dataParameterText.toInt()
                                    "cameraTrack" -> parseCameraTrack(dataParameterText)
                                    else -> {}
                                }
                            }

                            FlightLogSection.ACTOR_DATA -> {
                                // TODO create ActorTemplate or flag that following flight path is for ship
                                // TODO assign following flight path to correct ActorTemplate of ship


                                val dataParameterName = rowText.substringBefore("=").trim()
                                val dataParameterText = rowText.substringAfter("=").trim()

                                when (dataParameterName) {
                                    "type" -> currentActorTypeText = dataParameterText.trim()
                                    "initialPosition" -> {
                                        val values = dataParameterText.split(",")
                                        currentActorInitialPosition = Vec3(values[0].trim().toFloat(), values[1].trim().toFloat(), values[2].trim().toFloat())
                                    }
                                    "initialYaw" -> {
                                        currentActorInitialYaw = dataParameterText.trim().toDouble()
                                    }
                                    else -> {}
                                }



                                if (currentActorTemplate == null && currentActorTypeText != null && currentActorInitialPosition != null && currentActorInitialYaw != null) {
                                    when (currentActorTypeText) {
                                        "SHIP" -> shipLog.template = ShipTemplate(currentActorInitialPosition, yaw = currentActorInitialYaw)
                                        "GROUND_TRAINING_TARGET" -> currentActorTemplate = GroundTargetTemplate(position = currentActorInitialPosition, yaw = currentActorInitialYaw)
                                        "EASY_GROUND_ENEMY" -> currentActorTemplate = EasyGroundEnemyTemplate(position = currentActorInitialPosition, yaw = currentActorInitialYaw)
                                        "GROUND_ENEMY" -> currentActorTemplate = GroundEnemyTemplate(position = currentActorInitialPosition, yaw = currentActorInitialYaw)
                                        "EASY_FLYING_ENEMY" -> currentActorTemplate = EasyFlyingEnemyTemplate(position = currentActorInitialPosition, yaw = currentActorInitialYaw)
                                        "FLYING_ENEMY" -> currentActorTemplate = FlyingEnemyTemplate(position = currentActorInitialPosition, yaw = currentActorInitialYaw)
                                        "ADV_FLYING_ENEMY" -> currentActorTemplate = AdvFlyingEnemyTemplate(position = currentActorInitialPosition, yaw = currentActorInitialYaw)
                                        "GROUND_FRIENDLY" -> currentActorTemplate = GroundFriendlyTemplate(currentActorInitialPosition, currentActorInitialYaw)
                                        else -> {}
                                    }
                                    if (currentActorTemplate != null && currentActorTemplate != ShipTemplate) {
                                        currentActorTemplate.log = createActorLog(null, currentActorTemplate)
                                        replayActorTemplates.add(currentActorTemplate)
                                    }
                                }

                            }
                            FlightLogSection.EVENTS -> {
                                val valueStrings = rowText.split(",")
                                var i = 0
                                val event = ActorLog.LogEvent(
                                    timeMs = valueStrings[i].toInt(),
                                    x = valueStrings[++i].toFloat(),
                                    y = valueStrings[++i].toFloat(),
                                    z = valueStrings[++i].toFloat(),
                                    angleP = if (valueStrings[++i] != "") valueStrings[i].toDouble() else 0.0,
                                    angleE = if (valueStrings[++i] != "") valueStrings[i].toDouble() else 0.0,
                                    angleB = if (valueStrings[++i] != "") valueStrings[i].toDouble() else 0.0,
                                    weaponAngleP = if (valueStrings[++i] != "") valueStrings[i].toDouble() else 0.0,
                                    weaponAngleE = if (valueStrings[++i] != "") valueStrings[i].toDouble() else 0.0,
                                    firing = if (valueStrings[++i] != "") valueStrings[i].toInt() else 0,
                                    hit = if (valueStrings[++i] != "") valueStrings[i].toInt() else 0,
                                    destroyed = if (valueStrings[++i] != "") valueStrings[i].toInt() else 0,
                                )
                                // check which ActorTemplate log this event should be added to
                                if (currentActorTypeText == "SHIP") {
                                    shipLog.events.add(event)
                                }
                                else if (currentActorTemplate != null) {
                                    if (currentActorTemplate.log != null) {
                                        currentActorTemplate.log!!.events.add(event)
                                    }
                                    else {
                                        println("ERROR: currentActorTemplate does not yet have an Actorlog")
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
                rowIndex++
            }
        }
        catch (e: Exception) {
            println("ERROR: unable to parse flight record ${sectionType} section ${e.message!!}")
            println("ERROR: row index $rowIndex: \"$rowText\"")
            return false
        }
        return true
    }

    fun parseCameraTrack(cameraTrackText: String) {
        cameraTrack = Json.decodeFromString(CameraTrack.serializer(), cameraTrackText)
    }

}

class ActorLog(val flightLog: FlightLog) {
    class LogEvent(
        var timeMs: Int,
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var angleP: Double = 0.0,
        var angleE: Double = 0.0,
        var angleB: Double = 0.0,
        var weaponAngleP: Double = 0.0,
        var weaponAngleE: Double = 0.0,
        var firing: Int = 0,
        var hit: Int = 0,
        var destroyed: Int = 0,
        ): Comparable<LogEvent> {



        fun copy(source: LogEvent) {
            timeMs = source.timeMs
            x = source.x
            y = source.y
            z = source.z
            angleP = source.angleP
            angleE = source.angleE
            angleB = source.angleB
            weaponAngleP = source.weaponAngleP
            weaponAngleE = source.weaponAngleE
            firing = source.firing
            hit = source.hit
            destroyed = source.destroyed
        }

        fun lerpTo(other: LogEvent, t: Float, outTimeMs: Int, includeDiscretes: Boolean): LogEvent {
            if (includeDiscretes)
                return LogEvent(
                    timeMs = outTimeMs,
                    x = lerp(this.x, other.x, t),
                    y = lerp(this.y, other.y, t),
                    z = lerp(this.z, other.z, t),
                    angleP = lerp(this.angleP, other.angleP, t),
                    angleE = lerp(this.angleE, other.angleE, t),
                    angleB = lerp(this.angleB, other.angleB, t),
                    weaponAngleP = lerp(this.weaponAngleP, other.weaponAngleP, t),
                    weaponAngleE = lerp(this.weaponAngleE, other.weaponAngleE, t),
                    firing = this.firing,
                    hit = this.hit,
                    destroyed = this.destroyed,
                )
            else {
                return LogEvent(
                    timeMs = outTimeMs,
                    x = lerp(this.x, other.x, t),
                    y = lerp(this.y, other.y, t),
                    z = lerp(this.z, other.z, t),
                    angleP = lerp(this.angleP, other.angleP, t),
                    angleE = lerp(this.angleE, other.angleE, t),
                    angleB = lerp(this.angleB, other.angleB, t),
                    weaponAngleP = lerp(this.weaponAngleP, other.weaponAngleP, t),
                    weaponAngleE = lerp(this.weaponAngleE, other.weaponAngleE, t),
                    firing = 0,
                    hit = 0,
                    destroyed = 0,
                )
            }
        }

        override fun compareTo(other: LogEvent): Int {
            return timeMs - other.timeMs
        }
    }

    data class EventLookup(
        val event: LogEvent?,      // null if before first
        val changed: Boolean,      // true if different from last returned
        val index: Int,            // event index, or -1 if null
        val beforeFirst: Boolean,  // true if query < first event
        val afterLast: Boolean,    // true if query > last event
        val lastDestroyed: Boolean // true if last event was destroyed
    )

    /** Keeps track of the last returned event so you know if it changed. */
    class EventCursor(private val events: List<LogEvent>) {
        private var lastIndex: Int = -1  // -1 means "last was null"


        /**    fun get(timeMs: Int): EventLookup
         * ┌────────────────────────┬───────────────────────────────┬──────────────┬────────────┬────────────────┐
         * │ Query case             │ Returned event                │ beforeFirst  │ afterLast  │ lastDestroyed  │
         * ├────────────────────────┼───────────────────────────────┼──────────────┼────────────┼────────────────┤
         * │ Before first event     │ null                          │ true         │ false      │ false          │
         * │ Within range           │ event with timeMs <= query    │ false        │ false      │ true if that   │
         * │                        │                               │              │            │ event is last  │
         * │ After last event       │ last event                    │ false        │ true       │ true if last   │
         * │                        │                               │              │            │ event.destroyed│
         * └────────────────────────┴───────────────────────────────┴──────────────┴────────────┴────────────────┘
         *
         * Behavior summary:
         * - Returns the last event with timeMs <= query.
         * - If query is before the first event → returns null with beforeFirst = true.
         * - If query is after the last event → always returns the last event,
         *   and afterLast = true (plus lastDestroyed if applicable).
         */

        fun get(timeMs: Int): EventLookup {
            if (events.isEmpty()) {
                val changed = (lastIndex != -1)
                lastIndex = -1
                return EventLookup(
                    event = null,
                    changed = changed,
                    index = -1,
                    beforeFirst = true,
                    afterLast = false,
                    lastDestroyed = false
                )
            }

            // --- binary search for LAST event with timeMs <= query ---
            var lo = 0
            var hi = events.lastIndex
            var res = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (events[mid].timeMs <= timeMs) {
                    res = mid          // candidate satisfies <= query
                    lo = mid + 1       // move right for later candidates
                } else {
                    hi = mid - 1
                }
            }

            val lastIdx = events.lastIndex
            val lastEvent = events[lastIdx]

            // --- query before first event ---
            if (res == -1) {
                val changed = (lastIndex != -1)
                lastIndex = -1
                return EventLookup(
                    event = null,
                    changed = changed,
                    index = -1,
                    beforeFirst = true,
                    afterLast = false,
                    lastDestroyed = false
                )
            }

            // --- query AFTER last event ---
            if (timeMs > lastEvent.timeMs) {
                val changed = (lastIndex != lastIdx)
                lastIndex = lastIdx
                return EventLookup(
                    event = lastEvent,
                    changed = changed,
                    index = lastIdx,
                    beforeFirst = false,
                    afterLast = true,
                    lastDestroyed = (lastEvent.destroyed == 1)
                )
            }

            // --- normal case: event found within range ---
            val idx = res
            val ev = events[idx]
            val changed = (idx != lastIndex)
            lastIndex = idx
            return EventLookup(
                event = ev,
                changed = changed,
                index = idx,
                beforeFirst = false,
                afterLast = false,
                lastDestroyed = (idx == lastIdx && lastEvent.destroyed == 1)
            )
        }

        /** Call this if you mutate/sort the list externally. */
        fun reset() { lastIndex = -1 }
    }

    class HitCounter(events: List<LogEvent>) {

        // Strictly increasing times of hit events
        private val hitTimes: IntArray = events
            .asSequence()
            .filter { it.hit == 1 }
            .map { it.timeMs }
            .toList()
            .toIntArray()

        /**
         * Count events with hit == 1 STRICTLY BEFORE timeMs.
         */
        fun countHitsBefore(timeMs: Int): Int {
            // hitTimes is tiny (0..7), so a simple linear scan is absolutely fine.
            var count = 0
            val n = hitTimes.size
            while (count < n && hitTimes[count] < timeMs) {
                count++
            }
            return count
        }
    }


    var parent: Actor? = null
    var template: ActorTemplate? = null
    val events = mutableListOf<LogEvent>()

    var actorDestroyed = false
    var hitCounter = HitCounter(events)
    val cursor = EventCursor(events)
    var recording = false
    var lastPlayedContinuousEventId = 0 // only used for non-continuous logs

    fun reset() {
        cursor.reset()
    }

    fun prepare() {}

    fun trimIfDestroyed() {
        if (actorDestroyed) {

            // find index of destroyed event
            var i = 0
            var indexOfDestroyed = -1
            while (i < events.size) {
                if (events[i].destroyed == 1) {
                    indexOfDestroyed = i
                    break;
                }
                i++
            }

            // iterate backwards from end removing elements after destroyed event
            if (indexOfDestroyed != -1) {
                i = events.size - 1
                while (i > indexOfDestroyed) {
                    events.removeAt(i)
                    i--
                }
            }
        }
    }

    fun createCopy(parentFlightLog: FlightLog): ActorLog {
        val newActorLog = ActorLog(parentFlightLog)
        copy(this, newActorLog)
        return(newActorLog)
    }

    fun copy(sourceActorLog: ActorLog) {
        events.clear()
        copy(sourceActorLog, this)
    }

    fun copy(sourceActorLog: ActorLog, targetActorLog: ActorLog) {
        targetActorLog.parent = sourceActorLog.parent
        targetActorLog.template = sourceActorLog.template
        for (sourceEvent in sourceActorLog.events) {
            targetActorLog.events.add(LogEvent(sourceEvent.timeMs).also { it.copy(sourceEvent) })
        }
    }


    /**
     * Returns an interpolated Event at currentTimeMs, or null if outside the recorded range.
     * Assumes events are sorted by timeMs ascending. If not, sort before calling.
     */
    fun interpolateEventAtTime(currentTimeMs: Int, velocity: Vec3?): LogEvent? {
        if (events.isEmpty()) return null
        if (currentTimeMs < events.first().timeMs) return null
        if (currentTimeMs > events.last().timeMs) {
            val newInterval = lastPlayedContinuousEventId != events.lastIndex
            if (newInterval) {
                lastPlayedContinuousEventId = events.lastIndex
                return events.last()
            }
            else {
                return null
            }
        }


        // Exact hits fast path
        if (currentTimeMs == events.first().timeMs) return events.first()
        if (currentTimeMs == events.last().timeMs)  return events.last()

        // Binary search for bracketing pair [i0, i1]
        var lo = 0
        var hi = events.lastIndex
        var i0 = -1
        var i1 = -1

        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val tMid = events[mid].timeMs
            when {
                currentTimeMs < tMid -> hi = mid - 1
                currentTimeMs > tMid -> lo = mid + 1
                else -> return events[mid] // exact timestamp
            }
        }
        // After the loop: hi is last index with time <= currentTimeMs, lo is first index with time >= currentTimeMs
        i0 = hi
        i1 = lo

//        if (i0 < 0 || i1 >= events.size) return null // safety, though the range checks above should prevent this



        val e0 = events[i0]
        val e1 = events[i1]
        val dt = (e1.timeMs - e0.timeMs)
        if (dt <= 0) return events[currentTimeMs] // identical timestamps; just return one

        val t = (currentTimeMs - e0.timeMs).toFloat() / dt.toFloat()
        val newInterval = lastPlayedContinuousEventId != i0
        lastPlayedContinuousEventId = i0

        val e = e0.lerpTo(e1, t, currentTimeMs, newInterval)

        if (velocity != null) {

            val e2 = if (i1 < events.lastIndex) events[i1 + 1] else null
            interpolatVelocity(e, e0, e1, e2, velocity)
        }

        return e
    }

    // calculates an interpolated velocity between two event points (e0 and e1),
    // velocity of the second point (e1) is calculated using displacement and time to a third point (e2)
    fun interpolatVelocity(e: LogEvent, e0: LogEvent, e1: LogEvent, e2: LogEvent?, outVelocity: Vec3) {
        val dtTimeMs0 = (e1.timeMs - e0.timeMs)
        val dt0 = dtTimeMs0 / 1000f
        val v0x = (e1.x - e0.x) / dt0
        val v0y = (e1.y - e0.y) / dt0
        val v0z = (e1.z - e0.z) / dt0

        if (e2 != null) {

            val dt1 = (e2.timeMs - e1.timeMs) / 1000f
            val v1x = (e2.x - e1.x) / dt1
            val v1y = (e2.y - e1.y) / dt1
            val v1z = (e2.z - e1.z) / dt1

            val t = (e.timeMs - e0.timeMs).toFloat() / dtTimeMs0.toFloat()

            outVelocity.set(
                lerp(v0x, v1x, t),
                lerp(v0y, v1y, t),
                lerp(v0z, v1z, t),
            )
        }
        else {
            outVelocity.set(v0x, v0y, v0z)
        }
    }


    private val tmpPos0 = Vec3()
    private val tmpPos1 = Vec3()
    private val tmpVel  = Vec3()


    private fun applyCameraFromSegment(
        e0: ActorLog.LogEvent,
        e1: ActorLog.LogEvent,
        shipPos: ActorLog.LogEvent,
        outCam: Vec3
    ) {
        val dtMs = e1.timeMs - e0.timeMs
        if (dtMs <= 0) {
            // Degenerate; just look at the ship
            outCam.set(shipPos.x , shipPos.y, shipPos.z)
            return
        }

        tmpPos0.set(e0.x, e0.y, e0.z)
        tmpPos1.set(e1.x, e1.y, e1.z)

        // Velocity = (p1 - p0) / dt
        tmpVel.set(tmpPos1).subLocal(tmpPos0).mulLocal(1000f / dtMs.toFloat())

        val speed = tmpVel.length()
        if (speed < 0.001f) {
            // Basically stationary → focal point = ship
            outCam.set(shipPos.x , shipPos.y, shipPos.z)
            return
        }

        // Direction
        tmpVel.mulLocal(1f / speed) // normalize

        // Lead distance tuning:
        // tweak these three values to match your live camera "feel".
        val minLead = 3f      // base distance in front
        val maxLead = 16f     // clamp so it doesn't go crazy
        val k = 50f         // how strongly speed scales lead

        val lead = (maxLead - speed * k).coerceAtLeast(minLead)

        outCam.set(
            shipPos.x + tmpVel.x * lead,
            shipPos.y + tmpVel.y * lead,
            shipPos.z + tmpVel.z * lead
        )
    }


    fun stringify() : String {
        var output = ""
        output += "[ActorData]\n"

//        if (parent is ShipActor) output += "type=SHIP\n"
//        else if (parent is GroundFriendlyActor) output += "type=GROUND_FRIENDLY\n"
//        else if (parent is GroundTrainingTargetActor) output += "type=GROUND_TRAINING_TARGET\n"
//        else if (parent is EasyGroundEnemyActor) output += "type=EASY_GROUND_ENEMY\n"
//        else if (parent is GroundEnemyActor) output += "type=GROUND_ENEMY\n"
//        else if (parent is EasyFlyingEnemyActor) output += "type=EASY_FLYING_ENEMY\n"
//        else if (parent is FlyingEnemyActor) output += "type=FLYING_ENEMY\n"
//        else if (parent is AdvFlyingEnemyActor) output += "type=ADV_FLYING_ENEMY\n"
//        else output += "type=UNKNOWN\n"

        when (template) {
            is ShipTemplate -> output += "type=SHIP\n"
            is GroundFriendlyTemplate -> output += "type=GROUND_FRIENDLY\n"
            is GroundTargetTemplate -> output += "type=GROUND_TRAINING_TARGET\n"
            is EasyGroundEnemyTemplate -> output += "type=EASY_GROUND_ENEMY\n"
            is GroundEnemyTemplate -> output += "type=GROUND_ENEMY\n"
            is EasyFlyingEnemyTemplate -> output += "type=EASY_FLYING_ENEMY\n"
            is FlyingEnemyTemplate -> output += "type=FLYING_ENEMY\n"
            is AdvFlyingEnemyTemplate -> output += "type=ADV_FLYING_ENEMY\n"
            else -> output += "type=UNKNOWN\n"
        }

        if (template != null) {

            output += "initialPosition=${template!!.position.x},${template!!.position.y},${template!!.position.z}\n"
            output += "initialYaw=${template!!.yaw}\n"

            output += "[Events]\n"
            events.forEach {
                output += "${it.timeMs}," +
                        "${df2.format(it.x)}," +
                        "${df2.format(it.y)}," +
                        "${df2.format(it.z)}," +
                        "${if (it.angleP == 0.0) "" else it.angleP}," +
                        "${if (it.angleE == 0.0) "" else it.angleE}," +
                        "${if (it.angleB == 0.0) "" else it.angleB.toFloat()}," +
                        "${if (it.weaponAngleP == 0.0) "" else it.weaponAngleP}," +
                        "${if (it.weaponAngleE == 0.0) "" else it.weaponAngleE}," +
                        "${if (it.firing == 0) "" else it.firing}," +
                        "${if (it.hit == 0) "" else it.hit}," +
                        "${if (it.destroyed == 0) "" else it.destroyed}," +
                        "\n"
            }
        }
        return output
    }
}



