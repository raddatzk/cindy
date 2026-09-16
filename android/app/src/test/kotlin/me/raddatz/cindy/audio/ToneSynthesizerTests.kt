package me.raddatz.cindy.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToneSynthesizerTests {
    @Test
    fun tonesHaveTheIOSLengths() {
        assertEquals(3_528, ToneSynthesizer.beep().size) // 80 ms at 44.1 kHz
        assertEquals(6_615, ToneSynthesizer.high().size) // 150 ms
        assertEquals(3 * 6_615, ToneSynthesizer.end().size) // three high tones back to back
    }

    @Test
    fun fadesInAndOutOverFiveMilliseconds() {
        val tone = ToneSynthesizer.beep()
        val fade = 220 // 5 ms at 44.1 kHz
        assertEquals(0, tone[0].toInt())
        // Inside the fades the amplitude stays below the ramp; in the middle it reaches 0.8.
        assertTrue(tone.take(fade / 10).all { abs(it.toInt()) <= Short.MAX_VALUE * 0.8 * 0.1 + 1 })
        assertTrue(tone.takeLast(fade / 10).all { abs(it.toInt()) <= Short.MAX_VALUE * 0.8 * 0.1 + 1 })
        val peak = tone.maxOf { it.toInt() }
        assertTrue(peak > Short.MAX_VALUE * 0.79 && peak <= Short.MAX_VALUE * 0.8)
    }

    @Test
    fun samplesFollowTheSine() {
        val tone = ToneSynthesizer.tone(1_000.0, 0.08)
        val i = 1_000
        val expected = 0.8 * sin(2 * PI * 1_000 * i / 44_100.0) * Short.MAX_VALUE
        assertEquals(expected.toInt(), tone[i].toInt())
        val end = ToneSynthesizer.end()
        val high = ToneSynthesizer.high()
        assertEquals(high[10], end[high.size + 10])
    }
}
