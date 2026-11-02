package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-conversation planning state that does not belong in Convo: todos, goal, plan mode, autonomous mode.
 * Stored as filesDir/convo-state/<convoId>.json.
 */
object ConvoState {
    private val lock = Any()
    private val dir: File get() = File(Y.app.filesDir, "convo-state").apply { mkdirs() }
    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    /** A copy of the state (empty object when there is none). */
    fun get(id: String): JSONObject = synchronized(lock) {
        val f = file(id)
        if (f.exists()) try { return JSONObject(f.readText()) } catch (_: Exception) {}
        JSONObject()
    }

    /** Read-modify-write under the lock; returns the saved state. */
    fun edit(id: String, f: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
        val j = get(id)
        f(j)
        val out = file(id)
        val tmp = File(out.path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(out)
        j
    }

    fun delete(id: String) = synchronized(lock) { file(id).delete() }
}

/**
 * Questions the agent puts to the user while a tool waits (clarify, present_plan).
 * Integrator: set [emitter] (e.g. `{ t, d -> Bridge.emit(t, d); Bridge.host != null }`) and route the UI's answer to [answer];
 * or set [handler] to answer some other way. Both block the tool thread; null = no answer (no UI, background, dismissed, timeout).
 */
object Ask {
    /** Optional full override: blocks until answered; returns null when there is no UI. */
    @Volatile var handler: ((convoId: String, kind: String, payload: JSONObject) -> JSONObject?)? = null
    /** Sends the "ask" event to the UI; returns false when no UI is showing (then the question is not asked). */
    @Volatile var emitter: ((type: String, data: JSONObject) -> Boolean)? = null
    var timeoutMinutes = 30L

    private val pending = ConcurrentHashMap<Int, Pair<CountDownLatch, Array<JSONObject?>>>()
    private val seq = AtomicInteger(0)

    /** The UI's answer for request [reqId] (null = dismissed). */
    fun answer(reqId: Int, answer: JSONObject?) { pending[reqId]?.let { it.second[0] = answer; it.first.countDown() } }

    /** Cancels every open question of a conversation (e.g. when the chat is closed). */
    fun dismissAll() { for (p in pending.values) p.first.countDown() }

    fun request(ctx: ToolCtx, kind: String, payload: JSONObject): JSONObject? {
        if (ctx.background || ctx.listener == null) return null
        handler?.let { return it(ctx.convo.id, kind, payload) }
        val em = emitter ?: return null
        val rid = seq.incrementAndGet()
        val l = CountDownLatch(1)
        val box = arrayOfNulls<JSONObject>(1)
        pending[rid] = Pair(l, box)
        try {
            val data = JSONObject(payload.toString()).put("reqId", rid).put("convoId", ctx.convo.id).put("kind", kind)
            if (!em("ask", data)) return null
            ctx.cancel.onCancel { l.countDown() }
            l.await(timeoutMinutes, TimeUnit.MINUTES)
            ctx.cancel.check()
            return box[0]
        } finally {
            pending.remove(rid)
        }
    }
}

/** Todo list, clarifying questions, goal mode, plan mode, autonomous mode, deliverables. */
object Planning {
    const val GOAL_PREFIX = "[goal round]"
    const val PLAN_PREFIX = "[Plan mode] Explore and read as much as you need, but do not modify files or take actions with side effects yet. When the plan is ready, call present_plan with the complete plan in markdown, then stop and wait for the user's decision."
    const val PLAN_GO = "The plan is approved. Execute it now, step by step, and verify the result."
    const val AUTONOMOUS_CONTINUATION_PROMPT = "No human input is available in autonomous mode. Continue working until the host evaluator, verifier, or configured autonomous limits stop the run. If you were asking the user a question, make a reasonable assumption and verify it. If you believe you are blocked, prove it with host-observable evidence, preserve that evidence, and keep looking for safe progress while budget remains. Do not end the session yourself; the verifier/evaluator decides completion when configured gates pass."
    const val NO_HUMAN = "No human input is available in autonomous mode. Make a reasonable assumption, state it in your work, verify it where you can, and continue."
    const val GOAL_GUIDANCE = "- Long objectives: the goal tool keeps a goal and yaver continues it in automatic rounds until you call goal complete or block. When finished work produces files the user asked for, show them with present_files."
    private val AUTO_DEFAULTS = mapOf("maxContinuations" to 3.0, "maxTurns" to 12.0, "maxTokens" to 80000.0, "minutes" to 30.0)
    private const val AUTO_GATE_RETRIES = 3
    private val AUTO_FLAG = Regex("(?:^|\\s)--([\\w-]+)(?:[= ](\"[^\"]*\"|'[^']*'|[^\\s-]\\S*))?")

