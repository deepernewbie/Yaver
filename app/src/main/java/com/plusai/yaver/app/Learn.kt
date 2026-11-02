package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Events for the UI. Types: "refine" (yaver learned something card — see Harness.refineCard), "lesson_suspended" (toast),
 *  "sleep" (toast after the sleep pass), "note" (small status note), "followups" (state loading|ready|none, items). */
object LearnEvents {
    @Volatile var listener: ((type: String, data: JSONObject) -> Unit)? = null
    fun emit(type: String, data: JSONObject) { try { listener?.invoke(type, data) } catch (e: Throwable) { Diag.e("learn", "event $type", e) } }
}

/** Hooks other parts of the app may fill in. All optional. */
object LearnHooks {
    /** System-1 "refine" gate: given the trajectory and the two noul questions, return {"useful": p, "global": p, "confident"?: bool};
     *  null (or no hook) → the LLM review path, as when System-1 is off. */
    @Volatile var refineGate: ((state: String, questions: JSONObject) -> JSONObject?)? = null
    @Volatile var gateMark: ((gate: String, confident: Boolean, decision: String) -> Unit)? = null
    /** True while the agent is running a turn (sleep pass waits; follow-ups are dropped). */
    @Volatile var busy: (() -> Boolean)? = null
    /** Id of the conversation on screen; follow-ups for another one are not shown. */
    @Volatile var currentConvoId: (() -> String?)? = null
    /** Reply language name ("Turkish", "English"…) for a conversation and the user's text. */
    @Volatile var langName: ((c: Convo, userText: String) -> String)? = null
    /** Pinned memory entries count as standing rules. */
    @Volatile var isPinned: ((target: String, text: String) -> Boolean)? = null
    /** Memory lifecycle during the sleep pass; returns how many notes were retired. */
    @Volatile var memoryLifecycle: (() -> Int)? = null
    /** Memory RAG indexing during the sleep pass; returns how many notes were indexed. */
    @Volatile var memIndex: (() -> Int)? = null
    /** Where a skill entry's SKILL.md lives (default files/harness/skills/<id>/SKILL.md). */
    @Volatile var skillFile: ((id: String) -> File)? = null
    @Volatile var onSkillsChanged: (() -> Unit)? = null
}

/** Settings of the learning harness (Prefs). The review interval lives in the harness state (Harness.every()/setAuto). */
object LearnSettings {
    /** "Learn from experience" (CFG.harnessLearn in the original). */
    var learn: Boolean get() = Prefs.on("learn.on", true); set(v) = Prefs.set("learn.on", v)
}

/** Turkish-aware word folding used by memory and lesson matching. */
object LearnText {
    private val MARKS = Regex("\\p{M}+")
    private val WS = Regex("\\s+")
    private val TOK = Regex("[\\p{L}\\p{N}_]{3,}")
    private val TR = Locale("tr")

    fun fold(t: String?): String = Normalizer.normalize((t ?: "").lowercase(TR), Normalizer.Form.NFKD).replace(MARKS, "").replace('ı', 'i').replace(WS, " ").trim()
    fun toks(t: String?): Set<String> = TOK.findAll(fold(t)).map { it.value.take(6) }.toSet()
    /** djb2 over target|fold(text), as base-36 unsigned. */
    fun key(target: String, text: String): String {
        var h = 5381; val s = target + "|" + fold(text)
        for (ch in s) h = (h shl 5) + h + ch.code
        return (h.toLong() and 0xffffffffL).toString(36)
    }
    /** Overlap / smaller set. */
    fun sim(a: String?, b: String?): Double {
        val A = toks(a); val B = toks(b); if (A.isEmpty() || B.isEmpty()) return 0.0
        return A.count { it in B }.toDouble() / minOf(A.size, B.size)
    }
}

/**
 * Learning from outcomes. ACE: lessons carry ✓helpful / ✗harmful counters; one that hurts more than it helps is suspended.
 * Explicit ratings, praise and corrections are captured deterministically; complaints go verbatim into a failures log.
 * A sleep pass consolidates while yaver is idle.
 */
