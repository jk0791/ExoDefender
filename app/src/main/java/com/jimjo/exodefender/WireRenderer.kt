package com.jimjo.exodefender

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WireRenderer(
    val instance: ModelInstance,
    private val program: Int,
    private val glowProgram: Int,
) {

    // cached locations for main program...
    private val aPosLoc by lazy { GLES20.glGetAttribLocation(program, "vPosition") }
    private val uMvpLoc by lazy { GLES20.glGetUniformLocation(program, "uMVPMatrix") }
    private val uColorLoc by lazy { GLES20.glGetUniformLocation(program, "vColor") }

    // cached locations for glowProgram
    private val aPosGlowLoc by lazy { GLES20.glGetAttribLocation(glowProgram, "vPosition") }
    private val uMvpGlowLoc by lazy { GLES20.glGetUniformLocation(glowProgram, "uMVPMatrix") }
    private val uGlowLoc by lazy { GLES20.glGetUniformLocation(glowProgram, "uGlow") }
    private val uTintLoc by lazy { GLES20.glGetUniformLocation(glowProgram, "uTint") }

    private val mvpMatrix = FloatArray(16)

    // Colors
    var normalFillColor: FloatArray? = null // e.g. floatArrayOf(0f, 0f, 0f, 1f) or null for no fill
    var normalLineColor = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
    var highlightLineColor = floatArrayOf(1f, 0f, 0f, 1f)
    var currentLineColor = normalLineColor

    var signalLineColor = floatArrayOf(1f, 0f, 0f, 1f)


    var nozzleGlow = 0f
    var nozzleTint = floatArrayOf(1.0f, 0.85f, 0.6f)

    private var flashing = false
    private var flashStartMs = 0
    private val flashPeriodMs = 400     // full on→off cycle every 400 ms
    private var flashUntilMs = 0

    var landingPadOverlay: LandingPadOverlay? = null

    fun reset() {
        flashUntilMs = 0
        currentLineColor = normalLineColor
        stopFlashing()
    }

    fun flashLinesOnce(timeMs: Int) {
        flashUntilMs = timeMs + 100                  // flash for 100 ms
    }

    fun startFlashing(timeMs: Int, startDelayMs: Int = 0) {
        flashing = true
        flashStartMs = timeMs + startDelayMs
    }

    fun stopFlashing() {
        flashing = false
        currentLineColor = normalLineColor
    }

    fun draw(vpMatrix: FloatArray, timeMs: Int) {
        val mesh = instance.model.mesh
        if (mesh.vertexBufferId == 0) return

        instance.update() // updates worldAabb + renderMatrix

        // MVP = VP * Model
        Matrix.multiplyMM(
            mvpMatrix, 0,
            vpMatrix, 0,
            instance.renderMatrix, 0
        )

        GLES20.glUseProgram(program)

        // Bind shared vertex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vertexBufferId)

        if (aPosLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(
                aPosLoc,
                3,
                GLES20.GL_FLOAT,
                false,
                3 * 4,
                0
            )
        }

        if (uMvpLoc >= 0) {
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        }

        // Optional: filled silhouette (OPAQUE triangles)
        if (normalFillColor != null && mesh.triIndexBufferOpaqueId != 0 && mesh.triIndexCountOpaque > 0) {
            GLES20.glUseProgram(program)

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.triIndexBufferOpaqueId)
            GLES20.glUniform4fv(uColorLoc, 1, normalFillColor, 0)

            // (polygon offset if you want)
            GLES20.glPolygonOffset(0.5f, 1f)

            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                mesh.triIndexCountOpaque,
                GLES20.GL_UNSIGNED_SHORT,
                0
            )
        }

        // NOZZLE GLOW pass (additive emissive triangles)
        if (mesh.triIndexBufferGlowId != 0 && mesh.triIndexCountGlow > 0 && nozzleGlow > 0.001f) {

            GLES20.glUseProgram(glowProgram)

            // attributes (bind vPosition for glow program)
            if (aPosGlowLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aPosGlowLoc)
                GLES20.glVertexAttribPointer(
                    aPosGlowLoc,
                    3,
                    GLES20.GL_FLOAT,
                    false,
                    3 * 4,
                    0
                )
            }

            if (uMvpGlowLoc >= 0) GLES20.glUniformMatrix4fv(uMvpGlowLoc, 1, false, mvpMatrix, 0)
            if (uGlowLoc >= 0) GLES20.glUniform1f(uGlowLoc, nozzleGlow)
            if (uTintLoc >= 0) GLES20.glUniform3f(uTintLoc, nozzleTint[0], nozzleTint[1], nozzleTint[2])

            // State: additive, depth test ON, but don't write depth
            val depthMask = BooleanArray(1)
            GLES20.glGetBooleanv(GLES20.GL_DEPTH_WRITEMASK, depthMask, 0) // optional; can skip if you know it's true

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(false)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.triIndexBufferGlowId)
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                mesh.triIndexCountGlow,
                GLES20.GL_UNSIGNED_SHORT,
                0
            )

            // Restore state so your other rendering isn't affected
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // your default
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDepthMask(true)

            // IMPORTANT: switch back to main program before your line pass (below)
            GLES20.glUseProgram(program)
        }

        // Wireframe lines
        // decide which line color to use
        val lineColor = when {
            timeMs < flashUntilMs -> highlightLineColor        // one-shot flash
            flashing -> {
                val phase = ((timeMs - flashStartMs) % flashPeriodMs) / flashPeriodMs.toFloat()
                // blink between normal and signal every half-period
                if (phase < 0.5f) signalLineColor else normalLineColor
            }
            else -> currentLineColor
        }

        if (mesh.lineIndexBufferId != 0 && mesh.lineIndexCount > 0) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.lineIndexBufferId)
            GLES20.glUniform4fv(uColorLoc, 1, lineColor, 0)

            // optional: thicker lines for style (ES 2.0 support varies)
            // GLES20.glLineWidth(2f)

            GLES20.glDrawElements(
                GLES20.GL_LINES,
                mesh.lineIndexCount,
                GLES20.GL_UNSIGNED_SHORT,
                0
            )
        }


        drawLandingPadOverlay(vpMatrix, timeMs)

        // Cleanup
        if (aPosLoc >= 0) {
            GLES20.glDisableVertexAttribArray(aPosLoc)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }


    private fun drawLandingPadOverlay(vpMatrix: FloatArray, timeMs: Int) {
        val o = landingPadOverlay ?: return
        if (!o.enabled) return
        if (o.radius <= 0f) return

        // Enable blending so alpha in vColor is respected
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Optional but often good for translucent overlays:
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)   // don't write depth for the overlay

        // --- alpha modulation ---
        val alpha = if (o.pulse && !o.isConfirmed) {
            // I'd recommend disabling pulse when confirmed so "locked" looks stable.
            val phase = (timeMs % o.pulsePeriodMs).toFloat() / o.pulsePeriodMs.toFloat()
            o.baseAlpha + o.pulseAmp * kotlin.math.sin(phase * Math.PI * 2.0).toFloat()
        } else {
            o.steadyAlpha
        }.coerceIn(0f, 1f)

        val rInset = o.radius - o.inset
        if (rInset <= 0.01f) {
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
            return
        }

        // World center of cylinder top (your instances are positioned at block center)
        val cx = instance.position.x
        val cy = instance.position.y
        val topZ = instance.position.z + o.halfHeight + o.zBias

        // Base colors (don’t mutate o.color)
        val base = floatArrayOf(o.color[0], o.color[1], o.color[2], alpha)

        // Optional: brighten slightly when within bounds (helps tuning)
        val within = if (o.isWithin && !o.isConfirmed) {
            floatArrayOf(
                (base[0] * 1.25f).coerceAtMost(1f),
                (base[1] * 1.25f).coerceAtMost(1f),
                (base[2] * 1.25f).coerceAtMost(1f),
                alpha
            )
        } else base

        val confirmedGreen = floatArrayOf(0.2f, 1.0f, 0.2f, alpha)

        if (!o.isConfirmed) {
            // --- Outline mode (existing behavior) ---
            drawCircleWireSwapYZHorizontal(
                vpMatrix = vpMatrix,
                centerWorldX = cx,
                centerWorldY = cy,
                centerWorldZ = topZ,
                radius = rInset,
                segments = o.segments,
                color = within
            )

            val crossLen = rInset * o.crossFrac
            val crossVertsWorld = floatArrayOf(
                // X axis line (world XY plane)
                cx - crossLen, cy,           topZ,
                cx + crossLen, cy,           topZ,

                // Y axis line (world XY plane)
                cx,           cy - crossLen, topZ,
                cx,           cy + crossLen, topZ
            )
            drawLinesSwapYZ(vpMatrix, crossVertsWorld, within)

        } else {
            // --- Confirmed mode: "solid" via thickness/overdraw ---
            val c = confirmedGreen

            // 1) Thick ring: multiple slightly offset rings
            val ringThickness = 0.18f   // world units; tune later
            val ringLayers = 5
            for (i in 0 until ringLayers) {
                val t = if (ringLayers == 1) 0.5f else i.toFloat() / (ringLayers - 1).toFloat()
                val r = rInset - (t - 0.5f) * ringThickness // spans +/- thickness/2
                if (r > 0.01f) {
                    drawCircleWireSwapYZHorizontal(
                        vpMatrix = vpMatrix,
                        centerWorldX = cx,
                        centerWorldY = cy,
                        centerWorldZ = topZ,
                        radius = r,
                        segments = o.segments,
                        color = c
                    )
                }
            }

            // 2) Thick crosshair: draw parallel lines
            val crossLen = rInset * o.crossFrac
            val crossOffset = 0.10f // world units; tune later

            fun drawCrossAtOffset(dx: Float, dy: Float) {
                val verts = floatArrayOf(
                    // X axis line (offset in Y)
                    cx - crossLen, cy + dy, topZ,
                    cx + crossLen, cy + dy, topZ,

                    // Y axis line (offset in X)
                    cx + dx, cy - crossLen, topZ,
                    cx + dx, cy + crossLen, topZ
                )
                drawLinesSwapYZ(vpMatrix, verts, c)
            }

            drawCrossAtOffset(0f, 0f)
            drawCrossAtOffset(+crossOffset, 0f)
            drawCrossAtOffset(-crossOffset, 0f)
            drawCrossAtOffset(0f, +crossOffset)
            drawCrossAtOffset(0f, -crossOffset)
        }

        // Restore state
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }



    private fun drawLinesSwapYZ(
        vpMatrix: FloatArray,
        vertsWorldXYZ: FloatArray,
        color: FloatArray
    ) {
        // Client-side vertices (unbind VBOs)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        // Convert world (x,y,z) -> render space (x, z, y)
        val verts = FloatArray(vertsWorldXYZ.size)
        var i = 0
        while (i < vertsWorldXYZ.size) {
            val wx = vertsWorldXYZ[i]
            val wy = vertsWorldXYZ[i + 1]
            val wz = vertsWorldXYZ[i + 2]
            verts[i] = wx
            verts[i + 1] = wz
            verts[i + 2] = wy
            i += 3
        }

        val vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }

        GLES20.glUseProgram(program)

        if (uColorLoc >= 0) GLES20.glUniform4fv(uColorLoc, 1, color, 0)
        if (uMvpLoc >= 0) GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, vpMatrix, 0)

        if (aPosLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
        }

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, verts.size / 3)

        if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
    }

    fun drawCircleWireSwapYZHorizontal(
        vpMatrix: FloatArray,
        centerWorldX: Float,
        centerWorldY: Float,
        centerWorldZ: Float,
        radius: Float,
        segments: Int,
        color: FloatArray
    ) {
        val segs = segments.coerceAtLeast(6)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        // In swapped render space:
        // renderX = worldX
        // renderY = worldZ   (up)
        // renderZ = worldY
        val cx = centerWorldX
        val cy = centerWorldZ   // <-- swapped
        val cz = centerWorldY   // <-- swapped

        val verts = FloatArray(segs * 2 * 3)
        val twoPi = Math.PI * 2.0
        var i = 0

        for (s in 0 until segs) {
            val a0 = twoPi * (s.toDouble() / segs.toDouble())
            val a1 = twoPi * ((s + 1).toDouble() / segs.toDouble())

            // Horizontal ring in render space is XZ (because renderY is "up")
            val x0 = cx + radius * kotlin.math.cos(a0).toFloat()
            val z0 = cz + radius * kotlin.math.sin(a0).toFloat()
            val y0 = cy

            val x1 = cx + radius * kotlin.math.cos(a1).toFloat()
            val z1 = cz + radius * kotlin.math.sin(a1).toFloat()
            val y1 = cy

            verts[i++] = x0; verts[i++] = y0; verts[i++] = z0
            verts[i++] = x1; verts[i++] = y1; verts[i++] = z1
        }

        val vertexBuffer = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }

        GLES20.glUseProgram(program)

        if (uColorLoc >= 0) GLES20.glUniform4fv(uColorLoc, 1, color, 0)
        if (uMvpLoc >= 0) GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, vpMatrix, 0)

        if (aPosLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
        }

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, verts.size / 3)

        if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
    }

    fun drawAabbWire(vpMatrix: FloatArray, aabb: Aabb, color: FloatArray) {
        // IMPORTANT: unbind any VBO so the FloatBuffer is used (client-side attrib array)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        // Use your world axes directly (x,y,z). No swapping.
        val x0 = aabb.min.x
        val y0 = aabb.min.z
        val z0 = aabb.min.y
        val x1 = aabb.max.x
        val y1 = aabb.max.z
        val z1 = aabb.max.y

        // 12 edges -> 24 vertices -> 72 floats
        val verts = floatArrayOf(
            // Bottom face (z0)
            x0, y0, z0,   x1, y0, z0,
            x1, y0, z0,   x1, y1, z0,
            x1, y1, z0,   x0, y1, z0,
            x0, y1, z0,   x0, y0, z0,

            // Top face (z1)
            x0, y0, z1,   x1, y0, z1,
            x1, y0, z1,   x1, y1, z1,
            x1, y1, z1,   x0, y1, z1,
            x0, y1, z1,   x0, y0, z1,

            // Vertical edges
            x0, y0, z0,   x0, y0, z1,
            x1, y0, z0,   x1, y0, z1,
            x1, y1, z0,   x1, y1, z1,
            x0, y1, z0,   x0, y1, z1
        )

        val vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(verts)
                position(0)
            }

        GLES20.glUseProgram(program)

        if (uColorLoc >= 0) GLES20.glUniform4fv(uColorLoc, 1, color, 0)
        if (uMvpLoc >= 0) GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, vpMatrix, 0)

        if (aPosLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(
                aPosLoc,
                3,
                GLES20.GL_FLOAT,
                false,
                3 * 4,
                vertexBuffer
            )
        }

        // Optional: thicker lines (device-dependent in ES 2.0)
        // GLES20.glLineWidth(2f)

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, verts.size / 3)

        if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
    }


}

data class LandingPadOverlay(
    var enabled: Boolean = false,

    // Geometry (local-space semantics, but we render using world center + known Z top)
    var radius: Float = 0f,        // you decided: radius = halfXExtent
    var halfHeight: Float = 0f,    // halfExtents.z

    // Style
    var color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),

    // Shape params
    var inset: Float = 1.5f,      // meters
    var zBias: Float = 0.01f,      // meters above top face to avoid z-fighting
    var segments: Int = 24,
    var crossFrac: Float = 0.4f,   // crosshair half-length = rInset * crossFrac

    // Alpha behaviour
    var pulse: Boolean = true,
    var steadyAlpha: Float = 1.0f,     // used when pulse == false
    var baseAlpha: Float = 0.625f,      // midpoint of pulse
    var pulseAmp: Float = 0.375f,       // amplitude
    var pulsePeriodMs: Int = 2500
) {
    var isWithin: Boolean = false
    var isConfirmed: Boolean = false
}