    /**
     * UI events: type "state" (data = [state] — todos/goal/plan/autonomous changed), "note" (data.text: a line to show in the chat),
     * "files" (data.files: [{path, name, description, size, image}] — deliverable cards), "plan" (data.plan: a plan card with
     * Approve → [approvePlan] and Keep planning, used only when the blocking question could not be asked).
     */
    @Volatile var onEvent: ((convoId: String, type: String, data: JSONObject) -> Unit)? = null

    /** LLM tokens used so far (in + out); the integrator adds every Llm.chat usage. Used by autonomous token limits. */
    val tokensUsed = AtomicLong(0)
    fun countTokens(n: Int) { if (n > 0) tokensUsed.addAndGet(n.toLong()) }

    private fun emit(id: String, type: String, data: JSONObject) { try { onEvent?.invoke(id, type, data) } catch (e: Throwable) { Diag.e("planning", "event", e) } }
    private fun note(id: String, text: String) = emit(id, "note", JSONObject().put("text", text))
    private fun changed(id: String) = emit(id, "state", state(id))

    /** {todos:[{id,content,status}], goal:{objective,status,round,maxRounds,notes,summary}|null, planMode, autonomous:{…, status}|null} */
    fun state(convoId: String): JSONObject {
        val s = ConvoState.get(convoId)
        val o = JSONObject().put("todos", s.optJSONArray("todos") ?: JSONArray()).put("goal", s.optJSONObject("goal") ?: JSONObject.NULL).put("planMode", s.optBoolean("planMode"))
        val a = s.optJSONObject("autonomous")
        o.put("autonomous", if (a == null) JSONObject.NULL else JSONObject(a.toString()).put("status", autoStatus(convoId)))
        return o
    }

    // ------------------------------------------------------------ todos

    fun todos(convoId: String): JSONArray = ConvoState.get(convoId).optJSONArray("todos") ?: JSONArray()

    private fun todoText(t: JSONArray): String {
        val l = (0 until t.length()).map { t.getJSONObject(it) }
        return "${l.count { it.optString("status") == "completed" }}/${l.size} done\n" + l.joinToString("\n") {
            "[${when (it.optString("status")) { "completed" -> "x"; "in_progress" -> ">"; "cancelled" -> "-"; else -> " " }}] ${it.optString("content")}"
        }
    }

    // ------------------------------------------------------------ plan mode

    fun isPlanMode(convoId: String) = ConvoState.get(convoId).optBoolean("planMode")
    fun setPlanMode(convoId: String, on: Boolean) { ConvoState.edit(convoId) { it.put("planMode", on) }; changed(convoId) }

    /** The plan card's Approve button: plan mode off; send the returned text as the next turn (label "plan"). */
    fun approvePlan(convoId: String): String { setPlanMode(convoId, false); return PLAN_GO }

    /** Text of a user message as sent to the model: plan-mode prefix for the user's own messages. */
    fun userPrefix(c: Convo, text: String, internal: Boolean = false): String =
        if (!internal && isPlanMode(c.id)) PLAN_PREFIX + "\n\n" + text else text

    private val SIDE_EFFECT_TOOLS = setOf("write_file", "delete_file", "export_file", "skill_manage", "phone_notify", "phone_open", "schedule_task", "goal_noop")

    fun hasSideEffect(name: String, a: JSONObject): Boolean {
        val act = a.optString("action")
        if (name in SIDE_EFFECT_TOOLS) return name != "schedule_task" || act != "list"
        return when (name) {
            "memory" -> act in setOf("add", "replace", "remove")
            "mail" -> act in setOf("draft", "send", "send_now", "mark_read", "delete", "move", "archive")
            "calendar" -> act in setOf("create", "respond", "cancel")
            "phone_calendar" -> act in setOf("create", "delete")
            "phone_alarm" -> act in setOf("alarm", "timer")
            "phone_reminder" -> act in setOf("add", "cancel")
            "phone_clipboard" -> act == "set"
            "http_request" -> a.optString("method").let { it.isNotEmpty() && !Regex("^(GET|HEAD)$", RegexOption.IGNORE_CASE).matches(it) }
            "run_code" -> if (a.optString("language", "python").startsWith("py"))
                Regex("open\\([^)]*['\"][wax+]|\\.to_\\w+\\(|savefig|\\.save\\(|\\bos\\.|shutil|pathlib|write_text|write_bytes|pyfetch|micropip").containsMatchIn(a.optString("code"))
            else Regex("fs\\.writeFile|tools\\.|fetch\\(").containsMatchIn(a.optString("code"))
            else -> false
        }
    }

