package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bridge methods, settings, slash commands and tool aliases that close the gaps to the original (PARITY.md):
 * permission presets, per-turn file changes, whole-state export/import, the skills editor, the debug dump, LLM
 * settings (temperature, extra headers), memory settings, undo of /clear, LLM call statistics, editable shortcuts.
 * Kept in one place so Bridge.kt only forwards to it.
 */
object Parity {
    private val METHODS = setOf(
        "perm.get", "perm.set", "changes.diff", "changes.revert", "state.export", "state.importApply", "state.stagePath", "state.extract", "state.erase", "app.restart",
        "skills.list2", "skills.get", "skills.save", "skills.new", "skills.copy", "skills.delete", "skills.addFile",
        "convo.debug", "llmx.get", "llmx.set", "memx.get", "memx.set", "ctx.restore", "stats.get", "stats.reset", "shortcuts.get", "shortcuts.set",
        "files.exists", "files.upload", "memory.raw", "memory.rawSave", "models.info", "browser.view", "browser.live", "browser.go", "browser.back", "browser.reload", "browser.takeover", "gates.clearLog", "files.view", "files.runHtml", "files.stats"
    )

    fun handles(m: String) = m in METHODS

    fun dispatch(m: String, a: JSONObject): Any? = when (m) {
        "perm.get" -> permJson()
        "perm.set" -> { Permissions.mode = a.str("mode"); permJson() }
        "changes.diff" -> Changes.diffOf(a.str("id"), a.str("path"))
        "changes.revert" -> {
            val r = Changes.revert(a.str("id"))
            // the card shows "reverted" after a reload too
            a.str("convoId").takeIf { it.isNotEmpty() }?.let { id -> Convos.load(id)?.let { c -> markReverted(c, a.str("id")) } }
            r
        }
        "state.export" -> {
            if (a.has("keys")) StateIO.includeKeys = a.optBoolean("keys")
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm", java.util.Locale.US).format(java.util.Date())
            val f = File(File(Y.app.filesDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }, "yaver-state-$stamp.zip")
            val r = StateIO.export(f)
            val saved = try { Workspace.exportToDownloads(Y.app, f) } catch (e: Throwable) { Diag.e("export", "downloads", e); "" }
            if (a.optBoolean("share")) Y.ui { try { Workspace.share(Bridge.host ?: Y.app, f) } catch (e: Throwable) { Diag.e("export", "share", e) } }
            r.put("saved", saved)
        }
        "state.stagePath" -> StateIO.stagePath(a.str("path"))
        "state.importApply" -> StateIO.applyStaged(a.str("token"), a.str("mode").ifEmpty { "merge" })
        "state.extract" -> StateIO.extract(a.str("path"))
        "state.erase" -> { if (a.str("confirm") != "ERASE") throw IllegalArgumentException("not confirmed"); StateIO.eraseAll(); Prefs.set("migrated", true); StateIO.restart(); true }
        "app.restart" -> { StateIO.restart(); true }
        "skills.list2" -> SkillsUi.list()
        "skills.get" -> SkillsUi.get(a.str("dir"))
        "skills.save" -> SkillsUi.save(a.str("dir"), a.optString("name"), a.optString("description"), a.optString("body"))
        "skills.new" -> SkillsUi.create(a.optString("name"))
        "skills.copy" -> SkillsUi.copy(a.str("dir"))
        "skills.delete" -> SkillsUi.delete(a.str("dir"))
        "skills.addFile" -> SkillsUi.addFile(a.str("dir"), a.optString("name"))
        "convo.debug" -> {
            val c = Convos.load(a.str("id")) ?: throw IllegalArgumentException(Y.t("Conversation not found", "Sohbet bulunamadı"))
            DebugDump.forUi(c, a.optBoolean("full"), a.optBoolean("short"), a.optBoolean("save"))
        }
        "llmx.get" -> llmJson()
        "llmx.set" -> { setLlm(a.str("key"), a.opt("value")); llmJson() }
        "memx.get" -> memJson()
        "memx.set" -> { setMem(a.str("key"), a.opt("value")); memJson() }
        "ctx.restore" -> JSONObject().put("note", restoreContext(a.str("convoId")))
        "stats.get" -> Trace.statsJson()
        "stats.reset" -> { Trace.resetStats(); Trace.statsJson() }
        "shortcuts.get" -> shortcuts()
        "shortcuts.set" -> { val l = a.optJSONArray("items"); if (l == null) Prefs.remove("cfg.shortcuts") else Prefs.put("cfg.shortcuts", l.toString()); shortcuts() }
        "files.exists" -> filesExist(a.optJSONArray("paths") ?: JSONArray())
        "files.upload" -> upload(a.str("name"), a.optString("b64"), a.str("dir").ifEmpty { "${VPath.WS}/uploads" })
        "memory.raw" -> JSONObject().put("text", MemoryX.entries(if (a.str("target") == "user") "user" else "memory").joinToString(MemoryX.DELIM)).put("delim", MemoryX.DELIM)
            .put("limit", if (a.str("target") == "user") MemoryXCfg.userChars else 0)
        "memory.rawSave" -> {
            val t = if (a.str("target") == "user") "user" else "memory"
            val l = a.optString("text").split(Regex("\\n?§\\n?")).map { it.trim() }.filter { it.isNotEmpty() }
            MemoryX.save(t, l); JSONObject().put("entries", l.size)
        }
        "models.info" -> modelsInfo()
        "browser.view" -> browserView()
        "browser.live" -> Browser.live(a.optString("mode"))
        "browser.go" -> Browser.userGo(a.str("url"))
        "browser.back" -> Browser.userBack()
        "browser.reload" -> Browser.userReload()
        "browser.takeover" -> Browser.userTakeOver()
        "gates.clearLog" -> { S1.clearLog(); true }
        "files.view" -> viewFile(a.str("path"))
        "files.runHtml" -> {
            val vp = VPath.normTool(a.str("path"))
            val w = a.optInt("width", 390).coerceIn(200, 4000)
            JSONObject().put("path", vp).put("report", HtmlRun.report(HtmlRun.run(vp, emptyList(), w, 800, 1200, 15000, null)))
        }
        "files.stats" -> diskStats()
        else -> throw IllegalArgumentException("unknown method $m")
    }

