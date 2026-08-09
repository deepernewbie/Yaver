package dev.yaver.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar as JCalendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The loop.
 *
 * The tool protocol is prompt-based rather than the provider's native function
 * calling, so any model on OpenRouter works and switching model is a setting
 * rather than a rewrite. The price is that models disagree about how to write
 * a call, which is why the parser below accepts three dialects instead of
 * rejecting two of them and watching a small model loop forever.
 */
object Agent {

    interface Listener {
        fun onAssistantToken(delta: String)
        fun onAssistantTurnStart()
        fun onToolStart(name: String, args: JSONObject)
        fun onToolEnd(name: String, ok: Boolean, ms: Long, result: JSONObject)
        fun onCard(card: JSONObject)
        fun onFinished(reply: String)
        fun onFailed(message: String)
    }

    private const val MAX_TURNS = 10

    // ── the memory nudge ─────────────────────────────────────────────────────
    //
    // Writing memory is optional and answering is urgent, so a model always
    // picks answering and then "never remembers anything". Counting the
    // exchanges and reminding it is what closes that gap. Deliberately a nudge
    // rather than a forced call: made mandatory it writes noise every turn.

    private const val NUDGE_AFTER = 3
    private var sinceMemoryWrite = 0
    private var pendingNudge = ""

    private val MEMORY_TOOLS = setOf("remember", "forget")

    // ── system prompt ────────────────────────────────────────────────────────

    private fun dayTable(): String {
        // Models get weekday arithmetic wrong constantly, and a meeting on the
        // wrong day is the most expensive mistake this app can make. Handing
        // them the answer costs a few hundred tokens.
        val tr = arrayOf("Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi")
        val en = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = JCalendar.getInstance(TimeZone.getDefault())
        return (0 until 14).joinToString("\n") { i ->
            val c = cal.clone() as JCalendar
            c.add(JCalendar.DAY_OF_YEAR, i)
            val dow = c.get(JCalendar.DAY_OF_WEEK) - 1
            val label = when (i) { 0 -> "  ← today"; 1 -> "  ← tomorrow"; else -> "" }
            "${fmt.format(c.time)} = ${tr[dow]} / ${en[dow]}$label"
        }
    }

