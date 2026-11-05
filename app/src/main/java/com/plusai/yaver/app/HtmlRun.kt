package com.plusai.yaver.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * run_html: runs an HTML file from the workspace in a hidden web view and reports what goes wrong. Local CSS / JS /
 * images / fonts / ES modules (and fetch() of local files) are served from the workspace under a private address, so
 * file names and line numbers in errors are the real ones. A probe injected on the <head> line (line numbers stay
 * right) reports JS errors, promise rejections, console errors/warnings, failed resources and requests, broken images,
 * horizontal overflow and the visible text. Optional actions (click / type / key / eval / wait) exercise the page;
 * alert/confirm/prompt are answered automatically.
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled", "JavascriptInterface")
object HtmlRun {
    private const val HOST = "htmlrun.yaver.invalid"
    private const val BASE = "https://$HOST"

    class Res(val path: String, val width: Int) {
        val errors = ArrayList<JSONObject>(); val console = ArrayList<JSONObject>(); val resources = ArrayList<JSONObject>(); val net = ArrayList<JSONObject>()
        val snaps = ArrayList<JSONObject>(); val actions = HashMap<Int, JSONObject>(); val missing = ArrayList<Pair<String, String>>()
        @Volatile var started = false; @Volatile var loaded = false; var timeout = false
    }

    private fun urlOf(vp: String) = BASE + vp.removePrefix(VPath.WS).split('/').joinToString("/") { Uri.encode(it) }
    private fun vpOf(url: String): String? {
        if (!url.startsWith("$BASE/")) return null
        val path = Uri.parse(url).path ?: return null
        return VPath.norm(VPath.WS + path)
    }
    /** Shows local addresses as workspace paths. */
    private fun local(s: String) = s.replace(Regex(Regex.escape(BASE) + "(/[^\\s\"'():?#]*)")) { m -> VPath.WS + Uri.decode(m.groupValues[1]) }

