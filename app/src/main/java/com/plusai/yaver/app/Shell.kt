package com.plusai.yaver.app

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A shell error: [silent] prints nothing, [plain] prints the message without the command name. */
class ShErr(msg: String, val silent: Boolean = false, val exitCode: Int = 1, val plain: Boolean = false) : RuntimeException(msg)

class ShResult(val output: String, val exit: Int, val cwd: String)

/** Archives for unzip / tar / zip / gunzip. Archive.kt (another engineer) sets [ShellHooks.archive]. Paths are /workspace/… paths. */
interface ArchiveHook {
    /** Unpacks [archive] into [dest] (null: the archive's default folder). Returns (files unpacked, destination path). */
    fun unpack(archive: String, dest: String?, cancel: Cancel?): Pair<Int, String>
    /** Names of the entries in an archive. */
    fun entries(archive: String): List<String>
    /** Packs files/folders into a .zip at [out]. Returns (files packed, output path). */
    fun pack(paths: List<String>, out: String, cancel: Cancel?): Pair<Int, String>
}

object ShellHooks {
    @Volatile var archive: ArchiveHook? = null
    /** Called after files were unpacked (the original re-indexes the disk for search). */
    @Volatile var onFilesChanged: (() -> Unit)? = null
}

/**
 * The original's virtual shell over the workspace: pipes, ; && || > >> <, variables, $(…), $((…)), globs, for / while /
 * until / if, test, and Unix text tools. One shell (cwd, variables) per conversation.
 */
class Shell private constructor() {
    var cwd = VPath.WS
    val vars = HashMap<String, String>()
    var last = 0
    @Volatile var ctx: ToolCtx? = null

    private class Glob(val g: String)
    private class Op(val op: String)
    private class Cmd : ArrayList<String>() { var stdinFile: String? = null }
    private class Redir(val mode: String, val path: String?)
    private class Chain(val pipeline: List<Cmd>, val redir: Redir?, val op: String?)

    private fun R(p: String) = VPath.norm(p, cwd)
    private fun cancel() { ctx?.cancel?.check() }

