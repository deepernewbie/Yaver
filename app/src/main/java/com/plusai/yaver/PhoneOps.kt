package com.plusai.yaver

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.SearchManager
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
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The phone actions yaver's tools call through window.__yn.call(method, args). Runs on a worker thread. */
object PhoneOps {
    fun run(c: Context, method: String, a: JSONObject): Any? = when (method) {
        "calendar.calendars" -> Cal.calendars(c)
        "calendar.list" -> Cal.list(c, a)
        "calendar.create" -> Cal.create(c, a)
        "calendar.delete" -> Cal.delete(c, a)
        "alarm.set" -> alarm(c, a)
        "timer.set" -> timer(c, a)
        "alarm.show" -> {
            launch(c, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            "opened the alarms list"
        }
        "remind.add" -> ReminderStore.add(c, a)
        "remind.list" -> ReminderStore.all()
        "remind.cancel" -> ReminderStore.cancel(c, a.optInt("id"))
        "notify" -> {
            val id = Notifier.show(c, a.optString("title", "yaver"), a.optString("text", ""), "open", null)
            if (id < 0) throw SecurityException("notifications are turned off for yaver — allow them in Settings → Apps → yaver → Notifications")
            "notification shown"
        }
        "open" -> open(c, a)
        "apps" -> apps(c, a)
        "info" -> info(c)
        "clipboard.get" -> clipGet(c)
        "clipboard.set" -> {
            onMain { c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("yaver", a.optString("text"))) }
            "copied"
        }
        "speak" -> Speaker.speak(c, a.optString("text"), a.optString("lang", ""))
        "speak.stop" -> Speaker.stop()
        "listen" -> Speech.listen(a)
        else -> throw IllegalArgumentException("unknown phone action: $method")
    }

    /** Runs [f] on the main thread and waits for it. */
    fun <T> onMain(f: () -> T): T? {
        var out: T? = null
        var err: Throwable? = null
        val l = CountDownLatch(1)
        YaverCore.main.post {
            try { out = f() } catch (e: Throwable) { err = e } finally { l.countDown() }
        }
        l.await(15, TimeUnit.SECONDS)
        err?.let { throw it }
        return out
    }

    fun launch(c: Context, i: Intent) {
        try {
            onMain {
                val act = YaverCore.activity
                if (act != null && !act.isFinishing) act.startActivity(i)
                else c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("no app on this phone can do that (${i.action})")
        }
    }

    private fun alarm(c: Context, a: JSONObject): String {
        if (!a.has("hour")) throw IllegalArgumentException("hour is required")
        val h = a.getInt("hour")
        val m = a.optInt("minute", 0)
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, a.optBoolean("skipUi", true))
        val label = a.optString("label", "")
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val days = a.optJSONArray("days")
        if (days != null && days.length() > 0) {
            val list = ArrayList<Int>()
            for (k in 0 until days.length()) list.add(days.getInt(k))
            i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, list)
        }
        launch(c, i)
        return String.format(Locale.US, "alarm set for %02d:%02d", h, m) + if (label.isNotEmpty()) " ($label)" else ""
    }

    private fun timer(c: Context, a: JSONObject): String {
        val s = a.optInt("seconds", 0)
        if (s <= 0) throw IllegalArgumentException("seconds must be more than 0")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, a.optBoolean("skipUi", true))
        val label = a.optString("label", "")
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        launch(c, i)
        return "timer started for ${s / 60} min ${s % 60} s"
    }

    private fun open(c: Context, a: JSONObject): String {
        val target = a.optString("target", "url")
        val v = a.optString("value", "")
        val i: Intent = when (target) {
            "url" -> {
                if (a.optBoolean("inApp", true)) {
                    onMain { BrowserActivity.open(YaverCore.activity ?: c, v) }
                    return "opened $v in yaver's browser"
                }
                Intent(Intent.ACTION_VIEW, Uri.parse(v))
            }
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(v)))
            "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(v))).putExtra("sms_body", a.optString("body"))
            "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(v, "@,")))
                .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
                .putExtra(Intent.EXTRA_TEXT, a.optString("body"))
            "maps" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(v)))
            "navigate" -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(v)))
            "web_search" -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, v)
            "share" -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v), "Share")
            "app" -> return openApp(c, v)
            "settings" -> Intent(
                when (v.lowercase()) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "assistant" -> Settings.ACTION_VOICE_INPUT_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
            )
            else -> throw IllegalArgumentException("unknown target \"$target\" (use url, dial, sms, email, maps, navigate, web_search, share, app, settings)")
        }
        launch(c, i)
        return "opened $target" + if (v.isNotEmpty()) ": $v" else ""
    }

    @Suppress("DEPRECATION")
    private fun launcherApps(c: Context) =
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)

    private fun openApp(c: Context, name: String): String {
        val pm = c.packageManager
        val q = name.lowercase().trim()
        if (q.isEmpty()) throw IllegalArgumentException("which app?")
        val list = launcherApps(c)
        val hit = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: list.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) }
            ?: throw IllegalArgumentException("no app called \"$name\" on this phone")
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: throw IllegalStateException("${hit.loadLabel(pm)} cannot be opened directly")
        launch(c, i)
        return "opened ${hit.loadLabel(pm)}"
    }

    private fun apps(c: Context, a: JSONObject): JSONArray {
        val pm = c.packageManager
        val q = a.optString("query", "").lowercase()
        val names = launcherApps(c).map { it.loadLabel(pm).toString() }.distinct().sortedBy { it.lowercase() }
            .filter { q.isEmpty() || it.lowercase().contains(q) }
        return JSONArray(names.take(400))
    }

    private fun info(c: Context): JSONObject {
        val o = JSONObject()
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) o.put("battery", level * 100 / scale)
            o.put("charging", bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        o.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        o.put("android", Build.VERSION.RELEASE)
        o.put("locale", Locale.getDefault().toLanguageTag())
        o.put("timezone", TimeZone.getDefault().id)
        o.put("now", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = if (cm != null && net != null) cm.getNetworkCapabilities(net) else null
            o.put(
                "network", when {
                    caps == null -> "offline"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "connected"
                }
            )
        } catch (_: Throwable) {}
        val assistant = Settings.Secure.getString(c.contentResolver, "assistant") ?: ""
        o.put("yaverIsDefaultAssistant", assistant.contains(c.packageName))
        o.put("alwaysOn", KeepAliveService.running)
        return o
    }

    private fun clipGet(c: Context): String {
        val t = onMain {
            val cm = c.getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(c)?.toString() else null
        }
        return t ?: "(the clipboard is empty, or Android hid it because yaver was not on screen)"
    }
}