    /**
     * The stable half of the prompt.
     *
     * Every byte here is identical on every turn, which is what lets a local
     * runtime keep the processed prefix in its KV cache and skip re-reading it.
     * Anything that changes — the date, the goals, what came in — is appended
     * to the user's message instead, where it costs a few hundred tokens
     * rather than invalidating everything.
     *
     * Tool schemas used to live here: forty of them, ten thousand tokens,
     * rebuilt every turn. Now only the dozen core ones are inlined and the rest
     * are behind `open_tools`.
     */
    private fun systemPrompt(): String = buildString {
        append("You are Yaver — an aide-de-camp running on someone's phone. The name is Turkish: the officer who keeps a commander's affairs in order, anticipates what is needed and prepares it, but never gives the orders.\n\n")

        append("## Time\n\n")
        append("**Every time you write is local wall-clock time.** Write `2026-08-11T19:00` for seven in the evening. Never append `Z`, never convert to UTC — the app handles that. Times coming back from tools are local too.\n\n")
        append("Turkish ordinals like \"6'sında\" mean the sixth DAY of the month, never a clock time. A due time and a reminder are different: \"dinner at 19:00, remind me at 18:00\" is one task due at 19:00 with `remind: 60`.\n\n")
        append("Today's date and the weekday table are given with each message. Use that table rather than working weekdays out yourself.\n\n")

        append("## Tools\n\nCall a tool by emitting exactly:\n<tool_call>\n{\"name\": \"tool_name\", \"arguments\": {...}}\n</tool_call>\n\n")
        append("Rules:\n")
        append("- Emit tool calls alone, with no other prose in that reply. You will get the result and can then continue.\n")
        append("- Several independent calls may go in one reply.\n")
        append("- Never invent a tool result, and never claim you searched or read something you did not.\n\n")

        append(Tools.schemaText())
        append("\nEach tool covers one subject and takes an `action`. Pass the action plus whatever that action needs — the parameter names are listed above.\n\n")

        append("## Saying it is not doing it\n\n")
        append("Announcing an action does not perform it. If you write that you are adding a task or creating an event, emit the tool call in that same reply. Describe what you did in the past tense only after seeing the tool result.\n\n")

        // A worked exchange is worth more to a small model than any amount of
        // instruction about format. This is the shape of nearly every turn.
        append("### What a turn looks like\n\n")
        append("User: yarın 10'da Yavuz abiyle toplantı var, hatırlat\n\n")
        append("You (nothing but the call):\n")
        append("<tool_call>\n{\"name\": \"task\", \"arguments\": {\"action\": \"add\", \"title\": \"Yavuz abiyle toplantı\", \"due\": \"2026-08-11T10:00\", \"remind\": 30}}\n</tool_call>\n\n")
        append("Then you receive:\n<tool_response>{\"added\": true, \"due\": \"2026-08-11T10:00:00+03:00\"}</tool_response>\n\n")
        append("You (now, and only now, in the past tense):\nEklendi — yarın 10:00, yarım saat önce hatırlatırım.\n\n")
        append("Never write \"ekliyorum\" or \"I'll add that\" without the call in the same reply. Either call the tool or say you have not.\n\n")
        append("Close the tag: `</tool_call>`. If you forget it the call still works, but closing it is safer.\n\n")

        append("## Never invent specifics\n\n")
        append("- Never state a price, date, figure or URL you did not read from a tool result in this conversation.\n")
        append("- Every link must be one that appeared in a tool result. Never build a URL from a pattern.\n")
        append("- If search is down, say so and ask which site to try — guessing domain after domain produces confident nonsense.\n")
        append("- When a site blocks you, name it and move on rather than filling the gap from memory.\n\n")

        append("## How to work\n\n")
        append("- Ground answers about the user's world in their tasks, calendar and memory rather than assumptions. Look, do not guess.\n")
        append("- Search for anything current or verifiable; your training data is stale.\n")
        append("- Two ways to read a page. `browse_open` downloads the HTML: fast, fine for articles. `browser` with action=open drives a real browser: slower, but it sees pages built by JavaScript and can dismiss banners, type and click. If a fetched page comes back thin or blocked, use the browser instead of guessing.\n")
        append("- Use `calculate` for arithmetic instead of doing it in your head.\n")
        append("- Finish the thought the user started: a mentioned meeting wants a calendar entry, a deadline wants a task. Prepare it, say what you inferred, and let them correct you. Never do anything irreversible.\n")
        append("- Keep a profile up to date with `profile` action=update, and write a `skill` for any job you may be asked to repeat.\n")
        append("- Answer in the user's language, and match their register.\n")
        append("- Stop when you have enough. A partial answer with sources beats a perfect one that never arrives.\n")
    }

    /**
     * The changing half, appended to the user's message.
     *
     * Deliberately thin. Memories, tasks and messages used to be injected here
     * on the chance they mattered; that is thousands of tokens a turn spent on
     * a guess. The agent can fetch what it needs, and now knows how.
     */
    private fun contextBlock(context: Context, userText: String): String = buildString {
        val now = System.currentTimeMillis()
        append("\n\n---\n")
        append("now: ${Store.localIso(now)} (offset ${Store.offsetLabel()})\n")
        append(dayTable())
        append("\n")

        val name = Store.setting(Store.USER_NAME)
        if (name.isNotBlank()) append("user: $name\n")

        // A profile is small and shapes everything, so it stays.
        val profile = Store.profile()
        if (profile.isNotBlank()) append("\nprofile:\n${profile.take(1200)}\n")

        // Titles only. If a goal matters this turn, the agent opens the group.
        val goals = Store.goals().filter { it.status == "active" }
        if (goals.isNotEmpty()) {
            append("\ngoals in progress: ")
            append(goals.take(5).joinToString("; ") { it.title })
            append("\n")
        }

        // Counts, not contents: enough to know whether looking is worthwhile.
        val open = Store.tasks().count { !it.done }
        val urgent = Store.tasks().count { Store.urgency(it, now) == "overdue" }
        if (open > 0) {
            append("open tasks: $open")
            if (urgent > 0) append(" ($urgent overdue)")
            append(" — use list_tasks to see them\n")
        }
        if (NotificationCapture.isEnabled()) {
            val recent = Store.messages(now - 86_400_000L, limit = 40).size
            if (recent > 0) append("messages in the last day: $recent — open the messages group to read them\n")
        }

        // The three memories most relevant to what was just said. Cheap, and it
        // stops the agent asking about things it already knows.
        val memories = Store.recall(userText, 3)
        if (memories.isNotEmpty()) {
            append("\nrelevant memories:\n")
            memories.forEach { append("- ${it.text}\n") }
            append("(use recall for more)\n")
        }

        val persona = Store.setting(Store.PERSONA)
        if (persona.isNotBlank()) append("\nstanding instructions: $persona\n")

        if (pendingNudge.isNotBlank()) append("\n$pendingNudge\n")
    }

