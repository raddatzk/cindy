package me.raddatz.cindy.core.signal

/**
 * Running median over the last `window` samples; removes single-frame outliers
 * (e.g. a body-pose frame with the shoulders swapped for other joints).
 */
class MedianFilter(window: Int) {
    val window: Int = maxOf(window, 1)
    private val samples = ArrayDeque<Float>()

    fun update(x: Float): Float {
        samples.addLast(x)
        while (samples.size > window) samples.removeFirst()
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
