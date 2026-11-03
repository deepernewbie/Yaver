package com.plusai.yaver.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps the company-mail session alive and brings it back by itself.
 *
 * - Keep-alive: a light mailbox request every few minutes on an alarm that also fires while the phone sleeps
 *   (gateways such as F5 end a session after ~15 idle minutes). It runs whether or not mail notifications are on.
 * - Silent re-login: when the session has ended, a hidden sign-in page is opened, the saved user name and password
 *   are filled in and submitted, and the authenticator code too when its secret is saved. With push approval the
 *   phone shows the approval request and yaver waits for it. Only when that cannot work is the user asked to sign in.
 */
object MailKeepAlive {
    const val ACTION = "com.plusai.yaver.MAIL_KEEPALIVE"
    private const val REQ = 4711

    fun minutes(): Int = Cfg.mailPoll.coerceIn(2, 5)

    fun arm(c: Context) {
        if (!Cfg.mailConfigured) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, REQ, Intent(c, KeepAliveReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = SystemClock.elapsedRealtime() + minutes() * 60_000L
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: Throwable) { Diag.e("keepalive", "arm", e) }
    }

    /** One keep-alive round (background thread). */
    fun ping() {
        if (!Cfg.mailConfigured) return
        try {
            Mail.use { it.probe() }
            Prefs.put("mail.lastOk", System.currentTimeMillis().toString())
        } catch (e: LoginRequired) {
            Diag.i("keepalive", "session not available: ${e.message?.take(120)}")
        } catch (e: Throwable) {
            Diag.e("keepalive", "ping", e)
        }
    }

    /** How long the last session lasted, for the diagnostic log. */
    fun lifeNote(): String {
        val now = System.currentTimeMillis()
        val at = Prefs.get("mail.signedInAt", "0").toLongOrNull() ?: 0L
        val ok = Prefs.get("mail.lastOk", "0").toLongOrNull() ?: 0L
        fun m(t: Long) = if (t <= 0) "?" else "${(now - t) / 60000} min"
        return "signed in ${m(at)} ago, last good request ${m(ok)} ago"
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Y.init(context)
        val pending = goAsync()
        val wl = try { context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaver:mailkeepalive")?.apply { acquire(110_000L) } } catch (e: Throwable) { null }
        Y.bg {
            try { MailKeepAlive.ping() } finally {
                MailKeepAlive.arm(context)
                try { wl?.release() } catch (_: Throwable) {}
                pending.finish()
            }
        }
    }
}

object SilentLogin {
    @Volatile private var lastTry = 0L
    @Volatile var running = false

    fun canTry(): Boolean = Cfg.mailAutoSubmit && Cfg.mailPass.isNotEmpty() && Cfg.mailUser.isNotEmpty() && Mail.owaOrigin != null &&
        Looper.myLooper() != Looper.getMainLooper() && !running && System.currentTimeMillis() - lastTry > 10 * 60_000L

    /**
     * Signs in again on a hidden page. Blocking (background thread only); true when the mailbox works again.
     * Waits up to [timeoutMs] (long enough for a push approval on the phone).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(cancel: Cancel? = null, timeoutMs: Long = 100_000L): Boolean {
        val origin = Mail.owaOrigin ?: return false
        lastTry = System.currentTimeMillis(); running = true
        Diag.i("relogin", "session ended (${MailKeepAlive.lifeNote()}) — signing in again silently")
        val done = CountDownLatch(1)
        var ok = false
        var needCode = false
        var submits = 0
        var lastUrl = ""
        var view: WebView? = null
        val host = Uri.parse(origin).host ?: ""
        var checking = false

        fun finish(result: Boolean) { if (done.count > 0L) { ok = result; done.countDown() } }

        lateinit var step: () -> Unit
        step = step@{
            val v = view ?: return@step
            if (done.count == 0L) return@step
            val url = v.url ?: ""
            val u = Uri.parse(url)
            val onMail = (u.host ?: "").equals(host, true) && !LoginJs.gate(u.path ?: "")
            v.evaluateJavascript(LoginJs.SIGNED_IN) { r ->
                if (done.count == 0L) return@evaluateJavascript
                if (onMail && r == "true" && !checking) {
                    checking = true
                    Y.bg {
                        val good = try { Mail.signedIn(); Mail.connect(cancel); true } catch (e: Throwable) { Diag.i("relogin", "not yet: ${e.message?.take(120)}"); false }
                        checking = false
                        if (good) finish(true)
                    }
                    return@evaluateJavascript
                }
                if (submits < 3 && url != lastUrl) {
                    val code = if (Cfg.mailTotp.isNotEmpty()) Totp.code(Cfg.mailTotp) else ""
                    val args = JSONObject().put("u", Cfg.mailUser).put("p", Cfg.mailPass).put("code", code).put("auto", true)
                        .put("msgPw", "pw").put("msgCode", "code").put("msgAsk", "ask")
                    v.evaluateJavascript(LoginJs.fill(args)) { res ->
                        if (res?.contains("submitted") == true) { submits++; lastUrl = url; Diag.i("relogin", "submitted the sign-in form at ${Diag.url(url)}") }
                    }
                }
            }
        }

        val tick = object : Runnable {
            override fun run() {
                if (done.count == 0L) return
                step()
                Y.main.postDelayed(this, 2000)
            }
        }

        Y.main.post {
            try {
                MailSession.clear(origin)
                val v = WebView(Y.app)
                val st = v.settings
                st.javaScriptEnabled = true; st.domStorageEnabled = true; st.userAgentString = Mail.ua
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
                v.addJavascriptInterface(object {
                    @JavascriptInterface fun creds(u: String?, p: String?) {}
                    @JavascriptInterface fun filled(m: String?) { if (m == "ask") { needCode = true; if (Cfg.mailTotp.isEmpty()) finish(false) } }
                }, "YaverLogin")
                v.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { Y.main.postDelayed({ step() }, 700) }
                    override fun onReceivedHttpAuthRequest(view: WebView?, handler: android.webkit.HttpAuthHandler?, host: String?, realm: String?) {
                        if (submits++ < 1) handler?.proceed(Cfg.mailUser, Cfg.mailPass) else { handler?.cancel(); finish(false) }
                    }
                }
                val d = Y.app.resources.displayMetrics.density
                v.measure(View.MeasureSpec.makeMeasureSpec((412 * d).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((892 * d).toInt(), View.MeasureSpec.EXACTLY))
                v.layout(0, 0, (412 * d).toInt(), (892 * d).toInt())
                v.onResume(); v.resumeTimers()
                view = v
                v.loadUrl("$origin/owa/")
                Y.main.postDelayed(tick, 3000)
            } catch (e: Throwable) { Diag.e("relogin", "web view", e); finish(false) }
        }

        try {
            val end = System.currentTimeMillis() + timeoutMs
            while (!done.await(500, TimeUnit.MILLISECONDS)) {
                cancel?.check()
                // a code is needed and none can be produced: give a push approval some time, then stop
                if (System.currentTimeMillis() > end) break
            }
        } catch (e: CancelledException) {
        } finally {
            running = false
            Y.main.post { try { view?.stopLoading(); view?.destroy() } catch (_: Throwable) {}; view = null }
        }
        Diag.i("relogin", if (ok) "signed in again silently" else "silent sign-in did not finish${if (needCode) " (the gateway asks for an authenticator code)" else ""}")
        if (ok) Prefs.put("mail.lastOk", System.currentTimeMillis().toString())
        return ok
    }
}
