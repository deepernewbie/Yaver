package dev.yaver.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A browser the agent operates, rather than a page it downloads.
 *
 * Fetching HTML stopped being enough years ago. Half the web builds its
 * content in JavaScript after load, so a plain fetch returns an empty shell;
 * prices, ratings and search results simply are not in the markup. And a fetch
 * can only ever read — it cannot dismiss a cookie wall, open a filter, or type
 * into a search box, all of which stand between the agent and the answer.
 *
 * The approach is borrowed from browser-use: after every action the page is
 * described as text plus a numbered list of the things that can be interacted
 * with, and the agent acts by number. That indirection is what makes it work
 * with a language model — no CSS selectors to invent, no coordinates to guess,
 * just "element 12 is the search box, type into it".
 *
 * The WebView runs off-screen, measured to the real display size so sites lay
 * themselves out as they would for a phone. It can also be brought on screen,
 * which is the only practical way to sign in to something.
 */
object Browser {

    private val ui = Handler(Looper.getMainLooper())

    @SuppressLint("StaticFieldLeak")
    private var web: WebView? = null

    @Volatile private var loading = false
    @Volatile private var lastError: String? = null

    data class Element(val index: Int, val tag: String, val type: String, val label: String, val href: String)

    data class State(
        val url: String,
        val title: String,
        val text: String,
        val elements: List<Element>,
        val note: String? = null
    )

    fun isReady() = web != null

    /**
     * Built once, from an Activity, because a WebView wants a UI context. It is
     * laid out at display size without ever being attached, so pages compute a
     * sensible viewport while nothing appears on screen.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(activity: Activity) {
        if (web != null) return
        runOnUi {
            val metrics = activity.resources.displayMetrics
            val view = WebView(activity)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                databaseEnabled = true
                // The default WebView user agent already reads as Chrome on
                // Android. Appending anything of our own is the one tell that
                // gets a client refused, which defeats the purpose entirely.
                mediaPlaybackRequiresUserGesture = true
            }
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    loading = false
                }
                override fun onReceivedError(
                    v: WebView?, request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        lastError = error?.description?.toString() ?: "navigation failed"
                        loading = false
                    }
                }
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean =
                    false     // follow redirects and in-page navigation
            }

            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            web = view
            Log.info("browser ready")
        }
    }

    fun stop() = runOnUi {
        web?.loadUrl("about:blank")
    }

    /** The WebView itself, for putting on screen so the user can sign in. */
    fun view(): WebView? = web

