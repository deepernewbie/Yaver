package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class TaskNote(val id: String, var text: String, val by: String, var at: Long, var edited: Long = 0, var editedBy: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("by", by).put("at", at).also { if (edited > 0) it.put("edited", edited).put("editedBy", editedBy) }
    companion object {
        fun from(j: JSONObject) = TaskNote(j.optString("id").ifEmpty { "n" + java.lang.Long.toString(System.nanoTime(), 36) }, j.optString("text"), j.optString("by", "user"), j.optLong("at", System.currentTimeMillis()), j.optLong("edited"), j.optString("editedBy"))
    }
}

/** One task: id T1, T2…; status todo | doing | done; due YYYY-MM-DD; source {mailId, subject} when made from an e-mail. */
class BoardTask(
    val id: String, var title: String, var desc: String, var owner: String, var due: String, var status: String, var order: Int,
    var created: Long, var updated: Long, val notes: MutableList<TaskNote>, var source: JSONObject?, var by: String, var lastBy: String = "", var closed: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("desc", desc).put("owner", owner).put("due", due).put("status", status)
        .put("order", order).put("created", created).put("updated", updated).put("notes", JSONArray(notes.map { it.toJson() }))
        .put("source", source ?: JSONObject.NULL).put("by", by).also { if (lastBy.isNotEmpty()) it.put("lastBy", lastBy); if (closed > 0) it.put("closed", closed) }

    companion object {
        fun from(j: JSONObject): BoardTask {
            val n = j.optJSONArray("notes") ?: JSONArray()
            return BoardTask(j.optString("id"), j.optString("title"), j.optString("desc"), j.optString("owner"), j.optString("due"), j.optString("status", "todo").ifEmpty { "todo" },
                j.optDouble("order", 0.0).toInt(), j.optLong("created"), j.optLong("updated"), (0 until n.length()).mapNotNull { n.optJSONObject(it)?.let { o -> TaskNote.from(o) } }.toMutableList(),
                j.optJSONObject("source"), j.optString("by", "user"), j.optString("lastBy"), j.optLong("closed"))
        }
    }
}

/** Approval cards for work actions (WorkOK): asked even with "ask before risky actions" off unless MailPlusCfg.workConfirm is off. */
internal object WorkOk {
    /** fields: label, new value, old value (shown as "old → new" when it changed). */
    fun ask(ctx: ToolCtx, title: String, fields: List<Triple<String, String?, String?>>, danger: Boolean = false): Boolean {
        val rows = fields.filter { !it.second.isNullOrEmpty() || !it.third.isNullOrEmpty() }.map { (l, v, o) ->
            l to (if (o != null && o != (v ?: "")) "${o.ifEmpty { "—" }} → " else "") + (v ?: "").ifEmpty { "—" }
        }
        return ctx.approve(title, rows, always = MailPlusCfg.workConfirm, danger = danger)
    }

    fun denied(ctx: ToolCtx, what: String): String = if (ctx.background || ctx.listener == null) ctx.denied(what)
    else "Not done: the user rejected $what on the approval card. Do not retry the same action; ask the user what to change."
}

/**
 * The user's work task board (the Tasks view). Stored in files/tasks/board.json together with the Outlook backup
 * state (outlook, deleted) used by [OSync].
 *
 * UI the integrator builds in the WebView (all data via [dispatch]): a toolbar (New task, filter box, owner select,
 * status select Open/All/To do/In progress/Done with counts, sort select manual/due/owner/updated, People button,
 * Outlook backup chip from MailPlus "osync.state", "Ask yaver about tasks…" box that sends the question plus
 * "(Answer from my task board with the task_board tool.)"); a grid of cards (status select, id, ⤒ ⤓ in manual sort,
 * title, last 3 notes newest first with [x](mail:id)/[x](task:T3) chips, owner button with inline picker, due date
 * input coloured overdue/soon (≤2 days), notes count, "Updated … by yaver"); a side panel for one task (title, status,
 * due, owner with people datalist, description, source e-mail link, notes with edit/delete, add note, Ask yaver,
 * Delete); a People panel (add, open counts, filter by person, remove). A badge on the Tasks tab shows [overdueCount].
 */
