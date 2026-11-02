package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Per-turn state of the agent loop (the JS kept these on `ctx`). Create one per Agent.run turn. */
class LoopState(var userText: String = "") {
    /** repeat-call counter: tool name + raw args -> times called in this turn */
    val repeat = HashMap<String, Int>()
    /** the server already rejected the prompt as too long once in this turn */
    var overflowRetried = false
    /** finish_reason of the last model reply ("length" = hit the output limit) */
    var lastFinish: String? = null
}

/** Helpers of the original agent loop: safe history for strict servers, argument checks, repeat reminders. */
object LoopUtil {
    /** JSON.parse that only accepts a complete JSON value (no trailing garbage). Returns null on failure. */
    fun jsonTry(s: String?): Any? {
        if (s == null) return null
        return try {
            val t = JSONTokener(s)
            val v = t.nextValue()
            if (t.nextClean() != 0.toChar()) null else v
        } catch (e: Exception) { null }
    }

    private fun isObjectJson(s: String): Boolean = s.isNotBlank() && jsonTry(s) is JSONObject

    /**
     * Servers such as vLLM parse the arguments of every earlier tool call in the history; a call that was cut off
     * (output limit) or streamed badly would make every following request fail with a 400. Such arguments are
     * replaced by a small valid object in what is sent; the tool result next to it already explains what happened.
     */
    fun safeToolCall(c: JSONObject): JSONObject {
        val f = c.optJSONObject("function")
        val a: Any? = f?.opt("arguments")
        if (a is String && isObjectJson(a)) return c
        val out = JSONObject(c.toString())
        val nf = JSONObject(f?.toString() ?: "{}")
        if (a is JSONObject) {
            nf.put("arguments", a.toString())
        } else {
            nf.put("name", f?.optString("name", "")?.ifEmpty { null } ?: "unknown")
            val str = if (a == null || a == JSONObject.NULL) "" else a.toString()
            nf.put("arguments", if (str.isBlank()) "{}" else JSONObject().put("_invalid_arguments", true).put("received_prefix", str.take(200)).toString())
        }
        out.put("function", nf)
        return out
    }

