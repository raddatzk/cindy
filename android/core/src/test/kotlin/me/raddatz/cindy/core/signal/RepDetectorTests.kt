package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.Sample
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SyntheticSignal
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepDetectorTests {
    private val thresholds = RepThresholds.from(min = 0.05f, max = 0.20f, direction = RepDirection.PEAK)

    private data class Run(val reps: Int, val rejected: Int, val events: List<RepDetectorEvent>)

    private fun run(
        samples: List<Sample>,
        thresholds: RepThresholds? = null,
        config: SignalConfig = SignalConfig.default,
    ): Run {
        val detector = RepDetector(thresholds ?: this.thresholds, config)
        var reps = 0
        var rejected = 0
        val events = mutableListOf<RepDetectorEvent>()
        for (sample in samples) {
            val event = detector.process(sample.value, sample.confidence, sample.t) ?: continue
            events += event
            if (event is RepDetectorEvent.RepCompleted) reps += 1
            if (event is RepDetectorEvent.RepRejected) rejected += 1
        }
        return Run(reps, rejected, events)
    }

    @Test
    fun countsCleanReps() {
        val result = run(SyntheticSignal().reps(10))
        assertEquals(10, result.reps)
        assertEquals(0, result.rejected)
        assertEquals(RepDetectorEvent.Armed, result.events.first())
    }

    @Test
    fun thresholdRuleUsesMargin() {
        val t = RepThresholds.from(min = 0f, max = 1f, direction = RepDirection.PEAK, margin = 0.25f)
        assertEquals(0.25f, t.low)
        assertEquals(0.75f, t.high)
    }

    @Test
    fun requiresStableFramesBeforeArming() {
        val detector = RepDetector(thresholds)
        var armedAt: Int? = null
        for (i in 0 until 20) {
            val event = detector.process(0.05f, 1f, i.toDouble() / 30)
            if (event == RepDetectorEvent.Armed) armedAt = i
        }
        assertEquals(SignalConfig.default.stableFrames - 1, armedAt)
    }

    @Test
    fun armsByDurationAtLowFrameRates() {
        // Galaxy A20e at 5 fps: ten frames would take 2 s. Three samples spanning 0.3 s (the
        // duration of ten frames at 30 fps) arm instead.
        val fiveFps = RepDetector(thresholds)
        assertEquals(2, (0 until 10).indexOfFirst { fiveFps.process(0.05f, 1f, it * 0.2) == RepDetectorEvent.Armed })
        // 10 fps: four samples span 0.3 s. Recorded timestamps are rounded (0.0999 s apart), which the tolerance absorbs.
        val tenFps = RepDetector(thresholds)
        assertEquals(3, (0 until 10).indexOfFirst { tenFps.process(0.05f, 1f, it * 0.0999) == RepDetectorEvent.Armed })
        // 15 fps: five samples span only 0.267 s, the sixth arms.
        val fifteenFps = RepDetector(thresholds)
        assertEquals(5, (0 until 10).indexOfFirst { fifteenFps.process(0.05f, 1f, it / 15.0) == RepDetectorEvent.Armed })
    }

    @Test
    fun aSingleSlowFrameDoesNotArm() {
        val detector = RepDetector(thresholds)
        assertNull(detector.process(0.05f, 1f, 0.0))
        assertNull(detector.process(0.05f, 1f, 1.0)) // 1 s of rest, but only two samples
        assertEquals(RepDetectorEvent.Armed, detector.process(0.05f, 1f, 1.1))
    }

    @Test
    fun countsCleanRepsAtFiveFps() {
        val result = run(SyntheticSignal(fps = 5.0).reps(10))
        assertEquals(10, result.reps)
        assertEquals(0, result.rejected)
    }

    @Test
    fun doesNotArmOutsideRestBand() {
        val detector = RepDetector(thresholds)
        for (i in 0 until 30) detector.process(0.19f, 1f, i.toDouble() / 30)
        assertFalse(detector.isArmed)
    }

    @Test
    fun settlingFromAboveHighDoesNotCount() {
        // Signal starts above `high` (e.g. athlete still in push-up position when squats begin),
        // drops into the rest band, then performs one real rep.
        val samples = mutableListOf<Sample>()
        var t = 0.0
        repeat(15) { samples += Sample(t, 0.25f, 1f); t += 1.0 / 30 }
        samples += SyntheticSignal().reps(1).map { Sample(t + it.t, it.value, it.confidence) }
        assertEquals(1, run(samples).reps)
    }

    @Test
    fun rejectsTooFastReps() {
        val result = run(SyntheticSignal().reps(3, period = 0.2, gap = 0.5))
        assertEquals(0, result.reps)
        assertEquals(3, result.rejected)
    }

    @Test
    fun rejectsTooSlowReps() {
        val result = run(SyntheticSignal().reps(1, period = 7.0, gap = 0.5))
        assertEquals(0, result.reps)
        assertEquals(1, result.rejected)
    }

    @Test
    fun holdsStateDuringShortFaceLoss() {
        val samples = SyntheticSignal().reps(1)
        // Blank out the peak of the rep (~0.3 s) as if the face left the frame.
        var peakIndex = 0
        for (i in samples.indices) if ((samples[peakIndex].value ?: 0f) < (samples[i].value ?: 0f)) peakIndex = i
        for (i in (peakIndex - 4)..(peakIndex + 4)) {
            samples[i].value = null
            samples[i].confidence = 0f
        }
        assertEquals(1, run(samples).reps)
    }

    @Test
    fun disarmsAfterLongLoss() {
        val samples = SyntheticSignal().reps(1, leadIn = 1.0)
        val lossFrames = (3.0 * 30).toInt()
        var t = samples.last().t
        repeat(lossFrames) {
            t += 1.0 / 30
            samples += Sample(t, null, 0f)
        }
        val result = run(samples)
        assertEquals(1, result.reps)
        assertEquals(RepDetectorEvent.Disarmed, result.events.last())
    }

    @Test
    fun bounceWithoutReachingHighIsNotARep() {
        val samples = mutableListOf<Sample>()
        var t = 0.0
        repeat(15) { samples += Sample(t, 0.05f, 1f); t += 1.0 / 30 }
        repeat(15) { samples += Sample(t, 0.12f, 1f); t += 1.0 / 30 } // between low and high
        repeat(15) { samples += Sample(t, 0.05f, 1f); t += 1.0 / 30 }
        assertEquals(0, run(samples).reps)
    }

    @Test
    fun troughDirectionCountsInvertedSignal() {
        // Pull-up style: rest is the high value, the rep dips below `low`.
        val peakSamples = SyntheticSignal(rest = 0.05f, peak = 0.20f).reps(5)
        val inverted = peakSamples.map { Sample(it.t, it.value?.let { v -> 0.25f - v }, it.confidence) }
        val troughThresholds = RepThresholds.from(min = 0.05f, max = 0.20f, direction = RepDirection.TROUGH)
        assertEquals(5, run(inverted, troughThresholds).reps)
    }

    // Relative thresholds

    @Test
    fun relativeThresholdsRescaleToMeasuredRest() {
        val relative = RepThresholds(0.0875f, 0.1625f, RepDirection.PEAK, baseline = 0.05f)
        // Twice as much face area as during calibration: rest 0.10, reps up to 0.40.
        val samples = SyntheticSignal(rest = 0.10f, peak = 0.40f).reps(5)
        val detector = RepDetector(relative)
        var reps = 0
        for (sample in samples) {
            if (detector.process(sample.value, sample.confidence, sample.t) is RepDetectorEvent.RepCompleted) reps += 1
        }
        assertEquals(5, reps)
        assertTrue(abs(detector.activeThresholds.low - 0.175f) < 1e-4f)
        assertTrue(abs(detector.activeThresholds.high - 0.325f) < 1e-4f)
        assertEquals(0, run(samples, RepThresholds(0.0875f, 0.1625f, RepDirection.PEAK)).reps)
    }

    @Test
    fun relativeThresholdsDoNotArmFarFromBaseline() {
        // Standing right at the phone: seven times the calibrated rest area, perfectly still.
        val detector = RepDetector(RepThresholds(0.0875f, 0.1625f, RepDirection.PEAK, baseline = 0.05f))
        for (i in 0 until 60) detector.process(0.35f, 1f, i.toDouble() / 30)
        assertFalse(detector.isArmed)
    }

    @Test
    fun relativeTroughThresholdsRescale() {
        val peakSamples = SyntheticSignal(rest = 0.05f, peak = 0.20f).reps(5)
        // Pull-up style at 1.5× the calibrated area: rest 0.30, dips to 0.075.
        val inverted = peakSamples.map { Sample(it.t, it.value?.let { v -> (0.25f - v) * 1.5f }, it.confidence) }
        val trough = RepThresholds(0.0875f, 0.1625f, RepDirection.TROUGH, baseline = 0.20f)
        assertEquals(5, run(inverted, trough).reps)
    }

    @Test
    fun restTrackingRecoversFromArmingWhileWalkingAway() {
        // Arms at about 1.55× the calibrated rest while still walking away, then settles at
        // the calibrated rest: without tracking `high` (0.25) stays out of reach of the 0.20 peaks.
        val relative = RepThresholds(0.0875f, 0.1625f, RepDirection.PEAK, baseline = 0.05f)
        val samples = mutableListOf<Sample>()
        var t = 0.0
        for (i in 0 until 60) {
            val value = maxOf(0.05f, 0.08f - 0.0006f * i.toFloat())
            samples += Sample(t, value, 1f)
            t += 1.0 / 30
        }
        samples += SyntheticSignal(rest = 0.05f, peak = 0.20f).reps(5, leadIn = 2.0).map {
            Sample(t + it.t, it.value, it.confidence)
        }
        assertEquals(5, run(samples, relative).reps)
        val untracked = SignalConfig.default.copy(restTrackingAlpha = 0f)
        assertEquals(0, run(samples, relative, untracked).reps)
    }

    @Test
    fun openCycleDisarmsRelativeThresholds() {
        // Stepping closer and staying there: the cycle never closes, so the detector re-arms at the new rest.
        val detector = RepDetector(RepThresholds(0.0875f, 0.1625f, RepDirection.PEAK, baseline = 0.05f))
        val events = mutableListOf<RepDetectorEvent>()
        var t = 0.0
        for (value in List(15) { 0.05f } + List(300) { 0.09f }) {
            detector.process(value, 1f, t)?.let { events += it }
            t += 1.0 / 30
        }
        assertEquals(2, events.count { it == RepDetectorEvent.Armed })
        assertTrue(events.contains(RepDetectorEvent.Disarmed))
        assertTrue(abs(detector.activeThresholds.low - 0.0875f * 1.8f) < 1e-3f)
    }

    @Test
    fun lowConfidenceFramesAreIgnoredWhileArming() {
        val detector = RepDetector(thresholds)
        for (i in 0 until 8) detector.process(0.05f, 1f, i.toDouble() / 30)
        detector.process(0.05f, 0.1f, 8.0 / 30)
        for (i in 9 until 17) detector.process(0.05f, 1f, i.toDouble() / 30)
        assertFalse(detector.isArmed)
        detector.process(0.05f, 1f, 17.0 / 30)
        detector.process(0.05f, 1f, 18.0 / 30)
        assertTrue(detector.isArmed)
    }
}
