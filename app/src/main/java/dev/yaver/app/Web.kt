package dev.yaver.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Browsing without an API key.
 *
 * Two lessons are baked in. First, a phone's connection is blocked outright by
 * a lot of sites — Trendyol, Amazon, most news publishers — and no amount of
 * header fiddling changes that, so anything that fails goes through a reader
 * proxy that fetches server-side. Second, a search engine that returns results
 * about the wrong subject is worse than one that returns nothing, because the
 * model believes it; so results are checked against the query before being
 * handed over.
 */
object Web {

    private const val READER = "https://r.jina.ai/"

    /**
     * Without a User-Agent, HttpURLConnection announces itself as "Java/17" and
     * anything behind Cloudflare or Akamai — IMDb, most newspapers, most
     * retailers — refuses immediately. The page is not blocked to the user; it
     * is blocked to a client that looks like a script. Claiming to be the
     * phone's own browser is what makes the same page load.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-A556E) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Set by [fetch] so a caller can tell a refusal from a timeout. */
    private var lastStatus: Int = 0

    private fun fetch(url: String, timeoutMs: Int = 12000, post: String? = null): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (post == null) "GET" else "POST"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                setRequestProperty("Sec-Fetch-Mode", "navigate")
                setRequestProperty("Sec-Fetch-Dest", "document")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                if (post != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            post?.let { conn.outputStream.use { os -> os.write(it.toByteArray()) } }

            lastStatus = conn.responseCode
            if (lastStatus !in 200..299) {
                Log.net("HTTP $lastStatus for ${short(url)}")
                conn.disconnect()
                return null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            lastStatus = 0
            Log.net("fetch failed ${short(url)}: ${e.message}")
            null
        }
    }

    private fun viaReader(url: String): String? {
        Log.net("reader fallback for ${short(url)}")
        return fetch(READER + url, timeoutMs = 25000)
    }

    private fun short(url: String) = url.removePrefix("https://").removePrefix("http://").take(60)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── search ───────────────────────────────────────────────────────────────

    data class Result(val title: String, val url: String, val snippet: String)

    private val STOP = setOf("the", "and", "for", "with", "bir", "ile", "için", "icin", "ve", "en", "mi", "mu")

    private fun queryTerms(q: String) = q.lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .split(" ").filter { it.length > 2 && it !in STOP }

    /**
     * Scraped engines occasionally serve a cached page for an entirely
     * different search. If barely anything mentions the query, discard the lot.
     */
    private fun relevant(query: String, results: List<Result>): Boolean {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return true
        val hits = results.count { r ->
            val hay = "${r.title} ${r.snippet} ${r.url}".lowercase(Locale.ROOT)
            terms.any { hay.contains(it) }
        }
        return hits >= maxOf(1, Math.ceil(results.size * 0.34).toInt())
    }

    private val JUNK = Regex("duckduckgo\\.com|mojeek\\.com|bing\\.com|google\\.|jina\\.ai|w3\\.org")

    /** Pull [text](url) pairs out of the reader proxy's markdown. */
    private fun fromMarkdown(md: String, limit: Int): List<Result> {
        val out = mutableListOf<Result>()
        val seen = mutableSetOf<String>()
        val re = Regex("\\[([^\\]]{3,120})\\]\\((https?://[^)\\s]+)\\)")
        var pending: Triple<String, String, Int>? = null

        fun close(end: Int) {
            val p = pending ?: return
            val snippet = md.substring(minOf(p.third, md.length), minOf(end, md.length))
                .replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)"), " ")
                .replace(Regex("[#*>|`]"), " ")
                .replace(Regex("\\s+"), " ").trim().take(240)
            out.add(Result(p.first, p.second, snippet))
            pending = null
        }

