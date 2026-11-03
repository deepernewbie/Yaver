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

    /** The server accepts stream_options.include_usage (token counts while streaming); off after an HTTP 400. */
    @Volatile private var usageOk = true

    private fun headers(): Map<String, String> {
        val h = HashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "text/event-stream, application/json"
        val k = Cfg.llmKey
        if (k.isNotEmpty()) h["Authorization"] = "Bearer $k"
        if (Cfg.llmBase.contains("openrouter")) {
            h["HTTP-Referer"] = "https://plusai.yaver.app"
            h["X-Title"] = "yaver"
        }
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
        onThink: ((String) -> Unit)? = null, cancel: Cancel? = null, maxTokens: Int = 0, temperature: Double? = null, stream: Boolean = Cfg.stream
    ): LlmResult {
        val m = (model ?: "").ifEmpty { Cfg.model }
        if (m.isEmpty()) throw LlmException(Y.t("No model chosen. Open Settings → Model and pick one.", "Model seçilmemiş. Ayarlar → Model bölümünden bir model seçin."))
        val body = JSONObject()
        body.put("model", m)
        body.put("messages", messages)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        body.put("temperature", temperature ?: Cfg.temperature)
        if (maxTokens > 0) body.put("max_tokens", maxTokens)
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
                val retry = e.status == 0 || e.status == 408 || e.status == 429 || e.status >= 500
                if (e.status == 400 && body.has("stream_options")) { body.remove("stream_options"); droppedUsage = true; continue }
                if (e.status == 400 && body.has("tool_choice")) { body.remove("tool_choice"); continue }
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
        }
    }

    private fun once(body: JSONObject, stream: Boolean, onDelta: ((String) -> Unit)?, onThink: ((String) -> Unit)?, cancel: Cancel?): LlmResult {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = java.util.TreeMap<Int, Array<String>>() // index -> [id, name, args]
        var finish: String? = null
        var tin = 0
        var tout = 0
        var streamErr: String? = null
        val (code, raw) = Http.streamLines(Cfg.llmBase + "/chat/completions", headers(), body.toString().toByteArray(), cancel) { line ->
            if (!line.startsWith("data:")) return@streamLines
            val d = line.substring(5).trim()
            if (d == "[DONE]" || d.isEmpty()) return@streamLines
            val j = try { JSONObject(d) } catch (e: Exception) { return@streamLines }
            j.optJSONObject("error")?.let { streamErr = it.optString("message", it.toString()); return@streamLines }
            j.optJSONObject("usage")?.let { tin = it.optInt("prompt_tokens", tin); tout = it.optInt("completion_tokens", tout) }
            val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: return@streamLines
            ch.optString("finish_reason", "").let { if (it.isNotEmpty() && it != "null") finish = it }
            val delta = ch.optJSONObject("delta") ?: return@streamLines
            val c = delta.optString("content", "")
            if (c.isNotEmpty() && c != "null") { content.append(c); onDelta?.invoke(c) }
            val r = delta.optString("reasoning", "").ifEmpty { delta.optString("reasoning_content", "") }
            if (r.isNotEmpty() && r != "null") { reasoning.append(r); onThink?.invoke(r); info.get()?.invoke(reasoning.length, calls.values.lastOrNull()?.get(1) ?: "", calls.values.sumOf { it[2].length }, calls.size) }
            val tc = delta.optJSONArray("tool_calls")
            if (tc != null) for (i in 0 until tc.length()) {
                val t = tc.getJSONObject(i)
                val idx = t.optInt("index", i)
                val slot = calls.getOrPut(idx) { arrayOf("", "", "") }
                val id = t.optString("id", "")
                if (id.isNotEmpty() && id != "null") slot[0] = id
                val f = t.optJSONObject("function")
                if (f != null) {
                    val n = f.optString("name", "")
                    if (n.isNotEmpty() && n != "null") slot[1] = slot[1] + n
                    val a = f.optString("arguments", "")
                    if (a != "null") slot[2] = slot[2] + a
                }
                info.get()?.invoke(reasoning.length, slot[1], calls.values.sumOf { it[2].length }, calls.size)
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
                list.add(ToolCall(t.optString("id").ifEmpty { "call_" + System.nanoTime() }, f.optString("name"), f.optString("arguments", "{}")))
            }
            val u = j.optJSONObject("usage")
            return LlmResult(text, msg.optString("reasoning", "").let { if (it == "null") "" else it }, list, ch.optString("finish_reason"), u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0)
        }
        val list = calls.values.filter { it[1].isNotEmpty() }.map { ToolCall(it[0].ifEmpty { "call_" + System.nanoTime() }, it[1], it[2].ifEmpty { "{}" }) }
        return LlmResult(content.toString(), reasoning.toString(), list, finish, tin, tout)
    }

    /** One quick question to the (fast) model without tools. */
    fun ask(system: String, user: String, cancel: Cancel? = null, maxTokens: Int = 1500, fast: Boolean = true): String {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val r = chat(msgs, null, if (fast) Cfg.fastModel else null, cancel = cancel, maxTokens = maxTokens, stream = false)
        if (r.content.isNotBlank()) return r.content
        // a reasoning model spent the whole budget thinking: its hidden reasoning is not an answer — try once more with room, then the main model
        Diag.i("llm", "empty answer (finish ${r.finish}, ${r.reasoning.length} reasoning chars) — retrying")
        val r2 = chat(msgs, null, if (fast) Cfg.fastModel else null, cancel = cancel, maxTokens = maxOf(4000, maxTokens * 3), stream = false)
        if (r2.content.isNotBlank()) return r2.content
        if (fast && Cfg.fastModel.isNotEmpty() && Cfg.fastModel != Cfg.model) {
            val r3 = chat(msgs, null, null, cancel = cancel, maxTokens = maxOf(4000, maxTokens * 3), stream = false)
            if (r3.content.isNotBlank()) return r3.content
        }
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
