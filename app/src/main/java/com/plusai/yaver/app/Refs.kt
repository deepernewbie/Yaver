package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Short handles for Exchange item ids. An id like "AAMkAGUxNTNh…AAA=" is ~150 characters (≈60 tokens); a calendar
 * listing of 90 events spent ~5 000 tokens on ids alone. Tool results shown to the model carry "m" + 7 letters/digits
 * instead, and tool arguments are expanded back before the tool runs. The map is kept on disk, so links such as
 * [subject](mail:m3k9x2ab) in old answers keep working.
 */
object Refs {
    private val ID_RX = Regex("(?:AAMk|AQMk|AAQk|AAkA|AQkA|AAMh)[A-Za-z0-9+/_-]{30,}={0,2}")
    private val REF_RX = Regex("\\bm[0-9a-z]{7}\\b")
    private const val MAX = 30000
    private val lock = Any()
    private var toId: LinkedHashMap<String, String>? = null
    private val toRef = HashMap<String, String>()
    private var dirty = 0

    private fun file() = File(Y.app.filesDir, "harness/refs.json")

    private fun load(): LinkedHashMap<String, String> {
        toId?.let { return it }
        val m = LinkedHashMap<String, String>(1024, 0.75f, true)
        try { val j = JSONObject(file().readText()); for (k in j.keys()) { m[k] = j.getString(k); toRef[j.getString(k)] = k } } catch (_: Throwable) {}
        toId = m
        return m
    }

    private fun save(force: Boolean = false) {
        if (!force && ++dirty < 20) return
        dirty = 0
        try { val f = file(); f.parentFile?.mkdirs(); val j = JSONObject(); load().forEach { (k, v) -> j.put(k, v) }; f.writeText(j.toString()) } catch (e: Throwable) { Diag.e("refs", "save", e) }
    }

    fun ref(id: String): String = synchronized(lock) {
        val m = load()
        toRef[id]?.let { m[it]; return it }
        var h = (id.hashCode().toLong() and 0xffffffffL) * 31 + id.length
        var r: String
        do { r = "m" + java.lang.Long.toString(h, 36).padStart(7, '0').takeLast(7); h += 7919 } while (m.containsKey(r) && m[r] != id)
        m[r] = id; toRef[id] = r
        while (m.size > MAX) { val first = m.keys.first(); toRef.remove(m.remove(first)) }
        save(); r
    }

    /** Real Exchange id for a handle (or the text itself when it is not one). */
    fun real(s: String?): String {
        val t = (s ?: "").trim().removePrefix("mail:")
        if (!REF_RX.matches(t)) return s ?: ""
        return synchronized(lock) { load()[t] } ?: (s ?: "")
    }

    /** The real id behind a handle, or null when [t] is not a known handle (a word that only looks like one). */
    fun known(t: String): String? = if (!REF_RX.matches(t)) null else synchronized(lock) { load()[t] }

    /** Long ids in a tool result → handles. */
    fun short(text: String): String {
        if (text.length < 34 || !ID_RX.containsMatchIn(text)) return text
        val out = ID_RX.replace(text) { ref(it.value) }
        // the map can hold tens of thousands of ids: write it only when this text added new ones
        synchronized(lock) { if (dirty > 0) save(true) }
        return out
    }

    /** Handles in tool arguments → real ids (whole values and inside strings, e.g. "mail:m3k9x2ab"). */
    fun expand(args: JSONObject): JSONObject {
        for (k in args.keys().asSequence().toList()) args.put(k, expandAny(args.get(k)))
        return args
    }

    private fun expandAny(v: Any?): Any? = when (v) {
        is String -> expandString(v)
        is JSONObject -> expand(v)
        is JSONArray -> { for (i in 0 until v.length()) v.put(i, expandAny(v.get(i))); v }
        else -> v
    }

    private fun expandString(s: String): String {
        if (!REF_RX.containsMatchIn(s)) return s
        return REF_RX.replace(s) { m -> synchronized(lock) { load()[m.value] } ?: m.value }
    }
}
