package me.raddatz.cindy.core.camera

import me.raddatz.cindy.core.signal.FrameMetrics
import java.nio.ByteBuffer

/**
 * Measures the [FrameMetrics] of a camera frame from its luminance (Y) plane.
 *
 * Takes the plane the way Android's `YUV_420_888` `ImageProxy.planes[0]` hands it out: 8-bit
 * samples, `rowStride` bytes per row (may exceed `width`), full or video range as delivered.
 */
object FrameMetricsCalculator {
    /** Every n-th luma pixel in both directions is sampled. */
    private const val LUMA_STEP = 8

    /** Mean of the whole frame and of its central half, 0…1. */
    data class Luma(val mean: Float, val center: Float)

    fun measure(plane: ByteBuffer, width: Int, height: Int, rowStride: Int): FrameMetrics {
        val luma = luma(plane, width, height, rowStride) ?: return FrameMetrics()
        return FrameMetrics(lumaMean = luma.mean, lumaCenter = luma.center)
    }

    fun measure(plane: ByteArray, width: Int, height: Int, rowStride: Int): FrameMetrics {
        val luma = luma(plane, width, height, rowStride) ?: return FrameMetrics()
        return FrameMetrics(lumaMean = luma.mean, lumaCenter = luma.center)
    }

    /**
     * Mean of the Y plane over the whole frame and its central half, 0…1. Indices are relative to
     * the buffer's current position, which is left unchanged. `null` for an empty frame.
     */
    fun luma(plane: ByteBuffer, width: Int, height: Int, rowStride: Int): Luma? {
        val base = plane.position()
        return luma(width, height, rowStride) { index -> plane.get(base + index).toInt() and 0xFF }
    }

    /** Mean of the Y plane over the whole frame and its central half, 0…1. `null` for an empty frame. */
    fun luma(plane: ByteArray, width: Int, height: Int, rowStride: Int): Luma? =
        luma(width, height, rowStride) { index -> plane[index].toInt() and 0xFF }

    private inline fun luma(width: Int, height: Int, rowStride: Int, sample: (Int) -> Int): Luma? {
        if (width <= 0 || height <= 0) return null
        var total = 0L
        var count = 0
        var centerTotal = 0L
        var centerCount = 0
        var y = 0
        while (y < height) {
            val row = y * rowStride
            val centerRow = y >= height / 4 && y < height * 3 / 4
            var x = 0
            while (x < width) {
                val value = sample(row + x)
                total += value
                count += 1
                if (centerRow && x >= width / 4 && x < width * 3 / 4) {
                    centerTotal += value
                    centerCount += 1
                }
                x += LUMA_STEP
            }
            y += LUMA_STEP
        }
        if (count <= 0 || centerCount <= 0) return null
        return Luma(
            mean = total.toFloat() / count.toFloat() / 255f,
            center = centerTotal.toFloat() / centerCount.toFloat() / 255f,
        )
    }
}
