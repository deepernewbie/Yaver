package com.plusai.yaver.app

import java.io.File

/**
 * Paths as the original's tools and prompts use them: /workspace is the workspace root, relative paths resolve
 * from a working folder (default /workspace), ~ means /workspace. Only /workspace exists on the phone.
 */
object VPath {
    const val WS = "/workspace"

    fun norm(path: String?, cwd: String = WS): String {
        var p = (path ?: "").trim().replace('\\', '/')
        if (p.isEmpty() || p == ".") p = cwd
        if (p == "~") p = WS else if (p.startsWith("~/")) p = WS + p.substring(1)
        if (!p.startsWith("/")) p = "$cwd/$p"
        val out = ArrayList<String>()
        for (s in p.split('/')) { if (s.isEmpty() || s == ".") continue; if (s == "..") { if (out.isNotEmpty()) out.removeAt(out.size - 1) } else out.add(s) }
        return "/" + out.joinToString("/")
    }

    /** Like Workspace.resolve: "x", "workspace/x" and "/workspace/x" are the same file. */
    fun normTool(path: String?): String {
        val p = (path ?: "").trim().replace('\\', '/')
        if (p.startsWith("workspace/") || p == "workspace") return norm("/$p")
        return norm(p)
    }

    fun file(vp: String): File {
        val root = Workspace.root.canonicalFile
        if (vp == WS || vp == "$WS/") return root
        if (!vp.startsWith("$WS/")) throw IllegalArgumentException("$vp is outside the workspace (only /workspace exists here)")
        val f = File(root, vp.substring(WS.length + 1)).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) throw IllegalArgumentException("path is outside the workspace: $vp")
        return f
    }

    fun of(f: File): String { val r = Workspace.rel(f); return if (r.isEmpty()) WS else "$WS/$r" }
    fun base(vp: String) = vp.substringAfterLast('/')
    fun parent(vp: String) = vp.substringBeforeLast('/', "").ifEmpty { "/" }

    fun isFile(vp: String) = try { file(vp).isFile } catch (e: Exception) { false }
    fun isDir(vp: String) = vp == "/" || try { file(vp).isDirectory } catch (e: Exception) { false }
    fun exists(vp: String) = isFile(vp) || isDir(vp)
    fun isText(vp: String) = try { val f = file(vp); f.isFile && Workspace.isText(f) } catch (e: Exception) { false }

    fun read(vp: String): String {
        val f = try { file(vp) } catch (e: Exception) { throw IllegalArgumentException("ENOENT $vp") }
        if (f.isDirectory) throw IllegalArgumentException("EISDIR $vp")
        if (!f.isFile) throw IllegalArgumentException("ENOENT $vp")
        return f.readText()
    }
    fun readBytes(vp: String): ByteArray { val f = file(vp); if (!f.isFile) throw IllegalArgumentException("ENOENT $vp"); return f.readBytes() }
    fun write(vp: String, text: String): String { val f = file(vp); if (f.isDirectory) throw IllegalArgumentException("EISDIR $vp"); f.parentFile?.mkdirs(); f.writeText(text); Workspace.changed(vp); return vp }
    fun writeBytes(vp: String, b: ByteArray): String { val f = file(vp); if (f.isDirectory) throw IllegalArgumentException("EISDIR $vp"); f.parentFile?.mkdirs(); f.writeBytes(b); Workspace.changed(vp); return vp }
    fun append(vp: String, text: String): String { val f = file(vp); f.parentFile?.mkdirs(); f.appendText(text); Workspace.changed(vp); return vp }

    class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val m: Long)

    fun list(vp: String): List<Entry> {
        if (vp == "/") return listOf(Entry("workspace", WS, true, 0, Workspace.root.lastModified()))
        val d = file(vp)
        if (!d.isDirectory) throw IllegalArgumentException(if (d.isFile) "ENOTDIR: $vp" else "ENOENT: $vp")
        return (d.listFiles() ?: emptyArray()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).map { Entry(it.name, "$vp/${it.name}".replace("//", "/"), it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
    }

    /** Every file under a folder (sorted full paths). */
    fun walk(vp: String): List<String> {
        val start = if (vp == "/") Workspace.root.canonicalFile else file(vp)
        if (start.isFile) return listOf(of(start))
        val out = ArrayList<String>()
        start.walkTopDown().forEach { if (it.isFile) out.add(of(it)) }
        return out.sorted()
    }

    fun walkDirs(vp: String): List<String> {
        val start = if (vp == "/") Workspace.root.canonicalFile else file(vp)
        if (!start.isDirectory) return emptyList()
        val out = ArrayList<String>()
        start.walkTopDown().forEach { if (it.isDirectory && it != start) out.add(of(it)) }
        return out.sorted()
    }

    fun globRe(glob: String) = Regex("^" + Regex("[.+^\${}()|\\[\\]\\\\]").replace(glob) { "\\" + it.value }.replace("*", ".*").replace("?", ".") + "$")
}

/** read_file, write_file, patch and search_files as in the original (they replace CoreTools' simpler read_file / write_file). */
object FileTools {
    fun register() {
        Tools.add(Tool(
            "read_file",
            "Read a text file from the workspace with line numbers. Relative paths resolve from /workspace.",
            P.props("path" to P.s("File path"), "offset" to toolInt("1-based start line"), "limit" to toolInt("Max lines (default 400)")),
            listOf("path")
        ) { a, _ ->
            val p = VPath.normTool(a.str("path"))
            if (VPath.isDir(p)) return@Tool "$p is a directory:\n" + VPath.list(p).joinToString("\n") { it.name + if (it.dir) "/" else " (${Workspace.fmtBytes(it.size)})" }
            if (!VPath.isText(p)) {
                if (!VPath.isFile(p)) throw IllegalArgumentException("ENOENT $p")
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
            "Create or overwrite a text file in the workspace (parent folders are created). For large files (over ~6000 characters) write the first part, then call again with append=true for each following part, so no single call gets cut off.",
            P.props("path" to P.s("File path"), "content" to P.s("File content (or the next part when append is true)"), "append" to P.b("Append to the end of the file instead of overwriting")),
            listOf("path", "content")
        ) { a, ctx ->
            val p0 = VPath.normTool(a.str("path"))
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
            "Search file contents with a regex, or list files matching a glob when pattern is omitted.",
            P.props("pattern" to P.s("Regex to search for"), "path" to P.s("Directory (default /workspace)"), "glob" to P.s("Filename glob, e.g. *.md")),
            emptyList()
        ) { a, ctx ->
            val glob = a.str("glob")
            val re = if (glob.isNotEmpty()) VPath.globRe(glob) else null
            val files = VPath.walk(VPath.normTool(a.str("path").ifEmpty { VPath.WS })).filter { re == null || re.matches(VPath.base(it)) }
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
