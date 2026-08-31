package com.example.hey_bro

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Background notification listener that caches active notifications and extracts
 * RemoteInput reply actions for direct voice-controlled replies.
 */
class HeyBroNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "HeyBroNotification"

        @Volatile
        var instance: HeyBroNotificationListenerService? = null
            private set

        val notificationCache = ConcurrentHashMap<String, CachedNotification>()

        @Volatile
        var latestCached: CachedNotification? = null
            private set

        fun latestText(): String {
            return getLatestMessagesSummary(null)
        }

        fun getLatestMessagesSummary(targetContactOrApp: String? = null): String {
            val s = instance
            if (s == null && latestCached == null && notificationCache.isEmpty()) {
                return "Notification access is not enabled or there are no notifications."
            }

            if (!targetContactOrApp.isNullOrBlank()) {
                val query = targetContactOrApp.lowercase().trim()
                val match = notificationCache.values.find {
                    it.title.lowercase().contains(query) ||
                    it.appLabel.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
                }
                if (match != null) {
                    return "Message from ${match.title} on ${match.appLabel}: ${match.text}"
                }
            }

            val recent = notificationCache.values
                .filter { it.text.isNotBlank() }
                .sortedByDescending { it.postedAt }
                .take(3)

            if (recent.isEmpty()) {
                val fallback = latestCached
                return if (fallback != null && fallback.text.isNotBlank()) {
                    "Latest notification from ${fallback.title} on ${fallback.appLabel}: ${fallback.text}"
                } else {
                    "You have no new messages or notifications."
                }
            }

            if (recent.size == 1) {
                val n = recent.first()
                return "You have a message from ${n.title} on ${n.appLabel} saying: ${n.text}"
            }

            return recent.joinToString(". ") { n ->
                "${n.title} on ${n.appLabel} says ${n.text}"
            }
        }

        fun sendDirectReply(packageName: String?, replyMessage: String, contactOrApp: String? = null): Boolean {
            return instance?.replyToNotification(packageName, replyMessage, contactOrApp) ?: false
        }
    }

    data class CachedNotification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val replyAction: Notification.Action?,
        val remoteInput: RemoteInput?
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val bodyText = if (bigText.isNotBlank()) bigText else text

        if (title.isBlank() && bodyText.isBlank()) return

        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val (replyAction, remoteInput) = extractReplyAction(n)
        val cached = CachedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = bodyText,
            postedAt = sbn.postTime,
            replyAction = replyAction,
            remoteInput = remoteInput
        )

        notificationCache[sbn.packageName] = cached
        latestCached = cached
        Log.d(TAG, "Cached notification from $appLabel ($title): hasReply=${replyAction != null}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notificationCache.remove(sbn.packageName)
        Log.d(TAG, "Removed notification from ${sbn.packageName}")
    }

    fun replyToNotification(packageName: String?, replyMessage: String, contactOrApp: String? = null): Boolean {
        if (replyMessage.isBlank()) return false

        val cached = if (!packageName.isNullOrBlank() && notificationCache.containsKey(packageName)) {
            notificationCache[packageName]
        } else if (!contactOrApp.isNullOrBlank()) {
            val query = contactOrApp.lowercase().trim()
            notificationCache.values.find {
                it.title.lowercase().contains(query) ||
                it.appLabel.lowercase().contains(query) ||
                it.packageName.lowercase().contains(query)
            }
        } else {
            latestCached ?: notificationCache.values.maxByOrNull { it.postedAt }
        }

        if (cached == null) {
            Log.w(TAG, "No cached notification found to reply")
            return false
        }

        val action = cached.replyAction
        val remoteInput = cached.remoteInput
        if (action == null || remoteInput == null) {
            Log.w(TAG, "Notification from ${cached.appLabel} has no direct reply action")
            return false
        }

        return try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(remoteInput.resultKey, replyMessage)
            RemoteInput.addResultsToIntent(arrayOf<RemoteInput>(remoteInput), intent, bundle)
            action.actionIntent.send(this, 0, intent)
            Log.i(TAG, "Successfully sent direct reply to ${cached.appLabel} (${cached.title}): \"$replyMessage\"")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification reply: ${e.message}", e)
            false
        }
    }

    private fun extractReplyAction(notification: Notification): Pair<Notification.Action?, RemoteInput?> {
        val actions = notification.actions ?: return Pair(null, null)
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (ri in remoteInputs) {
                if (ri.resultKey.isNotBlank()) {
                    return Pair(action, ri)
                }
            }
        }

        // Check wear/wearable extensible actions if present
        try {
            val wearableExtras = Notification.WearableExtender(notification).actions
            for (action in wearableExtras) {
                val remoteInputs = action.remoteInputs ?: continue
                for (ri in remoteInputs) {
                    if (ri.resultKey.isNotBlank()) {
                        return Pair(action, ri)
                    }
                }
            }
        } catch (_: Exception) {}

        return Pair(null, null)
    }

    private fun refreshActiveNotifications() {
        try {
            val notifications = getActiveNotifications() ?: emptyArray()
            for (sbn in notifications) {
                onNotificationPosted(sbn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query active notifications: ${e.message}")
        }
    }
}