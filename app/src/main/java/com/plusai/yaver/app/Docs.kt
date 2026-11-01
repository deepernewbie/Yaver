package com.plusai.yaver.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.ZipFile

/** Text out of documents, with nothing but the platform: PDF, Office (docx/xlsx/pptx), OpenDocument, HTML, text. */
object Docs {
    fun extract(f: File, pages: String, ocr: Boolean, cancel: Cancel?): String {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> pdf(f, pages, ocr, cancel)
            "docx", "docm", "dotx" -> docx(f)
            "xlsx", "xlsm" -> xlsx(f)
            "pptx" -> pptx(f)
            "odt", "ods", "odp" -> odf(f)
            "html", "htm" -> Html.read(f.readText(), "file:///").text
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Workspace.dataUrlForImage(Workspace.rel(f))?.let { Llm.vision(it, "Transcribe all text in this picture exactly, then describe anything else important.", cancel) } ?: "cannot open the picture"
            "doc", "xls", "ppt" -> throw IllegalArgumentException("old binary Office format (.$ext) — ask the user to save it as .${ext}x or PDF")
            else -> if (Workspace.isText(f)) f.readText() else throw IllegalArgumentException("cannot read .$ext files as text")
        }
    }

    // ---------------- Office Open XML ----------------
    private fun zipText(z: ZipFile, name: String): String? = z.getEntry(name)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

    private fun parser(xml: String): XmlPullParser {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(xml.reader())
        return p
    }

    private fun docx(f: File): String = ZipFile(f).use { z ->
        val xml = zipText(z, "word/document.xml") ?: throw IllegalArgumentException("not a Word document")
        val p = parser(xml)
        val sb = StringBuilder()
        val row = ArrayList<String>()
        var cell = StringBuilder()
        var inCell = 0
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            val n = p.name
            if (ev == XmlPullParser.START_TAG) when (n) {
                "t" -> { val t = p.nextText(); if (inCell > 0) cell.append(t) else sb.append(t) }
                "tab" -> if (inCell > 0) cell.append(' ') else sb.append('\t')
                "br", "cr" -> if (inCell > 0) cell.append(' ') else sb.append('\n')
                "tc" -> { inCell++; cell = StringBuilder() }
                "tr" -> row.clear()
            } else if (ev == XmlPullParser.END_TAG) when (n) {
                "p" -> if (inCell > 0) cell.append(' ') else sb.append('\n')
                "tc" -> { inCell--; row.add(cell.toString().trim()) }
                "tr" -> sb.append("| ").append(row.joinToString(" | ")).append(" |\n")
            }
        }
        sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun xlsx(f: File): String = ZipFile(f).use { z ->
        val shared = ArrayList<String>()
        zipText(z, "xl/sharedStrings.xml")?.let { xml ->
            val p = parser(xml)
            var cur: StringBuilder? = null
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG && p.name == "si") cur = StringBuilder()
                else if (ev == XmlPullParser.START_TAG && p.name == "t") cur?.append(p.nextText())
                else if (ev == XmlPullParser.END_TAG && p.name == "si") { shared.add(cur?.toString() ?: ""); cur = null }
            }
        }
        // sheet names in order
        val names = ArrayList<Pair<String, String>>() // name, rId
        zipText(z, "xl/workbook.xml")?.let { xml ->
            val p = parser(xml)
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                    var rid = ""
                    for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == "id") rid = p.getAttributeValue(i)
                    names.add(Pair(p.getAttributeValue(null, "name") ?: "Sheet", rid))
                }
            }
        }
        val rels = HashMap<String, String>()
        zipText(z, "xl/_rels/workbook.xml.rels")?.let { xml ->
            val p = parser(xml)
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") rels[p.getAttributeValue(null, "Id") ?: ""] = (p.getAttributeValue(null, "Target") ?: "").removePrefix("/xl/").removePrefix("xl/")
            }
        }
        val sheets = if (names.isNotEmpty()) names.map { (n, rid) -> Pair(n, "xl/" + (rels[rid] ?: "worksheets/sheet${names.indexOf(Pair(n, rid)) + 1}.xml")) }
        else z.entries().toList().map { it.name }.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sorted().map { Pair(it.substringAfterLast('/'), it) }
        val sb = StringBuilder()
        for ((name, path) in sheets) {
            val xml = zipText(z, path) ?: continue
            sb.append("## Sheet: ").append(name).append("\n")
            val p = parser(xml)
            val row = ArrayList<Pair<Int, String>>()
            var type = ""
            var ref = ""
            var value = StringBuilder()
            var rows = 0
            fun col(r: String): Int { var c = 0; for (ch in r) { if (!ch.isLetter()) break; c = c * 26 + (ch.uppercaseChar() - 'A' + 1) }; return c - 1 }
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "row" -> row.clear()
                    "c" -> { type = p.getAttributeValue(null, "t") ?: ""; ref = p.getAttributeValue(null, "r") ?: ""; value = StringBuilder() }
                    "v" -> value.append(p.nextText())
                    "t" -> value.append(p.nextText())
                } else if (ev == XmlPullParser.END_TAG) when (p.name) {
                    "c" -> {
                        var v = value.toString()
                        if (type == "s") v = v.toIntOrNull()?.let { shared.getOrNull(it) } ?: v
                        if (type == "b") v = if (v == "1") "TRUE" else "FALSE"
                        row.add(Pair(if (ref.isNotEmpty()) col(ref) else row.size, v))
                    }
                    "row" -> {
                        if (row.any { it.second.isNotBlank() }) {
                            val max = row.maxOf { it.first }
                            val cells = Array(max + 1) { "" }
                            for ((c, v) in row) if (c in cells.indices) cells[c] = v.replace("\n", " ")
                            sb.append(cells.joinToString(" | ")).append("\n")
                            rows++
                        }
                        if (rows > 3000) { sb.append("…(more rows)\n"); break }
                    }
                }
            }
            sb.append("\n")
        }
        sb.toString().trim()
    }

    private fun pptx(f: File): String = ZipFile(f).use { z ->
        val slides = z.entries().toList().map { it.name }.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
        val sb = StringBuilder()
        for ((i, s) in slides.withIndex()) {
            sb.append("## Slide ${i + 1}\n")
            val p = parser(zipText(z, s) ?: continue)
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG && p.name == "t") sb.append(p.nextText())
                else if (ev == XmlPullParser.END_TAG && p.name == "p") sb.append('\n')
            }
            sb.append('\n')
        }
        sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun odf(f: File): String = ZipFile(f).use { z ->
        val p = parser(zipText(z, "content.xml") ?: throw IllegalArgumentException("not an OpenDocument file"))
        val sb = StringBuilder()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.TEXT) sb.append(p.text)
            else if (ev == XmlPullParser.START_TAG && (p.name == "tab" || p.name == "s")) sb.append(' ')
            else if (ev == XmlPullParser.END_TAG && (p.name == "p" || p.name == "h")) sb.append('\n')
            else if (ev == XmlPullParser.END_TAG && p.name == "table-cell") sb.append(" | ")
            else if (ev == XmlPullParser.END_TAG && p.name == "table-row") sb.append('\n')
        }
        sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    // ---------------- PDF ----------------
    private fun pageSet(spec: String, count: Int): List<Int> {
        if (spec.isBlank()) return (1..count).toList()
        val out = LinkedHashSet<Int>()
        for (part in spec.split(',')) {
            val p = part.trim()
            if (p.contains('-')) { val a = p.substringBefore('-').trim().toIntOrNull() ?: 1; val b = p.substringAfter('-').trim().toIntOrNull() ?: count; for (i in a..minOf(b, count)) out.add(i) }
            else p.toIntOrNull()?.let { if (it in 1..count) out.add(it) }
        }
        return out.toList()
    }

    fun pdf(f: File, pages: String, ocr: Boolean, cancel: Cancel?): String {
        val texts = try { PdfText(f.readBytes()).pages() } catch (e: Throwable) { emptyList() }
        val count = if (texts.isNotEmpty()) texts.size else pageCount(f)
        val want = pageSet(pages, count)
        val sb = StringBuilder()
        var empty = 0
        for (n in want) {
            cancel?.check()
            val t = texts.getOrNull(n - 1)?.trim() ?: ""
            if (t.length < 20) empty++
            sb.append("## Page $n\n").append(t).append("\n\n")
        }
        val scanned = want.isNotEmpty() && empty * 2 > want.size
        if (scanned && (ocr || texts.isEmpty())) {
            if (!ocr) return "This PDF has no extractable text (probably scanned, $count pages). Call read_document again with ocr=true to read the pages with the vision model."
            return ocrPdf(f, want.take(15), cancel)
        }
        if (texts.isEmpty()) return "Could not extract text from this PDF ($count pages). Try ocr=true."
        return sb.toString().trim() + if (scanned) "\n\n(Some pages have little or no text — they may be scanned. Use ocr=true to read them with the vision model.)" else ""
    }

    private fun pageCount(f: File): Int = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    } catch (e: Throwable) { 0 }

    private fun ocrPdf(f: File, pages: List<Int>, cancel: Cancel?): String {
        val sb = StringBuilder()
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                for (n in pages) {
                    cancel?.check()
                    if (n < 1 || n > r.pageCount) continue
                    r.openPage(n - 1).use { pg ->
                        val scale = 1600f / maxOf(pg.width, pg.height)
                        val bmp = Bitmap.createBitmap((pg.width * scale).toInt().coerceAtLeast(1), (pg.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        val t = try { Llm.vision(url, "Transcribe all text on this document page exactly, keeping the reading order. Render tables as markdown tables. Output only the content.", cancel) } catch (e: Exception) { "(page could not be read: ${e.message})" }
                        sb.append("## Page $n\n").append(t).append("\n\n")
                    }
                }
            }
        }
        return sb.toString().trim()
    }
}

