package com.plusai.yaver

import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The phone's own HTTP client, used by the page's fetch() for every other website. It replaces the
 * CORS proxies: no third party sees the traffic, there is no CORS, cookies are shared with the
 * in-app browser and the mail screen, and responses stream back (LLM answers appear word by word).
 */
object HttpEngine {
    private val pool = Executors.newCachedThreadPool()
    private val live = ConcurrentHashMap<Int, HttpURLConnection>()
    private val cancelled: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile private var ua: String? = null

    fun userAgent(): String {
        ua?.let { return it }
        val u = try {
            WebSettings.getDefaultUserAgent(YaverCore.app)
        } catch (e: Throwable) {
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        }
        ua = u
        return u
    }

    fun start(id: Int, method: String, url: String, headersJson: String, bodyB64: String) {
        pool.execute { run(id, method.uppercase(), url, headersJson, bodyB64) }
    }

    fun abort(id: Int) {
        cancelled.add(id)
        val c = live.remove(id) ?: return
        pool.execute { try { c.disconnect() } catch (_: Throwable) {} }
    }

    private fun js(code: String) = YaverCore.js(code)

    private fun run(id: Int, method0: String, url0: String, headersJson: String, bodyB64: String) {
        var method = method0
        var url = url0
        var body: ByteArray? = if (bodyB64.isEmpty()) null else Base64.decode(bodyB64, Base64.DEFAULT)
        val headers = try { JSONObject(headersJson) } catch (e: Exception) { JSONObject() }
        try {
            var hops = 0
            while (true) {
                if (cancelled.contains(id)) return
                val conn = URL(url).openConnection() as HttpURLConnection
                live[id] = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 30000
                conn.readTimeout = 600000
                conn.useCaches = false
                try {
                    conn.requestMethod = method
                } catch (e: ProtocolException) {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("X-HTTP-Method-Override", method)
                }
                var hasUa = false
                var hasCookie = false
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val lk = k.lowercase()
                    if (lk == "host" || lk == "content-length" || lk == "connection" ||
                        lk == "accept-encoding" || lk == "transfer-encoding") continue
                    if (lk == "user-agent") hasUa = true
                    if (lk == "cookie") hasCookie = true
                    conn.setRequestProperty(k, headers.optString(k))
                }
                if (!hasUa) conn.setRequestProperty("User-Agent", userAgent())
                if (!hasCookie) {
                    val ck = try { CookieManager.getInstance().getCookie(url) } catch (e: Throwable) { null }
                    if (!ck.isNullOrEmpty()) conn.setRequestProperty("Cookie", ck)
                }
                val b = body
                if (b != null && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(b.size)
                    conn.outputStream.use { it.write(b) }
                }
                val code = conn.responseCode
                val fields = conn.headerFields
                try {
                    val cm = CookieManager.getInstance()
                    for ((k, vs) in fields) {
                        if (k == null || vs == null) continue
                        if (k.equals("Set-Cookie", true) || k.equals("Set-Cookie2", true)) for (v in vs) cm.setCookie(url, v)
                    }
                    cm.flush()
                } catch (_: Throwable) {}

                val loc = conn.getHeaderField("Location")
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && loc != null && hops < 10) {
                    hops++
                    url = URL(URL(url), loc).toString()
                    if (code == 303 || ((code == 301 || code == 302) && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                    try { conn.inputStream?.close() } catch (_: Throwable) {}
                    conn.disconnect()
                    continue
                }

                val arr = JSONArray()
                for ((k, vs) in fields) {
                    if (k == null || vs == null) continue
                    for (v in vs) arr.put(JSONArray().put(k).put(v ?: ""))
                }
                val statusText = conn.responseMessage ?: ""
                js("window.__yn&&__yn.head($id,$code,${JSONObject.quote(statusText)},$arr,${JSONObject.quote(url)})")

                val input: InputStream? = try {
                    if (code >= 400) conn.errorStream else conn.inputStream
                } catch (e: Exception) {
                    conn.errorStream
                }
                if (input != null && method != "HEAD") {
                    input.use { ins ->
                        val buf = ByteArray(65536)
                        while (true) {
                            if (cancelled.contains(id)) return
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                            js("window.__yn&&__yn.chunk($id,\"$b64\")")
                        }
                    }
                }
                js("window.__yn&&__yn.end($id)")
                return
            }
        } catch (e: Throwable) {
            if (!cancelled.contains(id)) {
                val msg = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                js("window.__yn&&__yn.err($id,${JSONObject.quote(msg)})")
            }
        } finally {
            live.remove(id)
            cancelled.remove(id)
        }
    }
}
