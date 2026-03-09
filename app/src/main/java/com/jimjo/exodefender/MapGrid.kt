package com.jimjo.exodefender

import android.opengl.GLES20
import java.nio.FloatBuffer
import java.nio.IntBuffer

class MapGrid {

    var pointsCount = 0

    private var vertexBuffer: FloatBuffer? = null
    private var fillDrawListBuffer: IntBuffer? = null
    private var lineDrawListBuffer: IntBuffer? = null

    private var fillIndexCount = 0
    private var lineIndexCount = 0

    private val vertexStride: Int = COORDS_PER_VERTEX * 4

    private val fillColor = floatArrayOf(0f, 0f, 0f, 1.0f)
    private val lineColor = floatArrayOf(0.251f, 0.827f, 0.827f, 1.0f)

    private var mProgram: Int = -1
    private var vPMatrixHandle: Int = -1
    private var positionHandle: Int = -1
    private var mColorHandle: Int = -1

    fun load(
        world: World,
        worldManager: WorldManager,
        glProgram: Int,
        vPMatrixHandle: Int,
        positionHandle: Int,
        mColorHandle: Int
    ) {
        val cachedTerrainMesh = world.cachedTerrainMesh
            ?: error("World ${world.mapId} cachedTerrainMesh not initialized")

        val fillBuffer = worldManager.sharedFillDrawListBuffer
            ?: error("sharedFillDrawListBuffer not initialized")

        val lineBuffer = worldManager.sharedLineDrawListBuffer
            ?: error("sharedLineDrawListBuffer not initialized")

        println("MapGrid.load() using cached terrain for mapId=${world.mapId}")

        this.mProgram = glProgram
        this.vPMatrixHandle = vPMatrixHandle
        this.positionHandle = positionHandle
        this.mColorHandle = mColorHandle

        this.pointsCount = cachedTerrainMesh.pointsCount
        this.vertexBuffer = cachedTerrainMesh.vertexBuffer
        this.fillDrawListBuffer = fillBuffer
        this.lineDrawListBuffer = lineBuffer
        this.fillIndexCount = worldManager.sharedFillIndexCount
        this.lineIndexCount = worldManager.sharedLineIndexCount
    }

    fun unload() {
        vertexBuffer = null
        fillDrawListBuffer = null
        lineDrawListBuffer = null
        fillIndexCount = 0
        lineIndexCount = 0
        pointsCount = 0
    }

    fun draw(mvpMatrix: FloatArray) {
        val vertexBufferLocal = vertexBuffer ?: return
        val fillDrawListBufferLocal = fillDrawListBuffer ?: return
        val lineDrawListBufferLocal = lineDrawListBuffer ?: return

        vertexBufferLocal.position(0)
        fillDrawListBufferLocal.position(0)
        lineDrawListBufferLocal.position(0)

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