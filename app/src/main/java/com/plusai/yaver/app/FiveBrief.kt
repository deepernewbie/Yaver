package com.plusai.yaver.app

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** Hooks other modules plug into (set in Wiring). */
object FiveHooks {
    /** Agent.resumeInfo(convo): null when nothing to resume, else {convoId, lastUser, interruptedAt}. */
    @Volatile var resumeInfo: ((Convo) -> JSONObject?)? = null

    const val RESUME_MSG = "[harness] The app was closed while you were working. Continue the task from where it stopped."
}

/**
 * The "today" brief behind the home screen card and the widget: next meetings, mail that needs a reply, unread
 * count, due tasks, next alarm. Network data (Exchange calendar, inbox page, unread count) is cached on disk and
 * refreshed in the background when older than 5 minutes; local data (triage flags, task board, alarm) is read on
 * every call, so [today] never waits on the network and never throws.
 */
object FiveBrief {
    private const val MAX_AGE = 5 * 60_000L
    private const val RETRY_GAP = 60_000L

    /** Network sources, as last fetched. */
    private class Src(val fetched: Long, val mailConnected: Boolean, val events: List<FiveLogic.Ev>, val eventsFrom: String, val mails: List<FiveLogic.MailLite>, val unread: Int) {
        fun toJson(): JSONObject = JSONObject().put("fetched", fetched).put("mailConnected", mailConnected).put("eventsFrom", eventsFrom).put("unread", unread)
            .put("events", JSONArray(events.map { it.toJson() })).put("mails", JSONArray(mails.map { it.toJson() }))
        companion object {
            fun from(o: JSONObject) = Src(o.optLong("fetched"), o.optBoolean("mailConnected"),
                o.optJSONArray("events")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> FiveLogic.Ev.from(j) } } } ?: emptyList(),
                o.optString("eventsFrom"),
                o.optJSONArray("mails")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> FiveLogic.MailLite.from(j) } } } ?: emptyList(),
                o.optInt("unread", 0))
        }
    }

    @Volatile private var src: Src? = null
    @Volatile private var loaded = false
    @Volatile private var lastTry = 0L
    private val refreshing = AtomicBoolean(false)

    private fun file() = File(Y.app.filesDir, "harness/brief.json")

    private fun source(): Src? {
        if (!loaded) synchronized(this) {
            if (!loaded) { src = try { file().takeIf { it.exists() }?.let { Src.from(JSONObject(it.readText())) } } catch (_: Throwable) { null }; loaded = true }
        }
        return src
    }

    /** The bridge method brief.today: cached data at once, a background refresh when it is stale. */
    fun today(): JSONObject {
        val s = source()
        if (s == null || System.currentTimeMillis() - s.fetched > MAX_AGE) refreshAsync()
        return snapshot()
    }

    /** The brief from what is known now, without starting a refresh (the widget uses this). */
    fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val s = source()
        return try {
            val evs = s?.events ?: phoneEvents(now)
            val mails = mailsNow(s)
            FiveLogic.assemble(
                s?.fetched?.takeIf { it > 0 } ?: now,
                // the state now, not the cached one: a refresh that ran before the mailbox connected (app start) saved
                // "false". Configured and no sign-in needed counts as connected (the app connects on first use).
                Mail.connected || (Cfg.mailConfigured && !Mail.needsSignIn),
                FiveLogic.pickEvents(evs, now, TimeZone.getDefault()),
                FiveLogic.pickNeedReply(mails, now),
                if (s != null && s.unread >= 0) s.unread else mails.count { !it.read },
                FiveLogic.pickTasks(tasks(), MxText.dayPlus(2)),
                nextAlarm(now)
            ) { id -> try { Refs.ref(id) } catch (_: Throwable) { id } }
        } catch (e: Throwable) {
            Diag.e("brief", "snapshot", e)
            FiveLogic.assemble(now, false, emptyList(), emptyList(), 0, emptyList(), null) { it }
        }
    }

    /** Inbox page with live triage flags: MailPlus's fresh in-memory page when it has one, else the cached page. */
    private fun mailsNow(s: Src?): List<FiveLogic.MailLite> {
        val live = MailPlus.inbox
        val base = if (live.isNotEmpty() && (s == null || MailPlus.lastSync >= s.fetched)) live.map { lite(it) } else s?.mails ?: emptyList()
        return base.map { m -> m.withReply(try { MailPlus.triage(m.id)?.r == true } catch (_: Throwable) { false }) }
    }

    private fun lite(h: MailHeader) = FiveLogic.MailLite(h.id, h.subject, MxText.who(h.from), Exchange.parseTime(h.received), h.read)

    private fun tasks(): List<FiveLogic.TaskLite> = try {
        TaskBoard.ordered().map { FiveLogic.TaskLite(it.id, it.title, it.due, it.status == "done") }
    } catch (e: Throwable) { emptyList() }

    private fun nextAlarm(now: Long): String? = try {
        val at = Y.app.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: 0L
        if (at > now) FiveLogic.alarmLabel(now, at, TimeZone.getDefault(), Y.tr) else null
    } catch (_: Throwable) { null }

    /** Today and tomorrow from the phone's own calendars (only when the calendar permission is already granted). */
    private fun phoneEvents(now: Long): List<FiveLogic.Ev> {
        if (Y.app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return try {
            val from = FiveLogic.dayStart(now, TimeZone.getDefault())
            Phone.calList(from, from + 2 * 86400000L + 3600000L, "").map { o ->
                FiveLogic.Ev("phone:" + o.optLong("id"), o.optString("title"), o.optLong("start"), o.optLong("end"), o.optString("location"), o.optBoolean("allDay"))
            }
        } catch (e: Throwable) { Diag.i("brief", "phone calendar: ${e.message}"); emptyList() }
    }

    fun refreshIfStale() { val s = source(); if (s == null || System.currentTimeMillis() - s.fetched > MAX_AGE) refreshAsync() }

    fun refreshAsync(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastTry < RETRY_GAP) return
        if (!refreshing.compareAndSet(false, true)) return
        lastTry = System.currentTimeMillis()
        Y.bg { try { refreshNow() } catch (e: Throwable) { Diag.e("brief", "refresh", e) } finally { refreshing.set(false) } }
    }

    /** Fetches the network sources (worker thread), saves them, tells the screen ("brief" event) and the widgets. */
    private fun refreshNow() {
        val now = System.currentTimeMillis()
        val old = source()
        var events: List<FiveLogic.Ev>? = null
        var from = "phone"
        var connected = false
        var mails: List<FiveLogic.MailLite>? = null
        var unread = -1
        // no sign-in prompts from here: when the mailbox needs a sign-in, the mail screen / mail check asks for it
        if (Cfg.mailConfigured && !Mail.needsSignIn) {
            try {
                val start = FiveLogic.dayStart(now, TimeZone.getDefault())
                events = Mail.use { ex -> ex.events(start, start + 2 * 86400000L + 3600000L) }
                    .map { e -> FiveLogic.Ev(e.id, e.subject, Exchange.parseTime(e.start), Exchange.parseTime(e.end), e.location, e.allDay, e.cancelled) }
                from = "exchange"; connected = true
            } catch (e: Throwable) { Diag.i("brief", "calendar: ${e.message}") }
            if (connected) {
                try {
                    val page = if (MailPlus.inbox.isNotEmpty() && now - MailPlus.lastSync < MAX_AGE) MailPlus.inbox else Mail.use { it.find("inbox", "", false, 40, 0) }.items
                    mails = page.map { lite(it) }
                } catch (e: Throwable) { Diag.i("brief", "inbox: ${e.message}") }
                unread = try { MxEws.folderInfo("inbox", null).unread } catch (e: Throwable) { mails?.count { !it.read } ?: -1 }
            }
        }
        if (events == null) events = phoneEvents(now)
        val s = Src(now, connected, events, from, mails ?: (if (connected) old?.mails else null) ?: emptyList(), if (unread >= 0) unread else if (connected) (old?.unread ?: 0) else 0)
        src = s; loaded = true
        try { file().parentFile?.mkdirs(); file().writeText(s.toJson().toString()) } catch (e: Throwable) { Diag.e("brief", "save", e) }
        val out = snapshot()
        if (Bridge.host != null) Bridge.emit("brief", out)  // a closed screen reads brief.today when it opens
        try { FiveWidget.updateAll(Y.app) } catch (e: Throwable) { Diag.e("brief", "widget", e) }
    }
}