    /** Rough sizes for the Debug panel — four characters to a token. */
    fun promptSize(): Pair<Int, Int> {
        val fixed = systemPrompt().length / 4
        val context = try {
            contextBlock(Store.appContext, "").length / 4
        } catch (e: Exception) { 0 }
        return fixed to context
    }

    // ── tool-call parsing ────────────────────────────────────────────────────
    //
    // Three dialects seen in the wild on OpenRouter:
    //   <tool_call>{"name":"x","arguments":{…}}</tool_call>        JSON
    //   <tool_call>x<arg_key>k</arg_key><arg_value>v</arg_value>…  XML pairs
    //   <function=x>{"k":"v"}</function>                           function tag

    private val CALL_RE = Regex("<(tool_call|function_call|tool_use)>([\\s\\S]*?)</\\1>")
    private val FN_TAG_RE = Regex("<function\\s*=\\s*([\\w.\\-]+)\\s*>([\\s\\S]*?)</function>")
    private val ARG_PAIR_RE = Regex("<arg_key>([\\s\\S]*?)</arg_key>\\s*<arg_value>([\\s\\S]*?)</arg_value>")

    data class Call(val name: String, val arguments: JSONObject)

    private fun coerce(raw: String): Any {
        val t = raw.trim()
        return when {
            t == "true" -> true
            t == "false" -> false
            t.toLongOrNull() != null -> t.toLong()
            t.toDoubleOrNull() != null -> t.toDouble()
            t.startsWith("[") -> try { JSONArray(t) } catch (e: Exception) { t }
            t.startsWith("{") -> try { JSONObject(t) } catch (e: Exception) { t }
            else -> t
        }
    }

    private fun parseOne(body: String): Call {
        val raw = body.trim()
            .removePrefix("```json").removePrefix("```xml").removePrefix("```")
            .removeSuffix("```").trim()

        if (raw.startsWith("{") || raw.startsWith("[")) {
            try {
                val o = if (raw.startsWith("[")) JSONArray(raw).optJSONObject(0) else JSONObject(raw)
                val name = o?.optString("name")?.ifBlank { null }
                    ?: o?.optString("tool")?.ifBlank { null }
                if (name != null && o != null) {
                    val args = o.optJSONObject("arguments") ?: o.optJSONObject("parameters")
                        ?: o.optJSONObject("args") ?: JSONObject()
                    return Call(name, args)
                }
            } catch (e: Exception) { /* fall through to the XML dialects */ }
        }

        val nameMatch = Regex("^([\\w.\\-]+)").find(raw)
        if (nameMatch != null) {
            val args = JSONObject()
            var found = false
            for (m in ARG_PAIR_RE.findAll(raw)) {
                args.put(m.groupValues[1].trim(), coerce(m.groupValues[2]))
                found = true
            }
            if (found) return Call(nameMatch.groupValues[1], args)

            val rest = raw.substring(nameMatch.value.length).trim()
            if (rest.startsWith("{")) {
                try { return Call(nameMatch.groupValues[1], JSONObject(rest)) } catch (e: Exception) { }
            }
            if (rest.isEmpty()) return Call(nameMatch.groupValues[1], JSONObject())
        }

        return Call("__parse_error__", JSONObject().put("raw", raw.take(400)))
    }