/** Waits for runtime permission answers from the open screen. */
object PermGate {
    private val lock = Any()
    @Volatile private var latch: CountDownLatch? = null

    fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun ensure(c: Context, perms: Array<String>, why: String) {
        if (perms.all { has(c, it) }) return
        synchronized(lock) {
            val missing = perms.filter { !has(c, it) }
            if (missing.isNotEmpty()) {
                val act = YaverCore.activity
                    ?: throw SecurityException("$why needs a permission. Open yaver and try again so it can ask you.")
                val l = CountDownLatch(1)
                latch = l
                YaverCore.main.post {
                    try { act.requestPermissions(missing.toTypedArray(), MainActivity.REQ_PERMS) } catch (e: Throwable) { l.countDown() }
                }
                l.await(120, TimeUnit.SECONDS)
                latch = null
            }
        }
        val still = perms.filter { !has(c, it) }
        if (still.isNotEmpty()) {
            throw SecurityException("$why: permission was not given (${still.joinToString { it.substringAfterLast('.') }}). Allow it in Settings → Apps → yaver → Permissions.")
        }
    }

    fun done() { latch?.countDown() }
}

/** The phone's own calendars through CalendarContract. */
object Cal {
    private val READ = arrayOf(Manifest.permission.READ_CALENDAR)
    private val RW = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun calendars(c: Context): JSONArray {
        PermGate.ensure(c, READ, "Reading the phone calendar")
        val out = JSONArray()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE
        )
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("id", cur.getLong(0))
                        .put("name", cur.getString(1) ?: "")
                        .put("account", cur.getString(2) ?: "")
                        .put("writable", cur.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                        .put("primary", cur.getInt(4) == 1)
                        .put("visible", cur.getInt(5) == 1)
                )
            }
        }
        return out
    }

    private fun pickCalendar(c: Context): Long {
        val all = calendars(c)
        var best: JSONObject? = null
        for (k in 0 until all.length()) {
            val o = all.getJSONObject(k)
            if (!o.optBoolean("writable")) continue
            if (best == null) best = o
            if (o.optBoolean("primary") && o.optBoolean("visible")) { best = o; break }
        }
        return best?.getLong("id") ?: throw IllegalStateException("there is no writable calendar on this phone (add a Google account or a local calendar first)")
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun utcMidnight(ms: Long): Long {
        val l = Calendar.getInstance()
        l.timeInMillis = ms
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        u.clear()
        u.set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
        return u.timeInMillis
    }

    fun list(c: Context, a: JSONObject): JSONArray {
        PermGate.ensure(c, READ, "Reading the phone calendar")
        val start = if (a.has("start")) a.getLong("start") else startOfToday()
        val end = if (a.has("end")) a.getLong("end") else start + 8 * 86400000L
        val q = a.optString("query", "").lowercase()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start)
        ContentUris.appendId(b, end)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        val out = JSONArray()
        c.contentResolver.query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            while (cur.moveToNext() && out.length() < 300) {
                val title = cur.getString(1) ?: "(no title)"
                val loc = cur.getString(5) ?: ""
                val desc = cur.getString(6) ?: ""
                if (q.isNotEmpty() && !(title.lowercase().contains(q) || loc.lowercase().contains(q) || desc.lowercase().contains(q))) continue
                val allDay = cur.getInt(4) == 1
                var s = cur.getLong(2)
                var e = cur.getLong(3)
                if (allDay) {
                    // all-day events are stored at UTC midnight: show them on the right local day
                    val off = TimeZone.getDefault().getOffset(s)
                    s -= off; e -= off
                }
                out.put(
                    JSONObject().put("id", cur.getLong(0)).put("title", title).put("start", s).put("end", e)
                        .put("allDay", allDay).put("location", loc).put("description", desc.take(300))
                        .put("calendar", cur.getString(7) ?: "")
                )
            }
        }
        return out
    }

    fun create(c: Context, a: JSONObject): JSONObject {
        PermGate.ensure(c, RW, "Adding to the phone calendar")
        val title = a.optString("title", "").trim()
        if (title.isEmpty()) throw IllegalArgumentException("title is required")
        if (!a.has("start")) throw IllegalArgumentException("start is required")
        val allDay = a.optBoolean("allDay", false)
        var start = a.getLong("start")
        var end = if (a.has("end")) a.getLong("end") else start + if (allDay) 86400000L else 3600000L
        val calId = if (a.has("calendarId")) a.getLong("calendarId") else pickCalendar(c)
        val tz: String
        if (allDay) {
            start = utcMidnight(start)
            end = maxOf(utcMidnight(end), start + 86400000L)
            tz = "UTC"
        } else {
            if (end <= start) end = start + 3600000L
            tz = TimeZone.getDefault().id
        }
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calId)
        v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.DTSTART, start)
        v.put(CalendarContract.Events.DTEND, end)
        v.put(CalendarContract.Events.EVENT_TIMEZONE, tz)
        if (allDay) v.put(CalendarContract.Events.ALL_DAY, 1)
        val loc = a.optString("location", "")
        if (loc.isNotEmpty()) v.put(CalendarContract.Events.EVENT_LOCATION, loc)
        val desc = a.optString("description", "")
        if (desc.isNotEmpty()) v.put(CalendarContract.Events.DESCRIPTION, desc)
        val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            ?: throw IllegalStateException("the calendar did not accept the event")
        val id = ContentUris.parseId(uri)
        val mins = a.optInt("reminderMinutes", -1)
        if (mins >= 0) {
            try {
                val r = ContentValues()
                r.put(CalendarContract.Reminders.EVENT_ID, id)
                r.put(CalendarContract.Reminders.MINUTES, mins)
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
            } catch (_: Throwable) {}
        }
        return JSONObject().put("id", id).put("calendarId", calId)
    }

    fun delete(c: Context, a: JSONObject): String {
        PermGate.ensure(c, RW, "Changing the phone calendar")
        val id = a.optLong("id", -1)
        if (id < 0) throw IllegalArgumentException("id is required")
        val n = c.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        if (n == 0) throw IllegalArgumentException("no phone calendar event #$id")
        return "deleted"
    }
}

