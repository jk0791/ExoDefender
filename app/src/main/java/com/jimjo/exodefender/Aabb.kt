package com.jimjo.exodefender

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class Aabb(
    val min: Vec3 = Vec3(),
    val max: Vec3 = Vec3()
) {

    fun getCorners(): Array<Vec3> {
        return arrayOf(
            Vec3(min.x, min.y, min.z),
            Vec3(max.x, min.y, min.z),
            Vec3(min.x, max.y, min.z),
            Vec3(max.x, max.y, min.z),
            Vec3(min.x, min.y, max.z),
            Vec3(max.x, min.y, max.z),
            Vec3(min.x, max.y, max.z),
            Vec3(max.x, max.y, max.z),

            )
    }

    fun contains(point: Vec3): Boolean {
        return point.x >= min.x &&
                point.x <= max.x &&
                point.y >= min.y &&
                point.y <= max.y &&
                point.z >= min.z &&
                point.z <= max.z
    }

    fun intersects(aabb: Aabb): Boolean {
        val corners = aabb.getCorners()
        for (corner in corners) {
            if (contains(corner)) {
                return true
            }
        }
        return false
    }

    fun center(): Vec3 {
        return Vec3(
            (max.x + min.x) / 2f,
            (max.y + min.y) / 2f,
            (max.z + min.z) / 2f,
        )
    }
    fun copy(sourceAabb: Aabb) {
        min.set(sourceAabb.min)
        max.set(sourceAabb.max)
    }


    fun repositionCenter(newCenter: Vec3) {
        min.set(
            newCenter.x - width() / 2f,
            newCenter.y - depth() / 2f,
            newCenter.z - height() / 2f,
        )
        max.set(
            newCenter.x + width() / 2f,
            newCenter.y + depth() / 2f,
            newCenter.z + height() / 2f,
        )
    }

    fun width(): Float {
        return max.x - min.x
    }
    fun depth(): Float {
        return max.y - min.y
    }
    fun height(): Float {
        return max.z - min.z
    }
}
