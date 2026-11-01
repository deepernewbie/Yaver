package com.plusai.yaver

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

/**
 * Company mail without bookmarklets. yaver's Mail → Connect opens the Outlook-on-the-web (or EWS) page
 * in this built-in login screen. As soon as the inbox (or the EWS page) is showing, the bridge script is
 * injected automatically and talks to yaver directly — exactly what the "yaver bridge" bookmark did in a
 * browser tab, but the tab can never fall asleep and nothing has to be bookmarked.
 */
@SuppressLint("StaticFieldLeak")
object MailHost {
    var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    var visible = false
    var status = "Log in to your mail. yaver connects by itself once your inbox (or the EWS page) is showing."
    private var silent = false
    private var bridgeUp = false
    private var bridgeHost: String? = null
    private var mainFrameError = 0
    private var loginNotified = false
    private val authTried = HashMap<String, Long>()
    private var bridgeJs: String? = null

    private fun bridgeScript(): String {
        bridgeJs?.let { return it }
        val s = YaverCore.app.assets.open("mail-bridge.js").bufferedReader().use { it.readText() }
        bridgeJs = s
        return s
    }

    private const val DETECT_JS =
        "(function(){try{var p=location.pathname.toLowerCase(),h=location.href;" +
            "if(/^\\/ews\\//.test(p))return 'ews';" +
            "if(/\\/owa\\/auth\\//.test(p)||/logon|signin|adfs|oauth2|login\\.microsoftonline/i.test(h))return 'login';" +
            "if(/\\/owa(\\/|$)/.test(p)&&/X-OWA-CANARY=/i.test(document.cookie))return 'owa';" +
            "return 'no';}catch(e){return 'no';}})()"

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun ensure(): WebView {
        web?.let { return it }
        val base: Context = YaverCore.activity ?: YaverCore.app
        val w = MutableContextWrapper(base)
        wrapper = w
        val v = WebView(w)
        val s = v.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        try { v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) } catch (_: Throwable) {}
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        v.addJavascriptInterface(MailJs(), "YaverMailHost")
        v.webViewClient = Client()
        v.webChromeClient = YaverCore.DialogChrome()
        v.setDownloadListener { url, ua, cd, mime, _ -> Files.enqueueDownload(YaverCore.app, url, ua, cd, mime) }
        web = v
        return v
    }

    fun detach() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper?.setBaseContext(YaverCore.app)
    }

    fun attachTo(a: MainActivity) {
        wrapper?.setBaseContext(a)
    }

    /** Called by yaver's EWS.connect() through window.open(url, "yaver-mailbridge"). */
    fun open(url: String, silentOpen: Boolean) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        val v = ensure()
        if (bridgeUp) sendToMain("{\"yaver\":\"bye\"}")
        silent = silentOpen
        bridgeUp = false
        bridgeHost = null
        loginNotified = false
        status = "Log in to your mail. yaver connects by itself once your inbox (or the EWS page) is showing."
        v.loadUrl(url)
        if (!silentOpen) show() else YaverCore.activity?.ensureMailPanel()
        if (silentOpen) {
            YaverCore.main.postDelayed({ if (!bridgeUp && silent) notifyLogin() }, 30000)
        }
    }

    fun show() {
        ensure()
        visible = true
        silent = false
        val a = YaverCore.activity
        if (a != null) {
            a.showMail()
        } else {
            val i = Intent(YaverCore.app, MainActivity::class.java)
                .putExtra(Keys.ACTION, "mail_show")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { YaverCore.app.startActivity(i) } catch (_: Throwable) {}
        }
    }

    fun hide() {
        visible = false
        YaverCore.activity?.hideMail()
    }

    fun close() {
        hide()
        if (bridgeUp) sendToMain("{\"yaver\":\"bye\"}")
        bridgeUp = false
        web?.loadUrl("about:blank")
    }

    fun reload() { web?.reload() }

    /** A message from yaver (EWS.win.postMessage): requests, pings and status lines. */
    fun fromMain(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        if (o.optString("yaver") == "status") {
            status = o.optString("text", status)
            if (o.opt("ok") == true) {
                status = "Connected — yaver is reading your mail. You can go back to yaver."
                loginNotified = false
                if (visible) {
                    hide()
                    Toast.makeText(YaverCore.app, "Mail connected", Toast.LENGTH_SHORT).show()
                }
            }
            YaverCore.activity?.updateMailStatus()
        }
        web?.evaluateJavascript("window.__ynbRecv&&window.__ynbRecv($json)", null)
    }

    fun sendToMain(json: String) {
        YaverCore.js("window.__yn&&__yn.mailMsg(${JSONObject.quote(json)})")
    }

    /** Same as clicking the old "yaver bridge" bookmark — done automatically, or by the Connect button. */
    fun injectBridge(manual: Boolean) {
        val v = web ?: return
        val host = Uri.parse(v.url ?: "").host
        if (host.isNullOrEmpty()) {
            if (manual) Toast.makeText(YaverCore.app, "Open your mail page first", Toast.LENGTH_SHORT).show()
            return
        }
        bridgeHost = host
        bridgeUp = true
        status = "Connecting yaver to $host…"
        YaverCore.activity?.updateMailStatus()
        v.evaluateJavascript(bridgeScript(), null)
    }

    private fun detect(view: WebView) {
        if (bridgeUp || web !== view) return
        view.evaluateJavascript(DETECT_JS) { r ->
            val kind = r?.trim('"') ?: "no"
            if ((kind == "ews" || kind == "owa") && !bridgeUp && mainFrameError == 0) injectBridge(false)
            else if (kind == "login" && silent && !visible) notifyLogin()
        }
    }

    private fun notifyLogin() {
        if (loginNotified) return
        loginNotified = true
        Notifier.show(
            YaverCore.app, "Mail needs you to log in",
            "Tap to open the mail login so yaver can read your mail and calendar again.", "mail_show", null
        )
    }

    class MailJs {
        @JavascriptInterface
        fun post(json: String?) {
            if (json == null) return
            YaverCore.main.post { relay(json) }
        }

        private fun relay(json: String) {
            val v = web ?: return
            val host = Uri.parse(v.url ?: "").host
            if (host == null || host != bridgeHost) return
            if (json.contains("\"hello\"")) {
                status = "Connected to $host — checking your mailbox…"
                YaverCore.activity?.updateMailStatus()
            }
            sendToMain(json)
        }
    }

    private class Client : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (view !== web) return
            if (bridgeUp) {
                bridgeUp = false
                sendToMain("{\"yaver\":\"bye\"}")
            }
            mainFrameError = 0
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == null || view !== web) return
            YaverCore.activity?.updateMailStatus()
            for (d in longArrayOf(300, 2000, 5000, 10000, 20000)) {
                YaverCore.main.postDelayed({ detect(view) }, d)
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request != null && request.isForMainFrame && errorResponse != null && errorResponse.statusCode >= 400) {
                mainFrameError = errorResponse.statusCode
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (request == null) return false
            val u = request.url ?: return false
            val s = u.scheme ?: return false
            if (s == "http" || s == "https" || s == "about" || s == "data" || s == "blob" || s == "javascript") return false
            YaverCore.openOutside(YaverCore.activity ?: YaverCore.app, u)
            return true
        }

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
            if (handler == null) return
            val h = host ?: ""
            val saved = Creds.get(h)
            val now = System.currentTimeMillis()
            val last = authTried[h] ?: 0L
            if (saved != null && now - last > 20000) {
                authTried[h] = now
                handler.proceed(saved.first, saved.second)
                return
            }
            val act = YaverCore.activity
            if (act == null || act.isFinishing || act.isDestroyed) {
                handler.cancel()
                notifyLogin()
                return
            }
            if (!visible) show()
            act.askCredentials(h, realm, saved?.first) { user, pass, remember ->
                if (user == null || pass == null) {
                    handler.cancel()
                } else {
                    if (remember) Creds.put(h, user, pass) else Creds.remove(h)
                    authTried[h] = System.currentTimeMillis()
                    handler.proceed(user, pass)
                }
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (view === web) {
                if (bridgeUp) sendToMain("{\"yaver\":\"bye\"}")
                bridgeUp = false
                web = null
                view?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    try { it.destroy() } catch (_: Throwable) {}
                }
                hide()
            }
            return true
        }
    }
}
