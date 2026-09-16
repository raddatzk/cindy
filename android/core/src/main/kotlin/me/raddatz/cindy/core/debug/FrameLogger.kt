package me.raddatz.cindy.core.debug

import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.RepDetectorEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Per-frame CSV logger for the debug/recording mode.
 *
 * Columns are plain numbers only (no images). One row per camera frame: timestamp, exercise,
 * source, raw & smoothed signal, confidence, face box, selected pose landmarks, detector
 * event/phase, rep count, app state and the thresholds the detector was comparing against, plus
 * shoulder width and image brightness. The format is shared with iOS, `tools/analyze_csv.py` and
 * the test fixtures — keep [HEADER] and [row] in sync with `FrameLogger.swift`.
 *
 * Creates `cindy_<yyyy-MM-dd_HH-mm-ss>_<label>.csv` in [directory] (created if needed). Rows are
 * formatted on the calling thread and written on a single background thread.
 *
 * @throws IOException when the directory or file cannot be created.
 */
class FrameLogger(directory: File, label: String, now: LocalDateTime = LocalDateTime.now()) {
    val file: File
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "me.raddatz.cindy.logger").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private var output: FileOutputStream?
    private val buffer = ByteArrayOutputStream()
    private val rows = AtomicInteger(0)
    private var firstTimestamp: Double? = null

    val rowCount: Int get() = rows.get()

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(now)
        file = File(directory, "cindy_${stamp}_$label.csv")
        output = FileOutputStream(file).also { it.write((HEADER + "\n").toByteArray(Charsets.UTF_8)) }
    }

    fun log(observation: FrameObservation, output: PipelineOutput?, state: String) {
        val first = firstTimestamp ?: observation.timestamp.also { firstTimestamp = it }
        val bytes = (row(observation, output, state, first) + "\n").toByteArray(Charsets.UTF_8)
        try {
            executor.execute {
                buffer.write(bytes)
                rows.incrementAndGet()
                if (buffer.size() > 16 * 1024) flushOnQueue()
            }
        } catch (_: RejectedExecutionException) {
            // Closed: drop the row, like writes to a closed handle on iOS.
        }
    }

    /** Blocks until everything logged so far is written. */
    fun flush() {
        runOnQueue { flushOnQueue() }
    }

    fun close() {
        runOnQueue {
            flushOnQueue()
            try {
                output?.close()
            } catch (_: IOException) {
            }
            output = null
        }
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun runOnQueue(block: () -> Unit) {
        try {
            executor.submit(block).get()
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun flushOnQueue() {
        val stream = output ?: return
        if (buffer.size() == 0) return
        try {
            buffer.writeTo(stream)
            stream.flush()
        } catch (_: IOException) {
        }
        buffer.reset()
    }

    companion object {
        val HEADER: String = listOf(
            "t", "exercise", "source", "raw", "smoothed", "confidence",
            "face_x", "face_y", "face_w", "face_h", "face_conf", "face_area", "orientation",
            "pose_nose_y", "pose_shoulder_y", "pose_hip_y", "pose_conf",
            "event", "phase", "armed", "rep_count", "state", "low", "high",
            "pose_shoulder_w", "pose_shoulder_conf", "pose_orientation", "luma_mean", "luma_center",
        ).joinToString(",")

        /** iOS stores logs in `Documents/DebugLogs`; the Android equivalent under the app's files dir. */
        fun logsDirectory(filesDir: File): File = File(filesDir, "DebugLogs")

        /** One CSV row; `t` is relative to [firstTimestamp]. */
        fun row(observation: FrameObservation, output: PipelineOutput?, state: String, firstTimestamp: Double): String {
            val t = observation.timestamp - firstTimestamp
            val fields = ArrayList<String>(29)
            fields += format(t, 4)
            fields += output?.exercise?.rawValue ?: ""
            fields += output?.source?.rawValue ?: ""
            fields += format(output?.raw)
            fields += format(output?.smoothed)
            fields += format(output?.confidence ?: observation.face?.confidence ?: 0f)
            val face = observation.face
            if (face != null) {
                fields += format(face.boundingBox.minX.toFloat())
                fields += format(face.boundingBox.minY.toFloat())
                fields += format(abs(face.boundingBox.width).toFloat())
                fields += format(abs(face.boundingBox.height).toFloat())
                fields += format(face.confidence)
                fields += format(face.area)
            } else {
                repeat(6) { fields += "" }
            }
            fields += observation.orientation?.toString() ?: ""
            fields += format(observation.pose?.noseY)
            fields += format(observation.pose?.shoulderY)
            fields += format(observation.pose?.hipY)
            fields += format(observation.pose?.overallConfidence)
            fields += eventName(output?.event)
            fields += output?.phase?.rawValue ?: ""
            fields += output?.let { if (it.isArmed) "1" else "0" } ?: ""
            fields += output?.repCount?.toString() ?: ""
            fields += state.replace(",", ";")
            fields += format(output?.thresholds?.low)
            fields += format(output?.thresholds?.high)
            val shoulders = observation.pose?.shoulderWidthSample
            fields += format(shoulders?.value)
            fields += format(shoulders?.confidence)
            fields += observation.poseOrientation?.toString() ?: ""
            fields += format(observation.metrics?.lumaMean)
            fields += format(observation.metrics?.lumaCenter)
            return fields.joinToString(",")
        }

        fun eventName(event: RepDetectorEvent?): String = when (event) {
            null -> ""
            RepDetectorEvent.Armed -> "armed"
            RepDetectorEvent.Disarmed -> "disarmed"
            is RepDetectorEvent.RepCompleted -> "rep"
            is RepDetectorEvent.RepRejected -> "rejected"
        }

        private fun format(value: Float?, digits: Int = 6): String =
            if (value == null) "" else formatFixed(value.toDouble(), digits)

        private fun format(value: Double, digits: Int): String = formatFixed(value, digits)

        /**
         * C `printf("%.<digits>f")`, as Swift's `String(format:)` produces it: exact binary value,
         * round-half-even, locale-independent, "-0.000000" for tiny negatives.
         */
        internal fun formatFixed(value: Double, digits: Int): String {
            if (value.isNaN()) return "nan"
            if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
            val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
            val text = BigDecimal(abs(value)).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
            return if (negative) "-$text" else text
        }
    }
}
