package com.plusai.yaver.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns that yaver starts by itself in a conversation (heartbeats, subagent reports, autonomous continuations), and
 * messages steered into a running turn. Port of Agent.steer / deliverPending / send({internal}) and the end-of-turn
 * chain in Agent.send's finally block.
 */
object Turns {
    const val STEER_PREFIX = "[Message from the user while you were working — take it into account and continue]"

    // ---- hooks (set by the integrator / other modules) ----
    /** Language note appended to injected texts (LANG.note(session.lang)); "" when unknown. */
    @Volatile var langNote: (String) -> String = { "" }
    /** Bridge's own listener for a run (approvals reach the screen). Null → a local copy without approvals. */
    @Volatile var listenerFor: ((String, Bridge.Run) -> AgentListener)? = null
    /** Goal continuation (planning engineer): returns true when it started a goal round for this conversation. */
    @Volatile var continueGoal: ((String) -> Boolean)? = null
    /** Notes shown in the chat (UI.note); default: a Bridge event. */
    @Volatile var note: (String, String) -> Unit = { id, text -> Bridge.emit("note", JSONObject().put("convoId", id).put("text", text)) }

    /** [user]: typed by the user (only those cut a streaming answer); heartbeats / reports / agents are not. */
    private class Item(val text: String, val display: String?, val user: Boolean, val id: String = H51.newId("s"))
    private val pending = ConcurrentHashMap<String, MutableList<Item>>()

    /** Steer state events for the UI: received | delivered (given to the model) | next (became the next turn) | withdrawn. */
    private fun steerEvent(convoId: String, id: String, state: String, reason: String? = null) {
        val o = JSONObject().put("convoId", convoId).put("steerId", id).put("state", state)
        if (reason != null) o.put("reason", reason)
        H51.emit("steer", o)
    }
    // runs started here (their end is handled by start itself); weak so finished runs are dropped
    private val own: MutableSet<Bridge.Run> = Collections.synchronizedSet(Collections.newSetFromMap(java.util.WeakHashMap()))

    fun isRunning(convoId: String) = Bridge.runs.containsKey(convoId)

    /** Queue a message for the running turn of [convoId]; delivered at the next safe point or right after the turn. */
    fun steer(convoId: String, text: String, display: String? = null, user: Boolean = true): String {
        val item = Item(text, display, user)
        pending.getOrPut(convoId) { Collections.synchronizedList(ArrayList()) }.add(item)
        steerEvent(convoId, item.id, "received")
        watch()
        // a streaming answer that already showed text is cut by the user's message; the shown text is kept (Agent loop)
        if (user) try { H51.steerArrived(convoId) } catch (e: Throwable) { Diag.e("turns", "steer cut", e) }
        return item.id
    }

    /** Takes back a steered message that the model has not received yet: its text, or null when already delivered. */
    fun withdraw(convoId: String, steerId: String): String? {
        val l = pending[convoId] ?: return null
        val item = synchronized(l) { l.firstOrNull { it.id == steerId }?.also { l.remove(it) } } ?: return null
        steerEvent(convoId, steerId, "withdrawn")
        return item.text
    }

    // ---- queue: messages that run as the next turns of a conversation (persistent FIFO) ----
    /**
     * Starts a queued message as a new turn, like chat.send would (convoId, text, display, attachments). Set by the
     * bridge; null = Bridge.dispatch("chat.send", {convoId, text, display, attachments}).
     */
    @Volatile var startNext: ((String, String, String?, List<String>) -> Unit)? = null
    private val qlock = Any()

    fun enqueue(convoId: String, text: String, display: String?, files: List<String>): String {
        val id = H51Queue.add(convoId, text, display, files)
        H51Queue.changed(convoId)
        watch()
        // nothing running any more (the run ended meanwhile): start it now (unless the user stopped this chat)
        if (!isRunning(convoId) && !Convos.isLive(convoId)) Y.bg { Thread.sleep(300); if (!isRunning(convoId) && !hasPending(convoId)) startQueued(convoId, auto = true) }
        return id
    }
    fun queued(convoId: String): JSONArray = H51Queue.json(convoId)
    fun unqueue(convoId: String, id: String): Boolean = H51Queue.remove(convoId, id).also { if (it) H51Queue.changed(convoId) }