object TaskBoard {
    val STATUS = linkedMapOf("todo" to "To do", "doing" to "In progress", "done" to "Done")
    private val file: File get() = File(Y.app.filesDir, "tasks/board.json")
    private val lock = Any()
    private var loaded = false
    private var mtime = 0L
    val people = ArrayList<String>()
    val tasks = ArrayList<BoardTask>()
    var seq = 0
    /** Outlook backup state ({map, lastSync, idxHash, idxId}); owned by OSync. */
    var outlook = JSONObject()
    /** Deleted task ids ({id, at, keepRemote?}); used by OSync so deletions reach Outlook. */
    var deleted = JSONArray()
    private val listeners = ArrayList<() -> Unit>()

    private val trColl: Collator by lazy { Collator.getInstance(Locale("tr", "TR")) }

    fun onChange(f: () -> Unit) { synchronized(listeners) { listeners.add(f) } }

    /** (Re)reads the board when the file changed. Every public function calls it. */
    fun load(): TaskBoard {
        synchronized(lock) {
            val m = if (file.exists()) file.lastModified() else 0L
            if (loaded && m == mtime) return this
            people.clear(); tasks.clear(); seq = 0; outlook = JSONObject(); deleted = JSONArray()
            try {
                if (file.exists()) {
                    val j = JSONObject(file.readText())
                    j.optJSONArray("people")?.let { a -> for (i in 0 until a.length()) people.add(a.optString(i)) }
                    j.optJSONArray("tasks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { tasks.add(BoardTask.from(it)) } }
                    seq = j.optInt("seq", 0)
                    outlook = j.optJSONObject("outlook") ?: JSONObject()
                    deleted = j.optJSONArray("deleted") ?: JSONArray()
                }
            } catch (e: Exception) { Diag.e("tasks", "load", e) }
            loaded = true; mtime = m
            return this
        }
    }

    /** Runs [f] on the loaded board under the lock, then saves (used by the Outlook backup). */
    fun <T> edit(f: TaskBoard.() -> T): T { val r = synchronized(lock) { load(); f() }; save(); return r }

    fun toJson(): JSONObject = synchronized(lock) {
        JSONObject().put("v", 1).put("people", JSONArray(people)).put("tasks", JSONArray(tasks.map { it.toJson() })).put("seq", seq).put("outlook", outlook).put("deleted", deleted)
    }

    /** Writes the board and tells the listeners (UI refresh, Outlook backup). */
    fun save() {
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "board.json.tmp")
                tmp.writeText(toJson().toString(1))
                tmp.renameTo(file)
                mtime = file.lastModified()
            } catch (e: Exception) { Diag.e("tasks", "save", e); MailPlus.event("toast", JSONObject().put("text", "Could not save tasks: " + e.message)) }
        }
        val l = synchronized(listeners) { listeners.toList() }
        for (f in l) try { f() } catch (_: Throwable) {}
    }

    fun normStatus(s: String?): String? {
        val x = MxText.fold(s).replace(Regex("[\\s_-]+"), "")
        if (Regex("^(done|closed|complete|completed|bitti|tamam|tamamlandi|kapali|kapat)").containsMatchIn(x)) return "done"
        if (Regex("^(doing|inprogress|progress|started|wip|devam|suruyor|baslandi)").containsMatchIn(x)) return "doing"
        if (Regex("^(todo|open|new|yapilacak|acik|bekliyor)").containsMatchIn(x)) return "todo"
        return null
    }

    fun addPerson(name0: String?, persist: Boolean = true): String {
        val name = (name0 ?: "").trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return ""
        synchronized(lock) {
            load()
            people.firstOrNull { MxText.fold(it) == MxText.fold(name) }?.let { return it }
            people.add(name); people.sortWith(trColl)
        }
        if (persist) save()
        return name
    }

    fun removePerson(name: String) {
        synchronized(lock) {
            load()
            people.remove(name)
            for (t in tasks) if (t.owner == name) { t.owner = ""; t.updated = System.currentTimeMillis() }
        }
        save()
    }

    fun get(id: String): BoardTask = synchronized(lock) { load(); tasks.firstOrNull { it.id == id || it.id == id.trim() } } ?: throw IllegalArgumentException("no task with id $id")
    fun find(id: String): BoardTask? = try { get(id) } catch (e: Exception) { null }

    fun normDate(d: String?): String {
        val s = (d ?: "").trim()
        if (s.isEmpty()) return ""
        if (Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)) return s.take(10)
        Regex("^(\\d{1,2})[./](\\d{1,2})[./](\\d{4})$").find(s)?.let { m -> return "${m.groupValues[3]}-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[1].padStart(2, '0')}" }
        throw IllegalArgumentException("dates must look like 2026-10-01")
    }

    fun add(title: String?, desc: String? = "", owner: String? = "", due: String? = "", status: String? = "todo", source: JSONObject? = null, by: String = "user"): BoardTask {
        if ((title ?: "").isBlank()) throw IllegalArgumentException("a task needs a title")
        val dueN = normDate(due)
        val t: BoardTask
        synchronized(lock) {
            load()
            val now = System.currentTimeMillis()
            seq = (if (seq > 0) seq else tasks.size) + 1
            t = BoardTask("T$seq", title!!.trim(), desc ?: "", if (!owner.isNullOrBlank()) addPerson(owner, false) else "", dueN, normStatus(status) ?: "todo",
                minOf(0, tasks.minOfOrNull { it.order } ?: 0) - 1, now, now, ArrayList(), source, by)
            tasks.add(t)
        }
        save()
        return t
    }

    /** Changes any of title, desc, owner, due, status (null = keep). */
    fun update(id: String, title: String? = null, desc: String? = null, owner: String? = null, due: String? = null, status: String? = null, by: String = "user"): BoardTask {
        val t = get(id)
        synchronized(lock) {
            if (title != null) t.title = title.trim().ifEmpty { t.title }
            if (desc != null) t.desc = desc
            if (owner != null) t.owner = if (owner.isNotBlank()) addPerson(owner, false) else ""
            if (due != null) t.due = normDate(due)
            if (status != null) {
                val s = normStatus(status) ?: throw IllegalArgumentException("status must be todo, doing (in progress) or done")
                if (s == "done" && t.status != "done") t.closed = System.currentTimeMillis()
                t.status = s
            }
            t.updated = System.currentTimeMillis(); t.lastBy = by
        }
        save()
        return t
    }

    /** at: the note's own time when known (a note pulled from Outlook keeps the moment it was written there). */
    fun addNote(id: String, text: String?, by: String = "user", at: Long = 0, persist: Boolean = true): TaskNote {
        val t = get(id)
        if ((text ?: "").isBlank()) throw IllegalArgumentException("the note is empty")
        val n = TaskNote("n" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 46656).toLong(), 36), text!!.trim(), by, if (at > 0) at else System.currentTimeMillis())
        synchronized(lock) {
            t.notes.add(n)
            if (at > 0) sortNotes(t)
            t.updated = System.currentTimeMillis(); t.lastBy = by
        }
        if (persist) save()
        return n
    }

    /** Oldest first by their own time; equal times keep their order (stable sort). */
    fun sortNotes(t: BoardTask) { val s = t.notes.sortedBy { it.at }; t.notes.clear(); t.notes.addAll(s) }

    fun editNote(id: String, nid: String, text: String?, by: String = "user"): TaskNote? {
        val t = get(id)
        val n = synchronized(lock) { t.notes.firstOrNull { it.id == nid } } ?: throw IllegalArgumentException("no note $nid on task $id")
        val v = (text ?: "").trim()
        if (v.isEmpty()) { delNote(id, nid); return null }
        if (v == n.text) return n
        synchronized(lock) { n.text = v; n.edited = System.currentTimeMillis(); n.editedBy = by; t.updated = System.currentTimeMillis() }
        save()
        return n
    }

    fun delNote(id: String, nid: String) {
        val t = get(id)
        synchronized(lock) {
            val before = t.notes.size
            t.notes.removeAll { it.id == nid }
            if (t.notes.size == before) throw IllegalArgumentException("no note $nid on task $id")
            t.updated = System.currentTimeMillis()
        }
        save()
    }

    /** Deletes a task; the deletion is remembered so the Outlook copy goes too. */
    fun remove(id: String) {
        synchronized(lock) {
            load()
            deleted.put(JSONObject().put("id", id).put("at", System.currentTimeMillis()))
            if (deleted.length() > 500) { val k = JSONArray(); for (i in deleted.length() - 500 until deleted.length()) k.put(deleted.get(i)); deleted = k }
            tasks.removeAll { it.id == id }
        }
        save()
        OSync.schedule(1500)
    }

    // ---- manual order ----
    fun ordered(): List<BoardTask> = synchronized(lock) { load(); tasks.sortedBy { it.order } }

    fun moveTo(id: String, index: Int) {
        synchronized(lock) {
            val list = ordered().toMutableList()
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return
            val t = list.removeAt(i)
            list.add(index.coerceIn(0, list.size), t)
            list.forEachIndexed { k, x -> x.order = k }
        }
        save()
    }

    fun moveBefore(id: String, beforeId: String?) {
        val list = ordered().filter { it.id != id }
        val k = if (!beforeId.isNullOrEmpty()) list.indexOfFirst { it.id == beforeId } else list.size
        moveTo(id, if (k < 0) list.size else k)
    }

    private fun overdue(t: BoardTask, today: String) = t.due.isNotEmpty() && t.due < today && t.status != "done"

    /** All-words filter (status "open" = not done). */
    fun query(q: String = "", owner: String = "", status: String = "", dueBefore: String = "", overdue: Boolean = false): List<BoardTask> {
        val words = MxText.tok(q)
        val today = MxText.today()
        val fo = MxText.fold(owner)
        return ordered().filter { t ->
            if (owner.isNotEmpty() && MxText.fold(t.owner) != fo && !MxText.fold(t.owner).contains(fo)) return@filter false
            if (status == "open") { if (t.status == "done") return@filter false }
            else { val s = if (status.isNotEmpty()) normStatus(status) else null; if (s != null && t.status != s) return@filter false }
            if (dueBefore.isNotEmpty() && (t.due.isEmpty() || t.due > dueBefore)) return@filter false
            if (overdue && !overdue(t, today)) return@filter false
            if (words.isNotEmpty()) {
                val hay = MxText.tok(listOf(t.id, t.title, t.desc, t.owner, t.notes.joinToString(" ") { it.text }, t.source?.optString("subject") ?: "").joinToString(" "))
                if (!words.all { w -> hay.any { it.startsWith(w) } }) return@filter false
            }
            true
        }
    }

    internal fun taskDoc(t: BoardTask): MxText.Doc<BoardTask> {
        val toks = ArrayList<String>()
        val tt = MxText.tok(t.title); toks.addAll(tt); toks.addAll(tt)
        toks.addAll(MxText.tok(t.id)); toks.addAll(MxText.tok(t.owner)); toks.addAll(MxText.tok(t.desc))
        for (n in t.notes) toks.addAll(MxText.tok(n.text))
        toks.addAll(MxText.tok(t.source?.optString("subject")))
        return MxText.Doc(t, toks)
    }

    /** list with words: ranked (vectorless) instead of all-words only. */
    fun rankedQuery(q: String = "", owner: String = "", status: String = "", dueBefore: String = "", overdue: Boolean = false): List<BoardTask> {
        if (q.isBlank()) return query("", owner, status, dueBefore, overdue)
        val pool = query("", owner, status, dueBefore, overdue)
        return MxText.vrank(q, pool.map { taskDoc(it) }).map { it.first }
    }

    private fun dt(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    fun line(t: BoardTask): String = "${t.id} [${STATUS[t.status]}] ${t.title} · owner: ${t.owner.ifEmpty { "—" }} · due: ${t.due.ifEmpty { "—" }} · updated ${dt(t.updated)}" +
        (if (t.notes.isNotEmpty()) " · ${t.notes.size} note(s)" else "") + (t.source?.let { " · from e-mail \"${it.optString("subject")}\"" } ?: "")

    fun detail(t: BoardTask): String = "${line(t)}\nCreated: ${dt(t.created)}" + (if (t.desc.isNotEmpty()) "\nDescription: ${t.desc}" else "") +
        (t.source?.optString("mailId")?.takeIf { it.isNotEmpty() }?.let { "\nSource e-mail id: $it" } ?: "") +
        "\nNotes:" + if (t.notes.isNotEmpty()) "\n" + t.notes.joinToString("\n") { "  - ${it.id} (${it.by}, ${dt(it.at)}): ${it.text}" } else " none"

    fun overdueCount(): Int { val today = MxText.today(); return synchronized(lock) { load(); tasks.count { overdue(it, today) } } }

    // ---------------------------------------------------------------- JSON for the WebView UI

    fun taskJson(t: BoardTask, today: String = MxText.today()): JSONObject {
        val soonLimit = MxText.dayPlus(2)
        val od = overdue(t, today)
        return t.toJson().put("overdue", od).put("soon", t.due.isNotEmpty() && !od && t.due <= soonLimit && t.status != "done")
    }

    /** The board for the Tasks view: filters q/owner/status(open|all|todo|doing|done)/sort(manual|due|owner|updated). */
    fun uiState(q: String = "", owner: String = "", status: String = "open", sort: String = MailPlusCfg.taskSort): JSONObject {
        load()
        var list = query(q, owner, if (status == "all") "" else status)
        val dueKey = { t: BoardTask -> t.due.ifEmpty { "9999" } }
        list = when (sort) {
            "due" -> list.sortedWith(compareBy<BoardTask> { dueKey(it) }.thenBy { it.order })
            "owner" -> list.sortedWith(Comparator { a, b -> trColl.compare(a.owner.ifEmpty { "￿" }, b.owner.ifEmpty { "￿" }).let { c -> if (c != 0) c else dueKey(a).compareTo(dueKey(b)) } })
            "updated" -> list.sortedByDescending { it.updated }
            else -> list
        }
        val today = MxText.today()
        val all = synchronized(lock) { tasks.toList() }
        val counts = JSONObject().put("todo", all.count { it.status == "todo" }).put("doing", all.count { it.status == "doing" }).put("done", all.count { it.status == "done" }).put("all", all.size)
        val ppl = JSONArray(synchronized(lock) { people.toList() }.map { p -> JSONObject().put("name", p).put("open", all.count { it.owner == p && it.status != "done" }) })
        return JSONObject().put("tasks", JSONArray(list.map { taskJson(it, today) })).put("people", ppl).put("counts", counts)
            .put("overdue", all.count { overdue(it, today) }).put("today", today).put("sort", sort)
            .put("manual", sort == "manual" && q.isEmpty() && owner.isEmpty())
    }

    /** Bridge methods "tasks.*" for the Tasks view; returns the new state (or the requested object). */
    fun dispatch(m: String, a: JSONObject): Any? {
        fun state() = uiState(a.str("q"), a.str("owner"), a.str("status").ifEmpty { "open" }, a.str("sort").ifEmpty { MailPlusCfg.taskSort })
        fun opt(k: String): String? = if (a.has(k) && !a.isNull(k)) a.optString(k) else null
        when (m) {
            "tasks.state" -> return state()
            "tasks.get" -> return taskJson(get(a.str("id")))
            "tasks.add" -> {
                val t = add(a.optString("title"), a.optString("desc"), a.optString("owner"), a.optString("due"), a.optString("status", "todo"), a.optJSONObject("source"), "user")
                a.str("note").takeIf { it.isNotEmpty() }?.let { addNote(t.id, it, "user") }
                return taskJson(t)
            }
            "tasks.update" -> return taskJson(update(a.str("id"), opt("title"), opt("desc"), opt("owner"), opt("due"), opt("status"), "user"))
            "tasks.note.add" -> { addNote(a.str("id"), a.optString("text"), "user"); return taskJson(get(a.str("id"))) }
            "tasks.note.edit" -> { editNote(a.str("id"), a.str("noteId"), a.optString("text"), "user"); return taskJson(get(a.str("id"))) }
            "tasks.note.delete" -> { delNote(a.str("id"), a.str("noteId")); return taskJson(get(a.str("id"))) }
            "tasks.delete" -> { remove(a.str("id")); return state() }
            "tasks.move" -> { if (a.has("index")) moveTo(a.str("id"), a.optInt("index")) else moveBefore(a.str("id"), a.str("before")); return state() }
            "tasks.people.add" -> { addPerson(a.str("name")); return state() }
            "tasks.people.remove" -> { removePerson(a.str("name")); return state() }
            "tasks.sort" -> { MailPlusCfg.taskSort = a.str("sort").ifEmpty { "manual" }; return state() }
            "tasks.overdue" -> return overdueCount()
        }
        throw IllegalArgumentException("unknown method $m")
    }

    // ---------------------------------------------------------------- agent tool

    /** One approval card per changing action (WorkOK.task); null = approved. */
    private fun approval(a: JSONObject, ctx: ToolCtx): String? {
        val act = a.str("action")
        val st = { x: String? -> if (x.isNullOrEmpty()) x else STATUS[x] ?: x }
        val t = if (a.str("id").isNotEmpty()) find(a.str("id")) else null
        val title: String
        val f = ArrayList<Triple<String, String?, String?>>()
        fun opt(k: String): String? = if (a.has(k) && !a.isNull(k)) a.optString(k) else null
        when (act) {
            "add" -> {
                title = Y.t("New task", "Yeni görev") + ": ${a.str("title")}"
                f.add(Triple(Y.t("Title", "Başlık"), a.str("title"), null)); f.add(Triple(Y.t("Owner", "Sorumlu"), a.str("owner"), null)); f.add(Triple(Y.t("Due", "Son tarih"), a.str("due"), null))
                f.add(Triple(Y.t("Status", "Durum"), st(a.str("status").ifEmpty { "todo" }.let { normStatus(it) ?: it }), null)); f.add(Triple(Y.t("Description", "Açıklama"), a.str("description").take(600), null))
                f.add(Triple(Y.t("From e-mail", "Kaynak e-posta"), if (a.str("source_mail_id").isNotEmpty()) "✓" else "", null))
            }
            "update", "close", "reopen" -> {
                if (t == null) return null
                title = Y.t("Update task", "Görevi güncelle") + " ${t.id}: ${t.title}"
                val status = when (act) { "close" -> "done"; "reopen" -> "todo"; else -> opt("status") }
                if (act == "update" && opt("title") != null) f.add(Triple(Y.t("Title", "Başlık"), opt("title"), t.title))
                if (status != null) f.add(Triple(Y.t("Status", "Durum"), st(normStatus(status) ?: status), st(t.status)))
                if (act == "update" && opt("owner") != null) f.add(Triple(Y.t("Owner", "Sorumlu"), opt("owner"), t.owner))
                if (act == "update" && opt("due") != null) f.add(Triple(Y.t("Due", "Son tarih"), opt("due"), t.due))
                if (act == "update" && opt("description") != null) f.add(Triple(Y.t("Description", "Açıklama"), opt("description")!!.take(600), t.desc.take(300)))
            }
            "add_note", "delete_note", "edit_note" -> {
                if (t == null) return null
                title = (when (act) { "add_note" -> Y.t("Add a note to", "Not ekle:"); "edit_note" -> Y.t("Edit a note on", "Notu düzenle:"); else -> Y.t("Delete a note from", "Not sil:") }) + " ${t.id} ${t.title}"
                val old = if (act != "add_note") t.notes.firstOrNull { it.id == a.str("note_id") }?.text else null
                f.add(Triple(Y.t("Task", "Görev"), "${t.id} · ${t.title}", null))
                f.add(Triple(Y.t("Note", "Not"), if (act == "delete_note") "" else a.str("note").take(800), old?.take(400)))
            }
            "delete" -> {
                if (t == null) return null
                title = Y.t("Delete task", "Görevi sil") + " ${t.id}: ${t.title}"
                f.add(Triple(Y.t("Task", "Görev"), "${t.id} · ${t.title}", null)); f.add(Triple(Y.t("Owner", "Sorumlu"), t.owner, null))
                f.add(Triple(Y.t("Status", "Durum"), st(t.status), null)); f.add(Triple(Y.t("Notes", "Notlar"), t.notes.size.toString(), null))
            }
            else -> return null
        }
        return if (WorkOk.ask(ctx, title, f, danger = act == "delete")) null else WorkOk.denied(ctx, title)
    }

    fun register() {
        Tools.add(Tool(
            "task_board",
            "The user's work task board (the Tasks tab). Every task has an id (T1, T2…), title, description, owner (a person), due date (YYYY-MM-DD), status (todo | doing = in progress | done) and notes. Actions: list (filters: query words, owner, status todo|doing|done|open, due_before, overdue), get (id), add (title, description, owner, due, status), update (id + any of title, description, owner, due, status), close (id → done), reopen (id), add_note (id, note — to refer to an e-mail write [subject](mail:<id>), to another task [title](task:T3); they show as clickable links), edit_note (id, note_id, note — rewrite an existing note), delete_note (id, note_id), people (list people with their open tasks), add_person (name), delete (id; the user confirms). Unknown owner names are added as people automatically. Use this for any question about the user's tasks, deadlines or who is doing what. IMPORTANT: whenever you mention a task in your reply to the user, write it as a markdown link with its title and id, exactly like [Q3 budget review](task:T3) — the user clicks it to open that task. Never paste raw ids or long links.",
            P.props(
                "action" to P.s("list | get | add | update | close | reopen | add_note | edit_note | delete_note | people | add_person | delete", "list", "get", "add", "update", "close", "reopen", "add_note", "edit_note", "delete_note", "people", "add_person", "delete"),
                "id" to P.s("Task id, e.g. T3"), "query" to P.s("For list: words to look for in titles, descriptions, notes"),
                "owner" to P.s("Owner name (filter for list; value for add/update; empty string removes the owner on update)"), "status" to P.s("todo | doing | done (open = not done, for list)"),
                "due_before" to P.s("For list: YYYY-MM-DD"), "overdue" to P.b("For list: only overdue open tasks"),
                "title" to P.s("For add/update"), "description" to P.s("For add/update"), "due" to P.s("For add/update: YYYY-MM-DD (empty string clears)"),
                "note" to P.s("For add_note"), "note_id" to P.s("For delete_note"), "name" to P.s("For add_person"), "source_mail_id" to P.s("For add: the e-mail this task comes from")
            ),
            listOf("action")
        ) { a, ctx -> tool(a, ctx) })
    }

    private fun tool(a: JSONObject, ctx: ToolCtx): String {
        val act = a.str("action")
        fun opt(k: String): String? = if (a.has(k) && !a.isNull(k)) a.optString(k) else null
        if (act in setOf("add", "update", "close", "reopen", "add_note", "edit_note", "delete_note", "delete")) approval(a, ctx)?.let { return it }
        when (act) {
            "list" -> {
                val l = rankedQuery(a.str("query"), a.str("owner"), a.str("status"), a.str("due_before"), a.optBoolean("overdue"))
                val today = MxText.today()
                return if (l.isNotEmpty()) "${l.size} task(s) (today is $today):\n" + l.joinToString("\n") { "- " + line(it) + if (overdue(it, today)) " · OVERDUE" else "" }
                else "No tasks match." + if (load().tasks.isEmpty()) " The board is empty." else ""
            }
            "get" -> return detail(get(a.str("id")))
            "add" -> {
                var source: JSONObject? = null
                val mid = a.str("source_mail_id")
                if (mid.isNotEmpty()) source = try { JSONObject().put("mailId", mid).put("subject", Mail.use(ctx.cancel) { it.get(mid, ctx.cancel) }.h.subject) } catch (e: CancelledException) { throw e } catch (e: Exception) { JSONObject().put("mailId", mid).put("subject", "e-mail") }
                return "Created: " + line(add(a.str("title"), a.optString("description", ""), a.optString("owner", ""), a.optString("due", ""), a.optString("status", "todo"), source, "yaver"))
            }
            "update" -> {
                if (listOf("title", "description", "owner", "due", "status").none { opt(it) != null }) throw IllegalArgumentException("nothing to change")
                return "Updated: " + line(update(a.str("id"), opt("title"), opt("description"), opt("owner"), opt("due"), opt("status"), "yaver"))
            }
            "close" -> return "Closed: " + line(update(a.str("id"), status = "done", by = "yaver"))
            "reopen" -> return "Reopened: " + line(update(a.str("id"), status = "todo", by = "yaver"))
            "add_note" -> { val n = addNote(a.str("id"), a.optString("note"), "yaver"); return "Note ${n.id} added to ${a.str("id")}." }
            "delete_note" -> { delNote(a.str("id"), a.str("note_id")); return "Note ${a.str("note_id")} deleted from ${a.str("id")}." }
            "edit_note" -> {
                if (a.str("note_id").isEmpty()) throw IllegalArgumentException("note_id is required (see action get)")
                editNote(a.str("id"), a.str("note_id"), a.optString("note"), "yaver"); return "Note ${a.str("note_id")} on ${a.str("id")} updated."
            }
            "people" -> {
                load()
                val (pp, tt) = synchronized(lock) { people.toList() to tasks.toList() }
                return if (pp.isEmpty()) "No people on the board yet." else pp.joinToString("\n") { p ->
                    val mine = tt.filter { it.owner == p && it.status != "done" }
                    "- $p: ${mine.size} open" + if (mine.isNotEmpty()) " (" + mine.joinToString(", ") { it.id + if (it.due.isNotEmpty()) " due " + it.due else "" } + ")" else ""
                }
            }
            "add_person" -> return "Person: " + addPerson(a.str("name"))
            "delete" -> { val t = get(a.str("id")); remove(t.id); return "Deleted ${t.id}." }
        }
        throw IllegalArgumentException("unknown action")
    }
}
