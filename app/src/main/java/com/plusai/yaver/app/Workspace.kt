package com.plusai.yaver.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** yaver's own folder for attachments, downloads, mail attachments and files made by code. */
object Workspace {
    val root: File get() = File(Y.app.filesDir, "workspace").apply { mkdirs() }

    fun resolve(path: String): File {
        var p = path.trim().replace('\\', '/')
        p = p.removePrefix("/workspace/").removePrefix("workspace/").removePrefix("/")
        val f = File(root, p).canonicalFile
        if (!f.path.startsWith(root.canonicalPath)) throw IllegalArgumentException("path is outside the workspace: $path")
        return f
    }

    fun rel(f: File): String = f.canonicalPath.removePrefix(root.canonicalPath).trimStart('/')

    fun safeName(name: String?): String {
        val n = (name ?: "").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return (if (n.isEmpty()) "file" else n).take(120)
    }

    fun unique(dir: File, name: String): File {
        dir.mkdirs()
        var f = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"); n++ }
        return f
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md" -> "text/markdown"; "txt", "log" -> "text/plain"; "csv" -> "text/csv"; "json" -> "application/json"
            "html", "htm" -> "text/html"; "svg" -> "image/svg+xml"; "py" -> "text/x-python"; "js" -> "text/javascript"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun isImage(name: String) = Regex("\\.(png|jpe?g|gif|webp|bmp|heic)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    fun isText(f: File): Boolean {
        if (Regex("\\.(txt|md|csv|tsv|json|xml|html?|js|ts|py|kt|java|c|cpp|h|css|yaml|yml|ini|log|sql|sh|svg)$", RegexOption.IGNORE_CASE).containsMatchIn(f.name)) return true
        if (!f.exists() || f.length() == 0L) return true
        val b = f.inputStream().use { val a = ByteArray(minOf(4096L, f.length()).toInt()); it.read(a); a }
        return b.none { it == 0.toByte() }
    }

    /** Copies a picked or shared file into the workspace (uploads/). Returns the workspace path. */
    fun importUri(c: Context, uri: Uri, dirName: String = "uploads"): String {
        var name = "file"
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) name = cur.getString(0) ?: name
            }
        } catch (_: Throwable) {}
        if (!name.contains('.')) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(c.contentResolver.getType(uri) ?: "")
            if (ext != null) name = "$name.$ext"
        }
        val f = unique(File(root, dirName), safeName(name))
        val ins = c.contentResolver.openInputStream(uri) ?: throw IOException("cannot read $uri")
        ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        changed(rel(f))
        return rel(f)
    }

    fun write(dirName: String, name: String, bytes: ByteArray): String {
        val f = unique(File(root, dirName), safeName(name))
        f.writeBytes(bytes)
        if (dirName != Spill.DIR) changed(rel(f))
        return rel(f)
    }

    /** Re-index documents and sync connected folders after a workspace change (never throws). */
    fun changed(path: String?) {
        try { WsChange.notify(path) } catch (e: Throwable) { Diag.e("workspace", "change hook", e) }
    }

    /** A picture as a data URL for the model, shrunk to at most 1600 px. */
    fun dataUrlForImage(path: String): String? = try {
        val f = resolve(path)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        if (bmp == null) null else {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Throwable) { null }

    fun dataUrlForBytes(bytes: ByteArray, mime: String): String? = try {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) null else {
            var b: Bitmap = bmp
            val max = maxOf(b.width, b.height)
            if (max > 1600) b = Bitmap.createScaledBitmap(b, b.width * 1600 / max, b.height * 1600 / max, true)
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 85, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Throwable) { null }

    fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    fun open(c: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(c, f), mime(f.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun share(c: Context, f: File, text: String = "") {
        val i = Intent(Intent.ACTION_SEND).setType(mime(f.name)).putExtra(Intent.EXTRA_STREAM, uri(c, f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, text)
        c.startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Saves a copy into the phone's Download/yaver folder. Returns where it went. */
    fun exportToDownloads(c: Context, f: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/yaver")
            }
            val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Android refused to create the file")
            val out = c.contentResolver.openOutputStream(u) ?: throw IOException("could not write")
            out.use { o -> f.inputStream().use { it.copyTo(o) } }
            return "Download/yaver/${f.name}"
        }
        val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("no download folder")
        val out = unique(dir, f.name)
        f.copyTo(out, true)
        return out.absolutePath
    }

    fun list(dir: File, depth: Int = 0, out: StringBuilder = StringBuilder()): String {
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return out.toString()
        for (f in items) {
            out.append("  ".repeat(depth)).append(if (f.isDirectory) "${f.name}/" else "${f.name} (${fmtBytes(f.length())})").append("\n")
            if (f.isDirectory && depth < 3) list(f, depth + 1, out)
            if (out.length > 20000) break
        }
        return out.toString()
    }

    fun fmtBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        else -> String.format("%.1f MB", n / 1048576.0)
    }
}