object Learn {
    val CAP = mapOf("prompt" to 14, "memory" to 12, "skill" to 20, "subagent" to 10)
    private val dir: File get() = File(Y.app.filesDir, "harness").apply { mkdirs() }
    private val failFile: File get() = File(dir, "failures.json")
    private val sleepFile: File get() = File(dir, "sleep.json")
    private val ruleFile: File get() = File(dir, "rulemeta.json")
    private val fileLock = Any()
    private fun s(o: JSONObject?, k: String) = Harness.s(o, k)

    fun score(e: JSONObject?): Double = (e?.optInt("helpful", 0) ?: 0) - 1.5 * (e?.optInt("harmful", 0) ?: 0)
    fun text(e: JSONObject): String = "${s(e, "title")}\n${s(e, "content")}"

    /** Cosine-like word overlap. */
    fun sim(a: String, b: String): Double {
        val A = LearnText.toks(a); val B = LearnText.toks(b); if (A.isEmpty() || B.isEmpty()) return 0.0
        return A.count { it in B } / Math.sqrt((A.size * B.size).toDouble())
    }

    fun similar(st: JSONObject, e: JSONObject, scope: String, sid: String?): JSONObject? {
        var best: JSONObject? = null; var bs = 0.0
        for (x in Harness.objs(Harness.entries(st, s(e, "kind")))) {
            if (s(x, "scope") != scope || (scope == "local" && Harness.sessOf(x) != sid)) continue
            val v = maxOf(sim(text(e), text(x)), if (LearnText.sim(s(e, "title"), s(x, "title")) >= 0.85) 1.0 else 0.0)
            if (v > bs) { bs = v; best = x }
        }
        return if (bs >= 0.6) best else null
    }

    // ---------------- standing rules (the user's own words: counted, never suspended) ----------------

    private val RULE_RX = Harness.rx("^\\s*(kural(lar)?|talimat|rule(s)?|instruction(s)?)\\s*[:\\-–]|\\b(tüm|bütün|her) (konuşma|oturum|sohbet|session)|\\bher zaman\\b|\\bdaima\\b|\\basla\\b|\\bhiçbir zaman\\b|\\balways\\b|\\bnever\\b|in (every|all) (conversation|session|chat)s?")

    fun isRule(target: String, e: String): Boolean = RULE_RX.matcher(e).find() || (try { LearnHooks.isPinned?.invoke(target, e) } catch (_: Throwable) { null } == true)

    /** (target, text) of memory entries that are standing rules. Targets as in Memory: "user", "notes". */
    fun ruleRefs(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (t in listOf("user", "notes")) { val es = try { Memory.entries(t) } catch (_: Throwable) { emptyList<String>() }; for (e in es) if (isRule(t, e)) out.add(t to e) }
        return out
    }

    private fun ruleMetaAll(): JSONObject = synchronized(fileLock) { try { if (ruleFile.exists()) JSONObject(ruleFile.readText()) else JSONObject() } catch (_: Exception) { JSONObject() } }
    fun ruleMeta(target: String, text: String): JSONObject = ruleMetaAll().optJSONObject(target + ":" + LearnText.key(target, text)) ?: JSONObject()

    // ---------------- credit assignment ----------------

    val GENERIC: Set<String> = "kullan kullanıc kullani user users answer yanit yanıt cevap when whenev always never always every istedi istend istenir sorduğ sordug soru questi respon reply should must önce first sonra then before after kural rule rules tüm bütün hepsi with için icin olarak olan gibi daha more less like that this have make yap yapma etme yaz write give ver"
        .split(" ").map { LearnText.fold(it).take(6) }.toSet()

