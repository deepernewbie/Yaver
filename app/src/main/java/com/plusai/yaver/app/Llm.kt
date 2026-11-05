package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject

class ToolCall(val id: String, val name: String, val args: String)

class LlmResult(
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val finish: String?,
    val tokensIn: Int,
    val tokensOut: Int
)

class LlmException(msg: String, val status: Int = 0) : RuntimeException(msg)

/** OpenAI-compatible chat completions (OpenRouter, OpenAI, Groq, Together, Ollama, LM Studio…), streaming and tool calls. */
object Llm {
    /** Live progress of the current streamed call on this thread: reasoning chars, tool being prepared, argument chars, number of calls. */
    val info = ThreadLocal<((Int, String, Int, Int) -> Unit)?>()
    /** Called before a call is sent again (network retry / overflow retry), so live text already shown is cleared. */
    val onRetry = ThreadLocal<(() -> Unit)?>()

    /** The server accepts stream_options.include_usage (token counts while streaming); off after an HTTP 400. */
    @Volatile private var usageOk = true
    /** The server accepts tool_choice; off after an HTTP 400 caused by it (otherwise every tool request fails once first). */
    @Volatile private var toolChoiceOk = true
    /** The server accepts a reasoning-effort field; off after an HTTP 400/422 caused by it. */
    @Volatile private var effortOk = true

    /** Test seam: when set, [chat] returns this instead of calling the service (JVM tests of the agent and the learning path). */
    @Volatile var fake: ((messages: JSONArray, tools: JSONArray?, model: String, maxTokens: Int) -> LlmResult)? = null

    fun headers(): Map<String, String> {
        val h = HashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "text/event-stream, application/json"
        val k = Cfg.llmKey
        if (k.isNotEmpty()) h["Authorization"] = "Bearer $k"
        if (Cfg.llmBase.contains("openrouter")) {
            h["HTTP-Referer"] = "https://plusai.yaver.app"
            h["X-Title"] = "yaver"
        }
        // Settings → Model → Extra headers (JSON object), for gateways that want their own headers
        try { for ((k, v) in LlmX.extraHeaders()) h[k] = v } catch (_: Throwable) {}
        return h
    }

