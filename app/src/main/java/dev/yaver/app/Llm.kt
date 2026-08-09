package dev.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only thing that talks to OpenRouter.
 *
 * Streaming rather than a single response because a phone screen showing
 * nothing for eight seconds looks broken, and because it lets the user stop a
 * run that is clearly going the wrong way.
 */
object Llm {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    class LlmError(message: String, val rateLimited: Boolean = false) : Exception(message)

    @Volatile var cancelled = false

    private fun connect(body: JSONObject): HttpURLConnection {
        val key = Store.setting(Store.API_KEY)
        if (key.isBlank()) throw LlmError("No OpenRouter API key. Open Settings and add one.")

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("HTTP-Referer", "https://yaver.app")
            setRequestProperty("X-Title", "Yaver")
            doOutput = true
            connectTimeout = 20000
            readTimeout = 180000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return conn
    }

    /** Cost tracking must never delay or break a reply. */
    private fun noteUsage(model: String, usage: JSONObject) {
        try {
            Store.recordUsage(
                model,
                usage.optInt("prompt_tokens"),
                usage.optInt("completion_tokens"),
                usage.optDouble("cost", 0.0)
            )
        } catch (e: Exception) { /* accounting is not worth an exception */ }
    }

    private fun failure(conn: HttpURLConnection): LlmError {
        val status = conn.responseCode
        val text = try {
            conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        } catch (e: Exception) { "" }
        val detail = try {
            JSONObject(text).optJSONObject("error")?.optString("message") ?: text.take(200)
        } catch (e: Exception) { text.take(200) }

        return if (status == 429) {
            LlmError("Rate limited: $detail. Free models throttle hard — waiting and retrying.", true)
        } else {
            LlmError("OpenRouter $status: $detail")
        }
    }

    /**
     * Free tiers throttle per minute and a burst of tool calls hits the ceiling
     * quickly. A short wait almost always clears it, and losing a minute of
     * finished work to one refused call is the worst possible outcome.
     */
    private fun <T> withRetry(tries: Int = 6, block: () -> T): T {
        var wait = 1500L
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: LlmError) {
                if (!e.rateLimited || attempt >= tries || cancelled) throw e
                Log.info("Rate limited — waiting ${wait}ms then retrying ($attempt/${tries - 1})")
                Thread.sleep(wait)
                // A free tier's window is a minute; giving up after six
                // seconds guarantees failure just as it was about to clear.
                wait = minOf(wait * 2, 25000)
                attempt++
            }
        }
    }

    /** Streams a completion, calling [onToken] with each delta. Returns the whole text. */
    fun stream(
        messages: List<Pair<String, String>>,
        model: String = Store.model(),
        temperature: Double = 0.4,
        onToken: (String) -> Unit
    ): String = withRetry {
        val msgs = JSONArray()
        messages.forEach { (role, content) ->
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("temperature", temperature)
            // Some providers default to a small completion budget, which
            // truncates a tool call mid-JSON and looks like the model refusing.
            .put("max_tokens", 2048)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))

        val conn = connect(body)
        if (conn.responseCode !in 200..299) throw failure(conn)

        val out = StringBuilder()
        try {
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                while (true) {
                    if (cancelled) break
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    try {
                        val frame = JSONObject(payload)
                        val delta = frame.optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content")
                        if (!delta.isNullOrEmpty()) {
                            out.append(delta)
                            onToken(delta)
                        }
                        // The last frame carries token counts when asked for.
                        frame.optJSONObject("usage")?.let { noteUsage(model, it) }
                    } catch (e: Exception) { /* keep-alive or a split frame */ }
                }
            }
        } finally {
            conn.disconnect()
        }
        Log.net("model replied with ${out.length} chars")
        out.toString()
    }

    /** One-shot completion, for extraction jobs where streaming buys nothing. */
    fun complete(
        messages: List<Pair<String, String>>,
        model: String = Store.model(),
        temperature: Double = 0.2
    ): String = withRetry {
        val msgs = JSONArray()
        messages.forEach { (role, content) ->
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model).put("messages", msgs)
            .put("temperature", temperature).put("max_tokens", 2048)

        val conn = connect(body)
        if (conn.responseCode !in 200..299) throw failure(conn)
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()
        val parsed = JSONObject(text)
        parsed.optJSONObject("usage")?.let { noteUsage(model, it) }
        parsed.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }
}
