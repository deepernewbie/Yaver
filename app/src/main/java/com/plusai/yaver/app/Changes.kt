package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Files changed per turn, with diff and revert (port of the original's Changes, all.js 2890-2920).
 *
 * The original's disk kept a journal of every write. Here files are written by many paths (tools, the terminal,
 * Python, archives, downloads), so the turn compares two scans instead: at the start of a turn every tracked file
 * (/workspace and /.yaver/skills) is listed with its size and time, and a copy of its bytes is kept in a small
 * content store (files up to [MAX_BLOB]; a copy is made once per version, so an unchanged disk costs only a listing).
 * At the end the scan is repeated: created / modified / deleted files with line counts; the journal entry keeps the
 * before and after copies, so "View diff" and "Revert all" still work after a restart.
 */
object Changes {
    @Volatile var dir: () -> File = { File(Y.app.filesDir, "changes") }
    const val MAX_BLOB = 1_000_000L
    const val MAX_STORE = 96L * 1024 * 1024
    private const val KEEP_JOURNALS = 40
    private const val MAX_CELLS = 2_500_000L

    class Ent(val size: Long, val mtime: Long)

    private val open = java.util.concurrent.ConcurrentHashMap<String, Map<String, Ent>>()
    private val lock = Any()

    private fun blobs() = File(dir(), "blobs").apply { mkdirs() }
    private fun journalFile() = File(dir().apply { mkdirs() }, "journal.json")

    /** Tracked areas: the user's files and the skills (as the original: everything outside ~ plus skills and memories). */
    private fun roots(): List<Pair<String, File>> = listOf(VPath.WS to VPath.workspaceDir(), VPath.SKILLS to VPath.skillsDir())

    private fun skip(vp: String) = vp.startsWith("${VPath.WS}/${Spill.DIR}/") || vp.contains("/.yaver-trash/") || vp.endsWith(".tmp")

