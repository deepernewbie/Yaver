package dev.yaver.app

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads incoming messages the only way Android permits: by listening to the
 * notifications they raise.
 *
 * What this can and cannot see is worth being blunt about, because the limits
 * are not obvious and people assume otherwise:
 *
 *   - It sees messages *sent to you* that raise a notification.
 *   - It never sees messages *you* send. WhatsApp raises no notification for
 *     your own messages, so a chat containing only you captures nothing — the
 *     "message myself" idea cannot work, on any phone, for any app.
 *   - It misses anything arriving while the app is open in the foreground, and
 *     anything from a muted chat, because neither raises a notification.
 *   - Long messages arrive truncated to whatever the notification showed.
 *
 * For "what came in today and what needs me" this is enough. For a faithful
 * archive of a conversation it is not, and pretending otherwise would set up a
 * disappointment later.
 *
 * Declaring BIND_NOTIFICATION_LISTENER_SERVICE is what makes Play Protect
 * refuse the first sideloaded install. That is the price of the feature, and
 * it is paid once.
 */
class NotificationCapture : NotificationListenerService() {

    companion object {
        /** Apps worth listening to. Everything else is noise. */
        val WATCHED = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages"
        )

        const val ENABLED = "captureEnabled"

        fun isEnabled(): Boolean = Store.setting(ENABLED, "false") == "true"

        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(context.packageName)
        }

        fun openAccessSettings(context: Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isEnabled()) return
        val app = WATCHED[sbn.packageName] ?: return

        try {
            val extras = sbn.notification.extras ?: return

            // Group summaries repeat what the individual notifications already
            // said; storing them doubles every message.
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

            val chat = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            if (chat.isEmpty()) return

            // MessagingStyle carries sender and text separately and is the only
            // reliable way to tell who spoke in a group.
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null && messages.isNotEmpty()) {
                messages.forEach { item ->
                    val bundle = item as? Bundle ?: return@forEach
                    val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
                    if (text.isEmpty()) return@forEach
                    val sender = bundle.getCharSequence("sender")?.toString()?.trim().orEmpty()
                    val at = bundle.getLong("time").takeIf { it > 0 } ?: sbn.postTime
                    Store.addMessage(app, chat, sender.ifEmpty { chat }, text, at,
                        key = "${sbn.key}|$at|${text.hashCode()}")
                }
                return
            }

            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            // "3 new messages" is a counter, not a message.
            if (Regex("^\\d+ (new |)messages?$", RegexOption.IGNORE_CASE).matches(text)) return

            Store.addMessage(app, chat, chat, text, sbn.postTime,
                key = "${sbn.key}|${sbn.postTime}|${text.hashCode()}")
        } catch (e: Exception) {
            Log.error("capture failed: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.info("notification capture connected")
    }
}
