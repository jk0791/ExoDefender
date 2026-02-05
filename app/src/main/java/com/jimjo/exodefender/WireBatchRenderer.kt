package com.jimjo.exodefender

import android.opengl.GLES20
import android.opengl.Matrix

interface VisualObject {
    var active: Boolean
    fun update(dt: Float) {}
    fun draw(vpMatrix: FloatArray, timeMs: Int)
}
class WireBatchRenderer(
    private val program: Int,
) {
    private val aPosLoc by lazy { GLES20.glGetAttribLocation(program, "vPosition") }
    private val uMvpLoc by lazy { GLES20.glGetUniformLocation(program, "uMVPMatrix") }
    private val uColorLoc by lazy { GLES20.glGetUniformLocation(program, "vColor") }

    private val mvpMatrix = FloatArray(16)

    var lineColor = floatArrayOf(0f, 1f, 0f, 1f)
    var fillColor = floatArrayOf(0f, 0f, 0f, 1f)

    private val lineVerts = FloatArray(6)
    private val lineBuf = java.nio.ByteBuffer
        .allocateDirect(6 * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun setLineColor(r: Float, g: Float, b: Float, a: Float) {
        lineColor = floatArrayOf(r, g, b, a)
    }

    fun drawInstance(vpMatrix: FloatArray, instance: ModelInstance) {
        val mesh = instance.model.mesh
        if (mesh.vertexBufferId == 0) return

        // For civilians, we want *render matrix* valid; see updateRenderOnly() below.
        instance.updateRenderOnly()

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, instance.renderMatrix, 0)

        GLES20.glUseProgram(program)

        // shared vertex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vertexBufferId)

        if (aPosLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)
        }

        if (uMvpLoc >= 0) GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

        // optional fill (if you want civilians filled)
        val fc = fillColor
        if (fc != null && mesh.triIndexBufferOpaqueId != 0 && mesh.triIndexCountOpaque > 0) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.triIndexBufferOpaqueId)
            GLES20.glUniform4fv(uColorLoc, 1, fc, 0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.triIndexCountOpaque, GLES20.GL_UNSIGNED_SHORT, 0)
        }

        // wire lines
        if (mesh.lineIndexBufferId != 0 && mesh.lineIndexCount > 0) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.lineIndexBufferId)
            GLES20.glUniform4fv(uColorLoc, 1, lineColor, 0)
            GLES20.glDrawElements(GLES20.GL_LINES, mesh.lineIndexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        }

        // cleanup
        if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun drawVerticalGlowLineWorld(
        vpMatrix: FloatArray,
        x: Float,
        y: Float,
        z0: Float,
        z1: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        // unbind VBOs, client-side verts
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        // Convert world (x,y,z) -> render space (x, z, y) like your other helpers
        lineVerts[0] = x;  lineVerts[1] = z0; lineVerts[2] = y
        lineVerts[3] = x;  lineVerts[4] = z1; lineVerts[5] = y

        lineBuf.position(0)
        lineBuf.put(lineVerts)
        lineBuf.position(0)

        GLES20.glUseProgram(program)

        if (uColorLoc >= 0) GLES20.glUniform4f(uColorLoc, r, g, b, a)
        if (uMvpLoc >= 0) GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, vpMatrix, 0)

        if (aPosLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, 3 * 4, lineBuf)
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) // additive pop
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_BLEND)

        if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
    }

}