    /** Call before running a tool: non-null = do not run it, use this as the tool result. */
    fun gate(c: Convo, toolName: String, args: JSONObject): String? {
        if (toolName != "present_plan" && isPlanMode(c.id) && hasSideEffect(toolName, args))
            return "Not executed: plan mode is on. Finish exploring, then call present_plan and wait for approval."
        return null
    }

    // ------------------------------------------------------------ goal

    fun goal(convoId: String): JSONObject? = ConvoState.get(convoId).optJSONObject("goal")

    fun setGoal(convoId: String, objective: String, maxRounds: Int = 0): JSONObject {
        val g = JSONObject().put("objective", objective.trim()).put("status", "active").put("round", 0)
            .put("maxRounds", if (maxRounds > 0) maxRounds.coerceIn(1, 50) else 8).put("notes", "").put("created", System.currentTimeMillis())
        ConvoState.edit(convoId) { it.put("goal", g) }
        changed(convoId)
        return g
    }

    fun describe(g: JSONObject?): String = if (g == null) "No goal is set." else
        "Goal: ${g.optString("objective")}\nStatus: ${g.optString("status")} · round ${g.optInt("round")}/${g.optInt("maxRounds")}" +
            (if (g.optString("notes").isNotEmpty()) "\nNotes: ${g.optString("notes")}" else "") +
            (if (g.optString("summary").isNotEmpty()) "\nSummary: ${g.optString("summary")}" else "")

    fun continuation(g: JSONObject): String =
        "$GOAL_PREFIX Round ${g.optInt("round")}/${g.optInt("maxRounds")}. Keep working toward the goal: ${g.optString("objective")}" +
            (if (g.optString("notes").isNotEmpty()) "\nProgress notes so far: ${g.optString("notes")}" else "") +
            "\nDo the next concrete piece of work. Record progress with goal action \"update\" (notes). When the goal is fully achieved call goal action \"complete\" with a short summary; if you cannot continue without the user, call goal action \"block\" with the reason."

    private fun joinNotes(vararg p: String) = p.filter { it.isNotEmpty() }.joinToString(" | ")

    /**
     * After a turn ends (not stopped, nothing queued, agent idle): the next goal round's user message, or null.
     * Counts the round. Pauses the goal at its round limit (and says so through a "note" event).
     */
    fun goalContinuation(c: Convo): String? {
        var out: String? = null
        var limit = 0
        ConvoState.edit(c.id) { s ->
            val g = s.optJSONObject("goal") ?: return@edit
            if (g.optString("status") != "active") return@edit
            val max = g.optInt("maxRounds", 8)
            if (g.optInt("round") >= max) {
                g.put("status", "paused").put("notes", joinNotes(g.optString("notes"), "round limit ($max) reached"))
                limit = max
                return@edit
            }
            g.put("round", g.optInt("round") + 1)
            out = continuation(g)
        }
        if (limit > 0) note(c.id, "⏹ Goal paused: round limit reached. Use /goal resume to continue.")
        if (out != null || limit > 0) changed(c.id)
        return out
    }

    /** UI "resume" button: goal active again (5 more rounds when at the limit); then start [goalContinuation] if idle. */
    fun resumeGoal(convoId: String) {
        ConvoState.edit(convoId) { s ->
            val g = s.optJSONObject("goal") ?: return@edit
            g.put("status", "active")
            if (g.optInt("round") >= g.optInt("maxRounds")) g.put("maxRounds", g.optInt("round") + 5)
        }
        changed(convoId)
    }

    fun pauseGoal(convoId: String) { ConvoState.edit(convoId) { s -> s.optJSONObject("goal")?.put("status", "paused") }; changed(convoId) }
    fun clearGoal(convoId: String) { ConvoState.edit(convoId) { it.remove("goal") }; changed(convoId) }

    /** The user stopped a run: an active goal pauses. */
    fun onStopped(c: Convo) {
        var did = false
        ConvoState.edit(c.id) { s -> s.optJSONObject("goal")?.let { if (it.optString("status") == "active") { it.put("status", "paused"); did = true } } }
        if (did) changed(c.id)
    }

