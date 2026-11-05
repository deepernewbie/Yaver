package com.plusai.yaver.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * yaver's own headless browser for the agent: a real web view (cookies shared with the rest of the app,
 * so sites you signed in to stay signed in) that the model drives step by step — open, read, click, type,
 * scroll — for sites that have no API. It can hand the page to the user (captcha, sign-in) and continue after.
 */
object Browser {
    private const val W = 412
    private const val H = 892
    private var view: WebView? = null
    @Volatile private var loading = false
    @Volatile private var lastEvent = 0L
    @Volatile private var userLatch: CountDownLatch? = null
    @Volatile var userUrl: String? = null

    private fun <T> onMain(timeoutS: Long = 20, f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val latch = CountDownLatch(1)
        var r: T? = null
        var err: Throwable? = null
        Y.main.post { try { r = f() } catch (e: Throwable) { err = e } finally { latch.countDown() } }
        if (!latch.await(timeoutS, TimeUnit.SECONDS)) throw IllegalStateException("browser is not responding")
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(): WebView = onMain {
        view ?: WebView(Y.app).also { v ->
            val s = v.settings
            s.javaScriptEnabled = true; s.domStorageEnabled = true; s.loadWithOverviewMode = true; s.useWideViewPort = true
            s.setSupportMultipleWindows(false); s.javaScriptCanOpenWindowsAutomatically = false
            s.mediaPlaybackRequiresUserGesture = true
            s.userAgentString = Mail.ua
            CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
            v.setBackgroundColor(Color.WHITE)
            v.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true; lastEvent = System.currentTimeMillis() }
                override fun onPageFinished(view: WebView?, url: String?) { loading = false; lastEvent = System.currentTimeMillis() }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? = LocalView.serve(request)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val sc = request?.url?.scheme ?: return false
                    return !(sc == "http" || sc == "https" || sc == "about" || sc == "data" || sc == "blob" || sc == "javascript")
                }
            }
            v.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) { lastEvent = System.currentTimeMillis(); if (newProgress >= 100) loading = false }
            }
            val d = Y.app.resources.displayMetrics.density
            val w = (W * d).toInt(); val h = (H * d).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            v.onResume(); v.resumeTimers()
            view = v
        }
    }

    /** Waits for the page to settle: loading finished and no new events for a moment. */
    private fun settle(cancel: Cancel?, maxMs: Long = 20000) {
        val start = System.currentTimeMillis()
        Thread.sleep(350)
        while (System.currentTimeMillis() - start < maxMs) {
            cancel?.check()
            if (!loading && System.currentTimeMillis() - lastEvent > 700) break
            Thread.sleep(150)
        }
        Thread.sleep(250)
    }

    private fun js(code: String, timeoutS: Long = 15): String {
        val v = ensure()
        val latch = CountDownLatch(1)
        var out = "null"
        Y.main.post { try { v.evaluateJavascript(code) { r -> out = r ?: "null"; latch.countDown() } } catch (e: Throwable) { latch.countDown() } }
        latch.await(timeoutS, TimeUnit.SECONDS)
        return out
    }

    /** evaluateJavascript returns JSON; a returned string arrives quoted. */
    private fun jsString(code: String): String {
        val r = js(code)
        return try { JSONArray("[$r]").optString(0, "") } catch (e: Exception) { r }
    }

    private const val SNAPSHOT = """
(function(maxText, offset){
 function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';}
 [].slice.call(document.querySelectorAll('[data-yv]')).forEach(function(e){e.removeAttribute('data-yv');});
 var q='a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=radio],[role=switch],[role=combobox],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"])';
 var els=[].slice.call(document.querySelectorAll(q)), out=[], n=0, seen=new Set();
 for(var i=0;i<els.length&&n<160;i++){var e=els[i];if(e.type==='hidden'||!vis(e))continue;
  var p=e.parentElement,dup=false;while(p){if(seen.has(p)&&(p.tagName==='A'||p.tagName==='BUTTON')){dup=true;break;}p=p.parentElement;}if(dup)continue;seen.add(e);
  n++;e.setAttribute('data-yv',n);
  var tag=e.tagName.toLowerCase(),type=(e.getAttribute('type')||'').toLowerCase();
  var lab=(e.getAttribute('aria-label')||(tag==='input'||tag==='textarea'||tag==='select'?'':e.innerText)||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||'').replace(/\s+/g,' ').trim();
  if(!lab&&(tag==='input'||tag==='textarea'||tag==='select')){var l=e.id&&document.querySelector('label[for="'+e.id+'"]');if(l)lab=l.innerText.trim();}
  if(!lab){var im=e.querySelector&&e.querySelector('img[alt]');if(im)lab=im.alt;}
  if(!lab)lab=e.getAttribute('name')||'';
  var kind=tag==='input'?'input['+(type||'text')+']':tag==='a'?'link':tag;
  var role=e.getAttribute('role');if(role&&tag!=='a'&&tag!=='button')kind=role;
  var x='';
  if(tag==='input'||tag==='textarea'){if(type==='checkbox'||type==='radio')x=e.checked?' (checked)':' (unchecked)';else if(type==='password')x=e.value?' (filled)':'';else if(e.value)x=' value="'+e.value.slice(0,60)+'"';}
  if(tag==='select'){x=' selected="'+(e.options[e.selectedIndex]||{}).text+'" options: '+[].slice.call(e.options).slice(0,20).map(function(o){return o.text.trim();}).join(' | ');}
  if(tag==='a'){var h=e.getAttribute('href')||'';if(h&&h[0]!=='#'&&!/^javascript:/i.test(h))x+=' → '+h.replace(/^https?:\/\/(www\.)?/,'').slice(0,70);}
  if(e.disabled)x+=' (disabled)';
  var r=e.getBoundingClientRect();if(r.bottom<0)x+=' (above)';else if(r.top>innerHeight)x+=' (below)';
  out.push('['+n+'] '+kind+' "'+lab.slice(0,90)+'"'+x);}
 var t=(document.body?document.body.innerText:'').replace(/[ \t]+/g,' ').replace(/\n\s*\n+/g,'\n').trim();
 var dh=Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0);
 return JSON.stringify({title:document.title,url:location.href,elements:out,text:t.slice(offset,offset+maxText),textLength:t.length,scroll:dh>innerHeight?Math.round(scrollY/(dh-innerHeight)*100):100,dialogs:[].slice.call(document.querySelectorAll('dialog[open],[role=dialog],[aria-modal=true]')).filter(vis).length});
})"""

    private fun snapshot(maxText: Int = 6000, offset: Int = 0, elementsOnly: Boolean = false): String {
        val raw = jsString("$SNAPSHOT($maxText,$offset)")
        val o = try { JSONObject(raw) } catch (e: Exception) { return "The page could not be read ($raw)." }
        val sb = StringBuilder()
        sb.append("Page: ").append(o.optString("title")).append('\n').append("URL: ").append(o.optString("url")).append('\n')
        sb.append("Scrolled: ").append(o.optInt("scroll")).append("%")
        if (o.optInt("dialogs") > 0) sb.append(" · a dialog/pop-up is open")
        sb.append("\n\nInteractive elements (use their numbers):\n")
        val els = o.optJSONArray("elements") ?: JSONArray()
        if (els.length() == 0) sb.append("(none)\n")
        for (i in 0 until els.length()) sb.append(els.optString(i)).append('\n')
        if (!elementsOnly) {
            val len = o.optInt("textLength")
            sb.append("\nPage text").append(if (offset > 0 || len > offset + maxText) " (characters $offset–${minOf(len, offset + maxText)} of $len; use action=text with offset for more)" else "").append(":\n")
            sb.append(o.optString("text"))
        }
        return sb.toString()
    }

    private fun el(id: Int) = "document.querySelector('[data-yv=\"$id\"]')"

    private fun risky(label: String) = Regex("(?i)\\b(pay|buy|purchase|order|checkout|confirm|delete|remove|send|submit|transfer|book|subscribe|sign up|öde|ödeme|satın|sipariş|onayla|sil|gönder|kaydol|abone|rezervasyon|transfer)").containsMatchIn(label)

    private fun label(id: Int): String = jsString("(function(){var e=${el(id)};if(!e)return '';return (e.getAttribute('aria-label')||e.innerText||e.value||e.title||'').replace(/\\s+/g,' ').trim().slice(0,80);})()")

    private fun needUserApproval(ctx: ToolCtx, what: String, lab: String): Boolean =
        !risky(lab) || ctx.approve(Y.t("Browser: ", "Tarayıcı: ") + what, listOf(Y.t("Page", "Sayfa") to Diag.url(currentUrl()), Y.t("Element", "Öğe") to lab))

    private fun currentUrl(): String = try { onMain { view?.url ?: "" } } catch (e: Throwable) { "" }

    /** Opens the current page in a visible browser so the user can act (sign in, solve a captcha); waits until it is closed. */
    private fun handToUser(ctx: ToolCtx, why: String): String {
        if (ctx.background) return "Not possible in a background task: the user is not looking. Tell them what is needed."
        val url = currentUrl().ifEmpty { return "Open a page first." }
        val latch = CountDownLatch(1)
        userLatch = latch; userUrl = null
        Y.ui {
            Y.app.startActivity(Intent(Y.app, BrowserActivity::class.java).putExtra("url", url).putExtra("agent", true).putExtra("why", why).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ctx.progress(Y.t("Waiting for you in the browser…", "Tarayıcıda sizi bekliyorum…"))
        while (!latch.await(500, TimeUnit.MILLISECONDS)) ctx.cancel.check()
        val back = userUrl ?: url
        Diag.i("browser", "user handed back at ${Diag.url(back)}")
        onMain { view?.loadUrl(back) }
        settle(ctx.cancel)
        return Y.t("The user finished in the browser. ", "Kullanıcı tarayıcıda işini bitirdi. ") + snapshot()
    }

    /** Called by the visible browser when the user closes it. */
    fun userDone(url: String?) { userUrl = url; userLatch?.countDown(); userLatch = null }

    // ---------------------------------------------------------------- the user watches the agent's browser (the original's Browser pane)
    /** The agent's page as the user sees it in the live view: url, title, loading, and a picture (or its text). Never opens a browser. */
    fun live(mode: String): JSONObject {
        val o = JSONObject()
        val v = view ?: return o.put("url", "").put("open", false)
        val (url, title) = try { onMain(5) { (v.url ?: "") to (v.title ?: "") } } catch (e: Throwable) { "" to "" }
        o.put("open", true).put("url", url).put("title", title).put("loading", loading)
        if (url.isEmpty()) return o
        if (mode == "text") {
            o.put("text", try { snapshot(12000) } catch (e: Throwable) { e.message ?: "" })
        } else try {
            val bmp = onMain(5) {
                val b = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val c = Canvas(b); c.drawColor(Color.WHITE); v.draw(c); b
            }
            val sw = W.coerceAtMost(bmp.width)
            val small = Bitmap.createScaledBitmap(bmp, sw, (bmp.height * (sw.toFloat() / bmp.width)).toInt().coerceAtLeast(1), true)
            val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            o.put("image", "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
        } catch (e: Throwable) { o.put("error", e.message ?: "") }
        return o
    }

    /** A file on yaver's disk (/workspace/…, file://…, ~/…) as an address the browser can load (served read-only from the disk). */
    fun localUrl(u0: String): String? {
        val t = u0.trim()
        val p = when {
            t.startsWith("file://") -> t.removePrefix("file://")
            t.startsWith("/") && !t.startsWith("//") -> t
            t.startsWith("~/") || t.startsWith("workspace/") || t.startsWith(".yaver/") -> t
            else -> return null
        }
        val vp = VPath.normTool(android.net.Uri.decode(p.substringBefore('#').substringBefore('?')))
        if (!VPath.isFile(vp)) throw IllegalArgumentException("no such file $vp")
        return LocalView.urlOf(vp) + t.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
    }

    /** The URL bar of the live view: open a page (also starts the browser) or go back. */
    fun userGo(url: String): JSONObject {
        var u = url.trim(); if (u.isEmpty()) throw IllegalArgumentException(Y.t("Enter an address", "Bir adres girin"))
        localUrl(u)?.let { u = it }
        if (!Regex("^[a-z]+:", RegexOption.IGNORE_CASE).containsMatchIn(u)) u = if (u.contains('.') && !u.contains(' ')) "https://$u" else "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(u, "UTF-8")
        if (!Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(u)) throw IllegalArgumentException(Y.t("Only web addresses", "Yalnızca web adresleri"))
        val v = ensure(); onMain { v.loadUrl(u) }
        Diag.i("browser", "live view: user opened ${Diag.url(u)}")
        return JSONObject().put("url", u)
    }
    fun userBack(): JSONObject { val v = view ?: return JSONObject().put("ok", false); val ok = onMain { if (v.canGoBack()) { v.goBack(); true } else false }; return JSONObject().put("ok", ok) }
    fun userReload(): JSONObject { val v = view ?: return JSONObject().put("ok", false); onMain { v.reload() }; return JSONObject().put("ok", true) }
    /** Take over: the page in the visible browser (sign in, solve a captcha); the agent's browser follows where the user ends. */
    fun userTakeOver(): JSONObject {
        val url = currentUrl().ifEmpty { throw IllegalArgumentException(Y.t("yaver's browser has no page open", "yaver'in tarayıcısında açık sayfa yok")) }
        Y.ui { Y.app.startActivity(Intent(Y.app, BrowserActivity::class.java).putExtra("url", url).putExtra("agent", false).putExtra("follow", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return JSONObject().put("url", url)
    }
    /** The user closed the visible browser after taking over: the agent's browser continues from that page (cookies are shared). */
    fun followUser(url: String?) { if (url.isNullOrBlank() || view == null) return; try { onMain { view?.loadUrl(url) } } catch (_: Throwable) {} }
    fun pageUrl(): String = if (view == null) "" else currentUrl()

    private fun screenshot(ctx: ToolCtx, question: String): String {
        val v = ensure()
        val bmp = onMain {
            val b = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Color.WHITE); v.draw(c); b
        }
        val out = ByteArrayOutputStream()
        val small = Bitmap.createScaledBitmap(bmp, W * 2, (bmp.height * (W * 2f / bmp.width)).toInt(), true)
        small.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val path = Workspace.write("browser", "screen-" + System.currentTimeMillis() + ".jpg", out.toByteArray())
        val data = "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        ctx.progress(Y.t("Looking at the page…", "Sayfaya bakılıyor…"))
        val seen = Llm.vision(data, question.ifEmpty { "This is a screenshot of a web page in a phone browser. Describe what is shown: layout, main content, buttons, any pop-ups or errors. Transcribe important text exactly." }, ctx.cancel)
        return "Screenshot saved as $path (you can show it to the user as ![]($path)).\n$seen"
    }

    fun register() {
        Tools.add(Tool(
            "browser",
            "Your own web browser (a real browser engine, headless, with the user's cookies) for sites without an API: search forms, portals, booking or tracking pages, sites that need clicking or typing, pages that only render with JavaScript. " +
                "Works step by step: action=open (url) loads a page and returns its text and a numbered list of interactive elements. Then click (id), type (id, text; submit=true presses Enter), select (id, option), check (id), press (key: Enter|Escape|Tab|ArrowDown|ArrowUp), scroll (direction down|up|top|bottom), back, forward, reload, wait (seconds), " +
                "text (offset: read more of the page), elements (only the element list), find (text: where it occurs), screenshot (question: look at the page with the vision model, e.g. for charts, images, layout or captchas), " +
                "user (why: show the page to the user so they can sign in, solve a captcha or confirm something themselves; you continue after they close it), close. " +
                "Element numbers change after every page change: always use the numbers from the latest result. Prefer read_page for simply reading an article; use this when interaction is needed. Never type passwords or payment details unless the user gave them for this purpose; for sign-ins use action=user.",
            P.props(
                "action" to P.s("What to do", "open", "click", "type", "select", "check", "press", "scroll", "back", "forward", "reload", "wait", "text", "elements", "find", "screenshot", "user", "close"),
                "url" to P.s("For open"), "id" to P.n("Element number from the latest list"), "text" to P.s("Text to type, or text to find"),
                "submit" to P.b("For type: press Enter afterwards"), "option" to P.s("For select: the option's text"), "key" to P.s("For press"),
                "direction" to P.s("For scroll", "down", "up", "top", "bottom"), "seconds" to P.n("For wait (max 15)"), "offset" to P.n("For text: start character"),
                "question" to P.s("For screenshot"), "why" to P.s("For user: what the user should do")
            ),
            listOf("action")
        ) { a, ctx ->
            val action = a.str("action")
            val id = a.int("id", 0)
            Diag.i("browser", "$action ${if (id > 0) "#$id " else ""}${Diag.url(a.str("url"))}")
            fun after(note: String): String { settle(ctx.cancel); return note + "\n\n" + snapshot() }
            fun need(): Int { if (id <= 0) throw IllegalArgumentException("id is required: use a number from the element list"); if (jsString("(function(){return ${el(id)}?'y':'n';})()") != "y") throw IllegalArgumentException("element $id is not on the page any more — use action=elements to get fresh numbers"); return id }
            when (action) {
                "open" -> {
                    var u = a.str("url").ifEmpty { throw IllegalArgumentException("url is required") }
                    localUrl(u)?.let { u = it }
                    if (!Regex("^[a-z]+:").containsMatchIn(u)) u = if (u.contains('.') && !u.contains(' ')) "https://$u" else "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(u, "UTF-8")
                    ctx.progress(Y.t("Opening ", "Açılıyor: ") + (android.net.Uri.parse(u).host ?: u))
                    val v = ensure()
                    loading = true; lastEvent = System.currentTimeMillis()
                    onMain { v.loadUrl(u) }
                    settle(ctx.cancel, 30000)
                    snapshot()
                }
                "click" -> {
                    val n = need(); val lab = label(n)
                    if (!needUserApproval(ctx, Y.t("click “$lab”", "“$lab” tıkla"), lab)) return@Tool ctx.denied("click $lab")
                    ctx.progress(Y.t("Clicking ", "Tıklanıyor: ") + lab.take(40))
                    jsString("(function(){var e=${el(n)};e.scrollIntoView({block:'center'});if(e.tagName==='A')e.removeAttribute('target');var r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window}));});if(e.focus)e.focus();e.click();return 'ok';})()")
                    after("Clicked [$n] “$lab”.")
                }
                "type" -> {
                    val n = need(); val text = a.optString("text", "")
                    val sub = a.optBoolean("submit")
                    if (sub && !needUserApproval(ctx, Y.t("submit a form", "form gönder"), label(n) + " " + text)) return@Tool ctx.denied("submit")
                    ctx.progress(Y.t("Typing…", "Yazılıyor…"))
                    jsString("(function(t,sub){var e=${el(n)};e.scrollIntoView({block:'center'});e.focus();" +
                        "if(e.isContentEditable){document.execCommand('selectAll',false,null);document.execCommand('insertText',false,t);}" +
                        "else{var pr=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(pr,'value');d.set.call(e,t);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}" +
                        "if(sub){['keydown','keypress','keyup'].forEach(function(k){e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));});var f=e.form;if(f){setTimeout(function(){if(f.requestSubmit)f.requestSubmit();else f.submit();},50);}}return 'ok';})(${JSONObject.quote(text)},$sub)")
                    after("Typed into [$n]" + if (sub) " and submitted." else ".")
                }
                "select" -> {
                    val n = need(); val opt = a.str("option")
                    val r = jsString("(function(o){var e=${el(n)};if(e.tagName!=='SELECT')return 'not a select';var l=o.toLowerCase();for(var i=0;i<e.options.length;i++){var t=e.options[i].text.trim().toLowerCase();if(t===l||e.options[i].value===o){e.selectedIndex=i;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}}for(var j=0;j<e.options.length;j++){if(e.options[j].text.toLowerCase().indexOf(l)>=0){e.selectedIndex=j;e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}}return 'no such option';})(${JSONObject.quote(opt)})")
                    if (r != "ok") throw IllegalArgumentException("select [$n]: $r")
                    after("Selected “$opt” in [$n].")
                }
                "check" -> {
                    val n = need()
                    jsString("(function(){var e=${el(n)};e.click();return 'ok';})()")
                    after("Toggled [$n].")
                }
                "press" -> {
                    val key = a.str("key").ifEmpty { "Enter" }
                    if (key == "Enter" && !needUserApproval(ctx, Y.t("press Enter", "Enter'a bas"), "submit")) return@Tool ctx.denied("press Enter")
                    jsString("(function(k){var e=document.activeElement||document.body;var c={Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38}[k]||0;['keydown','keypress','keyup'].forEach(function(t){e.dispatchEvent(new KeyboardEvent(t,{key:k,code:k,keyCode:c,which:c,bubbles:true,cancelable:true}));});if(k==='Enter'&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}return 'ok';})(${JSONObject.quote(key)})")
                    after("Pressed $key.")
                }
                "scroll" -> {
                    val d = a.str("direction").ifEmpty { "down" }
                    jsString("(function(d){if(d==='top')scrollTo(0,0);else if(d==='bottom')scrollTo(0,document.documentElement.scrollHeight);else scrollBy(0,(d==='up'?-1:1)*innerHeight*0.85);return 'ok';})(${JSONObject.quote(d)})")
                    Thread.sleep(500)
                    "Scrolled $d.\n\n" + snapshot(2500)
                }
                "back" -> { onMain { view?.let { if (it.canGoBack()) it.goBack() } }; after("Went back.") }
                "forward" -> { onMain { view?.let { if (it.canGoForward()) it.goForward() } }; after("Went forward.") }
                "reload" -> { onMain { view?.reload() }; after("Reloaded.") }
                "wait" -> { val s = a.int("seconds", 2).coerceIn(1, 15); for (i in 0 until s * 4) { ctx.cancel.check(); Thread.sleep(250) }; snapshot() }
                "text" -> snapshot(12000, a.int("offset", 0).coerceAtLeast(0))
                "elements" -> snapshot(0, 0, true)
                "find" -> {
                    val t = a.str("text")
                    jsString("(function(q){var t=document.body.innerText,l=t.toLowerCase(),i=-1,o=[],w=q.toLowerCase();while((i=l.indexOf(w,i+1))>=0&&o.length<12)o.push('@'+i+': …'+t.slice(Math.max(0,i-120),i+200).replace(/\\s+/g,' ')+'…');return o.length?o.join('\\n'):'not found on this page';})(${JSONObject.quote(t)})")
                }
                "screenshot" -> screenshot(ctx, a.str("question"))
                "user" -> handToUser(ctx, a.str("why"))
                "close" -> { onMain { view?.destroy(); view = null }; "Browser closed." }
                else -> throw IllegalArgumentException("unknown action $action")
            }
        })
    }
}
