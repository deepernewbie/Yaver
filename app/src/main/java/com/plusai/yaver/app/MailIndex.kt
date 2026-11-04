package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Turkish-aware folding, tokens and prefix BM25 (private to the mail/tasks/search port). */
internal object MxText {
    private val TR = Locale("tr", "TR")
    private val MARKS = Regex("\\p{M}+")
    private val TOK = Regex("[\\p{L}\\p{N}_]{2,}")
    private val WS = Regex("\\s+")

    fun fold(s: String?): String = Normalizer.normalize((s ?: "").lowercase(TR), Normalizer.Form.NFKD).replace(MARKS, "").replace("ı", "i")
    fun tok(s: String?): List<String> = if (s.isNullOrEmpty()) emptyList() else TOK.findAll(fold(s)).map { it.value }.toList()
    fun memFold(s: String?): String = fold(s).replace(WS, " ").trim()

    /** "rapor" matches "raporu", "raporlar"; tokens shorter than 4 must match exactly. */
    fun hit(tok: String, t: String) = if (t.length < 4) tok == t else tok.startsWith(t)

    class Doc<T>(val x: T, val toks: List<String>)

    /** Shared ranking (JS vrank): no query → every doc with score 0 in the given order. */
    fun <T> vrank(query: String, docs: List<Doc<T>>, minShare: Double = 0.0): List<Pair<T, Double>> {
        val q = tok(query).distinct()
        if (q.isEmpty()) return docs.map { it.x to 0.0 }
        val n = maxOf(1, docs.size)
        val avg = (docs.sumOf { it.toks.size }.toDouble() / n).let { if (it <= 0) 1.0 else it }
        val df = q.associateWith { t -> docs.count { d -> d.toks.any { hit(it, t) } } }
        val out = ArrayList<Pair<T, Double>>()
        for (d in docs) {
            var s = 0.0; var m = 0
            for (t in q) {
                val tf = d.toks.count { hit(it, t) }
                if (tf == 0) continue
                m++
                val f = df[t] ?: 0
                val idf = Math.log(1 + (n - f + .5) / (f + .5))
                s += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * d.toks.size / avg))
            }
            if (s > 0 && m.toDouble() / q.size >= minShare) out.add(d.x to s * (0.6 + 0.4 * m / q.size))
        }
        return out.sortedByDescending { it.second }
    }

    fun who(b: MailBox?): String = b?.let { it.name.ifEmpty { it.email.ifEmpty { "?" } } } ?: "?"

    /** JS fmtWhen: time today, otherwise "3 Oct" (+ year when not this year). */
    fun fmtWhen(iso: String): String {
        val ms = Exchange.parseTime(iso)
        if (ms == 0L) return ""
        val loc = if (Y.tr) TR else Locale.getDefault()
        val d = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        val sameDay = d.get(Calendar.YEAR) == now.get(Calendar.YEAR) && d.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return SimpleDateFormat("HH:mm", loc).format(Date(ms))
        return SimpleDateFormat("d MMM", loc).format(Date(ms)) + if (d.get(Calendar.YEAR) != now.get(Calendar.YEAR)) " " + d.get(Calendar.YEAR) else ""
    }

    fun relTime(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return if (Y.tr) when {
            s < 60 -> "az önce"; s < 3600 -> "${s / 60} dk önce"; s < 86400 -> "${s / 3600} sa önce"; s < 604800 -> "${s / 86400} gün önce"
            else -> SimpleDateFormat("d.MM.yyyy", TR).format(Date(t))
        } else when {
            s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; s < 604800 -> "${s / 86400} d ago"
            else -> java.text.DateFormat.getDateInstance().format(Date(t))
        }
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun dayPlus(n: Int): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + n * 86400000L))

    /** First {...} in a model answer. */
    fun jsonIn(t: String): JSONObject? = Regex("\\{[\\s\\S]*\\}").find(t)?.let { try { JSONObject(it.value) } catch (e: Exception) { null } }
    fun arrayIn(t: String): JSONArray? { val a = t.indexOf('['); val b = t.lastIndexOf(']'); return if (a >= 0 && b > a) try { JSONArray(t.substring(a, b + 1)) } catch (e: Exception) { null } else null }

    // ---- small EWS DOM helpers (Exchange's own are private) ----
    fun box(el: Element?): MailBox? = Exchange.box(el)
    fun boxes(el: Element?, name: String): List<MailBox> = Exchange.boxes(el, name)
    fun itemId(el: Element?): Pair<String, String>? {
        val i = el?.let { e -> Xml.children(e).firstOrNull { it.localName == "ItemId" } } ?: Xml.kids(el, "ItemId").firstOrNull() ?: return null
        return i.getAttribute("Id") to i.getAttribute("ChangeKey")
    }
    fun boxJson(b: MailBox?): Any = b?.let { JSONObject().put("name", it.name).put("email", it.email) } ?: JSONObject.NULL
    fun boxFrom(o: JSONObject?): MailBox? = o?.let { MailBox(it.optString("name"), it.optString("email")) }
}

