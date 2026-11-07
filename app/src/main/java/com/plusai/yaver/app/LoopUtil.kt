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

    /** Result sent for a tool call whose result row is missing (the app was killed mid-tool, a row was lost…). */
    const val MISSING_RESULT = "(no result: the call was interrupted before it finished)"

    /**
     * Makes the history valid for strict OpenAI-compatible servers and strips UI-only fields (ts, display, images, ok,
     * local rows…):
     * - every tool row must directly follow the assistant message that made its call — orphaned or out-of-place tool
     *   rows are dropped, duplicates too;
     * - every call of an assistant message gets a result before the next non-tool message (a placeholder when missing),
     *   otherwise the server rejects the whole request with a 400;
     * - an assistant message with neither text nor calls is dropped (several providers reject empty assistant content).
     */
    fun fixAlternation(msgs: JSONArray): JSONArray {
        val out = JSONArray()
        var open = LinkedHashSet<String>()      // calls of the latest assistant message still waiting for their result
        val answered = HashSet<String>()
        fun closeOpen() {
            for (id in open) out.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", MISSING_RESULT))
            open = LinkedHashSet()
        }
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optBoolean("local")) continue
            val role = m.optString("role")
            if (role == "tool") {
                val id = m.optString("tool_call_id")
                if (id !in open) continue
                open.remove(id); answered.add(id)
            } else closeOpen()
            val calls = m.optJSONArray("tool_calls")?.takeIf { role == "assistant" && it.length() > 0 }
            val content = m.opt("content")
            if (role == "assistant" && calls == null && (content == null || content == JSONObject.NULL || (content is String && content.isBlank()))) continue
            val o = JSONObject().put("role", role).put("content", if (content == null || content == JSONObject.NULL) "" else content)
            if (calls != null) {
                val a = JSONArray()
                for (k in 0 until calls.length()) {
                    val c = calls.optJSONObject(k) ?: continue
                    val id = c.optString("id")
                    if (id.isEmpty() || id in answered || id in open) continue   // a reused id would make the history ambiguous
                    a.put(safeToolCall(c)); open.add(id)
                }
                if (a.length() > 0) o.put("tool_calls", a)
                else if (o.optString("content").isBlank()) continue
            }
            if (role == "tool") o.put("tool_call_id", m.optString("tool_call_id"))
            if (m.has("name") && m.optString("name").isNotEmpty()) o.put("name", m.optString("name"))
            // two user messages in a row (a steer delivered right after the turn's message): joined, some servers reject the pair
            val prev = if (out.length() > 0) out.optJSONObject(out.length() - 1) else null
            if (role == "user" && prev != null && prev.optString("role") == "user" && prev.opt("content") is String && o.opt("content") is String) {
                prev.put("content", prev.optString("content") + "\n\n" + o.optString("content")); continue
            }
            out.put(o)
        }
        closeOpen()
        return out
    }

    /**
     * True while [msgs] ends with exactly [the cut-off answer at contFrom, its "continue" harness request] — the only
     * shape in which the next reply may be joined to that answer (an empty-reply nudge may have been appended to the request).
     */
    fun isContinuationTail(msgs: JSONArray, contFrom: Int, continueMsg: String): Boolean {
        if (contFrom < 0 || contFrom + 2 != msgs.length()) return false
        val a = msgs.optJSONObject(contFrom) ?: return false
        val h = msgs.optJSONObject(contFrom + 1) ?: return false
        return a.optString("role") == "assistant" && a.optJSONArray("tool_calls") == null &&
            h.optString("role") == "user" && h.optBoolean("harness") && h.optString("content").startsWith(continueMsg)
    }

    /**
     * Joins a continuation to the cut-off text. Models often repeat the last words before going on: an overlap of at
     * least 12 characters between the end of [prev] and the start of [next] is written once.
     */
    fun joinContinuation(prev: String, next: String): String {
        if (prev.isEmpty()) return next
        val n = next.trimStart('\n')
        val max = minOf(400, prev.length, n.length)
        for (k in max downTo 12) if (prev.regionMatches(prev.length - k, n, 0, k)) return prev + n.substring(k)
        // the cut fell inside a word or line: keep the continuation exactly as written
        return prev + next
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
    /** Reads a JSON string value from possibly cut-off JSON: (value, complete). Incomplete escapes at the end are dropped. */
    fun partialString(raw: String, key: String): Pair<String, Boolean>? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"").find(raw) ?: return null
        val sb = StringBuilder(); var i = m.range.last + 1
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '"') return Pair(sb.toString(), true)
            if (ch == '\\') {
                if (i + 1 >= raw.length) break
                when (val e = raw[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { if (i + 5 >= raw.length) break; try { sb.append(raw.substring(i + 2, i + 6).toInt(16).toChar()) } catch (_: Exception) { break }; i += 4 }
                    else -> sb.append(e)
                }
                i += 2; continue
            }
            sb.append(ch); i++
        }
        return Pair(sb.toString(), false)
    }

    /**
     * Augment mode for calls cut off by the output limit: what arrived is kept instead of thrown away.
     * write_file → the received part is written (or appended) and the model continues with append=true;
     * execute_code/run_code → the received code goes to a file the model completes with append=true and then runs.
     * Returns the tool result to use, or null when nothing could be salvaged.
     */
    fun salvage(name: String, raw: String): String? = try { salvage0(name, raw) } catch (e: Throwable) { Diag.e("augment", "salvage $name", e); null }

    private fun salvage0(name: String, raw: String): String? {
        return when (name) {
            "write_file" -> {
                val path = partialString(raw, "path")?.takeIf { it.second }?.first?.trim()
                val part = partialString(raw, "content")?.first
                if (!path.isNullOrEmpty() && part.isNullOrEmpty())
                    return "Error: your write_file call for \"$path\" was cut off by the output limit before ANY of the content arrived — nothing was written. " +
                        "The file is too large for one call. Do not send the whole file again: call write_file with path=\"$path\" and only the FIRST part of the content (at most about 4000 characters), " +
                        "then call write_file again with append=true for each next part of at most about 4000 characters, until the file is complete."
                if (path.isNullOrEmpty() || part.isNullOrEmpty()) null else {
                    val append = Regex("\"append\"\\s*:\\s*true").containsMatchIn(raw)
                    val vp = VPath.normTool(path); val f = VPath.file(vp)
                    if (!append && f.exists() && f.length() > 0) return null  // an overwrite still needs the normal, confirmed call
                    f.parentFile?.mkdirs()
                    if (append) f.appendText(part) else f.writeText(part)
                    try { WsChange.notify(Workspace.rel(f)) } catch (_: Throwable) {}
                    Diag.i("augment", "write_file cut off: saved ${part.length} chars to $vp (append=$append)")
                    "[augment] Your write_file call was cut off by the output limit, so yaver ${if (append) "appended" else "saved"} the part that arrived (${part.length} characters) to $vp — nothing is lost. " +
                        "Continue the SAME file now: call write_file with path=\"$vp\", append=true and the rest of the content, starting exactly after these last characters (do not repeat them):\n«${part.takeLast(240)}»\n" +
                        "Keep each part under about 4000 characters and keep appending until the file is complete; then check the end of the file with read_file."
                }
            }
            "execute_code", "run_code" -> {
                val code = partialString(raw, "code")?.first
                if (code.isNullOrEmpty() || code.length < 200) null else {
                    val lang = partialString(raw, "language")?.first?.lowercase() ?: ""
                    val py = lang.startsWith("py") || (name == "run_code" && lang.isEmpty())
                    val vp = "/workspace/code/cut-" + System.currentTimeMillis() + if (py) ".py" else ".js"
                    val f = VPath.file(vp); f.parentFile?.mkdirs(); f.writeText(code)
                    Diag.i("augment", "$name cut off: saved ${code.length} chars of code to $vp")
                    "[augment] Your $name call was cut off by the output limit before the code was complete, so it was NOT run. yaver saved the ${code.length} characters that arrived to $vp. " +
                        "Finish it: call write_file with path=\"$vp\", append=true and the rest of the code, starting exactly after these last characters (do not repeat them):\n«${code.takeLast(240)}»\n" +
                        "Add parts of under about 4000 characters until the code is complete, then run the file with the terminal tool: `${if (py) "python" else "node"} $vp`."
                }
            }
            else -> null
        }
    }

    fun cutOffError(name: String) =
        "Error: your $name call was cut off before its arguments were complete (the reply hit the output limit). Nothing was done. Do not resend the same large call: split it. For a large file, call write_file with only the first part (at most about 4000 characters), then call it again with append=true for each following part of at most about 4000 characters."

    /**
     * Parses and checks one tool call's raw arguments like the original runTools did.
     * Returns (args, null) when the call may run, or (null, "Error: …" text to use as the tool result).
     * [params] = the tool's schema parameters (see [paramsOf]).
     */
    /** Arguments that start a JSON object but do not parse (unclosed): the reply was cut while writing them. */
    fun isCutArgs(raw: String?): Boolean {
        val t = (raw ?: "").trim()
        if (t.isEmpty() || jsonTry(t) != null) return false
        return t.startsWith("{")
    }

    fun checkCall(name: String, rawArgs: String?, params: JSONObject?, state: LoopState?): Pair<JSONObject?, String?> {
        val raw = rawArgs ?: ""
        val parsed = jsonTry(raw.ifEmpty { "{}" })
        if (parsed == null) {
            val cut = state?.lastFinish == "length" || isCutArgs(raw)
            val err = if (cut) (salvage(name, raw) ?: cutOffError(name))
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
    /** Spill (oversized output → file), then [wrap] (the <untrusted> tag, H51.untrusted), then the repeat reminder outside the tag. */
    fun finishResult(result: String, name: String, rawArgs: String?, state: LoopState, wrap: (String) -> String = { it }): String =
        wrap(Spill.maybe(result, name)) + repeatReminder(state, name, rawArgs)
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
