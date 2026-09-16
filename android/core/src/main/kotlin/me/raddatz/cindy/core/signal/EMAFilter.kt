package me.raddatz.cindy.core.signal

/** Exponential moving average. `alpha` = 1 passes the input through unchanged. */
class EMAFilter(val alpha: Float) {
    var value: Float? = null
        private set

    /** Feeds one sample and returns the smoothed value. */
    fun update(x: Float): Float {
        val v = value
        if (v != null) {
            val next = v + alpha * (x - v)
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
