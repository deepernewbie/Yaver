package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure logic behind yaver 5's phone features (today brief, home-screen widget, screen-aware assist, share intake).
 * No Android calls here, so it is tested on the JVM; FiveBrief / FiveWidget / FiveAssist feed it real data.
 */
object FiveLogic {
    class Ev(val id: String, val subject: String, val start: Long, val end: Long, val location: String, val allDay: Boolean, val cancelled: Boolean = false) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("subject", subject).put("start", start).put("end", end).put("location", location).put("allDay", allDay).put("cancelled", cancelled)
        companion object {
            fun from(o: JSONObject) = Ev(o.optString("id"), o.optString("subject"), o.optLong("start"), o.optLong("end"), o.optString("location"), o.optBoolean("allDay"), o.optBoolean("cancelled"))
        }
    }

    class MailLite(val id: String, val subject: String, val from: String, val received: Long, val read: Boolean, val needsReply: Boolean = false) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("subject", subject).put("from", from).put("received", received).put("read", read)
        fun withReply(r: Boolean) = MailLite(id, subject, from, received, read, r)
        companion object {
            fun from(o: JSONObject) = MailLite(o.optString("id"), o.optString("subject"), o.optString("from"), o.optLong("received"), o.optBoolean("read", true))
        }
    }

    class TaskLite(val id: String, val title: String, val due: String, val done: Boolean)

    // ------------------------------------------------------------------ brief

    private val ONLINE_LOC = Regex("(?i)teams|zoom|webex|skype|google meet|meet\\.google|gotomeeting|whereby|jitsi|https?://|çevrim ?içi|online|sanal")
    private val ONLINE_SUBJ = Regex("(?i)\\b(teams|zoom|webex)\\b")
    fun isOnline(location: String, subject: String = ""): Boolean = ONLINE_LOC.containsMatchIn(location) || ONLINE_SUBJ.containsMatchIn(subject)

    fun dayStart(ms: Long, tz: TimeZone): Long = Calendar.getInstance(tz).apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun plusDays(ms: Long, n: Int, tz: TimeZone): Long = Calendar.getInstance(tz).apply { timeInMillis = ms; add(Calendar.DAY_OF_MONTH, n) }.timeInMillis

    /** Today's remaining events (ongoing ones included) in time order, then tomorrow's first [tomorrowMax]. Cancelled events are left out. */
    fun pickEvents(evs: List<Ev>, now: Long, tz: TimeZone, tomorrowMax: Int = 2): List<Ev> {
        val today = dayStart(now, tz)
        val tomorrow = plusDays(today, 1, tz)
        val after = plusDays(today, 2, tz)
        val live = evs.filter { !it.cancelled }.distinctBy { it.id + "@" + it.start }.sortedWith(compareBy<Ev>({ it.start }, { if (it.allDay) 0 else 1 }))
        val rest = live.filter { it.start < tomorrow && it.end > now }
        val next = live.filter { it.start >= tomorrow && it.start < after }.take(tomorrowMax)
        return rest + next
    }

    /** Mails flagged "needs reply" by triage, newest first; older than [maxAgeDays] days are left out. */
    fun pickNeedReply(mails: List<MailLite>, now: Long, max: Int = 5, maxAgeDays: Int = 14): List<MailLite> =
        mails.filter { it.needsReply && (it.received <= 0 || it.received >= now - maxAgeDays * 86400000L) }
            .distinctBy { it.id }.sortedByDescending { it.received }.take(max)

    /** Open board tasks that are overdue or due by [soon] (yyyy-MM-dd), earliest first. */
    fun pickTasks(tasks: List<TaskLite>, soon: String, max: Int = 5): List<TaskLite> =
        tasks.filter { !it.done && it.due.isNotEmpty() && it.due.take(10) <= soon }.sortedWith(compareBy({ it.due }, { it.title })).take(max)

    /** The brief.today JSON (contract shared with the UI). [ref] turns real Exchange mail ids into handles. */
    fun assemble(generated: Long, mailConnected: Boolean, events: List<Ev>, needReply: List<MailLite>, unread: Int, tasks: List<TaskLite>, nextAlarm: String?, ref: (String) -> String): JSONObject =
        JSONObject().put("generated", generated).put("mailConnected", mailConnected)
            .put("events", JSONArray(events.map { e ->
                JSONObject().put("id", e.id).put("subject", e.subject).put("start", e.start).put("end", e.end).put("location", e.location).put("allDay", e.allDay).put("online", isOnline(e.location, e.subject))
            }))
            .put("needReply", JSONArray(needReply.map { m -> JSONObject().put("id", ref(m.id)).put("subject", m.subject).put("from", m.from).put("received", m.received) }))
            .put("unread", maxOf(0, unread))
            .put("tasks", JSONArray(tasks.map { t -> JSONObject().put("id", t.id).put("title", t.title).put("due", t.due) }))
            .put("nextAlarm", nextAlarm ?: JSONObject.NULL)

    private fun locale(tr: Boolean) = if (tr) Locale("tr", "TR") else Locale.ENGLISH

    private fun hhmm(ms: Long, tz: TimeZone, tr: Boolean) = SimpleDateFormat("HH:mm", locale(tr)).apply { timeZone = tz }.format(ms)

    /** "07:30" today, "Yarın 07:30" / "Tomorrow 07:30", else "Cum 07:30" / "Fri 07:30". */
    fun alarmLabel(now: Long, at: Long, tz: TimeZone, tr: Boolean): String {
        val today = dayStart(now, tz)
        val d = dayStart(at, tz)
        val t = hhmm(at, tz, tr)
        return when {
            d == today -> t
            d == plusDays(today, 1, tz) -> (if (tr) "Yarın " else "Tomorrow ") + t
            else -> SimpleDateFormat("EEE", locale(tr)).apply { timeZone = tz }.format(at) + " " + t
        }
    }

    class WidgetText(
        val meetWhen: String, val meetTitle: String, val hasMeeting: Boolean, val mailLine: String, val alarm: String,
        /** "14:00–15:00" / "Tüm gün"; "20 dk sonra" / "şimdi" / "yarın"; place; online meeting */
        val meetTime: String = "", val meetRel: String = "", val meetPlace: String = "", val online: Boolean = false,
        val unread: Int = 0, val reply: Int = 0, val connected: Boolean = false
    )

    /** What the home-screen widget shows, from a brief.today JSON. */
    fun widgetText(brief: JSONObject?, now: Long, tz: TimeZone, tr: Boolean): WidgetText {
        fun t(en: String, trs: String) = if (tr) trs else en
        val evs = brief?.optJSONArray("events") ?: JSONArray()
        val list = (0 until evs.length()).mapNotNull { evs.optJSONObject(it) }.filter { it.optLong("end") > now }
        val today = dayStart(now, tz); val tomorrow = plusDays(today, 1, tz)
        // the next timed meeting first; an all-day item only when nothing timed is left
        val e = list.firstOrNull { !it.optBoolean("allDay") } ?: list.firstOrNull()
        val meetWhen: String; val meetTitle: String
        if (e == null) {
            meetWhen = ""
            meetTitle = if (brief == null) t("Loading your day…", "Gününüz yükleniyor…") else t("No more meetings today", "Bugün başka toplantı yok")
        } else {
            val s = e.optLong("start"); val en = e.optLong("end")
            val day = if (dayStart(s, tz) >= tomorrow) t("Tomorrow ", "Yarın ") else ""
            meetWhen = when {
                e.optBoolean("allDay") -> day + t("All day", "Tüm gün")
                s <= now -> t("Now", "Şimdi") + " · " + t("until ", "bitiş ") + hhmm(en, tz, tr)
                else -> day + hhmm(s, tz, tr)
            } + if (e.optBoolean("online")) " · " + t("online", "çevrim içi") else ""
            meetTitle = e.optString("subject").ifEmpty { t("(no title)", "(başlıksız)") }
        }
        val connected = brief?.optBoolean("mailConnected") == true
        val unread = brief?.optInt("unread", 0) ?: 0
        val reply = brief?.optJSONArray("needReply")?.length() ?: 0
        val mailLine = when {
            brief == null -> ""
            !connected && unread == 0 && reply == 0 -> t("Mail not connected", "Posta bağlı değil")
            else -> t("$unread unread", "$unread okunmamış") + " · " + t("$reply need reply", "$reply yanıt bekliyor")
        }
        val alarm = brief?.optString("nextAlarm")?.takeIf { brief.has("nextAlarm") && !brief.isNull("nextAlarm") && it.isNotEmpty() } ?: ""
        var meetTime = ""; var meetRel = ""; var place = ""; var online = false
        if (e != null) {
            val st = e.optLong("start"); val en = e.optLong("end")
            meetTime = if (e.optBoolean("allDay")) t("All day", "Tüm gün") else hhmm(st, tz, tr) + "–" + hhmm(en, tz, tr)
            val mins = ((st - now) / 60000L).toInt()
            meetRel = when {
                st <= now -> t("now", "şimdi")
                dayStart(st, tz) >= tomorrow -> t("tomorrow", "yarın")
                mins < 60 -> t("in $mins min", "$mins dk sonra")
                else -> { val hh = mins / 60; val mm = mins % 60; if (mm == 0) t("in $hh h", "$hh sa sonra") else t("in $hh h $mm min", "$hh sa $mm dk sonra") }
            }
            online = e.optBoolean("online")
            place = e.optString("location").trim().ifEmpty { if (online) t("Online meeting", "Çevrim içi toplantı") else "" }
        }
        return WidgetText(meetWhen, meetTitle, e != null, mailLine, alarm, meetTime, meetRel, place, online, unread, reply, connected || unread > 0 || reply > 0)
    }

    /** When the widget should next redraw by itself: the end of the shown meeting (or its start), at most [maxMs] ahead. */
    fun nextWidgetTick(brief: JSONObject?, now: Long, maxMs: Long = 30 * 60000L): Long {
        var at = now + maxMs
        val evs = brief?.optJSONArray("events") ?: return at
        for (i in 0 until evs.length()) {
            val o = evs.optJSONObject(i) ?: continue
            val s = o.optLong("start"); val e = o.optLong("end")
            if (s > now + 60000) at = minOf(at, s + 60000) else if (e > now) at = minOf(at, e + 60000)
        }
        return maxOf(at, now + 60000)
    }

    // ------------------------------------------------------------------ intents

    /** Launch payload for an intent's yaver_action / yaver_payload extras that need more than {action, text}; null = the default handling. */
    fun launchFor(action: String?, payload: String?): JSONObject? = when (action) {
        "nav" -> JSONObject().put("action", "nav").put("page", when (payload?.trim()?.lowercase()) { "calendar", "cal" -> "calendar"; "tasks" -> "tasks"; "chat" -> "chat"; else -> "mail" })
        else -> null
    }

    /**
     * The composer text for a share: Chrome sends the page title as EXTRA_SUBJECT and the URL as EXTRA_TEXT; many apps
     * repeat the subject inside the text. Returns "Title\nhttps://…" without duplicates.
     */
    fun shareText(subject: String?, texts: List<String?>, html: String? = null): String {
        val body = texts.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
            .ifEmpty { html?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("[ \\t]+"), " ")?.trim() ?: "" }
        val s = subject?.trim().orEmpty()
        if (s.isEmpty() || body.contains(s) || s == body) return body
        if (body.isEmpty()) return s
        return "$s\n$body"
    }

    // ------------------------------------------------------------------ screen (AssistStructure) text

    /** A view of the assist tree, copied out of AssistStructure.ViewNode so the walk is testable. */
    class SNode(val text: String?, val desc: String?, val hidden: Boolean = false, val password: Boolean = false, val kids: List<SNode> = emptyList())

    /** InputType (class + variation) of a password field. */
    fun isPassword(inputType: Int): Boolean {
        val cls = inputType and 0x0f; val v = inputType and 0xff0
        return (cls == 1 && (v == 0x80 || v == 0x90 || v == 0xe0)) || (cls == 2 && v == 0x10)
    }

    private val WS = Regex("[ \\t\\u00a0]+")
    private val NL = Regex("\\s*\\n\\s*")

    /**
     * The visible text of a screen, in reading order: each node's text (or its content description when it has no
     * text), whitespace collapsed, hidden subtrees and password fields skipped, repeated short lines (tabs, buttons)
     * kept once, at most [cap] characters.
     */
    fun flattenScreen(roots: List<SNode>, cap: Int = 8000): String {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        var full = false
        fun add(raw: String?) {
            if (full || raw == null) return
            val line = raw.replace(WS, " ").replace(NL, "\n").trim()
            if (line.isEmpty()) return
            if (line.length < 80 && !seen.add(line)) return
            val room = cap - sb.length - (if (sb.isEmpty()) 0 else 1)
            if (room <= 1) { full = true; if (sb.isNotEmpty()) sb.setCharAt(sb.length - 1, '…'); return }
            if (sb.isNotEmpty()) sb.append('\n')
            if (line.length > room) { sb.append(line, 0, maxOf(0, room - 1)).append('…'); full = true } else sb.append(line)
        }
        fun walk(n: SNode, depth: Int) {
            if (full || n.hidden || depth > 80) return
            if (!n.password) {
                val t = n.text?.trim().orEmpty()
                if (t.isNotEmpty()) add(t) else add(n.desc)
            }
            for (k in n.kids) walk(k, depth + 1)
        }
        for (r in roots) walk(r, 0)
        return sb.toString()
    }

    /** The first user message with the screen attached as context (the model sees this; the chat shows the user's own words). */
    fun screenPrompt(app: String, pkg: String, text: String, hasImage: Boolean, user: String): String {
        val who = if (pkg.isNotEmpty() && pkg != app) "$app ($pkg)" else app
        val head = "[Screen context — the user opened yaver over the app \"$who\". " +
            (if (text.isNotBlank()) "Text visible on that screen:]\n<<<\n${text.trim()}\n>>>" else "No text could be read from that screen.]") +
            (if (hasImage) "\n[A screenshot of that screen is attached.]" else "")
        val q = user.trim().ifEmpty { "Help me with what is on this screen." }
        return "$head\n\n$q"
    }
}
