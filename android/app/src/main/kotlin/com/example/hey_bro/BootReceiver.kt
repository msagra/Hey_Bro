package com.example.hey_bro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Handles device boot and restores Hey Bro voice service.
 *
 * Note on Android 15 (API 35): FOREGROUND_SERVICE_MICROPHONE cannot be
 * newly started from background BOOT_COMPLETED. If the platform blocks direct
 * FGS start, BootReceiver posts a high-priority notification to resume listening.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "hey_bro_prefs"
        const val KEY_AUTOSTART = "autostart_enabled"
        private const val BOOT_CHANNEL_ID = "HeyBroBootChannel"
        private const val BOOT_NOTIFICATION_ID = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d(TAG, "Boot completed – checking autostart preference")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autostart = prefs.getBoolean(KEY_AUTOSTART, false)

        if (!autostart) {
            Log.d(TAG, "Autostart disabled – skipping service restart")
            return
        }

        Log.i(TAG, "Autostart enabled – attempting to resume CoreVoiceService")
        val serviceIntent = Intent(context, CoreVoiceService::class.java)

        var startedDirectly = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            startedDirectly = true
            Log.i(TAG, "CoreVoiceService started directly from boot")
        } catch (e: Exception) {
            Log.w(TAG, "Direct FGS start blocked on Android 15 / API 35: ${e.message}")
        }

        // Android 15 fallback: Post notification to resume listening
        if (!startedDirectly) {
            postResumeNotification(context)
        }
    }

    private fun postResumeNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                "Hey Bro Resume",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tap to resume Hey Bro voice assistant"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("resume_assistant", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Bro Voice Assistant")
            .setContentText("Tap to resume voice listening")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(BOOT_NOTIFICATION_ID, notification)
    }
}
