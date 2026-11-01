package com.plusai.yaver.app

/** A forgiving HTML parser (tag soup → tree) with just enough querying for search results and reading pages. */
class HNode(val tag: String, val attrs: Map<String, String>, val parent: HNode?) {
    val kids = ArrayList<Any>() // HNode or String
    fun attr(n: String) = attrs[n] ?: ""
    val classes: Set<String> get() = attr("class").split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    fun text(): String {
        val sb = StringBuilder()
        fun walk(n: HNode) { for (k in n.kids) if (k is String) sb.append(k) else if (k is HNode && k.tag != "script" && k.tag != "style") walk(k) }
        walk(this)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun all(pred: (HNode) -> Boolean): List<HNode> {
        val out = ArrayList<HNode>()
        fun walk(n: HNode) { for (k in n.kids) if (k is HNode) { if (pred(k)) out.add(k); walk(k) } }
        walk(this)
        return out
    }

    fun first(pred: (HNode) -> Boolean): HNode? {
        for (k in kids) if (k is HNode) { if (pred(k)) return k; k.first(pred)?.let { return it } }
        return null
    }

    fun byTag(t: String) = all { it.tag == t }
    fun byClass(c: String) = all { c in it.classes }
}

object Html {
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val RAW = setOf("script", "style", "textarea", "title", "noscript")
    private val AUTO_CLOSE = mapOf("p" to setOf("p", "div", "ul", "ol", "table", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "section", "article"), "li" to setOf("li"), "tr" to setOf("tr"), "td" to setOf("td", "th", "tr"), "th" to setOf("td", "th", "tr"), "option" to setOf("option"), "dt" to setOf("dt", "dd"), "dd" to setOf("dt", "dd"))

