package com.plusai.yaver.app

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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class YaverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt").writeText("Thread: ${thread.name}\n" + android.util.Log.getStackTraceString(error))
                Diag.e("crash", "thread ${thread.name}: " + android.util.Log.getStackTraceString(error).take(1000))
            }
            previous?.uncaughtException(thread, error)
        }
        Y.init(this)
        // load the cookie store (shared by the mail client and the web pages) once, on the main thread
        Y.main.post { try { android.webkit.CookieManager.getInstance().setAcceptCookie(true) } catch (_: Throwable) {} }
        Notifier.createChannels(this)
        Shortcuts.publish(this)
        // the harness: hooks between modules, heartbeats, idle-time learning, connected folders
        try { Wiring.init() } catch (e: Throwable) { Diag.e("app", "wiring", e) }
        try { Heartbeats.start(this) } catch (e: Throwable) { Diag.e("app", "heartbeats", e) }
        try { MailKeepAlive.arm(this) } catch (e: Throwable) { Diag.e("app", "keepalive", e) }
        try { Learn.start() } catch (e: Throwable) { Diag.e("app", "learn", e) }
        try { Folders.init() } catch (e: Throwable) { Diag.e("app", "folders", e) }
        try { SkillsUi.seed() } catch (e: Throwable) { Diag.e("app", "seed skill", e) }
    }
}

/** App-wide helpers: context, main-thread handler, worker threads. */
object Y {
    lateinit var app: Context
    val main = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newCachedThreadPool()

    fun init(c: Context) {
        if (!::app.isInitialized) app = c.applicationContext
        Prefs.init(app)
    }

    fun ui(f: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f) }
    fun bg(f: () -> Unit) { io.execute { try { f() } catch (e: Throwable) { android.util.Log.e("yaver", "bg", e) } } }

    /** The interface language: Settings → Interface language when set (P3-9), else the answer language setting. */
    val tr: Boolean get() { val u = Prefs.get("cfg.uiLang", ""); if (u == "tr" || u == "en") return u == "tr"; return Cfg.lang == "tr" || (Cfg.lang == "auto" && java.util.Locale.getDefault().language == "tr") }
    fun t(en: String, tr: String) = if (this.tr) tr else en

    fun dark(c: Context) = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

object Keys {
    const val ACTION = "yaver_action"
    const val PAYLOAD = "yaver_payload"
    const val NOTIF_ID = "yaver_notif_id"
    const val CONVO = "yaver_convo"
}

/** Plain preferences ("1"/"0" for switches). */
object Prefs {
    private var sp: SharedPreferences? = null
    fun init(c: Context) { if (sp == null) sp = c.getSharedPreferences("yaver_native", Context.MODE_PRIVATE) }
    fun get(k: String, d: String = ""): String = sp?.getString(k, d) ?: d
    fun put(k: String, v: String) { sp?.edit()?.putString(k, v)?.apply() }
    fun remove(k: String) { sp?.edit()?.remove(k)?.apply() }
    fun on(k: String, d: Boolean): Boolean { val v = get(k, ""); return if (v.isEmpty()) d else v == "1" }
    fun set(k: String, b: Boolean) = put(k, if (b) "1" else "0")
    fun int(k: String, d: Int): Int = get(k, "").toIntOrNull() ?: d
    /** Every stored string (for the state export). */
    fun all(): Map<String, String> = (sp?.all ?: emptyMap<String, Any?>()).mapNotNull { (k, v) -> if (v is String) k to v else null }.toMap()
}