    /** "Clear context": todos are emptied and an active goal pauses. */
    fun onContextCleared(c: Convo) {
        ConvoState.edit(c.id) { s -> s.put("todos", JSONArray()); s.optJSONObject("goal")?.let { if (it.optString("status") == "active") it.put("status", "paused") } }
        changed(c.id)
    }

    // ------------------------------------------------------------ autonomous mode

    private fun num(v: String?): Double = if (v != null && Regex("^unlimited$", RegexOption.IGNORE_CASE).matches(v)) Double.POSITIVE_INFINITY
        else (v ?: "").replace(Regex("[,_]"), "").toDoubleOrNull() ?: Double.NaN

    private fun limJson(d: Double): Any = if (d.isInfinite()) "unlimited" else d
    private fun lim(v: Any?): Double = if (v == null || v == JSONObject.NULL || v == "unlimited") Double.POSITIVE_INFINITY else (v as? Number)?.toDouble() ?: v.toString().toDoubleOrNull() ?: Double.POSITIVE_INFINITY

    private fun parseAuto(rest: String): JSONObject {
        val limits = HashMap<String, Double>()
        val gates = ArrayList<String>()
        var gateRetries = Double.NaN
        var named = false
        for (m in AUTO_FLAG.findAll(rest)) {
            val k = m.groupValues[1].removePrefix("autonomous-")
            val v = m.groups[2]?.value?.replace(Regex("^[\"']|[\"']$"), "")
            when (k) {
                "gate" -> if (!v.isNullOrBlank()) gates.add(v)
                "gate-retries" -> gateRetries = num(v)
                "max-continuations" -> { limits["maxContinuations"] = num(v); named = true }
                "max-turns" -> { limits["maxTurns"] = num(v); named = true }
                "max-tokens" -> { limits["maxTokens"] = num(v); named = true }
                "minutes" -> { limits["minutes"] = num(v); named = true }
                "timeout-ms" -> { limits["minutes"] = num(v) / 60000; named = true }
            }
        }
        // named budget flags define the whole budget: the ones not named become unlimited
        val L = LinkedHashMap<String, Double>()
        for ((k, d) in AUTO_DEFAULTS) L[k] = if (named) limits[k] ?: Double.POSITIVE_INFINITY else d
        for (k in L.keys.toList()) if (!(L[k]!! > 0)) L[k] = AUTO_DEFAULTS[k]!!
        val lj = JSONObject(); for ((k, d) in L) lj.put(k, limJson(d))
        return JSONObject().put("limits", lj).put("gates", JSONArray(gates))
            .put("gateRetries", if (gateRetries >= 0) limJson(gateRetries) else AUTO_GATE_RETRIES)
    }

    private fun assistantTurns(c: Convo) = (0 until c.messages.length()).count { c.messages.getJSONObject(it).optString("role") == "assistant" }

    fun enableAutonomous(c: Convo, flags: String): JSONObject {
        val p = parseAuto(flags)
        val a = JSONObject().put("enabled", true).put("limits", p.getJSONObject("limits")).put("gates", p.getJSONArray("gates")).put("gateRetries", p.get("gateRetries"))
            .put("used", JSONObject().put("continuations", 0).put("turnsAt", assistantTurns(c)).put("tokensAt", tokensUsed.get()).put("started", System.currentTimeMillis()).put("gateRetries", 0))
        ConvoState.edit(c.id) { it.put("autonomous", a) }
        changed(c.id)
        return a
    }

    fun autonomousOn(convoId: String) = ConvoState.get(convoId).optJSONObject("autonomous")?.optBoolean("enabled") == true

    fun disableAutonomous(convoId: String, reason: String = "turned off") {
        ConvoState.edit(convoId) { s -> s.optJSONObject("autonomous")?.let { it.put("enabled", false).put("stopReason", reason) } }
        changed(convoId)
    }

    private fun fmtN(x: Double) = String.format(java.util.Locale.US, "%,d", Math.round(x))

