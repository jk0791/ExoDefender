package com.jimjo.exodefender


import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

class Skybox(private val context: Context) {

    private var program = 0
    private var vbo = 0
    private var cubemapTex = 0
    private val vertexCount = 36

    // Cube vertices (inside-facing)
    private val vertices = floatArrayOf(
        // Back (-Z)
        -1f,  1f, -1f,  -1f, -1f, -1f,   1f, -1f, -1f,
        1f, -1f, -1f,   1f,  1f, -1f,  -1f,  1f, -1f,

        // Left (-X)
        -1f, -1f,  1f,  -1f, -1f, -1f,  -1f,  1f, -1f,
        -1f,  1f, -1f,  -1f,  1f,  1f,  -1f, -1f,  1f,

        // Right (+X)
        1f, -1f, -1f,   1f, -1f,  1f,   1f,  1f,  1f,
        1f,  1f,  1f,   1f,  1f, -1f,   1f, -1f, -1f,

        // Bottom (-Y) (reversed)
        -1f, -1f, -1f,  -1f, -1f,  1f,   1f, -1f,  1f,
        1f, -1f,  1f,   1f, -1f, -1f,  -1f, -1f, -1f,

        // Front (+Z) (reversed)
        -1f, -1f,  1f,  -1f,  1f,  1f,   1f,  1f,  1f,
        1f,  1f,  1f,   1f, -1f,  1f,  -1f, -1f,  1f,

        // Top (+Y)
        -1f,  1f, -1f,   1f,  1f, -1f,   1f,  1f,  1f,
        1f,  1f,  1f,  -1f,  1f,  1f,  -1f,  1f, -1f
    )


    fun init(cubemapFaces: Array<String>) {
        createProgram()
        createVbo()
        cubemapTex = loadCubemap(cubemapFaces)
    }

    private fun createProgram() {
        val vertexSrc = """
            attribute vec3 aPos;
            uniform mat4 uView;
            uniform mat4 uProj;
            varying vec3 vTex;
            void main() {
                vTex = aPos;
                mat4 view = mat4(mat3(uView));   // remove translation
                gl_Position = uProj * view * vec4(aPos, 1.0);
            }
        """.trimIndent()

        val fragSrc = """
            precision mediump float;
            varying vec3 vTex;
            uniform samplerCube uSky;
            void main() {
                gl_FragColor = textureCube(uSky, vTex);
            }
        """.trimIndent()

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createVbo() {
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val vertexData = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexData.put(vertices).position(0)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertices.size * 4,
            vertexData,
            GLES20.GL_STATIC_DRAW
        )

        // Important: unbind so it doesn’t affect other draw calls
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun loadCubemap(faces: Array<String>): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, tex[0])

        val targets = intArrayOf(
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_X,
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_Z,
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z,
        )

        for (i in faces.indices) {
            val bmp = BitmapFactory.decodeStream(context.assets.open(faces[i]))
            if (bmp == null) {
//                Log.e("Skybox", "Failed to load ${faces[i]}")
                println("Failed to load ${faces[i]}")
            }
            else {
                Log.d("Skybox", "Loading ${faces[i]} into target ${targets[i]}")
            }
            GLUtils.texImage2D(targets[i], 0, bmp, 0)
            bmp.recycle()
        }

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glDepthMask(false)               // don’t write to depth buffer
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        // Remove translation from view
        val viewNoTrans = FloatArray(16)
        System.arraycopy(viewMatrix, 0, viewNoTrans, 0, 16)
        viewNoTrans[12] = 0f
        viewNoTrans[13] = 0f
        viewNoTrans[14] = 0f

        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val uView = GLES20.glGetUniformLocation(program, "uView")
        val uProj = GLES20.glGetUniformLocation(program, "uProj")

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)

        GLES20.glUniformMatrix4fv(uView, 1, false, viewNoTrans, 0)
        GLES20.glUniformMatrix4fv(uProj, 1, false, projectionMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, cubemapTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uSky"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDepthMask(true)
        GLES20.glDepthFunc(GLES20.GL_LESS)

    }
}

/*

class MyRenderer(val context: Context) : GLSurfaceView.Renderer {

    private lateinit var skybox: Skybox
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        skybox = Skybox(context)
        skybox.init(arrayOf(
            "right.png", "left.png",
            "top.png", "bottom.png",
            "front.png", "back.png"
        ))
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height
        Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // update viewMatrix from camera rotation here
        // (camera always at origin for skybox)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

        skybox.draw(viewMatrix, projectionMatrix)

        // draw rest of scene after
    }
}
 */