package com.jimjo.exodefender

class ShipCameraFollower(private val events: List<ActorLog.LogEvent>) {

    // Scratch to avoid allocations
    private val tmpPos0 = Vec3()
    private val tmpPos1 = Vec3()
    private val tmpVel  = Vec3()
    private val outPos  = Vec3()

    /**
     * Returns a suggested camera focal point at timeMs.
     *
     * Strategy:
     * - Use nearby events to estimate velocity direction around timeMs.
     * - Offset the target in the direction of motion (like live camera).
     * - If we can't get a good velocity, just return the ship position.
     */
    fun getCameraTarget(timeMs: Int): Vec3? {
        if (events.isEmpty()) return null
        if (events.size == 1) {
            // Only one sample → just look at the ship
            val e = events[0]
            return outPos.set(e.x, e.y, e.z)
        }

        // 1) Find first index with timeMs >= query (lower bound)
        var lo = 0
        var hi = events.lastIndex
        var idxNext = events.size // default: after last
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeMs >= timeMs) {
                idxNext = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        val idxPrev = idxNext - 1

        // 2) Get ship position at timeMs (simple linear interp between prev/next)
        val shipPos = when {
            idxNext == 0 -> {
                // Before first sample: clamp to first
                val e = events[0]
                outPos.set(e.x, e.y, e.z)
            }
            idxNext >= events.size -> {
                // After last sample: clamp to last
                val e = events.last()
                outPos.set(e.x, e.y, e.z)
            }
            else -> {
                val a = events[idxPrev]
                val b = events[idxNext]
                val t0 = a.timeMs.toFloat()
                val t1 = b.timeMs.toFloat()
                val t  = ((timeMs - t0) / (t1 - t0)).coerceIn(0f, 1f)

                // Linear interpolation; replace with your existing smoother if you like
                outPos.set(
                    a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t,
                    a.z + (b.z - a.z) * t
                )
            }
        }

        // 3) Estimate velocity direction around timeMs from neighboring samples

        // Choose two samples to form a local segment:
        // - Prefer one before and one after timeMs when possible
        val (e0, e1) = when {
            idxNext in 1 until events.size -> {
                // We have both sides: [idxPrev, idxNext]
                events[idxPrev] to events[idxNext]
            }
            idxNext == 0 -> {
                // We are before first: use [0,1]
                events[0] to events[1]
            }
            else -> {
                // We are after last: use [last-1,last]
                val last = events.lastIndex
                events[last - 1] to events[last]
            }
        }

        val dtMs = (e1.timeMs - e0.timeMs)
        if (dtMs <= 0) {
            // Degenerate; just look at ship
            return shipPos
        }

        tmpPos0.set(e0.x, e0.y, e0.z)
        tmpPos1.set(e1.x, e1.y, e1.z)

        // vel = (p1 - p0) / dt
        tmpVel.set(tmpPos1).subLocal(tmpPos0).mulLocal(1000f / dtMs.toFloat())

        val speed = tmpVel.length()
        if (speed < 0.001f) {
            // Essentially stationary → no stylish offset; just focus ship
            return shipPos
        }

        // Direction of motion
        tmpVel.mulLocal(1f / speed) // normalize in place

        // 4) Compute offset like your live camera (tweak to taste)

        // Example: forward offset scales with speed, clamped
        val minLead = 3f     // meters in front at low speed
        val maxLead = 15f    // cap so it doesn’t go crazy at high speed
        val k = 0.05f        // how strongly speed influences lead
        val lead = (minLead + speed * k).coerceAtMost(maxLead)

        // focalPoint = shipPos + dir * lead
        // (Use outPos as base; don't mutate tmpVel permanently if you reuse it)
        return Vec3(
            shipPos.x + tmpVel.x * lead,
            shipPos.y + tmpVel.y * lead,
            shipPos.z + tmpVel.z * lead
        )
    }
}
