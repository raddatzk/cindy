package me.raddatz.cindy.core.calibration

import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.signal.FrameTiming
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.core.signal.RepThresholds
import java.time.Clock
import kotlin.math.abs

/** One smoothed, confident sample of the calibration trace. [timestamp] in seconds. */
data class CalibrationSample(val timestamp: Double, val value: Float)

/** Why a calibration failed. The iOS `message` texts are quoted on each case. */
sealed interface CalibrationFailure {
    /**
     * Not enough confident frames to even measure a baseline (face signal).
     * "No face detected. Check light and position: your face has to be right above the phone."
     */
    data object NoFace : CalibrationFailure

    /**
     * Same for the body-pose signal, or no body at all next to the brightness signal.
     * "No person detected. Check light and position: your shoulders have to be above the camera and in the picture."
     */
    data object NoPerson : CalibrationFailure

    /**
     * The signal never swung far enough from the baseline.
     * "The movement was too weak in the signal. Keep your face right above the phone; for pull-ups put the phone directly under the bar."
     */
    data object TooWeak : CalibrationFailure

    /**
     * The brightness barely changed: too little contrast between the athlete and the background.
     * "The picture hardly got darker during the squat. Keep your toes right behind the phone; a bright ceiling light above you helps, a bright top on a bright ceiling does not."
     */
    data object LowContrast : CalibrationFailure

    /**
     * The signal moved but did not come back to the start position.
     * "The movement was detected but the start position was not reached again. Please start and end in the start position."
     */
    data object NoReturn : CalibrationFailure

    /**
     * A cycle was found but was too fast or too slow ([duration] in seconds).
     * Swift: "The rep took \(duration.formattedDecimal(1)) s. Plausible is \((0.5).formattedDecimal(1))–\((5.0).formattedDecimal(0)) s. Please repeat at a normal pace."
     * (iOS key "The rep took %@ s. Plausible is %@–%@ s. Please repeat at a normal pace.")
     */
    data class ImplausibleDuration(val duration: Double) : CalibrationFailure
}

/**
 * Pure analysis of a calibration trace. Fed with the growing trace after every frame; returns a
 * calibration once one full cycle is visible.
 *
 * Algorithm: median of the first `calibrationBaselineDuration` seconds is the rest baseline. The
 * direction is whichever side of the baseline the signal swung further to. Thresholds follow the
 * standard margin rule on the observed extremes. The cycle is complete when the signal has crossed
 * the far threshold and afterwards re-entered the rest band.
 *
 * [clock] stamps `calibratedAt`.
 */