    // ---------------------------------------------------------------- tokenizer and simple command lines
    private fun tokenize(line: String): List<Any> {
        val out = ArrayList<Any>()
        val cur = StringBuilder(); var q: Char? = null; var has = false; var quoted = false
        fun push() { if (cur.isNotEmpty() || has) { val s = cur.toString(); out.add(if (!quoted && (s.contains('*') || s.contains('?'))) Glob(s) else s) }; cur.setLength(0); has = false; quoted = false }
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (q != null) {
                if (c == q) q = null else if (c == '\\' && q == '"' && i + 1 < line.length) cur.append(line[++i]) else cur.append(c)
                i++; continue
            }
            when {
                c == '\'' || c == '"' -> { q = c; has = true; quoted = true }
                c == '\\' && i + 1 < line.length -> { cur.append(line[++i]); has = true; quoted = true }
                c.isWhitespace() -> push()
                c == '<' -> { push(); out.add(Op("<")) }
                c == '|' || c == ';' || c == '>' || c == '&' -> {
                    push()
                    val n = line.getOrNull(i + 1)
                    if (c == '>' && n == '>') { out.add(Op(">>")); i++ } else if (c == '&' && n == '&') { out.add(Op("&&")); i++ } else if (c == '|' && n == '|') { out.add(Op("||")); i++ } else out.add(Op(c.toString()))
                }
                else -> { cur.append(c); has = true }
            }
            i++
        }
        if (q != null) throw ShErr("unterminated quote")
        push()
        return out
    }

    /** A command line without scripting (pipes, redirections, ; && ||). */
    private fun runSimple(line: String): ShResult {
        val toks = tokenize(line)
        val out = ArrayList<String>()
        val chains = ArrayList<Chain>()
        var cmd = Cmd(); var pipeline = arrayListOf(cmd); var redir: Redir? = null
        fun endPipeline(op: String?) { chains.add(Chain(pipeline, redir, op)); cmd = Cmd(); pipeline = arrayListOf(cmd); redir = null }
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            when (t) {
                is Glob -> cmd.addAll(glob(t.g))
                is Op -> when (t.op) {
                    "<" -> { val nx = toks.getOrNull(i + 1); if (nx is String) { cmd.stdinFile = nx; i++ } }
                    "|" -> { cmd = Cmd(); pipeline.add(cmd) }
                    ">", ">>" -> {
                        // one output stream: 2>&1 / 1>&2 merge nothing, 2>file and 2>/dev/null drop only "stderr"
                        val prev = cmd.lastOrNull(); val next = toks.getOrNull(i + 1)
                        if (next is Op && next.op == "&") { if (prev == "2" || prev == "1") cmd.removeAt(cmd.size - 1); i += 3; continue }
                        if (prev == "2") { cmd.removeAt(cmd.size - 1); i += 2; continue }
                        if (prev == "1") cmd.removeAt(cmd.size - 1)
                        redir = Redir(t.op, next as? String); i++
                    }
                    ";", "&&", "||" -> endPipeline(t.op)
                    "&" -> endPipeline(";")
                }
                else -> cmd.add(t as String)
            }
            i++
        }
        endPipeline(null)
        var status = 0
        var ci = 0
        while (ci < chains.size) {
            cancel()
            val ch = chains[ci]
            if (ci > 0 && chains[ci - 1].op == "&&" && status != 0) {
                if (chains.drop(ci).any { it.op == "||" }) { while (ci < chains.size && chains[ci].op != "||") ci++; ci++; continue }
                break
            }
            if (ci > 0 && chains[ci - 1].op == "||" && status == 0) { while (ci < chains.size && chains[ci].op == "||") ci++; ci++; continue }
            var stdin: String? = null; status = 0
            val sf = ch.pipeline.firstOrNull()?.stdinFile
            if (sf != null) {
                try { stdin = VPath.read(R(sf)) } catch (e: Exception) { out.add("$sf: no such file"); status = 1; ci++; continue }
            }
            for (c in ch.pipeline) {
                if (c.isEmpty()) continue
                try { stdin = exec(c[0], c.drop(1), stdin) }
                catch (e: CancelledException) { throw e }
                catch (e: ShErr) { if (!e.silent) out.add(if (e.plain) e.message ?: "" else "${c[0]}: ${e.message}"); status = e.exitCode; stdin = null; break }
                catch (e: Throwable) { out.add("${c[0]}: ${e.message ?: e.toString()}"); status = 1; stdin = null; break }
            }
            val r = ch.redir
            if (stdin != null) {
                if (r != null && r.path == "/dev/null") { /* discarded */ }
                else if (r?.path != null) {
                    val p = R(r.path)
                    try { if (r.mode == ">>") VPath.append(p, stdin) else VPath.write(p, stdin) }
                    catch (e: IllegalArgumentException) { out.add("sh: ${e.message}"); status = 1 }
                }
                else if (stdin != "") out.add(stdin.removeSuffix("\n"))
            }
            ci++
        }
        return ShResult(out.joinToString("\n"), status, cwd)
    }

    private fun flags(args: List<String>): Pair<Set<Char>, List<String>> {
        val f = HashSet<Char>(); val rest = ArrayList<String>()
        for (a in args) if (Regex("^-[a-zA-Z]+$").matches(a)) for (c in a.substring(1)) f.add(c) else rest.add(a)
        return Pair(f, rest)
    }

    private fun lines(s: String?): MutableList<String> { val l = (s ?: "").split("\n").toMutableList(); if (l.isNotEmpty() && l.last() == "") l.removeAt(l.size - 1); return l }
    private fun join(l: List<String>) = if (l.isEmpty()) "" else l.joinToString("\n") + "\n"
    private fun src(files: List<String>, stdin: String?) = if (files.isNotEmpty()) files.joinToString("") { VPath.read(R(it)) } else (stdin ?: "")
    private fun opt(args: MutableList<String>, name: String, def: String? = null): String? {
        val i = args.indexOf(name)
        if (i >= 0) { val v = args.getOrNull(i + 1); args.removeAt(i); if (i < args.size) args.removeAt(i); return v }
        val j = args.indexOfFirst { it.startsWith(name) && it.length > name.length && name.length == 2 }
        if (j >= 0) { val v = args[j].substring(2); args.removeAt(j); return v }
        return def
    }
    private fun has(args: MutableList<String>, flag: String): Boolean { val i = args.indexOf(flag); if (i >= 0) { args.removeAt(i); return true }; return false }
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\/", "/")
    private fun iso16(m: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(m))
    private fun isoFull(m: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(m))
    private fun fmtB(n: Long) = if (n < 1024) "$n B" else if (n < 1048576) String.format(Locale.US, "%.1f KB", n / 1024.0) else String.format(Locale.US, "%.2f MB", n / 1048576.0)

    // ---------------------------------------------------------------- commands
    private fun exec(cmd: String, args0: List<String>, stdin: String?): String {
        cancel()
        scriptBuiltin(cmd, args0, stdin)?.let { return it }
        if (cmd == "help") return HELP
        extra(cmd, args0.toMutableList(), stdin)?.let { return it }
        return core(cmd, args0, stdin)
    }

    private fun wr(vp: String): File = try { VPath.fileW(vp) } catch (e: IllegalArgumentException) { throw ShErr(e.message ?: "read-only: $vp") }

    private fun mkdirs(vp: String) { val f = wr(vp); if (f.isFile) throw ShErr("cannot create directory $vp: a file exists"); f.mkdirs() }

    private fun rm(vp: String, recursive: Boolean): Int {
        if (vp in VPath.PROTECTED) throw ShErr("refusing to remove $vp (a fixed folder of the disk)")
        val f = wr(vp)
        if (f.isFile) { f.delete(); VPath.commit(vp); return 1 }
        if (!f.isDirectory) throw ShErr("ENOENT: $vp")
        val n = f.walkTopDown().count { it.isFile }
        if (!recursive && (f.list()?.isNotEmpty() == true)) throw ShErr("$vp is a directory (use -r)")
        f.deleteRecursively()
        VPath.commit(vp)
        return n
    }

    /** dest as a path: into [b] when it is a folder. */
    private fun into(s: String, b: String) = if (VPath.isDir(b)) VPath.join(b, VPath.base(s)) else b

    private fun mv(a: String, b: String) {
        if (a in VPath.PROTECTED) throw ShErr("cannot move $a (a fixed folder of the disk)")
        val s = wr(a); if (!s.exists()) throw ShErr("ENOENT: $a")
        val to = into(a, b); val d = wr(to)
        if (s.isDirectory && d.canonicalPath.startsWith(s.canonicalPath + File.separator)) throw ShErr("cannot move $a into itself")
        d.parentFile?.mkdirs()
        if (!s.renameTo(d)) { s.copyRecursively(d, true); s.deleteRecursively() }
        VPath.commit(a); VPath.commit(to)
    }

    private fun cp(a: String, b: String) {
        val s = VPath.file(a); if (!s.exists()) throw ShErr("ENOENT: $a")
        val to = into(a, b); val d = wr(to)
        d.parentFile?.mkdirs()
        if (s.isDirectory) s.copyRecursively(d, true) else s.copyTo(d, true)
        VPath.commit(to)
    }

    private fun core(cmd: String, args: List<String>, stdin: String?): String {
        val (f, a) = flags(args)
        when (cmd) {
            "pwd" -> return cwd + "\n"
            "cd" -> { val p = R(a.getOrNull(0) ?: VPath.WS); if (!VPath.isDir(p)) throw ShErr("no such directory $p" + FileTools.hint(p)); cwd = p; return "" }
            "ls" -> {
                val p = R(a.getOrNull(0) ?: ".")
                if (VPath.isFile(p)) { val st = VPath.file(p); return if ('l' in f) "- ${st.length().toString().padStart(9)} ${iso16(st.lastModified())} $p  (${fmtB(st.length())})\n" else p + "\n" }
                if (!VPath.isDir(p)) throw ShErr("ENOENT: $p" + FileTools.hint(p))
                return VPath.list(p).filter { 'a' in f || p == "/" || !it.name.startsWith(".") }.joinToString("\n") { e ->
                    if ('l' in f) "${if (e.dir) "d" else "-"} ${(if (e.dir) "-" else e.size.toString()).padStart(9)} ${if (e.dir) "" else iso16(e.m)} ${e.name}${if (e.dir) "/" else ""}" else e.name + if (e.dir) "/" else ""
                } + "\n"
            }
            "tree" -> {
                val root = R(a.getOrNull(0) ?: "."); val out = arrayListOf(root)
                fun rec(d: String, pre: String) { val l = VPath.list(d); l.forEachIndexed { i, e -> val lastE = i == l.size - 1; out.add(pre + (if (lastE) "└── " else "├── ") + e.name + if (e.dir) "/" else ""); if (e.dir) rec(e.path, pre + if (lastE) "    " else "│   ") } }
                rec(root, ""); return out.joinToString("\n") + "\n"
            }
            "cat" -> return if (a.isNotEmpty()) a.joinToString("") { VPath.read(R(it)) } else (stdin ?: "")
            "head", "tail" -> {
                var n = 10; val i = args.indexOf("-n")
                val files = args.filterIndexed { j, x -> !(x == "-n" || (j == i + 1 && i >= 0)) && !Regex("^-\\d+$").matches(x) }
                if (i >= 0) n = args.getOrNull(i + 1)?.toIntOrNull() ?: 10
                args.firstOrNull { Regex("^-\\d+$").matches(it) }?.let { n = it.substring(1).toInt() }
                val L = lines(if (files.isNotEmpty()) VPath.read(R(files[0])) else (stdin ?: ""))
                return (if (cmd == "head") L.take(n) else L.takeLast(n)).joinToString("\n") + "\n"
            }
            "wc" -> {
                val s = if (a.isNotEmpty()) VPath.read(R(a[0])) else (stdin ?: "")
                val l = s.count { it == '\n' }; val w = Regex("\\S+").findAll(s).count(); val c = s.toByteArray().size
                return (if ('l' in f) "$l" else if ('w' in f) "$w" else if ('c' in f) "$c" else "$l $w $c") + (if (a.isNotEmpty()) " " + a[0] else "") + "\n"
            }
            "echo" -> return (if ('n' in f) a.joinToString(" ") else a.joinToString(" ") + "\n").replace("\\n", if ('e' in f) "\n" else "\\n")
            "printf" -> { val rest = a.drop(1).toMutableList(); return Regex("%s").replace((a.getOrNull(0) ?: "").replace("\\n", "\n")) { if (rest.isNotEmpty()) rest.removeAt(0) else "" } }
            "touch" -> { for (p in a) if (!VPath.exists(R(p))) VPath.write(R(p), ""); return "" }
            "mkdir" -> { for (p in a) mkdirs(R(p)); return "" }
            "rm" -> { for (p in a) { if (!VPath.exists(R(p))) { if ('f' in f) continue; throw ShErr("no such file " + R(p)) }; rm(R(p), 'r' in f || 'R' in f) }; return "" }
            "rmdir" -> { for (p in a) rm(R(p), false); return "" }
            "mv" -> { if (a.size < 2) throw ShErr("usage: mv SOURCE DEST"); mv(R(a[0]), R(a[1])); return "" }
            "cp" -> { if (a.size < 2) throw ShErr("usage: cp [-r] SOURCE DEST"); val s = R(a[0]); if (VPath.isDir(s) && !('r' in f || 'R' in f)) throw ShErr("-r not specified; omitting directory"); cp(s, R(a[1])); return "" }
            "grep" -> {
                val pat = a.getOrNull(0) ?: throw ShErr("usage: grep [-inrlc] PATTERN [PATH...]")
                val paths = a.drop(1)
                val re = Regex(pat, if ('i' in f) setOf(RegexOption.IGNORE_CASE) else emptySet())
                val res = ArrayList<String>()
                fun scan(name: String, text: String) {
                    var c = 0
                    lines(text).forEachIndexed { i, l -> if (re.containsMatchIn(l) != ('v' in f)) { c++; if (!('l' in f) && !('c' in f)) res.add((if (paths.size > 1 || 'r' in f) "$name:" else "") + (if ('n' in f) "${i + 1}:" else "") + l) } }
                    if ('l' in f && c > 0) res.add(name)
                    if ('c' in f) res.add((if (name.isNotEmpty()) "$name:" else "") + c)
                }
                if (paths.isEmpty()) scan("", stdin ?: "") else for (p in paths) {
                    val ap = R(p)
                    if (VPath.isDir(ap)) { if (!('r' in f) && !('R' in f)) throw ShErr("$ap is a directory (use -r)"); for (fp in VPath.walk(ap)) { cancel(); if (VPath.isText(fp)) scan(fp, VPath.read(fp)) } }
                    else scan(ap, VPath.read(ap))
                }
                if (res.isEmpty()) throw ShErr("(no matches)")
                return res.joinToString("\n") + "\n"
            }
            "find" -> {
                val root = R(args.firstOrNull()?.takeIf { !it.startsWith("-") } ?: ".")
                if (!VPath.exists(root)) throw ShErr("ENOENT: $root" + FileTools.hint(root))
                val ni = args.indexOf("-name"); val ti = args.indexOf("-type")
                val g = if (ni >= 0) args.getOrNull(ni + 1) else null; val type = if (ti >= 0) args.getOrNull(ti + 1) else null
                val re = g?.let { VPath.globRe(it) }
                val all = ((if (type == "f") emptyList() else VPath.walkDirs(root)) + (if (type == "d") emptyList() else VPath.walk(root))).filter { re == null || re.matches(VPath.base(it)) }.sorted()
                return all.joinToString("\n") + if (all.isNotEmpty()) "\n" else ""
            }
            "du" -> { val p = R(a.getOrNull(0) ?: "."); val n = VPath.walk(p).sumOf { VPath.file(it).length() }; return "${fmtB(n)}\t$p\n" }
            "date" -> return Date().toString() + "\n"
            "whoami" -> return "yaver\n"
            "curl", "wget" -> {
                val url = a.firstOrNull { Regex("^https?:").containsMatchIn(it) } ?: throw ShErr("usage: curl URL [-o FILE]")
                val oi = if (args.indexOf("-o") >= 0) args.indexOf("-o") else args.indexOf("-O")
                if (oi >= 0) {
                    val dest = args.getOrNull(oi + 1)?.takeIf { !Regex("^https?:").containsMatchIn(it) }
                    val r = WebPlus.downloadTo(url, dest?.let { R(it) }, ctx?.cancel)
                    return "saved ${fmtB(r.bytes.toLong())} to ${r.path} (${r.ct}, via ${r.via})\n"
                }
                val r = Http.request(url, headers = mapOf("Accept" to "*/*"), cancel = ctx?.cancel)
                return clip(r.text(), 20000)
            }
            "unzip", "gunzip" -> {
                val file = a.firstOrNull { !it.startsWith("-") } ?: throw ShErr("usage: $cmd ARCHIVE [-d DEST]")
                val di = args.indexOf("-d")
                val r = archive().unpack(R(file), if (di >= 0) args.getOrNull(di + 1)?.let { R(it) } else null, ctx?.cancel)
                ShellHooks.onFilesChanged?.invoke()
                return "unpacked ${r.first} files into ${r.second}\n"
            }
            "tar" -> {
                val fl = (args.firstOrNull { Regex("^-?[xctzvf]+$").matches(it) } ?: "").removePrefix("-")
                val fi = if (args.indexOf("-f") >= 0) args.indexOf("-f") + 1 else args.indexOfFirst { Regex("\\.(tar|tgz|tar\\.gz)$", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                val file = args.getOrNull(fi) ?: throw ShErr("usage: tar -xf ARCHIVE [-C DEST] | tar -tf ARCHIVE | tar -czf OUT.zip PATHS…")
                if ('t' in fl) return archive().entries(R(file)).joinToString("\n") + "\n"
                val ci = args.indexOf("-C")
                if ('c' in fl) {
                    val paths = args.filterIndexed { i, x -> i != fi && !x.startsWith("-") && !(ci >= 0 && i == ci + 1) }
                    val r = archive().pack(paths.map { R(it) }, R(file).replace(Regex("\\.(tar|tgz|tar\\.gz)$", RegexOption.IGNORE_CASE), ".zip"), ctx?.cancel)
                    return "created ${r.second} (zip format) with ${r.first} files\n"
                }
                val r = archive().unpack(R(file), if (ci >= 0) args.getOrNull(ci + 1)?.let { R(it) } else null, ctx?.cancel)
                ShellHooks.onFilesChanged?.invoke()
                return "unpacked ${r.first} files into ${r.second}\n"
            }
            "zip" -> {
                if (a.size < 2) throw ShErr("usage: zip [-r] OUT.zip PATHS…")
                val r = archive().pack(a.drop(1).map { R(it) }, R(a[0]), ctx?.cancel)
                return "created ${r.second} with ${r.first} files\n"
            }
            "python", "python3" -> return shellPython(args, stdin)
            "pip", "pip3" -> return CodeEngine.pip(args, ctx?.convo?.id, ctx?.cancel) + "\n"
            "node", "js" -> {
                val code = if (a.isNotEmpty() && a[0] == "-e") a.drop(1).joinToString(" ") else if (args.isNotEmpty() && args[0] == "-e") args.drop(1).joinToString(" ") else if (a.isNotEmpty()) VPath.read(R(a[0])) else (stdin ?: "")
                val r = CodeEngine.js(code, null, persist = false, timeoutSec = 120, reset = false, ctx = null, cancel = ctx?.cancel)
                return r.second + "\n"
            }
        }
        throw ShErr("command not found (this is a virtual shell over yaver's workspace on the phone; run 'help'). For computation use python or execute_code.")
    }

    private fun archive(): ArchiveHook = ShellHooks.archive ?: throw ShErr("archives are not available in this version of yaver")

    private fun shellPython(args: List<String>, stdin: String?): String {
        if (args.firstOrNull() == "--version" || args.firstOrNull() == "-V") return "Python ${CodeEngine.pyVersion ?: "3.13"} (Pyodide ${CodeEngine.PY_VERSION}, runs inside yaver on the phone)\n"
        if (args.getOrNull(0) == "-m" && args.getOrNull(1) == "pip") return CodeEngine.pip(args.drop(2), ctx?.convo?.id, ctx?.cancel) + "\n"
        var script: String? = null; var argv: List<String>? = null
        val code: String
        if (args.firstOrNull() == "-c") { code = args.getOrNull(1) ?: ""; argv = listOf("-c") + args.drop(2) }
        else if (args.isNotEmpty()) { val p = R(args[0]); code = VPath.read(p); script = p; argv = listOf(p) + args.drop(1) }
        else if (!stdin.isNullOrEmpty()) code = stdin
        else throw ShErr("interactive mode is not available; use python -c \"...\", python FILE, or execute_code with language python")
        val r = CodeEngine.python(code, ctx?.convo?.id, cwd, script, argv, 300, false, ctx?.cancel, ctx?.let { c -> { s: String -> c.progress(s) } })
        return r.second + "\n"
    }

    // ---------------------------------------------------------------- more Unix tools
    private fun bre(re: String, ext: Boolean) = if (ext) re else Regex("\\\\([(){}|+?])").replace(re) { it.groupValues[1] }

    /** JS-style replacement ($1…$99, $&, $$) of [re] in [s]: all matches, or only the nth (1-based) when nth > 0. */
    private fun jsReplace(s: String, re: Regex, rep: String, global: Boolean, nth: Int = 0): String {
        val sb = StringBuilder(); var lastEnd = 0; var k = 0
        for (m in re.findAll(s)) {
            k++
            if (nth > 0 && k != nth) continue
            sb.append(s, lastEnd, m.range.first)
            var i = 0
            while (i < rep.length) {
                val c = rep[i]
                if (c == '$' && i + 1 < rep.length) {
                    val n = rep[i + 1]
                    if (n == '$') { sb.append('$'); i += 2; continue }
                    if (n == '&') { sb.append(m.value); i += 2; continue }
                    if (n.isDigit()) {
                        var j = i + 1; while (j < rep.length && rep[j].isDigit() && j < i + 3) j++
                        var num = rep.substring(i + 1, j).toInt()
                        if (num > m.groupValues.size - 1 && j - i > 2) { j--; num = rep.substring(i + 1, j).toInt() }
                        if (num in 1 until m.groupValues.size) { sb.append(m.groups[num]?.value ?: ""); i = j; continue }
                    }
                }
                sb.append(c); i++
            }
            lastEnd = m.range.last + 1
            if (!global && nth == 0) break
            if (nth > 0) break
        }
        sb.append(s, lastEnd, s.length)
        return sb.toString()
    }

    private class SedAddr(val n: Int? = null, val last: Boolean = false, val re: Regex? = null)
    private class SedCmd(val addr: List<SedAddr?>?, val neg: Boolean, val c: Char, val re: Regex? = null, val global: Boolean = false, val rep: String = "", val nth: Int = 0, val print: Boolean = false, val from: String = "", val to: String = "", val text: String = "")

    private fun sed(args: MutableList<String>, stdin: String?): String {
        val inPlace = has(args, "-i"); val quiet = has(args, "-n"); val ext = has(args, "-E") || has(args, "-r")
        val scripts = ArrayList<String>()
        while (true) { val e = opt(args, "-e") ?: break; scripts.add(e) }
        if (scripts.isEmpty()) { if (args.isEmpty()) throw ShErr("usage: sed [-n] [-i] [-E] SCRIPT [FILE...]"); scripts.add(args.removeAt(0)) }
        val cmds = ArrayList<SedCmd>()
        for (sc in scripts) {
            var s = sc.trim()
            while (s.isNotEmpty()) {
                fun pa(): SedAddr? {
                    Regex("^(\\d+|\\$)").find(s)?.let { m -> s = s.substring(m.value.length); return if (m.value == "$") SedAddr(last = true) else SedAddr(n = m.value.toInt()) }
                    if (s.startsWith("/")) { val end = s.indexOf('/', 1); val re = Regex(bre(s.substring(1, end), ext)); s = s.substring(end + 1); return SedAddr(re = re) }
                    return null
                }
                var addr: MutableList<SedAddr?>? = null
                val a1 = pa()
                if (a1 != null) { addr = mutableListOf(a1); if (s.startsWith(",")) { s = s.substring(1); addr.add(pa()) } }
                s = s.trimStart()
                val neg = s.startsWith("!"); if (neg) s = s.substring(1).trimStart()
                if (s.isEmpty()) break
                val c = s[0]; s = s.substring(1)
                if (c == 's' || c == 'y') {
                    val d = s[0]; val parts = ArrayList<String>(); val cur = StringBuilder(); var i = 1
                    while (i < s.length && parts.size < 2) {
                        if (s[i] == '\\' && i + 1 < s.length && s[i + 1] == d) { cur.append(d); i += 2; continue }
                        if (s[i] == d) { parts.add(cur.toString()); cur.setLength(0); i++; continue }
                        cur.append(s[i]); i++
                    }
                    while (parts.size < 2) parts.add(cur.toString().also { cur.setLength(0) })
                    val fl = Regex("^[gipI0-9]*").find(s.substring(minOf(i, s.length)))?.value ?: ""
                    s = s.substring(minOf(s.length, i + fl.length))
                    if (c == 's') {
                        val opts = if (Regex("[iI]").containsMatchIn(fl)) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        val nth = Regex("\\d+").find(fl)?.value?.toInt() ?: 0
                        var rep = Regex("\\\\(\\d)").replace(unesc(parts[1])) { "$" + it.groupValues[1] }
                        rep = Regex("(^|[^\\\\])&").replace(rep) { it.groupValues[1] + "$&" }
                        rep = rep.replace("\\&", "&")
                        cmds.add(SedCmd(addr, neg, c, re = Regex(bre(parts[0], ext), opts), global = fl.contains('g'), rep = rep, nth = nth, print = fl.contains('p')))
                    } else cmds.add(SedCmd(addr, neg, c, from = unesc(parts[0]), to = unesc(parts[1])))
                } else if ("pdqP=".contains(c)) cmds.add(SedCmd(addr, neg, c))
                else if (c == 'a' || c == 'i' || c == 'c') {
                    val t = s.replace(Regex("^\\\\?\\s*"), ""); val end = t.indexOf(';')
                    cmds.add(SedCmd(addr, neg, c, text = unesc(if (end < 0) t else t.substring(0, end))))
                    s = if (end < 0) "" else t.substring(end)
                } else throw ShErr("sed: unsupported command '$c'", plain = true)
                s = s.replace(Regex("^[\\s;]+"), "")
            }
        }
        fun run(text: String): String {
            val L = lines(text); val out = ArrayList<String>(); val range = HashMap<Int, Boolean>(); var quit = false
            L.forEachIndexed { idx, line ->
                if (quit) return@forEachIndexed
                var cur = line; var del = false; val n = idx + 1; val lastLine = idx == L.size - 1
                val before = ArrayList<String>(); val after = ArrayList<String>()
                for ((k, c) in cmds.withIndex()) {
                    if (del) break
                    fun hit(a: SedAddr?): Boolean = a != null && (if (a.last) lastLine else if (a.n != null) n == a.n else a.re!!.containsMatchIn(cur))
                    var on = true
                    val ad = c.addr
                    if (ad != null) {
                        if (ad.size == 1) on = hit(ad[0]) else {
                            val a2 = ad[1]
                            if (range[k] == true) { on = true; if (a2 != null && (if (a2.n != null) n >= a2.n else hit(a2))) range.remove(k) }
                            else if (hit(ad[0])) { on = true; if (!(a2?.n != null && a2.n <= n)) range[k] = true }
                            else on = false
                        }
                    }
                    if (c.neg) on = !on
                    if (!on) continue
                    when (c.c) {
                        's' -> { val nx = jsReplace(cur, c.re!!, c.rep, c.global, c.nth); if (nx != cur && c.print) after.add(nx); cur = nx }
                        'y' -> cur = cur.map { ch -> val j = c.from.indexOf(ch); if (j >= 0 && j < c.to.length) c.to[j] else ch }.joinToString("")
                        'p' -> out.add(cur)
                        'P' -> out.add(cur.split("\n")[0])
                        '=' -> out.add(n.toString())
                        'd' -> del = true
                        'q' -> quit = true
                        'a' -> after.add(c.text)
                        'i' -> before.add(c.text)
                        'c' -> cur = c.text
                    }
                }
                out.addAll(before); if (!del && !quiet) out.add(cur); out.addAll(after)
            }
            return join(out)
        }
        if (inPlace) { for (p in args) VPath.write(R(p), run(VPath.read(R(p)))); return "" }
        return run(src(args, stdin))
    }

    /** jq: . .a .a.b .[0] .[] keys length map(.x) select(.x == 1) | pipes, -r raw, -c compact */
    private fun jq(args: MutableList<String>, stdin: String?): String {
        val raw = has(args, "-r"); val compact = has(args, "-c")
        val f = if (args.isNotEmpty()) args.removeAt(0) else "."
        val text = src(args, stdin).trim().ifEmpty { "null" }
        val data: Any? = org.json.JSONTokener(text).nextValue()
        fun eq(l: Any?, r: Any?): Boolean = if (l is Number && r is Number) l.toDouble() == r.toDouble() else if ((l == null || l == JSONObject.NULL) && (r == null || r == JSONObject.NULL)) true else l == r
        fun cmp(l: Any?, r: Any?): Int = if (l is Number && r is Number) l.toDouble().compareTo(r.toDouble()) else l.toString().compareTo(r.toString())
        lateinit var run: (List<Any?>, String) -> List<Any?>
        fun step(vals: List<Any?>, expr0: String): List<Any?> {
            val expr = expr0.trim(); if (expr == "." || expr.isEmpty()) return vals
            val out = ArrayList<Any?>()
            for (v in vals) {
                if (expr == "keys") { out.add(JSONArray(((v as? JSONObject)?.keys()?.asSequence()?.toList() ?: if (v is JSONArray) (0 until v.length()).map { it } else emptyList<Any>()).let { l -> if (v is JSONObject) l.map { it.toString() }.sorted() else l })); continue }
                if (expr == "length") { out.add(when (v) { null, JSONObject.NULL -> 0; is JSONObject -> v.length(); is JSONArray -> v.length(); else -> v.toString().length }); continue }
                val mm = Regex("^map\\((.*)\\)$").find(expr)
                if (mm != null) { val arr = v as? JSONArray ?: JSONArray(); val r = JSONArray(); for (i in 0 until arr.length()) for (x in run(listOf(arr.opt(i)), mm.groupValues[1])) r.put(x ?: JSONObject.NULL); out.add(r); continue }
                val sm = Regex("^select\\((.*)\\)$").find(expr)
                if (sm != null) {
                    val c = Regex("^(.*?)\\s*(==|!=|>=|<=|>|<)\\s*(.*)$").find(sm.groupValues[1])
                    val ok = if (c != null) {
                        val l = run(listOf(v), c.groupValues[1]).firstOrNull(); val rs = c.groupValues[3].replace(Regex("^'(.*)'$"), "\"$1\"")
                        val r = org.json.JSONTokener(rs).nextValue()
                        when (c.groupValues[2]) { "==" -> eq(l, r); "!=" -> !eq(l, r); ">" -> cmp(l, r) > 0; "<" -> cmp(l, r) < 0; ">=" -> cmp(l, r) >= 0; else -> cmp(l, r) <= 0 }
                    } else run(listOf(v), sm.groupValues[1]).firstOrNull().let { it != null && it != JSONObject.NULL && it != false }
                    if (ok) out.add(v); continue
                }
                var cur: List<Any?> = listOf(v)
                val parts = Regex("\\.[A-Za-z_][\\w-]*|\\.?\\[\"[^\"]+\"]|\\.?\\[\\d*]|\\.?\\[]").findAll(expr).map { it.value }.toList()
                if (parts.joinToString("") != expr.replace(Regex("\\s+"), "")) throw ShErr("jq: unsupported filter $expr", plain = true)
                for (p in parts) {
                    val nx = ArrayList<Any?>()
                    for (c in cur) {
                        if (p.endsWith("[]")) { when (c) { is JSONArray -> for (i in 0 until c.length()) nx.add(c.opt(i)); is JSONObject -> for (k in c.keys()) nx.add(c.opt(k)) } }
                        else { val im = Regex("\\[(\\d+)]").find(p); if (im != null) nx.add((c as? JSONArray)?.opt(im.groupValues[1].toInt())) else { val k = p.replace(Regex("^\\.?\\[\"?|\"?]$"), "").removePrefix("."); nx.add((c as? JSONObject)?.opt(k)) } }
                    }
                    cur = nx
                }
                out.addAll(cur)
            }
            return out
        }
        run = { vals, prog -> prog.split("|").fold(vals) { acc, e -> step(acc, e) } }
        fun str(x: Any?): String = when (x) {
            null, JSONObject.NULL -> "null"
            is String -> if (raw) x else JSONObject.quote(x)
            is JSONObject -> if (compact) x.toString() else x.toString(2)
            is JSONArray -> if (compact) x.toString() else x.toString(2)
            is Double -> if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString() else x.toString()
            else -> x.toString()
        }
        return run(listOf(data), f).joinToString("\n") { str(it) } + "\n"
    }

    private fun num2s(x: Double) = if (x == Math.floor(x) && !x.isInfinite() && Math.abs(x) < 1e15) x.toLong().toString() else x.toString()

    private fun extra(cmd: String, args: MutableList<String>, stdin: String?): String? {
        when (cmd) {
            "uniq" -> {
                val c = has(args, "-c"); val d = has(args, "-d"); val u = has(args, "-u"); val ic = has(args, "-i")
                class G(val k: String, val l: String, var n: Int)
                val out = ArrayList<G>()
                for (l in lines(src(args, stdin))) { val k = if (ic) l.lowercase() else l; val lastG = out.lastOrNull(); if (lastG != null && lastG.k == k) lastG.n++ else out.add(G(k, l, 1)) }
                return join(out.filter { (!d || it.n > 1) && (!u || it.n == 1) }.map { if (c) "${it.n.toString().padStart(7)} ${it.l}" else it.l })
            }
            "sort" -> {
                val t = opt(args, "-t"); val k = opt(args, "-k")
                val fl = HashSet<Char>()
                val rest = args.filter { a -> if (Regex("^-[nrufhV]+$").matches(a)) { for (ch in a.substring(1)) fl.add(ch); false } else true }
                val L = lines(src(rest, stdin))
                fun key(l: String): String {
                    if (k == null) return l
                    val ab = k.split(","); val fa = ab[0].takeWhile { it.isDigit() }.toIntOrNull() ?: 1; val fb = ab.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: fa
                    val parts = if (t != null) l.split(t) else l.trim().split(Regex("\\s+"))
                    return parts.subList(minOf(parts.size, fa - 1), minOf(parts.size, fb)).joinToString(t ?: " ")
                }
                fun num(x: String) = Regex("-?\\d+(\\.\\d+)?").find(x)?.value?.toDouble() ?: 0.0
                val coll = Collator.getInstance(Locale("tr", "TR"))
                val sorted = L.sortedWith { x, y -> var a = key(x); var b = key(y); if ('f' in fl) { a = a.lowercase(); b = b.lowercase() }; if ('n' in fl || 'h' in fl) num(a).compareTo(num(b)) else coll.compare(a, b) }.toMutableList()
                if ('r' in fl) sorted.reverse()
                return join(if ('u' in fl) sorted.filterIndexed { i, x -> i == 0 || key(x) != key(sorted[i - 1]) } else sorted)
            }
            "sed" -> return sed(args, stdin)
            "awk", "gawk" -> {
                val files = JSONObject()
                for (a in args) try { val p = R(a); if (VPath.isText(p)) files.put(a, VPath.read(p)) } catch (_: Exception) {}
                return CodeEngine.awk(JSONArray(args), files, stdin ?: "", ctx?.cancel)
            }
            "jq" -> return jq(args, stdin)
            "cut" -> {
                val d = opt(args, "-d", "\t")!!; val fl = opt(args, "-f"); val ch = opt(args, "-c")
                fun sel(spec: String): (Int) -> Boolean {
                    val want = spec.split(",").map { part -> val m = Regex("^(\\d*)-?(\\d*)$").find(part); val a = m?.groupValues?.get(1)?.toIntOrNull() ?: 1; val b = if (part.contains("-")) (m?.groupValues?.get(2)?.toIntOrNull() ?: 1_000_000_000) else a; a to b }
                    return { i -> want.any { i >= it.first && i <= it.second } }
                }
                return join(lines(src(args, stdin)).map { l ->
                    if (ch != null) { val ok = sel(ch); l.filterIndexed { i, _ -> ok(i + 1) } }
                    else {
                        if (fl == null) throw ShErr("cut: use -f FIELDS or -c CHARS", plain = true)
                        val ok = sel(fl); val p = l.split(d)
                        if (p.size == 1 && !l.contains(d)) l else p.filterIndexed { i, _ -> ok(i + 1) }.joinToString(d)
                    }
                })
            }
            "tr" -> {
                val del = has(args, "-d"); val sq = has(args, "-s")
                fun ex(s: String?): String = Regex("(.)-(.)").replace(unesc(s ?: "").replace("[:upper:]", "A-Z").replace("[:lower:]", "a-z").replace("[:digit:]", "0-9").replace("[:space:]", " \t\n")) { m -> val a = m.groupValues[1][0]; val b = m.groupValues[2][0]; (a..b).joinToString("") }
                val A = ex(args.getOrNull(0)); val B = ex(args.getOrNull(1))
                var t = stdin ?: ""
                if (del) t = t.filter { !A.contains(it) } else if (B.isNotEmpty()) t = t.map { c -> val i = A.indexOf(c); if (i < 0) c else B[minOf(i, B.length - 1)] }.joinToString("")
                if (sq) { val set = B.ifEmpty { A }; t = Regex("(.)\\1+", RegexOption.DOT_MATCHES_ALL).replace(t) { m -> if (set.contains(m.groupValues[1])) m.groupValues[1] else m.value } }
                return t
            }
            "xargs" -> {
                val n = opt(args, "-n", "0")!!.toIntOrNull() ?: 0; val rep = opt(args, "-I")
                val items = (stdin ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }
                val base = if (args.isNotEmpty()) args.toList() else listOf("echo")
                val sb = StringBuilder()
                val groups = if (rep != null) items.map { listOf(it) } else if (n > 0) items.chunked(n) else listOf(items)
                for (g in groups) {
                    val line = if (rep != null) base.map { it.split(rep).joinToString(g[0]) } else base + g
                    val r = run(line.joinToString(" ") { if (Regex("[\\s\"'$;|&<>]").containsMatchIn(it)) "'" + it.replace("'", "'\\''") + "'" else it })
                    if (r.output.isNotEmpty()) sb.append(r.output).append("\n")
                }
                return sb.toString()
            }
            "tee" -> { val app = has(args, "-a"); for (p in args) { if (app) VPath.append(R(p), stdin ?: "") else VPath.write(R(p), stdin ?: "") }; return stdin ?: "" }
            "rev" -> return join(lines(src(args, stdin)).map { it.reversed() })
            "tac" -> return join(lines(src(args, stdin)).reversed())
            "nl" -> return join(lines(src(args, stdin)).mapIndexed { i, l -> "${(i + 1).toString().padStart(6)}\t$l" })
            "shuf" -> return join(lines(src(args, stdin)).shuffled())
            "paste" -> {
                val d = unesc(opt(args, "-d", "\t")!!); val ser = has(args, "-s")
                if (ser) return join(if (args.isNotEmpty()) args.map { lines(VPath.read(R(it))).joinToString(d) } else listOf(lines(stdin).joinToString(d)))
                val cols = args.map { lines(if (it == "-") stdin else VPath.read(R(it))) }
                val n = cols.maxOfOrNull { it.size } ?: 0
                return join((0 until n).map { i -> cols.joinToString(d) { it.getOrNull(i) ?: "" } })
            }
            "column" -> {
                val sep = opt(args, "-s"); has(args, "-t")
                val rows = lines(src(args, stdin)).map { if (sep != null) it.split(sep) else it.trim().split(Regex("\\s+")) }
                val w = ArrayList<Int>()
                rows.forEach { r -> r.forEachIndexed { i, c -> if (w.size <= i) w.add(0); w[i] = maxOf(w[i], c.length) } }
                return join(rows.map { r -> r.mapIndexed { i, c -> if (i < r.size - 1) c.padEnd(w[i]) else c }.joinToString("  ") })
            }
            "fold" -> { val w = opt(args, "-w", "80")!!.toIntOrNull() ?: 80; return join(lines(src(args, stdin)).flatMap { l -> if (l.isEmpty()) listOf("") else l.chunked(maxOf(1, w)) }) }
            "diff" -> {
                val xy = args.filter { !it.startsWith("-") }; val x = xy.getOrNull(0); val y = xy.getOrNull(1)
                if (x == null || y == null) throw ShErr("usage: diff FILE1 FILE2")
                val A = lines(VPath.read(R(x))); val B = lines(VPath.read(R(y)))
                if (A.joinToString("\n") == B.joinToString("\n")) return ""
                val n = A.size; val m = B.size
                val L = Array(n + 1) { IntArray(m + 1) }
                for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) L[i][j] = if (A[i] == B[j]) L[i + 1][j + 1] + 1 else maxOf(L[i + 1][j], L[i][j + 1])
                val out = arrayListOf("--- $x", "+++ $y"); var i = 0; var j = 0
                while (i < n || j < m) {
                    if (i < n && j < m && A[i] == B[j]) { out.add("  " + A[i]); i++; j++ }
                    else if (j < m && (i >= n || L[i][j + 1] >= L[i + 1][j])) out.add("+ " + B[j++])
                    else out.add("- " + A[i++])
                }
                throw ShErr(join(out).removeSuffix("\n"), exitCode = 1, plain = true)
            }
            "base64" -> {
                val d = has(args, "-d") || has(args, "--decode")
                if (d) return String(java.util.Base64.getMimeDecoder().decode(src(args, stdin).replace(Regex("\\s+"), "")), Charsets.UTF_8)
                val bytes = if (args.isNotEmpty()) VPath.readBytes(R(args[0])) else (stdin ?: "").toByteArray()
                return java.util.Base64.getEncoder().encodeToString(bytes).replace(Regex(".{76}"), "$0\n") + "\n"
            }
            "sha256sum", "sha1sum", "md5sum" -> {
                val alg = when (cmd) { "sha1sum" -> "SHA-1"; "md5sum" -> "MD5"; else -> "SHA-256" }
                fun one(b: ByteArray, name: String) = MessageDigest.getInstance(alg).digest(b).joinToString("") { "%02x".format(it) } + "  " + name
                val out = ArrayList<String>()
                if (args.isEmpty()) out.add(one((stdin ?: "").toByteArray(), "-"))
                for (p in args) out.add(one(VPath.readBytes(R(p)), p))
                return join(out)
            }
            "stat" -> return join(args.map { p -> val q = R(p); if (!VPath.exists(q)) throw ShErr("no such file $q"); val st = VPath.file(q); "  File: $q\n  Size: ${if (st.isFile) st.length() else 0}\tType: ${if (st.isDirectory) "directory" else "regular file"}\nModify: ${isoFull(st.lastModified())}" })
            "file" -> return join(args.map { p -> val q = R(p); if (VPath.isDir(q)) "$p: directory" else if (!VPath.isFile(q)) "$p: cannot open" else "$p: ${if (VPath.isText(q)) "text" else "data"} (${Workspace.mime(q)})" })
            "env", "printenv" -> {
                val env = linkedMapOf("HOME" to VPath.HOME, "PWD" to cwd, "SHELL" to "/bin/yaver-sh", "USER" to "yaver"); env.putAll(vars)
                return if (args.isNotEmpty()) join(args.map { env[it] ?: "" }) else join(env.map { "${it.key}=${it.value}" })
            }
            "which", "type", "command" -> {
                val known = listOf("sed", "awk", "jq", "cut", "tr", "xargs", "tee", "diff", "grep", "find", "sort", "uniq", "python", "node", "ls", "cat")
                return join(args.filter { it != "-v" }.map { a -> if (a in known || Regex("^(cd|pwd|echo|printf|test|seq)$").matches(a)) "/usr/bin/$a" else "$a: not found" })
            }
            "realpath", "readlink" -> return join(args.filter { !it.startsWith("-") }.map { R(it) })
            "ln" -> {
                val xy = args.filter { !it.startsWith("-") }; if (xy.size < 2) throw ShErr("usage: ln [-s] TARGET LINK")
                val t = R(xy[0]); VPath.writeBytes(R(xy[1]), VPath.readBytes(t))
                return "(links are not supported in the workspace: copied $t → ${R(xy[1])})\n"
            }
            "uname" -> return (if (args.contains("-a")) "yaver-sh android-workspace 1.0" else "yaver-sh") + "\n"
            "hostname" -> return "phone\n"
            "id" -> return "uid=1000(yaver) gid=1000(yaver)\n"
            "df" -> {
                fun used(p: String) = try { VPath.walk(p).sumOf { VPath.file(it).length() } } catch (e: Exception) { 0L }
                return "Area              Used        Mode\n/workspace        ${fmtB(used(VPath.WS)).padEnd(12)}rw\n/.yaver/skills    ${fmtB(used(VPath.SKILLS)).padEnd(12)}rw\n/.yaver/memories  ${fmtB(used(VPath.MEMORIES)).padEnd(12)}ro\n/.yaver/sessions  ${fmtB(used(VPath.SESSIONS)).padEnd(12)}ro\n/tmp              ${fmtB(used(VPath.TMP)).padEnd(12)}rw\n"
            }
            "less", "more", "most" -> return src(args.filter { !it.startsWith("-") }, stdin)
            "clear", "reset" -> return ""
            "history" -> return "(no history in this shell)\n"
            "ps" -> return "  PID CMD\n    1 yaver-sh\n"
            "kill", "killall" -> return "(no processes to signal in this shell)\n"
            "chmod", "chown", "sync" -> return ""
        }
        return null
    }

    // ---------------------------------------------------------------- scripting: variables, $(…), $((…)), globs, loops, if, test
    private sealed class Node
    private class NCmd(val s: String) : Node()
    private class NFor(val v: String, val words: String, val body: List<Node>) : Node()
    private class NLoop(val kind: String, val cond: String, val body: List<Node>) : Node()
    private class Arm(val cond: String, val body: List<Node>)
    private class NIf(val arms: List<Arm>, val els: List<Node>?) : Node()
    private class St(var steps: Int = 0, var flow: String? = null)

    private val KW = Regex("^(for|while|until|if|do|done|then|elif|else|fi)$")

    /** Statements split at ; and newlines (quotes, $( ), $(( )) and `…` aware); && and | stay inside a statement. */
    private fun split(src: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q: Char? = null; var depth = 0
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (q != null) { cur.append(c); if (c == '\\' && q == '"' && i + 1 < src.length) { cur.append(src[++i]); i++; continue }; if (c == q) q = null; i++; continue }
            when {
                c == '\'' || c == '"' || c == '`' -> { q = c; cur.append(c) }
                c == '\\' && i + 1 < src.length -> { cur.append(c).append(src[++i]) }
                c == '$' && src.getOrNull(i + 1) == '(' -> { depth++; cur.append("$("); i++ }
                c == '(' && depth > 0 -> { depth++; cur.append(c) }
                c == ')' && depth > 0 -> { depth--; cur.append(c) }
                depth == 0 && (c == ';' || c == '\n') && !(c == ';' && src.getOrNull(i + 1) == ';') -> { if (cur.isNotBlank()) out.add(cur.toString().trim()); cur.setLength(0) }
                depth == 0 && c == '#' && (cur.isEmpty() || cur.last().isWhitespace()) -> { while (i < src.length && src[i] != '\n') i++; i-- }
                else -> cur.append(c)
            }
            i++
        }
        if (q != null) throw ShErr("unterminated quote")
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private fun explode(segs: List<String>): List<String> {
        val out = ArrayList<String>()
        for (s in segs) { val m = Regex("^(do|then|else)\\s+([\\s\\S]+)$").find(s); if (m != null) { out.add(m.groupValues[1]); out.add(m.groupValues[2]) } else out.add(s) }
        return out
    }

    private fun first(s: String) = Regex("^\\S+").find(s)?.value ?: ""

    private fun parse(segs: List<String>, i0: Int, stop: List<String>): Pair<List<Node>, Int> {
        val nodes = ArrayList<Node>(); var i = i0
        while (i < segs.size) {
            val s = segs[i]; val w = first(s)
            if (w in stop) return Pair(nodes, i)
            if (w == "for") {
                val m = Regex("^for\\s+([A-Za-z_]\\w*)(?:\\s+in\\s+([\\s\\S]*))?$").find(s) ?: throw ShErr("syntax: for NAME in WORDS; do …; done")
                if (segs.getOrNull(i + 1) != "do") throw ShErr("syntax: 'do' expected after for")
                val (body, j) = parse(segs, i + 2, listOf("done"))
                if (segs.getOrNull(j) != "done") throw ShErr("syntax: 'done' missing")
                nodes.add(NFor(m.groupValues[1], if (m.groups[2] == null) "\"$@\"" else m.groupValues[2], body)); i = j + 1; continue
            }
            if (w == "while" || w == "until") {
                val cond = s.substring(w.length).trim()
                if (segs.getOrNull(i + 1) != "do") throw ShErr("syntax: 'do' expected after $w")
                val (body, j) = parse(segs, i + 2, listOf("done"))
                if (segs.getOrNull(j) != "done") throw ShErr("syntax: 'done' missing")
                nodes.add(NLoop(w, cond, body)); i = j + 1; continue
            }
            if (w == "if") {
                val arms = ArrayList<Arm>(); var cond = s.substring(2).trim(); var j = i; var els: List<Node>? = null
                while (true) {
                    if (segs.getOrNull(j + 1) != "then") throw ShErr("syntax: 'then' expected")
                    val (body, k) = parse(segs, j + 2, listOf("elif", "else", "fi"))
                    arms.add(Arm(cond, body))
                    val kw = first(segs.getOrNull(k) ?: "")
                    if (kw == "elif") { cond = segs[k].substring(4).trim(); j = k; continue }
                    if (kw == "else") { val (eb, k2) = parse(segs, k + 1, listOf("fi")); els = eb; j = k2 } else j = k
                    break
                }
                if (first(segs.getOrNull(j) ?: "") != "fi") throw ShErr("syntax: 'fi' missing")
                nodes.add(NIf(arms, els)); i = j + 1; continue
            }
            if (KW.matches(w)) throw ShErr("syntax error near '$w'")
            nodes.add(NCmd(s)); i++
        }
        return Pair(nodes, i)
    }

    /** $((…)) arithmetic, $(…) and `…` command output, $VAR ${VAR} $? — not inside single quotes. */
    private fun expand(s: String, st: St): String {
        val out = StringBuilder(); var q: Char? = null; var i = 0
        fun put(v0: Any) { val v = v0.toString(); out.append(if (q == '"') v.replace(Regex("([\"\\\\])"), "\\\\$1") else v) }
        while (i < s.length) {
            val c = s[i]
            if (q == '\'') { out.append(c); if (c == '\'') q = null; i++; continue }
            if (c == '\'' && q == null) { q = '\''; out.append(c); i++; continue }
            if (c == '"') { q = if (q == '"') null else '"'; out.append(c); i++; continue }
            if (c == '\\' && i + 1 < s.length) { out.append(c).append(s[i + 1]); i += 2; continue }
            if (c == '$' && s.startsWith("$((", i)) {
                var d = 0; var j = i + 1
                while (j < s.length) { if (s[j] == '(') d++ else if (s[j] == ')') { d--; if (d == 0) break }; j++ }
                val inner = s.substring(i + 3, maxOf(i + 3, j - 1))
                val expr = Regex("\\b[A-Za-z_]\\w*\\b").replace(expand(inner, st)) { m -> val v = vars[m.value]; if (v.isNullOrEmpty()) "0" else v }
                put(arith(expr)); i = j + 1; continue
            }
            if ((c == '$' && s.getOrNull(i + 1) == '(') || c == '`') {
                val j: Int; val inner: String
                if (c == '`') { j = s.indexOf('`', i + 1); if (j < 0) throw ShErr("unterminated `"); inner = s.substring(i + 1, j) }
                else { var d = 0; var k = i + 1; while (k < s.length) { if (s[k] == '(') d++ else if (s[k] == ')') { d--; if (d == 0) break }; k++ }; j = k; inner = s.substring(i + 2, minOf(k, s.length)) }
                val r = script(inner, St(st.steps))
                put(r.first.replace(Regex("\\n+$"), "")); i = j + 1; continue
            }
            if (c == '$') {
                val m = Regex("^(\\{[A-Za-z_]\\w*}|[A-Za-z_]\\w*|\\?|#)").find(s.substring(i + 1))
                if (m != null) { val n = m.value.replace("{", "").replace("}", ""); put(if (n == "?") last else if (n == "#") 0 else (vars[n] ?: "")); i += m.value.length + 1; continue }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    /** Arithmetic like the original's JS evaluation of $((…)): numbers, + - * / % ( ) comparisons ! && ||; the result is truncated. */
    private fun arith(e: String): Long {
        val x = e.replace(Regex("\\s+"), " ").trim()
        if (!Regex("^[\\d\\s+\\-*/%()<>=!&|.]*$").matches(x)) throw ShErr("arithmetic: only numbers and + - * / % ( ) < > = ! & | are allowed: $x")
        if (x.isEmpty()) return 0
        val t = x.replace(" ", ""); var p = 0
        fun err(): Nothing = throw ShErr("arithmetic syntax: $x")
        fun peek(s: String) = t.startsWith(s, p)
        lateinit var orE: () -> Double
        fun prim(): Double {
            if (peek("(")) { p++; val v = orE(); if (!peek(")")) err(); p++; return v }
            if (peek("!")) { p++; return if (prim() == 0.0) 1.0 else 0.0 }
            if (peek("-")) { p++; return -prim() }
            if (peek("+")) { p++; return prim() }
            val m = Regex("^\\d+(\\.\\d*)?|^\\.\\d+").find(t.substring(p)) ?: err()
            p += m.value.length; return m.value.toDouble()
        }
        fun mul(): Double { var v = prim(); while (p < t.length) { v = when { peek("*") -> { p++; v * prim() }; peek("/") -> { p++; v / prim() }; peek("%") -> { p++; v % prim() }; else -> return v } }; return v }
        fun add(): Double { var v = mul(); while (p < t.length) { v = when { peek("+") -> { p++; v + mul() }; peek("-") -> { p++; v - mul() }; else -> return v } }; return v }
        fun rel(): Double { var v = add(); while (p < t.length) { v = when { peek("<=") -> { p += 2; if (v <= add()) 1.0 else 0.0 }; peek(">=") -> { p += 2; if (v >= add()) 1.0 else 0.0 }; peek("<") -> { p++; if (v < add()) 1.0 else 0.0 }; peek(">") -> { p++; if (v > add()) 1.0 else 0.0 }; else -> return v } }; return v }
        fun eqE(): Double { var v = rel(); while (p < t.length) { v = when { peek("===") -> { p += 3; if (v == rel()) 1.0 else 0.0 }; peek("!==") -> { p += 3; if (v != rel()) 1.0 else 0.0 }; peek("==") -> { p += 2; if (v == rel()) 1.0 else 0.0 }; peek("!=") -> { p += 2; if (v != rel()) 1.0 else 0.0 }; else -> return v } }; return v }
        fun andE(): Double { var v = eqE(); while (peek("&&")) { p += 2; val r = eqE(); v = if (v != 0.0) r else v }; return v }
        orE = { var v = andE(); while (peek("||")) { p += 2; val r = andE(); v = if (v != 0.0) v else r }; v }
        val v = orE()
        if (p != t.length) err()
        if (v.isNaN() || v.isInfinite()) throw ShErr("arithmetic syntax: $x")
        return v.toLong()
    }

    /** Words for `for … in` and unquoted globs (wildcards in any part of the path, e.g. skills/STAR/scripts/STAR.js), matched against the disk. */
    private fun glob(w: String): List<String> {
        if (!w.contains('*') && !w.contains('?')) return listOf(w)
        val abs = w.startsWith("/")
        val parts = w.split('/').filter { it.isNotEmpty() }
        // (shown form, virtual path) pairs, one segment at a time
        var cur = listOf(Pair(if (abs) "" else "", if (abs) "/" else cwd))
        for ((i, seg) in parts.withIndex()) {
            val last = i == parts.size - 1
            val next = ArrayList<Pair<String, String>>()
            for ((shown, vp) in cur) {
                fun show(n: String) = if (abs) (if (shown.isEmpty()) "/$n" else "$shown/$n") else (if (shown.isEmpty()) n else "$shown/$n")
                if (!seg.contains('*') && !seg.contains('?')) {
                    val p = VPath.norm(seg, vp)
                    if (if (last) VPath.exists(p) else VPath.isDir(p)) next.add(Pair(show(seg), p))
                    continue
                }
                if (!VPath.isDir(vp)) continue
                val re = VPath.globRe(seg)
                for (e in try { VPath.list(vp) } catch (_: Exception) { emptyList() }) {
                    if (!re.matches(e.name) || (e.name.startsWith(".") && !seg.startsWith("."))) continue
                    if (!last && !e.dir) continue
                    next.add(Pair(show(e.name), e.path))
                }
            }
            cur = next
            if (cur.isEmpty()) break
        }
        val hits = cur.map { it.first }.filter { it.isNotEmpty() }.sorted()
        return hits.ifEmpty { listOf(w) }
    }

    private fun words(s: String): List<String> = tokenize(s).filter { it is String || it is Glob }.flatMap { if (it is String) listOf(it) else glob((it as Glob).g) }

    private fun execNodes(nodes: List<Node>, st: St): Pair<String, Int> {
        val out = ArrayList<String>(); var exit = 0
        fun emit(t: String) { if (t.isNotEmpty()) out.add(t.removeSuffix("\n")) }
        for (n in nodes) {
            cancel()
            if (++st.steps > 20000) throw ShErr("stopped: more than 20000 steps (endless loop?)")
            when (n) {
                is NCmd -> {
                    val s = expand(n.s, st)
                    val asg = Regex("^([A-Za-z_]\\w*)=([\\s\\S]*)$").find(s)
                    if (asg != null && !Regex("^\\S+\\s").containsMatchIn(asg.groupValues[2].replace(Regex("\"[^\"]*\"|'[^']*'"), "x"))) {
                        vars[asg.groupValues[1]] = tokenize(asg.groupValues[2]).mapNotNull { if (it is String) it else (it as? Glob)?.g }.joinToString(" ")
                        exit = 0; last = 0; continue
                    }
                    if (Regex("^(break|continue)$").matches(s.trim())) { st.flow = s.trim(); break }
                    if (Regex("^exit(\\s+\\d+)?$").matches(s.trim())) { st.flow = "exit"; exit = s.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0; break }
                    val r = runSimple(s); emit(r.output); exit = r.exit; last = exit
                    continue
                }
                is NFor -> {
                    for (v in words(expand(n.words, st))) {
                        vars[n.v] = v
                        val r = execNodes(n.body, st); emit(r.first); exit = r.second
                        if (st.flow == "break") { st.flow = null; break }
                        if (st.flow == "continue") st.flow = null
                        if (st.flow == "exit") break
                    }
                }
                is NLoop -> {
                    var guard = 0
                    while (true) {
                        if (++guard > 10000) throw ShErr("stopped: the ${n.kind} loop ran 10000 times")
                        val r0 = execNodes(listOf(NCmd(n.cond)), st); emit(r0.first)
                        val ok = r0.second == 0
                        if (if (n.kind == "while") !ok else ok) break
                        val r = execNodes(n.body, st); emit(r.first); exit = r.second
                        if (st.flow == "break") { st.flow = null; break }
                        if (st.flow == "continue") st.flow = null
                        if (st.flow == "exit") break
                    }
                }
                is NIf -> {
                    var done = false
                    for (a in n.arms) { val r0 = execNodes(listOf(NCmd(a.cond)), st); emit(r0.first); if (r0.second == 0) { val r = execNodes(a.body, st); emit(r.first); exit = r.second; done = true; break } }
                    if (!done && n.els != null) { val r = execNodes(n.els, st); emit(r.first); exit = r.second } else if (!done) exit = 0
                }
            }
            if (st.flow != null) break
            if (out.sumOf { it.length + 1 } > 400000) { out.add("… (output cut at 400 KB)"); break }
        }
        return Pair(out.joinToString("\n"), exit)
    }

    private fun script(src: String, st: St): Pair<String, Int> {
        val segs = explode(split(src))
        val (nodes, i) = parse(segs, 0, emptyList())
        if (i < segs.size) throw ShErr("syntax error near '${first(segs[i])}'")
        return execNodes(nodes, st)
    }

    private val NEEDS = Regex("(^|[;\\n]\\s*|&&\\s*|\\|\\|\\s*)(for|while|until|if)\\s|\\$\\(|\\$\\{|\\$[A-Za-z_?]|`|^\\s*[A-Za-z_]\\w*=|\\n")

    /** Runs a command line (or a small script). */
    fun run(line: String): ShResult {
        if (!NEEDS.containsMatchIn(line) && !Regex("(^|\\s)\\[\\s").containsMatchIn(line)) {
            return try { val r = runSimple(line); last = r.exit; r } catch (e: CancelledException) { throw e } catch (e: ShErr) { ShResult("sh: ${e.message}", 2, cwd) }
        }
        return try { val r = script(line, St()); ShResult(r.first, r.second, cwd) }
        catch (e: CancelledException) { throw e }
        catch (e: Throwable) { ShResult("sh: " + (e.message ?: e.toString()), 2, cwd) }
    }

    private fun test(a0: List<String>): Boolean {
        var a = a0
        if (a.lastOrNull() == "]") a = a.dropLast(1)
        if (a.isEmpty()) return false
        if (a[0] == "!") return !test(a.drop(1))
        if (a.size == 1) return a[0] != ""
        if (a.size == 2) {
            val (o, x) = a
            return when (o) {
                "-f" -> VPath.isFile(R(x)); "-d" -> VPath.isDir(R(x)); "-e" -> VPath.exists(R(x))
                "-s" -> VPath.isFile(R(x)) && VPath.file(R(x)).length() > 0
                "-z" -> x == ""; "-n" -> x != ""
                else -> throw ShErr("test: unknown operator $o")
            }
        }
        val x = a[0]; val o = a[1]; val y = a.getOrNull(2) ?: ""
        val nx = x.trim().toDoubleOrNull() ?: (if (x.isBlank()) 0.0 else Double.NaN); val ny = y.trim().toDoubleOrNull() ?: (if (y.isBlank()) 0.0 else Double.NaN)
        when (o) {
            "=", "==" -> return x == y; "!=" -> return x != y
            "-eq" -> return nx == ny; "-ne" -> return nx != ny; "-lt" -> return nx < ny; "-le" -> return nx <= ny; "-gt" -> return nx > ny; "-ge" -> return nx >= ny
        }
        if (a.contains("-a")) { val k = a.indexOf("-a"); return test(a.subList(0, k)) && test(a.drop(k + 1)) }
        if (a.contains("-o")) { val k = a.indexOf("-o"); return test(a.subList(0, k)) || test(a.drop(k + 1)) }
        throw ShErr("test: unsupported expression " + a.joinToString(" "))
    }

    private fun scriptBuiltin(cmd: String, args: List<String>, stdin: String?): String? {
        when (cmd) {
            "test", "[" -> { if (test(args)) return ""; throw ShErr("", silent = true, exitCode = 1) }
            "true", ":" -> return ""
            "false" -> throw ShErr("", silent = true, exitCode = 1)
            "seq" -> {
                val n = args.map { it.toDoubleOrNull() ?: Double.NaN }
                val (a0, st, b0) = when (n.size) { 1 -> Triple(1.0, 1.0, n[0]); 2 -> Triple(n[0], 1.0, n[1]); else -> Triple(n.getOrElse(0) { Double.NaN }, n.getOrElse(1) { Double.NaN }, n.getOrElse(2) { Double.NaN }) }
                if (st == 0.0 || st.isNaN() || a0.isNaN() || b0.isNaN() || Math.abs((b0 - a0) / st) > 100000) throw ShErr("seq: range too large")
                val o = ArrayList<String>(); var x = a0
                while (if (st > 0) x <= b0 else x >= b0) { o.add(num2s(x)); x += st }
                return o.joinToString("\n") + if (o.isNotEmpty()) "\n" else ""
            }
            "basename" -> { var b = (args.getOrNull(0) ?: "").trimEnd('/').substringAfterLast('/'); val sfx = args.getOrNull(1); if (sfx != null && b.endsWith(sfx)) b = b.dropLast(sfx.length); return b + "\n" }
            "dirname" -> { val p = (args.getOrNull(0) ?: "").trimEnd('/'); val i = p.lastIndexOf('/'); return (if (i > 0) p.substring(0, i) else if (i == 0) "/" else ".") + "\n" }
            "sleep" -> { val s = minOf(5.0, args.getOrNull(0)?.toDoubleOrNull() ?: 0.0); var ms = (s * 1000).toLong(); while (ms > 0) { cancel(); Thread.sleep(minOf(ms, 200)); ms -= 200 }; return "" }
            "expr" -> return arith(args.joinToString(" ").replace("\\*", "*")).toString() + "\n"
            "export", "local", "set", "unset" -> {
                for (x in args) { val m = Regex("^([A-Za-z_]\\w*)=(.*)$").find(x); if (cmd == "unset") vars.remove(x) else if (m != null) vars[m.groupValues[1]] = m.groupValues[2] }
                return ""
            }
        }
        return null
    }

    companion object {
        private val shells = ConcurrentHashMap<String, Shell>()
        fun of(convoId: String): Shell = shells.getOrPut(convoId) { Shell() }

        const val HELP = "Virtual shell over yaver's disk on the phone (no real OS).\nDisk: /workspace (your files, cwd) · /.yaver = ~ (PERSONA.md, skills/<name>/ with scripts and templates, memories/ read-only, sessions/ read-only) · /tmp\nFiles: pwd cd ls[-la] tree cat head tail wc touch mkdir rm[-rf] rmdir mv cp[-r] ln stat file find[-name -type] du df realpath tee\nText: echo printf grep[-inrvlc] sort[-nru] uniq cut tr sed awk jq diff rev tac nl paste column fold shuf xargs base64 sha256sum sha1sum md5sum\nScripting: VAR=value  \$VAR \${VAR} \$? \$(cmd) `cmd` \$((1+2))  for x in a b *.csv; do …; done  while/until COND; do …; done  if COND; then …; elif …; else …; fi  test / [ ]  true false seq expr basename dirname sleep export break continue exit\nOperators: | ; && || > >> 2>/dev/null\nSystem: date whoami uname hostname id env printenv which clear\nNet & archives: curl/wget[-o] unzip [-d] tar -x|-t|-c [-C] gunzip zip [-r]\nCode: python -c|FILE (CPython/Pyodide; sees the same disk) pip install|list|uninstall node -e|FILE (JavaScript)\n"

        fun register() {
            Tools.add(Tool(
                "terminal",
                "Run a command in the virtual shell over yaver's disk (no real OS; run 'help' for commands): /workspace (cwd), /.yaver (= ~: skills/<name>/scripts…, memories/, PERSONA.md), /tmp. Supports pipes, ;, &&, ||, > and >>, variables, \$(…), for/while/if loops and test, plus sed, awk, cut, tr, xargs, jq, diff, zip/unzip/tar, python, node and more (run help).",
                P.props("command" to P.s("Shell command line")), listOf("command")
            ) { a, ctx ->
                val command = a.optString("command", "")
                if (Regex("\\brm\\s+-[a-z]*r[a-z]*\\s+(/|~|/workspace|/\\.yaver|/\\.yaver/skills)/?\\s*($|[;&|])").containsMatchIn(command))
                    return@Tool "Refused: recursive delete of /, the whole workspace or yaver's own folder."
                val risky = if (Regex("(^|[;&|]\\s*)(rm|mv)\\s").containsMatchIn(command)) "deletes or moves files" else if (Regex("\\b(curl|wget)\\b.*-[oO]").containsMatchIn(command)) "downloads to disk" else null
                if (risky != null && !ctx.approve(Y.t("Terminal", "Terminal") + ": " + risky, listOf(Y.t("Command", "Komut") to command.take(500)))) return@Tool ctx.denied(command.take(80))
                val sh = of(ctx.convo.id)
                synchronized(sh) {
                    sh.ctx = ctx
                    try { val r = sh.run(command); "${r.output.ifEmpty { "(no output)" }}\n[exit ${r.exit} · cwd ${r.cwd}]" } finally { sh.ctx = null }
                }
            })
        }
    }
}

/**
 * The engine behind execute_code and the terminal's python / pip / node / awk: one hidden web view whose page runs
 * Python (Pyodide, CPython 3.13) and a JavaScript REPL in Web Workers — off the page's main thread, so a busy
 * program never freezes the app's screens, and a stuck one is stopped by terminating its worker. The page reaches
 * the workspace, the agent's tools and the model through the YaverExec bridge.
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled", "JavascriptInterface")
object CodeEngine {
    const val PY_VERSION = "0.28.3"
    @Volatile var pyVersion: String? = null
    private const val BASE = "https://appassets.androidplatform.net/yaver-exec/"
    private var web: WebView? = null
    @Volatile private var ready: CountDownLatch? = null
    private class Job(val ctx: ToolCtx?, val progress: ((String) -> Unit)?) { val latch = CountDownLatch(1); @Volatile var result: String? = null }
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val seq = AtomicInteger(0)
    private val PTC_BLOCKED = setOf("execute_code", "clarify", "delegate_task", "present_plan")

    private fun pyDir() = File(Y.app.filesDir, "python").apply { mkdirs() }
    private fun cacheIndexFile() = File(pyDir(), "cache.json")
    private val cacheLock = Any()
    private fun cacheIndex(): JSONObject = try { JSONObject(cacheIndexFile().readText()) } catch (e: Exception) { JSONObject() }
    private fun q(s: String?) = if (s == null) "null" else JSONObject.quote(s)

    class Bridge {
        @JavascriptInterface fun ready() { CodeEngine.ready?.countDown() }
        @JavascriptInterface fun done(rid: Int, json: String?) { jobs[rid]?.let { it.result = json; it.latch.countDown() } }
        @JavascriptInterface fun progress(rid: Int, text: String?) { try { jobs[rid]?.progress?.invoke(text ?: "") } catch (_: Throwable) {} }
        @JavascriptInterface fun pyVersion(v: String?) { CodeEngine.pyVersion = v }

        @JavascriptInterface fun index(): String {
            val a = JSONArray()
            try {
                // the workspace and yaver's own folder (skills with their scripts and templates, memory views, persona); not saved conversations
                for (vp in VPath.walk(VPath.WS) + VPath.walk(VPath.HOME)) { val f = VPath.file(vp); if (f.isFile) a.put(JSONArray().put(vp).put(f.lastModified()).put(f.length()).put(Workspace.isText(f))) }
            } catch (_: Throwable) {}
            return a.toString()
        }
        @JavascriptInterface fun read(p: String, binary: Boolean): String = try {
            val f = VPath.file(p)
            if (!f.isFile) JSONObject().put("e", "ENOENT $p").toString()
            else if (binary) JSONObject().put("b", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)).toString()
            else JSONObject().put("t", f.readText()).toString()
        } catch (e: Throwable) { JSONObject().put("e", e.message ?: e.toString()).toString() }
        @JavascriptInterface fun write(p: String, data: String, b64: Boolean): String = try {
            val f = VPath.fileW(p)
            if (f.isDirectory) throw IllegalArgumentException("EISDIR $p")
            f.parentFile?.mkdirs()
            if (b64) f.writeBytes(android.util.Base64.decode(data, android.util.Base64.DEFAULT)) else f.writeText(data)
            VPath.commit(p)
            ""
        } catch (e: Throwable) { e.message ?: e.toString() }
        @JavascriptInterface fun remove(p: String) { try { val f = VPath.fileW(p); if (f.isFile) { f.delete(); VPath.commit(p) } } catch (_: Throwable) {} }
        @JavascriptInterface fun stat(p: String): String = try { val f = VPath.file(p); if (f.isFile) "${f.lastModified()}|${f.length()}" else "" } catch (e: Throwable) { "" }

        @JavascriptInterface fun call(rid: Int, cid: Int, name: String, args: String) {
            Y.bg {
                var value: String? = null; var err: String? = null
                try {
                    val ctx = jobs[rid]?.ctx ?: throw IllegalStateException("tools, llm() and spawn() are only available through the execute_code tool")
                    val a = try { JSONObject(args) } catch (e: Exception) { JSONObject() }
                    value = when (name) { "__llm" -> llm(a, ctx); "__spawn" -> spawn(a, ctx); else -> tool(name, a, ctx) }
                } catch (e: CancelledException) { err = "stopped by the user" } catch (e: Throwable) { err = e.message ?: e.toString() }
                Y.main.post { try { web?.evaluateJavascript("window.__reply($cid, ${q(value)}, ${q(err)})", null) } catch (_: Throwable) {} }
            }
        }

        @JavascriptInterface fun cacheUrls(): String = synchronized(cacheLock) { JSONArray(cacheIndex().keys().asSequence().toList()).toString() }
        @JavascriptInterface fun cacheGet(url: String): String = synchronized(cacheLock) {
            try { val n = cacheIndex().optString(url); val f = File(pyDir(), "cache/$n"); if (n.isNotEmpty() && f.isFile) android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP) else "" } catch (e: Throwable) { "" }
        }
        @JavascriptInterface fun cachePut(url: String, b64: String) = synchronized(cacheLock) {
            try {
                val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + "-" + url.substringAfterLast('/').substringBefore('?').replace(Regex("[^\\w.-]"), "_").take(80)
                val f = File(pyDir(), "cache/$name"); f.parentFile?.mkdirs()
                f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                val ix = cacheIndex(); ix.put(url, name); cacheIndexFile().writeText(ix.toString())
            } catch (e: Throwable) { Diag.e("code", "python cache", e) }
        }
        @JavascriptInterface fun reqs(): String = try { JSONArray(File(pyDir(), "requirements.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }).toString() } catch (e: Throwable) { "[]" }
        @JavascriptInterface fun reqsRecord(add: String, remove: String) {
            try {
                val f = File(pyDir(), "requirements.txt")
                val s = LinkedHashSet(try { f.readLines().map { it.trim() }.filter { it.isNotEmpty() } } catch (e: Throwable) { emptyList() })
                val ad = JSONArray(add); for (i in 0 until ad.length()) s.add(ad.getString(i))
                val rm = JSONArray(remove); for (i in 0 until rm.length()) { val r = rm.getString(i).lowercase(); s.removeAll { it.lowercase().split(Regex("[=<>\\s]"))[0] == r } }
                f.writeText(s.joinToString("\n") + if (s.isNotEmpty()) "\n" else "")
            } catch (_: Throwable) {}
        }
    }

    private fun tool(name: String, a: JSONObject, ctx: ToolCtx): String {
        val t = Tools.get(name)
        if (t == null) {
            val names = ArrayList<String>(); val s = Tools.schemas(); for (i in 0 until s.length()) s.getJSONObject(i).optJSONObject("function")?.optString("name")?.let { if (it !in PTC_BLOCKED) names.add(it) }
            throw IllegalArgumentException("unknown tool $name. Available: ${names.joinToString(", ")}")
        }
        if (name in PTC_BLOCKED) throw IllegalArgumentException("$name cannot be called from code" + if (name == "delegate_task") " — use spawn(goal, {context}) instead" else "")
        return t.run(a, ctx)
    }

    private fun llm(a: JSONObject, ctx: ToolCtx): String {
        val m = a.optString("model", "")
        val model = if (m == "strong") Cfg.model else if (m.isNotEmpty() && m != "fast") m else Cfg.fastModel.ifEmpty { Cfg.model }
        val msgs = JSONArray()
        if (a.optString("system").isNotEmpty()) msgs.put(JSONObject().put("role", "system").put("content", a.optString("system")))
        msgs.put(JSONObject().put("role", "user").put("content", clip(a.optString("prompt", ""), 200000)))
        val r = Llm.chat(msgs, null, model, cancel = ctx.cancel, maxTokens = a.optInt("max_tokens", 0), stream = false)
        return r.content
    }

    private fun spawn(a: JSONObject, ctx: ToolCtx): String {
        if (ctx.depth > 0) throw IllegalStateException("helpers cannot start further helpers")
        val sub = Convo.create("helper", background = true)
        val sublistener = object : AgentListener {
            override fun onToolStart(name: String, args: JSONObject) { ctx.progress("↳ $name") }
            override fun approve(title: String, details: String, danger: Boolean) = ctx.listener?.approve(title, details, danger) ?: false
        }
        return try {
            Agent.run(sub, "Task: ${a.optString("goal")}\n\nContext: ${a.optString("context")}\n\nWork step by step with your tools, then answer with a complete report (sources as links).", listener = sublistener, cancel = ctx.cancel, background = ctx.background, depth = 1)
        } finally { Convos.delete(sub.id) }
    }

    private fun ensure() {
        if (web != null && ready?.count == 0L) return
        val l = CountDownLatch(1)
        ready = l
        Y.main.post {
            try {
                web?.destroy()
                val v = WebView(Y.app)
                v.settings.javaScriptEnabled = true
                v.settings.domStorageEnabled = true
                v.addJavascriptInterface(Bridge(), "YaverExec")
                v.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        if (view == web) { web = null; ready = null }
                        try { view?.destroy() } catch (_: Throwable) {}
                        for ((_, j) in jobs) { j.result = JSONObject().put("ok", false).put("text", "Error: the code engine crashed (out of memory?) and was restarted; variables from earlier runs are gone.").toString(); j.latch.countDown() }
                        return true
                    }
                }
                v.resumeTimers()
                web = v
                v.loadDataWithBaseURL(BASE, PAGE, "text/html", "utf-8", null)
            } catch (e: Throwable) { Diag.e("code", "engine", e); l.countDown() }
        }
        if (!l.await(30, TimeUnit.SECONDS) || web == null) { ready = null; throw IllegalStateException("the code engine did not start") }
    }

    /** Calls a page function and waits for its job to finish. */
    private fun request(fn: String, payload: String, waitSec: Long, ctx: ToolCtx?, progress: ((String) -> Unit)?, cancel: Cancel?): JSONObject {
        ensure()
        val rid = seq.incrementAndGet()
        val job = Job(ctx, progress)
        jobs[rid] = job
        val abort = { Y.main.post { try { web?.evaluateJavascript("window.__abort($rid)", null) } catch (_: Throwable) {} } }
        try {
            Y.main.post { try { web?.evaluateJavascript("window.$fn($rid, $payload)", null) } catch (_: Throwable) {} }
            var waited = 0L; var abortedAt = -1L
            while (!job.latch.await(250, TimeUnit.MILLISECONDS)) {
                waited += 250
                if (cancel?.cancelled == true && abortedAt < 0) { abort(); abortedAt = waited }
                if (abortedAt >= 0 && waited - abortedAt > 4000) throw CancelledException()
                if (waited > waitSec * 1000) { abort(); throw IllegalStateException("the code engine did not answer within $waitSec s") }
            }
        } finally { jobs.remove(rid) }
        cancel?.check()
        return try { JSONObject(job.result ?: "{}") } catch (e: Exception) { JSONObject().put("ok", false).put("text", "Error: bad answer from the code engine") }
    }

    /** Python with a persistent namespace per conversation; /workspace is its working folder. Returns (ok, text). */
    fun python(code: String, sid: String?, cwd: String, script: String?, argv: List<String>?, timeoutSec: Int, reset: Boolean, cancel: Cancel?, progress: ((String) -> Unit)?): Pair<Boolean, String> {
        val p = JSONObject().put("code", code).put("sid", sid ?: JSONObject.NULL).put("cwd", cwd).put("script", script ?: JSONObject.NULL)
            .put("argv", argv?.let { JSONArray(it) } ?: JSONObject.NULL).put("timeout", timeoutSec * 1000L).put("reset", reset)
        val r = request("__py", p.toString(), timeoutSec + 900L, null, progress, cancel)
        return Pair(r.optBoolean("ok"), r.optString("text"))
    }

    fun pip(args: List<String>, sid: String?, cancel: Cancel?): String {
        val r = request("__pip", JSONObject().put("args", JSONArray(args)).put("sid", sid ?: JSONObject.NULL).toString(), 900, null, null, cancel)
        return r.optString("text")
    }

    /** JavaScript in a worker; persist=true keeps top-level variables for the conversation (REPL). ctx enables tools.*, llm(), spawn(). */
    fun js(code: String, sid: String?, persist: Boolean, timeoutSec: Int, reset: Boolean, ctx: ToolCtx?, cancel: Cancel?, cwd: String = VPath.WS): Pair<Boolean, String> {
        val p = JSONObject().put("code", code).put("sid", sid ?: JSONObject.NULL).put("persist", persist).put("timeout", timeoutSec * 1000L).put("reset", reset).put("ctx", ctx != null).put("cwd", cwd)
        val r = request("__js", p.toString(), maxOf(900L, timeoutSec + 600L) + 60, ctx, ctx?.let { c -> { s: String -> c.progress(s) } }, cancel)
        return Pair(r.optBoolean("ok"), r.optString("text"))
    }

    fun awk(args: JSONArray, files: JSONObject, stdin: String, cancel: Cancel?): String {
        val r = request("__awk", JSONObject().put("args", args).put("files", files).put("stdin", stdin).toString(), 40, null, null, cancel)
        if (!r.optBoolean("ok")) throw ShErr(r.optString("text"))
        return r.optString("text")
    }

    /**
     * run_code (the older tool, kept for compatibility) on this engine — the same Python and JavaScript as execute_code.
     * [files] are made available by name: the code starts in their folder (when they share one; else /workspace).
     * Without a conversation ([sid] null) Python runs in whatever session is open. Returns (ok, text).
     */
    fun runCode(language: String, code: String, files: List<String>, timeoutSec: Int, sid: String?, ctx: ToolCtx?, cancel: Cancel?, progress: ((String) -> Unit)? = null): Pair<Boolean, String> {
        val vps = files.map { VPath.normTool(it) }
        for (p in vps) if (!VPath.exists(p)) throw IllegalArgumentException("file not found: $p" + FileTools.hint(p))
        val dirs = vps.map { if (VPath.isDir(it)) it else VPath.parent(it) }.distinct()
        val cwd = if (dirs.size == 1 && VPath.writable(dirs[0])) dirs[0] else VPath.WS
        val lang = language.lowercase()
        val t = timeoutSec.coerceIn(5, 1800)
        val r = if (lang.isEmpty() || lang.startsWith("py")) python(code, sid, cwd, null, null, maxOf(10, t), false, cancel, progress).let { Pair(it.first, codeHint(it.second, "python")) }
            else js(code, sid, ctx != null && sid != null, t, false, ctx, cancel, cwd).let { Pair(it.first, codeHint(it.second, "javascript")) }
        val note = if (cwd != VPath.WS) "[ran in $cwd]\n" else ""
        return Pair(r.first, note + r.second)
    }

    /** Short, actionable hints when code hits a limit of the runtimes (saves the model many blind retries). */
    fun codeHint(text: String, lang: String): String {
        val add = ArrayList<String>()
        if (lang == "python" && Regex("Errno 50|Protocol not available|urlopen error|ConnectionError|NewConnectionError|getaddrinfo|socket\\.gaierror|No module named '(requests|urllib3)'", RegexOption.IGNORE_CASE).containsMatchIn(text)) add.add("Python here (Pyodide) has no internet access. Download the file first with the download_file tool or `curl URL -o /workspace/name` in the terminal, then open it from Python.")
        if (lang == "python" && Regex("No module named '(cartopy|geopandas|basemap|fiona|pyproj|rasterio|osgeo|gdal|plotly|tensorflow|torch)'", RegexOption.IGNORE_CASE).containsMatchIn(text)) add.add("This package cannot run in Pyodide (it needs compiled system libraries or is not built for Pyodide). Use what is available (matplotlib, numpy, pandas, shapely, scipy…) or do it in JavaScript (e.g. D3 via import()).")
        if (lang == "javascript" && Regex("require is not defined|process is not defined|Buffer is not defined|Cannot use import statement", RegexOption.IGNORE_CASE).containsMatchIn(text)) add.add("This JavaScript runs in a web worker, not Node. Use `const m = await import('https://cdn.jsdelivr.net/npm/<package>/+esm')` for libraries and fetch() for data.")
        return if (add.isEmpty()) text else text + "\n\n[hint] " + add.joinToString(" ")
    }

    fun register() {
        Tools.add(Tool(
            "execute_code",
            "Run code in a persistent session that lives for this conversation. language \"javascript\" (default): a Web Worker REPL — top-level variables and functions stay defined in later calls; vars() lists them; globals: console; files — fs.readFile(p) (text → string, binary → Uint8Array; fs.readFile(p, 'binary') always bytes), fs.readText(p), fs.readBytes(p), fs.readJSON(p), fs.writeFile(p, string | Uint8Array | ArrayBuffer | object→JSON), fs.exists(p), fs.list(dir), fs.isBinary(p); /workspace is preloaded (text and binary), any other workspace file comes with await fs.load(p); the same files are visible to Python; tools.<name>({...}) calls any agent tool and returns its text (programmatic tool calling — loop or Promise.all over many calls); llm(prompt, {system, model: 'fast'|'strong'}) asks a sub-model, llm_batch(prompts) runs many in parallel — use them to map over chunks of a long input; spawn(goal, {context}) runs a helper agent and returns its report. language \"python\": real CPython 3.13 (Pyodide) with a persistent namespace; /workspace is its real working folder (open(), pandas.read_csv/read_excel, to_excel … read and write the workspace, binary files included); packages load automatically on import (numpy, pandas, matplotlib, scipy, scikit-learn, pillow, lxml and more) and other pure-Python packages install with `pip install` in the terminal; matplotlib figures are saved to /workspace/plots automatically; no subprocess, threads or sockets — Python has NO internet access (urllib, requests, socket, pip-from-URL fail): first download files into /workspace with the download_file tool or `curl URL -o file` in the terminal, then open them from Python. JavaScript runs in a web worker inside the app, not Node: no require/process/Buffer — load libraries with await import('https://cdn.jsdelivr.net/npm/<package>/+esm'). reset=true starts a clean session. Return or print only what matters. fetch() (JS) and pyodide.http.pyfetch (Python) work only for CORS-enabled URLs.",
            P.props(
                "code" to P.s("Source code"), "language" to P.s("javascript (default) or python", "javascript", "python"),
                "reset" to P.b("Start a clean session before running (drops all variables)"),
                "timeout" to toolInt("Time limit in seconds — Python default 300, JavaScript default 120 (compute time; waiting for tools/llm() does not count); up to 1800")
            ),
            listOf("code")
        ) { a, ctx ->
            val code = a.optString("code", "")
            // no language given but the code is clearly Python (import pandas, def …:, print(…) without semicolons): run it as Python
            val lang = a.str("language").lowercase()
            val code0 = a.optString("code")
            val looksPy = Regex("(?m)^\\s*(import \\w|from \\w[\\w.]* import |def \\w+\\(.*\\):|class \\w+.*:\\s*$|for \\w+ in .+:\\s*$|if .+:\\s*$|print\\()").containsMatchIn(code0) &&
                !Regex("(?m)(^\\s*(const|let|var|function)\\s|=>|;\\s*$|await import\\(|console\\.log)").containsMatchIn(code0)
            val py = lang == "python" || lang == "py" || (lang.isEmpty() && looksPy)
            val risky = if (py) (if (Regex("\\b(os\\.(remove|unlink|rmdir|removedirs)|shutil\\.(rmtree|move)|Path\\([^)]*\\)\\.unlink|\\.unlink\\(|pyfetch)").containsMatchIn(code)) "deletes files or makes network requests" else null)
                else if (Regex("\\bfetch\\(\\s*['\"`]https?:").containsMatchIn(code)) "makes network requests" else null  // writing workspace files is the point of running code: no approval for that
            if (risky != null && !ctx.approve(Y.t("Run code", "Kod çalıştır") + ": " + risky, listOf(Y.t("Code", "Kod") to clip(code, 600)))) return@Tool ctx.denied("execute_code ($risky)")
            val t = if (a.has("timeout") && !a.isNull("timeout")) a.optDouble("timeout", 0.0).toInt() else 0
            if (py) {
                ctx.progress(Y.t("Running Python…", "Python çalışıyor…"))
                val r = python(code, ctx.convo.id, VPath.WS, null, null, minOf(1800, maxOf(10, if (t > 0) t else 300)), a.optBoolean("reset"), ctx.cancel) { ctx.progress(it) }
                codeHint(r.second, "python")
            } else {
                ctx.progress(Y.t("Running code…", "Kod çalışıyor…"))
                val r = js(code, ctx.convo.id, true, minOf(1800, maxOf(5, if (t > 0) t else 120)), a.optBoolean("reset"), ctx, ctx.cancel)
                codeHint(r.second, "javascript")
            }
        })
    }

    private val PAGE: String by lazy { ("<!doctype html><html><head><meta charset=\"utf-8\"><title>yaver code engine</title></head><body><script>\n" + ENGINE_JS.replace('¤', '$') + "\n</script></body></html>") }
}

/* The engine page (JavaScript; ¤ stands for the dollar sign). Worker sources are the original's pyWorkerMain, REPL worker and awk. */
private val ENGINE_JS = """
(function () {
"use strict";
const R = window.YaverExec;
const b64ToBytes = b => { const s = atob(b || ""), u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; };
const bytesToB64 = u => { let s = ""; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); };
const clip = (s, n) => { s = String(s ?? ""); return s.length > n ? s.slice(0, n) + `\n…[truncated ¤{s.length - n} chars]` : s; };
const J = s => { try { return JSON.parse(s); } catch (e) { return null; } };
const blobUrl = s => URL.createObjectURL(new Blob([s], { type: "text/javascript" }));
const CWD = "/workspace";
const MAX_PY_FILE = 64 * 1024 * 1024, MAX_PY_TOTAL = 256 * 1024 * 1024;
/* the workspace, through the app (synchronous calls) */
const host = {
  index() { return J(R.index()) || []; },
  bytes(p) { const r = J(R.read(p, true)); if (!r || r.e) throw new Error(r ? r.e : "ENOENT " + p); return b64ToBytes(r.b); },
  text(p) { const r = J(R.read(p, false)); if (!r || r.e) throw new Error(r ? r.e : "ENOENT " + p); return r.t; },
  write(p, v) { const e = typeof v === "string" ? R.write(p, v, false) : R.write(p, bytesToB64(v instanceof Uint8Array ? v : new Uint8Array(v)), true); if (e) throw new Error(e); },
  remove(p) { R.remove(p); },
  stat(p) { return R.stat(p); },
};
const finishJob = (rid, obj) => { let s; try { s = JSON.stringify(obj); } catch (e) { s = JSON.stringify({ ok: false, text: "Error: " + (e.message || e) }); } R.done(rid, s); };
const aborts = new Map(), cancelled = new Set();
window.__abort = rid => { cancelled.add(rid); const f = aborts.get(rid); if (f) f(); };
function pyWorkerMain() {
  let py = null, base = "", cached = new Set(), seq = 0; const pend = new Map(); const known = new Map(); let out = [];
  const ask = (type, data) => new Promise(res => { const id = ++seq; pend.set(id, res); self.postMessage({ type, id, ...data }); });
  const realFetch = self.fetch.bind(self);
  const ctype = u => /\.wasm(\?|¤)/.test(u) ? "application/wasm" : /\.m?js(\?|¤)/.test(u) ? "text/javascript" : /\.json(\?|¤)/.test(u) ? "application/json" : "application/octet-stream";
  const cacheable = u => (base && u.startsWith(base)) || /\/pyodide\/v[^/]+\/full\//.test(u) || /\.whl(\?|#|¤)/i.test(u) || /\/pypi\/[^/]+\/json¤|\/simple\/[^/]+\/?¤/.test(u);
  const networkFirst = u => /\/pypi\/[^/]+\/json¤|\/simple\/[^/]+\/?¤/.test(u);
  self.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : (input && input.url) || String(input);
    let abs; try { abs = new URL(url, base || self.location.href).href; } catch { abs = url; }
    const method = (init && init.method) || (input && input.method) || "GET";
    if (method !== "GET" || !cacheable(abs)) return realFetch(input, init);
    const fromCache = async () => { const b = await ask("cacheGet", { url: abs }); return b ? new Response(b, { status: 200, headers: { "Content-Type": ctype(abs) } }) : null; };
    if (cached.has(abs) && !networkFirst(abs)) { const r = await fromCache(); if (r) return r; }
    let r; try { r = await realFetch(abs); } catch (e) { if (cached.has(abs)) { const c = await fromCache(); if (c) return c; } throw e; }
    if (r.ok) { const b = new Uint8Array(await r.clone().arrayBuffer()); cached.add(abs); self.postMessage({ type: "cachePut", url: abs, bytes: b }, [b.buffer]); }
    return r;
  };
  const progress = t => self.postMessage({ type: "progress", text: String(t).slice(0, 300) });
  /* classic worker: the loader is fetched (through the cache above), then run with importScripts from a blob.
     Mirrors are tried in order; every failure names the step that failed. */
  async function boot({ bases, cachedUrls, reinstall }) {
    cached = new Set(cachedUrls || []); const tried = [];
    const blob = s => URL.createObjectURL(new Blob([s], { type: "text/javascript" }));
    for (const b of bases) {
      base = b; let host = b; try { host = new URL(b).host; } catch {} let stage = "downloading pyodide.js";
      try {
        progress("loading the Python runtime from " + host);
        const get = async name => { let r; try { r = await self.fetch(b + name); } catch (e) { throw new Error(`network error for ¤{name} (¤{e.message || e})`); } if (!r.ok) throw new Error(`HTTP ¤{r.status} for ¤{name}`); return r.text(); };
        const loader = await get("pyodide.js");
        stage = "downloading pyodide.asm.js"; const asm = await get("pyodide.asm.js");
        stage = "running pyodide.js"; try { delete self.loadPyodide; } catch {} importScripts(blob(loader));
        if (typeof self.loadPyodide !== "function") throw new Error("pyodide.js did not define loadPyodide");
        stage = "running pyodide.asm.js"; try { importScripts(blob(asm)); } catch (e) { progress("pre-loading pyodide.asm.js failed, the loader will fetch it: " + (e.message || e)); }
        stage = "loading the WebAssembly runtime and the standard library (pyodide.asm.wasm, python_stdlib.zip)";
        py = await self.loadPyodide({ indexURL: b, env: { HOME: "/home/pyodide", MPLBACKEND: "Agg" }, stdout: s => out.push(s), stderr: s => out.push(s) });
        stage = "setting up the session";
        py.setStdout({ batched: s => out.push(s) }); py.setStderr({ batched: s => out.push(s) });
        py.FS.mkdirTree("/workspace");
        if (reinstall && reinstall.length) { try { await py.loadPackage("micropip"); const mp = py.pyimport("micropip"); for (const r of reinstall) { try { await mp.install(r); } catch (e) { progress(`could not reinstall ¤{r}: ¤{String(e.message || e).split("\n").pop()}`); } } } catch {} }
        return { version: py.version, base: b };
      } catch (e) { py = null; tried.push(`¤{host} — ¤{stage} failed: ¤{String(e && e.message || e).split("\n")[0].slice(0, 240)}`); }
    }
    throw new Error("every mirror failed.\n  " + tried.join("\n  "));
  }
  function writeIn(files, deletes) {
    const FS = py.FS;
    for (const [p, bytes] of files) { try { FS.mkdirTree(p.slice(0, p.lastIndexOf("/")) || "/"); FS.writeFile(p, bytes); const st = FS.stat(p); known.set(p, `¤{st.mtime.getTime ? st.mtime.getTime() : st.mtime}|¤{st.size}`); } catch (e) { progress(`could not copy ¤{p}: ¤{e.message}`); } }
    for (const p of deletes) { try { FS.unlink(p); } catch {} known.delete(p); }
  }
  function collect() {
    const FS = py.FS, writes = [], seen = new Set();
    const walk = d => { let names = []; try { names = FS.readdir(d); } catch { return; } for (const n of names) { if (n === "." || n === ".." || n === "__pycache__") continue; const p = d + "/" + n; let st; try { st = FS.stat(p); } catch { continue; } if (FS.isDir(st.mode)) walk(p); else if (FS.isFile(st.mode)) { seen.add(p); const sig = `¤{st.mtime.getTime ? st.mtime.getTime() : st.mtime}|¤{st.size}`; if (known.get(p) !== sig) { known.set(p, sig); if (st.size <= 200 * 1024 * 1024) writes.push([p, FS.readFile(p)]); } } } };
    walk("/workspace"); walk("/.yaver");
    const deletes = [...known.keys()].filter(p => !seen.has(p)); for (const p of deletes) known.delete(p);
    return { writes, deletes };
  }
  const FIGS = "def __yaver_figs():\n    import sys\n    if 'matplotlib.pyplot' not in sys.modules:\n        return []\n    import matplotlib.pyplot as plt, os, time\n    out = []\n    nums = plt.get_fignums()\n    if nums:\n        os.makedirs('/workspace/plots', exist_ok=True)\n    for i, n in enumerate(nums):\n        p = '/workspace/plots/plot-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(i + 1) + '.png'\n        plt.figure(n).savefig(p, dpi=110, bbox_inches='tight')\n        out.append(p)\n    plt.close('all')\n    return out\n__yaver_figs()\n";
  async function run({ code, files, deletes, cwd, script, argv }) {
    out = []; writeIn(files || [], deletes || []);
    let result = null, error = null, figs = [];
    try { py.FS.mkdirTree(cwd); py.FS.chdir(cwd); } catch {}
    const ns = script ? py.toPy({ __name__: "__main__", __file__: script }) : py.globals;
    try {
      await py.loadPackagesFromImports(code, { messageCallback: m => /Loading|Loaded/.test(m) && progress(m), errorCallback: m => progress(m) });
      if (argv) py.runPython("import sys\nsys.argv = " + JSON.stringify(argv));
      const r = await py.runPythonAsync(code, { globals: ns, filename: script || "<cell>" });
      if (r !== undefined && r !== null) { try { result = py.globals.get("repr")(r); } catch { result = String(r); } if (r && r.destroy) try { r.destroy(); } catch {} }
    } catch (e) { error = String(e.message || e); }
    try { const f = py.runPython(FIGS); figs = f ? f.toJs() : []; if (f && f.destroy) f.destroy(); } catch {}
    if (script && ns.destroy) try { ns.destroy(); } catch {}
    const { writes, deletes: dels } = collect();
    return { out: out.join("\n"), result, error, figs, writes, deletes: dels };
  }
  async function pip(args) {
    out = []; await py.loadPackage("micropip"); const mp = py.pyimport("micropip");
    const [cmd, ...rest] = args; const names = rest.filter(a => !a.startsWith("-"));
    if (cmd === "install") { if (!names.length) throw new Error("usage: pip install <package> [...]"); await mp.install(names.length === 1 ? names[0] : py.toPy(names)); return { text: `Successfully installed ¤{names.join(" ")}`, installed: names }; }
    if (cmd === "uninstall") { mp.uninstall(names.length === 1 ? names[0] : py.toPy(names)); return { text: `Uninstalled ¤{names.join(" ")}`, removed: names }; }
    if (cmd === "list" || cmd === "freeze") { const rows = JSON.parse(py.runPython("import json, micropip\njson.dumps(sorted([[p.name, p.version] for p in micropip.list().values()], key=lambda r: r[0].lower()))")); return { text: cmd === "freeze" ? rows.map(r => `¤{r[0]}==¤{r[1]}`).join("\n") : ["Package".padEnd(28) + "Version", "-".repeat(40), ...rows.map(r => r[0].padEnd(28) + r[1])].join("\n") }; }
    if (cmd === "show") { const all = JSON.parse(py.runPython("import json, micropip\njson.dumps([[p.name, p.version, p.source] for p in micropip.list().values()])")); const p = all.find(x => x[0].toLowerCase().replace(/_/g, "-") === (names[0] || "").toLowerCase().replace(/_/g, "-")); return { text: p ? `Name: ¤{p[0]}\nVersion: ¤{p[1]}\nSource: ¤{p[2]}` : `WARNING: Package(s) not found: ¤{names[0]}` }; }
    throw new Error(`pip ¤{cmd || ""}: supported commands are install, uninstall, list, freeze, show`);
  }
  self.onmessage = async e => {
    const m = e.data;
    if (m.type === "cacheResult") { const r = pend.get(m.id); if (r) { pend.delete(m.id); r(m.bytes); } return; }
    try {
      let value;
      if (m.type === "boot") value = await boot(m); else if (m.type === "run") value = await run(m); else if (m.type === "pip") value = await pip(m.args);
      self.postMessage({ type: "done", rid: m.rid, value });
    } catch (err) { self.postMessage({ type: "done", rid: m.rid, error: String(err && err.message || err) }); }
  };
}
const PY_WORKER_SRC = `(¤{pyWorkerMain.toString()})();`;
const PY_VERSION = "0.28.3";
const PY_MIRRORS = ["https://fastly.jsdelivr.net", "https://gcore.jsdelivr.net", "https://cdn.jsdelivr.net"].map(h => `¤{h}/pyodide/v¤{PY_VERSION}/full/`);
const PY_RUNTIME_RE = /\/pyodide\/v[^/]+\/full\//;
/* downloaded runtime files and packages are kept in the app's storage, so later starts work offline */
const PyCache = {
  urls() { return J(R.cacheUrls()) || []; },
  get(url) { const b = R.cacheGet(url); return b ? b64ToBytes(b) : null; },
  put(url, bytes) { try { R.cachePut(url, bytesToB64(bytes)); } catch (e) {} },
  installed() { return J(R.reqs()) || []; },
  record(add = [], remove = []) { try { R.reqsRecord(JSON.stringify(add), JSON.stringify(remove)); } catch (e) {} },
};
const PY = {
  w: null, sid: null, ready: null, sent: new Map(), rid: 0, waits: new Map(), onProgress: null, queue: Promise.resolve(), version: null, used: null,
  bases() { const ix = PyCache.urls(); const has = b => ix.includes(b + "pyodide.asm.wasm") && ix.includes(b + "pyodide.js"); return [...PY_MIRRORS.filter(has), ...PY_MIRRORS.filter(b => !has(b))]; },
  reset() { if (this.w) this.w.terminate(); this.w = null; this.ready = null; this.sid = null; this.sent.clear(); for (const [, w] of this.waits) w.reject(new Error("Python was restarted")); this.waits.clear(); },
  call(msg, transfer) { const rid = ++this.rid; return new Promise((resolve, reject) => { this.waits.set(rid, { resolve, reject }); this.w.postMessage({ ...msg, rid }, transfer || []); }); },
  start(sid) {
    if (this.w && (this.sid === sid || sid == null)) return this.ready;  /* null: any session (checks and gates run in the current one) */
    this.reset();
    try { this.w = new Worker(blobUrl(PY_WORKER_SRC)); } catch (e) { this.w = null; return (this.ready = Promise.reject(new Error(`Python could not start: the web engine refused to create a worker (¤{e.message || e}).`))); }
    this.sid = sid;
    this.w.onmessage = e => {
      const m = e.data;
      if (m.type === "cacheGet") { const b = PyCache.get(m.url); this.w.postMessage({ type: "cacheResult", id: m.id, bytes: b }); return; }
      if (m.type === "cachePut") { PyCache.put(m.url, new Uint8Array(m.bytes)); return; }
      if (m.type === "progress") { this.onProgress && this.onProgress(m.text); return; }
      if (m.type === "done") { const w = this.waits.get(m.rid); if (!w) return; this.waits.delete(m.rid); m.error ? w.reject(new Error(m.error)) : w.resolve(m.value); }
    };
    this.w.onerror = e => { e.preventDefault && e.preventDefault(); const why = e.message ? `the worker crashed: ¤{e.message}¤{e.lineno ? ` (line ¤{e.lineno})` : ""}` : "the web engine could not start the worker"; for (const [, w] of this.waits) w.reject(new Error(why)); this.waits.clear(); };
    const firstTime = !PyCache.urls().some(u => PY_RUNTIME_RE.test(u) && /pyodide\.asm\.wasm¤/.test(u));
    if (firstTime && this.onProgress) this.onProgress("Installing Python on the phone (first use, about 10 MB)…");
    const reinstall = PyCache.installed();
    const bases = this.bases();
    this.ready = this.call({ type: "boot", bases, cachedUrls: PyCache.urls(), reinstall }).then(v => { this.version = v.version; this.used = v.base; try { R.pyVersion(String(v.version)); } catch (e) {} return v; }, e => { this.reset(); throw new Error(`Python could not start: ¤{e.message}¤{firstTime ? `\nThe first start downloads about 10 MB from ¤{bases.map(b => new URL(b).host).join(" / ")}; after that Python starts from the phone's storage.` : ""}`); });
    return this.ready;
  },
  /* files that changed in the workspace since Python last saw them */
  delta() {
    const files = [], seen = new Set(), transfer = [], skipped = []; let total = 0;
    for (const [p, m, s] of host.index()) { seen.add(p); const sig = `¤{m}|¤{s}`; if (this.sent.get(p) === sig) continue; if (s > MAX_PY_FILE || total + s > MAX_PY_TOTAL) { skipped.push(p); continue; } total += s; let b; try { b = host.bytes(p); } catch (e) { continue; } files.push([p, b]); transfer.push(b.buffer); this.sent.set(p, sig); }
    const deletes = [...this.sent.keys()].filter(p => !seen.has(p)); for (const p of deletes) this.sent.delete(p);
    return { files, deletes, transfer, skipped };
  },
  applyBack(writes, deletes) {
    const changed = [];
    for (const [p, bytes] of writes) { try { host.write(p, bytes); changed.push(p); } catch (e) {} }
    for (const p of deletes) { try { if (host.stat(p)) { host.remove(p); changed.push(p); } } catch (e) {} }
    for (const p of changed) { const st = host.stat(p); if (st) this.sent.set(p, st); else this.sent.delete(p); }
    return changed;
  },
  run(code, o) {
    const job = async () => {
      if (cancelled.has(o.rid)) return { ok: false, text: "Error: stopped by the user" };
      this.onProgress = t => R.progress(o.rid, String(t));
      try { await this.start(o.sid); } catch (e) { return { ok: false, text: "Error: " + e.message }; }
      const { files, deletes, transfer, skipped } = this.delta();
      const timeout = o.timeout || 300000;
      let timer; const stop = new Promise((_, rej) => { timer = setTimeout(() => rej(new Error(`stopped after ¤{Math.round(timeout / 1000)} s (the time limit)`)), timeout); aborts.set(o.rid, () => rej(new Error("stopped by the user"))); });
      let r;
      try { r = await Promise.race([this.call({ type: "run", code, files, deletes, cwd: o.cwd || CWD, script: o.script || null, argv: o.argv || null }, transfer), stop]); }
      catch (e) { this.reset(); return { ok: false, text: `Error: Python ¤{e.message}. The Python session was restarted, so variables from earlier runs are gone; files already written to /workspace are kept.` }; }
      finally { clearTimeout(timer); aborts.delete(o.rid); this.onProgress = null; }
      const changed = this.applyBack(r.writes || [], r.deletes || []);
      const text = [r.out, r.result != null && r.result !== "None" ? "=> " + r.result : "", r.error ? r.error.trim() : "", r.figs && r.figs.length ? "Figures saved: " + r.figs.join(", ") : "", changed.length ? `Files written: ¤{changed.filter(p => !(r.figs || []).includes(p)).slice(0, 40).join(", ")}¤{changed.length > 40 ? ` … (¤{changed.length} files)` : ""}` : "", skipped.length ? `Not copied into Python (too large: over 64 MB, or over 256 MB in all): ¤{skipped.slice(0, 10).join(", ")}` : ""].filter(Boolean).join("\n").replace(/Files written: ¤/m, "").trim() || "(no output — print() something or end with an expression)";
      return { ok: !r.error, text: clip(text, 30000) };
    };
    const p = this.queue.then(job, job); this.queue = p.catch(() => {}); return p;
  },
  pip(args, o) {
    const job = async () => {
      this.onProgress = t => R.progress(o.rid, String(t));
      try { await this.start(o.sid); const r = await this.call({ type: "pip", args }); if (r.installed) PyCache.record(r.installed); if (r.removed) PyCache.record([], r.removed); return r.text; }
      catch (e) { return "ERROR: " + e.message.split("\n").filter(Boolean).slice(-3).join("\n"); }
      finally { this.onProgress = null; }
    };
    const p = this.queue.then(job, job); this.queue = p.catch(() => {}); return p;
  },
};
window.__py = (rid, o) => { o.rid = rid; if (o.reset) PY.reset(); PY.run(o.code, o).then(r => finishJob(rid, r), e => finishJob(rid, { ok: false, text: "Error: " + (e.message || e) })); };
window.__pip = (rid, o) => { PY.pip(o.args || [], { rid, sid: o.sid }).then(t => finishJob(rid, { ok: true, text: t }), e => finishJob(rid, { ok: false, text: "ERROR: " + (e.message || e) })); };
function replWorkerMain() {
const pend = new Map(); let seq = 0; const __S = Object.create(null); let RUN = null;
const call = (name, args = {}) => new Promise((resolve, reject) => { const id = ++seq; pend.set(id, { resolve, reject }); self.postMessage({ type: "call", id, name, args }); });
const tools = new Proxy({}, { get: (_, name) => typeof name !== "string" || name === "then" ? undefined : (args = {}) => call(name, args) });
const llm = (prompt, opts = {}) => call("__llm", { prompt: typeof prompt === "string" ? prompt : JSON.stringify(prompt), ...opts });
const llm_batch = async (prompts, opts = {}) => { const out = new Array(prompts.length); let i = 0; const lane = async () => { while (i < prompts.length) { const k = i++; try { out[k] = await llm(prompts[k], opts); } catch (e) { out[k] = "Error: " + e.message; } } }; await Promise.all(Array.from({ length: Math.min(opts.concurrency || 4, prompts.length) }, lane)); return out; };
const spawn = (goal, opts = {}) => call("__spawn", { goal, ...opts });
const BUILTIN = new Set(["console", "fs", "tools", "llm", "llm_batch", "spawn", "vars", "__S", "arguments", "eval", "undefined", "NaN", "Infinity"]);
const vars = () => Object.fromEntries(Object.keys(__S).map(k => [k, typeof __S[k] === "function" ? "function" : Array.isArray(__S[k]) ? "array(" + __S[k].length + ")" : typeof __S[k] === "string" ? "string(" + __S[k].length + ")" : typeof __S[k]]));
function declared(code) {
  const n = new Set();
  for (const m of code.matchAll(/^(?:var|let|const)\s+([A-Za-z_¤][\w¤]*)/gm)) n.add(m[1]);
  for (const m of code.matchAll(/^(?:var|let|const)\s*[{[]([^=]*)[}\]]\s*=/gm)) for (const x of m[1].split(",")) { const k = x.split(":").pop().split("=")[0].replace(/\.\.\./, "").trim(); if (/^[A-Za-z_¤][\w¤]*¤/.test(k)) n.add(k); }
  for (const m of code.matchAll(/^(?:async\s+)?function\s*\*?\s*([A-Za-z_¤][\w¤]*)/gm)) n.add(m[1]);
  for (const m of code.matchAll(/^class\s+([A-Za-z_¤][\w¤]*)/gm)) n.add(m[1]);
  for (const m of code.matchAll(/^([A-Za-z_¤][\w¤]*)\s*=[^=>]/gm)) n.add(m[1]);
  return [...n].filter(k => !BUILTIN.has(k));
}
self.onmessage = async (e) => {
  const msg = e.data;
  if (msg.type === "result") { const p = pend.get(msg.id); if (!p) return; pend.delete(msg.id); msg.error ? p.reject(new Error(msg.error)) : p.resolve(msg.value); return; }
  const { code, files, cwd, persist } = msg; const index = msg.index || []; const out = []; const writes = {}; let streamed = 0;
  const fmt = a => a.map(x => typeof x === "string" ? x : (() => { try { return JSON.stringify(x, null, 2); } catch { return String(x); } })()).join(" ");
  const say = t => { out.push(t); if (streamed++ < 300) self.postMessage({ type: "log", line: t.slice(0, 300) }); };
  const con = { log: (...a) => say(fmt(a)), info: (...a) => say(fmt(a)), warn: (...a) => say("WARN " + fmt(a)), error: (...a) => say("ERROR " + fmt(a)), table: (x) => say(fmt([x])) };
  const R = p => { if (!p.startsWith("/")) p = cwd + "/" + p; const o = []; for (const s of p.split("/")) { if (!s || s === ".") continue; if (s === "..") o.pop(); else o.push(s); } return "/" + o.join("/"); };
  /* files: text as strings, binary as Uint8Array (both directions); anything on the disk that is not in the snapshot comes with await fs.load(p) */
  const te = new TextEncoder(), td = new TextDecoder(); const isBin = x => x instanceof Uint8Array;
  const get = p => { p = R(p); if (p in writes) return writes[p]; if (p in files) return files[p]; throw new Error("ENOENT " + p + (index.includes(p) ? " (on the disk but not preloaded — use: await fs.load(path))" : "")); };
  const fs = { readFile: (p, enc) => { const v = get(p); const e = enc && typeof enc === "object" ? (enc.encoding === null ? "binary" : enc.encoding) : enc; if (e === "binary" || e === "bytes" || e === "buffer") return isBin(v) ? v : te.encode(v); if (e === "utf8" || e === "utf-8" || e === "text") return isBin(v) ? td.decode(v) : v; return v; },
               readText: p => { const v = get(p); return isBin(v) ? td.decode(v) : v; }, readBytes: p => { const v = get(p); return isBin(v) ? v : te.encode(v); }, readJSON: p => JSON.parse(fs.readText(p)),
               writeFile: (p, c) => { writes[R(p)] = typeof c === "string" ? c : isBin(c) ? c : c instanceof ArrayBuffer ? new Uint8Array(c) : ArrayBuffer.isView(c) ? new Uint8Array(c.buffer, c.byteOffset, c.byteLength) : JSON.stringify(c, null, 2); },
               load: async p => { p = R(p); if (p in writes) return writes[p]; if (p in files) return files[p]; const v = await call("__fs", { op: "read", path: p }); files[p] = v; return v; },
               exists: p => { p = R(p); return p in files || p in writes || index.includes(p); },
               isBinary: p => isBin(get(p)),
               list: (dir = cwd) => { dir = R(dir); return [...new Set([...index, ...Object.keys(files), ...Object.keys(writes)])].filter(f => f.startsWith(dir + "/")).sort(); } };
  let body = code;
  if (persist) {
    // REPL: names from earlier cells are loaded as locals, top-level declarations become vars, and everything is saved back at the end
    const names = [...new Set([...Object.keys(__S), ...declared(code)])].filter(k => /^[A-Za-z_¤][\w¤]*¤/.test(k) && !BUILTIN.has(k));
    const src = code.replace(/^(const|let)(\s)/gm, "var¤2").replace(/^class\s+([A-Za-z_¤][\w¤]*)/gm, "var ¤1 = class ¤1");
    body = (names.length ? "var " + names.map(k => k + " = __S[" + JSON.stringify(k) + "]").join(", ") + ";\n" : "") + "try {\n" + src + "\n} finally {\n" + names.map(k => "try { if (typeof " + k + " !== 'undefined') __S[" + JSON.stringify(k) + "] = " + k + "; } catch (_) {}").join("\n") + "\n}";
  }
  try { const AF = Object.getPrototypeOf(async function () {}).constructor; const r = await new AF("console", "fs", "tools", "llm", "llm_batch", "spawn", "vars", "__S", body)(con, fs, tools, llm, llm_batch, spawn, vars, __S);
        if (r !== undefined) out.push("=> " + fmt([r])); self.postMessage({ type: "done", ok: true, out, writes, vars: persist ? Object.keys(__S) : [] }); }
  catch (err) { self.postMessage({ type: "done", ok: false, out, writes, error: String(err && err.stack || err), vars: persist ? Object.keys(__S) : [] }); }
};
}
const REPL_SRC = `(¤{replWorkerMain.toString()})();`;
const PTC_PROGRESS = 20;
/* The REPL worker lives as long as the conversation: variables and functions from earlier execute_code calls stay defined */
const REPL = {
  w: null, sid: null, vars: [],
  get(sid) { if (this.w && this.sid === sid) return this.w; this.reset(); this.w = new Worker(blobUrl(REPL_SRC)); this.sid = sid; return this.w; },
  reset() { if (this.w) this.w.terminate(); this.w = null; this.vars = []; },
};
const LIMIT = (n) => { let active = 0; const q = []; const next = () => { if (active >= n || !q.length) return; active++; const [fn, res, rej] = q.shift(); fn().then(res, rej).finally(() => { active--; next(); }); }; return fn => new Promise((res, rej) => { q.push([fn, res, rej]); next(); }); };
const LLM_LANES = LIMIT(4), SPAWN_LANES = LIMIT(3);
const pendingCalls = new Map(); let cseq = 0;
const bridge = (rid, name, args) => new Promise((resolve, reject) => { const cid = ++cseq; pendingCalls.set(cid, { resolve, reject }); try { R.call(rid, cid, name, JSON.stringify(args || {})); } catch (e) { pendingCalls.delete(cid); reject(e); } });
window.__reply = (cid, value, error) => { const c = pendingCalls.get(cid); if (!c) return; pendingCalls.delete(cid); error ? c.reject(new Error(error)) : c.resolve(value); };
/* runJS: code runs in a worker; tools.<name>(args) calls are executed by the app (same approvals);
   llm()/llm_batch() are model calls and spawn() runs a helper agent. */
async function runJS(code, { rid, timeout = 120000, ctx = false, sid = null, persist = false, cwd = CWD } = {}) {
  const progress = line => { try { R.progress(rid, String(line)); } catch (e) {} };
  const files = {}; let budget = 8_000_000, binBudget = 24_000_000;
  const idx = host.index(); const meta = new Map(idx.map(x => [x[0], x]));
  const paths = idx.map(x => x[0]).sort((a, b) => (b.startsWith(CWD + "/inputs/") ? 1 : 0) - (a.startsWith(CWD + "/inputs/") ? 1 : 0));
  for (const p of paths) { const [, , s, isText] = meta.get(p); if (isText) { if ((budget -= s) < 0) continue; try { files[p] = host.text(p); } catch {} } else { if (s > binBudget) continue; binBudget -= s; try { files[p] = host.bytes(p); } catch {} } }
  const index = paths;
  const w = persist ? REPL.get(sid) : new Worker(blobUrl(REPL_SRC));
  let calls = 0, llmCalls = 0, active = 0, waited = 0, waitFrom = 0, aborted = cancelled.has(rid); const started = Date.now();
  aborts.set(rid, () => { aborted = true; });
  const computeMs = () => Date.now() - started - waited - (active ? Date.now() - waitFrom : 0); // time spent in tools/llm()/spawn() does not count
  const res = await new Promise(resolve => {
    const finish = (r, kill) => { clearInterval(tick); w.onmessage = null; w.onerror = null; if (!persist || kill) { if (persist) { REPL.reset(); r.error = (r.error || "") + " — the REPL was restarted, so variables from earlier cells are gone"; } else w.terminate(); } resolve(r); };
    const tick = setInterval(() => { if (active === 0 && computeMs() > timeout) finish({ ok: false, out: [], writes: {}, error: `Stopped: the code ran longer than its time limit of ¤{Math.round(timeout / 1000)} s (pass timeout: seconds, up to 1800, for longer computations)` }, true); else if (Date.now() - started > Math.max(900000, timeout + 600000)) finish({ ok: false, out: [], writes: {}, error: "Stopped: total run time limit reached" }, true); if (aborted) finish({ ok: false, out: [], writes: {}, error: "Stopped by the user" }, true); }, 500);
    w.onmessage = async e => {
      const m = e.data;
      if (m.type === "done") return finish(m);
      if (m.type === "log") { progress(m.line); return; }
      if (m.type !== "call") return;
      if (m.name === "__fs") { let value, error; try { const p = String((m.args || {}).path || ""); const x = meta.get(p); if (!x) throw new Error("ENOENT " + p); value = x[3] ? host.text(p) : host.bytes(p); } catch (err) { error = err.message; } try { w.postMessage({ type: "result", id: m.id, value, error }); } catch {} return; }
      calls++; if (!active) waitFrom = Date.now(); active++;
      let value, error;
      try {
        if (!ctx) throw new Error("tools, llm() and spawn() are only available through the execute_code tool");
        if (m.name === "__llm") {
          if (++llmCalls > 300) throw new Error("llm() call limit (300 per cell) reached");
          const a = m.args || {}; llmCalls <= PTC_PROGRESS && progress(`→ llm(¤{clip(a.prompt, 90)})`);
          value = await LLM_LANES(() => bridge(rid, "__llm", a));
        } else if (m.name === "__spawn") {
          const a = m.args || {}; if (!a.goal) throw new Error("spawn(goal, {context, toolsets, spec}) needs a goal");
          progress(`→ spawn(¤{clip(a.goal, 90)})`);
          value = await SPAWN_LANES(() => bridge(rid, "__spawn", { goal: String(a.goal), context: a.context ? String(a.context) : "", toolsets: a.toolsets, spec: a.spec }));
        } else {
          progress(`→ tools.¤{m.name}(¤{clip(JSON.stringify(m.args), 120)})`);
          value = await bridge(rid, m.name, m.args || {});
        }
      } catch (err) { error = err.message || String(err); }
      active--; if (!active) waited += Date.now() - waitFrom;
      try { w.postMessage({ type: "result", id: m.id, value, error }); } catch {}
    };
    w.onerror = e => finish({ ok: false, out: [], writes: {}, error: e.message }, true);
    w.postMessage({ type: "run", code, files, index, cwd, persist });
  });
  aborts.delete(rid);
  if (persist) REPL.vars = res.vars || REPL.vars;
  const written = [], failed = [];
  for (const [p, c] of Object.entries(res.writes || {})) { try { host.write(p, c); written.push(p); } catch (e) { failed.push(`¤{p} (¤{e.message})`); } }
  const text = [(res.out || []).join("\n"), res.error ? "Error: " + res.error : "", written.length ? "Files written: " + written.join(", ") : "", failed.length ? "Could not write: " + failed.join(", ") : "", calls ? `(¤{calls} call¤{calls > 1 ? "s" : ""} made from code¤{llmCalls ? `, ¤{llmCalls} to llm()` : ""})` : "", persist && REPL.vars.length ? `[REPL variables: ¤{clip(REPL.vars.join(", "), 400)}]` : ""].filter(Boolean).join("\n") || "(no output — use console.log or return a value)";
  return { ok: res.ok, text: clip(text, 30000) };
}
window.__js = (rid, o) => { if (o.reset) REPL.reset(); runJS(o.code, { rid, timeout: o.timeout, ctx: !!o.ctx, sid: o.sid, persist: !!o.persist, cwd: o.cwd || CWD }).then(r => finishJob(rid, r), e => finishJob(rid, { ok: false, text: "Error: " + (e.message || e) })); };
/* awk for the terminal: the original compiles awk programs to JavaScript, so it runs here, in a worker with a time limit */
const AWK_SRC = `(¤{awkWorkerMain.toString()})();`;
window.__awk = (rid, o) => {
  let w; try { w = new Worker(blobUrl(AWK_SRC)); } catch (e) { finishJob(rid, { ok: false, text: "awk: " + (e.message || e) }); return; }
  let over = false; const end = r => { if (over) return; over = true; clearTimeout(t); aborts.delete(rid); w.terminate(); finishJob(rid, r); };
  const t = setTimeout(() => end({ ok: false, text: "awk: stopped after 30 s (endless loop?)" }), 30000);
  aborts.set(rid, () => end({ ok: false, text: "stopped" }));
  w.onmessage = e => end(e.data.error != null ? { ok: false, text: e.data.error } : { ok: true, text: e.data.out });
  w.onerror = e => { e.preventDefault && e.preventDefault(); end({ ok: false, text: e.message || "awk failed" }); };
  w.postMessage(o);
};
function awkWorkerMain() {
  let FILES = {};
  const src = (files, stdin) => files.length ? files.map(p => { if (!(p in FILES)) throw new Error("ENOENT " + p); return FILES[p]; }).join("") : (stdin || "");
  const lines = s => { const l = String(s || "").split("\n"); if (l[l.length - 1] === "") l.pop(); return l; };
  const join = l => l.length ? l.join("\n") + "\n" : "";
  const opt = (args, name, def) => { const i = args.indexOf(name); if (i >= 0) { const v = args[i + 1]; args.splice(i, 2); return v; } const j = args.findIndex(a => a.startsWith(name) && a.length > name.length && name.length === 2); if (j >= 0) { const v = args[j].slice(2); args.splice(j, 1); return v; } return def; };
  function awk(args, stdin) {
    const vars = {}; let fs = opt(args, "-F"); let v; while ((v = opt(args, "-v")) !== undefined) { const m = v.match(/^(\w+)=(.*)¤/); if (m) vars[m[1]] = m[2]; }
    const prog = args.shift(); if (prog == null) throw new Error("usage: awk [-F SEP] 'PROGRAM' [FILE...]");
    const rules = []; let s = prog.trim();
    while (s) { let pat = "", act = null;
      if (s[0] !== "{") { let i = 0, q = null; for (; i < s.length; i++) { const c = s[i]; if (q) { if (c === "\\") { i++; continue; } if (c === q) q = null; continue; } if (c === '"' || (c === "/" && !/[\w)\]]\s*¤/.test(s.slice(0, i)))) { q = c; continue; } if (c === "{" || c === "\n" || c === ";") break; } pat = s.slice(0, i).trim(); s = s.slice(i).trimStart(); }
      if (s[0] === "{") { let d = 0, i = 0, q = null; for (; i < s.length; i++) { const c = s[i]; if (q) { if (c === "\\") { i++; continue; } if (c === q) q = null; continue; } if (c === '"') { q = c; continue; } if (c === "{") d++; else if (c === "}") { d--; if (!d) break; } } act = s.slice(1, i); s = s.slice(i + 1).trimStart(); }
      s = s.replace(/^[;\n\s]+/, ""); rules.push({ pat, act }); }
    const fields = e => e.replace(/\¤NF\b/g, "F[NF]").replace(/\¤\(([^)]+)\)/g, "F[¤1]").replace(/\¤(\d+)/g, "F[¤1]").replace(/\¤([A-Za-z_]\w*)/g, "F[¤1]");
    const jsExpr = e => e.replace(/\¤NF\b/g, "F[NF]").replace(/\¤\(([^)]+)\)/g, "F[¤1]").replace(/\¤(\d+)/g, "F[¤1]").replace(/\¤([A-Za-z_]\w*)/g, "F[¤1]")
      .replace(/(^|[^\w\]\)\s])\s*\/((?:[^/\\\n]|\\.)+)\/(?!\w)/g, (m, p, re) => `¤{p} /¤{re}/.test(F[0])`).replace(/(\S)\s*~\s*\/((?:[^/\\]|\\.)+)\//g, "/¤2/.test(¤1)").replace(/(\S)\s*!~\s*\/((?:[^/\\]|\\.)+)\//g, "!/¤2/.test(¤1)")
      .replace(/("(?:[^"\\]|\\.)*"|F\[[^\]]+\]|\b(?!in\b|if\b|else\b|for\b|while\b|return\b)[A-Za-z_]\w*\b(?!\s*\()|\d+(?:\.\d+)?)\s+(?=("|F\[|\b(?!in\b|if\b|else\b)[A-Za-z_]\w*\b(?!\s*\()|\d))/g, "¤1 + ");
    const jsAct = a => fields(a).split(/\n/).join(";").replace(/\bprintf\s*\(?([^;]*?)\)?\s*(;|¤)/g, (m, x, e) => `__pf(¤{jsExpr(x)})¤{e}`).replace(/\bprint\s*(>|\()?([^;]*)(;|¤)/g, (m, par, x, e) => `__p(¤{x.trim() ? jsExpr(par === "(" ? "(" + x : x) : "F[0]"})¤{e}`).replace(/\bnext\b/g, "throw __NEXT").replace(/(^|[^.\w])(length)\b(?!\s*\()/g, "¤1length(F[0])");
    const out = []; const NEXT = {};
    const fmt = (f, ...a) => { let i = 0; return String(f).replace(/%(-?\d*)(?:\.(\d+))?([sdfix%])/g, (m, w, p, t) => { if (t === "%") return "%"; let v = a[i++]; v = t === "d" || t === "i" ? String(Math.trunc(+v || 0)) : t === "f" ? (+v || 0).toFixed(p ? +p : 6) : t === "x" ? (Math.trunc(+v) || 0).toString(16) : String(v ?? ""); const n = Math.abs(+w || 0); return n ? (w.startsWith("-") ? v.padEnd(n) : v.padStart(n)) : v; }).replace(/\\n/g, "\n").replace(/\\t/g, "\t"); };
    const S = new Proxy(Object.assign(Object.create(null), vars, { NR: 0, NF: 0, FS: fs || " ", OFS: " ", __NEXT: NEXT, length: x => String(x ?? "").length, substr: (x, a, b) => String(x).substr(a - 1, b), index: (x, y) => String(x).indexOf(y) + 1, split: (x, arr, sep) => { const p = String(x).split(sep == null ? /\s+/ : sep); for (const k in arr) delete arr[k]; p.forEach((y, i) => { arr[i + 1] = y; }); return p.length; }, tolower: x => String(x).toLowerCase(), toupper: x => String(x).toUpperCase(), int: x => Math.trunc(+x), sprintf: fmt, gsub: null, sub: null, __p: (...a) => out.push(a.map(x => typeof x === "number" && !Number.isInteger(x) ? +x.toFixed(6) : x).join(S.OFS)), __pf: (...a) => { const t = fmt(...a); if (out.length && out.__open) out[out.length - 1] += t; else out.push(t); out.__open = !t.endsWith("\n"); if (!out.__open) out[out.length - 1] = out[out.length - 1].replace(/\n¤/, ""); } }),
      { has: (t, k) => k !== "F" && (typeof k === "string" && !(k in globalThis && !(k in t)) || k in t), get: (t, k) => k === Symbol.unscopables ? undefined : (k in t ? t[k] : (k === "F" ? undefined : (t[k] = 0))), set: (t, k, v) => { t[k] = v; return true; } });
    const compile = code => { try { return new Function("S", "F", `with (S) { ¤{code} }`); } catch (e) { throw new Error("awk: cannot understand the program (" + e.message + ")"); } };
    const R2 = rules.map(r => ({ kind: r.pat === "BEGIN" ? "B" : r.pat === "END" ? "E" : "M", test: r.pat && r.pat !== "BEGIN" && r.pat !== "END" ? compile(`return (¤{jsExpr(r.pat)})`) : null, act: compile(r.act == null ? "__p(F[0])" : jsAct(r.act)) }));
    const F = []; const call = (fn) => { try { fn(S, F); } catch (e) { if (e !== NEXT) throw new Error("awk: " + e.message); } };
    for (const r of R2) if (r.kind === "B") call(r.act);
    for (const line of lines(src(args, stdin))) { S.NR++; const sep = S.FS === " " ? /\s+/ : S.FS.length === 1 ? S.FS : new RegExp(S.FS); const parts = S.FS === " " ? line.trim().split(sep).filter(Boolean) : line.split(sep); F.length = 0; F.push(line, ...parts.map(x => (/^-?(0|[1-9]\d*)(\.\d+)?¤/.test(x) ? Number(x) : x))); S.NF = parts.length;
      for (const r of R2) { if (r.kind !== "M") continue; let ok = true; if (r.test) { try { ok = r.test(S, F); } catch (e) { throw new Error("awk: " + e.message); } } if (ok) { let nxt = false; try { r.act(S, F); } catch (e) { if (e === NEXT) nxt = true; else throw new Error("awk: " + e.message); } if (nxt) break; } } }
    for (const r of R2) if (r.kind === "E") call(r.act);
    return join(out.map(x => String(x)));
  }
  self.onmessage = e => { const m = e.data || {}; FILES = m.files || {}; try { self.postMessage({ out: awk((m.args || []).slice(), m.stdin || "") }); } catch (err) { self.postMessage({ error: String(err && err.message || err) }); } };
}
try { R.ready(); } catch (e) {}
})();
"""
