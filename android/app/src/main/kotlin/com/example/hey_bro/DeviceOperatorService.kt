package com.example.hey_bro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.view.KeyEvent
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
import android.Manifest
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
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
            
            // Handle compound MULTI_ACTION intents
            if (json.optString("type") == "MULTI_ACTION") {
                val actions = json.optJSONArray("actions") ?: return
                var delay = 0L
                for (i in 0 until actions.length()) {
                    val actionObj = actions.optJSONObject(i) ?: continue
                    val subPayload = JSONObject().apply {
                        put("type", "ACTION")
                        put("action", actionObj.optString("action"))
                        if (actionObj.has("params")) {
                            put("params", actionObj.optJSONObject("params"))
                        }
                    }
                    mainHandler.postDelayed({
                        executeCommand(subPayload.toString())
                    }, delay)
                    delay += 150L
                }
                return
            }
            
            // Standardized backend schema nests args under "params" and carries
            // a spoken "reply". Merge those nested params up to the top level so
            // both the new schema and legacy flat reads (json.optString("appName")
            // etc.) keep working seamlessly.
            json.optJSONObject("params")?.let { p ->
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (!json.has(k)) json.put(k, p.get(k))
                }
            }
            val action = json.optString("action")
            Log.d(TAG, "executeCommand → $action  |  $payload")

            if (action.isBlank()) {
                Log.w(TAG, "Ignoring device operation without an action: $payload")
                return
            }

            if (isDeviceLocked() && !isLockscreenSafeAction(action)) {
                Log.w(TAG, "Blocked unlocked-only action while device is locked: $action")
                return
            }

            if (executeExtendedOperation(action, json)) return

            when (action) {
                "click"                 -> clickNode(json.optString("targetText", ""), json.optString("targetId", ""))
                "setText"               -> setTextNode(json.optString("targetText", ""), json.optString("targetId", ""), json.optString("textValue", ""))
                "globalBack"            -> performGlobalAction(GLOBAL_ACTION_BACK)
                "globalHome"            -> performGlobalAction(GLOBAL_ACTION_HOME)
                "lockScreen"            -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                "notifications"         -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quickSettings"         -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "recents"               -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "globalAction"          -> handleGlobalAction(json.optString("target", "home"))
                "controlHardware"       -> controlHardware(json)
                "openApp"               -> openApp(json.optString("appName"))
                "openCamera"            -> openCamera(front = false)
                "takePhoto"             -> takePhoto(front = false, delaySeconds = json.optInt("delaySeconds", 0))
                "takeSelfie"            -> takePhoto(front = true, delaySeconds = json.optInt("delaySeconds", 0))
                "setAlarm"              -> setAlarm(json.optInt("hour", 7), json.optInt("minute", 0), json.optString("message", ""))
                "setTimer"              -> setTimer(json.optInt("seconds", 30), json.optString("message", ""))
                "makeCall"              -> {
                    val sim = if (json.has("simSlot")) json.optInt("simSlot", 1) else null
                    makeCall(json.optString("number"), sim)
                }
                "replyNotification"     -> {
                    val pkg = json.optString("packageName", "")
                    val msg = json.optString("message", "")
                    val contact = json.optString("contact", "")
                    HeyBroNotificationListenerService.sendDirectReply(pkg, msg, contact)
                }
                "readNotifications"     -> {
                    val contact = json.optString("contact", "").ifBlank { null }
                    val summary = HeyBroNotificationListenerService.getLatestMessagesSummary(contact)
                    LocalTtsEngine(this).apply {
                        initialize(object : LocalTtsEngine.Listener {
                            override fun onSpeechStart() {}
                            override fun onSpeechDone() {}
                        })
                        speak(summary)
                    }
                }
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
                "mediaPlayPause"        -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "mediaNext"              -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "mediaPrevious"          -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "flashlightOn"          -> setFlashlight(true)
                "flashlightOff"         -> setFlashlight(false)
                "toggleWifi"            -> toggleWifi(json.optBoolean("enabled", true))
                "toggleBluetooth"       -> toggleBluetooth(json.optBoolean("enabled", true))
                "toggleAirplaneMode"    -> toggleAirplaneMode(json.optBoolean("enabled", true))
                "toggleHotspot"         -> openSystemSettings("hotspot")
                "adjustBrightness"      -> adjustBrightness(json.optString("direction", "up"))
                "openSettings"          -> openSystemSettings(json.optString("setting", ""))
                "batterySaver"          -> openSystemSettings("battery")
                "doNotDisturb"          -> openSystemSettings("dnd")
                "autoRotate"            -> openSystemSettings("display")
                "createCalendarEvent"   -> createCalendarEvent(json)
                "sendSms"               -> sendSms(json.optString("number"), json.optString("message"))
                "doubleTap"             -> performScreenGesture(json, 2)
                "longPress"             -> performScreenGesture(json, 1)
                "swipeLeft"             -> performSwipe(false)
                "swipeRight"            -> performSwipe(true)
                "sleep"                 -> { /* handled by CoreVoiceService */ }
                else                    -> Log.w(TAG, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $payload", e)
        }
    }

    /** Executes the extended operation catalog through Android's public settings intents. */
    private fun executeExtendedOperation(action: String, json: JSONObject): Boolean {
        if (action == "openAppDetails" || action == "openAppNotificationSettings") {
            val packageName = json.optString("packageName").trim()
            if (packageName.isBlank()) {
                Log.w(TAG, "$action requires packageName")
                return true
            }
            val target = if (action == "openAppDetails") {
                "android.settings.APPLICATION_DETAILS_SETTINGS"
            } else {
                "android.settings.APP_NOTIFICATION_SETTINGS"
            }
            val intent = Intent(target).apply {
                data = Uri.parse("package:$packageName")
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return startSettingsIntent(intent, action)
        }

        val settingsAction = ExtendedOperations.EXTENDED_SETTINGS_ACTIONS[action] ?: return false
        return startSettingsIntent(
            Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            action
        )
    }

    private fun startSettingsIntent(intent: Intent, operation: String): Boolean {
        return try {
            startActivity(intent)
            Log.d(TAG, "Executed extended operation: $operation")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Extended operation unavailable: $operation", e)
            false
        }
    }

    private object ExtendedOperations {
        // 100 additional safe operations. Values are Android public settings actions.
        val EXTENDED_SETTINGS_ACTIONS = mapOf(
            "openWifiSettings" to "android.settings.WIFI_SETTINGS",
            "openBluetoothSettings" to "android.settings.BLUETOOTH_SETTINGS",
            "openDataUsageSettings" to "android.settings.DATA_USAGE_SETTINGS",
            "openDisplaySettings" to "android.settings.DISPLAY_SETTINGS",
            "openSoundSettings" to "android.settings.SOUND_SETTINGS",
            "openBatterySettings" to "android.settings.BATTERY_SETTINGS",
            "openLocationSettings" to "android.settings.LOCATION_SOURCE_SETTINGS",
            "openSecuritySettings" to "android.settings.SECURITY_SETTINGS",
            "openAccessibilitySettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openAppSettings" to "android.settings.APPLICATION_SETTINGS",
            "openNotificationSettings" to "android.settings.NOTIFICATION_SETTINGS",
            "openDateSettings" to "android.settings.DATE_SETTINGS",
            "openLanguageSettings" to "android.settings.LOCALE_SETTINGS",
            "openStorageSettings" to "android.settings.INTERNAL_STORAGE_SETTINGS",
            "openPrivacySettings" to "android.settings.PRIVACY_SETTINGS",
            "openDeveloperSettings" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            "openVpnSettings" to "android.settings.VPN_SETTINGS",
            "openNfcSettings" to "android.settings.NFC_SETTINGS",
            "openCastSettings" to "android.settings.CAST_SETTINGS",
            "openPrintSettings" to "android.settings.PRINT_SETTINGS",
            "openKeyboardSettings" to "android.settings.HARD_KEYBOARD_SETTINGS",
            "openAccountsSettings" to "android.settings.SYNC_SETTINGS",
            "openBackupSettings" to "android.settings.BACKUP_SETTINGS",
            "openBiometricSettings" to "android.settings.BIOMETRIC_ENROLL",
            "openInstallUnknownAppsSettings" to "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            "openManageApplicationsSettings" to "android.settings.MANAGE_APPLICATIONS_SETTINGS",
            "openManageAllApplicationsSettings" to "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS",
            "openDefaultAppsSettings" to "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            "openHomeSettings" to "android.settings.HOME_SETTINGS",
            "openBrowserSettings" to "android.settings.WEBVIEW_SETTINGS",
            "openAssistSettings" to "android.settings.VOICE_INPUT_SETTINGS",
            "openPowerUsageSettings" to "android.intent.action.POWER_USAGE_SUMMARY",
            "openBatterySaverSettings" to "android.settings.BATTERY_SAVER_SETTINGS",
            "openBatteryOptimizationSettings" to "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            "openDataSaverSettings" to "android.settings.DATA_SAVER_SETTINGS",
            "openTetherSettings" to "android.settings.TETHER_SETTINGS",
            "openWirelessSettings" to "android.settings.WIRELESS_SETTINGS",
            "openNetworkSettings" to "android.settings.NETWORK_OPERATOR_SETTINGS",
            "openInternetSettings" to "android.settings.WIFI_SETTINGS",
            "openAirplaneSettings" to "android.settings.AIRPLANE_MODE_SETTINGS",
            "openRoamingSettings" to "android.settings.DATA_ROAMING_SETTINGS",
            "openSimSettings" to "android.settings.SIM_SETTINGS",
            "openMobileNetworkSettings" to "android.settings.NETWORK_OPERATOR_SETTINGS",
            "openUsbSettings" to "android.settings.USB_SETTINGS",
            "openHdrSettings" to "android.settings.HDR_SETTINGS",
            "openNightDisplaySettings" to "android.settings.NIGHT_DISPLAY_SETTINGS",
            "openDarkModeSettings" to "android.settings.DARK_MODE_SETTINGS",
            "openWallpaperSettings" to "android.settings.WALLPAPER_SETTINGS",
            "openDreamSettings" to "android.settings.DREAM_SETTINGS",
            "openFontSettings" to "android.settings.FONT_SETTINGS",
            "openScreenTimeoutSettings" to "android.settings.DISPLAY_SETTINGS",
            "openAutoRotateSettings" to "android.settings.DISPLAY_SETTINGS",
            "openAccessibilityShortcutSettings" to "android.settings.ACCESSIBILITY_DETAILS_SETTINGS",
            "openCaptionsSettings" to "android.settings.CAPTIONING_SETTINGS",
            "openTextToSpeechSettings" to "android.settings.TTS_SETTINGS",
            "openSpeechSettings" to "android.settings.VOICE_INPUT_SETTINGS",
            "openInputMethodSettings" to "android.settings.INPUT_METHOD_SETTINGS",
            "openNotificationListenerSettings" to "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
            "openDoNotDisturbSettings" to "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
            "openZenSettings" to "android.settings.ZEN_MODE_SETTINGS",
            "openAlarmSettings" to "android.settings.ALARM_SETTINGS",
            "openStorageManagerSettings" to "android.settings.STORAGE_MANAGER_SETTINGS",
            "openManageExternalSourcesSettings" to "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            "openDeviceInfoSettings" to "android.settings.DEVICE_INFO_SETTINGS",
            "openSystemUpdateSettings" to "android.settings.SYSTEM_UPDATE_SETTINGS",
            "openMemorySettings" to "android.settings.INTERNAL_STORAGE_SETTINGS",
            "openDateTimeSettings" to "android.settings.DATE_SETTINGS",
            "openRegionSettings" to "android.settings.LOCALE_SETTINGS",
            "openUserSettings" to "android.settings.USER_SETTINGS",
            "openLockScreenSettings" to "android.settings.SECURITY_SETTINGS",
            "openTrustedCredentialsSettings" to "android.settings.TRUSTED_CREDENTIALS",
            "openEncryptionSettings" to "android.settings.ENCRYPTION_SETTINGS",
            "openPrivateDnsSettings" to "android.settings.PRIVATE_DNS_SETTINGS",
            "openProxySettings" to "android.settings.WIFI_SETTINGS",
            "openWifiAddNetwork" to "android.settings.WIFI_ADD_NETWORKS",
            "openWifiSavedNetworks" to "android.settings.WIFI_SAVED_NETWORKS_SETTINGS",
            "openBluetoothPairing" to "android.settings.BLUETOOTH_SETTINGS",
            "openConnectedDevicesSettings" to "android.settings.BLUETOOTH_SETTINGS",
            "openNearbySettings" to "android.settings.BLUETOOTH_SETTINGS",
            "openInteractionSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openTouchSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openVibrationSettings" to "android.settings.SOUND_SETTINGS",
            "openAccessibilityButtonSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openBatteryHealthSettings" to "android.settings.BATTERY_SETTINGS",
            "openNotificationPolicySettings" to "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
            "openDoNotDisturbVisualEffectsSettings" to "android.settings.ZEN_MODE_SETTINGS",
            "openManageOverlaySettings" to "android.settings.action.MANAGE_OVERLAY_PERMISSION",
            "openPictureInPictureSettings" to "android.settings.PICTURE_IN_PICTURE_SETTINGS",
            "openSpecialAccessSettings" to "android.settings.MANAGE_OVERLAY_PERMISSION",
            "openFullScreenIntentSettings" to "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
            "openUsageAccessSettings" to "android.settings.USAGE_ACCESS_SETTINGS",
            "openNotificationAssistantSettings" to "android.settings.NOTIFICATION_ASSISTANT_SETTINGS",
            "openVrSettings" to "android.settings.VR_LISTENER_SETTINGS",
            "openWebViewSettings" to "android.settings.WEBVIEW_SETTINGS",
            "openWifiDetailsSettings" to "android.settings.WIFI_SETTINGS",
            "openBluetoothDetailsSettings" to "android.settings.BLUETOOTH_SETTINGS",
            "openNetworkResetSettings" to "android.settings.NETWORK_RESET_SETTINGS",
            "openSystemNavigationSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openScreenReaderSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openMagnificationSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openColorCorrectionSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openMonoAudioSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openHearingAidSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openLiveCaptionSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openLiveTranscribeSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openAssistantSettings" to "android.settings.ASSIST_SETTINGS",
            "openNotificationBubblesSettings" to "android.settings.NOTIFICATION_SETTINGS",
            "openConversationSettings" to "android.settings.NOTIFICATION_SETTINGS",
            "openPictureSettings" to "android.settings.DISPLAY_SETTINGS",
            "openRefreshRateSettings" to "android.settings.DISPLAY_SETTINGS",
            "openColorModeSettings" to "android.settings.DISPLAY_SETTINGS",
            "openScreenSaverSettings" to "android.settings.DREAM_SETTINGS",
            "openOneHandedSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openGestureSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openQuickLaunchSettings" to "android.settings.ACCESSIBILITY_SETTINGS",
            "openAppPermissionSettings" to "android.settings.APPLICATION_DETAILS_SETTINGS",
            "openPermissionControllerSettings" to "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"
        )
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
            "replyNotification",
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

    private fun handleGlobalAction(target: String) {
        when (target.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            else   -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun controlHardware(json: JSONObject) {
        val device = json.optString("device", "").lowercase()
        val state  = json.optString("state", "").lowercase()
        when (device) {
            "flashlight" -> when (state) {
                "on"     -> setFlashlight(true)
                "off"    -> setFlashlight(false)
                "toggle" -> setFlashlight(true)
                else     -> Log.w(TAG, "Unknown flashlight state: $state")
            }
            "volume"     -> when (state) {
                "up", "increase"   -> adjustVolume(AudioManager.ADJUST_RAISE)
                "down", "decrease" -> adjustVolume(AudioManager.ADJUST_LOWER)
                "mute"             -> adjustVolume(AudioManager.ADJUST_MUTE)
                else               -> Log.w(TAG, "Unknown volume state: $state")
            }
            "wifi"       -> toggleWifi(state != "off")
            "bluetooth"  -> toggleBluetooth(state != "off")
            "airplane"   -> toggleAirplaneMode(state != "off")
            "brightness" -> adjustBrightness(if (state in setOf("up", "increase")) "up" else "down")
            else         -> Log.w(TAG, "Unknown controlHardware device: $device")
        }
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

    private fun makeCall(number: String, simSlot: Int? = null) {
        if (number.isBlank()) return
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val uri = Uri.parse("tel:${Uri.encode(number)}")
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (simSlot != null && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                @Suppress("DEPRECATION")
                val accounts = telecomManager?.callCapablePhoneAccounts ?: emptyList()
                if (simSlot in 1..accounts.size) {
                    val targetAccount = accounts[simSlot - 1]
                    intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, targetAccount)
                    Log.d(TAG, "Routing call through SIM slot $simSlot: ${targetAccount.id}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve SIM account handle: ${e.message}")
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            val dial = Intent(Intent.ACTION_DIAL, uri).apply {
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

    private fun dispatchMediaKey(keyCode: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = android.os.SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        Log.d(TAG, "Media key dispatched: $keyCode")
    }

    private fun createCalendarEvent(json: JSONObject) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra("title", json.optString("title", "Hey Bro event"))
            putExtra("description", json.optString("description", ""))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "Calendar error", e) }
    }

    private fun sendSms(number: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "SMS error", e) }
    }

    private fun performScreenGesture(json: JSONObject, taps: Int) {
        val metrics = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).currentWindowMetrics
        val x = json.optDouble("x", metrics.bounds.centerX().toDouble()).toFloat()
        val y = json.optDouble("y", (metrics.bounds.height() * 0.5).toDouble()).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val duration = if (taps == 1) 700L else 100L
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        if (taps == 2) {
            builder.addStroke(GestureDescription.StrokeDescription(path, 180L, 100L))
        }
        dispatchGesture(builder.build(), null, null)
    }

    private fun performSwipe(right: Boolean) {
        val bounds = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).currentWindowMetrics.bounds
        val y = bounds.centerY().toFloat()
        val startX = if (right) bounds.left + 100f else bounds.right - 100f
        val endX = if (right) bounds.right - 100f else bounds.left + 100f
        val path = Path().apply { moveTo(startX, y); lineTo(endX, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 500L))
                .build(), null, null
        )
    }

    private fun setFlashlight(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return
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
            "dnd"               -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            "location"          -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else                -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "openSettings error", e) }
    }
}