    private fun probe(token: String, waitMs: Int): String = """<script>(function(){var T=${JSONObject.quote(token)};function S(t,d){try{YaverProbe.send(T,t,JSON.stringify(d))}catch(e){}}
window.addEventListener("error",function(e){var el=e.target;if(el&&el!==window&&el.tagName){S("resource",{tag:el.tagName.toLowerCase(),url:el.currentSrc||el.src||el.href||""});return}S("error",{msg:e.message,file:e.filename,line:e.lineno,col:e.colno,stack:e.error&&e.error.stack?String(e.error.stack).slice(0,1500):""})},true);
window.addEventListener("unhandledrejection",function(e){var r=e.reason;S("error",{msg:"Unhandled promise rejection: "+(r&&(r.message||r))+"",stack:r&&r.stack?String(r.stack).slice(0,1500):""})});
try{var of=window.fetch;window.fetch=function(u,o){var url=String(u&&u.url||u);return of.apply(this,arguments).then(function(r){if(!r.ok)S("net",{url:url,status:r.status});return r},function(e){S("net",{url:url,error:String(e&&e.message||e)});throw e})}}catch(_){}
try{var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this.__u=String(u);return XO.apply(this,arguments)};XMLHttpRequest.prototype.send=function(){var x=this;x.addEventListener("loadend",function(){if(x.status===0||x.status>=400)S("net",{url:x.__u,status:x.status||0,error:x.status?"":"blocked or offline"})});return XS.apply(this,arguments)}}catch(_){}
["error","warn"].forEach(function(k){var o=console[k];console[k]=function(){var a=[].slice.call(arguments).map(function(x){try{return typeof x==="string"?x:(x instanceof Error?x.stack||x.message:JSON.stringify(x))}catch(_){return String(x)}}).join(" ");S("console",{level:k,msg:a.slice(0,1500)});return o.apply(console,arguments)}});
function mem(){var d={};return{getItem:function(k){return k in d?d[k]:null},setItem:function(k,v){d[k]=String(v)},removeItem:function(k){delete d[k]},clear:function(){d={}},key:function(i){return Object.keys(d)[i]||null},get length(){return Object.keys(d).length}}}
["localStorage","sessionStorage"].forEach(function(k){try{window[k].getItem("_")}catch(e){try{Object.defineProperty(window,k,{value:mem(),configurable:true})}catch(_){}}});
window.alert=function(m){S("console",{level:"info",msg:"alert(): "+m})};window.confirm=function(m){S("console",{level:"info",msg:"confirm(): "+m+" → OK"});return true};window.prompt=function(m,d){S("console",{level:"info",msg:"prompt(): "+m});return d||""};
function snap(label){var b=document.body,de=document.documentElement;S("snap",{label:label,title:document.title,text:b?b.innerText.slice(0,4000):"",elements:document.querySelectorAll("*").length,overflowX:de.scrollWidth>innerWidth+2?de.scrollWidth:0,height:de.scrollHeight,broken:[].slice.call(document.images).filter(function(i){return i.complete&&i.naturalWidth===0&&(i.currentSrc||i.src)}).map(function(i){return i.currentSrc||i.src}).slice(0,20),emptyLinks:[].slice.call(document.querySelectorAll("a[href='#'],a:not([href])")).length,noAlt:[].slice.call(document.images).filter(function(i){return !i.hasAttribute("alt")}).length})}
window.addEventListener("load",function(){setTimeout(function(){snap("load")},$waitMs)});
window.__yvAct=function(m){if(!m||m.__yp!==T||!m.act)return;var a=m.act,r={ok:true};try{var el=a.selector?document.querySelector(a.selector):null;if(a.selector&&!el)throw new Error("no element matches "+a.selector);
if(a.click){el.scrollIntoView&&el.scrollIntoView();el.click()}else if(a.type!=null){el.focus();el.value=a.type;el.dispatchEvent(new Event("input",{bubbles:true}));el.dispatchEvent(new Event("change",{bubbles:true}))}else if(a.key){(el||document.activeElement||document.body).dispatchEvent(new KeyboardEvent("keydown",{key:a.key,bubbles:true}))}else if(a.eval){r.value=String((0,eval)(a.eval)).slice(0,1000)}}catch(err){r={ok:false,error:String(err&&err.message||err)}}
setTimeout(function(){S("acted",{i:m.i,r:r});snap("after action "+(m.i+1))},a.wait||300)};
S("start",{})})();</script>""".replace("\n", "")

    private var view: WebView? = null
    @Volatile private var cur: Res? = null
    @Volatile private var token = ""
    @Volatile private var mainPath = ""
    @Volatile private var mainHtml = ""
    private var onLoad: CountDownLatch? = null
    private val waits = HashMap<Int, CountDownLatch>()

    class Probe {
        @JavascriptInterface fun send(t: String?, type: String?, json: String?) {
            val r = cur ?: return
            if (t != token) return
            val d = try { JSONObject(json ?: "{}") } catch (e: Exception) { JSONObject() }
            synchronized(r) {
                when (type) {
                    "start" -> r.started = true
                    "error" -> r.errors.add(d)
                    "console" -> r.console.add(d)
                    "net" -> { val u = d.optString("url"); if (r.net.none { it.optString("url") == u }) r.net.add(d.put("url", if (u.startsWith("data:")) "(inline data)" else u)) }
                    "resource" -> r.resources.add(d)
                    "snap" -> { r.snaps.add(d); if (d.optString("label") == "load") { r.loaded = true; onLoad?.countDown() } }
                    "acted" -> { val i = d.optInt("i"); r.actions[i] = d.optJSONObject("r") ?: JSONObject().put("ok", false); synchronized(waits) { waits[i]?.countDown() } }
                    else -> {}
                }
                Unit
            }
        }
    }

