package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Model settings the original had and the phone did not: extra request headers (temperature lives in Cfg). */
object LlmX {
    var extraHeadersJson: String
        get() = Prefs.get("cfg.extraHeaders", "")
        set(v) {
            val t = v.trim()
            if (t.isEmpty()) { Prefs.put("cfg.extraHeaders", ""); return }
            val o = try { JSONObject(t) } catch (e: Exception) { throw IllegalArgumentException(Y.t("Extra headers must be a JSON object, e.g. {\"X-Org\": \"abc\"}", "Ek başlıklar bir JSON nesnesi olmalı, ör. {\"X-Org\": \"abc\"}")) }
            for (k in o.keys()) if (!Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$").matches(k)) throw IllegalArgumentException(Y.t("Not a valid header name: ", "Geçersiz başlık adı: ") + k)
            Prefs.put("cfg.extraHeaders", o.toString())
        }

    fun extraHeaders(): Map<String, String> {
        val t = extraHeadersJson
        if (t.isBlank()) return emptyMap()
        val o = try { JSONObject(t) } catch (e: Exception) { return emptyMap() }
        return o.keys().asSequence().associateWith { o.optString(it) }
    }
}

/**
 * The original's TRACE (all.js 3247): a timeline per conversation of model requests (messages, tools, ms, reply or tool
 * calls, errors) and tool runs, kept with the conversation (so a debug dump still works after a restart), plus
 * per-purpose call counters (STATS) for the System-1 / harness screen.
 */
object Trace {
    /** The conversation whose turn runs on this thread (set by the agent loop). */
    val convo = ThreadLocal<String?>()
    /** What the model call is for ("main", "compact", "review"…); null = derived from the call. */
    val purpose = ThreadLocal<String?>()

    @Volatile var dir: () -> File = { File(Y.app.filesDir, "trace") }
    private const val KEEP = 150
    private val lock = Any()

    private fun file(id: String) = File(dir().apply { mkdirs() }, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun get(id: String): JSONArray = synchronized(lock) { try { file(id).takeIf { it.isFile }?.let { JSONArray(it.readText()) } } catch (_: Exception) { null } ?: JSONArray() }

    fun add(id: String?, e: JSONObject) {
        if (id.isNullOrEmpty()) return
        synchronized(lock) {
            val a = get(id)
            a.put(e.put("t", e.optLong("t", System.currentTimeMillis())))
            val out = if (a.length() > KEEP) JSONArray((a.length() - KEEP until a.length()).map { a.get(it) }) else a
            try { file(id).writeText(out.toString()) } catch (_: Exception) {}
        }
    }

    fun forget(id: String) = synchronized(lock) { file(id).delete(); Unit }

    private fun lastUser(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") { val c = m.opt("content"); return if (c is String) clip(c, 400) else "[multimodal]" }
        }
        return ""
    }

    fun llm(model: String, messages: JSONArray, tools: JSONArray?, r: LlmResult?, error: String?, ms: Long, effort: String?) {
        val p = purpose.get() ?: if (tools != null && tools.length() > 0) "main" else if (effort != null) "side" else "main"
        count(p, r?.tokensIn ?: 0, r?.tokensOut ?: 0)
        val id = convo.get() ?: return
        val e = JSONObject().put("kind", "llm").put("what", "$p · $model").put("purpose", p).put("model", model)
            .put("messages", messages.length()).put("tools", tools?.length() ?: 0).put("ms", ms).put("lastUser", lastUser(messages))
        if (r != null) {
            val calls = r.toolCalls.map { "${it.name}(${clip(it.args, 200)})" }
            e.put("reply", clip(r.content, 600)).put("toolCalls", JSONArray(calls)).put("finish", r.finish ?: "").put("tokensIn", r.tokensIn).put("tokensOut", r.tokensOut)
            if (r.reasoning.isNotEmpty()) e.put("reasoningChars", r.reasoning.length)
            e.put("note", (if (calls.isNotEmpty()) "→ " + r.toolCalls.joinToString(", ") { it.name } else if (r.content.isNotBlank()) clip(r.content, 120) else "EMPTY reply") +
                (if (r.finish != null && r.finish != "stop" && r.finish != "tool_calls") " · finish=${r.finish}" else "") + (if (r.reasoning.isNotEmpty()) " · ${r.reasoning.length} chars hidden reasoning" else ""))
            if (calls.isEmpty() && r.content.isBlank()) e.put("warn", true)
        }
        if (error != null) e.put("error", clip(error, 400))
        add(id, e)
    }

    fun tool(id: String, name: String, args: String, result: String, ok: Boolean, ms: Long, depth: Int) {
        val e = JSONObject().put("kind", "tool").put("what", name + if (depth > 0) " (sub-agent)" else "").put("args", clip(args, 700)).put("ms", ms)
        if (!ok) e.put("error", clip(result, 400)) else e.put("note", clip(result.lineSequence().firstOrNull() ?: "", 160))
        add(id, e)
    }

    // ---------------------------------------------------------------- counters (STATS)
    private fun stats(): JSONObject = try { JSONObject(Prefs.get("stats.llm", "{}")) } catch (_: Exception) { JSONObject() }

    @Synchronized private fun count(p: String, tin: Int, tout: Int) {
        val s = stats()
        s.put("llmCalls", s.optInt("llmCalls") + 1).put("tokensIn", s.optLong("tokensIn") + tin).put("tokensOut", s.optLong("tokensOut") + tout)
        val by = s.optJSONObject("byPurpose") ?: JSONObject()
        by.put(p, by.optInt(p) + 1); s.put("byPurpose", by)
        if (!s.has("since")) s.put("since", System.currentTimeMillis())
        Prefs.put("stats.llm", s.toString())
    }

    fun statsJson(): JSONObject = stats()
    fun resetStats() { Prefs.put("stats.llm", JSONObject().put("since", System.currentTimeMillis()).toString()) }
}
