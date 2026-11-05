package com.plusai.yaver.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * write_document: real Word / Excel / PowerPoint / PDF files (and .md / .html) from Markdown or CSV, built natively so a
 * weak model never has to hand-write OOXML. Everything except [PdfW] is plain JVM code (no Android types) so the
 * builders can be tested off the phone.
 */
object Documents {
    private val FORMATS = setOf("docx", "xlsx", "pptx", "pdf", "md", "markdown", "html", "htm")

    const val DESC = "Create a real Word (.docx), Excel (.xlsx), PowerPoint (.pptx) or PDF (.pdf) file — or .md / .html — from Markdown or CSV. " +
        "The extension of path decides the format. Write normal Markdown: # headings, paragraphs, **bold**, *italic*, `code`, [links](url), - bullets, 1. numbered lists (indent to nest), > quotes, ``` code blocks, --- rules, | tables | with a header row. " +
        "Word/PDF: headings become real heading styles, tables real tables, pages are numbered. " +
        "Excel: each Markdown table becomes a sheet named after the heading above it (or give CSV); numbers (1.234,56 or 1234.56) and dates become real numbers/dates; a cell starting with = is a formula. " +
        "PowerPoint: the first slide is a title slide; each # or ## heading starts a new slide; bullets and tables go on the slides (long content is split over several slides). " +
        "Long documents: first write the Markdown to a .md file in parts with write_file (append=true for each next part), then call write_document with source set to that file. Never build Office files with code — use this tool."

    fun register() {
        Tools.add(Tool(
            "write_document", DESC,
            P.props(
                "path" to P.s("Output file, e.g. /workspace/outputs/report.docx. Extension: .docx (Word), .xlsx (Excel), .pptx (PowerPoint), .pdf, .md or .html"),
                "source" to P.s("Optional: a workspace file to convert (.md, .csv/.tsv, .html or .txt; the text of .docx/.pdf/.xlsx also works). Use this for long content written with write_file"),
                "content" to P.s("Optional (if no source): the content as Markdown, or CSV for Excel"),
                "title" to P.s("Document title (default: the first # heading)"),
                "sheet_name" to P.s("Excel only: sheet name (default: the heading above each table)"),
                "landscape" to P.b("Landscape pages (Word, PDF, Excel printing)"),
                "theme" to P.s("Colour theme", "default", "blue", "grey")
            ), listOf("path")
        ) { a, ctx -> create(a, ctx) })
    }

    private fun readText(f: File): String {
        val b = f.readBytes()
        var s = String(b, Charsets.UTF_8)
        // Windows-1254 (Turkish) CSV exports from Excel: invalid UTF-8 shows up as U+FFFD
        if (s.contains('\uFFFD')) s = try { String(b, charset("windows-1254")) } catch (_: Throwable) { s }
        return s
    }

    fun create(a: JSONObject, ctx: ToolCtx): String {
        val p = a.str("path")
        if (p.isEmpty()) throw IllegalArgumentException("path is required, e.g. /workspace/outputs/report.docx")
        val f = Workspace.resolve(p)
        val ext = f.extension.lowercase()
        if (ext !in FORMATS) throw IllegalArgumentException("path must end in .docx (Word), .xlsx (Excel), .pptx (PowerPoint), .pdf, .md or .html — got \"${f.name}\"")
        if (f.isDirectory) throw IllegalArgumentException("path is a folder; give a file name like ${p.trimEnd('/')}/document.$ext")
        val vp = Workspace.vpath(f)

        var kind = "md"
        val parts = ArrayList<String>()
        val srcPath = a.str("source")
        if (srcPath.isNotEmpty()) {
            val sf = Workspace.resolve(srcPath)
            if (!sf.isFile) throw IllegalArgumentException("source not found: $srcPath. Write the content first with write_file (append=true for each next part), then call write_document with that file as source.")
            if (sf.canonicalPath == f.canonicalPath) throw IllegalArgumentException("source and path are the same file; choose another output name")
            val se = sf.extension.lowercase()
            when {
                se == "csv" || se == "tsv" -> { kind = "csv"; parts.add(readText(sf)) }
                se == "html" || se == "htm" -> { kind = "html"; parts.add(readText(sf)) }
                se in setOf("docx", "xlsx", "pptx", "pdf", "odt", "ods", "odp") -> parts.add(Docs.extract(sf, "", false, ctx.cancel))
                Workspace.isText(sf) -> parts.add(readText(sf))
                else -> throw IllegalArgumentException("cannot convert ${sf.name}: give a Markdown, CSV, HTML or text file as source")
            }
        }
        val content = if (a.has("content") && !a.isNull("content")) a.optString("content", "") else ""
        if (content.isNotBlank()) parts.add(content)
        if (parts.all { it.isBlank() }) throw IllegalArgumentException("nothing to write: give content (Markdown, or CSV for Excel) or source (a workspace file). For long documents write a .md file with write_file first (in parts, append=true), then pass it as source.")

        var text = DocMd.clean(parts.joinToString("\n\n"))
        if (kind == "md" && DocMd.looksHtml(text)) kind = "html"
        var htmlTitle = ""
        if (kind == "html") { val r = DocMd.htmlToMd(text); htmlTitle = r.first; text = r.second; kind = "md" }
        val blocks = if (kind == "csv") DocMd.csvBlocks(text) else DocMd.parse(text)

        val tr = (try { Y.tr } catch (_: Throwable) { false }) || Regex("[çğıöşüÇĞİÖŞÜ]").containsMatchIn(text)
        val h1 = blocks.firstOrNull { it is Blk.H && it.level == 1 } as Blk.H?
        val title = a.str("title").ifEmpty { htmlTitle }.ifEmpty { h1?.let { DocMd.plain(it.text) } ?: "" }
            .ifEmpty { f.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim() }
        val o = DocOpts(title = title, titleGiven = a.str("title").isNotEmpty(), landscape = a.optBoolean("landscape"),
            theme = DocTheme.of(a.str("theme")), sheetName = a.str("sheet_name"), tr = tr)

        if (f.exists() && !ctx.approve(Y.t("Overwrite file", "Dosyanın üzerine yaz") + ": " + vp, listOf(Y.t("Size", "Boyut") to Workspace.fmtBytes(f.length()))))
            return ctx.denied("overwrite $vp")
        ctx.progress(Y.t("Creating ", "Oluşturuluyor: ") + f.name)

        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.part")
        val info: Built
        try {
            info = when (ext) {
                "docx" -> DocxW.build(blocks, o)
                "xlsx" -> XlsxW.build(XlsxW.sheetsFrom(blocks, o, if (kind == "csv") null else text), o)
                "pptx" -> PptxW.build(blocks, o)
                "pdf" -> { val out = ByteArrayOutputStream(); val n = PdfW.build(blocks, o, out); Built(out.toByteArray(), "PDF, $n page" + (if (n == 1) "" else "s")) }
                "html", "htm" -> Built(DocHtml.render(blocks, o).toByteArray(), "HTML page")
                else -> Built((if (kind == "csv") DocMd.toMd(blocks) else text).trimEnd().plus("\n").toByteArray(), "Markdown")
            }
            tmp.writeBytes(info.bytes)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        } finally { if (tmp.exists()) tmp.delete() }
        try { WsChange.notify(Workspace.rel(f)) } catch (e: Throwable) { Diag.e("documents", "change hook", e) }
        // the finished document as a deliverable card under the answer (open / share), like present_files
        try { if (!ctx.background || ctx.listener != null) Planning.presentFiles(ctx.convo.id, JSONArray().put(JSONObject().put("path", Workspace.rel(f)).put("name", f.name).put("description", info.what).put("size", f.length()).put("image", false))) } catch (e: Throwable) { Diag.e("documents", "card", e) }
        val sb = StringBuilder("Created $vp (${info.what}, ${Workspace.fmtBytes(f.length())}).")
        for (n in info.notes) sb.append("\nNote: ").append(n)
        sb.append("\nTell the user they can tap $vp to open it. Do not paste the content again.")
        return sb.toString()
    }
}

// =====================================================================================================================
// Pure model + builders (no Android types below until PdfW)
// =====================================================================================================================

class Built(val bytes: ByteArray, val what: String, val notes: List<String> = emptyList())

class DocTheme(val name: String, val accent: String, val dark: String, val headFill: String, val headText: String, val zebra: String, val titleBg: String) {
    companion object {
        val DEFAULT = DocTheme("default", "2F5496", "1F3864", "D9E2F3", "1F3864", "F3F6FB", "1F3864")
        val BLUE = DocTheme("blue", "1F6FB2", "0B3D6E", "1F4E79", "FFFFFF", "EAF1F8", "0B3D6E")
        val GREY = DocTheme("grey", "404040", "262626", "E7E6E6", "262626", "F5F5F5", "3A3A3A")
        fun of(s: String) = when (s.trim().lowercase()) { "blue", "mavi" -> BLUE; "grey", "gray", "gri" -> GREY; else -> DEFAULT }
    }
}

class DocOpts(
    val title: String,
    val titleGiven: Boolean = false,
    val author: String = "yaver",
    val landscape: Boolean = false,
    val theme: DocTheme = DocTheme.DEFAULT,
    val sheetName: String = "",
    val tr: Boolean = true,
    val date: java.util.Date = java.util.Date()
) {
    val lang: String get() = if (tr) "tr-TR" else "en-US"
    val isoDate: String get() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(date)
    val shortDate: String get() = java.text.SimpleDateFormat(if (tr) "d MMMM yyyy" else "MMMM d, yyyy", if (tr) Locale("tr", "TR") else Locale.US).format(date)
}

class DocRun(val text: String, val b: Boolean = false, val i: Boolean = false, val code: Boolean = false, val link: String? = null,
             val strike: Boolean = false, val u: Boolean = false, val br: Boolean = false) {
    fun same(o: DocRun) = !br && !o.br && b == o.b && i == o.i && code == o.code && link == o.link && strike == o.strike && u == o.u
}

sealed class Blk {
    class H(val level: Int, val text: String) : Blk()
    class Para(val text: String) : Blk()
    class Item(val level: Int, val ordered: Boolean, val num: Int, var text: String, val list: Int) : Blk()
    class Quote(val blocks: List<Blk>) : Blk()
    class Code(val lang: String, val text: String) : Blk()
    object Rule : Blk()
    object Break : Blk()
    class Table(val head: List<String>, val rows: List<List<String>>, val align: List<Char>) : Blk() {
        val cols: Int get() = maxOf(head.size, rows.maxOfOrNull { it.size } ?: 0)
        fun cell(r: Int, c: Int): String = (if (r < 0) head else rows[r]).getOrNull(c) ?: ""
        fun alignOf(c: Int): Char = align.getOrNull(c) ?: ' '
    }
}