    private fun <T> onMain(timeoutS: Long = 20, f: () -> T): T {
        val latch = CountDownLatch(1); var r: T? = null; var err: Throwable? = null
        Y.main.post { try { r = f() } catch (e: Throwable) { err = e } finally { latch.countDown() } }
        if (!latch.await(timeoutS, TimeUnit.SECONDS)) throw IllegalStateException("the page runner is not responding")
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun serve(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url?.toString() ?: return null
        val vp = vpOf(url) ?: return null
        val r = cur
        fun notFound(): WebResourceResponse {
            if (r != null && !vp.endsWith("/favicon.ico")) synchronized(r) {
                val from = req.requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value?.let { vpOf(it) } ?: mainPath
                if (r.missing.none { it.first == vp }) r.missing.add(Pair(vp, from))
            }
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
        }
        return try {
            if (vp == mainPath) return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(mainHtml.toByteArray()))
            val f = VPath.file(vp)
            if (!f.isFile) notFound() else {
                val mime = when (f.extension.lowercase()) { "mjs", "js" -> "text/javascript"; "css" -> "text/css"; "wasm" -> "application/wasm"; "json" -> "application/json"; else -> Workspace.mime(f.name) }
                WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript")) "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"), f.inputStream())
            }
        } catch (e: Exception) { notFound() }
    }

