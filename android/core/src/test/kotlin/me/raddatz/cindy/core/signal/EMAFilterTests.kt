package me.raddatz.cindy.core.signal

import org.junit.Test
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
    fun resetForgetsState() {
        val filter = EMAFilter(alpha = 0.3f)
        filter.update(5f)
        filter.reset()
        assertNull(filter.value)
        assertEquals(2f, filter.update(2f))
    }
}
