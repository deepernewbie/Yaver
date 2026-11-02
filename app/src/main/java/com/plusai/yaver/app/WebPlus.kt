package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder

/**
 * The original's web tools that the native WebTools lacks: web_extract, fetch, download_file, page_images, and the
 * old text-browser tools (browser_navigate / snapshot / click / type / back / find / download, browser_use) as thin
 * aliases over the native browser tool. Requests go straight from the phone, so no CORS proxies are needed.
 */
object WebPlus {
    private fun headers() = mapOf(
        "User-Agent" to Http.UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to if (Y.tr) "tr-TR,tr;q=0.9,en;q=0.8" else "en-US,en;q=0.9,tr;q=0.6"
    )

    fun fmtBytes(n: Long): String = if (n < 1024) "$n B" else if (n < 1048576) String.format(java.util.Locale.US, "%.1f KB", n / 1024.0) else String.format(java.util.Locale.US, "%.2f MB", n / 1048576.0)

    private fun absUrl(base: String, ref: String): String? = try { URL(URL(base), ref).toString() } catch (e: Exception) { null }

    // ------------------------------------------------------------ readable text (markdown-ish, links as [text](url))
    class Readable(val title: String, val text: String)

    fun readableText(html: String, url: String): Readable {
        val root = Html.parse(html)
        val title = (root.first { it.tag == "title" }?.text() ?: "").trim()
        val drop = setOf("script", "style", "noscript", "svg", "nav", "footer", "iframe", "form")
        fun inHeader(n: HNode): Boolean { var p = n.parent; while (p != null) { if (p.tag == "header") return true; p = p.parent }; return false }
        val main = root.first { it.tag == "article" } ?: root.first { it.tag == "main" } ?: root.first { it.tag == "body" } ?: root
        val out = StringBuilder()
        fun walk(n: HNode) {
            for (c in n.kids) {
                if (c is String) { out.append(c); continue }
                val e = c as HNode
                val t = e.tag
                if (t in drop || (e.attr("role") == "navigation" && inHeader(e))) continue
                if (Regex("^h[1-6]$").matches(t)) { out.append("\n\n").append("#".repeat(t[1] - '0')).append(' ').append(e.text().trim()).append("\n"); continue }
                if (t == "a" && e.attr("href").isNotEmpty()) { val hf = absUrl(url, e.attr("href")) ?: e.attr("href"); out.append(" [").append(e.text().trim()).append("](").append(hf).append(") "); continue }
                if (Regex("^(p|div|li|tr|br|section|pre|blockquote|table)$").matches(t)) out.append("\n")
                if (t == "li") out.append("- ")
                walk(e)
            }
        }
        walk(main)
        val text = out.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n").trim()
        return Readable(title, text)
    }

