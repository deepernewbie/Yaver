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
    var background: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("created", created).put("updated", updated)
        .put("summary", summary).put("summaryUpTo", summaryUpTo).put("background", background)
        .put("messages", messages)

    companion object {
        fun fromJson(j: JSONObject) = Convo(
            j.getString("id"), j.optString("title", "Chat"), j.optLong("created"), j.optLong("updated"),
            j.optJSONArray("messages") ?: JSONArray(), j.optString("summary", ""), j.optInt("summaryUpTo", 0), j.optBoolean("background", false)
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

    fun save(c: Convo, touch: Boolean = true) = synchronized(lock) {
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

    fun delete(id: String) = synchronized(lock) {
        File(dir, "$id.json").delete()
        val idx = index()
        val keep = JSONArray()
        for (i in 0 until idx.length()) if (idx.getJSONObject(i).optString("id") != id) keep.put(idx.getJSONObject(i))
        File(dir, "index.json").writeText(keep.toString())
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

/** Long-term memory: facts about the user (profile) and notes yaver keeps for itself. */
object Memory {
    private val file: File get() = File(Y.app.filesDir, "memory.json")
    private val lock = Any()

    private fun load(): JSONObject {
        synchronized(lock) {
            if (file.exists()) try { return JSONObject(file.readText()) } catch (_: Exception) {}
            return JSONObject().put("user", JSONArray()).put("notes", JSONArray())
        }
    }

    private fun store(j: JSONObject) = synchronized(lock) { file.writeText(j.toString()) }

    fun entries(target: String): MutableList<String> {
        val a = load().optJSONArray(if (target == "user") "user" else "notes") ?: JSONArray()
        return (0 until a.length()).map { a.getString(it) }.toMutableList()
    }

    fun set(target: String, list: List<String>) {
        val j = load()
        j.put(if (target == "user") "user" else "notes", JSONArray(list))
        store(j)
    }

    fun add(target: String, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) throw IllegalArgumentException("content is empty")
        val l = entries(target)
        if (l.any { it == t }) return Y.t("Already in memory.", "Zaten hafızada.")
        l.add(t)
        set(target, l)
        return Y.t("Saved to memory.", "Hafızaya kaydedildi.")
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

    fun promptBlock(): String {
        val u = entries("user")
        val n = entries("notes")
        val sb = StringBuilder()
        if (u.isNotEmpty()) sb.append("## About the user\n").append(u.joinToString("\n") { "- $it" }).append("\n\n")
        if (n.isNotEmpty()) {
            sb.append("## Your memory notes\n")
            var total = 0
            for (e in n.asReversed()) { // newest first, bounded
                if (total > 12000) break
                sb.append("- ").append(e).append("\n")
                total += e.length
            }
        }
        return sb.toString()
    }
}
