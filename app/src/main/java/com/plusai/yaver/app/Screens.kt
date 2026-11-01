package com.plusai.yaver.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** All conversations, newest first, with search. */
class ConvosActivity : Activity() {
    private lateinit var p: Palette
    private var rows: List<org.json.JSONObject> = emptyList()
    private lateinit var lv: ListView
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        p = Palette(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        val bar = U.bar(this, Y.t("Conversations", "Sohbetler"))
        bar.addView(U.iconButton(this, "＋", Color.WHITE) { startActivity(Intent(this, MainActivity::class.java).putExtra(Keys.ACTION, "new").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() })
        root.addView(bar, U.lp())
        val search = U.input(this, Y.t("Search conversations", "Sohbetlerde ara"))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { filter = s?.toString() ?: ""; load() }
        })
        root.addView(search, U.lp().apply { setMargins(U.dp(this@ConvosActivity, 10), U.dp(this@ConvosActivity, 8), U.dp(this@ConvosActivity, 10), U.dp(this@ConvosActivity, 4)) })
        lv = ListView(this).apply { divider = null }
        root.addView(lv, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        lv.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, MainActivity::class.java).putExtra(Keys.ACTION, "open_convo").putExtra(Keys.PAYLOAD, rows[pos].optString("id")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val r = rows[pos]
            AlertDialog.Builder(this).setTitle(r.optString("title")).setItems(arrayOf(Y.t("Rename", "Yeniden adlandır"), Y.t("Delete", "Sil"))) { _, w ->
                if (w == 0) {
                    val e = U.input(this, Y.t("Title", "Başlık"), r.optString("title"))
                    AlertDialog.Builder(this).setView(e).setPositiveButton("OK") { _, _ -> Convos.rename(r.optString("id"), e.text.toString()); load() }.show()
                } else {
                    AlertDialog.Builder(this).setMessage(Y.t("Delete this conversation?", "Bu sohbet silinsin mi?")).setPositiveButton(Y.t("Delete", "Sil")) { _, _ -> Convos.delete(r.optString("id")); load() }.setNegativeButton(Y.t("Cancel", "İptal"), null).show()
                }
            }.show()
            true
        }
        load()
    }

    private fun load() {
        Y.bg {
            val r = if (filter.trim().length >= 2) Convos.search(filter, 50).map { it.first } else Convos.list()
            Y.ui {
                rows = r
                lv.adapter = object : BaseAdapter() {
                    override fun getCount() = rows.size
                    override fun getItem(position: Int) = rows[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                        val o = rows[position]
                        val c = this@ConvosActivity
                        return LinearLayout(c).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(U.dp(c, 16), U.dp(c, 12), U.dp(c, 16), U.dp(c, 12))
                            addView(U.text(c, (if (o.optBoolean("background")) "⏰ " else "") + o.optString("title"), 16f, p.text).apply { maxLines = 2 })
                            addView(U.text(c, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(o.optLong("updated"))), 12.5f, p.muted))
                        }
                    }
                }
            }
        }
    }
}

/** Facts about the user and yaver's notes — view, edit, delete, add. */
class MemoryActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        p = Palette(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        root.addView(U.bar(this, Y.t("Memory", "Hafıza")), U.lp())
        val sv = android.widget.ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(context, 14), U.dp(context, 10), U.dp(context, 14), U.dp(context, 30)) }
        sv.addView(box)
        root.addView(sv, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        box.removeAllViews()
        for ((target, title) in listOf("user" to Y.t("About you", "Hakkınızda"), "notes" to Y.t("yaver's notes", "yaver'in notları"))) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, U.dp(context, 14), 0, U.dp(context, 6)) }
            head.addView(U.text(this, title, 17f, p.text, true), U.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(U.button(this, "＋ " + Y.t("Add", "Ekle")) { edit(target, null) })
            box.addView(head)
            val l = Memory.entries(target)
            if (l.isEmpty()) box.addView(U.text(this, Y.t("(empty)", "(boş)"), 14f, p.muted))
            for (e in l) box.addView(U.text(this, e, 14.5f, p.text).apply {
                background = U.round(p.surface, 10, context, p.line)
                setPadding(U.dp(context, 12), U.dp(context, 10), U.dp(context, 12), U.dp(context, 10))
                setOnClickListener { edit(target, e) }
            }, U.lp().apply { topMargin = U.dp(6) })
        }
    }

    private fun edit(target: String, old: String?) {
        val e = U.input(this, Y.t("Text", "Metin"), old ?: "", multi = true)
        val b = AlertDialog.Builder(this).setView(e)
            .setPositiveButton(Y.t("Save", "Kaydet")) { _, _ ->
                val l = Memory.entries(target)
                val t = e.text.toString().trim()
                if (old == null) { if (t.isNotEmpty()) l.add(t) } else { val i = l.indexOf(old); if (i >= 0) { if (t.isEmpty()) l.removeAt(i) else l[i] = t } }
                Memory.set(target, l); render()
            }
            .setNegativeButton(Y.t("Cancel", "İptal"), null)
        if (old != null) b.setNeutralButton(Y.t("Delete", "Sil")) { _, _ -> val l = Memory.entries(target); l.remove(old); Memory.set(target, l); render() }
        b.show()
    }
}

/**
 * Mail login on the server's own web page — for servers that log in through another site (ADFS, two-factor).
 * The page's cookies are shared with yaver's native mail client; the user name and password typed into the
 * standard Outlook form are remembered (encrypted) so yaver can log in again by itself next time.
 */
class MailLoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var origin = ""
    private var done = false

    inner class Capture {
        @JavascriptInterface fun creds(user: String?, pass: String?) {
            if (!user.isNullOrBlank() && !pass.isNullOrEmpty()) { Cfg.mailUser = user.trim(); Cfg.mailPass = pass }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        val p = Palette(this)
        val owa = Cfg.mailOwa.ifEmpty { Cfg.mailEws }
        origin = try { val u = java.net.URL(owa); "${u.protocol}://${u.host}" } catch (e: Exception) { "" }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        root.addView(U.bar(this, Y.t("Mail login", "Posta girişi")), U.lp())
        status = U.text(this, Y.t("Log in as usual. yaver continues by itself once your inbox is showing.", "Her zamanki gibi oturum açın. Gelen kutunuz görününce yaver kendiliğinden devam eder."), 13f, p.muted).apply { setPadding(U.dp(context, 14), U.dp(context, 6), U.dp(context, 14), U.dp(context, 6)) }
        root.addView(status, U.lp())
        web = WebView(this)
        root.addView(web, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (origin.isEmpty()) { status.text = Y.t("Enter your Outlook web address in Settings → Mail first.", "Önce Ayarlar → Posta'ya Outlook web adresinizi girin."); return }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Capture(), "YaverLogin")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // remember what is typed into the standard login form (stored encrypted, used for automatic re-login)
                view?.evaluateJavascript("""(function(){try{var f=document.forms[0];if(!f||f.__y)return;var pw=f.querySelector('input[type=password]');if(!pw)return;f.__y=1;f.addEventListener('submit',function(){var u=f.querySelector('input[name=username],input[type=email],input[name=UserName],input[type=text]');YaverLogin.creds(u?u.value:'',pw.value);},true);var b=f.querySelector('[type=submit],.signinbutton,#submitButton');if(b)b.addEventListener('click',function(){var u=f.querySelector('input[name=username],input[type=email],input[name=UserName],input[type=text]');YaverLogin.creds(u?u.value:'',pw.value);},true);}catch(e){}})()""", null)
                checkDone(url ?: "")
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                if (handler == null) return
                val u = U.input(this@MailLoginActivity, "DOMAIN\\user", Cfg.mailUser)
                val pw = U.input(this@MailLoginActivity, Y.t("Password", "Şifre"), "", password = true)
                val box = LinearLayout(this@MailLoginActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(context, 20), U.dp(context, 8), U.dp(context, 20), 0); addView(u); addView(pw) }
                AlertDialog.Builder(this@MailLoginActivity).setTitle(host ?: "").setView(box)
                    .setPositiveButton("OK") { _, _ -> Cfg.mailUser = u.text.toString().trim(); Cfg.mailPass = pw.text.toString(); handler.proceed(Cfg.mailUser, pw.text.toString()) }
                    .setNegativeButton(Y.t("Cancel", "İptal")) { _, _ -> handler.cancel() }.show()
            }
        }
        web.loadUrl(if (Cfg.mailOwa.isNotEmpty()) "$origin/owa/" else Cfg.mailEws)
    }

    private fun checkDone(url: String) {
        if (done) return
        val inOwa = url.contains("/owa", true) && !url.contains("/auth", true) && !url.contains("logon", true)
        val canary = Http.cookie("$origin/owa/", "X-OWA-CANARY") != null
        val ews = url.contains("/ews/", true)
        if ((inOwa && canary) || ews) {
            done = true
            CookieManager.getInstance().flush()
            status.text = Y.t("Logged in — connecting yaver…", "Oturum açıldı — yaver bağlanıyor…")
            Mail.reset()
            Y.bg {
                val msg = try { Mail.connect(); Y.t("Mail connected: ", "Posta bağlandı: ") + Mail.status } catch (e: Throwable) { Y.t("Could not connect: ", "Bağlanılamadı: ") + e.message }
                Y.ui { U.toast(this, msg); if (Mail.connected) finish() else { done = false; status.text = msg } }
            }
        }
    }

    override fun onDestroy() { try { web.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}

/** yaver's own browser for links (pages for the user to see). */
class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        val p = Palette(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(p.bar); setPadding(U.dp(context, 2), U.dp(context, 4), U.dp(context, 2), U.dp(context, 4)) }
        bar.addView(U.iconButton(this, "✕", Color.WHITE) { finish() })
        urlBox = EditText(this).apply {
            isSingleLine = true; textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            hint = Y.t("Search or type an address", "Ara veya adres yaz"); imeOptions = EditorInfo.IME_ACTION_GO; background = null
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false }
        }
        bar.addView(urlBox, U.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(U.iconButton(this, "↗", Color.WHITE) { web.url?.let { u -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Throwable) {} } })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val sc = u.scheme ?: return false
                if (sc == "http" || sc == "https" || sc == "about" || sc == "data" || sc == "blob" || sc == "javascript") return false
                try { startActivity(if (sc == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)) } catch (_: Throwable) {}
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { if (url != null) urlBox.setText(url) }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress.progress = newProgress; progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE }
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
        root.addView(bar, U.lp())
        root.addView(progress, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 3)))
        root.addView(web, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        go(intent.getStringExtra("url") ?: "")
    }

    private fun go(t0: String) {
        val t = t0.trim()
        if (t.isEmpty()) return
        web.loadUrl(when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(" ") && t.contains(".") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        })
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }

    override fun onDestroy() { try { web.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}
