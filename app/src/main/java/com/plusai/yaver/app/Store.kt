package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One conversation. [messages] are in the OpenAI chat format plus a few local fields (ts, images, display). */
class Convo(
    val id: String,
    var title: String,
    val created: Long,
    var updated: Long,
    val messages: JSONArray,
    var summary: String = "",
    var summaryUpTo: Int = 0,
    var background: Boolean = false,
    /** The frozen base system prompt (Prompt.build), stored on the first turn. */
    var system: String = "",
    /** Conversation language (ISO-639-1), set by Lang. */
    var lang: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("created", created).put("updated", updated)
        .put("summary", summary).put("summaryUpTo", summaryUpTo).put("background", background)
        .put("system", system).put("lang", lang ?: JSONObject.NULL)
        .put("messages", messages)

    companion object {
        fun fromJson(j: JSONObject) = Convo(
            j.getString("id"), j.optString("title", "Chat"), j.optLong("created"), j.optLong("updated"),
            j.optJSONArray("messages") ?: JSONArray(), j.optString("summary", ""), j.optInt("summaryUpTo", 0), j.optBoolean("background", false),
            j.optString("system", ""), if (j.isNull("lang")) null else j.optString("lang", "").ifEmpty { null }
        )

        fun create(title: String = Y.t("New chat", "Yeni sohbet"), background: Boolean = false): Convo {
            val now = System.currentTimeMillis()
            return Convo("c" + now.toString(36) + UUID.randomUUID().toString().take(4), title, now, now, JSONArray(), background = background)
        }
    }
}

object Convos {
    private val dir: File get() = File(Y.app.filesDir, "convos").apply { mkdirs() }
    private val lock = Any()

    /**
     * Conversations with a running turn: the agent's own instance. Other code (the screen's 👍/👎, rename…) loads a
     * separate copy from disk; saving that copy would overwrite the turn's newer messages, and the turn's next save
     * would then drop the copy's change. Such saves are merged into the live instance instead (see [save]).
     */
    private val live = java.util.concurrent.ConcurrentHashMap<String, Convo>()
    private val MARKS = listOf("fb", "fbNote", "fbTold", "judged")

    fun begin(c: Convo) { live[c.id] = c }
    fun end(c: Convo) { live.remove(c.id, c) }
    /** A turn is working on this conversation right now (chat, scheduled task, sub-agent — whoever called [begin]). */
    fun isLive(id: String) = live.containsKey(id)

    /** Copies what a stale copy may have changed (title, per-message feedback marks) into the live instance. */
    fun mergeInto(dst: Convo, src: Convo) {
        if (src.title != dst.title) dst.title = src.title
        val n = minOf(src.messages.length(), dst.messages.length())
        for (i in 0 until n) {
            val a = src.messages.optJSONObject(i) ?: continue; val b = dst.messages.optJSONObject(i) ?: continue
            if (a.optLong("ts", -1) != b.optLong("ts", -1) || a.optString("role") != b.optString("role")) continue
            for (k in MARKS) { if (a.has(k)) { if (a.opt(k) != b.opt(k)) b.put(k, a.opt(k)) } else if (b.has(k)) b.remove(k) }
        }
    }

    fun save(c: Convo, touch: Boolean = true): Unit = synchronized(lock) {
        val l = live[c.id]
        if (l != null && l !== c) {
            mergeInto(l, c)
            // the live instance is written by its own turn; writing it here could race with the worker adding messages
            try { write(l, touch) } catch (e: java.util.ConcurrentModificationException) { /* the turn saves it at its next step */ }
            return
        }
        write(c, touch)
    }

