package com.jimjo.exodefender

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer



class WorldManager(val context: Context) {

    val mainActivity = context as MainActivity
    val mapFiles = listOf(
        Pair(R.raw.map_000, 0),
        Pair(R.raw.map_001, 1),
        Pair(R.raw.map_002, 2),
        Pair(R.raw.map_999, 999),
    )

    val worlds = mutableListOf<World>()
    val worldLookupById = hashMapOf<Int, World>()

    var sharedFillDrawListBuffer: IntBuffer? = null
    var sharedLineDrawListBuffer: IntBuffer? = null
    var sharedFillIndexCount = 0
    var sharedLineIndexCount = 0

    fun resetWorlds() {
        for (world in worlds) {
            world.reset()
        }
    }

    fun readMapFiles() {

        worlds.clear()
        worldLookupById.clear()

        mainActivity.adminLogView.printout("Building terrain mesh for worlds...")

        for (mapfile in mapFiles) {
            val inputStream = context.resources.openRawResource(mapfile.first)
            try {
                val mapId = mapfile.second
                val world = World(mapId)

                val inputStreamReader = InputStreamReader(inputStream)
                val bufferedReader = BufferedReader(inputStreamReader)

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


                buildCachedTerrainMesh(world)

                worlds.add(world)
                worldLookupById[world.mapId] = world

            } catch (e: Exception) {
                println("ERROR loading map ID ${mapfile.second}, ${e.message}")
            }
        }

        ensureSharedMapGridIndices()
    }

    private fun buildCachedTerrainMesh(world: World) {
//        println("Building terrain mesh for world ${world.mapId}")
        val pointsCount = MAP_GRID_SIZE * MAP_GRID_SIZE
        val coords = FloatArray(pointsCount * COORDS_PER_VERTEX)

        var ordCounter = -1
        for (y in 0 until MAP_GRID_SIZE) {
            for (x in 0 until MAP_GRID_SIZE) {
                ordCounter++
                coords[ordCounter] = x * MAP_GRID_SPACING

                ordCounter++
                coords[ordCounter] = world.heightMap[y][x]

                ordCounter++
                coords[ordCounter] = y * MAP_GRID_SPACING
            }
        }

        val vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(coords)
                position(0)
            }

        world.cachedTerrainMesh = CachedTerrainMesh(
            vertexBuffer = vertexBuffer,
            pointsCount = pointsCount
        )
    }

    private fun ensureSharedMapGridIndices() {
        if (sharedFillDrawListBuffer != null && sharedLineDrawListBuffer != null) return

        val fillDrawOrder = IntArray((MAP_GRID_SIZE - 1) * (MAP_GRID_SIZE - 1) * 6)
        val lineDrawOrder = IntArray((MAP_GRID_SIZE - 1) * (MAP_GRID_SIZE - 1) * 4)

        var fillCounter = 0
        var lineCounter = 0

        for (y in 0 until MAP_GRID_SIZE) {
            for (x in 0 until MAP_GRID_SIZE) {
                val coordIndex = x + y * MAP_GRID_SIZE

                if (x != MAP_GRID_SIZE - 1) {
                    if (y != MAP_GRID_SIZE - 1) {
                        fillDrawOrder[fillCounter++] = coordIndex
                        fillDrawOrder[fillCounter++] = coordIndex + MAP_GRID_SIZE
                        fillDrawOrder[fillCounter++] = coordIndex + 1
                    }

                    if (y != 0) {
                        fillDrawOrder[fillCounter++] = coordIndex
                        fillDrawOrder[fillCounter++] = coordIndex + 1
                        fillDrawOrder[fillCounter++] = coordIndex - MAP_GRID_SIZE + 1
                    }
                }

                if (x != MAP_GRID_SIZE - 1 && y != MAP_GRID_SIZE - 1) {
                    lineDrawOrder[lineCounter++] = coordIndex
                    lineDrawOrder[lineCounter++] = coordIndex + 1
                    lineDrawOrder[lineCounter++] = coordIndex
                    lineDrawOrder[lineCounter++] = coordIndex + MAP_GRID_SIZE
                }
            }
        }

        sharedFillIndexCount = fillCounter
        sharedLineIndexCount = lineCounter

        sharedFillDrawListBuffer = ByteBuffer.allocateDirect(sharedFillIndexCount * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .apply {
                put(fillDrawOrder, 0, sharedFillIndexCount)
                position(0)
            }

        sharedLineDrawListBuffer = ByteBuffer.allocateDirect(sharedLineIndexCount * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .apply {
                put(lineDrawOrder, 0, sharedLineIndexCount)
                position(0)
            }
    }
}




