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

    // ── conversation ─────────────────────────────────────────────────────────

    data class Turn(val role: String, val content: String, val ts: Long = System.currentTimeMillis())

    private const val SESSION = "session.json"

    fun loadSession(): MutableList<Turn> {
        val arr = readJson(SESSION).optJSONArray("turns") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Turn(o.optString("role"), o.optString("content"), o.optLong("ts"))
        }.toMutableList()
    }

    fun saveSession(turns: List<Turn>) {
        val arr = JSONArray()
        turns.takeLast(200).forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content).put("ts", it.ts))
        }
        writeText(SESSION, JSONObject().put("turns", arr).toString())
    }

    fun clearSession() { delete(SESSION) }

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