    private fun ensure(width: Int, height: Int): WebView = onMain {
        val v = view ?: WebView(Y.app).also { w ->
            val s = w.settings
            s.javaScriptEnabled = true; s.domStorageEnabled = true; s.loadWithOverviewMode = false; s.useWideViewPort = false
            s.setSupportMultipleWindows(false); s.javaScriptCanOpenWindowsAutomatically = false
            s.mediaPlaybackRequiresUserGesture = true
            s.textZoom = 100
            w.setBackgroundColor(Color.WHITE)
            w.addJavascriptInterface(Probe(), "YaverProbe")
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = request?.let { serve(it) }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = request?.url?.host != HOST
                override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (v == view) view = null
                    try { v?.destroy() } catch (_: Throwable) {}
                    cur?.let { r -> synchronized(r) { r.errors.add(JSONObject().put("msg", "The page crashed the web engine (out of memory or an endless loop).")) } }
                    onLoad?.countDown()
                    return true
                }
            }
            view = w
        }
        val d = Y.app.resources.displayMetrics.density
        val w = (width * d).toInt(); val h = (height * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.onResume(); v.resumeTimers()
        v
    }

    @Synchronized
    fun run(path0: String, actions: List<JSONObject>, width: Int = 1280, height: Int = 800, wait: Int = 1200, timeoutMs: Long = 15000, cancel: Cancel? = null): Res {
        val path = VPath.normTool(path0)
        if (!VPath.isFile(path)) throw IllegalArgumentException("no such file $path")
        if (!Regex("\\.x?html?$", RegexOption.IGNORE_CASE).containsMatchIn(path)) throw IllegalArgumentException("run_html needs an .html file")
        val tk = java.lang.Long.toString(System.nanoTime(), 36)
        var html = VPath.read(path)
        val pr = probe(tk, maxOf(200, minOf(10000, wait)))
        // on the same line as <head> (or <html>, or at the very start) so reported line numbers match the file
        val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val htm = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
        html = if (head != null) html.substring(0, head.range.last + 1) + pr + html.substring(head.range.last + 1)
        else if (htm != null) html.substring(0, htm.range.last + 1) + pr + html.substring(htm.range.last + 1) else pr + html
        val res = Res(path, width)
        val loaded = CountDownLatch(1)
        synchronized(waits) { waits.clear() }
        token = tk; mainPath = path; mainHtml = html; onLoad = loaded; cur = res
        try {
            val v = ensure(width, height)
            onMain { try { WebStorage.getInstance().deleteOrigin(BASE) } catch (_: Throwable) {}; v.clearCache(false); v.loadUrl(urlOf(path)) }
            val start = System.currentTimeMillis()
            while (!loaded.await(200, TimeUnit.MILLISECONDS)) { cancel?.check(); if (System.currentTimeMillis() - start > timeoutMs) break }
            if (!res.loaded) res.timeout = true
            for ((i, a) in actions.withIndex()) {
                if (!res.loaded || view == null) break
                cancel?.check()
                val l = CountDownLatch(1); synchronized(waits) { waits[i] = l }
                val msg = JSONObject().put("__yp", tk).put("act", a).put("i", i)
                onMain { view?.evaluateJavascript("window.__yvAct&&window.__yvAct($msg)", null) }
                l.await(maxOf(1500L, a.optLong("wait", 300) + 1500), TimeUnit.MILLISECONDS)
                synchronized(res) { if (res.actions[i] == null) res.actions[i] = JSONObject().put("ok", false).put("error", "no answer (the page may be frozen)") }
                Thread.sleep(80)
            }
            Thread.sleep(250)
        } finally {
            cur = null; token = ""
            try { onMain { view?.loadUrl("about:blank"); Unit } } catch (_: Throwable) {}
        }
        return res
    }

    private fun srcLine(file: String, line: Int): String = try {
        if (file.isEmpty() || line <= 0 || !VPath.isFile(file)) "" else VPath.read(file).split("\n").getOrNull(line - 1)?.trim()?.take(200) ?: ""
    } catch (e: Exception) { "" }

    private fun fixPos(d: JSONObject, path: String): Triple<String, Int, Int> {
        var file = local(d.optString("file")); var line = d.optInt("line"); var col = d.optInt("col")
        val top = Regex("(/[^\\s():]+\\.m?js):(\\d+):(\\d+)").find(local(d.optString("stack")))
        if (top != null && (file.isEmpty() || file.startsWith("data:") || file == "about:blank")) { file = top.groupValues[1]; line = top.groupValues[2].toInt(); col = top.groupValues[3].toInt() }
        else if (file.isEmpty()) file = path
        else if (file.startsWith("data:")) file = "(inlined module)"
        return Triple(file, line, col)
    }

    fun report(r: Res): String {
        val L = ArrayList<String>()
        val last = r.snaps.lastOrNull()
        val all = r.errors.map { e -> val (f, l, c) = fixPos(e, r.path); Triple(e, f, Pair(l, c)) }
        val errs = all.filterIndexed { i, x -> all.indexOfFirst { y -> y.first.optString("msg") == x.first.optString("msg") && y.third.first == x.third.first && y.second == x.second } == i }
        val cerr = r.console.filter { it.optString("level") == "error" }; val cwarn = r.console.filter { it.optString("level") == "warn" }
        fun isLocal(u: String) = !Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(u) || u.startsWith("$BASE/")
        val external = r.resources.filter { !isLocal(it.optString("url")) }
        val localBad = r.resources.filter { isLocal(it.optString("url")) && r.missing.isEmpty() }
        val failedActs = r.actions.entries.sortedBy { it.key }.filter { !it.value.optBoolean("ok") }
        val problems = errs.size + cerr.size + r.missing.size + (if (r.timeout) 1 else 0) + failedActs.size
        L.add("${if (problems > 0) "✗" else "✓"} ${r.path} at ${r.width}px — ${if (problems > 0) "$problems problem${if (problems == 1) "" else "s"}" else "no errors"}${if (!r.started) " (the page script did not start — invalid HTML before <head>, or a CSP?)" else ""}")
        if (r.timeout) L.add("\n! The page did not finish loading within the time limit (a long-running or endless script, or a resource that never loads).")
        if (errs.isNotEmpty()) {
            L.add("\nJavaScript errors (${errs.size}):")
            for ((e, file, lc) in errs.take(20)) {
                val (line, col) = lc
                val src = srcLine(file, line)
                val stack = local(e.optString("stack"))
                val frames = if (stack.isNotEmpty()) stack.split("\n").drop(1).take(4).map { it.trim() }.filter { fr ->
                    val mm = Regex("(/[^\\s():]+):(\\d+):(\\d+)").find(fr) ?: return@filter true
                    val s = if (VPath.isFile(mm.groupValues[1])) (VPath.read(mm.groupValues[1]).split("\n").getOrNull(mm.groupValues[2].toInt() - 1) ?: "") else null
                    s == null || mm.groupValues[3].toInt() <= s.length + 1
                }.take(3) else emptyList()
                L.add("- ${e.optString("msg")}\n  at $file${if (line > 0) ":$line" + (if (col > 0) ":$col" else "") else ""}${if (src.isNotEmpty()) "\n  > $src" else ""}${if (frames.isNotEmpty()) "\n  " + frames.joinToString("\n  ") else ""}")
            }
        }
        if (cerr.isNotEmpty()) { L.add("\nconsole.error (${cerr.size}):"); for (c in cerr.take(10)) L.add("- " + clip(local(c.optString("msg")), 400)) }
        if (r.missing.isNotEmpty()) { L.add("\nMissing local files (${r.missing.size}):"); for ((p, from) in r.missing.take(20)) L.add("- $p (referenced from $from)") }
        if (localBad.isNotEmpty()) { L.add("\nFailed to load:"); for (x in localBad.take(10)) L.add("- <${x.optString("tag")}> ${local(x.optString("url"))}") }
        if (r.net.isNotEmpty()) L.add("\nNetwork requests that failed (${r.net.size}) — fetch()/XHR the page makes; data it depends on may be missing:\n" + r.net.take(8).joinToString("\n") { x -> "- ${local(x.optString("url"))} → ${if (x.optInt("status") > 0) "HTTP " + x.optInt("status") else x.optString("error").ifEmpty { "failed" }}" })
        if (external.isNotEmpty()) L.add("\nExternal resources that did not load (${external.size}) — may be offline/blocked here, not necessarily a bug:\n" + external.take(8).joinToString("\n") { "- <${it.optString("tag")}> ${it.optString("url")}" })
        if (failedActs.isNotEmpty()) L.add("\nActions that failed:\n" + failedActs.joinToString("\n") { "- action ${it.key + 1}: ${it.value.optString("error")}" })
        val evals = r.actions.entries.sortedBy { it.key }.filter { it.value.optBoolean("ok") && it.value.has("value") }
        if (evals.isNotEmpty()) L.add("\nValues:\n" + evals.joinToString("\n") { "- action ${it.key + 1} → ${it.value.optString("value")}" })
        if (cwarn.isNotEmpty()) L.add("\nconsole.warn (${cwarn.size}):\n" + cwarn.take(6).joinToString("\n") { "- " + clip(local(it.optString("msg")), 250) })
        val info = r.console.filter { it.optString("level") == "info" }
        if (info.isNotEmpty()) L.add("\nDialogs: " + info.take(5).joinToString(" · ") { it.optString("msg") })
        if (last != null) {
            val brokenAll = last.optJSONArray("broken") ?: JSONArray()
            val broken = (0 until brokenAll.length()).map { brokenAll.optString(it) }.filter { !isLocal(it) || it.startsWith("data:") || it.startsWith("$BASE/") }
            if (broken.isNotEmpty()) L.add("\nBroken images (${broken.size}): ${broken.take(5).joinToString(", ") { if (it.startsWith("data:")) "(inline data, not a valid image)" else if (it.startsWith("$BASE/")) local(it) + " (local file, not a valid image)" else it }}")
            val notes = ArrayList<String>()
            if (last.optInt("overflowX") > 0) notes.add("content is ${last.optInt("overflowX")}px wide at a ${r.width}px viewport (horizontal scroll)")
            if (last.optInt("noAlt") > 0) notes.add("${last.optInt("noAlt")} image(s) without alt text")
            if (last.optString("text").isBlank() && last.optInt("elements") < 15) notes.add("the page shows no text (blank page?)")
            if (notes.isNotEmpty()) L.add("\nLayout / accessibility: ${notes.joinToString("; ")}")
            L.add("\nPage: “${last.optString("title").ifEmpty { "(no title)" }}” · ${last.optInt("elements")} elements · ${last.optInt("height")}px tall\nVisible text (start):\n${clip(last.optString("text").replace(Regex("\\n{3,}"), "\n\n"), 1200)}")
        }
        if (problems == 0) L.add("\nNote: \"no errors\" only means nothing crashed — it does not prove the page shows the right content. To confirm, run again with an eval action that reads what matters, e.g. {\"eval\": \"document.querySelectorAll('.item').length\"} or {\"eval\": \"[...document.querySelectorAll('path')].filter(p => p.getAttribute('fill') === '#ff4444').length\"}.")
        return L.joinToString("\n")
    }

    fun register() {
        val actionsSchema = JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))
            .put("description", "Optional steps after load: click (selector), type (+selector), key, eval (JS expression, returns its value), wait (ms after the step)")
        Tools.add(Tool(
            "run_html",
            "Run an HTML file from the workspace in a hidden browser view and report what goes wrong: JavaScript errors with file:line and the source line, unhandled promise rejections, console errors/warnings, missing local files (CSS/JS/images referenced but not in the workspace), broken images, horizontal overflow, and the visible text. Local CSS/JS/images and ES-module imports are loaded from the workspace. Optional actions exercise the page: [{\"click\":\"#btn\"}, {\"selector\":\"#name\",\"type\":\"Ayşe\"}, {\"key\":\"Enter\"}, {\"eval\":\"document.querySelectorAll('li').length\"}, {\"wait\":500}]. Use it after writing or changing an HTML page: fix every reported error with patch, then run again until it reports no errors. Also check a narrow screen with width 390.",
            P.props("path" to P.s("Path of the .html file"), "actions" to actionsSchema, "width" to toolInt("Viewport width in px (default 1280; use 390 for a phone)"), "wait" to toolInt("Milliseconds to wait after load before checking (default 1200)")),
            listOf("path")
        ) { a, ctx ->
            ctx.progress(Y.t("Running ", "Çalıştırılıyor: ") + VPath.base(VPath.normTool(a.str("path"))) + "…")
            val arr = a.optJSONArray("actions") ?: (a.str("actions").takeIf { it.startsWith("[") }?.let { try { JSONArray(it) } catch (e: Exception) { null } }) ?: JSONArray()
            val actions = (0 until arr.length()).mapNotNull { i ->
                val x = arr.optJSONObject(i) ?: return@mapNotNull null
                if (x.opt("click") is String) JSONObject(x.toString()).put("selector", x.getString("click")).put("click", true) else x
            }
            val w = a.int("width", 1280).let { if (it <= 0) 1280 else it.coerceIn(200, 4000) }
            val wait = a.int("wait", 1200).let { if (it <= 0) 1200 else it }
            report(run(a.str("path"), actions, w, 800, wait, 15000, ctx.cancel))
        })
    }
}

