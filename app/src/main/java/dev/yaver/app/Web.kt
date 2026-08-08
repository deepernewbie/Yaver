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

    private fun fetch(url: String, timeoutMs: Int = 12000, post: String? = null): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (post == null) "GET" else "POST"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en,tr;q=0.8")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                if (post != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            post?.let { conn.outputStream.use { os -> os.write(it.toByteArray()) } }
            if (conn.responseCode !in 200..299) {
                Log.net("HTTP ${conn.responseCode} for ${short(url)}")
                conn.disconnect()
                return null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
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
            "duckduckgo" to {
                fetch("https://html.duckduckgo.com/html/", post = "q=${enc(query)}")
                    ?.let { fromHtml(it, limit) } ?: emptyList()
            },
            "mojeek" to {
                fetch("https://www.mojeek.com/search?q=${enc(query)}")
                    ?.let { fromHtml(it, limit) } ?: emptyList()
            },
            "duckduckgo-reader" to {
                viaReader("https://lite.duckduckgo.com/lite/?q=${enc(query)}")
                    ?.let { fromMarkdown(it, limit) } ?: emptyList()
            },
            "mojeek-reader" to {
                viaReader("https://www.mojeek.com/search?q=${enc(query)}")
                    ?.let { fromMarkdown(it, limit) } ?: emptyList()
            }
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
            return Page(url, url, "", false, "could not be reached, directly or through the reader")
        }

        val title = if (viaReader) {
            Regex("(?i)^Title:\\s*(.+)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)?.trim() ?: url
        } else {
            Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.trim() ?: url
        }
        val text = if (viaReader) body.trim() else stripTags(body)
        return Page(url, title, text, viaReader, blockedReason(title, text))
    }
}
