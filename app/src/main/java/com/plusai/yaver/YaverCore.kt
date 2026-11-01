package com.plusai.yaver

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Owns the one WebView that runs yaver. It lives in the process, not in an Activity, so closing the
 * screen does not stop yaver: with the always-on service the page keeps running (mail checks,
 * heartbeats, reminders) and the next time the screen opens the same page is simply re-attached.
 */
@SuppressLint("StaticFieldLeak")
object YaverCore {
    const val HOST = "appassets.androidplatform.net"
    const val START_URL = "https://$HOST/yaver.html?nosplash"

    lateinit var app: Context
    var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    var activity: MainActivity? = null
    val main = Handler(Looper.getMainLooper())
    @Volatile var pageReady = false
    private val pending = ArrayList<String>()

    val isForeground: Boolean get() = activity?.resumedNow == true

    fun init(c: Context) {
        if (!::app.isInitialized) app = c.applicationContext
    }

    /** Main thread only. Creates the yaver WebView once; later calls just point it at the new screen. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun ensure(c: Context): WebView {
        init(c)
        val base: Context = if (c is Activity) c else app
        val existing = web
        if (existing != null) {
            wrapper?.setBaseContext(base)
            return existing
        }
        val w = MutableContextWrapper(base)
        wrapper = w
        WebView.setWebContentsDebuggingEnabled(true)
        val v = WebView(w)
        val s = v.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.textZoom = 100
        s.userAgentString = s.userAgentString + " YaverAndroid/1.0"
        try { v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) } catch (_: Throwable) {}
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        v.addJavascriptInterface(NativeBridge(app), "YaverAndroid")
        v.webViewClient = MainClient()
        v.webChromeClient = MainChrome()
        v.setDownloadListener { url, ua, cd, mime, _ -> Files.enqueueDownload(app, url, ua, cd, mime) }
        pageReady = false
        web = v
        v.loadUrl(START_URL)
        return v
    }

    /** Evaluate JavaScript in the yaver page (any thread). */
    fun js(code: String) {
        main.post { web?.evaluateJavascript(code, null) }
    }

    /** Like [js] but waits until the page has finished starting. */
    fun jsWhenReady(code: String) {
        main.post {
            if (pageReady && web != null) web?.evaluateJavascript(code, null) else pending.add(code)
        }
    }

    fun onReady() {
        main.post {
            pageReady = true
            val list = ArrayList(pending)
            pending.clear()
            for (c in list) web?.evaluateJavascript(c, null)
        }
    }

    fun detach(from: Activity) {
        if (activity === from) activity = null
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper?.setBaseContext(app)
        MailHost.detach()
    }

    fun destroy() {
        val old = web
        web = null
        pageReady = false
        old?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            try { it.destroy() } catch (_: Throwable) {}
        }
    }

    /** The page's renderer died (low memory): start a fresh one so the app does not crash. */
    fun recreate() {
        destroy()
        val a = activity
        if (a != null) a.attachWeb() else if (KeepAliveService.running) ensure(app)
    }

    fun serveAsset(c: Context, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url ?: return null
        if (u.host != HOST) return null
        var path = u.path ?: "/"
        if (path.isEmpty() || path == "/") path = "/yaver.html"
        val name = path.trimStart('/')
        return try {
            val ins = c.assets.open(name)
            val mime = Files.mimeFromName(name)
            val text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")
            WebResourceResponse(mime, if (text) "utf-8" else null, ins).apply {
                responseHeaders = mapOf("Cache-Control" to "no-cache", "Access-Control-Allow-Origin" to "*")
            }
        } catch (e: IOException) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), ByteArrayInputStream("not found".toByteArray()))
        }
    }

    fun openOutside(c: Context, u: Uri) {
        try {
            val s = u.scheme ?: ""
            val i = if (s == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Throwable) {
            android.widget.Toast.makeText(c, "No app can open $u", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private class MainClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request == null) return null
            return serveAsset(app, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (request == null) return false
            val u = request.url ?: return false
            if (!request.isForMainFrame) return false
            if (u.host == HOST) return false
            val s = u.scheme ?: ""
            if (s == "blob" || s == "data" || s == "about" || s == "javascript") return false
            if (s == "http" || s == "https") {
                BrowserActivity.open(activity ?: app, u.toString())
                return true
            }
            openOutside(activity ?: app, u)
            return true
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (view === web) main.post { recreate() }
            return true
        }
    }

    private class MainChrome : DialogChrome() {
        override fun onShowFileChooser(
            webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
        ): Boolean {
            val a = activity ?: return false
            if (filePathCallback == null) return false
            return a.startFileChooser(filePathCallback, fileChooserParams)
        }
    }

    /** alert / confirm / prompt shown on whichever yaver screen is open (answered automatically when none is). */
    open class DialogChrome : WebChromeClient() {
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val a = activity
            if (a == null || a.isFinishing) { result?.confirm(); return true }
            AlertDialog.Builder(a).setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.confirm() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val a = activity
            if (a == null || a.isFinishing) { result?.cancel(); return true }
            var done = false
            AlertDialog.Builder(a).setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> done = true; result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> done = true; result?.cancel() }
                .setOnDismissListener { if (!done) { done = true; result?.cancel() } }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
        ): Boolean {
            val a = activity
            if (a == null || a.isFinishing) { result?.cancel(); return true }
            val input = EditText(a).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                setText(defaultValue ?: "")
            }
            var done = false
            AlertDialog.Builder(a).setMessage(message ?: "").setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> done = true; result?.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> done = true; result?.cancel() }
                .setOnDismissListener { if (!done) { done = true; result?.cancel() } }
                .show()
            return true
        }
    }
}
