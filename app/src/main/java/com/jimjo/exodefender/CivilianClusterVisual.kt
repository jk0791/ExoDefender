package com.jimjo.exodefender

class CivilianClusterVisual(
    private val padInstance: ModelInstance,      // landing pad's instance (for world pos + yaw)
    private val civilianModel: Model,
    private val waitingAreaLocal: Vec3,          // manual authored local offset (can be below)
    val initialCount: Int,
    val maxSlots: Int,
    val structureTemplateId: Int,
    seed: Int,
    private val wire: WireBatchRenderer,
) : VisualObject {


    override var active = true

    var count: Int = initialCount
        set(value) { field = value.coerceIn(0, maxSlots) }

    // Preallocated instances
    private val civilians: Array<ModelInstance> =
        Array(maxSlots) { ModelInstance(civilianModel, isStatic = false) }

    // Slot data (XY offsets and yaw jitter), precomputed and stable
    private val offX = FloatArray(maxSlots)
    private val offY = FloatArray(maxSlots)
    private val yawJitter = FloatArray(maxSlots)

    // temp
    private val tmp = Vec3()
    private val anchorWorld = Vec3()

    private enum class TransferMode { NONE, BOARDING, DISEMBARKING }

    private var mode: TransferMode = TransferMode.NONE
    private var targetCount: Int = count

    private var transferActive = false
    private var activeStartMs = 0
    private var nextStartMs = 0

    // which slot is currently animating
    private var activeIndex = -1

    // Tuning knobs (start here and tweak by eye)
    private val TRANSFER_INTERVAL_MS = 220        // beat: start a transfer every N ms
    private val TRANSFER_ANIM_MS = 320            // visual duration for each transfer
    private val LIFT_M = 0.35f                    // vertical lift (meters)
    private val SHRINK_TO = 0.65f                 // scale target during transfer

    // Optional: where the glow column appears.
// If null -> use the active civilian's position.
    private val useFixedTransferPoint = false
    private val transferPointLocal = Vec3(0f, 0f, 0f) // local to waiting area anchor; author later if desired

    init {
        // Simple ring-ish layout (cheap + readable)
        // You can tweak spacing later.
        val rng = java.util.Random(seed.toLong())
        val spacing = 1.1f // meters between civilians
        var i = 0
        var ring = 0
        while (i < maxSlots) {
            val n = if (ring == 0) 1 else ring * 6
            val r = ring * spacing
            for (k in 0 until n) {
                if (i >= maxSlots) break
                val a = (k.toFloat() / n.toFloat()) * (Math.PI.toFloat() * 2f)

                // ring point + small jitter
                offX[i] = (kotlin.math.cos(a) * r) + (rng.nextFloat() - 0.5f) * spacing * 0.25f
                offY[i] = (kotlin.math.sin(a) * r) + (rng.nextFloat() - 0.5f) * spacing * 0.25f

                // yaw-only variety: roughly outward + jitter
                val outward = kotlin.math.atan2(offY[i], offX[i]).toDouble()
                yawJitter[i] = (outward + (rng.nextFloat() - 0.5f) * 0.8f).toFloat()

                i++
            }
            ring++
        }

        // optional scale variety (if you like)
        // civilians.forEach { it.setScale(0.8f, 0.8f, 0.8f) }
    }

    fun isThreatPad(): Boolean = initialCount > 0
    fun isSafePad(): Boolean = initialCount == 0
    fun isIdle(): Boolean = !transferActive && mode == TransferMode.NONE

    fun resetToInitial() {
//        count = initialCount
        active = true
        setCountImmediateForReplay(initialCount)
    }

    fun requestCount(newCount: Int, timeMs: Int) {
        val clamped = newCount.coerceIn(0, maxSlots)
        targetCount = clamped

        if (targetCount < count) {
            mode = TransferMode.BOARDING
            // start immediately
            nextStartMs = timeMs
        } else if (targetCount > count) {
            mode = TransferMode.DISEMBARKING
            nextStartMs = timeMs
        } else {
            mode = TransferMode.NONE
            transferActive = false
            activeIndex = -1
        }
    }

    override fun draw(vpMatrix: FloatArray, timeMs: Int) {

        // --- anchorWorld = padPos + rotate(padYaw, waitingAreaLocal.xy), z fixed ---
        rotateXY(waitingAreaLocal.x, waitingAreaLocal.y, -padInstance.yawRad, tmp)

        anchorWorld.x = padInstance.position.x + tmp.x
        anchorWorld.y = padInstance.position.y + tmp.y
        anchorWorld.z = padInstance.position.z + waitingAreaLocal.z

        val baseZ = anchorWorld.z

        // --- Transfer scheduling: start a new transfer on the beat (if none active) ---
        if (!transferActive && mode != TransferMode.NONE && timeMs >= nextStartMs) {
            when (mode) {
                TransferMode.BOARDING -> {
                    if (count > targetCount && count > 0) {
                        transferActive = true
                        activeStartMs = timeMs
                        activeIndex = count - 1          // last visible leaves
                        nextStartMs = timeMs + TRANSFER_INTERVAL_MS
                    } else {
                        mode = TransferMode.NONE
                    }
                }
                TransferMode.DISEMBARKING -> {
                    if (count < targetCount && count < maxSlots) {
                        transferActive = true
                        activeStartMs = timeMs
                        activeIndex = count              // next slot arrives (not yet in visible range)
                        nextStartMs = timeMs + TRANSFER_INTERVAL_MS
                    } else {
                        mode = TransferMode.NONE
                    }
                }
                else -> Unit
            }
        }

        // --- Compute active animation parameters (only for the one currently transferring) ---
        var activeAlpha = 1f
        var activeScale = 1f
        var activeZOffset = 0f
        var glow01 = 0f

        if (transferActive) {
            val t = ((timeMs - activeStartMs).toFloat() / TRANSFER_ANIM_MS.toFloat()).coerceIn(0f, 1f)
            // easeOutQuad
            val easeOut = 1f - (1f - t) * (1f - t)

            when (mode) {
                TransferMode.BOARDING -> {
                    activeAlpha = 1f - t
                    activeScale = lerp(1f, SHRINK_TO, easeOut)
                    activeZOffset = lerp(0f, LIFT_M, easeOut)
                }
                TransferMode.DISEMBARKING -> {
                    activeAlpha = t
                    activeScale = lerp(SHRINK_TO, 1f, easeOut)
                    activeZOffset = lerp(LIFT_M, 0f, easeOut)
                }
                else -> Unit
            }

            // glow pulse (0..1..0)
            glow01 = kotlin.math.sin((Math.PI * t).toFloat()).coerceIn(0f, 1f)

            // --- Finish transfer ---
            if (t >= 1f) {
                when (mode) {
                    TransferMode.BOARDING -> count = (count - 1).coerceAtLeast(targetCount)
                    TransferMode.DISEMBARKING -> count = (count + 1).coerceAtMost(targetCount)
                    else -> Unit
                }
                transferActive = false
                activeIndex = -1

                if (count == targetCount) mode = TransferMode.NONE
            }
        }

        // --- Draw civilians ---
        // We draw [0 until count] always.
        // If disembarking and activeIndex == count (the new one), we also draw that one during the anim.
        val drawExtraIncoming = transferActive && mode == TransferMode.DISEMBARKING && activeIndex == count

        val n = if (drawExtraIncoming) count + 1 else count
        for (i in 0 until n) {
            val inst = civilians[i]

            val isActive = transferActive && i == activeIndex

            val x = anchorWorld.x + offX[i]
            val y = anchorWorld.y + offY[i]
            val z = baseZ + (if (isActive) activeZOffset else 0f)

            inst.setPosition(x, y, z)
            inst.setDirection(yawRad = yawJitter[i].toDouble(), pitchRad = 0.0, rollRad = 0.0)

            if (isActive) {
                // scale the one transferring
                inst.setScale(activeScale, activeScale, activeScale)

                // alpha: easiest is to temporarily adjust line alpha on the batch renderer
                // (assuming you’re okay with civilians sharing a color)
                val prev = wire.lineColor
                wire.setLineColor(prev[0], prev[1], prev[2], activeAlpha)

                wire.drawInstance(vpMatrix, inst)

                wire.setLineColor(prev[0], prev[1], prev[2], prev[3])
                inst.setScale(1f, 1f, 1f)
            } else {
                wire.drawInstance(vpMatrix, inst)
            }
        }

        // --- Draw the vertical glow line for the active transfer (optional but recommended) ---
        if (transferActive) {
            val gx: Float
            val gy: Float
            val gz: Float

            if (useFixedTransferPoint) {
                // transfer point relative to anchorWorld (same Z plane convention)
                gx = anchorWorld.x + transferPointLocal.x
                gy = anchorWorld.y + transferPointLocal.y
                gz = baseZ + transferPointLocal.z
            } else {
                // glow at the active civilian position
                val i = activeIndex.coerceIn(0, maxSlots - 1)
                gx = anchorWorld.x + offX[i]
                gy = anchorWorld.y + offY[i]
                gz = baseZ
            }

            val height = lerp(0.3f, 2.2f, glow01)  // pops up as it glows
            val a = (0.15f + 0.85f * glow01).coerceIn(0f, 1f)

            wire.drawVerticalGlowLineWorld(
                vpMatrix = vpMatrix,
                x = gx,
                y = gy,
                z0 = gz,
                z1 = gz + height,
                r = 1f, g = 0.9f, b = 0.6f,
                a = a
            )
        }
    }

    fun setCountImmediateForReplay(newCount: Int) {
        val clamped = newCount.coerceIn(0, maxSlots)

        // If already correct and idle, nothing to do.
        if (count == clamped && !transferActive && mode == TransferMode.NONE && targetCount == clamped) return

        // Cancel any pending/active transfer animation.
        transferActive = false
        mode = TransferMode.NONE
        activeIndex = -1

        // Snap state.
        count = clamped
        targetCount = clamped

        // Prevent the scheduler from immediately starting something leftover.
        // (Not strictly necessary once mode=NONE, but keeps invariants tight.)
        activeStartMs = 0
        nextStartMs = 0
    }
}
