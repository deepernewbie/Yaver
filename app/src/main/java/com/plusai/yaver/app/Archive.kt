package com.plusai.yaver.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Archives: unpack .zip / .tar / .tar.gz / .tgz / .gz, pack .zip. Streams entries to disk (no whole-archive buffering). */
object Archive {
    private const val MAX_ENTRIES = 20000

    class Unpacked(val dest: String, val files: Int, val bytes: Long, val skipped: Int, val written: List<String>)
    class Packed(val out: String, val files: Int, val bytes: Long)

    fun kind(h: ByteArray, name: String = ""): String? {
        val n = name.lowercase()
        fun b(i: Int) = if (i < h.size) h[i].toInt() and 0xff else -1
        if (b(0) == 0x50 && b(1) == 0x4b) return "zip"
        if (b(0) == 0x1f && b(1) == 0x8b) return if (Regex("\\.(tar\\.gz|tgz)$").containsMatchIn(n)) "tgz" else "gz"
        if (h.size > 262 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar") return "tar"
        if (n.endsWith(".tar")) return "tar"
        if (b(0) == 0x42 && b(1) == 0x5a && b(2) == 0x68) return "bz2"
        if (b(0) == 0xfd && b(1) == 0x37 && b(2) == 0x7a) return "xz"
        if (b(0) == 0x37 && b(1) == 0x7a && b(2) == 0xbc) return "7z"
        if (b(0) == 0x52 && b(1) == 0x61 && b(2) == 0x72) return "rar"
        return null
    }

    fun isArchiveName(n: String) = Regex("\\.(zip|tar|tgz|tar\\.gz|gz)$", RegexOption.IGNORE_CASE).containsMatchIn(n)

    /** Removes ".", empty parts and climbs out of ".." so nothing lands outside the destination. */
    fun safe(rel: String): String {
        val parts = ArrayList<String>()
        for (s in rel.replace('\\', '/').split('/')) { if (s.isEmpty() || s == ".") continue; if (s == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); continue }; parts.add(s) }
        return parts.joinToString("/")
    }

    private fun head(f: File, n: Int = 512): ByteArray = f.inputStream().use { s -> val b = ByteArray(n); var r = 0; while (r < n) { val k = s.read(b, r, n - r); if (k < 0) break; r += k }; b.copyOf(r) }

    private fun readFully(s: InputStream, b: ByteArray): Int { var r = 0; while (r < b.size) { val k = s.read(b, r, b.size - r); if (k < 0) break; r += k }; return r }

    private fun skipFully(s: InputStream, n: Long) { var left = n; val buf = ByteArray(8192); while (left > 0) { val k = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (k < 0) break; left -= k } }

    /** Reads at most [left] bytes of the underlying stream; close() does nothing. */
    private class Bounded(s: InputStream, var left: Long) : FilterInputStream(s) {
        override fun read(): Int { if (left <= 0) return -1; val r = super.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { if (left <= 0) return -1; val r = super.read(b, off, minOf(len.toLong(), left).toInt()); if (r > 0) left -= r; return r }
        override fun close() {}
    }

    private fun utf8OrLatin1(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (e: Throwable) { String(b, Charsets.ISO_8859_1) }

    /** Walks a tar stream; [each] gets (name, isDir, data) — data must be consumed before returning (or not at all). */
    private fun tar(s: InputStream, cancel: Cancel?, each: (String, Boolean, InputStream) -> Unit) {
        val h = ByteArray(512)
        var longName: String? = null; var paxPath: String? = null
        fun str(a: Int, b: Int): ByteArray { var e = a; while (e < b && h[e].toInt() != 0) e++; return h.copyOfRange(a, e) }
        fun oct(a: Int, b: Int): Long = String(str(a, b), Charsets.ISO_8859_1).trim().toLongOrNull(8) ?: 0L
        while (true) {
            cancel?.check()
            if (readFully(s, h) < 512) break
            if (h.all { it.toInt() == 0 }) break
            var name = utf8OrLatin1(str(0, 100))
            val size = oct(124, 136)
            val type = if (h[156].toInt() == 0) '0' else h[156].toInt().toChar()
            val prefix = utf8OrLatin1(str(345, 500))
            if (prefix.isNotEmpty() && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar") name = "$prefix/$name"
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L', 'x' -> {
                    if (size > 1_000_000) { skipFully(s, padded); continue }
                    val b = ByteArray(size.toInt()); readFully(s, b); skipFully(s, padded - size)
                    val t = String(b, Charsets.UTF_8)
                    if (type == 'L') longName = t.trimEnd('\u0000') else Regex("\\d+ path=([^\\n]*)\\n").find(t)?.let { paxPath = it.groupValues[1] }
                    continue
                }
                'g' -> { skipFully(s, padded); continue }
            }
            name = paxPath ?: longName ?: name; paxPath = null; longName = null
            when (type) {
                '5' -> { each(name.trimEnd('/') + "/", true, ByteArray(0).inputStream()); skipFully(s, padded) }
                '0', '7' -> { val b = Bounded(s, size); each(name, false, b); skipFully(s, b.left + (padded - size)) }
                else -> skipFully(s, padded) // symlinks, devices and fifos are skipped
            }
        }
    }