    /**
     * The next queued message of [convoId] as a new turn, when nothing runs there. [auto] (end of a run, monitor):
     * only messages queued in this process, and not after the user pressed Stop in this chat. Manual (the UI's
     * "start queued" → chat.queue.start): any message, and it lifts the stop hold. The item is removed only once the
     * model is set up and the chat exists, and put back when the start fails.
     */
    fun startQueued(convoId: String, auto: Boolean = false): Boolean = synchronized(qlock) {
        if (isRunning(convoId) || Convos.isLive(convoId)) return false
        val head = H51Queue.items(convoId).firstOrNull() ?: return false
        if (auto && (H51Queue.isHeld(convoId) || head.at < H51Queue.processStart)) return false
        if (!Cfg.llmReady || Convos.load(convoId) == null) { Diag.i("turns", "$convoId: queued message waits (no model, or the chat is gone)"); return false }
        if (!auto) H51Queue.release(convoId)
        val it = H51Queue.pop(convoId) ?: return false
        Diag.i("turns", "$convoId: queued message ${it.id} starts as the next turn")
        try {
            val f = startNext
            if (f != null) f(convoId, it.text, it.display, it.files)
            else Bridge.dispatch("chat.send", JSONObject().put("convoId", convoId).put("text", it.text).put("display", it.display ?: "").put("attachments", JSONArray(it.files)))
        } catch (e: Throwable) {
            Diag.e("turns", "queued message could not start", e)
            H51Queue.pushFront(convoId, it)
            H51Queue.changed(convoId)
            note(convoId, Y.t("Queued message not sent: ", "Sıradaki mesaj gönderilemedi: ") + (e.message ?: e.toString()))
            return false
        }
        H51Queue.changed(convoId)
        true
    }

    /** A user steer is waiting (only those cut a streaming answer). */
    fun hasUserPending(convoId: String) = pending[convoId]?.let { l -> synchronized(l) { l.any { it.user } } } == true

    fun hasPending(convoId: String) = pending[convoId]?.isNotEmpty() == true

    /**
     * Agent loop hook: call between steps (after tool results are stored, before the next model call).
     * Returns the user message to append, or null.
     */
    fun takeSteer(convoId: String, note: ((List<String>) -> String)? = null): JSONObject? {
        val l = pending[convoId] ?: return null
        val items = synchronized(l) { val c = ArrayList(l); l.clear(); c }
        if (items.isEmpty()) return null
        for (it in items) steerEvent(convoId, it.id, "delivered")
        val ln = note?.invoke(items.map { it.text }) ?: langNote(convoId)
        val content = STEER_PREFIX + "\n" + items.joinToString("\n\n") { it.text } + ln.trim().let { if (it.isNotBlank()) "\n\n$it" else "" }
        return JSONObject().put("role", "user").put("content", content).put("display", items.joinToString("\n\n") { it.display ?: it.text })
            .put("steer", true).put("ts", System.currentTimeMillis())
    }

    /** Children.deliver: steer into running work, or start a new turn. */
    fun deliver(convoId: String, text: String, label: String?) {
        if (isRunning(convoId)) steer(convoId, text, label, user = false) else if (!start(convoId, text, label, "agent")) Diag.i("turns", "could not deliver to $convoId: " + clip(text, 200))
    }

    private fun listener(id: String, run: Bridge.Run) = listenerFor?.invoke(id, run) ?: object : AgentListener {
        override fun onDelta(text: String) { run.text.append(text); Bridge.emit("run", JSONObject().put("convoId", id).put("phase", "delta").put("text", text)) }
        override fun onStep() { run.text.setLength(0); Bridge.emit("run", JSONObject().put("convoId", id).put("phase", "step")) }
        override fun onToolStart(name: String, args: JSONObject) { run.status = name; Bridge.emit("run", JSONObject().put("convoId", id).put("phase", "tool").put("name", name).put("args", args)) }
        override fun onToolEnd(name: String, result: String, ok: Boolean) { run.status = ""; Bridge.emit("run", JSONObject().put("convoId", id).put("phase", "toolEnd").put("name", name).put("ok", ok)) }
        override fun onProgress(text: String) { run.status = text; Bridge.emit("run", JSONObject().put("convoId", id).put("phase", "progress").put("text", text)) }
    }

    /**
     * Agent.send(text, [], {internal, label}) for a conversation, without the screen. [label] marks the user message
     * as an internal turn (heartbeat, subagent report, autonomous continuation); null = an ordinary user message.
     * Returns false when the conversation is gone or no model is set up.
     */
    fun start(convoId: String, text: String, label: String?, internal: String?): Boolean {
        if (!Cfg.llmReady) return false
        val c = Convos.load(convoId) ?: return false
        val run = Bridge.Run(Cancel())
        if (Bridge.runs.putIfAbsent(c.id, run) != null) { steer(c.id, text, label, user = internal == null && label == null); return true }
        own.add(run)
        watch()
        Bridge.emit("run", JSONObject().put("convoId", c.id).put("phase", "start").put("internal", internal ?: JSONObject.NULL).put("label", label ?: JSONObject.NULL).put("text", label ?: text))
        Y.bg {
            val h = Bridge.host
            val wl = if (h == null || !h.resumedNow) Y.app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaver:turn") else null
            var answer = ""
            var err: String? = null
            var aborted = false
            try {
                wl?.acquire(15 * 60 * 1000L)
                answer = Agent.run(c, text, listener = listener(c.id, run), cancel = run.cancel, label = label, internal = internal)
            } catch (e: CancelledException) { aborted = true }
            catch (e: Throwable) { err = e.message ?: e.toString() }
            finally { try { if (wl?.isHeld == true) wl.release() } catch (_: Throwable) {} }
            Bridge.runs.remove(c.id, run)
            Bridge.emit("run", JSONObject().put("convoId", c.id).put("phase", "done").put("error", err ?: JSONObject.NULL))
            val host = Bridge.host
            if ((host == null || !host.resumedNow) && Prefs.on("notifyReplies", true) && (answer.isNotBlank() || err != null))
                Notifier.show(Y.app, (label ?: c.title).take(80), (err ?: answer).replace(Regex("[*#`>]"), "").trim().take(900), "open_convo", c.id)
            afterTurn(c.id, aborted || run.cancel.cancelled)
        }
        return true
    }