    // ---------------------------------------------------------------- permissions
    fun permJson(): JSONObject = JSONObject().put("mode", Permissions.mode).put("modes", JSONArray(Permissions.MODES.map { JSONObject().put("id", it).put("label", Permissions.label(it)).put("help", Permissions.help(it)) }))

    private fun markReverted(c: Convo, id: String) {
        for (i in 0 until c.messages.length()) { val ch = c.messages.optJSONObject(i)?.optJSONObject("changes") ?: continue; if (ch.optString("id") == id) ch.put("reverted", true) }
        Convos.save(c, touch = false)
    }

    // ---------------------------------------------------------------- settings
    /** Keys Bridge.setCfg does not know (Settings → Model / Assistant). Returns false for an unknown key. */
    fun setCfg(k: String, v: Any?): Boolean {
        val s = v?.toString() ?: ""
        when (k) {
            "temperature", "extraHeaders", "stateChars", "reviewFloor", "permissions", "exportKeys", "uiLang", "theme" -> setLlm(k, v)
            else -> return false
        }
        return true
    }

    fun llmJson(): JSONObject = JSONObject().put("temperature", Cfg.temperature).put("extraHeaders", LlmX.extraHeadersJson).put("maxSteps", Cfg.maxSteps)
        .put("sendImages", Cfg.sendImages).put("stateChars", try { S1Cfg.stateChars } catch (_: Throwable) { 2000 }).put("permissions", Permissions.mode)
        .put("exportKeys", StateIO.includeKeys).put("uiLang", Prefs.get("cfg.uiLang", "")).put("theme", Prefs.get("cfg.theme", "system"))

