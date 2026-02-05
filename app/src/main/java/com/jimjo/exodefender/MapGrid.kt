package com.jimjo.exodefender

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

class MapGrid {

    lateinit var world: World

    var pointsCount = 0

    private lateinit var coords: FloatArray

    val fillVerticesPerSquare = 6
    val lineVerticesPerSquare = 4 // draw two lines per square, 2 vertices per line

    private lateinit var fillDrawOrder: IntArray
    private lateinit var lineDrawOrder: IntArray

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var fillDrawListBuffer: IntBuffer
    private lateinit var lineDrawListBuffer: IntBuffer
    private val vertexStride: Int = COORDS_PER_VERTEX * 4

    private val fillColor = floatArrayOf(0f, 0f, 0f, 1.0f)
//    private val fillColor = floatArrayOf(0.3f, 0.3f, 0.3f, 1.0f)
//    private val lineColor = floatArrayOf(0.63671875f, 0.76953125f, 0.22265625f, 1.0f)
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
//        loadGLProgram()
    }

    fun loadCoords(world: World) {

        this.world = world

        pointsCount = MAP_GRID_SIZE * MAP_GRID_SIZE
//        coords = FloatArray(pointsCount * COORDS_PER_VERTEX)
        coords = FloatArray(pointsCount * COORDS_PER_VERTEX)
        fillDrawOrder = IntArray((MAP_GRID_SIZE - 1) * (MAP_GRID_SIZE - 1)  * fillVerticesPerSquare)
        lineDrawOrder = IntArray((MAP_GRID_SIZE - 1) * (MAP_GRID_SIZE - 1) * lineVerticesPerSquare)


        var ordCounter = -1
        for (y in 0 ..< MAP_GRID_SIZE) {
            for (x in 0 ..< MAP_GRID_SIZE) {

                ordCounter++
                coords[ordCounter] = x * MAP_GRID_SPACING

                // set y to elevation
                ordCounter++
                coords[ordCounter] = this.world.heightMap[y][x]

                ordCounter++
                coords[ordCounter] = y * MAP_GRID_SPACING
            }
        }
    }

    fun loadDrawLists() {

        var fillCounter = 0
        var lineCounter = 0

        for (y in 0 ..< MAP_GRID_SIZE) {

            for (x in 0..< MAP_GRID_SIZE) {

                val coordIndex = x + y * MAP_GRID_SIZE

                if (x != MAP_GRID_SIZE - 1) {

                    if (y != MAP_GRID_SIZE - 1) {
                        // current row triangle
                        fillDrawOrder[fillCounter++] = coordIndex
                        fillDrawOrder[fillCounter++] = coordIndex + MAP_GRID_SIZE
                        fillDrawOrder[fillCounter++] = coordIndex + 1
                    }

                    if (y != 0) {
                        // previous row triangle
                        fillDrawOrder[fillCounter++] = coordIndex
                        fillDrawOrder[fillCounter++] = coordIndex + 1
                        fillDrawOrder[fillCounter++] = coordIndex - MAP_GRID_SIZE + 1
                    }
                }
                // draw two lines bordering current row square
                if (x != MAP_GRID_SIZE - 1 && y != MAP_GRID_SIZE - 1) {
                    lineDrawOrder[lineCounter++] = coordIndex
                    lineDrawOrder[lineCounter++] = coordIndex + 1
                    lineDrawOrder[lineCounter++] = coordIndex
                    lineDrawOrder[lineCounter++] = coordIndex + MAP_GRID_SIZE
                }
            }
        }
//        println("fillDrawOrder.size=${fillDrawOrder.size}")
//        println("fillCounter=$fillCounter")
//        println("lineDrawOrder.size=${lineDrawOrder.size}")
//        println("lineCounter=$lineCounter")
    }

    fun loadByteArrays() {

        // initialize vertex byte buffer for shape coordinates
        vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coords)
                position(0)
            }
        }

        // initialize byte buffer for the draw list
        fillDrawListBuffer = ByteBuffer.allocateDirect(fillDrawOrder.size * 4).run {
            order(ByteOrder.nativeOrder())
            asIntBuffer().apply {
                put(fillDrawOrder)
                position(0)
            }
        }

        lineDrawListBuffer = ByteBuffer.allocateDirect(lineDrawOrder.size * 4).run {
            order(ByteOrder.nativeOrder())
            asIntBuffer().apply {
                put(lineDrawOrder)
                position(0)
            }
        }
    }

//    fun loadGLProgram() {
//        val vertexShaderCode =
//        // This matrix member variable provides a hook to manipulate
//            // the coordinates of the objects that use this vertex shader
//            "uniform mat4 uMVPMatrix;" +
//                    "attribute vec4 vPosition;" +
//                    "void main() {" +
//                    "  gl_Position = uMVPMatrix * vPosition;" +
//                    "}"
//
//        val fragmentShaderCode =
//            "precision mediump float;" +
//                    "uniform vec4 vColor;" +
//                    "void main() {" +
//                    "  gl_FragColor = vColor;" +
//                    "}"
//
//
//        val vertexShader: Int = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
//        val fragmentShader: Int = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
//
//        mProgram = GLES20.glCreateProgram()                 // create OpenGL ES Program
//        GLES20.glAttachShader(mProgram, vertexShader)       // add the vertex shader to program
//        GLES20.glAttachShader(mProgram, fragmentShader)     // add the fragment shader to program
//        GLES20.glLinkProgram(mProgram)                      // creates OpenGL ES program executables
//
//        positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
//        vPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
//
//    }

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



//        GLES20.glUniform4fv(mColorHandle, 1, lineColor, 0)
//        GLES20.glDrawElements(GLES20.GL_LINES, fillDrawOrder.size, GLES20.GL_UNSIGNED_INT, fillDrawListBuffer)

        GLES20.glUniform4fv(mColorHandle, 1, fillColor, 0)
        GLES20.glPolygonOffset(0.5f,1f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, fillDrawOrder.size, GLES20.GL_UNSIGNED_INT, fillDrawListBuffer)

        GLES20.glUniform4fv(mColorHandle, 1, lineColor, 0)
        GLES20.glDrawElements(GLES20.GL_LINES, lineDrawOrder.size, GLES20.GL_UNSIGNED_INT, lineDrawListBuffer)

        // Disable vertex array
        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}