    /** End of a turn (Agent.send finally): queued messages first, then the goal, then autonomous mode. */
    fun afterTurn(convoId: String, aborted: Boolean) {
        Y.bg {
            if (flushPending(convoId, aborted)) return@bg
            // queued messages run next; after a Stop they wait for the user ("start queued" in the UI)
            if (aborted) { H51Queue.hold(convoId); return@bg }
            if (startQueued(convoId, auto = true)) return@bg
            Thread.sleep(600)
            if (isRunning(convoId)) return@bg
            try { if (continueGoal?.invoke(convoId) == true) return@bg } catch (e: Throwable) { Diag.e("turns", "goal", e) }
            try { Autonomous.next(convoId) } catch (e: Throwable) { note(convoId, "Autonomous: " + (e.message ?: e.toString())) }
        }
    }

    /** Messages still queued when a turn ended: sent as the next turn (or dropped with a note when it was stopped). */
    private fun flushPending(convoId: String, aborted: Boolean): Boolean {
        val l = pending[convoId] ?: return false
        val rest = synchronized(l) { val c = ArrayList(l); l.clear(); c }
        if (rest.isEmpty()) return false
        if (aborted) { for (it in rest) steerEvent(convoId, it.id, "withdrawn", "stopped"); note(convoId, Y.t("Not sent — the task was stopped: ", "Gönderilmedi — görev durduruldu: ") + rest.joinToString(" · ") { clip(it.display ?: it.text, 80) }); return false }
        for (it in rest) steerEvent(convoId, it.id, "next")
        // queued user messages become the next ordinary turn; injected ones (heartbeats, reports) stay internal
        val user = rest.all { it.user }
        val label = if (!user && rest.all { it.display != null }) rest.joinToString(" · ") { it.display!! } else null
        if (!start(convoId, rest.joinToString("\n\n") { it.text }, label, if (user) null else "agent")) Diag.i("turns", "dropped ${rest.size} queued message(s) for $convoId (no model or conversation gone)")
        return true
    }

    // A small monitor notices when turns started elsewhere (Bridge.send) end, so the end-of-turn chain runs for them too.
    @Volatile private var monitor: Thread? = null
    @Synchronized fun watch() {
        if (monitor?.isAlive == true) return
        monitor = Thread({
            var seen = HashMap<String, Bridge.Run>()
            while (true) {
                try {
                    Thread.sleep(700)
                    val now = HashMap(Bridge.runs)
                    for ((id, r) in seen) if (now[id] !== r && r !in own) afterTurn(id, r.cancel.cancelled)
                    seen = now
                    // queued messages for conversations that are not running (the turn ended before we noticed)
                    for ((id, l) in pending) if (l.isNotEmpty() && !Bridge.runs.containsKey(id)) Y.bg { if (!isRunning(id) && !flushPending(id, false)) startQueued(id, auto = true) }
                    // queued messages of conversations where nothing runs (the run ended before we noticed, or after a restart)
                    for (id in H51Queue.ids()) if (!Bridge.runs.containsKey(id) && !Convos.isLive(id) && !hasPending(id)) Y.bg { if (!isRunning(id)) startQueued(id, auto = true) }
                } catch (_: InterruptedException) { break } catch (e: Throwable) { Diag.e("turns", "monitor", e) }
            }
        }, "yaver-turns").apply { isDaemon = true; start() }
    }
}

/* ---------- Heartbeats: recurring instructions re-entering a conversation, also while yaver is in the background ---------- */
object Heartbeats {
    const val ACTION = "com.plusai.yaver.HEARTBEAT"
    private const val REQ = 0x4842
    private val lock = Any()
    private val file: File get() = File(Y.app.filesDir, "harness/heartbeats.json").apply { parentFile?.mkdirs() }
    @Volatile private var cache: JSONObject? = null
    @Volatile private var ticker: ScheduledExecutorService? = null

    fun parseEvery(v: String?): Long? {
        val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*(s|sec|m|min|h|hr|d)?$", RegexOption.IGNORE_CASE).find((v ?: "").trim()) ?: return null
        val n = m.groupValues[1].toDouble()
        val u = (m.groupValues[2].ifEmpty { "m" }).lowercase()[0]
        val mult = when (u) { 's' -> 1000.0; 'm' -> 60000.0; 'h' -> 3600000.0; else -> 86400000.0 }
        return maxOf(15000L, (n * mult).toLong())
    }

