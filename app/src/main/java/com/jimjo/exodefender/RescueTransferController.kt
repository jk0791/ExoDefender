package com.jimjo.exodefender

class RescueTransferController(
    private val ship: ShipActor
) {
    private var lastCluster: CivilianClusterVisual? = null
    private var lastCount: Int = -1

    // optional: for debugging
    // var debug = false

    fun reset() {
        lastCluster = null
        lastCount = -1
    }

    fun update(
        padConfirmed: Boolean,
        restLatched: Boolean,
        lastPadBlock: BuildingBlockActor?,
        timeMs: Int
    ): Int {
        if (!(padConfirmed && restLatched)) {
            // Freeze behavior: no new requests while not latched.
            // Also, drop tracking so we don't mis-attribute a count change to an old pad.
            lastCluster = null
            lastCount = -1
            return 0
        }

        val cluster = lastPadBlock?.civilianCluster

        if (cluster == null) return 0

        var deltaCivilians = 0

        // Detect transfer completion by observing count changes.
        if (cluster === lastCluster) {
            val c = cluster.count
            if (lastCount >= 0 && c != lastCount) {
                // One transfer finished.
                if (cluster.isThreatPad()) {
                    // Threat pad: count went DOWN by 1 (boarding)
                    if (c < lastCount) {
                        val boarded = lastCount - c
                        ship.carryingCivilians =
                            (ship.carryingCivilians + boarded).coerceAtMost(ship.carryingCapacity)
                        deltaCivilians += boarded
                    }
                } else if (cluster.isSafePad()) {
                    // Safe pad: count went UP by 1 (disembarking)
                    if (c > lastCount) {
                        val disembarked = (c - lastCount)
                        ship.carryingCivilians =
                            (ship.carryingCivilians - disembarked).coerceAtLeast(0)
                        deltaCivilians -= disembarked
                    }
                }
            }
            lastCount = c
        } else {
            // New pad/cluster: initialize tracking
            lastCluster = cluster
            lastCount = cluster.count
        }

        // If cluster is mid-animation, do nothing this frame.
        if (!cluster.isIdle()) return 0

        // Decide the next one-at-a-time action.
        if (cluster.isThreatPad()) {
            // board until ship full or pad empty
            if (ship.carryingCivilians < ship.carryingCapacity && cluster.count > 0) {
                cluster.requestCount(cluster.count - 1, timeMs)   // one civilian boards
                // we will update ship.carryingCivilians when count change is observed (animation end)
            }
        } else if (cluster.isSafePad()) {
            // disembark all (one at a time, paced)
            if (ship.carryingCivilians > 0) {
                cluster.requestCount(cluster.count + 1, timeMs)   // one civilian disembarks to safe pad
                // we will decrement ship.carryingCivilians when count change is observed
            }
        }
        return deltaCivilians
    }
}
