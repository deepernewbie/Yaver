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

    /**
     * Events sent while no screen is listening (app closed, or the page still loading) wait here, in order, and are
     * replayed when the page says "ready" — so "yaver learned something", memory chips, follow-ups, notes and tasks
     * changes that happen in the background are not lost. Streaming text is transient and dropped (the chat reloads).
     */
    private val missed = ArrayDeque<Pair<String, String>>()
    private const val MISSED_MAX = 100
    private val TRANSIENT = setOf("delta", "think", "progress", "tool", "toolEnd", "step")

    fun emit(type: String, data: JSONObject) {
        // yaver 5.1: a run ended — scheduled windows and the token-use record (S51Bridge)
        if (type == "run" && data.optString("phase") == "done") try { S51Bridge.onRunDone(data.optString("convoId"), if (data.isNull("error")) null else data.optString("error")) } catch (_: Throwable) {}
        val payload = data.toString()
        synchronized(pendingLaunches) {
            if (!uiReady || host == null) {
                if (type == "run" && data.optString("phase") in TRANSIENT) return
                if (type == "resume" || type == "s1") return  // a fresh page reads the state itself
                missed.addLast(type to payload)
                while (missed.size > MISSED_MAX) missed.removeFirst()
                return
            }
        }
        js("window.YV&&YV.on(${JSONObject.quote(type)},$payload)")
    }

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
            if (!(uiReady && host != null)) { pendingLaunches.add(o); return }
        }
        emit("launch", o)
    }

    /** The page answered "ready" (after its reply is on its way): replay what it missed, then the pending launches. */
    fun onUiReady() {
        val (ev, l) = synchronized(pendingLaunches) {
            uiReady = true
            Pair(ArrayList(missed).also { missed.clear() }, ArrayList(pendingLaunches).also { pendingLaunches.clear() })
        }
        for ((t, d) in ev) js("window.YV&&YV.on(${JSONObject.quote(t)},$d)")
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
                .put("maxSteps", Cfg.maxSteps).put("contextTokens", Cfg.contextTokens).put("maxOut", Cfg.maxOut))
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
            .put("perm", Permissions.mode).put("llmx", Parity.llmJson())
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
            "contextTokens" -> s.toIntOrNull()?.let { Cfg.contextTokens = if (it <= 0) 0 else it.coerceIn(4000, 2000000) }
            "maxOut" -> s.toIntOrNull()?.let { Cfg.maxOut = it.coerceIn(0, 200000) }
            "lang" -> { Cfg.lang = s; Shortcuts.publish(Y.app) }
            "persona" -> Cfg.persona = s; "askRisky" -> Cfg.askRisky = b
            "mailOwa" -> { Cfg.mailOwa = s; Mail.reset() }; "mailEws" -> { Cfg.mailEws = s; Mail.reset() }
            "mailUser" -> { Cfg.mailUser = s; Mail.reset() }; "mailPass" -> { Cfg.mailPass = s; Mail.reset() }
            "mailTotp" -> Cfg.mailTotp = s; "mailPoll" -> s.toIntOrNull()?.let { Cfg.mailPoll = it.coerceIn(1, 120) }
            "mailNotify" -> Cfg.mailNotify = b; "mailAutoSubmit" -> Cfg.mailAutoSubmit = b
            "searxng" -> Cfg.searxng = s; "braveKey" -> Cfg.braveKey = s; "tavilyKey" -> Cfg.tavilyKey = s
            "alwaysOn" -> { Prefs.set("alwaysOn", b); if (b) AgentService.start(Y.app) else AgentService.stop(Y.app) }
            "notifyReplies", "voiceOnAssist", "speakVoice", "tileVoice", "runReminders" -> Prefs.set(k, b)
            else -> if (!Parity.setCfg(k, v)) throw IllegalArgumentException("unknown setting $k")
        }
    }

    // ---------------------------------------------------------------- chat

    fun listener(id: String, run: Run) = object : AgentListener {
        override fun onNote(text: String) { emit("note", JSONObject().put("convoId", id).put("text", text)) }
        override fun onDelta(text: String) { run.text.append(text); emit("run", JSONObject().put("convoId", id).put("phase", "delta").put("text", text)) }
        private var lastInfo = 0L
        override fun onThinkInfo(reasoning: Int, tool: String, args: Int, calls: Int) {
            val now = System.currentTimeMillis()
            if (now - lastInfo < 350) return
            lastInfo = now
            emit("run", JSONObject().put("convoId", id).put("phase", "think").put("reasoning", reasoning).put("tool", tool).put("args", args).put("calls", calls))
        }
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
        // yaver 5: chat.send {screen:true} attaches the captured screen (FiveScreen) to this message as context
        val sc = if (a.optBoolean("screen")) FiveScreen.take() else null
        if (sc != null) {
            if (a.optString("display").isBlank()) a.put("display", a.optString("text").trim().ifEmpty { Y.t("What is on my screen?", "Ekranımda ne var?") })
            a.put("text", FiveLogic.screenPrompt(sc.app.ifEmpty { "?" }, sc.pkg, sc.text, sc.image != null, a.optString("text")))
        }
        val att = a.list("attachments").let { l -> if (sc?.image != null && Workspace.resolve(sc.image).isFile) l + sc.image else l }
        Learn.touch()
        if (runs.containsKey(c.id)) {
            // a turn is running: the message joins it at the next safe point (or starts the next turn)
            val t = a.optString("text").trim() + if (att.isNotEmpty()) "\n[Attached files saved in the workspace: ${att.joinToString(", ")}]" else ""
            if (t.isBlank()) throw IllegalStateException(Y.t("yaver is still working on this chat.", "yaver bu sohbette hâlâ çalışıyor."))
            return steerOrQueue(c, t, a, att)
        }
        val images = att.filter { Workspace.isImage(it) }
        val files = att.filter { !Workspace.isImage(it) }
        val text = a.optString("text").trim().ifEmpty { Y.t("(see the attachments)", "(eklere bakın)") }
        val speak = a.optBoolean("speak")
        val run = Run(Cancel())
        if (runs.putIfAbsent(c.id, run) != null) return steerOrQueue(c, text, a, att)
        if (!c.background) Prefs.put("currentConvo", c.id)
        Y.bg {
            var answer = ""
            var err: String? = null
            try { answer = Agent.run(c, text, images, files, listener(c.id, run), run.cancel, display = a.optString("display").trim().ifEmpty { null }) }
            catch (e: CancelledException) { }
            catch (e: Throwable) { err = e.message ?: e.toString() }
            runs.remove(c.id, run)
            emit("run", JSONObject().put("convoId", c.id).put("phase", "done").put("error", err ?: JSONObject.NULL))
            if (speak && answer.isNotBlank()) Speaker.speak(answer.take(2500))
            val h = host
            if ((h == null || !h.resumedNow) && Prefs.on("notifyReplies", true))
                Notifier.show(Y.app, c.title, (err ?: answer).replace(Regex("[*#`>]"), "").trim().take(900), "open_convo", c.id)
        }
        return JSONObject().put("convoId", c.id).put("title", c.title)
    }

    /**
     * yaver 5.1: a message while a turn runs. mode "queue" → Turns.enqueue (its own turn after this one, returns queueId);
     * otherwise steer into the running turn (returns steerId when the harness gives ids).
     */
    private fun steerOrQueue(c: Convo, t: String, a: JSONObject, att: List<String>): JSONObject {
        val out = JSONObject().put("convoId", c.id).put("title", c.title)
        if (a.optString("mode") == "queue") {
            val qid = Turns.enqueue(c.id, a.optString("text").trim().ifEmpty { t }, a.optString("display").trim().ifEmpty { null }, att)
            return out.put("queued", true).put("mode", "queue").put("queueId", qid)
        }
        val sid = Turns.steer(c.id, t, a.optString("display").trim().ifEmpty { null })
        return out.put("steered", true).put("queued", true).put("mode", "steer").put("steerId", sid)
    }

    /** Turns.startNext: a queued message starts like a normal chat.send. */
    fun sendQueued(convoId: String, text: String, display: String?, files: List<String>) {
        val a = JSONObject().put("convoId", convoId).put("text", text).put("attachments", JSONArray(files))
        if (!display.isNullOrBlank()) a.put("display", display)
        if (!runs.containsKey(convoId)) emit("run", JSONObject().put("convoId", convoId).put("phase", "start").put("internal", JSONObject.NULL).put("label", JSONObject.NULL).put("text", display ?: text).put("fromQueue", true))
        try { send(a) } catch (e: Throwable) { emit("run", JSONObject().put("convoId", convoId).put("phase", "done").put("error", e.message ?: e.toString())) }
    }

    // ---------------------------------------------------------------- dispatch (worker thread)

    fun dispatch(m: String, a: JSONObject): Any? {
        // short mail handles (m3k9x2ab) used in answers and links → real Exchange ids
        if (m.startsWith("mail.") || m.startsWith("tasks.")) for (k in listOf("id", "ref", "attId")) if (a.has(k)) a.put(k, Refs.real(a.optString(k)))
        return dispatch0(m, a)
    }

    private fun dispatch0(m: String, a: JSONObject): Any? = when (m) {
        "state" -> state()
        "cfg.set" -> { setCfg(a.str("key"), a.opt("value")); state() }
        "models" -> JSONArray(Llm.models())
        "llm.test" -> Llm.ask("Answer in at most six words.", "Say hello and name yourself.", fast = false, maxTokens = 60).trim()
        "convos.list" -> JSONArray(Convos.list())
        "convos.load" -> {
            val c = Convos.load(a.str("id")) ?: throw IllegalArgumentException(Y.t("Conversation not found", "Sohbet bulunamadı"))
            val r = runs[c.id]
            // a turn the app died in: close its open tool calls so the history is valid (the "Continue" banner asks run.resumable)
            if (r == null) try { if (Agent.repair(c)) Convos.save(c) } catch (e: Throwable) { Diag.e("resume", "repair on load", e) }
            c.toJson().apply { remove("system") }.put("running", r != null).put("live", r?.text?.toString() ?: "").put("status", r?.status ?: "")
                .put("followups", JSONObject().put("items", JSONArray(FollowUps.get(c.id))).put("labels", FollowUps.labels()))
                .put("planning", Planning.state(c.id))
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
        "memory.get" -> JSONObject().put("user", JSONArray(MemoryX.entries("user"))).put("notes", JSONArray(MemoryX.entries("memory")))
        "memory.set" -> { MemoryX.save(if (a.str("target") == "user") "user" else "memory", a.list("list")); true }
        "workspace.list" -> CoreTools.tree(VPath.WS).ifEmpty { "" }
        // the Files screen (FilesUi): the virtual disk, as the agent sees it
        "files.list" -> FilesUi.list(a)
        "files.read" -> FilesUi.read(a)
        "files.extract" -> FilesUi.extract(a)
        "files.pdf" -> FilesUi.pdfPage(a)
        "files.write" -> FilesUi.write(a)
        "files.new" -> FilesUi.newFile(a)
        "files.rename" -> FilesUi.rename(a)
        "files.move" -> FilesUi.move(a)
        "files.copy" -> FilesUi.copy(a)
        "files.delete" -> FilesUi.delete(a)
        "files.mkdir" -> FilesUi.mkdir(a)
        "files.download" -> FilesUi.download(a)
        "files.folders" -> FilesUi.folders()
        "convo.export" -> {
            val c = Convos.load(a.str("id")) ?: throw IllegalArgumentException(Y.t("Conversation not found", "Sohbet bulunamadı"))
            val md = StringBuilder("# ${c.title}\n\n")
            for (i in 0 until c.messages.length()) {
                val m = c.messages.optJSONObject(i) ?: continue
                when (m.optString("role")) {
                    "user" -> md.append("## 🧑 ").append(Y.t("You", "Siz")).append("\n\n").append(m.optString("display", m.optString("content"))).append("\n\n")
                    "assistant" -> {
                        if (m.optString("content").isNotBlank()) md.append("## 🤖 yaver\n\n").append(m.optString("content")).append("\n\n")
                        val tc = m.optJSONArray("tool_calls")
                        if (tc != null) for (k in 0 until tc.length()) { val f = tc.optJSONObject(k)?.optJSONObject("function") ?: continue; md.append("> 🔧 `").append(f.optString("name")).append("` ").append(f.optString("arguments").take(600)).append("\n\n") }
                    }
                    "tool" -> md.append("<details><summary>").append(m.optString("name")).append(if (m.optBoolean("ok", true)) " ✓" else " ✗").append("</summary>\n\n```\n").append(m.optString("content").take(4000)).append("\n```\n</details>\n\n")
                }
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            val base = c.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifEmpty { "sohbet" } + "-" + stamp
            val mdPath = Workspace.write("exports", "$base.md", md.toString().toByteArray())
            val full = c.toJson().put("diagnostics", Diag.report().takeLast(80000))
            val jsPath = Workspace.write("exports", "$base.json", full.toString(1).toByteArray())
            val saved = try { Workspace.exportToDownloads(Y.app, Workspace.resolve(mdPath)); Workspace.exportToDownloads(Y.app, Workspace.resolve(jsPath)) } catch (e: Throwable) { Diag.e("export", "downloads", e); "" }
            JSONObject().put("md", mdPath).put("json", jsPath).put("saved", saved)
        }
        "diag.save" -> Workspace.write("logs", "yaver-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt", Diag.report().toByteArray()).also { p ->
            try { Workspace.exportToDownloads(Y.app, Workspace.resolve(p)) } catch (e: Throwable) { Diag.e("export", "log to downloads", e) }
        }
        "diag.get" -> Diag.report().takeLast(60000)
        "diag.clear" -> { Diag.clear(); true }
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
                .put("files", JSONArray(msg.files.filter { !(it.inline && it.type.startsWith("image/")) }.map { JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("size", it.size).put("item", it.item) }))
        }
        "mail.markRead" -> { Mail.use { it.markRead(a.str("id"), a.optBoolean("read", true)) }; try { MailIndex.markRead(a.str("id"), a.optBoolean("read", true)) } catch (_: Throwable) {}; true }
        // mail.remove / mail.move answer {id}: the item's new id in the folder it went to (Undo moves it back); permanent=true deletes for good
        "mail.remove" -> { val nid = Mail.use { it.remove(a.str("id"), a.optBoolean("permanent")) }; try { MailIndex.forget(a.str("id")) } catch (_: Throwable) {}; JSONObject().put("id", nid ?: JSONObject.NULL) }
        "mail.move" -> { val nid = Mail.use { it.move(a.str("id"), a.str("folder")) }; try { MailIndex.forget(a.str("id")) } catch (_: Throwable) {}; JSONObject().put("id", nid) }
        "mail.compose" -> Mail.use { it.compose(a.str("kind").ifEmpty { "new" }, a.str("ref"), a.list("to"), a.list("cc"), a.str("subject"), a.optString("body"), a.optBoolean("send")) }
        "mail.attachment" -> {
            val att = Mail.use { it.attachment(a.str("attId")) }
            // the server's name can hold path or reserved characters (attached e-mails: "RE: a/b?"): never a path
            val safe = att.name.ifEmpty { a.str("name") }.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+"), "_").trim().trim('.').take(150).ifEmpty { "attachment" }
            val path = Workspace.write("mail", safe, att.bytes)
            Y.ui { try { Workspace.open(host ?: Y.app, Workspace.resolve(path)) } catch (e: Throwable) { U.toast(Y.app, Y.t("Saved: ", "Kaydedildi: ") + path) } }
            path
        }
        "mail.events" -> {
            val start = if (a.has("start")) a.optLong("start") else java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
            val evs = Mail.use { it.events(start, start + a.int("days", 7) * 86400000L) }
            JSONArray(evs.map { e -> JSONObject().put("id", e.id).put("subject", e.subject).put("start", Exchange.parseTime(e.start)).put("end", Exchange.parseTime(e.end)).put("location", e.location)
                .put("organizer", e.organizer?.short ?: "").put("allDay", e.allDay).put("response", e.response).put("cancelled", e.cancelled).put("meeting", e.meeting) })
        }
        // ---- yaver 5: today brief, screen-aware assist, resume interrupted runs
        "brief.today" -> FiveBrief.today()
        "brief.refresh" -> { FiveBrief.refreshAsync(true); FiveBrief.snapshot() }
        "screen.context.take" -> FiveScreen.take()?.toJson()   // null when there is no capture (ret sends JSON null)
        "screen.context.clear" -> { FiveScreen.clear(); true }
        "run.resumable" -> {
            val c = Convos.load(a.str("convoId"))
            if (c == null || runs.containsKey(c.id)) null
            else try {
                // Agent.resumeInfo's own fields plus the names the UI uses
                (FiveHooks.resumeInfo ?: { x: Convo -> Agent.resumeInfo(x) }).invoke(c)?.also { j ->
                    j.put("convoId", c.id).put("lastUser", j.optString("question")).put("interruptedAt", j.optLong("at"))
                }
            } catch (e: Throwable) { Diag.e("resume", "resumeInfo", e); null }
        }
        "run.resume" -> {
            val id = a.str("convoId")
            if (!Cfg.llmReady) throw IllegalStateException(Y.t("Choose a model first (Settings → Model).", "Önce bir model seçin (Ayarlar → Model)."))
            if (Convos.load(id) == null) throw IllegalArgumentException(Y.t("Conversation not found", "Sohbet bulunamadı"))
            if (runs.containsKey(id) || Convos.isLive(id)) JSONObject().put("started", false).put("running", true)
            else {
                Prefs.put("currentConvo", id)
                JSONObject().put("started", Turns.start(id, FiveHooks.RESUME_MSG, Y.t("Continue the interrupted task", "Yarım kalan işe devam"), "resume")).put("convoId", id)
            }
        }
        "mail.respond" -> { Mail.use { it.respond(a.str("id"), a.str("response"), a.str("message")) }; true }
        else -> if (S51Bridge.handles(m)) S51Bridge.dispatch(m, a) else if (MailUi.handles(m)) MailUi.dispatch(m, a) else if (MailPlus.handles(m)) MailPlus.dispatch(m, a) else if (Parity.handles(m)) Parity.dispatch(m, a) else harness(m, a)
    }

    // ---------------------------------------------------------------- harness methods (planning, learning, memory, context…)

    private fun convo(a: JSONObject, key: String = "convoId"): Convo = Convos.load(a.str(key)) ?: throw IllegalArgumentException(Y.t("Conversation not found", "Sohbet bulunamadı"))
    private fun entry(a: JSONObject) = a.optString("entry").ifEmpty { a.optString("text").ifEmpty { a.optString("id") } }

    private fun harness(m: String, a: JSONObject): Any? = when (m) {
        "ask.answer" -> {
            val v = a.opt("answer")
            Ask.answer(a.optInt("reqId"), when (v) { is JSONObject -> v; null, JSONObject.NULL -> null; else -> JSONObject().put("text", v.toString()) })
            true
        }
        "plan.approve" -> {
            val id = a.str("convoId")
            // the plan card's "Edit" sends the edited plan: the model follows that version
            val edited = a.str("plan")
            val go = Planning.approvePlan(id).let { if (edited.isNotEmpty()) "$it\nThe user edited the plan before approving it; follow this version:\n$edited" else it }
            val ok = Turns.start(id, go, Y.t("Plan approved", "Plan onaylandı"), "plan")
            Diag.i("planning", "plan approved for $id (started=$ok)")
            JSONObject().put("started", ok)
        }
        "planning.state" -> Planning.state(a.str("convoId"))
        "command" -> command(a.str("convoId"), a.optString("text").trim())
        "feedback" -> {
            val c = convo(a)
            val idx = a.optInt("idx", -1); val vote = a.optInt("vote", 0)
            if (a.has("comment") && !a.isNull("comment")) {
                val note = Learn.feedback(c, idx, vote, a.optString("comment"))
                JSONObject().put("fb", c.messages.optJSONObject(idx)?.optInt("fb", 0) ?: 0).put("askNote", false).put("note", note)
            } else Learn.toggle(c, idx, vote).put("placeholder", Learn.feedbackPlaceholder())
        }
        "followups.get" -> JSONObject().put("items", JSONArray(FollowUps.get(a.str("convoId")))).put("labels", FollowUps.labels()).put("on", FollowUps.enabled)
        "followups.set" -> {
            if (a.has("on")) FollowUps.enabled = a.optBoolean("on")
            if (a.optBoolean("hide") || a.optBoolean("clear")) FollowUps.clear(a.str("convoId"))
            JSONObject().put("on", FollowUps.enabled)
        }
        "learn.state" -> learnState()
        "learn.set" -> {
            if (a.has("learn")) LearnSettings.learn = a.optBoolean("learn")
            if (a.has("every")) Harness.setAuto(a.optInt("every", 0) > 0, a.optInt("every", 0))
            learnState()
        }
        "s1.state" -> Gates.log(0).put("backends", JSONArray(S1Cfg.BACKENDS.map { JSONObject().put("id", it.first).put("label", it.second) }))
        "s1.set" -> {
            S1Cfg.apply(a)
            if (a.has("gate")) S1Cfg.setGate(a.str("gate"), a.optBoolean("on", true))
            if (a.optBoolean("resetStats")) S1.resetStats()
            if (a.optBoolean("clearLog")) S1.clearLog()
            Gates.log(0)
        }
        "gates.log" -> Gates.log(a.int("limit", 200))
        "ctx.set" -> {
            // the UI sends {key, value}; plain {autoCompact: …} also works
            if (a.has("key")) a.put(a.str("key"), a.opt("value"))
            if (a.has("window")) {
                val w = a.optInt("window", 0)
                // a window set by hand also lifts the token budget to use it (the user can lower "Context budget" again)
                if (w > 0 && Cfg.contextTokens in 1 until ((w - Compactor.outReserve(w)) * 0.92).toInt()) Cfg.contextTokens = 0  // automatic now uses the window
            }
            if (a.has("autoCompact")) CtxCfg.autoCompact = a.optBoolean("autoCompact")
            if (a.has("compactAt")) CtxCfg.compactAt = a.optInt("compactAt", 90)
            if (a.has("keepTurns")) CtxCfg.keepTurns = a.optInt("keepTurns", 3)
            if (a.has("window")) CtxCfg.window = a.optInt("window", 0)
            JSONObject().put("autoCompact", CtxCfg.autoCompact).put("compactAt", CtxCfg.compactAt).put("keepTurns", CtxCfg.keepTurns).put("window", CtxCfg.window)
        }
        "ctx.stats" -> if (a.str("convoId").isEmpty() || a.str("convoId") == "_new") JSONObject().put("autoCompact", CtxCfg.autoCompact).put("compactAt", CtxCfg.compactAt).put("keepTurns", CtxCfg.keepTurns).put("window", CtxCfg.window).put("windowSource", if (CtxCfg.window > 0) "set" else "").put("budget", Compactor.budget()) else Compactor.stats(convo(a))
        "mail.photo" -> MailPhotos.get(a.str("email")) ?: JSONObject.NULL
        "people.search" -> {
            val q = a.str("q")
            val out = JSONArray(); val seen = HashSet<String>()
            fun add(name: String, email: String, extra: String) { if (email.isNotEmpty() && seen.add(email.lowercase())) out.put(JSONObject().put("name", name.ifEmpty { email }).put("email", email).put("extra", extra)) }
            try { MailIndex.people(q, 8).forEach { add(it.name, it.email, "") } } catch (_: Throwable) {}
            if (out.length() < 8 && q.trim().length >= 2) try { Mail.use { it.resolve(q, 8) }.forEach { add(it.name, it.email, listOf(it.title, it.dept).filter { s -> s.isNotBlank() }.joinToString(" · ")) } } catch (_: Throwable) {}
            out
        }
        "mail.refs" -> {
            // which of these words are e-mail handles (m + 7 letters/digits) — answers mention them; they become links
            val out = JSONObject(); val arr = a.optJSONArray("tokens") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), 200)) {
                val t = arr.optString(i); val id = Refs.known(t) ?: continue
                val e = try { MailIndex.entry(id) } catch (_: Throwable) { null }
                out.put(t, JSONObject().put("subject", e?.s ?: "").put("from", e?.fr?.let { it.name.ifEmpty { it.email } } ?: ""))
            }
            out
        }
        "mail.title" -> {
            val id = a.str("id")
            MailIndex.entry(id)?.let { e -> JSONObject().put("subject", e.s).put("from", e.fr?.let { it.name.ifEmpty { it.email } } ?: "") }
                ?: try { val m = Mail.use { it.get(id) }; JSONObject().put("subject", m.h.subject).put("from", m.h.from?.let { it.name.ifEmpty { it.email } } ?: "") } catch (e: Throwable) { JSONObject() }
        }
        "followups.clear" -> { FollowUps.clear(a.str("convoId")); true }
        "ctx.clear" -> JSONObject().put("note", clearContext(a.str("convoId")))
        "ctx.compact" -> {
            val c = convo(a)
            if (runs.containsKey(c.id)) throw IllegalStateException(Y.t("Stop the running task first", "Önce çalışan işi durdurun"))
            val notes = ArrayList<String>()
            val log = Compactor.compactNow(c, a.optString("focus"), null) { notes.add(it) }
            Diag.i("ctx", "compact now ${c.id}: " + notes.joinToString(" "))
            JSONObject().put("log", log ?: JSONObject.NULL).put("note", notes.joinToString("\n")).put("stats", Compactor.stats(c))
        }
        "lessons.screen" -> Harness.screen(a.str("convoId").ifEmpty { null }, a.str("filter").ifEmpty { "all" }, a.optString("q"))
        "lessons.enable" -> Harness.setEnabled(a.str("kind"), a.str("id"), a.optBoolean("on", true))
        "lessons.delete" -> Harness.delete(a.str("kind"), a.str("id"))
        "lessons.edit" -> Harness.edit(a.str("kind"), a.str("id"), a.optString("title"), a.optString("content"))
        "lessons.undo" -> Harness.undo(a.str("id"), a.str("convoId").ifEmpty { null })
        "lessons.sleep" -> { Y.bg { try { Learn.sleep(true) } catch (e: Throwable) { Diag.e("learn", "sleep", e); emit("toast", JSONObject().put("text", e.message ?: e.toString())) } }; true }
        "memory.list" -> MemoryX.uiList(a.str("target").ifEmpty { "memory" })
        "memory.add" -> MemoryX.uiAdd(a.str("target").ifEmpty { "memory" }, a.optString("text"))
        "memory.edit" -> MemoryX.uiEdit(a.str("target").ifEmpty { "memory" }, a.optString("entry").ifEmpty { a.optString("id") }, a.optString("text"))
        "memory.pin" -> MemoryX.uiPin(a.str("target").ifEmpty { "memory" }, entry(a), a.optBoolean("on", true))
        "memory.tier" -> MemoryX.uiToggleTier(entry(a))
        "memory.delete" -> MemoryX.uiDelete(a.str("target").ifEmpty { "memory" }, entry(a))
        "memory.trash" -> MemoryX.uiTrash()
        "memory.restore" -> MemoryX.uiRestore(a.str("id"))
        "memory.forget" -> MemoryX.uiForget(a.str("id"))
        "memory.emptyTrash" -> MemoryX.uiEmptyTrash()
        "memory.reindex" -> MemoryX.uiReindex()
        "skills.list" -> Skills.listJson()
        "folders.state" -> Folders.state()
        "folders.sync" -> Folders.sync(a.str("name").ifEmpty { null })
        "folders.remove" -> Folders.remove(a.str("name"))
        "folders.deletes" -> Folders.resolveDeletes(a.str("name"), a.optBoolean("delete"))
        else -> throw IllegalArgumentException("unknown method $m")
    }

    private fun learnState() = JSONObject().put("learn", LearnSettings.learn).put("every", Harness.every()).put("followups", FollowUps.enabled)
        .put("sleepLog", JSONArray(Learn.sleepLog().take(10))).put("failures", Learn.failures().size)

    /**
     * /clear (as in the original): yaver forgets the earlier messages of this chat; they stay visible, dimmed,
     * behind a divider. Todos and follow-ups go, an active goal is paused.
     */
    private fun clearContext(convoId: String): String {
        if (runs.containsKey(convoId)) return Y.t("Stop the running task first", "Önce çalışan işi durdurun")
        val c = Convos.load(convoId) ?: return Y.t("Conversation not found", "Sohbet bulunamadı")
        if (c.messages.length() == 0 || c.summaryUpTo >= c.messages.length()) return Y.t("The context is already empty", "Bağlam zaten boş")
        try { Parity.rememberClear(c) } catch (_: Throwable) {}   // for "Undo" (ctx.restore)
        c.messages.put(JSONObject().put("role", "assistant").put("content", "").put("local", true).put("divider", true).put("ts", System.currentTimeMillis()))
        c.summary = ""; c.summaryUpTo = c.messages.length()
        try { Compactor.forget(c.id) } catch (_: Throwable) {}
        try { Planning.onContextCleared(c) } catch (_: Throwable) {}
        try { FollowUps.clear(c.id) } catch (_: Throwable) {}
        Convos.save(c)
        Diag.i("ctx", "context cleared in ${c.id}")
        return Y.t("Context cleared — yaver no longer sees the earlier messages", "Bağlam temizlendi — yaver önceki mesajları artık görmüyor")
    }

    /** Slash commands typed in the composer. Returns {note?, send?, started?}: [send] is sent by the UI as the user's message. */
    private fun command(convoId0: String, text: String): JSONObject {
        val out = JSONObject()
        val m = Regex("^/(\\w+)\\s*([\\s\\S]*)$").find(text) ?: return out.put("send", text)
        val cmd = m.groupValues[1]; val rest = m.groupValues[2].trim()
        var convoId = convoId0
        // a command typed in a new chat (/plan, /goal, /autonomous, /heartbeat, /btw): make the chat now so the
        // setting has somewhere to live; the UI takes the id from "convoId"
        if (convoId.isEmpty() && cmd in setOf("plan", "goal", "autonomous", "heartbeat", "btw")) {
            val c = Convo.create(); Convos.save(c); Prefs.put("currentConvo", c.id); convoId = c.id
        }
        if (convoId.isNotEmpty()) out.put("convoId", convoId)
        if (convoId.isEmpty() && cmd in setOf("compact", "clear", "refine", "subagents", "heartbeats"))
            return out.put("note", if (cmd == "compact" || cmd == "clear") Y.t("The context is already empty", "Bağlam zaten boş") else Y.t("Start a conversation first", "Önce bir sohbet başlatın"))
        val busy = runs.containsKey(convoId)
        Diag.i("command", "/$cmd ${clip(rest, 80)}")
        when (cmd) {
            "plan", "goal", "autonomous" -> {
                val c = convo(JSONObject().put("convoId", convoId))
                val r = Planning.command(c, text, busy) ?: return out.put("send", text)
                r.note?.let { out.put("note", it) }
                val s = r.send
                if (s != null) {
                    if (r.internal) out.put("started", Turns.start(convoId, s, r.label ?: s.take(60), if (cmd == "goal") "goal" else "auto"))
                    else out.put("send", s)
                }
            }
            "compact" -> {
                val c = convo(JSONObject().put("convoId", convoId))
                if (busy) out.put("note", Y.t("Stop the running task first", "Önce çalışan işi durdurun"))
                else { val notes = ArrayList<String>(); Compactor.compactNow(c, rest, null) { notes.add(it) }; out.put("note", notes.joinToString("\n").ifEmpty { Y.t("🗜 Nothing to compact yet — the conversation is short.", "🗜 Henüz sıkıştırılacak bir şey yok — konuşma kısa.") }) }
            }
            "refine", "harness" -> {
                // /harness only lists what was learned: a new (unsaved) chat is enough when none is open
                val c = Convos.load(convoId) ?: if (cmd == "harness") Convo.create() else convo(JSONObject().put("convoId", convoId))
                Harness.command(c, text, busy)?.let { out.put("note", it) }
            }
            "heartbeat" -> out.put("note", Heartbeats.command(convoId, rest))
            "heartbeats" -> out.put("note", Heartbeats.listNote(convoId))
            "subagents" -> out.put("note", Children.command(convoId, rest))
            "btw" -> SideQuestion.command(convoId, rest)?.let { out.put("note", it) }
            "today" -> out.put("send", MailPlus.dailyPlanPrompt())
            "clear" -> out.put("note", clearContext(convoId))
            else -> Parity.command(convoId, cmd, rest, out) ?: out.put("send", text)
        }
        if (cmd in setOf("plan", "goal", "autonomous") && convoId.isNotEmpty()) try { out.put("planning", Planning.state(convoId)) } catch (_: Throwable) {}
        return out
    }

    private fun boxJson(b: MailBox) = JSONObject().put("name", b.name).put("email", b.email)
    private fun headerJson(h: MailHeader) = MailUi.headerJson(h)

    /** Methods that need the screen (pickers, voice, system pages) — run on the main thread by the host. */
    val UI_METHODS = setOf("folder.connect", "files.pick", "voice.listen", "mail.login", "import.zip", "open.link", "open.file", "share.text", "share.file", "system.open")

    class Api {
        @JavascriptInterface fun call(id: Int, method: String?, args: String?) {
            val m = method ?: ""
            val a = try { JSONObject(if (args.isNullOrEmpty()) "{}" else args) } catch (e: Exception) { JSONObject() }
            // answer first: the page builds its screen from this reply, then the replayed events and launches arrive
            if (m == "ready") { ret(id, true, state()); onUiReady(); return }
            if (m in UI_METHODS) { Y.ui { val h = host; if (h == null) ret(id, false, Y.t("yaver is not on screen", "yaver ekranda değil")) else h.uiCall(id, m, a) }; return }
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
