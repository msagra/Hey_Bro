package com.example.hey_bro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Accessibility service that executes device automation commands.
 *
 * Supported actions (JSON field: "action"):
 *   click          – tap a UI element by text or resource ID
 *   setText        – type into an input field
 *   globalBack     – press Back
 *   globalHome     – press Home
 *   openApp        – launch an installed app by name
 *   openCamera     – launch the default camera app
 *   takePhoto      – open camera and auto-capture after delaySeconds
 *   takeSelfie     – open front camera and auto-capture
 *   setAlarm       – set a phone alarm
 *   makeCall       – dial a phone number
 *   sendWhatsApp   – send a WhatsApp message via URI intent
 *   searchWeb      – open browser with Google search
 *   playMedia      – open YouTube/Spotify with a search
 *   screenshot     – take a screenshot
 *   scrollDown     – scroll the currently focused scrollable view down
 *   scrollUp       – scroll up
 *   volumeUp       – increase media volume
 *   volumeDown     – decrease media volume
 *   mute           – mute all audio
 *   flashlightOn   – turn on the torch
 *   flashlightOff  – turn off the torch
 *   openSettings   – open specific system settings (wifi / bluetooth)
 *   sleep          – no-op, handled by CoreVoiceService
 */
class DeviceOperatorService : AccessibilityService() {

