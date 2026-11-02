package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject

/** What the screen (or the background runner) hears while yaver works. All calls come from the worker thread. */
interface AgentListener {
    fun onDelta(text: String) {}
    fun onThinking(text: String) {}
    /** Live status while the model works: hidden reasoning characters, the tool call being written and its size. */
    fun onThinkInfo(reasoning: Int, tool: String, args: Int, calls: Int) {}
    fun onStep() {}
    fun onToolStart(name: String, args: JSONObject) {}
    fun onToolEnd(name: String, result: String, ok: Boolean) {}
    fun onProgress(text: String) {}
    /** A chip / note in the running turn ("🗜 compacted…", "loop monitor", "memory rule"…). */
    fun onNote(text: String) {}
    /** Ask the user. Blocks until they answer. Background runs answer false. */
    fun approve(title: String, details: String, danger: Boolean): Boolean = false
}

class ToolCtx(val convo: Convo, val cancel: Cancel, val background: Boolean, val listener: AgentListener?, val depth: Int) {
    /** The request of the running turn (for the smart-approval gate). */
    @Volatile var userText: String = ""
    /** Why the last approval was refused by a gate (used by [denied]). */
    @Volatile var lastDenial: String? = null

    fun progress(s: String) { listener?.onProgress(s) }
    fun note(s: String) { listener?.onNote(s) }

    /**
     * Confirmation for actions with side effects. [always] actions (sending mail, invitations…) are always asked; in
     * background runs they are refused. Other risky actions: with "ask before risky actions" on, the user decides;
     * with it off, the smart-approval gate decides (hardline → System-1 → LLM safety check → the user when unsure).
     */
    fun approve(title: String, details: List<Pair<String, String?>>, always: Boolean = false, danger: Boolean = false): Boolean {
        lastDenial = null
        val text = details.filter { !it.second.isNullOrBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }
        if (!always && !Cfg.askRisky) {
            val tool = Agent.currentTool.get() ?: "action"
            val args = Agent.currentArgs.get() ?: JSONObject()
            val g = try { Gates.approval(tool, args, title, userText, cancel) } catch (e: CancelledException) { throw e } catch (e: Throwable) { ApprovalOutcome("approve") }
            g.note?.let { note(it); Diag.i("gates", it) }
            if (g.chips.isNotEmpty()) note(g.chips.joinToString(" · ") { it.second })
            if (g.ok) return true
            if (!g.askUser) { lastDenial = g.why; Diag.i("gates", "approval denied: ${g.why}"); return false }
            if (background || listener == null) { lastDenial = "needs the user's confirmation ($title)"; return false }
            val yes = listener.approve(title, text, danger)
            if (!yes) lastDenial = "user denied: $title"
            return yes
        }
        if (background || listener == null) return false
        return listener.approve(title, text, danger)
    }

    fun denied(what: String): String {
        lastDenial?.let { return "Not executed: $it" }
        return if (background)
            Y.t("Not done: \"$what\" needs the user's confirmation, and this task ran in the background. Tell the user to open yaver and ask again.",
                "Yapılmadı: \"$what\" kullanıcı onayı gerektiriyor ve bu görev arka planda çalıştı. Kullanıcıya yaver'i açıp yeniden istemesini söyle.")
        else Y.t("Not done: the user did not confirm \"$what\".", "Yapılmadı: kullanıcı \"$what\" işlemini onaylamadı.")
    }
}

/**
 * The agent loop (port of the original's Agent.send + agentLoop): pre-turn gates and context injection, the model ↔ tools
 * loop with compaction, argument checks, plan mode, loop monitor, empty-reply / wrong-language / "I don't know" handling,
 * verify-on-stop and the budget grace call; after the turn: learning, memory delta, follow-ups and background review.
 */
object Agent {
    /** Tool being run on this thread (for the smart-approval gate inside ToolCtx.approve). */
    val currentTool = ThreadLocal<String?>()
    val currentArgs = ThreadLocal<JSONObject?>()

    fun defaultPersona() = Prompt.DEFAULT_SOUL

    // ---------------------------------------------------------------- toolsets (System-1 toolset gate)
    val TOOLSET_DESC: LinkedHashMap<String, String> = linkedMapOf(
        "files" to "Read, write, patch and search files in the workspace",
        "terminal" to "Virtual shell over the workspace (ls, cat, grep, find, mv, curl…)",
        "code" to "Run JavaScript or Python for computation, data processing and charts",
        "web" to "Web search, page text extraction, HTTP fetch for APIs, and multi-step deep research with a cited report",
        "browser" to "Interactive browser (navigate, click, type, find elements) and an autonomous browser agent for multi-step web tasks",
        "vision" to "Look at an image file with the vision model",
        "memory" to "Persistent memory and past-session search",
        "skills" to "Skill library: list, view, create and edit procedures",
        "planning" to "Todo list, clarifying questions, subagent delegation",
        "mail" to "The user's company e-mail, calendar, people and task board",
        "phone" to "The phone: its calendars, alarms, reminders, notifications, apps, clipboard, speech and scheduled tasks"
    )
    val CORE_TOOLSETS = listOf("files", "memory", "skills", "planning")

