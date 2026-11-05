package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent loop 5.0 helpers: which tool calls may run side by side, the bounded pool that runs them, and repairs for
 * the slips weak / free models make in tool calls (prefixed names, fenced or double-encoded arguments, a call
 * written as text instead of a real tool call).
 */
object Loop5 {
    /** Parallel tool calls at most at once (per assistant message). */
    const val PARALLEL = 4

    // ---------------------------------------------------------------- read-only allow-list
    /** Tools that never change anything (no approval, no files written, no state another call reads). */
    private val ALWAYS = setOf(
        "read_file", "list_files", "search_files", "search_documents", "search_all", "web_search", "session_search",
        "skills_list", "skill_view", "people", "look_at_image", "vision_analyze", "phone_info"
    )
    /** Tools that are read-only only for these actions (the "action" argument). */
    private val BY_ACTION = mapOf(
        "mail" to setOf("list", "find", "search", "read"),
        "calendar" to setOf("list", "free"),
        "memory" to setOf("search"),
        "media" to setOf("info"),
        "task_board" to setOf("list", "get", "people"),
        "phone_calendar" to setOf("list", "calendars"),
        "phone_reminder" to setOf("list"),
        "schedule_task" to setOf("list"),
        "folder" to setOf("list")
    )
    /** Test seam / extensions: more tool names that are always read-only. */
    val extraReadOnly: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when [name] with [args] is on the explicit read-only allow-list (decided from the name and its arguments, never guessed). */
    fun readOnly(name: String, args: JSONObject?): Boolean {
        val a = args ?: return false
        if (name in ALWAYS || name in extraReadOnly) return true
        BY_ACTION[name]?.let { return a.optString("action") in it }
        return when (name) {
            // documents and pages are read-only unless the call also saves a copy
            "read_document" -> a.optString("save_as").isBlank()
            "web_extract" -> a.optString("save_to").isBlank()
            // a numbered link refers to "the last page read": order matters, so only plain URL reads run side by side
            "read_page" -> false   // remembers the page's numbered links per chat: two at once would mix them up
            "fetch", "http_request" -> a.optString("method", "GET").ifBlank { "GET" }.uppercase() in setOf("GET", "HEAD") && a.optString("save_to").isBlank() && a.optString("save_as").isBlank()
            else -> false
        }
    }

