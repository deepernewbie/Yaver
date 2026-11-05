package com.plusai.yaver.app

import org.json.JSONObject
import java.io.File

/**
 * Connects the harness modules to each other and to the screen (Bridge events). Called once per process from
 * YaverApp.onCreate, before anything runs a turn.
 */
object Wiring {
    @Volatile private var done = false

    @Synchronized fun init() {
        if (done) return
        done = true

        // ---- per-conversation fields (persisted in Convo)
        ConvoFields.system = { it.system }
        ConvoFields.setSystem = { c, s -> c.system = s }
        ConvoFields.lang = { it.lang }
        ConvoFields.setLang = { c, l -> c.lang = l }

        // ---- system prompt parts
        PromptHooks.skillsIndex = { Skills.index() }
        PromptHooks.memoryBlock = { MemoryX.block() }
        PromptHooks.harnessDigest = { id, globalOnly -> Harness.digest(id, globalOnly) }
        MemHooks.pinned = { t, e -> MemoryX.pinned(t, e) }
        MemHooks.setPinned = { t, e, on -> MemoryX.setPinned(if (t == "user") "user" else "memory", e, on) }
        Compactor.systemFor = { Prompt.forRequest(it) }
        SideQuestion.system = { c -> Prompt.forRequest(c, false) }
        SideQuestion.langNote = { c, q -> Lang.note(Lang.fixed() ?: Lang.detect(q) ?: c.lang) }

        // ---- System-1 gates
        Compactor.relevance = { goal, out, cancel -> if (S1.enabled("compress")) Gates.compressScore(goal, out, cancel) else null }
        RagHooks.sectionGate = { q, instr -> s1Probs("rag", q, instr, null) }
        RagHooks.threshold = { S1.threshold }
        RagHooks.mark = { ok, n -> S1.mark("rag", ok, n) }
        RagHooks.auxReplaced = { S1.replaced() }
        MemoryX.s1Rag = { q, instr, cancel -> s1Probs("rag", q, instr, cancel) }
        LearnHooks.refineGate = { state, _ ->
            Gates.refine(state)?.let { JSONObject().put("useful", if (it.go) 1.0 else 0.0).put("global", if (it.global) 1.0 else 0.0).put("confident", true) }
        }
        Research.s1Director = { brief, learnings, cands, keep -> Gates.research(brief, learnings, cands, keep)?.let { Pair(it.stop, it.chosen) } }

        // ---- learning
        LearnHooks.busy = { Bridge.runs.isNotEmpty() }
        LearnHooks.currentConvoId = { Prefs.get("currentConvo", "").ifEmpty { null } }
        LearnHooks.langName = { c, text -> Lang.out(c.lang ?: Lang.detect(text)) }
        LearnHooks.isPinned = { t, e -> MemoryX.pinned(t, e) }
        LearnHooks.memoryLifecycle = { MemoryX.lifecycle().optInt("retired", 0) }
        LearnHooks.memIndex = { MemoryX.index() }
        LearnHooks.skillFile = { id -> File(Skills.root, "$id/SKILL.md") }
        LearnHooks.onSkillsChanged = { Skills.onChange?.invoke() }
        LearnEvents.listener = { type, data -> Bridge.emit(type, data); if (type == "refine" || type == "sleep" || type == "lesson_suspended") Diag.i("learn", "$type: " + clip(data.optString("text", data.optString("summary")), 200)) }
        Research.langName = { q -> Lang.name(Lang.fixed() ?: Lang.detect(q)) }

        // ---- memory
        MemoryX.onChange = { Bridge.emit("memory.changed", JSONObject()) }
        MemoryX.onNote = { t -> Diag.i("memory", t); Bridge.emit("note", JSONObject().put("convoId", Prefs.get("currentConvo", "")).put("text", t)) }
        S1.onChange = { s1Changed() }

        // ---- turns, planning, subagents
        Turns.langNote = { id -> (Convos.load(id))?.let { Lang.note(Lang.fixed() ?: it.lang) } ?: "" }
        Turns.listenerFor = { id, run -> Bridge.listener(id, run) }
        Turns.continueGoal = { id ->
            val c = Convos.load(id)
            val r = if (c == null) null else Planning.nextRound(c, Cancel())
            if (r != null) { Diag.i("planning", "${r.kind} round: ${r.label}"); Turns.start(id, r.text, r.label, r.kind) } else false
        }
        Ask.emitter = { t, d -> Bridge.emit(t, d); Bridge.host != null }
        Planning.onEvent = { id, type, data -> Bridge.emit("planning", JSONObject(data.toString()).put("convoId", id).put("type", type)) }
        Subagents.findSpec = { spec -> try { Harness.subagentSpec(spec).let { it.optString("id") to it.optString("content") } } catch (_: Throwable) { null } }
        Subagents.specIds = { try { Harness.list(null).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("kind") == "subagent" }.map { it.optString("id") } } } catch (_: Throwable) { emptyList() } }
        Subagents.loop = { r ->
            Agent.run(r.convo, r.text, listener = r.listener, cancel = r.cancel, background = r.background, depth = 1,
                systemExtra = if (r.first) r.role else null, allowedTools = r.tools.ifEmpty { null }, maxSteps = r.maxSteps)
        }

        // ---- files
        ShellHooks.archive = object : ArchiveHook {
            override fun unpack(archive: String, dest: String?, cancel: Cancel?): Pair<Int, String> =
                Archive.unpack(archive, dest, true, cancel).let { it.files to VPath.of(Workspace.resolve(it.dest)) }
            override fun entries(archive: String): List<String> = Archive.list(archive)
            override fun pack(paths: List<String>, out: String, cancel: Cancel?): Pair<Int, String> =
                Archive.pack(paths, out, cancel).let { it.files to VPath.of(Workspace.resolve(it.out)) }
        }
        ShellHooks.onFilesChanged = { WsChange.notify(null) }
        EverythingHooks.searchFiles = { q, k ->
            val r = Rag.retrieve(q, max = k)
            if (r.passages.isEmpty()) listOf(JSONObject().put("note", r.note ?: "no matching sections"))
            else r.passages.mapIndexed { i, p -> JSONObject().put("path", p.doc).put("title", p.title).put("text", p.text).also { o -> if (p.page != null) o.put("page", p.page); if (i == 0) o.put("how", r.how) } }
        }
        Folders.listener = { type, data -> Bridge.emit(type, data); if (type == "folders.note") Diag.i("folders", data.optString("text")) }

        // ---- mail, tasks, Outlook backup
        MailPlus.emit = { type, data ->
            Bridge.emit(type, data)
            // yaver 5: the widget shows unread / needs-reply / task counts
            if (type == "mail.triage" || type == "mail.changed" || type == "tasks") try { FiveWidget.updateSoon() } catch (_: Throwable) {}
        }

        // ---- yaver 5: resume interrupted runs (Agent.resumeInfo: harness)
        FiveHooks.resumeInfo = { c -> Agent.resumeInfo(c) }
        OSync.skillsDir = { Skills.root }
        OSync.memMetaExport = { JSONObject(MemoryX.meta().toString()) }
        OSync.memMetaImport = { mm -> MemoryX.importMeta(mm.toString()) }
    }

    /** Yes/no probabilities from System-1 for a list of instructions (null when the gate is off or failed). */
    private fun s1Probs(gate: String, question: String, instructions: List<String>, cancel: Cancel?): List<Double>? {
        if (!S1.enabled(gate) || instructions.isEmpty()) return null
        val qs = LinkedHashMap<String, S1Q>()
        instructions.forEachIndexed { i, s -> qs["s$i"] = S1Q.noul(s) }
        val r = S1.ask(gate, JSONObject().put("question", question), qs, cancel) ?: return null
        return instructions.indices.map { r.answers["s$it"]?.noul ?: 0.5 }
    }

    @Volatile private var s1At = 0L
    private fun s1Changed() {
        val now = System.currentTimeMillis()
        if (now - s1At < 1000) return
        s1At = now
        Bridge.emit("s1", JSONObject())
    }
}
