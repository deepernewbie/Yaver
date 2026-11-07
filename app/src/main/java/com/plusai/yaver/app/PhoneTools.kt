package com.plusai.yaver.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Waits for runtime permission answers from the open screen. */
object PermGate {
    private val lock = Any()
    @Volatile private var latch: CountDownLatch? = null
    @Volatile var activity: android.app.Activity? = null

    fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun ensure(c: Context, perms: Array<String>, why: String) {
        if (perms.all { has(c, it) }) return
        synchronized(lock) {
            val missing = perms.filter { !has(c, it) }
            if (missing.isNotEmpty()) {
                val act = activity ?: throw SecurityException("$why needs a permission. Open yaver and try again so it can ask.")
                val l = CountDownLatch(1)
                latch = l
                Y.main.post { try { act.requestPermissions(missing.toTypedArray(), 41) } catch (e: Throwable) { l.countDown() } }
                l.await(120, TimeUnit.SECONDS)
                latch = null
            }
        }
        val still = perms.filter { !has(c, it) }
        if (still.isNotEmpty()) throw SecurityException("$why: permission not given (${still.joinToString { it.substringAfterLast('.') }}). Allow it in Settings → Apps → yaver → Permissions.")
    }

    fun done() { latch?.countDown() }
}

object Phone {
    fun <T> onMain(f: () -> T): T? {
        var out: T? = null
        var err: Throwable? = null
        val l = CountDownLatch(1)
        Y.main.post { try { out = f() } catch (e: Throwable) { err = e } finally { l.countDown() } }
        l.await(15, TimeUnit.SECONDS)
        err?.let { throw it }
        return out
    }

    fun launch(i: Intent) {
        try {
            onMain {
                val a = PermGate.activity
                if (a != null && !a.isFinishing) a.startActivity(i) else Y.app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("no app on this phone can do that (${i.action})")
        }
    }

    // ---------------- the phone's own calendars ----------------
    private val READ = arrayOf(Manifest.permission.READ_CALENDAR)
    private val RW = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun calendars(): JSONArray {
        PermGate.ensure(Y.app, READ, "Reading the phone calendar")
        val out = JSONArray()
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE)
        Y.app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) out.put(JSONObject().put("id", cur.getLong(0)).put("name", cur.getString(1) ?: "").put("account", cur.getString(2) ?: "")
                .put("writable", cur.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR).put("primary", cur.getInt(4) == 1).put("visible", cur.getInt(5) == 1))
        }
        return out
    }

    private fun pickCalendar(): Long {
        val all = calendars()
        var best: JSONObject? = null
        for (k in 0 until all.length()) {
            val o = all.getJSONObject(k)
            if (!o.optBoolean("writable")) continue
            if (best == null) best = o
            if (o.optBoolean("primary") && o.optBoolean("visible")) { best = o; break }
        }
        return best?.getLong("id") ?: throw IllegalStateException("there is no writable calendar on this phone")
    }

    fun calList(start: Long, end: Long, q: String): List<JSONObject> {
        PermGate.ensure(Y.app, READ, "Reading the phone calendar")
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start); ContentUris.appendId(b, end)
        val proj = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val out = ArrayList<JSONObject>()
        Y.app.contentResolver.query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            while (cur.moveToNext() && out.size < 300) {
                val title = cur.getString(1) ?: "(no title)"
                val loc = cur.getString(5) ?: ""
                if (q.isNotEmpty() && !(title.lowercase().contains(q.lowercase()) || loc.lowercase().contains(q.lowercase()))) continue
                val allDay = cur.getInt(4) == 1
                var s = cur.getLong(2); var e = cur.getLong(3)
                if (allDay) { val off = TimeZone.getDefault().getOffset(s); s -= off; e -= off }
                out.add(JSONObject().put("id", cur.getLong(0)).put("title", title).put("start", s).put("end", e).put("allDay", allDay).put("location", loc).put("calendar", cur.getString(6) ?: ""))
            }
        }
        return out
    }

    private fun utcMidnight(ms: Long): Long {
        val l = Calendar.getInstance(); l.timeInMillis = ms
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")); u.clear()
        u.set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
        return u.timeInMillis
    }

    fun calCreate(title: String, start0: Long, end0: Long?, allDay: Boolean, location: String, desc: String, reminder: Int, calId0: Long?): Long {
        PermGate.ensure(Y.app, RW, "Adding to the phone calendar")
        var start = start0
        var end = end0 ?: (start + if (allDay) 86400000L else 3600000L)
        val calId = calId0 ?: pickCalendar()
        val tz: String
        if (allDay) { start = utcMidnight(start); end = maxOf(utcMidnight(end), start + 86400000L); tz = "UTC" } else { if (end <= start) end = start + 3600000L; tz = TimeZone.getDefault().id }
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calId); v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.DTSTART, start); v.put(CalendarContract.Events.DTEND, end); v.put(CalendarContract.Events.EVENT_TIMEZONE, tz)
        if (allDay) v.put(CalendarContract.Events.ALL_DAY, 1)
        if (location.isNotEmpty()) v.put(CalendarContract.Events.EVENT_LOCATION, location)
        if (desc.isNotEmpty()) v.put(CalendarContract.Events.DESCRIPTION, desc)
        val uri = Y.app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw IllegalStateException("the calendar did not accept the event")
        val id = ContentUris.parseId(uri)
        if (reminder >= 0) try {
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, id); r.put(CalendarContract.Reminders.MINUTES, reminder); r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            Y.app.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
        } catch (_: Throwable) {}
        return id
    }

    fun calDelete(id: Long) {
        PermGate.ensure(Y.app, RW, "Changing the phone calendar")
        if (Y.app.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) == 0) throw IllegalArgumentException("no phone calendar event #$id")
    }

    // ---------------- apps, device ----------------
    @Suppress("DEPRECATION")
    fun launcherApps() = Y.app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)

    fun openApp(name: String): String {
        val pm = Y.app.packageManager
        val q = name.lowercase().trim()
        val list = launcherApps()
        val hit = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == q } ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: list.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) } ?: throw IllegalArgumentException("no app called \"$name\" on this phone")
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: throw IllegalStateException("${hit.loadLabel(pm)} cannot be opened directly")
        launch(i)
        return "opened ${hit.loadLabel(pm)}"
    }

    fun info(): String {
        val sb = StringBuilder()
        val bi = Y.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) sb.append("battery: ${level * 100 / scale}%").append(if (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) " (charging)\n" else "\n")
        }
        sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        sb.append("language: ${Locale.getDefault().toLanguageTag()} · time zone: ${TimeZone.getDefault().id}\n")
        try {
            val cm = Y.app.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = if (cm != null && net != null) cm.getNetworkCapabilities(net) else null
            sb.append("network: ").append(when {
                caps == null -> "offline"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"; caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"; else -> "connected"
            }).append("\n")
        } catch (_: Throwable) {}
        val assistant = Settings.Secure.getString(Y.app.contentResolver, "assistant") ?: ""
        sb.append("yaver is the default assistant: ${assistant.contains(Y.app.packageName)}\n")
        sb.append("always on: ${AgentService.running}")
        return sb.toString()
    }
}

