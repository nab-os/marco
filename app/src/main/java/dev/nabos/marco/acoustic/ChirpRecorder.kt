package dev.nabos.marco.acoustic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class RecordingStats(
    val recording: Boolean = false,
    val seconds: Float = 0f,
    /** Loudest sample so far, 0..1. */
    val peak: Float = 0f,
    val source: String = "",
    val file: String? = null,
    val error: String? = null,
)

/**
 * Records raw audio to a WAV in `files/acoustic/`, unprocessed where the phone
 * offers it (no gain control or noise suppression, which would eat the chirps),
 * 48 kHz mono 16-bit. Needs RECORD_AUDIO.
 */
class ChirpRecorder(private val context: Context) {
    private val _stats = MutableStateFlow(RecordingStats())
    val stats: StateFlow<RecordingStats> = _stats.asStateFlow()
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(seconds: Int) {
        if (running) return
        val audio = context.getSystemService(AudioManager::class.java)!!
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuffer = AudioRecord.getMinBufferSize(Chirps.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(source, Chirps.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 4, Chirps.SAMPLE_RATE))
        }.getOrElse { return fail(it.message ?: "AudioRecord") }
        if (record.state != AudioRecord.STATE_INITIALIZED) return fail("The microphone wouldn't open at 48 kHz.")
        val dir = File(context.getExternalFilesDir(null), "acoustic").apply { mkdirs() }
        val file = File(dir, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".wav")
        running = true
        _stats.value = RecordingStats(recording = true, source = if (unprocessed) "unprocessed" else "voice recognition", file = file.name)
        thread = Thread {
            val out = RandomAccessFile(file, "rw")
            out.write(ByteArray(44)) // header, written at the end
            val buffer = ShortArray(Chirps.SAMPLE_RATE / 10)
            val bytes = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var total = 0L
            var peak = 0
            record.startRecording()
            while (running && total < seconds.toLong() * Chirps.SAMPLE_RATE) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) break
                bytes.clear()
                for (i in 0 until n) {
                    bytes.putShort(buffer[i])
                    if (abs(buffer[i].toInt()) > peak) peak = abs(buffer[i].toInt())
                }
                out.write(bytes.array(), 0, n * 2)
                total += n
                _stats.update { it.copy(seconds = total.toFloat() / Chirps.SAMPLE_RATE, peak = peak.toFloat() / Short.MAX_VALUE) }
            }
            record.stop()
            record.release()
            writeHeader(out, total * 2)
            out.close()
            running = false
            _stats.update { it.copy(recording = false) }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    private fun fail(message: String) {
        _stats.update { it.copy(recording = false, error = message) }
    }

    private fun writeHeader(out: RandomAccessFile, dataBytes: Long) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(Chirps.SAMPLE_RATE).putInt(Chirps.SAMPLE_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        out.seek(0)
        out.write(h.array())
    }
}