/**
 * "Open in a new tab" for the Files page (the original's FileOpen for html/svg): a disk file shown in yaver's own browser
 * (BrowserActivity, a WebView with no bridge to the app), with its local CSS/JS/images served read-only from the virtual disk.
 */
object LocalView {
    const val HOST = "view.yaver.invalid"
    private const val BASE = "https://$HOST"
    fun urlOf(vp: String) = BASE + vp.split('/').joinToString("/") { Uri.encode(it) }
    fun serve(req: WebResourceRequest?): WebResourceResponse? {
        val u = req?.url ?: return null
        if (u.host != HOST) return null
        val vp = VPath.norm(Uri.decode(u.encodedPath ?: "/"))
        return try {
            val f = VPath.file(vp)
            if (!f.isFile) WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), ByteArrayInputStream(ByteArray(0)))
            else {
                val mime = when (f.extension.lowercase()) { "mjs", "js" -> "text/javascript"; "css" -> "text/css"; "wasm" -> "application/wasm"; "json" -> "application/json"; "svg" -> "image/svg+xml"; "htm", "html" -> "text/html"; else -> Workspace.mime(f.name) }
                WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") || mime.contains("svg")) "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-store"), f.inputStream())
            }
        } catch (e: Exception) { WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), ByteArrayInputStream(ByteArray(0))) }
    }
}