    // ── driving it ───────────────────────────────────────────────────────────

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else ui.post(block)
    }

    /** Run JavaScript and wait for its result. Never call from the UI thread. */
    private fun js(script: String, timeoutMs: Long = 10000): String {
        val view = web ?: return ""
        val latch = CountDownLatch(1)
        var result = ""
        runOnUi {
            view.evaluateJavascript(script) { value ->
                result = value ?: ""
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    /**
     * evaluateJavascript hands back a JSON *literal*, so a returned string
     * arrives quoted and escaped and has to be unwrapped before it parses.
     */
    private fun unwrap(raw: String): String = try {
        when (val value = JSONTokener(raw).nextValue()) {
            is String -> value
            else -> raw
        }
    } catch (e: Exception) {
        raw
    }

    private fun settle(timeoutMs: Long = 20000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        // Wait for the load to finish…
        while (loading && System.currentTimeMillis() < deadline) Thread.sleep(120)
        // …then a moment more, because the interesting content usually arrives
        // after onPageFinished, drawn by scripts.
        Thread.sleep(900)
    }

    private const val DESCRIBE = """
    (function () {
      window.__yv = [];
      var out = [];
      var sel = 'a[href],button,input,textarea,select,[role="button"],[role="link"],[onclick],[contenteditable="true"]';
      var nodes = document.querySelectorAll(sel);
      for (var i = 0; i < nodes.length && out.length < 120; i++) {
        var el = nodes[i];
        var r = el.getBoundingClientRect();
        if (r.width < 3 || r.height < 3) continue;
        var st = window.getComputedStyle(el);
        if (st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0') continue;
        var tag = el.tagName.toLowerCase();
        var label = (el.innerText || el.value || el.getAttribute('aria-label') ||
                     el.getAttribute('placeholder') || el.getAttribute('title') || '')
                    .replace(/\s+/g, ' ').trim().slice(0, 90);
        if (!label && tag !== 'input' && tag !== 'textarea' && tag !== 'select') continue;
        window.__yv.push(el);
        out.push({
          i: window.__yv.length - 1,
          tag: tag,
          type: (el.type || ''),
          label: label,
          href: (el.getAttribute('href') || '').slice(0, 200)
        });
      }
      var body = document.body ? (document.body.innerText || '') : '';
      return JSON.stringify({
        url: location.href,
        title: document.title || '',
        text: body.replace(/\n{3,}/g, '\n\n').slice(0, 24000),
        elements: out
      });
    })();
    """

    private fun describe(note: String? = null): State {
        val raw = unwrap(js(DESCRIBE))
        if (raw.isBlank() || !raw.trimStart().startsWith("{")) {
            return State("", "", "", emptyList(), note ?: "The page returned nothing readable.")
        }
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("elements") ?: JSONArray()
            val elements = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                Element(e.optInt("i"), e.optString("tag"), e.optString("type"),
                    e.optString("label"), e.optString("href"))
            }
            State(o.optString("url"), o.optString("title"), o.optString("text"), elements, note)
        } catch (e: Exception) {
            State("", "", "", emptyList(), "Could not read the page: ${e.message}")
        }
    }

    fun open(url: String): State {
        val view = web ?: return State("", "", "", emptyList(), "The browser is not running.")
        val target = if (url.startsWith("http")) url else "https://$url"
        lastError = null
        loading = true
        runOnUi { view.loadUrl(target) }
        settle()
        lastError?.let { return State(target, "", "", emptyList(), "Navigation failed: $it") }
        Log.net("browser opened ${target.take(70)}")
        return describe()
    }

    fun state(): State = describe()

    fun click(index: Int): State {
        val script = """
        (function () {
          var el = window.__yv && window.__yv[$index];
          if (!el) return 'no-element';
          el.scrollIntoView({block: 'center'});
          el.click();
          return 'ok';
        })();
        """
        val result = unwrap(js(script))
        if (result == "no-element") {
            return describe("There is no element $index on this page. Read the list again.")
        }
        loading = true
        settle(12000)
        return describe("Clicked element $index.")
    }

    fun type(index: Int, text: String, submit: Boolean): State {
        val escaped = JSONObject.quote(text)
        val script = """
        (function () {
          var el = window.__yv && window.__yv[$index];
          if (!el) return 'no-element';
          el.scrollIntoView({block: 'center'});
          el.focus();
          var v = $escaped;
          if ('value' in el) {
            el.value = v;
          } else {
            el.textContent = v;
          }
          el.dispatchEvent(new Event('input', {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          if ($submit) {
            var f = el.form;
            if (f) { f.submit(); }
            else {
              el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', keyCode: 13, bubbles: true}));
              el.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', keyCode: 13, bubbles: true}));
            }
          }
          return 'ok';
        })();
        """
        val result = unwrap(js(script))
        if (result == "no-element") {
            return describe("There is no element $index on this page. Read the list again.")
        }
        if (submit) { loading = true; settle(12000) } else Thread.sleep(400)
        return describe(if (submit) "Typed into $index and submitted." else "Typed into $index.")
    }

    fun scroll(pages: Int): State {
        val script = "(function(){ window.scrollBy(0, window.innerHeight * $pages); return 'ok'; })();"
        js(script)
        Thread.sleep(700)     // give lazy-loaded content a chance to arrive
        return describe("Scrolled $pages screen(s).")
    }

    fun back(): State {
        val view = web ?: return State("", "", "", emptyList(), "The browser is not running.")
        loading = true
        runOnUi {
            if (view.canGoBack()) view.goBack() else { loading = false }
        }
        settle(12000)
        return describe("Went back.")
    }

    /**
     * Cookie walls sit between the agent and most European sites, and they all
     * look alike. Trying the usual wording is worth one action.
     */
    fun dismissConsent(): State {
        val script = """
        (function () {
          var words = ['accept all','accept','agree','i agree','allow all','got it',
                       'kabul et','tümünü kabul','tumunu kabul','onayla','anladım'];
          var nodes = document.querySelectorAll('button,a[role="button"],[role="button"],input[type="submit"]');
          for (var i = 0; i < nodes.length; i++) {
            var t = (nodes[i].innerText || nodes[i].value || '').toLowerCase().trim();
            if (!t) continue;
            for (var j = 0; j < words.length; j++) {
              if (t === words[j] || (t.length < 30 && t.indexOf(words[j]) === 0)) {
                nodes[i].click();
                return 'clicked:' + t;
              }
            }
          }
          return 'none';
        })();
        """
        val result = unwrap(js(script))
        Thread.sleep(900)
        return describe(
            if (result.startsWith("clicked:")) "Dismissed a consent banner (${result.removePrefix("clicked:")})."
            else "No consent banner found."
        )
    }

    fun toJson(state: State, maxText: Int): JSONObject {
        val elements = JSONArray()
        state.elements.forEach { e ->
            elements.put(JSONObject()
                .put("i", e.index)
                .put("tag", e.tag + if (e.type.isNotEmpty()) "/${e.type}" else "")
                .put("label", e.label)
                .apply { if (e.href.isNotEmpty()) put("href", e.href) })
        }
        return JSONObject()
            .put("url", state.url)
            .put("title", state.title)
            .put("text", state.text.take(maxText))
            .put("more_text", state.text.length > maxText)
            .put("elements", elements)
            .apply { state.note?.let { put("note", it) } }
    }
}
