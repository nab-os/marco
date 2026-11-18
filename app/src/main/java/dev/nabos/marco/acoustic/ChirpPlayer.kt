package dev.nabos.marco.acoustic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Loops one period of PCM on the alarm stream, sample-exactly (a static track
 * looping in hardware), so the chirps keep the pace of this phone's audio
 * clock. Raises the alarm volume to full meanwhile unless told not to.
 */
class ChirpPlayer(context: Context, private val pcm: ShortArray = Chirps.period(), private val raiseVolume: Boolean = true) {
    private val audio = context.getSystemService(AudioManager::class.java)!!
    private var track: AudioTrack? = null
    private var savedVolume: Int? = null

    val playing: Boolean get() = track != null

    /** Throws if the audio track can't be made. */
    fun start() {
        if (track != null) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Chirps.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        if (raiseVolume) {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        }
        t.play()
        track = t
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        savedVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedVolume = null
    }
}
