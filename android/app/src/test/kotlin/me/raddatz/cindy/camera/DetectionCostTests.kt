package me.raddatz.cindy.camera

import me.raddatz.cindy.FakeFrameSource
import me.raddatz.cindy.core.SignalSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetectionCostTests {
    @Test
    fun brightnessRunsFaceAndPoseOnlyAsThrottledEvidence() {
        val source = FakeFrameSource()
        source.setDetection(SignalSource.BRIGHTNESS)
        assertTrue(source.detectsFace && source.detectsPose && source.measuresMetrics)
        assertEquals(FrameSource.EVIDENCE_INTERVAL, source.faceInterval)
        assertEquals(FrameSource.EVIDENCE_INTERVAL, source.poseInterval)
    }

    @Test
    fun theSignalDetectorRunsOnEveryFrame() {
        val face = FakeFrameSource().apply { setDetection(SignalSource.FACE) }
        assertEquals(0.0, face.faceInterval)
        assertEquals(false, face.detectsPose)

        val pose = FakeFrameSource().apply { setDetection(SignalSource.POSE) }
        assertEquals(0.0, pose.poseInterval)
        assertEquals(false, pose.detectsFace)
    }

    @Test
    fun statsCloseAWindowAfterThreeSecondsWithPerRunDetectorTimes() {
        val stats = VisionStatsAccumulator(windowSeconds = 3.0)
        var closed: VisionStats? = null
        // 10 fps for 3 s: 31 frames at 20 ms each, 4 ms of conversion.
        for (i in 0..30) {
            if (i % 2 == 0) stats.face(30_000_000)
            if (i % 5 == 0) stats.pose(120_000_000)
            stats.rgb(4_000_000)
            val result = stats.frame(timestampSeconds = i * 0.1, nanos = 20_000_000)
            if (i < 30) assertNull(result) else closed = result
        }
        val window = assertNotNull(closed)
        assertEquals(10.0, window.framesPerSecond, 1e-9)
        assertEquals(20.0, window.frameMillis, 1e-9)
        assertEquals(4.0, window.rgbMillis, 1e-9)
        assertEquals(30.0, window.faceMillis, 1e-9)
        assertEquals(16 / 3.0, window.faceRunsPerSecond, 1e-9)
        assertEquals(120.0, window.poseMillis, 1e-9)
        assertEquals(7 / 3.0, window.poseRunsPerSecond, 1e-9)
    }
}