    fun toolsetMembers(): Map<String, Set<String>> {
        val m = LinkedHashMap<String, MutableSet<String>>()
        for ((k, v) in Subagents.TOOLSETS) m.getOrPut(k) { LinkedHashSet() }.addAll(v)
        m.getOrPut("files") { LinkedHashSet() }.addAll(listOf("patch", "search_files", "unpack_archive", "pack_archive", "folder", "search_all"))
        m.getOrPut("terminal") { LinkedHashSet() }.add("terminal")
        m.getOrPut("code") { LinkedHashSet() }.addAll(listOf("execute_code", "run_html"))
        m.getOrPut("web") { LinkedHashSet() }.addAll(listOf("web_extract", "download_file", "page_images"))
        m.getOrPut("browser") { LinkedHashSet() }.addAll(listOf("browser_navigate", "browser_snapshot", "browser_click", "browser_type", "browser_back", "browser_find", "browser_download"))
        m.getOrPut("memory") { LinkedHashSet() }.add("session_search")
        m.getOrPut("mail") { LinkedHashSet() }.add("task_board")
        m.getOrPut("planning") { LinkedHashSet() }.addAll(listOf("todo", "clarify", "goal", "present_plan", "present_files", "delegate_task", "subagents", "heartbeat", "harness"))
        return m
    }

    private fun sessionToolsets(c: Convo): MutableList<String> {
        val a = ConvoState.get(c.id).optJSONArray("toolsets") ?: return CORE_TOOLSETS.toMutableList()
        return (0 until a.length()).map { a.optString(it) }.toMutableList()
    }

    private fun saveToolsets(c: Convo, l: List<String>) { ConvoState.edit(c.id) { it.put("toolsets", JSONArray(l.distinct())) } }

    // ---------------------------------------------------------------- messages
    fun userMessage(text: String, images: List<String>, files: List<String>): JSONObject {
        var content = text
        if (files.isNotEmpty()) content += "\n\n[Attached files saved in the workspace: ${files.joinToString(", ")} — read them with read_document]"
        if (images.isNotEmpty()) content += "\n\n[Attached pictures (also saved in the workspace): ${images.joinToString(", ")}]"
        val m = JSONObject().put("role", "user").put("content", content).put("display", text).put("ts", System.currentTimeMillis())
        if (images.isNotEmpty()) m.put("images", JSONArray(images))
        if (files.isNotEmpty()) m.put("files", JSONArray(files))
        return m
    }

    /** resolveRefs: "@file" mentions in the user's text become a [Referenced files] block (small text files inline). */
    private fun resolveRefs(text: String): String {
        val refs = LinkedHashSet<String>()
        for (m in Regex("(^|\\s)@([^\\s@]+)").findAll(text)) {
            val raw = m.groupValues[2].replace(Regex("[.,;:!?)]+$"), "")
            val p = listOf(VPath.normTool(raw), VPath.norm("${VPath.WS}/uploads/$raw")).firstOrNull { VPath.exists(it) } ?: continue
            refs.add(p)
        }
        if (refs.isEmpty()) return ""
        return "\n\n[Referenced files]\n" + refs.joinToString("\n") { p ->
            if (VPath.isDir(p)) "- $p/ (folder: ${VPath.walk(p).size} files)"
            else {
                val f = VPath.file(p)
                if (VPath.isText(p) && f.length() <= 8000) "--- $p ---\n${VPath.read(p)}\n--- end of $p ---"
                else "- $p (${Workspace.fmtBytes(f.length())}; read it with ${if (Regex("\\.(pdf|docx|xlsx|pptx|csv)$", RegexOption.IGNORE_CASE).containsMatchIn(p)) "read_document" else "read_file"})"
            }
        }
    }

    /** The request messages: system prompt, compaction head, messages from summaryUpTo (pictures on the last two user turns). */
    private fun requestMessages(c: Convo, extraSystem: String?, isSub: Boolean): JSONArray {
        val out = JSONArray()
        var sys = Prompt.forRequest(c)
        if (!extraSystem.isNullOrBlank() && !isSub) sys += "\n\n" + extraSystem
        out.put(JSONObject().put("role", "system").put("content", sys))
        val head = Compactor.head(c)
        for (i in 0 until head.length()) out.put(head.get(i))
        val start = c.summaryUpTo.coerceIn(0, c.messages.length())
        val userIdx = (start until c.messages.length()).filter { c.messages.getJSONObject(it).let { m -> m.optString("role") == "user" && m.optJSONArray("images") != null } }
        val picOk = userIdx.takeLast(2).toSet()
        for (i in start until c.messages.length()) {
            val m = c.messages.getJSONObject(i)
            if (m.optBoolean("local")) continue
            val imgs = m.optJSONArray("images")
            if (m.optString("role") == "user" && imgs != null && imgs.length() > 0 && Cfg.sendImages && i in picOk) {
                val parts = JSONArray().put(JSONObject().put("type", "text").put("text", m.optString("content")))
                for (k in 0 until imgs.length()) {
                    val url = Workspace.dataUrlForImage(imgs.getString(k)) ?: continue
                    parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                }
                out.put(JSONObject().put("role", "user").put("content", parts))
            } else out.put(m)
        }
        val msgs = LoopUtil.fixAlternation(out)
        if (!isSub && msgs.length() > 0) {
            val last = msgs.getJSONObject(msgs.length() - 1)
            if (last.optString("role") == "tool") Lang.toolReminder(c)?.let { last.put("content", last.optString("content") + it) }
        }
        return msgs
    }