    private fun setLlm(k: String, v: Any?) {
        val s = v?.toString()?.trim() ?: ""
        when (k) {
            "temperature" -> Cfg.temperature = (s.replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException(Y.t("Not a number", "Sayı değil"))).coerceIn(0.0, 2.0)
            "extraHeaders" -> LlmX.extraHeadersJson = s
            "stateChars" -> S1Cfg.stateChars = (s.toIntOrNull() ?: 2000).coerceIn(200, 20000)
            "reviewFloor" -> Prefs.put("mem.reviewFloor", (s.replace(',', '.').toDoubleOrNull() ?: 0.6).coerceIn(0.0, 1.0).toString())
            "permissions" -> Permissions.mode = s
            "exportKeys" -> StateIO.includeKeys = v == true || s == "true" || s == "1"
            "uiLang" -> Prefs.put("cfg.uiLang", if (s in setOf("en", "tr")) s else "")
            "theme" -> Prefs.put("cfg.theme", if (s in setOf("light", "dark")) s else "system")
            else -> throw IllegalArgumentException("unknown setting $k")
        }
    }

    val reviewFloor: Double get() = Prefs.get("mem.reviewFloor", "0.6").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.6

    fun memJson(): JSONObject = JSONObject().put("activeMax", MemoryXCfg.activeMax).put("draftMax", MemoryXCfg.draftMax).put("draftDays", MemoryXCfg.draftDays)
        .put("promptNotes", MemoryXCfg.promptNotes).put("entryChars", MemoryXCfg.entryChars).put("userChars", MemoryXCfg.userChars).put("reason", MemoryXCfg.reason).put("reviewFloor", reviewFloor)

    private fun setMem(k: String, v: Any?) {
        val s = v?.toString()?.trim() ?: ""
        val n = s.toIntOrNull()
        when (k) {
            "activeMax" -> MemoryXCfg.activeMax = n ?: return
            "draftMax" -> MemoryXCfg.draftMax = n ?: return
            "draftDays" -> MemoryXCfg.draftDays = n ?: return
            "promptNotes" -> MemoryXCfg.promptNotes = n ?: return
            "entryChars" -> MemoryXCfg.entryChars = n ?: return
            "userChars" -> MemoryXCfg.userChars = n ?: return
            "reason" -> MemoryXCfg.reason = v == true || s == "true" || s == "1"
            "reviewFloor" -> setLlm("reviewFloor", v)
            else -> throw IllegalArgumentException("unknown memory setting $k")
        }
    }

    // ---------------------------------------------------------------- undo /clear (Agent.restoreContext, all.js 4076)
    /** Called by /clear before it cuts the context: remembers the summary so it can be put back. */
    fun rememberClear(c: Convo) {
        ConvoState.edit(c.id) { it.put("cleared", JSONObject().put("summary", c.summary).put("summaryUpTo", c.summaryUpTo).put("at", c.messages.length())) }
    }

    fun restoreContext(convoId: String): String {
        if (Bridge.runs.containsKey(convoId)) return Y.t("Stop the running task first", "Önce çalışan işi durdurun")
        val c = Convos.load(convoId) ?: return Y.t("Conversation not found", "Sohbet bulunamadı")
        val prev = ConvoState.get(c.id).optJSONObject("cleared")
        // the divider must be the last message (nothing was said since the /clear)
        val last = c.messages.optJSONObject(c.messages.length() - 1)
        if (last == null || !last.optBoolean("divider")) return Y.t("Nothing to undo — the conversation went on after the context was cleared", "Geri alınacak bir şey yok — bağlam temizlendikten sonra konuşma devam etti")
        c.messages.remove(c.messages.length() - 1)
        c.summary = prev?.optString("summary") ?: ""
        c.summaryUpTo = (prev?.optInt("summaryUpTo", 0) ?: 0).coerceIn(0, c.messages.length())
        Convos.save(c)
        ConvoState.edit(c.id) { it.remove("cleared") }
        Diag.i("ctx", "context restored in ${c.id}")
        return Y.t("Context restored — yaver sees the earlier messages again", "Bağlam geri yüklendi — yaver önceki mesajları yeniden görüyor")
    }