    fun decode(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } ?: m.value
                else -> when (e) {
                    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "; "ndash" -> "–"; "mdash" -> "—"
                    "hellip" -> "…"; "laquo" -> "«"; "raquo" -> "»"; "rsquo" -> "’"; "lsquo" -> "‘"; "rdquo" -> "”"; "ldquo" -> "“"; "copy" -> "©"; "reg" -> "®"; "euro" -> "€"; "middot" -> "·"; "bull" -> "•"; "times" -> "×"
                    else -> m.value
                }
            }
        }
    }

    fun parse(html: String): HNode {
        val root = HNode("#root", emptyMap(), null)
        var cur = root
        var i = 0
        val n = html.length
        val tagM = java.util.regex.Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9:-]*)([^>]*)>").matcher(html)
        val attrRe = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?")
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) { cur.kids.add(decode(html.substring(i))); break }
            if (lt > i) cur.kids.add(decode(html.substring(i, lt)))
            if (html.startsWith("<!--", lt)) { val e = html.indexOf("-->", lt + 4); i = if (e < 0) n else e + 3; continue }
            if (html.startsWith("<!", lt) || html.startsWith("<?", lt)) { val e = html.indexOf('>', lt); i = if (e < 0) n else e + 1; continue }
            tagM.region(lt, minOf(n, lt + 4000))
            if (!tagM.lookingAt()) { cur.kids.add("<"); i = lt + 1; continue }
            i = tagM.end()
            val closing = tagM.group(1) == "/"
            val tag = tagM.group(2).lowercase()
            val rawAttrs = tagM.group(3) ?: ""
            if (closing) {
                var p: HNode? = cur
                while (p != null && p.tag != tag) p = p.parent
                if (p != null && p.parent != null) cur = p.parent!!
                continue
            }
            val attrs = HashMap<String, String>()
            for (am in attrRe.findAll(rawAttrs)) {
                var v = am.groupValues[2]
                if (v.length >= 2 && (v[0] == '"' || v[0] == '\'')) v = v.substring(1, v.length - 1)
                attrs[am.groupValues[1].lowercase()] = decode(v)
            }
            if (AUTO_CLOSE.containsKey(tag)) {
                // an open <p>/<li>/<td>… is closed by a sibling of the same kind
                var p: HNode? = cur
                while (p != null && p.tag != "#root") {
                    if (p.tag == tag || (tag in setOf("td", "th") && p.tag in setOf("td", "th"))) { cur = p.parent ?: root; break }
                    if (p.tag in setOf("ul", "ol", "table", "tbody", "div", "body")) break
                    p = p.parent
                }
            }
            if (tag == "p" || tag in setOf("div", "ul", "ol", "table", "h1", "h2", "h3", "pre", "blockquote")) {
                if (cur.tag == "p" && tag != "p") cur = cur.parent ?: root
            }
            val node = HNode(tag, attrs, cur)
            cur.kids.add(node)
            val selfClose = rawAttrs.trimEnd().endsWith("/")
            if (tag in VOID || selfClose) continue
            if (tag in RAW) {
                val end = html.indexOf("</$tag", i, ignoreCase = true)
                val e = if (end < 0) n else end
                node.kids.add(if (tag == "title" || tag == "textarea") decode(html.substring(i, e)) else html.substring(i, e))
                i = if (end < 0) n else (html.indexOf('>', end).let { if (it < 0) n else it + 1 })
                continue
            }
            cur = node
        }
        return root
    }

    fun title(root: HNode): String = root.first { it.tag == "title" }?.text()
        ?: root.first { it.tag == "meta" && it.attr("property") == "og:title" }?.attr("content") ?: ""

    class Page(val title: String, val text: String, val links: List<Pair<String, String>>)

    /** Readable text of a page in Markdown-ish form, with numbered links. */
    fun read(html: String, baseUrl: String): Page {
        val root = parse(html)
        val title = title(root)
        val skip = setOf("script", "style", "noscript", "svg", "nav", "footer", "header", "aside", "form", "iframe", "button", "select", "template", "canvas")
        val main = root.first { it.tag == "article" } ?: root.first { it.tag == "main" || it.attr("role") == "main" } ?: root.first { it.tag == "body" } ?: root
        val links = ArrayList<Pair<String, String>>()
        val sb = StringBuilder()
        fun nl(k: Int = 1) { var t = 0; var j = sb.length - 1; while (j >= 0 && sb[j] == '\n') { t++; j-- }; repeat(maxOf(0, k - t)) { sb.append('\n') } }
        fun walk(nd: HNode, inPre: Boolean) {
            for (k in nd.kids) {
                if (k is String) {
                    if (inPre) sb.append(k) else {
                        val t = k.replace(Regex("\\s+"), " ")
                        if (t.isNotBlank() || (t == " " && sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n')) sb.append(t)
                    }
                    continue
                }
                val e = k as HNode
                if (e.tag in skip) continue
                if (e.attr("hidden").isNotEmpty() || e.attr("aria-hidden") == "true" || e.attr("style").replace(" ", "").contains("display:none")) continue
                when (e.tag) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> { nl(2); sb.append("#".repeat(e.tag[1] - '0')).append(' '); walk(e, false); nl(2) }
                    "p", "div", "section", "article", "main", "blockquote", "figure", "figcaption", "dl", "address" -> { nl(); walk(e, inPre); nl() }
                    "br" -> sb.append('\n')
                    "hr" -> { nl(); sb.append("---"); nl() }
                    "li" -> { nl(); sb.append("- "); walk(e, false); nl() }
                    "tr" -> { nl(); sb.append("| "); walk(e, false); if (sb.endsWith(" | ")) sb.setLength(sb.length - 3); sb.append(" |"); nl() }
                    "td", "th" -> { walk(e, false); sb.append(" | ") }
                    "pre" -> { nl(); sb.append("```\n"); walk(e, true); nl(); sb.append("```"); nl() }
                    "code" -> if (inPre) walk(e, true) else { sb.append('`'); walk(e, false); sb.append('`') }
                    "strong", "b" -> { sb.append("**"); walk(e, false); sb.append("**") }
                    "img" -> { val alt = e.attr("alt"); if (alt.isNotBlank()) sb.append("[image: $alt]") }
                    "a" -> {
                        val href = e.attr("href")
                        val text = e.text()
                        if (href.isNotEmpty() && !href.startsWith("javascript:") && !href.startsWith("#") && text.isNotBlank()) {
                            val abs = try { java.net.URL(java.net.URL(baseUrl), href).toString() } catch (x: Exception) { href }
                            links.add(Pair(text.take(100), abs))
                            sb.append(text).append(" [").append(links.size).append("]")
                        } else walk(e, false)
                    }
                    else -> walk(e, inPre)
                }
            }
        }
        walk(main, false)
        val text = sb.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").replace(Regex("(\\| ){2,}"), "| ").trim()
        return Page(title.trim(), text, links)
    }

    /** Plain text from an HTML e-mail body or snippet. */
    fun toText(html: String): String = read(html, "https://example.invalid/").text.replace(Regex(" \\[\\d+]"), "")
}
