package com.plusai.yaver

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything the yaver page can ask of the phone. Exposed to the page as window.YaverAndroid.
 * Methods run on a binder thread; anything touching views is posted to the main thread.
 */
class NativeBridge(private val app: Context) {
    private val pool = Executors.newCachedThreadPool()

    // ---- network (replaces the CORS proxies) ----
    @JavascriptInterface
    fun fetchStart(id: Int, method: String?, url: String?, headers: String?, body: String?) {
        HttpEngine.start(id, method ?: "GET", url ?: "", headers ?: "{}", body ?: "")
    }

    @JavascriptInterface
    fun fetchAbort(id: Int) = HttpEngine.abort(id)

    // ---- generic async phone calls: calendar, alarms, reminders, speech… ----
    @JavascriptInterface
    fun call(cb: Int, method: String?, args: String?) {
        pool.execute {
            val res = JSONObject()
            try {
                val v = PhoneOps.run(app, method ?: "", JSONObject(if (args.isNullOrEmpty()) "{}" else args))
                res.put("ok", true)
                res.put("value", v ?: JSONObject.NULL)
            } catch (e: Throwable) {
                res.put("ok", false)
                res.put("error", e.message ?: e.toString())
            }
            YaverCore.js("window.__yn&&__yn.ret($cb,${JSONObject.quote(res.toString())})")
        }
    }

    // ---- page lifecycle and settings ----
    @JavascriptInterface
    fun ready() = YaverCore.onReady()

    @JavascriptInterface
    fun isForeground(): Boolean = YaverCore.isForeground

    @JavascriptInterface
    fun getPref(k: String?): String = Prefs.get(k ?: "", "")

    @JavascriptInterface
    fun setPref(k: String?, v: String?) {
        if (!k.isNullOrEmpty()) Prefs.put(k, v ?: "")
    }

    @JavascriptInterface
    fun openSettings() {
        YaverCore.main.post {
            val a = YaverCore.activity
            val i = Intent(app, SettingsActivity::class.java)
            if (a != null) a.startActivity(i) else app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @JavascriptInterface
    fun showKeyboard() {
        YaverCore.main.post { YaverCore.activity?.showKeyboard() }
    }

    // ---- notifications, toasts, clipboard, sharing ----
    @JavascriptInterface
    fun notify(title: String?, text: String?, action: String?, payload: String?) {
        Notifier.show(app, title ?: "yaver", text ?: "", action, payload)
    }

    @JavascriptInterface
    fun toast(text: String?) {
        YaverCore.main.post { Toast.makeText(app, text ?: "", Toast.LENGTH_LONG).show() }
    }

    @JavascriptInterface
    fun copy(text: String?) {
        YaverCore.main.post {
            try {
                app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("yaver", text ?: ""))
            } catch (_: Throwable) {}
        }
    }

    @JavascriptInterface
    fun shareText(title: String?, text: String?) {
        YaverCore.main.post {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text ?: "")
            if (!title.isNullOrEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title)
            Files.startSafely(app, Intent.createChooser(send, title ?: "Share"))
        }
    }

    @JavascriptInterface
    fun shareFiles(pathsJson: String?, text: String?) {
        YaverCore.main.post { Files.shareFiles(app, pathsJson ?: "[]", text ?: "") }
    }

