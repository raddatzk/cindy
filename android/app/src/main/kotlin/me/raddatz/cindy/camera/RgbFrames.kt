package me.raddatz.cindy.camera

import java.nio.ByteBuffer

/** One `YUV_420_888` plane as CameraX hands it out. */
class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/** An RGB image (3 bytes per pixel, rows top to bottom) in a direct buffer sized exactly for it. */
class RgbImage(val buffer: ByteBuffer, val width: Int, val height: Int)

/**
 * YUV → RGB at half resolution, already rotated clockwise into the orientation a detector should
 * see (iOS hands Vision the buffer plus an orientation instead).
 *
 * MediaPipe's CPU input path takes RGB(A) only — a `MediaImage` has to be RGBA_8888 and YUV byte
 * buffers are rejected — so a conversion is unavoidable. Doing it here, at half resolution and
 * straight into the rotated layout, is the cheapest correct path: 640×360 = 230 k pixels of integer
 * arithmetic per needed orientation (one or two per frame, face and pose share the same buffer),
 * no `Bitmap`, no second rotation pass, and one memcpy into a reused direct buffer. The detectors
 * scale to 128×128 (face) and 224/256 px (pose) anyway, so the full 1280×720 would buy nothing.
 * Rotating the pixels ourselves also keeps the result coordinates in the upright frame, whatever
 * MediaPipe would do with a rotation option.
 *
 * Thread-safe: face and pose ask for their orientation from different threads.
 */
class RgbFrames {
    private var width = 0
    private var height = 0
    private var y: YuvPlane? = null
    private var u: YuvPlane? = null
    private var v: YuvPlane? = null
    private val buffers = arrayOfNulls<ByteBuffer>(4)
    private val filled = BooleanArray(4)
    private var scratch = ByteArray(0)
    private val rows = RowScratch()
    private var conversionNanos = 0L

    /** Starts a frame; the planes must stay valid until [end]. */
    @Synchronized
    fun begin(width: Int, height: Int, y: YuvPlane, u: YuvPlane, v: YuvPlane) {
        this.width = width
        this.height = height
        this.y = y
        this.u = u
        this.v = v
        filled.fill(false)
    }

    /** The current frame rotated clockwise by [rotationDegrees], converted on first use. */
    @Synchronized
    fun rgb(rotationDegrees: Int): RgbImage {
        val index = ((rotationDegrees % 360 + 360) % 360) / 90
        val (outWidth, outHeight) = RgbConverter.outputSize(width, height, index * 90)
        val size = outWidth * outHeight * 3
        val buffer = buffers[index]?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).also { buffers[index] = it }
        if (!filled[index]) {
            val started = System.nanoTime()
            if (scratch.size != size) scratch = ByteArray(size)
            RgbConverter.convert(width, height, checkNotNull(y), checkNotNull(u), checkNotNull(v), index * 90, scratch, rows)
            buffer.clear()
            buffer.put(scratch, 0, size)
            buffer.rewind()
            filled[index] = true
            conversionNanos += System.nanoTime() - started
        }
        return RgbImage(buffer, outWidth, outHeight)
    }

    /** Conversion time since the last call, for [VisionStats]. */
    @Synchronized
    fun takeConversionNanos(): Long = conversionNanos.also { conversionNanos = 0 }

    /** Drops the plane references of the finished frame. */
    @Synchronized
    fun end() {
        y = null
        u = null
        v = null
    }
}

/** Reusable row buffers for [RgbConverter.convert]. */
class RowScratch {
    internal var y = ByteArray(0)
    internal var u = ByteArray(0)
    internal var v = ByteArray(0)
}

object RgbConverter {
    /** Output size for a frame of [width]×[height], halved and rotated. */
    fun outputSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        OrientationMapping.orientedSize(width / 2, height / 2, rotationDegrees)

    /**
     * Writes the half-resolution RGB picture, rotated clockwise by [rotationDegrees], into [out].
     * Each output pixel takes the luma of the top-left pixel of its 2×2 block and the chroma sample
     * that block shares (full-range BT.601, as camera `YUV_420_888` frames are).
     */
    fun convert(
        width: Int,
        height: Int,
        y: YuvPlane,
        u: YuvPlane,
        v: YuvPlane,
        rotationDegrees: Int,
        out: ByteArray,
        rows: RowScratch = RowScratch(),
    ) {
        val w = width / 2
        val h = height / 2
        if (w <= 0 || h <= 0) return
        val rotation = (rotationDegrees % 360 + 360) % 360
        val outWidth = if (rotation % 180 == 0) w else h
        val ySpan = 2 * (w - 1) * y.pixelStride + 1
        val uSpan = (w - 1) * u.pixelStride + 1
        val vSpan = (w - 1) * v.pixelStride + 1
        if (rows.y.size < ySpan) rows.y = ByteArray(ySpan)
        if (rows.u.size < uSpan) rows.u = ByteArray(uSpan)
        if (rows.v.size < vSpan) rows.v = ByteArray(vSpan)
        val yRow = rows.y
        val uRow = rows.u
        val vRow = rows.v
        for (oy in 0 until h) {
            readRow(y, 2 * oy, yRow, ySpan)
            readRow(u, oy, uRow, uSpan)
            readRow(v, oy, vRow, vSpan)
            for (ox in 0 until w) {
                val luma = yRow[2 * ox * y.pixelStride].toInt() and 0xFF
                val cb = (uRow[ox * u.pixelStride].toInt() and 0xFF) - 128
                val cr = (vRow[ox * v.pixelStride].toInt() and 0xFF) - 128
                val r = clamp(luma + ((1436 * cr) shr 10))
                val g = clamp(luma - ((352 * cb + 731 * cr) shr 10))
                val b = clamp(luma + ((1815 * cb) shr 10))
                val dx: Int
                val dy: Int
                when (rotation) {
                    90 -> { dx = h - 1 - oy; dy = ox }
                    180 -> { dx = w - 1 - ox; dy = h - 1 - oy }
                    270 -> { dx = oy; dy = w - 1 - ox }
                    else -> { dx = ox; dy = oy }
                }
                val index = (dy * outWidth + dx) * 3
                out[index] = r.toByte()
                out[index + 1] = g.toByte()
                out[index + 2] = b.toByte()
            }
        }
    }

    private fun readRow(plane: YuvPlane, row: Int, target: ByteArray, span: Int) {
        val duplicate = plane.buffer.duplicate()
        duplicate.position(plane.buffer.position() + row * plane.rowStride)
        duplicate.get(target, 0, span)
    }

    private fun clamp(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value
}
