package com.plusai.yaver.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Colours shared with the web interface so native screens look the same. */
class Palette(c: Context) {
    val dark = Y.dark(c)
    val bg = if (dark) Color.parseColor("#0E0F13") else Color.parseColor("#F7F7F8")
    val surface = if (dark) Color.parseColor("#17181D") else Color.WHITE
    val text = if (dark) Color.parseColor("#ECECF1") else Color.parseColor("#16171D")
    val muted = if (dark) Color.parseColor("#9A9BA6") else Color.parseColor("#6B6C76")
    val line = if (dark) Color.parseColor("#262730") else Color.parseColor("#E6E6EA")
    val accent = Color.parseColor("#6D5DFC")
}

object U {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun dp(v: Int) = (v * Y.app.resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    fun applyTheme(a: Activity) {
        val p = Palette(a)
        a.setTheme(if (p.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        a.window.statusBarColor = p.bg
        a.window.navigationBarColor = p.bg
        var flags = a.window.decorView.systemUiVisibility
        flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (android.os.Build.VERSION.SDK_INT >= 26) flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        a.window.decorView.systemUiVisibility = flags
    }

    fun toast(c: Context, s: String) = Y.ui { Toast.makeText(c, s, Toast.LENGTH_LONG).show() }

    fun iconButton(c: Context, glyph: String, color: Int, onClick: () -> Unit) = TextView(c).apply {
        text = glyph; textSize = 20f; setTextColor(color); gravity = Gravity.CENTER
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
        val ta = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = ta.getDrawable(0); ta.recycle()
        setOnClickListener { onClick() }
    }

    /** A top bar: close, title over subtitle, optional action. */
    fun header(a: Activity, title: String, subtitle: String, onClose: () -> Unit): Pair<LinearLayout, TextView> {
        val p = Palette(a)
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(p.bg); setPadding(dp(a, 4), dp(a, 6), dp(a, 12), dp(a, 6)) }
        bar.addView(iconButton(a, "✕", p.text, onClose))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(p.text); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); maxLines = 1 })
        val sub = TextView(a).apply { text = subtitle; textSize = 12.5f; setTextColor(p.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        col.addView(sub)
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Pair(bar, sub)
    }

    fun pill(c: Context, label: String, primary: Boolean, onClick: () -> Unit) = TextView(c).apply {
        val p = Palette(c)
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(if (primary) Color.WHITE else p.text)
        background = GradientDrawable().apply { cornerRadius = dp(c, 20).toFloat(); setColor(if (primary) p.accent else p.surface); if (!primary) setStroke(dp(c, 1), p.line) }
        setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9))
        setOnClickListener { onClick() }
    }
}

/**
 * Mail sign-in on the server's own page — works with gateways and authenticators (F5, ADFS, two-factor).
 * The saved user name and password are filled in (and the authenticator code too, if its secret is saved);
 * what you type is remembered for next time. The session is shared with yaver's mail client.
 */
class MailLoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var sub: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private var origin = ""
    private var done = false
    private var autoSubmits = 0
    private var lastAutoUrl = ""

    inner class Capture {
        @JavascriptInterface fun creds(user: String?, pass: String?) {
            if (pass.isNullOrEmpty() || pass.length < 3) return
            // an authenticator code typed into a password-type box must never replace the saved password
            if (Regex("^\\d{4,8}$").matches(pass) && Cfg.mailPass.isNotEmpty() && Cfg.mailPass.length > pass.length) { Diag.i("login", "ignored a code-like value as password"); return }
            if (pass != Cfg.mailPass) Diag.i("login", "password captured from the sign-in page (${pass.length} chars)")
            Cfg.mailPass = pass; if (!user.isNullOrBlank()) Cfg.mailUser = user.trim()
        }
        @JavascriptInterface fun filled(what: String?) { Y.ui { hint.text = what ?: "" } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        val p = Palette(this)
        origin = Mail.owaOrigin ?: Mail.originOf(Cfg.mailEws) ?: ""
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        val (bar, s) = U.header(this, Y.t("Sign in to mail", "Postada oturum aç"), origin.removePrefix("https://")) { finish() }
        sub = s
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(p.accent) }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 3)))
        val frame = FrameLayout(this)
        web = WebView(this)
        frame.addView(web)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // bottom helper bar
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(p.surface); setPadding(U.dp(context, 16), U.dp(context, 10), U.dp(context, 12), U.dp(context, 10)) }
        hint = TextView(this).apply { textSize = 13f; setTextColor(p.muted); text = Y.t("Sign in as usual. yaver continues by itself when your inbox appears.", "Her zamanki gibi oturum açın. Gelen kutunuz görününce yaver kendiliğinden devam eder.") }
        foot.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (Cfg.mailTotp.isNotEmpty()) foot.addView(U.pill(this, Y.t("Fill code", "Kodu doldur"), true) { fill(true) })
        foot.addView(U.pill(this, Y.t("New session", "Yeni oturum"), false) { newSession(false) })
        foot.addView(U.pill(this, Y.t("Connect", "Bağlan"), Cfg.mailTotp.isEmpty()) { tryConnect(true) })
        root.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        if (origin.isEmpty()) { hint.text = Y.t("Add your Outlook web address in Settings → Mail first.", "Önce Ayarlar → Posta'ya Outlook web adresinizi girin."); return }

        val st = web.settings
        st.javaScriptEnabled = true; st.domStorageEnabled = true; st.loadWithOverviewMode = true; st.useWideViewPort = true
        st.builtInZoomControls = true; st.displayZoomControls = false
        st.userAgentString = Mail.ua
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Capture(), "YaverLogin")
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress.progress = newProgress; progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { Diag.i("login", "page ${Diag.url(url)}"); url?.let { sub.text = Uri.parse(it).host + Uri.parse(it).path.orEmpty().take(40) } }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { view?.postDelayed({ probePage() }, 800) }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) Diag.e("login", "load error ${error?.errorCode} ${error?.description} at ${Diag.url(request.url?.toString())}")
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                if (request?.isForMainFrame == true) Diag.e("login", "HTTP ${errorResponse?.statusCode} at ${Diag.url(request.url?.toString())}")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                checkGatewayError(url ?: "")
                fill(false)
                view?.postDelayed({ fill(false) }, 1200)
                checkDone(url ?: "")
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                if (handler == null) return
                if (Cfg.mailUser.isNotEmpty() && Cfg.mailPass.isNotEmpty() && autoSubmits < 1) { autoSubmits++; handler.proceed(Cfg.mailUser, Cfg.mailPass); return }
                askHttpAuth(handler, host ?: "")
            }
        }
        // the session already ended: start from a clean slate, or the gateway keeps answering with the dead session
        SilentLogin.visible = true; SilentLogin.abort()
        if (Mail.needsSignIn || intent.getBooleanExtra("fresh", false)) { Diag.i("login", "session had ended: clearing the gateway cookies first"); MailSession.clear(origin) }
        web.loadUrl(startUrl())
        web.postDelayed(ticker, 3000)
    }

    private fun startUrl() = if (Mail.owaOrigin != null) "$origin/owa/" else Cfg.mailEws
    private var resets = 0

    /** Fresh session: forget the old gateway/OWA cookies and open the sign-in page again. */
    private fun newSession(auto: Boolean) {
        resets++
        autoSubmits = 0; lastAutoUrl = ""; done = false
        MailSession.clear(origin)
        try { web.clearCache(false) } catch (_: Throwable) {}
        hint.text = Y.t("Started a new session — sign in again.", "Yeni oturum başlatıldı — yeniden oturum açın.")
        Diag.i("login", "new session (${if (auto) "gateway error page" else "button"})")
        web.postDelayed({ web.loadUrl(startUrl()) }, 600)
    }

    /** F5 / gateway error pages ("Access was denied…", "session could not be established", "start a new session"). */
    private fun checkGatewayError(url: String) {
        if (origin.isEmpty()) return
        web.evaluateJavascript("(function(){var t=(document.body&&document.body.innerText||'').slice(0,4000);return /access (was |is )?denied|access policy|group policy|session could not be|session has (expired|been terminated|timed out)|start a new session|new session|oturum(unuz)? (sona erdi|kurulamad|zaman aşımı)|erişim reddedildi|yeni oturum|errorcode|error code/i.test(t)&&t.length<3500&&!document.querySelector('input[type=password]');})()") { r ->
            if (r == "true") {
                Diag.e("login", "gateway error page at ${Diag.url(url)}")
                if (resets < 2) newSession(true)
                else hint.text = Y.t("The mail gateway refuses a new session. Wait a few minutes (it may still count the old session) or ask IT.", "Posta ağ geçidi yeni oturuma izin vermiyor. Birkaç dakika bekleyin (eski oturumu hâlâ sayıyor olabilir) ya da BT'ye danışın.")
            }
        }
    }

    private fun askHttpAuth(handler: HttpAuthHandler, host: String) {
        val u = EditText(this).apply { hint = "DOMAIN\\user"; setText(Cfg.mailUser); isSingleLine = true }
        val pw = EditText(this).apply { hint = Y.t("Password", "Şifre"); isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(context, 22), U.dp(context, 8), U.dp(context, 22), 0); addView(u); addView(pw) }
        AlertDialog.Builder(this).setTitle(host).setView(box)
            .setPositiveButton(Y.t("Sign in", "Oturum aç")) { _, _ -> Cfg.mailUser = u.text.toString().trim(); Cfg.mailPass = pw.text.toString(); handler.proceed(Cfg.mailUser, pw.text.toString()) }
            .setNegativeButton(Y.t("Cancel", "İptal")) { _, _ -> handler.cancel() }.show()
    }

    /** Fills the user name, password and authenticator code into whatever sign-in form is showing. */
    private fun fill(forceCode: Boolean) {
        if (done) return
        val url = web.url ?: ""
        val auto = Cfg.mailAutoSubmit && autoSubmits < 2 && url != lastAutoUrl
        val code = if (Cfg.mailTotp.isNotEmpty()) Totp.code(Cfg.mailTotp) else ""
        val args = JSONObject().put("u", Cfg.mailUser).put("p", Cfg.mailPass).put("code", code).put("auto", auto || forceCode)
            .put("msgPw", Y.t("Password filled in by yaver", "Şifre yaver tarafından dolduruldu"))
            .put("msgCode", Y.t("Authenticator code filled in", "Doğrulama kodu dolduruldu"))
            .put("msgAsk", Y.t("Enter the code from your authenticator app", "Doğrulama uygulamanızdaki kodu girin"))
        val js = LoginJs.fill(args)
        web.evaluateJavascript(js) { r ->
            if (r?.contains("submitted") == true) { autoSubmits++; lastAutoUrl = url }
        }
    }

    private var lastTry = 0L
    private var lastProbeLog = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (done || isFinishing) return
            probePage()
            web.postDelayed(this, 2000)
        }
    }

    /** Looks at the page: on the mail server, past the sign-in steps, no password or code field showing → try to connect. */
    private fun probePage() {
        if (done || origin.isEmpty()) return
        val url = web.url ?: return
        val u = Uri.parse(url)
        val path = (u.path ?: "").lowercase()
        val sameHost = u.host.equals(Uri.parse(origin).host, true)
        val gate = Regex("my\\.policy|/vdesk|logon|/auth|adfs|/f5-|login", RegexOption.IGNORE_CASE).containsMatchIn(path)
        if (!sameHost || gate) return
        web.evaluateJavascript("(function(){var v=[].slice.call(document.querySelectorAll('input')).filter(function(e){return e.offsetParent!==null&&(e.type==='password'||/otp|code|token|challenge/i.test((e.name||'')+(e.id||'')));});return v.length===0&&document.readyState!=='loading';})()") { r ->
            if (r == "true") tryConnect(false) else if (System.currentTimeMillis() - lastProbeLog > 15000) { lastProbeLog = System.currentTimeMillis(); Diag.i("login", "on ${Diag.url(url)}: sign-in fields still showing") }
        }
    }

    private fun checkDone(url: String) { probePage() }

    private fun tryConnect(force: Boolean) {
        if (done) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < 8000) return
        Diag.i("login", "signed in? trying to connect (force=$force) at ${Diag.url(web.url)}")
        lastTry = now
        done = true
        CookieManager.getInstance().flush()
        hint.text = Y.t("Signed in — connecting yaver…", "Oturum açıldı — yaver bağlanıyor…")
        Y.bg {
            val err = try { Mail.signedIn(); Mail.connect(); null } catch (e: Throwable) { e.message ?: e.toString() }
            Y.ui {
                if (isFinishing) return@ui
                if (err == null) {
                    U.toast(this, Y.t("Mail connected", "Posta bağlandı")); Bridge.emit("mail", JSONObject().put("connected", true)); setResult(RESULT_OK); finish()
                } else {
                    done = false
                    Diag.e("login", "connect after sign-in: $err")
                    hint.text = Y.t("Not connected yet: ", "Henüz bağlanamadı: ") + err.removePrefix("SIGNIN:").take(220)
                }
            }
        }
    }

    override fun onDestroy() { SilentLogin.visible = false; try { web.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}

/** yaver's own browser for links. */
class BrowserActivity : Activity() {
    private var agent = false
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var sub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        val p = Palette(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        val (bar, s) = U.header(this, Y.t("Browser", "Tarayıcı"), "") { finish() }
        sub = s
        val titleView = (bar.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        bar.addView(U.iconButton(this, "↗", p.text) { web.url?.let { u -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Throwable) {} } })
        bar.addView(U.iconButton(this, "⤴", p.text) { web.url?.let { u -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null)) } })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(p.accent) }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 3)))
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        val st = web.settings
        st.javaScriptEnabled = true; st.domStorageEnabled = true; st.loadWithOverviewMode = true; st.useWideViewPort = true
        st.builtInZoomControls = true; st.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val sc = u.scheme ?: return false
                if (sc == "http" || sc == "https" || sc == "about" || sc == "data" || sc == "blob" || sc == "javascript") return false
                try { startActivity(if (sc == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)) } catch (_: Throwable) {}
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { sub.text = (url ?: "").let { u -> if (u.contains(LocalView.HOST)) Uri.decode(Uri.parse(u).encodedPath ?: "") else u } }
            /* a disk file opened from Files: its local CSS/JS/images come from the virtual disk (read-only, no bridge to the app) */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = LocalView.serve(request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress.progress = newProgress; progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE }
            override fun onReceivedTitle(view: WebView?, title: String?) { if (!title.isNullOrBlank()) titleView.text = title }
        }
        web.setDownloadListener { url, _, cd, mime, _ ->
            try {
                val dm = getSystemService(android.app.DownloadManager::class.java)
                val name = android.webkit.URLUtil.guessFileName(url, cd, mime)
                val r = android.app.DownloadManager.Request(Uri.parse(url)).setTitle(name).setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                CookieManager.getInstance().getCookie(url)?.let { r.addRequestHeader("Cookie", it) }
                if (android.os.Build.VERSION.SDK_INT >= 29) r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name) else r.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, name)
                dm?.enqueue(r); U.toast(this, Y.t("Downloading ", "İndiriliyor: ") + name)
            } catch (e: Throwable) { U.toast(this, e.message ?: "download failed") }
        }
        if (intent.getBooleanExtra("agent", false)) {
            agent = true
            val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(p.surface); setPadding(U.dp(context, 16), U.dp(context, 10), U.dp(context, 12), U.dp(context, 10)) }
            val why = intent.getStringExtra("why").orEmpty().ifBlank { Y.t("yaver needs you to do something on this page.", "yaver bu sayfada sizin bir şey yapmanızı istiyor.") }
            foot.addView(TextView(this).apply { textSize = 13f; setTextColor(p.text); text = why + "\n" + Y.t("Tap Done when finished — yaver continues from here.", "Bitince Bitti'ye dokunun — yaver buradan devam eder.") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(U.pill(this, Y.t("Done", "Bitti"), true) { finish() })
            root.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            st.userAgentString = Mail.ua
        }
        val t = (intent.getStringExtra("url") ?: "").trim()
        web.loadUrl(if (t.startsWith("http")) t else "https://duckduckgo.com/?q=" + Uri.encode(t))
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }

    override fun onDestroy() { if (agent) Browser.userDone(try { web.url } catch (_: Throwable) { null }) else if (intent.getBooleanExtra("follow", false)) Browser.followUser(try { web.url } catch (_: Throwable) { null }); try { web.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}


/** Cookies of the mail server and its gateway (F5 MRHSession, LastMRH_Session, F5_ST, OWA cadata/canary…). */
object MailSession {
    fun clear(origin: String) {
        val cm = CookieManager.getInstance()
        val host = try { Uri.parse(origin).host ?: "" } catch (e: Throwable) { "" }
        val names = LinkedHashSet<String>()
        for (path in listOf("/", "/owa/", "/my.policy", "/vdesk/", "/ews/")) cm.getCookie(origin + path)?.split(";")?.forEach { p -> p.substringBefore('=').trim().takeIf { it.isNotEmpty() }?.let { names.add(it) } }
        val parent = host.substringAfter('.', "").takeIf { it.contains('.') }
        for (n in names) for (path in listOf("/", "/owa", "/owa/", "/my.policy", "/vdesk", "/ews")) {
            val dead = "$n=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path"
            cm.setCookie(origin, dead)
            cm.setCookie(origin, "$dead; Secure")
            if (parent != null) { cm.setCookie(origin, "$dead; Domain=.$parent"); cm.setCookie(origin, "$dead; Domain=$host") }
        }
        cm.flush()
        Mail.reset()
        Diag.i("login", "cleared ${names.size} cookies for $host")
    }
}


/** The sign-in page helper, shared by the visible sign-in page and the silent re-login. */
object LoginJs {
    fun fill(args: JSONObject): String = """
(function(a){try{
 function vis(e){return e&&e.offsetParent!==null&&!e.disabled&&e.type!=='hidden';}
 function set(e,v){if(!e||!v)return false;if(e.value===v)return true;e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true;}
 var ins=[].slice.call(document.querySelectorAll('input')).filter(vis);
 function lab(e){var t='';try{if(e.id){var l=document.querySelector('label[for="'+e.id+'"]');if(l)t+=' '+l.innerText;}var p=e.closest('label,td,tr,div');if(p)t+=' '+(p.innerText||'').slice(0,120);}catch(x){}return t;}
 var OTPRX=/otp|token|challenge|passcode|one.?time|verif|code|kod|do[gğ]rulama|authenticat|totp|mfa|2fa|\bpin\b|tek kullan/i;
 function isOtp(e){var k=(e.name||'')+' '+(e.id||'')+' '+(e.autocomplete||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'');if(/one-time-code/.test(e.autocomplete||''))return true;if(OTPRX.test(k))return true;if(e.inputMode==='numeric'||e.type==='tel'||e.type==='number')return true;if(e.maxLength>0&&e.maxLength<=8)return true;return e.type==='password'&&OTPRX.test(lab(e))&&!/password|parola|şifre|sifre/i.test(lab(e));}
 var user=ins.filter(function(e){return e.type!=='password'&&!isOtp(e)&&/user|login|email|account|kullan|logon|domain/i.test((e.name||'')+(e.id||'')+(e.autocomplete||''));})[0]||null;
 var pws=ins.filter(function(e){return e.type==='password'&&!isOtp(e);});
 var bodyT=(document.body&&document.body.innerText||'').slice(0,3000);
 var otpPage=/one.?time|verification code|authenticator|do[gğ]rulama kodu|tek kullan[ıi]ml[ıi]k|\bOTP\b|token code|passcode|security code|g[uü]venlik kodu/i.test(bodyT);
 if(otpPage&&!user&&pws.length===1&&!ins.some(function(e){return e!==pws[0]&&e.type==='password';})){pws=[];}
 var pw=pws[0]||null;
 if(!user&&pw)user=ins.filter(function(e){return (e.type==='text'||e.type==='email')&&!isOtp(e);})[0]||null;
 var otp=ins.filter(function(e){return e!==pw&&e!==user&&isOtp(e);})[0]||null;
 if(!otp&&!pw&&otpPage){var only=ins.filter(function(e){return e.type==='text'||e.type==='tel'||e.type==='number'||e.type==='password';});if(only.length===1)otp=only[0];}
 var did=false,msg='';
 if(pw&&a.p){if(user&&a.u)set(user,a.u);if(set(pw,a.p)){did=true;msg=a.msgPw;}}
 if(otp){if(a.code){if(set(otp,a.code)){did=true;msg=a.msgCode;}}else{msg=a.msgAsk;otp.focus();}}
 if(pw&&!pw.__y){pw.__y=1;var f=pw.form;var cap=function(){if(!isOtp(pw))YaverLogin.creds(user?user.value:'',pw.value);};if(f)f.addEventListener('submit',cap,true);[].slice.call(document.querySelectorAll('button,input[type=submit],input[type=image],a')).forEach(function(b){b.addEventListener('click',cap,true);});pw.addEventListener('keydown',function(e){if(e.key==='Enter')cap();});}
 if(msg)YaverLogin.filled(msg);
 if(did&&a.auto){var el=otp&&a.code?otp:pw;var f2=el.form;setTimeout(function(){var b=f2?f2.querySelector('[type=submit],button:not([type=button]),input[type=image]'):null;if(b)b.click();else if(f2)f2.submit();},500);return 'submitted';}
 return did?'filled':'';
}catch(e){return '';}})($args)
"""

    /** true when the page is past the sign-in steps: no password or code field showing. */
    const val SIGNED_IN = "(function(){var v=[].slice.call(document.querySelectorAll('input')).filter(function(e){return e.offsetParent!==null&&(e.type==='password'||/otp|code|token|challenge|passcode/i.test((e.name||'')+(e.id||'')));});return v.length===0&&document.readyState!=='loading';})()"

    fun gate(path: String) = Regex("my\\.policy|/vdesk|logon|/auth|adfs|/f5-|login", RegexOption.IGNORE_CASE).containsMatchIn(path)
}