    private fun names(tools: JSONArray): List<String> = (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.optJSONObject("function")?.optString("name") }

    private fun noCode(s: String) = s.replace(Regex("```[\\s\\S]*?```"), " ")

    private fun harnessMsg(content: String, extra: JSONObject? = null): JSONObject {
        val m = JSONObject().put("role", "user").put("content", content).put("harness", true).put("ts", System.currentTimeMillis())
        if (extra != null) for (k in extra.keys()) m.put(k, extra.get(k))
        return m
    }

    // ---------------------------------------------------------------- one turn
    /**
     * Runs one turn: the user's message, then model ↔ tools until the model answers. Returns the final answer.
     * [internal]: an automatic turn (goal round, heartbeat, subagent report, plan approval…): no language switch from its
     * text, no learning from it. Subagents ([depth] > 0) get [systemExtra] (their role) as the system prompt with the
     * soul and the environment, only [allowedTools], and at most [maxSteps] model calls.
     */
    fun run(
        c: Convo, text: String, images: List<String> = emptyList(), files: List<String> = emptyList(),
        listener: AgentListener? = null, cancel: Cancel = Cancel(), background: Boolean = false, depth: Int = 0, label: String? = null,
        internal: String? = null, systemExtra: String? = null, allowedTools: Set<String>? = null, maxSteps: Int = 0, display: String? = null
    ): String {
        val isSub = depth > 0
        val isInternal = internal != null
        val note: (String) -> Unit = { t -> listener?.onNote(t) }
        val progress: (String) -> Unit = { t -> listener?.onProgress(t) }
        if (!isSub) { Learn.touch(); try { FollowUps.clear(c.id) } catch (_: Throwable) {} }
        val turnStart = c.messages.length()
        val learnSnap: Map<String, Pair<String, String>>? = if (!isSub && !background) try { snapAll() } catch (_: Throwable) { null } else null

        // ---- the frozen system prompt
        if (isSub && !systemExtra.isNullOrBlank() && c.system.isBlank()) {
            c.system = Prompt.soul() + "\n\n" + systemExtra + "\n\n" + Prompt.envGuidance()
        } else try { Prompt.base(c, background) } catch (e: Throwable) { Diag.e("agent", "system prompt", e) }

        // ---- the user message with injected context
        val userText = if (internal == "goal") Planning.goal(c.id)?.optString("objective")?.ifEmpty { null } ?: text else text
        val st = LoopState(userText)
        var noTools = false
        var model: String? = null
        var toolsets: MutableList<String>? = null        // null = every tool
        val um: JSONObject
        if (isSub) {
            um = userMessage(text, images, files)
        } else {
            val long = if (!isInternal) LongInput.maybe(text) else null
            val route = try {
                val useTs = !S1Cfg.off && S1.enabled("toolsets")
                Gates.router(c, long?.content ?: text,
                    toolsets = if (useTs) TOOLSET_DESC else emptyMap(), active = if (useTs) sessionToolsets(c) else emptyList(), core = CORE_TOOLSETS,
                    skills = try { Skills.list().map { it.name to it.description } } catch (_: Throwable) { emptyList() },
                    sessionCount = try { Convos.list().size } catch (_: Throwable) { 0 },
                    docs = try { if (S1.enabled("rag")) Rag.docs() else emptyList() } catch (_: Throwable) { emptyList() }, cancel = cancel)
            } catch (e: CancelledException) { throw e } catch (e: Throwable) { Diag.e("agent", "router", e); Route() }
            if (route.chips.isNotEmpty()) { note("S1 · " + route.chips.joinToString(" · ") { it.second }); Diag.i("s1", "router: " + route.chips.joinToString(" · ") { it.second }) }
            if (!S1Cfg.off && S1.enabled("toolsets")) {
                val ts = sessionToolsets(c); for (t in route.addToolsets) if (t !in ts) ts.add(t); saveToolsets(c, ts); toolsets = ts
            }
            var content = long?.content ?: text
            if (long != null) note("S1 · long input → variable · ${long.path}")
            if (!isInternal) content += try { resolveRefs(text) } catch (e: Throwable) { "" }
            run {
                val rev = try { Harness.rev() } catch (_: Throwable) { 0 }
                val seen = ConvoState.get(c.id).optInt("harnessRev", 0)
                if (seen != rev && c.messages.length() > 0) {
                    val d = try { Harness.digest(c.id) } catch (_: Throwable) { "" }
                    if (d.isNotEmpty()) content += "\n\n[The continual harness was updated since this conversation started — current state:]\n$d"
                }
                if (seen != rev) ConvoState.edit(c.id) { it.put("harnessRev", rev) }
            }
            content = Planning.userPrefix(c, content, isInternal)
            route.skill?.let { sk -> try { content += "\n\n[Skill \"$sk\" auto-loaded by System-1 — follow it if it fits]\n" + Skills.get(sk).file.readText() } catch (_: Throwable) {} }
            if (route.docs) Rag.attach(text, cancel)?.let { (add, chip) -> content += add; note("S1 · $chip") }
            if (!isInternal) content += try { Learn.preWrite(c, text) } catch (_: Throwable) { "" }
            var memInfo: JSONObject? = null
            if (!isInternal) {
                try { Rules.list() } catch (_: Throwable) {}
                val at = MemoryX.attach(text, cancel)
                content += at.content; memInfo = at.info
                at.chip?.let { note(it); Diag.i("memory", it) }
            }
            if (route.recall) content += try { MemoryX.recallBlock(text, c.id) } catch (_: Throwable) { "" }
            content += Lang.onUserMessage(c, text, isInternal)
            content += "\n\n" + Prompt.nowNote()
            um = userMessage(content, images, files).put("display", display ?: text)
            if (route.chips.isNotEmpty()) um.put("s1", route.chipsJson())
            if (memInfo != null) um.put("mem", memInfo)
            if (internal != null) um.put("internal", internal)
            noTools = route.route == "reply_direct"
            if (route.tier == "fast" && Cfg.fastModel.isNotEmpty()) model = Cfg.fastModel
            if (route.route == "clarify") toolsets = mutableListOf("planning")
        }
        if (label != null) um.put("label", label)
        c.messages.put(um)
        if (!isInternal && c.messages.length() <= 2 && (c.title == Y.t("New chat", "Yeni sohbet") || c.title.isBlank())) {
            c.title = (label ?: text).replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { c.title }
        }
        Convos.save(c)

        val ctx = ToolCtx(c, cancel, background, listener, depth).also { it.userText = userText }
        val turn = GateTurn(userText, Lang.name(Lang.fixed() ?: c.lang))
        var final = ""
        try {
            final = loop(c, ctx, st, turn, turnStart, isSub, noTools, model, toolsets, allowedTools, if (maxSteps > 0) maxSteps else Cfg.maxSteps, systemExtra, note, progress)
        } catch (e: CancelledException) {
            closeOpenCalls(c, Y.t("(not run: stopped by the user)", "(çalıştırılmadı: kullanıcı durdurdu)"))
            c.messages.put(JSONObject().put("role", "assistant").put("content", Y.t("⏹ Stopped.", "⏹ Durduruldu.")).put("local", true))
            Convos.save(c)
            if (!isSub) try { Planning.onStopped(c) } catch (_: Throwable) {}
            throw e
        } catch (e: Throwable) {
            Diag.e("agent", "run failed", e)
            closeOpenCalls(c, "(not run: error)")
            val msg = "⚠️ " + (e.message ?: e.toString())
            c.messages.put(JSONObject().put("role", "assistant").put("content", msg).put("local", true).put("error", true))
            Convos.save(c)
            if (!isSub && !isInternal && !background) try { Harness.checkpoint(c) } catch (_: Throwable) {}
            throw e
        }
        Convos.save(c)
        if (!isSub) afterTurn(c, turnStart, text, userText, isInternal, internal, background, cancel, learnSnap)
        return final
    }

