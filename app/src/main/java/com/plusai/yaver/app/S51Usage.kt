package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * yaver 5.1 — token use by day and by model (Settings → Token usage). A final assistant message that carries
 * "metrics" {ms, calls, tools, tokensIn, tokensOut, firstMs, model?} is recorded once, when its run ends (Bridge "run done").
 * Condition checks (S51Check) add their own calls. 120 days are kept.
 */
object S51Usage {
    private val lock = Any()
    private val file: File get() = File(Y.app.filesDir, "s51/usage.json").apply { parentFile?.mkdirs() }
    @Volatile private var cache: JSONObject? = null

    private fun data(): JSONObject = synchronized(lock) { cache ?: S51Rules.loadJson(file) { Diag.i("usage", it) }.also { cache = it } }
    private fun save() = synchronized(lock) { try { file.writeText(data().toString()) } catch (e: Throwable) { Diag.e("usage", "save", e) } }
    private fun day(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** Adds one record (also usable without a message: side calls). */
    fun add(model: String, tokensIn: Int, tokensOut: Int, calls: Int, msgs: Int, at: Long = System.currentTimeMillis(), ms: Long = 0) = synchronized(lock) {
        val d = data()
        val days = d.optJSONObject("days") ?: JSONObject().also { d.put("days", it) }
        val k = day(at)
        val dd = days.optJSONObject(k) ?: JSONObject().also { days.put(k, it) }
        val m = model.ifEmpty { "?" }
        val r = dd.optJSONObject(m) ?: JSONObject().also { dd.put(m, it) }
        r.put("in", r.optLong("in") + tokensIn).put("out", r.optLong("out") + tokensOut).put("calls", r.optLong("calls") + calls).put("msgs", r.optLong("msgs") + msgs).put("ms", r.optLong("ms") + ms)
        // keep 120 days
        val keys = days.keys().asSequence().toList().sorted()
        if (keys.size > 120) for (x in keys.take(keys.size - 120)) days.remove(x)
        save()
    }

    /** A run ended: record the final messages with metrics that are not recorded yet. */
    fun onRunDone(convoId: String) {
        val c = Convos.load(convoId) ?: return
        synchronized(lock) {
            val d = data()
            val seen = d.optJSONObject("seen") ?: JSONObject().also { d.put("seen", it) }
            val mine = seen.optJSONArray(convoId) ?: JSONArray()
            val have = (0 until mine.length()).map { mine.optString(it) }.toMutableSet()
            var added = false
            for (i in 0 until c.messages.length()) {
                val m = c.messages.optJSONObject(i) ?: continue
                val mt = m.optJSONObject("metrics") ?: continue
                if (m.optString("role") != "assistant") continue
                val key = (if (m.has("ts")) m.optLong("ts").toString() else "i$i")
                if (key in have) continue
                have.add(key); added = true
                add(mt.optString("model").ifEmpty { Cfg.model }, mt.optInt("tokensIn"), mt.optInt("tokensOut"), mt.optInt("calls"), 1, if (m.has("ts")) m.optLong("ts") else System.currentTimeMillis(), mt.optLong("ms"))
            }
            if (added) {
                seen.put(convoId, JSONArray(have.toList().takeLast(200)))
                // forget conversations beyond the newest 300
                val ks = seen.keys().asSequence().toList()
                if (ks.size > 300) for (x in ks.take(ks.size - 300)) seen.remove(x)
                save()
            }
        }
    }

    /** usage.stats: {days:[{day, tokensIn, tokensOut, calls, msgs, models:[…]}] newest first, models:[…] by tokens, total:{…}}. */
    fun stats(daysBack: Int = 30): JSONObject = synchronized(lock) {
        val days = data().optJSONObject("days") ?: JSONObject()
        val keys = days.keys().asSequence().toList().sortedDescending()
        val cut = day(System.currentTimeMillis() - (daysBack - 1L) * 86400000L)
        val outDays = JSONArray(); val per = LinkedHashMap<String, LongArray>(); val tot = LongArray(5)
        for (k in keys) {
            if (k < cut) continue
            val dd = days.optJSONObject(k) ?: continue
            val sum = LongArray(5); val models = JSONArray()
            for (m in dd.keys().asSequence().toList().sorted()) {
                val r = dd.getJSONObject(m)
                val v = longArrayOf(r.optLong("in"), r.optLong("out"), r.optLong("calls"), r.optLong("msgs"), r.optLong("ms"))
                for (i in 0..4) { sum[i] += v[i]; tot[i] += v[i] }
                val p = per.getOrPut(m) { LongArray(5) }; for (i in 0..4) p[i] += v[i]
                models.put(row(v).put("model", m))
            }
            outDays.put(row(sum).put("day", k).put("models", models))
        }
        val ms = JSONArray(); per.entries.sortedByDescending { it.value[0] + it.value[1] }.forEach { (m, v) -> ms.put(row(v).put("model", m)) }
        JSONObject().put("days", outDays).put("models", ms).put("total", row(tot)).put("daysBack", daysBack)
    }

    private fun row(v: LongArray) = JSONObject().put("tokensIn", v[0]).put("tokensOut", v[1]).put("calls", v[2]).put("msgs", v[3]).put("ms", v[4])

    fun clear() = synchronized(lock) { cache = JSONObject(); save() }
}