    private fun zipFile(f: File): ZipFile {
        for (cs in listOf("UTF-8", "IBM857", "IBM437")) {
            if (!Charset.isSupported(cs)) continue
            val z = ZipFile(f, Charset.forName(cs))
            try { z.entries().toList(); return z } catch (e: IllegalArgumentException) { z.close() }
        }
        return ZipFile(f)
    }

    /** Calls [each] for every entry; returns the archive kind. */
    private fun entries(src: File, cancel: Cancel?, each: (String, Boolean, InputStream) -> Unit): String {
        val h = head(src)
        val k = kind(h, src.name)
        when (k) {
            "zip" -> zipFile(src).use { z ->
                if (z.size() > MAX_ENTRIES) throw IllegalArgumentException("archive has ${z.size()} entries; the limit is $MAX_ENTRIES")
                for (e in z.entries()) { cancel?.check(); if (e.isDirectory) each(e.name, true, ByteArray(0).inputStream()) else z.getInputStream(e).use { each(e.name, false, it) } }
            }
            "tar" -> src.inputStream().buffered().use { tar(it, cancel, each) }
            "tgz" -> GZIPInputStream(src.inputStream().buffered(), 65536).use { tar(it, cancel, each) }
            "gz" -> BufferedInputStream(GZIPInputStream(src.inputStream().buffered(), 65536), 65536).use { s ->
                s.mark(1024)
                val inner = ByteArray(512); val r = readFully(s, inner); s.reset()
                val base = src.name.replace(Regex("\\.gz$", RegexOption.IGNORE_CASE), "").ifEmpty { "file" }
                if (kind(inner.copyOf(r), base) == "tar") tar(s, cancel, each) else each(base, false, s)
            }
            null -> throw IllegalArgumentException("Not a recognised archive (supported: zip, tar, tar.gz / tgz, gz).")
            else -> throw IllegalArgumentException(".$k archives are not supported on the phone. Supported: zip, tar, tar.gz / tgz, gz.")
        }
        return k
    }

    private fun write(target: File, data: InputStream): Long {
        target.parentFile?.mkdirs()
        return target.outputStream().use { o -> data.copyTo(o, 65536) }
    }

    /** Names of the entries in an archive (tar -t). */
    fun list(path: String): List<String> {
        val src = Workspace.resolve(path)
        if (!src.isFile) throw IllegalArgumentException("ENOENT: no such file ${Workspace.rel(src)}")
        val out = ArrayList<String>()
        entries(src, null) { name, dir, _ -> if (out.size < MAX_ENTRIES) out.add(if (dir && !name.endsWith("/")) "$name/" else name) }
        return out
    }

    fun unpack(path: String, dest0: String?, overwrite: Boolean = true, cancel: Cancel? = null): Unpacked {
        val src = Workspace.resolve(path)
        if (!src.isFile) throw IllegalArgumentException("ENOENT: no such file ${Workspace.rel(src)}")
        val extRe = Regex("\\.(zip|tar|tgz|tar\\.gz|gz)$", RegexOption.IGNORE_CASE)
        // a single gzipped file (not a tar) → a file, not a folder
        if (kind(head(src), src.name) == "gz") {
            val single = BufferedInputStream(GZIPInputStream(src.inputStream().buffered()), 1024).use { s -> val b = ByteArray(512); val r = readFully(s, b); kind(b.copyOf(r), src.name.replace(Regex("\\.gz$", RegexOption.IGNORE_CASE), "")) != "tar" }
            if (single) {
                val name = src.name.replace(Regex("\\.gz$", RegexOption.IGNORE_CASE), "").ifEmpty { "file" }
                val out = if (!dest0.isNullOrBlank()) Workspace.resolve(dest0).let { if (it.isDirectory) File(it, name) else it } else File(src.parentFile, name)
                if (!overwrite && out.exists()) return Unpacked(Workspace.rel(out), 0, 0, 1, emptyList())
                val n = GZIPInputStream(src.inputStream().buffered()).use { write(out, it) }
                return Unpacked(Workspace.rel(out), 1, n, 0, listOf(out.name))
            }
        }
        var dest = if (!dest0.isNullOrBlank()) Workspace.resolve(dest0)
            else File(src.parentFile, if (extRe.containsMatchIn(src.name)) src.name.replace(extRe, "") else src.name + "_unpacked")
        if (dest.canonicalPath == src.canonicalPath) dest = File(dest.path + "_unpacked")
        dest.mkdirs()
        val root = dest.canonicalPath
        var files = 0; var bytes = 0L; var skipped = 0; var count = 0
        val written = ArrayList<String>()
        entries(src, cancel) { name, dir, data ->
            if (++count > MAX_ENTRIES) throw IllegalArgumentException("archive has more than $MAX_ENTRIES entries; the limit is $MAX_ENTRIES (stopped after unpacking part of it)")
            val rel = safe(name)
            if (rel.isEmpty() || Regex("(^|/)__MACOSX(/|$)|(^|/)\\.DS_Store$").containsMatchIn(rel)) return@entries
            val target = File(dest, rel).canonicalFile
            if (!target.path.startsWith("$root/")) return@entries
            if (dir) { target.mkdirs(); return@entries }
            if (!overwrite && target.exists()) { skipped++; return@entries }
            bytes += write(target, data); files++
            if (written.size < 40) written.add(rel)
        }
        return Unpacked(Workspace.rel(dest), files, bytes, skipped, written)
    }