/** Markdown / CSV / HTML → blocks, inline runs. */
object DocMd {
    private val FENCE = Regex("^\\s*(`{3,}|~{3,})\\s*([^`\\s]*)")
    private val HEAD = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)(\\s+#+)?\\s*$")
    private val RULE = Regex("^ {0,3}([-*_])( *\\1){2,} *$")
    private val SEP = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")
    private val ITEM = Regex("^(\\s*)([-*+•]|(\\d{1,3})[.)])\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>")
    private val PB = Regex("^(\\\\newpage|\\\\pagebreak|<!--\\s*page-?break\\s*-->|<div[^>]*page-break[^>]*>\\s*(</div>)?)$", RegexOption.IGNORE_CASE)
    private const val PUNCT = "\\`*_{}[]()#+-.!|~<>\"'\$"

    /** Undo common model mistakes: literal \n escapes, the whole thing wrapped in a ```markdown fence, a BOM. */
    fun clean(s0: String): String {
        var s = s0.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (!s.contains('\n') && s.contains("\\n")) s = s.replace("\\n", "\n").replace("\\t", "\t")
        val m = Regex("^\\s*```(markdown|md|html|csv)?[ \\t]*\\n([\\s\\S]*?)\\n```\\s*$", RegexOption.IGNORE_CASE).find(s)
        if (m != null) {
            val inner = m.groupValues[2]
            if (m.groupValues[1].isNotEmpty() || !inner.lines().any { it.trim().startsWith("```") }) s = inner
        }
        return s
    }

    fun looksHtml(s: String): Boolean {
        if (Regex("^\\s*(<!doctype html|<html|<body|<(div|p|h[1-6]|table|ul|ol|section|article)[\\s>])", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
        val tags = Regex("</(p|h[1-6]|li|td|tr|div)>", RegexOption.IGNORE_CASE).findAll(s).count()
        return tags >= 4 && !Regex("^#{1,6}\\s", RegexOption.MULTILINE).containsMatchIn(s)
    }

    fun parse(md: String): List<Blk> {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
        val out = ArrayList<Blk>()
        val para = ArrayList<String>()
        var listId = 0
        var inList = false
        var blankSinceItem = false
        val indents = ArrayList<Int>()
        var lastItem: Blk.Item? = null
        var topOrdered: Boolean? = null
        fun flushPara() {
            if (para.isEmpty()) return
            val sb = StringBuilder()
            for ((k, l) in para.withIndex()) {
                var t = l.trim()
                val hard = k < para.size - 1 && (l.endsWith("  ") || t.endsWith("\\"))
                if (hard && t.endsWith("\\")) t = t.dropLast(1).trimEnd()
                sb.append(t)
                if (k < para.size - 1) sb.append(if (hard) "\n" else " ")
            }
            out.add(Blk.Para(sb.toString().trim())); para.clear()
        }
        fun endList() { inList = false; lastItem = null; indents.clear(); blankSinceItem = false; topOrdered = null }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty()) { flushPara(); if (inList) blankSinceItem = true; i++; continue }
            val indent = line.length - line.trimStart().length

            val fm = FENCE.find(line)
            if (fm != null && fm.range.first == 0) {
                flushPara()
                val fence = fm.groupValues[1]
                val close = Regex("^\\s*" + Regex.escape(fence[0].toString()) + "{" + fence.length + ",}\\s*$")
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !close.matches(lines[i])) { code.add(lines[i]); i++ }
                i++
                val strip = code.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length }?.coerceAtMost(indent) ?: 0
                out.add(Blk.Code(fm.groupValues[2], code.joinToString("\n") { if (it.length >= strip) it.substring(strip) else it.trimStart() }))
                if (inList) blankSinceItem = true
                continue
            }
            val hm = HEAD.find(line)
            if (hm != null) { flushPara(); endList(); out.add(Blk.H(hm.groupValues[1].length, hm.groupValues[2].trim())); i++; continue }
            if (PB.matches(t)) { flushPara(); endList(); out.add(Blk.Break); i++; continue }
            if (para.isNotEmpty() && Regex("^=+$").matches(t)) {
                val h = para.joinToString(" ") { it.trim() }; para.clear(); out.add(Blk.H(1, h)); i++; continue
            }
            if (RULE.matches(line)) { flushPara(); endList(); out.add(Blk.Rule); i++; continue }

            if (t.contains('|')) {
                val next = lines.getOrNull(i + 1)?.trim() ?: ""
                val withSep = next.contains('|') && next.contains('-') && SEP.matches(next) || (next.contains('-') && SEP.matches(next) && cells(t).size == 1)
                val pipeRows = t.startsWith("|") && t.endsWith("|") && t.length > 2 && next.startsWith("|") && next.endsWith("|") && next.length > 2
                if (withSep || pipeRows) {
                    flushPara(); endList()
                    val head = cells(t)
                    val align = if (withSep) cells(next).map { c -> val l = c.startsWith(":"); val r = c.endsWith(":"); if (l && r) 'c' else if (r) 'r' else if (l) 'l' else ' ' } else emptyList()
                    var j = if (withSep) i + 2 else i + 1
                    val rows = ArrayList<List<String>>()
                    while (j < lines.size) {
                        val r = lines[j].trim()
                        if (r.isEmpty() || !r.contains('|') || HEAD.containsMatchIn(lines[j])) break
                        if (!(SEP.matches(r) && r.contains('-'))) rows.add(cells(r))
                        j++
                    }
                    out.add(Blk.Table(head, rows, align))
                    i = j; continue
                }
            }
            if (QUOTE.containsMatchIn(line)) {
                flushPara(); endList()
                val inner = ArrayList<String>()
                while (i < lines.size && QUOTE.containsMatchIn(lines[i])) { inner.add(lines[i].replaceFirst(Regex("^\\s{0,3}> ?"), "")); i++ }
                out.add(Blk.Quote(parse(inner.joinToString("\n"))))
                continue
            }
            val lm = ITEM.find(line)
            if (lm != null && !(para.isNotEmpty() && lm.groupValues[3].isNotEmpty() && lm.groupValues[3] != "1")) {
                flushPara()
                if (!inList) { listId++; indents.clear(); inList = true }
                val ind = lm.groupValues[1].length
                if (indents.isEmpty()) indents.add(ind)
                else if (ind >= indents.last() + 2) indents.add(ind)
                else while (indents.size > 1 && indents.last() > ind + 1) indents.removeAt(indents.size - 1)
                var body = lm.groupValues[4].trim()
                when {
                    body.startsWith("[ ] ") -> body = "☐ " + body.substring(4)
                    body.startsWith("[x] ", true) -> body = "☑ " + body.substring(4)
                }
                val ordered = lm.groupValues[3].isNotEmpty()
                if (indents.size == 1) {
                    // a top-level bullet list followed by a numbered one (or the reverse) is a new list
                    if (topOrdered != null && topOrdered != ordered) listId++
                    topOrdered = ordered
                }
                val item = Blk.Item(minOf(indents.size - 1, 8), ordered, lm.groupValues[3].toIntOrNull() ?: 1, body, listId)
                out.add(item); lastItem = item; blankSinceItem = false
                i++; continue
            }
            val li = lastItem
            if (inList && li != null) {
                if (!blankSinceItem) { li.text += (if (lines[i - 1].endsWith("  ")) "\n" else " ") + t; i++; continue }
                if (indent >= 2) { li.text += "\n" + t; blankSinceItem = false; i++; continue }
                endList()
            }
            para.add(line)
            i++
        }
        flushPara()
        return out
    }

    /** Cells of a Markdown table row (pipes inside `code` and \| are kept). */
    fun cells(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var code = false
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (c == '`') code = !code
            if (c == '|' && !code) { out.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
            k++
        }
        out.add(cur.toString().trim())
        return out.map { it.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n") }
    }

    // ---------------------------------------------------------------- inline

    private class St(val b: Boolean = false, val i: Boolean = false, val s: Boolean = false, val u: Boolean = false, val code: Boolean = false, val link: String? = null) {
        fun with(b: Boolean = this.b, i: Boolean = this.i, s: Boolean = this.s, u: Boolean = this.u, code: Boolean = this.code, link: String? = this.link) = St(b, i, s, u, code, link)
    }
    private val INLINE_TAGS = Regex("^<(b|strong|i|em|u|s|del|strike|code|mark|span|sub|sup|small|font)(\\s[^<>]*)?>", RegexOption.IGNORE_CASE)
    private val STRAY_TAG = Regex("^</?(b|strong|i|em|u|s|del|strike|code|mark|span|sub|sup|small|font|p|div)(\\s[^<>]*)?/?>", RegexOption.IGNORE_CASE)

    fun inline(src: String): List<DocRun> {
        val out = ArrayList<DocRun>()
        inl(src, St(), out)
        val merged = ArrayList<DocRun>()
        for (r in out) {
            val last = merged.lastOrNull()
            if (last != null && last.same(r)) merged[merged.size - 1] = DocRun(last.text + r.text, r.b, r.i, r.code, r.link, r.strike, r.u)
            else if (r.br || r.text.isNotEmpty()) merged.add(r)
        }
        return merged
    }

    fun plain(s: String): String = inline(s).joinToString("") { if (it.br) "\n" else it.text }

    private fun inl(s: String, st: St, out: MutableList<DocRun>) {
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out.add(DocRun(buf.toString(), st.b, st.i, st.code, st.link, st.s, st.u)); buf.setLength(0) } }
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < n && s[i + 1] in PUNCT -> { buf.append(s[i + 1]); i += 2 }
                c == '\n' -> { flush(); out.add(DocRun("", br = true)); i++ }
                c == '`' -> {
                    var k = i; while (k < n && s[k] == '`') k++
                    val ticks = k - i
                    var close = -1
                    var j = k
                    while (j < n) {
                        val e = s.indexOf("`".repeat(ticks), j)
                        if (e < 0) break
                        var m = e; while (m < n && s[m] == '`') m++
                        if (m - e == ticks) { close = e; break }
                        j = m
                    }
                    if (close < 0) { buf.append(s, i, k); i = k } else {
                        flush()
                        var code = s.substring(k, close)
                        if (code.length > 2 && code.startsWith(" ") && code.endsWith(" ")) code = code.substring(1, code.length - 1)
                        out.add(DocRun(code, st.b, st.i, true, st.link, st.s, st.u))
                        i = close + ticks
                    }
                }
                (c == '[' || (c == '!' && i + 1 < n && s[i + 1] == '[')) -> {
                    val img = c == '!'
                    val lk = link(s, if (img) i + 1 else i)
                    if (lk == null) { buf.append(c); i++ } else {
                        flush()
                        if (img) { if (lk.first.isNotBlank()) inl(lk.first, st.with(i = true), out) }
                        else inl(lk.first, if (lk.second.startsWith("#")) st else st.with(link = lk.second), out)
                        i = lk.third
                    }
                }
                c == '<' -> {
                    val rest = s.substring(i, minOf(n, i + 400))
                    val auto = Regex("^<((https?://|mailto:)[^>\\s]+)>").find(rest)
                    val br = Regex("^<br\\s*/?>", RegexOption.IGNORE_CASE).find(rest)
                    val open = INLINE_TAGS.find(rest)
                    when {
                        auto != null -> { flush(); out.add(DocRun(auto.groupValues[1].removePrefix("mailto:"), st.b, st.i, st.code, auto.groupValues[1], st.s, st.u)); i += auto.value.length }
                        br != null -> { flush(); out.add(DocRun("", br = true)); i += br.value.length }
                        open != null -> {
                            val tag = open.groupValues[1].lowercase()
                            val end = s.indexOf("</$tag>", i + open.value.length, ignoreCase = true)
                            if (end < 0) { i += open.value.length } else {
                                flush()
                                val inner = s.substring(i + open.value.length, end)
                                val ns = when (tag) {
                                    "b", "strong" -> st.with(b = true); "i", "em" -> st.with(i = true); "u" -> st.with(u = true)
                                    "s", "del", "strike" -> st.with(s = true); "code" -> st.with(code = true); else -> st
                                }
                                inl(inner, ns, out)
                                i = end + tag.length + 3
                            }
                        }
                        else -> { val stray = STRAY_TAG.find(rest); if (stray != null) i += stray.value.length else { buf.append(c); i++ } }
                    }
                }
                c == '*' || c == '_' -> {
                    var k = i; while (k < n && s[k] == c) k++
                    val run = k - i
                    val prev = if (i > 0) s[i - 1] else ' '
                    val nextCh = if (k < n) s[k] else ' '
                    if (run > 3 || nextCh.isWhitespace() || (c == '_' && prev.isLetterOrDigit())) { buf.append(s, i, k); i = k }
                    else {
                        val close = closer(s, k, c, run)
                        if (close < 0) { buf.append(s, i, k); i = k } else {
                            flush()
                            val ns = when (run) { 1 -> st.with(i = true); 2 -> st.with(b = true); else -> st.with(b = true, i = true) }
                            inl(s.substring(k, close), ns, out)
                            i = close + run
                        }
                    }
                }
                c == '~' && i + 1 < n && s[i + 1] == '~' -> {
                    val e = s.indexOf("~~", i + 2)
                    if (e < 0 || e == i + 2) { buf.append("~~"); i += 2 } else { flush(); inl(s.substring(i + 2, e), st.with(s = true), out); i = e + 2 }
                }
                c == 'h' && st.link == null && (s.startsWith("http://", i) || s.startsWith("https://", i)) && (i == 0 || s[i - 1].isWhitespace() || s[i - 1] in "(\"'") -> {
                    var e = i; while (e < n && !s[e].isWhitespace() && s[e] != '<') e++
                    var url = s.substring(i, e)
                    while (url.isNotEmpty() && url.last() in ".,;:!?\"'" || (url.endsWith(")") && !url.contains('('))) url = url.dropLast(1)
                    flush(); out.add(DocRun(url, st.b, st.i, st.code, url, st.s, st.u)); i += url.length
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
    }

    /** End index of the emphasis that opens with [run] × [c] before [from]; -1 if none. */
    private fun closer(s: String, from: Int, c: Char, run: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '`') { val e = s.indexOf('`', j + 1); if (e > 0) { j = e + 1; continue } }
            if (s[j] == c && (j == 0 || s[j - 1] != c)) {
                var k = j; while (k < s.length && s[k] == c) k++
                val m = k - j
                val prev = s[j - 1]
                val after = if (k < s.length) s[k] else ' '
                val okEdge = !prev.isWhitespace() && !(c == '_' && after.isLetterOrDigit())
                if (okEdge && j > from) {
                    if (m == run) return j
                    if (m == 3 && run < 3) return j + (m - run)
                }
                j = k; continue
            }
            j++
        }
        return -1
    }

    /** [text](url) at [i]: (text, url, end) */
    private fun link(s: String, i: Int): Triple<String, String, Int>? {
        if (i >= s.length || s[i] != '[') return null
        var depth = 0
        var j = i
        while (j < s.length) {
            when (s[j]) { '\\' -> j++; '[' -> depth++; ']' -> { depth--; if (depth == 0) break } }
            j++
        }
        if (j >= s.length || j + 1 >= s.length || s[j + 1] != '(') return null
        var k = j + 2
        var pd = 1
        while (k < s.length) { if (s[k] == '(') pd++ else if (s[k] == ')') { pd--; if (pd == 0) break }; k++ }
        if (k >= s.length) return null
        var url = s.substring(j + 2, k).trim()
        url = url.replace(Regex("\\s+(\"[^\"]*\"|'[^']*')$"), "").removePrefix("<").removeSuffix(">").trim()
        return Triple(s.substring(i + 1, j), url, k + 1)
    }

    // ---------------------------------------------------------------- CSV

    fun delimiter(text: String): Char? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (lines.isEmpty()) return null
        var best: Char? = null
        var bestScore = 0
        for (d in charArrayOf('\t', ';', ',', '|')) {
            val counts = lines.map { l -> var q = false; var c = 0; for (ch in l) { if (ch == '"') q = !q else if (ch == d && !q) c++ }; c }
            val mode = counts.groupingBy { it }.eachCount().filter { it.key > 0 }.maxByOrNull { it.value } ?: continue
            val score = mode.value * 100 + mode.key
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }

    fun csv(text0: String, delim: Char? = null): List<List<String>> {
        val text = text0.removePrefix("\uFEFF")
        val d = delim ?: delimiter(text) ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else q = false } else cell.append(c)
            } else when (c) {
                '"' -> if (cell.isBlank()) { cell.setLength(0); q = true } else cell.append(c)
                d -> { row.add(cell.toString().trim()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cell.toString().trim()); cell.setLength(0); if (row.any { it.isNotEmpty() }) rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString().trim())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }

    fun csvBlocks(text: String): List<Blk> {
        val rows = csv(text)
        if (rows.isEmpty()) return emptyList()
        return listOf(Blk.Table(rows[0], rows.drop(1), emptyList()))
    }

    fun mdTable(t: Blk.Table): String {
        val n = t.cols
        fun row(r: List<String>) = "| " + (0 until n).joinToString(" | ") { (r.getOrNull(it) ?: "").replace("|", "\\|").replace("\n", "<br>") } + " |"
        val sb = StringBuilder(row(t.head)).append('\n')
        sb.append("| ").append((0 until n).joinToString(" | ") { when (t.alignOf(it)) { 'c' -> ":---:"; 'r' -> "---:"; 'l' -> ":---"; else -> "---" } }).append(" |\n")
        for (r in t.rows) sb.append(row(r)).append('\n')
        return sb.toString()
    }

    fun toMd(blocks: List<Blk>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is Blk.Table -> mdTable(b)
            is Blk.H -> "#".repeat(b.level) + " " + b.text + "\n"
            is Blk.Para -> b.text + "\n"
            else -> ""
        }
    }

    /** Relative column widths (sum 1) from the longest line in each column. */
    fun colWeights(t: Blk.Table, minW: Int = 4, maxW: Int = 40): DoubleArray {
        val n = maxOf(1, t.cols)
        val w = DoubleArray(n) { minW.toDouble() }
        for (r in -1 until t.rows.size) for (c in 0 until n) {
            val len = plain(t.cell(r, c)).split('\n').maxOf { it.length } + (if (r < 0) 1 else 0)
            w[c] = maxOf(w[c], len.coerceIn(minW, maxW).toDouble())
        }
        val sum = w.sum()
        return DoubleArray(n) { w[it] / sum }
    }

    private val NUMERIC = Regex("^[-+−±]?[₺$€£]?\\s?\\d[\\d.,  ]*\\s?(%|TL|₺|USD|EUR|€|\\$|£)?$")
    fun numeric(s: String) = NUMERIC.matches(s.trim())

    // ---------------------------------------------------------------- HTML → Markdown

    fun htmlToMd(html: String): Pair<String, String> {
        val root = Html.parse(html)
        val title = Html.title(root).trim()
        val body = root.first { it.tag == "body" } ?: root
        val out = StringBuilder()
        mdBlocks(body, out, 0)
        return Pair(title, out.toString().replace(Regex("\n{3,}"), "\n\n").trim())
    }

    private val SKIP = setOf("head", "script", "style", "title", "noscript", "template", "svg", "button", "select", "iframe", "canvas")
    private val BLOCK = setOf("p", "div", "section", "article", "main", "header", "footer", "nav", "aside", "figure", "figcaption", "address", "center", "form", "body", "html", "dl", "dd", "dt", "details", "summary", "fieldset", "tbody")

    private fun inlineMd(k: Any): String = when (k) {
        is String -> k.replace(Regex("\\s+"), " ")
        is HNode -> {
            fun kids() = k.kids.joinToString("") { inlineMd(it) }
            fun wrap(m: String): String { val inner = kids(); val t = inner.trim(); return if (t.isEmpty()) inner else (if (inner.startsWith(" ")) " " else "") + m + t + m + (if (inner.endsWith(" ")) " " else "") }
            when (k.tag) {
                in SKIP -> ""
                "br" -> "<br>"
                "strong", "b" -> wrap("**")
                "em", "i" -> wrap("*")
                "s", "del", "strike" -> wrap("~~")
                "u" -> "<u>" + kids() + "</u>"
                "code", "kbd", "samp" -> "`" + k.text() + "`"
                "a" -> { val h = k.attr("href"); val t = kids().trim(); if (h.isNotEmpty() && !h.startsWith("javascript:") && t.isNotEmpty()) "[$t]($h)" else kids() }
                "img" -> k.attr("alt")
                else -> kids()
            }
        }
        else -> ""
    }

    private fun rawText(n: HNode): String = n.kids.joinToString("") { if (it is String) it else if (it is HNode) (if (it.tag == "br") "\n" else rawText(it)) else "" }

    private fun mdBlocks(n: HNode, out: StringBuilder, depth: Int) {
        val buf = StringBuilder()
        fun flush() { val t = buf.toString().trim(); if (t.isNotEmpty()) out.append(t).append("\n\n"); buf.setLength(0) }
        for (k in n.kids) {
            if (k is String) { buf.append(inlineMd(k)); continue }
            val e = k as HNode
            when (e.tag) {
                in SKIP -> {}
                in BLOCK -> { flush(); mdBlocks(e, out, depth) }
                "h1", "h2", "h3", "h4", "h5", "h6" -> { flush(); out.append("#".repeat(e.tag[1] - '0')).append(' ').append(inlineMd(e).replace("<br>", " ").trim()).append("\n\n") }
                "ul", "ol", "menu" -> { flush(); mdList(e, out, 0); out.append('\n') }
                "table" -> { flush(); mdHtmlTable(e, out) }
                "pre" -> { flush(); out.append("```\n").append(rawText(e).trimEnd('\n')).append("\n```\n\n") }
                "blockquote" -> { flush(); val sub = StringBuilder(); mdBlocks(e, sub, depth); out.append(sub.toString().trim().lines().joinToString("\n") { "> $it" }).append("\n\n") }
                "hr" -> { flush(); out.append("---\n\n") }
                "li" -> { flush(); out.append("- ").append(inlineMd(e).trim()).append('\n') }
                else -> buf.append(inlineMd(e))
            }
        }
        flush()
    }

    private fun mdList(n: HNode, out: StringBuilder, depth: Int) {
        val ordered = n.tag == "ol"
        var num = n.attr("start").toIntOrNull() ?: 1
        for (li in n.kids) {
            if (li !is HNode || li.tag != "li") continue
            val text = StringBuilder()
            val nested = ArrayList<HNode>()
            for (k in li.kids) if (k is HNode && (k.tag == "ul" || k.tag == "ol")) nested.add(k) else text.append(inlineMd(k))
            out.append("  ".repeat(depth)).append(if (ordered) "${num++}. " else "- ").append(text.toString().replace(Regex("\\s+"), " ").trim()).append('\n')
            for (s in nested) mdList(s, out, depth + 1)
        }
    }

    private fun mdHtmlTable(t: HNode, out: StringBuilder) {
        val rows = t.all { it.tag == "tr" }.map { tr -> tr.kids.filterIsInstance<HNode>().filter { it.tag == "td" || it.tag == "th" }.map { inlineMd(it).trim().replace("<br>", " ") } }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return
        t.first { it.tag == "caption" }?.let { out.append("**").append(it.text()).append("**\n\n") }
        out.append(mdTable(Blk.Table(rows[0], rows.drop(1), emptyList()))).append('\n')
    }
}