    fun autoStatus(convoId: String, c: Convo? = null): String {
        val a = ConvoState.get(convoId).optJSONObject("autonomous") ?: return "Autonomous mode is off."
        val u = a.getJSONObject("used")
        val L = a.getJSONObject("limits")
        val turns = (c?.let { assistantTurns(it) } ?: u.optInt("turnsAt")) - u.optInt("turnsAt")
        val tokens = tokensUsed.get() - u.optLong("tokensAt")
        val mins = (System.currentTimeMillis() - u.optLong("started")) / 60000.0
        fun f(x: Double, l: Any?) = "${fmtN(x)}/${if (lim(l).isInfinite()) "∞" else fmtN(lim(l))}"
        val gates = a.optJSONArray("gates") ?: JSONArray()
        val gl = (0 until gates.length()).joinToString(", ") { "`" + gates.getString(it) + "`" }
        return "Autonomous mode ${if (a.optBoolean("enabled")) "ON" else "off"}${a.optString("stopReason").let { if (it.isNotEmpty()) " (stopped: $it)" else "" }} · continuations ${f(u.optDouble("continuations"), L.opt("maxContinuations"))} · turns ${f(turns.toDouble(), L.opt("maxTurns"))} · tokens ${f(tokens.toDouble(), L.opt("maxTokens"))} · minutes ${f(mins, L.opt("minutes"))}" +
            if (gates.length() > 0) " · gates: $gl (retries ${u.optInt("gateRetries")}/${a.opt("gateRetries")})" else " · no gates"
    }

    private fun limitReason(c: Convo, a: JSONObject): String? {
        val u = a.getJSONObject("used"); val L = a.getJSONObject("limits")
        if (u.optInt("continuations") >= lim(L.opt("maxContinuations"))) return "continuation limit"
        if (assistantTurns(c) - u.optInt("turnsAt") >= lim(L.opt("maxTurns"))) return "turn limit"
        if (tokensUsed.get() - u.optLong("tokensAt") >= lim(L.opt("maxTokens"))) return "token limit"
        if ((System.currentTimeMillis() - u.optLong("started")) / 60000.0 >= lim(L.opt("minutes"))) return "time limit"
        return null
    }

    /**
     * A gate passes or fails after a round. Android has no shell, so gates are code: "js: <expression>" passes when the
     * value is truthy; "python: <code>" (or "py:") passes when the code runs without an error. Both run in run_code's engine.
     */
    private fun runGate(cmd: String, cancel: Cancel): Pair<Boolean, String> {
        return try {
            val js = Regex("^js:", RegexOption.IGNORE_CASE).find(cmd)
            val py = Regex("^(python|py):", RegexOption.IGNORE_CASE).find(cmd)
            when {
                js != null -> {
                    val expr = cmd.substring(js.value.length).trim()
                    val out = CodeRunner.run("javascript", "const __v = ($expr);\nconsole.log(\"=> \" + (typeof __v === \"string\" ? JSON.stringify(__v) : String(__v)));", emptyList(), 15, cancel)
                    Pair(Regex("=> (?!false\\b|0\\b|null\\b|undefined\\b|\"\"|'')\\S").containsMatchIn(out), out)
                }
                py != null -> Pair(true, CodeRunner.run("python", cmd.substring(py.value.length).trim(), emptyList(), 120, cancel) + "\n[exit 0]")
                else -> Pair(false, "This gate is not code: Android has no shell. Use a gate like \"js: <expression>\" or \"python: <code>\".\n[exit 127]")
            }
        } catch (e: CancelledException) { throw e } catch (e: Throwable) { Pair(false, (e.message ?: e.toString()) + "\n[exit 1]") }
    }

    private fun workspaceStamp(): Long = try {
        var h = 0L
        Workspace.root.walkTopDown().filter { it.isFile }.forEach { h = h * 31 + it.lastModified() + it.length() + it.path.hashCode() }
        h
    } catch (_: Throwable) { System.currentTimeMillis() }

    private fun autoStop(c: Convo, reason: String, text: String) {
        disableAutonomous(c.id, reason)
        note(c.id, text)
    }

