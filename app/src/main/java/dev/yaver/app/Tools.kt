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

        // ── who this person is ───────────────────────────────────────────────

        Tool("read_profile",
            "Read your working description of the user before rewriting it.",
            mapOf()
        ) { _, _ ->
            val text = Store.profile()
            ok("profile" to text.ifBlank { "(empty — nothing recorded about this person yet)" })
        },

        Tool("update_profile",
            "Rewrite your description of this person: who they are, what they are working on, how they like things done. Read it first, then return the whole improved version. It is loaded into every conversation, so it is the most valuable thing you maintain — but keep it short and factual.",
            mapOf("content" to "The complete profile in markdown. Replaces the old one entirely.")
        ) { _, args ->
            val content = args.str("content")
            if (content.trim().length < 10) throw ToolError("content is required")
            Store.saveProfile(content)
            ok("saved" to true, "chars" to content.length)
        },

        Tool("search_history",
            "Search past conversations. Use this when the user refers to something you discussed before rather than admitting you don't remember.",
            mapOf("query" to "Words that would have appeared in that conversation.", "limit" to "Default 6.")
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
                "body" to "The procedure in markdown — steps, format, preferences, anything that surprised you."
            )
        ) { _, args ->
            val body = args.str("body")
            if (body.isBlank()) throw ToolError("body is required")
            val name = args.str("name").ifBlank { args.str("title") }
            if (name.isBlank()) throw ToolError("name or title is required")
            val file = Store.saveSkill(name, args.str("title", name), args.str("when"), body)
            ok("saved" to true, "file" to file,
                "note" to "It is listed in your instructions from now on.")
        },

        Tool("list_skills", "List the skills you have written, with what each is for.", mapOf()
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
            mapOf("name" to "Skill name from list_skills.")
        ) { _, args ->
            val skill = Store.readSkill(args.str("name"))
                ?: throw ToolError("No skill by that name. Call list_skills.")
            ok("name" to skill.name, "content" to skill.body)
        },

        Tool("delete_skill", "Remove a skill that turned out wrong or is no longer wanted.",
            mapOf("name" to "Skill name.")
        ) { _, args ->
            ok("deleted" to Store.deleteSkill(args.str("name")))
        },

        // ── tending the memory store ─────────────────────────────────────────

        Tool("memory_status",
            "See how memory is doing: how much is stored, and what is about to be forgotten through disuse.",
            mapOf()
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
            mapOf("topic" to "Optional — only consolidate memories about this.")
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

        // ── recurring work ───────────────────────────────────────────────────

        Tool("add_routine",
            "Set something to run regularly — a morning brief, a weekly review. At the given time a notification appears; tapping it opens Yaver and runs the prompt. It does not run silently in the background: waking to find an agent has spent your tokens unasked is not a pleasant surprise.",
            mapOf(
                "name" to "Short name.",
                "prompt" to "Exactly what to do, written as if the user had just asked you.",
                "every" to "daily | weekdays | weekly",
                "hour" to "Hour of day, 0-23, local time.",
                "minute" to "Minute, default 0."
            )
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

        Tool("list_routines", "List the recurring jobs that are set up.", mapOf()
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

        Tool("delete_routine", "Remove a recurring job.", mapOf("id" to "Routine id from list_routines.")
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
            )
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
            mapOf("since" to "Local datetime. Default: the last 7 days.")
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
            )
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

        // ── investigating in parallel ────────────────────────────────────────

        Tool("delegate",
            "Hand two to four independent questions to sub-agents that research them at the same time and report back. Use it when a request contains several separate investigations — comparing options, checking a few places, gathering different kinds of fact. Each task must stand alone; they cannot see each other's work.",
            mapOf("tasks" to "Array of self-contained task descriptions, 2 to 4.")
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
            mapOf("question" to "The question to investigate.", "breadth" to "How many sub-questions, 2 to 4. Default 3.")
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
            )
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
            mapOf("name" to "Filename, e.g. \"packing-list.md\".", "content" to "The file contents.")
        ) { _, args ->
            val content = args.str("content")
            if (content.isEmpty()) throw ToolError("content is required")
            val path = Store.writeArtifact(args.str("name", "note.md"), content)
            ok("written" to true, "path" to path, "chars" to content.length,
                "card" to JSONObject().put("type", "file").put("title", args.str("name", "note.md")).put("path", path))
        },

        Tool("read_file", "Read something you saved earlier.",
            mapOf("path" to "Path from list_files or from an earlier write.")
        ) { _, args ->
            val path = args.str("path")
            val text = Store.readText(path) ?: Store.readText("${Store.ARTIFACT_DIR}/$path")
                ?: throw ToolError("No file at $path. Call list_files.")
            ok("path" to path, "chars" to text.length, "text" to text.take(20000))
        },

        Tool("list_files", "List what you have saved for the user.", mapOf()
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
