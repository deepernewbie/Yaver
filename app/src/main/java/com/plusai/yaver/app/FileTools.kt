package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * yaver's virtual disk, laid out as in the original:
 *   /workspace            the user's files (cwd; uploads in /workspace/uploads)
 *   /.yaver               yaver's own data (also ~)
 *     PERSONA.md          the persona setting (editable)
 *     memories/           MEMORY.md and USER.md, read-only views of memory (change memory with the memory tool)
 *     skills/<name>/      skills: SKILL.md plus scripts/, templates/, references/ … (editable)
 *     sessions/           saved conversations (read-only)
 *   /tmp                  scratch space
 * Every tool, the terminal, Python/JavaScript (execute_code) and the Files screen go through this one mapping.
 */
object VPath {
    const val WS = "/workspace"
    const val HOME = "/.yaver"
    const val SKILLS = "$HOME/skills"
    const val MEMORIES = "$HOME/memories"
    const val SESSIONS = "$HOME/sessions"
    const val PERSONA = "$HOME/PERSONA.md"
    const val TMP = "/tmp"

    /** Folders that cannot be deleted, renamed or moved. */
    val PROTECTED = setOf("/", WS, HOME, SKILLS, MEMORIES, SESSIONS, TMP)
    private val ROOT_CHILDREN = listOf("workspace", ".yaver", "tmp")
    private val HOME_CHILDREN = listOf("PERSONA.md", "memories", "skills", "sessions")

    /* where the areas live on the phone (replaceable in tests) */
    @Volatile var workspaceDir: () -> File = { Workspace.root }
    @Volatile var skillsDir: () -> File = { Skills.root }
    @Volatile var sessionsDir: () -> File = { File(Y.app.filesDir, "convos") }
    @Volatile var vfsDir: () -> File = { File(Y.app.filesDir, "vfs") }
    @Volatile var tmpDir: () -> File = { File(Y.app.filesDir, "vfs/tmp") }
    @Volatile var memoryText: (target: String) -> String = { t -> MemoryX.entries(t).joinToString(MemoryX.DELIM) }
    @Volatile var personaGet: () -> String = { Cfg.persona.ifBlank { Prompt.DEFAULT_SOUL } }
    @Volatile var personaSet: (String) -> Unit = { t -> val v = t.trim(); Cfg.persona = if (v == Prompt.DEFAULT_SOUL.trim()) "" else v }

    // ---------------------------------------------------------------- path strings

    fun norm(path: String?, cwd: String = WS): String {
        var p = (path ?: "").trim().replace('\\', '/')
        if (p.startsWith("file://")) p = p.removePrefix("file://")
        if (p.isEmpty() || p == ".") p = cwd
        if (p == "~") p = HOME else if (p.startsWith("~/")) p = HOME + p.substring(1)
        if (!p.startsWith("/")) p = "$cwd/$p"
        val out = ArrayList<String>()
        for (s in p.split('/')) { if (s.isEmpty() || s == ".") continue; if (s == "..") { if (out.isNotEmpty()) out.removeAt(out.size - 1) } else out.add(s) }
        return "/" + out.joinToString("/")
    }

    /** True when [vp] is [area] or inside it. */
    fun under(vp: String, area: String) = vp == area || vp.startsWith("$area/")

    /** A path on one of the disk's areas (or one of its two virtual folders). */
    fun known(vp: String) = vp == "/" || under(vp, WS) || under(vp, HOME) || under(vp, TMP)

    /**
     * Paths as tools get them: relative paths resolve from /workspace; "workspace/x", "/workspace/x" and "x" are the
     * same file; ".yaver/…" means /.yaver/…; an absolute path outside the disk ("/uploads/a.pdf") is taken as a
     * workspace path, as the phone version always did.
     */
    fun normTool(path: String?): String {
        var p = (path ?: "").trim().replace('\\', '/')
        if (p.startsWith("file://")) p = p.removePrefix("file://")
        if (p == "workspace" || p.startsWith("workspace/") || p == ".yaver" || p.startsWith(".yaver/")) p = "/$p"
        val n = norm(p)
        return if (known(n)) n else norm(WS + n)
    }