    fun parseCalls(text: String): List<Call> {
        val calls = mutableListOf<Call>()
        for (m in CALL_RE.findAll(text)) calls.add(parseOne(m.groupValues[2]))

        // Models drop the closing tag constantly — the JSON is complete, the
        // wrapper is not. Refusing that is pedantry: the intent is unambiguous
        // and throwing it away costs the user a whole turn.
        if (calls.isEmpty()) {
            val open = text.lastIndexOf("<tool_call>", ignoreCase = true)
            if (open >= 0 && !text.substring(open).contains("</tool_call>", ignoreCase = true)) {
                val body = text.substring(open + "<tool_call>".length)
                balancedJson(body)?.let { calls.add(parseOne(it)) }
            }
        }

        // Some emit the JSON with no wrapper at all, which is just as clear.
        if (calls.isEmpty()) {
            balancedJson(text)?.let { candidate ->
                if (candidate.contains("\"name\"") &&
                    (candidate.contains("\"arguments\"") || candidate.contains("\"parameters\""))) {
                    calls.add(parseOne(candidate))
                }
            }
        }

        for (m in FN_TAG_RE.findAll(text)) {
            val args = try { JSONObject(m.groupValues[2].trim().ifEmpty { "{}" }) }
                       catch (e: Exception) {
                           JSONObject().also { o ->
                               for (p in ARG_PAIR_RE.findAll(m.groupValues[2]))
                                   o.put(p.groupValues[1].trim(), coerce(p.groupValues[2]))
                           }
                       }
            calls.add(Call(m.groupValues[1], args))
        }
        return calls
    }

    /**
     * The first complete JSON object in a string, by counting braces.
     *
     * Trailing prose after the object is common, and so is a missing closing
     * wrapper; both are handled by finding where the object actually ends
     * rather than trusting a delimiter to be there.
     */
    private fun balancedJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> { }
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Reasoning models wrap their working in <think> tags, and some emit a
     * stray closing tag with no opener when the provider trims the front. Tool
     * calls are parsed from the whole text — a call inside a think block still
     * counts — but none of it should reach the screen.
     */
    /** A wrapper-less JSON call, which would otherwise be shown to the user. */
    private val BARE_JSON_CALL = Regex("^\\s*\\{\\s*[\"']?name[\"']?\\s*:[\\s\\S]*$")