    /** After a turn in autonomous mode: the next continuation message, or null when the run is over (gates passed, limits). */
    fun autonomousNext(c: Convo, cancel: Cancel): String? {
        val a = ConvoState.get(c.id).optJSONObject("autonomous") ?: return null
        if (!a.optBoolean("enabled")) return null
        val u = a.getJSONObject("used")
        val gates = a.optJSONArray("gates") ?: JSONArray()
        val gl = (0 until gates.length()).map { gates.getString(it) }
        val retries = lim(a.opt("gateRetries"))
        if (gl.isNotEmpty()) {
            val stamp = workspaceStamp()
            val last = a.optJSONObject("lastGate")
            if (last != null && !last.optBoolean("pass") && last.optLong("stamp") == stamp && u.optInt("continuations") > 0) { /* nothing changed since the failed gate: do not rerun it */ }
            else {
                var failed: Pair<String, String>? = null
                for (g in gl) { cancel.check(); val (pass, out) = runGate(g, cancel); if (!pass) { failed = Pair(g, out); break } }
                a.put("lastGate", JSONObject().put("pass", failed == null).put("stamp", stamp).put("cmd", failed?.first ?: JSONObject.NULL).put("out", failed?.second?.let { clip(it, 3000) } ?: JSONObject.NULL))
                ConvoState.edit(c.id) { it.put("autonomous", a) }
                if (failed == null) { autoStop(c, "gates passed", "✅ Autonomous run finished: all quality gates passed (${gl.joinToString(", ") { "`$it`" }})."); return null }
            }
            u.put("gateRetries", u.optInt("gateRetries") + 1)
            ConvoState.edit(c.id) { it.put("autonomous", a) }
            if (u.optInt("gateRetries") > retries) {
                autoStop(c, "gate retries exhausted", "⛔ Autonomous run stopped: the gate `${a.optJSONObject("lastGate")?.optString("cmd")}` still fails after ${a.opt("gateRetries")} retries. This does not mean the task succeeded.")
                return null
            }
        }
        val why = limitReason(c, a)
        if (why != null) {
            autoStop(c, why, "⏹ Autonomous run stopped at the $why. Reaching a limit does not mean the task succeeded${if (gl.isNotEmpty()) "" else " (no quality gates were set)"}.")
            return null
        }
        u.put("continuations", u.optInt("continuations") + 1)
        ConvoState.edit(c.id) { it.put("autonomous", a) }
        changed(c.id)
        val last = a.optJSONObject("lastGate")
        return if (gl.isNotEmpty() && last != null && !last.optBoolean("pass"))
            "[autonomous-continuation] Quality gate failed (attempt ${u.optInt("gateRetries")}/${a.opt("gateRetries")}). The command `${last.optString("cmd")}` did not pass:\n${last.optString("out")}\n\nFix the cause, verify, and continue. $AUTONOMOUS_CONTINUATION_PROMPT"
        else "[autonomous-continuation]\n\n$AUTONOMOUS_CONTINUATION_PROMPT"
    }

    // ------------------------------------------------------------ next automatic round

    class Round(val text: String, val label: String, val kind: String)

    /**
     * Call when a turn ended normally (not stopped, no queued user message). Returns the next automatic round —
     * run it with Agent.run(c, round.text, label = round.label, …) — or null. Goal first, then autonomous mode (as in the original).
     */
    fun nextRound(c: Convo, cancel: Cancel): Round? {
        goalContinuation(c)?.let { t ->
            val g = goal(c.id)
            return Round(t, Y.t("Goal round", "Hedef turu") + " ${g?.optInt("round")}/${g?.optInt("maxRounds")}: " + clip(g?.optString("objective") ?: "", 80), "goal")
        }
        if (autonomousOn(c.id)) {
            val t = autonomousNext(c, cancel) ?: return null
            val a = ConvoState.get(c.id).optJSONObject("autonomous")
            val used = a?.optJSONObject("used")?.optInt("continuations") ?: 0
            val max = lim(a?.optJSONObject("limits")?.opt("maxContinuations"))
            return Round(t, "Autonomous continuation $used/${if (max.isInfinite()) "∞" else max.toLong().toString()}", "auto")
        }
        return null
    }

    // ------------------------------------------------------------ slash commands

    /** Result of a slash command: [note] to show; [send] = a message to run as a turn ([internal] = true: do not add the plan prefix). */
    class Cmd(val note: String? = null, val send: String? = null, val label: String? = null, val internal: Boolean = false)

    val COMMANDS = linkedMapOf(
        "/plan" to "Turn plan mode on (optionally followed by a request); /plan off turns it off",
        "/goal" to "/goal <objective> sets a goal and starts working; /goal pause | resume | clear | status",
        "/autonomous" to "/autonomous on [--max-continuations N --max-turns N --max-tokens N --minutes N --gate \"js: …\" --gate-retries N] | off | status"
    )