    fun models(): List<String> {
        val r = Http.request(Cfg.llmBase + "/models", headers = headers() - "Content-Type", useCookies = false, timeoutMs = 30000)
        if (!r.ok) throw LlmException("HTTP ${r.code}: ${r.text().take(200)}", r.code)
        val j = JSONObject(r.text())
        val arr = j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val id = o?.optString("id")?.ifEmpty { o.optString("name") } ?: arr.optString(i)
            if (!id.isNullOrEmpty()) out.add(id)
        }
        return out.sorted()
    }

    fun chat(
        messages: JSONArray, tools: JSONArray?, model: String? = null, onDelta: ((String) -> Unit)? = null,
        onThink: ((String) -> Unit)? = null, cancel: Cancel? = null, maxTokens: Int = 0, temperature: Double? = null, stream: Boolean = Cfg.stream,
        /** "low" for side calls (reviews, follow-ups, summaries): reasoning models otherwise spend the whole budget thinking. */
        effort: String? = null
    ): LlmResult {
        // every call is recorded for the debug dump (per conversation) and the per-purpose counters (Trace.kt)
        val t0 = System.currentTimeMillis()
        val m0 = (model ?: "").ifEmpty { Cfg.model }
        try {
            val r = chat0(messages, tools, model, onDelta, onThink, cancel, maxTokens, temperature, stream, effort)
            try { Trace.llm(m0, messages, tools, r, null, System.currentTimeMillis() - t0, effort) } catch (_: Throwable) {}
            return r
        } catch (e: Throwable) {
            try { Trace.llm(m0, messages, tools, null, if (e is CancelledException) "aborted by user" else (e.message ?: e.toString()), System.currentTimeMillis() - t0, effort) } catch (_: Throwable) {}
            throw e
        }
    }

    private fun chat0(
        messages: JSONArray, tools: JSONArray?, model: String?, onDelta: ((String) -> Unit)?,
        onThink: ((String) -> Unit)?, cancel: Cancel?, maxTokens: Int, temperature: Double?, stream: Boolean, effort: String?
    ): LlmResult {
        val m = (model ?: "").ifEmpty { Cfg.model }
        fake?.let { return it(messages, tools, m, maxTokens) }
        if (m.isEmpty()) throw LlmException(Y.t("No model chosen. Open Settings → Model and pick one.", "Model seçilmemiş. Ayarlar → Model bölümünden bir model seçin."))
        val body = JSONObject()
        body.put("model", m)
        body.put("messages", messages)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            if (toolChoiceOk) body.put("tool_choice", "auto")
        }
        body.put("temperature", temperature ?: Cfg.temperature)
        if (maxTokens > 0) body.put("max_tokens", maxTokens)
        if (effort != null && effortOk) { if (Cfg.llmBase.contains("openrouter")) body.put("reasoning", JSONObject().put("effort", effort)) else body.put("reasoning_effort", effort) }
        if (stream) { body.put("stream", true); if (usageOk) body.put("stream_options", JSONObject().put("include_usage", true)) }
        val waits = intArrayOf(2, 5, 12, 25)
        var attempt = 0
        var droppedUsage = false
        var lastErr: Throwable? = null
        while (true) {
            cancel?.check()
            try {
                val r = once(body, stream, onDelta, onThink, cancel)
                if (droppedUsage) usageOk = false
                return r
            } catch (e: LlmException) {
                lastErr = e
                // a prompt that is too long is not fixed by dropping optional fields: hand it to the compactor right away
                if (Compactor.isOverflow(e)) throw e
                val retry = e.status == 0 || e.status == 408 || e.status == 429 || e.status >= 500
                val msg = (e.message ?: "").lowercase()
                if (e.status == 400 && body.has("stream_options")) { body.remove("stream_options"); droppedUsage = true; continue }
                if ((e.status == 400 || e.status == 422) && (body.has("reasoning") || body.has("reasoning_effort"))) {
                    body.remove("reasoning"); body.remove("reasoning_effort")
                    if (msg.contains("reasoning") || msg.contains("effort")) { effortOk = false; Diag.i("llm", "reasoning effort not accepted — sent without it from now on") }
                    continue
                }
                if (e.status == 400 && body.has("tool_choice")) { body.remove("tool_choice"); toolChoiceOk = false; Diag.i("llm", "tool_choice not accepted — sent without it from now on"); continue }
                if (!retry || attempt >= waits.size) throw e
            } catch (e: CancelledException) {
                throw e
            } catch (e: java.io.IOException) {
                if (cancel?.cancelled == true) throw CancelledException()
                lastErr = e
                if (attempt >= waits.size) throw LlmException(Y.t("Network error talking to the model: ", "Modele bağlanırken ağ hatası: ") + (e.message ?: e.toString()))
            }
            val w = waits[attempt++]
            var slept = 0
            while (slept < w * 1000) { cancel?.check(); Thread.sleep(250); slept += 250 }
            android.util.Log.w("yaver", "LLM retry $attempt after ${lastErr?.message}")
            try { onRetry.get()?.invoke() } catch (_: Throwable) {}
        }
    }

    private fun once(body: JSONObject, stream: Boolean, onDelta: ((String) -> Unit)?, onThink: ((String) -> Unit)?, cancel: Cancel?): LlmResult {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = ArrayList<Array<String>>() // in order: [id, name, args]
        var finish: String? = null
        var done = false
        var tin = 0
        var tout = 0
        var streamErr: String? = null
        val (code, raw) = Http.streamLines(Cfg.llmBase + "/chat/completions", headers(), body.toString().toByteArray(), cancel) { line ->
            if (!line.startsWith("data:")) return@streamLines
            val d = line.substring(5).trim()
            if (d == "[DONE]") { done = true; return@streamLines }
            if (d.isEmpty()) return@streamLines
            val j = try { JSONObject(d) } catch (e: Exception) { return@streamLines }
            j.optJSONObject("error")?.let { streamErr = it.optString("message", it.toString()); return@streamLines }
            j.optJSONObject("usage")?.let { tin = it.optInt("prompt_tokens", tin); tout = it.optInt("completion_tokens", tout) }
            val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: return@streamLines
            ch.optString("finish_reason", "").let { if (it.isNotEmpty() && it != "null") finish = it }
            val delta = ch.optJSONObject("delta") ?: return@streamLines
            val c = delta.optString("content", "")
            if (c.isNotEmpty() && c != "null") { content.append(c); onDelta?.invoke(c) }
            val r = delta.optString("reasoning", "").ifEmpty { delta.optString("reasoning_content", "") }
            if (r.isNotEmpty() && r != "null") { reasoning.append(r); onThink?.invoke(r); info.get()?.invoke(reasoning.length, calls.lastOrNull()?.get(1) ?: "", calls.sumOf { it[2].length }, calls.size) }
            val tc = delta.optJSONArray("tool_calls")
            if (tc != null) for (i in 0 until tc.length()) {
                val t = tc.optJSONObject(i) ?: continue
                val slot = toolChunk(calls, t)
                info.get()?.invoke(reasoning.length, slot[1], calls.sumOf { it[2].length }, calls.size)
            }
        }
        if (code !in 200..299) {
            val msg = try { val j = JSONObject(raw); j.optJSONObject("error")?.let { e -> e.optString("message") + (e.optJSONObject("metadata")?.optString("raw")?.let { " — $it" } ?: "") } ?: raw } catch (e: Exception) { raw }
            throw LlmException("LLM HTTP $code: ${msg.take(500)}", code)
        }
        streamErr?.let { throw LlmException("LLM: $it", 502) }
        if (raw.isNotEmpty()) {
            // the server answered without streaming
            val j = try { JSONObject(raw) } catch (e: Exception) { throw LlmException("LLM answered something that is not JSON: ${raw.take(200)}", 502) }
            j.optJSONObject("error")?.let { throw LlmException("LLM: " + it.optString("message", it.toString()), 502) }
            val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: throw LlmException("LLM gave no answer: ${raw.take(200)}", 502)
            val msg = ch.optJSONObject("message") ?: JSONObject()
            val text = msg.optString("content", "").let { if (it == "null") "" else it }
            if (text.isNotEmpty()) onDelta?.invoke(text)
            val list = ArrayList<ToolCall>()
            val tc = msg.optJSONArray("tool_calls")
            if (tc != null) for (i in 0 until tc.length()) {
                val t = tc.getJSONObject(i)
                val f = t.optJSONObject("function") ?: continue
                val a = f.opt("arguments")
                list.add(ToolCall(t.optString("id").ifEmpty { "call_" + System.nanoTime() }, f.optString("name"), when (a) { is String -> a.ifEmpty { "{}" }; is JSONObject -> a.toString(); else -> "{}" }))
            }
            val u = j.optJSONObject("usage")
            val rsn = listOf("reasoning", "reasoning_content").map { k -> msg.opt(k) }.firstOrNull { it is String && it.isNotEmpty() } as String? ?: ""
            val fin = ch.opt("finish_reason").let { if (it is String && it.isNotEmpty()) it else null }
            return LlmResult(text, rsn, list.filter { it.name.isNotEmpty() }, fin, u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0)
        }
        val list = calls.filter { it[1].isNotEmpty() }.map { ToolCall(it[0].ifEmpty { "call_" + System.nanoTime() }, it[1], it[2].ifEmpty { "{}" }) }
        val dropped = calls.count { it[1].isEmpty() && it[2].isNotEmpty() }
        if (dropped > 0) Diag.i("llm", "$dropped streamed tool call(s) without a name were dropped")
        // the stream ended without finish_reason and without [DONE]: the connection was cut — treat it as a cut-off reply
        if (finish == null && !done && (content.isNotEmpty() || list.isNotEmpty())) { finish = "length"; Diag.i("llm", "stream ended without finish_reason or [DONE] — treated as cut off") }
        return LlmResult(content.toString(), reasoning.toString(), list, finish, tin, tout)
    }

    /**
     * The slot a streamed tool-call chunk belongs to (as the original): its `index` when present; else the call with
     * the same id; a new id starts a new call; a chunk with neither continues the last call.
     */
    fun toolChunk(calls: ArrayList<Array<String>>, t: JSONObject): Array<String> {
        val slot = toolSlot(calls, t)
        val f = t.optJSONObject("function") ?: return slot
        val n = f.opt("name").let { if (it is String && it != "null") it else "" }
        // some providers repeat the whole name in every chunk: do not double it
        if (n.isNotEmpty()) slot[1] = if (slot[1].isEmpty() || slot[1] == n) n else slot[1] + n
        when (val a = f.opt("arguments")) { is String -> if (a != "null") slot[2] += a; is JSONObject, is JSONArray -> slot[2] += a.toString() }
        return slot
    }

    fun toolSlot(calls: ArrayList<Array<String>>, t: JSONObject): Array<String> {
        val id = t.opt("id").let { if (it is String && it != "null") it else "" }
        val k = when {
            t.has("index") && !t.isNull("index") -> t.optInt("index", 0).coerceIn(0, 63)
            id.isNotEmpty() -> calls.indexOfFirst { it[0] == id }.let { if (it >= 0) it else calls.size }
            else -> maxOf(0, calls.size - 1)
        }
        while (calls.size <= k) calls.add(arrayOf("", "", ""))
        val slot = calls[k]
        if (id.isNotEmpty() && slot[0].isEmpty()) slot[0] = id
        return slot
    }

    /** One quick question to the (fast) model without tools. */
    fun ask(system: String, user: String, cancel: Cancel? = null, maxTokens: Int = 1500, fast: Boolean = true): String {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return text(msgs, if (fast) Cfg.fastModel else null, cancel, maxTokens, fast)
    }

    /**
     * A side call (no tools, no streaming, low reasoning effort) that must return visible text. A reasoning model that
     * spends the whole budget thinking returns no content: its hidden reasoning is NOT an answer — retry once with
     * room, then with the main model. Returns "" when nothing came back.
     */
    fun text(msgs: JSONArray, model: String?, cancel: Cancel? = null, maxTokens: Int = 1500, fallbackToMain: Boolean = true, purpose: String = "side call"): String {
        val m = (model ?: "").ifEmpty { Cfg.model }
        val r = chat(msgs, null, m, cancel = cancel, maxTokens = maxTokens, stream = false, effort = "low")
        if (r.content.isNotBlank()) return r.content
        Diag.i("llm", "$purpose: empty answer (finish ${r.finish}, ${r.reasoning.length} reasoning chars) — retrying with a larger budget")
        val big = maxOf(8000, maxTokens * 4)   // free reasoning models think 4–5k tokens even at "low" effort
        val r2 = chat(msgs, null, m, cancel = cancel, maxTokens = big, stream = false, effort = "low")
        if (r2.content.isNotBlank()) return r2.content
        if (fallbackToMain && Cfg.model.isNotEmpty() && m != Cfg.model) {
            Diag.i("llm", "$purpose: still empty — asking the main model")
            val r3 = chat(msgs, null, Cfg.model, cancel = cancel, maxTokens = big, stream = false, effort = "low")
            if (r3.content.isNotBlank()) return r3.content
        }
        Diag.i("llm", "$purpose: no answer (finish ${r2.finish}, ${r2.reasoning.length} reasoning chars)")
        return ""
    }

    /** Looks at an image with the vision model (or the main model). */
    fun vision(dataUrl: String, question: String, cancel: Cancel? = null): String {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", parts))
        val r = chat(msgs, null, Cfg.visionModel.ifEmpty { Cfg.model }, cancel = cancel, maxTokens = 2000, stream = false)
        return r.content.ifEmpty { r.reasoning }
    }
}
