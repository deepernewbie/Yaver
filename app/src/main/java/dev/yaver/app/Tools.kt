package dev.yaver.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * What the agent can actually do.
 *
 * Each tool declares a description and its parameters — that text goes into the
 * system prompt verbatim, so it is written for the model to read, not for a
 * developer. Results come back as JSON objects; anything the user should see on
 * screen sets a `card`, which the UI renders and the model never sees.
 */
object Tools {

    class ToolError(message: String) : Exception(message)

    data class Tool(
        val name: String,
        val description: String,
        val parameters: Map<String, String>,
        val execute: (Context, JSONObject) -> JSONObject
    )

    private fun ok(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        return o
    }

    private fun JSONObject.str(key: String, fallback: String = ""): String {
        val v = optString(key, fallback)
        return if (v == "null") fallback else v
    }

    private fun JSONObject.strings(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    val all: List<Tool> = listOf(

        // ── tasks ────────────────────────────────────────────────────────────

        Tool("add_task",
            "Add an action item. Give it a due time whenever the request implies any deadline at all — the app warns as it approaches.",
            mapOf(
                "title" to "Short imperative title.",
                "due" to "Local datetime, e.g. 2026-08-11T10:00. Never append Z.",
                "remind" to "Minutes before the due time to warn. Default 30.",
                "notes" to "Optional detail.",
                "priority" to "low | normal | high"
            )
        ) { _, args ->
            val title = args.str("title")
            if (title.isBlank()) throw ToolError("title is required")
            val due = Store.parseLocal(args.str("due").ifBlank { null })
            val task = Store.addTask(
                title, due, args.optInt("remind", 30),
                args.str("notes"), args.str("priority", "normal")
            )
            ok("added" to true, "id" to task.id,
                "due" to (task.due?.let { Store.localIso(it) }),
                "note" to (task.due?.let { "Saved for ${Store.localIso(it)} — check that is the time meant." }))
        },

        Tool("list_tasks", "List the user's tasks.",
            mapOf("status" to "open | done | all — default open.")
        ) { _, args ->
            val status = args.str("status", "open")
            val now = System.currentTimeMillis()
            val list = Store.tasks().filter {
                when (status) { "all" -> true; "done" -> it.done; else -> !it.done }
            }
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(JSONObject()
                    .put("id", t.id).put("title", t.title)
                    .put("due", t.due?.let { Store.localIso(it) } ?: JSONObject.NULL)
                    .put("remind_minutes_before", t.remind)
                    .put("priority", t.priority).put("done", t.done)
                    .put("urgency", Store.urgency(t, now) ?: JSONObject.NULL))
            }
            ok("count" to list.size, "timezone" to "local (${Store.offsetLabel()})", "tasks" to arr)
        },

        Tool("update_task",
            "Change an existing task — its time, title, reminder or priority. Use this to move a deadline; never complete a task and create a replacement just to change one field.",
            mapOf(
                "id" to "Task id from list_tasks.",
                "due" to "New local datetime.", "remind" to "Minutes before.",
                "title" to "New title.", "notes" to "New notes.", "priority" to "low | normal | high"
            )
        ) { _, args ->
            val list = Store.tasks()
            val t = list.firstOrNull { it.id == args.str("id") }
                ?: throw ToolError("No task with that id. Call list_tasks first.")
            if (args.has("title")) t.title = args.str("title")
            if (args.has("notes")) t.notes = args.str("notes")
            if (args.has("priority")) t.priority = args.str("priority")
            if (args.has("remind")) t.remind = args.optInt("remind", t.remind)
            if (args.has("due")) t.due = Store.parseLocal(args.str("due").ifBlank { null })
            Store.saveTasks(list)
            ok("updated" to true, "title" to t.title, "due" to (t.due?.let { Store.localIso(it) }))
        },

        Tool("complete_task", "Mark a task done.", mapOf("id" to "Task id from list_tasks.")
        ) { _, args ->
            val list = Store.tasks()
            val t = list.firstOrNull { it.id == args.str("id") }
                ?: throw ToolError("No task with that id.")
            t.done = true
            Store.saveTasks(list)
            ok("done" to true, "title" to t.title)
        },

        Tool("delete_task",
            "Remove a task entirely. Prefer complete_task for something finished — deleting loses the record.",
            mapOf("id" to "Task id from list_tasks.")
        ) { _, args ->
            val list = Store.tasks()
            val t = list.firstOrNull { it.id == args.str("id") }
                ?: throw ToolError("No task with that id.")
            Store.saveTasks(list - t)
            ok("deleted" to true, "title" to t.title)
        },

        // ── memory ───────────────────────────────────────────────────────────

        Tool("remember",
            "Store a durable fact about the user, their people, preferences or commitments. Give entities and topics too — they are what makes it findable later. Memories unused for ${Store.MEMORY_TTL_DAYS} days are dropped unless pinned, important above 0.8, or recalled three times.",
            mapOf(
                "text" to "One self-contained sentence.",
                "entities" to "Array of names in it — people, places, companies.",
                "topics" to "Array of 1–3 subject areas.",
                "importance" to "0 to 1. Above 0.8 is never auto-forgotten."
            )
        ) { _, args ->
            val m = Store.addMemory(
                args.str("text"), args.strings("entities"), args.strings("topics"),
                args.optDouble("importance", 0.5)
            ) ?: throw ToolError("text is required")
            ok("saved" to true, "id" to m.id, "importance" to m.importance)
        },

        Tool("recall",
            "Search long-term memory. Relevant memories are already injected each turn; use this to dig for something specific.",
            mapOf("query" to "What to look for.", "limit" to "Default 8.")
        ) { _, args ->
            val hits = Store.recall(args.str("query"), args.optInt("limit", 8))
            val arr = JSONArray()
            hits.forEach { arr.put(JSONObject().put("text", it.text).put("id", it.id)) }
            ok("count" to hits.size, "memories" to arr)
        },

        Tool("forget",
            "Remove something from memory — a fact that turned out wrong, or that the user asked you to drop.",
            mapOf("query" to "What to forget; the closest match is removed.", "id" to "Exact memory id if known.")
        ) { _, args ->
            val list = Store.memories()
            val target = if (args.str("id").isNotBlank()) {
                list.firstOrNull { it.id == args.str("id") }
            } else {
                Store.recall(args.str("query"), 1, touch = false).firstOrNull()
            } ?: throw ToolError("Nothing in memory matched that.")
            Store.saveMemories(list - target)
            ok("forgotten" to true, "removed" to target.text)
        },

        // ── calendar ─────────────────────────────────────────────────────────

        Tool("calendar_read",
            "Read the phone's real calendar. Use this before answering anything about the user's schedule.",
            mapOf(
                "from" to "Local datetime to start from. Default now.",
                "to" to "Local datetime to stop at. Default 7 days out.",
                "limit" to "Default 50."
            )
        ) { ctx, args ->
            if (!Calendar.canRead(ctx)) {
                ok("needs_permission" to true,
                    "note" to "Calendar permission not granted. A button has been shown to the user — ask them to tap it, then try again.",
                    "card" to JSONObject().put("type", "permission").put("what", "calendar"))
            } else {
                val from = Store.parseLocal(args.str("from")) ?: System.currentTimeMillis()
                val to = Store.parseLocal(args.str("to")) ?: (from + 7 * 86_400_000L)
                val events = Calendar.list(ctx, from, to, args.optInt("limit", 50))
                ok("count" to events.size,
                    "window" to "${Store.localIso(from)} → ${Store.localIso(to)}",
                    "events" to Calendar.toJson(events))
            }
        },

        Tool("calendar_add",
            "Put an event straight into the phone's calendar. No file, no import step.",
            mapOf(
                "title" to "Event title.",
                "start" to "Local datetime, e.g. 2026-08-11T19:00. Never append Z.",
                "end" to "Local datetime. Defaults to start + 1h.",
                "location" to "Optional.", "notes" to "Optional.",
                "remind" to "Minutes before to alert. Default 30."
            )
        ) { ctx, args ->
            val start = Store.parseLocal(args.str("start"))
                ?: throw ToolError("start must be a local datetime like 2026-08-11T19:00")
            val end = Store.parseLocal(args.str("end")) ?: (start + 3_600_000L)
            val id = Calendar.create(ctx, args.str("title", "Event"), start, end,
                args.str("location"), args.str("notes"), args.optInt("remind", 30))
            ok("created" to true, "id" to id, "starts" to Store.localIso(start),
                "note" to "In the calendar now. Check the time is what the user meant.",
                "card" to JSONObject().put("type", "event")
                    .put("title", args.str("title", "Event"))
                    .put("starts", Store.localIso(start)).put("eventId", id))
        },

        Tool("calendar_update", "Move or rename an event already in the calendar.",
            mapOf("id" to "Event id from calendar_read.", "title" to "New title.",
                  "start" to "New local datetime.", "end" to "New local datetime.",
                  "location" to "New location.", "notes" to "New notes.")
        ) { ctx, args ->
            val id = args.optLong("id").takeIf { it > 0 } ?: throw ToolError("id is required")
            val changed = Calendar.update(ctx, id,
                if (args.has("title")) args.str("title") else null,
                Store.parseLocal(args.str("start").ifBlank { null }),
                Store.parseLocal(args.str("end").ifBlank { null }),
                if (args.has("location")) args.str("location") else null,
                if (args.has("notes")) args.str("notes") else null)
            if (!changed) throw ToolError("No event with id $id, or nothing to change.")
            ok("updated" to true, "id" to id)
        },

        Tool("calendar_delete", "Remove an event from the calendar. Confirm with the user first.",
            mapOf("id" to "Event id from calendar_read.")
        ) { ctx, args ->
            val id = args.optLong("id").takeIf { it > 0 } ?: throw ToolError("id is required")
            ok("deleted" to Calendar.delete(ctx, id), "id" to id)
        },

        // ── the web ──────────────────────────────────────────────────────────

        Tool("web_search",
            "Search the web. Results are leads, not answers — open the promising one with browse_open before asserting what it says.",
            mapOf("query" to "Search query.", "max_results" to "Default 6.")
        ) { _, args ->
            val query = args.str("query")
            if (query.isBlank()) throw ToolError("query is required")
            val (results, engine) = Web.search(query, args.optInt("max_results", 6).coerceIn(1, 12))
            if (results.isEmpty()) {
                ok("results" to JSONArray(), "error" to "Every search engine failed or returned nothing usable.",
                    "detail" to engine,
                    "instruction" to "Do NOT guess a website address — invented domains waste turns. Tell the user search failed and ask which site to try.")
            } else {
                val arr = JSONArray()
                results.forEach {
                    arr.put(JSONObject().put("title", it.title).put("url", it.url).put("snippet", it.snippet))
                }
                ok("engine" to engine, "count" to results.size, "results" to arr)
            }
        },

        Tool("browse_open",
            "Open a web page and read it. Only open URLs that came from a search result or from the user — never one you constructed.",
            mapOf("url" to "Full URL.", "chars" to "How much text to return, default 5000.",
                  "offset" to "Start reading from this character, for long pages.")
        ) { _, args ->
            val url = args.str("url")
            if (url.isBlank()) throw ToolError("url is required")
            val page = Web.open(url)
            val offset = args.optInt("offset", 0).coerceAtLeast(0)
            val chars = args.optInt("chars", 5000).coerceIn(200, 20000)
            val slice = page.text.drop(offset).take(chars)
            ok("url" to page.url, "title" to page.title,
                "via_reader" to page.viaReader,
                "chars_total" to page.text.length,
                "more" to (offset + chars < page.text.length),
                "blocked" to page.blocked,
                "instruction" to page.blocked?.let { "This page gave no usable content — $it. Do not retry this URL; find the information elsewhere." },
                "text" to slice)
        },

        // ── things handed to the agent ───────────────────────────────────────

        Tool("read_forwards",
            "Read what the user has shared to Yaver from other apps — links, messages, notes. Check this when they mention having sent you something.",
            mapOf("limit" to "Default 20.")
        ) { _, args ->
            val items = Store.forwards().take(args.optInt("limit", 20))
            if (items.isEmpty()) {
                ok("count" to 0, "note" to "Nothing has been shared to Yaver yet. The user shares from another app's Share menu.")
            } else {
                val arr = JSONArray()
                items.forEach {
                    arr.put(JSONObject().put("time", Store.localIso(it.ts))
                        .put("from", it.from).put("text", it.text))
                }
                ok("count" to items.size, "items" to arr)
            }
        },

        // ── arithmetic ───────────────────────────────────────────────────────

        Tool("calculate",
            "Evaluate an arithmetic expression. Supports + - * / % ( ) and decimals. Use it rather than doing sums in your head — you get those wrong.",
            mapOf("expression" to "e.g. \"1250 * 1.2 + 300\"")
        ) { _, args ->
            val expr = args.str("expression")
            if (expr.isBlank()) throw ToolError("expression is required")
            ok("expression" to expr, "result" to Calc.eval(expr))
        }
    )

