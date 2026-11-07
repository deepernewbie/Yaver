package com.plusai.yaver.app

import android.Manifest
import android.widget.Toast
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
    private var zipPreview = false
    private var pickDir = "" // where files.pick puts the picked files ("" = /workspace/uploads)

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
        askMicIfAssistant()
        Bridge.emit("resume", Bridge.state())
        Learn.touch()
        try { Folders.onForeground() } catch (e: Throwable) { Diag.e("folders", "foreground", e) }
        // yaver 5.1: a time-zone change while yaver was closed moves local-time schedules; re-arm the schedule alarm
        Y.bg { try { S51Sched.syncZone(); S51Sched.arm() } catch (e: Throwable) { Diag.e("s51", "resume", e) } }
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
                    if (!f.isFile) null else {
                        // audio/video players ask for byte ranges to seek
                        val range = req.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
                        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
                        val len = f.length()
                        if (m == null || len == 0L) WebResourceResponse(Workspace.mime(f.name), null, 200, "OK", mapOf("Accept-Ranges" to "bytes", "Content-Length" to len.toString()), FileInputStream(f))
                        else {
                            val a = m.groupValues[1].toLongOrNull(); val b = m.groupValues[2].toLongOrNull()
                            val start = a ?: maxOf(0L, len - (b ?: len)); val end = if (a != null && b != null) minOf(b, len - 1) else len - 1
                            if (start >= len || start > end) WebResourceResponse(Workspace.mime(f.name), null, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$len"), ByteArrayInputStream(ByteArray(0)))
                            else {
                                val inp = FileInputStream(f); var skip = start; while (skip > 0) { val k = inp.skip(skip); if (k <= 0) break; skip -= k }
                                val n = end - start + 1
                                val body = object : java.io.FilterInputStream(inp) {
                                    var left = n
                                    override fun read(): Int { if (left <= 0) return -1; val r = super.read(); if (r >= 0) left--; return r }
                                    override fun read(b: ByteArray, off: Int, l: Int): Int { if (left <= 0) return -1; val r = super.read(b, off, minOf(l.toLong(), left).toInt()); if (r > 0) left -= r; return r }
                                }
                                WebResourceResponse(Workspace.mime(f.name), null, 206, "Partial Content", mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes $start-$end/$len", "Content-Length" to n.toString()), body)
                            }
                        }
                    }
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
                    pickDir = a.str("dir")  // a folder of the disk to upload into (the Files screen); default /workspace/uploads
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
                "import.zip" -> { pending[REQ_ZIP] = id; zipPreview = a.optBoolean("preview"); startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_ZIP) }
                "open.link" -> { openLink(a.str("url")); Bridge.ret(id, true, true) }
                "open.file" -> {
                    val f = Workspace.resolve(a.str("path"))
                    if (!f.isFile) { Bridge.ret(id, false, Y.t("File not found: ", "Dosya bulunamadı: ") + VPath.normTool(a.str("path"))); return }
                    try { Workspace.open(this, f); Bridge.ret(id, true, true) }
                    catch (e: android.content.ActivityNotFoundException) { Bridge.ret(id, false, Y.t("No app on this phone opens ", "Bu telefonda şunu açan uygulama yok: ") + f.name) }
                }
                "share.text" -> { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("text")), null)); Bridge.ret(id, true, true) }
                "share.file" -> {
                    val f = Workspace.resolve(a.str("path"))
                    if (!f.isFile) { Bridge.ret(id, false, Y.t("File not found: ", "Dosya bulunamadı: ") + VPath.normTool(a.str("path"))); return }
                    Workspace.share(this, f); Bridge.ret(id, true, true)
                }
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
                        val p = Workspace.importUri(this, u, pickDir.ifEmpty { "uploads" })
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
                // preview: the zip is staged and the screen asks Merge / Replace (StateIO); else the older direct import
                if (zipPreview) { Y.bg { try { Bridge.ret(id, true, StateIO.stage(u)) } catch (e: Throwable) { Bridge.ret(id, false, e.message ?: "") } }; return }
                Y.bg { try { Bridge.ret(id, true, Migration.importZip(u).also { Workspace.changed(null) }.ifEmpty { Y.t("Nothing new to import.", "İçe alınacak yeni bir şey yok.") }) } catch (e: Throwable) { Bridge.ret(id, false, e.message ?: "") } }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) PermGate.done()
        Bridge.emit("resume", Bridge.state())
    }

    /**
     * Choosing yaver as the digital assistant also makes it the phone's voice-input service (FiveRecognitionService
     * hands speech on to Google's/Samsung's recogniser). That hand-over needs the microphone permission, otherwise
     * other apps' voice typing fails: ask once, as soon as yaver is the assistant.
     */
    private fun askMicIfAssistant() {
        try {
            val asst = android.provider.Settings.Secure.getString(contentResolver, "assistant") ?: ""
            val voice = android.provider.Settings.Secure.getString(contentResolver, "voice_recognition_service") ?: ""
            val mine = asst.startsWith("$packageName/") || voice.startsWith("$packageName/")
            if (mine && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && !Prefs.on("askedMicAssist", false)) {
                Prefs.set("askedMicAssist", true)
                Toast.makeText(this, Y.t("yaver is now your assistant: allow the microphone so voice typing keeps working in other apps", "yaver artık asistanınız: diğer uygulamalarda sesle yazmanın çalışması için mikrofona izin verin"), Toast.LENGTH_LONG).show()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 46)
            }
        } catch (e: Throwable) { Diag.e("assist", "mic check", e) }
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
        var o = JSONObject()
        when {
            i.action == Intent.ACTION_ASSIST || i.action == "android.intent.action.VOICE_COMMAND" || i.action == Intent.ACTION_SEARCH_LONG_PRESS ->
                o.put("action", if (Prefs.on("voiceOnAssist", false)) "voice" else "assist")
            i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE -> {
                o.put("action", "share")
                // Chrome: EXTRA_SUBJECT = page title, EXTRA_TEXT = URL; SEND_MULTIPLE may carry a list of texts
                val texts = ArrayList<String?>()
                i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { texts.add(it.toString()) }
                if (i.action == Intent.ACTION_SEND_MULTIPLE) try { i.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.forEach { texts.add(it?.toString()) } } catch (_: Throwable) {}
                o.put("text", FiveLogic.shareText(i.getStringExtra(Intent.EXTRA_SUBJECT), texts, i.getStringExtra(Intent.EXTRA_HTML_TEXT)))
                val uris = ArrayList<Uri>()
                if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) } else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
                // some apps grant the files only through ClipData
                i.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k)?.uri?.let { if (it !in uris) uris.add(it) } }
                if (uris.isNotEmpty()) {
                    for (u in uris) try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}  // only document URIs allow it
                    i.removeExtra(Keys.ACTION); i.removeExtra(Keys.PAYLOAD)
                    Y.bg {
                        val arr = JSONArray()
                        var failed = 0
                        for (u in uris) try {
                            val p = Workspace.importUri(this, u)
                            arr.put(JSONObject().put("path", p).put("name", p.substringAfterLast('/')).put("image", Workspace.isImage(p)))
                            Workspace.changed(Workspace.rel(Workspace.resolve(p)))
                        } catch (e: Throwable) { failed++; Diag.e("share", "import $u", e) }
                        if (failed > 0) U.toast(this, Y.t("$failed shared file(s) could not be read", "Paylaşılan $failed dosya okunamadı"))
                        o.put("files", arr)
                        Bridge.launch(o)
                    }
                    return
                }
            }
            i.action == Intent.ACTION_PROCESS_TEXT -> o.put("action", "share").put("text", i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: "")
            action == "mail_login" -> { startActivity(Intent(this, MailLoginActivity::class.java)); return }
            // yaver 5: widget rows → a page of the app; the assistant session → chat with the screen as context
            action == "nav" -> { FiveLogic.launchFor(action, payload)?.let { o = it } }
            action == "screen" && i.getStringExtra(Keys.TOKEN) != Keys.token() -> o.put("action", "assist")
            action == "screen" -> {
                val cap = FiveScreen.peek()
                if (cap != null) o = FiveScreen.launchJson(cap) else o.put("action", "assist")
                showKeyboard()
            }
            action != null && action != "open" -> {
                // only yaver's own intents may run a prompt; from any other app the text just fills the composer
                val trusted = i.getStringExtra(Keys.TOKEN) == Keys.token()
                if (!trusted && action !in setOf("assist", "voice", "mail_login")) { Diag.i("intent", "untrusted '$action' from outside — composer only"); o.put("action", "share"); if (payload != null) o.put("text", payload) }
                else { o.put("action", action); if (payload != null) o.put("text", payload) }
            }
            else -> return
        }
        if (o.optString("action") == "assist" && i.getBooleanExtra(FiveWidget.EXTRA_KEYBOARD, false)) showKeyboard()
        i.removeExtra(Keys.ACTION); i.removeExtra(Keys.PAYLOAD); i.removeExtra(FiveWidget.EXTRA_KEYBOARD); i.removeExtra(Keys.TOKEN)
        Bridge.launch(o)
    }
}
