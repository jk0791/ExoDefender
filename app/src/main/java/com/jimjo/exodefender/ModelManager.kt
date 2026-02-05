package com.jimjo.exodefender

import android.content.res.AssetManager
import kotlin.sequences.forEach

data class CpuModel(
    val meshData: MeshData,
    val localAabb: Aabb
)

data class MeshData(
    val positions: FloatArray,
    val normals: FloatArray?,
    val triIndicesOpaque: ShortArray,
    val triIndicesGlow: ShortArray,
    val lineIndices: ShortArray
)

class ModelManager(private val assetManager: AssetManager) {

    private val templates = mutableMapOf<String, ModelTemplate>()  // CPU-only cache, safe to keep global
    private val models = mutableMapOf<String, Model>()
    private val cpuModels = mutableMapOf<String, CpuModel>()

    fun getTemplate(path: String): ModelTemplate {
        return templates.getOrPut(path) {
            val meshData = objLoaderFromAssets(assetManager, path)    // disk + CPU
            val localAabb = computeLocalAabb(meshData.positions)      // CPU
            ModelTemplate(meshData, localAabb)
        }
    }

    fun getModel(path: String): Model {
        return models.getOrPut(path) {
            val meshData = objLoaderFromAssets(assetManager, path)
            val gpuMesh = uploadToGpu(meshData)
            val localAabb = computeLocalAabb(meshData.positions)
            Model(gpuMesh, localAabb)
        }
    }

    fun getCpuModel(path: String): CpuModel =
        cpuModels.getOrPut(path) {
            val meshData = objLoaderFromAssets(assetManager, path)
            val aabb = computeLocalAabb(meshData.positions)
            CpuModel(meshData, aabb)
        }

    private fun computeLocalAabb(positions: FloatArray): Aabb {
        val min = Vec3(
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY
        )
        val max = Vec3(
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        )

        var i = 0
        while (i < positions.size) {
            val x = positions[i]
            val z = positions[i + 1] // swapped
            val y = positions[i + 2]

            if (x < min.x) min.x = x
            if (y < min.y) min.y = y
            if (z < min.z) min.z = z
            if (x > max.x) max.x = x
            if (y > max.y) max.y = y
            if (z > max.z) max.z = z

            i += 3
        }
        return Aabb(min, max)
    }
}