    fun base(vp: String) = vp.substringAfterLast('/')
    fun parent(vp: String) = vp.substringBeforeLast('/', "").ifEmpty { "/" }
    fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

    // ---------------------------------------------------------------- mapping

    /**
     * Where a virtual path lives: [file] is the real file or folder; [children] lists the entries of a virtual
     * folder (/ and /.yaver); [readOnly] is true where neither tools nor the user may change anything.
     */
    class Loc(val vp: String, val file: File, val readOnly: Boolean, val area: String, val children: List<String>? = null)

    private fun inside(root: File, rel: String, vp: String): File {
        val r = root.apply { mkdirs() }.canonicalFile
        if (rel.isEmpty()) return r
        val f = File(r, rel).canonicalFile
        if (f != r && !f.path.startsWith(r.path + File.separator)) throw IllegalArgumentException("path is outside its folder: $vp")
        return f
    }

    private fun rest(vp: String, area: String) = vp.removePrefix(area).trimStart('/')

    private fun syncFile(f: File, text: String) {
        try { if (!f.isFile || f.readText() != text) { f.parentFile?.mkdirs(); f.writeText(text) } } catch (_: Exception) {}
    }

    private fun memDir(): File {
        val d = File(vfsDir(), "memories").apply { mkdirs() }
        syncFile(File(d, "MEMORY.md"), try { memoryText("memory") } catch (e: Throwable) { "" })
        syncFile(File(d, "USER.md"), try { memoryText("user") } catch (e: Throwable) { "" })
        return d
    }

    private fun personaFile(): File = File(vfsDir(), "PERSONA.md").also { syncFile(it, try { personaGet() } catch (e: Throwable) { "" }) }

    fun locate(vp0: String): Loc {
        val vp = norm(vp0)
        return when {
            vp == "/" -> Loc(vp, File(vfsDir(), ".root").apply { mkdirs() }, true, "root", ROOT_CHILDREN)
            vp == HOME -> Loc(vp, File(vfsDir(), ".home").apply { mkdirs() }, true, "home", HOME_CHILDREN)
            under(vp, WS) -> Loc(vp, inside(workspaceDir(), rest(vp, WS), vp), false, "workspace")
            under(vp, SKILLS) -> Loc(vp, inside(skillsDir(), rest(vp, SKILLS), vp), false, "skills")
            under(vp, MEMORIES) -> Loc(vp, inside(memDir(), rest(vp, MEMORIES), vp), true, "memories")
            under(vp, SESSIONS) -> Loc(vp, inside(sessionsDir(), rest(vp, SESSIONS), vp), true, "sessions")
            vp == PERSONA -> Loc(vp, personaFile(), false, "persona")
            under(vp, TMP) -> Loc(vp, inside(tmpDir(), rest(vp, TMP), vp), false, "tmp")
            under(vp, HOME) -> throw IllegalArgumentException("ENOENT $vp (/.yaver holds PERSONA.md, memories/, skills/ and sessions/)")
            else -> throw IllegalArgumentException("$vp is not on yaver's disk: it has /workspace (files), /.yaver (yaver's own data: skills, memories, persona) and /tmp")
        }
    }

    /** The real file behind a virtual path (for reading). */
    fun file(vp: String): File = locate(vp).file

    /** Why [vp] cannot be changed, or null when it can. */
    fun readOnlyReason(vp: String): String? {
        val l = try { locate(vp) } catch (e: Exception) { return e.message }
        if (!l.readOnly) return null
        return when (l.area) {
            "memories" -> "$vp is a read-only view of yaver's memory: change memory with the memory tool"
            "sessions" -> "$vp is read-only: conversations are saved by yaver itself"
            else -> "$vp is a fixed folder of the disk: put files in /workspace (or skills in /.yaver/skills)"
        }
    }

    fun writable(vp: String) = readOnlyReason(vp) == null

    /** The real file behind a virtual path, for changing it (throws for read-only places). */
    fun fileW(vp: String): File {
        readOnlyReason(vp)?.let { throw IllegalArgumentException(it) }
        return file(vp)
    }

