package com.plusai.yaver.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Always on: a quiet foreground service. It runs reminders and scheduled tasks in the background, checks the
 * company inbox for new mail and keeps yaver ready. Starts again after the phone restarts.
 */
class AgentService : Service() {
    companion object {
        @Volatile var running = false
        private const val NOTIF_ID = 7
        private val jobs = AtomicInteger(0)

        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, AgentService::class.java)) } catch (e: Throwable) { android.util.Log.w("yaver", "service: $e") }
        }

        fun stop(c: Context) {
            try { c.startService(Intent(c, AgentService::class.java).setAction("stop")) } catch (_: Throwable) {}
        }

        /** Runs a request without the screen (reminder prompts, scheduled tasks); the answer arrives as a notification. */
        fun runBackground(c: Context, prompt: String, title: String) {
            val i = Intent(c, AgentService::class.java).setAction("run").putExtra("prompt", prompt).putExtra("title", title)
            try { c.startForegroundService(i) } catch (e: Throwable) { work(c, prompt, title, null) }
        }

        fun work(c: Context, prompt: String, title: String, svc: AgentService?) {
            jobs.incrementAndGet()
            Y.bg {
                val pm = c.getSystemService(PowerManager::class.java)
                val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaver:task")
                try {
                    wl?.acquire(15 * 60 * 1000L)
                    if (!Cfg.llmReady) throw IllegalStateException(Y.t("no model is set up", "model ayarlanmamış"))
                    val convo = Convo.create(title, background = true)
                    val answer = Agent.run(convo, prompt, background = true, label = title)
                    Notifier.show(c, "⏰ $title", answer.replace(Regex("[*#`]"), "").trim().take(1500).ifEmpty { Y.t("Done.", "Tamamlandı.") }, "open_convo", convo.id, Notifier.CH_REMIND)
                } catch (e: Throwable) {
                    Notifier.show(c, "⏰ $title", Y.t("Could not finish: ", "Tamamlanamadı: ") + (e.message ?: e.toString()), "open", null, Notifier.CH_REMIND)
                } finally {
                    try { if (wl?.isHeld == true) wl.release() } catch (_: Throwable) {}
                    if (jobs.decrementAndGet() == 0 && !Prefs.on("alwaysOn", true)) svc?.let { Y.ui { it.stopSelf() } }
                }
            }
        }
    }

    private var timer: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pi(action: String, code: Int): PendingIntent = PendingIntent.getActivity(
        this, code, Intent(this, MainActivity::class.java).putExtra(Keys.TOKEN, Keys.token()).putExtra(Keys.ACTION, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(): Notification = Notification.Builder(this, Notifier.CH_SERVICE)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(Y.t("yaver is on", "yaver açık"))
        .setContentText(Y.t("Tap to ask · mail, reminders and tasks keep running", "Sormak için dokunun · posta, hatırlatmalar ve görevler çalışıyor"))
        .setOngoing(true).setShowWhen(false)
        .setContentIntent(pi("assist", 101))
        .addAction(Notification.Action.Builder(Icon.createWithResource("android", android.R.drawable.ic_btn_speak_now), Y.t("Ask by voice", "Sesle sor"), pi("voice", 102)).build())
        .addAction(Notification.Action.Builder(Icon.createWithResource("android", android.R.drawable.ic_menu_add), Y.t("New chat", "Yeni sohbet"), pi("new", 103)).build())
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Y.init(this)
        try { Wiring.init(); Heartbeats.start(this) } catch (e: Throwable) { Diag.e("service", "heartbeats", e) }
        if (intent?.action == "stop") {
            running = false
            timer?.shutdownNow(); timer = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, notification())
            running = true
            registerScreenOn()
        } catch (e: Throwable) {
            runCatching { File(filesDir, "last-crash.txt").writeText("Always-on service could not start:\n" + android.util.Log.getStackTraceString(e)) }
            intent?.getStringExtra("prompt")?.let { work(this, it, intent.getStringExtra("title") ?: "yaver", null) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "run") {
            val p = intent.getStringExtra("prompt")
            if (!p.isNullOrBlank()) work(this, p, intent.getStringExtra("title") ?: "yaver", this)
        }
        if (timer == null && Prefs.on("alwaysOn", true)) {
            val t = Executors.newSingleThreadScheduledExecutor()
            timer = t
            t.scheduleWithFixedDelay({
                try { MailWatch.check(this) } catch (e: Throwable) { android.util.Log.w("yaver", "mail check: $e") }
                try { MailPlus.onMailCheck(this) } catch (e: Throwable) { Diag.e("mailx", "background check", e) }
                // yaver 5: keep the today brief (and the home-screen widget) fresh while widgets are placed
                try { if (FiveWidget.hasWidgets(this)) FiveBrief.refreshIfStale() } catch (e: Throwable) { Diag.e("brief", "background", e) }
            }, 20, maxOf(1, Cfg.mailPoll).toLong() * 60, TimeUnit.SECONDS)
        }
        return if (Prefs.on("alwaysOn", true)) START_STICKY else START_NOT_STICKY
    }

    /** The screen comes on: redraw the widget at once (times, the meeting that just ended) and refresh stale data. */
    private var screenOn: android.content.BroadcastReceiver? = null
    private fun registerScreenOn() {
        if (screenOn != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { if (FiveWidget.hasWidgets(c)) { FiveWidget.updateSoon(); FiveBrief.refreshIfStale() } } catch (e: Throwable) { Diag.e("widget", "screen on", e) }
            }
        }
        try {
            val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
            screenOn = r
        } catch (e: Throwable) { Diag.e("widget", "screen receiver", e) }
    }

    override fun onDestroy() {
        try { screenOn?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        screenOn = null
        running = false
        timer?.shutdownNow()
        super.onDestroy()
    }
}

