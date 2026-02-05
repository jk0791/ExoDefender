package com.jimjo.exodefender

import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.random.Random

interface ShrapnelParent {
    fun notifyDeactivtaion()
}

class Shrapnel(val parent: ShrapnelParent, size: Float, val lineColor: FloatArray, sideOffset: Float):
    RectF() {

    var velocity = 0f
    var rotationalVelocity = 0f
    var active = false
    var distanceTravelled = 0f
    val position = Vec3()
    var rotationAngle = 0f
    var maxDuration = 0f
    var duration = 0f


    val position2d = PointF()
    var startAngleP = 0.0
        set(value) {
            field = value
            sinAngleP = Math.sin(value)
            cosAngleP = Math.cos(value)
        }
    var startAngleE = 0.0
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
    protected lateinit var lineDrawOrder: ShortArray
    private lateinit var fillDrawOrder: ShortArray

    private lateinit var vertexBuffer: FloatBuffer
    protected lateinit var lineDrawListBuffer: ShortBuffer
    private lateinit var fillDrawListBuffer: ShortBuffer

//    private val lineColor = floatArrayOf(0.9f, 0.9f, 0.9f, 1.0f)
    private val fillColor = floatArrayOf(0f, 0f, 0f, 1.0f)

    private var mProgram: Int = -1
    private var vPMatrixHandle: Int = -1
    private var positionHandle: Int = -1
    private var mColorHandle: Int = -1

    private val modelMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)

    init {
        val maxLenth = 5f
        this.top = -maxLenth / 2 * size
        this.bottom = maxLenth / 2 * size
        this.left = -maxLenth / 2 * size + sideOffset
        this.right = maxLenth / 2 * size + sideOffset

    }

    fun activate(x: Float,
                 y: Float,
                 z: Float,
                 velocity: Float,
                 rotationalVelocity: Float,
                 maxDuration: Float,
                 angleP: Double,
                 angleE: Double) {

        this.maxDuration = maxDuration
        duration = 0f
        distanceTravelled = 0f
        this.velocity = velocity
        this.rotationalVelocity = rotationalVelocity
        rotationAngle = Random.nextFloat() * 360f
        position.x = x
        position.y = y
        position.z = z

        this.startAngleP = angleP
        this.startAngleE = angleE

//        Matrix.setRotateM(rotationMatrixP, 0, Math.toDegrees(angleP).toFloat(), 0f, 1f, 0f)
//        Matrix.setRotateM(rotationMatrixE, 0, -Math.toDegrees(angleE).toFloat(), 1f, 0f, 0f)
//        Matrix.multiplyMM(rotationMatrix, 0, rotationMatrixP,0, rotationMatrixE, 0)

        active = true
    }

    fun update(interval: Float, map: World) {

        duration += interval

        val intervalDisplacement = velocity * interval
        distanceTravelled += intervalDisplacement
        val xyIntervalDisplacement = intervalDisplacement * cosAngleE.toFloat()
        position.x -= xyIntervalDisplacement * sinAngleP.toFloat()
        position.y -= xyIntervalDisplacement * cosAngleP.toFloat()
        position.z -= intervalDisplacement * sinAngleE.toFloat()
        position2d.set(position.x, position.y)


        rotationAngle += rotationalVelocity * interval

        if (active) {

            // Note: don't bother with following code that checks if shrapnel has hit the ground
//            val elevation = map.getElevationBefore(position2d)
//            if (elevation != null) {
//                if (position.z < elevation) {
//                    active = false
//                    parent.notifyDeactivtaion()
//                }
//            }
            if (duration > maxDuration) {
                active = false
                parent.notifyDeactivtaion()
            }
        }
    }

    fun load() {
        coords = FloatArray(3 * COORDS_PER_VERTEX)
        lineDrawOrder = ShortArray(3 * 2) // 3 lines
        fillDrawOrder = ShortArray(2 * 3) // 2 back-to-back faces (to defeat back-face culling)

        var ordCounter = 0
        coords[ordCounter++] = left
        coords[ordCounter++] = 0f
        coords[ordCounter++] = top

        coords[ordCounter++] = left
        coords[ordCounter++] = 0f
        coords[ordCounter++] = bottom

        coords[ordCounter++] = right
        coords[ordCounter++] = 0f
        coords[ordCounter++] = top

        var lineCounter = 0
        // lower square
        lineDrawOrder[lineCounter++] = 0
        lineDrawOrder[lineCounter++] = 1
        lineDrawOrder[lineCounter++] = 1
        lineDrawOrder[lineCounter++] = 2
        lineDrawOrder[lineCounter++] = 2
        lineDrawOrder[lineCounter++] = 0

        var fillCounter = 0
        fillDrawOrder[fillCounter++] = 0
        fillDrawOrder[fillCounter++] = 1
        fillDrawOrder[fillCounter++] = 2

        fillDrawOrder[fillCounter++] = 0
        fillDrawOrder[fillCounter++] = 2
        fillDrawOrder[fillCounter++] = 1

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
        fillDrawListBuffer = ByteBuffer.allocateDirect(fillDrawOrder.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(fillDrawOrder)
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


        Matrix.translateM(modelMatrix, 0, mvpMatrix, 0, position.x, position.z, position.y)
        Matrix.setRotateM(rotationMatrix, 0, rotationAngle, 0.5f, 0.5f, 0.5f)
        Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, rotationMatrix, 0)

        // Pass the projection and view transformation to the shader
        GLES20.glUniformMatrix4fv(vPMatrixHandle, 1, false, modelMatrix, 0)

        GLES20.glUniform4fv(mColorHandle, 1, fillColor, 0)

        GLES20.glUniform4fv(mColorHandle, 1, fillColor, 0)
        GLES20.glPolygonOffset(0.5f,1f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, fillDrawOrder.size, GLES20.GL_UNSIGNED_SHORT, fillDrawListBuffer)

        // get handle to fragment shader's vColor member
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor")

        GLES20.glUniform4fv(mColorHandle, 1, lineColor, 0)
        GLES20.glDrawElements(GLES20.GL_LINES, lineDrawOrder.size, GLES20.GL_UNSIGNED_SHORT, lineDrawListBuffer)


        // Disable vertex array
        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}