    /** After a file at [vp] changed outside write()/append(): apply it (persona) and tell whoever watches. */
    fun commit(vp: String) {
        try {
            when {
                vp == PERSONA -> personaSet(File(vfsDir(), "PERSONA.md").readText())
                under(vp, SKILLS) -> Skills.onChange?.invoke()
                under(vp, WS) -> Workspace.changed(vp)
            }
        } catch (e: Throwable) { try { Diag.e("vfs", "commit $vp", e) } catch (_: Throwable) {} }
    }

    /** The virtual path of a real file, or null when it is not on the disk. */
    fun of(f: File): String {
        val c = f.canonicalFile
        fun rel(root: File, area: String): String? {
            val r = try { root.canonicalFile } catch (e: Exception) { return null }
            if (c == r) return area
            return if (c.path.startsWith(r.path + File.separator)) area + "/" + c.path.substring(r.path.length + 1).replace(File.separatorChar, '/') else null
        }
        return rel(workspaceDir(), WS) ?: rel(skillsDir(), SKILLS) ?: rel(File(vfsDir(), "memories"), MEMORIES) ?: rel(sessionsDir(), SESSIONS)
            ?: (if (c == File(vfsDir(), "PERSONA.md").canonicalFile) PERSONA else null) ?: rel(tmpDir(), TMP) ?: (WS + "/" + c.name)
    }

    // ---------------------------------------------------------------- queries

    fun isFile(vp: String) = try { val l = locate(vp); l.children == null && l.file.isFile } catch (e: Exception) { false }
    fun isDir(vp: String) = try { val l = locate(vp); l.children != null || l.file.isDirectory } catch (e: Exception) { false }
    fun exists(vp: String) = isFile(vp) || isDir(vp)
    fun isText(vp: String) = try { val f = file(vp); f.isFile && Workspace.isText(f) } catch (e: Exception) { false }

    fun read(vp: String): String {
        val f = try { file(vp) } catch (e: Exception) { throw IllegalArgumentException(if (known(vp)) "ENOENT $vp" else e.message ?: "ENOENT $vp") }
        if (f.isDirectory || isDir(vp)) throw IllegalArgumentException("EISDIR $vp")
        if (!f.isFile) throw IllegalArgumentException("ENOENT $vp")
        return f.readText()
    }
    fun readBytes(vp: String): ByteArray { val f = file(vp); if (!f.isFile) throw IllegalArgumentException("ENOENT $vp"); return f.readBytes() }

    fun write(vp: String, text: String): String { val f = fileW(vp); if (f.isDirectory) throw IllegalArgumentException("EISDIR $vp"); f.parentFile?.mkdirs(); f.writeText(text); commit(vp); return vp }
    fun writeBytes(vp: String, b: ByteArray): String { val f = fileW(vp); if (f.isDirectory) throw IllegalArgumentException("EISDIR $vp"); f.parentFile?.mkdirs(); f.writeBytes(b); commit(vp); return vp }
    fun append(vp: String, text: String): String { val f = fileW(vp); f.parentFile?.mkdirs(); f.appendText(text); commit(vp); return vp }
    fun mkdirs(vp: String): String { val f = fileW(vp); if (f.isFile) throw IllegalArgumentException("cannot create folder $vp: a file exists"); f.mkdirs(); return vp }

