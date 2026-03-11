package com.jimjo.exodefender

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class CollisionInfo(
    var collided: Boolean = false,
    val normal: Vec3 = Vec3(),
    var actor: Actor? = null,

    var hasSupport: Boolean = false,
    val supportNormal: Vec3 = Vec3(),
    var supportActor: Actor? = null
)

class ShipCollisionSolver {
    private val tmpDisp = Vec3()
    private val tmpDir = Vec3()
    private val tmpN = Vec3()
    private val tmpBestN = Vec3()
    private val tmpLocalN = Vec3()
    private val tmpSupportN = Vec3()
    private val tmpLastN = Vec3()
    private val tmpSweepMin = Vec3()
    private val tmpSweepMax = Vec3()
    private val candidateTargets = mutableListOf<Actor>()


    /**
     * Continuous collision with sliding along AABB faces.
     * Mutates position/velocity. Returns true if any collision handling occurred this frame.
     *
     * Notes:
     * - Z is up in your world. To avoid fighting terrain, set horizontalOnly=true (default).
     *   That will zero the Z of the contact normal before sliding.
     */
    fun moveShipSlideOnCollide(
        position: Vec3,
        velocity: Vec3,
        dt: Float,
        radiusXY: Float,            // <---
        radiusZ: Float,             // <---
        epsilon: Float,
        queryEnemiesNearInto: (sweepMin: Vec3, sweepMax: Vec3, out: MutableList<Actor>) -> Unit,
        horizontalOnly: Boolean = false,   // <- important for Z-up worlds with terrain-following
        maxIters: Int = 4,                 // safety cap for edge/corner chains
        outCollisionInfo: CollisionInfo,
    ): Boolean {

        outCollisionInfo.collided = false
        outCollisionInfo.hasSupport = false
        outCollisionInfo.supportActor = null
        outCollisionInfo.supportNormal.set(0f, 0f, 0f)

        if (dt <= 0f) return false

        var collided = false
        var remaining = 1f   // we’ll consume [0,1] of the frame’s displacement
        var bestActor: Actor? = null
        var lastActor: Actor? = null
        var frameSupportActor: Actor? = null
        var frameSupportTopZ = 0f
        tmpSupportN.set(0f, 0f, 0f)
        tmpLastN.set(0f, 0f, 0f)   // add a scratch Vec3 tmpLastN



        // --- 0) Static depenetration once at frame start (resolves any start-inside states) ---
        candidateTargets.clear()

        // Query a small AABB around current position (not dependent on movement)
        tmpSweepMin.set(position.x - radiusXY, position.y - radiusXY, position.z - radiusZ)
        tmpSweepMax.set(position.x + radiusXY, position.y + radiusXY, position.z + radiusZ)
        queryEnemiesNearInto(tmpSweepMin, tmpSweepMax, candidateTargets)

        collided = staticDepenetrationWithCandidates(position, radiusXY, radiusZ, epsilon, candidateTargets, horizontalOnly) || collided

        // NOW it is safe to early out on zero velocity
        if (velocity.length2() == 0f) return collided

        var iter = 0
        while (iter < maxIters && remaining > 1e-4f) {
            // Displacement for the remaining slice of the frame
            tmpDisp.set(velocity).mulLocal(dt * remaining)
            val p0x = position.x
            val p0y = position.y
            val p0z = position.z
            val p1x = p0x + tmpDisp.x
            val p1y = p0y + tmpDisp.y
            val p1z = p0z + tmpDisp.z

            // Broad-phase
            tmpSweepMin.set(
                min(p0x, p1x) - radiusXY,
                min(p0y, p1y) - radiusXY,
                min(p0z, p1z) - radiusZ
            )
            tmpSweepMax.set(
                max(p0x, p1x) + radiusXY,
                max(p0y, p1y) + radiusXY,
                max(p0z, p1z) + radiusZ
            )

            candidateTargets.clear()
            queryEnemiesNearInto(tmpSweepMin, tmpSweepMax, candidateTargets)

            // No movement? done.
            if (tmpDisp.length2() <= 1e-12f) break

            // Ray dir for sweep
            tmpDir.set(p1x - p0x, p1y - p0y, p1z - p0z)

            // Find earliest hit among candidates
            var bestT = 1f
            var hit = false
            tmpN.set(0f, 0f, 0f)
            tmpBestN.set(0f, 0f, 0f) // you’ll need a scratch Vec3 tmpBestN

            for (a in candidateTargets) {

                if (!a.active) continue

                // identify enemies close to ship for "storm" attack
                if (a is EnemyActor && abs(a.position.z - position.z) < 15f) a.closeToShip = true

                if (a === frameSupportActor) {
                    // position.z is ship center; if it's at/above the top plane (with a small tolerance), skip
                    if (position.z >= frameSupportTopZ - 0.05f) {
                        continue
                    }
                }

                val bb = a as? BuildingBlockActor
                if (bb != null) {
                    // Broadphase already got us here via bb.instance.worldAabb.
                    // Narrowphase uses the true yawed box.


                    val realTopZ = bb.position.z + bb.halfExtents.z

                    val insideTopFootprint =
                        pointInsideYawedBoxFootprintXY(
                            p0x,
                            p0y,
                            bb.position,
                            bb.halfExtents,
                            bb.yawRad,
                            radiusXY
                        )

                    if (insideTopFootprint && p0z >= realTopZ - 0.05f) {
                        val t = 0f
                        if (t < bestT) {
                            bestT = t
                            hit = true
                            bestActor = a
                            tmpBestN.set(0f, 0f, +1f)
                        }
                        continue
                    }

                    val t = rayYawedBoxEnterT(
                        p0x, p0y, p0z,
                        tmpDir.x, tmpDir.y, tmpDir.z,
                        bb.position,
                        bb.halfExtents,
                        bb.yawRad,
                        radiusXY,
                        radiusZ,
                        tmpN
                    )
                    if (t >= 0f && t < bestT) {
                        bestT = t
                        hit = true
                        bestActor = a
                        tmpBestN.set(tmpN)
                    }
                    continue
                }

                val minx = a.instance.worldAabb.min.x - radiusXY
                val miny = a.instance.worldAabb.min.y - radiusXY
                val minz = a.instance.worldAabb.min.z - radiusZ
                val maxx = a.instance.worldAabb.max.x + radiusXY
                val maxy = a.instance.worldAabb.max.y + radiusXY
                val maxz = a.instance.worldAabb.max.z + radiusZ

                val inside =
                    p0x >= minx && p0x <= maxx &&
                            p0y >= miny && p0y <= maxy &&
                            p0z >= minz && p0z <= maxz

                if (inside) {
                    val t = 0f
                    if (t < bestT) {
                        bestT = t
                        hit = true
                        bestActor = a
                        tmpBestN.set(0f, 0f, +1f)
                    }
                    continue
                }

                val t = rayAabbEnterT(
                    p0x, p0y, p0z,
                    tmpDir.x, tmpDir.y, tmpDir.z,
                    minx, miny, minz,
                    maxx, maxy, maxz,
                    tmpN
                )
                if (t >= 0f && t < bestT) {
                    bestT = t
                    hit = true
                    bestActor = a
                    tmpBestN.set(tmpN)
                }
            }

            if (!hit) {
                // Free move for the remainder: p += disp
                position.mad(tmpDisp, 1f)
                break
            }

            val n = tmpBestN
            lastActor = bestActor
            tmpLastN.set(n)

            // If this hit is a "floor/support" hit, remember this actor as the support for this frame
            if (n.z > 0.95f) {
                frameSupportActor = bestActor
                // true (non-inflated) top plane:
                frameSupportTopZ = bestActor!!.instance.worldAabb.max.z
            }

            // Slide: remove only the *into-surface* component from velocity
            val vn = velocity.dot(n)
            if (vn < 0f) { // moving into the surface
                velocity.mad(n, -vn) // v = v - n*vn
            }

            // back off a bit
            position.mad(n, epsilon)

            // Move to contact
            position.mad(tmpDisp, bestT)

            // Record a "support" collision: something mostly upward-facing that we are pushing into
            if (n.z > 0.95f && vn < 0f) {
                tmpSupportN.set(n)

                outCollisionInfo.hasSupport = true
                outCollisionInfo.supportNormal.set(n)
                outCollisionInfo.supportActor = bestActor
            }

            collided = true

            // 5) Consume the time we’ve used and iterate with remaining
            val MIN_T = 1e-3f          // try 1e-3, if too aggressive try 1e-4
            val tUsed = max(bestT, MIN_T)
            remaining *= (1f - tUsed)

            // If velocity became tiny, we’re done
            if (velocity.length2() < 1e-8f) break

            iter++
        }

        if (collided) {
            outCollisionInfo.collided = true
            outCollisionInfo.normal.set(tmpLastN)     // last collision normal (or whatever you choose)
            outCollisionInfo.actor = lastActor
        }

        return collided
    }
    // ---------- Helpers ----------