    /** Lessons (Learn.snap "h:" keys) + memory notes (MemoryX), for the "Memory: +1 learned" chip. */
    private fun snapAll(): Map<String, Pair<String, String>> = LinkedHashMap(Learn.snap().filterKeys { it.startsWith("h:") }).also { it.putAll(MemoryX.snap()) }

    private fun afterTurn(c: Convo, turnStart: Int, text: String, userText: String, isInternal: Boolean, internal: String?, background: Boolean, cancel: Cancel, learnSnap: Map<String, Pair<String, String>>?) {
        try { MemoryX.afterTurn(c.messages, turnStart) { Bridge.runs.isNotEmpty() } } catch (e: Throwable) { Diag.e("memory", "afterTurn", e) }
        if (!isInternal && !background) {
            val idx = try { Harness.afterTurn(c, turnStart, text, cancel) } catch (e: Throwable) { Diag.e("learn", "afterTurn", e); -1 }
            if (idx >= 0) Diag.i("learn", "answer #$idx tagged with ${c.messages.optJSONObject(idx)?.optJSONArray("lr")?.length() ?: 0} lessons")
        }
        if (learnSnap != null) try {
            val now = snapAll()
            MemoryX.delta(learnSnap, now)?.let { d ->
                Diag.i("memory", d.optString("label"))
                Bridge.emit("memory", JSONObject(d.toString()).put("convoId", c.id))
            }
        } catch (e: Throwable) { Diag.e("memory", "delta", e) }
        if (!background && (!isInternal || internal == "followup")) {
            val copy = try { Harness.snapshot(c) } catch (_: Throwable) { c }
            Y.bg { try { val l = FollowUps.suggest(copy, turnStart, userText); if (l.isNotEmpty()) Diag.i("followups", "${l.size} suggestions for ${c.id}") } catch (e: Throwable) { Diag.e("followups", "suggest", e) } }
        }
        if (!background) {
            val copy = try { Harness.snapshot(c) } catch (_: Throwable) { c }
            Y.bg { try { backgroundReview(copy, turnStart) } catch (e: Throwable) { Diag.e("review", "background review", e) } }
        }
    }