fun objLoaderFromAssets(
    assetManager: AssetManager,
    filePath: String
): MeshData {

    val srcPositions = ArrayList<Vec3>()
    val srcNormals = ArrayList<Vec3>()
    var currentMtl: String? = null
    val triOpaque = ArrayList<Short>()
    val triGlow = ArrayList<Short>()

    // (vIndex, vnIndex) -> final vertex index
    data class Key(val v: Int, val vn: Int)

    val indexMap = HashMap<Key, Int>()
    val outPositions = ArrayList<Float>()
    val outNormals = ArrayList<Float>()

    // For mapping original position index -> some vertex index (for 'l' lines)
    val posToVertex = HashMap<Int, Int>()

    // Unique edges (store sorted endpoints so (a,b) == (b,a))
    data class Edge(val a: Int, val b: Int)
    val edgeSet = HashSet<Edge>()

    val input = assetManager.open(filePath)
    val reader = input.bufferedReader()

    fun getOrCreateVertex(vIdx: Int, vnIdx: Int): Int {
        val key = Key(vIdx, vnIdx)
        val existing = indexMap[key]
        if (existing != null) return existing

        val pos = srcPositions[vIdx]
        outPositions.add(pos.x)
        outPositions.add(pos.y)
        outPositions.add(pos.z)

        if (vnIdx >= 0 && vnIdx < srcNormals.size) {
            val n = srcNormals[vnIdx]
            outNormals.add(n.x)
            outNormals.add(n.y)
            outNormals.add(n.z)
        } else {
            // placeholder; we'll drop normals later if unused
            outNormals.add(0f)
            outNormals.add(0f)
            outNormals.add(0f)
        }

        val newIndex = indexMap.size
        indexMap[key] = newIndex

        // Record a default vertex for this position index (only if not set yet)
        posToVertex.putIfAbsent(vIdx, newIndex)

        return newIndex
    }

    fun addEdge(a: Int, b: Int) {
        if (a == b) return
        val e = if (a < b) Edge(a, b) else Edge(b, a)
        edgeSet.add(e)
    }

    reader.use { br ->
        br.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "v" -> {
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    val z = parts[3].toFloat()
                    // Keep your current convention; scaling optional:
                    // val s = INCH_TO_METER
                    // srcPositions.add(Vec3(x*s, y*s, z*s))
                    srcPositions.add(Vec3(x, y, z))
                }

                "vn" -> {
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    val z = parts[3].toFloat()
                    srcNormals.add(Vec3(x, y, z))
                }

                "vt" -> {
                    // Ignore textures for your vector look
                }

                "usemtl" -> {
                    currentMtl = parts.getOrNull(1) // e.g. "NOZZLE_GLOW"
                }

                "f" -> {
                    val tokens = parts.drop(1)
                    if (tokens.size !in 3..4) {
                        throw IllegalArgumentException("Only triangles/quads supported (got ${tokens.size})")
                    }

                    fun parseFaceVertex(tok: String): Int {
                        // v, v/vt, v//vn, v/vt/vn
                        val comps = tok.split("/")
                        val vIdx = comps[0].toInt() - 1
                        val vnIdx =
                            if (comps.size >= 3 && comps[2].isNotEmpty()) comps[2].toInt() - 1
                            else -1
                        return getOrCreateVertex(vIdx, vnIdx)
                    }

                    // Triangulate quads to 2 triangles
                    val triIndices: IntArray = when (tokens.size) {
                        3 -> intArrayOf(
                            parseFaceVertex(tokens[0]),
                            parseFaceVertex(tokens[1]),
                            parseFaceVertex(tokens[2])
                        )
                        4 -> {
                            val i0 = parseFaceVertex(tokens[0])
                            val i1 = parseFaceVertex(tokens[1])
                            val i2 = parseFaceVertex(tokens[2])
                            val i3 = parseFaceVertex(tokens[3])
                            intArrayOf(
                                i0, i1, i2,
                                i0, i2, i3
                            )
                        }
                        else -> error("unreachable")
                    }

                    val target = if (currentMtl?.trim()?.equals("NOZZLE_GLOW", ignoreCase = true) == true) triGlow else triOpaque


                    // Add triangle indices
                    for (idx in triIndices) {
                        if (idx > Short.MAX_VALUE) error("Too many vertices for Short indices; use Int.")
                        target.add(idx.toShort())
                    }


                }

                "l" -> {
                    // Polyline or simple segments: indices reference original v's
                    val idxTokens = parts.drop(1)
                    if (idxTokens.size < 2) return@forEach

                    // Map OBJ v index -> our vertex index (create if necessary)
                    fun mapPosIndex(tok: String): Int {
                        val vIdx = tok.toInt() - 1
                        val existing = posToVertex[vIdx]
                        if (existing != null) return existing

                        // No vertex yet using this position? Make one (no normal).
                        val pos = srcPositions[vIdx]
                        outPositions.add(pos.x)
                        outPositions.add(pos.y)
                        outPositions.add(pos.z)
                        outNormals.add(0f)
                        outNormals.add(0f)
                        outNormals.add(0f)

                        val newIndex = indexMap.size
                        indexMap[Key(vIdx, -1)] = newIndex
                        posToVertex[vIdx] = newIndex
                        return newIndex
                    }

                    // Build edges for each consecutive pair along the line
                    var prev = mapPosIndex(idxTokens[0])
                    for (i in 1 until idxTokens.size) {
                        val curr = mapPosIndex(idxTokens[i])
                        addEdge(prev, curr)
                        prev = curr
                    }
                }


                else -> {
                    // ignore mtllib, usemtl, g, o, s, etc.
                }
            }
        }
    }

    // Final arrays

    val positionsArray = outPositions.toFloatArray()

    val normalsArray =
        if (srcNormals.isNotEmpty() && outNormals.any { it != 0f }) {
            outNormals.toFloatArray()
        } else {
            null
        }

    val lineIndexList = ArrayList<Short>(edgeSet.size * 2)
    edgeSet.forEach { e ->
        lineIndexList.add(e.a.toShort())
        lineIndexList.add(e.b.toShort())
    }
    val lineIndices = ShortArray(lineIndexList.size) { i -> lineIndexList[i] }

    val triIndicesOpaque = ShortArray(triOpaque.size) { i -> triOpaque[i] }
    val triIndicesGlow   = ShortArray(triGlow.size) { i -> triGlow[i] }

    return MeshData(
        positions = positionsArray,
        normals = normalsArray,
        triIndicesOpaque = triIndicesOpaque,
        triIndicesGlow = triIndicesGlow,
        lineIndices = lineIndices
    )
}