    /** Handles /plan, /goal and /autonomous typed in the composer; null = not one of these. [busy] = a turn is running. */
    fun command(c: Convo, text: String, busy: Boolean): Cmd? {
        val m = Regex("^/(\\w+)\\s*([\\s\\S]*)$").find(text.trim()) ?: return null
        val cmd = m.groupValues[1]; val rest = m.groupValues[2].trim()
        when (cmd) {
            "plan" -> {
                if (Regex("^off$", RegexOption.IGNORE_CASE).matches(rest)) { setPlanMode(c.id, false); return Cmd(note = "Plan mode off") }
                setPlanMode(c.id, true)
                return Cmd(note = "Plan mode on: yaver will plan and ask before executing", send = rest.ifEmpty { null })
            }
            "goal" -> {
                if (rest.isEmpty() || rest == "status") return Cmd(note = describe(goal(c.id)))
                if (rest in setOf("pause", "resume", "clear")) {
                    if (goal(c.id) == null) return Cmd(note = "No goal is set")
                    when (rest) { "clear" -> clearGoal(c.id); "pause" -> pauseGoal(c.id); else -> ConvoState.edit(c.id) { s -> s.optJSONObject("goal")?.put("status", "active") }.also { changed(c.id) } }
                    if (rest == "resume" && !busy) goalContinuation(c)?.let { return Cmd(send = it, label = goalLabel(c), internal = true) }
                    return Cmd()
                }
                setGoal(c.id, rest)
                if (busy) return Cmd(note = "Goal set; it starts when the current task finishes")
                return goalContinuation(c)?.let { Cmd(send = it, label = goalLabel(c), internal = true) } ?: Cmd()
            }
            "autonomous" -> {
                if (rest.isEmpty() || rest == "status") return Cmd(note = "🤖 " + autoStatus(c.id, c))
                if (rest == "off") { if (ConvoState.get(c.id).has("autonomous")) disableAutonomous(c.id); return Cmd(note = "Autonomous mode off") }
                val on = Regex("^on\\b([\\s\\S]*)$").find(rest) ?: return Cmd(note = "Use /autonomous on [flags] | off | status")
                val flags = on.groupValues[1].trim()
                val task = flags.replace(AUTO_FLAG, " ").replace(Regex("\\s+"), " ").trim()
                enableAutonomous(c, flags)
                val st = "🤖 " + autoStatus(c.id, c)
                if (task.isNotEmpty()) return Cmd(note = st, send = task)
                if (!busy && c.messages.length() > 0) autonomousNext(c, Cancel())?.let { return Cmd(note = st, send = it, label = "Autonomous continuation", internal = true) }
                return Cmd(note = st)
            }
        }
        return null
    }

    private fun goalLabel(c: Convo): String { val g = goal(c.id); return Y.t("Goal round", "Hedef turu") + " ${g?.optInt("round")}/${g?.optInt("maxRounds")}" }

    // ------------------------------------------------------------ tools