    @JavascriptInterface
    fun print(title: String?) {
        YaverCore.main.post {
            val a = YaverCore.activity ?: return@post
            val w = YaverCore.web ?: return@post
            try {
                val pm = a.getSystemService(PrintManager::class.java) ?: return@post
                val name = (title ?: "yaver").ifBlank { "yaver" }
                pm.print(name, w.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
            } catch (e: Throwable) {
                Toast.makeText(app, "Printing failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- browsing ----
    @JavascriptInterface
    fun openBrowser(url: String?) {
        if (url.isNullOrEmpty()) return
        YaverCore.main.post { BrowserActivity.open(YaverCore.activity ?: app, url) }
    }

    @JavascriptInterface
    fun openExternal(url: String?) {
        if (url.isNullOrEmpty()) return
        YaverCore.main.post { YaverCore.openOutside(YaverCore.activity ?: app, Uri.parse(url)) }
    }

    @JavascriptInterface
    fun viewUri(uri: String?, mime: String?) {
        if (uri.isNullOrEmpty()) return
        YaverCore.main.post {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), if (mime.isNullOrEmpty()) "*/*" else mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Files.startSafely(app, i)
        }
    }

    // ---- company mail: the built-in login screen replaces the bookmarklet bridge ----
    @JavascriptInterface
    fun mailOpen(url: String?, silent: Boolean) {
        YaverCore.main.post { MailHost.open(url ?: "", silent) }
    }

    @JavascriptInterface
    fun mailPost(json: String?) {
        if (json == null) return
        YaverCore.main.post { MailHost.fromMain(json) }
    }

    @JavascriptInterface
    fun mailShow() {
        YaverCore.main.post { MailHost.show() }
    }

    @JavascriptInterface
    fun mailClose() {
        YaverCore.main.post { MailHost.close() }
    }

    // ---- files from the page (downloads, "open", share) arrive in base64 chunks ----
    @JavascriptInterface
    fun fileBegin(name: String?, mime: String?): Int = Files.begin(app, name, mime)

    @JavascriptInterface
    fun fileChunk(id: Int, b64: String?) = Files.chunk(id, b64)

    @JavascriptInterface
    fun fileEnd(id: Int, target: String?, text: String?): String = Files.end(app, id, target ?: "download", text)
}

/** Downloads, viewing and sharing of files. */
object Files {
    private class Pending(val file: File, val mime: String)

    private val open = ConcurrentHashMap<Int, Pending>()
    private val seq = AtomicInteger(0)

    fun authority(c: Context) = c.packageName + ".files"

    fun safeName(name: String?): String {
        val n = (name ?: "").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return (if (n.isEmpty()) "file" else n).take(120)
    }

    fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "md" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "wasm" -> "application/wasm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun begin(c: Context, name: String?, mime: String?): Int {
        val id = seq.incrementAndGet()
        val dir = File(c.cacheDir, "out")
        dir.mkdirs()
        val f = File(dir, "$id-" + safeName(name))
        f.delete()
        open[id] = Pending(f, mime ?: "")
        return id
    }

    fun chunk(id: Int, b64: String?) {
        val p = open[id] ?: return
        if (b64.isNullOrEmpty()) return
        FileOutputStream(p.file, true).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
    }

    fun end(c: Context, id: Int, target: String, text: String?): String {
        val p = open.remove(id) ?: return JSONObject().put("ok", false).put("error", "unknown file").toString()
        return try {
            val f = p.file
            if (!f.exists()) f.createNewFile()
            val name = f.name.substringAfter('-')
            val mime = p.mime.substringBefore(';').trim().ifEmpty { mimeFromName(name) }
            val r: JSONObject = when (target) {
                "stage" -> JSONObject().put("ok", true).put("path", f.absolutePath)
                "view" -> {
                    view(c, f, mime)
                    JSONObject().put("ok", true).put("target", "view").put("where", name)
                }
                else -> {
                    val saved = saveToDownloads(c, f, name, mime)
                    f.delete()
                    saved
                }
            }
            r.toString()
        } catch (e: Throwable) {
            JSONObject().put("ok", false).put("error", e.message ?: e.toString()).toString()
        }
    }

    fun startSafely(c: Context, i: Intent) {
        try {
            val a = YaverCore.activity
            if (a != null && !a.isFinishing) a.startActivity(i) else c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            Toast.makeText(c, "No app on this phone can open that", Toast.LENGTH_LONG).show()
        }
    }

    /** html / text open in yaver's browser; everything else (PDF, images, Office files…) in the matching app. */
    fun view(c: Context, f: File, mime: String) {
        YaverCore.main.post {
            try {
                if (mime.startsWith("text/") || mime.contains("html") || mime.contains("svg") || mime.contains("json") || mime.contains("xml")) {
                    BrowserActivity.openFile(YaverCore.activity ?: c, f)
                } else {
                    val uri = FileProvider.getUriForFile(c, authority(c), f)
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startSafely(c, i)
                }
            } catch (e: Throwable) {
                Toast.makeText(c, "Could not open: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return f
    }

    /** Saves into Download/yaver (Android 10+) or the app's own Download folder (Android 8–9). */
    fun saveToDownloads(c: Context, src: File, name: String, mime: String): JSONObject {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/yaver")
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Android refused to create the file")
            val out = c.contentResolver.openOutputStream(uri) ?: throw IOException("could not write the file")
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            return JSONObject().put("ok", true).put("target", "download").put("where", "Download/yaver/$name")
                .put("uri", uri.toString()).put("mime", mime)
        }
        val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(c.filesDir, "Download")
        dir.mkdirs()
        val out = uniqueFile(dir, name)
        src.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(c, authority(c), out)
        return JSONObject().put("ok", true).put("target", "download").put("where", out.absolutePath)
            .put("uri", uri.toString()).put("mime", mime)
    }

    fun shareFiles(c: Context, pathsJson: String, text: String) {
        try {
            val arr = JSONArray(pathsJson)
            val uris = ArrayList<Uri>()
            for (k in 0 until arr.length()) {
                val f = File(arr.getString(k))
                if (f.exists()) uris.add(FileProvider.getUriForFile(c, authority(c), f))
            }
            if (uris.isEmpty()) return
            val i = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            i.type = "*/*"
            if (text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, text)
            val clip = ClipData.newRawUri("files", uris[0])
            for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startSafely(c, Intent.createChooser(i, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Throwable) {
            Toast.makeText(c, "Could not share: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Real downloads (links to files on websites) go through Android's download manager. */
    fun enqueueDownload(c: Context, url: String?, ua: String?, cd: String?, mime: String?) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val r = DownloadManager.Request(Uri.parse(url))
            val ck = CookieManager.getInstance().getCookie(url)
            if (!ck.isNullOrEmpty()) r.addRequestHeader("Cookie", ck)
            r.addRequestHeader("User-Agent", ua ?: HttpEngine.userAgent())
            if (!mime.isNullOrEmpty()) r.setMimeType(mime)
            r.setTitle(name)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= 29) r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            else r.setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, name)
            c.getSystemService(DownloadManager::class.java)?.enqueue(r)
            Toast.makeText(c, "Downloading $name…", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(c, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
