package me.raddatz.cindy.core.signal

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EMAFilterTests {
    @Test
    fun firstSamplePassesThrough() {
        val filter = EMAFilter(alpha = 0.3f)
        assertEquals(10f, filter.update(10f))
    }

    @Test
    fun convergesTowardsInput() {
        val filter = EMAFilter(alpha = 0.3f)
        filter.update(0f)
        var last = 0f
        repeat(50) { last = filter.update(1f) }
        assertTrue(last > 0.99f)
    }

    @Test
    fun alphaOneIsIdentity() {
        val filter = EMAFilter(alpha = 1f)
        filter.update(5f)
        assertEquals(7f, filter.update(7f))
    }

    @Test
    fun referenceFrameIntervalMatchesThePerSampleFilter() {
        val perSample = EMAFilter(alpha = 0.3f)
        val exact = EMAFilter(alpha = 0.3f)
        val jittered = EMAFilter(alpha = 0.3f)
        for (i in 0 until 90) {
            val x = if (i % 30 < 15) 0.05f else 0.20f
            val expected = perSample.update(x)
            assertTrue(abs(expected - exact.update(x, if (i == 0) null else 1.0 / 30)) < 1e-6f, "frame $i")
            // Recorded iPhone timestamps lie 0.0333 or 0.0334 s apart.
            val interval = if (i % 3 == 0) 0.0334 else 0.0333
            assertTrue(abs(expected - jittered.update(x, if (i == 0) null else interval)) < 1e-3f, "jittered frame $i")
        }
    }

    @Test
    fun oneStepAtFifteenFpsEqualsTwoStepsAtThirty() {
        val thirty = EMAFilter(alpha = 0.3f)
        thirty.update(0f)
        thirty.update(1f, frameInterval = 1.0 / 30)
        val twoSteps = thirty.update(1f, frameInterval = 1.0 / 30)
        val fifteen = EMAFilter(alpha = 0.3f)
        fifteen.update(0f)
        val oneStep = fifteen.update(1f, frameInterval = 2.0 / 30)
        assertTrue(abs(twoSteps - 0.51f) < 1e-5f)
        assertTrue(abs(oneStep - twoSteps) < 1e-5f)
    }

    @Test
    fun unusableIntervalsKeepTheAlphaAndLongOnesAreCapped() {
        assertEquals(0.3f, FrameTiming.alpha(0.3f, null))
        assertEquals(0.3f, FrameTiming.alpha(0.3f, 0.0))
        assertEquals(0.3f, FrameTiming.alpha(0.3f, -1.0))
        assertEquals(1f, FrameTiming.alpha(1f, 0.2))
        // A camera stall moves the filter as far as `maxFrameInterval` does, not all the way.
        assertEquals(FrameTiming.alpha(0.3f, FrameTiming.maxFrameInterval), FrameTiming.alpha(0.3f, 5.0))
        assertTrue(FrameTiming.alpha(0.3f, 5.0) < 1f)
    }

    @Test
    fun resetForgetsState() {
        val filter = EMAFilter(alpha = 0.3f)
        filter.update(5f)
        filter.reset()
        assertNull(filter.value)
        assertEquals(2f, filter.update(2f))
    }
}
