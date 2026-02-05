package com.jimjo.exodefender

import kotlin.math.max

enum class CameraMode {CHASE, TRACK, FIXED}
class Camera(val flightControls: FlightControls, val ship: ShipActor, val world: World) {
    val position = Vec3()
    val focalPoint = Vec3()
//    val orientationUp = Vec3(0f, 1f, 0f)
    val orientationUp = Vec3(0f, 1f, 0f)

//    var chaseModeActive = true
    var mode = CameraMode.CHASE
    var posedByTrack = false
    val defaultChaseDistance = 40.0
    val defaultChasePitchOffset = 0.35
    val defaultChaseYawOffset = 0.0

    var chasePitchOffset = defaultChasePitchOffset
    var chaseYawOffset = defaultChaseYawOffset

    var angleP = 0.0
        set(value) {
            field = value
            sinAngleP = Math.sin(value)
            cosAngleP = Math.cos(value)
        }
    var angleE = 0.0
        set(value) {
            field = value
            sinAngleE = Math.sin(value)
            cosAngleE = Math.cos(value)
        }

    var sinAngleP = 0.0
        private set
    var cosAngleP = 1.0
        private set
    var sinAngleE = 0.0
        private set
    var cosAngleE = 1.0
        private set

    var distanceToFocalPoint = 0.0
    var agl = 0f

    var velocityF = 0f  // metres per second
    var velocityH = 0f
    var velocityV = 0f

    fun setDirection(angleP_: Double, angleE_: Double) {
        angleP = angleP_
        angleE = angleE_
    }

    fun forwardWorld(out: Vec3): Vec3 {
        val cE = cosAngleE.toFloat()
        val sE = sinAngleE.toFloat()
        val sP = sinAngleP.toFloat()
        val cP = cosAngleP.toFloat()

        out.set(
            -cE * sP,   // x
            -cE * cP,   // y
            sE         // z
        )
        return out
    }
    fun updatePositionForDistanceToFocalPoint() {
        val z = (-distanceToFocalPoint * sinAngleE).toFloat() + focalPoint.z
        val xy = (distanceToFocalPoint * cosAngleE).toFloat()
        val x = focalPoint.x + xy * sinAngleP.toFloat()
        val y = focalPoint.y + xy * cosAngleP.toFloat()

        position.set(x, y, z)
    }

    fun updateDirectionForFocalPoint(focalPoint: Vec3) {
        distanceToFocalPoint = position.distance(focalPoint).toDouble()
        val e = Math.asin((focalPoint.z - position.z).toDouble() / distanceToFocalPoint)

        val y = (position.y -  focalPoint.y).toDouble()
        val x = (focalPoint.x - position.x).toDouble()
        val p = getPlanAngle(x, y)

        setDirection(p, e)
    }

    fun updateFocalPointForPosition(r: Double) {
        focalPoint.z = position.z + (r * Math.sin(angleE)).toFloat()
        val xy: Double
        if (angleE != 0.0) {
            xy = r * Math.cos(angleE)
        }
        else {
            xy = r
        }
        focalPoint.x = position.x + (xy * Math.sin(angleP)).toFloat()
        focalPoint.y = position.y - (xy * Math.cos(angleP)).toFloat()
    }


    fun updateDirectly(interval: Float) {

        val elevation = world.terrainElevationAt(position.x, position.y)
        if (elevation != null) {
            agl = position.z - elevation
        }

        angleP -= flightControls.rotationHorz * interval
        angleE -= flightControls.rotationVert * interval
        angleE = Math.max(Math.min(angleE, 1.57), -1.57)

        val factor = max(agl, 1f)
        velocityF = (flightControls.throttle - 0.5f) * factor * 2f
        velocityH = flightControls.translationHorz * factor
        velocityV = flightControls.translationVert * factor

        val displacementF = velocityF * interval
        val displacementH = velocityH * interval
        val displacementV = velocityV * interval
        val displacementVxy = displacementV * -sinAngleE.toFloat() // displacement on the horizontal plane due to vertical displacement

        position.x -= (displacementF * sinAngleP).toFloat() - displacementH * cosAngleP.toFloat() - displacementVxy * sinAngleP.toFloat()
        position.y -= (displacementF * cosAngleP).toFloat() + displacementH * sinAngleP.toFloat() - displacementVxy * cosAngleP.toFloat()
        position.z -= displacementF * -sinAngleE.toFloat() + displacementV * cosAngleE.toFloat()

        updateFocalPointForDirection()
    }

