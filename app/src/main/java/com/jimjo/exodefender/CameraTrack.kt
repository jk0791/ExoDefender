package com.jimjo.exodefender

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


class CameraSample(
    val position: Vec3 = Vec3(),   // always reused, never replaced
    var mode: CameraMode = CameraMode.TRACK,
    var chaseDistance: Double = 0.0,
    var angleP: Double = 0.0,
    var angleE: Double = 0.0
)
@Serializable
class CameraTrack {
    val events = mutableListOf<CameraEvent>()

    @kotlinx.serialization.Transient
    private var needsSort = true

    fun add(event: CameraEvent) {
        events.add(event)
        ensureSorted(true)
    }

    fun clear() {
        events.clear()
        needsSort = false
    }

    fun sortEvents() {
        ensureSorted(true)
    }
    private fun ensureSorted(force: Boolean) {
        if (needsSort || force) {
            events.sortBy { it.timeMs }
            needsSort = false
        }
    }

    fun copyFrom(source: CameraTrack) {
        events.clear()
        for (sourceEvent in source.events) {
            events.add(sourceEvent.copy())
        }
    }

    /** Return a deep copy of this CameraTrack */
    fun deepCopy(): CameraTrack {
        val result = CameraTrack()
        for (e in events) {
            result.events.add(e.copy())
        }
        return result
    }
    fun stringify(): String {
        return Json.encodeToString(this)
    }

    /**
     * Zero-allocation evaluation into a mutable runtime sample.
     */
    fun evalInto(timeMs: Int, out: CameraSample) {
        ensureSorted(false)
        require(events.isNotEmpty()) { "CameraTrack has no events" }

        val list = events

        // Before first event → clamp to first.jumpTo
        if (timeMs <= list.first().timeMs) {
            applyCameraPos(list.first().jumpTo, out)
            return
        }

        // Find segment [E_i, E_{i+1}) containing timeMs
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]

            if (timeMs < b.timeMs) {
                val startTime = a.timeMs
                val endTime = b.timeMs
                val duration = (endTime - startTime).coerceAtLeast(1)
                val rawT = (timeMs - startTime).toDouble() / duration.toDouble()
                val t = rawT.coerceIn(0.0, 1.0)

                // No transition → stay at jumpTo
                if (b.transitionTo == null || b.transitionType == null) {
                    applyCameraPos(a.jumpTo, out)
                    return
                }

                val easedT = ease(t, b.transitionType!!)
                interpolateCameraPosInto(out, a.jumpTo, b.transitionTo!!, easedT)
                return
            }
        }

        val last = list.last()

//        // After last event → clamp to last transition target if any, else jumpTo
//        applyCameraPos(last.transitionTo ?: last.jumpTo, out)

        // After last event → clamp to last  jumpTo
        applyCameraPos(last.jumpTo, out)


    }

    private fun interpolateCameraPosInto(
        out: CameraSample,
        from: CameraPos,
        to: CameraPos,
        t: Double
    ) {
        val tt = t.coerceIn(0.0, 1.0)

        when {
            from is ChaseCameraPos && to is ChaseCameraPos -> {
                out.mode = CameraMode.CHASE
                out.chaseDistance = lerpScalar(from.distance, to.distance, tt)
                // position is irrelevant for pure chase mode; leave as is or set if you like
            }

            from is TrackCameraPos && to is TrackCameraPos -> {
                out.mode = CameraMode.TRACK
                lerpVec3Into(out.position, from.position, to.position, tt)
            }

            from is FixedCameraPos && to is FixedCameraPos -> {
                out.mode = CameraMode.FIXED
                lerpVec3Into(out.position, from.position, to.position, tt)
                out.angleP = lerpAngleRad(from.angleP, to.angleP, tt)
                out.angleE = lerpScalar(from.angleE, to.angleE, tt)
            }

            // Different types → simple cross-fade
            else -> {
                if (tt < 0.5) {
                    applyCameraPos(from, out)
                } else {
                    applyCameraPos(to, out)
                }
            }
        }
    }

    private fun applyCameraPos(pos: CameraPos, out: CameraSample) {
        when (pos) {
            is ChaseCameraPos -> {
                out.mode = CameraMode.CHASE
                out.chaseDistance = pos.distance
            }
            is TrackCameraPos -> {
                out.mode = CameraMode.TRACK
                out.position.set(pos.position)
            }
            is FixedCameraPos -> {
                out.mode = CameraMode.FIXED
                out.position.set(pos.position)
                out.angleP = pos.angleP
                out.angleE = pos.angleE
            }
        }
    }

    fun lerpScalar(a: Double, b: Double, t: Double): Double =
        a + (b - a) * t

    fun lerpAngleRad(a: Double, b: Double, t: Double): Double {
        // ensure inputs are in a nice range first (optional but helpful)
        val aNorm = normalizeAngleRad(a)
        val bNorm = normalizeAngleRad(b)

        var delta = (bNorm - aNorm) % TAU
        if (delta > Math.PI) delta -= TAU
        if (delta < -Math.PI) delta += TAU

        val result = aNorm + delta * t
        return normalizeAngleRad(result)
    }
    fun lerpVec3Into(out: Vec3, a: Vec3, b: Vec3, t: Double): Vec3 {
        val tt = t.toFloat()
        val oneMinusT = 1f - tt

        out.x = a.x * oneMinusT + b.x * tt
        out.y = a.y * oneMinusT + b.y * tt
        out.z = a.z * oneMinusT + b.z * tt
        return out
    }

}
fun ease(t: Double, type: CameraPosTransitionType): Double =
    when (type) {
        CameraPosTransitionType.LINEAR -> t

        CameraPosTransitionType.EASE_IN -> {
            // slow start, fast end
            t * t * t
        }

        CameraPosTransitionType.EASE_OUT -> {
            // fast start, slow end
            val u = 1.0 - t
            1.0 - u * u * u
        }

        CameraPosTransitionType.EASE_BOTH -> {
            // smooth in & out (cubic ease in/out)
            if (t < 0.5) {
                4.0 * t * t * t          // ease in first half
            } else {
                val u = -2.0 * t + 2.0   // remap [0.5,1] → [1,0]
                1.0 - (u * u * u) / 2.0  // ease out second half
            }
        }
    }

@Serializable
enum class CameraPosTransitionType { LINEAR, EASE_OUT, EASE_IN, EASE_BOTH }
@Serializable
class CameraEvent(
    var timeMs: Int,
    var jumpTo: CameraPos,
    var transitionTo: CameraPos?,
    var transitionType: CameraPosTransitionType?
) {
    fun copy(): CameraEvent {
        return CameraEvent(
            timeMs,
            jumpTo.copy(),
            if (transitionTo != null) transitionTo!!.copy() else null,
            transitionType
        )
    }
}
@Serializable
sealed class CameraPos {
    abstract fun copy(): CameraPos
}
@Serializable
@SerialName("Chase")
class ChaseCameraPos(val distance: Double): CameraPos() {
    override fun copy(): CameraPos {
        return ChaseCameraPos(distance)
    }
}
@Serializable
@SerialName("Track")
class TrackCameraPos(val position: Vec3): CameraPos() {
    override fun copy(): CameraPos {
        return TrackCameraPos(position.copy())
    }
}
@Serializable
@SerialName("Fixed")
class FixedCameraPos(val position: Vec3, val angleP: Double, val angleE: Double): CameraPos() {
    override fun copy(): CameraPos {
        return FixedCameraPos(position.copy(), angleP, angleE)
    }
}
