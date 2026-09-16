package me.raddatz.cindy.camera

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CameraPipelineTests {
    /**
     * A 4×4 frame whose 2×2 blocks have distinct lumas (top-left pixel of each block: 10, 20 / 30, 40),
     * neutral chroma, with row padding and semi-planar chroma (pixel stride 2) as many devices deliver.
     */
    private fun planes(cb: Int = 128, cr: Int = 128): Triple<YuvPlane, YuvPlane, YuvPlane> {
        val rowStride = 6
        val luma = arrayOf(
            intArrayOf(10, 99, 20, 99),
            intArrayOf(99, 99, 99, 99),
            intArrayOf(30, 99, 40, 99),
            intArrayOf(99, 99, 99, 99),
        )
        val y = ByteBuffer.allocateDirect(rowStride * 4)
        for (row in luma) {
            for (col in 0 until rowStride) y.put((if (col < 4) row[col] else 0).toByte())
        }
        y.flip()
        // One buffer V U V U …, two chroma rows with padding; U and V are views into it.
        val chroma = ByteArray(8)
        for (i in 0 until 4) {
            chroma[2 * i] = cr.toByte()
            chroma[2 * i + 1] = cb.toByte()
        }
        val v = ByteBuffer.wrap(chroma, 0, 7).slice()
        val u = ByteBuffer.wrap(chroma, 1, 7).slice()
        return Triple(YuvPlane(y, rowStride, 1), YuvPlane(u, 4, 2), YuvPlane(v, 4, 2))
    }

    private fun lumas(width: Int, height: Int, rotation: Int): List<Int> {
        val (y, u, v) = planes()
        val out = ByteArray(width * height * 3)
        RgbConverter.convert(4, 4, y, u, v, rotation, out)
        return (0 until width * height).map { out[it * 3].toInt() and 0xFF }
    }

    @Test
    fun halfResolutionTakesTheTopLeftOfEachBlock() {
        assertEquals(listOf(10, 20, 30, 40), lumas(2, 2, 0))
    }

    @Test
    fun rotationsAreClockwise() {
        // 10 20      90°: 30 10    180°: 40 30    270°: 20 40
        // 30 40           40 20          20 10          10 30
        assertEquals(listOf(30, 10, 40, 20), lumas(2, 2, 90))
        assertEquals(listOf(40, 30, 20, 10), lumas(2, 2, 180))
        assertEquals(listOf(20, 40, 10, 30), lumas(2, 2, 270))
    }

    @Test
    fun rotatedOutputIsPortraitForLandscapeFrames() {
        assertEquals(640 to 360, RgbConverter.outputSize(1280, 720, 0))
        assertEquals(360 to 640, RgbConverter.outputSize(1280, 720, 90))
        assertEquals(360 to 640, RgbConverter.outputSize(1280, 720, 270))
    }

    @Test
    fun colorsFollowFullRangeBT601() {
        val (y, u, v) = planes(cb = 128, cr = 228) // strong red
        val out = ByteArray(12)
        RgbConverter.convert(4, 4, y, u, v, 0, out)
        val first = out.take(3).map { it.toInt() and 0xFF }
        assertEquals(listOf(10 + 140, 0, 10), first) // R = Y + 1.402·100, G clamps below 0
        val (y2, u2, v2) = planes(cb = 28, cr = 128) // no blue at all: B clamps, G rises
        RgbConverter.convert(4, 4, y2, u2, v2, 0, out)
        assertContentEquals(listOf(10, 10 + 35, 0), out.take(3).map { it.toInt() and 0xFF })
    }

    @Test
    fun framesConvertOncePerOrientationIntoExactlySizedDirectBuffers() {
        val (y, u, v) = planes()
        val frames = RgbFrames()
        frames.begin(4, 4, y, u, v)
        val upright = frames.rgb(0)
        val again = frames.rgb(360)
        assertEquals(upright.buffer, again.buffer)
        assertEquals(12, upright.buffer.capacity())
        assertEquals(true, upright.buffer.isDirect)
        assertEquals(0, upright.buffer.position())
        val turned = frames.rgb(90)
        assertEquals(30, turned.buffer.get(0).toInt() and 0xFF)
        frames.end()
    }

    @Test
    fun targetFpsPrefersTheFixedRange() {
        assertEquals(30 to 30, CameraTuning.targetFpsRange(listOf(15 to 30, 30 to 30, 7 to 30), 30))
        assertEquals(24 to 30, CameraTuning.targetFpsRange(listOf(15 to 30, 24 to 30, 7 to 60), 30))
        assertEquals(15 to 60, CameraTuning.targetFpsRange(listOf(15 to 60, 7 to 60), 30))
        assertNull(CameraTuning.targetFpsRange(listOf(7 to 24), 30))
    }
}