    fun updateReplay(interval: Float) {


        val displacementF: Float
        if (flightControls.throttle != 0.5f) {
            displacementF = distanceToFocalPoint.toFloat() * (0.5f - flightControls.throttle) * interval * 3f
            distanceToFocalPoint += displacementF
        }
        else {
            displacementF = 0f
        }

        if (mode == CameraMode.CHASE) {
            updatePositionForDistanceToFocalPoint()
            updateForChase()
        }
        else if (mode == CameraMode.TRACK) {  // mode == CameraMode.FIXED

            val displacementH = flightControls.translationHorz * distanceToFocalPoint.toFloat() * interval
            var displacementV = flightControls.translationVert * distanceToFocalPoint.toFloat() * interval
            if ((angleE < -1.4 && displacementV < 0) || (angleE > 1.4 && displacementV > 0)) {
                displacementV = 0f
            }

            val displacementVxy = displacementV * sinAngleE.toFloat() // displacement on the horizontal plane due to vertical displacement

//            val elevation = gameMap.terrainElevationAt(position.x, position.y)
//            if (elevation != null) {
//                agl = position.z - elevation
//            }

            position.x -= (displacementF * sinAngleP).toFloat() - displacementH * cosAngleP.toFloat()  - displacementVxy * sinAngleP.toFloat()
            position.y -= -(displacementF * cosAngleP).toFloat() - displacementH * sinAngleP.toFloat() + displacementVxy * cosAngleP.toFloat()
            position.z -= -displacementF * -sinAngleE.toFloat() + displacementV * cosAngleE.toFloat()


//            println("displacementH=$displacementH displacementV=$displacementV cosAngleP=$cosAngleP sinAngleP=$sinAngleP distanceToFocalPoint=$distanceToFocalPoint")

//            updateDirectionForFocalPoint(focalPoint)

            focalPoint.set(ship.position)
            updateDirectionForFocalPoint(focalPoint)
        }
        else {  // // mode == CameraMode.FIXED
            updateDirectly(interval)
        }
    }

    fun updateReplayPosed(cameraSample: CameraSample) {
        setCameraMode(cameraSample.mode)

        when (cameraSample.mode) {
            CameraMode.CHASE -> {
                distanceToFocalPoint = cameraSample.chaseDistance
                updatePositionForDistanceToFocalPoint()
                updateForChase()
            }
            CameraMode.TRACK -> {
                // e.g. chase cam locked to a point in world
                position.set(cameraSample.position)
                focalPoint.set(ship.position)
                updateDirectionForFocalPoint(focalPoint)

            }
            CameraMode.FIXED -> {
                position.set(cameraSample.position)
                angleP = cameraSample.angleP
                angleE = cameraSample.angleE
                updateFocalPointForDirection()
            }
        }
    }

    fun setCameraMode(mode: CameraMode) {
        this.mode = mode
        when (mode) {
            CameraMode.CHASE -> {
                distanceToFocalPoint = defaultChaseDistance
                chasePitchOffset = defaultChasePitchOffset
                chaseYawOffset = defaultChaseYawOffset
           }
            CameraMode.TRACK -> {}
            CameraMode.FIXED -> {}
        }
    }


    fun updateForChase() {
        focalPoint.set(ship.chaseFocalPointWorld)
        angleP = ship.instance.yawRad - chaseYawOffset
        angleE = ship.instance.pitchRad - chasePitchOffset
        updatePositionForDistanceToFocalPoint()
    }

    fun updateFocalPointForDirection() {
        focalPoint.set(
            (cosAngleE * -sinAngleP).toFloat(),
            (-cosAngleE * cosAngleP).toFloat(), // x points backward, so -cosP
            (sinAngleE).toFloat()
        ).normalizeInPlace()
        focalPoint.mulLocal(distanceToFocalPoint.toFloat())
        focalPoint.addLocal(position)
    }
}

class SmoothTarget(
    x: Float = 0f,
    y: Float = 0f,
    z: Float = 0f
) {
    val pos = Vec3(x, y, z)

    fun snapTo(target: Vec3) {
        if (!target.isFinite()) return
        pos.set(target)
    }

    /**
     * Smoothly move towards target.
     *
     * @param dt         elapsed time in seconds (non-negative, small)
     * @param stiffness  responsiveness; ~5-12 is typical
     * @param maxDt      clamp dt to avoid huge jumps
     */
    fun updateTowards(target: Vec3, dt: Int, stiffness: Float, maxDt: Int = 100) {
        // If target is bogus, bail.
        if (!target.isFinite()) {
//            println("Camera.updateTowards() invalid target")
            return
        }

        // Bad or zero/negative dt? Just snap.
        if (!dt.toFloat().isFinite() || dt <= 0f) {
//            println("Camera.updateTowards() invalid dt")
            pos.set(target)
            return
        }

        // Clamp dt so a hitch or pause doesn't explode physics.
        val safeDt = dt.coerceAtMost(maxDt)

        // Standard exponential smoothing factor.
        val exponent = -stiffness * safeDt
        // Clamp exponent to avoid overflow in exp()
        val clampedExp = if (exponent < -60f) {  // exp(-60) ≈ 8.7e-27, basically zero
            0.0
        } else {
            kotlin.math.exp(exponent.toDouble())
        }

        var alpha = (1.0 - clampedExp).toFloat()
        if (!alpha.isFinite()) alpha = 1f
        alpha = alpha.coerceIn(0f, 1f)

        pos.x += (target.x - pos.x) * alpha
        pos.y += (target.y - pos.y) * alpha
        pos.z += (target.z - pos.z) * alpha
    }
}

// Small helper
fun Vec3.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite()