    /** The model ↔ tools loop (agentLoop). */
    private fun loop(
        c: Convo, ctx: ToolCtx, st: LoopState, turn: GateTurn, turnStart: Int, isSub: Boolean, noTools0: Boolean, model: String?,
        toolsets0: MutableList<String>?, allowed: Set<String>?, maxIter: Int, systemExtra: String?, note: (String) -> Unit, progress: (String) -> Unit
    ): String {
        val cancel = ctx.cancel
        val listener = ctx.listener
        var noTools = noTools0
        var toolsets = toolsets0
        val members = toolsetMembers()
        var empty = 0
        var langFixAt = -1
        var langFixed = false
        var memNudged = false
        var idkNudged = false
        var restrict: Set<String>? = null      // tools actually sent after a slim retry
        fun turnMsgs() = Gates.turnMsgs(c, turnStart)

        fun toolsNow(): JSONArray {
            if (noTools) return JSONArray()
            val all = Tools.schemas(isSub)
            val listed = members.values.flatten().toSet()
            val ts = toolsets
            val out = JSONArray()
            for (i in 0 until all.length()) {
                val sc = all.getJSONObject(i); val n = sc.getJSONObject("function").getString("name")
                if (allowed != null && n !in allowed) continue
                if (restrict != null && n !in restrict!!) continue
                if (ts != null && n in listed && ts.none { members[it]?.contains(n) == true }) continue
                out.put(sc)
            }
            return out
        }

        for (iter in 0 until maxIter) {
            cancel.check()
            turn.iter = iter
            if (!isSub && iter > 0) deliverPending(c, st, turn)
            val tools = toolsNow()
            Compactor.auto(c, tools, model, cancel, isSub, { t -> note(t); Diag.i("ctx", t) }, progress, st.userText)
            Llm.info.set(if (listener == null) null else { a, b, cc, d -> listener.onThinkInfo(a, b, cc, d) })
            val (r, sent) = Compactor.chatWithRetry(c, tools, model, cancel, isSub, st, { t -> note(t); Diag.i("ctx", t) }, progress) { tl ->
                Llm.chat(requestMessages(c, systemExtra, isSub), if (empty >= 3 || tl.length() == 0) null else tl, model,
                    onDelta = { listener?.onDelta(it) }, onThink = { listener?.onThinking(it) }, cancel = cancel, stream = if (empty >= 2) false else Cfg.stream)
            }
            if (sent !== tools) restrict = names(sent).toSet()
            Planning.countTokens(r.tokensIn + r.tokensOut); Autonomous.addTokens(r.tokensIn, r.tokensOut)
            val am = JSONObject().put("role", "assistant").put("content", r.content).put("ts", System.currentTimeMillis())
            if (r.toolCalls.isNotEmpty()) {
                val tc = JSONArray()
                for (t in r.toolCalls) tc.put(JSONObject().put("id", t.id).put("type", "function").put("function", JSONObject().put("name", t.name).put("arguments", t.args)))
                am.put("tool_calls", tc)
                c.messages.put(am); Convos.save(c)
                listener?.onStep()
                runTools(c, ctx, st, r.toolCalls, names(sent), noTools, allowed, isSub, members, { ts -> toolsets?.let { l -> if (ts !in l) { l.add(ts); if (!isSub) saveToolsets(c, l) } } }, note)
                if (!isSub) {
                    val nudge = try { Gates.loop(c, turn, turnMsgs(), cancel) } catch (e: CancelledException) { throw e } catch (e: Throwable) { null }
                    if (nudge != null) {
                        val last = c.messages.getJSONObject(c.messages.length() - 1)
                        last.put("content", last.optString("content") + "\n\n" + nudge)
                        Convos.save(c)
                        note("S1 · loop monitor · " + Gates.loopChip(nudge)); Diag.i("gates", "loop nudge: " + Gates.loopChip(nudge))
                    }
                }
                continue
            }
            val fin = r.content
            if (fin.isBlank()) {
                // empty reply: deliver waiting messages first; else retry (2nd time without streaming, 3rd without tools), then stop
                if (!isSub && Turns.hasPending(c.id)) { empty = 0; continue }
                empty++
                if (empty > 3 || iter >= maxIter - 1) throw LlmException("The model returned an empty reply $empty times in a row (model ${model ?: Cfg.model}${if (!r.finish.isNullOrEmpty()) ", finish_reason ${r.finish}" else ""}${if (r.reasoning.isNotEmpty()) ", it produced ${r.reasoning.length} characters of hidden reasoning but no answer" else ""}). Try again, or choose another model in Settings.")
                val nudge = (if (empty >= 3) "[harness] Your last replies were empty. Answer the user now in plain text with what you already know; tools are switched off for this reply." else "[harness] Your last reply was empty. Continue the task: call the next tool you need, or answer the user.") + (if (!turn.lang.isNullOrEmpty()) " Reply in ${turn.lang}." else "")
                val last = c.messages.optJSONObject(c.messages.length() - 1)
                val rx = Regex("\\[harness\\] Your last repl(y was|ies were) empty[\\s\\S]*$")
                if (last != null && last.optString("role") == "user" && rx.containsMatchIn(last.optString("content"))) last.put("content", rx.replace(last.optString("content"), Regex.escapeReplacement(nudge)))
                else if (last != null && last.optString("role") == "user") last.put("content", last.optString("content") + "\n\n" + nudge)
                else c.messages.put(harnessMsg(nudge))
                Diag.i("agent", "empty reply #$empty")
                continue
            }
            empty = 0
            c.messages.put(am); Convos.save(c)
            listener?.onStep()
            if (!isSub && Turns.hasPending(c.id)) { noTools = false; continue }
            if (!isSub) {
                if (!langFixed) {
                    val got = Lang.wrongLanguage(c, fin)
                    val want = Lang.fixed() ?: c.lang
                    if (got != null && want != null) {
                        langFixed = true; langFixAt = c.messages.length() - 1
                        note(Lang.rewriteChip(got, want)); Diag.i("lang", "answer in $got, rewriting in $want")
                        c.messages.put(harnessMsg(Lang.rewriteRequest(got, want))); noTools = true
                        continue
                    }
                }
                if (langFixAt >= 0) { c.messages.remove(langFixAt); c.messages.remove(langFixAt); langFixAt = -1; noTools = false; Convos.save(c) }
            }
            val plain = noCode(fin)
            if (!isSub && !memNudged && Prompt.IDK_RX.containsMatchIn(plain)) {
                val looked = turnMsgs().any { m -> m.optString("role") == "assistant" && (m.optJSONArray("tool_calls")?.let { tc -> (0 until tc.length()).any { k -> tc.optJSONObject(k)?.optJSONObject("function")?.let { f -> f.optString("name") == "session_search" || (f.optString("name") == "memory" && f.optString("arguments").contains("search")) } == true } } == true) }
                val hasWeb = Tools.get("web_search") != null
                val n = if (looked) null else MemoryX.idkNudge(st.userText, hasWeb, turn.lang, cancel)
                if (n != null) {
                    memNudged = true
                    note(Y.t("memory rule · said it does not know, but memory has ${n.second.size} related entries — asked to answer from them", "hafıza kuralı · “bilmiyorum” dedi ama hafızada ${n.second.size} ilgili kayıt var — onlarla yanıtlaması isteniyor"))
                    Diag.i("memory", "idk nudge with ${n.second.size} entries")
                    c.messages.put(harnessMsg(n.first, JSONObject().put("mem", JSONObject().put("how", "memory check after 'I don't know'").put("rel", JSONArray(n.second.map { clip(it, 200) })))))
                    continue
                }
            }
            if (!isSub && !idkNudged && names(sent).contains("web_search") && Prompt.IDK_RX.containsMatchIn(plain) && !Prompt.MEM_SELF_RX.containsMatchIn(st.userText)) {
                val searched = turnMsgs().any { m -> m.optString("role") == "assistant" && (m.optJSONArray("tool_calls")?.let { tc -> (0 until tc.length()).any { k -> Regex("^(web_search|web_extract|fetch|deep_research|browser_|read_page|browser$)").containsMatchIn(tc.optJSONObject(k)?.optJSONObject("function")?.optString("name") ?: "") } } == true) }
                if (!searched) {
                    idkNudged = true
                    note(Y.t("research rule · said it does not know without searching — asked to search the web first", "araştırma kuralı · “bilmiyorum” dedi ama aramadı — önce web'de araştırması isteniyor"))
                    Diag.i("agent", "idk web nudge")
                    c.messages.put(harnessMsg("[harness] You said you do not know or are not sure, but you did not search. Do not guess and do not give up: search the web now (web_search, then open the best results with web_extract), then answer from what you found and cite the sources. Say you could not find it only if several searches really fail.${if (!turn.lang.isNullOrEmpty()) " Reply in ${turn.lang}." else ""}"))
                    continue
                }
            }
            val v = if (isSub) null else try { Gates.stop(c, turn, fin, turnMsgs(), cancel) } catch (e: CancelledException) { throw e } catch (e: Throwable) { null }
            if (v != null) {
                note("S1 · verify-on-stop · " + Gates.stopChip(v)); Diag.i("gates", "verify-on-stop nudge")
                c.messages.put(harnessMsg(v))
                continue
            }
            return fin
        }
        // budget exhausted: one final call without tools to wrap up
        c.messages.put(harnessMsg("[harness] Iteration budget exhausted. Summarize what you did, what is verified, and what is left. No more tool calls." + (if (!turn.lang.isNullOrEmpty()) " Reply in ${turn.lang}." else "")))
        Diag.i("agent", "iteration budget ($maxIter) exhausted")
        val r = Llm.chat(requestMessages(c, systemExtra, isSub), null, model, onDelta = { listener?.onDelta(it) }, cancel = cancel)
        c.messages.put(JSONObject().put("role", "assistant").put("content", r.content).put("ts", System.currentTimeMillis()))
        Convos.save(c)
        listener?.onStep()
        return r.content
    }

