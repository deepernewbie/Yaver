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

    private fun systemPrompt(context: Context, userText: String, nudge: String): String {
        val name = Store.setting(Store.USER_NAME)
        val persona = Store.setting(Store.PERSONA)
        val memories = Store.recall(userText, 10)
        val allMemories = Store.memories().size
        val now = System.currentTimeMillis()

        val openTasks = Store.tasks().filter { !it.done }
        val taskSummary = if (openTasks.isEmpty()) "(no open tasks)" else
            openTasks.take(8).joinToString("\n") { t ->
                val due = t.due?.let { " — due ${Store.localIso(it)}" } ?: ""
                val urgent = Store.urgency(t, now)?.takeIf { it != "later" }?.let { " [$it]" } ?: ""
                "- ${t.title}$due$urgent"
            }

        return buildString {
            append("You are Yaver — an aide-de-camp running on ")
            append(if (name.isNotBlank()) "$name's" else "the user's")
            append(" phone. The name is Turkish: the officer who keeps a commander's affairs in order, anticipates what is needed and prepares it, but never gives the orders.\n\n")

            append("Current time: ${Store.localIso(now)} (offset ${Store.offsetLabel()}).\n\n")
            append("### The next two weeks\n\n")
            append(dayTable())
            append("\n\nUse that table rather than working weekdays out yourself.\n\n")

            append("**Every time you write is local wall-clock time.** Write `2026-08-11T19:00` for seven in the evening. Never append `Z`, never convert to UTC — the app handles that. Times coming back from tools are local too.\n\n")
            append("Turkish ordinals like \"6'sında\" mean the sixth DAY of the month, never a clock time. A due time and a reminder are different things: \"dinner at 19:00, remind me at 18:00\" is one task due at 19:00 with `remind: 60`.\n\n")

            append("## Tools\n\nCall a tool by emitting exactly:\n<tool_call>\n{\"name\": \"tool_name\", \"arguments\": {...}}\n</tool_call>\n\n")
            append("Rules:\n")
            append("- Emit tool calls alone, with no other prose in that reply. You will get the result and can then continue.\n")
            append("- Several independent calls may go in one reply.\n")
            append("- Never invent a tool result, and never claim you searched or read something you did not.\n\n")
            append("Available tools:\n\n")
            append(Tools.schemaText())
            append("\n\n")

            append("## Saying it is not doing it\n\n")
            append("Announcing an action does not perform it. If you write that you are adding a task or creating an event, emit the tool call in that same reply. Describe what you did in the past tense only after seeing the tool result.\n\n")

            append("## Never invent specifics\n\n")
            append("- Never state a price, date, figure or URL you did not read from a tool result in this conversation.\n")
            append("- Every link must be one that appeared in a tool result. Never build a URL from a pattern.\n")
            append("- Never invent a website address. If search is down, say so and ask which site to try — guessing domain after domain produces confident nonsense.\n")
            append("- When a site blocks you, name it and move on rather than filling the gap from memory.\n\n")

            append("## How to work\n\n")
            append("- Ground answers about the user's world in `list_tasks`, `calendar_read` and memory, not assumptions.\n")
            append("- Search for anything current or verifiable; your training data is stale.\n")
            append("- Use `calculate` for arithmetic instead of doing it in your head.\n")
            append("- Finish the thought the user started: a mentioned meeting wants a calendar entry, a deadline wants a task. Prepare it, say what you inferred, and let them correct you. Never do anything irreversible.\n")
            append("- Keep a profile: `read_profile` then `update_profile` whenever you learn something structural about who they are. It is loaded into every future conversation.\n")
            append("- After finishing anything non-trivial you could be asked for again, write a `create_skill`: the format that worked, the steps, what surprised you. Next time, read it and start where you left off.\n")
            append("- When they refer to something from before, call `search_history` rather than saying you don't remember.\n")
            append("- When an answer has real structure — a comparison, a table, a plan with sections, anything they will want to reread — use `render_html` instead of a long message. It becomes a document they keep.\n")
            append("- Check `read_forwards` when they mention having sent or shared you something.\n")
            append("- Answer in the user's language, and match their register.\n")
            append("- Stop when you have enough. A partial answer with sources beats a perfect one that never arrives.\n\n")

            val profile = Store.profile()
            if (profile.isNotBlank()) {
                append("## Who this person is\n\n")
                append(profile)
                append("\n\n")
            }

            append("## What you know about this person\n\n")
            if (memories.isEmpty()) append("(nothing saved yet)\n")
            else memories.forEach { append("- ${it.text}\n") }
            if (allMemories > memories.size) {
                append("\n($allMemories memories stored in total; those above are the ones relevant to this message. Use `recall` for the rest.)\n")
            }

            val skills = Store.skillIndex()
            if (skills.isNotBlank()) {
                append("\n## Skills you have written\n\n")
                append(skills)
                append("\n\nRead one in full with `read_skill` before following it.\n")
            }

            append("\n## Their open tasks\n\n$taskSummary\n")

            if (persona.isNotBlank()) append("\n## Standing instructions from the user\n\n$persona\n")
                if (nudge.isNotBlank()) append("\n$nudge\n")
        }
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

    /** Removes finished calls and any half-streamed opening tag still arriving. */
    fun stripCalls(text: String): String = text
        .replace(CALL_RE, "")
        .replace(FN_TAG_RE, "")
        .replace(Regex("<(tool_call|function_call|tool_use)>[\\s\\S]*$"), "")
        .replace(Regex("<function\\s*=[\\s\\S]*$"), "")
        .trim()

    // ── the run ──────────────────────────────────────────────────────────────

    fun run(context: Context, userText: String, history: List<Store.Turn>, listener: Listener) {
        Llm.cancelled = false
        val deliveredNudge = pendingNudge
        pendingNudge = ""
        if (deliveredNudge.isNotBlank()) Log.info("memory nudge delivered with this turn")

        val convo = mutableListOf<Pair<String, String>>()
        convo.add("system" to systemPrompt(context, userText, deliveredNudge))
        history.filter { it.role == "user" || it.role == "assistant" }
            .takeLast(20)
            .forEach { convo.add(it.role to it.content) }
        convo.add("user" to userText)

        var finalText = ""
        var toolsRun = 0
        var consecutiveFailures = 0
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
                val calls = parseCalls(text)
                val prose = stripCalls(text)

                if (calls.isEmpty()) {
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
                    Log.tool("${call.name} ${if (failed) "failed" else "ok"} (${ms}ms)")
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

            listener.onFinished(finalText)
        } catch (e: Exception) {
            Log.error("run failed: ${e.message}")
            listener.onFailed(e.message ?: e.toString())
        }
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