/**
 * A small PDF text extractor: objects, object streams, Flate, page tree, content-stream text operators and
 * ToUnicode maps. Enough for normal text PDFs (reports, invoices, articles).
 */
class PdfText(private val data: ByteArray) {
    private val s = String(data, Charsets.ISO_8859_1)
    private val objs = HashMap<Int, String>()          // object number → body text (latin1)
    private val streams = HashMap<Int, ByteArray>()    // object number → decoded stream
    private val cmapCache = HashMap<Int, FontInfo>()

    class FontInfo(val cmap: Map<Int, String>?, val twoByte: Boolean)

    init {
        if (Regex("/Encrypt\\s").containsMatchIn(s.substring(maxOf(0, s.length - 200000)))) throw IllegalArgumentException("the PDF is encrypted")
        val re = Regex("(\\d+)\\s+(\\d+)\\s+obj\\b")
        var m = re.find(s)
        while (m != null) {
            val num = m.groupValues[1].toInt()
            val start = m.range.last + 1
            val streamAt = s.indexOf("stream", start)
            val endObj = s.indexOf("endobj", start)
            if (endObj < 0) break
            if (streamAt in start until endObj) {
                val dict = s.substring(start, streamAt)
                var ds = streamAt + 6
                if (ds < s.length && s[ds] == '\r') ds++
                if (ds < s.length && s[ds] == '\n') ds++
                val len = Regex("/Length\\s+(\\d+)(?!\\s+\\d+\\s+R)").find(dict)?.groupValues?.get(1)?.toIntOrNull()
                var de = if (len != null && ds + len <= s.length && s.startsWith("endstream", skipWs(ds + len))) ds + len else s.indexOf("endstream", ds)
                if (de < 0) de = endObj
                objs[num] = dict
                val raw = data.copyOfRange(ds, maxOf(ds, de))
                streams[num] = decode(dict, raw)
                val realEnd = s.indexOf("endobj", de)
                m = re.find(s, if (realEnd > 0) realEnd else de)
            } else {
                objs[num] = s.substring(start, endObj)
                m = re.find(s, endObj)
            }
        }
        // compressed object streams
        for ((num, dict) in objs.entries.toList()) {
            if (!dict.contains("/ObjStm")) continue
            val body = streams[num] ?: continue
            val n = Regex("/N\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val first = Regex("/First\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val bs = String(body, Charsets.ISO_8859_1)
            val head = bs.substring(0, minOf(first, bs.length)).trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            for (i in 0 until minOf(n, head.size / 2)) {
                val on = head[i * 2]
                val off = first + head[i * 2 + 1]
                val next = if (i + 1 < head.size / 2) first + head[(i + 1) * 2 + 1] else bs.length
                if (off <= bs.length && !objs.containsKey(on)) objs[on] = bs.substring(off, minOf(next, bs.length))
            }
        }
    }

    private fun skipWs(i: Int): Int { var j = i; while (j < s.length && s[j].isWhitespace()) j++; return j }

    private fun decode(dict: String, raw: ByteArray): ByteArray {
        val fm = Regex("/Filter\\s*(\\[[^\\]]*]|/[A-Za-z0-9]+)").find(dict) ?: return raw
        val filters = Regex("/([A-Za-z0-9]+)").findAll(fm.groupValues[1]).map { it.groupValues[1] }.toList()
        var d = raw
        for (f in filters) {
            d = when (f) {
                "FlateDecode", "Fl" -> inflate(d) ?: return d
                "ASCII85Decode", "A85" -> a85(d)
                "ASCIIHexDecode", "AHx" -> ahx(d)
                else -> return d
            }
        }
        if (filters.any { it == "FlateDecode" || it == "Fl" } && Regex("/Predictor\\s+1[0-5]").containsMatchIn(dict))
            d = unpredict(d, Regex("/Columns\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
        return d
    }

    private fun inflate(raw: ByteArray): ByteArray? = try {
        val inf = Inflater()
        inf.setInput(raw)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 50_000_000) break
        }
        inf.end()
        out.toByteArray()
    } catch (e: Exception) { null }

    private fun a85(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val tuple = IntArray(5)
        var n = 0
        var i = 0
        val s = String(d, Charsets.ISO_8859_1)
        var start = s.indexOf("<~").let { if (it >= 0) it + 2 else 0 }
        i = start
        while (i < s.length) {
            val c = s[i]
            i++
            if (c == '~') break
            if (c.isWhitespace()) continue
            if (c == 'z' && n == 0) { out.write(byteArrayOf(0, 0, 0, 0)); continue }
            if (c < '!' || c > 'u') continue
            tuple[n++] = c - '!'
            if (n == 5) {
                var v = 0L
                for (k in 0 until 5) v = v * 85 + tuple[k]
                out.write(((v shr 24) and 0xff).toInt()); out.write(((v shr 16) and 0xff).toInt()); out.write(((v shr 8) and 0xff).toInt()); out.write((v and 0xff).toInt())
                n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) tuple[k] = 84
            var v = 0L
            for (k in 0 until 5) v = v * 85 + tuple[k]
            for (k in 0 until n - 1) out.write(((v shr (24 - 8 * k)) and 0xff).toInt())
        }
        return out.toByteArray()
    }

    private fun ahx(d: ByteArray): ByteArray {
        val h = String(d, Charsets.ISO_8859_1).substringBefore('>').filter { it.isLetterOrDigit() }
        val s = if (h.length % 2 == 1) h + "0" else h
        return ByteArray(s.length / 2) { k -> s.substring(k * 2, k * 2 + 2).toIntOrNull(16)?.toByte() ?: 0 }
    }

    private fun unpredict(d: ByteArray, cols: Int): ByteArray {
        val rowLen = cols + 1
        val out = ByteArrayOutputStream()
        val prev = ByteArray(cols)
        var i = 0
        while (i + rowLen <= d.size) {
            val type = d[i].toInt()
            val row = d.copyOfRange(i + 1, i + rowLen)
            if (type == 2) for (k in 0 until cols) row[k] = (row[k] + prev[k]).toByte()
            out.write(row); row.copyInto(prev)
            i += rowLen
        }
        return out.toByteArray()
    }

    private fun ref(v: String?): Int? = v?.let { Regex("^\\s*(\\d+)\\s+\\d+\\s+R").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun resolveDict(text: String): String {
        val r = ref(text)
        return if (r != null) objs[r] ?: "" else text
    }

    /** Value after /Key in a dictionary text: a reference, a <<dict>>, an [array] or a token. */
    private fun valueOf(dict: String, key: String): String? {
        val m = Regex("/$key(?![A-Za-z0-9])\\s*").find(dict) ?: return null
        var i = m.range.last + 1
        if (i >= dict.length) return null
        if (dict.startsWith("<<", i)) { var depth = 0; var j = i; while (j < dict.length - 1) { if (dict.startsWith("<<", j)) { depth++; j += 2; continue }; if (dict.startsWith(">>", j)) { depth--; j += 2; if (depth == 0) return dict.substring(i, j); continue }; j++ }; return dict.substring(i) }
        if (dict[i] == '[') { var depth = 0; var j = i; while (j < dict.length) { if (dict[j] == '[') depth++; if (dict[j] == ']') { depth--; if (depth == 0) return dict.substring(i, j + 1) }; j++ }; return dict.substring(i) }
        Regex("^\\d+\\s+\\d+\\s+R").find(dict.substring(i))?.let { return it.value }
        val e = Regex("[\\s/\\]>]").find(dict, i + 1)?.range?.first ?: dict.length
        return dict.substring(i, e)
    }

    fun pages(): List<String> {
        val root = ref(Regex("/Root\\s+(\\d+\\s+\\d+\\s+R)").findAll(s).lastOrNull()?.groupValues?.get(1))
        val pageObjs = ArrayList<Int>()
        val catalog = root?.let { objs[it] }
        val pagesRef = catalog?.let { ref(valueOf(it, "Pages")) }
        if (pagesRef != null) walk(pagesRef, pageObjs, HashSet(), null)
        if (pageObjs.isEmpty()) objs.entries.filter { Regex("/Type\\s*/Page(?!s)").containsMatchIn(it.value) }.sortedBy { it.key }.forEach { pageObjs.add(it.key) }
        return pageObjs.map { pageText(it) }
    }

    private val inheritedRes = HashMap<Int, String>()

    private fun walk(n: Int, out: MutableList<Int>, seen: MutableSet<Int>, res: String?) {
        if (!seen.add(n) || seen.size > 20000) return
        val d = objs[n] ?: return
        val r = valueOf(d, "Resources") ?: res
        if (Regex("/Type\\s*/Pages").containsMatchIn(d)) {
            val kids = valueOf(d, "Kids") ?: return
            for (m in Regex("(\\d+)\\s+\\d+\\s+R").findAll(kids)) walk(m.groupValues[1].toInt(), out, seen, r)
        } else {
            out.add(n)
            if (r != null) inheritedRes[n] = r
        }
    }

    private fun fontsOf(page: Int): Map<String, FontInfo> {
        val res = resolveDict(valueOf(objs[page] ?: "", "Resources") ?: inheritedRes[page] ?: return emptyMap())
        val fontDict = resolveDict(valueOf(res, "Font") ?: return emptyMap())
        val out = HashMap<String, FontInfo>()
        for (m in Regex("/([^\\s/<>\\[\\]()]+)\\s+(\\d+)\\s+\\d+\\s+R").findAll(fontDict)) {
            val fo = m.groupValues[2].toInt()
            out[m.groupValues[1]] = cmapFor(fo)
        }
        return out
    }

    private fun cmapFor(fontObj: Int): FontInfo {
        cmapCache[fontObj]?.let { return it }
        val fd = objs[fontObj] ?: ""
        val two = fd.contains("/Type0") || fd.contains("Identity-H") || fd.contains("Identity-V")
        val tu = ref(valueOf(fd, "ToUnicode"))
        val cm = tu?.let { streams[it] }?.let { parseCMap(String(it, Charsets.ISO_8859_1)) }
        val fi = FontInfo(cm, two)
        cmapCache[fontObj] = fi
        return fi
    }

    private fun hexToStr(h: String): String {
        val sb = StringBuilder()
        val clean = h.filter { !it.isWhitespace() }
        var i = 0
        while (i + 4 <= clean.length) { sb.append(clean.substring(i, i + 4).toInt(16).toChar()); i += 4 }
        if (clean.length == 2) sb.append(clean.toInt(16).toChar())
        return sb.toString()
    }

    private fun parseCMap(c: String): Map<Int, String> {
        val map = HashMap<Int, String>()
        for (blk in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(c)) {
            for (m in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f\\s]*)>").findAll(blk.groupValues[1])) map[m.groupValues[1].toInt(16)] = hexToStr(m.groupValues[2])
        }
        for (blk in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(c)) {
            for (m in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f\\s]*>|\\[[^\\]]*])").findAll(blk.groupValues[1])) {
                val a = m.groupValues[1].toInt(16); val b = m.groupValues[2].toInt(16)
                val d = m.groupValues[3]
                if (b - a > 65535) continue
                if (d.startsWith("[")) {
                    val items = Regex("<([0-9A-Fa-f\\s]*)>").findAll(d).map { hexToStr(it.groupValues[1]) }.toList()
                    for (k in a..b) items.getOrNull(k - a)?.let { map[k] = it }
                } else {
                    val base = d.trim('<', '>').filter { !it.isWhitespace() }
                    if (base.isEmpty()) continue
                    val start = base.takeLast(4).toInt(16)
                    val prefix = hexToStr(base.dropLast(4).let { if (it.length % 4 == 0) it else "" })
                    for (k in a..b) map[k] = prefix + (start + k - a).toChar()
                }
            }
        }
        return map
    }

    private fun contentBytes(page: Int): ByteArray {
        val d = objs[page] ?: return ByteArray(0)
        val c = valueOf(d, "Contents") ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        for (m in Regex("(\\d+)\\s+\\d+\\s+R").findAll(c)) {
            val n = m.groupValues[1].toInt()
            val st = streams[n]
            if (st != null) { out.write(st); out.write('\n'.code) }
            else objs[n]?.let { arr -> for (m2 in Regex("(\\d+)\\s+\\d+\\s+R").findAll(arr)) streams[m2.groupValues[1].toInt()]?.let { out.write(it); out.write('\n'.code) } }
        }
        return out.toByteArray()
    }

    private fun decodeString(bytes: ByteArray, fi: FontInfo?): String {
        val cmap = fi?.cmap
        if (fi != null && fi.twoByte && (cmap == null || cmap.isEmpty())) {
            // no map: Identity encodings often equal Unicode for simple fonts; better than nothing
            val sb = StringBuilder(); var i = 0
            while (i + 1 < bytes.size) { val code = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff); if (code >= 32) sb.append(code.toChar()); i += 2 }
            return sb.toString()
        }
        if (cmap != null && cmap.isNotEmpty()) {
            val two = fi.twoByte || cmap.keys.any { it > 255 }
            val sb = StringBuilder()
            if (two) {
                var i = 0
                while (i + 1 < bytes.size) { val code = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff); sb.append(cmap[code] ?: ""); i += 2 }
            } else for (b in bytes) { val code = b.toInt() and 0xff; sb.append(cmap[code] ?: code.toChar().toString()) }
            return sb.toString()
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val sb = StringBuilder()
        for (b in bytes) sb.append(WIN[b.toInt() and 0xff])
        return sb.toString()
    }

    private fun pageText(page: Int): String {
        val fonts = try { fontsOf(page) } catch (e: Exception) { emptyMap() }
        val c = contentBytes(page)
        val sb = StringBuilder()
        var font: FontInfo? = null
        var lastY = Float.NaN
        val operands = ArrayList<Any>()
        var i = 0
        val n = c.size
        fun nlIfNeeded() { if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n') }
        while (i < n) {
            val ch = c[i].toInt().toChar()
            when {
                ch.isWhitespace() -> i++
                ch == '%' -> { while (i < n && c[i].toInt() != 10 && c[i].toInt() != 13) i++ }
                ch == '(' -> {
                    val out = ByteArrayOutputStream(); var depth = 1; i++
                    while (i < n && depth > 0) {
                        var b = c[i].toInt() and 0xff
                        if (b == '\\'.code && i + 1 < n) {
                            i++
                            val e = c[i].toInt().toChar()
                            when (e) {
                                'n' -> out.write(10); 'r' -> out.write(13); 't' -> out.write(9); 'b' -> out.write(8); 'f' -> out.write(12)
                                '(', ')', '\\' -> out.write(e.code)
                                '\r', '\n' -> {}
                                in '0'..'7' -> { var v = e - '0'; var k = 0; while (k < 2 && i + 1 < n && c[i + 1].toInt().toChar() in '0'..'7') { i++; v = v * 8 + (c[i].toInt().toChar() - '0'); k++ }; out.write(v and 0xff) }
                                else -> out.write(e.code)
                            }
                            i++; continue
                        }
                        if (b == '('.code) depth++
                        if (b == ')'.code) { depth--; if (depth == 0) { i++; break } }
                        out.write(b); i++
                        b = 0
                    }
                    operands.add(out.toByteArray())
                }
                ch == '<' && i + 1 < n && c[i + 1].toInt().toChar() == '<' -> { // inline dict: skip
                    var depth = 0
                    while (i < n - 1) { if (c[i].toInt().toChar() == '<' && c[i + 1].toInt().toChar() == '<') { depth++; i += 2; continue }; if (c[i].toInt().toChar() == '>' && c[i + 1].toInt().toChar() == '>') { depth--; i += 2; if (depth == 0) break; continue }; i++ }
                }
                ch == '<' -> {
                    val e = (i + 1 until n).firstOrNull { c[it].toInt().toChar() == '>' } ?: n
                    var hex = String(c, i + 1, e - i - 1, Charsets.ISO_8859_1).filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                    if (hex.length % 2 == 1) hex += "0"
                    operands.add(ByteArray(hex.length / 2) { k -> hex.substring(k * 2, k * 2 + 2).toInt(16).toByte() })
                    i = e + 1
                }
                ch == '[' -> { operands.add("["); i++ }
                ch == ']' -> {
                    val arr = ArrayList<Any>()
                    while (operands.isNotEmpty() && operands.last() != "[") arr.add(0, operands.removeAt(operands.size - 1))
                    if (operands.isNotEmpty()) operands.removeAt(operands.size - 1)
                    operands.add(arr); i++
                }
                ch == '/' -> { val st = i; i++; while (i < n && !isDelim(c[i])) i++; operands.add(String(c, st, i - st, Charsets.ISO_8859_1)) }
                ch.isDigit() || ch == '-' || ch == '+' || ch == '.' -> {
                    val st = i; i++
                    while (i < n && (c[i].toInt().toChar().isDigit() || c[i].toInt().toChar() == '.')) i++
                    operands.add(String(c, st, i - st, Charsets.ISO_8859_1).toFloatOrNull() ?: 0f)
                }
                else -> {
                    val st = i
                    while (i < n && !isDelim(c[i])) i++
                    if (i == st) { i++; continue }
                    val op = String(c, st, i - st, Charsets.ISO_8859_1)
                    when (op) {
                        "BI" -> { // inline image: skip to EI
                            val idx = String(c, i, minOf(n - i, 2_000_000), Charsets.ISO_8859_1).indexOf("EI")
                            i = if (idx < 0) n else i + idx + 2
                        }
                        "Tf" -> { val name = operands.getOrNull(operands.size - 2) as? String; font = name?.let { fonts[it.removePrefix("/")] } }
                        "Tj", "'", "\"" -> {
                            if (op != "Tj") nlIfNeeded()
                            (operands.lastOrNull() as? ByteArray)?.let { sb.append(decodeString(it, font)) }
                        }
                        "TJ" -> {
                            val arr = operands.lastOrNull() as? List<*>
                            if (arr != null) for (x in arr) {
                                if (x is ByteArray) sb.append(decodeString(x, font))
                                else if (x is Float && x < -180 && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                            }
                        }
                        "Td", "TD" -> {
                            val y = operands.getOrNull(operands.size - 1) as? Float ?: 0f
                            val x = operands.getOrNull(operands.size - 2) as? Float ?: 0f
                            if (kotlin.math.abs(y) > 1f) nlIfNeeded() else if (x > 1f && sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') sb.append(' ')
                        }
                        "T*" -> nlIfNeeded()
                        "Tm" -> {
                            val y = operands.getOrNull(operands.size - 1) as? Float
                            if (y != null) { if (!lastY.isNaN() && kotlin.math.abs(y - lastY) > 1f) nlIfNeeded() else if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') sb.append(' '); lastY = y }
                        }
                        "ET" -> if (sb.isNotEmpty() && sb.last() != '\n' && sb.last() != ' ') sb.append(' ')
                    }
                    operands.clear()
                }
            }
        }
        return sb.toString().replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun isDelim(b: Byte): Boolean { val c = b.toInt().toChar(); return c.isWhitespace() || c in "()<>[]{}/%" }

    companion object {
        /** WinAnsiEncoding (close enough for PDFs without ToUnicode). */
        val WIN: CharArray = CharArray(256) { it.toChar() }.also { w ->
            val hi = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f\u0090‘’“”•–—˜™š›œ\u009džŸ"
            for (k in hi.indices) w[0x80 + k] = hi[k]
        }
    }
}