class CalibrationAnalyzer(
    val config: SignalConfig = SignalConfig.default,
    val source: SignalSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Returns a calibration once the trace contains one plausible full cycle. */
    fun evaluate(trace: List<CalibrationSample>): ExerciseCalibration? {
        val stats = stats(trace) ?: return null
        if (!stats.excursionOK) return null
        val cycle = findCycle(trace, stats) ?: return null
        if (!(cycle.duration >= config.minRepDuration && cycle.duration <= config.maxRepDuration)) return null
        return ExerciseCalibration(
            source = source,
            minValue = stats.min,
            maxValue = stats.max,
            baseline = stats.baseline,
            low = stats.thresholds.low,
            high = stats.thresholds.high,
            direction = stats.direction,
            repDuration = cycle.duration,
            calibratedAt = clock.instant(),
        )
    }

    /**
     * Hold calibration (plank): after `calibrationHoldDuration` seconds of confident samples the
     * band is the observed min/max widened by `calibrationHoldBandMargin` of the mean (at least
     * the observed spread).
     */
    fun evaluateHold(trace: List<CalibrationSample>): ExerciseCalibration? {
        val first = trace.firstOrNull() ?: return null
        val last = trace.last()
        if (!(last.timestamp - first.timestamp >= config.calibrationHoldDuration &&
                trace.size >= config.calibrationBaselineMinSamples)
        ) {
            return null
        }
        var sum = 0f
        for (sample in trace) sum += sample.value
        val mean = sum / trace.size.toFloat()
        val observedMin = trace.minOf { it.value }
        val observedMax = trace.maxOf { it.value }
        val halfBand = maxOf((observedMax - observedMin) / 2, config.calibrationHoldBandMargin * abs(mean))
        return ExerciseCalibration(
            source = source, minValue = observedMin, maxValue = observedMax, baseline = mean,
            low = mean - halfBand, high = mean + halfBand, direction = RepDirection.PEAK,
            repDuration = config.calibrationHoldDuration, calibratedAt = clock.instant(),
        )
    }

    fun diagnoseHold(trace: List<CalibrationSample>): CalibrationFailure =
        if (trace.size < config.calibrationBaselineMinSamples) noSubject else CalibrationFailure.NoReturn

    /** Best explanation for why [evaluate] has not succeeded (used on timeout). */
    fun diagnose(trace: List<CalibrationSample>): CalibrationFailure {
        val stats = stats(trace) ?: return noSubject
        if (!stats.excursionOK) {
            return if (source == SignalSource.BRIGHTNESS) CalibrationFailure.LowContrast else CalibrationFailure.TooWeak
        }
        val cycle = findCycle(trace, stats) ?: return CalibrationFailure.NoReturn
        return CalibrationFailure.ImplausibleDuration(cycle.duration)
    }

    // Internals

    private val noSubject: CalibrationFailure
        get() = if (source == SignalSource.FACE) CalibrationFailure.NoFace else CalibrationFailure.NoPerson

    data class TraceStats(
        val baseline: Float,
        val min: Float,
        val max: Float,
        val direction: RepDirection,
        val excursion: Float,
        val excursionOK: Boolean,
        val thresholds: RepThresholds,
        val baselineEndIndex: Int,
    )

    fun stats(trace: List<CalibrationSample>): TraceStats? {
        val first = trace.firstOrNull() ?: return null
        val baselineEnd = first.timestamp + config.calibrationBaselineDuration
        val baselineSamples = trace.takeWhile { it.timestamp <= baselineEnd }
        // A count at the reference frame rate: a slower camera fits fewer samples in the window.
        val minSamples = FrameTiming.scaledCount(
            config.calibrationBaselineMinSamples, trace.map { it.timestamp }, FrameTiming.minStableSamples,
        )
        if (baselineSamples.size < minSamples) return null
        val baseline = median(baselineSamples.map { it.value })
        val values = trace.map { it.value }
        val minValue = values.minOrNull() ?: baseline
        val maxValue = values.maxOrNull() ?: baseline
        val up = maxValue - baseline
        val down = baseline - minValue
        val required = when (source) {
            SignalSource.FACE -> maxOf(config.calibrationMinRelativeExcursion * abs(baseline), 1e-4f)
            SignalSource.POSE -> config.calibrationMinAbsoluteExcursion
            SignalSource.BRIGHTNESS -> config.calibrationMinBrightnessExcursion
        }
        val direction: RepDirection
        val thresholds: RepThresholds
        if (source == SignalSource.BRIGHTNESS) {
            // Brightness overshoots past rest when the athlete stands back up, so the side the
            // signal left first is the rep, and the thresholds hang off the rest level.
            direction = firstExcursion(values, baseline, reach = maxOf(up, down) / 2) ?: RepDirection.TROUGH
            thresholds = RepThresholds.fromRest(
                baseline = baseline,
                extreme = if (direction == RepDirection.PEAK) maxValue else minValue,
                direction = direction,
                leave = config.restAnchoredLeave,
                peak = config.restAnchoredPeak,
            )
        } else {
            direction = if (up >= down) RepDirection.PEAK else RepDirection.TROUGH
            thresholds = RepThresholds.from(minValue, maxValue, direction, config.thresholdMargin)
        }
        val excursion = if (direction == RepDirection.PEAK) up else down
        return TraceStats(
            baseline = baseline, min = minValue, max = maxValue, direction = direction,
            excursion = excursion, excursionOK = excursion >= required, thresholds = thresholds,
            baselineEndIndex = baselineSamples.size,
        )
    }

    data class Cycle(val startIndex: Int, val extremeIndex: Int, val endIndex: Int, val duration: Double)

    /** Finds departure → extreme → return using the derived thresholds. */
    fun findCycle(trace: List<CalibrationSample>, stats: TraceStats): Cycle? {
        if (trace.isEmpty()) return null
        val values = trace.map { it.value }
        val isPeak = stats.direction == RepDirection.PEAK
        // Index of the extreme (max for peak, min for trough); the first one on ties.
        var extremeIndex = 0
        for (i in 1 until values.size) {
            if (if (isPeak) values[extremeIndex] < values[i] else values[i] < values[extremeIndex]) extremeIndex = i
        }
        val inRest: (Float) -> Boolean =
            if (isPeak) { v -> v < stats.thresholds.low } else { v -> v > stats.thresholds.high }
        // Return: first sample after the extreme that is back in the rest band.
        val endIndex = ((extremeIndex + 1) until values.size).firstOrNull { inRest(values[it]) } ?: return null
        // Departure: last sample before the extreme that was still in the rest band.
        val startIndex = (extremeIndex - 1 downTo 0).firstOrNull { inRest(values[it]) } ?: 0
        val duration = trace[endIndex].timestamp - trace[startIndex].timestamp
        return Cycle(startIndex, extremeIndex, endIndex, duration)
    }

    /** Direction of the first sample that moves `reach` away from the baseline. */
    private fun firstExcursion(values: List<Float>, baseline: Float, reach: Float): RepDirection? {
        if (!(reach > 0)) return null
        val first = values.firstOrNull { abs(it - baseline) >= reach } ?: return null
        return if (first > baseline) RepDirection.PEAK else RepDirection.TROUGH
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0f
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
