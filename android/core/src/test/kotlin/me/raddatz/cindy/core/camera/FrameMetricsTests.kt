package me.raddatz.cindy.core.camera

import me.raddatz.cindy.core.signal.BodyPoseObservation
import me.raddatz.cindy.core.signal.PoseJoint
import me.raddatz.cindy.core.signal.PosePoint
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameMetricsTests {
    /**
     * Y plane whose pixels are `outer`, with the central half set to `inner`. Rows are
     * `rowStride` bytes long; the padding past `width` holds garbage that must never be sampled.
     */
    private fun lumaPlane(width: Int = 64, height: Int = 48, rowStride: Int = width, outer: Int, inner: Int): ByteArray {
        val plane = ByteArray(rowStride * height) { 77 }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val central = y >= height / 4 && y < height * 3 / 4 && x >= width / 4 && x < width * 3 / 4
                plane[y * rowStride + x] = (if (central) inner else outer).toByte()
            }
        }
        return plane
    }

    @Test
    fun lumaSeparatesFrameAndCentre() {
        // Same expectations for a tight plane, a padded one (Android row stride), and a ByteBuffer.
        for (rowStride in listOf(64, 72)) {
            val plane = lumaPlane(rowStride = rowStride, outer = 255, inner = 0)
            val luma = assertNotNull(FrameMetricsCalculator.luma(plane, 64, 48, rowStride))
            assertEquals(0f, luma.center)
            // The central half covers a quarter of the sampled pixels.
            assertTrue(abs(luma.mean - 0.75f) < 0.01f)

            val direct = ByteBuffer.allocateDirect(plane.size).put(plane).also { it.flip() }
            val buffered = FrameMetricsCalculator.measure(direct, 64, 48, rowStride)
            assertEquals(luma.mean, buffered.lumaMean)
            assertEquals(luma.center, buffered.lumaCenter)
            assertEquals(0, direct.position())
        }
        assertNull(FrameMetricsCalculator.luma(ByteArray(0), 0, 0, 0))
    }

    @Test
    fun shoulderWidthIsTheDistanceBetweenShoulders() {
        val pose = BodyPoseObservation(
            mapOf(
                PoseJoint.LEFT_SHOULDER to PosePoint(0.2f, 0.5f, 0.9f),
                PoseJoint.RIGHT_SHOULDER to PosePoint(0.5f, 0.9f, 0.9f),
            ),
        )
        assertTrue(abs((pose.shoulderWidth ?: 0f) - 0.5f) < 1e-6f)
        assertNull(BodyPoseObservation(mapOf(PoseJoint.NOSE to PosePoint(0.5f, 0.5f, 1f))).shoulderWidth)
    }
}
