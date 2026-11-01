package com.plusai.yaver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.io.File

/** yaver's own browser: links from chats, "open" on files the agent made, OWA "Open Outlook" links. */
class BrowserActivity : Activity() {
    companion object {
        fun open(c: Context, url: String) {
            val i = Intent(c, BrowserActivity::class.java).putExtra("url", url)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        }

        fun openFile(c: Context, f: File) {
            val i = Intent(c, BrowserActivity::class.java).putExtra("file", f.absolutePath)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        }
    }

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#1f2430")
        val file = intent.getStringExtra("file")
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#1f2430"))
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        urlBox = EditText(this)
        urlBox.isSingleLine = true
        urlBox.textSize = 14f
        urlBox.setTextColor(Color.WHITE)
        urlBox.setHintTextColor(Color.LTGRAY)
        urlBox.hint = "Search or type an address"
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                go(urlBox.text.toString())
                true
            } else false
        }
        bar.addView(btn("✕") { finish() })
        bar.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn("↗") {
            val u = web.url
            if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) YaverCore.openOutside(this, Uri.parse(u))
        })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100

        web = WebView(this)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = file != null
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null) return false
                val u = request.url ?: return false
                val sc = u.scheme ?: return false
                if (sc == "http" || sc == "https" || sc == "about" || sc == "data" || sc == "blob" || sc == "file" || sc == "javascript") return false
                YaverCore.openOutside(this@BrowserActivity, u)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && !url.startsWith("file:")) urlBox.setText(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (file != null && title != null) urlBox.setText(title)
            }
        }
        web.setDownloadListener { url, ua, cd, mime, _ -> Files.enqueueDownload(this, url, ua, cd, mime) }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (file != null) {
            urlBox.setText(File(file).name)
            web.loadUrl(Uri.fromFile(File(file)).toString())
        } else {
            go(intent.getStringExtra("url") ?: "")
        }
    }

    private fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(" ") && t.contains(".") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        web.loadUrl(url)
        web.requestFocus()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        try {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