    /** Drops navigation: runs of link-only lines (menus, footers, tickers), empty bullets and images; keeps the content. */
    fun declutter(text: String): String {
        val lines = text.split("\n")
        val linkOnly = Regex("^\\s*(?:[-*•]\\s*)?(?:!?\\[[^\\]]*]\\([^)]*\\)\\s*)+$")
        val filler = Regex("^\\s*(?:[-*•|]\\s*)?$")
        val image = Regex("^\\s*!\\[[^\\]]*]\\([^)]*\\)\\s*$")
        val out = ArrayList<String>(); var i = 0; var dropped = 0
        while (i < lines.size) {
            if (linkOnly.matches(lines[i])) {
                var j = i; var n = 0
                while (j < lines.size && (linkOnly.matches(lines[j]) || filler.matches(lines[j]))) { if (linkOnly.matches(lines[j])) n++; j++ }
                if (n >= 4) { dropped += n; i = j; continue }
            }
            if (image.matches(lines[i])) { i++; continue }
            out.add(lines[i]); i++
        }
        val t = out.joinToString("\n").replace(Regex("(?m)^[ \\t]*[-*•][ \\t]*$"), "").replace(Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+"), "\n\n").trim()
        return if (dropped > 0) "$t\n\n[$dropped navigation links removed]" else t
    }

    // ------------------------------------------------------------ binary downloads
    fun sniffMime(b: ByteArray): String? {
        if (b.size < 4) return null
        fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xff else -1
        if (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4e && u(3) == 0x47) return "image/png"
        if (u(0) == 0xff && u(1) == 0xd8 && u(2) == 0xff) return "image/jpeg"
        if (u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46) return "image/gif"
        if (u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 && u(8) == 0x57 && u(9) == 0x45) return "image/webp"
        if (u(0) == 0x42 && u(1) == 0x4d) return "image/bmp"
        if (u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) { val brand = String(b, 8, minOf(4, maxOf(0, b.size - 8)), Charsets.ISO_8859_1); return if (Regex("^(avif|avis)").containsMatchIn(brand)) "image/avif" else "video/mp4" }
        if (u(0) == 0x25 && u(1) == 0x50 && u(2) == 0x44 && u(3) == 0x46) return "application/pdf"
        if (u(0) == 0x50 && u(1) == 0x4b) return "application/zip"
        if (u(0) == 0x1f && u(1) == 0x8b) return "application/gzip"
        if ((u(0) == 0x49 && u(1) == 0x44 && u(2) == 0x33) || (u(0) == 0xff && (u(1) and 0xe0) == 0xe0)) return "audio/mpeg"
        val head = String(b, 0, minOf(300, b.size), Charsets.UTF_8).trim().lowercase()
        if (head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))) return "image/svg+xml"
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) return "text/html"
        return null
    }

    private val EXT_OF = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp", "image/bmp" to "bmp", "image/avif" to "avif", "image/svg+xml" to "svg", "application/pdf" to "pdf", "application/zip" to "zip", "application/gzip" to "gz", "audio/mpeg" to "mp3", "video/mp4" to "mp4", "text/html" to "html", "application/json" to "json", "text/csv" to "csv", "text/plain" to "txt")

    class Bin(val bytes: ByteArray, val ct: String, val via: String)

    /** Downloads bytes straight from the phone; HTML error pages are rejected when a file was expected. */
    fun fetchBinary(url: String, cancel: Cancel?): Bin {
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) throw IllegalArgumentException("Only http(s) URLs can be downloaded: $url")
        val wantsFile = !Regex("\\.(html?|php|aspx?)(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(try { URL(url).path } catch (e: Exception) { url })
        val errors = ArrayList<String>()
        try {
            val r = Http.request(url, headers = mapOf("User-Agent" to Http.UA, "Accept" to "*/*"), timeoutMs = 120000, maxBytes = 300 * 1024 * 1024, cancel = cancel)
            if (!r.ok) errors.add("direct: HTTP ${r.code}")
            else if (r.body.isEmpty()) errors.add("direct: empty response")
            else {
                var ct = r.contentType.split(";")[0].trim()
                val sniff = sniffMime(r.body)
                if (wantsFile && (sniff == "text/html" || (sniff == null && ct.contains("text/html")))) errors.add("direct: returned a web page instead of the file")
                else {
                    if (sniff != null && (ct.isEmpty() || Regex("octet-stream|text/plain|text/html").containsMatchIn(ct))) ct = sniff
                    return Bin(r.body, ct.ifEmpty { "application/octet-stream" }, "direct")
                }
            }
        } catch (e: CancelledException) { throw e } catch (e: Throwable) { errors.add("direct: ${e.message ?: e}") }
        throw RuntimeException("Could not download $url. Tried ${errors.joinToString("; ")}. The site may block automatic downloads; ask the user to download it and share it with yaver.")
    }

    class DL(val path: String, val bytes: Int, val ct: String, val via: String)

    /** Downloads [url] to [path] (a /workspace path or folder; default /workspace/downloads/<name>). */
    fun downloadTo(url: String, path: String?, cancel: Cancel?): DL {
        val r = fetchBinary(url, cancel)
        val name = (try { URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8") } catch (e: Exception) { "" }).ifEmpty { "download" }
        var p = VPath.normTool(path?.takeIf { it.isNotBlank() } ?: "${VPath.WS}/downloads/$name")
        if (VPath.isDir(p)) p += "/$name"
        if (!Regex("\\.[a-z0-9]{2,5}$", RegexOption.IGNORE_CASE).containsMatchIn(VPath.base(p))) EXT_OF[r.ct]?.let { p += ".$it" }
        VPath.writeBytes(p, r.bytes)
        return DL(p, r.bytes.size, r.ct, r.via)
    }

    private fun isProbablyText(b: ByteArray, name: String): Boolean {
        if (Regex("\\.(png|jpe?g|gif|webp|bmp|ico|pdf|zip|gz|tar|mp[34]|wav|ogg|webm|woff2?|ttf|otf|exe|bin|wasm|docx|xlsx|pptx)$", RegexOption.IGNORE_CASE).containsMatchIn(name.substringBefore('?').substringBefore('#'))) return false
        try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b, 0, minOf(b.size, 65536))) } catch (e: java.nio.charset.CharacterCodingException) { if (b.size <= 65536) return false }
        for (i in 0 until minOf(b.size, 8192)) if (b[i] == 0.toByte()) return false
        return true
    }

    private fun getPage(url: String, cancel: Cancel?): HttpResp {
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) throw IllegalArgumentException("Only http(s) URLs are supported: $url")
        val r = Http.request(url, headers = headers(), timeoutMs = 45000, cancel = cancel)
        if (!r.ok) throw RuntimeException("HTTP ${r.code} fetching $url")
        return r
    }

    // ------------------------------------------------------------ the old browser tools on the native browser
    private fun browser(ctx: ToolCtx, args: JSONObject): String {
        val t = Tools.get("browser") ?: throw IllegalStateException("the browser is not available")
        return t.run(args, ctx)
    }

    private fun currentUrl(ctx: ToolCtx): String = try {
        Regex("(?m)^URL: (\\S+)").find(browser(ctx, JSONObject().put("action", "elements")))?.groupValues?.get(1) ?: ""
    } catch (e: Exception) { "" }

    private fun tok(s: String) = Regex("[\\p{L}\\p{N}_]{2,}").findAll(s.lowercase()).map { it.value }.toList()

    private val BROWSER_TOOLS = listOf("browser_navigate", "browser_snapshot", "browser_click", "browser_type", "browser_back", "browser_find", "browser_download", "browser", "web_search", "web_extract", "fetch", "download_file", "page_images", "read_page")

    /** browser_use: an autonomous browser sub-agent (observe → act loop) with only browser and web tools. */
    private fun browserAgent(task: String, startUrl: String, maxSteps: Int, parent: ToolCtx): String {
        if (parent.depth >= 1) return "Error: sub-agents cannot start another browser agent."
        val steps = minOf(maxOf(3, if (maxSteps > 0) maxSteps else 15), 40)
        val system = Agent.defaultPersona() + "\n\n" +
            "You are a browser agent. You operate a real web browser on the user's phone: browser_navigate opens a URL and returns a snapshot where interactive elements are numbered [n]; browser_click / browser_type act on those numbers; browser_find locates an element from a description; browser_snapshot re-reads or pages through the current page; browser_back goes back; the browser tool also scrolls, presses keys, waits and takes screenshots. web_search and web_extract are available for finding and reading pages quickly.\n" +
            "Work step by step: look at the page, take one action, check the result. Prefer direct URLs (search result pages, site search forms) over clicking through menus. Pages run their JavaScript, so element numbers change after every page change: always use the numbers from the latest snapshot.\n" +
            "When the task is done, reply with the result the user asked for: the facts or data found, the URLs they came from, and anything you could not complete. You cannot ask the user questions."
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", "TASK: $task" + if (startUrl.isNotEmpty()) "\nSTART AT: $startUrl" else ""))
        val all = Tools.schemas(true); val schemas = JSONArray()
        for (i in 0 until all.length()) { val s = all.getJSONObject(i); if (s.optJSONObject("function")?.optString("name") in BROWSER_TOOLS) schemas.put(s) }
        val ctx = ToolCtx(parent.convo, parent.cancel, parent.background, parent.listener, 1)
        var actions = 0; var lastUrl = ""; var out = ""
        for (step in 0 until steps) {
            parent.cancel.check()
            val res = Llm.chat(msgs, if (step < steps - 1) schemas else null, Cfg.model, cancel = parent.cancel)
            val am = JSONObject().put("role", "assistant").put("content", res.content)
            if (res.toolCalls.isNotEmpty()) {
                val tc = JSONArray()
                for (t in res.toolCalls) tc.put(JSONObject().put("id", t.id).put("type", "function").put("function", JSONObject().put("name", t.name).put("arguments", t.args)))
                am.put("tool_calls", tc)
            }
            msgs.put(am)
            if (res.toolCalls.isEmpty()) { out = res.content.ifBlank { res.reasoning.trim().takeLast(3000) }; break }
            for (t in res.toolCalls) {
                parent.cancel.check()
                parent.progress("↳ ${t.name}")
                val r = try {
                    if (t.name !in BROWSER_TOOLS || t.name == "browser_use") throw IllegalArgumentException("tool ${t.name} is not available to the browser agent")
                    val tool = Tools.get(t.name) ?: throw IllegalArgumentException("unknown tool ${t.name}")
                    tool.run(if (t.args.isBlank()) JSONObject() else JSONObject(t.args), ctx)
                } catch (e: CancelledException) { throw e } catch (e: Throwable) { "Error: " + (e.message ?: e.toString()) }
                actions++
                Regex("(?m)^URL: (\\S+)").findAll(r).lastOrNull()?.let { lastUrl = it.groupValues[1] }
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", t.id).put("name", t.name).put("content", clip(r, 20000)))
            }
            if (step == steps - 1) out = res.content
        }
        if (out.isBlank()) out = "(the browser agent stopped without a final answer — its action budget ran out)"
        return "[browser agent · $actions actions · last page ${lastUrl.ifEmpty { "none" }}]\n$out"
    }

    fun register() {
        Tools.add(Tool(
            "web_extract", "Fetch one or more URLs and return readable text (markdown-ish). Optionally save to a file.",
            P.props("urls" to P.arr("URLs (max 4)"), "save_to" to P.s("Optional file path to save the extracted text")), listOf("urls")
        ) { a, ctx ->
            val res = ArrayList<String>()
            for (u in a.list("urls").take(4)) {
                ctx.cancel.check()
                ctx.progress(Y.t("Reading ", "Okunuyor: ") + u)
                try {
                    val r = getPage(u, ctx.cancel)
                    val ct = r.contentType.lowercase()
                    if (ct.contains("pdf") || ct.startsWith("image/")) { val w = Web.read(u, ctx.cancel); res.add("# ${w.title.ifEmpty { u }}\nURL: $u\n\n${clip(w.text, 15000)}") }
                    else { val rt = readableText(r.text(), r.url); res.add("# ${rt.title.ifEmpty { u }}\nURL: $u\n\n${clip(declutter(rt.text), 15000)}") }
                } catch (e: CancelledException) { throw e } catch (e: Throwable) { res.add("# $u\nERROR: ${e.message}") }
            }
            val out = res.joinToString("\n\n---\n\n")
            val save = a.str("save_to")
            if (save.isNotEmpty()) { val p = VPath.write(VPath.normTool(save), out); "$out\n\n[saved to $p]" } else out
        })

        val headersSchema = JSONObject().put("type", "object").put("description", "Request headers")
        Tools.add(Tool(
            "fetch", "Make an HTTP request to a URL or API and return status, content type and body (HTML is converted to readable text unless format=raw, JSON is pretty-printed). Requests go straight from the phone (no CORS limits). Optionally saves the body to a file.",
            P.props("url" to P.s("URL"), "method" to P.s("HTTP method (default GET)", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"), "headers" to headersSchema, "body" to P.s("Request body as text (JSON as a string)"),
                "format" to P.s("auto | text | json | raw", "auto", "text", "json", "raw"), "save_to" to P.s("Optional file path to save the response body")),
            listOf("url")
        ) { a, ctx ->
            val url = a.str("url")
            val method = a.str("method").uppercase().ifEmpty { "GET" }
            val format = a.str("format").ifEmpty { "auto" }
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) throw IllegalArgumentException("url must start with http:// or https://")
            if (method != "GET" && method != "HEAD") {
                val host = try { URL(url).host } catch (e: Exception) { url }
                if (!ctx.approve("HTTP $method", listOf(Y.t("Why", "Neden") to "sends an HTTP $method request to $host", "URL" to url, "Body" to a.optString("body", "").take(500)))) return@Tool ctx.denied("HTTP $method $url")
            }
            val hs = HashMap<String, String>()
            a.optJSONObject("headers")?.let { h -> for (k in h.keys()) hs[k] = h.optString(k) }
            a.str("headers").takeIf { it.startsWith("{") && a.optJSONObject("headers") == null }?.let { s -> try { val h = JSONObject(s); for (k in h.keys()) hs[k] = h.optString(k) } catch (_: Exception) {} }
            if (hs.keys.none { it.equals("user-agent", true) }) hs["User-Agent"] = Http.UA
            val body = if (method == "GET" || method == "HEAD") null else a.optString("body", "").toByteArray()
            ctx.progress("$method $url")
            val r = Http.request(url, method, hs, body, timeoutMs = 60000, maxBytes = 300 * 1024 * 1024, cancel = ctx.cancel)
            val ct = r.contentType
            val buf = r.body
            var saved = ""
            val save = a.str("save_to")
            if (save.isNotEmpty()) { val p = VPath.writeBytes(VPath.normTool(save), buf); saved = "\n[saved ${fmtBytes(buf.size.toLong())} to $p]" }
            if (!isProbablyText(buf, url) && !Regex("json|text|xml|html|javascript").containsMatchIn(ct))
                return@Tool "HTTP ${r.code} · ${ct.ifEmpty { "unknown type" }} · ${fmtBytes(buf.size.toLong())} · via direct\n(binary body${if (save.isNotEmpty()) "" else " — pass save_to to keep it"})$saved"
            val text = r.text()
            var shown = text
            if (format == "json" || (format == "auto" && ct.contains("json"))) {
                try { val v = org.json.JSONTokener(text.trim()).nextValue(); shown = when (v) { is JSONObject -> v.toString(2); is JSONArray -> v.toString(2); else -> text } } catch (_: Exception) {}
            } else if ((format == "auto" || format == "text") && (ct.contains("html") || Regex("^\\s*<(!doctype|html)", RegexOption.IGNORE_CASE).containsMatchIn(text))) {
                val rt = readableText(text, r.url); shown = (if (rt.title.isNotEmpty()) "# ${rt.title}\n\n" else "") + rt.text
            }
            "HTTP ${r.code} · ${ct.ifEmpty { "unknown type" }} · via direct\n\n${clip(shown, 20000)}$saved"
        })

        Tools.add(Tool(
            "download_file", "Download a file (image, PDF, archive, audio, dataset…) from a URL into the workspace. Downloads it directly from the phone and checks that a real file came back (not an HTML error page). Mention the saved path in your answer so the user can open it.",
            P.props("url" to P.s("Direct URL of the file"), "path" to P.s("Destination file or folder (default /workspace/downloads/<name>)")), listOf("url")
        ) { a, ctx ->
            ctx.progress(Y.t("Downloading ", "İndiriliyor: ") + a.str("url"))
            val r = downloadTo(a.str("url"), a.str("path").ifEmpty { null }, ctx.cancel)
            "Saved ${fmtBytes(r.bytes.toLong())} (${r.ct}) to ${r.path} via ${r.via}."
        })

        Tools.add(Tool(
            "page_images", "List the images on a web page (absolute URLs with alt text and size hints, largest srcset candidate, og:image), so you can pick one and download it with download_file.",
            P.props("url" to P.s("Page URL"), "limit" to toolInt("Max images (default 25)")), listOf("url")
        ) { a, ctx ->
            val url = a.str("url"); val limit = a.int("limit", 25)
            val r = getPage(url, ctx.cancel)
            val d = Html.parse(r.text())
            val out = ArrayList<String>(); val seen = HashSet<String>()
            fun add(src: String?, alt: String?, note: String) {
                if (src.isNullOrEmpty() || src.startsWith("data:")) return
                val abs = absUrl(url, src) ?: return
                if (!seen.add(abs)) return
                out.add("${out.size + 1}. $abs${if (!alt.isNullOrEmpty()) "  — \"${alt.take(100)}\"" else ""}${if (note.isNotEmpty()) "  ($note)" else ""}")
            }
            for (m in d.all { it.tag == "meta" && (it.attr("property") == "og:image" || it.attr("name") == "twitter:image") }) add(m.attr("content"), "", "page preview image")
            for (img in d.byTag("img")) {
                var src = img.attr("src").ifEmpty { img.attr("data-src") }.ifEmpty { img.attr("data-original") }
                val ss = img.attr("srcset").ifEmpty { img.attr("data-srcset") }
                if (ss.isNotEmpty()) {
                    val best = ss.split(",").map { it.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() && it[0].isNotEmpty() }.map { it[0] to (it.getOrNull(1)?.takeWhile { c -> c.isDigit() }?.toIntOrNull() ?: 1) }.maxByOrNull { it.second }
                    if (best != null) src = best.first
                }
                val w = img.attr("width"); val h = img.attr("height")
                add(src, img.attr("alt"), if (w.isNotEmpty() && h.isNotEmpty()) "$w×$h" else "")
                if (out.size >= limit) break
            }
            if (out.isNotEmpty()) "Images on $url:\n${out.joinToString("\n")}" else "No images found on $url (they may be loaded by JavaScript; try the site's image URLs directly)."
        })

        // ---- the original's browser_* tools, on the native browser
        Tools.add(Tool("browser_navigate", "Open a URL in the agent's browser and return a snapshot with numbered interactive elements [n].",
            P.props("url" to P.s("URL")), listOf("url")) { a, ctx -> browser(ctx, JSONObject().put("action", "open").put("url", a.str("url"))) })
        Tools.add(Tool("browser_snapshot", "Re-read the current page. Long pages are split into pages.",
            P.props("page" to toolInt("0-based page index")), emptyList()) { a, ctx -> browser(ctx, JSONObject().put("action", "text").put("offset", maxOf(0, a.int("page", 0)) * 12000)) })
        Tools.add(Tool("browser_click", "Click element [ref]: follows links, submits forms, toggles checkboxes.",
            P.props("ref" to toolInt("Element number from the snapshot")), listOf("ref")) { a, ctx -> browser(ctx, JSONObject().put("action", "click").put("id", a.int("ref", 0))) })
        Tools.add(Tool("browser_type", "Type text into input/textarea [ref] (or choose an option of a select). Set submit=true to submit its form.",
            P.props("ref" to toolInt("Element number"), "text" to P.s("Text to type"), "submit" to P.b("Submit the form afterwards")), listOf("ref", "text")
        ) { a, ctx ->
            val id = a.int("ref", 0); val text = a.optString("text", "")
            try { browser(ctx, JSONObject().put("action", "select").put("id", id).put("option", text)) }
            catch (e: IllegalArgumentException) {
                if (e.message?.contains("not a select") != true) throw e
                browser(ctx, JSONObject().put("action", "type").put("id", id).put("text", text).put("submit", a.optBoolean("submit")))
            }
        })
        Tools.add(Tool("browser_back", "Go back to the previous page.", P.props(), emptyList()) { _, ctx -> browser(ctx, JSONObject().put("action", "back")) })
        Tools.add(Tool("browser_find", "Find the element that matches a natural-language description (e.g. 'the search box', 'next page link'); returns the ref to click or type into.",
            P.props("description" to P.s("What you are looking for")), listOf("description")
        ) { a, ctx ->
            val snap = browser(ctx, JSONObject().put("action", "elements"))
            val els = snap.lines().filter { Regex("^\\[\\d+] ").containsMatchIn(it) }
            if (els.isEmpty()) return@Tool if (snap.contains("URL: ")) "No interactive elements on this page." else "No page loaded."
            val qt = tok(a.str("description")).toSet()
            val cands = els.map { it to tok(it).count { t -> t in qt } }.sortedByDescending { it.second }.take(40)
            "Candidates by keyword overlap:\n" + cands.take(8).joinToString("\n") { it.first }
        })
        Tools.add(Tool("browser_download", "Download a URL (or the current page) as a file into the workspace (HTML error pages are rejected).",
            P.props("url" to P.s("URL (default: current page)"), "path" to P.s("Destination path")), listOf("path")
        ) { a, ctx ->
            val cur = currentUrl(ctx)
            val u = if (a.str("url").isNotEmpty()) (if (cur.isNotEmpty()) absUrl(cur, a.str("url")) else a.str("url")) ?: a.str("url") else cur
            if (u.isEmpty()) throw IllegalArgumentException("No URL and no page open")
            val r = downloadTo(u, a.str("path"), ctx.cancel)
            "Saved ${fmtBytes(r.bytes.toLong())} (${r.ct}) to ${r.path} via ${r.via}"
        })
        Tools.add(Tool("browser_use", "Hand a multi-step web task to an autonomous browser agent that navigates, reads, clicks, fills forms and paginates on its own, then reports what it found. Use it for tasks that need several page interactions; for reading one page use web_extract.",
            P.props("task" to P.s("What to accomplish and what to report back"), "start_url" to P.s("Optional URL to start from"), "max_steps" to toolInt("Action budget, 3–40 (default 15)")), listOf("task"),
            subagentOk = false
        ) { a, ctx ->
            ctx.progress(Y.t("Browser agent is working…", "Tarayıcı ajanı çalışıyor…"))
            browserAgent(a.str("task"), a.str("start_url"), a.int("max_steps", 15), ctx)
        })
    }
}
