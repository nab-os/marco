package dev.nabos.marco.findable

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import dev.nabos.marco.acoustic.ChirpPlayer
import dev.nabos.marco.acoustic.Chirps

/**
 * Makes the phone as findable as possible for a short time: alarm-stream sound at
 * max volume (alarm stream ignores the ringer's silent/vibrate mode), vibration,
 * and a blinking flashlight. Everything is restored on [stop].
 *
 * Call from the main thread.
 */
class Ringer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)!!
    private val notifications = context.getSystemService(NotificationManager::class.java)!!
    private val camera = context.getSystemService(CameraManager::class.java)!!
    private val vibrator = context.getSystemService(VibratorManager::class.java)!!.defaultVibrator
    private val power = context.getSystemService(PowerManager::class.java)!!

    private var player: ChirpPlayer? = null
    private var toneFallback: ToneGenerator? = null
    private var savedAlarmVolume: Int? = null
    private var savedInterruptionFilter: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val torchId: String? = findTorch()
    private var torchOn = false

    var onStateChanged: ((ringing: Boolean) -> Unit)? = null

    var isRinging = false
        private set

    private val autoStop = Runnable { stop() }

    /** Start ringing, or extend the current ring, for [durationMs]. */
    fun ring(durationMs: Long) {
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, durationMs)
        if (isRinging) return
        isRinging = true

        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marco:ring").apply {
            acquire(durationMs + 5_000)
        }
        punchThroughDnd()
        maxAlarmVolume()
        startSound()
        startVibration()
        handler.post(torchBlink)
        onStateChanged?.invoke(true)
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        if (!isRinging) return
        isRinging = false

        handler.removeCallbacks(torchBlink)
        handler.removeCallbacks(toneLoop)
        setTorch(false)
        runCatching { vibrator.cancel() }
        player?.stop()
        player = null
        toneFallback?.release()
        toneFallback = null
        restoreAlarmVolume()
        restoreDnd()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        onStateChanged?.invoke(false)
    }

    // --- sound -------------------------------------------------------------

    /**
     * The alarm is the ranging chirp train ([Chirps]): loud, and the finder times
     * each chirp to place this phone to centimetres.
     */
    private fun startSound() {
        try {
            player = ChirpPlayer(context, Chirps.rangingPeriod(), raiseVolume = false).also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "Chirp track failed, using tone fallback", e)
            player = null
            toneFallback = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            handler.post(toneLoop)
        }
    }

    private val toneLoop = object : Runnable {
        override fun run() {
            toneFallback?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            handler.postDelayed(this, 900)
        }
    }

    private fun maxAlarmVolume() {
        try {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't raise alarm volume", e)
            savedAlarmVolume = null
        }
    }

    private fun restoreAlarmVolume() {
        val v = savedAlarmVolume ?: return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
        savedAlarmVolume = null
    }

    /**
     * If the user granted Do Not Disturb access and DND currently blocks alarms
     * ("Total silence" or a priority mode), temporarily switch to "Alarms only".
     * Best effort: on newer Android versions with DND "modes", the user's own
     * mode may still win.
     */
    private fun punchThroughDnd() {
        if (!notifications.isNotificationPolicyAccessGranted) return
        val current = notifications.currentInterruptionFilter
        if (current == NotificationManager.INTERRUPTION_FILTER_ALL ||
            current == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
            current == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) return
        try {
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            savedInterruptionFilter = current
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't change DND filter", e)
        }
    }

    private fun restoreDnd() {
        val f = savedInterruptionFilter ?: return
        runCatching { notifications.setInterruptionFilter(f) }
        savedInterruptionFilter = null
    }

    // --- vibration -----------------------------------------------------------

    private fun startVibration() {
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect)
        }
    }

    // --- flashlight ----------------------------------------------------------

    private val torchBlink = object : Runnable {
        override fun run() {
            setTorch(!torchOn)
            handler.postDelayed(this, 350)
        }
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        try {
            camera.setTorchMode(id, on)
            torchOn = on
        } catch (e: Exception) {
            // Camera in use by another app, or torch unavailable. Not fatal.
            torchOn = false
        }
    }

    private fun findTorch(): String? = runCatching {
        camera.cameraIdList.firstOrNull { id ->
            camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    companion object {
        private const val TAG = "Marco.Ringer"
    }
}