/** Shared XML / ZIP helpers. */
object DocXml {
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;"); c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;"); c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> {}
                Character.isHighSurrogate(c) -> { if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { sb.append(c).append(s[i + 1]); i++ } }
                Character.isLowSurrogate(c) -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    class Zip {
        private val bos = ByteArrayOutputStream()
        private val z = ZipOutputStream(bos)
        fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        fun bytes(): ByteArray { z.close(); return bos.toByteArray() }
    }

    const val HDR = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun rels(vararg r: Triple<String, String, String>, external: Set<String> = emptySet()): String =
        HDR + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            r.joinToString("") { "<Relationship Id=\"${it.first}\" Type=\"${it.second}\" Target=\"${esc(it.third)}\"" + (if (it.first in external) " TargetMode=\"External\"" else "") + "/>" } +
            "</Relationships>"

    fun rootRels(main: String) = rels(
        Triple("rId1", "$R/officeDocument", main),
        Triple("rId2", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties", "docProps/core.xml"),
        Triple("rId3", "$R/extended-properties", "docProps/app.xml"))

    fun core(o: DocOpts) = HDR + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
        "<dc:title>${esc(o.title)}</dc:title><dc:creator>${esc(o.author)}</dc:creator><cp:lastModifiedBy>${esc(o.author)}</cp:lastModifiedBy><dc:language>${o.lang}</dc:language>" +
        "<dcterms:created xsi:type=\"dcterms:W3CDTF\">${o.isoDate}</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">${o.isoDate}</dcterms:modified></cp:coreProperties>"

    fun app(appName: String) = HDR + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>$appName</Application></Properties>"
}

// ===================================================================================================================== DOCX

object DocxW {
    private const val W_NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private const val FONT = "Calibri"
    private const val MONO = "Consolas"

    private class Ctx(val o: DocOpts) {
        val links = LinkedHashMap<String, String>()
        val nums = ArrayList<Pair<Int, Int>>() // numId → (abstract, start)
        val body = StringBuilder()
        var tables = 0
        var headings = 0
        var lines = 0.0
        fun linkId(url: String): String = links.getOrPut(url) { "rIdL${links.size + 1}" }
        fun newNum(abs: Int, start: Int): Int { nums.add(abs to start); return nums.size + 1 } // numId 1 is the shared bullet list
    }

    fun build(blocks: List<Blk>, o: DocOpts): Built {
        val c = Ctx(o)
        val first = blocks.firstOrNull()
        if (o.title.isNotBlank() && !(first is Blk.H && first.level == 1) && (o.titleGiven || first !is Blk.H)) {
            c.body.append(para("Title", DocMd.inline(o.title), c))
            c.lines += 3
        }
        render(blocks, c, null)
        val pgW = if (o.landscape) 16838 else 11906
        val pgH = if (o.landscape) 11906 else 16838
        c.body.append("<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdF1\"/><w:pgSz w:w=\"$pgW\" w:h=\"$pgH\"" + (if (o.landscape) " w:orient=\"landscape\"" else "") + "/>" +
            "<w:pgMar w:top=\"1418\" w:right=\"1247\" w:bottom=\"1418\" w:left=\"1247\" w:header=\"709\" w:footer=\"567\" w:gutter=\"0\"/><w:cols w:space=\"708\"/></w:sectPr>")

        val z = DocXml.Zip()
        z.put("[Content_Types].xml", DocXml.HDR + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
            "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" +
            "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>")
        z.put("_rels/.rels", DocXml.rootRels("word/document.xml"))
        z.put("docProps/core.xml", DocXml.core(o))
        z.put("docProps/app.xml", DocXml.app("Microsoft Office Word"))
        val R = DocXml.R
        val rels = arrayListOf(
            Triple("rIdS1", "$R/styles", "styles.xml"), Triple("rIdS2", "$R/numbering", "numbering.xml"),
            Triple("rIdS3", "$R/settings", "settings.xml"), Triple("rIdF1", "$R/footer", "footer1.xml"))
        for ((url, id) in c.links) rels.add(Triple(id, "$R/hyperlink", url))
        z.put("word/_rels/document.xml.rels", DocXml.rels(*rels.toTypedArray(), external = c.links.values.toSet()))
        z.put("word/document.xml", DocXml.HDR + "<w:document $W_NS><w:body>" + c.body + "</w:body></w:document>")
        z.put("word/styles.xml", styles(o))
        z.put("word/numbering.xml", numbering(c))
        z.put("word/settings.xml", DocXml.HDR + "<w:settings $W_NS><w:zoom w:percent=\"100\"/><w:defaultTabStop w:val=\"708\"/><w:characterSpacingControl w:val=\"doNotCompress\"/>" +
            "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>")
        z.put("word/footer1.xml", DocXml.HDR + "<w:ftr $W_NS><w:p><w:pPr><w:pStyle w:val=\"Footer\"/><w:jc w:val=\"center\"/></w:pPr>" +
            "<w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple><w:r><w:t xml:space=\"preserve\"> / </w:t></w:r>" +
            "<w:fldSimple w:instr=\" NUMPAGES \"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>")
        val perPage = if (o.landscape) 38.0 else 54.0
        val pages = maxOf(1, Math.ceil(c.lines / perPage).toInt())
        val what = "Word document, about $pages page" + (if (pages == 1) "" else "s") +
            (if (c.headings > 0) ", ${c.headings} heading" + (if (c.headings == 1) "" else "s") else "") +
            (if (c.tables > 0) ", ${c.tables} table" + (if (c.tables == 1) "" else "s") else "")
        return Built(z.bytes(), what)
    }

    private fun charsPerLine(c: Ctx) = if (c.o.landscape) 140.0 else 95.0