    /** Resolve all overlaps (point vs expanded shape) with smallest-penetration-first MTV. */
    private fun staticDepenetrationWithCandidates(
        position: Vec3,
        radiusXY: Float,
        radiusZ: Float,
        epsilon: Float,
        candidates: List<Actor>,
        horizontalOnly: Boolean
    ): Boolean {
        var moved = false
        val MAX_ITERS = 1
        var it = 0

        while (it < MAX_ITERS) {
            var bestPen = Float.POSITIVE_INFINITY
            var nx = 0f
            var ny = 0f
            var nz = 0f

            val px = position.x
            val py = position.y
            val pz = position.z

            var found = false

            for (a in candidates) {
                if (!a.active) continue

                val bb = a as? BuildingBlockActor
                if (bb != null) {
                    val pen = pointInsideYawedExpandedBoxPen(
                        px, py, pz,
                        bb.position,
                        bb.halfExtents,
                        bb.yawRad,
                        radiusXY,
                        radiusZ,
                        includeZ = !horizontalOnly,
                        outWorldN = tmpN
                    )

                    if (pen < 0f) continue
                    found = true

                    // Preserve your "above the true top face = support" behavior
                    if (!horizontalOnly) {
                        val realTopZ = bb.position.z + bb.halfExtents.z
                        if (pz >= realTopZ - 0.05f) {
                            val expandedTopZ = realTopZ + radiusZ
                            val dzMax = expandedTopZ - pz
                            if (dzMax < bestPen) {
                                bestPen = dzMax
                                nx = 0f
                                ny = 0f
                                nz = +1f
                            }
                            continue
                        }
                    }

                    if (pen < bestPen) {
                        bestPen = pen
                        nx = tmpN.x
                        ny = tmpN.y
                        nz = tmpN.z
                    }
                    continue
                }

                val minx = a.instance.worldAabb.min.x - radiusXY
                val miny = a.instance.worldAabb.min.y - radiusXY
                val minz = a.instance.worldAabb.min.z - radiusZ
                val maxx = a.instance.worldAabb.max.x + radiusXY
                val maxy = a.instance.worldAabb.max.y + radiusXY
                val maxz = a.instance.worldAabb.max.z + radiusZ

                if (px < minx || px > maxx || py < miny || py > maxy || pz < minz || pz > maxz) continue
                found = true

                val dxMin = px - minx
                val dxMax = maxx - px
                val dyMin = py - miny
                val dyMax = maxy - py
                val dzMin = pz - minz
                val dzMax = maxz - pz

                var pen = dxMin
                var tx = -1f
                var ty = 0f
                var tz = 0f

                fun pick(p: Float, xn: Float, yn: Float, zn: Float) {
                    if (p < pen) {
                        pen = p
                        tx = xn
                        ty = yn
                        tz = zn
                    }
                }

                pick(dxMax, +1f, 0f, 0f)
                pick(dyMin, 0f, -1f, 0f)
                pick(dyMax, 0f, +1f, 0f)

                if (!horizontalOnly) {
                    pick(dzMin, 0f, 0f, -1f)
                    pick(dzMax, 0f, 0f, +1f)
                }

                if (pen < bestPen) {
                    bestPen = pen
                    nx = tx
                    ny = ty
                    nz = tz
                }
            }

            if (!found) break

            if (horizontalOnly) {
                nz = 0f
                val l2 = nx * nx + ny * ny
                if (l2 > 0f) {
                    val inv = 1f / sqrt(l2)
                    nx *= inv
                    ny *= inv
                }
            }

            position.x += nx * (bestPen + epsilon)
            position.y += ny * (bestPen + epsilon)
            position.z += nz * (bestPen + epsilon)

            moved = true
            it++
        }

        return moved
    }