    fun fmtEvery(ms: Long): String = when {
        ms % 86400000L == 0L -> "${ms / 86400000L}d"
        ms % 3600000L == 0L -> "${ms / 3600000L}h"
        ms % 60000L == 0L -> "${ms / 60000L}m"
        else -> "${Math.round(ms / 1000.0)}s"
    }

    private fun load(): JSONObject = synchronized(lock) {
        cache ?: (try { JSONObject(file.readText()) } catch (_: Throwable) { JSONObject() }).also { cache = it }
    }
    private fun save() = synchronized(lock) { cache?.let { try { file.writeText(it.toString()) } catch (e: Throwable) { Diag.e("heartbeat", "save", e) } } }

    private fun arr(convoId: String): JSONArray = synchronized(lock) { load().optJSONArray(convoId) ?: JSONArray().also { load().put(convoId, it) } }
    private fun items(convoId: String): List<JSONObject> = synchronized(lock) { val a = load().optJSONArray(convoId) ?: return emptyList(); (0 until a.length()).map { a.getJSONObject(it) } }
    private fun setItems(convoId: String, l: List<JSONObject>) = synchronized(lock) { if (l.isEmpty()) load().remove(convoId) else load().put(convoId, JSONArray(l)); save() }

    fun list(convoId: String): List<JSONObject> = items(convoId)
    fun any(): Boolean = synchronized(lock) { load().length() > 0 }

    fun add(convoId: String, prompt: String, every: String, label: String?, owner: String = "user", delivery: String? = "steer"): JSONObject {
        val ms = parseEvery(every) ?: throw IllegalArgumentException("interval \"$every\" is not valid; use forms like 30s, 5m, 2h, 1d")
        val h: JSONObject
        synchronized(lock) {
            var l = items(convoId).toMutableList()
            if (owner == "user") l = l.filter { it.optString("owner") != "user" }.toMutableList() // one visible user heartbeat per conversation
            if (owner == "agent" && l.count { it.optString("owner") == "agent" } >= 8) throw IllegalArgumentException("at most 8 agent heartbeats per conversation")
            h = JSONObject().put("id", "hb_" + System.currentTimeMillis().toString(36)).put("label", (label?.ifBlank { null } ?: prompt).take(40))
                .put("prompt", prompt.take(2000)).put("every", ms).put("next", System.currentTimeMillis() + ms).put("status", "active")
                .put("owner", owner).put("delivery", if (delivery == "follow_up") "follow_up" else "steer").put("fired", 0)
            l.add(h)
            setItems(convoId, l)
        }
        start(Y.app)
        return h
    }

    fun describe(h: JSONObject): String =
        "${h.optString("id")} [${h.optString("owner")}] every ${fmtEvery(h.optLong("every"))} · ${h.optString("status")} · ${h.optString("delivery")}" +
            (if (h.optInt("fired") > 0) " · fired ${h.optInt("fired")}×" else "") + " · \"${clip(h.optString("prompt"), 80)}\""

    fun text(h: JSONObject) = "[heartbeat: ${h.optString("label")}] ${h.optString("prompt")}"

    /** Forget the heartbeats of a deleted conversation. */
    fun clearConvo(convoId: String) { setItems(convoId, emptyList()); arm(Y.app) }

    /** Due heartbeats fire: steer into running work, or start a turn. One per conversation per tick; missed ticks coalesce. */
    fun tick() {
        val now = System.currentTimeMillis()
        val fire = ArrayList<Pair<String, JSONObject>>()
        synchronized(lock) {
            val root = load()
            var changed = false
            for (cid in root.keys().asSequence().toList()) {
                val a = root.optJSONArray(cid) ?: continue
                if (!File(Y.app.filesDir, "convos/$cid.json").exists()) { root.remove(cid); changed = true; continue }
                val busy = Turns.isRunning(cid)
                for (k in 0 until a.length()) {
                    val h = a.getJSONObject(k)
                    if (h.optString("status") != "active" || h.optLong("next") > now) continue
                    if (busy && h.optString("delivery") == "follow_up") continue // waits until the current work finishes
                    h.put("next", now + h.optLong("every")).put("fired", h.optInt("fired") + 1)
                    changed = true
                    fire.add(cid to JSONObject(h.toString()))
                    break
                }
            }
            if (changed) save()
        }
        for ((cid, h) in fire) {
            val text = text(h) + Turns.langNote(cid).let { if (it.isNotBlank()) "\n\n$it" else "" }
            if (Turns.isRunning(cid)) Turns.steer(cid, text, "💓 heartbeat · ${h.optString("label")}", user = false)
            else Turns.start(cid, text, "Heartbeat · ${h.optString("label")} (every ${fmtEvery(h.optLong("every"))})", "heartbeat")
        }
    }

