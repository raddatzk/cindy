package me.raddatz.cindy.camera

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the frame time goes, averaged over a few seconds. Shown on the debug screen and logged
 * under the `CindyPerf` tag, so a slow phone can be measured without a debugger.
 *
 * Detector times are per run, not per frame: an evidence-only detector runs a few times a second.
 */
data class VisionStats(
    /** Frames that made it through the chain per second (the camera drops the rest). */
    val framesPerSecond: Double,
    /** Whole processing time per frame on the frame thread. */
    val frameMillis: Double,
    /** YUV → RGB per frame, both threads together (zero when no detector ran). */
    val rgbMillis: Double,
    val faceMillis: Double,
    val faceRunsPerSecond: Double,
    val poseMillis: Double,
    val poseRunsPerSecond: Double,
) {
    fun summary(): String = String.format(
        Locale.ROOT,
        "%.1f fps · frame %.0f ms · rgb %.0f ms · face %.0f ms × %.1f/s · pose %.0f ms × %.1f/s",
        framesPerSecond, frameMillis, rgbMillis, faceMillis, faceRunsPerSecond, poseMillis, poseRunsPerSecond,
    )
}

/**
 * Collects timings from the frame thread and the evidence thread, and closes a window every few
 * seconds on the frame thread.
 */
internal class VisionStatsAccumulator(private val windowSeconds: Double = 3.0) {
    private var windowStart = Double.NaN
    private var frames = 0
    private var frameNanos = 0L
    private val rgbNanos = AtomicLong()
    private val faceNanos = AtomicLong()
    private val faceRuns = AtomicInteger()
    private val poseNanos = AtomicLong()
    private val poseRuns = AtomicInteger()

    fun rgb(nanos: Long) {
        rgbNanos.addAndGet(nanos)
    }

    fun face(nanos: Long) {
        faceNanos.addAndGet(nanos)
        faceRuns.incrementAndGet()
    }

    fun pose(nanos: Long) {
        poseNanos.addAndGet(nanos)
        poseRuns.incrementAndGet()
    }

    /** Ends a frame (frame thread only); returns the stats when this frame closed a window. */
    fun frame(timestampSeconds: Double, nanos: Long): VisionStats? {
        if (windowStart.isNaN()) windowStart = timestampSeconds
        frames += 1
        frameNanos += nanos
        val elapsed = timestampSeconds - windowStart
        if (elapsed < windowSeconds || frames < 2) return null
        val faceCount = faceRuns.getAndSet(0)
        val faceTotal = faceNanos.getAndSet(0)
        val poseCount = poseRuns.getAndSet(0)
        val poseTotal = poseNanos.getAndSet(0)
        val stats = VisionStats(
            framesPerSecond = (frames - 1) / elapsed,
            frameMillis = frameNanos / 1e6 / frames,
            rgbMillis = rgbNanos.getAndSet(0) / 1e6 / frames,
            faceMillis = if (faceCount == 0) 0.0 else faceTotal / 1e6 / faceCount,
            faceRunsPerSecond = faceCount / elapsed,
            poseMillis = if (poseCount == 0) 0.0 else poseTotal / 1e6 / poseCount,
            poseRunsPerSecond = poseCount / elapsed,
        )
        windowStart = timestampSeconds
        frames = 0
        frameNanos = 0
        return stats
    }

    fun reset() {
        windowStart = Double.NaN
        frames = 0
        frameNanos = 0
        rgbNanos.set(0)
        faceNanos.set(0)
        faceRuns.set(0)
        poseNanos.set(0)
        poseRuns.set(0)
    }
}
