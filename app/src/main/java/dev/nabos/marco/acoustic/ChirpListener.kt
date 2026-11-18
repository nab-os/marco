package dev.nabos.marco.acoustic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log

/**
 * Listens for the ranging chirps and hands each arrival on with its time on
 * the [SystemClock.elapsedRealtimeNanos] clock, the one the tracking uses, so
 * it can be placed on the finder's path. Needs RECORD_AUDIO.
 */
class ChirpListener(private val context: Context, private val onArrival: (tNs: Long, marginDb: Double) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var error: String? = null
        private set
    @Volatile var heard = 0
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val audio = context.getSystemService(AudioManager::class.java)!!
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuffer = AudioRecord.getMinBufferSize(Chirps.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(source, Chirps.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 4, Chirps.SAMPLE_RATE))
        }.getOrElse {
            error = it.message ?: "AudioRecord"
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            error = "The microphone wouldn't open at 48 kHz."
            return
        }
        running = true
        heard = 0
        error = null
        thread = Thread({
            val detector = ChirpDetector()
            val buffer = ShortArray(Chirps.SAMPLE_RATE / 10)
            val stamp = AudioTimestamp()
            var read = 0L
            // Stream position to clock: from the audio timestamp when there is one, else from when a block came in.
            var refFrame = 0L
            var refNs = 0L
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) break
                val bootMinusMono = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    refFrame = stamp.framePosition
                    refNs = stamp.nanoTime + bootMinusMono
                } else {
                    refFrame = read + n
                    refNs = SystemClock.elapsedRealtimeNanos()
                }
                read += n
                for (a in detector.feed(buffer, n)) {
                    val tNs = refNs + ((a.sample - refFrame) * 1e9 / Chirps.SAMPLE_RATE).toLong()
                    heard++
                    onArrival(tNs, a.marginDb)
                }
            }
            runCatching { record.stop() }
            record.release()
        }, "marco-chirps").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    companion object {
        private const val TAG = "Marco.Chirps"
    }
}
