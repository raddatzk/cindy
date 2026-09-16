package me.raddatz.cindy.audio

import kotlin.math.PI
import kotlin.math.sin

/** The feedback tones the engines play; [AudioFeedback] in the app, a recorder in tests. */
interface AudioCues {
    /** Gets audio ready (and audible) for a session. Safe to call again. */
    fun prepare()

    /** Short tick for every counted rep and every countdown second. */
    fun beep()

    /** Single higher tone: "go", and the last rep of an exercise. */
    fun goSignal()

    /** Three high tones: workout or calibration finished. */
    fun endSignal()

    /** Ends the session [prepare] started: other apps' audio is no longer ducked. */
    fun release()
}

/** The synthesized tones of iOS `AudioFeedback.makeTone`, as 16-bit PCM. */
object ToneSynthesizer {
    const val SAMPLE_RATE: Int = 44_100

    /** 1 kHz, 80 ms: [AudioCues.beep]. */
    fun beep(sampleRate: Int = SAMPLE_RATE): ShortArray = tone(1_000.0, 0.08, sampleRate)

    /** 1.5 kHz, 150 ms: [AudioCues.goSignal]. */
    fun high(sampleRate: Int = SAMPLE_RATE): ShortArray = tone(1_500.0, 0.15, sampleRate)

    /**
     * [AudioCues.endSignal]: iOS schedules the high tone three times on one player, which plays
     * them back to back; here the three are one buffer.
     */
    fun end(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val high = high(sampleRate)
        return ShortArray(high.size * 3) { high[it % high.size] }
    }

    /** Sine at 0.8 amplitude with 5 ms linear fade-in and fade-out, so the tone does not click. */
    fun tone(frequency: Double, duration: Double, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val frameCount = (duration * sampleRate).toInt()
        val fade = (0.005 * sampleRate).toInt()
        return ShortArray(frameCount) { i ->
            val t = i.toDouble() / sampleRate
            var amplitude = 0.8
            if (i < fade) amplitude *= i.toDouble() / fade
            if (i > frameCount - fade) amplitude *= (frameCount - i).toDouble() / fade
            (amplitude * sin(2 * PI * frequency * t) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