/** Reads text aloud. */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayList<String>()

    fun speak(text: String) {
        val t0 = text.replace(Regex("[*#`_>|]"), "").replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1").trim()
        if (t0.isEmpty()) return
        Y.main.post {
            val t = tts
            if (t == null) { queue.add(t0); tts = TextToSpeech(Y.app, this) } else if (ready) say(t, t0) else queue.add(t0)
        }
    }

    fun stop() { Y.main.post { tts?.stop() } }

    private fun say(t: TextToSpeech, text: String) {
        try { t.language = if (Y.tr) Locale("tr", "TR") else Locale.getDefault() } catch (_: Throwable) {}
        var rest = text
        while (rest.isNotEmpty()) { t.speak(rest.take(3500), TextToSpeech.QUEUE_ADD, null, "y" + System.nanoTime()); rest = rest.drop(3500) }
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        val t = tts
        if (ready && t != null) for (q in queue) say(t, q)
        queue.clear()
    }
}

/**
 * Reminders (one notification at a time, optionally with a request yaver runs then) and scheduled tasks
 * (a request repeated every N minutes or daily at a time). AlarmManager wakes yaver even when it is closed.
 */
object Schedule {
    private val lock = Any()

    fun all(): JSONArray = try { JSONArray(Prefs.get("schedule", "[]")) } catch (e: Exception) { JSONArray() }
    private fun save(a: JSONArray) = Prefs.put("schedule", a.toString())

