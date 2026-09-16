package me.raddatz.cindy.core

import me.raddatz.cindy.core.calibration.CalibrationAnalyzer
import me.raddatz.cindy.core.calibration.CalibrationSample
import me.raddatz.cindy.core.calibration.ExerciseCalibration
import me.raddatz.cindy.core.signal.BodyPoseObservation
import me.raddatz.cindy.core.signal.FaceObservation
import me.raddatz.cindy.core.signal.FrameMetrics
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PoseJoint
import me.raddatz.cindy.core.signal.PosePoint
import me.raddatz.cindy.core.signal.RepDetectorEvent
import me.raddatz.cindy.core.signal.RepThresholds
import me.raddatz.cindy.core.signal.SignalPipeline
import java.net.URL
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Locates test fixtures (the shared CSVs are on the test classpath). */
object FixtureLocator {
    fun url(name: String, extension: String = "csv"): URL? =
        FixtureLocator::class.java.classLoader.getResource("$name.$extension")

    fun require(name: String): URL = requireNotNull(url(name)) { "Missing fixture $name" }
}

/** One synthetic or replayed sample. Mutable so tests can blank out frames. */
data class Sample(var t: Double, var value: Float?, var confidence: Float)

/** Synthetic signal generator for detector tests. */
data class SyntheticSignal(
    val fps: Double = 30.0,
    val rest: Float = 0.05f,
    val peak: Float = 0.20f,
    val noise: Float = 0f,
) {
    /** `leadIn` seconds of rest, then `reps` half-sine cycles of `period` seconds with `gap` seconds of rest in between. */
    fun reps(
        reps: Int,
        period: Double = 1.6,
        gap: Double = 0.4,
        leadIn: Double = 1.0,
        leadOut: Double = 1.0,
    ): MutableList<Sample> {
        val samples = mutableListOf<Sample>()
        var t = 0.0
        fun jitter(): Float = if (noise == 0f) 0f else Random.nextDouble(-noise.toDouble(), noise.toDouble()).toFloat()
        fun emitRest(seconds: Double) {
            repeat((seconds * fps).toInt()) {
                samples += Sample(t, rest + jitter(), 0.95f)
                t += 1 / fps
            }
        }
        emitRest(leadIn)
        repeat(reps) {
            val n = (period * fps).toInt()
            for (i in 0 until n) {
                val phase = i.toFloat() / n.toFloat()
                val value = rest + (peak - rest) * sin(PI.toFloat() * phase) + jitter()
                samples += Sample(t, value, 0.95f)
                t += 1 / fps
            }
            emitRest(gap)
        }
        emitRest(leadOut)
        return samples
    }
}

/** Multiplies every value, e.g. to simulate standing closer to the phone. */
fun List<Sample>.scaled(factor: Float): List<Sample> = map { Sample(it.t, it.value?.let { v -> v * factor }, it.confidence) }

/** Parses CSV files written by `FrameLogger` (columns t, raw, confidence by default). */
object CSVSignalReplay {
    private fun lines(url: URL): MutableList<String> =
        url.readText(Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }.toMutableList()

    /** `column` / `confidenceColumn` pick another signal, e.g. `pose_shoulder_w` / `pose_conf`. */
    fun load(url: URL, column: String = "raw", confidenceColumn: String = "confidence"): List<Sample> {
        val lines = lines(url)
        if (lines.isEmpty()) return emptyList()
        val header = lines.removeAt(0).split(",").filter { it.isNotEmpty() }
        val tIndex = header.indexOf("t").takeIf { it >= 0 } ?: return emptyList()
        val rawIndex = header.indexOf(column).takeIf { it >= 0 } ?: return emptyList()
        val confIndex = header.indexOf(confidenceColumn).takeIf { it >= 0 } ?: return emptyList()
        return lines.map { line ->
            val fields = line.split(",")
            val t = fields[tIndex].toDoubleOrNull() ?: 0.0
            val raw = fields[rawIndex].toFloatOrNull()
            val conf = fields[confIndex].toFloatOrNull() ?: 0f
            Sample(t, raw, conf)
        }
    }

