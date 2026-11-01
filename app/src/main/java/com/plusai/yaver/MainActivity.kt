package com.plusai.yaver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    companion object {
        const val REQ_PERMS = 41
        const val REQ_FILE = 42
        const val REQ_SPEECH = 43
        const val REQ_NOTIF = 44
        private const val MAX_SHARE_BYTES = 25L * 1024 * 1024
    }

    var resumedNow = false
    private lateinit var root: FrameLayout
    private var mailPanel: LinearLayout? = null
    private var mailStatus: TextView? = null
    private var mailFrame: FrameLayout? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        YaverCore.init(this)
        window.statusBarColor = Color.parseColor("#1f2430")
        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)
        setContentView(root)
        YaverCore.activity = this
        attachWeb()
        if (MailHost.web != null) {
            ensureMailPanel()
            if (MailHost.visible) showMail()
        }
        showCrashIfAny()
        askNotificationPermission()
        if (Prefs.on("alwaysOn", true)) {
            try { KeepAliveService.start(this) } catch (e: Throwable) { Toast.makeText(this, "Always-on could not start: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        YaverCore.activity = this
        YaverCore.web?.onResume()
    }

    override fun onPause() {
        resumedNow = false
        super.onPause()
    }

    override fun onDestroy() {
        resumedNow = false
        mailPanel = null
        mailFrame = null
        mailStatus = null
        YaverCore.detach(this)
        if (isFinishing && !Prefs.on("alwaysOn", true)) {
            YaverCore.destroy()
        }
        super.onDestroy()
    }

    /** Puts the (process-wide) yaver WebView into this screen. */
    fun attachWeb() {
        val w = YaverCore.ensure(this)
        (w.parent as? ViewGroup)?.removeView(w)
        root.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (mailPanel?.visibility == View.VISIBLE) {
            MailHost.hide()
            return
        }
        val w = YaverCore.web
        if (w == null) {
            moveTaskToBack(true)
            return
        }
        w.evaluateJavascript("(window.__yn&&__yn.back&&__yn.back())?'1':'0'") { r ->
            if (r != "\"1\"") moveTaskToBack(true)
        }
    }

    // ---------------- mail login screen ----------------

    private fun barButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    fun ensureMailPanel() {
        val mw = MailHost.ensure()
        MailHost.attachTo(this)
        if (mailPanel == null) {
            val panel = LinearLayout(this)
            panel.orientation = LinearLayout.VERTICAL
            panel.setBackgroundColor(Color.WHITE)
            panel.isClickable = true
            val bar = LinearLayout(this)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.setBackgroundColor(Color.parseColor("#1f2430"))
            bar.setPadding(dp(4), dp(4), dp(4), dp(4))
            val status = TextView(this)
            status.setTextColor(Color.WHITE)
            status.textSize = 12f
            status.maxLines = 3
            status.setPadding(dp(6), 0, dp(6), 0)
            bar.addView(barButton("← yaver") { MailHost.hide() })
            bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(barButton("⟳") { MailHost.reload() })
            bar.addView(barButton("Connect") { MailHost.injectBridge(true) })
            val frame = FrameLayout(this)
            panel.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            panel.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            panel.visibility = View.GONE
            root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            mailPanel = panel
            mailStatus = status
            mailFrame = frame
        }
        val frame = mailFrame ?: return
        val parent = mw.parent as? ViewGroup
        if (parent !== frame) {
            parent?.removeView(mw)
            frame.addView(mw, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        updateMailStatus()
    }

    fun showMail() {
        ensureMailPanel()
        mailPanel?.visibility = View.VISIBLE
        mailPanel?.bringToFront()
        MailHost.web?.requestFocus()
    }

    fun hideMail() {
        mailPanel?.visibility = View.GONE
        YaverCore.web?.requestFocus()
    }

    fun updateMailStatus() {
        val host = try { Uri.parse(MailHost.web?.url ?: "").host ?: "" } catch (e: Throwable) { "" }
        mailStatus?.text = if (host.isNotEmpty()) "$host\n${MailHost.status}" else MailHost.status
    }

    fun askCredentials(host: String, realm: String?, user: String?, cb: (String?, String?, Boolean) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val u = EditText(this)
        u.isSingleLine = true
        u.hint = "DOMAIN\\user or e-mail address"
        u.setText(user ?: "")
        val p = EditText(this)
        p.isSingleLine = true
        p.hint = "Password"
        p.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val rem = CheckBox(this)
        rem.text = "Remember on this phone (lets yaver reconnect by itself)"
        rem.isChecked = true
        box.addView(u)
        box.addView(p)
        box.addView(rem)
        var answered = false
        AlertDialog.Builder(this)
            .setTitle("Log in to $host")
            .setMessage(if (realm.isNullOrEmpty()) null else "Server: $realm")
            .setView(box)
            .setPositiveButton("Log in") { _, _ ->
                answered = true
                cb(u.text.toString(), p.text.toString(), rem.isChecked)
            }
            .setNegativeButton("Cancel") { _, _ ->
                answered = true
                cb(null, null, false)
            }
            .setOnDismissListener {
                if (!answered) {
                    answered = true
                    cb(null, null, false)
                }
            }
            .show()
    }

    // ---------------- keyboard, files, speech, permissions ----------------

    fun showKeyboard() {
        val w = YaverCore.web ?: return
        w.requestFocus()
        root.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(w, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    fun startFileChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams?): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        return try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            val types = params?.acceptTypes?.filter { it.contains("/") }?.distinct() ?: emptyList()
            if (types.isNotEmpty()) i.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            startActivityForResult(Intent.createChooser(i, "Choose files for yaver"), REQ_FILE)
            true
        } catch (e: Throwable) {
            fileCallback = null
            Toast.makeText(this, "Cannot open the file picker: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                val cb = fileCallback
                fileCallback = null
                if (cb != null) {
                    var uris: Array<Uri>? = null
                    if (resultCode == RESULT_OK && data != null) {
                        val clip = data.clipData
                        uris = if (clip != null && clip.itemCount > 0) {
                            Array(clip.itemCount) { k -> clip.getItemAt(k).uri }
                        } else {
                            val d = data.data
                            if (d != null) arrayOf(d) else null
                        }
                    }
                    cb.onReceiveValue(uris)
                }
            }
            REQ_SPEECH -> {
                if (resultCode == RESULT_OK) {
                    val list = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    Speech.deliver(list?.firstOrNull() ?: "", null)
                } else {
                    Speech.deliver(null, "cancelled")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) PermGate.done()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Prefs.on("askedNotif", false)
        ) {
            Prefs.set("askedNotif", true)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    private fun showCrashIfAny() {
        val f = File(filesDir, "last-crash.txt")
        if (!f.exists()) return
        val t = runCatching { f.readText() }.getOrDefault("")
        runCatching { f.copyTo(File(filesDir, "last-crash-shown.txt"), overwrite = true) }
        f.delete()
        val tv = TextView(this)
        tv.text = t.take(6000)
        tv.textSize = 11f
        tv.typeface = Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(16), dp(8), dp(16), dp(8))
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("yaver closed unexpectedly last time")
            .setView(sv)
            .setPositiveButton("Copy report") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("yaver crash", t))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- launch actions (assistant, shortcuts, notifications, share) ----------------

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val nid = i.getIntExtra(Keys.NOTIF_ID, -1)
        if (nid > 0) getSystemService(NotificationManager::class.java)?.cancel(nid)
        val action = i.getStringExtra(Keys.ACTION)
        val payload = i.getStringExtra(Keys.PAYLOAD)
        when {
            i.action == Intent.ACTION_ASSIST || i.action == "android.intent.action.VOICE_COMMAND" ||
                i.action == Intent.ACTION_SEARCH_LONG_PRESS -> sendLaunch("assist", null)
            i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE -> handleShare(i)
            i.action == Intent.ACTION_PROCESS_TEXT -> sendLaunch("share", i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            action == "mail_show" -> MailHost.show()
            action == "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            action != null && action != "open" -> sendLaunch(action, payload)
        }
        // do not run the same action again if Android re-delivers this intent
        i.removeExtra(Keys.ACTION)
        i.removeExtra(Keys.PAYLOAD)
        i.action = Intent.ACTION_MAIN
    }

    private fun sendLaunch(action: String, text: String?, files: JSONArray? = null) {
        val o = JSONObject().put("action", action)
        if (text != null) o.put("text", text)
        if (files != null) o.put("files", files)
        if (action == "assist" && Prefs.on("voiceOnAssist", false)) o.put("voice", true)
        YaverCore.jsWhenReady("window.__yn&&__yn.launch(${JSONObject.quote(o.toString())})")
    }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent) {
        val text = listOfNotNull(
            i.getStringExtra(Intent.EXTRA_SUBJECT),
            i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ).joinToString("\n").trim()
        val uris = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        } else {
            i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        }
        if (uris.isEmpty()) {
            sendLaunch("share", text)
            return
        }
        Toast.makeText(this, "Adding ${uris.size} file(s) to yaver…", Toast.LENGTH_SHORT).show()
        Thread {
            val files = JSONArray()
            var total = 0L
            for (u in uris) {
                try {
                    var name = "shared-file"
                    contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    val mime = contentResolver.getType(u) ?: Files.mimeFromName(name)
                    val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                    total += bytes.size
                    if (total > MAX_SHARE_BYTES) {
                        runOnUiThread { Toast.makeText(this, "Skipped $name: shared files are limited to 25 MB in total", Toast.LENGTH_LONG).show() }
                        continue
                    }
                    files.put(JSONObject().put("name", name).put("mime", mime).put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                } catch (e: Throwable) {
                    runOnUiThread { Toast.makeText(this, "Could not read a shared file: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
            runOnUiThread { sendLaunch("share", text.ifEmpty { null }, files) }
        }.start()
    }
}
