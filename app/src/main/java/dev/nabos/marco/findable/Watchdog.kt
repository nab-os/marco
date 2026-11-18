package dev.nabos.marco.findable

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Brings the findable service back if it has gone: an alarm every quarter
 * hour (allowed while idle, as the app is exempt from battery optimisation)
 * runs [FindableService.sync], which starts the service when it should be
 * running and is harmless when it is. Chains itself; [FindableService.sync]
 * cancels it when the phone shouldn't be findable.
 */
class Watchdog : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceLog.log(context, "watchdog: running=${FindableService.status.value.running}")
        FindableService.sync(context)
    }

    companion object {
        private const val EVERY_MS = 15 * 60_000L

        /** The next check in [inMs], then every quarter hour. */
        fun schedule(context: Context, inMs: Long = EVERY_MS) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + inMs, pending(context))
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        }

        private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, Watchdog::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