/** New-mail notifications. */
object MailWatch {
    fun check(c: Context) {
        if (!Cfg.mailNotify || !Cfg.mailConfigured || Cfg.mailUser.isEmpty() && Cfg.mailMode.isEmpty()) return
        val r = Mail.use { it.find("inbox", "", true, 15, 0) }
        val seen = try { JSONArray(Prefs.get("mail.seen", "[]")) } catch (e: Exception) { JSONArray() }
        val set = (0 until seen.length()).map { seen.getString(it) }.toMutableList()
        val first = Prefs.get("mail.seenInit", "") != "1"
        val fresh = r.items.filter { it.id !in set }
        for (m in fresh) set.add(m.id)
        while (set.size > 400) set.removeAt(0)
        Prefs.put("mail.seen", JSONArray(set).toString())
        Prefs.put("mail.seenInit", "1")
        if (first || fresh.isEmpty()) return
        if (fresh.size <= 3) for (m in fresh) {
            Notifier.show(c, "📬 " + (m.from?.short ?: "?"), m.subject + if (m.preview.isNotBlank()) "\n" + m.preview.replace(Regex("\\s+"), " ").take(200) else "", "mail_open", m.id, Notifier.CH_MAIL,
                actions = { nid -> MailActions.forMail(c, m.id, nid) })
        } else Notifier.show(c, "📬 " + Y.t("${fresh.size} new e-mails", "${fresh.size} yeni e-posta"), fresh.take(6).joinToString("\n") { "${it.from?.short ?: "?"}: ${it.subject}" }, "mail", null, Notifier.CH_MAIL,
            actions = { nid -> MailActions.forMany(c, fresh.map { it.id }, nid) })
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Y.init(context)
        try { Schedule.rearmAll(context) } catch (_: Throwable) {}
        try { Heartbeats.arm(context) } catch (e: Throwable) { Diag.e("boot", "heartbeats", e) }
        try { MailKeepAlive.arm(context) } catch (e: Throwable) { Diag.e("boot", "keepalive", e) }
        if (Prefs.on("alwaysOn", true)) AgentService.start(context)
    }
}

class YaverTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        t.state = Tile.STATE_INACTIVE
        t.label = "Ask yaver"
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        Y.init(this)
        val i = Intent(this, MainActivity::class.java).putExtra(Keys.TOKEN, Keys.token()).putExtra(Keys.ACTION, if (Prefs.on("tileVoice", true)) "voice" else "assist")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 201, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }
}


/** Buttons on new-mail notifications: ask yaver, mark read, delete (one e-mail); summarize, mark all read (several). */
object MailActions {
    const val ACTION = "com.plusai.yaver.MAIL_ACTION"
    private fun broadcast(c: Context, what: String, ids: List<String>, nid: Int, req: Int): PendingIntent =
        PendingIntent.getBroadcast(c, req, Intent(c, MailActionReceiver::class.java).setAction(ACTION).putExtra("what", what)
            .putExtra("ids", ids.toTypedArray()).putExtra("nid", nid), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun act(label: String, pi: PendingIntent) = android.app.Notification.Action.Builder(null as Icon?, label, pi).build()

    private fun open(c: Context, action: String, payload: String, nid: Int, req: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Keys.TOKEN, Keys.token()).putExtra(Keys.ACTION, action).putExtra(Keys.PAYLOAD, payload).putExtra(Keys.NOTIF_ID, nid)
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun forMail(c: Context, id: String, nid: Int): List<android.app.Notification.Action> = listOf(
        act(Y.t("Ask yaver", "yaver'e sor"), open(c, "mail_ask", id, nid, nid * 10 + 1)),
        act(Y.t("Mark read", "Okundu"), broadcast(c, "read", listOf(id), nid, nid * 10 + 2)),
        act(Y.t("Delete", "Sil"), broadcast(c, "delete", listOf(id), nid, nid * 10 + 3))
    )

    fun forMany(c: Context, ids: List<String>, nid: Int): List<android.app.Notification.Action> = listOf(
        act(Y.t("Summarize", "Özetle"), open(c, "mail_summary", ids.joinToString(","), nid, nid * 10 + 1)),
        act(Y.t("Mark all read", "Tümü okundu"), broadcast(c, "read", ids, nid, nid * 10 + 2))
    )
}

class MailActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Y.init(context)
        val what = intent.getStringExtra("what") ?: return
        val ids = intent.getStringArrayExtra("ids")?.toList().orEmpty()
        val nid = intent.getIntExtra("nid", -1)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm?.cancel(nid)
        val pending = goAsync()
        Y.bg {
            try {
                for (id in ids) {
                    when (what) {
                        "read" -> { Mail.use { it.markRead(id, true) }; try { MailIndex.markRead(id, true) } catch (_: Throwable) {} }
                        "delete" -> { Mail.use { it.remove(id, false) }; try { MailIndex.forget(id) } catch (_: Throwable) {} }
                    }
                }
                Diag.i("mail", "notification action $what ×${ids.size}")
                Bridge.emit("mail.changed", org.json.JSONObject().put("what", what))
                Y.ui { android.widget.Toast.makeText(context, if (what == "delete") Y.t("Moved to Deleted Items", "Silinmiş Öğeler'e taşındı") else Y.t("Marked as read", "Okundu olarak işaretlendi"), android.widget.Toast.LENGTH_SHORT).show() }
            } catch (e: Throwable) {
                Diag.e("mail", "notification action $what", e)
                Notifier.show(context, Y.t("Could not do it", "Yapılamadı"), (e.message ?: e.toString()).removePrefix("SIGNIN:").take(300), if (e is LoginRequired) "mail_login" else "mail", null, Notifier.CH_MAIL)
            } finally { pending.finish() }
        }
    }
}
