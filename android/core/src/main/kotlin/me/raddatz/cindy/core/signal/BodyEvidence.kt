package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.SignalConfig

/**
 * Plausibility check for brightness reps: a dip in brightness only counts when a body signal
 * moved in the same cycle, so a cloud or a lamp cannot count a squat.
 *
 * Evidence is any of: the face area grew by `evidenceFaceAreaRatio` over its rest level (looking
 * down), the shoulder width grew by `evidenceShoulderRatio`, or face or pose were seen and then
 * vanished for `evidenceLostFrames` consecutive frames (looking ahead, the floor camera loses both
 * at the bottom of the squat). Frames count at the reference frame rate ([FrameTiming.covers]),
 * and the rest levels follow with a per-reference-frame factor, so both mean the same time at 5 fps.
 */
class BodyEvidence(val config: SignalConfig = SignalConfig.default) {
    /** Whether any observation was fed; replays of a bare scalar have none and skip the check. */
    var hasObservations: Boolean = false
        private set
    private var restFaceArea: Float? = null
    private var restShoulderWidth: Float? = null

    private var inCycle = false
    private var cycleRestFaceArea: Float? = null
    private var cycleRestShoulderWidth: Float? = null
    private var maxFaceArea = 0f
    private var maxShoulderWidth = 0f
    private var face = Presence()
    private var pose = Presence()

    private var lastTimestamp: Double? = null

    /** Seen-then-missing bookkeeping for one detector. */
    private class Presence(var seen: Boolean = false) {
        var missingRun = 0
        var missingSince = 0.0

        /** Whether a missing run in this cycle covered `evidenceLostFrames`. */
        var vanished = false

        fun update(present: Boolean, timestamp: Double, lostFrames: Int) {
            if (present) {
                seen = true
                missingRun = 0
            } else if (seen) {
                if (missingRun == 0) missingSince = timestamp
                missingRun += 1
                if (FrameTiming.covers(lostFrames, missingRun, timestamp - missingSince, FrameTiming.minLostSamples)) {
                    vanished = true
                }
            }
        }
    }

    /** Per reference frame. */
    private val restAlpha = 0.1f

    /** Feeds one frame; [cycleActive] is whether the rep detector is inside a cycle. */
    fun update(observation: FrameObservation, cycleActive: Boolean) {
        hasObservations = true
        val frameInterval = lastTimestamp?.let { observation.timestamp - it }
        lastTimestamp = observation.timestamp
        val alpha = FrameTiming.alpha(restAlpha, frameInterval)
        val faceArea = observation.face?.area
        val shoulders = observation.pose?.shoulderWidthSample
        val shoulderWidth = shoulders?.takeIf { it.confidence >= config.evidencePoseMinConfidence }?.value

        if (!cycleActive) {
            inCycle = false
            if (faceArea != null) {
                restFaceArea = restFaceArea?.let { it + alpha * (faceArea - it) } ?: faceArea
            }
            if (shoulderWidth != null) {
                restShoulderWidth = restShoulderWidth?.let { it + alpha * (shoulderWidth - it) } ?: shoulderWidth
            }
            return
        }
        if (!inCycle) {
            inCycle = true
            cycleRestFaceArea = restFaceArea
            cycleRestShoulderWidth = restShoulderWidth
            maxFaceArea = 0f
            maxShoulderWidth = 0f
            face = Presence(seen = restFaceArea != null)
            pose = Presence(seen = restShoulderWidth != null)
        }
        if (faceArea != null) maxFaceArea = maxOf(maxFaceArea, faceArea)
        if (shoulderWidth != null) maxShoulderWidth = maxOf(maxShoulderWidth, shoulderWidth)
        val lostFrames = maxOf(config.evidenceLostFrames, 1)
        face.update(faceArea != null, observation.timestamp, lostFrames)
        pose.update(shoulderWidth != null, observation.timestamp, lostFrames)
    }

    /** Whether the current cycle showed a body moving. */
    val supportsCycle: Boolean
        get() {
            val restFace = cycleRestFaceArea
            if (restFace != null && restFace > 0 && maxFaceArea >= config.evidenceFaceAreaRatio * restFace) return true
            val restShoulders = cycleRestShoulderWidth
            if (restShoulders != null && restShoulders > 0 &&
                maxShoulderWidth >= config.evidenceShoulderRatio * restShoulders
            ) {
                return true
            }
            return face.vanished || pose.vanished
        }
}
