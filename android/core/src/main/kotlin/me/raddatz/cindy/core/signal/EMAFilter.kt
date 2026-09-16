package me.raddatz.cindy.core.signal

/**
 * Exponential moving average. `alpha` = 1 passes the input through unchanged.
 *
 * [alpha] is the factor per reference frame (30 fps); pass the frame interval to [update] so the
 * smoothing covers the same time at any frame rate (see [FrameTiming.alpha]).
 */
class EMAFilter(val alpha: Float) {
    var value: Float? = null
        private set

    /**
     * Feeds one sample and returns the smoothed value. [frameInterval] is the time in seconds since
     * the previous camera frame; `null` applies [alpha] per sample.
     */
    fun update(x: Float, frameInterval: Double? = null): Float {
        val v = value
        if (v != null) {
            val next = v + FrameTiming.alpha(alpha, frameInterval) * (x - v)
            value = next
            return next
        }
        value = x
        return x
    }

    fun reset() {
        value = null
    }
}