    /** Messages the user (or a heartbeat) sent while the turn runs join here (assistant → tool → user is valid). */
    private fun deliverPending(c: Convo, st: LoopState, turn: GateTurn) {
        var texts: List<String> = emptyList()
        val m = Turns.takeSteer(c.id) { t -> texts = t; Lang.onSteer(c, t) } ?: return
        c.messages.put(m)
        val add = "\n" + texts.joinToString("\n")
        st.userText += add; turn.userText += add
        turn.lang = Lang.name(Lang.fixed() ?: c.lang)
        Convos.save(c)
        Diag.i("agent", "steer delivered (${texts.size})")
    }

    private fun runTools(
        c: Convo, ctx: ToolCtx, st: LoopState, calls: List<ToolCall>, sentNames: List<String>, noTools: Boolean, allowed: Set<String>?, isSub: Boolean,
        members: Map<String, Set<String>>, enableToolset: (String) -> Unit, note: (String) -> Unit
    ) {
        val listener = ctx.listener
        val all = Tools.schemas(isSub)
        for (t in calls) {
            ctx.cancel.check()
            val parsed = LoopUtil.jsonTry(t.args.ifEmpty { "{}" })
            listener?.onToolStart(t.name, parsed as? JSONObject ?: JSONObject().put("_raw", clip(t.args, 300)))
            Diag.i("tool", "${t.name} " + clip(t.args, 300))
            val tool = Tools.get(t.name)
            var ok = true
            var result: String = if (tool == null) LoopUtil.unknownToolError(t.name, sentNames) else {
                val (args, err) = LoopUtil.checkCall(t.name, t.args, LoopUtil.paramsOf(all, t.name) ?: JSONObject(), st)
                when {
                    err != null -> err
                    noTools || (allowed != null && t.name !in allowed) || (isSub && !tool.subagentOk) -> "Error: tool ${t.name} is not enabled for this turn."
                    else -> {
                        if (t.name !in sentNames) members.entries.firstOrNull { t.name in it.value }?.key?.let { ts -> enableToolset(ts); note("+tools: $ts · enabled on demand") }
                        val gate = Planning.gate(c, t.name, args!!)
                        if (gate != null) gate else try {
                            currentTool.set(t.name); currentArgs.set(args)
                            tool.run(args, ctx)
                        } catch (e: CancelledException) {
                            throw e
                        } catch (e: Throwable) {
                            Diag.e("tool", "${t.name} failed", e)
                            "Error: " + (e.message ?: e.toString())
                        } finally { currentTool.set(null); currentArgs.set(null) }
                    }
                }
            }
            if (result.startsWith("Error") || result.startsWith("Not executed")) ok = false
            result = LoopUtil.finishResult(result, t.name, t.args, st)
            c.messages.put(JSONObject().put("role", "tool").put("tool_call_id", t.id).put("name", t.name).put("content", result).put("ok", ok))
            Convos.save(c)
            listener?.onToolEnd(t.name, result, ok)
        }
    }