    /** Starts the in-process ticker and arms the wake-up alarm. Call at app start and from the always-on service. */
    @Synchronized fun start(c: Context) {
        Turns.watch()
        if (ticker == null) {
            val t = Executors.newSingleThreadScheduledExecutor()
            ticker = t
            t.scheduleWithFixedDelay({ try { if (any()) { tick(); arm(Y.app) } } catch (e: Throwable) { Diag.e("heartbeat", "tick", e) } }, 5, 5, TimeUnit.SECONDS)
        }
        arm(c)
    }

    /** The wake-up alarm fired (ScheduleReceiver hook): run due heartbeats and arm the next one. */
    fun onAlarm(c: Context) {
        Y.init(c)
        Y.bg { try { start(c); tick(); arm(c) } catch (e: Throwable) { Diag.e("heartbeat", "alarm", e) } }
    }

    private fun pi(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REQ, Intent(c, ScheduleReceiver::class.java).setAction(ACTION).putExtra("id", 0),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** AlarmManager wakes yaver for the earliest active heartbeat (Android may batch it when the phone is idle). */
    fun arm(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        var next = Long.MAX_VALUE
        synchronized(lock) {
            val root = load()
            for (cid in root.keys()) { val a = root.optJSONArray(cid) ?: continue; for (k in 0 until a.length()) { val h = a.getJSONObject(k); if (h.optString("status") == "active") next = minOf(next, h.optLong("next")) } }
        }
        val p = pi(c)
        if (next == Long.MAX_VALUE) { am.cancel(p); return }
        val at = maxOf(next, System.currentTimeMillis() + 5000)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    /** /heartbeat every 10m <instruction> [--follow-up] | status | pause | resume | clear — returns the note to show. */
    fun command(convoId: String, rest0: String): String {
        val rest = rest0.trim()
        val user = list(convoId).firstOrNull { it.optString("owner") == "user" }
        if (rest.isEmpty() || rest == "status") return "💓 " + (user?.let { describe(it) } ?: Y.t("No heartbeat. Use /heartbeat every 10m <instruction>", "Heartbeat yok. /heartbeat every 10m <talimat> kullanın"))
        if (rest in listOf("pause", "resume", "clear")) {
            if (user == null) return Y.t("No heartbeat set", "Heartbeat ayarlı değil")
            val l = list(convoId).toMutableList()
            val h = l.first { it.optString("id") == user.optString("id") }
            if (rest == "clear") l.remove(h) else { h.put("status", if (rest == "pause") "paused" else "active"); if (rest == "resume") h.put("next", System.currentTimeMillis() + h.optLong("every")) }
            setItems(convoId, l); arm(Y.app)
            return "Heartbeat " + if (rest == "clear") "cleared" else rest + "d"
        }
        val ev = Regex("^every\\s+(\\S+)\\s+([\\s\\S]+)$", RegexOption.IGNORE_CASE).find(rest) ?: return Y.t("Use /heartbeat every 10m <instruction>", "Kullanım: /heartbeat every 10m <talimat>")
        return try {
            val follow = Regex("\\s--follow-up\\b").containsMatchIn(" " + ev.groupValues[2])
            val h = add(convoId, ev.groupValues[2].replace(Regex("\\s*--follow-up\\b"), "").trim(), ev.groupValues[1], null, "user", if (follow) "follow_up" else "steer")
            "💓 Heartbeat set: " + describe(h) + Y.t(" (keeps running while yaver is in the background)", " (yaver arka plandayken de çalışır)")
        } catch (e: Throwable) { e.message ?: e.toString() }
    }

    /** /heartbeats — all heartbeats of the conversation. */
    fun listNote(convoId: String) = "💓 " + (list(convoId).joinToString(" · ") { describe(it) }.ifEmpty { "No heartbeats." })

    fun register() {
        Tools.add(Tool(
            "heartbeat",
            "Recurring instructions for this conversation: yaver re-enters the conversation with the prompt every interval, also while the app is in the background (for example: check a page for changes every 10m; when the phone is idle Android may delay a run by a few minutes). Actions: create (prompt, interval like 30s/5m/2h, label, delivery steer|follow_up), list, pause, resume, delete. You manage only your own heartbeats; the user's /heartbeat is theirs.",
            P.props(
                "action" to P.s("create | list | pause | resume | delete", "create", "list", "pause", "resume", "delete"),
                "prompt" to P.s("For create: the instruction to repeat"), "interval" to P.s("For create: 30s, 5m, 2h, 1d"), "label" to P.s("Short label"),
                "delivery" to P.s("steer (join running work) or follow_up (wait until it finishes)", "steer", "follow_up"), "id" to P.s("Heartbeat id for pause/resume/delete")
            ), listOf("action"), subagentOk = false
        ) { a, ctx ->
            val cid = ctx.convo.id
            when (a.str("action")) {
                "create" -> {
                    if (a.str("prompt").isEmpty() || a.str("interval").isEmpty()) throw IllegalArgumentException("prompt and interval are required")
                    val h = add(cid, a.str("prompt"), a.str("interval"), a.str("label"), "agent", a.str("delivery"))
                    "Created " + describe(h) + ". It keeps running while yaver is in the background."
                }
                "list" -> list(cid).joinToString("\n") { describe(it) }.ifEmpty { "No heartbeats." }
                else -> {
                    val l = list(cid).toMutableList()
                    val h = l.firstOrNull { it.optString("id") == a.str("id") } ?: throw IllegalArgumentException("no heartbeat ${a.str("id")}")
                    if (h.optString("owner") != "agent") throw IllegalArgumentException("that is the user's heartbeat; only the user can change it")
                    if (a.str("action") == "delete") l.remove(h) else h.put("status", if (a.str("action") == "pause") "paused" else "active")
                    setItems(cid, l); arm(Y.app)
                    if (a.str("action") == "delete") "Deleted." else describe(h)
                }
            }
        })
    }
}

/* ---------- Autonomous mode: bounded continuations with quality gates ---------- */
object Autonomous {
    const val CONTINUATION_PROMPT = "No human input is available in autonomous mode. Continue working until the host evaluator, verifier, or configured autonomous limits stop the run. If you were asking the user a question, make a reasonable assumption and verify it. If you believe you are blocked, prove it with host-observable evidence, preserve that evidence, and keep looking for safe progress while budget remains. Do not end the session yourself; the verifier/evaluator decides completion when configured gates pass."
    /** What clarify returns while autonomous mode is on (the clarify tool calls [clarifyOverride]). */
    const val CLARIFY_REPLY = "No human input is available in autonomous mode. Make a reasonable assumption, state it in your work, verify it where you can, and continue."
    private val DEFAULTS = mapOf("maxContinuations" to 3.0, "maxTurns" to 12.0, "maxTokens" to 80000.0, "minutes" to 30.0)
    private const val DEFAULT_GATE_RETRIES = 3.0
    private val FLAG = Regex("(?:^|\\s)--([\\w-]+)(?:[= ](\"[^\"]*\"|'[^']*'|[^\\s-]\\S*))?")

    // token accounting (STATS.llmTokensIn + llmTokensOut): call addTokens after every Llm.chat, or replace tokensUsed
    private val tokens = AtomicLong(0)
    fun addTokens(tin: Int, tout: Int) { tokens.addAndGet((tin + tout).toLong()) }
    @Volatile var tokensUsed: () -> Long = { tokens.get() }

    private val lock = Any()
    private val file: File get() = File(Y.app.filesDir, "harness/autonomous.json").apply { parentFile?.mkdirs() }
    private fun all(): JSONObject = try { JSONObject(file.readText()) } catch (_: Throwable) { JSONObject() }
    fun state(convoId: String): JSONObject? = synchronized(lock) { all().optJSONObject(convoId) }
    private fun put(convoId: String, a: JSONObject?) = synchronized(lock) { val j = all(); if (a == null) j.remove(convoId) else j.put(convoId, a); file.writeText(j.toString()) }
    fun isOn(convoId: String) = state(convoId)?.optBoolean("enabled") == true
    fun anyEnabled(): Boolean = synchronized(lock) { val j = all(); j.keys().asSequence().any { j.optJSONObject(it)?.optBoolean("enabled") == true } }
    fun clarifyOverride(convoId: String): String? = if (isOn(convoId)) CLARIFY_REPLY else null

    private fun num(v: String?): Double = if (Regex("^unlimited$", RegexOption.IGNORE_CASE).matches(v ?: "")) Double.POSITIVE_INFINITY
        else (v ?: "").replace(Regex("[,_]"), "").toDoubleOrNull() ?: Double.NaN

    class Parsed(val limits: Map<String, Double>, val gates: List<String>, val gateRetries: Double)

    fun parse(rest: String): Parsed {
        val lim = HashMap<String, Double>(); val gates = ArrayList<String>(); var gr = Double.NaN; var named = false
        for (m in FLAG.findAll(rest)) {
            val k = m.groupValues[1].removePrefix("autonomous-")
            val v = m.groups[2]?.value?.replace(Regex("^[\"']|[\"']$"), "")
            when (k) {
                "gate" -> if (!v.isNullOrBlank()) gates.add(v)
                "gate-retries" -> gr = num(v)
                "max-continuations" -> { lim["maxContinuations"] = num(v); named = true }
                "max-turns" -> { lim["maxTurns"] = num(v); named = true }
                "max-tokens" -> { lim["maxTokens"] = num(v); named = true }
                "minutes" -> { lim["minutes"] = num(v); named = true }
                "timeout-ms" -> { lim["minutes"] = num(v) / 60000; named = true }
            }
        }
        // named budget flags define the whole budget: the ones not named become unlimited
        val L = HashMap<String, Double>()
        for (k in DEFAULTS.keys) L[k] = if (named) (lim[k] ?: Double.POSITIVE_INFINITY) else DEFAULTS.getValue(k)
        for (k in L.keys.toList()) if (!(L.getValue(k) > 0)) L[k] = DEFAULTS.getValue(k)
        return Parsed(L, gates, if (gr >= 0) gr else DEFAULT_GATE_RETRIES)
    }

    private fun ser(d: Double): Any = if (d.isInfinite()) "unlimited" else if (d == Math.floor(d)) d.toLong() else d
    private fun lim(v: Any?): Double = if (v == null || v == JSONObject.NULL || v == "unlimited") Double.POSITIVE_INFINITY else v.toString().toDoubleOrNull() ?: Double.POSITIVE_INFINITY
    private fun limStr(v: Any?): String = if (lim(v).isInfinite()) "∞" else String.format("%,d", lim(v).toLong())

    private fun assistantTurns(convoId: String): Int {
        val c = Convos.load(convoId) ?: return 0
        return (0 until c.messages.length()).count { c.messages.getJSONObject(it).optString("role") == "assistant" }
    }

    fun enable(convoId: String, rest: String): JSONObject {
        val p = parse(rest)
        val limits = JSONObject(); for ((k, v) in p.limits) limits.put(k, ser(v))
        val a = JSONObject().put("enabled", true).put("limits", limits).put("gates", JSONArray(p.gates)).put("gateRetries", ser(p.gateRetries))
            .put("used", JSONObject().put("continuations", 0).put("turnsAt", assistantTurns(convoId)).put("tokensAt", tokensUsed()).put("started", System.currentTimeMillis()).put("gateRetries", 0))
            .put("lastGate", JSONObject.NULL)
        put(convoId, a)
        Turns.watch()
        return a
    }

    fun off(convoId: String) { val a = state(convoId) ?: return; a.put("enabled", false).put("stopReason", "turned off"); put(convoId, a) }

    fun status(convoId: String): String {
        val a = state(convoId) ?: return "Autonomous mode is off."
        val u = a.getJSONObject("used"); val L = a.getJSONObject("limits")
        val turns = assistantTurns(convoId) - u.optInt("turnsAt"); val tk = tokensUsed() - u.optLong("tokensAt"); val mins = (System.currentTimeMillis() - u.optLong("started")) / 60000.0
        fun f(x: Double, l: Any?) = String.format("%,d", Math.round(x)) + "/" + limStr(l)
        val gates = a.optJSONArray("gates") ?: JSONArray()
        return "Autonomous mode ${if (a.optBoolean("enabled")) "ON" else "off"}" + (a.optString("stopReason").takeIf { it.isNotEmpty() }?.let { " (stopped: $it)" } ?: "") +
            " · continuations ${f(u.optInt("continuations").toDouble(), L.opt("maxContinuations"))} · turns ${f(turns.toDouble(), L.opt("maxTurns"))} · tokens ${f(tk.toDouble(), L.opt("maxTokens"))} · minutes ${f(mins, L.opt("minutes"))}" +
            if (gates.length() > 0) " · gates: ${(0 until gates.length()).joinToString(", ") { "`" + gates.getString(it) + "`" }} (retries ${u.optInt("gateRetries")}/${limStr(a.opt("gateRetries"))})" else " · no gates"
    }

    private fun limitReason(convoId: String, a: JSONObject): String? {
        val u = a.getJSONObject("used"); val L = a.getJSONObject("limits")
        if (u.optInt("continuations") >= lim(L.opt("maxContinuations"))) return "continuation limit"
        if (assistantTurns(convoId) - u.optInt("turnsAt") >= lim(L.opt("maxTurns"))) return "turn limit"
        if (tokensUsed() - u.optLong("tokensAt") >= lim(L.opt("maxTokens"))) return "token limit"
        if ((System.currentTimeMillis() - u.optLong("started")) / 60000.0 >= lim(L.opt("minutes"))) return "time limit"
        return null
    }

    /** Workspace change stamp (VFS.changeCount): file count + newest modification time. */
    private fun stamp(): String { var n = 0; var t = 0L; Workspace.root.walkTopDown().forEach { if (it.isFile) { n++; t = maxOf(t, it.lastModified()) } }; return "$n:$t" }

    /**
     * A gate passes or fails. Android has no shell, so gates run in run_code's engine:
     * "js: <expression>" passes when the expression is truthy; "py: <expression>" likewise in Python;
     * anything else is Python code that passes when it runs without an error (use assert). Workspace files the
     * gate mentions are made available by name.
     */
    fun runGate(cmd: String, cancel: Cancel = Cancel()): Pair<Boolean, String> {
        val files = Regex("[\\w./-]+\\.[A-Za-z0-9]{1,6}").findAll(cmd).map { it.value }.distinct()
            .filter { try { Workspace.resolve(it).isFile } catch (_: Throwable) { false } }.toList()
        return try {
            when {
                cmd.startsWith("js:", true) -> {
                    val out = CodeRunner.run("javascript", "const __r = (${cmd.substring(3).trim()});\nconsole.log((__r ? 'GATE_PASS ' : 'GATE_FAIL ') + (typeof __r === 'string' ? __r : JSON.stringify(__r)));", files, 15, cancel)
                    Pair(out.contains("GATE_PASS"), out)
                }
                cmd.startsWith("py:", true) -> {
                    val out = CodeRunner.run("python", "__r = (${cmd.substring(3).trim()})\nprint(('GATE_PASS ' if __r else 'GATE_FAIL ') + repr(__r))", files, 60, cancel)
                    Pair(out.contains("GATE_PASS"), out)
                }
                else -> { val out = CodeRunner.run("python", cmd, files, 120, cancel); Pair(true, "$out\n[exit 0]") }
            }
        } catch (e: CancelledException) { throw e } catch (e: Throwable) { Pair(false, (e.message ?: e.toString()) + "\n[exit 1]") }
    }

    private fun stop(convoId: String, a: JSONObject, reason: String, note: String) {
        a.put("enabled", false).put("stopReason", reason); put(convoId, a); Turns.note(convoId, note)
    }

    /** Called when a turn ends (Turns.afterTurn): decide whether to inject another continuation. Blocking; run off the main thread. */
    fun next(convoId: String): Boolean {
        val a = state(convoId) ?: return false
        if (!a.optBoolean("enabled")) return false
        val u = a.getJSONObject("used")
        val gates = a.optJSONArray("gates") ?: JSONArray()
        val gl = (0 until gates.length()).map { gates.getString(it) }
        if (gl.isNotEmpty()) {
            val st = stamp()
            val last = a.optJSONObject("lastGate")
            if (last != null && !last.optBoolean("pass") && last.optString("stamp") == st && u.optInt("continuations") > 0) { /* nothing changed since the failed gate: do not rerun it */ }
            else {
                var failed: Pair<String, String>? = null
                for (g in gl) { val r = runGate(g); if (!r.first) { failed = g to r.second; break } }
                a.put("lastGate", JSONObject().put("pass", failed == null).put("stamp", st).put("cmd", failed?.first ?: JSONObject.NULL).put("out", failed?.let { clip(it.second, 3000) } ?: JSONObject.NULL))
                if (failed == null) { stop(convoId, a, "gates passed", "✅ Autonomous run finished: all quality gates passed (${gl.joinToString(", ") { "`$it`" }})."); return false }
            }
            u.put("gateRetries", u.optInt("gateRetries") + 1)
            if (u.optInt("gateRetries") > lim(a.opt("gateRetries"))) {
                stop(convoId, a, "gate retries exhausted", "⛔ Autonomous run stopped: the gate `${a.getJSONObject("lastGate").optString("cmd")}` still fails after ${limStr(a.opt("gateRetries"))} retries. This does not mean the task succeeded.")
                return false
            }
        }
        val lr = limitReason(convoId, a)
        if (lr != null) { stop(convoId, a, lr, "⏹ Autonomous run stopped at the $lr. Reaching a limit does not mean the task succeeded${if (gl.isNotEmpty()) "" else " (no quality gates were set)"}."); return false }
        u.put("continuations", u.optInt("continuations") + 1)
        val last = a.optJSONObject("lastGate")
        val text = if (gl.isNotEmpty() && last != null && !last.optBoolean("pass"))
            "[autonomous-continuation] Quality gate failed (attempt ${u.optInt("gateRetries")}/${limStr(a.opt("gateRetries"))}). The command `${last.optString("cmd")}` did not pass:\n${last.optString("out")}\n\nFix the cause, verify, and continue. $CONTINUATION_PROMPT"
        else "[autonomous-continuation]\n\n$CONTINUATION_PROMPT"
        put(convoId, a)
        val max = a.getJSONObject("limits").opt("maxContinuations")
        val ln = Turns.langNote(convoId)
        return Turns.start(convoId, text + if (ln.isNotBlank()) "\n\n$ln" else "", "Autonomous continuation ${u.optInt("continuations")}/${limStr(max)}", "auto")
    }

    /** /autonomous on [flags] [task] | off | status — returns the note to show; starts work when asked. */
    fun command(convoId: String, rest0: String): String {
        val rest = rest0.trim()
        if (rest.isEmpty() || rest == "status") return "🤖 " + status(convoId)
        if (rest == "off") { off(convoId); return Y.t("Autonomous mode off", "Otonom mod kapalı") }
        val on = Regex("^on\\b([\\s\\S]*)$").find(rest) ?: return Y.t("Use /autonomous on [flags] | off | status", "Kullanım: /autonomous on [seçenekler] | off | status")
        val flags = on.groupValues[1].trim()
        val task = flags.replace(FLAG, " ").replace(Regex("\\s+"), " ").trim()
        enable(convoId, flags)
        val msg = "🤖 " + status(convoId)
        if (task.isNotEmpty()) { if (Turns.isRunning(convoId)) Turns.steer(convoId, task, user = false) else Turns.start(convoId, task, null, null) }
        else if (!Turns.isRunning(convoId) && (Convos.load(convoId)?.messages?.length() ?: 0) > 0) Y.bg { try { next(convoId) } catch (e: Throwable) { Turns.note(convoId, "Autonomous: " + e.message) } }
        return msg
    }
}
