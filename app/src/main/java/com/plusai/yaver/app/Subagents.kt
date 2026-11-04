package com.plusai.yaver.app

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One subagent run handed to the agent loop. */
class SubRun(
    val convo: Convo,
    /** The user message for this run (first: GOAL/CONTEXT; later: a follow-up). */
    val text: String,
    /** The subagent's role, to be added to its system prompt. */
    val role: String,
    /** Allowed tool names (planning tools are never allowed); empty = no restriction. */
    val tools: Set<String>,
    val maxSteps: Int,
    val cancel: Cancel,
    val background: Boolean,
    val listener: AgentListener?,
    val first: Boolean
)

/**
 * Subagents: delegate_task (foreground and background=true), saved subagent specs from the continual harness,
 * and the subagents tool for background workers (port of runSubagent / subagentSession / subagentToolsets / Children).
 */
object Subagents {
    /** Native toolsets (TOOLSETS minus planning). Tools not listed in any set are allowed unless Tool.subagentOk is false. */
    val TOOLSETS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "files" to listOf("list_files", "read_file", "write_file", "delete_file", "read_document", "export_file", "search_documents", "present_files"),
        "code" to listOf("run_code"),
        "web" to listOf("web_search", "read_page", "http_request", "deep_research", "fetch"),
        "browser" to listOf("browser", "open_in_browser", "browser_use"),
        "vision" to listOf("look_at_image"),
        "memory" to listOf("memory", "session_search"),
        "skills" to listOf("skill_view", "skill_manage", "skills_list"),
        "mail" to listOf("mail", "calendar", "people"),
        "phone" to listOf("phone_calendar", "phone_alarm", "phone_reminder", "phone_notify", "phone_open", "phone_info", "phone_clipboard", "phone_speak", "schedule_task")
    )
    private val PLANNING = setOf("delegate_task", "subagents", "heartbeat", "todo", "clarify", "goal", "present_plan", "harness")

    // ---- hooks ----
    /** Harness subagent specs (Harness engineer): spec id or title → (id, content), or null when unknown. */
    @Volatile var findSpec: (String) -> Pair<String, String>? = { null }
    /** Saved spec ids, for the error message. */
    @Volatile var specIds: () -> List<String> = { emptyList() }
    /**
     * The agent loop for a subagent. The default uses Agent.run as it is today: the role goes into the first user
     * message and the tool filter / step budget are not applied (see report for the Agent.run extension).
     */
    @Volatile var loop: (SubRun) -> String = { r ->
        val text = if (r.first) r.role + "\n\n" + r.text else r.text
        Agent.run(r.convo, text, listener = r.listener, cancel = r.cancel, background = r.background, depth = 1)
    }

    fun toolsets(requested: List<String>): List<String> =
        (listOf("files") + requested.ifEmpty { listOf("web") }).distinct().filter { it in TOOLSETS }

    /** Tool names a subagent with [toolsets] may use (for the loop hook's filter). */
    fun allowedTools(toolsets: List<String>): Set<String> {
        val all = Tools.schemas(true)
        val listed = TOOLSETS.values.flatten().toSet()
        val granted = toolsets.flatMap { TOOLSETS[it] ?: emptyList() }.toSet()
        val out = HashSet<String>()
        for (i in 0 until all.length()) {
            val n = all.getJSONObject(i).getJSONObject("function").getString("name")
            if (n in PLANNING) continue
            if (n !in listed || n in granted) out.add(n)
        }
        return out
    }

    /** subagentSession: the role text and the first message. */
    fun session(goal: String, context: String, spec: String): Pair<String, String> {
        var specText = ""
        if (spec.isNotEmpty()) {
            val e = findSpec(spec) ?: throw IllegalArgumentException("no subagent spec \"$spec\". Saved specs: ${specIds().joinToString(", ").ifEmpty { "none" }}")
            specText = "\n\n# Your role (subagent spec \"${e.first}\")\n${e.second}"
        }
        val role = "You are a subagent. Complete the goal and reply with a concise, complete report (facts, file paths written, sources). You cannot ask the user questions.$specText"
        return Pair(role, "GOAL: $goal\n\nCONTEXT: ${context.ifEmpty { "(none)" }}")
    }

    private fun toolCount(c: Convo) = (0 until c.messages.length()).count { c.messages.getJSONObject(it).optString("role") == "tool" }

    /** delegate_task. */
    fun run(a: JSONObject, ctx: ToolCtx): String {
        if (ctx.depth >= 1) return "Error: subagents cannot delegate further."
        val goal = a.str("goal")
        if (a.optBoolean("background")) {
            val ch = Children.spawn(ctx.convo.id, a.str("name"), goal, a.str("context"), a.list("toolsets"), a.str("spec"))
            return "Background subagent \"${ch.name}\" started. It runs on its own and its report arrives later as a message (\"[subagent \"${ch.name}\" finished]\"). Continue with other work or end your turn; do not poll. Follow up with the subagents tool."
        }
        val (role, first) = session(goal, a.str("context"), a.str("spec"))
        val ts = toolsets(a.list("toolsets"))
        val sub = Convo.create("🤖 " + (a.str("name").ifEmpty { goal }).take(50), background = true)
        val name = a.str("name")
        ctx.progress(Y.t("Subagent is working…", "Alt ajan çalışıyor…"))
        val sublistener = object : AgentListener {
            override fun onToolStart(name: String, args: JSONObject) { ctx.progress("↳ $name") }
            override fun onProgress(text: String) { ctx.progress("↳ $text") }
            override fun approve(title: String, details: String, danger: Boolean) = ctx.listener?.approve(title, details, danger) ?: false
        }
        try {
            val out = loop(SubRun(sub, first, role, allowedTools(ts), 15, ctx.cancel, ctx.background, sublistener, true))
            return "[subagent report${if (name.isNotEmpty()) " · $name" else ""} · ${toolCount(sub)} tool calls]\n$out"
        } finally { try { Convos.delete(sub.id) } catch (_: Throwable) {} }
    }

    fun register() {
        Tools.add(Tool(
            "delegate_task",
            "Spawn a subagent with a fresh context to do a self-contained subtask (research, file processing). It shares the workspace but not this conversation. By default it returns its final report; with background=true it runs on its own (give it a name) and its report arrives later as a message, so you can start several in parallel and follow up with the subagents tool. spec = id of a saved subagent spec from the continual harness.",
            P.props(
                "goal" to P.s("What the subagent must accomplish and return"), "context" to P.s("Everything it needs to know"),
                "toolsets" to P.arr("Toolsets to grant (default files, web): " + TOOLSETS.keys.joinToString(", ")).also { it.getJSONObject("items").put("enum", org.json.JSONArray(TOOLSETS.keys.toList())) },
                "name" to P.s("Short name (needed for background subagents)"), "background" to P.b("Run in the background and report back later"),
                "spec" to P.s("Optional subagent spec id from the harness")
            ), listOf("goal"), subagentOk = false
        ) { a, ctx -> run(a, ctx) })

        Tools.add(Tool(
            "subagents",
            "Manage background subagents started with delegate_task background=true. Actions: list, message (send a follow-up to a named subagent; it keeps its context and reports back), stop, delete.",
            P.props("action" to P.s("list | message | stop | delete", "list", "message", "stop", "delete"), "name" to P.s("Subagent name"), "message" to P.s("For message: the follow-up")),
            listOf("action"), subagentOk = false
        ) { a, ctx ->
            val pid = ctx.convo.id
            if (a.str("action") == "list") return@Tool Children.list(pid).joinToString("\n") { ch ->
                "${ch.name}: ${ch.status} · runs ${ch.runs} · goal: ${clip(ch.goal, 100)}" + (ch.report?.let { " · last report: ${clip(it, 160)}" } ?: "")
            }.ifEmpty { "No subagents." }
            val reg = Children.of(pid)
            val ch = reg[Children.slugId(a.str("name"), "worker")] ?: reg[a.str("name")]
                ?: throw IllegalArgumentException("no subagent named ${a.str("name")}. Running: ${Children.list(pid).joinToString(", ") { it.name }.ifEmpty { "none" }}")
            when (a.str("action")) {
                "stop" -> { ch.cancel?.cancel(); Y.t("Stopping ${ch.name}.", "${ch.name} durduruluyor.") }
                "delete" -> { Children.delete(pid, ch); "Deleted ${ch.name}." }
                else -> {
                    if (a.str("message").isEmpty()) throw IllegalArgumentException("message is required")
                    if (ch.status == "running") { ch.inbox.add(a.str("message")); "${ch.name} is busy; the message is queued and it will continue with it when the current work finishes." }
                    else { Children.run(ch, pid, a.str("message"), false); "Sent to ${ch.name}. Its reply arrives as a message when it finishes; end your turn if you have nothing else to do." }
                }
            }
        })
    }
}