    private fun render(blocks: List<Blk>, c: Ctx, quote: String?) {
        val ordNum = IntArray(9)
        var prevList = -1
        for ((idx, b) in blocks.withIndex()) {
            if (b !is Blk.Item) { if (b !is Blk.Code) { prevList = -1; ordNum.fill(0) } }
            when (b) {
                is Blk.H -> {
                    c.headings++; c.lines += 2.5
                    c.body.append(para("Heading${b.level}", DocMd.inline(b.text), c))
                }
                is Blk.Para -> {
                    val txt = DocMd.plain(b.text)
                    c.lines += 1 + txt.length / charsPerLine(c) + txt.count { it == '\n' } + 0.4
                    c.body.append(para(quote ?: "Normal", DocMd.inline(b.text), c))
                }
                is Blk.Item -> {
                    if (b.list != prevList) { ordNum.fill(0); prevList = b.list }
                    val numId: Int
                    if (b.ordered) {
                        if (ordNum[b.level] == 0) ordNum[b.level] = c.newNum(1, b.num)
                        numId = ordNum[b.level]
                    } else { ordNum[b.level] = 0; numId = 1 }
                    for (l in b.level + 1 until 9) ordNum[l] = 0
                    c.lines += 1 + DocMd.plain(b.text).length / (charsPerLine(c) - 6)
                    c.body.append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"${b.level}\"/><w:numId w:val=\"$numId\"/></w:numPr></w:pPr>")
                        .append(runs(DocMd.inline(b.text), c)).append("</w:p>")
                }
                is Blk.Quote -> render(b.blocks, c, "Quote")
                is Blk.Code -> {
                    val ls = b.text.split('\n')
                    c.lines += ls.size * 0.9 + 1
                    for ((k, l) in ls.withIndex()) {
                        val sp = when { ls.size == 1 -> "<w:spacing w:before=\"60\" w:after=\"200\"/>"; k == 0 -> "<w:spacing w:before=\"60\"/>"; k == ls.size - 1 -> "<w:spacing w:after=\"200\"/>"; else -> "" }
                        c.body.append("<w:p><w:pPr><w:pStyle w:val=\"CodeBlock\"/>$sp</w:pPr>")
                        if (l.isNotEmpty()) c.body.append("<w:r><w:t xml:space=\"preserve\">").append(DocXml.esc(l)).append("</w:t></w:r>")
                        c.body.append("</w:p>")
                    }
                }
                Blk.Rule -> { c.lines += 1; c.body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"BFBFBF\"/></w:pBdr><w:spacing w:after=\"240\"/></w:pPr></w:p>") }
                Blk.Break -> { c.lines = Math.ceil(c.lines / 46.0) * 46.0; c.body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>") }
                is Blk.Table -> table(b, c, idx == blocks.size - 1)
            }
        }
    }

    private fun para(style: String, rs: List<DocRun>, c: Ctx, extraPPr: String = ""): String =
        "<w:p><w:pPr><w:pStyle w:val=\"$style\"/>$extraPPr</w:pPr>" + runs(rs, c) + "</w:p>"

    private fun runs(rs: List<DocRun>, c: Ctx, bold: Boolean = false, color: String? = null): String {
        val sb = StringBuilder()
        for (r in rs) {
            if (r.br) { sb.append("<w:r><w:br/></w:r>"); continue }
            val pr = StringBuilder()
            if (r.link != null) pr.append("<w:rStyle w:val=\"Hyperlink\"/>") else if (r.code) pr.append("<w:rStyle w:val=\"CodeChar\"/>")
            if (r.b || bold) pr.append("<w:b/><w:bCs/>")
            if (r.i) pr.append("<w:i/><w:iCs/>")
            if (r.strike) pr.append("<w:strike/>")
            if (color != null && r.link == null) pr.append("<w:color w:val=\"$color\"/>")
            if (r.u && r.link == null) pr.append("<w:u w:val=\"single\"/>")
            val run = "<w:r>" + (if (pr.isNotEmpty()) "<w:rPr>$pr</w:rPr>" else "") + "<w:t xml:space=\"preserve\">" + DocXml.esc(r.text) + "</w:t></w:r>"
            if (r.link != null && Regex("^(https?|mailto|ftp|file):", RegexOption.IGNORE_CASE).containsMatchIn(r.link)) sb.append("<w:hyperlink r:id=\"${c.linkId(r.link)}\" w:history=\"1\">").append(run).append("</w:hyperlink>")
            else sb.append(run)
        }
        return sb.toString()
    }

    private fun table(t: Blk.Table, c: Ctx, last: Boolean) {
        val n = maxOf(1, t.cols)
        c.tables++
        val total = (if (c.o.landscape) 16838 else 11906) - 2 * 1247
        val w = DocMd.colWeights(t).map { (it * total).toInt() }
        val th = c.o.theme
        val numCol = BooleanArray(n) { col -> t.rows.isNotEmpty() && t.rows.count { DocMd.numeric(it.getOrNull(col) ?: "") } * 2 > t.rows.count { (it.getOrNull(col) ?: "").isNotBlank() } }
        val sb = c.body
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"$total\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/><w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr><w:tblGrid>")
        for (x in w) sb.append("<w:gridCol w:w=\"$x\"/>")
        sb.append("</w:tblGrid>")
        for (r in -1 until t.rows.size) {
            sb.append("<w:tr>")
            if (r < 0) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            var rowLines = 1.0
            for (col in 0 until n) {
                val txt = t.cell(r, col)
                rowLines = maxOf(rowLines, DocMd.plain(txt).length / maxOf(4.0, w[col] / 110.0) + 1)
                val fill = if (r < 0) th.headFill else if (r % 2 == 1) th.zebra else null
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${w[col]}\" w:type=\"dxa\"/>")
                if (fill != null) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fill\"/>")
                sb.append("</w:tcPr><w:p><w:pPr><w:spacing w:before=\"20\" w:after=\"20\" w:line=\"252\" w:lineRule=\"auto\"/>")
                val al = when (t.alignOf(col)) { 'c' -> "center"; 'r' -> "right"; 'l' -> null; else -> if (numCol[col] && r >= 0) "right" else null }
                if (al != null) sb.append("<w:jc w:val=\"$al\"/>")
                sb.append("</w:pPr>").append(runs(DocMd.inline(txt), c, bold = r < 0, color = if (r < 0) th.headText else null)).append("</w:p></w:tc>")
            }
            c.lines += rowLines * 0.9 + 0.3
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        // keeps adjacent tables apart and gives the section a trailing paragraph
        sb.append("<w:p><w:pPr><w:spacing w:after=\"" + (if (last) "0" else "120") + "\"/></w:pPr></w:p>")
        c.lines += 1
    }

    private fun styles(o: DocOpts): String {
        val th = o.theme
        val f = "<w:rFonts w:ascii=\"$FONT\" w:hAnsi=\"$FONT\" w:eastAsia=\"$FONT\" w:cs=\"$FONT\"/>"
        val mono = "<w:rFonts w:ascii=\"$MONO\" w:hAnsi=\"$MONO\" w:eastAsia=\"$MONO\" w:cs=\"$MONO\"/>"
        fun heading(l: Int, sz: Int, before: Int, color: String, italic: Boolean = false) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$l\"><w:name w:val=\"heading $l\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"$before\" w:after=\"120\"/><w:outlineLvl w:val=\"${l - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:bCs/>" + (if (italic) "<w:i/><w:iCs/>" else "") + "<w:color w:val=\"$color\"/><w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr></w:style>"
        return DocXml.HDR + "<w:styles $W_NS>" +
            "<w:docDefaults><w:rPrDefault><w:rPr>$f<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"${o.lang}\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/><w:rPr><w:color w:val=\"1F1F1F\"/></w:rPr></w:style>" +
            "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/></w:style>" +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:semiHidden/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "<w:style w:type=\"numbering\" w:default=\"1\" w:styleId=\"NoList\"><w:name w:val=\"No List\"/><w:semiHidden/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"4\" w:color=\"${th.accent}\"/></w:pBdr><w:spacing w:after=\"300\" w:line=\"240\" w:lineRule=\"auto\"/><w:contextualSpacing/></w:pPr>" +
            "<w:rPr><w:color w:val=\"${th.dark}\"/><w:spacing w:val=\"-10\"/><w:kern w:val=\"28\"/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr></w:style>" +
            heading(1, 32, 360, th.dark) + heading(2, 28, 280, th.accent) + heading(3, 24, 240, th.accent) +
            heading(4, 22, 200, th.dark, true) + heading(5, 22, 160, th.accent) + heading(6, 22, 160, "595959", true) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:uiPriority w:val=\"34\"/><w:qFormat/><w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"${th.accent}\"/></w:pBdr><w:spacing w:before=\"120\" w:after=\"120\"/><w:ind w:left=\"567\" w:right=\"567\"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val=\"404040\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"CodeBlock\"><w:name w:val=\"Code Block\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/><w:ind w:left=\"113\" w:right=\"113\"/></w:pPr><w:rPr>$mono<w:color w:val=\"262626\"/><w:sz w:val=\"19\"/><w:szCs w:val=\"19\"/></w:rPr></w:style>" +
            "<w:style w:type=\"character\" w:styleId=\"CodeChar\"><w:name w:val=\"Code Char\"/><w:basedOn w:val=\"DefaultParagraphFont\"/><w:rPr>$mono<w:color w:val=\"9C2B2B\"/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/></w:rPr></w:style>" +
            "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:basedOn w:val=\"DefaultParagraphFont\"/><w:uiPriority w:val=\"99\"/><w:unhideWhenUsed/><w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Footer\"><w:name w:val=\"footer\"/><w:basedOn w:val=\"Normal\"/><w:uiPriority w:val=\"99\"/><w:unhideWhenUsed/><w:pPr><w:tabs><w:tab w:val=\"center\" w:pos=\"4536\"/><w:tab w:val=\"right\" w:pos=\"9072\"/></w:tabs><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:color w:val=\"7F7F7F\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/></w:rPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:uiPriority w:val=\"39\"/><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
            "<w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/><w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/><w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/></w:tblBorders>" +
            "<w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "</w:styles>"
    }

    private fun numbering(c: Ctx): String {
        val sb = StringBuilder(DocXml.HDR + "<w:numbering $W_NS>")
        val bullets = listOf("•" to FONT, "o" to "Courier New", "▪" to "Arial")
        sb.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0 until 9) {
            val (ch, font) = bullets[l % 3]
            sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"$ch\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + 360 * l}\" w:hanging=\"360\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:hint=\"default\"/></w:rPr></w:lvl>")
        }
        sb.append("</w:abstractNum><w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        val fmts = listOf("decimal", "lowerLetter", "lowerRoman")
        for (l in 0 until 9) sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${fmts[l % 3]}\"/><w:lvlText w:val=\"%${l + 1}.\"/><w:lvlJc w:val=\"" + (if (l % 3 == 2) "right" else "left") + "\"/><w:pPr><w:ind w:left=\"${720 + 360 * l}\" w:hanging=\"" + (if (l % 3 == 2) "180" else "360") + "\"/></w:pPr></w:lvl>")
        sb.append("</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        for ((k, n) in c.nums.withIndex()) {
            sb.append("<w:num w:numId=\"${k + 2}\"><w:abstractNumId w:val=\"${n.first}\"/>")
            for (l in 0 until 9) sb.append("<w:lvlOverride w:ilvl=\"$l\"><w:startOverride w:val=\"1\"/></w:lvlOverride>".let { if (n.second != 1) it.replace("w:val=\"1\"/></w:lvlOverride>", "w:val=\"" + (if (l == 0) n.second else 1) + "\"/></w:lvlOverride>") else it })
            sb.append("</w:num>")
        }
        return sb.append("</w:numbering>").toString()
    }
}

// ===================================================================================================================== XLSX

class XSheet(val name: String, val rows: List<List<String>>)

object XlsxW {
    class Cell(val kind: Char, val str: String = "", val num: Double = 0.0, val fmt: String = "General")

    private val TR_UP = Locale("tr", "TR")
    private val TRF = mapOf("TOPLA" to "SUM", "ORTALAMA" to "AVERAGE", "EĞER" to "IF", "MAK" to "MAX", "MİN" to "MIN", "SAY" to "COUNT",
        "BAĞ_DEĞ_SAY" to "COUNTA", "YUVARLA" to "ROUND", "ETOPLA" to "SUMIF", "EĞERSAY" to "COUNTIF", "DÜŞEYARA" to "VLOOKUP",
        "BİRLEŞTİR" to "CONCATENATE", "BUGÜN" to "TODAY", "ŞİMDİ" to "NOW", "MUTLAK" to "ABS", "ÇARPIM" to "PRODUCT", "VE" to "AND",
        "YADA" to "OR", "DEĞİL" to "NOT", "ORTANCA" to "MEDIAN", "ÇOKETOPLA" to "SUMIFS", "ÇOKEĞERSAY" to "COUNTIFS", "EĞERHATA" to "IFERROR")

