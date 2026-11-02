package com.plusai.yaver.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One call after anything adds, changes or deletes workspace files: re-indexes documents and syncs connected folders. */
object WsChange {
    fun notify(path: String? = null) {
        try { Rag.indexLater(path) } catch (e: Throwable) { Diag.e("wschange", "index", e) }
        try { Folders.changed(path) } catch (e: Throwable) { Diag.e("wschange", "folders", e) }
    }
}

/**
 * Folders on the phone (or in a cloud provider's documents app) that the user picks with the system picker
 * (ACTION_OPEN_DOCUMENT_TREE, persisted permission), synced two-way with workspace/folder/<name>.
 * Same rules as the original's FolderSync: per-file baseline, "keep both" on conflicts (the folder's version keeps the
 * name, yaver's becomes "… (conflict <time>)" on both sides), files deleted from the folder side go to
 * <folder>/.yaver-trash/<date>/, and many deletions at once wait for the user's OK.
 */
object Folders {
    private val SKIP_NAMES = Regex("^(~\\$|\\.~lock\\.|\\.DS_Store$|Thumbs\\.db$|desktop\\.ini$|\\.yaver-trash$)|\\.(tmp|crdownload|part)$", RegexOption.IGNORE_CASE)
    private const val MAX_BYTES = 50L * 1024 * 1024
    const val REQ_PICK = 71

    class Base(val m: Long, val s: Long, val lm: Long, val fs: Long)
    class Stats { var toDisk = 0; var fromDisk = 0; var delHere = 0; var delThere = 0; var skipped = 0; val conflicts = ArrayList<String>() }
    class Pending(val n: Int, val here: List<String>, val there: List<String>)

    class Conn(val uri: Uri, var name: String, val dir: String) {
        val base = HashMap<String, Base>()
        var state = "on"            // on | needs
        var busy = false
        var lastSync = 0L
        var lastErr: String? = null
        var lastStats: Stats? = null
        val conflicts = ArrayList<String>()
        var pending: Pending? = null
        val local: File get() = File(Workspace.root, dir)
    }

    private class DiskFile(val docId: String, val lm: Long, val size: Long)

    /** UI hook: (type, data). Types: "folders" (state changed, data = {folders: state()}), "folders.note" (data = {text}). */
    @Volatile var listener: ((String, JSONObject) -> Unit)? = null

