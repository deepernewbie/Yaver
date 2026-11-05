package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The debug dump of one conversation (port of the original's Debug.build / markdown / redact, all.js 3314-3375):
 * config summary (keys only as "set"), session flags, problems, memory diagnostics (notes, tiers, what is in the
 * prompt, rules, topics and keys, what each message got from memory), the timeline of model requests and tools
 * (Trace), the conversation, LLM request detail, System-1 decisions, the system prompt and a disk listing. Every
 * secret is redacted. full = long clips, short = very short clips.
 */
object DebugDump {
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun time(t: Long) = tf.format(Date(t))

    fun redact(text: String, secrets: List<String> = secretValues()): String {
        var s = text
        for (v in secrets) if (v.length > 4) s = s.replace(v, "[REDACTED]")
        return s.replace(Regex("\\b(sk|pk|rk|tsk|or)-[A-Za-z0-9_\\-]{12,}"), "[REDACTED-KEY]")
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-]{10,}"), "Bearer [REDACTED]")
            .replace(Regex("(?i)(\"?(api[_-]?key|authorization|token|secret|password)\"?\\s*[:=]\\s*\")[^\"]{6,}\""), "$1[REDACTED]\"")
    }

    private fun secretValues(): List<String> = try { StateIO.SECRET_KEYS.map { Secure.dec(Prefs.get("sec.$it", "")) }.filter { it.isNotEmpty() } } catch (_: Throwable) { emptyList() }

    fun build(c: Convo, full: Boolean = false, short: Boolean = false): JSONObject {
        val cut = if (full) 20000 else if (short) 300 else 1500
        val st = try { ConvoState.get(c.id) } catch (_: Throwable) { JSONObject() }
        val plan = try { Planning.state(c.id) } catch (_: Throwable) { JSONObject() }
        val config = JSONObject()
            .put("llm", JSONObject().put("baseUrl", Cfg.llmBase).put("model", Cfg.model).put("fastModel", Cfg.fastModel).put("visionModel", Cfg.visionModel)
                .put("stream", Cfg.stream).put("maxIterations", Cfg.maxSteps).put("contextBudget", Cfg.contextTokens).put("maxOut", Cfg.maxOut).put("temperature", Cfg.temperature)
                .put("extraHeaders", LlmX.extraHeaders().keys.joinToString(", ")))
            .put("s1", try { S1Cfg.toJson().apply { remove("key") } } catch (_: Throwable) { JSONObject() })
            .put("web", JSONObject().put("searxng", Cfg.searxng).put("brave", Cfg.braveKey.isNotEmpty()).put("tavily", Cfg.tavilyKey.isNotEmpty()))
            .put("permissions", Permissions.mode).put("lang", Cfg.lang)
            .put("keysSet", JSONObject().put("llm", Cfg.llmKey.isNotEmpty()).put("s1", try { S1Cfg.key.isNotEmpty() } catch (_: Throwable) { false }).put("mail", Cfg.mailPass.isNotEmpty()))
        val session = JSONObject().put("id", c.id).put("title", c.title).put("created", c.created).put("messages", c.messages.length())
            .put("toolsets", st.optJSONArray("toolsets") ?: JSONArray()).put("planMode", plan.optBoolean("planMode")).put("goal", plan.opt("goal") ?: JSONObject.NULL)
            .put("todos", plan.optJSONArray("todos") ?: JSONArray()).put("summaryUpTo", c.summaryUpTo).put("lang", c.lang ?: "")
        val msgs = JSONArray()
        for (i in 0 until c.messages.length()) {
            val m = c.messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = m.opt("content").let { if (it is String) it else it?.toString() ?: "" }
            val o = JSONObject().put("i", i).put("role", role).put("content", clip(content, if (role == "tool") cut else maxOf(cut, 3000)))
            m.optJSONArray("tool_calls")?.let { tc -> o.put("tool_calls", JSONArray((0 until tc.length()).map { k -> val t = tc.getJSONObject(k); val f = t.optJSONObject("function") ?: JSONObject(); JSONObject().put("id", t.optString("id")).put("name", f.optString("name")).put("args", clip(f.optString("arguments"), if (full) 8000 else if (short) 200 else 800)) })) }
            if (m.has("tool_call_id")) o.put("tool_call_id", m.optString("tool_call_id"))
            if (m.has("ms")) o.put("ms", m.optLong("ms"))
            m.optJSONArray("s1")?.let { o.put("s1", it) }
            m.optJSONObject("mem")?.let { o.put("mem", it) }
            if (m.optBoolean("harness")) o.put("harness", true)
            msgs.put(o)
        }
        val trace = try { Trace.get(c.id) } catch (_: Throwable) { JSONArray() }
        val s1 = JSONArray()
        try {
            val log = S1.log(200)
            for (i in 0 until log.length()) { val e = log.getJSONObject(i); if (e.optLong("t") >= c.created && s1.length() < 60) s1.put(JSONObject().put("t", e.optLong("t")).put("gate", e.optString("gate")).put("ms", e.optLong("ms")).put("answers", e.opt("answers") ?: JSONObject.NULL).put("error", e.optString("error"))) }
        } catch (_: Throwable) {}
        val memory = try {
            val pick = MemoryX.promptPick().toSet()
            fun cl(t: String) = if (full) t else clip(t, 160)
            val notes = JSONArray()
            for (t in listOf("user", "memory")) for (e in MemoryX.entries(t)) notes.put(JSONObject().put("text", cl(e)).put("where", if (t == "user") "about you" else "memory")
                .put("tier", if (t == "user") "active" else MemoryX.tier(e)).put("inPrompt", t == "user" || e in pick).put("rule", Rules.isRule(t, e))
                .put("topic", MemoryX.topic(t, e)).put("keys", JSONArray(MemoryX.keys(t, e))))
            val turns = JSONArray()
            for (i in 0 until c.messages.length()) c.messages.optJSONObject(i)?.optJSONObject("mem")?.let { turns.put(JSONObject(it.toString()).put("i", i)) }
            JSONObject().put("profileChars", MemoryX.entries("user").joinToString(MemoryX.DELIM).length).put("profileMax", MemoryXCfg.userChars)
                .put("promptChars", pick.joinToString("").length).put("promptMax", MemoryXCfg.promptNotes).put("pendingIndex", MemoryX.pending())
                .put("notes", notes).put("turns", turns)
        } catch (e: Throwable) { JSONObject().put("error", e.message ?: e.toString()) }
        val problems = JSONArray()
        try { Diag.text().lineSequence().filter { it.contains(" E/") }.toList().takeLast(40).forEach { problems.put(it) } } catch (_: Throwable) {}
        val system = c.system.ifBlank { try { Prompt.forRequest(c) } catch (_: Throwable) { "" } }
        val disk = JSONArray()
        try { for (p in VPath.walk(VPath.WS).take(200)) disk.put("$p (${Workspace.fmtBytes(VPath.file(p).length())})") } catch (_: Throwable) {}
        return JSONObject().put("app", "yaver (Android)").put("exported", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            .put("device", try { android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE } catch (_: Throwable) { "" })
            .put("config", config).put("session", session).put("systemPrompt", clip(system, if (full) 20000 else 2500)).put("messages", msgs)
            .put("trace", trace).put("s1Log", s1).put("memory", memory).put("errors", problems).put("stats", try { Trace.statsJson() } catch (_: Throwable) { JSONObject() }).put("disk", disk)
    }

    fun markdown(d: JSONObject): String {
        val L = ArrayList<String>()
        val cfg = d.optJSONObject("config") ?: JSONObject(); val llm = cfg.optJSONObject("llm") ?: JSONObject(); val s1 = cfg.optJSONObject("s1") ?: JSONObject()
        val keys = cfg.optJSONObject("keysSet") ?: JSONObject(); val ses = d.optJSONObject("session") ?: JSONObject()
        L.add("# yaver debug dump"); L.add("")
        L.add("- app: ${d.optString("app")} · exported ${d.optString("exported")}"); L.add("- device: ${d.optString("device")}")
        L.add("- LLM: ${llm.optString("model").ifEmpty { "(none)" }} at ${llm.optString("baseUrl")}${if (llm.optString("fastModel").isNotEmpty()) " · fast: " + llm.optString("fastModel") else ""} · stream ${llm.optBoolean("stream")} · temperature ${llm.opt("temperature")} · max steps ${llm.opt("maxIterations")} · key set: ${keys.optBoolean("llm")}${if (llm.optString("extraHeaders").isNotEmpty()) " · extra headers: " + llm.optString("extraHeaders") else ""}")
        L.add("- System-1: ${s1.optString("backend", "off")}${if (s1.optString("backend") != "off") " at ${s1.optString("url")} (threshold ${s1.opt("threshold")}) · key set: ${keys.optBoolean("s1")}" else ""}")
        L.add("- permissions: ${cfg.optString("permissions")} · language: ${cfg.optString("lang")}")
        val goal = ses.optJSONObject("goal")
        L.add("- conversation: \"${ses.optString("title")}\" · ${ses.optInt("messages")} messages · toolsets: ${(ses.optJSONArray("toolsets") ?: JSONArray()).let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }}" +
            (if (goal != null) " · goal: ${goal.optString("status")} ${goal.optInt("round")}/${goal.optInt("maxRounds")}" else "") + (if (ses.optBoolean("planMode")) " · plan mode" else "") +
            ((ses.optJSONArray("todos")?.length() ?: 0).let { if (it > 0) " · $it todos" else "" }))
        L.add("")
        val trace = d.optJSONArray("trace") ?: JSONArray(); val errs = d.optJSONArray("errors") ?: JSONArray()
        val bad = (0 until trace.length()).map { trace.getJSONObject(it) }.filter { it.has("error") || it.optBoolean("warn") }
        L.add("## Problems (${bad.size + errs.length()})")
        if (bad.isEmpty() && errs.length() == 0) L.add("none recorded")
        for (e in bad) L.add("- ${time(e.optLong("t"))} [${e.optString("kind")}] ${e.optString("what")}: ${e.optString("error").ifEmpty { e.optString("note") }}")
        for (i in 0 until errs.length()) L.add("- ${errs.optString(i)}")
        L.add("")
        val M = d.optJSONObject("memory")
        if (M != null && !M.has("error")) {
            val ns = M.optJSONArray("notes") ?: JSONArray(); val n = (0 until ns.length()).map { ns.getJSONObject(it) }
            val mem = n.filter { it.optString("where") == "memory" }
            L.add("## Memory")
            L.add("- about you (always in the prompt): ${n.count { it.optString("where") == "about you" }} entries · ${M.optInt("profileChars")}/${M.optInt("profileMax")} chars")
            L.add("- memory notes: ${mem.size} (${mem.count { it.optString("tier") == "active" }} active, ${mem.count { it.optString("tier") == "draft" }} draft) · in every prompt: ${mem.count { it.optBoolean("inPrompt") }}/${M.optInt("promptMax")} (${M.optInt("promptChars")} chars) · found only by search: ${n.count { !it.optBoolean("inPrompt") }}")
            L.add("- standing rules: ${n.count { it.optBoolean("rule") }} · search index: ${n.size - M.optInt("pendingIndex")}/${n.size} notes have topics and keys")
            L.add(""); L.add("### Notes")
            for (x in n) {
                val ks = x.optJSONArray("keys") ?: JSONArray()
                L.add("- [${x.optString("where")}${if (x.optString("where") == "memory") " · " + x.optString("tier") else ""}${if (x.optBoolean("inPrompt")) " · IN PROMPT" else " · search only"}${if (x.optBoolean("rule")) " · RULE" else ""} · ${x.optString("topic")}] ${x.optString("text")}" +
                    (if (ks.length() > 0) "\n  keys: " + (0 until ks.length()).joinToString(", ") { ks.optString(it) } else "\n  keys: (not indexed yet)"))
            }
            L.add(""); L.add("### What each message got from memory")
            val turns = M.optJSONArray("turns") ?: JSONArray()
            if (turns.length() == 0) L.add("nothing was attached by search in this conversation (notes may still have been in the prompt)")
            for (i in 0 until turns.length()) { val t = turns.getJSONObject(i); val rel = t.optJSONArray("rel") ?: JSONArray()
                L.add("- [${t.optInt("i")}] ${t.optString("how")}${if (t.has("pick")) " · ${t.optInt("pick")} notes already in the prompt" else ""} · attached: ${if (rel.length() > 0) (0 until rel.length()).joinToString(" | ") { "\"" + rel.optString(it) + "\"" } else "none"}") }
            L.add("")
        } else if (M != null) { L.add("## Memory"); L.add("error: " + M.optString("error")); L.add("") }
        L.add("## Timeline")
        for (i in 0 until trace.length()) { val e = trace.getJSONObject(i)
            L.add("- ${time(e.optLong("t"))} ${e.optString("kind").padEnd(4)} ${e.optString("what")}${if (e.has("ms")) " · ${e.optLong("ms")} ms" else ""}${if (e.has("error")) " · ERROR " + clip(e.optString("error"), 200) else if (e.has("note")) " · " + e.optString("note") else ""}") }
        L.add(""); L.add("## Conversation")
        val msgs = d.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgs.length()) { val m = msgs.getJSONObject(i)
            if (m.optString("role") == "tool") { L.add("### [${m.optInt("i")}] tool result (${m.optString("tool_call_id")})${if (m.has("ms")) " · ${m.optLong("ms")} ms" else ""}"); L.add("```"); L.add(m.optString("content")); L.add("```") }
            else {
                val s1c = m.optJSONArray("s1"); val mem = m.optJSONObject("mem")
                L.add("### [${m.optInt("i")}] ${m.optString("role")}${if (m.optBoolean("harness")) " (harness)" else ""}${if (s1c != null) " · S1: " + (0 until s1c.length()).joinToString(" | ") { k -> s1c.optJSONArray(k)?.optString(1) ?: s1c.optString(k) } else ""}${if (mem != null) " · memory: ${mem.optJSONArray("rel")?.length() ?: 0} note(s) attached (${mem.optString("how")})" else ""}")
                if (m.optString("content").isNotEmpty()) L.add(m.optString("content"))
                m.optJSONArray("tool_calls")?.let { tc -> for (k in 0 until tc.length()) { val t = tc.getJSONObject(k); L.add("→ ${t.optString("name")}(${t.optString("args")})") } }
            } }
        L.add(""); L.add("## LLM requests (detail)")
        for (i in 0 until trace.length()) { val e = trace.getJSONObject(i); if (e.optString("kind") != "llm") continue
            val tc = e.optJSONArray("toolCalls") ?: JSONArray()
            L.add("- ${time(e.optLong("t"))} ${e.optString("what")} · ${e.optInt("messages")} msgs · ${e.optInt("tools")} tools · ${e.optLong("ms")} ms${if (e.has("tokensIn")) " · ${e.optInt("tokensIn")}→${e.optInt("tokensOut")} tokens" else ""}${if (e.has("error")) " · ERROR " + e.optString("error") else ""}" +
                (if (tc.length() > 0) "\n  calls: " + (0 until tc.length()).joinToString("; ") { tc.optString(it) } else if (e.optString("reply").isNotEmpty()) "\n  reply: " + clip(e.optString("reply"), 300).replace("\n", " ") else "") +
                (if (e.optString("lastUser").isNotEmpty()) "\n  last user: " + clip(e.optString("lastUser"), 200).replace("\n", " ") else "")) }
        val s1l = d.optJSONArray("s1Log") ?: JSONArray()
        if (s1l.length() > 0) { L.add(""); L.add("## System-1 decisions")
            for (i in 0 until s1l.length()) { val e = s1l.getJSONObject(i); L.add("- ${time(e.optLong("t"))} ${e.optString("gate")} · ${e.optLong("ms")} ms${if (e.optString("error").isNotEmpty()) " · ERROR " + e.optString("error") else " · " + clip(e.opt("answers")?.toString() ?: "", 400)}") } }
        L.add(""); L.add("## System prompt (start)"); L.add("```"); L.add(clip(d.optString("systemPrompt"), 1500)); L.add("```")
        L.add(""); L.add("## Config"); L.add("```json")
        L.add(JSONObject().put("config", cfg).put("stats", d.optJSONObject("stats") ?: JSONObject()).put("disk", JSONObject().put("files", d.optJSONArray("disk")?.length() ?: 0)).toString(1))
        L.add("```")
        val disk = d.optJSONArray("disk") ?: JSONArray()
        if (disk.length() > 0) { L.add(""); L.add("## Workspace files"); for (i in 0 until disk.length()) L.add("- " + disk.optString(i)) }
        return redact(L.joinToString("\n"))
    }

    /** For the sheet: the markdown, its size line, and paths of saved .md / .json copies when [save]. */
    fun forUi(c: Convo, full: Boolean, short: Boolean, save: Boolean): JSONObject {
        val d = build(c, full, short)
        val md = markdown(d)
        val tr = d.optJSONArray("trace") ?: JSONArray()
        val probs = (0 until tr.length()).count { tr.getJSONObject(it).has("error") } + (d.optJSONArray("errors")?.length() ?: 0)
        val o = JSONObject().put("md", md).put("chars", md.length).put("messages", c.messages.length()).put("events", tr.length()).put("problems", probs)
        if (save) {
            val base = "yaver-debug-${c.id}"
            o.put("mdPath", Workspace.write("exports", "$base.md", md.toByteArray()))
            o.put("jsonPath", Workspace.write("exports", "$base.json", redact(build(c, true, false).toString(2)).toByteArray()))
        }
        return o
    }
}
