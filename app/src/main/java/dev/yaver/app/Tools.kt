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
        val group: String = "core",
        val execute: (Context, JSONObject) -> JSONObject
    )

    /**
     * Tools are grouped and only the group names are in the prompt.
     *
     * Listing every schema every turn cost about ten thousand tokens before a
     * word was written — the same mistake as reading a whole codebase to answer
     * one question about it. Worse, it made the prompt change on every turn,
     * which throws away any hope of a cached prefix. Now the agent opens the
     * drawer it needs; the schemas arrive in the conversation and stay there.
     */
    val GROUPS = linkedMapOf(
        "calendar" to "read, add, move and delete events in the phone's calendar",
        "messages" to "read captured WhatsApp and SMS messages, draft replies",
        "browser" to "drive a real browser: click, type, scroll, dismiss banners",
        "files" to "write, read and render documents the user keeps",
        "goals" to "long-running work tracked across conversations",
        "memory" to "recall, forget, consolidate; the profile and past conversations",
        "media" to "find photographs, look up places and addresses, current location",
        "research" to "parallel sub-agents for questions with several separate parts",
        "skills" to "write and read your own procedures for recurring jobs",
        "routines" to "recurring jobs that notify at a set time"
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

        Tool("open_tools",
            "Open a group of tools you need. Their full descriptions arrive in the result and stay available for the rest of this conversation. Open a group before using anything in it — one extra step, and it keeps every turn small and fast.",
            mapOf("group" to "One of the group names listed in your instructions.")
        ) { _, args ->
            val group = args.str("group").lowercase(Locale.ROOT)
            if (group !in GROUPS) {
                ok("error" to "No group called \"$group\".",
                    "available" to JSONArray(GROUPS.keys.toList()))
            } else {
                ok("group" to group,
                    "tools" to schemaText(group),
                    "note" to "These are now available. Do not open this group again.")
            }
        },

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
        ) { ctx, args ->
            val title = args.str("title")
            if (title.isBlank()) throw ToolError("title is required")
            val due = Store.parseLocal(args.str("due").ifBlank { null })
            val task = Store.addTask(
                title, due, args.optInt("remind", 30),
                args.str("notes"), args.str("priority", "normal")
            )
            Reminders.scheduleTask(ctx, task)
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
        ) { ctx, args ->
            val list = Store.tasks()
            val t = list.firstOrNull { it.id == args.str("id") }
                ?: throw ToolError("No task with that id. Call list_tasks first.")
            if (args.has("title")) t.title = args.str("title")
            if (args.has("notes")) t.notes = args.str("notes")
            if (args.has("priority")) t.priority = args.str("priority")
            if (args.has("remind")) t.remind = args.optInt("remind", t.remind)
            if (args.has("due")) t.due = Store.parseLocal(args.str("due").ifBlank { null })
            Store.saveTasks(list)
            Reminders.scheduleTask(ctx, t)
            ok("updated" to true, "title" to t.title, "due" to (t.due?.let { Store.localIso(it) }))
        },

        Tool("complete_task", "Mark a task done.", mapOf("id" to "Task id from list_tasks.")
        ) { ctx, args ->
            val list = Store.tasks()
            val t = list.firstOrNull { it.id == args.str("id") }
                ?: throw ToolError("No task with that id.")
            t.done = true
            Store.saveTasks(list)
            Reminders.cancelTask(ctx, t.id)
            ok("done" to true, "title" to t.title)
        },

        Tool("delete_task",
            "Remove a task entirely. Prefer complete_task for something finished — deleting loses the record.",
            mapOf("id" to "Task id from list_tasks.")
        ) { ctx, args ->
            val list = Store.tasks()
            val t = list.firstOrNull { it.id == args.str("id") }
                ?: throw ToolError("No task with that id.")
            Store.saveTasks(list - t)
            Reminders.cancelTask(ctx, t.id)
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
            mapOf("query" to "What to forget; the closest match is removed.", "id" to "Exact memory id if known."),
            group = "memory"
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
            ),
            group = "calendar"
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
            ),
            group = "calendar"
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
                  "location" to "New location.", "notes" to "New notes."),
            group = "calendar"
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
            mapOf("id" to "Event id from calendar_read."),
            group = "calendar"
        ) { ctx, args ->
            val id = args.optLong("id").takeIf { it > 0 } ?: throw ToolError("id is required")
            ok("deleted" to Calendar.delete(ctx, id), "id" to id)
        },

        // ── who this person is ───────────────────────────────────────────────

        Tool("read_profile",
            "Read your working description of the user before rewriting it.",
            mapOf(),
            group = "memory"
        ) { _, _ ->
            val text = Store.profile()
            ok("profile" to text.ifBlank { "(empty — nothing recorded about this person yet)" })
        },

        Tool("update_profile",
            "Rewrite your description of this person: who they are, what they are working on, how they like things done. Read it first, then return the whole improved version. It is loaded into every conversation, so it is the most valuable thing you maintain — but keep it short and factual.",
            mapOf(
                "content" to "The complete profile in markdown. Replaces the old one entirely.",
                "because" to "What in this conversation prompted the change. Required."
            ),
            group = "memory"
        ) { _, args ->
            val content = args.str("content")
            if (content.trim().length < 10) throw ToolError("content is required")
            val why = args.str("because")
            if (why.isBlank()) {
                throw ToolError("Say what prompted this change in `because` — a profile that rewrites itself with no trail cannot be debugged when it goes wrong.")
            }
            Store.saveProfile(content)
            Store.recordRevision("profile", why)
            ok("saved" to true, "chars" to content.length)
        },

        Tool("search_history",
            "Search past conversations. Use this when the user refers to something you discussed before rather than admitting you don't remember.",
            mapOf("query" to "Words that would have appeared in that conversation.", "limit" to "Default 6."),
            group = "memory"
        ) { _, args ->
            val hits = Store.searchSessions(args.str("query"), args.optInt("limit", 6))
            if (hits.isEmpty()) {
                ok("found" to 0, "note" to "Nothing in past conversations matched.")
            } else {
                val arr = JSONArray()
                hits.forEach { (info, excerpt) ->
                    arr.put(JSONObject()
                        .put("when", Store.localIso(info.updated).replace("T", " ").take(16))
                        .put("title", info.title)
                        .put("excerpt", excerpt))
                }
                ok("found" to hits.size, "conversations" to arr)
            }
        },

        // ── skills: how this person likes a job done ─────────────────────────

        Tool("create_skill",
            "Write down how to do a job the way this user wants it done, so you do it the same way next time. Create one after finishing anything non-trivial you might be asked for again — a report format they liked, a checklist, a routine they follow. Reuse the same name to rewrite an existing skill.",
            mapOf(
                "name" to "Short identifier, e.g. \"weekly-review\".",
                "title" to "One line: what this skill does.",
                "when" to "One line: when to use it. This is what you match against later.",
                "body" to "The procedure in markdown — steps, format, preferences, anything that surprised you.",
                "because" to "What made this worth writing down."
            ),
            group = "skills"
        ) { _, args ->
            val body = args.str("body")
            if (body.isBlank()) throw ToolError("body is required")
            val name = args.str("name").ifBlank { args.str("title") }
            if (name.isBlank()) throw ToolError("name or title is required")
            val file = Store.saveSkill(name, args.str("title", name), args.str("when"), body)
            Store.recordRevision("skill:$name", args.str("because").ifBlank { "no reason given" })
            ok("saved" to true, "file" to file,
                "note" to "It is listed in your instructions from now on.")
        },

        Tool("list_skills", "List the skills you have written, with what each is for.", mapOf(),
            group = "skills"
        ) { _, _ ->
            val list = Store.skills()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("name", it.name).put("title", it.title).put("when", it.useWhen))
            }
            ok("count" to list.size, "skills" to arr)
        },

        Tool("read_skill",
            "Read a skill in full before following it. The index in your instructions carries only titles.",
            mapOf("name" to "Skill name from list_skills."),
            group = "skills"
        ) { _, args ->
            val skill = Store.readSkill(args.str("name"))
                ?: throw ToolError("No skill by that name. Call list_skills.")
            ok("name" to skill.name, "content" to skill.body)
        },

        Tool("delete_skill", "Remove a skill that turned out wrong or is no longer wanted.",
            mapOf("name" to "Skill name."),
            group = "skills"
        ) { _, args ->
            ok("deleted" to Store.deleteSkill(args.str("name")))
        },

        // ── tending the memory store ─────────────────────────────────────────

        Tool("memory_status",
            "See how memory is doing: how much is stored, and what is about to be forgotten through disuse.",
            mapOf(),
            group = "memory"
        ) { _, _ ->
            val all = Store.memories()
            val fading = all.filter {
                !Store.memoryProtected(it) && Store.memoryIdleDays(it) >= Store.MEMORY_WARN_DAYS
            }.sortedByDescending { Store.memoryIdleDays(it) }
            val arr = JSONArray()
            fading.take(10).forEach { m ->
                arr.put(JSONObject()
                    .put("text", m.text)
                    .put("unused_days", Store.memoryIdleDays(m))
                    .put("deleted_in_days", Store.MEMORY_TTL_DAYS - Store.memoryIdleDays(m)))
            }
            ok("stored" to all.size,
                "pinned" to all.count { it.pinned },
                "fading" to fading.size,
                "about_to_go" to arr,
                "note" to if (fading.isEmpty()) "Nothing is fading."
                          else "Tell the user which of these are worth keeping — recalling one keeps it alive.")
        },

        Tool("consolidate_memory",
            "Tidy the memory store: merge duplicates, correct anything now out of date, note connections. Do this when memory looks messy or contradictory, or when asked.",
            mapOf("topic" to "Optional — only consolidate memories about this."),
            group = "memory"
        ) { _, args ->
            val topic = args.str("topic")
            val list = if (topic.isBlank()) Store.memories().take(60)
                       else Store.recall(topic, 60, touch = false)
            if (list.size < 2) {
                ok("note" to "Not enough memories to consolidate.")
            } else {
                val arr = JSONArray()
                list.forEach { m ->
                    arr.put(JSONObject()
                        .put("id", m.id).put("text", m.text)
                        .put("entities", JSONArray(m.entities))
                        .put("importance", m.importance).put("uses", m.uses)
                        .put("age_days", (System.currentTimeMillis() - m.created) / 86_400_000L))
                }
                ok("count" to list.size, "memories" to arr,
                    "instruction" to "Review these. For anything duplicated or superseded, call `forget` on the weaker one and `remember` a single merged version. For anything now wrong, forget it. Leave good memories alone — churn is worse than clutter. Report briefly what you changed.")
            }
        },

        // ── long-running work ────────────────────────────────────────────────

        Tool("set_goal",
            "Record something being pursued over weeks and many conversations — finding a flat, planning a trip, getting something signed. Different from a task: a task gets done, a goal gets progressed. It is loaded into every conversation, so the next one starts informed.",
            mapOf("title" to "Short name.", "detail" to "What success looks like, and any constraints."),
            group = "goals"
        ) { _, args ->
            val title = args.str("title")
            if (title.isBlank()) throw ToolError("title is required")
            val list = Store.goals()
            val now = System.currentTimeMillis()
            val goal = Store.Goal(Store.newId(), title, args.str("detail"), "active", now, now, mutableListOf())
            list.add(goal)
            Store.saveGoals(list)
            ok("added" to true, "id" to goal.id)
        },

        Tool("note_progress",
            "Add what you learned or did towards a goal. Write it as evidence — what happened, what it means, what is still open — so a later conversation can pick it up without asking again.",
            mapOf("id" to "Goal id from list_goals.", "note" to "What changed.",
                  "status" to "Optional: active | paused | done."),
            group = "goals"
        ) { _, args ->
            val list = Store.goals()
            val goal = list.firstOrNull { it.id == args.str("id") }
                ?: throw ToolError("No goal with that id. Call list_goals first.")
            val note = args.str("note")
            if (note.isNotBlank()) goal.notes.add(System.currentTimeMillis() to note)
            args.str("status").takeIf { it in listOf("active", "paused", "done") }?.let { goal.status = it }
            goal.updated = System.currentTimeMillis()
            Store.saveGoals(list)
            ok("noted" to true, "title" to goal.title, "status" to goal.status,
                "notes_total" to goal.notes.size)
        },

        Tool("list_goals", "What is being pursued, and where each stands.",
            mapOf("status" to "active | all. Default active."),
            group = "goals"
        ) { _, args ->
            val all = args.str("status", "active") == "all"
            val list = Store.goals().filter { all || it.status == "active" }
            val arr = JSONArray()
            list.forEach { g ->
                val recent = JSONArray()
                g.notes.takeLast(5).forEach { (at, text) ->
                    recent.put("${Store.localIso(at).take(10)}: $text")
                }
                arr.put(JSONObject()
                    .put("id", g.id).put("title", g.title).put("detail", g.detail)
                    .put("status", g.status)
                    .put("last_touched", Store.localIso(g.updated).take(10))
                    .put("recent_notes", recent))
            }
            ok("count" to list.size, "goals" to arr)
        },

        // ── recurring work ───────────────────────────────────────────────────

        Tool("add_routine",
            "Set something to run regularly — a morning brief, a weekly review. At the given time a notification appears; tapping it opens Yaver and runs the prompt. It does not run silently in the background: waking to find an agent has spent your tokens unasked is not a pleasant surprise.",
            mapOf(
                "name" to "Short name.",
                "prompt" to "Exactly what to do, written as if the user had just asked you.",
                "every" to "daily | weekdays | weekly",
                "hour" to "Hour of day, 0-23, local time.",
                "minute" to "Minute, default 0."
            ),
            group = "routines"
        ) { ctx, args ->
            val prompt = args.str("prompt")
            if (prompt.isBlank()) throw ToolError("prompt is required")
            val list = Store.routines()
            val routine = Store.Routine(
                id = Store.newId(),
                name = args.str("name").ifBlank { prompt.take(40) },
                prompt = prompt,
                every = args.str("every", "daily"),
                hour = args.optInt("hour", 8).coerceIn(0, 23),
                minute = args.optInt("minute", 0).coerceIn(0, 59),
                lastRun = 0
            )
            list.add(routine)
            Store.saveRoutines(list)
            Reminders.scheduleRoutine(ctx, routine)
            val next = routine.nextFireAt()
            ok("added" to true, "id" to routine.id,
                "next" to (next?.let { Store.localIso(it) }),
                "note" to "You will be notified; tapping the notification runs it.")
        },

        Tool("list_routines", "List the recurring jobs that are set up.", mapOf(),
            group = "routines"
        ) { _, _ ->
            val list = Store.routines()
            val arr = JSONArray()
            list.forEach { r ->
                arr.put(JSONObject()
                    .put("id", r.id).put("name", r.name)
                    .put("every", r.every)
                    .put("at", String.format(Locale.US, "%02d:%02d", r.hour, r.minute))
                    .put("next", r.nextFireAt()?.let { Store.localIso(it) } ?: "never"))
            }
            ok("count" to list.size, "routines" to arr)
        },

        Tool("delete_routine", "Remove a recurring job.", mapOf("id" to "Routine id from list_routines."),
            group = "routines"
        ) { ctx, args ->
            val id = args.str("id")
            val list = Store.routines()
            val r = list.firstOrNull { it.id == id } ?: throw ToolError("No routine with that id.")
            Store.saveRoutines(list - r)
            Reminders.cancelRoutine(ctx, id)
            ok("deleted" to true, "name" to r.name)
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

        // ── what came in ─────────────────────────────────────────────────────

        Tool("read_messages",
            "Read messages captured from WhatsApp and other messaging apps. Use this for \"what came in\", \"what did X say\", \"what needs me today\". Only messages sent TO the user are here — never their own, and nothing from muted chats.",
            mapOf(
                "since" to "Local datetime to read from. Default: the last 24 hours.",
                "chat" to "Optional — only this conversation, matched loosely.",
                "limit" to "Default 60."
            ),
            group = "messages"
        ) { _, args ->
            if (!NotificationCapture.isEnabled()) {
                ok("capture_off" to true,
                    "note" to "Message capture is switched off. Tell the user to turn it on under More → Messages; it needs notification access.")
            } else {
                val since = Store.parseLocal(args.str("since"))
                    ?: (System.currentTimeMillis() - 86_400_000L)
                val list = Store.messages(since, args.str("chat"), args.optInt("limit", 60))
                if (list.isEmpty()) {
                    ok("count" to 0,
                        "note" to "Nothing captured in that window. Messages only arrive when they raise a notification, so anything read on the phone first, or from a muted chat, is missed.")
                } else {
                    val arr = JSONArray()
                    list.forEach { m ->
                        arr.put(JSONObject()
                            .put("at", Store.localIso(m.ts).replace("T", " ").take(16))
                            .put("chat", m.chat)
                            .put("from", m.sender)
                            .put("text", m.text))
                    }
                    ok("count" to list.size, "messages" to arr)
                }
            }
        },

        Tool("list_chats", "Which conversations have been active, and how much.",
            mapOf("since" to "Local datetime. Default: the last 7 days."),
            group = "messages"
        ) { _, args ->
            val since = Store.parseLocal(args.str("since"))
                ?: (System.currentTimeMillis() - 7 * 86_400_000L)
            val arr = JSONArray()
            Store.chats(since).take(30).forEach { (chat, count) ->
                arr.put(JSONObject().put("chat", chat).put("messages", count))
            }
            ok("count" to arr.length(), "chats" to arr)
        },

        Tool("draft_message",
            "Prepare a reply for the user to send. It appears as a card with the text and a button that opens WhatsApp with the message already typed. You never send anything — they press send.",
            mapOf(
                "to" to "Who it is for: a name, or a phone number in international form without + or spaces.",
                "text" to "The message itself, in the user's own voice."
            ),
            group = "messages"
        ) { _, args ->
            val text = args.str("text")
            if (text.isBlank()) throw ToolError("text is required")
            val to = args.str("to")
            val digits = to.filter { it.isDigit() }
            val url = if (digits.length >= 8) {
                "https://wa.me/$digits?text=" + java.net.URLEncoder.encode(text, "UTF-8")
            } else {
                "https://wa.me/?text=" + java.net.URLEncoder.encode(text, "UTF-8")
            }
            ok("drafted" to true,
                "note" to "Shown to the user with a send button. Nothing has been sent.",
                "card" to JSONObject().put("type", "draft")
                    .put("to", to).put("text", text).put("url", url))
        },

        // ── the browser ──────────────────────────────────────────────────────

        Tool("browser_open",
            "Open a page in a real browser and read it as rendered. Use this instead of browse_open whenever the content is built by JavaScript, needs cookies dismissed, or needs interacting with — search boxes, filters, tabs, \"load more\". You get the visible text plus a numbered list of everything you can click or type into.",
            mapOf("url" to "Full URL, from a search result or from the user."),
            group = "browser"
        ) { _, args ->
            val url = args.str("url")
            if (url.isBlank()) throw ToolError("url is required")
            if (!Browser.isReady()) throw ToolError("The browser is not running. Use browse_open instead.")
            val state = Browser.open(url)
            Browser.toJson(state, 8000).put("instruction",
                "Act by element number: browser_click, browser_type. If a cookie wall is in the way, call browser_dismiss_consent first.")
        },

        Tool("browser_state",
            "Re-read the current page — its text and the numbered elements. Use after something on the page has changed.",
            mapOf("chars" to "How much text, default 8000."),
            group = "browser"
        ) { _, args ->
            if (!Browser.isReady()) throw ToolError("The browser is not running.")
            Browser.toJson(Browser.state(), args.optInt("chars", 8000).coerceIn(500, 24000))
        },

        Tool("browser_click",
            "Click a numbered element on the current page, then read what changed.",
            mapOf("index" to "The number from the element list."),
            group = "browser"
        ) { _, args ->
            if (!Browser.isReady()) throw ToolError("The browser is not running.")
            val index = args.optInt("index", -1)
            if (index < 0) throw ToolError("index is required")
            Browser.toJson(Browser.click(index), 8000)
        },

        Tool("browser_type",
            "Type into a numbered field — a search box, a form. Set submit to true to press Enter afterwards.",
            mapOf(
                "index" to "The number from the element list.",
                "text" to "What to type.",
                "submit" to "true to submit the form afterwards. Default false."
            ),
            group = "browser"
        ) { _, args ->
            if (!Browser.isReady()) throw ToolError("The browser is not running.")
            val index = args.optInt("index", -1)
            if (index < 0) throw ToolError("index is required")
            Browser.toJson(Browser.type(index, args.str("text"), args.optBoolean("submit", false)), 8000)
        },

        Tool("browser_scroll",
            "Scroll the page. Many sites load more content only as you go down.",
            mapOf("pages" to "Screens to scroll; negative goes up. Default 1."),
            group = "browser"
        ) { _, args ->
            if (!Browser.isReady()) throw ToolError("The browser is not running.")
            Browser.toJson(Browser.scroll(args.optInt("pages", 1).coerceIn(-5, 5)), 8000)
        },

        Tool("browser_back", "Go back to the previous page.", mapOf(),
            group = "browser"
        ) { _, _ ->
            if (!Browser.isReady()) throw ToolError("The browser is not running.")
            Browser.toJson(Browser.back(), 8000)
        },

        Tool("browser_dismiss_consent",
            "Try to close a cookie or consent banner. Worth one call when a page looks empty or blocked by an overlay.",
            mapOf(),
            group = "browser"
        ) { _, _ ->
            if (!Browser.isReady()) throw ToolError("The browser is not running.")
            Browser.toJson(Browser.dismissConsent(), 8000)
        },

        Tool("browser_show",
            "Put the browser on screen so the user can act themselves — signing in, solving a check, choosing something. Their session is kept, so afterwards you can carry on from the same page.",
            mapOf("why" to "One line telling the user what they need to do."),
            group = "browser"
        ) { _, args ->
            if (!Browser.isReady()) throw ToolError("The browser is not running.")
            ok("shown" to true,
                "note" to "The browser has been opened for the user. Wait for them to say they are done, then call browser_state.",
                "card" to JSONObject().put("type", "browser").put("why",
                    args.str("why", "Take a look and do what is needed, then close it.")))
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

        // ── showing things ───────────────────────────────────────────────────

        Tool("find_images",
            "Find photographs of something and show them to the user. Searches Wikimedia Commons, so it is good for species, places, buildings and objects, and poor for products and people. Never describe a picture you have not fetched.",
            mapOf(
                "query" to "What to picture, in English and as specific as possible. Scientific names work best for species.",
                "count" to "1 to 4. Default 3."
            ),
            group = "media"
        ) { _, args ->
            val query = args.str("query")
            if (query.isBlank()) throw ToolError("query is required")
            val found = Web.images(query, args.optInt("count", 3).coerceIn(1, 4))
            if (found.isEmpty()) {
                ok("found" to 0, "note" to "No pictures found. Try the scientific name, or a broader term.")
            } else {
                val arr = JSONArray()
                val cards = JSONArray()
                found.forEach { img ->
                    arr.put(JSONObject().put("title", img.title)
                        .put("credit", img.credit).put("licence", img.licence))
                    cards.put(JSONObject().put("title", img.title).put("url", img.url)
                        .put("credit", img.credit).put("licence", img.licence))
                }
                ok("found" to found.size, "images" to arr,
                    "note" to "Shown to the user. Credit the photographers in your reply.",
                    "card" to JSONObject().put("type", "images").put("items", cards))
            }
        },

        Tool("where_am_i",
            "Find out roughly where the user is. Call this before any \"near me\" search rather than asking them where they are.",
            mapOf(),
            group = "media"
        ) { ctx, _ ->
            if (!Whereabouts.granted(ctx)) {
                ok("needs_permission" to true,
                    "note" to "Location permission not granted. A button has been shown — ask them to tap it, then try again.",
                    "card" to JSONObject().put("type", "permission").put("what", "location"))
            } else {
                val fix = Whereabouts.current(ctx)
                if (fix == null) {
                    ok("found" to false,
                        "note" to "No location available — location services may be switched off on the phone.")
                } else {
                    // A place name is far more useful to a model than a pair of
                    // numbers, and it is one request away.
                    val place = Web.reverseGeocode(fix.latitude, fix.longitude)
                    ok("latitude" to fix.latitude, "longitude" to fix.longitude,
                        "accuracy_metres" to fix.accuracyMetres.toInt(),
                        "fix_age_minutes" to fix.ageMinutes,
                        "place" to (place ?: "unknown"),
                        "note" to "Use this with show_places for anything nearby.")
                }
            }
        },

        Tool("show_places",
            "Look up real addresses and coordinates and show them to the user with a button that opens their maps app. Use this rather than stating an address from memory — invented addresses are the single most damaging kind of mistake here.",
            mapOf("query" to "What to find, e.g. \"pharmacy\" or a specific place name.",
                  "near_me" to "true to search around the user's current position.",
                  "limit" to "1 to 5. Default 3."),
            group = "media"
        ) { ctx, args ->
            val query = args.str("query")
            if (query.isBlank()) throw ToolError("query is required")
            val limit = args.optInt("limit", 3).coerceIn(1, 5)
            val places = if (args.optBoolean("near_me", false)) {
                val fix = Whereabouts.current(ctx)
                if (fix == null) Web.geocode(query, limit)
                else Web.geocodeNear(query, fix.latitude, fix.longitude, limit)
            } else {
                Web.geocode(query, limit)
            }
            if (places.isEmpty()) {
                ok("found" to 0, "note" to "Nothing matched. Do not invent an address — ask the user to be more specific.")
            } else {
                val arr = JSONArray()
                places.forEach { p ->
                    arr.put(JSONObject().put("name", p.name).put("address", p.address)
                        .put("lat", p.lat).put("lon", p.lon))
                }
                ok("found" to places.size, "places" to arr,
                    "card" to JSONObject().put("type", "places").put("items", arr))
            }
        },

        // ── investigating in parallel ────────────────────────────────────────

        Tool("delegate",
            "Hand two to four independent questions to sub-agents that research them at the same time and report back. Use it when a request contains several separate investigations — comparing options, checking a few places, gathering different kinds of fact. Each task must stand alone; they cannot see each other's work.",
            mapOf("tasks" to "Array of self-contained task descriptions, 2 to 4."),
            group = "research"
        ) { ctx, args ->
            val raw = args.optJSONArray("tasks")
                ?: throw ToolError("tasks must be an array of task descriptions")
            val tasks = (0 until raw.length()).map { i ->
                // Models pass strings or {task}/{description} objects.
                val item = raw.opt(i)
                when (item) {
                    is String -> item
                    is JSONObject -> item.optString("task").ifBlank {
                        item.optString("description").ifBlank { item.optString("goal") }
                    }
                    else -> item?.toString() ?: ""
                }
            }.filter { it.isNotBlank() }
            if (tasks.size < 2) throw ToolError("Give at least two independent tasks, or just do it yourself.")

            val findings = SubAgent.runMany(ctx, tasks) { Log.info(it) }
            ok("count" to findings.size,
                "findings" to SubAgent.findingsJson(findings),
                "instruction" to "Synthesise these into one answer. Say plainly which parts nobody could verify.")
        },

        Tool("deep_research",
            "Investigate one broad question properly: it is split into independent sub-questions, researched in parallel, and reported back. Slow and expensive — use it when the user asks for real research, not for a quick lookup.",
            mapOf("question" to "The question to investigate.", "breadth" to "How many sub-questions, 2 to 4. Default 3."),
            group = "research"
        ) { ctx, args ->
            val question = args.str("question")
            if (question.isBlank()) throw ToolError("question is required")
            val subs = SubAgent.planQuestions(question, args.optInt("breadth", 3))
            Log.info("deep research: ${subs.size} lines of enquiry")
            val findings = SubAgent.runMany(ctx, subs) { Log.info(it) }
            ok("question" to question,
                "sub_questions" to JSONArray(subs),
                "findings" to SubAgent.findingsJson(findings),
                "instruction" to "Write the answer from these findings. Keep what was verified separate from what was not. Consider render_html if the answer has structure.")
        },

        // ── things the user keeps ────────────────────────────────────────────

        Tool("render_html",
            "Show the user a formatted page: a report, a comparison, a table, a summary with structure. It appears in the conversation as a card they can open full screen, and is saved so they can reopen it later. Write a complete, self-contained HTML document with its own inline CSS — no external stylesheets or scripts, they will not load.",
            mapOf(
                "title" to "Short title for the card.",
                "html" to "A complete HTML document.",
                "save_as" to "Optional filename, e.g. \"casio-comparison.html\"."
            ),
            group = "files"
        ) { _, args ->
            val html = args.str("html")
            if (html.trim().length < 20) throw ToolError("html is required")
            val title = args.str("title", "Report")
            val name = args.str("save_as").ifBlank {
                title.replace(Regex("[^A-Za-z0-9]+"), "-").lowercase(Locale.ROOT).take(40) + ".html"
            }
            val path = Store.writeArtifact(if (name.endsWith(".html")) name else "$name.html", html)
            ok("rendered" to true, "saved" to path,
                "note" to "Shown on screen and saved. Tell the user it is under Files.",
                "card" to JSONObject().put("type", "html").put("title", title).put("path", path))
        },

        Tool("write_file",
            "Save something the user will keep and reread — a note, a list, a draft. For anything with structure prefer render_html; use this for plain text and markdown.",
            mapOf("name" to "Filename, e.g. \"packing-list.md\".", "content" to "The file contents."),
            group = "files"
        ) { _, args ->
            val content = args.str("content")
            if (content.isEmpty()) throw ToolError("content is required")
            val path = Store.writeArtifact(args.str("name", "note.md"), content)
            ok("written" to true, "path" to path, "chars" to content.length,
                "card" to JSONObject().put("type", "file").put("title", args.str("name", "note.md")).put("path", path))
        },

        Tool("read_file", "Read something you saved earlier.",
            mapOf("path" to "Path from list_files or from an earlier write."),
            group = "files"
        ) { _, args ->
            val path = args.str("path")
            val text = Store.readText(path) ?: Store.readText("${Store.ARTIFACT_DIR}/$path")
                ?: throw ToolError("No file at $path. Call list_files.")
            ok("path" to path, "chars" to text.length, "text" to text.take(20000))
        },

        Tool("list_files", "List what you have saved for the user.", mapOf(),
            group = "files"
        ) { _, _ ->
            val list = Store.artifacts()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("path", it.path).put("name", it.name).put("chars", it.bytes))
            }
            ok("count" to list.size, "files" to arr)
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

    private fun render(tools: List<Tool>): String = tools.joinToString("\n\n") { t ->
        val params = t.parameters.entries.joinToString("\n") { "    ${it.key}: ${it.value}" }
        "- ${t.name}: ${t.description}" + if (params.isNotEmpty()) "\n$params" else ""
    }

    /** The always-available tools, written into the stable part of the prompt. */
    fun coreSchema(): String = render(all.filter { it.group == "core" })

    fun schemaText(group: String): String = render(all.filter { it.group == group })

    /** One line per drawer — what the agent sees instead of forty schemas. */
    fun groupIndex(): String = GROUPS.entries.joinToString("\n") { (name, what) ->
        val count = all.count { it.group == name }
        "- $name ($count tools): $what"
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
