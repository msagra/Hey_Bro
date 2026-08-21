package com.example.hey_bro

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val METHOD_CHANNEL = "com.example.hey_bro/assistant"
    private val EVENT_CHANNEL  = "com.example.hey_bro/events"

    private val MIC_PERMISSION_CODE       = 1001
    private val CAMERA_PERMISSION_CODE    = 1002
    private val NOTIFICATION_PERMISSION_CODE = 1003

    private lateinit var prefs: SharedPreferences

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)

        // ── Method Channel ────────────────────────────────────────────────────
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startAssistantService" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_CODE
                            )
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        startAssistantService()
                        prefs.edit().putBoolean(BootReceiver.KEY_AUTOSTART, true).apply()
                        result.success(true)
                    }
                    "stopAssistantService" -> {
                        stopAssistantService()
                        prefs.edit().putBoolean(BootReceiver.KEY_AUTOSTART, false).apply()
                        result.success(true)
                    }
                    "checkPermissions"       -> result.success(checkPermissions())
                    "requestNotificationPermission" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_CODE
                            )
                        }
                        result.success(true)
                    }
                    "getActiveWakeEngine"    -> result.success(CoreVoiceService.activeEngineName)
                    "triggerAssistant"       -> {
                        val i = Intent(this, CoreVoiceService::class.java).also { it.action = "TRIGGER_MANUAL" }
                        startService(i)
                        result.success(true)
                    }
                    "requestMicPermission"    -> {
                        ActivityCompat.requestPermissions(
                            this, arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_CODE
                        )
                        result.success(true)
                    }
                    "requestCameraPermission" -> {
                        ActivityCompat.requestPermissions(
                            this, arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
                        )
                        result.success(true)
                    }
                    "requestAccessibilityPermission" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.success(true)
                    }
                    "requestOverlayPermission" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        result.success(true)
                    }
                    "requestBatteryExemption" -> {
                        requestBatteryExemption()
                        result.success(true)
                    }
                    "getAutostartEnabled" -> result.success(prefs.getBoolean(BootReceiver.KEY_AUTOSTART, false))
                    "setAutostartEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        prefs.edit().putBoolean(BootReceiver.KEY_AUTOSTART, enabled).apply()
                        result.success(true)
                    }
                    "setSttDelays" -> {
                        // expects map with ackMs, startMs, postTtsMs
                        val map = call.arguments as? Map<String, Any?>
                        val ack = (map?.get("ackMs") as? Number)?.toLong() ?: 400L
                        val start = (map?.get("startMs") as? Number)?.toLong() ?: 2200L
                        val post = (map?.get("postTtsMs") as? Number)?.toLong() ?: 800L
                        prefs.edit().putLong("stt_ack_ms", ack).putLong("stt_start_ms", start).putLong("stt_post_tts_ms", post).apply()
                        result.success(true)
                    }
                    "getSttDelays" -> {
                        val ack = prefs.getLong("stt_ack_ms", 400L)
                        val start = prefs.getLong("stt_start_ms", 2200L)
                        val post = prefs.getLong("stt_post_tts_ms", 800L)
                        val map = mapOf("ackMs" to ack, "startMs" to start, "postTtsMs" to post)
                        result.success(map)
                    }
                    else -> result.notImplemented()
                }
            }

        // ── Event Channel ─────────────────────────────────────────────────────
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    CoreVoiceService.eventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    CoreVoiceService.eventSink = null
                }
            })
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startAssistantService()
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, CoreVoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopAssistantService() {
        stopService(Intent(this, CoreVoiceService::class.java))
    }

    private fun checkPermissions(): Map<String, Boolean> {
        val mic     = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera  = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val a11y    = isAccessibilityEnabled(this, DeviceOperatorService::class.java)
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        else true
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        return mapOf(
            "microphone"    to mic,
            "camera"        to camera,
            "overlay"       to overlay,
            "accessibility" to a11y,
            "battery"       to battery,
            "notification"  to notification
        )
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .also { it.data = Uri.parse("package:$packageName") }
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun isAccessibilityEnabled(context: Context, cls: Class<*>): Boolean {
        val expected = android.content.ComponentName(context, cls)
        val am       = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == expected.packageName && info.name == expected.className
            }
    }
}
