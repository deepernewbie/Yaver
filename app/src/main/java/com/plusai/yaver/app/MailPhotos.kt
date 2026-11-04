package com.plusai.yaver.app

import java.io.File
import java.security.MessageDigest

/**
 * Directory photos for the mail list and reading view (the original's Photos): OWA's persona photo through the
 * signed-in page, or EWS GetUserPhoto. Kept on disk — a month for a photo, a week for "no photo".
 */
object MailPhotos {
    private val busy = HashSet<String>()
    private fun dir() = File(Y.app.filesDir, "photos").apply { mkdirs() }
    private fun key(email: String) = MessageDigest.getInstance("SHA-1").digest(email.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** A data URL, "" when the person has no photo, or null when it could not be checked now. */
    fun get(email: String): String? {
        val e = email.trim().lowercase()
        if (!e.contains("@") || !Cfg.mailConfigured) return ""
        val k = key(e)
        val img = File(dir(), "$k.jpg"); val none = File(dir(), "$k.none")
        val now = System.currentTimeMillis()
        if (img.exists() && now - img.lastModified() < 30L * 864e5) return "data:image/jpeg;base64," + android.util.Base64.encodeToString(img.readBytes(), android.util.Base64.NO_WRAP)
        if (none.exists() && now - none.lastModified() < 7L * 864e5) return ""
        synchronized(busy) { if (!busy.add(k)) return null }
        try {
            val bytes = fetch(e) ?: return null
            if (bytes.isEmpty()) { none.writeText(""); img.delete(); return "" }
            img.writeBytes(bytes); none.delete()
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (t: Throwable) { Diag.e("photo", "fetch", t); return null }
        finally { synchronized(busy) { busy.remove(k) } }
    }

    /** Photo bytes, empty when there is none, null when the mailbox is not reachable now. */
    private fun fetch(email: String): ByteArray? {
        if (!Mail.connected) return null
        val mode = Cfg.mailMode
        val origin = Mail.owaOrigin
        if (origin != null && (mode.startsWith("web:") || mode.startsWith("owa:"))) {
            val url = "$origin/owa/service.svc/s/GetPersonaPhoto?email=${java.net.URLEncoder.encode(email, "UTF-8")}&size=HR96x96"
            val canary = Http.cookie("$origin/owa/", "X-OWA-CANARY") ?: ""
            val hdr = mapOf("X-OWA-CANARY" to canary, "X-Requested-With" to "XMLHttpRequest")
            if (mode.startsWith("web:")) {
                val r = WebOwa.send(origin, url, hdr, null, 15000, null, binary = true)
                if (r.redirected || r.code == 0) return null
                val ct = r.text.substringBefore('\n'); val b64 = r.text.substringAfter('\n', "")
                return if (r.code == 200 && ct.startsWith("image/") && b64.isNotEmpty()) android.util.Base64.decode(b64, android.util.Base64.DEFAULT) else ByteArray(0)
            }
            val r = Http.request(url, headers = hdr + ("User-Agent" to Mail.ua), follow = false, timeoutMs = 15000)
            return if (r.code == 200 && r.contentType.startsWith("image/")) r.body else if (r.code in 300..399 || r.code == 401 || r.code == 440) null else ByteArray(0)
        }
        // EWS
        return try {
            val doc = Mail.use { it.t.call("<m:GetUserPhoto><m:Email>${Xml.esc(email)}</m:Email><m:SizeRequested>HR96x96</m:SizeRequested></m:GetUserPhoto>", 15000) }
            val b64 = Xml.kids(doc, "PictureData").firstOrNull()?.textContent?.replace(Regex("\\s+"), "") ?: ""
            if (b64.isEmpty()) ByteArray(0) else android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (e: MailException) { ByteArray(0) }
    }
}