    private val list = ArrayList<Conn>()
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "folder-sync").apply { isDaemon = true } }
    private var timer: ScheduledFuture<*>? = null
    private var periodic: ScheduledFuture<*>? = null
    @Volatile private var applying = false
    @Volatile private var loaded = false
    private val cr get() = Y.app.contentResolver
    private val store: File get() = File(Y.app.filesDir, "harness/folders.json").apply { parentFile?.mkdirs() }

    /* ---- persistence ---- */
    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!store.isFile) return
            val a = JSONObject(store.readText()).optJSONArray("folders") ?: return
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val c = Conn(Uri.parse(o.getString("uri")), o.optString("name"), o.getString("dir"))
                c.lastSync = o.optLong("lastSync")
                o.optJSONObject("base")?.let { b -> for (k in b.keys()) b.optJSONObject(k)?.let { x -> c.base[k] = Base(x.optLong("m"), x.optLong("s"), x.optLong("lm"), x.optLong("fs")) } }
                o.optJSONArray("conflicts")?.let { x -> for (k in 0 until x.length()) c.conflicts.add(x.optString(k)) }
                list.add(c)
            }
        } catch (e: Throwable) { Diag.e("folders", "load", e) }
    }

    @Synchronized private fun save() {
        val a = JSONArray()
        for (c in list) {
            val b = JSONObject(); for ((k, v) in c.base) b.put(k, JSONObject().put("m", v.m).put("s", v.s).put("lm", v.lm).put("fs", v.fs))
            a.put(JSONObject().put("uri", c.uri.toString()).put("name", c.name).put("dir", c.dir).put("lastSync", c.lastSync).put("base", b).put("conflicts", JSONArray(c.conflicts)))
        }
        try { store.writeText(JSONObject().put("folders", a).toString()) } catch (e: Throwable) { Diag.e("folders", "save", e) }
    }

    private fun emit() { listener?.invoke("folders", JSONObject().put("folders", state())) }
    private fun note(text: String) { listener?.invoke("folders.note", JSONObject().put("text", text)) }

    fun folders(): List<Conn> { load(); return synchronized(this) { list.toList() } }
    fun find(key: String): Conn? = folders().let { l -> l.firstOrNull { it.name == key || it.dir == key || it.uri.toString() == key } ?: l.firstOrNull { TrText.fold(it.name) == TrText.fold(key) } ?: key.toIntOrNull()?.let { l.getOrNull(it - 1) } }

    private fun permitted(u: Uri) = try { cr.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission } } catch (e: Throwable) { false }

    /** For the Files screen: [{name, path, state, busy, lastSync, lastErr, stats, conflicts, pendingDeletes}]. */
    fun state(): JSONArray {
        val a = JSONArray()
        for (c in folders()) {
            val st = c.lastStats
            a.put(JSONObject().put("name", c.name).put("path", c.dir).put("uri", c.uri.toString()).put("state", c.state).put("busy", c.busy).put("lastSync", c.lastSync)
                .put("lastErr", c.lastErr ?: "").put("conflicts", JSONArray(c.conflicts.take(6)))
                .put("stats", st?.let { JSONObject().put("toDisk", it.toDisk).put("fromDisk", it.fromDisk).put("deleted", it.delHere + it.delThere).put("conflicts", it.conflicts.size).put("skipped", it.skipped) } ?: JSONObject.NULL)
                .put("pendingDeletes", c.pending?.let { p -> JSONObject().put("n", p.n).put("here", JSONArray(p.here.take(5))).put("there", JSONArray(p.there.take(5))) } ?: JSONObject.NULL))
        }
        return a
    }

    /* ---- start / connect ---- */

    /** App start: check permissions and sync every folder once. */
    fun init() {
        exec.execute {
            for (c in folders()) c.state = if (permitted(c.uri)) "on" else "needs"
            emit()
            for (c in folders()) if (c.state == "on") syncNow(c, "start")
        }
    }

    /** The picker intent (MainActivity: startActivityForResult(Folders.pickIntent(), Folders.REQ_PICK)). */
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    /** After the picker returned [uri]: keeps the permission, connects (or reconnects) and syncs. Call off the UI thread is not needed. */
    fun add(uri: Uri, then: ((String) -> Unit)? = null) {
        exec.execute {
            val msg = try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val rootId = DocumentsContract.getTreeDocumentId(uri)
                val name = queryName(uri, rootId) ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "folder" }
                var c = folders().firstOrNull { it.uri == uri }
                val again = c != null
                if (c == null) {
                    var d = "folder/" + Workspace.safeName(name); var k = 2
                    while (folders().any { it.dir == d }) d = "folder/" + Workspace.safeName(name) + " ($k)".also { k++ }
                    c = Conn(uri, name, d)
                    synchronized(this) { list.add(c) }
                } else c.name = name
                c.state = "on"; c.lastErr = null
                save(); emit()
                syncNow(c, if (again) "reconnect" else "connect")
                val st = c.lastStats
                if (c.lastErr != null && st == null) Y.t("Folder “$name” connected, but syncing failed: ${c.lastErr}", "“$name” klasörü bağlandı ama senkron başarısız: ${c.lastErr}")
                else Y.t("Folder “$name” synced: ${st?.toDisk ?: 0} saved to the folder, ${st?.fromDisk ?: 0} loaded from it${if ((st?.conflicts?.size ?: 0) > 0) ", ${st!!.conflicts.size} conflicts kept" else ""}. Its files are in ${c.dir}.",
                    "“$name” klasörü senkron edildi: ${st?.toDisk ?: 0} dosya klasöre yazıldı, ${st?.fromDisk ?: 0} dosya alındı${if ((st?.conflicts?.size ?: 0) > 0) ", ${st!!.conflicts.size} çakışmada iki sürüm de tutuldu" else ""}. Dosyaları: ${c.dir}.")
            } catch (e: Throwable) {
                Diag.e("folders", "add", e)
                Y.t("Could not connect the folder: ", "Klasör bağlanamadı: ") + (e.message ?: e.toString())
            }
            then?.invoke(msg)
        }
    }

    /** Stops syncing; files stay where they are, in the folder and in the workspace. */
    fun remove(key: String): Boolean {
        val c = find(key) ?: return false
        try { cr.releasePersistableUriPermission(c.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) {}
        synchronized(this) { list.remove(c) }
        save(); emit()
        return true
    }

    /* ---- scheduling ---- */
    fun schedule(ms: Long) {
        timer?.cancel(false)
        timer = exec.schedule({ for (c in folders()) if (c.state == "on") syncNow(c, "change") }, ms, TimeUnit.MILLISECONDS)
    }

    /** A workspace path changed (null = unknown): sync soon when it is inside a connected folder. */
    fun changed(path: String?) {
        if (applying) return
        val l = folders(); if (l.isEmpty()) return
        if (path == null || l.any { c -> path.trimStart('/').removePrefix("workspace/").let { it == c.dir || it.startsWith(c.dir + "/") || c.dir.startsWith("$it/") } }) schedule(1500)
    }

    /** MainActivity.onResume: sync soon and every 30 s while the app is in front. */
    fun onForeground() {
        if (folders().isEmpty()) return
        schedule(300)
        periodic?.cancel(false)
        periodic = exec.scheduleWithFixedDelay({ for (c in folders()) if (c.state == "on") syncNow(c, "interval") }, 30, 30, TimeUnit.SECONDS)
    }

    /** MainActivity.onPause. */
    fun onBackground() { periodic?.cancel(false); periodic = null }

    /** Sync one folder (or all when [key] is null) and wait. Returns a summary. */
    fun sync(key: String? = null, why: String = "manual"): String {
        val targets = if (key.isNullOrBlank()) folders() else listOfNotNull(find(key))
        if (targets.isEmpty()) return if (key.isNullOrBlank()) "No folder is connected." else "No connected folder \"$key\"."
        val out = ArrayList<String>()
        for (c in targets) {
            exec.submit { syncNow(c, why) }.get()
            out.add(summary(c))
        }
        return out.joinToString("\n")
    }

    fun summary(c: Conn): String {
        val st = c.lastStats
        return "• ${c.name} → ${c.dir} — " + (if (c.state == "needs") "not connected (Android no longer allows access; the user must pick the folder again in Files)" else
            (if (c.lastSync > 0) "synced ${java.text.DateFormat.getDateTimeInstance().format(Date(c.lastSync))}" else "not synced yet") +
            (st?.let { "; last sync: ${it.toDisk} to the folder, ${it.fromDisk} from it" + (if (it.delHere + it.delThere > 0) ", ${it.delHere + it.delThere} deleted" else "") + (if (it.skipped > 0) ", ${it.skipped} too large (>50 MB) skipped" else "") + (if (it.conflicts.isNotEmpty()) ", ${it.conflicts.size} conflicts kept" else "") } ?: "")) +
            (c.lastErr?.let { "\n  last problem: $it" } ?: "") +
            (if (c.conflicts.isNotEmpty()) "\n  recent conflicts: ${c.conflicts.take(6).joinToString(", ") { it.substringAfterLast('/') }}" else "") +
            (c.pending?.let { "\n  waiting for the user: ${it.n} files look deleted (${it.here.size} gone from the folder, ${it.there.size} deleted in yaver) — nothing deleted yet" } ?: "")
    }

    /* ---- DocumentsContract helpers ---- */
    private fun docUri(c: Conn, id: String) = DocumentsContract.buildDocumentUriUsingTree(c.uri, id)

    private fun queryName(tree: Uri, id: String): String? = try {
        cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, id), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Throwable) { null }

    private class Child(val id: String, val name: String, val dir: Boolean, val lm: Long, val size: Long)

    private fun children(c: Conn, parentId: String): List<Child> {
        val out = ArrayList<Child>()
        val q = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(c.uri, parentId),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)
            ?: throw java.io.FileNotFoundException("folder not found")
        q.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: continue
                out.add(Child(cur.getString(0), name, cur.getString(2) == Document.MIME_TYPE_DIR, if (cur.isNull(3)) 0 else cur.getLong(3), if (cur.isNull(4)) 0 else cur.getLong(4)))
            }
        }
        return out
    }

    private fun stat(c: Conn, id: String): Pair<Long, Long> = cr.query(docUri(c, id), arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)?.use {
        if (it.moveToFirst()) (if (it.isNull(0)) 0L else it.getLong(0)) to (if (it.isNull(1)) 0L else it.getLong(1)) else 0L to 0L
    } ?: (0L to 0L)

    /** Every file in the picked folder: relative path → id, lastModified, size; [dirs] gets relative dir → id. */
    private fun scan(c: Conn, dirs: MutableMap<String, String>): Map<String, DiskFile> {
        val out = HashMap<String, DiskFile>()
        val rootId = DocumentsContract.getTreeDocumentId(c.uri)
        dirs[""] = rootId
        fun walk(id: String, rel: String, depth: Int) {
            for (k in children(c, id)) {
                if (SKIP_NAMES.containsMatchIn(k.name)) continue
                val r = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                if (k.dir) { dirs[r] = k.id; if (depth < 30) walk(k.id, r, depth + 1) } else out[r] = DiskFile(k.id, k.lm, k.size)
            }
        }
        walk(rootId, "", 0)
        return out
    }

    private fun ensureDir(c: Conn, dirs: MutableMap<String, String>, rel: String): String {
        if (rel.isEmpty()) return dirs[""]!!
        dirs[rel]?.let { return it }
        val parent = ensureDir(c, dirs, rel.substringBeforeLast('/', ""))
        val name = rel.substringAfterLast('/')
        val id = children(c, parent).firstOrNull { it.dir && it.name == name }?.id
            ?: DocumentsContract.createDocument(cr, docUri(c, parent), Document.MIME_TYPE_DIR, name)?.let { DocumentsContract.getDocumentId(it) }
            ?: throw java.io.IOException("could not create the folder $rel")
        dirs[rel] = id
        return id
    }

    /** Writes bytes from [src] to the folder at [rel]; returns (id, lastModified, size). */
    private fun writeDisk(c: Conn, dirs: MutableMap<String, String>, disk: Map<String, DiskFile>, rel: String, src: () -> InputStream): Triple<String, Long, Long> {
        val id = disk[rel]?.docId ?: run {
            val parent = ensureDir(c, dirs, rel.substringBeforeLast('/', ""))
            val name = rel.substringAfterLast('/')
            children(c, parent).firstOrNull { !it.dir && it.name == name }?.id
                ?: DocumentsContract.createDocument(cr, docUri(c, parent), "application/octet-stream", name)?.let { DocumentsContract.getDocumentId(it) }
                ?: throw java.io.IOException("could not create $rel in the folder")
        }
        val u = docUri(c, id)
        val os = try { cr.openOutputStream(u, "wt") } catch (e: Throwable) { null } ?: cr.openOutputStream(u, "w") ?: throw java.io.IOException("cannot write $rel")
        os.use { o -> src().use { it.copyTo(o, 65536) } }
        val (lm, size) = stat(c, id)
        return Triple(id, lm, size)
    }

    /** Never delete outright: copy into <folder>/.yaver-trash/<date>/… then remove. */
    private fun trashDisk(c: Conn, dirs: MutableMap<String, String>, rel: String, f: DiskFile) {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val t = ".yaver-trash/$stamp/$rel"
        writeDisk(c, dirs, emptyMap(), t) { cr.openInputStream(docUri(c, f.docId)) ?: throw java.io.IOException("cannot read $rel") }
        DocumentsContract.deleteDocument(cr, docUri(c, f.docId))
    }

    private fun same(a: InputStream, b: InputStream): Boolean {
        a.use { x -> b.use { y ->
            val ba = ByteArray(65536); val bb = ByteArray(65536)
            while (true) {
                var na = 0; while (na < ba.size) { val k = x.read(ba, na, ba.size - na); if (k < 0) break; na += k }
                var nb = 0; while (nb < bb.size) { val k = y.read(bb, nb, bb.size - nb); if (k < 0) break; nb += k }
                if (na != nb) return false
                for (i in 0 until na) if (ba[i] != bb[i]) return false
                if (na < ba.size) return true
            }
        } }
    }

    private fun conflictName(rel: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        val m = Regex("^(.*?)(\\.[^./]+)?$").find(rel)!!
        return "${m.groupValues[1]} (conflict $stamp)${m.groupValues[2]}"
    }

    private fun localFiles(c: Conn): Map<String, File> {
        val root = c.local.apply { mkdirs() }
        return root.walkTopDown().onEnter { it == root || !SKIP_NAMES.containsMatchIn(it.name) }
            .filter { it.isFile && !SKIP_NAMES.containsMatchIn(it.name) }.associateBy { it.relativeTo(root).invariantSeparatorsPath }
    }

    /* ---- the two-way sync (runs on the folder-sync thread) ---- */
    private fun syncNow(c: Conn, why: String) {
        if (c.state != "on") return
        if (!permitted(c.uri)) { c.state = "needs"; c.lastErr = "Android no longer allows access to the folder — connect it again"; emit(); return }
        c.busy = true; c.lastErr = null; emit()
        val stats = Stats()
        try {
            val dirs = HashMap<String, String>()
            val disk = scan(c, dirs)
            val here = localFiles(c)
            val all = (disk.keys + here.keys).toSortedSet()
            val plan = ArrayList<Pair<String, String>>()
            for (p in all) {
                val b = c.base[p]; val v = here[p]; val d = disk[p]
                val vCh = v != null && (b == null || v.lastModified() != b.m || v.length() != b.s)
                val dCh = d != null && (b == null || d.lm != b.lm || d.size != b.fs)
                if (v != null && d != null) { if (b == null) plan.add("both-new" to p) else if (vCh && dCh) plan.add("conflict" to p) else if (vCh) plan.add("up" to p) else if (dCh) plan.add("down" to p) }
                else if (v != null) { if (b != null && !vCh) plan.add("del-here" to p) else plan.add("up" to p) }
                else if (d != null) { if (b != null && !dCh) plan.add("del-there" to p) else plan.add("down" to p) }
            }
            // many deletions at once → ask first
            val dels = plan.filter { it.first.startsWith("del-") }
            val mirrored = c.base.size
            if (dels.size >= 25 || (mirrored >= 8 && dels.size > mirrored * 0.3)) {
                if (c.pending?.n != dels.size) {
                    c.pending = Pending(dels.size, dels.filter { it.first == "del-here" }.map { it.second }, dels.filter { it.first == "del-there" }.map { it.second })
                    val pd = c.pending!!
                    note(Y.t("${pd.n} files look deleted in “${c.name}”. ${if (pd.here.isNotEmpty()) "${pd.here.size} file(s) are gone from the folder (e.g. ${pd.here.take(3).joinToString(", ") { it.substringAfterLast('/') }}). " else ""}${if (pd.there.isNotEmpty()) "${pd.there.size} file(s) were deleted in yaver. " else ""}That is a lot at once, so nothing was deleted yet.",
                        "“${c.name}” klasöründe ${pd.n} dosya silinmiş görünüyor. ${if (pd.here.isNotEmpty()) "${pd.here.size} dosya klasörden kalkmış (ör. ${pd.here.take(3).joinToString(", ") { it.substringAfterLast('/') }}). " else ""}${if (pd.there.isNotEmpty()) "${pd.there.size} dosya yaver'de silinmiş. " else ""}Bir seferde çok fazla olduğu için henüz hiçbir şey silinmedi."))
                }
                plan.removeAll { it.first.startsWith("del-") }
            }
            applying = true
            for ((act, p) in plan) {
                try {
                    val d = disk[p]
                    val lf = File(c.local, p)
                    fun baseFromDisk(lm: Long, fs: Long) { c.base[p] = Base(lf.lastModified(), lf.length(), lm, fs) }
                    when (act) {
                        "up" -> { val r = writeDisk(c, dirs, disk, p) { lf.inputStream() }; baseFromDisk(r.second, r.third); stats.toDisk++ }
                        "down" -> {
                            if (d!!.size > MAX_BYTES) { stats.skipped++; continue }
                            download(c, d, lf); baseFromDisk(d.lm, d.size); stats.fromDisk++
                        }
                        "both-new", "conflict" -> {
                            d!!
                            if (same(cr.openInputStream(docUri(c, d.docId)) ?: throw java.io.IOException("cannot read $p"), lf.inputStream())) { baseFromDisk(d.lm, d.size); continue }
                            if (act == "both-new" && lf.length() == 0L) { download(c, d, lf); baseFromDisk(d.lm, d.size); stats.fromDisk++; continue }
                            if (d.size > MAX_BYTES) { stats.skipped++; continue }
                            // keep both: the folder's version stays under the name, yaver's version becomes "(conflict …)" on both sides
                            val cp = conflictName(p); val cf = File(c.local, cp)
                            lf.copyTo(cf, true)
                            download(c, d, lf); baseFromDisk(d.lm, d.size)
                            val r = writeDisk(c, dirs, disk, cp) { cf.inputStream() }
                            c.base[cp] = Base(cf.lastModified(), cf.length(), r.second, r.third)
                            stats.conflicts.add("${c.dir}/$p")
                        }
                        "del-here" -> { lf.delete(); c.base.remove(p); stats.delHere++ }
                        "del-there" -> { trashDisk(c, dirs, p, d!!); c.base.remove(p); stats.delThere++ }
                    }
                } catch (e: Throwable) { c.lastErr = "$p: ${e.message ?: e}" }
            }
            applying = false
            val planned = plan.map { it.second }.toSet()
            c.base.keys.toList().forEach { p -> if (!File(c.local, p).exists() && !disk.containsKey(p) && p !in planned) c.base.remove(p) }
            c.lastSync = System.currentTimeMillis(); c.lastStats = stats
            if (stats.conflicts.isNotEmpty()) {
                c.conflicts.addAll(0, stats.conflicts); while (c.conflicts.size > 30) c.conflicts.removeAt(c.conflicts.size - 1)
                val names = stats.conflicts.joinToString(", ") { it.substringAfterLast('/') }
                note(Y.t("📁 ${stats.conflicts.size} file${if (stats.conflicts.size == 1) " was" else "s were"} changed both here and in the folder — kept both versions: $names (yaver's version is saved as “… (conflict …)”).",
                    "📁 ${stats.conflicts.size} dosya hem burada hem klasörde değişmiş — iki sürüm de tutuldu: $names (yaver'in sürümü “… (conflict …)” adıyla kaydedildi)."))
            }
            if (stats.fromDisk > 0 || stats.delHere > 0 || stats.conflicts.isNotEmpty()) Rag.indexLater()
        } catch (e: Throwable) {
            c.lastErr = e.message ?: e.toString()
            if (e is java.io.FileNotFoundException || e is SecurityException) { c.state = "needs"; c.lastErr = "The folder was moved or deleted, or access was withdrawn — connect it again" }
            Diag.e("folders", "sync ${c.name} ($why)", e)
        } finally {
            applying = false; c.busy = false
            save(); emit()
        }
    }

    private fun download(c: Conn, d: DiskFile, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, to.name + ".part")
        (cr.openInputStream(docUri(c, d.docId)) ?: throw java.io.IOException("cannot read ${to.name}")).use { i -> tmp.outputStream().use { i.copyTo(it, 65536) } }
        if (to.exists()) to.delete()
        if (!tmp.renameTo(to)) { tmp.copyTo(to, true); tmp.delete() }
    }

    /** The user's answer to "many files look deleted": delete = true applies the deletions, false restores the files on both sides. */
    fun resolveDeletes(key: String, delete: Boolean): String {
        val c = find(key) ?: return "No connected folder \"$key\"."
        val pd = c.pending ?: return "Nothing is waiting."
        exec.submit {
            if (delete) {
                applying = true
                try {
                    for (p in pd.here) { try { File(c.local, p).delete() } catch (_: Throwable) {}; c.base.remove(p) }
                    val dirs = HashMap<String, String>()
                    val disk = try { scan(c, dirs) } catch (e: Throwable) { emptyMap() }
                    for (p in pd.there) { try { disk[p]?.let { trashDisk(c, dirs, p, it) } } catch (_: Throwable) {}; c.base.remove(p) }
                } finally { applying = false }
            } else { for (p in pd.here + pd.there) c.base.remove(p) }
            c.pending = null; save()
            if (!delete) syncNow(c, "restore") else { Rag.indexLater(); emit() }
        }.get()
        return if (delete) Y.t("Deleted (folder files went to .yaver-trash)", "Silindi (klasördeki dosyalar .yaver-trash'e taşındı)") else Y.t("Restored the files on both sides", "Dosyalar iki tarafta da geri getirildi")
    }

    /* ---- the agent's tool ---- */
    fun register() {
        Tools.add(Tool(
            "folder",
            "Folders the user connected from the phone (or a cloud drive's app) with Android's folder picker. Each one is mirrored two-way into the workspace under folder/<name>/ — read and write its files there with the normal file tools (read_file, read_document, write_file, search_documents…); changes are synced back automatically. Actions: list (connected folders, sync state, conflicts), sync (name, or all; waits for the result), deletes (name + apply=true to carry out deletions that are waiting for confirmation, apply=false to restore the files instead; the user confirms), disconnect (name; files stay on both sides; the user confirms). New folders can only be connected by the user (Files → Connect a folder).",
            P.props("action" to P.s("list | sync | deletes | disconnect", "list", "sync", "deletes", "disconnect"), "name" to P.s("Folder name (or its number from list)"), "apply" to P.b("For deletes: true = delete, false = restore")),
            listOf("action")
        ) { a, ctx ->
            val name = a.str("name")
            when (a.str("action")) {
                "sync" -> { ctx.progress(Y.t("Syncing folders…", "Klasörler senkron ediliyor…")); sync(name.ifEmpty { null }) }
                "deletes" -> {
                    val c = find(name) ?: folders().firstOrNull { it.pending != null } ?: return@Tool "No folder has deletions waiting."
                    val pd = c.pending ?: return@Tool "Nothing is waiting in “${c.name}”."
                    val del = a.optBoolean("apply", false)
                    if (!ctx.approve(if (del) Y.t("Delete files", "Dosyaları sil") + ": ${pd.n} (${c.name})" else Y.t("Restore files", "Dosyaları geri getir") + ": ${pd.n} (${c.name})",
                            listOf(Y.t("Gone from the folder", "Klasörden kalkan") to pd.here.take(8).joinToString(", ") { it.substringAfterLast('/') }, Y.t("Deleted in yaver", "yaver'de silinen") to pd.there.take(8).joinToString(", ") { it.substringAfterLast('/') }), always = true, danger = del))
                        return@Tool ctx.denied(if (del) "delete ${pd.n} files" else "restore ${pd.n} files")
                    resolveDeletes(c.name, del)
                }
                "disconnect" -> {
                    val c = find(name) ?: return@Tool "No connected folder \"$name\"."
                    if (!ctx.approve(Y.t("Stop syncing", "Senkronu durdur") + ": ${c.name}", listOf(Y.t("Files", "Dosyalar") to Y.t("stay where they are, in the folder and in ${c.dir}", "olduğu yerde kalır: klasörde ve ${c.dir} içinde")), always = true))
                        return@Tool ctx.denied("disconnect ${c.name}")
                    remove(c.name); "Disconnected “${c.name}”. Its files stay in ${c.dir} and in the folder."
                }
                else -> folders().let { l -> if (l.isEmpty()) "No folder is connected. The user can connect one in Files → Connect a folder (Android's folder picker)." else l.joinToString("\n") { summary(it) } }
            }
        })
    }
}
