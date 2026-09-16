package me.raddatz.cindy.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread

/**
 * Beeps (iOS: `AudioFeedback`). The phone lies on the floor during a workout, so these tones are
 * the whole feedback channel.
 *
 * Audibility: iOS uses the `.playback` session category, which ignores the ring/silent switch.
 * The Android counterpart is [AudioAttributes.USAGE_MEDIA]: the media stream is not muted by the
 * silent or vibrate ringer mode and follows the media volume the athlete controls anyway.
 * `USAGE_ASSISTANCE_SONIFICATION` would sound more fitting, but it maps to the system stream, which
 * silent mode mutes; `USAGE_ALARM` would bypass the athlete's volume choice. The content type stays
 * `SONIFICATION`.
 *
 * Mixing: iOS mixes with other audio and ducks it while its session is active. [prepare] requests
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, so music keeps playing, quieter, and [release] (called by
 * the engines when a workout or calibration ends) abandons it again — the scope of the iOS session
 * activation.
 *
 * Interruptions: a call takes the focus transiently; the tones are skipped until the focus comes
 * back, like iOS skips scheduling into a stopped engine until the interruption ends. Another app
 * taking the focus for good does not silence the cues — they are mixable, as on iOS.
 *
 * The tones are synthesized once ([ToneSynthesizer]) into static `AudioTrack`s. A tone started
 * while the same tone is still playing restarts it; different tones overlap instead of queueing.
 *
 * Use one instance per engine: each holds its own focus request, so a screen that is torn down
 * after the next one started cannot release the audio of the new one.
 */
class AudioFeedback(context: Context) : AudioCues {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Switches all tones off (e.g. a settings toggle). */
    @Volatile
    var isEnabled: Boolean = true

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var beepTrack: AudioTrack? = null
    private var highTrack: AudioTrack? = null
    private var endTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Between [prepare] and [release]. */
    private var sessionActive = false
    private var focusHeld = false

    /** A call holds the audio: tones are skipped until the focus comes back. */
    private var callInterrupted = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                callInterrupted = false
            }
            // A call: nothing would be heard, and tones mixed into the call are worse than none.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> callInterrupted = true
            // Another app took the focus for good: the cues keep mixing; the next prepare() asks again.
            AudioManager.AUDIOFOCUS_LOSS -> focusHeld = false
            else -> Unit
        }
    }

    /** Builds the tones once and requests audio focus. Safe to call again. */
    @MainThread
    override fun prepare() {
        mainHandler.removeCallbacks(releaseTracks)
        buildTracks()
        sessionActive = true
        if (focusHeld) return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
            .also { focusRequest = it }
        focusHeld = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // Refused only while a call holds the audio; the next prepare() tries again.
        callInterrupted = !focusHeld
    }

    override fun beep() = play(beepTrack)

    override fun goSignal() = play(highTrack)

    override fun endSignal() = play(endTrack)

    /** Tones already playing finish; other apps' audio is no longer ducked. */
    @MainThread
    override fun release() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        sessionActive = false
        focusHeld = false
        callInterrupted = false
        // Long enough for the end signal (450 ms) that usually comes right before.
        mainHandler.removeCallbacks(releaseTracks)
        mainHandler.postDelayed(releaseTracks, TRACK_RELEASE_DELAY_MILLIS)
    }

    private val releaseTracks = Runnable {
        listOfNotNull(beepTrack, highTrack, endTrack).forEach { it.release() }
        beepTrack = null
        highTrack = null
        endTrack = null
    }

    private fun buildTracks() {
        if (beepTrack == null) beepTrack = makeTrack(ToneSynthesizer.beep())
        if (highTrack == null) highTrack = makeTrack(ToneSynthesizer.high())
        if (endTrack == null) endTrack = makeTrack(ToneSynthesizer.end())
    }

    private fun makeTrack(samples: ShortArray): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynthesizer.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .also { it.write(samples, 0, samples.size) }
    } catch (_: Exception) {
        // Audio is best effort.
        null
    }

    private fun play(track: AudioTrack?) {
        if (!isEnabled || !sessionActive || callInterrupted || track == null) return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
            // Best effort.
        }
    }

    private companion object {
        const val TRACK_RELEASE_DELAY_MILLIS = 1_000L
    }
}