    // ---------------------------------------------------------------- shortcuts on the hello screen (the original's Shortcuts, all.js 360-404)
    fun shortcuts(): JSONObject {
        val raw = Prefs.get("cfg.shortcuts", "")
        val l = if (raw.isNotEmpty()) try { JSONArray(raw) } catch (_: Exception) { null } else null
        return JSONObject().put("items", l ?: JSONObject.NULL).put("custom", l != null)
    }

    // ---------------------------------------------------------------- answers: which paths exist (clickable file names)
    fun filesExist(paths: JSONArray): JSONObject {
        val out = JSONObject()
        for (i in 0 until minOf(paths.length(), 80)) {
            val p = paths.optString(i).trim(); if (p.isEmpty()) continue
            val cands = listOf(VPath.normTool(p), VPath.norm("${VPath.WS}/uploads/${p.substringAfterLast('/')}"), VPath.norm("${VPath.WS}/outputs/${p.substringAfterLast('/')}"))
            val hit = cands.firstOrNull { try { VPath.isFile(it) } catch (_: Exception) { false } }
            if (hit != null) out.put(p, hit)
        }
        return out
    }

    /** A pasted picture (or any file) from the composer: saved into /workspace/uploads. */
    fun upload(name: String, b64: String, dir: String): JSONObject {
        val bytes = android.util.Base64.decode(b64.substringAfter("base64,"), android.util.Base64.DEFAULT)
        val d = VPath.fileW(VPath.normTool(dir)).apply { mkdirs() }
        val f = Workspace.unique(d, Workspace.safeName(name.ifBlank { "pasted-" + System.currentTimeMillis() + ".png" }))
        f.writeBytes(bytes)
        val vp = VPath.of(f)
        try { VPath.commit(vp) } catch (_: Throwable) {}
        return JSONObject().put("path", vp).put("name", f.name).put("image", Workspace.isImage(vp)).put("size", f.length())
    }