    /** Sheets from the Markdown tables (named after the heading above each), else CSV / lines from [raw]. */
    fun sheetsFrom(blocks: List<Blk>, o: DocOpts, raw: String?): List<XSheet> {
        val out = ArrayList<XSheet>()
        var heading = ""
        var headingUsed = false
        fun collect(bs: List<Blk>) {
            for (b in bs) when (b) {
                is Blk.H -> { heading = DocMd.plain(b.text); headingUsed = false }
                is Blk.Quote -> collect(b.blocks)
                is Blk.Table -> {
                    val n = out.size
                    val name = when {
                        o.sheetName.isNotBlank() -> if (n == 0) o.sheetName else "${o.sheetName} ${n + 1}"
                        heading.isNotBlank() && !headingUsed -> heading
                        else -> (if (o.tr) "Tablo" else "Sheet") + (n + 1)
                    }
                    headingUsed = true
                    out.add(XSheet(name, listOf(b.head.map { DocMd.plain(it) }) + b.rows.map { r -> r.map { DocMd.plain(it) } }))
                }
                else -> {}
            }
        }
        collect(blocks)
        if (out.isEmpty() && raw != null) {
            val d = DocMd.delimiter(raw)
            val rows = if (d != null) DocMd.csv(raw, d) else emptyList()
            val multi = rows.size >= 2 && rows.count { it.size >= 2 } * 2 >= rows.size
            if (multi) out.add(XSheet(o.sheetName.ifBlank { if (o.tr) "Sayfa1" else "Sheet1" }, rows))
            else {
                val lines = ArrayList<List<String>>()
                for (b in blocks) when (b) {
                    is Blk.H -> lines.add(listOf(DocMd.plain(b.text)))
                    is Blk.Para -> lines.add(listOf(DocMd.plain(b.text)))
                    is Blk.Item -> lines.add(listOf("  ".repeat(b.level) + "• " + DocMd.plain(b.text)))
                    is Blk.Code -> b.text.lines().forEach { lines.add(listOf(it)) }
                    else -> {}
                }
                if (lines.isNotEmpty()) out.add(XSheet(o.sheetName.ifBlank { if (o.tr) "Sayfa1" else "Sheet1" }, lines))
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("no table found for Excel: give a Markdown table (| a | b | with a |---|---| line) or CSV")
        // valid, unique sheet names
        val seen = HashSet<String>()
        return out.map { s ->
            var nm = s.name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").replace(Regex("\\s+"), " ").trim().trim('\'').take(31).trim()
            if (nm.isEmpty()) nm = "Sheet"
            var cand = nm
            var k = 2
            while (!seen.add(cand.lowercase(TR_UP))) { val suf = " ($k)"; cand = nm.take(31 - suf.length).trim() + suf; k++ }
            XSheet(cand, s.rows)
        }
    }

    enum class Dec { TR, EN, UNK }

    private val CUR = Regex("^([-+−]?)\\s*(%|₺|\\$|€|£|TL|USD|EUR)?\\s*([-+−]?[\\d., ]*\\d)\\s*(%|₺|TL|\\$|€|£|USD|EUR)?$")

    private fun core(raw: String): Triple<String, String, String>? {  // (number part, prefix, suffix)
        val m = CUR.matchEntire(raw.trim().replace('\u00A0', ' ')) ?: return null
        var num = m.groupValues[3].replace('−', '-')
        if (num.contains(' ')) {
            if (!Regex("^[-+]?\\d{1,3}( \\d{3})+([.,]\\d+)?$").matches(num)) return null
            num = num.replace(" ", "")
        }
        val sign = m.groupValues[1].replace('−', '-')
        if (sign.isNotEmpty()) { if (num.startsWith("-") || num.startsWith("+")) return null; num = sign + num }
        val pre = m.groupValues[2]
        val suf = m.groupValues[4]
        if (pre == "%") return if (suf.isEmpty()) Triple(num, "", "%") else null
        return Triple(num, pre, suf)
    }

    /** Evidence for the decimal style of a column. */
    fun decOf(values: List<String>): Dec {
        var tr = 0; var en = 0
        for (v in values) {
            val n = core(v)?.first?.trimStart('-', '+') ?: continue
            when {
                Regex("^\\d{1,3}(\\.\\d{3})+,\\d+$").matches(n) || Regex("^\\d+,(\\d{1,2}|\\d{4,})$").matches(n) || Regex("^\\d{1,3}(\\.\\d{3}){2,}$").matches(n) || Regex("^\\d{4,},\\d+$").matches(n) -> tr++
                Regex("^\\d{1,3}(,\\d{3})+\\.\\d+$").matches(n) || Regex("^\\d+\\.(\\d{1,2}|\\d{4,})$").matches(n) || Regex("^\\d{1,3}(,\\d{3}){2,}$").matches(n) || Regex("^\\d{4,}\\.\\d+$").matches(n) -> en++
            }
        }
        return if (tr > en) Dec.TR else if (en > tr) Dec.EN else Dec.UNK
    }

    private fun serial(y: Int, mo: Int, d: Int): Double? = try { (java.time.LocalDate.of(y, mo, d).toEpochDay() + 25569).toDouble() } catch (_: Throwable) { null }

    fun classify(raw0: String, dec: Dec, trDefault: Boolean): Cell {
        val raw = raw0.trim()
        if (raw.isEmpty()) return Cell('e')
        if (raw.startsWith("'")) return Cell('s', raw.substring(1))
        if (raw.startsWith("=") && raw.length > 1 && !raw.startsWith("==")) return Cell('f', formula(raw, trDefault))
        // dates
        Regex("^(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$").matchEntire(raw)?.let { m ->
            var d = m.groupValues[1].toInt(); var mo = m.groupValues[2].toInt()
            if (mo > 12 && d <= 12) { val x = d; d = mo; mo = x }
            val s = serial(m.groupValues[3].toInt(), mo, d)
            if (s != null) {
                if (m.groupValues[4].isEmpty()) return Cell('n', num = s, fmt = "dd.mm.yyyy")
                val t = (m.groupValues[4].toInt() * 3600 + m.groupValues[5].toInt() * 60 + (m.groupValues[6].toIntOrNull() ?: 0)) / 86400.0
                return Cell('n', num = s + t, fmt = "dd.mm.yyyy hh:mm")
            }
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?)?$").matchEntire(raw)?.let { m ->
            val s = serial(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (s != null) {
                if (m.groupValues[4].isEmpty()) return Cell('n', num = s, fmt = "yyyy-mm-dd")
                val t = (m.groupValues[4].toInt() * 3600 + m.groupValues[5].toInt() * 60 + (m.groupValues[6].toIntOrNull() ?: 0)) / 86400.0
                return Cell('n', num = s + t, fmt = "yyyy-mm-dd hh:mm")
            }
        }
        val (n0, pre, suf) = core(raw) ?: return Cell('s', raw)
        val neg = n0.startsWith("-")
        val n = n0.trimStart('-', '+')
        val trMode = when (dec) { Dec.TR -> true; Dec.EN -> false; Dec.UNK -> trDefault }
        var sep = false
        val plain: String = when {
            Regex("^\\d+$").matches(n) -> { if ((n.length > 1 && n.startsWith("0")) || n.length > 15) return Cell('s', raw); n }
            Regex("^\\d{1,3}(\\.\\d{3})+,\\d+$").matches(n) -> { sep = true; n.replace(".", "").replace(',', '.') }
            Regex("^\\d{1,3}(,\\d{3})+\\.\\d+$").matches(n) -> { sep = true; n.replace(",", "") }
            Regex("^\\d{1,3}(\\.\\d{3}){2,}$").matches(n) -> { sep = true; n.replace(".", "") }
            Regex("^\\d{1,3}(,\\d{3}){2,}$").matches(n) -> { sep = true; n.replace(",", "") }
            Regex("^\\d{1,3}\\.\\d{3}$").matches(n) -> if (trMode) { sep = true; n.replace(".", "") } else n
            Regex("^\\d{1,3},\\d{3}$").matches(n) -> if (trMode) n.replace(',', '.') else { sep = true; n.replace(",", "") }
            Regex("^\\d*\\.\\d+$").matches(n) -> n
            Regex("^\\d*,\\d+$").matches(n) -> n.replace(',', '.')
            else -> return Cell('s', raw)
        }
        var v = plain.toDoubleOrNull() ?: return Cell('s', raw)
        if (neg) v = -v
        val fd = plain.substringAfter('.', "").length
        if (pre.isNotEmpty() && suf.isNotEmpty() && suf != "%") return Cell('s', raw)
        if (suf == "%") { return Cell('n', num = v / 100.0, fmt = if (fd == 0) "0%" else "0." + "0".repeat(minOf(fd, 4)) + "%") }
        val base = if (fd == 0) (if (sep || pre.isNotEmpty() || suf.isNotEmpty()) "#,##0" else "General") else if (fd == 2 || pre.isNotEmpty() || suf.isNotEmpty()) "#,##0." + "0".repeat(maxOf(fd, 2).coerceAtMost(4)) else if (sep) "#,##0." + "0".repeat(minOf(fd, 6)) else "General"
        val cur = pre.ifEmpty { suf }
        val fmt = when {
            cur.isEmpty() -> base
            pre.isNotEmpty() -> "\"$cur\"" + (if (cur.length > 1) "\\ " else "") + (if (base == "General") "#,##0.##" else base)
            else -> (if (base == "General") "#,##0.##" else base) + "\\ \"$cur\""
        }
        return Cell('n', num = v, fmt = fmt)
    }

    fun formula(s: String, tr: Boolean = false): String {
        var f = s.removePrefix("=")
        f = Regex("([A-Za-zÇĞİÖŞÜçğıöşü_.]+)\\s*\\(").replace(f) { m -> TRF[m.groupValues[1].uppercase(TR_UP)]?.let { "$it(" } ?: m.value }
        // Turkish separators: ; between arguments and , as decimal point
        val outside = f.replace(Regex("\"[^\"]*\""), "")
        if (outside.contains(';')) {
            val sb = StringBuilder(); var q = false
            for ((i, ch) in f.withIndex()) {
                if (ch == '"') q = !q
                sb.append(when { q -> ch; ch == ';' -> ','; ch == ',' && i > 0 && i + 1 < f.length && f[i - 1].isDigit() && f[i + 1].isDigit() -> '.'; else -> ch })
            }
            f = sb.toString()
        } else if (tr && !outside.contains('(')) f = f.replace(Regex("(?<=\\d),(?=\\d)"), ".")  // =B4*1,1
        return f
    }

    fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }; return sb.toString() }

    private fun numStr(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    private val BUILTIN = mapOf("General" to 0, "0" to 1, "0.00" to 2, "#,##0" to 3, "#,##0.00" to 4, "0%" to 9, "0.00%" to 10)

    fun build(sheets: List<XSheet>, o: DocOpts): Built {
        val sst = LinkedHashMap<String, Int>()
        var sstTotal = 0
        val customFmts = LinkedHashMap<String, Int>()
        val xfs = LinkedHashMap<String, Int>()
        xfs["0|0|0|0|0"] = 0
        fun fmtId(code: String): Int = BUILTIN[code] ?: customFmts.getOrPut(code) { 164 + customFmts.size }
        fun xf(code: String, header: Boolean, wrap: Boolean): Int = xfs.getOrPut("${fmtId(code)}|${if (header) 1 else 0}|${if (header) 2 else 0}|1|${if (wrap) 1 else 0}") { xfs.size }
        fun si(s: String): Int { sstTotal++; return sst.getOrPut(s) { sst.size } }
        val z = DocXml.Zip()
        val sheetXml = ArrayList<String>()
        val defined = StringBuilder()
        var totalRows = 0
        for ((si0, sh) in sheets.withIndex()) {
            val rows = sh.rows
            val ncol = maxOf(1, rows.maxOfOrNull { it.size } ?: 1)
            val decs = (0 until ncol).map { c -> decOf(rows.drop(1).map { it.getOrNull(c) ?: "" }) }
            val anyTr = decs.any { it == Dec.TR }
            val anyEn = decs.any { it == Dec.EN }
            val trDefault = if (anyTr != anyEn) anyTr else o.tr
            val widths = DoubleArray(ncol) { 6.0 }
            val sd = StringBuilder()
            for ((r, row) in rows.withIndex()) {
                sd.append("<row r=\"${r + 1}\">")
                for (c in 0 until ncol) {
                    val raw = (row.getOrNull(c) ?: "").let { if (it.length > 32000) it.take(32000) else it }
                    val ref = colName(c) + (r + 1)
                    val len = raw.split('\n').maxOf { it.length }
                    widths[c] = maxOf(widths[c], if (r == 0) len * 1.15 + 4 else len * 1.05 + 2)
                    if (raw.isBlank()) { if (r == 0) sd.append("<c r=\"$ref\" s=\"${xf("General", true, false)}\"/>"); continue }
                    if (r == 0) { sd.append("<c r=\"$ref\" t=\"s\" s=\"${xf("General", true, raw.contains('\n'))}\"><v>${si(raw.trim())}</v></c>"); continue }
                    val cell = classify(raw, decs[c], trDefault)
                    when (cell.kind) {
                        'n' -> {
                            if (cell.fmt.contains("yy")) widths[c] = maxOf(widths[c], if (cell.fmt.contains("hh")) 17.0 else 12.0)
                            sd.append("<c r=\"$ref\" s=\"${xf(cell.fmt, false, false)}\"><v>${numStr(cell.num)}</v></c>")
                        }
                        'f' -> sd.append("<c r=\"$ref\" s=\"${xf("General", false, false)}\"><f>${DocXml.esc(cell.str)}</f></c>")
                        else -> sd.append("<c r=\"$ref\" t=\"s\" s=\"${xf("General", false, cell.str.contains('\n'))}\"><v>${si(cell.str)}</v></c>")
                    }
                }
                sd.append("</row>")
            }
            totalRows += maxOf(0, rows.size - 1)
            val last = colName(ncol - 1) + maxOf(1, rows.size)
            val x = StringBuilder(DocXml.HDR + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"${DocXml.R}\">")
            x.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr><dimension ref=\"A1:$last\"/>")
            x.append("<sheetViews><sheetView workbookViewId=\"0\"" + (if (si0 == 0) " tabSelected=\"1\"" else "") + ">")
            if (rows.size > 1) x.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>")
            x.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>")
            for (c in 0 until ncol) x.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${String.format(Locale.US, "%.1f", widths[c].coerceIn(8.0, 60.0))}\" customWidth=\"1\"/>")
            x.append("</cols><sheetData>").append(sd).append("</sheetData>")
            if (rows.size > 1) {
                x.append("<autoFilter ref=\"A1:$last\"/>")
                val q = "'" + sh.name.replace("'", "''") + "'"
                defined.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"$si0\" hidden=\"1\">${DocXml.esc(q)}!\$A\$1:\$${colName(ncol - 1)}\$${rows.size}</definedName>")
            }
            x.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
            x.append("<pageSetup paperSize=\"9\" orientation=\"" + (if (o.landscape) "landscape" else "portrait") + "\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
            x.append("</worksheet>")
            sheetXml.add(x.toString())
        }
        val th = o.theme
        val n = sheets.size
        z.put("[Content_Types].xml", DocXml.HDR + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            (1..n).joinToString("") { "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>")
        z.put("_rels/.rels", DocXml.rootRels("xl/workbook.xml"))
        z.put("docProps/core.xml", DocXml.core(o))
        z.put("docProps/app.xml", DocXml.app("Microsoft Excel"))
        val R = DocXml.R
        val wbRels = (1..n).map { Triple("rId$it", "$R/worksheet", "worksheets/sheet$it.xml") } +
            listOf(Triple("rId${n + 1}", "$R/styles", "styles.xml"), Triple("rId${n + 2}", "$R/sharedStrings", "sharedStrings.xml"))
        z.put("xl/_rels/workbook.xml.rels", DocXml.rels(*wbRels.toTypedArray()))
        z.put("xl/workbook.xml", DocXml.HDR + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$R\">" +
            "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>" +
            sheets.withIndex().joinToString("") { (i, s) -> "<sheet name=\"${DocXml.esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" } + "</sheets>" +
            (if (defined.isNotEmpty()) "<definedNames>$defined</definedNames>" else "") +
            "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
        for ((i, x) in sheetXml.withIndex()) z.put("xl/worksheets/sheet${i + 1}.xml", x)
        val ss = StringBuilder(DocXml.HDR + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"$sstTotal\" uniqueCount=\"${sst.size}\">")
        for (s in sst.keys) ss.append("<si><t xml:space=\"preserve\">").append(DocXml.esc(s)).append("</t></si>")
        z.put("xl/sharedStrings.xml", ss.append("</sst>").toString())
        // styles
        val st = StringBuilder(DocXml.HDR + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (customFmts.isNotEmpty()) {
            st.append("<numFmts count=\"${customFmts.size}\">")
            for ((code, id) in customFmts) st.append("<numFmt numFmtId=\"$id\" formatCode=\"${DocXml.esc(code)}\"/>")
            st.append("</numFmts>")
        }
        st.append("<fonts count=\"2\"><font><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/><scheme val=\"minor\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FF${th.headText}\"/><name val=\"Calibri\"/><family val=\"2\"/><scheme val=\"minor\"/></font></fonts>")
        st.append("<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${th.headFill}\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>")
        st.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left style=\"thin\"><color rgb=\"FFD0D0D0\"/></left><right style=\"thin\"><color rgb=\"FFD0D0D0\"/></right><top style=\"thin\"><color rgb=\"FFD0D0D0\"/></top><bottom style=\"thin\"><color rgb=\"FFD0D0D0\"/></bottom><diagonal/></border></borders>")
        st.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"${xfs.size}\">")
        for (k in xfs.keys) {
            val p = k.split('|').map { it.toInt() }
            if (p.all { it == 0 }) { st.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"); continue }
            st.append("<xf numFmtId=\"${p[0]}\" fontId=\"${p[1]}\" fillId=\"${p[2]}\" borderId=\"${p[3]}\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">")
            st.append("<alignment vertical=\"" + (if (p[1] == 1) "center" else "top") + "\"" + (if (p[4] == 1) " wrapText=\"1\"" else "") + "/></xf>")
        }
        st.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles><dxfs count=\"0\"/><tableStyles count=\"0\"/></styleSheet>")
        z.put("xl/styles.xml", st.toString())
        val what = "Excel workbook, $n sheet" + (if (n == 1) "" else "s") + " (" + sheets.joinToString(", ") { it.name } + "), $totalRows data row" + (if (totalRows == 1) "" else "s")
        return Built(z.bytes(), what)
    }
}

// ===================================================================================================================== PPTX

object PptxW {
    private const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    const val SW = 12192000L
    const val SH = 6858000L
    private const val EMU = 12700L          // per point
    private const val BX = 609600L
    private const val BW = 10972800L
    private const val BY = 1508760L
    private const val BH = 4754880L
    private const val GRP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    class Slide(val title: String, val text: List<Blk>, val table: Blk.Table?, val isTitle: Boolean = false, val subtitle: String = "")

