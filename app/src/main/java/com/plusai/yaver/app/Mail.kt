package com.plusai.yaver.app

import android.webkit.WebSettings
import java.net.URL

/**
 * The company mailbox. Outlook on the web is used with the session of the sign-in page (works behind gateways
 * with an authenticator); EWS with the Windows login is used when it is reachable. The session is kept alive by
 * the background mail check; when the server ends it anyway, the user is asked to sign in again — the password
 * (and, if saved, the authenticator code) is filled in for them.
 */
object Mail {
    @Volatile private var ex: Exchange? = null
    @Volatile var status: String = ""
    @Volatile var lastError: String = ""
    @Volatile var needsSignIn: Boolean = false
    /** guards connect(); never waited on from the main thread — connecting waits for the main thread (the mail web view) */
    private val lock = java.util.concurrent.locks.ReentrantLock()
    /** bumped by reset(): a connect that started before a reset (old cookies, ended session) stops at the next step */
    private val gen = java.util.concurrent.atomic.AtomicInteger()

    /** The same browser identity as the sign-in page, so gateways see one client. */
    val ua: String by lazy { try { WebSettings.getDefaultUserAgent(Y.app) } catch (e: Throwable) { Http.MOBILE_UA } }

    val connected: Boolean get() = ex != null

    /**
     * Forgets the connection. Lock-free on purpose: the sign-in page calls it on the main thread while a background
     * connect may hold the lock and wait for the main thread — that deadlock froze the sign-in page ("not responding").
     */
    fun reset() { gen.incrementAndGet(); ex = null }

    private fun isMain() = try { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() } catch (_: Throwable) { false }

    fun originOf(u: String): String? = try { val x = URL(u.trim()); "${x.protocol}://${x.host}${if (x.port > 0 && x.port != x.defaultPort) ":" + x.port else ""}" } catch (e: Exception) { null }

    val owaOrigin: String? get() = if (Cfg.mailOwa.startsWith("http")) originOf(Cfg.mailOwa) else null

    private fun ewsUrl(u: String): String? {
        val o = originOf(u) ?: return null
        val path = try { URL(u.trim()).path } catch (e: Exception) { "" }
        return if (path.lowercase().startsWith("/ews/") && path.length > 5) o + path else "$o/EWS/Exchange.asmx"
    }

    fun connect(cancel: Cancel? = null): Exchange {
        ex?.let { return it }
        if (isMain()) throw MailException(Y.t("Mail is busy connecting — try again in a moment.", "Posta şu an bağlanıyor — biraz sonra yeniden deneyin."))
        repeat(4) {
            // wait for a connect already running (it may finish for us), but stay cancellable
            while (!lock.tryLock(250, java.util.concurrent.TimeUnit.MILLISECONDS)) cancel?.check()
            val got = try { ex ?: attempt(cancel, gen.get()) } finally { lock.unlock() }
            if (got != null) return got
        }
        throw MailException(Y.t("The mail connection kept being reset — try again.", "Posta bağlantısı sürekli sıfırlandı — yeniden deneyin."))
    }