    // ---------------------------------------------------------------- the pool
    private val pool = Executors.newCachedThreadPool(object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "yaver-tool-" + n.incrementAndGet()).apply { isDaemon = true }
    })

    /** Results of jobs started with [start]; read them in order with [await]. */
    class Batch<T> internal constructor(n: Int) {
        internal val out = arrayOfNulls<Any?>(n)
        internal val done = BooleanArray(n)
        internal val lock = Object()
        internal val workers = ArrayList<Future<*>>()
        /** Waits for job [i]; null when [cancel] fired first. */
        @Suppress("UNCHECKED_CAST")
        fun await(i: Int, cancel: Cancel): T? = synchronized(lock) {
            while (!done[i] && !cancel.cancelled) lock.wait(50)
            if (done[i]) out[i] as T? else null
        }
        /** Interrupts the workers (after a cancel). */
        fun stop() { workers.forEach { it.cancel(true) } }
    }

    /**
     * Starts [jobs] on the pool, at most [PARALLEL] at once, in order. Jobs must not touch the conversation and should
     * catch their own errors (a throwing job yields null). Stops taking new jobs once [cancel] fires.
     */
    fun <T> start(jobs: List<() -> T>, cancel: Cancel): Batch<T> {
        val b = Batch<T>(jobs.size)
        val next = AtomicInteger(0)
        repeat(minOf(PARALLEL, jobs.size)) {
            b.workers.add(pool.submit(Runnable {
                while (true) {
                    val i = next.getAndIncrement(); if (i >= jobs.size || cancel.cancelled) break
                    val r = try { jobs[i]() } catch (e: Throwable) { null }
                    synchronized(b.lock) { b.out[i] = r; b.done[i] = true; b.lock.notifyAll() }
                }
            }))
        }
        return b
    }

    // ---------------------------------------------------------------- weak-model slips
    /**
     * The tool name a model meant: "functions.write_file", "write_file<|channel|>commentary", " Write_File " → write_file
     * when that is a known tool. Returns [name] unchanged when nothing matches.
     */
    fun fixName(name: String, known: (String) -> Boolean): String {
        if (known(name)) return name
        var n = name.trim()
        n = n.substringBefore("<|").substringBefore("<").trim()
        n = n.removePrefix("functions.").removePrefix("function.").removePrefix("tools.").removePrefix("default_api.")
        n = n.trim('`', '"', '\'', ' ', ':', '.')
        if (known(n)) return n
        val low = n.lowercase()
        if (known(low)) return low
        return name
    }

    /**
     * Arguments as JSON object text when the model wrapped them: a ```json fence, a JSON string holding the object
     * (double-encoded), or text around one object. Returns [raw] unchanged when it already parses or nothing helps.
     */
    fun fixArgs(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return raw
        val p = LoopUtil.jsonTry(t)
        if (p is JSONObject) return raw
        if (p is String) { val q = LoopUtil.jsonTry(p.trim()); if (q is JSONObject) return q.toString() }
        val fence = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$").find(t)?.groupValues?.get(1)
        if (fence != null && LoopUtil.jsonTry(fence) is JSONObject) return fence
        return raw
    }

    private val TEXT_CALL = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>")

    /**
     * Tool calls a model wrote into its text instead of making them (Hermes/Qwen style `<tool_call>{"name":…,
     * "arguments":{…}}</tool_call>`). Only when the reply is nothing but such blocks and every name is a tool that
     * was offered. Returns the calls, or null.
     */
    fun textCalls(content: String, offered: Set<String>): List<ToolCall>? {
        val t = content.trim()
        if (!t.startsWith("<tool_call>")) return null
        val ms = TEXT_CALL.findAll(t).toList()
        if (ms.isEmpty() || TEXT_CALL.replace(t, "").isNotBlank()) return null
        val out = ArrayList<ToolCall>()
        for (m in ms) {
            val o = LoopUtil.jsonTry(m.groupValues[1]) as? JSONObject ?: return null
            val name = o.optString("name").ifEmpty { o.optJSONObject("function")?.optString("name") ?: "" }
            if (name !in offered) return null
            val a = o.opt("arguments") ?: o.opt("parameters") ?: JSONObject()
            val args = when (a) { is JSONObject -> a.toString(); is String -> fixArgs(a); else -> "{}" }
            out.add(ToolCall("call_t" + System.nanoTime().toString(36) + out.size, name, args))
        }
        return out
    }

    /** Unique tool-call ids for a new assistant message: providers that reuse "call_0" every turn (or send none) would make the history ambiguous. */
    fun uniqueIds(c: Convo, calls: List<ToolCall>): List<ToolCall> {
        val used = HashSet<String>()
        for (i in 0 until c.messages.length()) {
            val tc = c.messages.optJSONObject(i)?.optJSONArray("tool_calls") ?: continue
            for (k in 0 until tc.length()) tc.optJSONObject(k)?.optString("id")?.let { used.add(it) }
        }
        return calls.mapIndexed { k, t ->
            if (t.id.isNotEmpty() && used.add(t.id)) t
            else { val id = "call_" + System.currentTimeMillis().toString(36) + "_" + k + "_" + (t.id.ifEmpty { "x" }).takeLast(8); used.add(id); ToolCall(id, t.name, t.args) }
        }
    }

    // ---------------------------------------------------------------- resume after the app died mid-turn
    const val CLOSED_RESULT_EN = "(not run: the app was closed)"

    /** The open tool calls of the last assistant message with calls (ids and names), when results are missing. */
    fun openCalls(msgs: JSONArray): List<Pair<String, String>> {
        val answered = HashSet<String>()
        var last: JSONArray? = null
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") answered.add(m.optString("tool_call_id"))
            if (m.optString("role") == "assistant") m.optJSONArray("tool_calls")?.let { last = it }
        }
        val l = last ?: return emptyList()
        return (0 until l.length()).mapNotNull { l.optJSONObject(it) }.filter { it.optString("id") !in answered }
            .map { it.optString("id") to (it.optJSONObject("function")?.optString("name") ?: "") }
    }
}
