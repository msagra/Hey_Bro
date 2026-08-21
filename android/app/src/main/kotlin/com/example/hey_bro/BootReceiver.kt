package com.example.hey_bro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Automatically restarts CoreVoiceService after device reboot.
 * Requires RECEIVE_BOOT_COMPLETED permission (already in Manifest).
 *
 * The service only auto-starts if the user had previously enabled it
 * (tracked via SharedPreferences).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG   = "BootReceiver"
        const val PREFS_NAME    = "hey_bro_prefs"
        const val KEY_AUTOSTART = "autostart_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.d(TAG, "Boot completed – checking autostart preference")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autostart = prefs.getBoolean(KEY_AUTOSTART, false)

        if (!autostart) {
            Log.d(TAG, "Autostart disabled – skipping service restart")
            return
        }

        Log.i(TAG, "Autostart enabled – starting CoreVoiceService")
        val serviceIntent = Intent(context, CoreVoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
