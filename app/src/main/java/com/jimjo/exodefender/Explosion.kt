package com.jimjo.exodefender

import android.graphics.PointF
import kotlin.random.Random

class ExplosionPool(
    private val world: World,
    private val poolSize: Int,
    private val size: Float,
    private val lineColor: FloatArray
) {
    private val pool = ArrayList<Explosion>(poolSize)
    private var cursor = 0

    fun load() {
        repeat(poolSize) {
            val e = Explosion(world, size, lineColor)
            e.load()
            pool.add(e)
        }
    }

    fun update(dt: Float) {
        for (e in pool) e.update(dt, world)
    }

    fun draw(vp: FloatArray) {
        for (e in pool) e.draw(vp)
    }

    fun activateLarge(pos: Vec3) {
        // round-robin; overwrites the oldest if everything is active (acceptable for VFX)
        val e = pool[cursor]
        cursor = (cursor + 1) % pool.size
        e.activateLarge(pos)
    }

    fun activateSmall(pos: Vec3) {
        val e = pool[cursor]
        cursor = (cursor + 1) % pool.size
        e.activateSmall(pos)
    }

    fun cancelAll() {
        for (e in pool) e.active = false
    }
}

class Explosion(val world: World, val size: Float, val lineColor: FloatArray): ShrapnelParent {
    val largeShrapnelList = mutableListOf<Shrapnel>()
    val smallShrapnelList = mutableListOf<Shrapnel>()
    var activeShrapnelList = largeShrapnelList
    var active = true

    val largeNumberOfShrapnel = 20
    val smallNumberOfShrapnel = 8

    fun activateSmall(position: Vec3) {
        activate(position.x, position.y, position.z, false)
    }

    fun activateLarge(position: Vec3) {
        activate(position.x, position.y, position.z, true)
    }

    fun activate(x: Float, y: Float, z: Float, large: Boolean) {
        active = true

        val durationFactor: Float
        val maxVelocity: Float
        if (large) {
            activeShrapnelList = largeShrapnelList
            durationFactor = 1f
            maxVelocity = 140f
        }
        else {
            activeShrapnelList = smallShrapnelList
            durationFactor = 0.3f
            maxVelocity = 70f
        }
        for (shrapnel in activeShrapnelList) {
            val angleE: Double

            val elevation = world.getElevationAverage(PointF(x, y))

            if (elevation != null && (z - elevation) < 4f) {
                angleE = Random.nextDouble() * -3.14
            }
            else {
                angleE = Random.nextDouble() * 6.2
            }


            shrapnel.activate(x, y, z,
                Random.nextFloat() * maxVelocity + 20f,
                Random.nextFloat() * 360,
                Random.nextFloat() * durationFactor,
                Random.nextDouble() * 6.2,
                angleE,
            )
        }
    }

    override fun notifyDeactivation() {
        active = false
        for (shrapnel in activeShrapnelList) {
            if (shrapnel.active) {
                active = true
                break
            }
        }
    }

    fun update(interval: Float, world: World) {
        if (active) {
            for (shrapnel in activeShrapnelList) {
                if (shrapnel.active) {
                    shrapnel.update(interval, world)
                }
            }
        }
    }

    fun draw(mvpMatrix: FloatArray) {
        if (active) {
            for (shrapnel in activeShrapnelList) {
                if (shrapnel.active) {
                    shrapnel.draw(mvpMatrix)
                }
            }
        }
    }


    fun load() {
        for (i in 0..< largeNumberOfShrapnel) {
            val shrapnel = Shrapnel(this, Random.nextFloat() * size, lineColor, 2.5f * size)
            shrapnel.load()
            largeShrapnelList.add(shrapnel)
        }
        for (i in 0..< smallNumberOfShrapnel) {
            val shrapnel = Shrapnel(this, Random.nextFloat() * size / 2f, lineColor, 2.5f * size)
            shrapnel.load()
            smallShrapnelList.add(shrapnel)
        }
    }
}