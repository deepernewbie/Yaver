package dev.yaver.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Everything Yaver keeps, as plain files in the app's own directory.
 *
 * JSON files rather than a database on purpose: the whole store is a few
 * hundred kilobytes, it survives being read by a human when something looks
 * wrong, and there is no schema migration to get right on a build nobody can
 * step through.
 *
 * The API key is the exception — it lives in EncryptedSharedPreferences,
 * because a token in a world-readable preference file is a different class of
 * mistake from a lost task list.
 */
object Store {

    lateinit var appContext: Context

    fun init(context: Context) { appContext = context.applicationContext }

    // ── time ─────────────────────────────────────────────────────────────────
    //
    // Every timestamp the model reads or writes is local wall-clock with an
    // explicit offset. Handing a model bare UTC is how "today at 19:00" turns
    // into an event at 22:00.

    fun localIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ms))

    fun offsetLabel(): String {
        val off = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val sign = if (off >= 0) "+" else "-"
        return String.format(Locale.US, "%s%02d:%02d", sign, Math.abs(off) / 60, Math.abs(off) % 60)
    }

    /** A bare date or datetime means local time, never UTC. */
    fun parseLocal(input: String?): Long? {
        if (input.isNullOrBlank()) return null
        val s = input.trim()
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.isLenient = false
                return fmt.parse(s)?.time ?: continue
            } catch (e: Exception) { /* try the next shape */ }
        }
        return null
    }

    // ── raw files ────────────────────────────────────────────────────────────

    private fun file(name: String) = File(appContext.filesDir, name)

    fun readText(name: String): String? = try {
        file(name).takeIf { it.exists() }?.readText()
    } catch (e: Exception) { null }

    fun writeText(name: String, text: String): Boolean = try {
        file(name).parentFile?.mkdirs()
        file(name).writeText(text)
        true
    } catch (e: Exception) {
        Log.error("write failed: $name — ${e.message}")
        false
    }

    fun listFiles(dir: String): List<String> =
        file(dir).listFiles()?.map { it.name }?.sorted() ?: emptyList()

    fun delete(name: String): Boolean = try { file(name).delete() } catch (e: Exception) { false }

    private fun readJson(name: String): JSONObject =
        try { JSONObject(readText(name) ?: "{}") } catch (e: Exception) { JSONObject() }

    // ── settings ─────────────────────────────────────────────────────────────

    private fun prefs() = try {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext, "yaver-secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore faults happen after some restores. Falling back keeps the
        // app usable; the token's protection is the price.
        Log.error("encrypted prefs unavailable: ${e.message}")
        appContext.getSharedPreferences("yaver-plain", Context.MODE_PRIVATE)
    }

    fun setting(key: String, fallback: String = ""): String =
        prefs().getString(key, fallback) ?: fallback

    fun setSetting(key: String, value: String) {
        prefs().edit().putString(key, value).apply()
    }

    const val API_KEY = "apiKey"
    const val MODEL = "model"
    const val PERSONA = "persona"
    const val USER_NAME = "userName"

    fun model() = setting(MODEL, "anthropic/claude-sonnet-4.5")

    // ── tasks ────────────────────────────────────────────────────────────────

    data class Task(
        val id: String,
        var title: String,
        var due: Long?,          // epoch millis
        var remind: Int,         // minutes before
        var notes: String,
        var priority: String,
        var done: Boolean,
        val created: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title)
            .put("due", due ?: JSONObject.NULL)
            .put("remind", remind).put("notes", notes)
            .put("priority", priority).put("done", done).put("created", created)

        companion object {
            fun from(o: JSONObject) = Task(
                id = o.optString("id"),
                title = o.optString("title"),
                due = if (o.isNull("due")) null else o.optLong("due").takeIf { it > 0 },
                remind = o.optInt("remind", 30),
                notes = o.optString("notes"),
                priority = o.optString("priority", "normal"),
                done = o.optBoolean("done"),
                created = o.optLong("created", System.currentTimeMillis())
            )
        }
    }

    private const val TASKS = "tasks.json"

    fun tasks(): MutableList<Task> {
        val arr = readJson(TASKS).optJSONArray("tasks") ?: JSONArray()
        return (0 until arr.length()).map { Task.from(arr.getJSONObject(it)) }.toMutableList()
    }

    fun saveTasks(list: List<Task>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeText(TASKS, JSONObject().put("tasks", arr).put("savedAt", System.currentTimeMillis()).toString(2))
    }

    fun addTask(title: String, due: Long?, remind: Int, notes: String, priority: String): Task {
        val list = tasks()
        val task = Task(newId(), title, due, remind, notes, priority, false, System.currentTimeMillis())
        list.add(0, task)
        saveTasks(list)
        return task
    }

    /** Overdue, due within its reminder window, or neither. Null when done or undated. */
    fun urgency(t: Task, now: Long = System.currentTimeMillis()): String? {
        if (t.done || t.due == null) return null
        val lead = t.remind.coerceAtLeast(0) * 60_000L
        return when {
            t.due!! <= now -> "overdue"
            t.due!! - now <= lead -> "soon"
            else -> "later"
        }
    }

    // ── memory ───────────────────────────────────────────────────────────────

    data class Memory(
        val id: String,
        val text: String,
        val entities: List<String>,
        val topics: List<String>,
        var importance: Double,
        var pinned: Boolean,
        var uses: Int,
        var lastUsed: Long,
        val created: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("text", text)
            .put("entities", JSONArray(entities)).put("topics", JSONArray(topics))
            .put("importance", importance).put("pinned", pinned)
            .put("uses", uses).put("lastUsed", lastUsed).put("created", created)

        companion object {
            fun from(o: JSONObject): Memory {
                fun strings(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                }
                return Memory(
                    id = o.optString("id"),
                    text = o.optString("text"),
                    entities = strings("entities"),
                    topics = strings("topics"),
                    importance = o.optDouble("importance", 0.5),
                    pinned = o.optBoolean("pinned"),
                    uses = o.optInt("uses"),
                    lastUsed = o.optLong("lastUsed", o.optLong("created")),
                    created = o.optLong("created", System.currentTimeMillis())
                )
            }
        }
    }

    private const val MEMORIES = "memories.json"
    const val MEMORY_TTL_DAYS = 45
    const val MEMORY_WARN_DAYS = 30

    fun memories(): MutableList<Memory> {
        val arr = readJson(MEMORIES).optJSONArray("memories") ?: JSONArray()
        return (0 until arr.length()).map { Memory.from(arr.getJSONObject(it)) }.toMutableList()
    }

    fun saveMemories(list: List<Memory>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeText(MEMORIES, JSONObject().put("memories", arr).put("savedAt", System.currentTimeMillis()).toString(2))
    }

    fun addMemory(
        text: String, entities: List<String> = emptyList(), topics: List<String> = emptyList(),
        importance: Double = 0.5, pinned: Boolean = false
    ): Memory? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val list = memories()

        // Seeing the same fact again is evidence it matters, not a reason to
        // store it twice.
        val incoming = tokenise(clean).toSet()
        val dupe = list.firstOrNull { m ->
            val known = tokenise(m.text).toSet()
            if (known.isEmpty() || incoming.isEmpty()) false
            else incoming.intersect(known).size >= minOf(incoming.size, known.size) * 0.85
        }
        if (dupe != null) {
            dupe.uses += 1
            dupe.importance = minOf(1.0, maxOf(dupe.importance, importance) + 0.05)
            dupe.lastUsed = System.currentTimeMillis()
            saveMemories(list)
            return dupe
        }

        val now = System.currentTimeMillis()
        val entry = Memory(newId(), clean, entities.take(8), topics.take(6),
            importance.coerceIn(0.0, 1.0), pinned, 0, now, now)
        list.add(0, entry)
        saveMemories(list)
        return entry
    }

    /** Recall marks what it returns as used — that is what keeps a memory alive. */
    fun recall(query: String, limit: Int = 8, touch: Boolean = true): List<Memory> {
        val list = memories()
        if (list.isEmpty()) return emptyList()
        val terms = tokenise(query)
        if (terms.isEmpty()) return list.take(limit)

        val scored = list.map { m ->
            val hay = tokenise("${m.text} ${m.entities.joinToString(" ")} ${m.topics.joinToString(" ")}").toSet()
            var score = terms.count { hay.contains(it) }.toDouble()
            // A name matching is worth more than a verb matching.
            score += terms.count { t -> m.entities.any { it.lowercase(Locale.ROOT).contains(t) } } * 0.6
            score += m.importance * 0.4
            if (m.pinned) score += 1.0
            m to score
        }
        val hits = scored.filter { it.second > 0.9 }.sortedByDescending { it.second }
            .take(limit).map { it.first }

        if (touch && hits.isNotEmpty()) {
            val now = System.currentTimeMillis()
            hits.forEach { it.lastUsed = now; it.uses += 1 }
            saveMemories(list)
        }
        return hits
    }

    fun memoryProtected(m: Memory) = m.pinned || m.importance >= 0.8 || m.uses >= 3

    fun memoryIdleDays(m: Memory) =
        ((System.currentTimeMillis() - m.lastUsed) / 86_400_000L).toInt()

    /** Drop what has aged out. Returns what went so the user can be told. */
    fun pruneMemories(): List<Memory> {
        val list = memories()
        val dropped = list.filter { !memoryProtected(it) && memoryIdleDays(it) >= MEMORY_TTL_DAYS }
        if (dropped.isNotEmpty()) saveMemories(list - dropped.toSet())
        return dropped
    }

    // ── routines ─────────────────────────────────────────────────────────────

    data class Routine(
        val id: String,
        var name: String,
        var prompt: String,
        var every: String,        // daily | weekdays | weekly
        var hour: Int,
        var minute: Int,
        var lastRun: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("prompt", prompt)
            .put("every", every).put("hour", hour).put("minute", minute)
            .put("lastRun", lastRun)

        /**
         * When this should next fire, in epoch millis.
         *
         * Walks forward a day at a time rather than doing calendar arithmetic:
         * fourteen iterations is nothing, and it handles weekday-only rules and
         * "already past today" without a special case for either.
         */
        fun nextFireAt(from: Long = System.currentTimeMillis()): Long? {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = from
            for (day in 0..14) {
                val c = cal.clone() as java.util.Calendar
                c.add(java.util.Calendar.DAY_OF_YEAR, day)
                c.set(java.util.Calendar.HOUR_OF_DAY, hour)
                c.set(java.util.Calendar.MINUTE, minute)
                c.set(java.util.Calendar.SECOND, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                if (c.timeInMillis <= from) continue

                val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
                val weekday = dow >= java.util.Calendar.MONDAY && dow <= java.util.Calendar.FRIDAY
                val ok = when (every) {
                    "weekdays" -> weekday
                    "weekly" -> c.timeInMillis - lastRun >= 7 * 86_400_000L
                    else -> true
                }
                if (ok) return c.timeInMillis
            }
            return null
        }

        companion object {
            fun from(o: JSONObject) = Routine(
                id = o.optString("id"),
                name = o.optString("name"),
                prompt = o.optString("prompt"),
                every = o.optString("every", "daily"),
                hour = o.optInt("hour", 8),
                minute = o.optInt("minute", 0),
                lastRun = o.optLong("lastRun")
            )
        }
    }

    private const val ROUTINES = "routines.json"

    fun routines(): MutableList<Routine> {
        val arr = readJson(ROUTINES).optJSONArray("routines") ?: JSONArray()
        return (0 until arr.length()).map { Routine.from(arr.getJSONObject(it)) }.toMutableList()
    }

    fun saveRoutines(list: List<Routine>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeText(ROUTINES, JSONObject().put("routines", arr).toString(2))
    }

    // ── the user profile ─────────────────────────────────────────────────────
    //
    // Memories are scattered facts; the profile is the coherent picture. It is
    // loaded into every conversation, which makes it the most valuable thing
    // the agent maintains.

    private const val PROFILE = "profile.md"

    fun profile(): String = readText(PROFILE) ?: ""

    fun saveProfile(text: String) { writeText(PROFILE, text.take(8000)) }

    // ── skills: procedural memory ────────────────────────────────────────────
    //
    // Memory is what happened; a skill is how this person likes a job done.
    // Written after finishing something non-trivial, read before doing it
    // again, so the second time starts where the first one ended.

    private const val SKILL_DIR = "skills"

    data class Skill(val name: String, val title: String, val useWhen: String, val body: String)

    private fun slug(s: String) = s.lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(48).ifEmpty { "skill" }

    fun skills(): List<Skill> = listFiles(SKILL_DIR).filter { it.endsWith(".md") }.mapNotNull { file ->
        val raw = readText("$SKILL_DIR/$file") ?: return@mapNotNull null
        Skill(
            name = file.removeSuffix(".md"),
            title = Regex("(?m)^#\\s+(.+)$").find(raw)?.groupValues?.get(1)?.trim()
                ?: file.removeSuffix(".md"),
            useWhen = Regex("(?mi)^when:\\s*(.+)$").find(raw)?.groupValues?.get(1)?.trim() ?: "",
            body = raw
        )
    }

    fun readSkill(name: String): Skill? = skills().firstOrNull { it.name == slug(name) || it.name == name }

    fun saveSkill(name: String, title: String, useWhen: String, body: String): String {
        val file = "$SKILL_DIR/${slug(name.ifBlank { title })}.md"
        val doc = buildString {
            append("# ").append(title.ifBlank { name }).append('\n')
            if (useWhen.isNotBlank()) append("when: ").append(useWhen).append('\n')
            append('\n').append(body.trim()).append('\n')
        }
        writeText(file, doc)
        return file
    }

    fun deleteSkill(name: String): Boolean = delete("$SKILL_DIR/${slug(name)}.md")

    /** Titles and triggers only — bodies are read on demand, not carried around. */
    fun skillIndex(): String = skills().joinToString("\n") { sk ->
        "- ${sk.name}: ${sk.title}" + if (sk.useWhen.isNotBlank()) " — use when ${sk.useWhen}" else ""
    }

    // ── artifacts ────────────────────────────────────────────────────────────
    //
    // Things the agent makes that outlive the conversation: reports, notes,
    // small pages. Kept as files so they can be reopened, shared and deleted
    // long after the chat that produced them has scrolled away.

    const val ARTIFACT_DIR = "artifacts"

    data class Artifact(val path: String, val name: String, val bytes: Int, val modified: Long)

    fun artifacts(): List<Artifact> =
        listFiles(ARTIFACT_DIR).mapNotNull { name ->
            val text = readText("$ARTIFACT_DIR/$name") ?: return@mapNotNull null
            Artifact("$ARTIFACT_DIR/$name", name, text.length, fileModified("$ARTIFACT_DIR/$name"))
        }.sortedByDescending { it.modified }

    private fun fileModified(name: String): Long =
        try { File(appContext.filesDir, name).lastModified() } catch (e: Exception) { 0L }

    fun writeArtifact(name: String, content: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "-").take(80).ifEmpty { "note.txt" }
        writeText("$ARTIFACT_DIR/$safe", content)
        return "$ARTIFACT_DIR/$safe"
    }

    // ── goals ────────────────────────────────────────────────────────────────
    //
    // Adapted from Prime Agent's long-running work. A task is something to do;
    // a goal is something being pursued across weeks and many conversations —
    // finding a flat, planning a trip, getting a contract signed. It carries
    // its own running notes, so the next session starts informed instead of
    // asking the same questions again.

    data class Goal(
        val id: String,
        var title: String,
        var detail: String,
        var status: String,            // active | paused | done
        val created: Long,
        var updated: Long,
        val notes: MutableList<Pair<Long, String>>
    ) {
        fun toJson(): JSONObject {
            val log = JSONArray()
            notes.takeLast(40).forEach { (at, text) ->
                log.put(JSONObject().put("at", at).put("text", text))
            }
            return JSONObject()
                .put("id", id).put("title", title).put("detail", detail)
                .put("status", status).put("created", created).put("updated", updated)
                .put("notes", log)
        }

        companion object {
            fun from(o: JSONObject): Goal {
                val log = mutableListOf<Pair<Long, String>>()
                o.optJSONArray("notes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val n = arr.optJSONObject(i) ?: continue
                        log.add(n.optLong("at") to n.optString("text"))
                    }
                }
                return Goal(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    detail = o.optString("detail"),
                    status = o.optString("status", "active"),
                    created = o.optLong("created"),
                    updated = o.optLong("updated"),
                    notes = log
                )
            }
        }
    }

    private const val GOALS = "goals.json"

    fun goals(): MutableList<Goal> {
        val arr = readJson(GOALS).optJSONArray("goals") ?: JSONArray()
        return (0 until arr.length()).map { Goal.from(arr.getJSONObject(it)) }.toMutableList()
    }

    fun saveGoals(list: List<Goal>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeText(GOALS, JSONObject().put("goals", arr).toString(2))
    }

    // ── the harness log ──────────────────────────────────────────────────────
    //
    // Also from Prime Agent: when the agent edits its own operating state — the
    // profile, a skill — the change is recorded with the evidence that
    // prompted it. Two reasons. An agent that rewrites its own instructions
    // with no trail is impossible to debug when it starts behaving oddly, and
    // requiring a reason in the first place discourages churn.

    private const val HARNESS_LOG = "harness-log.jsonl"

    data class Revision(val ts: Long, val what: String, val why: String)

    fun recordRevision(what: String, why: String) {
        val line = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("what", what).put("why", why.take(400))
            .toString()
        try {
            File(appContext.filesDir, HARNESS_LOG).appendText(line + "\n")
        } catch (e: Exception) { /* the log is a courtesy, not a dependency */ }
    }

    fun revisions(limit: Int = 40): List<Revision> {
        val raw = readText(HARNESS_LOG) ?: return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val o = JSONObject(line)
                Revision(o.optLong("ts"), o.optString("what"), o.optString("why"))
            } catch (e: Exception) { null }
        }.sortedByDescending { it.ts }.take(limit)
    }

    // ── usage ────────────────────────────────────────────────────────────────

    private const val USAGE = "usage.json"

    fun recordUsage(model: String, prompt: Int, completion: Int, cost: Double) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val data = readJson(USAGE)
        val days = data.optJSONObject("days") ?: JSONObject()
        val bucket = days.optJSONObject(day) ?: JSONObject()
        bucket.put("calls", bucket.optInt("calls") + 1)
        bucket.put("prompt", bucket.optInt("prompt") + prompt)
        bucket.put("completion", bucket.optInt("completion") + completion)
        bucket.put("cost", bucket.optDouble("cost", 0.0) + cost)
        bucket.put("model", model)
        days.put(day, bucket)

        // Thirty days answers "what am I spending" without growing forever.
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - 30L * 86_400_000L))
        val keys = days.keys().asSequence().toList()
        keys.filter { it < cutoff }.forEach { days.remove(it) }

        writeText(USAGE, JSONObject().put("days", days).toString())
    }

    data class UsageDay(val day: String, val calls: Int, val tokens: Int, val cost: Double)

    fun usage(days: Int = 7): List<UsageDay> {
        val data = readJson(USAGE).optJSONObject("days") ?: return emptyList()
        val since = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - days * 86_400_000L))
        return data.keys().asSequence().filter { it >= since }.map { key ->
            val o = data.getJSONObject(key)
            UsageDay(key, o.optInt("calls"), o.optInt("prompt") + o.optInt("completion"), o.optDouble("cost"))
        }.sortedByDescending { it.day }.toList()
    }

    // ── conversation ─────────────────────────────────────────────────────────

    data class Turn(val role: String, val content: String, val ts: Long = System.currentTimeMillis())

    data class SessionInfo(val id: String, val title: String, val updated: Long, val turns: Int)

    private const val SESSION_DIR = "sessions"
    private const val CURRENT = "currentSession"

    fun currentSessionId(): String {
        val existing = setting(CURRENT)
        if (existing.isNotBlank()) return existing
        val fresh = newId()
        setSetting(CURRENT, fresh)
        return fresh
    }

    fun switchSession(id: String) { setSetting(CURRENT, id) }

    fun newSession(): String {
        val id = newId()
        setSetting(CURRENT, id)
        return id
    }

    fun loadSession(id: String = currentSessionId()): MutableList<Turn> {
        val arr = readJson("$SESSION_DIR/$id.json").optJSONArray("turns") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Turn(o.optString("role"), o.optString("content"), o.optLong("ts"))
        }.toMutableList()
    }

    fun saveSession(turns: List<Turn>, id: String = currentSessionId()) {
        if (turns.isEmpty()) return
        val arr = JSONArray()
        turns.takeLast(300).forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content).put("ts", it.ts))
        }
        // The title is the opening line of the first thing the user said —
        // cheap, stable, and good enough to recognise a conversation by.
        val title = turns.firstOrNull { it.role == "user" }?.content
            ?.lineSequence()?.firstOrNull()?.take(60) ?: "New session"
        writeText("$SESSION_DIR/$id.json", JSONObject()
            .put("id", id).put("title", title)
            .put("updated", System.currentTimeMillis())
            .put("turns", arr).toString())
    }

    fun sessions(): List<SessionInfo> =
        listFiles(SESSION_DIR).filter { it.endsWith(".json") }.mapNotNull { name ->
            val o = try { JSONObject(readText("$SESSION_DIR/$name") ?: return@mapNotNull null) }
                    catch (e: Exception) { return@mapNotNull null }
            SessionInfo(
                id = o.optString("id", name.removeSuffix(".json")),
                title = o.optString("title", "Session"),
                updated = o.optLong("updated"),
                turns = o.optJSONArray("turns")?.length() ?: 0
            )
        }.sortedByDescending { it.updated }

    fun deleteSession(id: String) { delete("$SESSION_DIR/$id.json") }

    /** Everything the user ever said, searched for a phrase. */
    fun searchSessions(query: String, limit: Int = 6): List<Pair<SessionInfo, String>> {
        val terms = tokenise(query)
        if (terms.isEmpty()) return emptyList()
        val out = mutableListOf<Triple<SessionInfo, String, Int>>()
        for (info in sessions().take(80)) {
            val turns = loadSession(info.id)
            val hay = turns.joinToString("\n") { it.content }
            val lower = hay.lowercase(Locale.ROOT)
            var score = 0
            terms.forEach { t -> if (lower.contains(t)) score++ }
            if (score == 0) continue
            val at = lower.indexOf(terms.first())
            val excerpt = hay.substring(maxOf(0, at - 80), minOf(hay.length, at + 240)).trim()
            out.add(Triple(info, excerpt, score))
        }
        return out.sortedByDescending { it.third }.take(limit).map { it.first to it.second }
    }

    // ── captured messages ────────────────────────────────────────────────────

    private const val MESSAGES = "messages.jsonl"
    private const val MESSAGE_RETENTION_DAYS = 30

    data class Message(
        val ts: Long, val app: String, val chat: String,
        val sender: String, val text: String, val key: String
    )

    /** Notifications repeat constantly — the same message arrives several times. */
    private val recentKeys = LinkedHashSet<String>()

    @Synchronized
    fun addMessage(app: String, chat: String, sender: String, text: String, ts: Long, key: String) {
        if (!recentKeys.add(key)) return
        while (recentKeys.size > 400) recentKeys.remove(recentKeys.first())

        val line = JSONObject()
            .put("ts", ts).put("app", app).put("chat", chat)
            .put("sender", sender).put("text", text.take(4000)).put("key", key)
            .toString()
        try {
            File(appContext.filesDir, MESSAGES).appendText(line + "\n")
        } catch (e: Exception) {
            Log.error("message write failed: ${e.message}")
        }
    }

    fun messages(sinceMs: Long = 0, chat: String = "", limit: Int = 200): List<Message> {
        val raw = readText(MESSAGES) ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<Message>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val o = try { JSONObject(line) } catch (e: Exception) { continue }
            val ts = o.optLong("ts")
            if (ts <= sinceMs) continue
            val key = o.optString("key")
            if (key.isNotEmpty() && !seen.add(key)) continue
            val chatName = o.optString("chat")
            if (chat.isNotEmpty() && !chatName.contains(chat, ignoreCase = true)) continue
            out.add(Message(ts, o.optString("app"), chatName,
                o.optString("sender"), o.optString("text"), key))
        }
        return out.sortedByDescending { it.ts }.take(limit)
    }

    fun chats(sinceMs: Long = 0): List<Pair<String, Int>> =
        messages(sinceMs, limit = 1000).groupingBy { it.chat }.eachCount()
            .toList().sortedByDescending { it.second }

    /** Keep the file from growing without bound. Runs on launch. */
    fun pruneMessages() {
        val cutoff = System.currentTimeMillis() - MESSAGE_RETENTION_DAYS * 86_400_000L
        val raw = readText(MESSAGES) ?: return
        val lines = raw.lines().filter { it.isNotBlank() }
        val kept = lines.filter { line ->
            try { JSONObject(line).optLong("ts") >= cutoff } catch (e: Exception) { false }
        }
        if (kept.size < lines.size) {
            writeText(MESSAGES, if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
            Log.info("pruned ${lines.size - kept.size} old messages")
        }
    }

    fun clearMessages() { delete(MESSAGES) }

    // ── things shared to the agent ───────────────────────────────────────────

    private const val FORWARDS = "forwards.jsonl"

    fun addForward(text: String, from: String) {
        val now = System.currentTimeMillis()
        val line = JSONObject()
            .put("ts", now).put("iso", localIso(now))
            .put("from", from).put("text", text.take(20000))
            .toString()
        try {
            val f = File(appContext.filesDir, FORWARDS)
            f.appendText(line + "\n")
        } catch (e: Exception) { Log.error("forward write failed: ${e.message}") }
    }

    data class Forward(val ts: Long, val from: String, val text: String)

    fun forwards(sinceMs: Long = 0): List<Forward> {
        val raw = readText(FORWARDS) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            try {
                val o = JSONObject(line)
                val ts = o.optLong("ts")
                if (ts <= sinceMs) null
                else Forward(ts, o.optString("from"), o.optString("text"))
            } catch (e: Exception) { null }
        }.toList().sortedByDescending { it.ts }
    }

    fun clearForwards() { delete(FORWARDS) }

    // ── helpers ──────────────────────────────────────────────────────────────

    fun newId(): String =
        java.lang.Long.toString(System.currentTimeMillis(), 36) +
        (1000..9999).random().toString(36)

    private val STOP = setOf(
        "the", "and", "for", "with", "bir", "ile", "için", "icin", "ve",
        "bu", "şu", "da", "de", "mi", "mu", "ne", "ki", "var", "yok"
    )

    fun tokenise(s: String): List<String> =
        s.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.length > 2 && it !in STOP }
}

/**
 * A log you can read from the phone.
 *
 * There is no logcat on the other end of this app, so anything that goes wrong
 * has to be visible in the app itself or it may as well not have happened.
 */
object Log {
    private val entries = ArrayDeque<String>()
    private const val MAX = 400

    @Synchronized
    fun add(kind: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        entries.addLast("$time  [$kind] $message")
        while (entries.size > MAX) entries.removeFirst()
    }

    fun info(message: String) = add("info", message)
    fun error(message: String) = add("error", message)
    fun tool(message: String) = add("tool", message)
    fun net(message: String) = add("net", message)

    @Synchronized
    fun dump(): String = entries.joinToString("\n")

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun errorCount(): Int = entries.count { it.contains("[error]") }
}
