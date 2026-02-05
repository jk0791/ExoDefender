package com.jimjo.exodefender

import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.sin
import kotlin.math.sqrt

class WorldManager(val context: Context) {

    val mapsDirPath = context.getFilesDir().getAbsolutePath().toString() + File.separator + "maps"
    // this list contains the current available maps in the game
    val mapFiles = listOf(
        Pair(R.raw.map_000, 0),
        Pair(R.raw.map_001, 1),
        Pair(R.raw.map_002, 2),
        Pair(R.raw.map_999, 999),
    )

    val worlds = mutableListOf<World>()
    val worldLookupById = hashMapOf<Int, World>()

    fun resetWorlds() {
        for (world in worlds) {
            world.reset()
        }
    }

    fun readMapFiles() {

        worlds.clear()
        worldLookupById.clear()

        for (mapfile in mapFiles) {
            val inputStream = context.resources.openRawResource(mapfile.first)
            // read map file
            try {
                val mapId = mapfile.second
                val world = World(mapId)

                val inputStreamReader = InputStreamReader(inputStream)
                val bufferedReader = BufferedReader(inputStreamReader)

                // read first line
                var receiveString = bufferedReader.readLine()

                var y = 0
                while (receiveString != null) {

                    if (receiveString != "") {
                        val values = receiveString.split(",")

                        for ((x, elevation) in values.withIndex()) {
                            if (elevation != "") {
                                world.heightMap[y][x] = elevation.toFloat()
                            }
                        }
                    }

                    receiveString = bufferedReader.readLine()
                    y++
                }
                bufferedReader.close()

                worlds.add(world)
                worldLookupById[world.mapId] = world

            }
            catch (e: Exception) {
                println("ERROR loading map ID ${mapfile.second}, " + e.message)
            }
        }
    }

    fun getPointsInBounds(bounds: RectF): MutableList<Point> {

        val leftIndex = Math.min(Math.max(getIndexBefore(bounds.left), 0), MAP_GRID_SIZE - 1)
        val topIndex = Math.min(Math.max(getIndexBefore(bounds.top), 0), MAP_GRID_SIZE - 1)
        val rightIndex = Math.min(Math.max(getIndexAfter(bounds.right), 0), MAP_GRID_SIZE - 1)
        val bottomIndex = Math.min(Math.max(getIndexAfter(bounds.bottom), 0), MAP_GRID_SIZE - 1)

        val points = mutableListOf<Point>()
        for (indexY in topIndex .. bottomIndex) {
            for (indexX in leftIndex .. rightIndex) {
                points.add(Point(indexX, indexY))
            }
        }
        return points
    }
}



class EllipticalMound(xpos: Float, ypos: Float, width: Float, depth: Float, height: Float):
    MapFeature(xpos, ypos, height)
{
    val xRadius = width / 2
    val yRadius = depth / 2

    init {
        bounds.set(xpos - xRadius, ypos - yRadius, xpos + xRadius, ypos + yRadius)
    }

    override fun getElevation(x: Float, y: Float): Float {
        return getElevationFromCenterOffsetVaried(x - xpos, y - ypos)
    }

    fun getElevationFromCenterOffsetSmooth(deltax: Float, deltay: Float): Float {

        val r = getRadiusThroughPoint(deltax, deltay)
        val x = getCenterOffset(deltax, deltay)

        return getCosShape(x / r) * height
    }

    fun getElevationFromCenterOffsetVaried(deltax: Float, deltay: Float): Float {

        val r = getRadiusThroughPoint(deltax, deltay)
        val x = getCenterOffset(deltax, deltay)

        val offsetRatio = x / r

        val elevRaw = getCosShape(offsetRatio) * height

        if (elevRaw == 0f) return 0f

        val amplitude = 2f * invertedQuad(offsetRatio)

        val frequency = 0.02f // 3f + Random.nextFloat() / 10f

        return elevRaw + getSinsoidalVariation(x, frequency, amplitude)
    }

    fun getCenterOffset(deltax: Float, deltay: Float): Float {
        return Math.sqrt((deltax * deltax + deltay * deltay).toDouble()).toFloat()
    }

    fun getRadiusThroughPoint(deltax: Float, deltay: Float): Float {

        val angle = getPlanAngle(deltax.toDouble(), deltay.toDouble())
        val r = (xRadius * yRadius) / sqrt( Math.pow(xRadius * Math.cos(angle), 2.0) + Math.pow(yRadius * Math.sin(angle), 2.0))

        return r.toFloat()
    }
}




class EllipticalFeatureOnAxis(xpos: Float, ypos: Float, height: Float, val xRadius: Float, val yRadius: Float):
    MapFeature(xpos = xpos, ypos = ypos, height = height) {

    init {
        bounds.set(xpos - xRadius, ypos - yRadius, xpos + xRadius, ypos + yRadius)
    }

    override fun getElevation(x: Float, y: Float): Float {
        val deltax = x - xpos
        val deltay = y - ypos
        val t = (sqrt(deltax * deltax + deltay * deltay) / getRadiusThroughPoint(deltax, deltay)).toDouble()

        if (t >= 0 && t <= 1) {
            val e = (Math.pow(1 - t, 3.0) * height + 3 * Math.pow(1 - t, 2.0) * t * height).toFloat()
//            println("deltax=$deltax deltay=$deltay radius=$radius ($x,$y) $t $e")
            return e
        }
        else {
            return 0f
        }
    }

    fun getRadiusThroughPoint(deltax: Float, deltay: Float): Float {

        val angle = getPlanAngle(deltax.toDouble(), deltay.toDouble())
        val r = (xRadius * yRadius) / sqrt( Math.pow(xRadius * Math.cos(angle), 2.0) + Math.pow(yRadius * Math.sin(angle), 2.0))

        return r.toFloat()
    }

}

abstract class MapFeature(val xpos: Float, val ypos: Float, val height: Float) {
    val bounds = RectF()

    abstract fun getElevation(x: Float, y: Float): Float

    fun invertedQuad(x: Float): Float {
        val t = x.coerceIn(0f, 1f)      // optional clamp to [0,1]
        return 4f * t * (1f - t)
    }

    fun getSinsoidalVariation(x: Float, frequency: Float, amplitude: Float): Float {
        return sin(x * TAU.toFloat() * frequency) * amplitude
    }
}