    /**
     * Rebuilds full frames (face box, shoulder width, brightness) so a replay also exercises
     * `BodyEvidence`. `lumaOffset` simulates a brighter or darker scene.
     */
    fun observations(url: URL, lumaOffset: Float = 0f): List<FrameObservation> {
        val lines = lines(url)
        if (lines.isEmpty()) return emptyList()
        val header = lines.removeAt(0).split(",").filter { it.isNotEmpty() }
        return lines.map { line ->
            val fields = line.split(",")
            fun value(name: String): Float? {
                val index = header.indexOf(name)
                if (index < 0 || index >= fields.size) return null
                return fields[index].toFloatOrNull()
            }
            var face: FaceObservation? = null
            val x = value("face_x")
            val y = value("face_y")
            val w = value("face_w")
            val h = value("face_h")
            if (x != null && y != null && w != null && h != null) {
                face = FaceObservation(
                    Rect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()),
                    value("face_conf") ?: 1f,
                )
            }
            var pose: BodyPoseObservation? = null
            val width = value("pose_shoulder_w")
            if (width != null) {
                val confidence = value("pose_shoulder_conf") ?: value("pose_conf") ?: 1f
                pose = BodyPoseObservation(
                    mapOf(
                        PoseJoint.LEFT_SHOULDER to PosePoint(0f, 0f, confidence),
                        PoseJoint.RIGHT_SHOULDER to PosePoint(width, 0f, confidence),
                    ),
                )
            }
            val luma = value("luma_mean")
            val metrics = if (luma != null) FrameMetrics(luma + lumaOffset, value("luma_center")) else null
            FrameObservation(
                timestamp = (value("t") ?: 0f).toDouble(),
                face = face,
                pose = pose,
                metrics = metrics,
            )
        }
    }

    /** Feeds frames between `from` and `to` through a calibration capture, like `CalibrationEngine`. */
    fun calibrate(
        frames: List<FrameObservation>,
        from: Double,
        to: Double,
        exercise: Exercise,
        source: SignalSource,
        config: SignalConfig = SignalConfig.default,
    ): ExerciseCalibration? {
        val pipeline = SignalPipeline(exercise, RepThresholds.hardcoded(exercise), source, config)
        val analyzer = CalibrationAnalyzer(config, source)
        val trace = mutableListOf<CalibrationSample>()
        for (frame in frames) {
            if (!(frame.timestamp >= from && frame.timestamp <= to)) continue
            val output = pipeline.process(frame)
            val value = output.smoothed
            if (value != null && output.confidence >= config.minConfidence) {
                trace += CalibrationSample(frame.timestamp, value)
            }
            analyzer.evaluate(trace)?.let { return it }
        }
        return null
    }

    data class FrameCount(val reps: List<Double>, val rejected: Int)

    /** Counted and rejected reps of full frames through the pipeline. */
    fun countReps(
        frames: List<FrameObservation>,
        exercise: Exercise,
        thresholds: RepThresholds,
        source: SignalSource,
        config: SignalConfig = SignalConfig.default,
    ): FrameCount {
        val pipeline = SignalPipeline(exercise, thresholds, source, config)
        val reps = mutableListOf<Double>()
        var rejected = 0
        for (frame in frames) {
            when (pipeline.process(frame).event) {
                is RepDetectorEvent.RepCompleted -> reps += frame.timestamp
                is RepDetectorEvent.RepRejected -> rejected += 1
                else -> {}
            }
        }
        return FrameCount(reps, rejected)
    }

    /** Runs the pipeline over the samples and returns the number of counted reps. */
    fun countReps(
        samples: List<Sample>,
        exercise: Exercise,
        thresholds: RepThresholds,
        source: SignalSource = SignalSource.FACE,
        config: SignalConfig = SignalConfig.default,
    ): Int {
        val pipeline = SignalPipeline(exercise, thresholds, source, config)
        var reps = 0
        for (sample in samples) {
            val output = pipeline.process(sample.value, sample.confidence, sample.t)
            if (output.event is RepDetectorEvent.RepCompleted) reps += 1
        }
        return reps
    }
}
