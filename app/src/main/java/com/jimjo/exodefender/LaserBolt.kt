package com.jimjo.exodefender

import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

const val MAX_LASERBOLT_TRAVEL = 400f

abstract class LaserBoltPool {
    abstract val pool: MutableList<LaserBolt>

    fun update(interval: Float, flightTimeMs: Int, world: World, ship: ShipActor) {
        for (laserBolt in pool) {
            if (laserBolt.active) {
                laserBolt.update(interval, flightTimeMs, world, ship)
            }
        }
    }

    fun draw(vPMatrix: FloatArray) {
        for (laserBolt in pool) {
            if (laserBolt.active) {
                laserBolt.draw(vPMatrix)
            }
        }
    }

}
class DoubleLaserBoltPool(val poolSize: Int): LaserBoltPool() {

    // poolSize = number of *pairs*
    override val pool = mutableListOf<LaserBolt>()
    val targetCandidates = ArrayList<Actor>(128)
    var nextAvailableIndex = 0
    val maxIntervalMs = 150
    var sinceLastFired = 0

    fun fillPool(glProgram: Int, vPMatrixHandle: Int, positionHandle: Int, mColorHandle: Int) {

        for (i in 0..< poolSize) {
            val laserBoltL = LaserBolt(LaserBolt.SourceType.SHIP, 10f, 400f, targetCandidates)
            val laserBoltR = LaserBolt(LaserBolt.SourceType.SHIP, 10f, 400f, targetCandidates)
            laserBoltL.load(glProgram, vPMatrixHandle, positionHandle, mColorHandle)
            laserBoltR.load(glProgram, vPMatrixHandle, positionHandle, mColorHandle)
            pool.add(laserBoltL)
            pool.add(laserBoltR)
        }
    }


    fun activateNext(
        weaponPositionL: Vec3,
        weaponPositionR: Vec3,
        weaponYawRad: Double,
        weaponPitchRad: Double
    ) {
        // Left bolt
        pool[nextAvailableIndex].activate(
            weaponPositionL,
            weaponYawRad,
            weaponPitchRad
        )

        // Next index (wrap)
        var i2 = nextAvailableIndex + 1
        if (i2 >= poolSize) i2 = 0

        // Right bolt
        pool[i2].activate(
            weaponPositionR,
            weaponYawRad,
            weaponPitchRad
        )

        // Advance for next call (two bolts at a time)
        nextAvailableIndex = i2 + 1
        if (nextAvailableIndex >= poolSize) nextAvailableIndex = 0
    }


}

class SingleLaserBoltPool(val sourceType: LaserBolt.SourceType, val poolSize: Int, val boltLength: Float): LaserBoltPool() {

    val boltVelocity = 200f
    override val pool = mutableListOf<LaserBolt>()
    var nextAvailableIndex = 0

    fun fillPool(glProgram: Int, vPMatrixHandle: Int, positionHandle: Int, mColorHandle: Int) {

        for (i in 0..< poolSize) {
            val laserBolt = LaserBolt(sourceType, boltLength, boltVelocity)
//            laserBolt.harmless = harmless
            laserBolt.load(glProgram, vPMatrixHandle, positionHandle, mColorHandle)
            pool.add(laserBolt)
        }
    }

    fun activateNext(weaponPosition: Vec3, angleP: Double, angleE: Double) {
        pool[nextAvailableIndex].activate(weaponPosition, angleP, angleE)
        nextAvailableIndex++

        // cycle the pool
        if (nextAvailableIndex >= poolSize) {
            nextAvailableIndex = 0
        }
    }
}

class LaserBolt(val sourceType: SourceType, val length: Float, val velocityF: Float, val targetCandidates: ArrayList<Actor>? = null): RectF() {

    enum class SourceType { SHIP, ENEMY, FRIENDLY}

//    var sourceId = -1
    var active = false
    var distanceTravelled = 0f
    val position = Vec3()
    val nextPosition = Vec3()
    val hitPoint = Vec3()
    val dir = Vec3()

    val position2d = PointF()
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

