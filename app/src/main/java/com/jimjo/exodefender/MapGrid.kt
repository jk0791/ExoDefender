package com.jimjo.exodefender

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

class MapGrid {

    var pointsCount = 0

    val fillVerticesPerSquare = 6
    val lineVerticesPerSquare = 4 // draw two lines per square, 2 vertices per line

    private var fillIndexCount = 0
    private var lineIndexCount = 0

    private var coords: FloatArray? = null
    private var fillDrawOrder: IntArray? = null
    private var lineDrawOrder: IntArray? = null

    private var vertexBuffer: FloatBuffer? = null
    private var fillDrawListBuffer: IntBuffer? = null
    private var lineDrawListBuffer: IntBuffer? = null
    private val vertexStride: Int = COORDS_PER_VERTEX * 4

    private val fillColor = floatArrayOf(0f, 0f, 0f, 1.0f)
    private val lineColor = floatArrayOf(0.251f, 0.827f, 0.827f, 1.0f)

    private var mProgram: Int = -1
    private var vPMatrixHandle: Int = -1
    private var positionHandle: Int = -1
    private var mColorHandle: Int = -1

    fun load(world: World, glProgram: Int, vPMatrixHandle: Int, positionHandle: Int, mColorHandle: Int) {

        this.mProgram = glProgram
        this.vPMatrixHandle = vPMatrixHandle
        this.positionHandle = positionHandle
        this.mColorHandle = mColorHandle

        loadCoords(world)
        loadDrawLists()
        loadByteArrays()
    }

    fun loadCoords(world: World) {

        pointsCount = MAP_GRID_SIZE * MAP_GRID_SIZE
        val coordsLocal = FloatArray(pointsCount * COORDS_PER_VERTEX)
        fillDrawOrder = IntArray((MAP_GRID_SIZE - 1) * (MAP_GRID_SIZE - 1)  * fillVerticesPerSquare)
        lineDrawOrder = IntArray((MAP_GRID_SIZE - 1) * (MAP_GRID_SIZE - 1) * lineVerticesPerSquare)


        var ordCounter = -1
        for (y in 0 ..< MAP_GRID_SIZE) {
            for (x in 0 ..< MAP_GRID_SIZE) {

                ordCounter++
                coordsLocal[ordCounter] = x * MAP_GRID_SPACING

                // set y to elevation
                ordCounter++
                coordsLocal[ordCounter] = world.heightMap[y][x]

                ordCounter++
                coordsLocal[ordCounter] = y * MAP_GRID_SPACING
            }
        }

        coords = coordsLocal
    }

    fun loadDrawLists() {

        val fillDrawOrderLocal = fillDrawOrder ?: error("fillDrawOrder not loaded")
        val lineDrawOrderLocal = lineDrawOrder ?: error("lineDrawOrder not loaded")


        var fillCounter = 0
        var lineCounter = 0

        for (y in 0 ..< MAP_GRID_SIZE) {

            for (x in 0..< MAP_GRID_SIZE) {

                val coordIndex = x + y * MAP_GRID_SIZE

                if (x != MAP_GRID_SIZE - 1) {

                    if (y != MAP_GRID_SIZE - 1) {
                        // current row triangle
                        fillDrawOrderLocal[fillCounter++] = coordIndex
                        fillDrawOrderLocal[fillCounter++] = coordIndex + MAP_GRID_SIZE
                        fillDrawOrderLocal[fillCounter++] = coordIndex + 1
                    }

                    if (y != 0) {
                        // previous row triangle
                        fillDrawOrderLocal[fillCounter++] = coordIndex
                        fillDrawOrderLocal[fillCounter++] = coordIndex + 1
                        fillDrawOrderLocal[fillCounter++] = coordIndex - MAP_GRID_SIZE + 1
                    }
                }
                // draw two lines bordering current row square
                if (x != MAP_GRID_SIZE - 1 && y != MAP_GRID_SIZE - 1) {
                    lineDrawOrderLocal[lineCounter++] = coordIndex
                    lineDrawOrderLocal[lineCounter++] = coordIndex + 1
                    lineDrawOrderLocal[lineCounter++] = coordIndex
                    lineDrawOrderLocal[lineCounter++] = coordIndex + MAP_GRID_SIZE
                }
            }
        }

        fillIndexCount = fillCounter
        lineIndexCount = lineCounter

//        println("fillDrawOrderLocal.size=${fillDrawOrderLocal.size}")
//        println("fillCounter=$fillCounter")
//        println("lineDrawOrderLocal.size=${lineDrawOrderLocal.size}")
//        println("lineCounter=$lineCounter")
    }

    fun unload() {
        coords = null
        fillDrawOrder = null
        lineDrawOrder = null

        vertexBuffer = null
        fillDrawListBuffer = null
        lineDrawListBuffer = null

        fillIndexCount = 0
        lineIndexCount = 0
        pointsCount = 0
    }

    fun loadByteArrays() {
        val coordsLocal = coords ?: error("coords not loaded")
        val fillLocal = fillDrawOrder ?: error("fillDrawOrder not loaded")
        val lineLocal = lineDrawOrder ?: error("lineDrawOrder not loaded")

        vertexBuffer = ByteBuffer.allocateDirect(coordsLocal.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coordsLocal)
                position(0)
            }
        }

        fillDrawListBuffer = ByteBuffer.allocateDirect(fillLocal.size * 4).run {
            order(ByteOrder.nativeOrder())
            asIntBuffer().apply {
                put(fillLocal)
                position(0)
            }
        }

        lineDrawListBuffer = ByteBuffer.allocateDirect(lineLocal.size * 4).run {
            order(ByteOrder.nativeOrder())
            asIntBuffer().apply {
                put(lineLocal)
                position(0)
            }
        }

        // release heap copies
        coords = null
        fillDrawOrder = null
        lineDrawOrder = null
    }


    fun draw(mvpMatrix: FloatArray) {
        val vertexBufferLocal = vertexBuffer ?: return
        val fillDrawListBufferLocal = fillDrawListBuffer ?: return
        val lineDrawListBufferLocal = lineDrawListBuffer ?: return

        GLES20.glUseProgram(mProgram)

        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glVertexAttribPointer(
            positionHandle,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            vertexStride,
            vertexBufferLocal
        )

        GLES20.glUniformMatrix4fv(vPMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glUniform4fv(mColorHandle, 1, fillColor, 0)
        GLES20.glPolygonOffset(0.5f, 1f)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            fillIndexCount,
            GLES20.GL_UNSIGNED_INT,
            fillDrawListBufferLocal
        )

        GLES20.glUniform4fv(mColorHandle, 1, lineColor, 0)
        GLES20.glDrawElements(
            GLES20.GL_LINES,
            lineIndexCount,
            GLES20.GL_UNSIGNED_INT,
            lineDrawListBufferLocal
        )

        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}