    // ---------------------------------------------------------------- background review (memory / skills)
    private fun bgReviewNote(name: String, args: JSONObject, res: String): String {
        val j = LoopUtil.jsonTry(res) as? JSONObject
        val failed = res.startsWith("Error") || (j != null && (j.opt("ok") == false || j.has("error")))
        val why = j?.optString("error")?.ifEmpty { null } ?: j?.optString("message")?.ifEmpty { null } ?: if (failed) res.removePrefix("Error:").trim() else ""
        val q = { t: String? -> if (!t.isNullOrBlank()) " — “${clip(t.replace(Regex("\\s+"), " "), 90)}”" else "" }
        if (name == "memory") {
            val prof = args.optString("target") == "user"; val act = args.optString("action").ifEmpty { "add" }
            if (failed) return "🧠 " + Y.t("Background review: could not update ${if (prof) "your profile" else "memory"}", "Arka plan: ${if (prof) "profiliniz" else "hafıza"} güncellenemedi") + (if (why.isNotEmpty()) " (${clip(why, 80)})" else "")
            val en = mapOf("add" to "note saved to ${if (prof) "your profile" else "memory"}", "replace" to "note updated in ${if (prof) "your profile" else "memory"}", "remove" to "note removed from ${if (prof) "your profile" else "memory"}")
            val trw = mapOf("add" to if (prof) "profilinize not eklendi" else "hafızaya not eklendi", "replace" to if (prof) "profilinizdeki bir not güncellendi" else "hafızadaki bir not güncellendi", "remove" to if (prof) "profilinizden bir not silindi" else "hafızadan bir not silindi")
            return "🧠 " + Y.t("Background review: " + (en[act] ?: en["add"]), "Arka plan: " + (trw[act] ?: trw["add"])) + q(args.optString("content").ifEmpty { args.optString("old_text") })
        }
        if (name == "skill_manage") {
            val n = args.optString("name").ifEmpty { Regex("skill ([\\w-]+)").find(res)?.groupValues?.get(1) ?: "" }
            if (failed) return "🧠 " + Y.t("Background review: could not save a skill", "Arka plan: yetenek kaydedilemedi") + (if (why.isNotEmpty()) " (${clip(why, 80)})" else "")
            return "🧠 " + if (args.optString("action") == "create" || Regex("Created", RegexOption.IGNORE_CASE).containsMatchIn(res)) Y.t("Background review: new skill “$n” created", "Arka plan: yeni yetenek oluşturuldu — “$n”") else Y.t("Background review: skill “$n” updated", "Arka plan: yetenek güncellendi — “$n”")
        }
        return "🧠 " + Y.t("Background review", "Arka plan") + " · $name" + if (failed) " (${clip(why.ifEmpty { res }, 80)})" else ""
    }