    private lateinit var coords: FloatArray
    private val vertexStride: Int = COORDS_PER_VERTEX * 4
    private lateinit var fillDrawOrder: ShortArray

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var fillDrawListBuffer: ShortBuffer

    private val fillColor = floatArrayOf(0.9f, 0.9f, 0.9f, 1.0f)

    private var mProgram: Int = -1
    private var vPMatrixHandle: Int = -1
    private var positionHandle: Int = -1
    private var mColorHandle: Int = -1

    private val modelMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val rotationMatrixP = FloatArray(16)
    private val rotationMatrixE = FloatArray(16)


    init {
        this.top = -length / 2f
        this.bottom = length / 2f
        this.left = -length / 50f // + sideOffset
        this.right = length / 50f // + sideOffset

    }

    fun activate(pos: Vec3, angleP: Double, angleE: Double) {
//        this.sourceId = sourceId
        distanceTravelled = 0f

        this.position.set(pos)

        this.angleP = angleP
        this.angleE = angleE

        Matrix.setRotateM(rotationMatrixP, 0, Math.toDegrees(angleP).toFloat(), 0f, 1f, 0f)
        Matrix.setRotateM(rotationMatrixE, 0, -Math.toDegrees(angleE).toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(rotationMatrix, 0, rotationMatrixP,0, rotationMatrixE, 0)

        active = true
    }

    fun update(interval: Float, flightTimeMs: Int, world: World, ship: ShipActor) {

        val intervalDisplacement = velocityF * interval
        distanceTravelled += intervalDisplacement
        val xyIntervalDisplacement = intervalDisplacement * cosAngleE.toFloat()
        nextPosition.x = position.x - xyIntervalDisplacement * sinAngleP.toFloat()
        nextPosition.y = position.y - xyIntervalDisplacement * cosAngleP.toFloat()
        nextPosition.z = position.z - intervalDisplacement * sinAngleE.toFloat()
        position2d.set(nextPosition.x, nextPosition.y)

        // deactivate if travelled far enough
        if (distanceTravelled > MAX_LASERBOLT_TRAVEL) {
            active = false
        }

        if (active) {
            if (sourceType == SourceType.ENEMY) {
                // use contains() for enemy-fired projectiles (20 times less CPU intensive than segment sweeping)
                if (ship.active && ship.instance.worldAabb.contains(nextPosition)) {
                    ship.onHit(flightTimeMs, false, nextPosition)
                    active = false
                }
                // check hitting friendly
                for (friendlyActor in world.friendlyActors) {
                    if (
                        friendlyActor.active
                        && friendlyActor !is FriendlyStructureActor  // let laser bolt pass through and hit blocks inside instead
                        && friendlyActor.instance.worldAabb.contains(nextPosition))
                    {
                        friendlyActor.onHit(flightTimeMs, false, nextPosition)
                        active = false
                        break
                    }
                }

            } else if (sourceType == SourceType.SHIP){

                // get the enemies close enough to bother doing segment sweeping
                if (targetCandidates != null) {
                    targetCandidates.clear()

                    // segment sweeping, CPU-intensive so use grid to narrow down
                    world.queryEnemyForSegmentInto(position, nextPosition, length, targetCandidates)
                    for (enemy in targetCandidates) {

                        // TODO pass hitPoint to enemy so that non-destructive can show small explosion right at the hit location
                        if (segmentIntersectsAABBMutable(position, nextPosition, enemy.instance.worldAabb, hitPoint)) {
                            enemy.onHit(flightTimeMs, true, hitPoint)
                            active = false
                            break
                        }
                    }
                }
            } else if (sourceType == SourceType.FRIENDLY){
                for (enemy in world.enemyActors) {
                    // use contains() for friendly-fired projectiles
                    if (enemy.active && enemy.instance.worldAabb.contains(nextPosition)) {
                        active = false
                        break
                    }
                }
            }
        }

        // check if bolt has hit the terrain
        if (active && distanceTravelled > 40f) {
            val elevation = world.getElevationBefore(position2d)
            if (elevation != null) {
                if (nextPosition.z < elevation - 2f) {
                    active = false
                }
            }
        }

        position.set(nextPosition)

    }

    private fun segmentIntersectsAABBMutable(start: Vec3, end: Vec3, box: Aabb, outHit: Vec3): Boolean {
        dir.set(end).subLocal(start) // dir = end - start

        var tMin = 0.0f
        var tMax = 1.0f

        for (axis in 0..2) {
            val s = when(axis) { 0 -> start.x; 1 -> start.y; else -> start.z }
            val d = when(axis) { 0 -> dir.x; 1 -> dir.y; else -> dir.z }
            val bMin = when(axis) { 0 -> box.min.x; 1 -> box.min.y; else -> box.min.z }
            val bMax = when(axis) { 0 -> box.max.x; 1 -> box.max.y; else -> box.max.z }

            if (d == 0f) {
                if (s < bMin || s > bMax) return false
            } else {
                val invD = 1f / d
                var t1 = (bMin - s) * invD
                var t2 = (bMax - s) * invD
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }

                tMin = maxOf(tMin, t1)
                tMax = minOf(tMax, t2)
                if (tMax < tMin) return false
            }
        }

        // Compute hit point into preallocated outHit vector
        outHit.set(dir).mulLocal(tMin).addLocal(start)
        return true
    }