/** Reminders that fire as notifications through AlarmManager, also when yaver is closed. */
object ReminderStore {
    private val lock = Any()

    fun all(): JSONArray = try { JSONArray(Prefs.get("reminders", "[]")) } catch (e: Exception) { JSONArray() }
    private fun save(a: JSONArray) = Prefs.put("reminders", a.toString())

    fun add(c: Context, a: JSONObject): JSONObject {
        if (!a.has("at")) throw IllegalArgumentException("at (time) is required")
        val at = a.getLong("at")
        if (at < System.currentTimeMillis() - 60000) throw IllegalArgumentException("that time is in the past")
        val r = JSONObject()
            .put("id", Random.nextInt(1, Int.MAX_VALUE))
            .put("at", at)
            .put("title", a.optString("title", "Reminder").ifBlank { "Reminder" })
            .put("text", a.optString("text", ""))
            .put("prompt", a.optString("prompt", ""))
        synchronized(lock) {
            val arr = all()
            arr.put(r)
            save(arr)
        }
        schedule(c, r)
        return r
    }

    private fun pi(c: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, id,
            Intent(c, ReminderReceiver::class.java).setAction("com.plusai.yaver.REMIND").putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(c: Context, r: JSONObject) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(c, r.getInt("id"))
        val at = r.getLong("at")
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun cancel(c: Context, id: Int): String {
        take(id) ?: throw IllegalArgumentException("no reminder #$id")
        c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, id))
        return "cancelled reminder #$id"
    }

    fun take(id: Int): JSONObject? = synchronized(lock) {
        val arr = all()
        val keep = JSONArray()
        var found: JSONObject? = null
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            if (o.optInt("id") == id && found == null) found = o else keep.put(o)
        }
        if (found != null) save(keep)
        found
    }

    fun rescheduleAll(c: Context) {
        val arr = all()
        val now = System.currentTimeMillis()
        for (k in 0 until arr.length()) {
            val r = arr.getJSONObject(k)
            if (r.getLong("at") <= now) {
                take(r.getInt("id"))
                fire(c, r, late = true)
            } else schedule(c, r)
        }
    }

    fun fire(c: Context, r: JSONObject, late: Boolean = false) {
        val title = r.optString("title", "Reminder")
        val text = r.optString("text", "")
        val prompt = r.optString("prompt", "")
        val prefix = if (late) "(missed) " else ""
        if (prompt.isNotEmpty() && Prefs.on("runReminders", true) && YaverCore.pageReady && YaverCore.web != null) {
            YaverCore.js("window.__yn&&__yn.runPrompt(${JSONObject.quote(prompt)},${JSONObject.quote(title)})")
            Notifier.show(c, prefix + title, text.ifEmpty { "yaver is working on: $prompt" }, "open", null, Notifier.CH_REMIND)
        } else if (prompt.isNotEmpty()) {
            Notifier.show(c, prefix + title, (if (text.isNotEmpty()) "$text\n" else "") + "Tap to let yaver: $prompt", "run", prompt, Notifier.CH_REMIND)
        } else {
            Notifier.show(c, prefix + title, text.ifEmpty { title }, "open", null, Notifier.CH_REMIND)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        YaverCore.init(context)
        val id = intent.getIntExtra("id", 0)
        val r = ReminderStore.take(id) ?: return
        ReminderStore.fire(context, r)
    }
}