    /** Slab method: entry t in [0,1], -1 if none. Writes normal to outN. */
    private fun rayAabbEnterT(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        minx: Float, miny: Float, minz: Float,
        maxx: Float, maxy: Float, maxz: Float,
        outN: Vec3
    ): Float {
        if (dx == 0f && dy == 0f && dz == 0f) return -1f
        var tmin = 0f
        var tmax = 1f
        var nx = 0f;
        var ny = 0f;
        var nz = 0f

        // X
        if (dx == 0f) {
            if (ox < minx || ox > maxx) return -1f
        } else {
            val inv = 1f / dx
            var t0 = (minx - ox) * inv
            var t1 = (maxx - ox) * inv
            var nEnter = -1f  // minX → -X
            if (t0 > t1) {
                val tmp = t0; t0 = t1; t1 = tmp; nEnter = +1f
            } // maxX → +X
            if (t0 > tmin) {
                tmin = t0; nx = nEnter; ny = 0f; nz = 0f
            }
            if (t1 < tmax) tmax = t1
            if (tmin > tmax) return -1f
        }
        // Y
        if (dy == 0f) {
            if (oy < miny || oy > maxy) return -1f
        } else {
            val inv = 1f / dy
            var t0 = (miny - oy) * inv
            var t1 = (maxy - oy) * inv
            var nEnter = -1f  // minY → -Y
            if (t0 > t1) {
                val tmp = t0; t0 = t1; t1 = tmp; nEnter = +1f
            } // maxY → +Y
            if (t0 > tmin) {
                tmin = t0; nx = 0f; ny = nEnter; nz = 0f
            }
            if (t1 < tmax) tmax = t1
            if (tmin > tmax) return -1f
        }
        // Z
        if (dz == 0f) {
            if (oz < minz || oz > maxz) return -1f
        } else {
            val inv = 1f / dz
            var t0 = (minz - oz) * inv
            var t1 = (maxz - oz) * inv
            var nEnter = -1f  // minZ → -Z
            if (t0 > t1) {
                val tmp = t0; t0 = t1; t1 = tmp; nEnter = +1f
            } // maxZ → +Z
            if (t0 > tmin) {
                tmin = t0; nx = 0f; ny = 0f; nz = nEnter
            }
            if (t1 < tmax) tmax = t1
            if (tmin > tmax) return -1f
        }

        val inside =
            ox >= minx && ox <= maxx &&
                    oy >= miny && oy <= maxy &&
                    oz >= minz && oz <= maxz

        if (inside) {
            // pick the smallest penetration axis, like your static depenetration
            val dxMin = ox - minx
            val dxMax = maxx - ox
            val dyMin = oy - miny
            val dyMax = maxy - oy
            val dzMin = oz - minz
            val dzMax = maxz - oz

            var pen = dxMin; nx = -1f; ny = 0f; nz = 0f
            fun pick(p: Float, x: Float, y: Float, z: Float) {
                if (p < pen) { pen = p; nx = x; ny = y; nz = z }
            }
            pick(dxMax, +1f, 0f, 0f)
            pick(dyMin, 0f, -1f, 0f)
            pick(dyMax, 0f, +1f, 0f)
            pick(dzMin, 0f, 0f, -1f)
            pick(dzMax, 0f, 0f, +1f)

            outN.set(nx, ny, nz)
            return 0f
        }

        if (tmin < 0f || tmin > 1f) return -1f
        outN.set(nx, ny, nz)
        return tmin
    }

