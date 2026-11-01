package com.plusai.yaver.app

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Brings over what the previous (web-page) yaver kept on this phone — model and API key, mail addresses,
 * memory, persona and conversations — once, automatically. Also imports a yaver state export (.zip).
 */
object Migration {
    private var web: WebView? = null

    fun needed(): Boolean = !Prefs.on("migrated", false) && File(Y.app.dataDir, "app_webview").exists()

    @SuppressLint("SetJavaScriptEnabled")
    fun runOnce(done: (String) -> Unit) {
        if (!needed()) return
        Prefs.set("migrated", true)
        val v = WebView(Y.app)
        web = v
        v.settings.javaScriptEnabled = true
        v.settings.domStorageEnabled = true
        v.addJavascriptInterface(object {
            @JavascriptInterface fun done(json: String?) {
                Y.bg {
                    val msg = try { importJson(JSONObject(json ?: "{}")) } catch (e: Throwable) { "" }
                    Y.ui { try { web?.destroy() } catch (_: Throwable) {}; web = null; if (msg.isNotEmpty()) done(msg) }
                }
            }
        }, "YaverMigrate")
        v.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                if (u.host != "appassets.androidplatform.net") return null
                return WebResourceResponse("text/html", "utf-8", Y.app.assets.open("migrate.html"))
            }
        }
        v.loadUrl("https://appassets.androidplatform.net/migrate.html")
    }

    /** From the old app's storage: {cfg, secrets, files:{path:text}}. */
    fun importJson(o: JSONObject): String {
        val cfg = o.optString("cfg").takeIf { it.startsWith("{") }?.let { JSONObject(it) }
        val sec = o.optString("secrets").takeIf { it.startsWith("{") }?.let { JSONObject(it) }
        val files = o.optJSONObject("files") ?: JSONObject()
        val map = HashMap<String, String>()
        for (k in files.keys()) map[k] = files.optString(k)
        return apply(cfg, sec, map)
    }

    /** A yaver state export (.zip with yaver-state.json and fs/…). */
    fun importZip(uri: Uri): String {
        var cfg: JSONObject? = null
        var sec: JSONObject? = null
        val map = HashMap<String, String>()
        val ins = Y.app.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("cannot open the file")
        ZipInputStream(ins).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name == "yaver-state.json") {
                    val j = JSONObject(String(z.readBytes()))
                    cfg = j.optJSONObject("config"); sec = j.optJSONObject("secrets")
                } else if (name.startsWith("fs/.yaver/") && Regex("\\.(md|json|txt)$").containsMatchIn(name)) {
                    map[name.removePrefix("fs")] = String(z.readBytes())
                }
            }
        }
        if (cfg == null && map.isEmpty()) throw IllegalArgumentException(Y.t("this is not a yaver export", "bu bir yaver dışa aktarımı değil"))
        return apply(cfg, sec, map)
    }

    private fun apply(cfg: JSONObject?, sec: JSONObject?, files: Map<String, String>): String {
        val done = ArrayList<String>()
        cfg?.optJSONObject("llm")?.let { l ->
            if (Cfg.model.isEmpty()) {
                l.optString("baseUrl").takeIf { it.startsWith("http") }?.let { Cfg.llmBase = it }
                l.optString("model").takeIf { it.isNotEmpty() }?.let { Cfg.model = it }
                l.optString("fastModel").takeIf { it.isNotEmpty() }?.let { Cfg.fastModel = it }
                l.optString("visionModel").takeIf { it.isNotEmpty() }?.let { Cfg.visionModel = it }
                done.add(Y.t("model", "model"))
            }
        }
        sec?.optString("llmKey")?.takeIf { it.isNotEmpty() && Cfg.llmKey.isEmpty() }?.let { Cfg.llmKey = it; done.add(Y.t("API key", "API anahtarı")) }
        cfg?.optJSONObject("mail")?.let { m ->
            val bad = Regex("company\\.com", RegexOption.IGNORE_CASE)
            m.optString("url").takeIf { it.startsWith("http") && !bad.containsMatchIn(it) && Cfg.mailOwa.isEmpty() }?.let { Cfg.mailOwa = it; done.add("Outlook web") }
            m.optString("ews").takeIf { it.startsWith("http") && !bad.containsMatchIn(it) && Cfg.mailEws.isEmpty() }?.let { Cfg.mailEws = it }
        }
        cfg?.optJSONObject("web")?.optString("searxng")?.takeIf { it.startsWith("http") }?.let { Cfg.searxng = it }
        files["/.yaver/memories/USER.md"]?.let { t ->
            val l = t.split(Regex("\\n?§\\n?")).map { it.trim() }.filter { it.isNotEmpty() }
            if (l.isNotEmpty()) { Memory.set("user", (Memory.entries("user") + l).distinct()); done.add(Y.t("${l.size} facts about you", "hakkınızda ${l.size} bilgi")) }
        }
        files["/.yaver/memories/MEMORY.md"]?.let { t ->
            val l = t.split(Regex("\\n?§\\n?")).map { it.trim() }.filter { it.isNotEmpty() }
            if (l.isNotEmpty()) { Memory.set("notes", (Memory.entries("notes") + l).distinct()); done.add(Y.t("${l.size} memory notes", "${l.size} hafıza notu")) }
        }
        files["/.yaver/PERSONA.md"]?.let { t ->
            if (t.isNotBlank() && !t.contains("running entirely inside the user's web browser") && Cfg.persona.isEmpty()) { Cfg.persona = t.trim(); done.add("persona") }
        }
        var n = 0
        for ((p, t) in files) {
            if (!p.startsWith("/.yaver/sessions/") || !p.endsWith(".json")) continue
            try {
                val j = JSONObject(t)
                val msgs = j.optJSONArray("messages") ?: continue
                val c = Convo.create(j.optString("title", "yaver"))
                for (i in 0 until msgs.length()) {
                    val m = msgs.optJSONObject(i) ?: continue
                    val role = m.optString("role")
                    val content = m.opt("content")
                    if ((role == "user" || role == "assistant") && content is String && content.isNotBlank()) {
                        c.messages.put(JSONObject().put("role", role).put("content", content).put("display", content))
                    }
                }
                if (c.messages.length() == 0) continue
                val conv = Convo(c.id, c.title, j.optLong("created", c.created), j.optLong("updated", c.updated), c.messages)
                Convos.save(conv, touch = false)
                n++
            } catch (_: Exception) {}
        }
        if (n > 0) done.add(Y.t("$n conversations", "$n sohbet"))
        return if (done.isEmpty()) "" else Y.t("Brought over from the previous yaver: ", "Önceki yaver'den aktarıldı: ") + done.joinToString(", ")
    }
}
