package com.jimjo.exodefender

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class GpuModelCache(private val modelManager: ModelManager) {
    private val models = mutableMapOf<String, Model>()  // models by path

    fun getModel(path: String): Model {
        return models.getOrPut(path) {
            // CPU stuff from the global manager:
            val template = modelManager.getTemplate(path)

            // GPU upload happens in the current GL context:
            val gpuMesh = uploadToGpu(template.meshData)

            Model(gpuMesh, template.localAabb)
        }
    }

    fun clear() {
        // Delete GL buffers when context is lost / renderer destroyed
//        for (model in models.values) {
//            val mesh = model.mesh
//
//            if (mesh.vertexBufferId != 0) {
//                GLES20.glDeleteBuffers(1, intArrayOf(mesh.vertexBufferId), 0)
//            }
//            if (mesh.triIndexBufferId != 0) {
//                GLES20.glDeleteBuffers(1, intArrayOf(mesh.triIndexBufferId), 0)
//            }
//            if (mesh.lineIndexBufferId != 0) {
//                GLES20.glDeleteBuffers(1, intArrayOf(mesh.lineIndexBufferId), 0)
//            }
//        }
        models.clear()
    }
}

class GpuMesh(
    val vertexBufferId: Int,

    val triIndexBufferOpaqueId: Int,   // 0 if none
    val triIndexCountOpaque: Int,

    val triIndexBufferGlowId: Int,     // 0 if none
    val triIndexCountGlow: Int,

    val lineIndexBufferId: Int,        // 0 if none
    val lineIndexCount: Int
)

fun uploadToGpu(data: MeshData): GpuMesh {
    // --- VERTEX BUFFER (positions only, 3 floats/vertex) ---

    val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(data.positions.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(data.positions)
            position(0)
        }

    val vboIds = IntArray(1)
    GLES20.glGenBuffers(1, vboIds, 0)
    val vbo = vboIds[0]
    if (vbo == 0) throw RuntimeException("Failed to create vertex buffer")

    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
    GLES20.glBufferData(
        GLES20.GL_ARRAY_BUFFER,
        data.positions.size * 4,
        vertexBuffer,
        GLES20.GL_STATIC_DRAW
    )
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

    fun uploadIndexBuffer(indices: ShortArray): Pair<Int, Int> {
        val count = indices.size
        if (count == 0) return 0 to 0

        val iboIds = IntArray(1)
        GLES20.glGenBuffers(1, iboIds, 0)
        val id = iboIds[0]
        if (id == 0) throw RuntimeException("Failed to create index buffer")

        val buf: ShortBuffer = ByteBuffer
            .allocateDirect(count * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                position(0)
            }

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, id)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            count * 2,
            buf,
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        return id to count
    }

    // --- TRIANGLE INDEX BUFFERS ---
    val (triOpaqueIbo, triOpaqueCount) = uploadIndexBuffer(data.triIndicesOpaque)
    val (triGlowIbo, triGlowCount) = uploadIndexBuffer(data.triIndicesGlow)

    // --- LINE INDEX BUFFER (for wireframe edges) ---
    val (lineIbo, lineCount) = uploadIndexBuffer(data.lineIndices)

    return GpuMesh(
        vertexBufferId = vbo,

        triIndexBufferOpaqueId = triOpaqueIbo,
        triIndexCountOpaque = triOpaqueCount,

        triIndexBufferGlowId = triGlowIbo,
        triIndexCountGlow = triGlowCount,

        lineIndexBufferId = lineIbo,
        lineIndexCount = lineCount
    )
}