/** One indexed e-mail (JSON keys as in the original: s subject, fr from, d date, a attachments, r read, b body, f folder). */
class MailEntry(val id: String, val s: String, val fr: MailBox?, val to: List<MailBox>, val cc: List<MailBox>, val d: String, val a: List<String>, var r: Boolean, val b: String, var f: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("s", s).put("fr", MxText.boxJson(fr))
        .put("to", JSONArray(to.map { MxText.boxJson(it) })).put("cc", JSONArray(cc.map { MxText.boxJson(it) }))
        .put("d", d).put("a", JSONArray(a)).put("r", r).put("b", b).put("f", f)

    companion object {
        private fun boxes(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { MxText.boxFrom(a.optJSONObject(it)) }
        fun from(j: JSONObject) = MailEntry(
            j.optString("id"), j.optString("s"), MxText.boxFrom(j.optJSONObject("fr")), boxes(j.optJSONArray("to")), boxes(j.optJSONArray("cc")),
            j.optString("d"), j.optJSONArray("a")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), j.optBoolean("r", true), j.optString("b"), j.optString("f")
        )
    }
}

/** Filters for [MailIndex.filter]; dates are YYYY-MM-DD, `when` is today|yesterday|week|month|quarter|year. */
class MailFilter(
    var from: String = "", var to: String = "", var person: String = "", var after: String = "", var before: String = "",
    var hasAttachments: Boolean? = null, var attachment: String = "", var folder: String = "all", var unread: Boolean = false, var `when`: String = ""
) {
    fun copy() = MailFilter(from, to, person, after, before, hasAttachments, attachment, folder, unread, `when`)
    fun toJson(): JSONObject = JSONObject().put("from", from).put("to", to).put("person", person).put("after", after).put("before", before)
        .put("has_attachments", hasAttachments ?: JSONObject.NULL).put("attachment", attachment).put("folder", folder).put("unread", unread).put("when", `when`)

    companion object {
        fun from(j: JSONObject?): MailFilter {
            if (j == null) return MailFilter()
            return MailFilter(j.str("from"), j.str("to"), j.str("person"), j.str("after"), j.str("before"),
                if (j.has("has_attachments") && !j.isNull("has_attachments")) j.optBoolean("has_attachments") else null,
                j.str("attachment"), j.str("folder").ifEmpty { "all" }, j.optBoolean("unread"), j.str("when"))
        }
    }
}

/**
 * Local index of recent mail (inbox, sent, archive): filters, prefix BM25 and an optional LLM step that turns a
 * request into filters and re-ranks. Kept fresh by the background mail check ([MailPlus.onMailCheck]).
 */
object MailIndex {
    val FOLDERS = listOf("inbox", "sentitems", "archive")
    private val file: File get() = File(Y.app.filesDir, "harness/mail-index.json")
    private val lock = Any()
    private var items: LinkedHashMap<String, MailEntry>? = null
    @Volatile var synced: Long = 0; private set
    private var host = ""
    /** Index written before recipients were read correctly over Outlook web (v1): entries without To/Cc are read again. */
    @Volatile private var refill = false
    @Volatile var busy = false; private set
    @Volatile var progress = ""; private set
    @Volatile var lastErr: String? = null; private set

