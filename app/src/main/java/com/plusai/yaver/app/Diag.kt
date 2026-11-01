package com.plusai.yaver.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short diagnostic log the user can copy or share from Settings → Diagnostic log.
 * It records what yaver did (mail sign-in steps, connections, tool calls, errors) — never passwords, codes or keys,
 * and web addresses without their query strings.
 */
object Diag {
    private const val MAX = 400_000L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private fun file(): File? = try { File(Y.app.filesDir, "diag.log") } catch (e: Throwable) { null }

    fun url(u: String?): String = (u ?: "").substringBefore('?').substringBefore('#').take(160)

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg + (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun scrub(s: String): String {
        var x = s
        val secrets = listOf(Cfg.mailPass, Cfg.mailTotp, Cfg.llmKey).filter { it.length >= 4 }
        for (p in secrets) x = x.replace(p, "•••")
        return x.replace(Regex("(?i)(password|passwd|pwd|token|apikey|api_key|secret)=([^&\\s]+)"), "$1=•••")
    }

    private fun write(level: String, tag: String, msg: String) {
        try {
            val line = "${fmt.format(Date())} $level/$tag: ${scrub(msg).replace('\n', ' ').take(1200)}\n"
            synchronized(lock) {
                val f = file() ?: return
                if (f.length() > MAX) {
                    val keep = f.readText().takeLast((MAX / 2).toInt())
                    f.writeText(keep.substringAfter('\n'))
                }
                f.appendText(line)
            }
            android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, "yaver/$tag", msg.take(1000))
        } catch (_: Throwable) {}
    }

    fun text(): String = synchronized(lock) { file()?.takeIf { it.exists() }?.readText() ?: "" }

    fun report(): String {
        val st = StringBuilder()
        st.append("yaver ").append(try { Y.app.packageManager.getPackageInfo(Y.app.packageName, 0).versionName } catch (e: Throwable) { "?" })
            .append(" · Android ").append(android.os.Build.VERSION.RELEASE).append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
        st.append("mail: owa=").append(url(Cfg.mailOwa)).append(" ews=").append(url(Cfg.mailEws)).append(" mode=").append(Cfg.mailMode)
            .append(" user=").append(if (Cfg.mailUser.isEmpty()) "-" else "set").append(" pass=").append(if (Cfg.mailPass.isEmpty()) "-" else "set")
            .append(" totp=").append(if (Cfg.mailTotp.isEmpty()) "-" else "set").append(" connected=").append(Mail.connected).append('\n')
        st.append("model: ").append(url(Cfg.llmBase)).append(' ').append(Cfg.model).append("\n\n")
        st.append(text().takeLast(150_000))
        return st.toString()
    }

    fun clear() = synchronized(lock) { file()?.delete(); Unit }
}
