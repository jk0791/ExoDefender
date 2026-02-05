package com.jimjo.exodefender

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class ExplosionFlashSystem(
    private val maxFlashes: Int = 64
) {
    private data class Flash(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var t: Float = 0f,
        var baseSize: Float = 2f,
        var speed: Float = 35f,
        var tau: Float = 0.10f,
        var strength: Float = 1f,
        var r: Float = 1f,
        var g: Float = 0.9f,
        var b: Float = 0.7f
    )

    private val flashes = Array(maxFlashes) { Flash() }
    private var cursor = 0

    // Fullscreen-ish quad in "corner" space: (-1,-1) .. (1,1)
    // We'll build world positions in the vertex shader: center + right*corner.x*size + up*corner.y*size
    private val quadCorners: FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f,  1f,
        1f,  1f
    )

    private val quadIndices = shortArrayOf(0, 1, 2, 2, 1, 3)
    private val indexBuffer = ByteBuffer
        .allocateDirect(quadIndices.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply { put(quadIndices); position(0) }

    // Shader program + uniforms/attribs
    private var program = 0
    private var aCorner = 0
    private var uVP = 0
    private var uCenter = 0
    private var uCamRight = 0
    private var uCamUp = 0
    private var uSize = 0
    private var uIntensity = 0
    private var uTint = 0

    // Temp matrices/vectors
    private val invView = FloatArray(16)
    private val camRight = FloatArray(3)
    private val camUp = FloatArray(3)

    private var uCamFwd = 0
    private var uPush = 0
    private val camFwd = FloatArray(3)

    fun initGl() {
        if (program != 0) return

        val vs = """
    uniform mat4 uVP;
    uniform vec3 uCenter;
    uniform vec3 uCamRight;
    uniform vec3 uCamUp;
    uniform vec3 uCamFwd;
    uniform float uSize;
    uniform float uPush;
    attribute vec2 aCorner;
    varying vec2 vCorner;

    void main() {
        vCorner = aCorner;
        vec3 worldPos = uCenter
                      + uCamFwd   * uPush
                      + uCamRight * (aCorner.x * uSize)
                      + uCamUp    * (aCorner.y * uSize);

        gl_Position = uVP * vec4(worldPos, 1.0);
    }
""".trimIndent()

        val fs = """
            precision mediump float;
            uniform float uIntensity;
            uniform vec3 uTint;
            varying vec2 vCorner;

            void main() {
                // Radial falloff: center hot, edges fade
                float r = length(vCorner);              // 0 at center, ~1.414 at corner
                float soft = smoothstep(1.2, 0.0, r);    // soft disk
                float core = smoothstep(0.35, 0.0, r);   // bright core
                float alpha = soft * 0.85 + core * 0.55;
                vec3 color = uTint * (uIntensity * alpha);

                // Additive blending uses RGB; alpha doesn't matter much, but keep it sane:
                gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent()

        program = buildProgram(vs, fs)
        aCorner = GLES20.glGetAttribLocation(program, "aCorner")
        uVP = GLES20.glGetUniformLocation(program, "uVP")
        uCenter = GLES20.glGetUniformLocation(program, "uCenter")
        uCamRight = GLES20.glGetUniformLocation(program, "uCamRight")
        uCamUp = GLES20.glGetUniformLocation(program, "uCamUp")
        uSize = GLES20.glGetUniformLocation(program, "uSize")
        uIntensity = GLES20.glGetUniformLocation(program, "uIntensity")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uCamFwd = GLES20.glGetUniformLocation(program, "uCamFwd")
        uPush = GLES20.glGetUniformLocation(program, "uPush")

    }

    fun spawnWorldSmall(position: Vec3) {
        // world → GL swizzle
        spawnInternal(position.x, position.z, position.y,
            strength = 0.6f,
            r = 0.9f, g = 0.95f, b = 1.0f,
            baseSize = 3f,
            speed = 35f)
    }

    fun spawnWorldLarge(position: Vec3) {
        // world → GL swizzle
        spawnInternal(position.x, position.z, position.y,
            strength = 1.4f,
            r = 1.0f, g = 0.85f, b = 0.6f,
            baseSize = 7f,
            speed = 60f)
    }

    private fun spawnInternal(
        x: Float, y: Float, z: Float,
        strength: Float,
        r: Float, g: Float, b: Float,
        baseSize: Float,
        speed: Float
    ) {
        val f = flashes[cursor]
        cursor = (cursor + 1) % flashes.size

        f.active = true
        f.x = x; f.y = y; f.z = z
        f.t = 0f

        f.baseSize = baseSize
        f.speed = speed
        f.tau = 0.10f
        f.strength = strength

        f.r = r; f.g = g; f.b = b
    }


    fun update(dt: Float) {
        val dtClamped = min(max(dt, 0f), 0.05f) // avoid crazy jumps
        for (f in flashes) {
            if (!f.active) continue
            f.t += dtClamped
            // kill after it's basically invisible
            if (f.t > 0.6f) f.active = false
        }
    }

    fun draw(vPMatrix: FloatArray, viewMatrix: FloatArray) {
        if (program == 0) return

        // Extract camera right/up in WORLD space from inverse view matrix
        Matrix.invertM(invView, 0, viewMatrix, 0)
        // Column-major: invView column 0 = right, column 1 = up
        camRight[0] = invView[0];  camRight[1] = invView[1];  camRight[2] = invView[2]
        camUp[0]    = invView[4];  camUp[1]    = invView[5];  camUp[2]    = invView[6]

        // invView columns: 0=right, 1=up, 2=forward (world-space camera basis)
        // Depending on convention, forward might need a minus.
        // We'll try the common "camera looks down -Z" convention first:
        camFwd[0] = invView[8]
        camFwd[1] = invView[9]
        camFwd[2] = invView[10]

        GLES20.glUseProgram(program)

        // Common uniforms
        GLES20.glUniformMatrix4fv(uVP, 1, false, vPMatrix, 0)
        GLES20.glUniform3f(uCamRight, camRight[0], camRight[1], camRight[2])
        GLES20.glUniform3f(uCamUp, camUp[0], camUp[1], camUp[2])
        GLES20.glUniform3f(uCamFwd, camFwd[0], camFwd[1], camFwd[2])

        // State: additive billboard, depth-tested but not depth-writing
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)

        // Attribute: quad corners
        GLES20.glEnableVertexAttribArray(aCorner)
        GLES20.glVertexAttribPointer(aCorner, 2, GLES20.GL_FLOAT, false, 2 * 4, quadCorners)



        for (f in flashes) {
            if (!f.active) continue

            val age = f.t
            val size = f.baseSize + f.speed * age
            val push = 0.45f * size + 0.25f  // bigger flash gets pushed more

            // Intensity curve: quick pop then exponential fade
            var intensity = (f.strength.toDouble() * exp((-age / f.tau).toDouble())).toFloat()
            // tiny ramp-in avoids a one-frame “teleport pop”
            val ramp = smoothstep(0f, 0.02f, age)
            intensity *= ramp

            if (intensity < 0.01f) {
                f.active = false
                continue
            }

            GLES20.glUniform3f(uCenter, f.x, f.y, f.z)
            GLES20.glUniform1f(uSize, size)
            GLES20.glUniform1f(uPush, push)
            GLES20.glUniform1f(uIntensity, intensity)
            GLES20.glUniform3f(uTint, f.r, f.g, f.b)

            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                quadIndices.size,
                GLES20.GL_UNSIGNED_SHORT,
                indexBuffer
            )

        }


        // Restore (important so your other transparent stuff behaves)
        GLES20.glDisableVertexAttribArray(aCorner)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    // --- helpers ---
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun floatBufferOf(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(v); position(0) }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)

        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("ExplosionFlash link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("ExplosionFlash shader compile failed: $log")
        }
        return s
    }
}
