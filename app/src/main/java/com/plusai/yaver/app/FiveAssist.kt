package com.plusai.yaver.app

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The last screen yaver was invoked over (long-press home as the default digital assistant): the app's name, the
 * text visible on it and a screenshot in /workspace/uploads. Kept until the next chat message takes it (10 minutes max).
 */
object FiveScreen {
    class Cap(val app: String, val pkg: String, val text: String, val image: String?, val at: Long) {
        val written = CountDownLatch(if (image == null) 0 else 1)
        fun toJson(): JSONObject = JSONObject().put("app", app).put("pkg", pkg).put("text", text).put("image", image ?: JSONObject.NULL)
            .put("chars", text.length).put("at", at)
    }

    private const val TTL = 10 * 60_000L
    @Volatile private var last: Cap? = null

    fun set(c: Cap) { last = c }

    fun peek(): Cap? = last?.takeIf { System.currentTimeMillis() - it.at < TTL }

    /** Returns and clears the capture (waits up to 3 s for the screenshot file to be written). */
    fun take(): Cap? {
        val c = peek()
        last = null
        if (c != null) try { c.written.await(3, TimeUnit.SECONDS) } catch (_: Throwable) {}
        return c
    }

    fun clear() { last = null }

    /** The launch event for the screen: {action:"screen", app, pkg, chars, image, preview}. The full text stays native until chat.send {screen:true} or screen.context.take. */
    fun launchJson(c: Cap): JSONObject = JSONObject().put("action", "screen").put("app", c.app).put("pkg", c.pkg).put("chars", c.text.length)
        .put("image", c.image ?: JSONObject.NULL).put("preview", c.text.take(300))

    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }

    /** AssistStructure → plain nodes for [FiveLogic.flattenScreen] (at most 6 000 nodes). */
    fun nodes(s: AssistStructure): List<FiveLogic.SNode> {
        var budget = 6000
        fun conv(n: AssistStructure.ViewNode, depth: Int): FiveLogic.SNode {
            budget--
            val kids = ArrayList<FiveLogic.SNode>()
            if (depth < 80) for (i in 0 until n.childCount) { if (budget <= 0) break; n.getChildAt(i)?.let { kids.add(conv(it, depth + 1)) } }
            val hints = try { n.autofillHints?.any { it.contains("password", true) } == true } catch (_: Throwable) { false }
            return FiveLogic.SNode(n.text?.toString(), n.contentDescription?.toString(), n.visibility != View.VISIBLE, FiveLogic.isPassword(n.inputType) || hints, kids)
        }
        val out = ArrayList<FiveLogic.SNode>()
        for (w in 0 until s.windowNodeCount) { val r = s.getWindowNodeAt(w)?.rootViewNode ?: continue; out.add(conv(r, 0)) }
        return out
    }
}

/** yaver as the phone's digital assistant (Settings → Default apps → Digital assistant app). */
class FiveVoiceService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); Y.init(this); Diag.i("assist", "voice interaction service ready") }
}

class FiveVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession { Y.init(this); return FiveSession(this) }
}

/**
 * One assist invocation: waits (max 2.5 s) for the screen's text and screenshot the system hands over, stores them
 * in [FiveScreen] and opens yaver's chat with the "Screen: <app>" context ready.
 */
class FiveSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private val h = Handler(Looper.getMainLooper())
    private var shown = false
    private var wantAssist = false
    private var wantShot = false
    private var gotAssist = false
    private var gotShot = false
    private var done = false
    private var text = ""
    private var pkg = ""
    private var shot: Bitmap? = null
    private val timeout = Runnable { finishCapture() }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shown = true; done = false
        wantAssist = showFlags and SHOW_WITH_ASSIST != 0
        wantShot = showFlags and SHOW_WITH_SCREENSHOT != 0
        h.removeCallbacks(timeout)
        h.postDelayed(timeout, 2500)
        maybeDone()
    }

    override fun onHide() { h.removeCallbacks(timeout); reset(); super.onHide() }

    /** The system may keep this session for the next long-press: nothing from this capture may leak into it. */
    private fun reset() { shown = false; gotAssist = false; gotShot = false; text = ""; pkg = ""; shot = null }

    // API 29+: one call per activity on screen; the first (index 0) is the focused one
    override fun onHandleAssist(state: AssistState) {
        if (state.index == 0) handle(state.assistStructure, state.assistContent)
        if (state.index >= state.count - 1) { gotAssist = true; maybeDone() }
    }

    // API 26–28
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        handle(structure, content)
        gotAssist = true
        maybeDone()
    }

    private fun handle(structure: AssistStructure?, content: AssistContent?) {
        try {
            if (structure != null) {
                pkg = structure.activityComponent?.packageName ?: pkg
                text = FiveLogic.flattenScreen(FiveScreen.nodes(structure), 8000)
            }
            // a web page's address (browsers fill it in) helps the model
            val uri = content?.webUri?.toString()
            if (!uri.isNullOrEmpty() && !text.contains(uri)) text = (uri + "\n" + text).take(8000)
        } catch (e: Throwable) { Diag.e("assist", "structure", e) }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        shot = screenshot
        gotShot = true
        maybeDone()
    }

    private fun maybeDone() {
        if (done || !shown) return
        if ((wantAssist && !gotAssist) || (wantShot && !gotShot)) return
        finishCapture()
    }

    private fun finishCapture() {
        if (done) return
        done = true
        h.removeCallbacks(timeout)
        val c = context
        val bmp = shot; shot = null
        var cap: FiveScreen.Cap? = null
        if (text.isNotBlank() || bmp != null || pkg.isNotEmpty()) {
            var image: String? = null
            var target: File? = null
            if (bmp != null) try {
                val name = "screen-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
                target = Workspace.unique(File(Workspace.root, "uploads"), name)
                image = VPath.WS + "/" + Workspace.rel(target)
            } catch (e: Throwable) { Diag.e("assist", "screenshot name", e) }
            val app = if (pkg.isEmpty()) "" else FiveScreen.appLabel(c, pkg)
            val made = FiveScreen.Cap(app, pkg, text, image, System.currentTimeMillis())
            cap = made
            FiveScreen.set(made)
            if (bmp != null && target != null) {
                val f: File = target
                Y.bg {
                    try {
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        f.parentFile?.mkdirs(); f.writeBytes(out.toByteArray())
                        Workspace.changed(Workspace.rel(f))
                    } catch (e: Throwable) { Diag.e("assist", "screenshot save", e) } finally { made.written.countDown() }
                }
            }
            Diag.i("assist", "screen from $pkg: ${text.length} chars, screenshot=${bmp != null}")
        }
        try {
            val i = Intent(c, MainActivity::class.java).putExtra(Keys.TOKEN, Keys.token()).putExtra(Keys.ACTION, if (cap != null) "screen" else "assist")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startAssistantActivity(i)
        } catch (e: Throwable) {
            Diag.e("assist", "open yaver", e)
            try { c.startActivity(Intent(c, MainActivity::class.java).putExtra(Keys.TOKEN, Keys.token()).putExtra(Keys.ACTION, "assist").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        }
        reset()
        h.postDelayed({ try { finish() } catch (_: Throwable) { try { hide() } catch (_: Throwable) {} } }, 300)
    }
}

/**
 * The speech recognizer an assistant app must name. Choosing yaver as the assistant can also make this the phone's
 * default recognizer, so it forwards to the phone's real one (Google / Samsung) instead of failing.
 */
class FiveRecognitionService : RecognitionService() {
    private var rec: SpeechRecognizer? = null

    private fun target(): ComponentName? = try {
        val all = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0).mapNotNull { it.serviceInfo }.filter { it.packageName != packageName }
        val pref = listOf("com.google.android.googlequicksearchbox", "com.google.android.as", "com.google.android.tts", "com.samsung.android.bixby.agent")
        (pref.firstNotNullOfOrNull { p -> all.firstOrNull { it.packageName == p } } ?: all.firstOrNull())?.let { ComponentName(it.packageName, it.name) }
    } catch (_: Throwable) { null }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        val t = target()
        if (t == null) { runCatching { cb.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        rec?.destroy()
        val r = try { SpeechRecognizer.createSpeechRecognizer(this, t) } catch (e: Throwable) { runCatching { cb.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { runCatching { cb.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { runCatching { cb.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { runCatching { cb.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { runCatching { cb.bufferReceived(buffer ?: ByteArray(0)) } }
            override fun onEndOfSpeech() { runCatching { cb.endOfSpeech() } }
            override fun onError(error: Int) { runCatching { cb.error(error) } }
            override fun onResults(results: Bundle?) { runCatching { cb.results(results ?: Bundle()) } }
            override fun onPartialResults(partialResults: Bundle?) { runCatching { cb.partialResults(partialResults ?: Bundle()) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(recognizerIntent ?: Intent())
    }

    override fun onStopListening(listener: Callback?) { rec?.stopListening() }

    override fun onCancel(listener: Callback?) { rec?.cancel(); rec?.destroy(); rec = null }

    override fun onDestroy() { rec?.destroy(); rec = null; super.onDestroy() }
}
