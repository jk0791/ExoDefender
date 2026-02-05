package com.jimjo.exodefender

import android.graphics.RectF
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class MapRect(var z: Float = 0f): RectF() {

    private val rectF = RectF()

    private lateinit var coords: FloatArray
    private val vertexStride: Int = COORDS_PER_VERTEX * 4

    private lateinit var lineDrawOrder: ShortArray
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var lineDrawListBuffer: ShortBuffer

    private val lineColor = floatArrayOf(0.9f, 0.2f, 0.2f, 1.0f)

    private var mProgram: Int = -1
    private var vPMatrixHandle: Int = -1
    private var positionHandle: Int = -1
    private var mColorHandle: Int = -1


    fun load() {
        coords = FloatArray(4 * COORDS_PER_VERTEX)
        lineDrawOrder = ShortArray(4 * 2)

        var ordCounter = 0
        coords[ordCounter++] = left
        coords[ordCounter++] = z
        coords[ordCounter++] = top

        coords[ordCounter++] = right
        coords[ordCounter++] = z
        coords[ordCounter++] = top

        coords[ordCounter++] = right
        coords[ordCounter++] = z
        coords[ordCounter++] = bottom

        coords[ordCounter++] = left
        coords[ordCounter++] = z
        coords[ordCounter] = bottom

        var lineCounter = 0
        lineDrawOrder[lineCounter++] = 0
        lineDrawOrder[lineCounter++] = 1

        lineDrawOrder[lineCounter++] = 1
        lineDrawOrder[lineCounter++] = 2

        lineDrawOrder[lineCounter++] = 2
        lineDrawOrder[lineCounter++] = 3

        lineDrawOrder[lineCounter++] = 3
        lineDrawOrder[lineCounter] = 0

        vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coords)
                position(0)
            }
        }

        lineDrawListBuffer = ByteBuffer.allocateDirect(lineDrawOrder.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(lineDrawOrder)
                position(0)
            }
        }

        val vertexShaderCode =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 vPosition;" +
                    "void main() {" +
                    "  gl_Position = uMVPMatrix * vPosition;" +
                    "}"

        val fragmentShaderCode =
            "precision mediump float;" +
                    "uniform vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
                    "}"


        val vertexShader: Int = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader: Int = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        mProgram = GLES20.glCreateProgram()                 // create OpenGL ES Program
        GLES20.glAttachShader(mProgram, vertexShader)       // add the vertex shader to program
        GLES20.glAttachShader(mProgram, fragmentShader)     // add the fragment shader to program
        GLES20.glLinkProgram(mProgram)                      // creates OpenGL ES program executables

        positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
        vPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")

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



        // Pass the projection and view transformation to the shader
        GLES20.glUniformMatrix4fv(vPMatrixHandle, 1, false, mvpMatrix, 0)

        // get handle to fragment shader's vColor member
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor")


        GLES20.glUniform4fv(mColorHandle, 1, lineColor, 0)
        GLES20.glDrawElements(GLES20.GL_LINES, lineDrawOrder.size, GLES20.GL_UNSIGNED_SHORT, lineDrawListBuffer)

        // Disable vertex array
        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}