    // ---- size estimates (points) used to pick font sizes and split long slides
    private fun indent(level: Int) = 22.5 + 36.0 * level
    private fun lines(text: String, sz: Double, widthPt: Double): Int =
        text.split('\n').sumOf { l -> maxOf(1, Math.ceil(l.length * sz * 0.5 / widthPt).toInt()) }
    fun textHeight(bs: List<Blk>, sz: Double): Double = bs.sumOf { b ->
        when (b) {
            is Blk.Item -> lines(DocMd.plain(b.text), sz - 2 * minOf(b.level, 3), 850 - indent(b.level)) * sz * 1.15 + sz * 0.45
            is Blk.Code -> b.text.split('\n').size * (sz * 0.7) * 1.2 + sz * 0.5
            is Blk.H -> lines(DocMd.plain(b.text), sz, 850.0) * sz * 1.2 + sz * 0.6
            is Blk.Para -> lines(DocMd.plain(b.text), sz, 850.0) * sz * 1.15 + sz * 0.45
            else -> 0.0
        }
    }
    private val SIZES = doubleArrayOf(24.0, 22.0, 20.0, 18.0, 16.0)
    private const val BODY_PT = 360.0

    fun fitSize(bs: List<Blk>, avail: Double = BODY_PT): Double? = SIZES.firstOrNull { textHeight(bs, it) <= avail }

    private fun rowHeights(t: Blk.Table, colW: List<Double>, fs: Double): List<Double> = (-1 until t.rows.size).map { r ->
        val l = (0 until t.cols).maxOfOrNull { c -> lines(DocMd.plain(t.cell(r, c)), fs, maxOf(20.0, colW[c] - 10)) } ?: 1
        l * fs * 1.2 + 9.0
    }
    private val TSIZES = doubleArrayOf(18.0, 16.0, 14.0, 12.0, 11.0, 10.0)

    /** Splits sections into slides that fit. */
    fun plan(blocks: List<Blk>, o: DocOpts): List<Slide> {
        class Sec(val title: String, val fromHeading: Boolean, val bs: MutableList<Blk> = ArrayList())
        val secs = ArrayList<Sec>()
        var cur: Sec? = null
        fun flat(b: Blk): List<Blk> = when (b) { is Blk.Quote -> b.blocks.flatMap { flat(it) }; else -> listOf(b) }
        for (b in blocks) {
            when {
                b is Blk.H && b.level <= 2 -> { cur = Sec(DocMd.plain(b.text), true); secs.add(cur!!) }
                b is Blk.Rule || b is Blk.Break -> { val t = cur?.title ?: ""; cur = Sec(t, false); secs.add(cur!!) }
                else -> { if (cur == null) { cur = Sec("", false); secs.add(cur!!) }; cur!!.bs.addAll(flat(b)) }
            }
        }
        val slides = ArrayList<Slide>()
        // title slide: an opening # heading followed by a little text, else made from the title
        val first = secs.firstOrNull()
        val h1First = blocks.firstOrNull().let { it is Blk.H && it.level == 1 }
        if (first != null && h1First && first.bs.all { it is Blk.Para } && first.bs.size <= 3) {
            slides.add(Slide(if (o.titleGiven) o.title else first.title, emptyList(), null, true, first.bs.joinToString("\n") { DocMd.plain((it as Blk.Para).text) }.ifBlank { o.shortDate }))
            secs.removeAt(0)
        } else {
            if (first != null && first.title.isEmpty() && first.bs.all { it is Blk.Para } && first.bs.size <= 2 && first.bs.isNotEmpty()) {
                slides.add(Slide(o.title, emptyList(), null, true, first.bs.joinToString("\n") { DocMd.plain((it as Blk.Para).text) })); secs.removeAt(0)
            } else slides.add(Slide(o.title, emptyList(), null, true, o.shortDate))
        }
        for (s in secs) {
            if (s.bs.isEmpty()) { if (s.fromHeading && s.title.isNotEmpty()) slides.add(Slide(s.title, emptyList(), null)); continue }
            var text = ArrayList<Blk>()
            val parts = ArrayList<Slide>()
            for (b in s.bs) {
                if (b is Blk.Table) {
                    if (text.isNotEmpty() && textHeight(text, 16.0) <= BODY_PT * 0.4) parts.add(Slide(s.title, text, b))
                    else { if (text.isNotEmpty()) parts.add(Slide(s.title, text, null)); parts.add(Slide(s.title, emptyList(), b)) }
                    text = ArrayList()
                } else text.add(b)
            }
            if (text.isNotEmpty()) parts.add(Slide(s.title, text, null))
            for (p in parts) {
                // split text that does not fit even at 16 pt
                val textChunks = if (p.table == null && p.text.isNotEmpty() && fitSize(p.text) == null) {
                    val chunks = ArrayList<List<Blk>>(); var c = ArrayList<Blk>()
                    for (b in p.text) { if (c.isNotEmpty() && textHeight(c + b, 18.0) > BODY_PT) { chunks.add(c); c = ArrayList() }; c.add(b) }
                    if (c.isNotEmpty()) chunks.add(c); chunks
                } else listOf(p.text)
                for (tc in textChunks) {
                    if (p.table == null) { slides.add(Slide(p.title, tc, null)); continue }
                    // split tall tables, repeating the header
                    val avail = BODY_PT + 20 - (if (tc.isNotEmpty()) textHeight(tc, 16.0) + 12 else 0.0)
                    val cw = colWidthsPt(p.table)
                    val fits = TSIZES.any { fs -> rowHeights(p.table, cw, fs).sum() <= avail }
                    if (fits) { slides.add(Slide(p.title, tc, p.table)); continue }
                    val hs = rowHeights(p.table, cw, 12.0)
                    var rows = ArrayList<List<String>>(); var h = hs[0]; var firstChunk = true
                    for ((k, r) in p.table.rows.withIndex()) {
                        val a = if (firstChunk) avail else BODY_PT + 20
                        if (rows.isNotEmpty() && h + hs[k + 1] > a) {
                            slides.add(Slide(p.title, if (firstChunk) tc else emptyList(), Blk.Table(p.table.head, rows, p.table.align)))
                            rows = ArrayList(); h = hs[0]; firstChunk = false
                        }
                        rows.add(r); h += hs[k + 1]
                    }
                    if (rows.isNotEmpty()) slides.add(Slide(p.title, if (firstChunk) tc else emptyList(), Blk.Table(p.table.head, rows, p.table.align)))
                }
            }
        }
        // number repeated titles: "Title (2)"
        val count = HashMap<String, Int>()
        return slides.mapIndexed { i, s ->
            if (s.isTitle || s.title.isEmpty()) s else {
                val prev = slides.getOrNull(i - 1)
                if (prev != null && !prev.isTitle && prev.title == s.title) { val k = (count[s.title] ?: 1) + 1; count[s.title] = k; Slide("${s.title} ($k)", s.text, s.table) }
                else { count[s.title] = 1; s }
            }
        }
    }

    private fun colWidthsPt(t: Blk.Table): List<Double> = DocMd.colWeights(t, 4, 30).map { it * BW / EMU }

