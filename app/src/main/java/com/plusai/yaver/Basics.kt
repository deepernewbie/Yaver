package com.plusai.yaver

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Intent extras shared by notifications, shortcuts, the tile and the service. */
object Keys {
    const val ACTION = "yaver_action"
    const val PAYLOAD = "yaver_payload"
    const val NOTIF_ID = "yaver_notif_id"
}

class YaverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        Prefs.init(this)
        YaverCore.init(this)
        Notifier.createChannels(this)
        Shortcuts.publish(this)
    }

    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt").writeText(
                    "Thread: ${thread.name}\n" + android.util.Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** Small string preferences shared by Kotlin and the web page ("1"/"0" for switches, "" = default). */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("yaver_native", Context.MODE_PRIVATE)
    }

    fun get(k: String, d: String): String = sp?.getString(k, d) ?: d
    fun put(k: String, v: String) { sp?.edit()?.putString(k, v)?.apply() }
    fun remove(k: String) { sp?.edit()?.remove(k)?.apply() }
    fun on(k: String, d: Boolean): Boolean {
        val v = get(k, "")
        return if (v.isEmpty()) d else v == "1"
    }
    fun set(k: String, b: Boolean) = put(k, if (b) "1" else "0")
    fun keys(prefix: String): List<String> = sp?.all?.keys?.filter { it.startsWith(prefix) } ?: emptyList()
}

/** Saved server logins for the mail screen (only when the user ticks "Remember"). */
object Creds {
    fun get(host: String): Pair<String, String>? {
        val raw = Prefs.get("cred:$host", "")
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            Pair(o.optString("u"), o.optString("p"))
        } catch (e: Exception) {
            null
        }
    }

    fun put(host: String, user: String, pass: String) =
        Prefs.put("cred:$host", JSONObject().put("u", user).put("p", pass).toString())

    fun remove(host: String) = Prefs.remove("cred:$host")
    fun clear() { for (k in Prefs.keys("cred:")) Prefs.remove(k) }
}

object Notifier {
    const val CH_MAIN = "yaver_main"
    const val CH_REMIND = "yaver_remind"
    const val CH_SERVICE = "yaver_service"
    private val ids = AtomicInteger(1000)

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_MAIN, "Replies and mail", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "yaver finished a task, new mail, messages from yaver"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you asked yaver to set"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Always on", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The small notification that keeps yaver running"
                setShowBadge(false)
            }
        )
    }

    fun canPost(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openIntent(c: Context, action: String?, payload: String?, id: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(Keys.ACTION, action ?: "open")
            if (payload != null) putExtra(Keys.PAYLOAD, payload)
            putExtra(Keys.NOTIF_ID, id)
        }
        return PendingIntent.getActivity(
            c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Shows a notification; tapping it opens yaver and runs [action] (with [payload]). */
    fun show(
        c: Context, title: String, text: String, action: String? = null, payload: String? = null,
        channel: String = CH_MAIN
    ): Int {
        if (!canPost(c)) return -1
        val id = ids.incrementAndGet()
        val n = Notification.Builder(c, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(c, action, payload, id))
            .build()
        c.getSystemService(NotificationManager::class.java)?.notify(id, n)
        return id
    }
}

/** Long-press the launcher icon: Ask by voice, New chat, Mail, Plan my day, Phone settings. */
object Shortcuts {
    fun publish(c: Context) {
        try {
            val sm = c.getSystemService(ShortcutManager::class.java) ?: return
            fun mk(id: String, label: String, action: String, icon: Int): ShortcutInfo {
                val i = Intent(c, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(Keys.ACTION, action)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                return ShortcutInfo.Builder(c, id)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithResource("android", icon))
                    .setIntent(i)
                    .build()
            }
            val list = listOf(
                mk("voice", "Ask by voice", "voice", android.R.drawable.ic_btn_speak_now),
                mk("new", "New chat", "new", android.R.drawable.ic_menu_add),
                mk("mail", "Mail", "mail", android.R.drawable.ic_dialog_email),
                mk("plan", "Plan my day", "plan", android.R.drawable.ic_menu_my_calendar)
            )
            sm.setDynamicShortcuts(list)
        } catch (e: Throwable) {
            android.util.Log.w("yaver", "shortcuts: $e")
        }
    }
}