    companion object {
        private const val TAG = "DeviceOperatorService"
        var instance: DeviceOperatorService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "DeviceOperatorService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* intentionally empty */ }

    override fun onInterrupt() { Log.w(TAG, "DeviceOperatorService interrupted") }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main dispatch
    // ─────────────────────────────────────────────────────────────────────────

    fun executeCommand(payload: String) {
        try {
            val json   = JSONObject(payload)
            val action = json.optString("action")
            Log.d(TAG, "executeCommand → $action  |  $payload")

            if (isDeviceLocked() && !isLockscreenSafeAction(action)) {
                Log.w(TAG, "Blocked unlocked-only action while device is locked: $action")
                return
            }

            when (action) {
                "click"                 -> clickNode(json.optString("targetText", null), json.optString("targetId", null))
                "setText"               -> setTextNode(json.optString("targetText", null), json.optString("targetId", null), json.optString("textValue", ""))
                "globalBack"            -> performGlobalAction(GLOBAL_ACTION_BACK)
                "globalHome"            -> performGlobalAction(GLOBAL_ACTION_HOME)
                "openApp"               -> openApp(json.optString("appName"))
                "openCamera"            -> openCamera(front = false)
                "takePhoto"             -> takePhoto(front = false, delaySeconds = json.optInt("delaySeconds", 0))
                "takeSelfie"            -> takePhoto(front = true, delaySeconds = json.optInt("delaySeconds", 0))
                "setAlarm"              -> setAlarm(json.optInt("hour", 7), json.optInt("minute", 0), json.optString("message", ""))
                "setTimer"              -> setTimer(json.optInt("seconds", 30), json.optString("message", ""))
                "makeCall"              -> makeCall(json.optString("number"))
                "sendWhatsApp"          -> sendWhatsApp(json.optString("contact"), json.optString("message"))
                "searchWeb"             -> searchWeb(json.optString("query"))
                "openMaps"              -> openMaps(json.optString("query"))
                "playMedia"             -> playMedia(json.optString("query"), json.optString("app", "YouTube"))
                "screenshot"            -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                "scrollDown"            -> scrollActiveView(false)
                "scrollUp"              -> scrollActiveView(true)
                "volumeUp"              -> adjustVolume(AudioManager.ADJUST_RAISE)
                "volumeDown"            -> adjustVolume(AudioManager.ADJUST_LOWER)
                "mute"                  -> adjustVolume(AudioManager.ADJUST_MUTE)
                "flashlightOn"          -> setFlashlight(true)
                "flashlightOff"         -> setFlashlight(false)
                "toggleWifi"            -> toggleWifi(json.optBoolean("enabled", true))
                "toggleBluetooth"       -> toggleBluetooth(json.optBoolean("enabled", true))
                "toggleAirplaneMode"    -> toggleAirplaneMode(json.optBoolean("enabled", true))
                "toggleHotspot"         -> openSystemSettings("hotspot")
                "adjustBrightness"      -> adjustBrightness(json.optString("direction", "up"))
                "openSettings"          -> openSystemSettings(json.optString("setting", ""))
                "sleep"                 -> { /* handled by CoreVoiceService */ }
                else                    -> Log.w(TAG, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $payload", e)
        }
    }

    private fun isDeviceLocked(): Boolean {
        return try {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguard.isKeyguardLocked || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && keyguard.isDeviceLocked)
        } catch (_: Exception) {
            false
        }
    }

    private fun isLockscreenSafeAction(action: String): Boolean {
        return action in setOf(
            "setAlarm",
            "setTimer",
            "makeCall",
            "openCamera",
            "takePhoto",
            "takeSelfie",
            "flashlightOn",
            "flashlightOff",
            "volumeUp",
            "volumeDown",
            "mute",
            "toggleAirplaneMode",
            "toggleWifi",
            "toggleBluetooth",
            "toggleHotspot",
            "adjustBrightness",
            "openSettings",
            "searchWeb",
            "openMaps"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Node finders
    // ─────────────────────────────────────────────────────────────────────────

    private fun findNode(text: String?, viewId: String?): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (!text.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) return nodes[0]
        }
        if (!viewId.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.isNotEmpty()) return nodes[0]
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Action implementations
    // ─────────────────────────────────────────────────────────────────────────

    private fun clickNode(text: String?, viewId: String?): Boolean {
        var node = findNode(text, viewId)
        while (node != null) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        Log.w(TAG, "Clickable node not found: text=$text, id=$viewId")
        return false
    }

    private fun setTextNode(text: String?, viewId: String?, value: String): Boolean {
        val node = findNode(text, viewId) ?: run {
            Log.w(TAG, "Editable node not found: text=$text, id=$viewId")
            return false
        }
        if (!node.isEditable) {
            Log.w(TAG, "Node is not editable: text=$text")
            return false
        }
        val args = Bundle().also {
            it.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scrollActiveView(scrollUp: Boolean) {
        val root = rootInActiveWindow ?: return
        fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findScrollable(child)
                if (result != null) return result
            }
            return null
        }
        val scrollable = findScrollable(root) ?: return
        val action = if (scrollUp) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                     else          AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        scrollable.performAction(action)
    }

    private fun openApp(appName: String) {
        if (appName.isBlank()) return
        val pm = packageManager
        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString()
            if (label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)) {
                val intent = pm.getLaunchIntentForPackage(app.packageName) ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(intent); return } catch (e: Exception) { Log.e(TAG, "openApp error", e) }
            }
        }
        Log.w(TAG, "App not found: $appName")
    }

