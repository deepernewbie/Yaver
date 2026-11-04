package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Memory settings (the original's CFG.memory). Stored with Prefs under "mem.*". */
object MemoryXCfg {
    var activeMax: Int get() = max(10, Prefs.int("mem.activeMax", 400)); set(v) = Prefs.put("mem.activeMax", max(10, v).toString())
    var draftMax: Int get() = max(5, Prefs.int("mem.draftMax", 200)); set(v) = Prefs.put("mem.draftMax", max(5, v).toString())
    var draftDays: Int get() = max(1, Prefs.int("mem.draftDays", 14)); set(v) = Prefs.put("mem.draftDays", max(1, v).toString())
    var promptNotes: Int get() = Prefs.int("mem.promptNotes", 40).coerceIn(5, 400); set(v) = Prefs.put("mem.promptNotes", v.coerceIn(5, 400).toString())
    var entryChars: Int get() = Prefs.int("mem.entryChars", 500).coerceIn(100, 2000); set(v) = Prefs.put("mem.entryChars", v.coerceIn(100, 2000).toString())
    var userChars: Int get() = max(20000, Prefs.int("mem.userChars", 20000)); set(v) = Prefs.put("mem.userChars", max(20000, v).toString())
    /** reasoning pass over the topic outline when keywords found nothing (CFG.memory.reason) */
    var reason: Boolean get() = Prefs.on("mem.reason", true); set(v) = Prefs.set("mem.reason", v)
}

/**
 * Port of the original's Memory + MemMeta + MemRAG: two targets ("user" = profile, "memory" = yaver's notes),
 * draft → active tiers with a trial counted in days of use, pinning, usage counts, curation of outdated entries,
 * a restorable trash, and vectorless retrieval (BM25 over text + model-written search keys, then an LLM pass).
 */
object MemoryX {
    const val DELIM = "\n§\n"
    private const val HALF_LIFE_DAYS = 30.0
    private const val PRIOR_USES = 1.0
    private const val PRIOR_TURNS = 10.0
    private const val GRACE_DAYS = 7
    private const val GRACE_TURNS = 20
    private const val KV = 2
    private const val DAY = 864e5

    /** Fired after anything changed (UI.renderMemory). */
    @Volatile var onChange: (() -> Unit)? = null
    /** A short notice for the user (UI.note), e.g. what curation retired. */
    @Volatile var onNote: ((String) -> Unit)? = null
    /** Optional System-1 judge: for each instruction return a probability (0..1) that the note helps, or null when unavailable. */
    @Volatile var s1Rag: ((query: String, instructions: List<String>, cancel: Cancel?) -> List<Double>?)? = null

    private val dir: File get() = File(Y.app.filesDir, "memory").apply { mkdirs() }
    private val entriesFile: File get() = File(dir, "entries.json")
    private val metaFile: File get() = File(dir, "meta.json")
    private val legacyFile: File get() = File(Y.app.filesDir, "memory.json")

    private var E: JSONObject? = null
    private var D: JSONObject? = null
    private var legacyM = -1L
    @Volatile private var roomNote: String? = null
    @Volatile private var busyIndex = false
    @Volatile private var retryAt = 0L

    private fun changed() { try { onChange?.invoke() } catch (_: Throwable) {} }

    // ---------------- text helpers ----------------
    private val TR = Locale("tr")
    fun fold(t: String?): String = Normalizer.normalize((t ?: "").lowercase(TR), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").replace('ı', 'i').replace(Regex("\\s+"), " ").trim()

    fun memKey(target: String, text: String): String {
        var h = 5381
        val s = target + "|" + fold(text)
        for (c in s) h = (h shl 5) + h + c.code
        return (h.toLong() and 0xffffffffL).toString(36)
    }

    private val TOK3 = Regex("[\\p{L}\\p{N}_]{3,}")
    private val TOK2 = Regex("[\\p{L}\\p{N}_]{2,}")
    fun memToks(t: String): Set<String> = TOK3.findAll(fold(t)).map { it.value.take(6) }.toSet()
    fun memSim(a: String, b: String): Double {
        val A = memToks(a); val B = memToks(b)
        if (A.isEmpty() || B.isEmpty()) return 0.0
        return A.count { it in B }.toDouble() / min(A.size, B.size)
    }

    private fun jclip(s: String, n: Int) = if (s.length > n) s.substring(0, n) + "\n…[truncated ${s.length - n} chars]" else s

    private fun firstJson(t: String?): JSONObject? {
        val s = (t ?: "").replace(Regex("^```(?:json)?\\s*|\\s*```\\s*$"), "")
        val a = s.indexOf('{'); if (a < 0) return null
        var d = 0; var q = false; var esc = false
        for (i in a until s.length) {
            val c = s[i]
            if (q) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') q = false; continue }
            if (c == '"') q = true else if (c == '{') d++ else if (c == '}' && --d == 0) return try { JSONObject(s.substring(a, i + 1)) } catch (_: Exception) { null }
        }
        return null
    }

    fun relTime(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000.0
        if (Y.tr) {
            if (s < 60) return "az önce"; if (s < 3600) return "${floor(s / 60).toInt()} dk önce"; if (s < 86400) return "${floor(s / 3600).toInt()} sa önce"
            if (s < 604800) return "${floor(s / 86400).toInt()} gün önce"; return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, TR).format(java.util.Date(t))
        }
        if (s < 60) return "just now"; if (s < 3600) return "${floor(s / 60).toInt()} min ago"; if (s < 86400) return "${floor(s / 3600).toInt()} h ago"
        if (s < 604800) return "${floor(s / 86400).toInt()} d ago"; return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))
    }

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val EE = "(?![\\p{L}\\p{N}])"
    private val IC = setOf(RegexOption.IGNORE_CASE)
    val IDENTITY_RX = Regex("(kullan[ıi]c[ıi]|${B}user$EE|${B}ben(im)?$EE)[\\s\\S]{0,60}$B(ad[ıi]|ismi|soyad[ıi]|lakab[ıi]|name|called|doğum|birthday|çalış[ıi]yor|çalışır|works? (at|for|as)|unvan[ıi]|görevi|job title)$EE|$B(ad[ıi]|ismi|name is|soyad[ıi])$EE[\\s\\S]{0,40}(kullan[ıi]c[ıi]|${B}user$EE)", IC)
    /** a question about the user: first-person words, or Turkish first-person endings */
    val SELF_RX = Regex("$B(ben(im)?|bana|beni|bende|kendim|ad[ıi]m[ıi]?|ismim|kimim|soyad[ıi]m|my|me|mine|i am|who am i|about me|hakkımda|hatırlıyor musun|biliyor musun|(eş|kar[ıi]|koca|hanım|nişanl[ıi]|sevgil[ıi]|anne|baba|kardeş|abla|ağabey|abi|oğl|oğul|k[ıi]z|çocu[ğk]|torun|arkadaş|müdür|patron|şef|ekib|şirket|ev|araba|telefon|adres|numara|mail|doğum gün|yaş)[ıiuü]?m(ın|[ıiuü]n|[ıiuü]|a|e|da|de|dan|den|la|le)?)$EE|\\p{L}{2,}(yorum|[ıiuü]yorum|[dt][ıiuü]m|[ğg][ıiuü]m|[ıiuü]m[ıiuü]n|[ıiuü]m[ıiuü]|[ıiuü]mda|[ıiuü]mdan|[ıiuü]me|[ıiuü]ma|m[ıiuü]z)$EE", IC)
    /** standing rules (the original's Rules.RX) */
    val RULE_RX = Regex("^\\s*(kural(lar)?|talimat|rule(s)?|instruction(s)?)\\s*[:\\-–]|\\b(tüm|bütün|her) (konuşma|oturum|sohbet|session)|\\bher zaman\\b|\\bdaima\\b|\\basla\\b|\\bhiçbir zaman\\b|\\balways\\b|\\bnever\\b|in (every|all) (conversation|session|chat)s?", IC)

    // ---------------- storage ----------------
    private fun normTarget(t: String?) = if (t == "user") "user" else "memory"

    @Synchronized private fun ent(): JSONObject {
        var e = E
        if (e == null) {
            e = try { if (entriesFile.exists()) JSONObject(entriesFile.readText()) else null } catch (_: Exception) { null } ?: JSONObject()
            if (!e.has("user")) e.put("user", JSONArray())
            if (!e.has("memory")) e.put("memory", JSONArray())
            E = e
        }
        syncLegacy(e)
        return e
    }

    /** Brings in entries of the old Store.Memory once (and anything added there later, e.g. by Migration). */
    @Synchronized private fun syncLegacy(e: JSONObject) {
        val lf = legacyFile
        val m = if (lf.exists()) lf.lastModified() else 0L
        if (m == legacyM) return
        legacyM = m
        if (m == 0L) return
        val d = meta()
        val seen = d.optJSONObject("legacySeen") ?: JSONObject().also { d.put("legacySeen", it) }
        var n = 0
        for ((old, t) in listOf("user" to "user", "notes" to "memory")) {
            val l = try { Memory.legacyEntries(old) } catch (_: Throwable) { emptyList<String>() }
            val arr = e.getJSONArray(t)
            for (x in l) {
                val v = x.trim(); if (v.isEmpty()) continue
                val k = memKey(t, v); if (seen.has(k)) continue
                seen.put(k, true)
                if ((0 until arr.length()).any { fold(arr.optString(it)) == fold(v) }) continue
                arr.put(v); n++
                ensure(t, v, "user")
            }
        }
        if (n > 0) { storeEnt(); Diag.i("memory", "imported $n entries from the old memory store") }
        saveMeta()
    }

    @Synchronized private fun storeEnt() {
        try { val tmp = File(dir, "entries.tmp"); tmp.writeText(E.toString()); tmp.renameTo(entriesFile) } catch (e: Exception) { Diag.e("memory", "save entries", e) }
    }

    @Synchronized fun entries(target: String): List<String> {
        val a = ent().getJSONArray(normTarget(target))
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    @Synchronized fun save(target: String, list: List<String>) { ent().put(normTarget(target), JSONArray(list)); storeEnt() }

    fun limit(target: String): Int = if (target == "user") MemoryXCfg.userChars else 10_000_000

    @Synchronized fun meta(): JSONObject {
        var d = D
        if (d == null) {
            d = try { if (metaFile.exists()) JSONObject(metaFile.readText()) else null } catch (_: Exception) { null }
            val dd: JSONObject = if (d == null || d.optJSONObject("items") == null) JSONObject().put("v", 1).put("items", JSONObject()).put("trash", JSONArray()) else d
            if (dd.optJSONArray("trash") == null) dd.put("trash", JSONArray())
            D = dd
            return dd
        }
        return d
    }

    @Synchronized fun saveMeta() {
        try { val tmp = File(dir, "meta.tmp"); tmp.writeText(meta().toString(1)); tmp.renameTo(metaFile) } catch (e: Exception) { Diag.e("memory", "save meta", e) }
    }

    private fun items() = meta().getJSONObject("items")
    @Synchronized fun mget(t: String, text: String): JSONObject? = items().optJSONObject(memKey(t, text))
    @Synchronized fun ensure(t: String, text: String, by: String = "yaver"): JSONObject {
        val k = memKey(t, text)
        items().optJSONObject(k)?.let { return it }
        val now = System.currentTimeMillis()
        val m = JSONObject().put("created", now).put("updated", now).put("by", by).put("bornTurn", meta().optInt("turns", 0))
        items().put(k, m); return m
    }
    @Synchronized fun pinned(target: String, entry: String): Boolean = mget(normTarget(target), entry)?.optBoolean("pinned") == true
    @Synchronized fun setPinned(t: String, text: String, on: Boolean) { val m = ensure(t, text, "user"); m.put("pinned", on).put("updated", System.currentTimeMillis()); saveMeta() }
    @Synchronized fun move(t: String, oldText: String, newText: String) {
        val a = memKey(t, oldText); val b = memKey(t, newText); val now = System.currentTimeMillis()
        if (a == b) { items().optJSONObject(a)?.put("updated", now); saveMeta(); return }
        val src = items().optJSONObject(a)
        val nm = if (src != null) JSONObject(src.toString()) else JSONObject().put("created", now).put("by", "user")
        nm.put("updated", now); nm.remove("conflict")
        items().put(b, nm); items().remove(a); saveMeta()
    }
    @Synchronized fun trash(t: String, text: String, kind: String, reason: String, extra: Map<String, Any?> = emptyMap()) {
        val k = memKey(t, text); val m = items().optJSONObject(k); items().remove(k)
        val now = System.currentTimeMillis()
        val x = JSONObject().put("id", "m" + now.toString(36) + java.util.UUID.randomUUID().toString().replace("-", "").take(3))
            .put("target", t).put("text", text).put("kind", kind).put("reason", reason).put("at", now)
        m?.let { if (it.has("created")) x.put("created", it.opt("created")) }
        for ((kk, v) in extra) if (v != null) x.put(kk, v)
        val old = meta().getJSONArray("trash"); val nt = JSONArray().put(x)
        for (i in 0 until min(old.length(), 199)) nt.put(old.get(i))
        meta().put("trash", nt); saveMeta()
    }
    @Synchronized fun forget(id: String) {
        val old = meta().getJSONArray("trash"); val nt = JSONArray()
        for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("id") != id) nt.put(o) }
        meta().put("trash", nt); saveMeta()
    }

    /** Merges the original app's memories/meta.json (keys are compatible) — for Migration. */
    @Synchronized fun importMeta(json: String) {
        val j = try { JSONObject(json) } catch (_: Exception) { return }
        val its = j.optJSONObject("items") ?: return
        for (k in its.keys()) if (!items().has(k)) items().put(k, its.get(k))
        val tr = j.optJSONArray("trash"); if (tr != null) { val cur = meta().getJSONArray("trash"); for (i in 0 until tr.length()) if (cur.length() < 200) cur.put(tr.get(i)) }
        for (k in listOf("turns", "dayCount", "lastDay")) if (j.has(k) && !meta().has(k)) meta().put(k, j.get(k))
        saveMeta(); changed()
    }

    // number helpers (JS ?? and || semantics)
    private fun num(m: JSONObject?, k: String): Double? = if (m != null && m.has(k) && !m.isNull(k)) m.optDouble(k).takeIf { !it.isNaN() } else null
    private fun truthy(m: JSONObject?, k: String): Double? = num(m, k)?.takeIf { it != 0.0 }

    // ---------------- usage ----------------
    fun freq(meta: JSONObject?, now: Long = System.currentTimeMillis()): Double {
        val uses = truthy(meta, "uses") ?: return 0.0
        val days = (now - (truthy(meta, "lastUsed") ?: truthy(meta, "created") ?: now.toDouble())) / DAY
        return uses * 0.5.pow(days / HALF_LIFE_DAYS)
    }

    /**
     * After every turn: counts which entries came up (most of their words in what the user asked or yaver answered),
     * starts a new day of use when needed, and runs the tier lifecycle. Returns how many entries were used.
     */
    @Synchronized fun noteUse(msgs: List<JSONObject>): Int {
        val text = msgs.filter { (it.optString("role") == "user" && !it.optBoolean("summary")) || (it.optString("role") == "assistant" && it.opt("content") is String && it.optString("content").isNotEmpty()) }
            .joinToString("\n") { it.opt("content") as? String ?: "" }
        val newDay = touchDay()
        val turn = memToks(text); val d = meta(); d.put("turns", d.optInt("turns", 0) + 1)
        if (turn.size < 3) { saveMeta(); return 0 }
        var n = 0; val now = System.currentTimeMillis(); val turns = d.optInt("turns")
        for (t in listOf("memory", "user")) for (e in entries(t)) {
            val et = memToks(e); if (et.size < 2) continue
            val hit = et.count { it in turn }; val r = hit.toDouble() / et.size
            if (r >= 0.5 || (hit >= 4 && r >= 0.34)) {
                val m = ensure(t, e); m.put("uses", freq(m, now) + 1).put("lastUsed", now); norm(m)
                if (m.optInt("lastHitTurn", -1) != turns) { m.put("hits", m.optInt("hits", 0) + 1).put("lastHitTurn", turns) }
                n++
            }
        }
        saveMeta()
        if (newDay || n > 0) try { lifecycle() } catch (_: Throwable) {}
        if (n > 0) changed()
        return n
    }
    fun noteUse(messages: JSONArray, from: Int): Int = noteUse((max(0, from) until messages.length()).mapNotNull { messages.optJSONObject(it) })

    private fun exposure(m: JSONObject?): Int = max(0, meta().optInt("turns", 0) - (num(m, "bornTurn")?.toInt() ?: 0))
    private fun rate(m: JSONObject?, now: Long) = (freq(m, now) + PRIOR_USES) / (min(300, exposure(m)) + PRIOR_TURNS)
    private fun isHot(t: String, e: String, now: Long): Boolean { val m = mget(t, e); val lu = truthy(m, "lastUsed") ?: return false; return now - lu < 14 * DAY && freq(m, now) >= 2 }
    private fun inGrace(t: String, e: String, now: Long): Boolean {
        val m = mget(t, e) ?: return false
        val days = (now - (truthy(m, "created") ?: now.toDouble())) / DAY
        return days < GRACE_DAYS || exposure(m) < GRACE_TURNS
    }
    private fun keepScore(t: String, e: String, now: Long): Double {
        val m = mget(t, e) ?: JSONObject()
        val used = truthy(m, "lastUsed")?.let { exp(-((now - it) / DAY) / 21) } ?: 0.0
        return 20 * rate(m, now) + 1.5 * used + (if (m.optString("by") == "user") 0.5 else 0.0)
    }

    /** retire the least useful unpinned entries until `need` more characters fit (used for the size-limited profile) */
    @Synchronized private fun makeRoom(target: String, need: Int, keep: List<String>): List<String> {
        val list = entries(target); val lim = limit(target); val now = System.currentTimeMillis()
        var size = list.joinToString(DELIM).length
        data class C(val e: String, val k: Double, val hot: Boolean)
        val cand = list.filter { !pinned(target, it) && it !in keep }.map { C(it, keepScore(target, it, now), isHot(target, it, now) || inGrace(target, it, now)) }
            .sortedWith(compareBy<C> { if (it.hot) 1 else 0 }.thenBy { it.k }.thenByDescending { it.e.length })
        val out = ArrayList<C>()
        for (c in cand) { if (size + need <= lim) break; if (c.hot) break; out.add(c); size -= c.e.length + DELIM.length }
        if (size + need > lim) {
            roomNote = "Memory is full and the remaining unpinned entries are either used often or still new (in their first $GRACE_DAYS days / $GRACE_TURNS turns), so none were retired. Merge or shorten related entries (replace several with one concise entry), then add the new one."
            return emptyList()
        }
        if (out.isNotEmpty()) {
            val drop = out.map { it.e }; save(target, list.filter { it !in drop })
            for (c in out) {
                val m = mget(target, c.e)
                val last = truthy(m, "lastUsed")?.let { "last used ${relTime(it.toLong())}" } ?: "never came up"
                trash(target, c.e, "expired", "retired to make room — least useful entry ($last in ${exposure(m)} conversation turns since it was saved)")
            }
        }
        return out.map { it.e }
    }

    /** background check that retires outdated entries after new ones arrive (blocking: call from a background thread) */
    fun curate(target: String, added: List<String>, by: String = "yaver") {
        if (added.isEmpty()) return
        val notes = ArrayList<String>()
        for (nw in added) {
            data class Cd(val e: String, val s: Double, val pin: Boolean)
            val cands = synchronized(this) {
                val list = entries(target); if (nw !in list) null
                else list.filter { it != nw && it !in added }.map { Cd(it, memSim(it, nw), pinned(target, it)) }.filter { it.s >= 0.3 }.sortedByDescending { it.s }.take(5)
            }
            if (cands.isNullOrEmpty()) continue
            var verdicts: List<Triple<Int, String, String>>? = null
            if (Cfg.fastModel.ifEmpty { Cfg.model }.isNotEmpty()) try {
                val r = Llm.ask(
                    "You keep an assistant's long-term memory clean. A NEW memory entry was just saved. For each OLD entry decide how it relates to the NEW one:\n" +
                        "\"duplicate\" — says the same thing; \"replaced\" — the new entry is a newer/updated version of the same fact (e.g. a changed preference, new phone number, new project state); \"invalidated\" — the new entry shows the old one is no longer true; \"keep\" — different facts or both still true.\n" +
                        "Reply with JSON only: {\"decisions\":[{\"i\": number, \"relation\": \"duplicate|replaced|invalidated|keep\", \"reason\": \"max 12 words\"}]}",
                    "NEW: $nw\n\nOLD:\n" + cands.mapIndexed { i, c -> "#$i ${c.e}" }.joinToString("\n"), null, 400, true
                )
                val m = Regex("\\{[\\s\\S]*\\}").find(r)
                val arr = m?.let { try { JSONObject(it.value) } catch (_: Exception) { null } }?.optJSONArray("decisions")
                if (arr != null) verdicts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Triple(it.optInt("i", -1), it.optString("relation"), it.optString("reason")) }
            } catch (_: Throwable) {}
            if (verdicts == null) verdicts = cands.mapIndexed { i, c ->
                Triple(i, if (c.s >= 0.85) "duplicate" else if (c.s >= 0.6) "replaced" else "keep", if (c.s >= 0.6) "very similar wording (automatic check)" else "")
            }
            synchronized(this) {
                for ((i, rel, reason) in verdicts) {
                    val c = cands.getOrNull(i) ?: continue
                    if (rel !in listOf("duplicate", "replaced", "invalidated")) continue
                    if (c.pin) {
                        val mm = ensure(target, nw, by); mm.put("conflict", JSONObject().put("pinned", c.e).put("relation", rel).put("reason", reason)); saveMeta()
                        notes.add("“${nw.take(60)}${if (nw.length > 60) "…" else ""}” may conflict with a pinned memory — kept both; check the Memory tab"); continue
                    }
                    val cur = entries(target); if (c.e !in cur) continue
                    val om = mget(target, c.e); val nm = ensure(target, nw)
                    if (om != null) { // the newer entry takes over the older one's standing
                        nm.put("hits", nm.optInt("hits", 0) + om.optInt("hits", 0)); nm.put("uses", nm.optDouble("uses", 0.0).let { if (it.isNaN()) 0.0 else it } + om.optDouble("uses", 0.0).let { if (it.isNaN()) 0.0 else it })
                        if (om.optString("tier") != "draft") {
                            if (om.has("tier")) nm.put("tier", om.optString("tier")) // om.tier || nm.tier
                            if (!om.has("tier") && nm.optString("tier") == "draft") nm.remove("tier")
                            if (num(nm, "promotedDay") == null) num(om, "promotedDay")?.let { nm.put("promotedDay", it) }
                        }
                        val ob = num(om, "bornDay"); val nb = num(nm, "bornDay")
                        if (ob != null && (nb == null || ob < nb)) nm.put("bornDay", ob)
                    }
                    save(target, cur.filter { it != c.e })
                    val why = reason.ifEmpty { mapOf("duplicate" to "same as a newer entry", "replaced" to "replaced by a newer entry", "invalidated" to "no longer true")[rel]!! }
                    trash(target, c.e, rel, why, mapOf("replacedBy" to nw))
                    notes.add("${mapOf("duplicate" to "removed duplicate", "replaced" to "replaced", "invalidated" to "invalidated")[rel]} “${c.e.take(60)}${if (c.e.length > 60) "…" else ""}”")
                }
            }
        }
        if (notes.isNotEmpty()) {
            changed()
            try { onNote?.invoke("🧠 Memory: " + notes.joinToString(" · ") + ". Restore anything from the Memory tab → Trash.") } catch (_: Throwable) {}
        }
    }

    // ---------------- tiers: DRAFT (trial) → ACTIVE ----------------
    private fun promptCeil(): Int = max(4000, (minOf(Compactor.budget(), 120000) * 0.25 * 3.5).roundToInt())
    private fun todayKey(): String { val c = java.util.Calendar.getInstance(); return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)}" }
    @Synchronized fun dayCount(): Int = meta().optInt("dayCount", 0)
    /** Counts a new day of use (true when today is a new one). noteUse() calls it after every turn. */
    @Synchronized fun touchDay(): Boolean {
        val d = meta(); val t = todayKey()
        if (d.optString("lastDay") == t) return false
        d.put("lastDay", t).put("dayCount", d.optInt("dayCount", 0) + 1); saveMeta(); return true
    }
    @Synchronized fun tier(e: String): String {
        val m = mget("memory", e)
        return if (m != null && m.optString("tier") == "draft" && !m.optBoolean("pinned") && !IDENTITY_RX.containsMatchIn(e)) "draft" else "active"
    }
    private fun norm(m: JSONObject): JSONObject {
        if (num(m, "bornDay") == null) m.put("bornDay", dayCount())
        if (num(m, "hits") == null) m.put("hits", (num(m, "uses") ?: 0.0).roundToInt())
        return m
    }
    private fun bornDay(m: JSONObject?) = num(m, "bornDay") ?: dayCount().toDouble()
    private fun hits(m: JSONObject?) = num(m, "hits") ?: (num(m, "uses") ?: 0.0).roundToInt().toDouble()
    private fun ageDays(m: JSONObject?): Double = max(1.0, dayCount() - bornDay(m) + 1)
    private fun useRate(m: JSONObject?): Double = hits(m) / ageDays(m)
    private fun daysLeft(m: JSONObject?): Int = max(0.0, MemoryXCfg.draftDays - ageDays(m) + 1).toInt()

    /** promotion / retirement; runs when a new day of use starts and after new entries arrive */
    @Synchronized fun lifecycle(): JSONObject {
        val all = entries("memory"); if (all.isEmpty()) return JSONObject().put("promoted", 0).put("retired", 0)
        fun M(e: String) = norm(ensure("memory", e))
        val dc = dayCount(); var promoted = 0; val drop = ArrayList<Pair<String, String>>()
        val act = all.filter { tier(it) == "active" }.toMutableList(); val dr = all.filter { tier(it) == "draft" }
        val amax = MemoryXCfg.activeMax; val dmax = MemoryXCfg.draftMax; val ddays = MemoryXCfg.draftDays
        fun evictable() = act.filter { e -> !pinned("memory", e) && M(e).optString("by") != "user" && dc - (num(M(e), "promotedDay") ?: bornDay(M(e))) >= 3 && drop.none { it.first == e } }
            .sortedWith(compareBy<String> { useRate(M(it)) }.thenBy { bornDay(M(it)) })
        while (act.size > amax) { val v = evictable().firstOrNull() ?: break; act.remove(v); drop.add(v to "retired from the active tier to make room — least used (${"%.2f".format(Locale.US, useRate(M(v)))} uses per day of use)") }
        for (d in dr.sortedByDescending { useRate(M(it)) }) {
            val m = M(d); val r = useRate(m); val h = hits(m)
            var ok = h >= 1 && act.size < amax
            if (!ok && h >= 1) {
                val v = evictable().firstOrNull()
                if (v != null && r > useRate(M(v))) { act.remove(v); drop.add(v to "replaced in the active tier by a more used entry (${"%.2f".format(Locale.US, useRate(M(v)))} vs ${"%.2f".format(Locale.US, r)} uses per day of use)"); ok = true }
            }
            if (ok) { m.put("tier", "active").put("promotedDay", dc); act.add(d); promoted++; continue }
            if (ageDays(m) > ddays) { val hi = h.toInt(); drop.add(d to "draft not used enough during its $ddays-day trial ($hi use${if (hi == 1) "" else "s"} in ${ageDays(m).toInt()} days of use)") }
        }
        val drLeft = dr.filter { e -> tier(e) == "draft" && drop.none { it.first == e } }
        if (drLeft.size > dmax) {
            val order = drLeft.sortedWith(compareBy<String> { hits(M(it)) }.thenBy { bornDay(M(it)) })
            for (e in order.take(drLeft.size - dmax)) drop.add(e to "the draft tier was full — least used and oldest draft")
        }
        if (drop.isNotEmpty()) { val gone = drop.map { it.first }.toSet(); save("memory", entries("memory").filter { it !in gone }); for ((e, why) in drop) trash("memory", e, "expired", why) }
        saveMeta()
        if (promoted > 0 || drop.isNotEmpty()) changed()
        return JSONObject().put("promoted", promoted).put("retired", drop.size)
    }

    /** what goes into the system prompt: pinned and rule entries always, then the most used active entries up to the configured count */
    @Synchronized fun promptPick(): List<String> {
        val all = entries("memory"); val mx = MemoryXCfg.promptNotes; val ceil = promptCeil()
        val act = all.filter { tier(it) == "active" }
        fun must(e: String) = pinned("memory", e) || RULE_RX.containsMatchIn(e)
        val ranked = act.sortedWith(compareByDescending<String> { if (must(it)) 1 else 0 }.thenByDescending { useRate(mget("memory", it)) }.thenByDescending { num(mget("memory", it), "created") ?: 0.0 })
        val out = ArrayList<String>(); var n = 0
        for (e in ranked) { if (!must(e) && (out.size >= mx || n + e.length > ceil)) continue; out.add(e); n += e.length + DELIM.length }
        return out
    }

    /** Is this entry pinned by the user (never changed or removed by yaver)? */
    fun isPinned(target: String, entry: String) = pinned(target, entry)

    /** entries relevant to a message (active and draft), keyword path only */
    fun relevant(text: String, k: Int = 6, exclude: List<String> = emptyList(), targets: List<String> = listOf("memory")): List<String> =
        rankSync(text, k, exclude, targets).map { it.d.e }

    // ---------------- system prompt block ----------------
    /**
     * The memory section of the system prompt (the original's Memory.block). [userText] is not used here: entries
     * relevant to a message go on the user message itself — see [attach].
     */
    @Synchronized fun block(@Suppress("UNUSED_PARAMETER") userText: String? = null): String {
        fun mark(t: String, e: String) = (if (pinned(t, e)) "[pinned by the user] " else "") + e
        val pick = promptPick(); val all = entries("memory"); val m = pick.map { mark("memory", it) }; val u = entries("user").map { mark("user", it) }
        val nAct = all.count { tier(it) == "active" }; val nDr = all.size - nAct
        val sb = StringBuilder()
        if (m.isNotEmpty()) sb.append("## MEMORY (your notes — ${if (m.size == nAct) "all $nAct" else "the ${m.size} most used of $nAct"} active entries${if (nDr > 0) ", plus $nDr draft entries on trial that are not listed here" else ""}; ${if (m.size < all.size) "entries relevant to a message are attached to it as [Relevant memories]; memory action=search finds the rest" else "memory action=search finds drafts too"})\n${m.joinToString(DELIM)}\n\n")
        if (u.isNotEmpty()) sb.append("## USER PROFILE [${u.joinToString(DELIM).length}/${MemoryXCfg.userChars} chars]\n${u.joinToString(DELIM)}\n\n")
        return sb.toString()
    }

    class Attach(val content: String, val info: JSONObject, val chip: String?)

    /**
     * What the original appends to the user's message before the turn: "[Relevant memories …]" (keywords + keys,
     * plus the reasoning pass for questions about the user / requests that found nothing / long multi-part questions).
     * Blocking (may call the model). [Attach.content] is appended to the user message; [Attach.info] is the _mem record;
     * [Attach.chip] is an optional progress line ("memory search · 3 · LLM tree search").
     */
    fun attach(text: String, cancel: Cancel? = null): Attach {
        try {
            val inPrompt = promptPick()
            var rel = relevant(text, exclude = inPrompt)
            val info = JSONObject().put("pick", inPrompt.size).put("how", if (rel.isNotEmpty()) "keywords + keys" else "none")
            var chip: String? = null
            val outside = entries("memory").size - inPrompt.size
            val isQ = Regex("\\?|\\b(ne|neydi|nedir|kim|nerede|hangi|kaç|hatırl|biliyor|öner|what|who|where|which|remember|know|suggest|recommend)\\b", IC).containsMatchIn(text)
            val isReq = isQ || Regex("\\b(ver|yap|öner|çevir|hesapla|bul|hazırla|yaz|çıkar|listele|göster|planla|ayarla|make|give|find|convert|calculate|prepare|write|list|show|plan)\\b", IC).containsMatchIn(text)
            val words = Regex("\\p{L}+").findAll(text).count()
            if (outside > 0 && ((rel.isEmpty() && SELF_RX.containsMatchIn(text) && isQ) || (MemoryXCfg.reason && rel.isEmpty() && isReq && words >= 4) ||
                    (outside >= 10 && text.length >= 90 && Regex("\\?|,| ve | and ").findAll(text).count() >= 2))) {
                val r = search(text, 6, listOf("memory"), inPrompt, true, cancel)
                val add = r.hits.map { it.text }.filter { it !in rel }
                if (r.how.startsWith("LLM") || r.how.startsWith("System")) {
                    val had = rel.size
                    rel = (rel + add).take(8)
                    info.put("how", "${if (had > 0) "keywords + keys, then " else ""}${r.how}")
                    chip = "${Y.t("memory search", "hafıza araması")} · ${rel.size} · ${r.how}"
                }
            }
            info.put("rel", JSONArray(rel.map { jclip(it, 200) }))
            val content = if (rel.isNotEmpty()) "\n\n[Relevant memories — yaver's notes that may apply to this message]\n" + rel.joinToString("\n") { "- " + it + if (tier(it) == "draft") " (draft)" else "" } else ""
            return Attach(content, info, chip)
        } catch (e: CancelledException) { throw e } catch (e: Throwable) {
            if (cancel?.cancelled == true) throw CancelledException()
            return Attach("", JSONObject().put("how", "none"), null)
        }
    }

    // ---------------- vectorless RAG ----------------
    val TOPICS = listOf("identity", "family", "work", "preferences", "rules", "projects", "tools", "other")
    private val TOPIC = mapOf("identity" to ("Identity" to "Kimlik"), "family" to ("Family & people" to "Aile ve çevre"), "work" to ("Work" to "İş"),
        "preferences" to ("Preferences" to "Tercihler"), "rules" to ("Rules" to "Kurallar"), "projects" to ("Projects" to "Projeler"),
        "tools" to ("Tools & setup" to "Araçlar ve ortam"), "other" to ("Other" to "Diğer"))
    private val STOP: Set<String> by lazy {
        "ne nedir neydi neler nasıl hangi kaç neden niye nerede nereye nereden kim kimin zaman misin mısın musun müsün mısınız misiniz lütfen şey şeyler bir ve veya ile mi mı mu mü da de ki bu şu o için icin gibi daha en çok cok var yok bugün yarın dün hafta sonu gün ay yıl saat sabah öğle akşam gece what is are was the a an of to in on my me do does did who how where when which can could would should please some any this that there today tomorrow yesterday week day month year olarak eder ederim isterim istiyorum isteriz yaparım yapar kullanırım kullan prefer want like use"
            .split(" ").map { fold(it) }.toSet()
    }
    fun label(k: String): String { val t = TOPIC[k] ?: TOPIC["other"]!!; return if (Y.tr) t.second else t.first }
    private fun topicEn(k: String) = (TOPIC[k] ?: TOPIC["other"]!!).first

    private val RX_NAME = Regex("ad[ıi]|isim|ismi|name|doğum|birth|soyad", IC)
    private val RX_FAM1 = Regex("(^|[^\\p{L}])(eşi?|kar[ıi]s[ıi]|koca(s[ıi])?|hanım[ıi]|nişanl[ıi]|sevgili|kız[ıi]|oğl|çocu|anne|baba|kardeş|ailesi|torun|arkadaş|wife|husband|daughter|son|child(ren)?|mother|father|friend)(?![\\p{L}])", IC)
    private val RX_FAM2 = Regex("(^|[^\\p{L}])(oğlu|kızı|çocuğu|annesi|babası|kardeşi|arkadaşı)", IC)
    private val RX_HOME = Regex("(yaşıyor|oturuyor|ikamet|memleket|doğdu|lives? in|based in|from )", IC)
    private val RX_PROJ = Regex("(proje|project)", IC)
    private val RX_HOBBY = Regex("(^|[^\\p{L}])(spor|maç|lig|futbol|basketbol|kitap|roman|film|dizi(?!n)|müzik|yemek|tarif|seyahat|tatil|yolculuk|tren(?!d)|uçak|uçuş|hava durumu|döviz|sports?|books?|movies?|music|recipes?|travel|trains?|weather|currency)", IC)
    private val RX_NOTWORK = Regex("(çalıştığı|şirketi|ofisi|müdürü)", IC)
    private val RX_WORK = Regex("(çalış|şirket|ofis|müdürüm|müdürü|yönetici|ekip|takım|departman|iş yeri|toplantı|works?|company|office|team|manager|meeting)", IC)
    private val RX_PREF = Regex("(tercih|sever|sevmez|ister|istemez|hoşlan|prefer|likes?|dislikes?|wants?|format|üslup|ton|kısa|uzun)", IC)
    private val RX_TOOLS = Regex("(araç|tool|yazılım|software|tarayıcı|browser|telefon|android|iphone|windows|linux|python|excel|outlook|ews|owa|model|api)", IC)
    fun heurTopic(e: String): String {
        if (RULE_RX.containsMatchIn(e)) return "rules"
        if (IDENTITY_RX.containsMatchIn(e) && RX_NAME.containsMatchIn(e)) return "identity"
        if (RX_FAM1.containsMatchIn(e) || RX_FAM2.containsMatchIn(e)) return "family"
        if (RX_HOME.containsMatchIn(e)) return "identity"
        if (RX_PROJ.containsMatchIn(e)) return "projects"
        if (RX_HOBBY.containsMatchIn(e) && !RX_NOTWORK.containsMatchIn(e)) return "preferences"
        if (RX_WORK.containsMatchIn(e)) return "work"
        if (RX_PREF.containsMatchIn(e)) return "preferences"
        if (RX_TOOLS.containsMatchIn(e)) return "tools"
        return "other"
    }
    @Synchronized private fun ragMeta(t: String, e: String): JSONObject? { val m = mget(t, e); return if (m != null && m.optString("kh") == memKey(t, e) && m.optInt("kv", 0) == KV) m else null }
    fun topic(t: String, e: String): String = ragMeta(t, e)?.optString("topic")?.takeIf { it.isNotEmpty() } ?: heurTopic(e)
    fun keys(t: String, e: String): List<String> { val a = ragMeta(t, e)?.optJSONArray("keys") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) } }

    class Doc(val t: String, val e: String, val id: String)
    class Ranked(val d: Doc, val score: Double, val hit: Double, val rare: Boolean)
    private fun allDocs(targets: List<String> = listOf("user", "memory")): List<Doc> {
        val out = ArrayList<Doc>()
        for (t in targets) entries(t).forEachIndexed { i, e -> out.add(Doc(t, e, (if (t == "user") "u" else "m") + i)) }
        return out
    }
    /** notes still waiting for topics and search keys */
    fun pending(): Int = allDocs().count { keys(it.t, it.e).isEmpty() }

    /** the background indexer: topics and search keys for notes that have none (or whose text changed). Blocking; may throw. */
    fun index(max: Int = 30, cancel: Cancel? = null): Int {
        if (busyIndex || Cfg.model.isEmpty() || System.currentTimeMillis() < retryAt) return 0
        val todo = allDocs().filter { keys(it.t, it.e).isEmpty() }.take(max); if (todo.isEmpty()) return 0
        busyIndex = true
        try {
            val r = Llm.ask(
                "You index a personal assistant's memory notes so they can be found later without embeddings. For each note return \"topic\" — one of ${TOPICS.joinToString(", ")} — and \"keys\": 8-14 short search keys.\n" +
                    "Keys are the words the user would type in a message where this note MATTERS — the tasks, objects and situations the note applies to — not questions about the note itself. Include synonyms, Turkish inflected forms (maç, maçlar, maç sonuçları) and English equivalents.\n" +
                    "Examples:\n" +
                    "- \"Yemek tariflerinde ölçüleri gram ve ml olarak isterim\" → [\"tarif\", \"yemek\", \"çorba\", \"kek\", \"nasıl yapılır\", \"pişir\", \"malzeme\", \"ölçü\", \"recipe\", \"cook\", \"ingredients\", \"gram\"]\n" +
                    "- \"Döviz çevirilerinde TCMB efektif satış kurunu tercih ederim\" → [\"döviz\", \"kur\", \"dolar\", \"euro\", \"TL çevir\", \"kaç lira\", \"kaç TL\", \"exchange rate\", \"convert\", \"currency\", \"TCMB\"]\n" +
                    "- \"Kullanıcının kızı Elif ilkokula gidiyor\" → [\"kızım\", \"kızımın adı\", \"çocuğum\", \"Elif\", \"okul\", \"daughter\", \"my daughter\", \"family\"]\n" +
                    "BAD keys (never): \"tariflerde ölçüyü nasıl istiyorsun\", \"what exchange rate do you use\" — nobody types those.\n" +
                    "Reply with JSON only: {\"notes\":[{\"i\":0,\"topic\":\"preferences\",\"keys\":[\"…\"]}]}",
                todo.mapIndexed { i, x -> "$i. ${jclip(x.e, 400)}" }.joinToString("\n"), cancel, 3000, true
            )
            val j = firstJson(r) ?: JSONObject(); var n = 0; retryAt = 0
            val notes = j.optJSONArray("notes") ?: JSONArray()
            synchronized(this) {
                for (q in 0 until notes.length()) {
                    val it = notes.optJSONObject(q) ?: continue
                    val x = todo.getOrNull(it.optInt("i", -1)) ?: continue
                    val ks = it.optJSONArray("keys") ?: continue
                    val m = ensure(x.t, x.e)
                    m.put("topic", it.optString("topic").takeIf { tp -> tp in TOPICS } ?: heurTopic(x.e))
                    m.put("keys", JSONArray((0 until ks.length()).map { k -> jclip(ks.optString(k), 60) }.filter { k -> k.isNotEmpty() }.take(14)))
                    m.put("kh", memKey(x.t, x.e)).put("kv", KV); n++
                }
                if (n > 0) saveMeta()
            }
            if (n > 0) changed()
            Diag.i("memory", "memory index: $n/${todo.size} notes got topics and search keys")
            return n
        } catch (e: Throwable) { retryAt = System.currentTimeMillis() + 3 * 60_000L; throw e } // a failed call is not retried for 3 minutes
        finally { busyIndex = false }
    }

    /** Runs the indexer in the background after [delayMs] when notes are pending and [isBusy] says the agent is idle. */
    fun indexSoon(delayMs: Long = 8000, isBusy: () -> Boolean = { false }) {
        try { if (pending() == 0) return } catch (_: Throwable) { return }
        Y.main.postDelayed({ if (!isBusy()) Y.bg { try { index() } catch (_: Throwable) {} } }, delayMs)
    }

    fun toks(s: String): List<String> = TOK2.findAll(fold(s)).map { it.value }.filter { it !in STOP }.toList()
    /** 1 = same word or a long stem, 0.5 = a short stem, 0 = no */
    fun same(a: String, b: String): Double {
        if (a == b) return 1.0
        val (x, y) = if (a.length <= b.length) a to b else b to a
        if (y.startsWith(x)) { if (x.length < 3 || y.length - x.length > 5) return 0.0; return if (x.length >= 5) 1.0 else 0.5 }
        var i = 0; while (i < x.length && x[i] == y[i]) i++
        return if (i >= 5 && x.length - i <= 3 && y.length - i <= 4) 1.0 else 0.0
    }
    private val RX_USERWORD = Regex("kullan[ıi]c[ıi]|\\buser\\b", IC)
    private val RX_ASKNAME = Regex("(^|[^\\p{L}])(ad[ıi]m|ad[ıi]n|ismim|ismin|kimim|name|who am i)", IC)
    private val RX_NAMEWORD = Regex("ad[ıi]|isim|ismi|name", IC)
    /** BM25 with prefix-tolerant term matching (Turkish suffixes); keys count like text, the topic name a little */
    fun rank(query: String, docs: List<Doc>): List<Ranked> {
        val q = toks(query).distinct(); if (q.isEmpty()) return emptyList()
        val D = docs.map { it to toks(it.e + " " + keys(it.t, it.e).joinToString(" ") + " " + topic(it.t, it.e)) }
        val N = if (D.isEmpty()) 1 else D.size
        val avg = (D.sumOf { it.second.size }.toDouble() / N).let { if (it == 0.0) 1.0 else it }
        val df = q.associateWith { w -> D.count { x -> x.second.any { same(w, it) > 0 } } }
        val self = SELF_RX.containsMatchIn(query); val askName = RX_ASKNAME.containsMatchIn(query)
        val rareDf = max(1, floor(N * 0.25).toInt())
        val out = ArrayList<Ranked>()
        for ((d, tk) in D) {
            var s = 0.0; var hit = 0.0; var rare = false
            for (w in q) {
                var tf = 0; var best = 0.0
                for (t in tk) { val v = same(w, t); if (v > 0) { tf++; if (v > best) best = v } }
                if (tf == 0) continue
                hit += best; val dfw = df[w]!!
                if (dfw <= (if (best == 1.0) rareDf else 2) && w.length >= 4) rare = true
                val idf = ln(1 + (N - dfw + .5) / (dfw + .5))
                s += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * tk.size / avg))
            }
            if (self && hit > 0 && RX_USERWORD.containsMatchIn(d.e)) s += 0.3
            if (self && askName && IDENTITY_RX.containsMatchIn(d.e) && RX_NAMEWORD.containsMatchIn(d.e)) { s += 1; hit++ }
            out.add(Ranked(d, s, hit, rare))
        }
        return out.filter { it.score > 0 }.sortedByDescending { it.score }
    }
    /** cheap path (every message): keyword + keys only */
    fun rankSync(query: String, k: Int = 6, exclude: List<String> = emptyList(), targets: List<String> = listOf("memory")): List<Ranked> {
        val docs = allDocs(targets).filter { it.e !in exclude }
        val need = min(2, toks(query).size); val self = SELF_RX.containsMatchIn(query)
        return rank(query, docs).filter { it.hit >= need || (it.rare && it.hit >= 0.5) || (it.hit >= 1 && self) }.take(k)
    }

    class Hit(val t: String, val text: String, val topic: String, val tier: String)
    class Search(val hits: List<Hit>, val how: String, val total: Int)

    /** Full retrieval: keywords, then System-1 (if hooked) or one LLM call over the topic outline. Blocking. */
    fun search(query: String, k: Int = 8, targets: List<String> = listOf("user", "memory"), exclude: List<String> = emptyList(), reason: Boolean = true, cancel: Cancel? = null): Search {
        val docs = allDocs(targets).filter { it.e !in exclude }; if (docs.isEmpty()) return Search(emptyList(), "none", 0)
        val ranked = rank(query, docs); var chosen: List<Doc>? = null; var how = ""
        if (reason && docs.size > 3) {
            val s1 = s1Rag
            if (s1 != null) {
                val short = (if (ranked.isNotEmpty()) ranked.map { it.d } else docs).take(16)
                val r = try { s1(query, short.map { "Does this memory note help answer the message? Note (${label(topic(it.t, it.e))}): ${jclip(it.e, 300)}" }, cancel) } catch (e: CancelledException) { throw e } catch (_: Throwable) { null }
                if (r != null) {
                    val hits = short.indices.filter { (r.getOrNull(it) ?: 0.0) >= 0.5 }.sortedByDescending { r[it] }.map { short[it] }
                    if (hits.isNotEmpty()) { chosen = hits.take(k); how = "System-1" }
                }
            }
            if (chosen == null && Cfg.model.isNotEmpty()) {
                val star = ranked.take(12).map { it.d.id }.toSet(); val outline = StringBuilder(); var budget = 12000
                for (tp in TOPICS) {
                    val es = docs.filter { topic(it.t, it.e) == tp }; if (es.isEmpty()) continue
                    outline.append("\n## ${topicEn(tp)}\n")
                    for (d in es) { val line = "[${d.id}] ${jclip(d.e.replace(Regex("\\s+"), " "), 200)}${if (d.id in star) " ★" else ""}\n"; budget -= line.length; if (budget < 0) break; outline.append(line) }
                }
                try {
                    val res = Llm.ask(
                        "You find which of the assistant's memory notes answer the user's message. The notes are grouped by topic; each has an id in brackets; ★ marks keyword matches. Reason about meaning, not only words (\"kızımın adı\" is answered by a note about the user's daughter; \"where do I live\" by a note about the user's city). Reply with JSON only: {\"ids\": [\"…\"], \"why\": \"one sentence\"}. At most $k ids; an empty list if none applies.",
                        "Message: ${jclip(query, 1500)}\n\nMemory outline:$outline", cancel, 1200, true
                    )
                    val ids = firstJson(res)?.optJSONArray("ids") ?: JSONArray()
                    val byId = docs.associateBy { it.id }
                    chosen = (0 until ids.length()).mapNotNull { byId[ids.optString(it).replace(Regex("^\\[|\\]$"), "")] }.take(k); how = "LLM tree search"
                } catch (e: Throwable) { if (e is CancelledException || cancel?.cancelled == true) throw CancelledException() }
            }
        }
        if (chosen.isNullOrEmpty()) {
            val tried = how; chosen = rankSync(query, k, exclude, targets).map { it.d }
            how = if (chosen.isNotEmpty()) "keywords + keys" + (if (tried.isNotEmpty()) " ($tried found none)" else "") else "none"
        }
        val out = Search(chosen.map { Hit(it.t, it.e, topic(it.t, it.e), if (it.t == "memory") tier(it.e) else "active") }, how, docs.size)
        Diag.i("memory", "memory search \"${query.take(80)}\": ${out.hits.size}/${docs.size} notes via $how")
        return out
    }

    /** retrieve(text, k): the texts of the notes (profile and memory) that answer [text]. Blocking (may call the model). */
    fun retrieve(text: String, k: Int = 8, cancel: Cancel? = null): List<String> = search(text, k, cancel = cancel).hits.map { it.text }

    fun format(r: Search): String {
        if (r.hits.isEmpty()) return JSONObject().put("ok", true).put("results", JSONArray())
            .put("note", "No memory note answers this (searched ${r.total} notes${if (r.how == "LLM tree search") " by reasoning over the topic outline" else ""}). It may be in an earlier conversation: try session_search.").toString()
        return JSONObject().put("ok", true).put("how", r.how).put("results", JSONArray(r.hits.map {
            JSONObject().put("text", it.text).put("topic", topicEn(it.topic)).put("where", if (it.t == "user") "about the user" else "memory").put("tier", it.tier)
        })).toString()
    }

    /** "[harness] You said you do not know…" nudge: memory entries that may answer [userText] (empty = no nudge). */
    fun idkNudge(userText: String, hasWeb: Boolean, lang: String?, cancel: Cancel?): Pair<String, List<String>>? {
        val hits = try { retrieve(userText, 8, cancel) } catch (e: CancelledException) { throw e } catch (_: Throwable) { emptyList() }
        if (hits.isEmpty()) return null
        val msg = "[harness] You said you do not know, but your memory already has entries that may answer this (drafts are real memories too):\n${hits.joinToString("\n") { "- " + jclip(it, 400) }}\nAnswer from them if they do. Only if they do not, search (memory action=search, session_search${if (hasWeb) ", or the web for public facts" else ""}).${if (!lang.isNullOrEmpty()) " Reply in $lang." else ""}"
        return msg to hits
    }

    // ---------------- the memory tool ----------------
    private class Op(val action: String, val content: String, val oldText: String)

    private fun err(msg: String, list: List<String>? = null) = JSONObject().put("error", msg).also { if (list != null) it.put("entries", JSONArray(list)) }

    @Synchronized private fun baseApply(target: String, ops: List<Op>): JSONObject {
        val list = entries(target).toMutableList()
        for (op in ops) {
            val c = op.content.trim()
            when (op.action) {
                "add" -> { if (c.isEmpty()) return err("add needs content"); if (list.any { it == c }) continue; list.add(c) }
                "replace", "remove" -> {
                    if (op.oldText.isEmpty()) return err("${op.action} needs old_text (a unique substring of the entry)")
                    val hits = list.indices.filter { list[it].contains(op.oldText) }
                    if (hits.isEmpty()) return err("no entry contains \"${op.oldText}\"", list)
                    if (hits.size > 1) return err("\"${op.oldText}\" matches ${hits.size} entries — use a longer substring", list)
                    if (op.action == "remove") list.removeAt(hits[0]) else { if (c.isEmpty()) return err("replace needs content"); list[hits[0]] = c }
                }
                else -> return err("unknown action ${op.action}")
            }
        }
        val size = list.joinToString(DELIM).length; val lim = limit(target)
        if (size > lim) return err("${if (target == "user") "USER.md" else "MEMORY.md"} would be $size/$lim chars. Reissue as ONE batch that removes or shortens stale entries and adds the new one.", entries(target))
        save(target, list)
        return JSONObject().put("ok", true).put("target", target).put("chars", "$size/$lim").put("entries", list.size)
            .put("note", "Saved to disk. Takes effect in the system prompt next session (prompt is frozen per session).")
    }

    /** The memory tool's add / replace / remove (one batch) with pin protection, room-making, tiers and curation. */
    fun apply(args: JSONObject, sid: String? = null): JSONObject {
        val target = normTarget(args.str("target").ifEmpty { "memory" })
        val opsArr = args.optJSONArray("operations")
        val ops = if (opsArr != null && opsArr.length() > 0) (0 until opsArr.length()).mapNotNull { opsArr.optJSONObject(it) }.map { o ->
            Op(o.str("action"), (if (o.has("content") && !o.isNull("content")) o.optString("content") else o.optString("new_text", "")), o.optString("old_text", ""))
        } else listOf(Op(args.str("action"), if (args.has("content") && !args.isNull("content")) args.optString("content") else args.optString("new_text", ""), args.optString("old_text", "")))
        var res: JSONObject; val added: List<String>
        synchronized(this) {
            val before = entries(target)
            for (op in ops) if ((op.action == "replace" || op.action == "remove") && op.oldText.isNotEmpty()) {
                val hits = before.filter { it.contains(op.oldText) }
                if (hits.size == 1 && pinned(target, hits[0])) return err("That entry is pinned by the user (\"${jclip(hits[0], 80)}\"), so it cannot be changed or removed. Add a new entry instead if something changed; the user will see the conflict.")
            }
            if (args.str("action") == "search") {
                val r = relevant(args.str("query").ifEmpty { args.str("content") }, k = 12)
                return if (r.isNotEmpty()) JSONObject().put("ok", true).put("results", JSONArray(r.map { JSONObject().put("text", it).put("tier", if (target == "memory") tier(it) else "active") }))
                else JSONObject().put("ok", true).put("results", JSONArray()).put("note", "No matching memory.")
            }
            if (target == "memory") {
                val lim = MemoryXCfg.entryChars; val long = ops.find { it.content.length > lim }
                if (long != null) return err("An entry is longer than $lim characters (${long.content.length}). Keep each memory entry to one concise fact, or split it into several entries.")
            }
            val fresh = ops.filter { op -> !(op.action == "add" && before.any { fold(it) == fold(op.content) }) }
            if (fresh.isEmpty()) return JSONObject().put("ok", true).put("target", target).put("note", "Already remembered — nothing changed.")
            res = baseApply(target, fresh); var retired: List<String> = emptyList()
            if (res.has("error") && Regex("would be \\d+/\\d+ chars").containsMatchIn(res.optString("error"))) {
                val need = fresh.sumOf { it.content.length + DELIM.length }
                val keep = fresh.filter { it.oldText.isNotEmpty() }.flatMap { op -> before.filter { it.contains(op.oldText) } }
                roomNote = null; retired = makeRoom(target, need, keep)
                if (retired.isNotEmpty()) res = baseApply(target, fresh) else roomNote?.let { return err(it, entries(target)) }
            }
            if (res.has("error")) return res
            val after = entries(target); added = after.filter { it !in before }
            val gone = before.filter { it !in after && it !in retired }.toMutableList()
            val inherit = HashMap<String, JSONObject>()
            for (op in fresh) if (op.action == "replace" && op.oldText.isNotEmpty()) {
                val old = gone.find { it.contains(op.oldText) }; val nw = op.content.trim()
                if (old != null) { mget(target, old)?.let { inherit[nw] = JSONObject(it.toString()) }; trash(target, old, "replaced", "replaced by yaver with an updated version", mapOf("replacedBy" to nw)); gone.remove(old) }
            }
            for (g in gone) trash(target, g, "deleted", "removed by yaver")
            for (a in added) {
                val mm = ensure(target, a, "yaver"); if (sid != null && !mm.has("sid")) mm.put("sid", sid)
                val inh = inherit[a]
                if (inh != null) { for (k in listOf("tier", "hits", "uses", "bornDay", "promotedDay", "lastUsed", "created", "by")) if (inh.has(k) && !inh.isNull(k)) mm.put(k, inh.get(k)) }
                else if (target == "memory" && mm.optString("by") != "user" && !mm.optBoolean("pinned") && !mm.has("tier")) { mm.put("tier", "draft").put("bornDay", dayCount()).put("hits", 0) }
            }
            saveMeta()
            if (target == "memory" && added.isNotEmpty()) lifecycle()
            var note = res.optString("note", "")
            if (retired.isNotEmpty()) note += " To make room, ${retired.size} old entr${if (retired.size == 1) "y was" else "ies were"} retired (restorable from the Memory tab)."
            note += " Outdated entries that this replaces are retired automatically." + if (target == "memory" && added.isNotEmpty()) " New notes start as drafts: they are shown to you when relevant and become active once they prove useful (trial: ${MemoryXCfg.draftDays} days of use)." else ""
            res.put("note", note)
        }
        if (added.isNotEmpty()) Y.bg { try { Thread.sleep(50); curate(target, added) } catch (_: Throwable) {} }
        changed()
        return res
    }

    // ---------------- session search (BM25 over saved conversations) ----------------
    private val STOK = Regex("[\\p{L}\\p{N}_]{2,}")
    private fun stok(s: String): List<String> = STOK.findAll(s.lowercase()).map { it.value }.toList()

    fun sessionSearch(query: String, excludeId: String? = null, limit: Int = 5): JSONArray {
        class SD(val sid: String, val title: String, val i: Int, val role: String, val text: String, val toks: List<String>) { var score = 0.0 }
        val docs = ArrayList<SD>()
        for (meta in Convos.list()) {
            val id = meta.optString("id"); if (id == excludeId) continue
            val c = Convos.load(id) ?: continue
            for (i in 0 until c.messages.length()) {
                val m = c.messages.optJSONObject(i) ?: continue; val role = m.optString("role")
                val ct = m.opt("content")
                if ((role == "user" || role == "assistant") && ct is String && ct.isNotBlank()) docs.add(SD(c.id, c.title, i, role, ct, stok(ct)))
            }
        }
        val q = stok(query).distinct(); if (q.isEmpty() || docs.isEmpty()) return JSONArray()
        val N = docs.size; val avg = docs.sumOf { it.toks.size }.toDouble() / N
        val df = q.associateWith { t -> docs.count { t in it.toks } }
        for (d in docs) {
            var s = 0.0; val tf = d.toks.groupingBy { it }.eachCount()
            for (t in q) { val f = tf[t] ?: continue; val idf = ln(1 + (N - df[t]!! + .5) / (df[t]!! + .5)); s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * d.toks.size / avg)) }
            d.score = s
        }
        val best = LinkedHashMap<String, SD>()
        for (d in docs.filter { it.score > 0 }.sortedByDescending { it.score }) if (!best.containsKey(d.sid)) best[d.sid] = d
        return JSONArray(best.values.take(limit).map {
            JSONObject().put("session_id", it.sid).put("title", it.title).put("message_index", it.i).put("role", it.role)
                .put("score", Math.round(it.score * 100) / 100.0).put("snippet", jclip(it.text, 400))
        })
    }

    /** "[Possibly relevant past sessions (session_search hits)]" block for the user message (route.recall). */
    fun recallBlock(text: String, excludeId: String?): String {
        val hits = sessionSearch(text, excludeId, 3); if (hits.length() == 0) return ""
        return "\n\n[Possibly relevant past sessions (session_search hits)]\n" + (0 until hits.length()).map { hits.getJSONObject(it) }
            .joinToString("\n") { "- ${it.optString("title")} (${it.optString("session_id")}): ${jclip(it.optString("snippet"), 300)}" }
    }

    /** Registers `memory` and `session_search` (replacing CoreTools' `memory`). Must run AFTER CoreTools.register(). */
    fun register() {
        val opItem = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "replace", "remove"))))
            .put("content", JSONObject().put("type", "string")).put("old_text", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))
        Tools.add(Tool(
            "memory",
            "Save durable facts that apply to EVERY future session (who the user is, stable preferences, environment facts). Make all changes in ONE call via 'operations' [{action:add|replace|remove, content?, old_text?}]. Your notes (target memory) hold up to ${MemoryXCfg.activeMax} active + ${MemoryXCfg.draftMax} draft entries of at most ${MemoryXCfg.entryChars} characters each: new notes start as drafts and become active when they prove useful; the most used ones are in the prompt, the others are attached to a message when relevant — use action=search to look something up. Keep each entry one concise fact. target 'user' = user profile, 'memory' = your notes. Task procedures belong in skills, not memory. Entries marked [pinned by the user] cannot be changed or removed. When a fact changed, just add the new entry: outdated or contradicting entries are retired automatically (the user can restore them). If the file is full, the oldest unpinned entries are retired to make room.",
            P.props(
                "action" to P.s("add | replace | remove (single-op shape) | search (query: find stored memories)", "add", "replace", "remove", "search"),
                "query" to P.s("For search: words to look for"),
                "target" to P.s("memory | user", "memory", "user"),
                "content" to P.s("Entry text (full entry for replace)"),
                "old_text" to P.s("Unique substring identifying the entry to replace/remove"),
                "operations" to JSONObject().put("type", "array").put("items", opItem)
            ), emptyList()
        ) { a, ctx ->
            if (a.str("action") == "search") format(search(a.str("query").ifEmpty { a.str("content") }, 10, cancel = ctx.cancel))
            else apply(a, ctx.convo.id).toString()
        })
        Tools.add(Tool(
            "session_search",
            "Search past conversations (BM25 over saved sessions) or read one: pass query to discover, or session_id to read.",
            P.props("query" to P.s("Keywords"), "session_id" to P.s("Session to read")), emptyList()
        ) { a, ctx ->
            val sid = a.str("session_id"); val query = a.str("query")
            if (sid.isNotEmpty()) {
                val s = Convos.load(sid) ?: throw IllegalArgumentException("no such session")
                jclip((0 until s.messages.length()).mapNotNull { s.messages.optJSONObject(it) }
                    .filter { it.optString("role") != "system" && it.opt("content") is String && it.optString("content").isNotEmpty() }
                    .joinToString("\n\n") { "[${it.optString("role")}] ${jclip(it.optString("content"), 1500)}" }, 20000)
            } else if (query.isEmpty()) {
                val df = java.text.DateFormat.getDateTimeInstance()
                Convos.list().take(20).joinToString("\n") { "${it.optString("id")} · ${df.format(java.util.Date(it.optLong("updated")))} · ${it.optString("title")}" }.ifEmpty { "(no past sessions)" }
            } else {
                val r = sessionSearch(query, ctx.convo.id); if (r.length() > 0) r.toString(1) else "(no matches)"
            }
        })
    }

    /** After each turn: usage counts + day of use + lifecycle, then the background indexer 8 s later if idle. */
    fun afterTurn(messages: JSONArray, turnStart: Int, isBusy: () -> Boolean = { false }) {
        try { noteUse(messages, turnStart) } catch (e: Throwable) { Diag.e("memory", "noteUse", e) }
        indexSoon(8000, isBusy)
    }

    // ---------------- memory delta (what a turn added / dropped) ----------------
    /** Snapshot: key → (label, text). Take one before the turn and pass it to [delta] after. */
    @Synchronized fun snap(): LinkedHashMap<String, Pair<String, String>> {
        val o = LinkedHashMap<String, Pair<String, String>>()
        for (t in listOf("memory", "user")) for (e in entries(t)) o["m:$t:" + memKey(t, e)] = (if (t == "user") Y.t("about you", "hakkımda") else Y.t("memory", "hafıza")) to e
        return o
    }
    /** {label:"Memory: +1 learned · −1 dropped", tip, added:[…], dropped:[…], open:"memory"} or null when nothing changed. */
    fun delta(before: Map<String, Pair<String, String>>, now: Map<String, Pair<String, String>> = snap()): JSONObject? {
        val add = now.filterKeys { it !in before }; val del = before.filterKeys { it !in now }
        if (add.isEmpty() && del.isEmpty()) return null
        val t = listOf(if (add.isNotEmpty()) "+${add.size} ${Y.t("learned", "öğrenildi")}" else "", if (del.isNotEmpty()) "−${del.size} ${Y.t("dropped", "bırakıldı")}" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val c120 = { s: String -> jclip(s, 120) }
        val tip = (add.values.map { "+ ${it.first}: ${c120(it.second)}" } + del.values.map { "− ${it.first}: ${c120(it.second)}" }).joinToString("\n")
        return JSONObject().put("label", "${Y.t("Memory", "Hafıza")}: $t").put("tip", tip)
            .put("added", JSONArray(add.values.map { JSONObject().put("what", it.first).put("text", it.second) }))
            .put("dropped", JSONArray(del.values.map { JSONObject().put("what", it.first).put("text", it.second) }))
            .put("open", if (add.keys.any { it.startsWith("h:") }) "lessons" else "memory")
    }

    // ---------------- memory screen (JSON for the UI) ----------------
    private fun ok(msg: String) = JSONObject().put("ok", true).put("message", msg)
    private fun bad(msg: String) = JSONObject().put("ok", false).put("error", msg)

    /** Stats + every entry of [target] ("memory" or "user") with tier, pin, uses, trial, topic… (UI sorts/filters). */
    @Synchronized fun uiList(target: String): JSONObject {
        val t = normTarget(target); val tiered = t == "memory"; val list = entries(t)
        val inPrompt = if (tiered) promptPick().toSet() else list.toSet(); val dc = dayCount()
        val rows = JSONArray(); var nAct = 0
        val titles by lazy { Convos.list().associate { it.optString("id") to it.optString("title") } }
        for (e in list) {
            val raw = mget(t, e); val m = norm(JSONObject((raw ?: JSONObject()).toString()))
            val tr = if (tiered) tier(e) else "active"; if (tr == "active") nAct++
            val r = JSONObject().put("text", e).put("target", t).put("tier", tr).put("pinned", raw?.optBoolean("pinned") == true)
                .put("uses", hits(m).toInt()).put("rate", Math.round(useRate(m) * 100) / 100.0)
                .put("created", num(m, "created")?.toLong() ?: 0L).put("lastUsed", num(m, "lastUsed")?.toLong() ?: 0L)
                .put("lastUsedText", truthy(m, "lastUsed")?.let { relTime(it.toLong()) } ?: Y.t("not yet", "henüz yok"))
                .put("by", if (m.optString("by") == "user") "user" else "yaver")
                .put("topic", topic(t, e)).put("topicLabel", label(topic(t, e))).put("keys", JSONArray(keys(t, e)))
                .put("rule", RULE_RX.containsMatchIn(e)).put("inPrompt", tiered && e in inPrompt)
            if (tr == "draft") r.put("daysLeft", daysLeft(m)) else r.put("activeDays", max(1.0, dc - (num(m, "promotedDay") ?: bornDay(m)) + 1).toInt())
            m.optJSONObject("conflict")?.let { r.put("conflict", it) }
            m.optString("sid").takeIf { it.isNotEmpty() }?.let { sid -> r.put("sid", sid); titles[sid]?.let { r.put("sourceTitle", it) } }
            rows.put(r)
        }
        val stats = JSONObject().put("days", dc)
        if (tiered) stats.put("active", nAct).put("activeMax", MemoryXCfg.activeMax).put("drafts", list.size - nAct).put("draftMax", MemoryXCfg.draftMax)
            .put("inPrompt", inPrompt.size).put("promptMax", MemoryXCfg.promptNotes).put("entryChars", MemoryXCfg.entryChars).put("draftDays", MemoryXCfg.draftDays).put("pendingIndex", pending())
        else stats.put("chars", list.joinToString(DELIM).length).put("charsMax", MemoryXCfg.userChars).put("entries", list.size)
        return JSONObject().put("target", t).put("stats", stats).put("entries", rows)
    }

    /** Hand-written entry (active right away). */
    fun uiAdd(target: String, text: String): JSONObject {
        val t = normTarget(target); val v = text.trim(); if (v.isEmpty()) return bad(Y.t("Empty", "Boş"))
        synchronized(this) {
            val cur = entries(t)
            if (cur.any { fold(it) == fold(v) }) return bad(Y.t("Already remembered", "Bu zaten hafızada"))
            val ec = MemoryXCfg.entryChars
            if (t == "memory" && v.length > ec) return bad(Y.t("Too long: ${v.length}/$ec characters — split it into several notes", "Çok uzun: ${v.length}/$ec karakter — birkaç nota bölün"))
            if (t == "user" && (cur + v).joinToString(DELIM).length > MemoryXCfg.userChars) return bad(Y.t("The profile is full — shorten or delete an entry first", "Profil dolu — önce bir kaydı kısaltın veya silin"))
            save(t, cur + v); ensure(t, v, "user"); saveMeta()
        }
        Y.bg { try { curate(t, listOf(v), "user") } catch (_: Throwable) {} }
        changed()
        return ok(Y.t("Saved — hand-written notes are active right away", "Kaydedildi — elle yazılan notlar hemen aktif olur"))
    }

    /** Edit an entry; an empty [newText] deletes it (to trash). */
    @Synchronized fun uiEdit(target: String, oldText: String, newText: String): JSONObject {
        val t = normTarget(target); val v = newText.trim(); val cur = entries(t).toMutableList(); val i = cur.indexOf(oldText)
        if (i < 0) return bad(Y.t("Entry not found", "Kayıt bulunamadı"))
        if (v.isEmpty()) { cur.removeAt(i); save(t, cur); trash(t, oldText, "deleted", Y.t("deleted by you", "sizin tarafınızdan silindi")); changed(); return ok(Y.t("Moved to trash", "Çöp kutusuna taşındı")) }
        cur[i] = v
        if (t == "user" && cur.joinToString(DELIM).length > MemoryXCfg.userChars) return bad(Y.t("Too long for the profile", "Profil için çok uzun"))
        val ec = MemoryXCfg.entryChars
        if (t == "memory" && v.length > ec) return bad(Y.t("Too long: ${v.length}/$ec characters", "Çok uzun: ${v.length}/$ec karakter"))
        save(t, cur); move(t, oldText, v); changed(); return ok(Y.t("Saved", "Kaydedildi"))
    }

    @Synchronized fun uiPin(target: String, text: String, on: Boolean): JSONObject { setPinned(normTarget(target), text, on); changed(); return ok("") }

    /** Draft → active now, or active → back to drafts (new trial). Not for pinned entries. */
    @Synchronized fun uiToggleTier(text: String): JSONObject {
        if (pinned("memory", text)) return bad(Y.t("Pinned", "Sabit"))
        val draft = tier(text) == "draft"; val mm = ensure("memory", text)
        if (draft) { mm.put("tier", "active").put("promotedDay", dayCount()).put("by", "user") }
        else { mm.put("tier", "draft").put("bornDay", dayCount()).put("hits", 0); if (mm.optString("by") == "user") mm.put("by", "yaver") }
        saveMeta(); changed(); return ok("")
    }

    @Synchronized fun uiDelete(target: String, text: String): JSONObject {
        val t = normTarget(target)
        if (pinned(t, text)) return bad(Y.t("Unpin before deleting", "Silmeden önce sabitlemeyi kaldırın"))
        save(t, entries(t).filter { it != text }); trash(t, text, "deleted", Y.t("deleted by you", "sizin tarafınızdan silindi")); changed()
        return ok(Y.t("Moved to trash", "Çöp kutusuna taşındı"))
    }

    /** Trash items: {id, target, text, kind, kindLabel, reason, replacedBy?, at, when}. */
    @Synchronized fun uiTrash(): JSONArray {
        val kinds = mapOf("replaced" to Y.t("Replaced", "Yenisiyle değişti"), "invalidated" to Y.t("No longer true", "Geçersiz"), "duplicate" to Y.t("Duplicate", "Tekrar"),
            "expired" to Y.t("Retired", "Emekli"), "deleted" to Y.t("Deleted", "Silindi"))
        val src = meta().getJSONArray("trash"); val out = JSONArray()
        for (i in 0 until src.length()) { val x = JSONObject(src.getJSONObject(i).toString()); x.put("kindLabel", kinds[x.optString("kind")] ?: x.optString("kind")).put("when", relTime(x.optLong("at"))); out.put(x) }
        return out
    }

    /** Restore brings a note back as active. */
    @Synchronized fun uiRestore(id: String): JSONObject {
        val tr = meta().getJSONArray("trash"); val x = (0 until tr.length()).mapNotNull { tr.optJSONObject(it) }.find { it.optString("id") == id } ?: return bad(Y.t("Not found", "Bulunamadı"))
        val t = normTarget(x.optString("target")); val text = x.optString("text"); val cur = entries(t)
        if (cur.none { fold(it) == fold(text) }) { save(t, cur + text); ensure(t, text, "user") }
        forget(id); saveMeta(); changed()
        return ok(Y.t("Restored (as active)", "Geri yüklendi (aktif olarak)"))
    }

    @Synchronized fun uiForget(id: String): JSONObject { forget(id); changed(); return ok("") }
    @Synchronized fun uiEmptyTrash(): JSONObject { meta().put("trash", JSONArray()); saveMeta(); changed(); return ok("") }

    /** "Rewrite keys": drops all search keys and re-indexes (one model call per 30 notes). Blocking. */
    fun uiReindex(cancel: Cancel? = null): JSONObject {
        synchronized(this) { val its = items(); for (k in its.keys()) its.optJSONObject(k)?.remove("kv"); saveMeta() }
        retryAt = 0
        return try {
            var n = 0; while (true) { cancel?.check(); val r = index(30, cancel); if (r <= 0) break; n += r }
            ok(Y.t("Search keys rewritten for $n notes", "$n notun arama anahtarları yeniden yazıldı"))
        } catch (e: Throwable) { bad(e.message ?: "error") }
    }
}