    fun build(blocks: List<Blk>, o: DocOpts): Built {
        val slides = plan(blocks, o)
        val th = o.theme
        val z = DocXml.Zip()
        val n = slides.size
        val R = DocXml.R
        z.put("[Content_Types].xml", DocXml.HDR + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            "<Override PartName=\"/ppt/presProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presProps+xml\"/>" +
            "<Override PartName=\"/ppt/viewProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml\"/>" +
            "<Override PartName=\"/ppt/tableStyles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml\"/>" +
            (1..n).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>")
        z.put("_rels/.rels", DocXml.rootRels("ppt/presentation.xml"))
        z.put("docProps/core.xml", DocXml.core(o))
        z.put("docProps/app.xml", DocXml.app("Microsoft Office PowerPoint"))
        val pr = arrayListOf(Triple("rId1", "$R/slideMaster", "slideMasters/slideMaster1.xml"), Triple("rId2", "$R/theme", "theme/theme1.xml"),
            Triple("rId3", "$R/presProps", "presProps.xml"), Triple("rId4", "$R/viewProps", "viewProps.xml"), Triple("rId5", "$R/tableStyles", "tableStyles.xml"))
        for (i in 1..n) pr.add(Triple("rId${i + 10}", "$R/slide", "slides/slide$i.xml"))
        z.put("ppt/_rels/presentation.xml.rels", DocXml.rels(*pr.toTypedArray()))
        z.put("ppt/presentation.xml", DocXml.HDR + "<p:presentation $NS saveSubsetFonts=\"1\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" +
            (1..n).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rId${it + 10}\"/>" } +
            "</p:sldIdLst><p:sldSz cx=\"$SW\" cy=\"$SH\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        z.put("ppt/presProps.xml", DocXml.HDR + "<p:presentationPr $NS/>")
        z.put("ppt/viewProps.xml", DocXml.HDR + "<p:viewPr $NS><p:normalViewPr><p:restoredLeft sz=\"15620\"/><p:restoredTop sz=\"94660\"/></p:normalViewPr><p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>")
        z.put("ppt/tableStyles.xml", DocXml.HDR + "<a:tblStyleLst xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>")
        z.put("ppt/theme/theme1.xml", theme(th))
        z.put("ppt/slideMasters/slideMaster1.xml", master(th))
        z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", DocXml.rels(Triple("rId1", "$R/slideLayout", "../slideLayouts/slideLayout1.xml"), Triple("rId2", "$R/slideLayout", "../slideLayouts/slideLayout2.xml"), Triple("rId3", "$R/theme", "../theme/theme1.xml")))
        z.put("ppt/slideLayouts/slideLayout1.xml", layoutTitle())
        z.put("ppt/slideLayouts/slideLayout2.xml", layoutContent())
        for (i in 1..2) z.put("ppt/slideLayouts/_rels/slideLayout$i.xml.rels", DocXml.rels(Triple("rId1", "$R/slideMaster", "../slideMasters/slideMaster1.xml")))
        var tables = 0
        for ((i, s) in slides.withIndex()) {
            val links = LinkedHashMap<String, String>()
            val xml = if (s.isTitle) titleSlide(s, o) else contentSlide(s, i + 1, o, links)
            if (s.table != null) tables++
            z.put("ppt/slides/slide${i + 1}.xml", xml)
            val rels = arrayListOf(Triple("rId1", "$R/slideLayout", "../slideLayouts/slideLayout${if (s.isTitle) 1 else 2}.xml"))
            for ((u, id) in links) rels.add(Triple(id, "$R/hyperlink", u))
            z.put("ppt/slides/_rels/slide${i + 1}.xml.rels", DocXml.rels(*rels.toTypedArray(), external = links.values.toSet()))
        }
        return Built(z.bytes(), "PowerPoint presentation, $n slide" + (if (n == 1) "" else "s") + (if (tables > 0) ", $tables with tables" else ""))
    }

    private fun esc(s: String) = DocXml.esc(s)

    private fun runs(rs: List<DocRun>, sz: Int, o: DocOpts, links: MutableMap<String, String>?, bold: Boolean = false, color: String? = null, mono: Boolean = false): String {
        val sb = StringBuilder()
        for (r in rs) {
            if (r.br) { sb.append("<a:br><a:rPr lang=\"${o.lang}\" sz=\"$sz\"/></a:br>"); continue }
            val link = r.link?.takeIf { links != null && Regex("^(https?|mailto):", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            sb.append("<a:r><a:rPr lang=\"${o.lang}\" sz=\"$sz\"")
            if (r.b || bold) sb.append(" b=\"1\"")
            if (r.i) sb.append(" i=\"1\"")
            if (r.u || link != null) sb.append(" u=\"sng\"")
            if (r.strike) sb.append(" strike=\"sngStrike\"")
            sb.append(" dirty=\"0\">")
            val col = if (link != null) "0563C1" else if (r.code) "9C2B2B" else color
            if (col != null) sb.append("<a:solidFill><a:srgbClr val=\"$col\"/></a:solidFill>")
            if (r.code || mono) sb.append("<a:latin typeface=\"Consolas\"/><a:cs typeface=\"Consolas\"/>")
            if (link != null) sb.append("<a:hlinkClick r:id=\"${links!!.getOrPut(link) { "rIdL${links.size + 1}" }}\"/>")
            sb.append("</a:rPr><a:t>").append(esc(r.text)).append("</a:t></a:r>")
        }
        return sb.toString()
    }

    private fun titleSlide(s: Slide, o: DocOpts): String {
        val th = o.theme
        val sub = s.subtitle.split('\n').filter { it.isNotBlank() }.joinToString("") { "<a:p>" + runs(DocMd.inline(it), 2000, o, null, color = "DCE6F2") + "</a:p>" }.ifEmpty { "<a:p><a:endParaRPr lang=\"${o.lang}\"/></a:p>" }
        return DocXml.HDR + "<p:sld $NS><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${th.titleBg}\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>$GRP" +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"ctrTitle\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
            "<p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p>" + runs(DocMd.inline(s.title), if (s.title.length > 60) 3600 else 4400, o, null, bold = true, color = "FFFFFF") + "</a:p></p:txBody></p:sp>" +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Subtitle 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"subTitle\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
            "<p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/>$sub</p:txBody></p:sp>" +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"4\" name=\"Accent\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"5181600\" y=\"3246120\"/><a:ext cx=\"1828800\" cy=\"45720\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>" +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private fun contentSlide(s: Slide, num: Int, o: DocOpts, links: MutableMap<String, String>): String {
        val th = o.theme
        val sb = StringBuilder(DocXml.HDR + "<p:sld $NS><p:cSld><p:spTree>$GRP")
        var id = 2
        val tsz = if (s.title.length > 70) 2400 else if (s.title.length > 45) 2800 else 3200
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"${id++}\" name=\"Title\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/>")
        sb.append("<p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p>").append(if (s.title.isEmpty()) "<a:endParaRPr lang=\"${o.lang}\"/>" else runs(DocMd.inline(s.title), tsz, o, links, bold = true)).append("</a:p></p:txBody></p:sp>")
        var tableTop = BY
        if (s.text.isNotEmpty()) {
            val avail = if (s.table != null) BODY_PT * 0.45 else BODY_PT
            val sz = fitSize(s.text, avail) ?: 16.0
            val hPt = if (s.table != null) textHeight(s.text, sz) + 14 else BH.toDouble() / EMU
            val h = (hPt * EMU).toLong().coerceAtMost(BH)
            tableTop = BY + h + 6 * EMU
            sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"${id++}\" name=\"Content\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr>")
            sb.append("<p:spPr><a:xfrm><a:off x=\"$BX\" y=\"$BY\"/><a:ext cx=\"$BW\" cy=\"$h\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/>")
            val ord = IntArray(9)
            var prevList = -1
            for (b in s.text) {
                val base = (sz * 100).toInt()
                when (b) {
                    is Blk.Item -> {
                        val lsz = maxOf(1200, base - 200 * minOf(b.level, 3))
                        if (b.list != prevList) { ord.fill(0); prevList = b.list }
                        val lvl = minOf(b.level, 4)
                        sb.append("<a:p><a:pPr lvl=\"$lvl\"")
                        if (b.ordered) {
                            val mar = (457200 + 342900 * lvl)
                            sb.append(" marL=\"$mar\" indent=\"-342900\"><a:buFont typeface=\"+mj-lt\"/><a:buAutoNum type=\"" + listOf("arabicPeriod", "alphaLcPeriod", "romanLcPeriod")[lvl % 3] + "\"" + (if (ord[lvl] == 0 && b.num != 1) " startAt=\"${b.num}\"" else "") + "/></a:pPr>")
                            ord[lvl]++
                        } else { sb.append("/>"); ord[lvl] = 0 }
                        for (l in lvl + 1 until 9) ord[l] = 0
                        sb.append(runs(DocMd.inline(b.text), lsz, o, links)).append("<a:endParaRPr lang=\"${o.lang}\" sz=\"$lsz\"/></a:p>")
                    }
                    is Blk.Para -> { prevList = -1; sb.append("<a:p><a:pPr marL=\"0\" indent=\"0\"><a:buNone/></a:pPr>").append(runs(DocMd.inline(b.text), base, o, links)).append("<a:endParaRPr lang=\"${o.lang}\" sz=\"$base\"/></a:p>") }
                    is Blk.H -> { prevList = -1; sb.append("<a:p><a:pPr marL=\"0\" indent=\"0\"><a:spcBef><a:spcPts val=\"1200\"/></a:spcBef><a:buNone/></a:pPr>").append(runs(DocMd.inline(b.text), base, o, links, bold = true, color = th.accent)).append("</a:p>") }
                    is Blk.Code -> {
                        prevList = -1
                        val csz = maxOf(1000, (base * 0.7).toInt() / 100 * 100)
                        for (l in b.text.split('\n')) sb.append("<a:p><a:pPr marL=\"0\" indent=\"0\"><a:spcBef><a:spcPts val=\"0\"/></a:spcBef><a:buNone/></a:pPr>")
                            .append(if (l.isEmpty()) "" else runs(listOf(DocRun(l)), csz, o, null, mono = true, color = "262626")).append("<a:endParaRPr lang=\"${o.lang}\" sz=\"$csz\"/></a:p>")
                    }
                    else -> {}
                }
            }
            sb.append("</p:txBody></p:sp>")
        }
        val t = s.table
        if (t != null) {
            val cw = colWidthsPt(t)
            val avail = (SH - 420000 - tableTop).toDouble() / EMU
            val fs = TSIZES.firstOrNull { rowHeights(t, cw, it).sum() <= avail } ?: 10.0
            val rh = rowHeights(t, cw, fs)
            val total = (rh.sum() * EMU).toLong()
            val widths = cw.map { (it * EMU).toLong() }.toMutableList()
            widths[widths.size - 1] += BW - widths.sum()
            sb.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"${id++}\" name=\"Table\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
            sb.append("<p:xfrm><a:off x=\"$BX\" y=\"$tableTop\"/><a:ext cx=\"$BW\" cy=\"$total\"/></p:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"1\" bandRow=\"1\"/><a:tblGrid>")
            for (w in widths) sb.append("<a:gridCol w=\"$w\"/>")
            sb.append("</a:tblGrid>")
            val szc = (fs * 100).toInt()
            val numCol = BooleanArray(t.cols) { c -> t.rows.isNotEmpty() && t.rows.count { DocMd.numeric(it.getOrNull(c) ?: "") } * 2 > t.rows.count { (it.getOrNull(c) ?: "").isNotBlank() } }
            for (r in -1 until t.rows.size) {
                sb.append("<a:tr h=\"${(rh[r + 1] * EMU).toLong()}\">")
                for (c in 0 until t.cols) {
                    val head = r < 0
                    val al = when (t.alignOf(c)) { 'c' -> "ctr"; 'r' -> "r"; 'l' -> "l"; else -> if (numCol[c] && !head) "r" else "l" }
                    sb.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr algn=\"$al\"/>")
                    sb.append(runs(DocMd.inline(t.cell(r, c)), szc, o, links, bold = head, color = if (head) th.headText else "1F1F1F"))
                    sb.append("<a:endParaRPr lang=\"${o.lang}\" sz=\"$szc\"/></a:p></a:txBody><a:tcPr marL=\"68580\" marR=\"68580\" marT=\"34290\" marB=\"34290\" anchor=\"ctr\">")
                    for (ln in listOf("lnL", "lnR", "lnT", "lnB")) sb.append("<a:$ln w=\"9525\"><a:solidFill><a:srgbClr val=\"BFBFBF\"/></a:solidFill></a:$ln>")
                    sb.append("<a:solidFill><a:srgbClr val=\"" + (if (head) th.headFill else if (r % 2 == 1) th.zebra else "FFFFFF") + "\"/></a:solidFill></a:tcPr></a:tc>")
                }
                sb.append("</a:tr>")
            }
            sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
        }
        // slide number
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"${id++}\" name=\"Slide Number\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"${SW - BX - 1219200}\" y=\"${SH - 420000}\"/><a:ext cx=\"1219200\" cy=\"320040\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
        sb.append("<p:txBody><a:bodyPr wrap=\"none\" lIns=\"0\" rIns=\"0\" anchor=\"ctr\"/><a:lstStyle/><a:p><a:pPr algn=\"r\"/><a:fld id=\"{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}\" type=\"slidenum\"><a:rPr lang=\"${o.lang}\" sz=\"1100\"><a:solidFill><a:srgbClr val=\"8C8C8C\"/></a:solidFill></a:rPr><a:t>$num</a:t></a:fld></a:p></p:txBody></p:sp>")
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    private fun ph(id: Int, name: String, ph: String, x: Long, y: Long, w: Long, h: Long, body: String, anchor: String = "t") =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
            "<p:txBody><a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr>$body</p:txBody></p:sp>"

    private fun master(th: DocTheme): String {
        val bullets = listOf("•", "–", "•", "–", "»")
        val sizes = listOf(2400, 2000, 1800, 1600, 1600)
        val body = StringBuilder("<p:bodyStyle>")
        for (l in 0 until 5) {
            val mar = 285750 + 457200 * l
            body.append("<a:lvl${l + 1}pPr marL=\"$mar\" indent=\"-285750\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:lnSpc><a:spcPct val=\"100000\"/></a:lnSpc><a:spcBef><a:spcPts val=\"${if (l == 0) 900 else 500}\"/></a:spcBef>" +
                "<a:buClr><a:schemeClr val=\"accent1\"/></a:buClr><a:buFont typeface=\"Arial\" panose=\"020B0604020202020204\" pitchFamily=\"34\" charset=\"0\"/><a:buChar char=\"${bullets[l]}\"/><a:defRPr sz=\"${sizes[l]}\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/></a:defRPr></a:lvl${l + 1}pPr>")
        }
        body.append("</p:bodyStyle>")
        return DocXml.HDR + "<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$GRP" +
            ph(2, "Title Placeholder 1", "<p:ph type=\"title\"/>", BX, 365125, BW, 1005840, "<a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Title</a:t></a:r></a:p>", "b") +
            ph(3, "Text Placeholder 2", "<p:ph type=\"body\" idx=\"1\"/>", BX, BY, BW, BH, "<a:lstStyle/><a:p><a:pPr lvl=\"0\"/><a:r><a:rPr lang=\"en-US\"/><a:t>Text</a:t></a:r></a:p>") +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"4\" name=\"Accent Bar\"/><p:cNvSpPr/><p:nvPr userDrawn=\"1\"/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"$BX\" y=\"1394460\"/><a:ext cx=\"1371600\" cy=\"45720\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>" +
            "</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/><p:sldLayoutId id=\"2147483650\" r:id=\"rId2\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc><a:spcBef><a:spcPct val=\"0\"/></a:spcBef><a:buNone/>" +
            "<a:defRPr sz=\"3200\" b=\"1\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx2\"/></a:solidFill><a:latin typeface=\"+mj-lt\"/><a:ea typeface=\"+mj-ea\"/><a:cs typeface=\"+mj-cs\"/></a:defRPr></a:lvl1pPr></p:titleStyle>" +
            body +
            "<p:otherStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr><a:lvl1pPr marL=\"0\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:defRPr sz=\"1800\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/></a:defRPr></a:lvl1pPr></p:otherStyle>" +
            "</p:txStyles></p:sldMaster>"
    }

    private fun layoutTitle() = DocXml.HDR + "<p:sldLayout $NS type=\"title\" preserve=\"1\" showMasterSp=\"0\"><p:cSld name=\"Title Slide\"><p:spTree>$GRP" +
        ph(2, "Title 1", "<p:ph type=\"ctrTitle\"/>", 914400, 1600200, 10363200, 1554480, "<a:lstStyle><a:lvl1pPr algn=\"ctr\"><a:defRPr sz=\"4400\"/></a:lvl1pPr></a:lstStyle><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Title</a:t></a:r></a:p>", "b") +
        ph(3, "Subtitle 2", "<p:ph type=\"subTitle\" idx=\"1\"/>", 1524000, 3429000, 9144000, 1655762, "<a:lstStyle><a:lvl1pPr marL=\"0\" indent=\"0\" algn=\"ctr\"><a:buNone/><a:defRPr sz=\"2000\"/></a:lvl1pPr></a:lstStyle><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Subtitle</a:t></a:r></a:p>") +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun layoutContent() = DocXml.HDR + "<p:sldLayout $NS type=\"obj\" preserve=\"1\"><p:cSld name=\"Title and Content\"><p:spTree>$GRP" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Title</a:t></a:r></a:p></p:txBody></p:sp>" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Content Placeholder 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr lvl=\"0\"/><a:r><a:rPr lang=\"en-US\"/><a:t>Text</a:t></a:r></a:p></p:txBody></p:sp>" +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun theme(th: DocTheme): String {
        fun c(n: String, v: String) = "<a:$n><a:srgbClr val=\"$v\"/></a:$n>"
        val fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        return DocXml.HDR + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"yaver\"><a:themeElements><a:clrScheme name=\"yaver\">" +
            c("dk1", "1F1F1F") + c("lt1", "FFFFFF") + c("dk2", th.dark) + c("lt2", "E7E6E6") + c("accent1", th.accent) + c("accent2", "ED7D31") +
            c("accent3", "A5A5A5") + c("accent4", "FFC000") + c("accent5", "5B9BD5") + c("accent6", "70AD47") + c("hlink", "0563C1") + c("folHlink", "954F72") +
            "</a:clrScheme><a:fontScheme name=\"yaver\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"yaver\"><a:fillStyleLst>$fill$fill$fill</a:fillStyleLst><a:lnStyleLst>" +
            listOf(6350, 12700, 19050).joinToString("") { "<a:ln w=\"$it\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">$fill<a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>" } +
            "</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }
}

// ===================================================================================================================== HTML

object DocHtml {
    private fun esc(s: String) = DocXml.esc(s).replace("\n", "<br>")

    fun runs(rs: List<DocRun>): String = rs.joinToString("") { r ->
        if (r.br) "<br>" else {
            var h = esc(r.text)
            if (r.code) h = "<code>$h</code>"
            if (r.b) h = "<strong>$h</strong>"
            if (r.i) h = "<em>$h</em>"
            if (r.strike) h = "<del>$h</del>"
            if (r.u) h = "<u>$h</u>"
            if (r.link != null) h = "<a href=\"${DocXml.esc(r.link)}\">$h</a>"
            h
        }
    }

    fun body(blocks: List<Blk>, sb: StringBuilder) {
        val open = ArrayList<String>() // stack of "ul"/"ol"
        fun closeTo(level: Int) { while (open.size > level) sb.append("</li></").append(open.removeAt(open.size - 1)).append(">") }
        var prevList = -1
        for (b in blocks) {
            if (b !is Blk.Item) { closeTo(0); prevList = -1 }
            when (b) {
                is Blk.H -> sb.append("<h${b.level}>").append(runs(DocMd.inline(b.text))).append("</h${b.level}>\n")
                is Blk.Para -> sb.append("<p>").append(runs(DocMd.inline(b.text))).append("</p>\n")
                is Blk.Item -> {
                    if (b.list != prevList) { closeTo(0); prevList = b.list }
                    val tag = if (b.ordered) "ol" else "ul"
                    if (open.size > b.level + 1) closeTo(b.level + 1)
                    if (open.size == b.level + 1) {
                        if (open.last() != tag) { closeTo(b.level); sb.append("<$tag" + (if (b.ordered && b.num != 1) " start=\"${b.num}\"" else "") + ">"); open.add(tag) } else sb.append("</li>")
                    }
                    while (open.size < b.level + 1) { sb.append("<$tag" + (if (b.ordered && b.num != 1) " start=\"${b.num}\"" else "") + ">"); open.add(tag) }
                    sb.append("<li>").append(runs(DocMd.inline(b.text)))
                }
                is Blk.Quote -> { sb.append("<blockquote>"); body(b.blocks, sb); sb.append("</blockquote>\n") }
                is Blk.Code -> sb.append("<pre><code>").append(DocXml.esc(b.text)).append("</code></pre>\n")
                Blk.Rule -> sb.append("<hr>\n")
                Blk.Break -> sb.append("<div class=\"pb\"></div>\n")
                is Blk.Table -> {
                    sb.append("<table><thead><tr>")
                    for (c in 0 until b.cols) sb.append("<th").append(al(b, c, true)).append(">").append(runs(DocMd.inline(b.cell(-1, c)))).append("</th>")
                    sb.append("</tr></thead><tbody>")
                    for (r in b.rows.indices) { sb.append("<tr>"); for (c in 0 until b.cols) sb.append("<td").append(al(b, c, false, b.cell(r, c))).append(">").append(runs(DocMd.inline(b.cell(r, c)))).append("</td>"); sb.append("</tr>") }
                    sb.append("</tbody></table>\n")
                }
            }
        }
        closeTo(0)
    }

    private fun al(t: Blk.Table, c: Int, head: Boolean, v: String = ""): String = when (t.alignOf(c)) {
        'c' -> " style=\"text-align:center\""; 'r' -> " style=\"text-align:right\""; 'l' -> ""
        else -> if (!head && DocMd.numeric(v)) " style=\"text-align:right\"" else ""
    }

    fun render(blocks: List<Blk>, o: DocOpts): String {
        val th = o.theme
        val sb = StringBuilder("<!doctype html>\n<html lang=\"${o.lang.take(2)}\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${DocXml.esc(o.title)}</title><style>\n" +
            "body{font-family:Calibri,'Segoe UI',Roboto,Arial,sans-serif;max-width:860px;margin:0 auto;padding:24px 16px;color:#1f1f1f;line-height:1.5;background:#fff}" +
            "h1,h2,h3,h4,h5,h6{color:#${th.dark};line-height:1.25}h2,h3{color:#${th.accent}}h1{border-bottom:2px solid #${th.accent};padding-bottom:4px}" +
            "table{border-collapse:collapse;width:100%;margin:12px 0}th,td{border:1px solid #bfbfbf;padding:5px 8px;vertical-align:top}th{background:#${th.headFill};color:#${th.headText};text-align:left}tbody tr:nth-child(even){background:#${th.zebra}}" +
            "blockquote{border-left:4px solid #${th.accent};margin:12px 0;padding:2px 14px;color:#404040;font-style:italic}pre{background:#f2f2f2;padding:10px;overflow:auto}code{font-family:Consolas,monospace;background:#f2f2f2;padding:0 3px}" +
            "pre code{padding:0}a{color:#0563c1}hr{border:0;border-top:1px solid #bfbfbf}.pb{page-break-after:always}@page{size:A4 ${if (o.landscape) "landscape" else "portrait"};margin:2cm}\n</style></head><body>\n")
        val first = blocks.firstOrNull()
        if (o.title.isNotBlank() && !(first is Blk.H && first.level == 1) && (o.titleGiven || first !is Blk.H)) sb.append("<h1>").append(DocXml.esc(o.title)).append("</h1>\n")
        body(blocks, sb)
        return sb.append("</body></html>\n").toString()
    }
}

// ===================================================================================================================== PDF

/** Pure pagination helpers for the PDF renderer (JVM-testable). */
object DocPager {
    /** Lines [from, result) of a layout fit in [avail] points; at least one line is always taken. */
    fun fit(tops: FloatArray, bottoms: FloatArray, from: Int, avail: Float): Int {
        var e = from
        while (e < bottoms.size && bottoms[e] - tops[from] <= avail) e++
        return if (e == from) from + 1 else e
    }

    /** Column widths for [weights] in [total] points, each at least [minW] (when it fits). */
    fun colWidths(weights: DoubleArray, total: Float, minW: Float): FloatArray {
        val n = weights.size
        if (n == 0) return FloatArray(0)
        val mw = minOf(minW, total / n)
        val w = FloatArray(n) { (weights[it] * total).toFloat() }
        var deficit = 0f
        for (i in 0 until n) if (w[i] < mw) { deficit += mw - w[i]; w[i] = mw }
        if (deficit > 0) {
            val big = (0 until n).filter { w[it] > mw }
            val sum = big.sumOf { (w[it] - mw).toDouble() }.toFloat()
            for (i in big) w[i] -= deficit * (w[i] - mw) / sum
        }
        return w
    }
}

object PdfW {
    private const val MARGIN = 54f
    private const val FOOTER = 22f

    private class Page(val ops: ArrayList<(Canvas) -> Unit> = ArrayList())

    private fun color(hex: String): Int = (0xFF000000.toInt()) or hex.toInt(16)

    private fun spans(rs: List<DocRun>, linkColor: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (r in rs) {
            val start = sb.length
            if (r.br) { sb.append('\n'); continue }
            sb.append(r.text)
            val end = sb.length
            if (end == start) continue
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            val style = (if (r.b) Typeface.BOLD else 0) or (if (r.i) Typeface.ITALIC else 0)
            if (style != 0) sb.setSpan(StyleSpan(style), start, end, f)
            if (r.code) { sb.setSpan(TypefaceSpan("monospace"), start, end, f); sb.setSpan(BackgroundColorSpan(0xFFF0F0F0.toInt()), start, end, f); sb.setSpan(ForegroundColorSpan(0xFF9C2B2B.toInt()), start, end, f) }
            if (r.link != null) { sb.setSpan(ForegroundColorSpan(linkColor), start, end, f); sb.setSpan(UnderlineSpan(), start, end, f) }
            if (r.u && r.link == null) sb.setSpan(UnderlineSpan(), start, end, f)
            if (r.strike) sb.setSpan(StrikethroughSpan(), start, end, f)
        }
        return sb
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, maxOf(10, width)).setAlignment(align).setLineSpacing(0f, 1.15f).setIncludePad(false).build()

    /** Renders [blocks] as A4 pages into [out]; returns the page count. */
    fun build(blocks: List<Blk>, o: DocOpts, out: OutputStream): Int {
        val th = o.theme
        val pw = if (o.landscape) 842 else 595
        val phh = if (o.landscape) 595 else 842
        val cw = pw - 2 * MARGIN
        val bottom = phh - MARGIN - FOOTER
        val accent = color(th.accent)
        val dark = color(th.dark)
        val linkColor = 0xFF0563C1.toInt()
        val pages = ArrayList<Page>()
        var y = MARGIN
        fun newPage() { pages.add(Page()); y = MARGIN }
        newPage()
        fun op(f: (Canvas) -> Unit) { pages.last().ops.add(f) }
        fun ensure(h: Float) { if (y + h > bottom && y > MARGIN + 0.5f) newPage() }
        fun paint(size: Float, c: Int = 0xFF1F1F1F.toInt(), bold: Boolean = false, italic: Boolean = false, mono: Boolean = false): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; color = c
            val base = if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF
            typeface = Typeface.create(base, (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
        }

        /** Draws a layout at x, splitting it over pages by whole lines; [decor] draws backgrounds/bars per chunk. */
        fun flow(lay: StaticLayout, x: Float, after: Float, decor: ((Canvas, Float, Float) -> Unit)? = null, pad: Float = 0f) {
            val n = lay.lineCount
            val tops = FloatArray(n) { lay.getLineTop(it).toFloat() }
            val bots = FloatArray(n) { lay.getLineBottom(it).toFloat() }
            var line = 0
            while (line < n) {
                if (y + (bots[line] - tops[line]) + 2 * pad > bottom && y > MARGIN + 0.5f) newPage()
                val end = DocPager.fit(tops, bots, line, bottom - y - 2 * pad)
                val t0 = tops[line]
                val t1 = bots[end - 1]
                val yy = y + pad
                decor?.let { d -> val top = y; val h = t1 - t0 + 2 * pad; op { c -> d(c, top, top + h) } }
                op { c -> c.save(); c.translate(x, yy - t0); c.clipRect(0f, t0, lay.width.toFloat(), t1); lay.draw(c); c.restore() }
                y += t1 - t0 + 2 * pad
                line = end
                if (line < n) newPage()
            }
            y += after
        }

        val body = 10.5f
        val first = blocks.firstOrNull()
        if (o.title.isNotBlank() && !(first is Blk.H && first.level == 1) && (o.titleGiven || first !is Blk.H)) {
            val lay = layout(spans(DocMd.inline(o.title), linkColor), paint(22f, dark, bold = true), cw.toInt())
            flow(lay, MARGIN, 4f)
            val ly = y
            op { c -> c.drawRect(MARGIN, ly, MARGIN + cw, ly + 1.5f, Paint().apply { color = accent }) }
            y += 16f
        }

        fun render(bs: List<Blk>, quote: Boolean) {
            val counters = IntArray(9)
            var prevList = -1
            for (b in bs) {
                if (b !is Blk.Item) prevList = -1
                when (b) {
                    is Blk.H -> {
                        val sz = when (b.level) { 1 -> 19f; 2 -> 16f; 3 -> 13.5f; 4 -> 12f; else -> 11f }
                        val lay = layout(spans(DocMd.inline(b.text), linkColor), paint(sz, if (b.level == 1 || b.level == 4) dark else accent, bold = true, italic = b.level >= 4 && b.level != 5), cw.toInt())
                        y += if (y <= MARGIN + 0.5f) 0f else sz * 0.8f
                        ensure(lay.height + 3 * body * 1.4f)   // keep with the next lines
                        flow(lay, MARGIN, sz * 0.4f)
                    }
                    is Blk.Para -> {
                        if (quote) {
                            val lay = layout(spans(DocMd.inline(b.text), linkColor), paint(body, 0xFF404040.toInt(), italic = true), (cw - 24).toInt())
                            flow(lay, MARGIN + 18, 8f, { c, t, btm -> c.drawRect(MARGIN + 4, t, MARGIN + 7, btm, Paint().apply { color = accent }) })
                        } else flow(layout(spans(DocMd.inline(b.text), linkColor), paint(body), cw.toInt()), MARGIN, 7f)
                    }
                    is Blk.Item -> {
                        if (b.list != prevList) { counters.fill(0); prevList = b.list }
                        if (b.ordered) { if (counters[b.level] == 0) counters[b.level] = b.num - 1; counters[b.level]++ } else counters[b.level] = 0
                        for (l in b.level + 1 until 9) counters[l] = 0
                        val indent = 16f + 18f * b.level
                        val marker = if (b.ordered) when (b.level % 3) { 0 -> "${counters[b.level]}."; 1 -> "${('a' + (counters[b.level] - 1) % 26)}."; else -> roman(counters[b.level]) + "." } else listOf("•", "◦", "▪")[b.level % 3]
                        val p = paint(body)
                        val lay = layout(spans(DocMd.inline(b.text), linkColor), p, (cw - indent - 4).toInt())
                        ensure(if (lay.lineCount > 0) lay.getLineBottom(0).toFloat() else body * 1.3f)
                        val my = y + (if (lay.lineCount > 0) lay.getLineBaseline(0).toFloat() else body)
                        val mx = MARGIN + indent - 4
                        op { c -> c.drawText(marker, mx - p.measureText(marker), my, p) }
                        flow(lay, MARGIN + indent, 3f)
                    }
                    is Blk.Quote -> { render(b.blocks, true); y += 2f }
                    is Blk.Code -> {
                        val lay = layout(b.text.ifEmpty { " " }, paint(8.8f, 0xFF262626.toInt(), mono = true), (cw - 16).toInt())
                        flow(lay, MARGIN + 8, 9f, { c, t, btm -> c.drawRect(MARGIN, t, MARGIN + cw, btm, Paint().apply { color = 0xFFF2F2F2.toInt() }) }, pad = 5f)
                    }
                    Blk.Rule -> { ensure(14f); val ly = y + 6; op { c -> c.drawRect(MARGIN, ly, MARGIN + cw, ly + 0.8f, Paint().apply { color = 0xFFBFBFBF.toInt() }) }; y += 16f }
                    Blk.Break -> if (y > MARGIN + 0.5f) newPage()
                    is Blk.Table -> table(b, o, cw, linkColor, { y }, { y = it }, { newPage() }, ::op, bottom, { h -> ensure(h) })
                }
            }
        }
        render(blocks, false)

        val doc = PdfDocument()
        try {
            val fp = paint(8.5f, 0xFF7F7F7F.toInt())
            for ((i, pg) in pages.withIndex()) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, phh, i + 1).create())
                val c = page.canvas
                for (f in pg.ops) f(c)
                val s = "${i + 1} / ${pages.size}"
                c.drawText(s, (pw - fp.measureText(s)) / 2, phh - MARGIN / 2 - 4, fp)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally { doc.close() }
        return pages.size
    }

    private fun roman(n0: Int): String {
        var n = n0; val sb = StringBuilder()
        for ((v, s) in listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")) while (n >= v) { sb.append(s); n -= v }
        return sb.toString()
    }

    private fun table(t: Blk.Table, o: DocOpts, cw: Float, linkColor: Int, getY: () -> Float, setY: (Float) -> Unit, newPage: () -> Unit,
                      op: ((Canvas) -> Unit) -> Unit, bottom: Float, ensure: (Float) -> Unit) {
        val th = o.theme
        val n = maxOf(1, t.cols)
        val widths = DocPager.colWidths(DocMd.colWeights(t), cw, 36f)
        val pad = 4f
        val fs = if (n > 6) 8.5f else 9.5f
        val headFill = color(th.headFill)
        val zebra = color(th.zebra)
        val headText = color(th.headText)
        val grid = Paint().apply { color = 0xFFBFBFBF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f }
        fun cellPaint(head: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fs; color = if (head) headText else 0xFF1F1F1F.toInt(); typeface = Typeface.create(Typeface.SANS_SERIF, if (head) Typeface.BOLD else Typeface.NORMAL) }
        val numCol = BooleanArray(n) { c -> t.rows.isNotEmpty() && t.rows.count { DocMd.numeric(it.getOrNull(c) ?: "") } * 2 > t.rows.count { (it.getOrNull(c) ?: "").isNotBlank() } }
        fun rowLayouts(r: Int): List<StaticLayout> = (0 until n).map { c ->
            val al = when (t.alignOf(c)) { 'c' -> Layout.Alignment.ALIGN_CENTER; 'r' -> Layout.Alignment.ALIGN_OPPOSITE; 'l' -> Layout.Alignment.ALIGN_NORMAL; else -> if (numCol[c] && r >= 0) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL }
            layout(spans(DocMd.inline(t.cell(r, c)), linkColor), cellPaint(r < 0), (widths[c] - 2 * pad).toInt(), al)
        }
        val head = rowLayouts(-1)
        val headH = head.maxOf { it.height } + 2 * pad
        fun drawRow(ls: List<StaticLayout>, h: Float, fill: Int?) {
            val top = getY()
            op { c ->
                var x = MARGIN
                if (fill != null) c.drawRect(x, top, x + cw, top + h, Paint().apply { color = fill })
                for ((k, l) in ls.withIndex()) {
                    c.save(); c.translate(x + pad, top + pad); c.clipRect(0f, 0f, widths[k] - 2 * pad, h - 2 * pad + 1); l.draw(c); c.restore()
                    c.drawRect(x, top, x + widths[k], top + h, grid)
                    x += widths[k]
                }
            }
            setY(top + h)
        }
        ensure(headH + 40f)
        drawRow(head, headH, headFill)
        for (r in t.rows.indices) {
            val ls = rowLayouts(r)
            val h = minOf(ls.maxOf { it.height } + 2 * pad, bottom - MARGIN - headH)
            if (getY() + h > bottom) { newPage(); drawRow(head, headH, headFill) }
            drawRow(ls, h, if (r % 2 == 1) zebra else null)
        }
        setY(getY() + 10f)
    }
}