    /** One connect pass under the lock; null when a reset() came meanwhile (the caller starts over). */
    private fun attempt(cancel: Cancel?, myGen: Int): Exchange? {
        run {
            if (!Cfg.mailConfigured) throw MailException(Y.t("Company mail is not set up yet — add your Outlook web address in Settings → Mail.", "Kurum postası henüz ayarlanmadı — Ayarlar → Posta'ya Outlook web adresinizi girin."))
            val user = Cfg.mailUser
            val pass = Cfg.mailPass
            val tries = ArrayList<Pair<String, () -> MailTransport>>()
            val owaO = owaOrigin
            val ews1 = if (Cfg.mailEws.startsWith("http")) ewsUrl(Cfg.mailEws) else null
            // the signed-in page's own way first: gateways (F5, ADFS) accept it exactly like the browser
            if (owaO != null) tries.add("web:$owaO" to { OwaTransport(owaO, user, pass, web = true) })
            if (owaO != null) tries.add("owa:$owaO" to { OwaTransport(owaO, user, pass) })
            if (ews1 != null) tries.add("ews:$ews1" to { EwsTransport(ews1, user, pass) })
            if (owaO != null && ews1 == null && user.isNotEmpty()) tries.add("ews:$owaO/EWS/Exchange.asmx" to { EwsTransport("$owaO/EWS/Exchange.asmx", user, pass) })
            val remembered = Cfg.mailMode
            tries.sortBy { if (it.first == remembered) 0 else 1 }
            val errors = ArrayList<String>()
            var signIn: LoginRequired? = null
            for ((key, make) in tries) {
                cancel?.check()
                // signed in again / settings changed meanwhile: these tries use the old session — start over fresh
                if (gen.get() != myGen) { Diag.i("mail", "connect superseded by a reset — starting over"); return null }
                try {
                    status = Y.t("Connecting…", "Bağlanılıyor…")
                    Diag.i("mail", "connect try $key")
                    val wasConnected = ex != null
                    val t = ChangeKeyTransport(make())
                    val e = Exchange(t)
                    e.probe(cancel)
                    Cfg.mailMode = key
                    if (gen.get() != myGen) { Diag.i("mail", "connected, but a reset came meanwhile — starting over"); return null }
                    ex = e
                    needsSignIn = false
                    status = t.label
                    lastError = ""
                    Diag.i("mail", "connected via $key")
                    // the "Bugün" card and the widget may still hold a "not connected" from before: refresh them now
                    try { if (!wasConnected) FiveBrief.refreshAsync(true) } catch (_: Throwable) {}
                    return e
                } catch (e: CancelledException) {
                    throw e
                } catch (e: LoginRequired) {
                    Diag.i("mail", "$key: sign-in needed (${e.message})")
                    signIn = e
                } catch (e: Throwable) {
                    Diag.e("mail", "$key failed", e)
                    errors.add("${key.substringBefore(':').uppercase()} ${try { URL(key.substringAfter(':')).host } catch (x: Exception) { "" }}: ${e.message}")
                }
            }
            if (gen.get() != myGen) { Diag.i("mail", "connect failed, but a reset came meanwhile — starting over"); return null }
            status = Y.t("Not connected", "Bağlı değil")
            signIn?.let { needsSignIn = true; lastError = it.message ?: ""; throw it }
            lastError = errors.joinToString(" · ")
            throw MailException(Y.t("Could not connect to the mailbox. ", "Posta kutusuna bağlanılamadı. ") + lastError)
        }
    }

    private fun askToSignIn(msg: String) {
        needsSignIn = true
        val last = Prefs.get("mail.loginNotified", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last > 3 * 3600_000L) {
            Prefs.put("mail.loginNotified", System.currentTimeMillis().toString())
            Notifier.show(Y.app, Y.t("Sign in to your mail", "Postada oturum açın"), Y.t("Your mail session ended. Tap to sign in — the password is filled in for you.", "Posta oturumunuz kapandı. Oturum açmak için dokunun — şifre otomatik doldurulur."), "mail_login", null, Notifier.CH_MAIL)
        }
        lastError = msg
    }

    /** Runs a mailbox operation; on an ended session it reconnects once, then asks the user to sign in. */
    /** Connects; when the session has ended, tries a silent sign-in once before asking the user. */
    private fun connectOrRelogin(cancel: Cancel?): Exchange = try { connect(cancel) } catch (l: LoginRequired) {
        Diag.i("mail", "session ended (${MailKeepAlive.lifeNote()})")
        if (SilentLogin.canTry() && SilentLogin.run(cancel)) connect(cancel) else throw l
    }

    fun <T> use(cancel: Cancel? = null, f: (Exchange) -> T): T {
        val e = try { connectOrRelogin(cancel) } catch (l: LoginRequired) { askToSignIn(l.message ?: ""); throw l }
        return try {
            f(e)
        } catch (a: AuthException) {
            reset()
            try { f(connectOrRelogin(cancel)) } catch (a2: AuthException) { askToSignIn(a2.message ?: ""); throw a2 }
        } catch (io: java.io.IOException) {
            reset()
            f(connect(cancel))
        }
    }

    /** Called after a successful sign-in on the page. */
    fun signedIn() {
        needsSignIn = false
        WebOwa.reset()   // the hidden mail page starts fresh with the new session's cookies
        Prefs.put("mail.signedInAt", System.currentTimeMillis().toString())
        try { MailKeepAlive.arm(Y.app) } catch (_: Throwable) {}
        Prefs.put("mail.loginNotified", "0")
        reset()
    }
}

/** RFC 6238 time-based one-time codes (Google / Microsoft Authenticator style), for the optional saved secret. */
object Totp {
    private fun base32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = s.uppercase().filter { it in alphabet }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray()
    }

    fun code(secret: String, time: Long = System.currentTimeMillis(), digits: Int = 6, period: Int = 30): String {
        val key = base32(secret)
        if (key.isEmpty()) return ""
        var counter = time / 1000 / period
        val msg = ByteArray(8)
        for (i in 7 downTo 0) { msg[i] = (counter and 0xff).toByte(); counter = counter shr 8 }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val o = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** Accepts a plain secret or an otpauth:// link from the QR code. */
    fun secretFrom(input: String): String {
        val s = input.trim()
        if (s.startsWith("otpauth://", true)) return Regex("[?&]secret=([^&]+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1) ?: ""
        return s.replace(" ", "")
    }
}
