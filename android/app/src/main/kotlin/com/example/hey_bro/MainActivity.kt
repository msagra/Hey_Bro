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
    private val COMMUNICATION_PERMISSION_CODE = 1004

    companion object {
        private const val KEY_BACKEND_WS   = "backend_ws"
        private const val DEFAULT_BACKEND_WS = "wss://hey-bro-1zxk.onrender.com/ws/audio"
        private const val SETTINGS_PREFS = "hey_bro_settings"
    }

    private lateinit var bootPrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        bootPrefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        settingsPrefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startAssistantService" -> {
                        val needsMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                        val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        
                        if (needsMic || needsNotif) {
                            val perms = mutableListOf<String>()
                            if (needsMic) perms.add(Manifest.permission.RECORD_AUDIO)
                            if (needsNotif) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            
                            ActivityCompat.requestPermissions(
                                this,
                                perms.toTypedArray(),
                                NOTIFICATION_PERMISSION_CODE
                            )
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        startAssistantService()
                        bootPrefs.edit().putBoolean(BootReceiver.KEY_AUTOSTART, true).apply()
                        result.success(true)
                    }
                    "stopAssistantService" -> {
                        stopAssistantService()
                        bootPrefs.edit().putBoolean(BootReceiver.KEY_AUTOSTART, false).apply()
                        result.success(true)
                    }
                    "checkPermissions"       -> result.success(checkPermissions())
                    "getActiveWakeEngine"    -> result.success(CoreVoiceService.activeEngineName)
                    "triggerAssistant"       -> {
                        val i = Intent(this, CoreVoiceService::class.java).also { it.action = "TRIGGER_MANUAL" }
                        startService(i)
                        result.success(true)
                    }
                    "executeAssistantAction" -> {
                        val payload = call.argument<String>("payload")
                        if (payload.isNullOrBlank()) {
                            result.error("INVALID_ACTION", "Action payload is required", null)
                        } else if (DeviceOperatorService.instance == null) {
                            result.error("ACCESSIBILITY_UNAVAILABLE", "Enable Hey Bro accessibility service first", null)
                        } else {
                            DeviceOperatorService.instance?.executeCommand(payload)
                            result.success(true)
                        }
                    }
                    "requestMicPermission"    -> {
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_CODE
                        )
                        result.success(true)
                    }
                    "requestCameraPermission" -> {
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
                        )
                        result.success(true)
                    }
                    "requestCommunicationPermissions" -> {
                        requestCommunicationPermissions()
                        result.success(true)
                    }
                    "requestNotificationAccess" -> {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    "getLatestNotification" -> result.success(HeyBroNotificationListenerService.latestText())
                    "getAutostartEnabled" -> result.success(bootPrefs.getBoolean(BootReceiver.KEY_AUTOSTART, false))
                    "setAutostartEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        bootPrefs.edit().putBoolean(BootReceiver.KEY_AUTOSTART, enabled).apply()
                        result.success(true)
                    }
                    "getBackendWs" -> {
                        result.success(settingsPrefs.getString(KEY_BACKEND_WS, DEFAULT_BACKEND_WS))
                    }
                    "setBackendWs" -> {
                        val url = call.argument<String>("url") ?: ""
                        settingsPrefs.edit().putString(KEY_BACKEND_WS, url).apply()
                        CoreVoiceService.updateBackendWs(url)
                        result.success(true)
                    }
                    "setSttDelays" -> {
                        val map = call.arguments as? Map<String, Any?>
                        val ack = (map?.get("ackMs") as? Number)?.toLong() ?: 250L
                        val start = (map?.get("startMs") as? Number)?.toLong() ?: 2200L
                        val post = (map?.get("postTtsMs") as? Number)?.toLong() ?: 100L
                        settingsPrefs.edit()
                            .putLong("stt_ack_ms", ack)
                            .putLong("stt_start_ms", start)
                            .putLong("stt_post_tts_ms", post)
                            .apply()
                        result.success(true)
                    }
                    "getSttDelays" -> {
                        val map = mapOf(
                            "ackMs" to settingsPrefs.getLong("stt_ack_ms", 250L),
                            "startMs" to settingsPrefs.getLong("stt_start_ms", 2200L),
                            "postTtsMs" to settingsPrefs.getLong("stt_post_tts_ms", 100L)
                        )
                        result.success(map)
                    }
                    else -> result.notImplemented()
                }
            }

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE && grantResults.isNotEmpty()) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startAssistantService()
            }
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
        val mic     = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera  = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val a11y    = isAccessibilityEnabled(this, DeviceOperatorService::class.java)
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        else true
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val call = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val readCallLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val readSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val sendSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val notificationAccess = isNotificationAccessEnabled()

        return mapOf(
            "microphone"    to mic,
            "camera"        to camera,
            "overlay"       to overlay,
            "accessibility" to a11y,
            "battery"       to battery,
            "notification"  to notification
            ,"phone"         to call
            ,"callLog"       to readCallLog
            ,"contacts"      to contacts
            ,"readSms"       to readSms
            ,"sendSms"       to sendSms
            ,"notificationAccess" to notificationAccess
        )
    }

    private fun requestCommunicationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions, COMMUNICATION_PERMISSION_CODE)
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { value ->
            try { android.content.ComponentName.unflattenFromString(value)?.packageName == packageName }
            catch (_: Exception) { false }
        }
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