    private fun pi(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c, id, Intent(c, ScheduleReceiver::class.java).setAction("com.plusai.yaver.SCHEDULE").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun nextDaily(hhmm: String, from: Long = System.currentTimeMillis()): Long {
        val p = hhmm.split(":")
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        cal.set(Calendar.HOUR_OF_DAY, p[0].trim().toInt()); cal.set(Calendar.MINUTE, p.getOrNull(1)?.trim()?.toInt() ?: 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= from + 1000) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun add(o: JSONObject): JSONObject {
        o.put("id", Random.nextInt(1, Int.MAX_VALUE))
        synchronized(lock) { val a = all(); a.put(o); save(a) }
        arm(Y.app, o)
        return o
    }

    fun arm(c: Context, o: JSONObject) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val at = o.getLong("next")
        val p = pi(c, o.getInt("id"))
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    fun remove(id: Int): JSONObject? {
        var found: JSONObject? = null
        synchronized(lock) {
            val a = all(); val keep = JSONArray()
            for (k in 0 until a.length()) { val o = a.getJSONObject(k); if (o.optInt("id") == id && found == null) found = o else keep.put(o) }
            save(keep)
        }
        Y.app.getSystemService(AlarmManager::class.java)?.cancel(pi(Y.app, id))
        return found
    }

    fun get(id: Int): JSONObject? { val a = all(); for (k in 0 until a.length()) if (a.getJSONObject(k).optInt("id") == id) return a.getJSONObject(k); return null }

    private fun update(o: JSONObject) = synchronized(lock) {
        val a = all(); val out = JSONArray()
        for (k in 0 until a.length()) { val x = a.getJSONObject(k); out.put(if (x.optInt("id") == o.optInt("id")) o else x) }
        save(out)
    }

    /** An alarm went off. */
    fun fire(c: Context, id: Int) {
        val o = get(id) ?: return
        val kind = o.optString("kind")
        val title = o.optString("title").ifEmpty { if (kind == "task") Y.t("Scheduled task", "Zamanlanmış görev") else Y.t("Reminder", "Hatırlatma") }
        val prompt = o.optString("prompt")
        if (kind == "task") {
            o.put("next", if (o.optString("daily").isNotEmpty()) nextDaily(o.optString("daily")) else System.currentTimeMillis() + o.optLong("every") * 60000L)
            update(o)
            arm(c, o)
        } else remove(id)
        if (prompt.isNotEmpty() && Prefs.on("runReminders", true)) {
            AgentService.runBackground(c, prompt, title)
            if (kind != "task" && o.optString("text").isNotEmpty()) Notifier.show(c, title, o.optString("text"), "open", null, Notifier.CH_REMIND)
        } else {
            Notifier.show(c, "⏰ $title", o.optString("text").ifEmpty { prompt.ifEmpty { title } }, if (prompt.isNotEmpty()) "ask" else "open", prompt.ifEmpty { null }, Notifier.CH_REMIND)
        }
    }

    fun rearmAll(c: Context) {
        val a = all()
        val now = System.currentTimeMillis()
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            if (o.optLong("next") <= now) {
                if (o.optString("kind") == "task") { o.put("next", if (o.optString("daily").isNotEmpty()) nextDaily(o.optString("daily")) else now + o.optLong("every") * 60000L); update(o); arm(c, o) }
                else { Notifier.show(c, "⏰ (" + Y.t("missed", "kaçırıldı") + ") " + o.optString("title"), o.optString("text").ifEmpty { o.optString("prompt") }, "open", null, Notifier.CH_REMIND); remove(o.optInt("id")) }
            } else arm(c, o)
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Y.init(context)
        if (intent.action == Heartbeats.ACTION) { Heartbeats.onAlarm(context); return }
        if (intent.action == S51Sched.ACTION) { S51Sched.onAlarm(context); return }
        Schedule.fire(context, intent.getIntExtra("id", 0))
    }
}

object PhoneTools {
    private fun ms(v: String, name: String): Long {
        if (v.matches(Regex("\\d{10,13}"))) return if (v.length <= 10) v.toLong() * 1000 else v.toLong()
        return MailTools.parseLocal(v).takeIf { it > 0 } ?: throw IllegalArgumentException("could not read the $name \"$v\" — use local ISO like 2026-11-02T09:30")
    }

    fun register() {
        Tools.add(Tool(
            "phone_calendar",
            "The phone's own calendars (Google, Samsung… synced on this device). The company Exchange calendar is the separate calendar tool. action=list (start, end — default today + 7 days; query), calendars, create (title, start, end, all_day, location, description, reminder_minutes, calendar_id), delete (event_id). Local times like 2026-11-02T09:30.",
            P.props("action" to P.s("list | calendars | create | delete", "list", "calendars", "create", "delete"), "start" to P.s("Start"), "end" to P.s("End"), "query" to P.s("Text filter"), "title" to P.s("Title"),
                "location" to P.s("Location"), "description" to P.s("Notes"), "all_day" to P.b("All-day"), "reminder_minutes" to P.n("Alert minutes before"), "calendar_id" to P.n("Calendar id"), "event_id" to P.n("Event id")),
            listOf("action")
        ) { a, ctx ->
            when (a.str("action")) {
                "calendars" -> { val cs = Phone.calendars(); (0 until cs.length()).joinToString("\n") { val o = cs.getJSONObject(it); "${o.getLong("id")}. ${o.optString("name")} (${o.optString("account")})${if (o.optBoolean("writable")) "" else " [read-only]"}${if (o.optBoolean("primary")) " [primary]" else ""}" }.ifEmpty { "(no calendars)" } }
                "list" -> {
                    val s = if (a.str("start").isNotEmpty()) ms(a.str("start"), "start") else Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
                    val e = if (a.str("end").isNotEmpty()) MailTools.parseLocal(a.str("end"), true) else s + 8 * 86400000L
                    val l = Phone.calList(s, e, a.str("query"))
                    if (l.isEmpty()) "(no events on the phone calendar in that range)" else l.joinToString("\n") { "#${it.getLong("id")} ${if (it.optBoolean("allDay")) MailTools.fmt(it.getLong("start")).substringBeforeLast(' ') + " (all day)" else MailTools.fmt(it.getLong("start")) + "–" + MailTools.hm(it.getLong("end"))} · ${it.optString("title")}${it.optString("location").let { l2 -> if (l2.isNotEmpty()) " @ $l2" else "" }} [${it.optString("calendar")}]" }
                }
                "create" -> {
                    val t = a.str("title").ifEmpty { throw IllegalArgumentException("title is required") }
                    val s = ms(a.str("start").ifEmpty { throw IllegalArgumentException("start is required") }, "start")
                    val id = Phone.calCreate(t, s, a.str("end").takeIf { it.isNotEmpty() }?.let { ms(it, "end") }, a.optBoolean("all_day"), a.str("location"), a.str("description"), a.int("reminder_minutes", -1), if (a.has("calendar_id")) a.optLong("calendar_id") else null)
                    "Added to the phone calendar (#$id): $t — ${MailTools.fmt(s)}"
                }
                "delete" -> {
                    val id = a.optLong("event_id", -1)
                    if (id < 0) throw IllegalArgumentException("event_id is required")
                    if (!ctx.approve(Y.t("Delete phone calendar event", "Telefon takviminden sil") + " #$id", emptyList(), danger = true)) return@Tool ctx.denied("delete event")
                    Phone.calDelete(id); "Deleted phone calendar event #$id"
                }
                else -> throw IllegalArgumentException("unknown action")
            }
        })

        Tools.add(Tool(
            "phone_alarm", "The phone's Clock app. action=alarm (hour 0-23, minute, label, days like [\"mon\",\"fri\"] to repeat), timer (seconds or minutes, label), show (open the alarm list).",
            P.props("action" to P.s("alarm | timer | show", "alarm", "timer", "show"), "hour" to P.n("Hour"), "minute" to P.n("Minute"), "seconds" to P.n("Timer seconds"), "minutes" to P.n("Timer minutes"), "label" to P.s("Label"), "days" to P.arr("Repeat days: sun mon tue wed thu fri sat")),
            listOf("action")
        ) { a, ctx ->
            if (ctx.background) return@Tool "Alarms can only be set while yaver is open."
            when (a.str("action")) {
                "show" -> { Phone.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)); "Opened the alarm list." }
                "timer" -> {
                    val s = a.int("seconds", 0) + a.int("minutes", 0) * 60
                    if (s <= 0) throw IllegalArgumentException("give seconds or minutes")
                    val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (a.str("label").isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.str("label"))
                    Phone.launch(i); "Timer started: ${s / 60} min ${s % 60} s."
                }
                else -> {
                    val h = a.int("hour", -1); val m = a.int("minute", 0)
                    if (h !in 0..23) throw IllegalArgumentException("hour 0-23 is required")
                    val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (a.str("label").isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.str("label"))
                    val map = mapOf("sun" to 1, "mon" to 2, "tue" to 3, "wed" to 4, "thu" to 5, "fri" to 6, "sat" to 7, "paz" to 1, "pzt" to 2, "sal" to 3, "çar" to 4, "car" to 4, "per" to 5, "cum" to 6, "cmt" to 7)
                    val days = ArrayList(a.list("days").mapNotNull { map[it.lowercase().take(3)] })
                    if (days.isNotEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
                    Phone.launch(i); String.format(Locale.US, "Alarm set for %02d:%02d.", h, m)
                }
            }
        })

        // reminders, scheduled and conditional tasks (yaver 5.1): schedule_task + phone_reminder
        S51Sched.register()

        Tools.add(Tool("phone_notify", "Show a notification on the phone now.", P.props("title" to P.s("Title"), "text" to P.s("Text")), listOf("text")) { a, _ ->
            if (Notifier.show(Y.app, a.str("title").ifEmpty { "yaver" }, a.str("text")) < 0) "Notifications are switched off for yaver." else "Notification shown."
        })

        Tools.add(Tool(
            "phone_open", "Open something on the phone: target url (yaver's browser; external=true for the phone's browser), dial (number — the user presses call), sms (number + body, opens the composer), email (address, subject, body — opens the e-mail app), maps (place), navigate (directions), web_search, share (text), app (installed app by name; value \"list\" lists apps), settings (wifi | bluetooth | display | sound | battery | location | apps | assistant).",
            P.props("target" to P.s("url | dial | sms | email | maps | navigate | web_search | share | app | settings"), "value" to P.s("URL, number, address, place, app name or text"), "subject" to P.s("E-mail subject"), "body" to P.s("SMS / e-mail text"), "external" to P.b("Use the phone's own browser")),
            listOf("target")
        ) { a, ctx ->
            if (ctx.background) return@Tool "This can only be done while yaver is open."
            val v = a.str("value")
            val i: Intent = when (a.str("target")) {
                "url" -> { if (!a.optBoolean("external")) { Phone.launch(Intent(Y.app, BrowserActivity::class.java).putExtra("url", v)); return@Tool "Opened $v." }; Intent(Intent.ACTION_VIEW, Uri.parse(v)) }
                "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(v)))
                "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(v))).putExtra("sms_body", a.str("body"))
                "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(v, "@,"))).putExtra(Intent.EXTRA_SUBJECT, a.str("subject")).putExtra(Intent.EXTRA_TEXT, a.str("body"))
                "maps" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(v)))
                "navigate" -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(v)))
                "web_search" -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, v)
                "share" -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v), "Share")
                "app" -> {
                    if (v.isEmpty() || v == "list") { val pm = Y.app.packageManager; return@Tool Phone.launcherApps().map { it.loadLabel(pm).toString() }.distinct().sorted().joinToString(", ") }
                    return@Tool Phone.openApp(v)
                }
                "settings" -> Intent(when (v.lowercase()) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS; "display" -> Settings.ACTION_DISPLAY_SETTINGS; "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY; "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS; "apps" -> Settings.ACTION_APPLICATION_SETTINGS; "assistant" -> Settings.ACTION_VOICE_INPUT_SETTINGS
                    else -> Settings.ACTION_SETTINGS })
                else -> throw IllegalArgumentException("unknown target")
            }
            Phone.launch(i)
            "Opened ${a.str("target")}${if (v.isNotEmpty()) ": $v" else ""}."
        })

        Tools.add(Tool("phone_info", "Battery, network, device, language, time zone, whether yaver is the default assistant.", JSONObject(), emptyList()) { _, _ -> Phone.info() })

        Tools.add(Tool("phone_clipboard", "Read (action=get) or set (action=set, text) the clipboard.", P.props("action" to P.s("get | set", "get", "set"), "text" to P.s("Text to copy")), listOf("action")) { a, _ ->
            if (a.str("action") == "set") { Phone.onMain { Y.app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("yaver", a.optString("text"))) }; "Copied." }
            else Phone.onMain { Y.app.getSystemService(ClipboardManager::class.java)?.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).coerceToText(Y.app)?.toString() else null } } ?: "(the clipboard is empty, or Android hid it because yaver was not on screen)"
        })

        Tools.add(Tool("phone_speak", "Read text aloud through the phone's speaker. action=stop stops.", P.props("text" to P.s("What to say"), "action" to P.s("say | stop", "say", "stop")), emptyList()) { a, _ ->
            if (a.str("action") == "stop") { Speaker.stop(); "Stopped." } else { Speaker.speak(a.str("text")); "Speaking." }
        })
    }
}