    /**
     * If point is inside the yawed box expanded by ship radii, returns the minimum
     * penetration depth and writes the depenetration normal in WORLD space to outWorldN.
     * Returns -1 if the point is outside.
     */
    private fun pointInsideYawedExpandedBoxPen(
        px: Float, py: Float, pz: Float,
        boxCenter: Vec3,
        halfExtents: Vec3,
        yawRad: Double,
        radiusXY: Float,
        radiusZ: Float,
        includeZ: Boolean,
        outWorldN: Vec3
    ): Float {
        val c = kotlin.math.cos(yawRad).toFloat()
        val s = kotlin.math.sin(yawRad).toFloat()

        // point relative to box center
        val rx = px - boxCenter.x
        val ry = py - boxCenter.y
        val rz = pz - boxCenter.z

        // point in box-local space
        val lx = c * rx - s * ry
        val ly = s * rx + c * ry
        val lz = rz

        val minx = -halfExtents.x - radiusXY
        val miny = -halfExtents.y - radiusXY
        val minz = -halfExtents.z - radiusZ
        val maxx = +halfExtents.x + radiusXY
        val maxy = +halfExtents.y + radiusXY
        val maxz = +halfExtents.z + radiusZ

        if (lx < minx || lx > maxx || ly < miny || ly > maxy || lz < minz || lz > maxz) {
            return -1f
        }

        val dxMin = lx - minx
        val dxMax = maxx - lx
        val dyMin = ly - miny
        val dyMax = maxy - ly
        val dzMin = lz - minz
        val dzMax = maxz - lz

        var pen = dxMin
        var nx = -1f
        var ny = 0f
        var nz = 0f

        fun pick(p: Float, x: Float, y: Float, z: Float) {
            if (p < pen) {
                pen = p
                nx = x
                ny = y
                nz = z
            }
        }

        pick(dxMax, +1f, 0f, 0f)
        pick(dyMin, 0f, -1f, 0f)
        pick(dyMax, 0f, +1f, 0f)
        if (includeZ) {
            pick(dzMin, 0f, 0f, -1f)
            pick(dzMax, 0f, 0f, +1f)
        }

        // rotate local normal back to world space
        val cw = kotlin.math.cos(-yawRad).toFloat()
        val sw = kotlin.math.sin(-yawRad).toFloat()

        outWorldN.set(
            cw * nx - sw * ny,
            sw * nx + cw * ny,
            nz
        )

        return pen
    }