    /** Which lessons and rules plausibly shaped this answer: their distinctive words overlap the question, the answer and the tools used. */
    fun attribute(convoId: String?, turnText: String): List<String> {
        val st = Harness.load(); val cands = ArrayList<Pair<String, String>>()
        for (e in Harness.visible(st, convoId)) if (s(e, "status") != "suspended") cands.add(text(e) to "h:${s(e, "kind")}:${s(e, "id")}")
        for ((t, e) in ruleRefs()) cands.add(e to "r:$t:${LearnText.key(t, e)}")
        val toks = cands.map { LearnText.toks(it.first) }
        val df = HashMap<String, Int>(); for (T in toks) for (x in T) df[x] = (df[x] ?: 0) + 1
        val maxDf = if (cands.size >= 2) maxOf(1, Math.floor(cands.size * 0.4).toInt()) else 1
        val turn = LearnText.toks(turnText); val refs = ArrayList<Pair<Double, String>>()
        toks.forEachIndexed { i, T ->
            val dist = T.filter { (df[it] ?: 0) <= maxDf && it !in GENERIC }
            if (dist.isEmpty()) return@forEachIndexed
            val n = dist.count { it in turn }; val v = n.toDouble() / minOf(dist.size, 8)
            if (n >= 2 && v >= 0.25) refs.add(v to cands[i].second)
        }
        return refs.sortedByDescending { it.first }.take(8).map { it.second }
    }

    /** Tags the turn's final answer with the lessons behind it ("lr"). Returns its message index or -1. */
    fun tag(c: Convo, turnStart: Int, userText: String): Int {
        val ms = c.messages; var fin = -1
        for (i in ms.length() - 1 downTo maxOf(0, turnStart)) {
            val m = ms.getJSONObject(i)
            if (m.optString("role") == "assistant" && m.optString("content").isNotEmpty() && (m.optJSONArray("tool_calls")?.length() ?: 0) == 0 && !m.optBoolean("local")) { fin = i; break }
        }
        if (fin < 0) return -1
        val tools = StringBuilder()
        for (i in maxOf(0, turnStart) until ms.length()) { val tc = ms.getJSONObject(i).optJSONArray("tool_calls") ?: continue; for (k in 0 until tc.length()) { val n = tc.getJSONObject(k).optJSONObject("function")?.optString("name") ?: continue; tools.append(n.replace('_', ' ')).append(' ').append(n).append(' ') } }
        val m = ms.getJSONObject(fin)
        m.put("lr", JSONArray(attribute(c.id, "$userText\n${m.optString("content")}\n$tools")))
        return fin
    }

    /** Adds (d=+1) or takes back (d=-1) one helpful/harmful vote. Emits "lesson_suspended" when a lesson is switched off. */
    fun credit(refs: JSONArray?, field: String, d: Int = 1): List<String> {
        if (refs == null || refs.length() == 0) return emptyList()
        val suspended = ArrayList<String>()
        synchronized(Harness.lock) {
            val st = Harness.load(); var dirty = false; var metaDirty = false
            val rkeys = HashSet<String>(); for ((t, e) in ruleRefs()) rkeys.add(t + ":" + LearnText.key(t, e))
            val meta = ruleMetaAll()
            for (i in 0 until refs.length()) {
                val parts = refs.optString(i).split(":"); if (parts.size < 3) continue
                val typ = parts[0]; val a = parts[1]; val b = parts.drop(2).joinToString(":")
                if (typ == "h") {
                    val e = st.getJSONObject("entries").optJSONObject(a)?.optJSONObject(b) ?: continue
                    e.put(field, maxOf(0, e.optInt(field, 0) + d))
                    if (d > 0) e.put(if (field == "helpful") "lastHelpful" else "lastHarmful", System.currentTimeMillis())
                    dirty = true
                    if (field == "harmful" && d > 0 && s(e, "status") != "suspended" && e.optInt("harmful") >= 2 && e.optInt("harmful") > e.optInt("helpful", 0)) {
                        e.put("status", "suspended"); e.put("suspendedAt", System.currentTimeMillis()); suspended.add(s(e, "title"))
                    }
                } else if (typ == "r") {
                    val k = "$a:$b"; if (k !in rkeys) continue
                    val m = meta.optJSONObject(k) ?: JSONObject().also { meta.put(k, it) }
                    m.put(field, maxOf(0, m.optInt(field, 0) + d)); metaDirty = true
                }
            }
            if (suspended.isNotEmpty()) Harness.save(st) else if (dirty) Harness.rawSave(st)
            if (metaDirty) synchronized(fileLock) { try { ruleFile.writeText(meta.toString()) } catch (_: Throwable) {} }
        }
        if (suspended.isNotEmpty()) {
            val j = suspended.joinToString("; ")
            LearnEvents.emit("lesson_suspended", JSONObject().put("titles", JSONArray(suspended)).put("open", "lessons")
                .put("text", "✨ " + Y.t("Lesson switched off — it kept leading to wrong answers: $j", "Ders askıya alındı — yanlış yanıtlara yol açıyordu: $j")))
        }
        return suspended
    }