/** Voice input through the phone's speech recognizer (Google app or the maker's). */
object Speech {
    private val lock = Any()
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var result: String? = null
    @Volatile private var error: String? = null

    fun listen(a: JSONObject): String {
        synchronized(lock) {
            val act = YaverCore.activity ?: throw IllegalStateException("open yaver to use the microphone")
            val l = CountDownLatch(1)
            latch = l
            result = null
            error = null
            val lang = a.optString("lang", "")
            val prompt = a.optString("prompt", "Speak to yaver")
            YaverCore.main.post {
                try {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                    if (lang.isNotEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    act.startActivityForResult(i, MainActivity.REQ_SPEECH)
                } catch (e: Throwable) {
                    error = "speech recognition is not available on this phone (install or enable the Google app)"
                    l.countDown()
                }
            }
            if (!l.await(180, TimeUnit.SECONDS)) {
                latch = null
                throw IllegalStateException("no speech heard")
            }
            latch = null
            error?.let { throw IllegalStateException(it) }
            return result ?: ""
        }
    }

    fun deliver(text: String?, err: String?) {
        result = text
        error = err
        latch?.countDown()
    }
}

/** Reads text aloud. */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayList<Pair<String, String>>()

    fun speak(c: Context, text: String, lang: String): String {
        if (text.isBlank()) return "nothing to say"
        YaverCore.main.post {
            val t = tts
            if (t == null) {
                queue.add(Pair(text, lang))
                tts = TextToSpeech(c.applicationContext, this)
            } else if (ready) say(t, text, lang) else queue.add(Pair(text, lang))
        }
        return "speaking"
    }

    fun stop(): String {
        YaverCore.main.post { tts?.stop() }
        return "stopped"
    }

    private fun say(t: TextToSpeech, text: String, lang: String) {
        try { if (lang.isNotEmpty()) t.language = Locale.forLanguageTag(lang) } catch (_: Throwable) {}
        var rest = text
        while (rest.isNotEmpty()) {
            val part = rest.take(3500)
            rest = rest.drop(3500)
            t.speak(part, TextToSpeech.QUEUE_ADD, null, "y" + System.nanoTime())
        }
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        val t = tts
        if (ready && t != null) for (q in queue) say(t, q.first, q.second)
        queue.clear()
    }
}
