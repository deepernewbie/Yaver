package com.plusai.yaver.app

import android.annotation.SuppressLint
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends Outlook web requests from inside a hidden web view that sits on the mail server's own address.
 * To the server (and to gateways such as F5 in front of it) these requests are indistinguishable from the
 * Outlook page you signed in to: same browser engine, same cookies, same connection behaviour.
 * Used when the gateway does not accept yaver's own HTTP client.
 */
object WebOwa {
    class Resp(val code: Int, val redirected: Boolean, val url: String, val text: String)

    @Volatile private var view: WebView? = null
    @Volatile private var viewOrigin = ""
    @Volatile private var ready: CountDownLatch? = null
    private val waits = ConcurrentHashMap<Int, Pair<CountDownLatch, Array<Resp?>>>()
    private val seq = AtomicInteger()

    private object Bridge {
        @JavascriptInterface fun done(id: Int, code: Int, redirected: Boolean, url: String?, text: String?) {
            val w = waits[id] ?: return
            w.second[0] = Resp(code, redirected, url ?: "", text ?: "")
            w.first.countDown()
        }
    }

    /** Synchronized: two mail requests starting together must not each build (and destroy) the web view. */
    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized private fun ensure(origin: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw MailException("WebOwa on main thread")
        if (view != null && viewOrigin == origin && ready?.count == 0L) return
        val latch = CountDownLatch(1)
        ready = latch
        Y.main.post {
            try {
                view?.destroy()
                val v = WebView(Y.app)
                v.settings.javaScriptEnabled = true
                v.settings.domStorageEnabled = true
                v.settings.userAgentString = Mail.ua
                v.addJavascriptInterface(Bridge, "YaverOwa")
                v.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { latch.countDown() }
                }
                // A blank page that the browser treats as being on the mail server, so requests are same-site.
                v.loadDataWithBaseURL("$origin/owa/", "<!doctype html><html><head><meta charset=utf-8></head><body></body></html>", "text/html", "utf-8", null)
                view = v
                viewOrigin = origin
            } catch (e: Throwable) { latch.countDown() }
        }
        if (!latch.await(20, TimeUnit.SECONDS) || view == null) throw MailException(Y.t("The mail web view could not start.", "Posta web görünümü başlatılamadı."))
    }

    /** POSTs (or GETs when body is null) from the mail server's own page. Redirects are not followed: code 0 + redirected=true. */
    /** binary=true: the answer text is "<content-type>\n<base64>". */
    fun send(origin: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int, cancel: Cancel?, binary: Boolean = false): Resp {
        ensure(origin)
        val id = seq.incrementAndGet()
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<Resp>(1)
        waits[id] = latch to box
        val h = JSONObject(); for ((k, v) in headers) if (!k.equals("user-agent", true) && !k.equals("cookie", true)) h.put(k, v)
        val opts = JSONObject().put("method", if (body == null) "GET" else "POST").put("headers", h).put("credentials", "include").put("redirect", "manual").put("cache", "no-store")
        if (body != null) opts.put("body", body)
        val js = "(function(){var o=$opts;fetch(${JSONObject.quote(url)},o).then(function(r){" +
            "if(r.type==='opaqueredirect'){YaverOwa.done($id,0,true,r.url,'');return;}" +
            (if (binary) "return r.arrayBuffer().then(function(b){var u=new Uint8Array(b),s='',k=0;for(;k<u.length;k+=32768)s+=String.fromCharCode.apply(null,u.subarray(k,k+32768));YaverOwa.done($id,r.status,r.redirected,r.url,(r.headers.get('content-type')||'')+'\\n'+btoa(s));});"
            else "return r.text().then(function(t){YaverOwa.done($id,r.status,r.redirected,r.url,t);});") +
            "}).catch(function(e){YaverOwa.done($id,-1,false,'',String(e&&e.message||e));});})();"
        Y.main.post { try { val v = view ?: throw IllegalStateException("web view closed"); v.evaluateJavascript(js, null) } catch (e: Throwable) { box[0] = Resp(-1, false, "", e.message ?: ""); latch.countDown() } }
        try {
            val end = System.currentTimeMillis() + timeoutMs
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                cancel?.check()
                if (System.currentTimeMillis() > end) throw java.io.IOException(Y.t("Mail server did not answer in time.", "Posta sunucusu zamanında yanıt vermedi."))
            }
        } finally { waits.remove(id) }
        val r = box[0] ?: throw java.io.IOException("no answer")
        if (r.code == -1) throw java.io.IOException(Y.t("Network error: ", "Ağ hatası: ") + r.text.take(160))
        return r
    }

    fun reset() { Y.main.post { try { view?.destroy() } catch (_: Throwable) {}; view = null; viewOrigin = "" } }
}