    class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val m: Long)

    fun list(vp: String): List<Entry> {
        val l = locate(vp)
        if (l.children != null) return l.children.map { n ->
            val p = join(l.vp, n); val c = try { locate(p).file } catch (e: Exception) { null }
            val d = n != "PERSONA.md"
            Entry(n, p, d, if (!d && c != null) c.length() else 0, c?.lastModified() ?: 0)
        }
        val d = l.file
        if (!d.isDirectory) throw IllegalArgumentException(if (d.isFile) "ENOTDIR: ${l.vp}" else "ENOENT: ${l.vp}")
        return (d.listFiles() ?: emptyArray()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { Entry(it.name, join(l.vp, it.name), it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
    }

    /** Every file under a folder (sorted full paths). Walking / or /.yaver leaves out saved conversations unless asked for. */
    fun walk(vp: String, sessions: Boolean = false): List<String> {
        val out = ArrayList<String>()
        fun rec(p: String) {
            val l = try { locate(p) } catch (e: Exception) { return }
            if (l.children != null) { for (c in l.children) { val cp = join(l.vp, c); if (cp == SESSIONS && !sessions && vp != SESSIONS && !under(vp, SESSIONS)) continue; rec(cp) }; return }
            val f = l.file
            if (f.isFile) { out.add(l.vp); return }
            if (!f.isDirectory) return
            val base = f.canonicalFile
            base.walkTopDown().forEach { if (it.isFile) out.add(l.vp + "/" + it.canonicalFile.path.removePrefix(base.path + File.separator).replace(File.separatorChar, '/')) }
        }
        rec(norm(vp))
        return out.sorted()
    }

    fun walkDirs(vp: String): List<String> {
        val out = ArrayList<String>()
        fun rec(p: String) {
            val l = try { locate(p) } catch (e: Exception) { return }
            if (l.children != null) { for (c in l.children) { val cp = join(l.vp, c); if (c == "PERSONA.md") continue; if (cp == SESSIONS && !under(vp, SESSIONS)) { out.add(cp); continue }; out.add(cp); rec(cp) }; return }
            val f = l.file
            if (!f.isDirectory) return
            val base = f.canonicalFile
            base.walkTopDown().forEach { if (it.isDirectory && it.canonicalFile != base) out.add(l.vp + "/" + it.canonicalFile.path.removePrefix(base.path + File.separator).replace(File.separatorChar, '/')) }
        }
        rec(norm(vp))
        return out.sorted()
    }

    fun globRe(glob: String) = Regex("^" + Regex("[.+^\${}()|\\[\\]\\\\]").replace(glob) { "\\" + it.value }.replace("*", ".*").replace("?", ".") + "$")

    /** A short map of the disk for prompts and errors. */
    fun layout(): String = "/workspace (your files; cwd) · /.yaver (PERSONA.md, memories/ read-only, skills/<name>/, sessions/ read-only) · /tmp"
}

/** read_file, write_file, patch and search_files as in the original (they replace CoreTools' simpler read_file / write_file). */
object FileTools {
    fun register() {
        Tools.add(Tool(
            "read_file",
            "Read a text file from the virtual disk with line numbers. Relative paths resolve from /workspace; ~ is /.yaver (skills in /.yaver/skills/<name>/, memory views in /.yaver/memories/). Reading a folder lists it.",
            P.props("path" to P.s("File path"), "offset" to toolInt("1-based start line"), "limit" to toolInt("Max lines (default 400)")),
            listOf("path")
        ) { a, _ ->
            val p = VPath.normTool(a.str("path"))
            if (VPath.isDir(p)) return@Tool "$p is a folder:\n" + VPath.list(p).joinToString("\n") { it.name + if (it.dir) "/" else " (${Workspace.fmtBytes(it.size)})" }.ifEmpty { "(empty)" }
            if (!VPath.isText(p)) {
                if (!VPath.isFile(p)) throw IllegalArgumentException("ENOENT $p" + hint(p))
                return@Tool "$p is binary (${Workspace.fmtBytes(VPath.file(p).length())}, ${Workspace.mime(p)}). Use read_document for PDF, Word, Excel and PowerPoint files, look_at_image for images."
            }
            val lines = VPath.read(p).split("\n")
            val s = maxOf(1, a.int("offset", 1))
            val limit = a.int("limit", 400)
            val sl = lines.drop(s - 1).take(maxOf(0, limit))
            sl.mapIndexed { i, l -> "${(s + i).toString().padStart(5)}| $l" }.joinToString("\n") +
                if (s - 1 + limit < lines.size) "\n… ${lines.size - (s - 1 + limit)} more lines (use offset)" else ""
        })

        Tools.add(Tool(
            "write_file",
            "Create or overwrite a text file on the virtual disk (parent folders are created). Paths: /workspace/… (relative paths land there), /.yaver/skills/<name>/…, /.yaver/PERSONA.md. For large files (over ~6000 characters) write the first part, then call again with append=true for each following part, so no single call gets cut off.",
            P.props("path" to P.s("File path"), "content" to P.s("File content (or the next part when append is true)"), "append" to P.b("Append to the end of the file instead of overwriting")),
            listOf("path", "content")
        ) { a, ctx ->
            val p0 = VPath.normTool(a.str("path"))
            VPath.readOnlyReason(p0)?.let { throw IllegalArgumentException(it) }
            val content = a.optString("content", "")
            val append = a.optBoolean("append")
            if (!append && VPath.isFile(p0) && !ctx.approve(Y.t("Overwrite file", "Dosyanın üzerine yaz") + ": " + p0, listOf(Y.t("Why", "Neden") to "overwrites existing file $p0", Y.t("Size", "Boyut") to Workspace.fmtBytes(VPath.file(p0).length()))))
                return@Tool ctx.denied("overwrite $p0")
            val p = if (append && VPath.isFile(p0)) VPath.append(p0, content) else VPath.write(p0, content)
            val size = VPath.file(p).length()
            "${if (append) "Appended" else "Wrote"} ${Workspace.fmtBytes(content.toByteArray().size.toLong())} to $p${if (append) " (now ${Workspace.fmtBytes(size)})" else ""}"
        })

        Tools.add(Tool(
            "patch",
            "Edit a file by exact string replacement. old_string must match exactly once unless replace_all.",
            P.props("path" to P.s("File path"), "old_string" to P.s("Exact text to find"), "new_string" to P.s("Replacement"), "replace_all" to P.b("Replace every occurrence")),
            listOf("path", "old_string", "new_string")
        ) { a, _ ->
            val p = VPath.normTool(a.str("path"))
            VPath.readOnlyReason(p)?.let { throw IllegalArgumentException(it) }
            val t = VPath.read(p)
            val old = a.optString("old_string", "")
            val new = a.optString("new_string", "")
            val all = a.optBoolean("replace_all")
            val n = count(t, old)
            if (n == 0) throw IllegalArgumentException("old_string not found. Re-read the file and copy the exact text.")
            if (n > 1 && !all) throw IllegalArgumentException("old_string matches $n times; add context or set replace_all")
            VPath.write(p, if (all) t.replace(old, new) else t.replaceFirst(old, new))
            "Patched $p (${if (all) n else 1} replacement${if (n > 1 && all) "s" else ""})"
        })

        Tools.add(Tool(
            "search_files",
            "Search file contents with a regex, or list files matching a glob when pattern is omitted. path: a folder on the virtual disk (default /workspace; /.yaver/skills for skills, / for everything).",
            P.props("pattern" to P.s("Regex to search for"), "path" to P.s("Directory (default /workspace)"), "glob" to P.s("Filename glob, e.g. *.md")),
            emptyList()
        ) { a, ctx ->
            val glob = a.str("glob")
            val re = if (glob.isNotEmpty()) VPath.globRe(glob) else null
            val root = VPath.normTool(a.str("path").ifEmpty { VPath.WS })
            if (!VPath.exists(root)) throw IllegalArgumentException("ENOENT $root" + hint(root))
            val files = VPath.walk(root).filter { re == null || re.matches(VPath.base(it)) }
            val pattern = a.optString("pattern", "")
            if (pattern.isEmpty()) return@Tool files.joinToString("\n").ifEmpty { "(no files)" }
            val rx = Regex(pattern, RegexOption.IGNORE_CASE)
            val out = ArrayList<String>()
            for (f in files) {
                ctx.cancel.check()
                if (!VPath.isText(f)) continue
                VPath.read(f).split("\n").forEachIndexed { i, l -> if (out.size < 200 && rx.containsMatchIn(l)) out.add("$f:${i + 1}: ${l.take(200)}") }
                if (out.size >= 200) break
            }
            out.joinToString("\n").ifEmpty { "(no matches)" }
        })
    }

    /** A nudge for a path that is not there: the closest file with the same name, or the disk's layout. */
    fun hint(p: String): String {
        val name = VPath.base(p)
        if (name.isEmpty()) return ""
        val same = try { VPath.walk("/").filter { VPath.base(it).equals(name, true) }.take(3) } catch (e: Exception) { emptyList() }
        return if (same.isNotEmpty()) " — did you mean ${same.joinToString(" or ")}?" else " (the disk: ${VPath.layout()})"
    }

    /** Non-overlapping occurrences, like String.split(s).length - 1 in JS. */
    fun count(t: String, s: String): Int {
        if (s.isEmpty()) return t.length + 1
        var n = 0; var i = t.indexOf(s)
        while (i >= 0) { n++; i = t.indexOf(s, i + s.length) }
        return n
    }
}

/** An integer parameter (the original's N()). */
fun toolInt(d: String): org.json.JSONObject = org.json.JSONObject().put("type", "integer").put("description", d)

/** The Files screen's calls (Bridge files.*): browse, preview, edit, rename, move, delete, download the virtual disk. */
object FilesUi {
    private const val TEXT_MAX = 300_000

    fun kind(name: String, dir: Boolean = false): String {
        if (dir) return "folder"
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic" -> "image"
            "pdf" -> "pdf"
            "doc", "docx", "docm", "dotx", "odt", "rtf" -> "doc"
            "xls", "xlsx", "xlsm", "ods" -> "sheet"
            "csv", "tsv" -> "csv"
            "ppt", "pptx", "odp" -> "slides"
            "md", "markdown" -> "md"
            "txt", "log", "ini", "cfg", "conf", "env" -> "text"
            "json", "yaml", "yml", "xml", "toml" -> "data"
            "js", "mjs", "ts", "py", "kt", "java", "c", "cpp", "h", "css", "sh", "sql", "go", "rs", "rb", "php" -> "code"
            "html", "htm" -> "html"
            "zip", "tar", "gz", "tgz", "7z", "rar" -> "archive"
            "mp3", "wav", "m4a", "ogg", "flac", "aac" -> "audio"
            "mp4", "mov", "webm", "mkv", "3gp" -> "video"
            else -> "other"
        }
    }

    private fun entry(e: VPath.Entry): JSONObject {
        val o = JSONObject().put("name", e.name).put("path", e.path).put("dir", e.dir).put("size", e.size).put("m", e.m).put("kind", kind(e.name, e.dir))
        if (e.dir) o.put("count", try { VPath.locate(e.path).let { l -> l.children?.size ?: (l.file.list()?.size ?: 0) } } catch (_: Exception) { 0 })
        if (!VPath.writable(e.path) || e.path in VPath.PROTECTED) o.put("fixed", true)
        if (!VPath.writable(e.path)) o.put("ro", true)
        return o
    }

    private fun crumbs(vp: String): JSONArray {
        val a = JSONArray().put(JSONObject().put("name", "/").put("path", "/"))
        var cur = ""
        for (s in vp.split('/').filter { it.isNotEmpty() }) { cur += "/$s"; a.put(JSONObject().put("name", s).put("path", cur)) }
        return a
    }

    private fun p(a: JSONObject, k: String = "path") = VPath.normTool(a.str(k).ifEmpty { VPath.WS })

    fun list(a: JSONObject): JSONObject {
        val vp = p(a)
        if (!VPath.isDir(vp)) throw IllegalArgumentException(if (VPath.isFile(vp)) "$vp is a file" else Y.t("No such folder: ", "Böyle bir klasör yok: ") + vp)
        val list = VPath.list(vp).filter { !(vp == VPath.WS && it.name == Spill.DIR) || a.optBoolean("all") }
        val ro = VPath.readOnlyReason(vp)
        val total = if (vp == VPath.WS) (try { VPath.walk(VPath.WS).size } catch (_: Exception) { 0 }) else -1
        return JSONObject().put("path", vp).put("name", if (vp == "/") "/" else VPath.base(vp)).put("parent", if (vp == "/") JSONObject.NULL else VPath.parent(vp))
            .put("readOnly", ro != null).put("why", ro ?: "").put("crumbs", crumbs(vp)).put("entries", JSONArray(list.map { entry(it) })).put("files", total)
    }

    fun read(a: JSONObject): JSONObject {
        val vp = p(a)
        val f = VPath.file(vp)
        if (!f.isFile) throw IllegalArgumentException(Y.t("File not found: ", "Dosya bulunamadı: ") + vp)
        val k = kind(f.name)
        val o = JSONObject().put("path", vp).put("name", f.name).put("size", f.length()).put("m", f.lastModified()).put("kind", k)
            .put("mime", Workspace.mime(f.name)).put("readOnly", !VPath.writable(vp))
        val max = a.optInt("max", TEXT_MAX).coerceIn(1000, 2_000_000)
        if (k != "image" && k != "pdf" && Workspace.isText(f)) {
            val bytes = f.inputStream().use { s -> val b = ByteArray(minOf(f.length(), max.toLong()).toInt()); var n = 0; while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }; b.copyOf(n) }
            o.put("text", String(bytes, Charsets.UTF_8)).put("truncated", f.length() > max).put("binary", false)
        } else o.put("binary", true)
        return o
    }

    /** Text out of a document (PDF, Word, Excel, PowerPoint…) for the preview. */
    fun extract(a: JSONObject): JSONObject {
        val vp = p(a)
        val f = VPath.file(vp)
        if (!f.isFile) throw IllegalArgumentException(Y.t("File not found: ", "Dosya bulunamadı: ") + vp)
        val t = Docs.extract(f, a.str("pages"), false, null)
        return JSONObject().put("path", vp).put("text", clip(t, 120000)).put("truncated", t.length > 120000)
    }

    /** One page of a PDF as a picture (android.graphics.pdf.PdfRenderer). */
    fun pdfPage(a: JSONObject): JSONObject {
        val f = VPath.file(p(a))
        val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { fd ->
            val r = android.graphics.pdf.PdfRenderer(fd)
            r.use { pdf ->
                val n = pdf.pageCount
                val i = a.optInt("page", 0).coerceIn(0, maxOf(0, n - 1))
                val page = pdf.openPage(i)
                page.use { pg ->
                    val w = a.optInt("width", 1080).coerceIn(200, 1600)
                    val h = (pg.height.toDouble() * w / pg.width).toInt().coerceAtMost(4000)
                    val bmp = android.graphics.Bitmap.createBitmap(w, maxOf(1, h), android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                    bmp.recycle()
                    return JSONObject().put("pages", n).put("page", i).put("data", "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
                }
            }
        }
    }

    private fun guard(vp: String) {
        if (vp in VPath.PROTECTED) throw IllegalArgumentException(Y.t("This folder belongs to the disk and cannot be changed: ", "Bu klasör diskin parçası, değiştirilemez: ") + vp)
        VPath.readOnlyReason(vp)?.let { throw IllegalArgumentException(Y.t("Read-only: ", "Salt okunur: ") + vp) }
        if (vp == VPath.PERSONA) throw IllegalArgumentException(Y.t("PERSONA.md is the persona setting: edit it instead", "PERSONA.md kişilik ayarıdır: düzenleyin"))
    }

    private fun checkName(n0: String): String {
        val n = n0.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\')) throw IllegalArgumentException(Y.t("Not a valid name", "Geçerli bir ad değil"))
        return Workspace.safeName(n)
    }

    fun write(a: JSONObject): JSONObject {
        val vp = p(a)
        VPath.readOnlyReason(vp)?.let { throw IllegalArgumentException(Y.t("Read-only: ", "Salt okunur: ") + vp) }
        VPath.write(vp, a.optString("text", ""))
        return read(JSONObject().put("path", vp))
    }

    fun rename(a: JSONObject): JSONObject {
        val vp = p(a); guard(vp)
        val name = checkName(a.optString("name"))
        val to = VPath.join(VPath.parent(vp), name)
        if (to == vp) return JSONObject().put("path", vp)
        val s = VPath.file(vp); if (!s.exists()) throw IllegalArgumentException(Y.t("Not found: ", "Bulunamadı: ") + vp)
        val d = VPath.fileW(to)
        if (d.exists() && !d.canonicalPath.equals(s.canonicalPath, ignoreCase = true)) throw IllegalArgumentException(Y.t("A file with that name already exists", "Bu adda bir dosya zaten var"))
        if (!s.renameTo(d)) throw IllegalArgumentException(Y.t("Could not rename", "Yeniden adlandırılamadı"))
        VPath.commit(vp); VPath.commit(to)
        return JSONObject().put("path", to)
    }

    private fun transfer(a: JSONObject, keep: Boolean): JSONObject {
        val vp = p(a); if (!keep) guard(vp)
        val dir = VPath.normTool(a.str("to"))
        if (!VPath.isDir(dir)) throw IllegalArgumentException(Y.t("No such folder: ", "Böyle bir klasör yok: ") + dir)
        VPath.readOnlyReason(dir)?.let { throw IllegalArgumentException(Y.t("Cannot put files in ", "Şuraya dosya konamaz: ") + dir) }
        if (dir == "/" || dir == VPath.HOME) throw IllegalArgumentException(Y.t("Cannot put files in ", "Şuraya dosya konamaz: ") + dir)
        if (VPath.under(dir, vp)) throw IllegalArgumentException(Y.t("Cannot move a folder into itself", "Klasör kendi içine taşınamaz"))
        val s = VPath.file(vp); if (!s.exists()) throw IllegalArgumentException(Y.t("Not found: ", "Bulunamadı: ") + vp)
        if (!keep && VPath.parent(vp) == dir) return JSONObject().put("path", vp)
        val d = Workspace.unique(VPath.fileW(dir), s.name)
        if (keep) { if (s.isDirectory) s.copyRecursively(d, false) else s.copyTo(d, false) }
        else if (!s.renameTo(d)) { if (s.isDirectory) s.copyRecursively(d, false) else s.copyTo(d, false); s.deleteRecursively() }
        val to = VPath.join(dir, d.name)
        if (!keep) VPath.commit(vp)
        VPath.commit(to)
        return JSONObject().put("path", to)
    }

    fun move(a: JSONObject) = transfer(a, false)
    fun copy(a: JSONObject) = transfer(a, true)

    fun delete(a: JSONObject): Boolean {
        val vp = p(a); guard(vp)
        val f = VPath.file(vp); if (!f.exists()) throw IllegalArgumentException(Y.t("Not found: ", "Bulunamadı: ") + vp)
        f.deleteRecursively()
        VPath.commit(vp)
        return true
    }

    fun mkdir(a: JSONObject): JSONObject {
        val dir = p(a)
        val name = checkName(a.optString("name"))
        if (dir == "/" || dir == VPath.HOME) throw IllegalArgumentException(Y.t("Cannot put files in ", "Şuraya dosya konamaz: ") + dir)
        val to = VPath.join(dir, name)
        if (VPath.exists(to)) throw IllegalArgumentException(Y.t("A file with that name already exists", "Bu adda bir dosya zaten var"))
        VPath.mkdirs(to)
        return JSONObject().put("path", to)
    }

    /** A new, empty text file (for notes and skill files). */
    fun newFile(a: JSONObject): JSONObject {
        val dir = p(a)
        val name = checkName(a.optString("name"))
        val to = VPath.join(dir, name)
        if (VPath.exists(to)) throw IllegalArgumentException(Y.t("A file with that name already exists", "Bu adda bir dosya zaten var"))
        VPath.write(to, a.optString("text", ""))
        return JSONObject().put("path", to)
    }

    /** Copies a file to the phone's Download/yaver folder; a folder goes as a .zip. */
    fun download(a: JSONObject): JSONObject {
        val vp = p(a)
        var f = VPath.file(vp)
        if (VPath.isDir(vp)) {
            val hook = ShellHooks.archive ?: throw IllegalArgumentException(Y.t("Folders cannot be packed in this version", "Klasörler bu sürümde paketlenemiyor"))
            val name = (if (vp == "/") "yaver-disk" else VPath.base(vp).trimStart('.').ifEmpty { "yaver" }) + ".zip"
            val out = VPath.TMP + "/" + name
            try { VPath.file(out).delete() } catch (_: Exception) {}
            hook.pack(listOf(vp), out, null)
            f = VPath.file(out)
        }
        if (!f.isFile) throw IllegalArgumentException(Y.t("File not found: ", "Dosya bulunamadı: ") + vp)
        return JSONObject().put("saved", Workspace.exportToDownloads(Y.app, f)).put("name", f.name)
    }

    /** Folders a file can be moved or uploaded to (writable ones). */
    fun folders(): JSONArray {
        val out = JSONArray().put(VPath.WS)
        for (d in (try { VPath.walkDirs(VPath.WS) } catch (_: Exception) { emptyList() }).filter { !VPath.base(it).startsWith(".") && !VPath.under(it, VPath.WS + "/" + Spill.DIR) }.take(400)) out.put(d)
        out.put(VPath.SKILLS)
        for (d in (try { VPath.walkDirs(VPath.SKILLS) } catch (_: Exception) { emptyList() }).take(200)) out.put(d)
        out.put(VPath.TMP)
        return out
    }
}