    private val THINK = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val THINK_OPEN = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val THINK_ORPHAN = Regex("^[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)

    /** Removes finished calls and any half-streamed opening tag still arriving. */
    fun stripCalls(text: String): String = text
        .replace(THINK, "")
        .let { if (it.contains("</think>", true)) it.replace(THINK_ORPHAN, "") else it }
        .replace(THINK_OPEN, "")
        .replace(CALL_RE, "")
        .replace(FN_TAG_RE, "")
        .replace(Regex("<(tool_call|function_call|tool_use)>[\\s\\S]*$"), "")
        // A bare JSON call with no wrapper would otherwise be shown as prose.
        .replace(BARE_JSON_CALL, "")
        .replace(Regex("<function\\s*=[\\s\\S]*$"), "")
        .trim()

    // ── the run ──────────────────────────────────────────────────────────────

    fun run(context: Context, userText: String, history: List<Store.Turn>, listener: Listener) {
        Llm.cancelled = false
        if (pendingNudge.isNotBlank()) Log.info("memory nudge delivered with this turn")

        val convo = mutableListOf<Pair<String, String>>()
        convo.add("system" to systemPrompt())
        history.filter { it.role == "user" || it.role == "assistant" }
            .takeLast(20)
            .forEach { convo.add(it.role to it.content) }
        // Context rides with the message, so the system prefix never changes
        // and a local runtime can keep it cached.
        convo.add("user" to userText + contextBlock(context, userText))
        pendingNudge = ""     // delivered with this turn

        var finalText = ""
        var toolsRun = 0
        var consecutiveFailures = 0
        var corrections = 0
        var lastSignature = ""
        var repeats = 0
        val urlsSeen = mutableSetOf<String>()
        val queriesSeen = mutableSetOf<String>()

        try {
            for (turn in 0 until MAX_TURNS) {
                if (Llm.cancelled) { finalText = "(stopped)"; break }
                listener.onAssistantTurnStart()

                val raw = StringBuilder()
                Llm.stream(convo) { delta ->
                    raw.append(delta)
                    // Only prose reaches the screen; a half-written tool tag
                    // shown to the user reads as garbage.
                    listener.onAssistantToken(delta)
                }

                val text = raw.toString()
                Log.model(turn + 1, text)
                val calls = parseCalls(text)
                val prose = stripCalls(text)
                if (calls.isEmpty() && text.contains("tool_call", ignoreCase = true)) {
                    // It tried and the shape was wrong — worth saying so.
                    Log.error("turn ${turn + 1}: emitted something tool-like that would not parse")
                }

                if (calls.isEmpty()) {
                    // "I'll add that" with no tool call is the commonest way a
                    // weaker model fails: it reads as success and nothing
                    // happened. One corrective round usually recovers it.
                    if (corrections < 2 && announcesAction(prose)) {
                        corrections++
                        Log.error("turn ${turn + 1}: announced an action without calling a tool — correcting")
                        convo.add("assistant" to text)
                        convo.add("user" to (
                            "You said you would do that, but you emitted no tool call, so nothing happened. " +
                            "Emit the call now, alone, in exactly this shape and nothing else:\n" +
                            "<tool_call>\n{\"name\": \"task\", \"arguments\": {\"action\": \"add\", \"title\": \"…\"}}\n</tool_call>\n" +
                            "Replace the name and arguments with what the user actually asked for."))
                        continue
                    }
                    finalText = prose.ifBlank {
                        if (toolsRun > 0) "Done." else "(The model returned an empty response. Try again, or switch model in Settings.)"
                    }
                    nudgeAfterTurn()
                    break
                }

                // A model repeating the identical call is stuck, not working.
                val signature = calls.joinToString("|") { "${it.name}${it.arguments}" }
                repeats = if (signature == lastSignature) repeats + 1 else 0
                lastSignature = signature
                if (repeats >= 2) {
                    finalText = prose.ifBlank {
                        "I got stuck repeating the same step (${calls.first().name}) and stopped. " +
                        "That usually means the model isn't following the tool format — try a stronger one in Settings."
                    }
                    Log.error("loop guard tripped on ${calls.first().name}")
                    break
                }

                convo.add("assistant" to text)

                val responses = StringBuilder()
                for (call in calls) {
                    if (Llm.cancelled) break
                    val started = System.currentTimeMillis()
                    listener.onToolStart(call.name, call.arguments)

                    val result: JSONObject
                    if (call.name == "__parse_error__") {
                        result = JSONObject()
                            .put("error", "Could not read that tool call.")
                            .put("correct_format", "<tool_call>{\"name\": \"list_tasks\", \"arguments\": {}}</tool_call>")
                            .put("instruction", "Re-emit it in exactly that shape.")
                    } else {
                        // Repeating a fetch inside one request is never useful,
                        // and models do it constantly when a page disappoints.
                        val url = call.arguments.optString("url")
                        val query = call.arguments.optString("query")
                        result = when {
                            call.name == "browse_open" && url.isNotBlank() && !urlsSeen.add(url) ->
                                JSONObject().put("repeat", true)
                                    .put("instruction", "You already opened this page in this request. Use what it gave you, or try a different source.")
                            call.name == "web_search" && query.isNotBlank() &&
                                !queriesSeen.add(query.lowercase(Locale.ROOT)) ->
                                JSONObject().put("repeat", true)
                                    .put("instruction", "You already ran this exact search. Reword it substantially or work with what you have.")
                            else -> Tools.run(context, call.name, call.arguments)
                        }
                    }

                    val failed = result.has("error") || result.optBoolean("repeat")
                    consecutiveFailures = if (failed) consecutiveFailures + 1 else 0
                    if (!failed) {
                        toolsRun++
                        if (call.name in MEMORY_TOOLS) sinceMemoryWrite = 0
                    }

                    val ms = System.currentTimeMillis() - started
                    Log.tool("${call.name} ${if (failed) "failed" else "ok"} (${ms}ms)" +
                        summarise(call.name, result))
                    listener.onToolEnd(call.name, !failed, ms, result)

                    result.optJSONObject("card")?.let { listener.onCard(it) }

                    // Cards are for the screen, not the model.
                    val forModel = JSONObject(result.toString()).apply { remove("card") }
                    forModel.put("name", call.name)
                    responses.append("<tool_response>\n")
                        .append(forModel.toString().take(12000))
                        .append("\n</tool_response>\n")
                }

                var respText = responses.toString()
                val left = MAX_TURNS - turn - 1
                if (left in 1..3) respText += "\n[$left tool round(s) left. Start drawing a conclusion.]"
                else if (left <= 0) respText += "\n[No tool rounds left. Answer now with what you have.]"
                convo.add("user" to respText)

                // Four failures in a row means the approach is wrong, not unlucky.
                if (consecutiveFailures >= 4) {
                    finalText = "I stopped: four tool calls failed in a row. " +
                        "Tell me a specific site to look at, or try again in a moment."
                    Log.error("stopped after 4 consecutive tool failures")
                    break
                }
            }

            // Out of rounds with no answer: ask once more with tools closed,
            // rather than leaving the user with nothing.
            if (finalText.isBlank() && !Llm.cancelled) {
                Log.info("tool budget exhausted — forcing a conclusion")
                convo.add("user" to "Stop searching. Using only what you already found, give your answer now: what you established, and plainly what you could not verify. Emit no tool calls.")
                val closing = StringBuilder()
                listener.onAssistantTurnStart()
                Llm.stream(convo) { delta -> closing.append(delta); listener.onAssistantToken(delta) }
                finalText = stripCalls(closing.toString()).ifBlank {
                    "I searched a lot but never reached a conclusion. Ask me again more narrowly."
                }
            }

            // A model that answers the same thing twice is not answering; it is
            // echoing. Silently letting it through leaves the user thinking
            // their instruction was understood and refused.
            val previous = history.lastOrNull { it.role == "assistant" }?.content
            if (previous != null && toolsRun == 0 && similar(previous, finalText)) {
                Log.error("model repeated its previous answer without acting")
                finalText += "\n\n---\n_(I repeated myself and did not actually do anything — " +
                    "this model is not following the tool format. Try a stronger one in Settings.)_"
            }

            listener.onFinished(finalText)
        } catch (e: Exception) {
            Log.error("run failed: ${e.message}")
            listener.onFailed(e.message ?: e.toString())
        }
    }

    /** One line saying what a tool actually came back with. */
    private fun summarise(name: String, result: JSONObject): String {
        val blocked = result.optString("blocked")
        if (blocked.isNotEmpty() && blocked != "null") return " — blocked: $blocked"
        if (result.optBoolean("repeat")) return " — already done this request"
        val error = result.optString("error")
        if (error.isNotEmpty() && error != "null") return " — ${error.take(80)}"

        return when (name) {
            "browse_open" -> {
                val chars = result.optInt("chars_total")
                val via = if (result.optBoolean("via_reader")) " via reader" else ""
                " — $chars chars$via"
            }
            "web_search" -> " — ${result.optInt("count")} results from ${result.optString("engine")}"
            "read_messages" -> " — ${result.optInt("count")} messages"
            "find_images" -> " — ${result.optInt("found")} images"
            "delegate", "deep_research" -> " — ${result.optInt("count")} findings"
            "calendar_read" -> " — ${result.optInt("count")} events"
            else -> ""
        }
    }

    /**
     * Does this read as a promise rather than a report?
     *
     * Present continuous and first-person future are the tells, in both
     * languages. Deliberately narrow: mistaking a finished report for a
     * promise would send the agent round again for nothing.
     */
    private val ANNOUNCES = Regex(
        "(ekliyorum|ekleyeyim|ekliyeyim|kaydediyorum|kaydedeyim|oluşturuyorum|olusturuyorum|" +
        "bakıyorum|bakiyorum|arıyorum|ariyorum|yapıyorum|yapiyorum|hallediyorum|ayarlıyorum|" +
        "ayarliyorum|kuruyorum|siliyorum|güncelliyorum|guncelliyorum|" +
        "let me |i'?ll |i will |going to |adding |creating |checking |searching |setting up )",
        RegexOption.IGNORE_CASE)

    private fun announcesAction(prose: String): Boolean {
        val text = prose.trim()
        if (text.isEmpty() || text.length > 400) return false
        return ANNOUNCES.containsMatchIn(text)
    }

    /** Near-identical after collapsing whitespace and case. */
    private fun similar(a: String, b: String): Boolean {
        fun norm(t: String) = t.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = minOf(x.length, y.length)
        val longer = maxOf(x.length, y.length)
        if (shorter.toDouble() / longer < 0.85) return false
        return x.take(shorter).commonPrefixWith(y.take(shorter)).length > shorter * 0.9
    }

    private fun nudgeAfterTurn() {
        sinceMemoryWrite++
        if (sinceMemoryWrite < NUDGE_AFTER) return
        sinceMemoryWrite = 0
        pendingNudge = "[You have gone several exchanges without recording anything. " +
            "If this conversation revealed a durable fact, a preference or a commitment, " +
            "write it with `remember` next turn. If it genuinely revealed nothing worth keeping, ignore this.]"
    }

    /**
     * Compress a long conversation into a dense summary.
     *
     * Context is the scarcest resource on a phone: a long session quietly makes
     * every turn slower and dearer. Keep the decisions and the open threads,
     * drop the retries and the dead ends.
     */
    fun compress(history: List<Store.Turn>): String {
        val transcript = history
            .filter { it.role == "user" || it.role == "assistant" }
            .joinToString("\n\n") { "${it.role}: ${it.content}" }
            .takeLast(30000)
        if (transcript.length < 500) return ""
        return try {
            Llm.complete(listOf(
                "system" to ("Compress this conversation so it can replace the original as context. " +
                    "Keep every decision, every fact established, every open thread, every file or link " +
                    "produced, and the user's stated preferences. Drop pleasantries, retries and dead ends. " +
                    "Write it as notes to yourself, not as a report to the user."),
                "user" to transcript
            ), temperature = 0.2)
        } catch (e: Exception) {
            Log.error("compression failed: ${e.message}")
            ""
        }
    }

    /** Pulls durable facts out of a finished conversation. Runs in the background. */
    fun harvest(history: List<Store.Turn>) {
        if (Store.setting(Store.API_KEY).isBlank()) return
        val transcript = history
            .filter { it.role == "user" || it.role == "assistant" }
            .joinToString("\n\n") { "${it.role}: ${it.content}" }
            .takeLast(12000)
        if (transcript.length < 200) return

        val instruction = """
            Extract durable facts about the user worth remembering months from now: people and their
            roles, recurring commitments, stated preferences, ongoing projects, deadlines. Ignore
            small talk, one-off questions and anything already time-expired.

            Reply with ONLY a JSON array:
            [{"text":"one self-contained sentence","entities":["names mentioned"],"topics":["subject areas"],"importance":0.0-1.0}]

            importance above 0.8 marks something that must never be auto-forgotten. Empty array if
            nothing qualifies.
        """.trimIndent()

        val reply = try {
            Llm.complete(listOf("system" to instruction, "user" to transcript), temperature = 0.0)
        } catch (e: Exception) { return }

        val cleaned = reply.replace("```json", "").replace("```", "").trim()
        val arr = try { JSONArray(cleaned) } catch (e: Exception) { return }
        var saved = 0
        for (i in 0 until minOf(arr.length(), 12)) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text")
            if (text.isBlank()) continue
            val entities = o.optJSONArray("entities")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList()
            val topics = o.optJSONArray("topics")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList()
            if (Store.addMemory(text, entities, topics, o.optDouble("importance", 0.5)) != null) saved++
        }
        if (saved > 0) Log.info("harvested $saved memories")
    }
}