    /** Drops orphaned tool rows and strips UI-only fields (ts, display, images, ok, local rows…). */
    fun fixAlternation(msgs: JSONArray): JSONArray {
        val ids = HashSet<String>()
        val out = JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optBoolean("local")) continue
            val role = m.optString("role")
            val calls = m.optJSONArray("tool_calls")
            if (role == "assistant" && calls != null) for (k in 0 until calls.length()) calls.optJSONObject(k)?.optString("id")?.let { ids.add(it) }
            if (role == "tool" && !ids.contains(m.optString("tool_call_id"))) continue
            val content = m.opt("content")
            val o = JSONObject().put("role", role).put("content", if (content == null || content == JSONObject.NULL) "" else content)
            if (calls != null) { val a = JSONArray(); for (k in 0 until calls.length()) calls.optJSONObject(k)?.let { a.put(safeToolCall(it)) }; o.put("tool_calls", a) }
            if (m.has("tool_call_id")) o.put("tool_call_id", m.optString("tool_call_id"))
            if (m.has("name") && m.optString("name").isNotEmpty()) o.put("name", m.optString("name"))
            out.put(o)
        }
        return out
    }

    private fun typeOf(v: Any?): String = when (v) {
        is JSONArray -> "array"
        is JSONObject -> "object"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        else -> "undefined"
    }

    /**
     * Checks [args] against a tool's JSON-schema `parameters` object ({properties, required}); fixes harmless type slips
     * in place (number→string, "5"→5, "true"→true, "x"→["x"]). Returns an error text or null when fine.
     */
    fun validateArgs(params: JSONObject?, args: Any?): String? {
        val p = params ?: JSONObject()
        val props = p.optJSONObject("properties") ?: JSONObject()
        if (args !is JSONObject) return "arguments must be a JSON object"
        val req = p.optJSONArray("required")
        if (req != null) for (i in 0 until req.length()) {
            val k = req.optString(i)
            if (!args.has(k) || args.isNull(k) || args.opt(k) == "") return "missing required argument \"$k\""
        }
        for (k in args.keys().asSequence().toList()) {
            val sch = props.optJSONObject(k) ?: continue
            if (args.isNull(k)) continue
            val v = args.opt(k)
            val ty = sch.optString("type")
            val got = typeOf(v)
            if (ty == "string" && got != "string") { if (got == "number" || got == "boolean") { args.put(k, if (v is Number) num(v) else v.toString()); continue }; return "argument \"$k\" must be a string, got $got" }
            if (ty == "integer" && got != "number") { if (got == "string" && Regex("^-?\\d+$").matches((v as String).trim())) { val n = v.trim().toLong(); if (n in Int.MIN_VALUE..Int.MAX_VALUE) args.put(k, n.toInt()) else args.put(k, n); continue }; return "argument \"$k\" must be an integer" }
            if (ty == "boolean" && got != "boolean") { if (v == "true" || v == "false") { args.put(k, v == "true"); continue }; return "argument \"$k\" must be true or false" }
            if (ty == "array" && got != "array") { if (got == "string") { val j = jsonTry(v as String); args.put(k, if (j is JSONArray) j else JSONArray().put(v)); continue }; return "argument \"$k\" must be an array" }
            val en = sch.optJSONArray("enum")
            if (en != null && got == "string") { val opts = (0 until en.length()).map { en.optString(it) }; if (v as String !in opts) return "argument \"$k\" must be one of: ${opts.joinToString(", ")}" }
        }
        return null
    }

    private fun num(n: Number): String { val d = n.toDouble(); return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() }

    /** The `parameters` object of [name] in a tools JSONArray (OpenAI format), or null when the tool is not in it. */
    fun paramsOf(tools: JSONArray, name: String): JSONObject? {
        for (i in 0 until tools.length()) { val f = tools.optJSONObject(i)?.optJSONObject("function") ?: continue; if (f.optString("name") == name) return f.optJSONObject("parameters") ?: JSONObject() }
        return null
    }

    fun unknownToolError(name: String, available: List<String>) = "Error: unknown tool $name. Available: ${available.joinToString(", ")}"

    /** The reply hit the output limit while writing the call's arguments. */
    fun cutOffError(name: String) =
        "Error: your $name call was cut off before its arguments were complete (the reply hit the output limit). Nothing was done. Split the work into smaller calls: for a large file, call write_file with the first part, then call it again with append=true for each following part (keep each part under about 6000 characters)."

    /**
     * Parses and checks one tool call's raw arguments like the original runTools did.
     * Returns (args, null) when the call may run, or (null, "Error: …" text to use as the tool result).
     * [params] = the tool's schema parameters (see [paramsOf]).
     */
    fun checkCall(name: String, rawArgs: String?, params: JSONObject?, state: LoopState?): Pair<JSONObject?, String?> {
        val raw = rawArgs ?: ""
        val parsed = jsonTry(raw.ifEmpty { "{}" })
        if (parsed == null) {
            val err = if (state?.lastFinish == "length" || Regex("^\\s*\\{[\\s\\S]*[^}\\s]\\s*$").matches(raw)) cutOffError(name)
            else "Error: arguments were not valid JSON: ${clip(raw, 300)}. Send the call again with valid JSON arguments."
            return Pair(null, err)
        }
        val v = validateArgs(params, parsed)
        if (v != null) return Pair(null, "Error: $v. Send the call again with corrected arguments.")
        return Pair(parsed as JSONObject, null)
    }

    /** Repeat-call reminder: identical tool + arguments repeated within one turn (3rd, 5th, then every 5th time). */
    fun repeatReminder(state: LoopState, name: String, rawArgs: String?): String {
        val key = name + "\u0000" + (rawArgs ?: "{}")
        val n = (state.repeat[key] ?: 0) + 1
        state.repeat[key] = n
        return if (n >= 3 && (n == 3 || n == 5 || n % 5 == 0))
            "\n\n[reminder] You have now called $name with identical arguments $n times in this turn. Read the previous result carefully and change your approach instead of repeating the same call."
        else ""
    }

    /** What the original appended to every tool result: spill + repeat reminder. */
    fun finishResult(result: String, name: String, rawArgs: String?, state: LoopState): String =
        Spill.maybe(result, name) + repeatReminder(state, name, rawArgs)
}

/** Spill: oversized tool output is saved in full in the workspace; the model gets a preview and the path. */
object Spill {
    const val LIMIT = 16000
    const val DIR = "spill"
    const val KEEP = 30

    fun maybe(text: String, tool: String): String {
        if (text.length <= LIMIT) return text
        var path: String? = null
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            path = Workspace.write(DIR, "${fmt.format(Date())}-$tool.txt", text.toByteArray())
            val all = File(Workspace.root, DIR).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            for (old in all.take(maxOf(0, all.size - KEEP))) old.delete()
        } catch (e: Exception) { Diag.e("spill", "could not save", e) }
        return text.substring(0, 6000) + "\n\n…[output was ${String.format(Locale.US, "%,d", text.length)} characters; " +
            (if (path != null) "the full text is saved at $path. Read more with read_file (offset/limit).]" else "the rest was cut because the disk is full.]")
    }
}
