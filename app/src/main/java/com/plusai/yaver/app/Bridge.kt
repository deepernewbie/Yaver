package com.plusai.yaver.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The link between yaver's interface and its native core. The interface calls [Api.call]; answers come back
 * through YV.ret(id, ok, value) and live updates (streaming text, tool steps, approvals) through YV.on(type, data).
 */
object Bridge {
    @Volatile var host: MainActivity? = null
    private val pendingLaunches = ArrayList<JSONObject>()
    @Volatile private var uiReady = false

    class Run(val cancel: Cancel) {
        val text = StringBuilder()
        @Volatile var status = ""
    }

    val runs = ConcurrentHashMap<String, Run>()
    private val approvals = ConcurrentHashMap<Int, Pair<CountDownLatch, BooleanArray>>()
    private val seq = AtomicInteger(0)

    fun js(code: String) = Y.ui { host?.web?.evaluateJavascript(code, null) }

    fun emit(type: String, data: JSONObject) = js("window.YV&&YV.on(${JSONObject.quote(type)},$data)")

    fun ret(id: Int, ok: Boolean, value: Any?) {
        val v = when (value) {
            null -> "null"
            is JSONObject, is JSONArray -> value.toString()
            is String -> JSONObject.quote(value)
            is Boolean, is Number -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
        js("window.YV&&YV.ret($id,$ok,$v)")
    }

    fun launch(o: JSONObject) {
        synchronized(pendingLaunches) {
            if (uiReady && host != null) emit("launch", o) else pendingLaunches.add(o)
        }
    }

    fun onUiReady() {
        val l = synchronized(pendingLaunches) { uiReady = true; ArrayList(pendingLaunches).also { pendingLaunches.clear() } }
        for (o in l) emit("launch", o)
    }

    fun onHostGone() { uiReady = false }

    // ---------------------------------------------------------------- state

    fun state(): JSONObject {
        val c = Y.app
        val assistant = Settings.Secure.getString(c.contentResolver, "assistant") ?: ""
        return JSONObject()
            .put("lang", if (Y.tr) "tr" else "en").put("langSetting", Cfg.lang)
            .put("version", try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (e: Throwable) { "" })
            .put("llm", JSONObject().put("ready", Cfg.llmReady).put("base", Cfg.llmBase).put("hasKey", Cfg.llmKey.isNotEmpty()).put("model", Cfg.model)
                .put("fastModel", Cfg.fastModel).put("visionModel", Cfg.visionModel).put("stream", Cfg.stream).put("sendImages", Cfg.sendImages)
                .put("maxSteps", Cfg.maxSteps).put("contextTokens", Cfg.contextTokens))
            .put("persona", Cfg.persona).put("personaDefault", Agent.defaultPersona()).put("askRisky", Cfg.askRisky)
            .put("mail", mailState())
            .put("web", JSONObject().put("searxng", Cfg.searxng).put("hasBrave", Cfg.braveKey.isNotEmpty()).put("hasTavily", Cfg.tavilyKey.isNotEmpty()))
            .put("phone", JSONObject()
                .put("alwaysOn", Prefs.on("alwaysOn", true)).put("notifyReplies", Prefs.on("notifyReplies", true)).put("voiceOnAssist", Prefs.on("voiceOnAssist", false))
                .put("speakVoice", Prefs.on("speakVoice", true)).put("tileVoice", Prefs.on("tileVoice", true)).put("runReminders", Prefs.on("runReminders", true))
                .put("isAssistant", assistant.contains(c.packageName)).put("serviceRunning", AgentService.running)
                .put("unrestricted", c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true)
                .put("notifications", Notifier.canPost(c)).put("calendar", c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED))
            .put("running", JSONArray(runs.keys.toList()))
            .put("current", Prefs.get("currentConvo", ""))
            .put("dark", Y.dark(c))
    }

    fun mailState(): JSONObject = JSONObject()
        .put("configured", Cfg.mailConfigured).put("connected", Mail.connected).put("needsSignIn", Mail.needsSignIn)
        .put("status", Mail.status).put("error", Mail.lastError).put("owa", Cfg.mailOwa).put("ews", Cfg.mailEws)
        .put("user", Cfg.mailUser).put("hasPass", Cfg.mailPass.isNotEmpty()).put("hasTotp", Cfg.mailTotp.isNotEmpty())
        .put("poll", Cfg.mailPoll).put("notify", Cfg.mailNotify).put("autoSubmit", Cfg.mailAutoSubmit)

    private fun setCfg(k: String, v: Any?) {
        val s = v?.toString() ?: ""
        val b = v == true || s == "true" || s == "1"
        when (k) {
            "llmBase" -> Cfg.llmBase = s; "llmKey" -> Cfg.llmKey = s; "model" -> Cfg.model = s; "fastModel" -> Cfg.fastModel = s; "visionModel" -> Cfg.visionModel = s
            "stream" -> Cfg.stream = b; "sendImages" -> Cfg.sendImages = b
            "maxSteps" -> s.toIntOrNull()?.let { Cfg.maxSteps = it.coerceIn(3, 100) }
            "contextTokens" -> s.toIntOrNull()?.let { Cfg.contextTokens = it.coerceIn(4000, 2000000) }
            "lang" -> { Cfg.lang = s; Shortcuts.publish(Y.app) }
            "persona" -> Cfg.persona = s; "askRisky" -> Cfg.askRisky = b
            "mailOwa" -> { Cfg.mailOwa = s; Mail.reset() }; "mailEws" -> { Cfg.mailEws = s; Mail.reset() }
            "mailUser" -> { Cfg.mailUser = s; Mail.reset() }; "mailPass" -> { Cfg.mailPass = s; Mail.reset() }
            "mailTotp" -> Cfg.mailTotp = s; "mailPoll" -> s.toIntOrNull()?.let { Cfg.mailPoll = it.coerceIn(1, 120) }
            "mailNotify" -> Cfg.mailNotify = b; "mailAutoSubmit" -> Cfg.mailAutoSubmit = b
            "searxng" -> Cfg.searxng = s; "braveKey" -> Cfg.braveKey = s; "tavilyKey" -> Cfg.tavilyKey = s
            "alwaysOn" -> { Prefs.set("alwaysOn", b); if (b) AgentService.start(Y.app) else AgentService.stop(Y.app) }
            "notifyReplies", "voiceOnAssist", "speakVoice", "tileVoice", "runReminders" -> Prefs.set(k, b)
            else -> throw IllegalArgumentException("unknown setting $k")
        }
    }

    // ---------------------------------------------------------------- chat

    private fun listener(id: String, run: Run) = object : AgentListener {
        override fun onDelta(text: String) { run.text.append(text); emit("run", JSONObject().put("convoId", id).put("phase", "delta").put("text", text)) }
        override fun onStep() { run.text.setLength(0); emit("run", JSONObject().put("convoId", id).put("phase", "step")) }
        override fun onToolStart(name: String, args: JSONObject) { run.status = name; emit("run", JSONObject().put("convoId", id).put("phase", "tool").put("name", name).put("args", args)) }
        override fun onToolEnd(name: String, result: String, ok: Boolean) { run.status = ""; emit("run", JSONObject().put("convoId", id).put("phase", "toolEnd").put("name", name).put("ok", ok)) }
        override fun onProgress(text: String) { run.status = text; emit("run", JSONObject().put("convoId", id).put("phase", "progress").put("text", text)) }
        override fun approve(title: String, details: String, danger: Boolean): Boolean {
            if (host == null) return false
            val rid = seq.incrementAndGet()
            val l = CountDownLatch(1)
            val box = BooleanArray(1)
            approvals[rid] = Pair(l, box)
            emit("approve", JSONObject().put("reqId", rid).put("convoId", id).put("title", title).put("details", details).put("danger", danger))
            l.await(15, TimeUnit.MINUTES)
            approvals.remove(rid)
            return box[0]
        }
    }

    fun answer(rid: Int, ok: Boolean) { approvals[rid]?.let { it.second[0] = ok; it.first.countDown() } }

    private fun send(a: JSONObject): JSONObject {
        if (!Cfg.llmReady) throw IllegalStateException(Y.t("Choose a model first (Settings → Model).", "Önce bir model seçin (Ayarlar → Model)."))
        val id = a.str("convoId")
        val c = (if (id.isNotEmpty()) Convos.load(id) else null) ?: Convo.create()
        if (runs.containsKey(c.id)) throw IllegalStateException(Y.t("yaver is still working on this chat.", "yaver bu sohbette hâlâ çalışıyor."))
        val att = a.list("attachments")
        val images = att.filter { Workspace.isImage(it) }
        val files = att.filter { !Workspace.isImage(it) }
        val text = a.optString("text").trim().ifEmpty { Y.t("(see the attachments)", "(eklere bakın)") }
        val speak = a.optBoolean("speak")
        val run = Run(Cancel())
        runs[c.id] = run
        if (!c.background) Prefs.put("currentConvo", c.id)
        Y.bg {
            var answer = ""
            var err: String? = null
            try { answer = Agent.run(c, text, images, files, listener(c.id, run), run.cancel) }
            catch (e: CancelledException) { }
            catch (e: Throwable) { err = e.message ?: e.toString() }
            runs.remove(c.id)
            emit("run", JSONObject().put("convoId", c.id).put("phase", "done").put("error", err ?: JSONObject.NULL))
            if (speak && answer.isNotBlank()) Speaker.speak(answer.take(2500))
            val h = host
            if ((h == null || !h.resumedNow) && Prefs.on("notifyReplies", true))
                Notifier.show(Y.app, c.title, (err ?: answer).replace(Regex("[*#`>]"), "").trim().take(900), "open_convo", c.id)
        }
        return JSONObject().put("convoId", c.id).put("title", c.title)
    }

    // ---------------------------------------------------------------- dispatch (worker thread)

    fun dispatch(m: String, a: JSONObject): Any? = when (m) {
        "state" -> state()
        "cfg.set" -> { setCfg(a.str("key"), a.opt("value")); state() }
        "models" -> JSONArray(Llm.models())
        "llm.test" -> Llm.ask("Answer in at most six words.", "Say hello and name yourself.", fast = false, maxTokens = 60).trim()
        "convos.list" -> JSONArray(Convos.list())
        "convos.load" -> {
            val c = Convos.load(a.str("id")) ?: throw IllegalArgumentException("not found")
            val r = runs[c.id]
            c.toJson().put("running", r != null).put("live", r?.text?.toString() ?: "").put("status", r?.status ?: "")
        }
        "convos.delete" -> { Convos.delete(a.str("id")); true }
        "convos.rename" -> { Convos.rename(a.str("id"), a.str("title")); true }
        "convos.search" -> JSONArray(Convos.search(a.str("q"), 40).map { (meta, snip) -> JSONObject(meta.toString()).put("snippet", snip) })
        "chat.send" -> send(a)
        "chat.stop" -> { runs[a.str("convoId")]?.cancel?.cancel(); true }
        "approve.answer" -> { answer(a.optInt("reqId"), a.optBoolean("ok")); true }
        "speak" -> { Speaker.speak(a.str("text")); true }
        "speak.stop" -> { Speaker.stop(); true }
        "copy" -> { Phone.onMain { Y.app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("yaver", a.optString("text"))) }; true }
        "memory.get" -> JSONObject().put("user", JSONArray(Memory.entries("user"))).put("notes", JSONArray(Memory.entries("notes")))
        "memory.set" -> { Memory.set(a.str("target"), a.list("list")); true }
        "schedule.list" -> Schedule.all()
        "schedule.cancel" -> { Schedule.remove(a.optInt("id")); true }
        "workspace.list" -> Workspace.list(Workspace.root).ifEmpty { "" }
        "crash.last" -> File(Y.app.filesDir, "last-crash-shown.txt").let { if (it.exists()) it.readText().take(8000) else "" }
        "cookies.clear" -> { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); Mail.reset(); true }
        // ---- mail
        "mail.state" -> mailState()
        "mail.test" -> { Mail.reset(); try { Mail.connect() } catch (e: Throwable) { }; mailState() }
        "mail.list" -> {
            val r = Mail.use { it.find(a.str("folder").ifEmpty { "inbox" }, a.str("query"), a.optBoolean("unread"), a.int("limit", 40), a.int("offset", 0)) }
            JSONObject().put("total", r.total).put("items", JSONArray(r.items.map { headerJson(it) }))
        }
        "mail.get" -> {
            val msg = Mail.use { it.get(a.str("id")) }
            if (!msg.h.read) try { Mail.use { it.markRead(msg.h.id, true) } } catch (_: Throwable) {}
            headerJson(msg.h).put("kind", msg.kind).put("to", JSONArray(msg.to.map { boxJson(it) })).put("cc", JSONArray(msg.cc.map { boxJson(it) }))
                .put("body", msg.body).put("invite", msg.invite ?: JSONObject.NULL)
                .put("files", JSONArray(msg.files.filter { !(it.inline && it.type.startsWith("image/")) }.map { JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("size", it.size) }))
        }
        "mail.markRead" -> { Mail.use { it.markRead(a.str("id"), a.optBoolean("read", true)) }; true }
        "mail.remove" -> { Mail.use { it.remove(a.str("id"), false) }; true }
        "mail.move" -> { Mail.use { it.move(a.str("id"), a.str("folder")) }; true }
        "mail.compose" -> Mail.use { it.compose(a.str("kind").ifEmpty { "new" }, a.str("ref"), a.list("to"), a.list("cc"), a.str("subject"), a.optString("body"), a.optBoolean("send")) }
        "mail.attachment" -> {
            val att = Mail.use { it.attachment(a.str("attId")) }
            val path = Workspace.write("mail", att.name.ifEmpty { a.str("name") }, att.bytes)
            Y.ui { try { Workspace.open(host ?: Y.app, Workspace.resolve(path)) } catch (e: Throwable) { U.toast(Y.app, "Saved: $path") } }
            path
        }
        "mail.events" -> {
            val start = if (a.has("start")) a.optLong("start") else java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
            val evs = Mail.use { it.events(start, start + a.int("days", 7) * 86400000L) }
            JSONArray(evs.map { e -> JSONObject().put("id", e.id).put("subject", e.subject).put("start", Exchange.parseTime(e.start)).put("end", Exchange.parseTime(e.end)).put("location", e.location)
                .put("organizer", e.organizer?.short ?: "").put("allDay", e.allDay).put("response", e.response).put("cancelled", e.cancelled).put("meeting", e.meeting) })
        }
        "mail.respond" -> { Mail.use { it.respond(a.str("id"), a.str("response"), a.str("message")) }; true }
        else -> throw IllegalArgumentException("unknown method $m")
    }

    private fun boxJson(b: MailBox) = JSONObject().put("name", b.name).put("email", b.email)
    private fun headerJson(h: MailHeader) = JSONObject().put("id", h.id).put("subject", h.subject).put("from", h.from?.let { boxJson(it) } ?: JSONObject.NULL)
        .put("received", Exchange.parseTime(h.received)).put("read", h.read).put("attachments", h.attachments).put("importance", h.importance).put("preview", h.preview)

    /** Methods that need the screen (pickers, voice, system pages) — run on the main thread by the host. */
    val UI_METHODS = setOf("files.pick", "voice.listen", "mail.login", "import.zip", "open.link", "open.file", "share.text", "share.file", "system.open", "settings.native")

    class Api {
        @JavascriptInterface fun call(id: Int, method: String?, args: String?) {
            val m = method ?: ""
            val a = try { JSONObject(if (args.isNullOrEmpty()) "{}" else args) } catch (e: Exception) { JSONObject() }
            if (m == "ready") { onUiReady(); ret(id, true, state()); return }
            if (m in UI_METHODS) { Y.ui { val h = host; if (h == null) ret(id, false, "no screen") else h.uiCall(id, m, a) }; return }
            Y.bg {
                try { ret(id, true, dispatch(m, a)) }
                catch (e: Throwable) { ret(id, false, (e.message ?: e.toString()).let { msg -> if (e is LoginRequired) "SIGNIN:$msg" else msg }) }
            }
        }
    }

    fun systemIntent(what: String): Intent? {
        val pkg = Y.app.packageName
        return when (what) {
            "assistant" -> Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
            "assistantAlt" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            "exactAlarm" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")) else null
            else -> null
        }
    }
}