    private fun openCamera(front: Boolean) {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (front) {
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extras.CAMERA_FACING", 1)
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open camera app generically
            val fallback = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_GALLERY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).also {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                Log.e(TAG, "openCamera error", e2)
            }
        }
    }

    /**
     * Opens camera and, after [delaySeconds], simulates the shutter button tap.
     * Uses a gesture-based tap at the center of the screen as the camera shutter.
     */
    private fun takePhoto(front: Boolean, delaySeconds: Int) {
        openCamera(front)
        val delayMs = (if (delaySeconds > 0) delaySeconds * 1000L else 1200L)
        mainHandler.postDelayed({
            // Try to find shutter button first, fall back to center tap
            val shutterNode = findNode("Shutter", null)
                ?: findNode("Take photo", null)
                ?: findNode("Capture", null)

            if (shutterNode != null && shutterNode.isClickable) {
                shutterNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Photo taken via shutter node click")
            } else {
                // Gesture tap at center of screen
                val display = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).currentWindowMetrics.bounds
                val cx = (display.right / 2).toFloat()
                val cy = (display.bottom * 0.75f)
                val path = Path().apply { moveTo(cx, cy) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
                    .build()
                dispatchGesture(gesture, null, null)
                Log.d(TAG, "Photo taken via gesture tap at ($cx, $cy)")
            }
        }, delayMs)
    }

    private fun setAlarm(hour: Int, minute: Int, message: String) {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR,    hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "setAlarm error", e) }
    }

    private fun setTimer(seconds: Int, message: String) {
        val timeSeconds = if (seconds <= 0) 30 else seconds
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, timeSeconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Hey Bro timer" })
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "setTimer error", e) }
    }

    private fun openMaps(query: String) {
        val encoded = Uri.encode(query.ifBlank { ""})
        val uri = if (query.isBlank()) Uri.parse("geo:0,0?q=My%20Location")
        else Uri.parse("geo:0,0?q=${encoded}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "openMaps error", e) }
    }

    private fun makeCall(number: String) {
        if (number.isBlank()) return
        val call = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(call)
        } catch (e: Exception) {
            val dial = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(dial) } catch (e2: Exception) { Log.e(TAG, "makeCall error", e2) }
        }
    }

    private fun sendWhatsApp(contact: String, message: String) {
        if (message.isNotBlank() && contact.isNotBlank()) {
            // Try phone-number-based deep link first
            val uri = Uri.parse("https://wa.me/${contact.filter { it.isDigit() }}?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(intent); return } catch (_: Exception) {}
        }
        // Fallback: open WhatsApp share sheet with pre-filled message
        val uri = Uri.parse("https://wa.me/?text=${Uri.encode(message.ifBlank { "" })}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            openApp("WhatsApp")
        }
    }

    private fun searchWeb(query: String) {
        if (query.isBlank()) return
        val url    = "https://www.google.com/search?q=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "searchWeb error", e) }
    }

    private fun playMedia(query: String, app: String) {
        if (query.isBlank()) return
        val intent: Intent = when {
            app.contains("spotify", ignoreCase = true) -> {
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
                    .setPackage("com.spotify.music")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> {
                val url = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            searchWeb("$app $query")
        }
    }

    private fun adjustVolume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun setFlashlight(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(cameraId, on)
            Log.d(TAG, "Flashlight ${if (on) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight error: ${e.message}")
        }
    }

    private fun toggleWifi(enabled: Boolean) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "WiFi settings opened for ${if (enabled) "enable" else "disable"} flow")
        } catch (e: Exception) {
            Log.e(TAG, "toggleWifi error", e)
        }
    }

    private fun toggleBluetooth(enabled: Boolean) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "Bluetooth settings opened for ${if (enabled) "enable" else "disable"} flow")
        } catch (e: Exception) {
            Log.e(TAG, "toggleBluetooth error", e)
        }
    }

    private fun toggleAirplaneMode(enabled: Boolean) {
        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "Airplane mode settings opened for ${if (enabled) "enable" else "disable"} flow")
        } catch (e: Exception) {
            Log.e(TAG, "toggleAirplaneMode error", e)
        }
    }

    private fun adjustBrightness(direction: String) {
        val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            Log.d(TAG, "Display settings opened for brightness $direction")
        } catch (e: Exception) {
            Log.e(TAG, "adjustBrightness error", e)
        }
    }

    private fun openSystemSettings(setting: String) {
        val action = when (setting.lowercase()) {
            "wifi", "wi-fi"     -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth"         -> Settings.ACTION_BLUETOOTH_SETTINGS
            "hotspot", "tether" -> Settings.ACTION_WIRELESS_SETTINGS
            "display"           -> Settings.ACTION_DISPLAY_SETTINGS
            "sound"             -> Settings.ACTION_SOUND_SETTINGS
            "battery"           -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location"          -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else                -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "openSettings error", e) }
    }
}
