package com.plusai.yaver.app

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs JavaScript and Python (Pyodide) for the run_code tool in an invisible engine. No screen of the app
 * is a web page; this is only the calculator underneath.
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled")
object CodeRunner {
    private const val HOST = "appassets.androidplatform.net"
    private var web: WebView? = null
    @Volatile private var ready: CountDownLatch? = null
    private val jobs = ConcurrentHashMap<Int, Pair<CountDownLatch, Array<String?>>>()
    private val progress = ConcurrentHashMap<Int, Cancel>()
    private val seq = AtomicInteger(0)

    class Bridge {
        @JavascriptInterface fun ready() { ready?.countDown() }
        @JavascriptInterface fun done(id: Int, json: String?) { jobs[id]?.let { it.second[0] = json; it.first.countDown() } }
        @JavascriptInterface fun progress(id: Int, text: String?) { }
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
                v.addJavascriptInterface(Bridge(), "YaverRunner")
                v.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url ?: return null
                        if (u.host != HOST) return null
                        return try { WebResourceResponse("text/html", "utf-8", Y.app.assets.open("runner.html")) } catch (e: Exception) { WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), ByteArrayInputStream(ByteArray(0))) }
                    }
                }
                web = v
                v.loadUrl("https://$HOST/runner.html")
            } catch (e: Throwable) { l.countDown() }
        }
        if (!l.await(30, TimeUnit.SECONDS)) throw IllegalStateException("the code engine did not start")
    }

    fun run(language: String, code: String, files: List<String>, timeoutSec: Int, cancel: Cancel): String {
        ensure()
        val id = seq.incrementAndGet()
        val fobj = JSONObject()
        for (p in files) {
            val f = Workspace.resolve(p)
            if (!f.exists()) throw IllegalArgumentException("file not found: $p")
            if (f.length() > 40L * 1024 * 1024) throw IllegalArgumentException("$p is too large for run_code")
            fobj.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        val job = JSONObject().put("language", language.lowercase()).put("code", code).put("files", fobj)
        val latch = CountDownLatch(1)
        val slot = arrayOfNulls<String>(1)
        jobs[id] = Pair(latch, slot)
        cancel.onCancel { latch.countDown() }
        Y.main.post { web?.evaluateJavascript("window.runJob($id, ${JSONObject.quote(job.toString())})", null) }
        val first = language.startsWith("py") && slot[0] == null
        val ok = latch.await((timeoutSec.coerceIn(5, 1800) + if (first) 120 else 0).toLong(), TimeUnit.SECONDS)
        jobs.remove(id)
        cancel.check()
        if (!ok || slot[0] == null) {
            // a stuck program: throw the engine away
            Y.main.post { try { web?.destroy() } catch (_: Throwable) {}; web = null }
            ready = null
            throw IllegalStateException("the code did not finish within $timeoutSec s and was stopped")
        }
        val r = JSONObject(slot[0]!!)
        val sb = StringBuilder()
        val out = r.optString("out")
        if (out.isNotEmpty()) sb.append(clip(out, 15000))
        val written = r.optJSONObject("files")
        if (written != null && written.length() > 0) {
            val saved = ArrayList<String>()
            for (k in written.keys()) saved.add(Workspace.write("outputs", k, Base64.decode(written.getString(k), Base64.DEFAULT)))
            sb.append("\n\nFiles saved: ").append(saved.joinToString(", "))
            val pics = saved.filter { Workspace.isImage(it) }
            if (pics.isNotEmpty()) sb.append("\nShow pictures to the user with markdown: ").append(pics.joinToString(" ") { "![](${it})" })
        }
        if (!r.optBoolean("ok")) throw RuntimeException(r.optString("error") + if (sb.isNotEmpty()) "\nOutput before the error:\n$sb" else "")
        return sb.toString().ifEmpty { "(no output — print what you need)" }
    }
}