    /** After a turn: System-1 (or every 10 turns) decides whether to save memories / skills; a small tool loop does it. */
    fun backgroundReview(c: Convo, turnStart: Int) {
        if (turnStart >= c.messages.length() || Cfg.model.isEmpty()) return
        val d = Gates.review(c, turnStart) ?: run {
            var n = 0
            ConvoState.edit(c.id) { s -> n = s.optInt("reviewCounter", 0) + 1; s.put("reviewCounter", n) }
            if (n % 10 != 0) return
            ReviewDecision(true, true)
        }
        if (!d.memory && !d.skill) return
        Diag.i("review", "background review (memory=${d.memory}, skill=${d.skill})")
        val allowed = (if (d.memory) listOf("memory") else emptyList()) + (if (d.skill) listOf("skill_manage", "skills_list", "skill_view") else emptyList())
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Prompt.forRequest(c)))
        for (i in maxOf(0, c.messages.length() - 24) until c.messages.length()) { val m = c.messages.getJSONObject(i); if (m.optString("role") != "system") msgs.put(m) }
        msgs.put(JSONObject().put("role", "user").put("content", Gates.reviewPrompt(d)))
        val all = Tools.schemas(false); val tools = JSONArray()
        for (i in 0 until all.length()) if (all.getJSONObject(i).getJSONObject("function").getString("name") in allowed) tools.put(all.get(i))
        val ctx = ToolCtx(c, Cancel(), true, null, 0)
        try {
            for (round in 0 until 4) {
                val r = Llm.chat(LoopUtil.fixAlternation(msgs), tools, Cfg.fastModel.ifEmpty { Cfg.model }, stream = false)
                val am = JSONObject().put("role", "assistant").put("content", r.content)
                if (r.toolCalls.isNotEmpty()) am.put("tool_calls", JSONArray(r.toolCalls.map { JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject().put("name", it.name).put("arguments", it.args)) }))
                msgs.put(am)
                if (r.toolCalls.isEmpty()) break
                for (t in r.toolCalls) {
                    val args = LoopUtil.jsonTry(t.args) as? JSONObject ?: JSONObject()
                    val res = try { if (t.name !in allowed) "Error: tool ${t.name} is not enabled" else Tools.get(t.name)!!.run(args, ctx) } catch (e: Throwable) { "Error: " + (e.message ?: e.toString()) }
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", t.id).put("content", res))
                    val n = bgReviewNote(t.name, args, res)
                    Diag.i("review", n)
                    Bridge.emit("note", JSONObject().put("convoId", c.id).put("text", n))
                }
            }
        } catch (e: Throwable) {
            Bridge.emit("note", JSONObject().put("convoId", c.id).put("text", "background review failed: " + (e.message ?: e.toString())))
        }
    }

    /** Every tool call must have a result, or the model rejects the conversation next time. */
    private fun closeOpenCalls(c: Convo, text: String) {
        val answered = HashSet<String>()
        var lastCalls: JSONArray? = null
        for (i in 0 until c.messages.length()) {
            val m = c.messages.getJSONObject(i)
            if (m.optString("role") == "tool") answered.add(m.optString("tool_call_id"))
            if (m.optString("role") == "assistant") m.optJSONArray("tool_calls")?.let { lastCalls = it }
        }
        val calls = lastCalls ?: return
        for (k in 0 until calls.length()) {
            val id = calls.getJSONObject(k).optString("id")
            if (id !in answered) c.messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", text).put("ok", false))
        }
    }
}
