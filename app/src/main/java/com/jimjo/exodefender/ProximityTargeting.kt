package com.jimjo.exodefender

class ProximityTargeting(
    private val zWeight: Float = 0.25f,
    private val retargetMs: Int = 250
) {
    private lateinit var actors: List<Actor>

    // indices of actors (into actors list)
    private lateinit var groundEnemyIdx: IntArray
    private lateinit var groundFriendlyIdx: IntArray
    private var groundEnemyCount = 0
    private var groundFriendlyCount = 0

    // For each actor index: precomputed sorted friendlies (only valid for GroundEnemyActor)
    private lateinit var sortedFriendliesForGroundEnemy: Array<IntArray?>

    // Dynamic caching: per actor index
    private lateinit var cachedTargetIdx: IntArray
    private lateinit var nextEvalAtMs: IntArray

    fun build(actors: List<Actor>) {
        this.actors = actors

        val n = actors.size
        groundEnemyIdx = IntArray(n)
        groundFriendlyIdx = IntArray(n)

        sortedFriendliesForGroundEnemy = arrayOfNulls(n)

        cachedTargetIdx = IntArray(n) { -1 }
        nextEvalAtMs = IntArray(n) { 0 }

        // collect ground enemy / ground friendly indices
        groundEnemyCount = 0
        groundFriendlyCount = 0

        for (i in 0 until n) {
            val a = actors[i]
            if (!a.active) continue

            when (a) {
                is GroundEnemyActor -> groundEnemyIdx[groundEnemyCount++] = i
                is GroundFriendlyActor -> groundFriendlyIdx[groundFriendlyCount++] = i
            }
        }

        // precompute: for each ground enemy, sort all ground friendlies by distance
        for (k in 0 until groundEnemyCount) {
            val ei = groundEnemyIdx[k]
            sortedFriendliesForGroundEnemy[ei] = buildSortedFriendliesForEnemy(ei)
        }
    }

    /**
     * Returns nearest opposing friendly (currently GroundFriendlyActor) for an enemy.
     *
     * enemyIndex should be enemy.actorIndex (so O(1))
     */
    fun nearestFriendlyForEnemy(enemy: EnemyActor, enemyIndex: Int, timeMs: Int): FriendlyActor? {
        if (!enemy.active) return null

        // Ground enemy: use precomputed sorted list
        if (enemy is GroundEnemyActor) {
            val sorted = sortedFriendliesForGroundEnemy[enemyIndex] ?: return null
            for (j in sorted.indices) {
                val fi = sorted[j]
                val f = actors[fi]
                if (f.active) return f as FriendlyActor
            }
            return null
        }

        // Flying (dynamic): only rescan occasionally
        if (timeMs < nextEvalAtMs[enemyIndex]) {
            val cached = cachedTargetIdx[enemyIndex]
            if (cached >= 0) {
                val t = actors[cached]
                if (t.active) return t as FriendlyActor
            }
        }

        val bestIdx = scanNearestFriendlyIndex(enemy)
        cachedTargetIdx[enemyIndex] = bestIdx
        nextEvalAtMs[enemyIndex] = timeMs + retargetMs

        return if (bestIdx >= 0) actors[bestIdx] as FriendlyActor else null
    }

    private fun scanNearestFriendlyIndex(enemy: EnemyActor): Int {
        var bestIdx = -1
        var bestD2 = Float.POSITIVE_INFINITY

        val ePos = enemy.position

        for (j in 0 until groundFriendlyCount) {
            val fi = groundFriendlyIdx[j]
            val f = actors[fi]
            if (!f.active) continue

            val d2 = dist2Weighted(ePos, f.position)
            if (d2 < bestD2) {
                bestD2 = d2
                bestIdx = fi
            }
        }
        return bestIdx
    }

    private fun buildSortedFriendliesForEnemy(enemyIndex: Int): IntArray {
        val out = IntArray(groundFriendlyCount)
        for (i in 0 until groundFriendlyCount) out[i] = groundFriendlyIdx[i]

        val ePos = actors[enemyIndex].position

        // insertion sort by distance
        for (i in 1 until out.size) {
            val key = out[i]
            val keyD2 = dist2Weighted(ePos, actors[key].position)

            var j = i - 1
            while (j >= 0) {
                val cur = out[j]
                val curD2 = dist2Weighted(ePos, actors[cur].position)
                if (curD2 <= keyD2) break
                out[j + 1] = out[j]
                j--
            }
            out[j + 1] = key
        }
        return out
    }

    private inline fun dist2Weighted(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx*dx + dy*dy + (dz*dz) * zWeight
    }
}