    // ---------------- failures log ----------------

    private val NOT_REAL = Regex("^(\\(Reply in [^)]*\\) )?\\[(harness|verify-on-stop|Context summary)")
    private val Q_SPLIT = Regex("\n\n\\[(Language|Relevant memories|Feedback on your previous|Past mistakes on similar|Skill |Attached files|Referenced files|Possibly relevant|Relevant passages)")

    private fun userTextOf(m: JSONObject, split: Regex = Q_SPLIT): String = m.optString("display").ifEmpty { m.optString("content").split(split)[0] }

    /** The question an answer replied to. */
    fun qFor(c: Convo, idx: Int): String {
        for (j in idx - 1 downTo 0) { val u = c.messages.getJSONObject(j); if (u.optString("role") == "user" && !NOT_REAL.containsMatchIn(u.optString("content"))) return userTextOf(u) }
        return ""
    }

    fun failures(): List<JSONObject> = synchronized(fileLock) { try { if (failFile.exists()) Harness.arr(JSONArray(failFile.readText())) else emptyList() } catch (_: Exception) { emptyList() } }
    private fun saveFailures(a: List<JSONObject>) = synchronized(fileLock) { try { failFile.writeText(JSONArray(a).toString()) } catch (_: Throwable) {} }

    fun logFailure(kind: String, sid: String, complaint: String, q: String, a: String) {
        val l = failures().toMutableList()
        l.add(0, JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("sid", sid).put("complaint", clip(complaint, 400)).put("q", clip(q, 300)).put("a", clip(a, 400)))
        while (l.size > 100) l.removeAt(l.size - 1)
        saveFailures(l)
    }

    // ---------------- explicit feedback (👍/👎) ----------------

    fun feedbackPlaceholder() = Y.t("What did I get wrong? (optional)", "Neyi yanlış yaptım? (isteğe bağlı)")

    /** A tap on 👍 (v=1) or 👎 (v=-1): tapping the active one again clears it. Returns {fb, askNote} — when askNote, show
     *  an optional text box ([feedbackPlaceholder], Send / Skip) and send its text with feedback(c, idx, -1, text). */
    fun toggle(c: Convo, idx: Int, v: Int): JSONObject {
        val m = c.messages.optJSONObject(idx) ?: return JSONObject().put("fb", 0).put("askNote", false)
        val nv = if (m.optInt("fb", 0) == v) 0 else v
        feedback(c, idx, nv, null)
        return JSONObject().put("fb", nv).put("askNote", nv < 0)
    }