    /** Ray vs yawed box expanded by ship footprint radii. Returns entry t in [0,1], -1 if none. */
    private fun rayYawedBoxEnterT(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        boxCenter: Vec3,
        halfExtents: Vec3,
        yawRad: Double,
        radiusXY: Float,
        radiusZ: Float,
        outWorldN: Vec3
    ): Float {
        val c = kotlin.math.cos(yawRad).toFloat()
        val s = kotlin.math.sin(yawRad).toFloat()

        // Ray origin relative to box center
        val rx = ox - boxCenter.x
        val ry = oy - boxCenter.y
        val rz = oz - boxCenter.z

        // Transform origin into box-local space
        val lox = c * rx - s * ry
        val loy = s * rx + c * ry
        val loz = rz

        // Transform direction into box-local space
        val ldx = c * dx - s * dy
        val ldy = s * dx + c * dy
        val ldz = dz

        // Expanded local AABB
        val minx = -halfExtents.x - radiusXY
        val miny = -halfExtents.y - radiusXY
        val minz = -halfExtents.z - radiusZ
        val maxx = +halfExtents.x + radiusXY
        val maxy = +halfExtents.y + radiusXY
        val maxz = +halfExtents.z + radiusZ

        val t = rayAabbEnterT(
            lox, loy, loz,
            ldx, ldy, ldz,
            minx, miny, minz,
            maxx, maxy, maxz,
            tmpLocalN
        )
        if (t < 0f) return -1f

        // Rotate local hit normal back to world space
        val cw = kotlin.math.cos(-yawRad).toFloat()
        val sw = kotlin.math.sin(-yawRad).toFloat()

        outWorldN.set(
            cw * tmpLocalN.x - sw * tmpLocalN.y,
            sw * tmpLocalN.x + cw * tmpLocalN.y,
            tmpLocalN.z
        )
        return t
    }

    /** True if point projects inside the yawed box footprint in XY, expanded by radiusXY. */
    private fun pointInsideYawedBoxFootprintXY(
        px: Float,
        py: Float,
        boxCenter: Vec3,
        halfExtents: Vec3,
        yawRad: Double,
        radiusXY: Float
    ): Boolean {
        val c = kotlin.math.cos(yawRad).toFloat()
        val s = kotlin.math.sin(yawRad).toFloat()

        val rx = px - boxCenter.x
        val ry = py - boxCenter.y

        val lx = c * rx - s * ry
        val ly = s * rx + c * ry

        return kotlin.math.abs(lx) <= halfExtents.x + radiusXY &&
                kotlin.math.abs(ly) <= halfExtents.y + radiusXY
    }

}