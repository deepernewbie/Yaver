package com.plusai.yaver.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * The window. Its interface is drawn from bundled files (assets/app) for a polished look; everything it shows
 * comes from — and everything it does goes to — the native core through [Bridge].
 */
class MainActivity : Activity() {
    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val REQ_FILES = 61
        private const val REQ_SPEECH = 62
        private const val REQ_ZIP = 63
        private const val REQ_LOGIN = 64
    }

    var web: WebView? = null
    @Volatile var resumedNow = false
    private val pending = HashMap<Int, Int>() // request code → bridge call id

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        PermGate.activity = this
        Bridge.host = this
        val w = WebView(this)
        web = w
        w.setBackgroundColor(Palette(this).bg)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.textZoom = 100
        s.setSupportZoom(false)
        w.isVerticalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.addJavascriptInterface(Bridge.Api(), "YaverHost")
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = serve(request)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.host == HOST) return false
                openLink(u.toString())
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean { if (m != null && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) android.util.Log.e("yaver-ui", "${m.message()} @${m.lineNumber()}"); return true }
        }
        setContentView(w)
        w.loadUrl("https://$HOST/app/index.html")
        showCrashIfAny()
        askNotificationPermission()
        if (Prefs.on("alwaysOn", true)) AgentService.start(this)
        if (Migration.needed()) Migration.runOnce { msg -> Bridge.emit("toast", JSONObject().put("text", msg)); Bridge.emit("refresh", JSONObject()) }
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent) }

    private var lastSyncCheck = 0L

    override fun onResume() {
        super.onResume()
        resumedNow = true
        Bridge.host = this
        // live memory: pick up what another phone learned (at most once a minute)
        if (System.currentTimeMillis() - lastSyncCheck > 60_000L) { lastSyncCheck = System.currentTimeMillis(); Y.bg { try { if (OSync.on() && Mail.connected) OSync.check() } catch (e: Throwable) { Diag.e("osync", "resume check", e) } } }
        PermGate.activity = this
        Bridge.emit("resume", Bridge.state())
        Learn.touch()
        try { Folders.onForeground() } catch (e: Throwable) { Diag.e("folders", "foreground", e) }
    }

    override fun onPause() {
        resumedNow = false
        try { Folders.onBackground() } catch (e: Throwable) { Diag.e("folders", "background", e) }
        try { OSync.flush() } catch (e: Throwable) { Diag.e("osync", "flush", e) }
        super.onPause()
    }

    override fun onDestroy() {
        if (Bridge.host === this) { Bridge.host = null; Bridge.onHostGone() }
        if (PermGate.activity === this) PermGate.activity = null
        web?.destroy()
        web = null
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        val w = web ?: return super.onBackPressed()
        w.evaluateJavascript("(window.YV&&YV.back&&YV.back())?'1':'0'") { r -> if (r != "\"1\"") moveTaskToBack(true) }
    }

    /** Bundled interface files and workspace files (pictures in answers, attachments). */
    private fun serve(req: WebResourceRequest?): WebResourceResponse? {
        val u = req?.url ?: return null
        if (u.host != HOST) return null
        val path = u.path ?: return null
        return try {
            when {
                path.startsWith("/app/") -> {
                    val name = path.removePrefix("/")
                    WebResourceResponse(Workspace.mime(name).let { if (name.endsWith(".js")) "text/javascript" else it }, "utf-8", assets.open(name))
                }
                path.startsWith("/ws/") -> {
                    val f = Workspace.resolve(Uri.decode(path.removePrefix("/ws/")))
                    if (!f.exists()) null else WebResourceResponse(Workspace.mime(f.name), null, FileInputStream(f))
                }
                else -> null
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    fun openLink(url: String) {
        try {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url))
                url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("geo:") || url.startsWith("sms") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                else -> { val f = Workspace.resolve(url.removePrefix("file:").removePrefix("/ws/")); if (f.exists()) Workspace.open(this, f) }
            }
        } catch (e: Throwable) { U.toast(this, e.message ?: "cannot open") }
    }

    /** Bridge calls that need this screen. */
    fun uiCall(id: Int, m: String, a: JSONObject) {
        try {
            when (m) {
                "files.pick" -> {
                    pending[REQ_FILES] = id
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(a.str("type").ifEmpty { "*/*" }).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_FILES)
                }
                "voice.listen" -> {
                    pending[REQ_SPEECH] = id
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, Y.t("Speak to yaver", "yaver'e konuşun"))
                    if (Y.tr) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    startActivityForResult(i, REQ_SPEECH)
                }
                "folder.connect" -> { pending[Folders.REQ_PICK] = id; startActivityForResult(Folders.pickIntent(), Folders.REQ_PICK) }
                "mail.login" -> { pending[REQ_LOGIN] = id; startActivityForResult(Intent(this, MailLoginActivity::class.java), REQ_LOGIN) }
                "import.zip" -> { pending[REQ_ZIP] = id; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_ZIP) }
                "open.link" -> { openLink(a.str("url")); Bridge.ret(id, true, true) }
                "open.file" -> { Workspace.open(this, Workspace.resolve(a.str("path"))); Bridge.ret(id, true, true) }
                "share.text" -> { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("text")), null)); Bridge.ret(id, true, true) }
                "share.file" -> { Workspace.share(this, Workspace.resolve(a.str("path"))); Bridge.ret(id, true, true) }
                "system.open" -> {
                    val what = a.str("what")
                    if (what == "calendar") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 45); Bridge.ret(id, true, true); return }
                    val i = Bridge.systemIntent(what)
                    val ok = i != null && try { startActivity(i); true } catch (e: Throwable) { false }
                    if (!ok && what == "assistant") Bridge.systemIntent("assistantAlt")?.let { try { startActivity(it) } catch (_: Throwable) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
                    if (!ok && what == "battery") try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) {}
                    Bridge.ret(id, true, true)
                }
                else -> Bridge.ret(id, false, "unknown method $m")
            }
        } catch (e: Throwable) {
            Bridge.ret(id, false, e.message ?: e.toString())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pending.remove(requestCode) ?: return
        when (requestCode) {
            REQ_FILES -> {
                if (resultCode != RESULT_OK || data == null) { Bridge.ret(id, true, JSONArray()); return }
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri) else data.data?.let { uris.add(it) }
                Y.bg {
                    val out = JSONArray()
                    for (u in uris) try {
                        val p = Workspace.importUri(this, u)
                        val f = Workspace.resolve(p)
                        out.put(JSONObject().put("path", p).put("name", f.name).put("image", Workspace.isImage(p)).put("size", f.length()))
                    } catch (e: Throwable) { U.toast(this, e.message ?: "") }
                    Bridge.ret(id, true, out)
                }
            }
            REQ_SPEECH -> Bridge.ret(id, true, if (resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: "" else "")
            REQ_LOGIN -> Bridge.ret(id, true, Bridge.mailState())
            Folders.REQ_PICK -> {
                val u = data?.data
                if (resultCode != RESULT_OK || u == null) { Bridge.ret(id, true, ""); return }
                Folders.add(u) { msg -> Diag.i("folders", msg); Bridge.ret(id, true, msg) }
            }
            REQ_ZIP -> {
                val u = data?.data
                if (resultCode != RESULT_OK || u == null) { Bridge.ret(id, true, ""); return }
                Y.bg { try { Bridge.ret(id, true, Migration.importZip(u).also { Workspace.changed(null) }.ifEmpty { Y.t("Nothing new to import.", "İçe alınacak yeni bir şey yok.") }) } catch (e: Throwable) { Bridge.ret(id, false, e.message ?: "") } }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) PermGate.done()
        Bridge.emit("resume", Bridge.state())
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !Prefs.on("askedNotif", false)) {
            Prefs.set("askedNotif", true)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
    }

    private fun showCrashIfAny() {
        val f = File(filesDir, "last-crash.txt")
        if (!f.exists()) return
        val t = runCatching { f.readText() }.getOrDefault("")
        runCatching { f.copyTo(File(filesDir, "last-crash-shown.txt"), true) }
        f.delete()
        Bridge.launch(JSONObject().put("action", "crash").put("text", t.take(6000)))
    }

    fun showKeyboard() {
        web?.requestFocus()
        web?.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT) }, 300)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val nid = i.getIntExtra(Keys.NOTIF_ID, -1)
        if (nid > 0) getSystemService(NotificationManager::class.java)?.cancel(nid)
        val action = i.getStringExtra(Keys.ACTION)
        val payload = i.getStringExtra(Keys.PAYLOAD)
        val o = JSONObject()
        when {
            i.action == Intent.ACTION_ASSIST || i.action == "android.intent.action.VOICE_COMMAND" || i.action == Intent.ACTION_SEARCH_LONG_PRESS ->
                o.put("action", if (Prefs.on("voiceOnAssist", false)) "voice" else "assist")
            i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE -> {
                o.put("action", "share")
                o.put("text", listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()).joinToString("\n").trim())
                val uris = ArrayList<Uri>()
                if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) } else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
                if (uris.isNotEmpty()) {
                    Y.bg {
                        val arr = JSONArray()
                        for (u in uris) try { val p = Workspace.importUri(this, u); arr.put(JSONObject().put("path", p).put("name", p.substringAfterLast('/')).put("image", Workspace.isImage(p))) } catch (_: Throwable) {}
                        o.put("files", arr)
                        Bridge.launch(o)
                    }
                    return
                }
            }
            i.action == Intent.ACTION_PROCESS_TEXT -> o.put("action", "share").put("text", i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: "")
            action == "mail_login" -> { startActivity(Intent(this, MailLoginActivity::class.java)); return }
            action != null && action != "open" -> { o.put("action", action); if (payload != null) o.put("text", payload) }
            else -> return
        }
        i.removeExtra(Keys.ACTION); i.removeExtra(Keys.PAYLOAD)
        Bridge.launch(o)
    }
}