    val byName: Map<String, Tool> = all.associateBy { it.name }

    /** The schema block that goes into the system prompt. */
    fun schemaText(): String = all.joinToString("\n\n") { t ->
        val params = t.parameters.entries.joinToString("\n") { "    ${it.key}: ${it.value}" }
        "- ${t.name}: ${t.description}" + if (params.isNotEmpty()) "\n$params" else ""
    }

    fun run(context: Context, name: String, args: JSONObject): JSONObject {
        val tool = byName[name] ?: return ok(
            "error" to "No tool named \"$name\".",
            "available" to JSONArray(all.map { it.name })
        )
        return try {
            tool.execute(context, args)
        } catch (e: ToolError) {
            ok("error" to e.message)
        } catch (e: Exception) {
            Log.error("$name failed: ${e.message}")
            ok("error" to (e.message ?: e.toString()))
        }
    }
}

/**
 * A small expression evaluator.
 *
 * Android has no JavaScript engine outside a WebView, and pulling one in for
 * arithmetic would be absurd. This is a plain recursive-descent parser: enough
 * for the sums that actually come up, and it cannot execute anything.
 */
object Calc {

    fun eval(expression: String): Double {
        val p = Parser(expression.replace(",", "."))
        val v = p.expression()
        p.skipSpace()
        if (!p.done) throw Tools.ToolError("Could not parse the expression at position ${p.pos}")
        return v
    }

