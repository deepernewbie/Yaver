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
            if (!pass.isNullOrEmpty()) { Cfg.mailPass = pass; if (!user.isNullOrBlank()) Cfg.mailUser = user.trim() }
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { url?.let { sub.text = Uri.parse(it).host + Uri.parse(it).path.orEmpty().take(40) } }
            override fun onPageFinished(view: WebView?, url: String?) {
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
        web.loadUrl(if (Mail.owaOrigin != null) "$origin/owa/" else Cfg.mailEws)
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
        val js = """
(function(a){try{
 function vis(e){return e&&e.offsetParent!==null&&!e.disabled&&e.type!=='hidden';}
 function set(e,v){if(!e||!v)return false;if(e.value===v)return true;e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true;}
 var ins=[].slice.call(document.querySelectorAll('input')).filter(vis);
 var pw=ins.filter(function(e){return e.type==='password';})[0];
 var user=ins.filter(function(e){return /user|login|email|account|kullan/i.test((e.name||'')+(e.id||'')+(e.autocomplete||''))&&e.type!=='password';})[0]||(pw?ins.filter(function(e){return (e.type==='text'||e.type==='email')&&e!==pw;})[0]:null);
 var otp=ins.filter(function(e){var k=(e.name||'')+' '+(e.id||'')+' '+(e.autocomplete||'')+' '+(e.placeholder||'');return e!==pw&&e!==user&&(/otp|token|challenge|passcode|one-time|verification|code|kod|_F5_challenge/i.test(k)||e.inputMode==='numeric'||e.maxLength===6);})[0];
 if(!otp&&!pw){var only=ins.filter(function(e){return e.type==='text'||e.type==='tel'||e.type==='number';});if(only.length===1&&/code|kod|token|otp|verification|doğrulama|authenticator/i.test(document.body.innerText||''))otp=only[0];}
 var did=false,msg='';
 if(pw&&a.p){if(user&&a.u)set(user,a.u);if(set(pw,a.p)){did=true;msg=a.msgPw;}}
 if(otp){if(a.code){if(set(otp,a.code)){did=true;msg=a.msgCode;}}else{msg=a.msgAsk;otp.focus();}}
 if(pw&&!pw.__y){pw.__y=1;var f=pw.form;var cap=function(){YaverLogin.creds(user?user.value:'',pw.value);};if(f)f.addEventListener('submit',cap,true);[].slice.call(document.querySelectorAll('button,input[type=submit],input[type=image],a')).forEach(function(b){b.addEventListener('click',cap,true);});pw.addEventListener('keydown',function(e){if(e.key==='Enter')cap();});}
 if(msg)YaverLogin.filled(msg);
 if(did&&a.auto){var el=otp&&a.code?otp:pw;var f2=el.form;setTimeout(function(){var b=f2?f2.querySelector('[type=submit],button:not([type=button]),input[type=image]'):null;if(b)b.click();else if(f2)f2.submit();},500);return 'submitted';}
 return did?'filled':'';
}catch(e){return '';}})($args)
"""
        web.evaluateJavascript(js) { r ->
            if (r?.contains("submitted") == true) { autoSubmits++; lastAutoUrl = url }
        }
    }

    private fun checkDone(url: String) {
        if (done || origin.isEmpty()) return
        val u = Uri.parse(url)
        val path = (u.path ?: "").lowercase()
        val sameHost = u.host == Uri.parse(origin).host
        val inOwa = sameHost && path.startsWith("/owa") && !path.contains("/auth") && !path.contains("logon")
        val canary = Http.cookie("$origin/owa/", "X-OWA-CANARY") != null
        if ((inOwa && canary) || (sameHost && path.startsWith("/ews/"))) {
            done = true
            CookieManager.getInstance().flush()
            hint.text = Y.t("Signed in — connecting yaver…", "Oturum açıldı — yaver bağlanıyor…")
            Mail.signedIn()
            Y.bg {
                val ok = try { Mail.connect(); true } catch (e: Throwable) { Y.ui { hint.text = (e.message ?: "").take(200) }; false }
                Y.ui {
                    if (ok) { U.toast(this, Y.t("Mail connected", "Posta bağlandı")); Bridge.emit("mail", JSONObject().put("connected", true)); setResult(RESULT_OK); finish() }
                    else done = false
                }
            }
        }
    }

    override fun onDestroy() { try { web.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}

/** yaver's own browser for links. */
class BrowserActivity : Activity() {
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { sub.text = url ?: "" }
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
        val t = (intent.getStringExtra("url") ?: "").trim()
        web.loadUrl(if (t.startsWith("http")) t else "https://duckduckgo.com/?q=" + Uri.encode(t))
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }

    override fun onDestroy() { try { web.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}