    // ---------------------------------------------------------------- model list with context size and price ("free")
    fun modelsInfo(): JSONArray {
        val r = Http.request(Cfg.llmBase + "/models", headers = Llm.headers() - "Content-Type", useCookies = false, timeoutMs = 30000)
        if (!r.ok) throw LlmException("HTTP ${r.code}: ${r.text().take(200)}", r.code)
        val j = JSONObject(r.text())
        val arr = j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val id = o?.optString("id")?.ifEmpty { o.optString("name") } ?: arr.optString(i)
            if (id.isNullOrEmpty()) continue
            out.add(modelInfo(id, o))
        }
        // the context sizes also feed the compactor (the original's CFG.llm.modelCtx), and known ones fill the gaps
        try {
            val known = try { JSONObject(Prefs.get("ctx.known", "{}")) } catch (_: Exception) { JSONObject() }
            var n = 0
            for (m in out) { val c = m.optInt("ctx"); if (c > 0) { if (known.optInt(m.optString("id")) != c) { known.put(m.optString("id"), c); n++ } } else if (known.optInt(m.optString("id")) > 0) m.put("ctx", known.optInt(m.optString("id"))) }
            if (n > 0) Prefs.put("ctx.known", known.toString())
        } catch (_: Throwable) {}
        return JSONArray(out.sortedBy { it.optString("id").lowercase() })
    }

    /** One /models entry: context size (OpenRouter context_length, top_provider, other gateways' names) and price-based "free". */
    fun modelInfo(id: String, o: JSONObject?): JSONObject {
        val ctx = if (o == null) 0 else listOf(o.optInt("context_length", 0), o.optJSONObject("top_provider")?.optInt("context_length", 0) ?: 0,
            o.optInt("context_window", 0), o.optInt("max_context_length", 0), o.optInt("max_model_len", 0), o.optInt("max_input_tokens", 0)).firstOrNull { it > 0 } ?: 0
        val pr = o?.optJSONObject("pricing")
        fun zero(k: String) = pr != null && pr.has(k) && pr.optString(k).trim().toDoubleOrNull() == 0.0
        val free = id.endsWith(":free", true) || (zero("prompt") && zero("completion"))
        return JSONObject().put("id", id).put("ctx", ctx).put("free", free).put("name", o?.optString("name")?.takeIf { it != id } ?: "")
    }

    // ---------------------------------------------------------------- the agent's browser, for the user to watch (P3-5)
    fun browserView(): JSONObject = JSONObject().put("url", try { Browser.pageUrl() } catch (_: Throwable) { "" })

    // ---------------------------------------------------------------- Files page: open a page, disk meter, document index
    fun viewFile(path: String): JSONObject {
        val vp = VPath.normTool(path)
        if (!VPath.isFile(vp)) throw IllegalArgumentException(Y.t("File not found: ", "Dosya bulunamadı: ") + vp)
        val url = LocalView.urlOf(vp)
        Y.ui { val ctx = Bridge.host ?: Y.app; ctx.startActivity(android.content.Intent(ctx, BrowserActivity::class.java).putExtra("url", url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return JSONObject().put("url", url)
    }

    fun diskStats(): JSONObject {
        fun size(f: File): Pair<Long, Int> { var b = 0L; var n = 0; f.walkTopDown().forEach { if (it.isFile) { b += it.length(); n++ } }; return b to n }
        val ws = try { size(VPath.file(VPath.WS)) } catch (_: Throwable) { 0L to 0 }
        val home = try { size(Y.app.filesDir) } catch (_: Throwable) { 0L to 0 }
        val (docs, sections) = try { Rag.stats() } catch (_: Throwable) { 0 to 0 }
        return JSONObject().put("workspaceBytes", ws.first).put("workspaceFiles", ws.second).put("appBytes", home.first)
            .put("free", try { Y.app.filesDir.usableSpace } catch (_: Throwable) { 0L }).put("total", try { Y.app.filesDir.totalSpace } catch (_: Throwable) { 0L })
            .put("ragDocs", docs).put("ragSections", sections)
    }

    // ---------------------------------------------------------------- slash commands
    /** /permissions, /settings, /debug, /help, /mail, /calendar, /tasks, /files, /memory. Null = not one of these. */
    fun command(convoId: String, cmd: String, rest: String, out: JSONObject): JSONObject? {
        when (cmd) {
            "permissions", "perm" -> out.put("note", Permissions.command(rest)).put("perm", Permissions.mode)
            "settings" -> out.put("open", "settings")
            "mail" -> out.put("open", "mail")
            "calendar", "cal" -> out.put("open", "cal")
            "tasks" -> out.put("open", "tasks")
            "files" -> out.put("open", "files")
            "browser" -> out.put("open", "browser")
            "memory", "skills" -> out.put("open", if (cmd == "skills") "skills" else "memory")
            "debug" -> { if (convoId.isEmpty()) out.put("note", Y.t("Start a conversation first", "Önce bir sohbet başlatın")) else out.put("open", "debug") }
            "help" -> out.put("help", true)
            "undo" -> out.put("note", restoreContext(convoId))
            else -> return null
        }
        return out
    }

    // ---------------------------------------------------------------- tools: vision_analyze alias, skill usage
    fun registerTools() {
        Tools.get("look_at_image")?.let { t ->
            // the original's name: skills, lessons and subagent specs written by the HTML app call it
            Tools.add(Tool("vision_analyze", "Analyze an image file with the vision model (same as look_at_image).",
                P.props("path" to P.s("Image path"), "question" to P.s("What to look for")), listOf("path"), t.subagentOk) { a, ctx -> t.run(a, ctx) })
        }
        Tools.get("skill_view")?.let { t ->
            Tools.add(Tool(t.name, t.description, t.properties, t.required, t.subagentOk) { a, ctx -> val r = t.run(a, ctx); SkillsUi.touch(a.str("name")); r })
        }
    }
}