    fun load(glProgram: Int, vPMatrixHandle: Int, positionHandle: Int, mColorHandle: Int) {

        this.mProgram = glProgram
        this.vPMatrixHandle = vPMatrixHandle
        this.positionHandle = positionHandle
        this.mColorHandle = mColorHandle

        coords = FloatArray(5 * COORDS_PER_VERTEX)
        fillDrawOrder = ShortArray(4 * 3)

        var ordCounter = 0
        coords[ordCounter++] = centerX()
        coords[ordCounter++] = 0f
        coords[ordCounter++] = top

        coords[ordCounter++] = right
        coords[ordCounter++] = 0f
        coords[ordCounter++] = bottom

        coords[ordCounter++] = left
        coords[ordCounter++] = 0f
        coords[ordCounter++] = bottom

        coords[ordCounter++] = centerX()
        coords[ordCounter++] = width() / 2
        coords[ordCounter++] = bottom

        coords[ordCounter++] = centerX()
        coords[ordCounter++] = -width() / 2
        coords[ordCounter++] = bottom

        var fillCounter = 0
        fillDrawOrder[fillCounter++] = 0
        fillDrawOrder[fillCounter++] = 2
        fillDrawOrder[fillCounter++] = 3

        fillDrawOrder[fillCounter++] = 0
        fillDrawOrder[fillCounter++] = 3
        fillDrawOrder[fillCounter++] = 1

        fillDrawOrder[fillCounter++] = 0
        fillDrawOrder[fillCounter++] = 1
        fillDrawOrder[fillCounter++] = 2

        fillDrawOrder[fillCounter++] = 1
        fillDrawOrder[fillCounter++] = 3
        fillDrawOrder[fillCounter++] = 2

        vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coords)
                position(0)
            }
        }
        fillDrawListBuffer = ByteBuffer.allocateDirect(fillDrawOrder.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(fillDrawOrder)
                position(0)
            }
        }
    }



    fun draw(mvpMatrix: FloatArray) {
        // Add program to OpenGL ES environment
        GLES20.glUseProgram(mProgram)


        // Enable a handle to the triangle vertices
        GLES20.glEnableVertexAttribArray(positionHandle)

        // Prepare the triangle coordinate data
        GLES20.glVertexAttribPointer(
            positionHandle,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            vertexStride,
            vertexBuffer
        )


        Matrix.translateM(modelMatrix, 0, mvpMatrix, 0, position.x, position.z, position.y)
        Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, rotationMatrix, 0)

        // Pass the projection and view transformation to the shader
        GLES20.glUniformMatrix4fv(vPMatrixHandle, 1, false, modelMatrix, 0)

        GLES20.glUniform4fv(mColorHandle, 1, fillColor, 0)
//        GLES20.glPolygonOffset(0.5f,1f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, fillDrawOrder.size, GLES20.GL_UNSIGNED_SHORT, fillDrawListBuffer)

        // get handle to fragment shader's vColor member
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor")

        // Disable vertex array
        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}