/** Secrets (API keys, mail password) are encrypted with a key that never leaves the phone's keystore. */
object Secure {
    private const val ALIAS = "yaver_secrets_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val e = ks.getEntry(ALIAS, null)
        if (e is KeyStore.SecretKeyEntry) return e.secretKey
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun enc(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            "v1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.toByteArray()), Base64.NO_WRAP)
        } catch (e: Throwable) {
            "p1:" + Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
        }
    }

    fun dec(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            when {
                s.startsWith("v1:") -> {
                    val p = s.split(":")
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[1], Base64.NO_WRAP)))
                    String(c.doFinal(Base64.decode(p[2], Base64.NO_WRAP)))
                }
                s.startsWith("p1:") -> String(Base64.decode(s.substring(3), Base64.NO_WRAP))
                else -> s
            }
        } catch (e: Throwable) {
            ""
        }
    }
}

object Notifier {
    const val CH_MAIN = "yaver_main"
    const val CH_MAIL = "yaver_mail"
    const val CH_REMIND = "yaver_remind"
    const val CH_SERVICE = "yaver_service"
    private val ids = AtomicInteger(1000)

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_MAIN, "Replies", NotificationManager.IMPORTANCE_HIGH).apply { description = "yaver finished a task or has a message" })
        nm.createNotificationChannel(NotificationChannel(CH_MAIL, "New mail", NotificationManager.IMPORTANCE_HIGH).apply { description = "New e-mail in your company inbox" })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders and scheduled tasks"; enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Always on", NotificationManager.IMPORTANCE_MIN).apply { description = "Keeps yaver running"; setShowBadge(false) })
    }

    fun canPost(c: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun intent(c: Context, action: String?, payload: String?, id: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(Keys.ACTION, action ?: "open")
            if (payload != null) putExtra(Keys.PAYLOAD, payload)
            putExtra(Keys.NOTIF_ID, id)
        }
        return PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(c: Context, title: String, text: String, action: String? = null, payload: String? = null, channel: String = CH_MAIN, pi: PendingIntent? = null,
             actions: ((Int) -> List<Notification.Action>)? = null): Int {
        if (!canPost(c)) return -1
        val id = ids.incrementAndGet()
        val b = Notification.Builder(c, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi ?: intent(c, action, payload, id))
        actions?.invoke(id)?.forEach { b.addAction(it) }
        val n = b.build()
        c.getSystemService(NotificationManager::class.java)?.notify(id, n)
        return id
    }
}

object Shortcuts {
    fun publish(c: Context) {
        try {
            val sm = c.getSystemService(ShortcutManager::class.java) ?: return
            fun mk(id: String, label: String, action: String, icon: Int): ShortcutInfo =
                ShortcutInfo.Builder(c, id).setShortLabel(label).setLongLabel(label)
                    .setIcon(Icon.createWithResource("android", icon))
                    .setIntent(Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(Keys.ACTION, action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    .build()
            sm.setDynamicShortcuts(
                listOf(
                    mk("voice", Y.t("Ask by voice", "Sesle sor"), "voice", android.R.drawable.ic_btn_speak_now),
                    mk("new", Y.t("New chat", "Yeni sohbet"), "new", android.R.drawable.ic_menu_add),
                    mk("mail", Y.t("Mail", "Posta"), "mail", android.R.drawable.ic_dialog_email),
                    mk("plan", Y.t("Plan my day", "Günümü planla"), "plan", android.R.drawable.ic_menu_my_calendar)
                )
            )
        } catch (e: Throwable) {
            android.util.Log.w("yaver", "shortcuts: $e")
        }
    }
}

/** Cancels a running request or agent turn. */
class Cancel {
    @Volatile var cancelled = false
    @Volatile var conn: java.net.HttpURLConnection? = null
    private val hooks = ArrayList<() -> Unit>()
    fun onCancel(f: () -> Unit) { synchronized(hooks) { hooks.add(f) } }
    fun cancel() {
        cancelled = true
        try { conn?.disconnect() } catch (_: Throwable) {}
        val l = synchronized(hooks) { ArrayList(hooks) }
        for (h in l) try { h() } catch (_: Throwable) {}
    }
    fun check() { if (cancelled) throw CancelledException() }
}

class CancelledException : RuntimeException("stopped")

fun clip(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n) + "\n…[${s.length - n} more characters]"