    private class Parser(val src: String) {
        var pos = 0
        val done get() = pos >= src.length

        fun skipSpace() { while (pos < src.length && src[pos] == ' ') pos++ }

        fun expression(): Double {
            var value = term()
            while (true) {
                skipSpace()
                if (done) return value
                when (src[pos]) {
                    '+' -> { pos++; value += term() }
                    '-' -> { pos++; value -= term() }
                    else -> return value
                }
            }
        }

        fun term(): Double {
            var value = factor()
            while (true) {
                skipSpace()
                if (done) return value
                when (src[pos]) {
                    '*' -> { pos++; value *= factor() }
                    '/' -> {
                        pos++
                        val d = factor()
                        if (d == 0.0) throw Tools.ToolError("Division by zero")
                        value /= d
                    }
                    '%' -> {
                        pos++
                        val d = factor()
                        if (d == 0.0) throw Tools.ToolError("Division by zero")
                        value %= d
                    }
                    else -> return value
                }
            }
        }

        fun factor(): Double {
            skipSpace()
            if (done) throw Tools.ToolError("Expression ends unexpectedly")
            return when {
                src[pos] == '(' -> {
                    pos++
                    val v = expression()
                    skipSpace()
                    if (done || src[pos] != ')') throw Tools.ToolError("Missing closing bracket")
                    pos++
                    v
                }
                src[pos] == '-' -> { pos++; -factor() }
                src[pos] == '+' -> { pos++; factor() }
                else -> number()
            }
        }

        fun number(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (start == pos) throw Tools.ToolError("Expected a number at position $pos")
            return src.substring(start, pos).toDoubleOrNull()
                ?: throw Tools.ToolError("\"${src.substring(start, pos)}\" is not a number")
        }
    }
}
