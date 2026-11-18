package dev.nabos.marco.findable

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small persistent log of the findable service's life (`files/findable.log`):
 * when it started, stopped and why. The system's own records only say a process
 * died while cached, not why the service had already gone.
 */
object ServiceLog {
    private const val TAG = "Marco.Findable"
    private const val MAX_BYTES = 200_000L

    @Synchronized
    fun log(context: Context, message: String) {
        Log.i(TAG, message)
        runCatching {
            val file = File(context.getExternalFilesDir(null), "findable.log")
            if (file.length() > MAX_BYTES) {
                // Keep the second half.
                val lines = file.readLines()
                file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
            file.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} $message\n")
        }
    }
}