    fun pack(paths: List<String>, out0: String?, cancel: Cancel? = null): Packed {
        var outPath = out0?.takeIf { it.isNotBlank() } ?: "archive.zip"
        if (!outPath.lowercase().endsWith(".zip")) outPath += ".zip"
        val out = Workspace.resolve(outPath)
        val items = ArrayList<Pair<String, File>>()
        for (p0 in paths) {
            val p = Workspace.resolve(p0)
            when {
                p.isFile -> items.add(p.name to p)
                p.isDirectory -> { val base = p.name.ifEmpty { "root" }; p.walkTopDown().filter { it.isFile && it.canonicalPath != out.canonicalPath }.forEach { items.add(base + "/" + it.relativeTo(p).invariantSeparatorsPath to it) } }
                else -> throw IllegalArgumentException("ENOENT: $p0")
            }
        }
        if (items.isEmpty()) throw IllegalArgumentException("nothing to pack")
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            val seen = HashSet<String>()
            for ((name, f) in items) {
                cancel?.check()
                if (!seen.add(name)) continue
                z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(z, 65536) }
                z.closeEntry()
            }
        }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        return Packed(Workspace.rel(out), items.size, out.length())
    }

    fun register() {
        Tools.add(Tool(
            "unpack_archive",
            "Unpack an archive in the workspace (.zip, .tar, .tar.gz / .tgz, .gz) into a folder. Paths inside the archive are sanitised so nothing is written outside the destination. Returns the list of extracted files.",
            P.props("path" to P.s("Archive path"), "dest" to P.s("Destination folder (default: next to the archive, named after it)"), "overwrite" to P.b("Overwrite existing files (default true)")),
            listOf("path")
        ) { a, ctx ->
            val overwrite = !(a.has("overwrite") && !a.isNull("overwrite") && !a.optBoolean("overwrite", true))
            val dest = a.str("dest")
            if (overwrite && dest.isNotEmpty()) {
                val d = Workspace.resolve(dest)
                if (d.isDirectory && d.walkTopDown().any { it.isFile } &&
                    !ctx.approve(Y.t("Unpack (may overwrite files)", "Arşivi aç (dosyaların üzerine yazabilir)") + ": " + Workspace.rel(d), listOf(Y.t("Archive", "Arşiv") to a.str("path"))))
                    return@Tool ctx.denied("unpack into ${Workspace.rel(d)}")
            }
            ctx.progress(Y.t("Unpacking…", "Arşiv açılıyor…"))
            val r = unpack(a.str("path"), dest, overwrite, ctx.cancel)
            WsChange.notify(r.dest)
            "Unpacked ${r.files} file${if (r.files == 1) "" else "s"} (${Workspace.fmtBytes(r.bytes)}) into ${r.dest}${if (r.skipped > 0) ", skipped ${r.skipped} existing" else ""}:\n" +
                r.written.joinToString("\n") { "  $it" } + if (r.files > r.written.size) "\n  … and ${r.files - r.written.size} more" else ""
        })

        Tools.add(Tool(
            "pack_archive",
            "Create a .zip archive from files and folders in the workspace (for example to hand results to the user as one file).",
            P.props("paths" to P.arr("Files and folders to include"), "out" to P.s("Output .zip path (default archive.zip)")),
            listOf("paths")
        ) { a, ctx ->
            val outArg = a.str("out").ifEmpty { "archive.zip" }.let { if (it.lowercase().endsWith(".zip")) it else "$it.zip" }
            val existing = Workspace.resolve(outArg)
            if (existing.exists() && !ctx.approve(Y.t("Overwrite file", "Dosyanın üzerine yaz") + ": " + Workspace.rel(existing), listOf(Y.t("Size", "Boyut") to Workspace.fmtBytes(existing.length()))))
                return@Tool ctx.denied("overwrite ${Workspace.rel(existing)}")
            ctx.progress(Y.t("Packing…", "Paketleniyor…"))
            val r = pack(a.list("paths"), outArg, ctx.cancel)
            WsChange.notify(r.out)
            "Created ${r.out} with ${r.files} file${if (r.files == 1) "" else "s"} (${Workspace.fmtBytes(r.bytes)}). The user can open or share it from Files (or you can use export_file)."
        })
    }
}
