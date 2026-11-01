package com.plusai.yaver.app

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class HttpResp(val code: Int, val headers: Map<String, List<String>>, val body: ByteArray, val url: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    val contentType: String get() = header("content-type") ?: ""
    fun text(): String {
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(String(body, 0, minOf(body.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return try { String(body, if (cs != null) Charset.forName(cs) else Charsets.UTF_8) } catch (e: Exception) { String(body, Charsets.UTF_8) }
    }
    val ok: Boolean get() = code in 200..299
}

/** The phone's HTTP client: cookies shared with every web view in the app, manual redirects, cancellable. */
object Http {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun cookies(): CookieManager? = try { CookieManager.getInstance() } catch (e: Throwable) { null }

    fun cookie(url: String, name: String): String? {
        val all = cookies()?.getCookie(url) ?: return null
        for (p in all.split(";")) {
            val kv = p.trim()
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(0, i).equals(name, true)) return kv.substring(i + 1)
        }
        return null
    }

    fun setCookie(url: String, value: String) { cookies()?.setCookie(url, value) }

    private fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?, useCookies: Boolean, timeoutMs: Int, cancel: Cancel?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        cancel?.conn = c
        c.instanceFollowRedirects = false
        c.connectTimeout = minOf(timeoutMs, 30000)
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.requestMethod = method
        var ua = false
        for ((k, v) in headers) {
            if (k.equals("user-agent", true)) ua = true
            c.setRequestProperty(k, v)
        }
        if (!ua) c.setRequestProperty("User-Agent", UA)
        if (useCookies && headers.keys.none { it.equals("cookie", true) }) {
            val ck = cookies()?.getCookie(url)
            if (!ck.isNullOrEmpty()) c.setRequestProperty("Cookie", ck)
        }
        if (body != null && method != "GET" && method != "HEAD") {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        return c
    }

    private fun storeCookies(url: String, c: HttpURLConnection) {
        val cm = cookies() ?: return
        try {
            for ((k, vs) in c.headerFields) {
                if (k == null || vs == null) continue
                if (k.equals("Set-Cookie", true)) for (v in vs) cm.setCookie(url, v)
            }
            cm.flush()
        } catch (_: Throwable) {}
    }

    fun readAll(ins: InputStream?, max: Int, cancel: Cancel?): ByteArray {
        if (ins == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32768)
        ins.use {
            while (true) {
                cancel?.check()
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) break
            }
        }
        return out.toByteArray()
    }

    fun request(
        url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
        follow: Boolean = true, useCookies: Boolean = true, timeoutMs: Int = 60000, maxBytes: Int = 25 * 1024 * 1024, cancel: Cancel? = null
    ): HttpResp {
        var u = url
        var m = method
        var b = body
        var hops = 0
        while (true) {
            cancel?.check()
            val c = open(u, m, headers, b, useCookies, timeoutMs, cancel)
            try {
                val code = c.responseCode
                if (useCookies) storeCookies(u, c)
                val loc = c.getHeaderField("Location")
                if (follow && code in 300..308 && code != 304 && loc != null && hops < 12) {
                    hops++
                    try { c.inputStream?.close() } catch (_: Throwable) {}
                    u = URL(URL(u), loc).toString()
                    if (code == 303 || ((code == 301 || code == 302) && m == "POST")) { m = "GET"; b = null }
                    continue
                }
                val ins = try { if (code >= 400) c.errorStream else c.inputStream } catch (e: IOException) { c.errorStream }
                val data = if (m == "HEAD") ByteArray(0) else readAll(ins, maxBytes, cancel)
                val hs = HashMap<String, List<String>>()
                for ((k, v) in c.headerFields) if (k != null && v != null) hs[k] = v
                return HttpResp(code, hs, data, u)
            } finally {
                c.disconnect()
            }
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), cancel: Cancel? = null) = request(url, "GET", headers, null, cancel = cancel)

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), follow: Boolean = true, cancel: Cancel? = null): HttpResp {
        val body = form.entries.joinToString("&") { java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }
        return request(url, "POST", headers + ("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray(), follow = follow, cancel = cancel)
    }

    /** Streams a response line by line (server-sent events). Returns the status; non-2xx bodies are returned in full. */
    fun streamLines(url: String, headers: Map<String, String>, body: ByteArray, cancel: Cancel?, timeoutMs: Int = 300000, onLine: (String) -> Unit): Pair<Int, String> {
        val c = open(url, "POST", headers, body, false, timeoutMs, cancel)
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val err = String(readAll(c.errorStream, 200000, cancel), Charsets.UTF_8)
                return Pair(code, err)
            }
            val ct = c.contentType ?: ""
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                if (!ct.contains("event-stream")) {
                    return Pair(code, r.readText())
                }
                while (true) {
                    cancel?.check()
                    val line = r.readLine() ?: break
                    onLine(line)
                }
            }
            return Pair(code, "")
        } finally {
            c.disconnect()
        }
    }
}
