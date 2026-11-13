package dev.nabos.marco.core

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Perms {
    val BLE = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    /** Everything we ask for at runtime in one go. Camera is asked by the QR scanner itself. */
    val RUNTIME: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) BLE + Manifest.permission.POST_NOTIFICATIONS
        else BLE

    fun hasBle(context: Context) = BLE.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun hasDndAccess(context: Context) =
        context.getSystemService(NotificationManager::class.java)!!.isNotificationPolicyAccessGranted

    fun ignoresBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(context.packageName)
}