        for (m in re.findAll(md)) {
            close(m.range.first)
            var url = m.groupValues[2]
            // DuckDuckGo wraps results in a redirect; unwrap it.
            Regex("[?&]uddg=([^&]+)").find(url)?.let {
                url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
            }
            if (JUNK.containsMatchIn(url) || !seen.add(url)) continue
            pending = Triple(m.groupValues[1].replace(Regex("\\s+"), " ").trim(), url, m.range.last + 1)
            if (out.size >= limit) break
        }
        close(md.length)
        return out.take(limit)
    }

    /** Heading-ish links out of a search results page, without engine-specific selectors. */
    private fun fromHtml(html: String, limit: Int): List<Result> {
        val out = mutableListOf<Result>()
        val seen = mutableSetOf<String>()
        val anchor = Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        for (m in anchor.findAll(html)) {
            var url = m.groupValues[1]
            Regex("[?&]uddg=([^&\"]+)").find(url)?.let {
                url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
            }
            if (!url.startsWith("http")) continue
            if (JUNK.containsMatchIn(url) || !seen.add(url)) continue
            val title = stripTags(m.groupValues[2]).trim()
            if (title.length < 4) continue
            out.add(Result(title, url, ""))
            if (out.size >= limit * 2) break
        }
        return out.take(limit)
    }

    fun search(query: String, limit: Int = 6): Pair<List<Result>, String> {
        val tried = mutableListOf<String>()

        // Direct first because it is fast when it works; reader-proxied second
        // because in practice the phone is blocked more often than not.
        val engines: List<Pair<String, () -> List<Result>>> = listOf(
            Pair("duckduckgo", {
                fetch("https://html.duckduckgo.com/html/", post = "q=${enc(query)}")
                    ?.let { fromHtml(it, limit) } ?: emptyList()
            }),
            Pair("mojeek", {
                fetch("https://www.mojeek.com/search?q=${enc(query)}")
                    ?.let { fromHtml(it, limit) } ?: emptyList()
            }),
            Pair("duckduckgo-reader", {
                viaReader("https://lite.duckduckgo.com/lite/?q=${enc(query)}")
                    ?.let { fromMarkdown(it, limit) } ?: emptyList()
            }),
            Pair("mojeek-reader", {
                viaReader("https://www.mojeek.com/search?q=${enc(query)}")
                    ?.let { fromMarkdown(it, limit) } ?: emptyList()
            })
        )

        for ((name, attempt) in engines) {
            val results = try { attempt() } catch (e: Exception) {
                tried.add("$name: ${e.message}"); continue
            }
            if (results.isEmpty()) { tried.add("$name: nothing parseable"); continue }
            if (!relevant(query, results)) { tried.add("$name: results unrelated to the query"); continue }
            Log.net("search via $name: ${results.size} results")
            return results to name
        }
        Log.error("search produced nothing: ${tried.joinToString("; ")}")
        return emptyList<Result>() to tried.joinToString("; ")
    }

    // ── pictures ─────────────────────────────────────────────────────────────

    data class Image(val title: String, val url: String, val credit: String, val licence: String)

    /**
     * Wikimedia Commons: no key, clear licensing, and genuinely good coverage
     * of species, places and objects — which is most of what anyone asks to
     * see. Image search engines all want a key or block a phone outright.
     */
    fun images(query: String, count: Int = 3, width: Int = 480): List<Image> {
        val url = "https://commons.wikimedia.org/w/api.php?" +
            "action=query&format=json&origin=*&generator=search" +
            "&gsrsearch=" + enc("filetype:bitmap $query") +
            "&gsrlimit=${(count * 2).coerceAtMost(12)}&gsrnamespace=6" +
            "&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=$width"

        val body = fetch(url, timeoutMs = 15000) ?: return emptyList()
        return try {
            val pages = org.json.JSONObject(body).optJSONObject("query")?.optJSONObject("pages")
                ?: return emptyList()
            val out = mutableListOf<Image>()
            for (key in pages.keys()) {
                val page = pages.optJSONObject(key) ?: continue
                val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val thumb = info.optString("thumburl")
                if (thumb.isBlank()) continue
                val meta = info.optJSONObject("extmetadata")
                out.add(Image(
                    title = page.optString("title").removePrefix("File:")
                        .replace(Regex("\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE), ""),
                    url = thumb,
                    credit = meta?.optJSONObject("Artist")?.optString("value")
                        ?.replace(Regex("<[^>]+>"), "")?.trim()?.take(60) ?: "",
                    licence = meta?.optJSONObject("LicenseShortName")?.optString("value") ?: ""
                ))
                if (out.size >= count) break
            }
            out
        } catch (e: Exception) {
            Log.error("image search failed: ${e.message}")
            emptyList()
        }
    }

    // ── places ───────────────────────────────────────────────────────────────

    data class Place(val name: String, val address: String, val lat: Double, val lon: Double)

    /**
     * Real coordinates from Nominatim rather than coordinates the model
     * remembers, which are confidently wrong often enough to matter.
     */
    fun geocode(query: String, limit: Int = 3): List<Place> {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=$limit&q=" + enc(query)
        val body = fetch(url, timeoutMs = 15000) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val display = o.optString("display_name")
                Place(
                    name = display.substringBefore(",").trim().ifBlank { query },
                    address = display,
                    lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null,
                    lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                )
            }
        } catch (e: Exception) {
            Log.error("geocode failed: ${e.message}")
            emptyList()
        }
    }

    /** Search around a point — what "near me" actually needs. */
    fun geocodeNear(query: String, lat: Double, lon: Double, limit: Int = 3): List<Place> {
        // A rough box around the point; Nominatim ranks inside it.
        val d = 0.09      // about ten kilometres
        val box = "${lon - d},${lat + d},${lon + d},${lat - d}"
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=$limit" +
            "&bounded=1&viewbox=$box&q=" + enc(query)
        val body = fetch(url, timeoutMs = 15000) ?: return geocode(query, limit)
        return try {
            val arr = org.json.JSONArray(body)
            val found = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val display = o.optString("display_name")
                Place(
                    name = display.substringBefore(",").trim().ifBlank { query },
                    address = display,
                    lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null,
                    lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                )
            }
            // An empty box is worse than a wider search.
            if (found.isEmpty()) geocode(query, limit) else found
        } catch (e: Exception) {
            geocode(query, limit)
        }
    }

    /** Coordinates alone tell a model nothing; a place name tells it a lot. */
    fun reverseGeocode(lat: Double, lon: Double): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?format=json&zoom=14&lat=$lat&lon=$lon"
        val body = fetch(url, timeoutMs = 12000) ?: return null
        return try {
            org.json.JSONObject(body).optString("display_name").ifBlank { null }
        } catch (e: Exception) { null }
    }

    // ── reading a page ───────────────────────────────────────────────────────

    data class Page(val url: String, val title: String, val text: String, val viaReader: Boolean, val blocked: String?)

    private fun stripTags(html: String) = html
        .replace(Regex("(?is)<(script|style|nav|footer|header|aside|noscript|svg)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n")
        .trim()

    /**
     * Pull the facts out of the structured data most pages carry.
     *
     * Ratings, prices, dates and addresses live in JSON-LD or in meta tags —
     * and JSON-LD sits inside a <script> block, which the text extractor
     * strips. IMDb is the clearest case: the rating is never in the visible
     * HTML at all, so a reader that only takes prose comes back with the plot
     * and no number. Lifting these out first is the difference between
     * answering the question and describing the page.
     */
    private fun structuredFacts(html: String): String {
        val facts = LinkedHashMap<String, String>()

        fun note(key: String, value: String?) {
            val v = value?.trim()?.replace(Regex("\\s+"), " ")?.take(200) ?: return
            if (v.isNotEmpty() && v != "null" && !facts.containsKey(key)) facts[key] = v
        }

        // Meta tags: cheap, and present on nearly everything.
        val metaRe = Regex(
            "<meta[^>]+(?:property|name)=[\"'](og:title|og:description|description)[\"'][^>]+content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE)
        metaRe.findAll(html).forEach {
            note(it.groupValues[1].removePrefix("og:"), it.groupValues[2])
        }

        // JSON-LD: where the numbers actually are.
        val ldRe = Regex(
            "<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>",
            RegexOption.IGNORE_CASE)
        ldRe.findAll(html).take(4).forEach { match ->
            val raw = match.groupValues[1].trim()
            try {
                val nodes = mutableListOf<org.json.JSONObject>()
                if (raw.startsWith("[")) {
                    val arr = org.json.JSONArray(raw)
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { nodes.add(it) }
                } else {
                    nodes.add(org.json.JSONObject(raw))
                }
                nodes.forEach { node ->
                    note("type", node.optString("@type"))
                    note("name", node.optString("name"))
                    node.optJSONObject("aggregateRating")?.let { r ->
                        note("rating", r.optString("ratingValue"))
                        note("votes", r.optString("ratingCount"))
                    }
                    node.optJSONObject("offers")?.let { o ->
                        note("price", o.optString("price"))
                        note("currency", o.optString("priceCurrency"))
                        note("availability", o.optString("availability"))
                    }
                    note("released", node.optString("datePublished"))
                    note("duration", node.optString("duration"))
                    node.optJSONObject("address")?.let { a ->
                        val parts = listOf(
                            a.optString("streetAddress"),
                            a.optString("addressLocality"),
                            a.optString("addressCountry")
                        ).filter { it.isNotBlank() && it != "null" }
                        note("address", parts.joinToString(", "))
                    }
                }
            } catch (e: Exception) { /* pages ship malformed JSON-LD constantly */ }
        }

        if (facts.isEmpty()) return ""
        return facts.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    /**
     * Cookie walls, bot checks and soft 404s all arrive as HTTP 200 with plenty
     * of text. Left unlabelled the model reads them as a failed attempt and
     * tries the same URL again.
     */
    private fun blockedReason(title: String, text: String): String? {
        val t = "$title $text".take(3000).lowercase(Locale.ROOT)
        return when {
            Regex("returned error 4\\d\\d|access denied|403 forbidden").containsMatchIn(t) ->
                "the site refused access"
            Regex("just a moment|security verification|checking your browser|captcha").containsMatchIn(t) ->
                "a bot check blocked the page"
            Regex("gizlilik tercihi merkezi|cookie preference|manage consent").containsMatchIn(t) && text.length < 6000 ->
                "only a cookie notice loaded"
            text.trim().length < 200 -> "the page returned almost no text"
            else -> null
        }
    }

    fun open(rawUrl: String): Page {
        val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        var viaReader = false
        var body = fetch(url)
        if (body == null) {
            body = viaReader(url)
            viaReader = true
        }
        if (body == null) {
            val why = when (lastStatus) {
                401, 403 -> "the site refused the request (HTTP $lastStatus), even through the reader"
                404 -> "the page does not exist (404)"
                429 -> "the site is rate-limiting (429) — wait or use another source"
                in 500..599 -> "the site is having problems (HTTP $lastStatus)"
                else -> "could not be reached, directly or through the reader"
            }
            return Page(url, url, "", false, why)
        }

        val title = if (viaReader) {
            Regex("(?i)^Title:\\s*(.+)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)?.trim() ?: url
        } else {
            Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.trim() ?: url
        }
        val prose = if (viaReader) body.trim() else stripTags(body)
        val facts = if (viaReader) "" else structuredFacts(body)
        val text = if (facts.isEmpty()) prose else "[page data]\n" + facts + "\n\n" + prose
        return Page(url, title, text, viaReader, blockedReason(title, prose))
    }
}