    /**
     * Explicit feedback on the answer at [idx]: +1, -1 or 0 (cleared), with an optional comment. Updates the lesson
     * counters; a 👎 is logged verbatim and starts a learning review in the background. Returns a user-facing note ("" for none).
     */
    fun feedback(c: Convo, idx: Int, vote: Int, comment: String?): String {
        val m = c.messages.optJSONObject(idx) ?: return ""
        val note = (comment ?: "").trim()
        val prev = m.optInt("fb", 0); val v = vote.coerceIn(-1, 1)
        if (prev == v && note.isEmpty()) return ""
        val lr = m.optJSONArray("lr")
        if (prev != 0 && prev != v) credit(lr, if (prev > 0) "helpful" else "harmful", -1)
        if (s(m, "judged").isNotEmpty() && prev == 0) { credit(lr, if (s(m, "judged") == "+") "helpful" else "harmful", -1); m.remove("judged") } // the button outranks an implicit judgement
        if (v != 0 && prev != v) credit(lr, if (v > 0) "helpful" else "harmful", 1)
        if (v != 0) m.put("fb", v) else m.remove("fb")
        if (note.isNotEmpty()) m.put("fbNote", clip(note, 400))
        try { Convos.save(c, touch = false) } catch (_: Throwable) {}
        if (v < 0) {
            val q = qFor(c, idx)
            if (prev != v) logFailure("thumbs_down", c.id, note, q, m.optString("content"))
            else if (note.isNotEmpty()) {
                val a = failures().toMutableList()
                if (a.isNotEmpty() && s(a[0], "kind") == "thumbs_down" && s(a[0], "sid") == c.id && s(a[0], "complaint").isEmpty()) { a[0].put("complaint", clip(note, 400)); saveFailures(a) }
            }
            if (note.isNotEmpty() || prev != v) {
                Harness.checkpoint(c, "user_feedback_negative: the user marked the answer to \"${clip(q, 200)}\" as wrong${if (note.isNotEmpty()) " and said: \"${clip(note, 300)}\"" else ""}. The answer was: \"${clip(m.optString("content"), 300)}\"")
            }
            if (note.isNotEmpty()) return Y.t("Thanks — yaver will learn from this", "Teşekkürler — yaver bundan ders çıkaracak")
        }
        return ""
    }

    // ---------------- implicit feedback ----------------

    private val PRAISE = Harness.rx("^\\s*(teşekkür(ler| ederim)?|tşk|sağ ?ol(un)?|eline sağlık|süper|harika|mükemmel|çok iyi|güzel olmuş|tam (da )?istediğim|oldu|tamamdır|çalıştı|işe yaradı|thanks|thank you|thx|great|perfect|awesome|excellent|nice|that works|it works|it worked|works now)\\b")
    private val RATING = Regex("^\\s*(10|[1-9])\\s*(/\\s*10)?\\s*[.!]?\\s*$")
    private val O_SPLIT = Regex("\n\n\\[(Language|Relevant memories|Feedback on your previous|Past mistakes on similar|Skill |Attached files|Referenced files|Possibly relevant|Relevant passages|The continual harness)")
    private val ANY_BRACKET = Regex("^(\\(Reply in [^)]*\\) )?\\[")

    /** The user's latest message judges the previous answer (rating, praise, correction). Returns "+", "-" or null. */
    fun outcome(c: Convo, why: String?): String? {
        val ms = c.messages; var i = ms.length() - 1
        while (i >= 0 && !Harness.isRealUser(ms.getJSONObject(i))) i--
        if (i < 1) return null
        val u = userTextOf(ms.getJSONObject(i), O_SPLIT).trim()
        var j = i - 1
        while (j >= 0) {
            val m = ms.getJSONObject(j)
            if (m.optString("role") == "assistant" && m.optString("content").isNotEmpty() && (m.optJSONArray("tool_calls")?.length() ?: 0) == 0 && !m.optBoolean("local")) break
            if (m.optString("role") == "user" && !ANY_BRACKET.containsMatchIn(m.optString("content"))) return null
            j--
        }
        if (j < 0) return null
        val prev = ms.getJSONObject(j)
        if (prev.optInt("fb", 0) != 0 || s(prev, "judged").isNotEmpty()) return null
        var verdict: String? = null
        val rm = RATING.find(u)
        if (rm != null) { val n = rm.groupValues[1].toInt(); verdict = if (n >= 7) "+" else if (n <= 4) "-" else null }
        else if ((why ?: "").startsWith("user_correction")) verdict = "-"
        else if (u.length <= 80 && PRAISE.matcher(u).find()) verdict = "+"
        if (verdict == null) return null
        prev.put("judged", verdict)
        credit(prev.optJSONArray("lr"), if (verdict == "+") "helpful" else "harmful", 1)
        if (verdict == "-") logFailure(if (rm != null) "rating" else "correction", c.id, u, qFor(c, j), prev.optString("content"))
        return verdict
    }

    /**
     * Pre-write contract: text to append to the new user message (before it is added to c.messages) — what the previous
     * answer got wrong (👎) and past mistakes on similar requests. "" when nothing applies.
     */
    fun preWrite(c: Convo, text: String): String {
        val sb = StringBuilder(); val ms = c.messages
        var prev: JSONObject? = null
        for (i in ms.length() - 1 downTo 0) { val m = ms.getJSONObject(i); if (m.optString("role") == "assistant" && m.optString("content").isNotEmpty() && (m.optJSONArray("tool_calls")?.length() ?: 0) == 0 && !m.optBoolean("local")) { prev = m; break } }
        if (prev != null && prev.optInt("fb", 0) == -1 && !prev.optBoolean("fbTold")) {
            prev.put("fbTold", true)
            sb.append("\n\n[Feedback on your previous answer: the user marked it as wrong with 👎${if (s(prev, "fbNote").isNotEmpty()) " and wrote: \"${clip(s(prev, "fbNote"), 300)}\"" else ""}. Take that into account; do not repeat the mistake.]")
        }
        val q = LearnText.toks(text)
        if (q.size >= 2) {
            val now = System.currentTimeMillis(); val seen = HashSet<String>()
            val hits = failures().filter { s(it, "q").isNotEmpty() && !(s(it, "sid") == c.id && now - it.optLong("at") < 30 * 60_000L) }
                .map { f -> val F = LearnText.toks(s(f, "q")); val n = F.count { it in q && it !in GENERIC }; Triple(f, n, n.toDouble() / maxOf(1, minOf(F.size, q.size))) }
                .filter { it.second >= 2 && it.third >= 0.5 }.sortedByDescending { it.third }
                .filter { val k = LearnText.fold(s(it.first, "complaint").ifEmpty { s(it.first, "q") }); seen.add(k) }.take(2)
            if (hits.isNotEmpty()) sb.append("\n\n[Past mistakes on similar requests — avoid repeating them]\n").append(hits.joinToString("\n") { (f) ->
                "- asked \"${clip(s(f, "q"), 140)}\"" + if (s(f, "complaint").isNotEmpty()) "; the user objected: \"${clip(s(f, "complaint"), 200)}\"" else "; the user marked the answer as wrong"
            })
        }
        return sb.toString()
    }

    // ---------------- visible memory delta ----------------

    /** Snapshot of memory + lessons; take one before the turn and pass it to [delta] after. */
    fun snap(): Map<String, Pair<String, String>> {
        val o = LinkedHashMap<String, Pair<String, String>>()
        for (t in listOf("notes", "user")) try { for (e in Memory.entries(t)) o["m:$t:" + LearnText.key(t, e)] = (if (t == "user") Y.t("about you", "hakkımda") else Y.t("memory", "hafıza")) to e } catch (_: Throwable) {}
        try { for (e in Harness.all(Harness.load())) o["h:${s(e, "kind")}:${s(e, "id")}"] = Y.t("lesson", "ders") to s(e, "title") } catch (_: Throwable) {}
        return o
    }

    /** The "Memory: +1 learned · −1 dropped" chip after a turn: {text, tip, open: "lessons"|"memory"}; null when nothing changed. */
    fun delta(before: Map<String, Pair<String, String>>?): JSONObject? {
        if (before == null) return null
        val now = snap()
        val add = now.filterKeys { it !in before }; val del = before.filterKeys { it !in now }
        if (add.isEmpty() && del.isEmpty()) return null
        val t = listOf(if (add.isNotEmpty()) "+${add.size} ${Y.t("learned", "öğrenildi")}" else "", if (del.isNotEmpty()) "−${del.size} ${Y.t("dropped", "bırakıldı")}" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val tip = (add.values.map { "+ ${it.first}: ${clip(it.second, 120)}" } + del.values.map { "− ${it.first}: ${clip(it.second, 120)}" }).joinToString("\n")
        return JSONObject().put("text", "${Y.t("Memory", "Hafıza")}: $t").put("tip", tip).put("open", if (add.keys.any { it.startsWith("h:") }) "lessons" else "memory")
    }

    // ---------------- sleep pass: idle-time consolidation, at most once every 20 h ----------------

    private val sleeping = AtomicBoolean(false)
    @Volatile private var lastActive = System.currentTimeMillis()
    @Volatile private var started = false

    fun sleepLog(): List<JSONObject> = synchronized(fileLock) { try { if (sleepFile.exists()) Harness.arr(JSONArray(sleepFile.readText())) else emptyList() } catch (_: Exception) { emptyList() } }

    /** Merges duplicate lessons, purges failed ones, turns recurring failures into a global note. Blocking (LLM). null when skipped. */
    fun sleep(force: Boolean = false): JSONObject? {
        val last = Prefs.get("learn.sleepLast", "0").toLongOrNull() ?: 0L
        if (!force && System.currentTimeMillis() - last < 20 * 3600_000L) return null
        if (!sleeping.compareAndSet(false, true)) return null
        val log = JSONObject().put("at", System.currentTimeMillis()).put("merged", 0).put("purged", 0).put("retired", 0).put("refined", "")
        try {
            val st = Harness.load(); val dels = LinkedHashMap<String, JSONObject>(); val bonus = ArrayList<Pair<JSONObject, JSONObject>>()
            val groups = LinkedHashMap<String, MutableList<JSONObject>>()
            for (e in Harness.all(st)) groups.getOrPut(s(e, "kind") + "|" + s(e, "scope") + "|" + (if (s(e, "scope") == "local") Harness.sessOf(e) ?: "" else "")) { ArrayList() }.add(e)
            var merged = 0; var purged = 0
            for (g in groups.values) {
                val gone = HashSet<String>()
                for (i in g.indices) for (j in i + 1 until g.size) {
                    val a = g[i]; val b = g[j]
                    if (s(a, "id") in gone || s(b, "id") in gone) continue
                    if (maxOf(sim(text(a), text(b)), if (LearnText.sim(s(a, "title"), s(b, "title")) >= 0.85) 1.0 else 0.0) < 0.6) continue
                    val keepA = score(a) > score(b) || (score(a) == score(b) && s(a, "updated_at") >= s(b, "updated_at"))
                    val keep = if (keepA) a else b; val lose = if (keepA) b else a
                    gone.add(s(lose, "id")); bonus.add(keep to lose); dels[s(lose, "kind") + ":" + s(lose, "id")] = lose; merged++
                }
            }
            for (e in Harness.all(st)) if (s(e, "status") == "suspended" && e.optInt("harmful", 0) >= e.optInt("helpful", 0) + 3 && !dels.containsKey(s(e, "kind") + ":" + s(e, "id"))) { dels[s(e, "kind") + ":" + s(e, "id")] = e; purged++ }
            val byScope = LinkedHashMap<String, MutableList<JSONObject>>()
            for (e in dels.values) byScope.getOrPut(s(e, "scope") + "|" + (Harness.sessOf(e) ?: "")) { ArrayList() }.add(e)
            for (es in byScope.values) {
                val edits = JSONArray(); for (e in es) edits.put(JSONObject().put("action", "delete").put("kind", s(e, "kind")).put("id", s(e, "id")).put("reason", if (s(e, "status") == "suspended") "suspended and harmful" else "duplicate"))
                Harness.apply(JSONObject().put("summary", Y.t("Sleep pass: merged duplicate lessons and removed failed ones", "Uyku aşaması: tekrar eden dersler birleştirildi, başarısızlar kaldırıldı"))
                    .put("rationale", "Deterministic consolidation while idle (duplicates by word overlap; suspended lessons that kept hurting).").put("edits", edits),
                    s(es[0], "scope"), Harness.sessOf(es[0]), "tidy")
            }
            if (bonus.isNotEmpty()) synchronized(Harness.lock) {
                val s2 = Harness.load()
                for ((keep, lose) in bonus) { val e = Harness.entries(s2, s(keep, "kind")).optJSONObject(s(keep, "id")) ?: continue; e.put("helpful", e.optInt("helpful", 0) + lose.optInt("helpful", 0)); e.put("harmful", e.optInt("harmful", 0) + lose.optInt("harmful", 0)) }
                Harness.rawSave(s2)
            }
            log.put("merged", merged).put("purged", purged)
            try { log.put("retired", LearnHooks.memoryLifecycle?.invoke() ?: 0) } catch (_: Throwable) {}
            try { LearnHooks.memIndex?.let { log.put("indexed", it()) } } catch (_: Throwable) {}
            val fresh = failures().filter { it.optLong("at") > last }; log.put("failures", fresh.size)
            if (fresh.size >= 2 && Cfg.model.isNotEmpty() && LearnSettings.learn) {
                try {
                    val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    val fs = fresh.take(20).joinToString("\n") { f -> "- [${s(f, "kind")}, ${day.format(java.util.Date(f.optLong("at")))}] user asked: \"${s(f, "q")}\" · yaver answered: \"${clip(s(f, "a"), 160)}\" · user: \"${s(f, "complaint").ifEmpty { "(👎 without a note)" }}\"" }
                    val rec = Harness.refine(JSONArray(), null, "Sleep-time consolidation (no conversation is attached). Look for failures that recur in <recent_failures> and turn each recurring pattern into ONE short, concrete global prompt note (what to do instead). Update an existing entry when one already covers it. At most 2 edits. Return empty edits if nothing recurs.",
                        true, "sleep", null, fs)
                    val ok = Harness.arr(rec.optJSONArray("applied")).filter { it.optBoolean("ok") }
                    log.put("refined", if (ok.isNotEmpty()) s(rec, "summary") else "")
                    if (ok.isNotEmpty()) LearnEvents.emit("refine", Harness.refineCard(rec, null))
                } catch (e: Throwable) { log.put("error", e.message ?: e.toString()) }
            }
            Prefs.put("learn.sleepLast", System.currentTimeMillis().toString())
            val L = sleepLog().toMutableList(); L.add(0, log); while (L.size > 20) L.removeAt(L.size - 1)
            synchronized(fileLock) { try { sleepFile.writeText(JSONArray(L).toString()) } catch (_: Throwable) {} }
            val refined = s(log, "refined").isNotEmpty()
            if (merged > 0 || purged > 0 || refined) LearnEvents.emit("sleep", JSONObject().put("open", "lessons").put("log", log).put("text", "🌙 " + Y.t(
                "yaver tidied its lessons: $merged merged, $purged removed${if (refined) ", 1 new lesson from repeated mistakes" else ""}",
                "yaver derslerini düzenledi: $merged birleştirildi, $purged kaldırıldı${if (refined) ", tekrarlanan hatalardan yeni ders" else ""}")))
            else if (force) LearnEvents.emit("sleep", JSONObject().put("log", log).put("text", Y.t("Nothing to tidy", "Düzenlenecek bir şey yok")))
            return log
        } finally { sleeping.set(false) }
    }

    /** Call on any user interaction (typing, tapping) so the idle timer restarts. */
    fun touch() { lastActive = System.currentTimeMillis() }

    /** Starts the idle watcher once (e.g. from MainActivity.onCreate): every 60 s, when no turn is running and the user
     *  has been idle ≥ 90 s, runs the sleep pass (which itself runs at most once per 20 h). */
    @Synchronized fun start() {
        if (started) return; started = true
        if (Prefs.get("learn.sleepLast", "").isEmpty()) Prefs.put("learn.sleepLast", System.currentTimeMillis().toString())
        lastActive = System.currentTimeMillis()
        val t = Thread({
            while (true) {
                try { Thread.sleep(60_000L) } catch (_: InterruptedException) { return@Thread }
                try {
                    if (LearnHooks.busy?.invoke() == true) continue
                    if (System.currentTimeMillis() - lastActive < 90_000L) continue
                    sleep()
                } catch (e: Throwable) { Diag.e("learn", "sleep pass", e) }
            }
        }, "yaver-sleep")
        t.isDaemon = true; t.priority = Thread.MIN_PRIORITY; t.start()
    }
}