    private fun write(c: Convo, touch: Boolean) {
        if (touch) c.updated = System.currentTimeMillis()
        val f = File(dir, c.id + ".json")
        val tmp = File(dir, c.id + ".tmp")
        tmp.writeText(c.toJson().toString())
        tmp.renameTo(f)
        val idx = index()
        var found = false
        for (i in 0 until idx.length()) {
            val o = idx.getJSONObject(i)
            if (o.optString("id") == c.id) { o.put("title", c.title).put("updated", c.updated).put("background", c.background); found = true }
        }
        if (!found) idx.put(JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated).put("background", c.background))
        File(dir, "index.json").writeText(idx.toString())
    }

    fun load(id: String): Convo? {
        synchronized(lock) {
            val f = File(dir, "$id.json")
            if (!f.exists()) return null
            return try { Convo.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
        }
    }

    private fun index(): JSONArray {
        val f = File(dir, "index.json")
        if (f.exists()) try { return JSONArray(f.readText()) } catch (_: Exception) {}
        // rebuild from files
        val arr = JSONArray()
        dir.listFiles()?.filter { it.name.endsWith(".json") && it.name != "index.json" }?.forEach {
            try { val j = JSONObject(it.readText()); arr.put(JSONObject().put("id", j.getString("id")).put("title", j.optString("title")).put("updated", j.optLong("updated")).put("background", j.optBoolean("background"))) } catch (_: Exception) {}
        }
        return arr
    }

    /** Newest first. */
    fun list(): List<JSONObject> = synchronized(lock) {
        val idx = index()
        (0 until idx.length()).map { idx.getJSONObject(it) }.sortedByDescending { it.optLong("updated") }
    }

    fun delete(id: String) {
        synchronized(lock) {
            File(dir, "$id.json").delete()
            val idx = index()
            val keep = JSONArray()
            for (i in 0 until idx.length()) if (idx.getJSONObject(i).optString("id") != id) keep.put(idx.getJSONObject(i))
            File(dir, "index.json").writeText(keep.toString())
        }
        // per-conversation harness state lives outside the convo file
        for ((what, f) in listOf<Pair<String, () -> Unit>>(
            "ctx" to { Compactor.forget(id) }, "state" to { ConvoState.delete(id) }, "harness" to { Harness.forgetConvo(id) },
            "heartbeats" to { Heartbeats.clearConvo(id) }, "children" to { Children.clearConvo(id) }, "followups" to { FollowUps.clear(id) }
        )) try { f() } catch (e: Throwable) { Diag.e("convos", "delete $what of $id", e) }
    }

    fun rename(id: String, title: String) {
        val c = load(id) ?: return
        c.title = title
        save(c)
    }

    /** Plain-text search over past conversations (for the agent and the list screen). */
    fun search(q: String, limit: Int = 8, exclude: String? = null): List<Pair<JSONObject, String>> {
        val words = q.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Triple<JSONObject, String, Int>>()
        for (meta in list()) {
            if (meta.optString("id") == exclude) continue
            val c = load(meta.optString("id")) ?: continue
            var best = 0
            var snippet = ""
            for (i in 0 until c.messages.length()) {
                val m = c.messages.getJSONObject(i)
                val role = m.optString("role")
                if (role != "user" && role != "assistant") continue
                val t = m.optString("display", "").ifEmpty { m.opt("content")?.let { if (it is String) it else "" } ?: "" }
                val low = t.lowercase()
                val score = words.count { low.contains(it) }
                if (score > best) {
                    best = score
                    val pos = words.map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
                    snippet = "[$role] " + t.substring(maxOf(0, pos - 120), minOf(t.length, pos + 280)).replace("\n", " ")
                }
            }
            if (c.title.lowercase().let { t -> words.any { t.contains(it) } }) best += 1
            if (best > 0) out.add(Triple(meta, snippet, best))
        }
        return out.sortedByDescending { it.third }.take(limit).map { Pair(it.first, it.second) }
    }
}

/**
 * Long-term memory. The store is MemoryX (MemoryPlus.kt: tiers, pins, trash, retrieval); this object keeps the old
 * API ("notes" = MemoryX's "memory" target) and reads the legacy file memory.json, which MemoryX imports once.
 */
object Memory {
    private val file: File get() = File(Y.app.filesDir, "memory.json")
    private val lock = Any()

    /** Entries of the legacy store (memory.json), for MemoryX's one-time import. */
    fun legacyEntries(target: String): List<String> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val a = try { JSONObject(file.readText()).optJSONArray(if (target == "user") "user" else "notes") } catch (_: Exception) { null } ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }
    }

    fun entries(target: String): MutableList<String> = MemoryX.entries(target).toMutableList()

    fun set(target: String, list: List<String>) = MemoryX.save(target, list.map { it.trim() }.filter { it.isNotEmpty() })

    fun add(target: String, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) throw IllegalArgumentException("content is empty")
        val r = MemoryX.uiAdd(target, t)
        return if (r.optBoolean("ok")) Y.t("Saved to memory.", "Hafızaya kaydedildi.") else r.optString("error")
    }

    private fun find(l: List<String>, old: String): Int {
        val hits = l.indices.filter { l[it].contains(old) }
        if (hits.isEmpty()) throw IllegalArgumentException("no memory entry contains \"$old\"")
        if (hits.size > 1) throw IllegalArgumentException("\"$old\" matches ${hits.size} entries — use a longer piece of the text")
        return hits[0]
    }

    fun replace(target: String, old: String, text: String): String {
        val l = entries(target)
        l[find(l, old)] = text.trim()
        set(target, l)
        return "Updated."
    }

    fun remove(target: String, old: String): String {
        val l = entries(target)
        l.removeAt(find(l, old))
        set(target, l)
        return "Removed."
    }

    fun promptBlock(): String = MemoryX.block()
}