/* ---------- Background subagents: named workers that keep running, report back as messages and accept follow-ups ---------- */
object Children {
    class Child(val name: String, val goal: String, val convo: Convo, val role: String, val toolsets: List<String>) {
        @Volatile var status = "starting"
        val inbox: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        @Volatile var runs = 0
        @Volatile var report: String? = null
        val created = System.currentTimeMillis()
        @Volatile var cancel: Cancel? = null
    }

    private val map = ConcurrentHashMap<String, LinkedHashMap<String, Child>>() // parent convo id → name → child

    fun of(pid: String): LinkedHashMap<String, Child> = map.getOrPut(pid) { LinkedHashMap() }
    fun list(pid: String): List<Child> = synchronized(this) { of(pid).values.toList() }

    fun slugId(t: String?, kind: String): String {
        val s = java.text.Normalizer.normalize((t?.ifEmpty { null } ?: kind).lowercase(), java.text.Normalizer.Form.NFKD)
            .replace(Regex("[^\\w\\s-]"), "").trim().replace(Regex("[\\s_]+"), "-").replace(Regex("-+"), "-").take(48)
        return s.ifEmpty { kind }
    }

    private fun emit(pid: String, ch: Child) = Bridge.emit("subagent", JSONObject().put("convoId", pid).put("name", ch.name).put("status", ch.status).put("childConvoId", ch.convo.id).put("runs", ch.runs))