    /** Called when the index changes or its progress text changes (for the mail view). */
    @Volatile var onState: (() -> Unit)? = null
    private fun notifyState() { try { onState?.invoke() } catch (_: Throwable) {} }

    private fun load(): LinkedHashMap<String, MailEntry> = synchronized(lock) {
        items?.let { return it }
        val m = LinkedHashMap<String, MailEntry>()
        try {
            if (file.exists()) {
                val j = JSONObject(file.readText())
                val o = j.optJSONObject("items") ?: JSONObject()
                for (k in o.keys()) o.optJSONObject(k)?.let { m[k] = MailEntry.from(it) }
                synced = j.optLong("synced"); host = j.optString("host")
                refill = j.optInt("v", 1) < 2 && m.values.any { it.to.isEmpty() && it.cc.isEmpty() }
            }
        } catch (e: Exception) { Diag.e("mailindex", "load", e) }
        items = m
        m
    }

    private fun save() = synchronized(lock) {
        try {
            val o = JSONObject()
            for ((k, v) in load()) o.put(k, v.toJson())
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("v", if (refill) 1 else 2).put("items", o).put("synced", synced).put("host", host).toString())
            tmp.renameTo(file)
        } catch (e: Exception) { lastErr = "could not save the mail index: " + e.message }
    }

    fun count(): Int = synchronized(lock) { load().size }
    fun entry(id: String): MailEntry? = synchronized(lock) { load()[id] }

    /** People seen in recent mail (senders and recipients) whose name or address starts with the query words; most frequent first. */
    fun people(q: String, limit: Int = 8): List<MailBox> {
        // a whole address: that person, with the best name seen for them
        if (Regex("^[^\\s@<>]+@[^\\s@<>]+$").matches(q.trim())) {
            val e = q.trim()
            val seen = all().flatMap { listOfNotNull(it.fr) + it.to + it.cc }.filter { it.email.equals(e, true) }
            val best = seen.firstOrNull { it.name.isNotBlank() && !it.name.equals(it.email, true) && !Regex("^[a-z]{0,2}\\d{4,}$", RegexOption.IGNORE_CASE).matches(it.name) } ?: seen.firstOrNull()
            return listOfNotNull(best)
        }
        val words = MxText.fold(q).split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val count = HashMap<String, Int>(); val best = HashMap<String, MailBox>()
        for (e in all()) for (b in listOfNotNull(e.fr) + e.to + e.cc) {
            if (b.email.isEmpty()) continue
            val key = b.email.lowercase()
            count[key] = (count[key] ?: 0) + 1
            val cur = best[key]
            if (cur == null || (cur.name.isEmpty() || cur.name.equals(cur.email, true) || Regex("^[a-z]?\\d{4,}$", RegexOption.IGNORE_CASE).matches(cur.name)) && b.name.isNotEmpty()) best[key] = b
        }
        return best.values.filter { b ->
            val hay = MxText.fold(b.name + " " + b.email).split(Regex("[\\s.@_<>-]+"))
            words.all { w -> hay.any { it.startsWith(w) } }
        }.sortedByDescending { count[it.email.lowercase()] ?: 0 }.take(limit)
    }
    /** The real name seen for this address in recent mail (not an account id), or null. */
    fun nameOf(email: String): String? = people(email, 1).firstOrNull()?.name?.takeIf { it.isNotBlank() && !it.equals(email, true) && !Regex("^[a-z]{0,2}\\d{4,}$", RegexOption.IGNORE_CASE).matches(it) }

    fun max(): Int = maxOf(200, MailPlusCfg.indexMax)
    private fun all(): List<MailEntry> = synchronized(lock) { load().values.toList() }

    /** JSON for the mail view's index line: "Search index: N e-mails · updated …", Update / Rebuild buttons. */
    fun state(): JSONObject = JSONObject().put("count", count()).put("synced", synced).put("busy", busy).put("progress", progress).put("error", lastErr ?: JSONObject.NULL)

    /** GetItem for many ids at once; one by one if a batch fails (e.g. an item was deleted). */
    private fun fetchMany(ids: List<String>, cancel: Cancel?): List<MailEntry> {
        fun one(list: List<String>): List<MailEntry> {
            val props = listOf("item:Subject", "message:From", "message:ToRecipients", "message:CcRecipients", "item:DateTimeReceived", "item:DateTimeSent", "item:HasAttachments", "item:Attachments", "message:IsRead", "item:Body", "item:DisplayTo", "item:DisplayCc")
            val doc = Mail.use(cancel) { ex -> ex.t.call("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:BodyType>Text</t:BodyType><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape><m:ItemIds>${list.joinToString("") { "<t:ItemId Id=\"${Xml.esc(it)}\"/>" }}</m:ItemIds></m:GetItem>", 120000, cancel) }
            val out = ArrayList<MailEntry>()
            for (box in Xml.kids(doc, "Items")) for (it in Xml.children(box)) {
                val id = MxText.itemId(it)?.first ?: continue
                if (id.isEmpty()) continue
                val own = { n: String -> Exchange.own(it, n) }
                val body = own("Body").replace(Regex("\\s+"), " ").trim()
                val atts = (Xml.kids(it, "FileAttachment") + Xml.kids(it, "ItemAttachment")).filter { a -> Exchange.own(a, "IsInline") != "true" }.map { a -> Exchange.own(a, "Name") }.filter { n -> n.isNotEmpty() }
                val from = MxText.box(Xml.children(it).firstOrNull { e -> e.localName == "From" }) ?: MxText.box(Xml.kids(it, "From").firstOrNull())
                out.add(MailEntry(id, own("Subject").ifEmpty { "(no subject)" }, from,
                    MxText.boxes(it, "ToRecipients").ifEmpty { Exchange.names(own("DisplayTo")) }, MxText.boxes(it, "CcRecipients").ifEmpty { Exchange.names(own("DisplayCc")) },
                    own("DateTimeReceived").ifEmpty { own("DateTimeSent") }, atts, own("IsRead") != "false", body.take(1500), ""))
            }
            return out
        }
        // a failing batch is split in halves, so one broken item costs a few requests instead of one per item;
        // an item that fails alone is skipped for a day (it would otherwise be fetched again on every mail check)
        fun go(list: List<String>): List<MailEntry> = try { one(list) } catch (e: CancelledException) { throw e } catch (e: AuthException) { throw e } catch (e: Exception) {
            if (list.size == 1) {
                Diag.i("mailindex", "item skipped: ${e.message?.take(120)}")
                synchronized(lock) { bad[list[0]] = System.currentTimeMillis() }
                emptyList()
            } else { cancel?.check(); go(list.subList(0, list.size / 2)) + go(list.subList(list.size / 2, list.size)) }
        }
        return go(ids)
    }

    /** Items the server could not return (id → when), not asked for again for a day. */
    private val bad = HashMap<String, Long>()
    private fun isBad(id: String) = synchronized(lock) { bad[id]?.let { System.currentTimeMillis() - it < 86400000L } == true }
    /** When Sent Items / Archive were last paged: they change rarely, so every 15 minutes is enough. */
    private val folderSynced = HashMap<String, Long>()

    private fun originNow(): String = Mail.owaOrigin ?: Cfg.mailEws

    /** Reads new mail of the three folders into the index (full=true rebuilds). Runs on the calling thread. */
    /**
     * [inboxHint]: the inbox page the caller just read (MailPlus.refresh): when it holds nothing new, the inbox is
     * not listed again — a quiet background check then costs no extra request.
     */
    fun sync(full: Boolean = false, cancel: Cancel? = null, inboxHint: List<MailHeader>? = null) {
        synchronized(lock) { if (busy) return; busy = true }
        lastErr = null
        try {
            val d = load()
            val again = refill && !full
            val o = originNow()
            synchronized(lock) { if (host.isNotEmpty() && host != o) d.clear(); host = o; if (full) d.clear() }
            for (f in FOLDERS) {
                if (!full && !again) {
                    if (f == "inbox" && inboxHint != null && inboxHint.isNotEmpty()) {
                        val allKnown = synchronized(lock) { inboxHint.all { h -> d[h.id]?.also { it.r = h.read; it.f = f } != null || isBad(h.id) } }
                        if (allKnown) continue
                    }
                    if (f != "inbox" && System.currentTimeMillis() - (folderSynced[f] ?: 0L) < 15 * 60000L) continue
                }
                folderSynced[f] = System.currentTimeMillis()
                var offset = 0
                val fresh = ArrayList<String>()
                var stop = false
                val cap = max()
                while (!stop && offset < cap) {
                    cancel?.check()
                    progress = "Indexing ${if (f == "sentitems") "sent" else f}… $offset"; notifyState()
                    val r = try { Mail.use(cancel) { it.find(f, "", false, 100, offset, cancel) } } catch (e: CancelledException) { throw e } catch (e: Exception) { if (f == "archive") break; throw e }
                    if (r.items.isEmpty()) break
                    synchronized(lock) {
                        for (m in r.items) {
                            val known = d[m.id]
                            if (known != null && again && known.to.isEmpty() && known.cc.isEmpty()) { fresh.add(m.id); continue }
                            if (known != null) { known.r = m.read; known.f = f; if (!full && !again) stop = true } else if (!isBad(m.id)) fresh.add(m.id)
                        }
                        if (!full && !again && r.items.all { d.containsKey(it.id) || isBad(it.id) }) stop = true
                    }
                    offset += r.items.size
                    if (offset >= r.total) break
                }
                var i = 0
                while (i < fresh.size) {
                    cancel?.check()
                    progress = "Reading ${if (f == "sentitems") "sent" else f} mail ${minOf(i + 40, fresh.size)}/${fresh.size}"; notifyState()
                    val got = fetchMany(fresh.subList(i, minOf(i + 40, fresh.size)).toList(), cancel)
                    synchronized(lock) { for (x in got) { x.f = f; d[x.id] = x } }
                    i += 40
                }
            }
            // keep the newest entries only
            synchronized(lock) {
                val keep = max() * FOLDERS.size
                if (d.size > keep) {
                    val sorted = d.values.sortedByDescending { Exchange.parseTime(it.d) }.take(keep)
                    d.clear(); for (x in sorted) d[x.id] = x
                }
                synced = System.currentTimeMillis()
                refill = false
            }
            save()
        } catch (e: CancelledException) {
        } catch (e: Exception) {
            lastErr = e.message ?: e.toString()
            Diag.e("mailindex", "sync", e)
        } finally {
            busy = false; progress = ""; notifyState()
        }
    }

    fun forget(id: String) { val had = synchronized(lock) { load().remove(id) != null }; if (had) save() }

    /** A read flag changed elsewhere (mark_read). */
    fun markRead(id: String, read: Boolean) { val e = synchronized(lock) { load()[id] } ?: return; e.r = read; save() }

    // ---- search ----
    private fun sod(c: Calendar = Calendar.getInstance()): Calendar = (c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private fun plusDays(c: Calendar, n: Int): Long = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, n) }.timeInMillis

    private fun dateRange(f: MailFilter): Pair<Long?, Long?> {
        val s = sod()
        var a: Long? = null; var b: Long? = null
        when (f.`when`) {
            "today" -> a = s.timeInMillis
            "yesterday" -> { a = plusDays(s, -1); b = s.timeInMillis }
            "week" -> a = plusDays(s, -7)
            "month" -> a = plusDays(s, -30)
            "quarter" -> a = plusDays(s, -90)
            "year" -> a = (s.clone() as Calendar).apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        }
        if (f.after.isNotEmpty()) try { a = MailTools.parseLocal(f.after) } catch (_: Exception) {}
        if (f.before.isNotEmpty()) try { b = MailTools.parseLocal(f.before) + if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(f.before)) 86400000L else 0L } catch (_: Exception) {}
        return a to b
    }

    private fun person(b: MailBox?): String = if (b == null) "" else MxText.fold(b.name + " " + b.email)

    fun filter(f: MailFilter = MailFilter()): List<MailEntry> {
        val (a, b) = dateRange(f)
        val from = MxText.fold(f.from); val to = MxText.fold(f.to); val att = MxText.fold(f.attachment); val who = MxText.fold(f.person)
        val folder = if (f.folder.isNotEmpty() && f.folder != "all") Exchange.folder(f.folder) else null
        return all().filter { x ->
            if (folder != null && x.f != folder) return@filter false
            val d = Exchange.parseTime(x.d).takeIf { it > 0 }
            if (a != null && (d == null || d < a)) return@filter false
            if (b != null && (d == null || d >= b)) return@filter false
            if (from.isNotEmpty() && !person(x.fr).contains(from)) return@filter false
            if (to.isNotEmpty() && (x.to + x.cc).none { person(it).contains(to) }) return@filter false
            if (who.isNotEmpty() && (listOfNotNull(x.fr) + x.to + x.cc).none { person(it).contains(who) }) return@filter false
            if (f.hasAttachments == true && x.a.isEmpty()) return@filter false
            if (f.hasAttachments == false && x.a.isNotEmpty()) return@filter false
            if (att.isNotEmpty() && x.a.none { MxText.fold(it).contains(att) }) return@filter false
            if (f.unread && x.r) return@filter false
            true
        }
    }

    /** Prefix BM25 with subject ×3, sender ×2, attachment names ×2, recipients, body; slight boost for recent mail. */
    fun rank(query: String, list: List<MailEntry>): List<Pair<MailEntry, Double>> {
        val q = MxText.tok(query).distinct().filter { it.length >= 2 }
        if (q.isEmpty()) return list.sortedByDescending { Exchange.parseTime(it.d) }.map { it to 0.0 }
        val docs = list.map { x ->
            val st = MxText.tok(x.s)
            val toks = ArrayList<String>()
            repeat(3) { toks.addAll(st) }
            toks.addAll(MxText.tok(x.fr?.let { it.name + " " + it.email } ?: ""))
            toks.addAll(MxText.tok(x.fr?.name ?: ""))
            for (n in x.a) { val t = MxText.tok(n); toks.addAll(t); toks.addAll(t) }
            for (p in x.to) toks.addAll(MxText.tok(p.name + " " + p.email))
            toks.addAll(MxText.tok(x.b))
            MxText.Doc(x, toks)
        }
        val n = maxOf(1, docs.size)
        val avg = (docs.sumOf { it.toks.size }.toDouble() / n).let { if (it <= 0) 1.0 else it }
        val df = q.associateWith { t -> docs.count { d -> d.toks.any { MxText.hit(it, t) } } }
        val now = System.currentTimeMillis()
        val out = ArrayList<Pair<MailEntry, Double>>()
        for (d in docs) {
            var s = 0.0; var matched = 0
            for (t in q) {
                val tf = d.toks.count { MxText.hit(it, t) }
                if (tf == 0) continue
                matched++
                val f = df[t] ?: 0
                val idf = Math.log(1 + (n - f + .5) / (f + .5))
                s += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * d.toks.size / avg))
            }
            if (s > 0) {
                s *= 0.6 + 0.4 * matched / q.size
                val age = (now - Exchange.parseTime(d.x.d)) / 864e5
                s *= 1 + 0.15 / (1 + age / 30)
                out.add(d.x to s)
            }
        }
        return out.sortedByDescending { it.second }
    }

    fun search(query: String, f: MailFilter = MailFilter(), limit: Int = 50): List<MailEntry> = rank(query, filter(f)).take(limit).map { it.first }

    class SmartResult(val items: List<MailEntry>, val how: String, val plan: MailFilter?)

    /** Vectorless "smart" search: LLM → filters + keywords, local shortlist, LLM re-rank. */
    fun smart(question: String, base: MailFilter = MailFilter(), limit: Int = 20, cancel: Cancel? = null): SmartResult {
        if (!Cfg.llmReady) return SmartResult(search(question, base, limit), "keywords", null)
        val today = MxText.today()
        val dow = SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())
        var plan = JSONObject()
        try {
            val r = Llm.ask("Turn a request for finding e-mails into JSON filters. Today is $today ($dow). Output only JSON with any of: {\"keywords\": \"important words, singular stems, both Turkish and English variants if useful\", \"from\": \"sender name or address part\", \"to\": \"recipient name part\", \"person\": \"someone who is sender or recipient\", \"after\": \"YYYY-MM-DD\", \"before\": \"YYYY-MM-DD\", \"has_attachments\": true|false, \"attachment\": \"file name part or extension like xlsx/pdf\", \"folder\": \"inbox|sentitems|archive|all\"}. Leave out what the request does not say.", question, cancel, 300, true)
            plan = MxText.jsonIn(r) ?: JSONObject()
        } catch (e: CancelledException) { throw e } catch (_: Exception) {}
        val f = base.copy()
        fun pick(k: String, cur: String): String = if (cur.isEmpty()) plan.optString(k, "").let { if (it == "null") "" else it }.ifEmpty { cur } else cur
        f.from = pick("from", f.from); f.to = pick("to", f.to); f.person = pick("person", f.person); f.after = pick("after", f.after); f.before = pick("before", f.before)
        f.attachment = pick("attachment", f.attachment); f.folder = pick("folder", f.folder)
        if (plan.opt("has_attachments") is Boolean && f.hasAttachments == null) f.hasAttachments = plan.optBoolean("has_attachments")
        val kw = plan.optString("keywords", "").ifEmpty { question }
        var short = rank(kw, filter(f)).take(40)
        if (short.isEmpty()) short = rank(question, filter(base)).take(40)
        if (short.isEmpty()) short = rank("", filter(f)).take(40)
        if (short.size <= 3) return SmartResult(short.map { it.first }, "filters + keywords", f)
        try {
            val list = short.mapIndexed { i, (x, _) -> "#$i ${Exchange.parseTime(x.d).let { ms -> if (ms > 0) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms)) else x.d.take(10) }} | from ${MxText.who(x.fr)} | to ${x.to.map { it.name.ifEmpty { it.email } }.take(3).joinToString(", ")} | ${x.s}${if (x.a.isNotEmpty()) " | attachments: " + x.a.joinToString(", ") else ""} | ${x.b.take(220)}" }.joinToString("\n")
            val r = Llm.ask("You pick the e-mails that best match a request. Output only JSON {\"ids\": [numbers of the matching e-mails, best first]}. Include only real matches (max $limit).", "Request: $question\n\nE-mails:\n$list", cancel, 300, true)
            val ids = MxText.jsonIn(r)?.optJSONArray("ids")
            if (ids != null && ids.length() > 0) {
                val picked = (0 until ids.length()).mapNotNull { short.getOrNull(ids.opt(it).toString().replace("#", "").trim().toDoubleOrNull()?.toInt() ?: -1)?.first }.distinct().take(limit)
                if (picked.isNotEmpty()) return SmartResult(picked, "AI search", f)
            }
        } catch (e: CancelledException) { throw e } catch (_: Exception) {}
        return SmartResult(short.take(limit).map { it.first }, "filters + keywords", f)
    }

    /** JSON for the mail view's index search (filter bar: when, from, to, attachments, file name, folder). */
    fun searchJson(q: String, f: MailFilter, smart: Boolean, limit: Int = 100, cancel: Cancel? = null): JSONObject {
        val r = if (smart && q.isNotEmpty()) smart(q, f, 30, cancel) else SmartResult(search(q, f, limit), if (q.isNotEmpty()) "keywords" else "filters", null)
        var how = r.how
        r.plan?.toJson()?.let { p -> val parts = p.keys().asSequence().filter { k -> k != "folder" && k != "unread" && p.opt(k).let { v -> v != null && v != JSONObject.NULL && v.toString().isNotEmpty() && v != false } }.map { k -> "$k: ${p.opt(k)}" }.toList(); if (parts.isNotEmpty()) how += " · " + parts.joinToString(", ") }
        return JSONObject().put("how", how).put("items", JSONArray(r.items.map { it.toJson().put("received", Exchange.parseTime(it.d)) }))
    }
}