    fun register() {
        val todoItem = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("id", JSONObject().put("type", "string")).put("content", JSONObject().put("type", "string"))
            .put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "in_progress", "completed", "cancelled")))))
            .put("required", JSONArray(listOf("content", "status")))
        Tools.add(Tool(
            "todo", "Manage the task list for multi-step work. Pass the full list each time; statuses: pending, in_progress, completed, cancelled.",
            P.props("todos" to JSONObject().put("type", "array").put("items", todoItem)), listOf("todos")
        ) { a, ctx ->
            val src = a.optJSONArray("todos") ?: JSONArray()
            val t = JSONArray()
            for (i in 0 until src.length()) {
                val o = src.optJSONObject(i) ?: continue
                t.put(JSONObject().put("id", o.str("id").ifEmpty { (i + 1).toString() }).put("content", o.optString("content")).put("status", o.optString("status")))
            }
            ConvoState.edit(ctx.convo.id) { it.put("todos", t) }
            changed(ctx.convo.id)
            todoText(t)
        })

        Tools.add(Tool(
            "clarify", "Ask the user a clarifying question when you cannot proceed without their decision. Offer 2–4 choices when possible.",
            P.props("question" to P.s("The question"), "choices" to P.arr("Optional choices")), listOf("question"),
            subagentOk = false
        ) { a, ctx ->
            if (autonomousOn(ctx.convo.id) || ctx.background) return@Tool NO_HUMAN
            val r = Ask.request(ctx, "clarify", JSONObject().put("question", a.str("question")).put("choices", JSONArray(a.list("choices"))))
            val ans = r?.str("text") ?: ""
            if (ans.isEmpty()) "The user did not answer. Make a reasonable assumption, state it in your work, and continue — or ask again later."
            else "User answered: $ans"
        })

        Tools.add(Tool(
            "goal",
            "Manage the conversation's goal: one long-running objective that yaver keeps working on in automatic rounds until it is complete. Actions: set (objective, max_rounds), update (notes on progress), complete (summary), block (reason in notes), pause, resume, clear, status.",
            P.props(
                "action" to P.s("set | update | complete | block | pause | resume | clear | status", "set", "update", "complete", "block", "pause", "resume", "clear", "status"),
                "objective" to P.s("For set: the objective, stated so that completion can be checked"), "notes" to P.s("Progress notes (update) or the blocking reason (block)"),
                "summary" to P.s("For complete: what was achieved"), "max_rounds" to P.n("For set: automatic rounds allowed (default 8)")
            ),
            listOf("action")
        ) { a, ctx ->
            val id = ctx.convo.id
            val action = a.str("action")
            if (action == "set") {
                if (a.str("objective").isEmpty()) throw IllegalArgumentException("objective is required")
                val g = setGoal(id, a.str("objective"), a.int("max_rounds", 0))
                return@Tool "Goal set. yaver will keep working on it in up to ${g.optInt("maxRounds")} automatic rounds after this turn.\n" + describe(g)
            }
            if (goal(id) == null) return@Tool "No goal is set. Use action set."
            if (action == "clear") { clearGoal(id); return@Tool "Goal cleared." }
            var completed: String? = null
            val s = ConvoState.edit(id) { st ->
                val g = st.getJSONObject("goal")
                when (action) {
                    "update" -> g.put("notes", joinNotes(g.optString("notes"), a.str("notes")).takeLast(1500))
                    "complete" -> { g.put("status", "complete").put("summary", a.str("summary").ifEmpty { a.str("notes") }); completed = g.optString("objective") }
                    "block" -> g.put("status", "blocked").put("notes", joinNotes(g.optString("notes"), "BLOCKED: " + a.str("notes").ifEmpty { a.str("summary") }))
                    "pause" -> g.put("status", "paused")
                    "resume" -> g.put("status", "active")
                }
            }
            completed?.let { note(id, "✅ Goal complete: $it") }
            changed(id)
            describe(s.optJSONObject("goal"))
        })

        Tools.add(Tool(
            "present_plan", "In plan mode: show the finished plan to the user for approval. After calling it, stop and wait; the user approves (execution starts) or asks for changes.",
            P.props("plan" to P.s("The complete plan in markdown: goal, steps, files to touch, risks, what you will verify")), listOf("plan"),
            subagentOk = false
        ) { a, ctx ->
            val plan = a.optString("plan")
            val r = Ask.request(ctx, "plan", JSONObject().put("plan", plan))
            when (r?.str("action")) {
                "approve" -> {
                    setPlanMode(ctx.convo.id, false)
                    val edited = r.optString("plan", "").trim()
                    if (edited.isNotEmpty() && edited != plan.trim()) "$PLAN_GO\nThe user edited the plan before approving it; follow this version:\n$edited" else PLAN_GO
                }
                "revise" -> {
                    val t = r.str("text")
                    "The user wants to keep planning" + (if (t.isNotEmpty()) ": \"$t\"" else ".") + " Plan mode stays on. Revise the plan, then call present_plan again."
                }
                else -> {
                    if (!ctx.background) emit(ctx.convo.id, "plan", JSONObject().put("plan", plan))
                    "The plan is shown to the user with Approve and Keep planning buttons. Stop here and wait for their decision; do not start executing."
                }
            }
        })

        val fileItem = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("path", JSONObject().put("type", "string")).put("description", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("path")))
        Tools.add(Tool(
            "present_files", "Present finished deliverables to the user as file cards they can open or download. Call it at the end of a task for the files the user asked for (reports, exports, archives).",
            P.props("files" to JSONObject().put("type", "array").put("items", fileItem).put("description", "Files to present (up to 8)")), listOf("files")
        ) { a, ctx ->
            val src = a.optJSONArray("files") ?: JSONArray()
            val ok = JSONArray(); val missing = ArrayList<String>()
            for (i in 0 until minOf(8, src.length())) {
                val o = src.optJSONObject(i) ?: JSONObject().put("path", src.optString(i))
                val f = try { Workspace.resolve(o.str("path")) } catch (_: Throwable) { null }
                if (f == null || !f.isFile) { missing.add(o.str("path")); continue }
                val rel = Workspace.rel(f)
                ok.put(JSONObject().put("path", rel).put("name", f.name).put("description", o.str("description")).put("size", f.length()).put("image", Workspace.isImage(f.name)))
            }
            if (ok.length() > 0) emit(ctx.convo.id, "files", JSONObject().put("files", ok))
            "Presented ${ok.length()} file(s) to the user." + if (missing.isNotEmpty()) " Not found: ${missing.joinToString(", ")}" else ""
        })
    }
}