    fun scan(): Map<String, Ent> {
        val out = HashMap<String, Ent>()
        for ((area, root) in roots()) {
            if (!root.isDirectory) continue
            val base = root.canonicalFile
            base.walkTopDown().onEnter { it.name != ".yaver-trash" }.filter { it.isFile }.forEach { f ->
                val rel = f.canonicalFile.path.removePrefix(base.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
                val vp = "$area/$rel"
                if (!skip(vp)) out[vp] = Ent(f.length(), f.lastModified())
            }
        }
        return out
    }

    fun key(vp: String, e: Ent): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$vp|${e.size}|${e.mtime}".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun storeBytes(): Long = blobs().listFiles()?.sumOf { it.length() } ?: 0L

    /** Keeps a copy of the current bytes of [vp] (once per version). Returns its key, or null when it is too big. */
    private fun keep(vp: String, e: Ent, budget: LongArray): String? {
        if (e.size > MAX_BLOB) return null
        val k = key(vp, e)
        val b = File(blobs(), k)
        if (b.isFile) return k
        if (budget[0] + e.size > MAX_STORE) return null
        return try { VPath.file(vp).copyTo(b, true); budget[0] += e.size; k } catch (_: Exception) { null }
    }

    fun bytesOf(k: String?): ByteArray? = if (k == null) null else File(blobs(), k).takeIf { it.isFile }?.readBytes()

    /** The start of a turn: list the tracked files and keep copies of their current versions. */
    fun begin(convoId: String) {
        try {
            val m = scan()
            val budget = longArrayOf(storeBytes())
            for ((vp, e) in m) keep(vp, e, budget)
            open[convoId] = m
            gc(m)
        } catch (e: Throwable) { Diag.e("changes", "begin", e) }
    }

    fun isText(b: ByteArray?): Boolean {
        if (b == null) return false
        val n = minOf(b.size, 8000)
        for (i in 0 until n) if (b[i] == 0.toByte()) return false
        return try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)); true } catch (_: Exception) { false }
    }

    /** The end of a turn: what changed since [begin], stored as a journal entry. Null when nothing changed. */
    fun end(convoId: String): JSONObject? {
        val before = open.remove(convoId) ?: return null
        return try { record(convoId, before, scan()) } catch (e: Throwable) { Diag.e("changes", "end", e); null }
    }

    fun record(convoId: String, before: Map<String, Ent>, after: Map<String, Ent>): JSONObject? {
        val budget = longArrayOf(storeBytes())
        val files = JSONArray()
        var add = 0; var del = 0
        for (vp in (before.keys + after.keys).toSortedSet()) {
            val b = before[vp]; val a = after[vp]
            if (b != null && a != null && b.size == a.size && b.mtime == a.mtime) continue
            val bk = b?.let { if (it.size <= MAX_BLOB) key(vp, it).takeIf { k -> File(blobs(), k).isFile } else null }
            val ak = a?.let { keep(vp, it, budget) }
            val bb = bytesOf(bk); val ab = bytesOf(ak)
            if (b != null && a != null && bb != null && ab != null && bb.contentEquals(ab)) continue
            val kind = if (b == null) "created" else if (a == null) "deleted" else "modified"
            val bText = if (b == null) "" else if (isText(bb)) String(bb!!) else null
            val aText = if (a == null) "" else if (isText(ab)) String(ab!!) else null
            val o = JSONObject().put("path", vp).put("kind", kind)
            if (bText != null && aText != null) { val c = counts(bText, aText); o.put("add", c.first).put("del", c.second); add += c.first; del += c.second }
            else o.put("binary", true).put("add", 0).put("del", 0)
            if (bk != null) o.put("b", bk)
            if (ak != null) o.put("a", ak)
            // a modified or deleted file whose old bytes were not kept (too big) cannot be put back
            if (b != null && bk == null) o.put("noRevert", true)
            files.put(o)
        }
        if (files.length() == 0) return null
        val j = JSONObject().put("id", "ch" + System.currentTimeMillis().toString(36) + (0..999).random()).put("convoId", convoId).put("ts", System.currentTimeMillis())
            .put("files", files).put("add", add).put("del", del)
        synchronized(lock) {
            val all = journals()
            all.put(j)
            val keep = if (all.length() > KEEP_JOURNALS) JSONArray((all.length() - KEEP_JOURNALS until all.length()).map { all.get(it) }) else all
            journalFile().writeText(keep.toString())
        }
        return summary(j)
    }

    /** What the chat shows (no store keys). */
    fun summary(j: JSONObject): JSONObject {
        val fs = JSONArray()
        val a = j.optJSONArray("files") ?: JSONArray()
        for (i in 0 until a.length()) { val f = a.getJSONObject(i); fs.put(JSONObject().put("path", f.optString("path")).put("kind", f.optString("kind")).put("add", f.optInt("add")).put("del", f.optInt("del")).put("binary", f.optBoolean("binary")).put("noRevert", f.optBoolean("noRevert"))) }
        return JSONObject().put("id", j.optString("id")).put("files", fs).put("add", j.optInt("add")).put("del", j.optInt("del")).put("reverted", j.optBoolean("reverted"))
    }

    fun journals(): JSONArray = synchronized(lock) { try { journalFile().takeIf { it.isFile }?.let { JSONArray(it.readText()) } } catch (_: Exception) { null } ?: JSONArray() }

    fun find(id: String): JSONObject? { val a = journals(); for (i in 0 until a.length()) { val j = a.getJSONObject(i); if (j.optString("id") == id) return j }; return null }

    /** Lines of the diff of one file of a journal entry: [[" "|"+"|"-", line], …]. */
    fun diffOf(id: String, path: String): JSONObject {
        val j = find(id) ?: throw IllegalArgumentException(Y.t("This change record is no longer kept", "Bu değişiklik kaydı artık tutulmuyor"))
        val fs = j.getJSONArray("files")
        val f = (0 until fs.length()).map { fs.getJSONObject(it) }.firstOrNull { it.optString("path") == path } ?: throw IllegalArgumentException("not in this change: $path")
        if (f.optBoolean("binary")) return JSONObject().put("path", path).put("kind", f.optString("kind")).put("binary", true)
        val bt = if (f.optString("kind") == "created") "" else bytesOf(f.optString("b").ifEmpty { null })?.let { String(it) }
        val at = if (f.optString("kind") == "deleted") "" else bytesOf(f.optString("a").ifEmpty { null })?.let { String(it) }
        if (bt == null || at == null) return JSONObject().put("path", path).put("kind", f.optString("kind")).put("missing", true)
        val lines = JSONArray()
        for ((op, l) in diff(bt, at)) lines.put(JSONArray().put(op.toString()).put(l))
        return JSONObject().put("path", path).put("kind", f.optString("kind")).put("lines", lines).put("add", f.optInt("add")).put("del", f.optInt("del"))
    }

    /** Puts every file of a journal entry back as it was before that turn. */
    fun revert(id: String): JSONObject {
        val j = find(id) ?: throw IllegalArgumentException(Y.t("This change record is no longer kept", "Bu değişiklik kaydı artık tutulmuyor"))
        val fs = j.getJSONArray("files")
        var n = 0
        val skipped = JSONArray()
        for (i in 0 until fs.length()) {
            val f = fs.getJSONObject(i)
            val vp = f.optString("path")
            try {
                when (f.optString("kind")) {
                    "created" -> { val x = VPath.file(vp); if (x.isFile) x.delete(); VPath.commit(vp); n++ }
                    else -> {
                        val b = bytesOf(f.optString("b").ifEmpty { null })
                        if (b == null) { skipped.put(vp); continue }
                        val x = VPath.file(vp); x.parentFile?.mkdirs(); x.writeBytes(b); VPath.commit(vp); n++
                    }
                }
            } catch (e: Exception) { skipped.put(vp); Diag.e("changes", "revert $vp", e) }
        }
        synchronized(lock) {
            val all = journals()
            for (i in 0 until all.length()) if (all.getJSONObject(i).optString("id") == id) all.getJSONObject(i).put("reverted", true)
            journalFile().writeText(all.toString())
        }
        Diag.i("changes", "reverted $id: $n files, ${skipped.length()} skipped")
        return JSONObject().put("reverted", n).put("skipped", skipped)
    }

    /** Drops stored copies that neither the current disk nor a kept journal entry needs. */
    private fun gc(current: Map<String, Ent>) {
        val need = HashSet<String>()
        for ((vp, e) in current) need.add(key(vp, e))
        for (m in open.values) for ((vp, e) in m) need.add(key(vp, e))   // turns still running in other chats
        val all = journals()
        for (i in 0 until all.length()) { val fs = all.getJSONObject(i).optJSONArray("files") ?: continue; for (k in 0 until fs.length()) { val f = fs.getJSONObject(k); f.optString("b").takeIf { it.isNotEmpty() }?.let { need.add(it) }; f.optString("a").takeIf { it.isNotEmpty() }?.let { need.add(it) } } }
        blobs().listFiles()?.forEach { if (it.name !in need) it.delete() }
    }

    // ---------------------------------------------------------------- diff (LCS, with the original's fallback for huge files)
    fun lines(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split("\n")

    fun diff(a: String, b: String): List<Pair<Char, String>> {
        val A = lines(a); val B = lines(b)
        if (A.size.toLong() * B.size > MAX_CELLS) {
            val sa = A.toHashSet(); val sb = B.toHashSet()
            return A.filter { it !in sb }.map { '-' to it } + B.filter { it !in sa }.map { '+' to it }
        }
        val n = A.size; val m = B.size
        val L = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) L[i][j] = if (A[i] == B[j]) L[i + 1][j + 1] + 1 else maxOf(L[i + 1][j], L[i][j + 1])
        val out = ArrayList<Pair<Char, String>>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            if (A[i] == B[j]) { out.add(' ' to A[i]); i++; j++ }
            else if (L[i + 1][j] >= L[i][j + 1]) out.add('-' to A[i++]) else out.add('+' to B[j++])
        }
        while (i < n) out.add('-' to A[i++])
        while (j < m) out.add('+' to B[j++])
        return out
    }

    fun counts(a: String, b: String): Pair<Int, Int> { val d = diff(a, b); return Pair(d.count { it.first == '+' }, d.count { it.first == '-' }) }
}