    fun spawn(pid: String, name0: String, goal: String, context: String, toolsets: List<String>, spec: String): Child {
        val (role, first) = Subagents.session(goal, context, spec)
        val ch: Child
        synchronized(this) {
            val reg = of(pid)
            val name = slugId(name0.ifEmpty { goal.trim().split(Regex("\\s+")).take(3).joinToString("-") }, "worker")
            if (reg[name]?.status == "running") throw IllegalStateException("a subagent named \"$name\" is already running")
            if (reg.values.count { it.status == "running" } >= 4) throw IllegalStateException("at most 4 background subagents can run at once; wait for one to finish")
            reg[name]?.let { old -> try { Convos.delete(old.convo.id) } catch (_: Throwable) {} }
            ch = Child(name, goal, Convo.create("🤖 $name", background = true), role, Subagents.toolsets(toolsets))
            reg[name] = ch
        }
        run(ch, pid, first, true)
        return ch
    }

    /** Runs the child once with [text] (then its queued follow-ups); the report reaches the parent conversation as a message. */
    fun run(ch: Child, pid: String, text: String, first: Boolean) {
        ch.status = "running"
        val cancel = Cancel()
        ch.cancel = cancel
        emit(pid, ch)
        Y.bg {
            var msg = text; var isFirst = first
            try {
                while (true) {
                    val out = Subagents.loop(SubRun(ch.convo, msg, ch.role, Subagents.allowedTools(ch.toolsets), 20, cancel, true, null, isFirst))
                    ch.status = "idle"; ch.report = out; ch.runs++
                    val more = synchronized(ch.inbox) { val l = ArrayList(ch.inbox); ch.inbox.clear(); l }
                    if (more.isNotEmpty()) { ch.status = "running"; msg = more.joinToString("\n\n"); isFirst = false; continue }
                    val ln = Turns.langNote(pid)
                    val n = (0 until ch.convo.messages.length()).count { ch.convo.messages.getJSONObject(it).optString("role") == "tool" }
                    Turns.deliver(pid, "[subagent \"${ch.name}\" finished · $n tool calls]\n$out\n\n(Follow up with subagents action=message name=\"${ch.name}\" if needed; its context is kept.)" + if (ln.isNotBlank()) "\n\n$ln" else "", "🤖 subagent ${ch.name} finished")
                    break
                }
            } catch (e: CancelledException) {
                ch.status = "stopped"; ch.report = e.message
            } catch (e: Throwable) {
                ch.status = "error"; ch.report = e.message ?: e.toString()
                Turns.deliver(pid, "[subagent \"${ch.name}\" failed] ${ch.report}", "🤖 subagent ${ch.name} failed")
            }
            emit(pid, ch)
        }
    }

    fun delete(pid: String, ch: Child) {
        ch.cancel?.cancel()
        synchronized(this) { of(pid).remove(ch.name) }
        try { Convos.delete(ch.convo.id) } catch (_: Throwable) {}
    }

    /** All children of a conversation (when it is deleted). */
    fun clearConvo(pid: String) { for (ch in list(pid)) delete(pid, ch); map.remove(pid) }

    /** /subagents [stop <name|all>] — returns the note to show. */
    fun command(pid: String, rest: String): String {
        val st = Regex("^stop\\s+(\\S+)$").find(rest.trim())
        if (st != null) { for (ch in list(pid)) if (st.groupValues[1] == "all" || ch.name == st.groupValues[1]) ch.cancel?.cancel(); return Y.t("Stopping ", "Durduruluyor: ") + st.groupValues[1] }
        return "🤖 " + list(pid).joinToString(" · ") { "${it.name}: ${it.status}" }.ifEmpty { Y.t("No background subagents.", "Arka planda alt ajan yok.") }
    }
}
