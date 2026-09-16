package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.calibration.CalibrationAnalyzer
import me.raddatz.cindy.core.calibration.CalibrationSample
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoldDetectorTests {
    private val band = RepThresholds(0.08f, 0.12f, RepDirection.PEAK)

    @Test
    fun accumulatesTimeInsideBandAndCompletes() {
        val detector = HoldDetector(band, targetSeconds = 2.0, config = SignalConfig.default)
        var completedAt: Double? = null
        for (i in 0 until 120) {
            val t = i.toDouble() / 30
            if (detector.process(0.10f, 1f, t) is RepDetectorEvent.RepCompleted) completedAt = t
        }
        val done = completedAt ?: -1.0
        assertTrue(done > 2.2 && done < 2.6, "completed at $done") // 10 arming frames + 2 s of hold
        assertEquals(1, detector.repCount)
    }

    @Test
    fun leavingBandPausesWithoutReset() {
        val detector = HoldDetector(band, targetSeconds = 5.0, config = SignalConfig.default)
        var t = 0.0
        fun feed(value: Float?, frames: Int) {
            repeat(frames) {
                detector.process(value, if (value == null) 0f else 1f, t)
                t += 1.0 / 30
            }
        }
        feed(0.10f, 40) // arm + ~1 s
        val held = detector.heldSeconds
        assertTrue(held > 0.9 && held < 1.1)
        feed(0.30f, 30) // out of band: paused
        assertEquals(held, detector.heldSeconds)
        feed(null, 15) // face lost briefly: still paused, still armed
        assertTrue(detector.isArmed)
        feed(0.10f, 30) // back: continues
        assertTrue(detector.heldSeconds > held + 0.8)
    }

    @Test
    fun holdCalibrationBuildsBandAroundMean() {
        val trace = (0 until 100).map { i ->
            CalibrationSample(i.toDouble() / 30, 0.10f + (if (i % 2 == 0) 0.002f else -0.002f))
        }
        val analyzer = CalibrationAnalyzer(source = SignalSource.FACE)
        val calibration = assertNotNull(analyzer.evaluateHold(trace))
        assertTrue(abs(calibration.baseline - 0.10f) < 0.001f)
        assertTrue(calibration.low < 0.08f && calibration.high > 0.12f) // ±25 % of the mean at least
        assertNull(analyzer.evaluateHold(trace.take(30))) // shorter than 3 s
    }
}
