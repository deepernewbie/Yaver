package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Settings of the context manager (the original's CFG.llm.autoCompact / compactAt / keepTurns / ctxWindow). */
object CtxCfg {
    /** Automatic compaction on/off (default on). */
    var autoCompact: Boolean get() = Prefs.on("ctx.autoCompact", true); set(v) = Prefs.set("ctx.autoCompact", v)
    /** Auto-compact when the context passes this % of the budget (40–95, default 90). */
    var compactAt: Int get() = Prefs.int("ctx.compactAt", 90).coerceIn(40, 95); set(v) = Prefs.put("ctx.compactAt", v.coerceIn(40, 95).toString())
    /** "Turns kept word for word" (1–20, default 3). */
    var keepTurns: Int get() = Prefs.int("ctx.keepTurns", 3).coerceIn(1, 20); set(v) = Prefs.put("ctx.keepTurns", v.coerceIn(1, 20).toString())
    /** Model context window set by hand (tokens, 0 = auto: discovered from the gateway or learned from errors). */
    var window: Int get() = maxOf(0, Prefs.int("ctx.window", 0)); set(v) = Prefs.put("ctx.window", maxOf(0, v).toString())
    /** Upper limit for the conversation ("Context budget"): the existing Cfg.contextTokens. */
    val budgetCap: Int get() = Cfg.contextTokens.takeIf { it > 0 } ?: 60000
    /** Output tokens reserved (the original used its max-tokens setting, default 4096; the app has none). */
    const val MAX_OUTPUT = 4096
}

/**
 * Context compaction (model-aware, staged, incremental) — port of the original's Compactor.
 * - knows the model's context window (discovered from /models or LiteLLM /model/info, learned from "context length
 *   exceeded" errors, or set by hand) and calibrates its token estimate against the prompt_tokens the server reports
 * - auto-compacts before a request when the context passes CtxCfg.compactAt % of the budget, and compacts + retries
 *   when the server still says the prompt is too long ([chatWithRetry])
 * - stages, cheapest first: 1) old pictures and superseded / oversized old tool outputs are shrunk
 *   2) System-1 relevance pruning (when [relevance] is set) 3) the oldest turns are folded into a running, structured
 *   summary that is UPDATED each time; the user's own messages and the files touched are kept verbatim next to it
 * - never breaks a turn: if the summary call fails, a deterministic summary is used
 *
 * Native layout: messages are never deleted (the screen shows them). Everything before `Convo.summaryUpTo` is folded
 * into `Convo.summary`; [head] gives the summary messages to send in front of messages[summaryUpTo…].
 */
object Compactor {
    private val OVERFLOW_RE = Regex("context[_ ]?length|context[_ ]?window|maximum context|too many tokens|prompt is too long|exceeds? the (?:available )?context|ContextWindowExceeded|reduce the length of the (?:messages|prompt)|input is too long|token limit|max_?input_?tokens|string too long", RegexOption.IGNORE_CASE)
    private val NOT_OVERFLOW_RE = Regex("rate limit|quota|insufficient|credit|balance", RegexOption.IGNORE_CASE)
    private val NOT_REAL_USER_RE = Regex("^\\[(?:Context summary|harness note|verify-on-stop|daily plan|Language)")

    /** System prompt as actually sent; the integrator may replace it (e.g. with the harness's full prompt builder). */
    @Volatile var systemFor: (Convo) -> String = { Prompt.forRequest(it) }
    /** Optional System-1 relevance scorer for stage 2: returns 0..3 (irrelevant now … essential) or null. */
    @Volatile var relevance: ((goal: String, oldToolOutput: String, cancel: Cancel?) -> Double?)? = null

    /** Tools always kept by [slimTools] (the original's core toolsets files/memory/planning, plus native equivalents). */
    val CORE_TOOLS = setOf("read_file", "write_file", "patch", "search_files", "list_files", "delete_file", "read_document", "search_documents",
        "unpack_archive", "pack_archive", "present_files", "search_all", "export_file", "memory", "session_search",
        "todo", "clarify", "delegate_task", "goal", "present_plan", "harness", "heartbeat", "subagents")
    private val LAST_RESORT_TOOLS = Regex("^(read_file|write_file|patch|search_files|terminal|memory|todo|clarify|list_files|run_code)$")
    private val SUPERSEDABLE = Regex("read_file|browser_snapshot|web_extract|skill_view|read_document|search_files|mail|calendar|task_board|read_page|list_files|people")

    private fun fmt(n: Int) = String.format(Locale.US, "%,d", n)
    private fun k(n: Int) = Math.round(n / 1000.0).toString()

    // ---------------------------------------------------------------- per-model learned values (Prefs)
    private fun map(key: String): JSONObject = try { JSONObject(Prefs.get(key, "{}")) } catch (e: Exception) { JSONObject() }
    private fun model(m: String?) = (m ?: "").ifEmpty { Cfg.model }

    /** characters per token (Turkish text is ~3, English ~4) */
    fun cpt(model: String? = null): Double { val v = map("ctx.cpt").optDouble(model(model), 0.0); return if (v > 1 && v < 8) v else 3.3 }

    fun learnedWindow(model: String? = null): Int = map("ctx.learned").optInt(model(model), 0)
    fun knownWindow(model: String? = null): Int = map("ctx.known").optInt(model(model), 0)

    /** The model's context window in tokens, or 0 when unknown. */
    fun window(model: String? = null): Int {
        val o = CtxCfg.window; if (o > 0) return o
        val l = learnedWindow(model); if (l > 0) return l
        return knownWindow(model)
    }

    fun outReserve(w: Int): Int { val want = maxOf(1024, minOf(64000, if (Cfg.maxOut > 0) Cfg.maxOut else CtxCfg.MAX_OUTPUT)); return if (w > 0) minOf(want, maxOf(768, (w * 0.2).toInt())) else want }
    fun budget(model: String? = null): Int { val cap = CtxCfg.budgetCap; val w = window(model); return if (w > 0) maxOf(2000, minOf(cap, ((w - outReserve(w)) * 0.92).toInt())) else cap }

    // ---------------------------------------------------------------- per-conversation state (harness/ctx/<id>.json)
    class State(
        val asks: MutableList<String> = ArrayList(), val files: MutableList<String> = ArrayList(),
        var compactions: Int = 0, var log: JSONArray = JSONArray(), var lastPrompt: Int = 0, var lastPromptAt: Long = 0, var toolsChars: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject().put("asks", JSONArray(asks)).put("files", JSONArray(files)).put("compactions", compactions)
            .put("log", log).put("lastPrompt", lastPrompt).put("lastPromptAt", lastPromptAt).put("toolsChars", toolsChars)
    }

    private val states = HashMap<String, State>()
    private fun stateFile(id: String) = File(Y.app.filesDir, "harness/ctx").apply { mkdirs() }.let { File(it, "$id.json") }

    fun state(c: Convo): State = synchronized(states) {
        states.getOrPut(c.id) {
            val f = stateFile(c.id)
            val s = State()
            if (f.exists()) try {
                val j = JSONObject(f.readText())
                j.optJSONArray("asks")?.let { a -> for (i in 0 until a.length()) s.asks.add(a.optString(i)) }
                j.optJSONArray("files")?.let { a -> for (i in 0 until a.length()) s.files.add(a.optString(i)) }
                s.compactions = j.optInt("compactions"); s.log = j.optJSONArray("log") ?: JSONArray()
                s.lastPrompt = j.optInt("lastPrompt"); s.lastPromptAt = j.optLong("lastPromptAt"); s.toolsChars = j.optInt("toolsChars")
            } catch (e: Exception) { Diag.e("ctx", "state of ${c.id} unreadable", e) }
            s
        }
    }

    fun saveState(c: Convo) { val s = state(c); try { synchronized(states) { stateFile(c.id).writeText(s.toJson().toString()) } } catch (e: Exception) { Diag.e("ctx", "save", e) } }

    /** Call when a conversation is deleted, or cleared (messages emptied, summary reset). */
    fun forget(id: String) { synchronized(states) { states.remove(id); stateFile(id).delete() } }

    // ---------------------------------------------------------------- sizes
    /** A message as sent to the API (same fields fixAlternation keeps); null for local-only rows. */
    private fun wire(m: JSONObject): JSONObject? {
        if (m.optBoolean("local")) return null
        val o = JSONObject().put("role", m.optString("role")).put("content", m.opt("content")?.takeIf { it != JSONObject.NULL } ?: "")
        m.optJSONArray("tool_calls")?.let { o.put("tool_calls", it) }
        if (m.has("tool_call_id")) o.put("tool_call_id", m.optString("tool_call_id"))
        return o
    }

    private fun anchorEnd(c: Convo) = c.summaryUpTo.coerceIn(0, c.messages.length())

    /** The summary messages sent before messages[summaryUpTo…] (empty when nothing was compacted yet). */
    fun head(c: Convo): JSONArray {
        val out = JSONArray()
        if (c.summary.isBlank()) return out
        val s = state(c)
        out.put(JSONObject().put("role", "user").put("summary", true).put("content",
            "[Context summary — the earlier part of this conversation was compacted (${maxOf(1, s.compactions)}×). Treat this as your memory of it.]\n\n${c.summary}\n\n${anchorsBlock(c)}"))
        var i = anchorEnd(c)
        while (i < c.messages.length() && c.messages.getJSONObject(i).optBoolean("local")) i++
        if (i < c.messages.length() && c.messages.getJSONObject(i).optString("role") == "user")
            out.put(JSONObject().put("role", "assistant").put("summary", true).put("content", "Understood — I have the summary of the earlier conversation and will continue from it."))
        return out
    }

    /** head + the messages from summaryUpTo on, in API form (no system message), ready for LoopUtil.fixAlternation. */
    fun sentMessages(c: Convo): JSONArray {
        val out = head(c)
        for (i in anchorEnd(c) until c.messages.length()) wire(c.messages.getJSONObject(i))?.let { out.put(it) }
        return out
    }

    private fun sysChars(c: Convo): Int = try { systemFor(c).length } catch (e: Exception) { 0 }
    @Volatile private var allSchema = 0
    private fun schemaChars(c: Convo): Int {
        val s = state(c).toolsChars; if (s > 0) return s
        if (allSchema == 0) allSchema = try { Tools.schemas(false).toString().length } catch (e: Exception) { 0 }
        return allSchema
    }

    /** what one request costs: the system prompt as sent, the messages, and the tool schemas (chars) */
    fun parts(c: Convo, tools: JSONArray? = null): IntArray = intArrayOf(sysChars(c), sentMessages(c).toString().length, tools?.toString()?.length ?: schemaChars(c))
    fun chars(c: Convo, tools: JSONArray? = null): Int = parts(c, tools).sum()
    fun tokens(c: Convo, tools: JSONArray? = null, model: String? = null): Int = Math.round(chars(c, tools) / cpt(model)).toInt()

    /** tools this conversation actually needs: core tools + tools called recently */
    fun slimTools(c: Convo, tools: JSONArray): JSONArray {
        val used = HashSet<String>()
        val n = c.messages.length()
        for (i in maxOf(0, n - 30) until n) {
            val tc = c.messages.getJSONObject(i).optJSONArray("tool_calls") ?: continue
            for (k in 0 until tc.length()) tc.optJSONObject(k)?.optJSONObject("function")?.optString("name")?.let { used.add(it) }
        }
        val out = JSONArray()
        for (i in 0 until tools.length()) { val sc = tools.optJSONObject(i) ?: continue; val name = sc.optJSONObject("function")?.optString("name") ?: continue; if (name in CORE_TOOLS || name in used) out.put(sc) }
        return out
    }

    private fun filter(tools: JSONArray, re: Regex): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) { val sc = tools.optJSONObject(i) ?: continue; if (re.matches(sc.optJSONObject("function")?.optString("name") ?: "")) out.put(sc) }
        return out
    }

    /** Learn from what the server reports. Call right after Llm.chat, BEFORE the reply is added to c.messages. */
    fun calibrate(c: Convo, tools: JSONArray?, model: String?, promptTokens: Int) {
        if (promptTokens < 500) return
        val ch = chars(c, tools).toDouble() / promptTokens
        if (!(ch > 1 && ch < 8)) return
        val m = map("ctx.cpt"); val key = model(model); val old = m.optDouble(key, 0.0)
        m.put(key, if (old > 0) old * 0.7 + ch * 0.3 else ch)
        Prefs.put("ctx.cpt", m.toString())
        val s = state(c); s.lastPrompt = promptTokens; s.lastPromptAt = System.currentTimeMillis()
    }

    private fun msgOf(e: Throwable?): String = e?.message ?: e?.toString() ?: ""

    fun isOverflow(e: Throwable?): Boolean { val t = msgOf(e); return OVERFLOW_RE.containsMatchIn(t) && !NOT_OVERFLOW_RE.containsMatchIn(t) }

    /** Reads the window size from an overflow error and remembers it for the model. Returns it, or 0. */
    fun learn(model: String?, e: Throwable?): Int {
        val t = msgOf(e); var n = 0
        val pats = listOf("maximum context length is (\\d{3,7})", "context (?:length|window|size)(?: limit)?(?: of| is|:)?\\s*\\(?(\\d{3,7})", "(\\d{3,7}) tokens? maximum",
            ">\\s*(\\d{3,7})\\s*maximum", "max(?:imum)?[_ ]?(?:input[_ ]?)?tokens?\\W+(\\d{3,7})", "limit (?:of|is) (\\d{3,7}) tokens", "available context size \\((\\d{3,7})")
        for (p in pats) { val m = Regex(p, RegexOption.IGNORE_CASE).find(t); if (m != null) { n = m.groupValues[1].toInt(); break } }
        if (n >= 1000) { val j = map("ctx.learned"); j.put(model(model), n); Prefs.put("ctx.learned", j.toString()) }
        return n
    }

    @Volatile private var discovered = false
    /** Context sizes from the gateway: OpenRouter-style /models (context_length) and LiteLLM /model/info. Blocking; returns how many were found. */
    fun discover(): Int {
        val h = HashMap<String, String>(); if (Cfg.llmKey.isNotEmpty()) h["Authorization"] = "Bearer ${Cfg.llmKey}"
        val known = map("ctx.known"); var n = 0
        try {
            val r = Http.request(Cfg.llmBase + "/models", headers = h, useCookies = false, timeoutMs = 30000)
            if (r.ok) { val a = JSONObject(r.text()).let { it.optJSONArray("data") ?: it.optJSONArray("models") } ?: JSONArray()
                for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; val id = o.optString("id").ifEmpty { o.optString("name") }; val w = o.optInt("context_length", o.optInt("context_window", 0)); if (id.isNotEmpty() && w > 0) { known.put(id, w); n++ } } }
        } catch (e: Exception) { Diag.i("ctx", "discover /models: ${e.message}") }
        try {
            val r = Http.request(Cfg.llmBase + "/model/info", headers = h, useCookies = false, timeoutMs = 30000)
            if (r.ok) { val a = JSONObject(r.text()).optJSONArray("data") ?: JSONArray()
                for (i in 0 until a.length()) { val d = a.optJSONObject(i) ?: continue; val mi = d.optJSONObject("model_info") ?: JSONObject(); val w = mi.optInt("max_input_tokens", 0).takeIf { it > 0 } ?: mi.optInt("max_tokens", 0); val name = d.optString("model_name"); if (name.isNotEmpty() && w > 0) { known.put(name, w); n++ } } }
        } catch (e: Exception) { Diag.i("ctx", "discover /model/info: ${e.message}") }
        if (n > 0) Prefs.put("ctx.known", known.toString())
        return n
    }

    // ---------------------------------------------------------------- helpers
    fun text(m: JSONObject): String = when (val c = m.opt("content")) {
        is String -> c
        is JSONArray -> (0 until c.length()).joinToString(" ") { val p = c.optJSONObject(it); when (p?.optString("type")) { "text" -> p.optString("text"); "image_url" -> "[picture]"; else -> "" } }
        else -> ""
    }

    fun isRealUser(m: JSONObject) = m.optString("role") == "user" && !m.optBoolean("summary") && !m.optBoolean("local") && !NOT_REAL_USER_RE.containsMatchIn(text(m))

    /** where the kept tail starts: at a user message K real turns back, the tail using at most ~40 % of the budget.
     *  If even the current turn is bigger than that (long tool work), cut inside it at an assistant message. */
    private fun tailStart(c: Convo, budget: Int, keepTurns: Int, model: String?): Int {
        val M = c.messages; val n = M.length(); val cpt = cpt(model); val maxTail = budget * 0.4; val a0 = anchorEnd(c)
        if (n == 0) return a0
        val sz = { i: Int -> (wire(M.getJSONObject(i))?.toString()?.length ?: 0) / cpt }
        var acc = 0.0; var turns = 0; var cut = n
        for (i in n - 1 downTo a0) {
            acc += sz(i)
            if (isRealUser(M.getJSONObject(i))) { if (turns > 0 && acc > maxTail) break; cut = i; turns++; if (turns >= keepTurns) break }
        }
        var tailTok = 0.0; for (i in cut until n) tailTok += sz(i)
        if (cut <= a0 || cut >= n || tailTok > maxTail) {
            var kk = n; var a = 0.0
            while (kk > a0 + 1 && a + sz(kk - 1) < maxTail) { kk--; a += sz(kk) }
            if (kk >= n) kk = n - 1
            while (kk > a0 && M.getJSONObject(kk).optString("role") == "tool") kk--
            cut = kk
        }
        return maxOf(cut, a0)
    }

    private val FILE_TOOL_RE = Regex("write|patch|edit|save|download|pack|create|move|copy|export", RegexOption.IGNORE_CASE)
    /** Workspace files written or changed by the tool calls in [msgs]. */
    fun filesFrom(msgs: List<JSONObject>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in msgs) { val tc = m.optJSONArray("tool_calls") ?: continue
            for (i in 0 until tc.length()) { val f = tc.optJSONObject(i)?.optJSONObject("function") ?: continue
                if (!FILE_TOOL_RE.containsMatchIn(f.optString("name"))) continue
                val a = LoopUtil.jsonTry(f.optString("arguments")) as? JSONObject ?: continue
                for (key in listOf("path", "save_as", "dest", "to", "out", "output", "file")) { val v = a.opt(key); if (v is String && v.isNotBlank() && !v.contains("@") && (v.contains("/") || v.contains("."))) out.add(v) } } }
        return out.toList()
    }

    // ---------------------------------------------------------------- stage 1: deterministic shrinking of old material
    private fun shrinkOld(c: Convo, until: Int, aggressive: Boolean): Int {
        val M = c.messages; val from = anchorEnd(c); val end = minOf(until, M.length()); var n = 0
        val lim = if (aggressive) 700 else 1600
        val seen = HashMap<String, MutableList<String>>()
        for (i in end - 1 downTo from) { val m = M.getJSONObject(i); if (m.optString("role") != "assistant") continue; val tc = m.optJSONArray("tool_calls") ?: continue
            for (k in 0 until tc.length()) { val call = tc.optJSONObject(k) ?: continue; val f = call.optJSONObject("function") ?: continue; val name = f.optString("name")
                if (!SUPERSEDABLE.containsMatchIn(name)) continue
                val key = name + "|" + f.optString("arguments", ""); val l = seen[key]; if (l != null) l.add(call.optString("id")) else seen[key] = ArrayList() } }
        val superseded = seen.values.flatten().toHashSet()
        for (i in from until end) {
            val m = M.getJSONObject(i)
            if (m.opt("content") is JSONArray) { m.put("content", text(m) + " [pictures removed from the old context]"); n++; continue }
            if (m.optString("role") != "tool" || m.optBoolean("shrunk")) continue
            val t = m.optString("content", "")
            if (superseded.contains(m.optString("tool_call_id"))) { m.put("content", "[old result — the same call was made again later; see the newer result]"); m.put("shrunk", true); n++; continue }
            if (t.length > lim) {
                val spill = Regex("saved at (\\S+)\\.").find(t)?.groupValues?.get(1)
                m.put("content", "${t.substring(0, Math.round(lim * 0.6).toInt())}\n…[older tool output shortened by the context manager from ${fmt(t.length)} characters${if (spill != null) "; full text in $spill" else ""}]…\n${t.takeLast(Math.round(lim * 0.25).toInt())}")
                m.put("shrunk", true); n++
            }
        }
        return n
    }

    // ---------------------------------------------------------------- stage 3: incremental structured summary
    private fun transcript(msgs: List<JSONObject>, maxChars: Int): List<String> {
        val lines = msgs.mapNotNull { m ->
            if (m.optBoolean("summary")) return@mapNotNull null
            if (m.optString("role") == "tool") return@mapNotNull "[tool result] ${clip(m.optString("content", ""), 1500)}"
            val tc = m.optJSONArray("tool_calls")
            val calls = if (tc != null && tc.length() > 0) " → calls " + (0 until tc.length()).joinToString(", ") { val f = tc.optJSONObject(it)?.optJSONObject("function"); "${f?.optString("name")}(${clip(f?.optString("arguments", "") ?: "", 200)})" } else ""
            "[${m.optString("role")}] ${clip(text(m), if (m.optString("role") == "user") 4000 else 2500)}$calls"
        }.filter { it.isNotEmpty() }
        val chunks = ArrayList<String>(); val cur = StringBuilder()
        for (l in lines) { if (cur.length + l.length > maxChars && cur.isNotEmpty()) { chunks.add(cur.toString()); cur.setLength(0) }; cur.append(l).append("\n") }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks
    }

    const val SUMMARY_SYS = """You maintain the working memory of an AI assistant's long conversation with a user. You get the CURRENT SUMMARY (may be empty) and the NEXT PART of the conversation that is about to be removed from the context. Return the UPDATED SUMMARY that fully replaces the old one, so the assistant can continue the work without the removed messages.
Use exactly these markdown sections (omit a section only if empty):
## Goal and current task
## User's instructions and preferences   (keep exact wording for constraints, formats, names, numbers)
## Done so far
## Key facts and findings   (concrete values: numbers, names, ids, dates, URLs, e-mail/task ids, error messages)
## Files and artifacts   (path — what it contains / its state)
## Decisions and reasons
## Open items and next steps
## Dead ends   (what failed and why, so it is not retried)
Rules: merge, don't append blindly; drop what is obsolete or finished and no longer matters; never invent; keep ids and paths exact; be dense (telegraphic style), at most ~900 words. Write in the language the user writes in. Output only the summary."""

    private fun summarize(prev: String, segment: List<JSONObject>, focus: String, cancel: Cancel?): String {
        var sum = prev
        val chunks = transcript(segment, 45000)
        for ((i, ch) in chunks.withIndex()) {
            cancel?.check()
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SUMMARY_SYS + (if (focus.isNotBlank()) "\nThe user asked to especially keep: $focus" else "")))
                .put(JSONObject().put("role", "user").put("content", "CURRENT SUMMARY:\n${sum.ifEmpty { "(empty)" }}\n\nNEXT PART${if (chunks.size > 1) " (${i + 1}/${chunks.size})" else ""}:\n$ch"))
            val r = Llm.chat(msgs, null, Cfg.fastModel.ifEmpty { Cfg.model }, cancel = cancel, maxTokens = 2200, stream = false)
            val t = r.content.trim()
            if (t.length < 40) throw IllegalStateException("the summary came back empty")
            sum = t
        }
        return sum
    }

    private fun fallbackSummary(prev: String, segment: List<JSONObject>): String {
        val said = segment.filter { it.optString("role") == "assistant" && text(it).isNotBlank() }.takeLast(6).map { "- " + clip(text(it).replace(Regex("\\s+"), " "), 400) }
        return "${if (prev.isNotEmpty()) "$prev\n\n" else ""}## Earlier replies (shortened automatically — the AI summary was not available)\n${said.joinToString("\n").ifEmpty { "- (no text replies)" }}"
    }

    fun anchorsBlock(c: Convo): String {
        val a = state(c)
        val asks = a.asks.takeLast(25).mapIndexed { i, t -> "${i + 1}. $t" }; val files = a.files.takeLast(40)
        return (if (asks.isNotEmpty()) "### Your requests in the removed part (verbatim, oldest first)\n${asks.joinToString("\n")}\n" else "") +
            (if (files.isNotEmpty()) "\n### Files written or changed earlier\n${files.joinToString("\n") { "- $it" }}\n" else "")
    }

    // ---------------------------------------------------------------- main entry points
    private val busy = HashSet<String>()

    /**
     * Before each model request in the loop. Compacts when the context passes CtxCfg.compactAt % of the budget.
     * Never throws except CancelledException (a failure is reported through [note] as "compaction skipped").
     */
    fun auto(c: Convo, tools: JSONArray?, model: String?, cancel: Cancel?, isSub: Boolean, note: (String) -> Unit, progress: ((String) -> Unit)? = null, userText: String = "") {
        if (tools != null) state(c).toolsChars = tools.toString().length
        if (!CtxCfg.autoCompact) return
        try {
            val budget = budget(model); val used = tokens(c, tools, model); val at = minOf(0.95, maxOf(0.4, CtxCfg.compactAt / 100.0))
            if (used < budget * at) return
            compact(c, tools, model, cancel, isSub, note, progress, reason = "${k(used)}k of ${k(budget)}k tokens used", userText = userText)
        } catch (e: CancelledException) { throw e } catch (e: Throwable) {
            Diag.e("ctx", "auto-compaction failed", e)
            if (!isSub) note(Y.t("🗜 compaction skipped: ", "🗜 sıkıştırma atlandı: ") + (e.message ?: e.toString()))
        }
    }

    /** Compacts the conversation. Returns the log entry ({at, before, after, reason, steps}) or null when it did nothing / was busy. */
    fun compact(
        c: Convo, tools: JSONArray?, model: String?, cancel: Cancel?, isSub: Boolean, note: (String) -> Unit, progress: ((String) -> Unit)? = null,
        reason: String = "", force: Boolean = false, aggressive: Boolean = false, focus: String = "", userText: String = ""
    ): JSONObject? {
        synchronized(busy) { if (!busy.add(c.id)) return null }
        val say = { t: String -> if (!isSub) note(t) }
        try {
            val budget = budget(model); val before = tokens(c, tools, model); val target = budget * (if (aggressive) 0.3 else 0.45)
            val steps = ArrayList<String>()
            if (!force && before < target) return null
            if (knownWindow(model) == 0 && !discovered) { discovered = true; Y.bg { discover() } }
            val keep = maxOf(1, CtxCfg.keepTurns)
            var tail = tailStart(c, budget, keep, model)
            // 1) shrink old pictures / superseded / oversized tool outputs
            val n1 = shrinkOld(c, tail, aggressive); if (n1 > 0) steps.add("shortened $n1 old tool output${if (n1 > 1) "s" else ""}")
            // 2) System-1 relevance pruning of what is still large
            val rel = relevance
            if (rel != null && tokens(c, tools, model) > target) {
                val cand = (anchorEnd(c) until tail).map { c.messages.getJSONObject(it) }.filter { it.optString("role") == "tool" && !it.optBoolean("pruned") && it.optString("content", "").length > 500 }.takeLast(24)
                var pr = 0
                for (m in cand) { cancel?.check()
                    val score = try { rel(userText, clip(m.optString("content"), 1200), cancel) } catch (e: CancelledException) { throw e } catch (e: Throwable) { null }
                    if (score != null && score < 1.5) { m.put("content", "[pruned — not relevant to the current goal] ${clip(m.optString("content"), 160)}"); m.put("pruned", true); pr++ } }
                if (pr > 0) steps.add("pruned $pr irrelevant output${if (pr > 1) "s" else ""} (System-1)")
            }
            // 3) fold the oldest turns into the running summary
            if (force || tokens(c, tools, model) > target) {
                tail = tailStart(c, budget, keep, model); val start = anchorEnd(c)
                if (force && tail <= start + 1) { val kk = tailStart(c, budget, 1, model); if (kk > start + 1) tail = kk }
                val segment = (start until tail).map { c.messages.getJSONObject(it) }.filter { !it.optBoolean("local") }
                if (segment.size >= 2) {
                    val A = state(c)
                    for (m in segment) if (isRealUser(m)) A.asks.add(clip(text(m).replace(Regex("\\s+"), " ").trim(), 600).replace(Regex("\n…\\[\\d+ more characters\\]$"), " …"))
                    for (f in filesFrom(segment)) if (!A.files.contains(f)) A.files.add(f)
                    if (A.asks.size > 40) { val keepAsks = A.asks.takeLast(40); A.asks.clear(); A.asks.addAll(keepAsks) }
                    var how = "AI summary"
                    val sum = try {
                        progress?.invoke(Y.t("Compacting the conversation…", "Konuşma sıkıştırılıyor…"))
                        summarize(c.summary, segment, focus, cancel)
                    } catch (e: CancelledException) { throw e } catch (e: Throwable) {
                        how = "automatic summary (AI summary failed: " + clip(e.message ?: e.toString(), 80) + ")"
                        fallbackSummary(c.summary, segment)
                    }
                    c.summary = sum; A.compactions++
                    c.summaryUpTo = tail
                    steps.add("${segment.size} older messages → $how")
                }
            }
            // 4) still too big (e.g. one gigantic recent tool output): shorten recent outputs too
            if (tokens(c, tools, model) > budget * 0.85) { val n4 = shrinkOld(c, maxOf(0, c.messages.length() - 2), true); if (n4 > 0) steps.add("shortened $n4 recent output${if (n4 > 1) "s" else ""}") }
            val after = tokens(c, tools, model)
            val log = JSONObject().put("at", System.currentTimeMillis()).put("before", before).put("after", after).put("reason", reason).put("steps", JSONArray(steps))
            val s = state(c); s.log.put(log); while (s.log.length() > 20) s.log.remove(0)
            if (steps.isNotEmpty()) say(Y.t(
                "🗜 Context compacted: ${k(before)}k → ${k(after)}k tokens (budget ${k(budget)}k${if (reason.isNotEmpty()) " · $reason" else ""}). ${steps.joinToString(" · ")}. Tap the context meter at the top to see the summary.",
                "🗜 Bağlam sıkıştırıldı: ${k(before)}k → ${k(after)}k token (bütçe ${k(budget)}k${if (reason.isNotEmpty()) " · $reason" else ""}). ${steps.joinToString(" · ")}. Özeti görmek için üstteki bağlam göstergesine dokunun."))
            else if (force) say(Y.t("🗜 Nothing to compact yet — the conversation is short.", "🗜 Henüz sıkıştırılacak bir şey yok — konuşma kısa."))
            try { Convos.save(c) } catch (e: Exception) { Diag.e("ctx", "save convo", e) }
            saveState(c)
            return log
        } finally { synchronized(busy) { busy.remove(c.id) } }
    }

    /** "Compact now" from the UI or the /compact command (the caller makes sure no turn is running). */
    fun compactNow(c: Convo, focus: String, cancel: Cancel?, note: (String) -> Unit): JSONObject? =
        compact(c, null, null, cancel, false, note, reason = "requested by you", force = true, focus = focus.trim())

    /**
     * The original loop's request with overflow handling: [ask] sends the request with the given tool list (it must rebuild
     * the messages from [c] each time, since compaction changes them). When the server says the prompt is too long:
     * learn its window, compact hard and retry; then retry once more with only the tools in use; then give up with a
     * clear message. Calibrates the token estimate and records finish_reason in [st]. Returns the result and the tools
     * actually sent (update the turn's allowed tool names when they differ).
     */
    fun chatWithRetry(
        c: Convo, tools: JSONArray, model: String?, cancel: Cancel?, isSub: Boolean, st: LoopState, note: (String) -> Unit,
        progress: ((String) -> Unit)? = null, ask: (JSONArray) -> LlmResult
    ): Pair<LlmResult, JSONArray> {
        var callTools = tools
        val r: LlmResult = try { ask(callTools) } catch (e: CancelledException) { throw e } catch (e: Throwable) {
            if (!isOverflow(e) || st.overflowRetried) throw e
            st.overflowRetried = true
            val w = learn(model, e)
            if (!isSub) note(Y.t("🗜 The model said the conversation is too long${if (w > 0) " (its limit is ${fmt(w)} tokens)" else ""} — compacting and retrying.",
                "🗜 Model konuşmanın çok uzun olduğunu söyledi${if (w > 0) " (sınırı ${fmt(w)} token)" else ""} — sıkıştırılıp yeniden deneniyor."))
            compact(c, tools, model, cancel, isSub, note, progress, reason = "the server rejected the prompt as too long", force = true, aggressive = true, userText = st.userText)
            val win = window(model)
            if (win > 0 && tokens(c, callTools, model) > win * 0.8) {
                callTools = slimTools(c, tools)
                if (!isSub) note(Y.t("🗜 Sending ${callTools.length()} of ${tools.length()} tool definitions so the request fits the model's window.",
                    "🗜 İstek modelin penceresine sığsın diye ${tools.length()} araç tanımından ${callTools.length()} tanesi gönderiliyor."))
            }
            try { ask(callTools) } catch (e2: CancelledException) { throw e2 } catch (e2: Throwable) {
                if (!isOverflow(e2)) throw e2
                callTools = filter(slimTools(c, tools), LAST_RESORT_TOOLS)
                compact(c, callTools, model, cancel, isSub, note, progress, reason = "still too long after the first retry", force = true, aggressive = true, userText = st.userText)
                try { ask(callTools) } catch (e3: CancelledException) { throw e3 } catch (e3: Throwable) {
                    if (isOverflow(e3)) throw LlmException("The model's context window${if (win > 0) " (${fmt(win)} tokens)" else ""} is too small even after compaction. Choose a model with a larger window, or lower \"Turns kept word for word\" in Settings. (${clip(msgOf(e3), 200)})")
                    throw e3
                }
            }
        }
        calibrate(c, callTools, model, r.tokensIn)
        st.lastFinish = r.finish
        saveState(c)
        return Pair(r, callTools)
    }

    // ---------------------------------------------------------------- context meter + details (for the UI)
    /**
     * Everything the original's context meter and panel showed: {est, budget, pct, level ("hi"|"mid"|""), label ("12k/60k"),
     * system, tools, messages (tokens each), cpt, window, windowSource ("set"|"learned"|"known"|""), lastPrompt, lastPromptAt,
     * compactions, compactAt, keepTurns, autoCompact, summary (with anchors, "" when none), log [ {at,before,after,reason,steps} ] newest first,
     * title (the meter's tooltip text)}.
     */
    fun stats(c: Convo, model: String? = null): JSONObject {
        val p = parts(c); val cpt = cpt(model); val est = Math.round((p[0] + p[1] + p[2]) / cpt).toInt()
        val budget = budget(model); val pct = minOf(100, Math.round(est * 100.0 / budget).toInt())
        val w = window(model); val s = state(c)
        val src = when { CtxCfg.window > 0 -> "set"; learnedWindow(model) > 0 -> "learned"; knownWindow(model) > 0 -> "known"; else -> "" }
        val sys = Math.round(p[0] / cpt).toInt(); val tl = Math.round(p[2] / cpt).toInt(); val ms = Math.round(p[1] / cpt).toInt()
        val brk = Y.t("System prompt ≈ ${fmt(sys)} · tool schemas ≈ ${fmt(tl)} · messages ≈ ${fmt(ms)} tokens (sent with every request).",
            "Sistem istemi ≈ ${fmt(sys)} · araç tanımları ≈ ${fmt(tl)} · mesajlar ≈ ${fmt(ms)} token (her istekte gönderilir).")
        val win = if (w > 0) Y.t(" Model window ${fmt(w)} tokens.", " Model penceresi ${fmt(w)} token.")
            else Y.t(" Model window unknown — set it in Settings if compaction starts too late.", " Model penceresi bilinmiyor — sıkıştırma geç başlıyorsa Ayarlar'dan girin.")
        val at = CtxCfg.compactAt
        val title = Y.t("Context: about ${fmt(est)} of ${fmt(budget)} tokens ($pct%). ", "Bağlam: yaklaşık ${fmt(est)} / ${fmt(budget)} token (%$pct). ") + brk + win + " " +
            Y.t("Auto-compaction at $at%. Tap for details.", "Otomatik sıkıştırma %$at'da. Ayrıntı için dokunun.")
        val log = JSONArray(); for (i in s.log.length() - 1 downTo 0) log.put(s.log.get(i))
        return JSONObject().put("est", est).put("budget", budget).put("pct", pct).put("level", if (pct >= 85) "hi" else if (pct >= 60) "mid" else "")
            .put("label", "${k(est)}k/${k(budget)}k").put("system", sys).put("tools", tl).put("messages", ms).put("cpt", cpt)
            .put("window", w).put("windowSource", src).put("lastPrompt", s.lastPrompt).put("lastPromptAt", s.lastPromptAt)
            .put("compactions", s.compactions).put("compactAt", at).put("keepTurns", CtxCfg.keepTurns).put("autoCompact", CtxCfg.autoCompact)
            .put("summary", if (c.summary.isNotBlank()) c.summary + "\n\n" + anchorsBlock(c) else "").put("log", log).put("title", title)
    }
}
