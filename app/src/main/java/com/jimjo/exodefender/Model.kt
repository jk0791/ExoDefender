package com.jimjo.exodefender

import android.opengl.Matrix

data class ModelTemplate(
    val meshData: MeshData,
    val localAabb: Aabb
)

class Model(
    val mesh: GpuMesh,
    val localAabb: Aabb
)

class ModelInstance(
    val model: Model,
    val isStatic: Boolean = false
) {
    val position = Vec3()

    var scaleX: Float = 1f
        private set
    var scaleY: Float = 1f
        private set
    var scaleZ: Float = 1f
        private set

    var yawRad: Double = 0.0
        private set
    var pitchRad: Double = 0.0
        private set
    var rollRad: Double = 0.0
        private set

    // cached trig (Float for math ops)
    private var sinY = 0f
    private var cosY = 1f
    private var sinP = 0f
    private var cosP = 1f
    private var sinR = 0f
    private var cosR = 1f

    val worldAabb = Aabb(Vec3(), Vec3())
    val renderMatrix = FloatArray(16)
    private var dirty = true

    val tmpRot = Vec3()

    fun setScale(x: Float, y: Float, z: Float) {
        // assume positive scales
        if (scaleX != x || scaleY != y || scaleZ != z) {
            scaleX = x
            scaleY = y
            scaleZ = z
            dirty = true
        }
    }

    fun setPosition(x: Float, y: Float, z: Float) {
        position.set(x, y, z)
        dirty = true
    }

    /**
     * Sets yaw, pitch, and roll (in radians).
     * Yaw = rotation around world Z axis
     * Pitch = rotation around local X axis
     * Roll = rotation around local Y axis
     */
    fun setDirection(yawRad: Double, pitchRad: Double, rollRad: Double = 0.0) {
        if (this.yawRad != yawRad || this.pitchRad != pitchRad || this.rollRad != rollRad) {
            this.yawRad = yawRad
            this.pitchRad = pitchRad
            this.rollRad = rollRad

            // cache trig once
            sinY = kotlin.math.sin(yawRad).toFloat()
            cosY = kotlin.math.cos(yawRad).toFloat()
            sinP = kotlin.math.sin(pitchRad).toFloat()
            cosP = kotlin.math.cos(pitchRad).toFloat()
            sinR = kotlin.math.sin(rollRad).toFloat()
            cosR = kotlin.math.cos(rollRad).toFloat()

            dirty = true
        }
    }

    fun localToWorld(local: Vec3, out: Vec3, applyRotation: Boolean = true): Vec3 {
        if (!applyRotation || (yawRad == 0.0 && pitchRad == 0.0 && rollRad == 0.0)) {
            out.x = position.x + local.x
            out.y = position.y + local.y
            out.z = position.z + local.z
            return out
        }
        rotateLocal(local, out)       // intrinsic rotation
        out.x += position.x
        out.y += position.y
        out.z += position.z
        return out
    }

    // Rotate a local vector (lx,ly,lz) by intrinsic yaw(Z)->pitch(X_local)->roll(Y_local)
    fun rotateLocal(lx: Float, ly: Float, lz: Float, out: Vec3): Vec3 {
        if (yawRad == 0.0 && pitchRad == 0.0 && rollRad == 0.0) {
            out.x = lx; out.y = ly; out.z = lz
            return out
        }

        // cached trig set in setDirection(...)
        val cy = cosY; val sy = sinY
        val cp = cosP; val sp = sinP
        val cr = cosR;
        val sr = sinR

        // Apply R * v (column-vector convention)
        val rx = (cy*cr - sy*sp*sr) * lx + (-sy*cp) * ly + (cy*sr + sy*sp*cr) * lz
        val ry = (sy*cr + cy*sp*sr) * lx + ( cy*cp) * ly + (sy*sr - cy*sp*cr) * lz
        val rz = (        -cp*sr   ) * lx + (   sp  ) * ly + (       cp*cr   ) * lz

        out.x = rx; out.y = ry; out.z = rz
        return out
    }

    // Convenience overload (no duplication)
    fun rotateLocal(local: Vec3, out: Vec3): Vec3 =
        rotateLocal(local.x, local.y, local.z, out)

    fun setSize(widthX: Float, depthY: Float, heightZ: Float) {
        val la = model.localAabb
        val baseX = (la.max.x - la.min.x)
        val baseY = (la.max.y - la.min.y)
        val baseZ = (la.max.z - la.min.z)

        // Avoid divide-by-zero if a mesh is flat in an axis
        val sx = if (baseX > 1e-6f) widthX / baseX else 1f
        val sy = if (baseY > 1e-6f) depthY / baseY else 1f
        val sz = if (baseZ > 1e-6f) heightZ / baseZ else 1f

        setScale(sx, sy, sz)
    }

    fun setHalfExtents(half: Vec3) {
        setSize(half.x * 2f, half.y * 2f, half.z * 2f)
    }

    fun update() {
        if (!dirty && isStatic) return
        if (!dirty) return

        updateRenderMatrix()
        updateWorldAabb()
        dirty = !isStatic
    }

    fun updateRenderOnly() {
        if (!dirty && isStatic) return
        if (!dirty) return
        updateRenderMatrix()
        dirty = !isStatic
    }

    private fun updateRenderMatrix() {
        android.opengl.Matrix.setIdentityM(renderMatrix, 0)

        // world (x, y, z) → GL (x, z, y)
        android.opengl.Matrix.translateM(
            renderMatrix, 0,
            position.x,
            position.z,
            position.y
        )

        // Convert to degrees for OpenGL
        val yawDeg = Math.toDegrees(yawRad).toFloat()
        val pitchDeg = Math.toDegrees(pitchRad).toFloat()
        val rollDeg = Math.toDegrees(rollRad).toFloat()

        // Apply yaw (around GL up = Y axis)
        if (yawDeg != 0f)
            Matrix.rotateM(renderMatrix, 0, yawDeg, 0f, 1f, 0f)

        // Apply pitch (around local X)
        if (pitchDeg != 0f)
            Matrix.rotateM(renderMatrix, 0, pitchDeg, 1f, 0f, 0f)

        // Apply roll (around local Z)
        if (rollDeg != 0f)
            Matrix.rotateM(renderMatrix, 0, rollDeg, 0f, 0f, 1f)

        // Apply scale (world -> GL axis mapping: x, z, y)
        if (scaleX != 1f || scaleY != 1f || scaleZ != 1f) {
            Matrix.scaleM(renderMatrix, 0, scaleX, scaleZ, scaleY)
        }
    }

    private fun updateWorldAabb() {
        val la = model.localAabb
        val min = worldAabb.min
        val max = worldAabb.max

        if (yawRad == 0.0 && pitchRad == 0.0 && rollRad == 0.0) {
            // scale local AABB, then translate
            val minx = la.min.x * scaleX + position.x
            val miny = la.min.y * scaleY + position.y
            val minz = la.min.z * scaleZ + position.z
            val maxx = la.max.x * scaleX + position.x
            val maxy = la.max.y * scaleY + position.y
            val maxz = la.max.z * scaleZ + position.z
            min.set(minx, miny, minz)
            max.set(maxx, maxy, maxz)
            return
        }

        min.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        max.set(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

        for (ix in 0..1) {
            val lx0 = if (ix == 0) la.min.x else la.max.x
            val lx = lx0 * scaleX
            for (iy in 0..1) {
                val ly0 = if (iy == 0) la.min.y else la.max.y
                val ly = ly0 * scaleY
                for (iz in 0..1) {
                    val lz0 = if (iz == 0) la.min.z else la.max.z
                    val lz = lz0 * scaleZ

                    rotateLocal(lx, ly, lz, tmpRot)
                    val wx = tmpRot.x + position.x
                    val wy = tmpRot.y + position.y
                    val wz = tmpRot.z + position.z

                    if (wx < min.x) min.x = wx
                    if (wy < min.y) min.y = wy
                    if (wz < min.z) min.z = wz
                    if (wx > max.x) max.x = wx
                    if (wy > max.y) max.y = wy
                    if (wz > max.z) max.z = wz
                }
            }
        }
    }
}



