package com.plusai.yaver.app

import java.net.URL

/**
 * The company mailbox. Picks the way in once (EWS with the Windows login, or Outlook on the web with forms
 * login), remembers it, and logs in again by itself whenever a session ends — no browser tab, no bookmark.
 */
object Mail {
    @Volatile private var ex: Exchange? = null
    @Volatile var status: String = ""
    @Volatile var lastError: String = ""
    private val lock = Any()

    val connected: Boolean get() = ex != null

    fun reset() { synchronized(lock) { ex = null } }

    private fun originOf(u: String): String? = try { val x = URL(u.trim()); "${x.protocol}://${x.host}${if (x.port > 0 && x.port != x.defaultPort) ":" + x.port else ""}" } catch (e: Exception) { null }

    private fun ewsUrl(u: String): String? {
        val o = originOf(u) ?: return null
        val path = try { URL(u.trim()).path } catch (e: Exception) { "" }
        return if (path.lowercase().startsWith("/ews/") && path.length > 5) o + path else "$o/EWS/Exchange.asmx"
    }

    /** Tries the remembered way first, then EWS address, EWS on the Outlook server, Outlook web. */
    fun connect(cancel: Cancel? = null): Exchange {
        synchronized(lock) {
            ex?.let { return it }
            if (!Cfg.mailConfigured) throw MailException(Y.t("Mail is not set up. Open Settings → Mail and enter your Outlook web (OWA) or EWS address, user name and password.", "Posta ayarlanmamış. Ayarlar → Posta'ya Outlook web (OWA) veya EWS adresinizi, kullanıcı adınızı ve şifrenizi girin."))
            val user = Cfg.mailUser
            val pass = Cfg.mailPass
            val tries = ArrayList<Pair<String, () -> MailTransport>>()
            val ews1 = if (Cfg.mailEws.startsWith("http")) ewsUrl(Cfg.mailEws) else null
            val owaO = if (Cfg.mailOwa.startsWith("http")) originOf(Cfg.mailOwa) else null
            val ews2 = owaO?.let { "$it/EWS/Exchange.asmx" }
            if (ews1 != null) tries.add("ews:$ews1" to { EwsTransport(ews1, user, pass) })
            if (ews2 != null && ews2 != ews1) tries.add("ews:$ews2" to { EwsTransport(ews2, user, pass) })
            if (owaO != null) tries.add("owa:$owaO" to { OwaTransport(owaO, user, pass) })
            val remembered = Cfg.mailMode
            tries.sortBy { if (it.first == remembered) 0 else 1 }
            val errors = ArrayList<String>()
            for ((key, make) in tries) {
                cancel?.check()
                try {
                    status = Y.t("Connecting ", "Bağlanılıyor: ") + key.substringAfter(':')
                    val t = make()
                    val e = Exchange(t)
                    e.probe(cancel)
                    Cfg.mailMode = key
                    ex = e
                    status = Y.t("Connected: ", "Bağlı: ") + t.label
                    lastError = ""
                    return e
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Throwable) {
                    errors.add("${key.substringBefore(':').uppercase()} ${URL(key.substringAfter(':')).host}: ${e.message}")
                    // a wrong password on Outlook web is final; other ways may still work
                }
            }
            lastError = errors.joinToString(" · ")
            status = Y.t("Not connected", "Bağlı değil")
            throw MailException(Y.t("Could not connect to the mailbox. ", "Posta kutusuna bağlanılamadı. ") + lastError)
        }
    }

    /** Runs a mailbox operation; on an ended session it reconnects (logs in again) once. */
    fun <T> use(cancel: Cancel? = null, f: (Exchange) -> T): T {
        val e = connect(cancel)
        return try {
            f(e)
        } catch (a: AuthException) {
            reset()
            try { f(connect(cancel)) } catch (a2: AuthException) {
                lastError = a2.message ?: ""
                val last = Prefs.get("mail.loginNotified", "0").toLongOrNull() ?: 0L
                if (System.currentTimeMillis() - last > 2 * 3600_000L) {
                    Prefs.put("mail.loginNotified", System.currentTimeMillis().toString())
                    Notifier.show(Y.app, Y.t("Mail login needed", "Posta için oturum açın"), a2.message ?: "", "mail_login", null, Notifier.CH_MAIL)
                }
                throw a2
            }
        } catch (io: java.io.IOException) {
            reset()
            f(connect(cancel))
        }
    }
}
