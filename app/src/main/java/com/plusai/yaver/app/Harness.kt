package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * Continual harness (/refine): small, evidence-backed edits to prompt notes, memories, skills and subagent specs,
 * with Mem0/ACE reconciliation, an erosion guard, history and rollback. State: files/harness/state.json.
 */
object Harness {
    val KINDS = listOf("prompt", "memory", "skill", "subagent")
    internal val lock = Any()
    private val dir: File get() = File(Y.app.filesDir, "harness").apply { mkdirs() }
    private val file: File get() = File(dir, "state.json")

    val REFINEMENT_SYSTEM_PROMPT = """You are yaver's /refine continual harness subsystem.

Your job is to improve the editable continual harness state from the current trajectory.
This is similar in spirit to context compaction, but instead of summarizing the
conversation you emit precise Create, Update, or Delete edits to reusable state.
The continual harness is the persistent, editable set of prompt notes, memories,
skills, and subagent specs that lets yaver improve reusable behavior
outside the token history.

Continual harness components:
- prompt: supplemental prompt notes only. The base system prompt is immutable and MUST NOT be rewritten.
- memory: durable facts, decisions, failures, preferences, and outcomes.
- skill: a reusable procedure saved as a SKILL.md that the agent loads with skill_view. Content is the procedure: when to use it, concrete steps (tool names and arguments), pitfalls as rules with a short reason.
- subagent: reusable delegation specs, including purpose, instructions, and when to invoke. The agent invokes one with delegate_task (spec = the entry id).

Scope and persistence policy:
- The default editable continual harness store is local to the current conversation. Use it for session-specific progress, active task state, current-run coordination notes, temporary blockers, and project facts that should not affect other conversations.
- A caller may explicitly request global refinement. Global edits must be stable cross-session lessons, durable user preferences, reusable skills/subagents, or tool/environment facts that should affect future conversations.
- All edits in one refinement apply only to the requested scope. During a local refinement, global entries are read-only context: never propose update or delete edits for them; create a local entry instead when a session-specific override is genuinely needed.
- Use memory for declarative facts and preferences, skill for repeatable procedures, prompt for narrow behavioral policy addendums, and subagent for reusable delegation roles.
- Create or update the smallest relevant component.

Use the trajectory, current continual harness state, and prior refinement history. Prefer
small evidence-backed edits. If prior refinements caused issues, rollback or
replace the faulty editable entries.

Lessons are kept as small atomic bullets (one lesson per entry) and grow by delta edits:
- Each entry in the state shows ✓helpful / ✗harmful counts gathered from the user's feedback (👍/👎, praise, corrections).
- Prefer updating an existing entry over creating a near-duplicate; never rewrite or delete many entries at once.
- An entry with more ✗ than ✓ is a candidate to fix (update) or delete; an entry with many ✓ is proven, keep it.
- Entries marked [suspended] were switched off because they hurt more than they helped; fix them or leave them.
- A <recent_failures> block, when present, quotes the user's complaints verbatim; turn recurring ones into one concrete rule. Never edit source files directly. Write titles and content in English unless the user's own words must be kept. Output
JSON only with this exact shape:

{
  "summary": "one sentence",
  "rationale": "why these edits are justified by trajectory evidence",
  "expectedOutcome": "what should improve and how to validate it",
  "edits": [
    {
      "action": "create|update|delete",
      "kind": "prompt|memory|skill|subagent",
      "id": "stable id for update/delete, optional for create",
      "title": "required for create/update except delete",
      "content": "required for create/update except delete",
      "path": "optional grouping path",
      "reason": "why this edit is useful"
    }
  ]
}"""

    val AUTO_REFINE_REVIEW_SYSTEM_PROMPT = """You are yaver's automatic /refine review gate.
Decide whether this checkpoint should run /refine. Auto /refine writes local continual harness state by default, so approve when the trajectory contains evidence useful to this conversation's future turns.
Reject one-off noise, unsupported hypotheses, and transient tool outputs. Ask for global refinement only for durable cross-session lessons or explicitly project-qualified lessons likely to be reused in future conversations.

Return JSON only:
{
  "shouldRefine": true|false,
  "global": true|false,
  "rationale": "short reason",
  "instructions": "optional concise instructions for /refine if shouldRefine is true"
}"""

    // ---------------- small helpers ----------------

    /** First balanced {...} JSON object in a model reply (fences allowed). */
    fun firstJson(t: String?): JSONObject? {
        val s = (t ?: "").replace(Regex("^```(?:json)?\\s*|\\s*```\\s*$"), "")
        val a = s.indexOf('{'); if (a < 0) return null
        var d = 0; var q = false; var esc = false
        for (i in a until s.length) {
            val c = s[i]
            if (q) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') q = false; continue }
            if (c == '"') q = true else if (c == '{') d++ else if (c == '}' && --d == 0) return try { JSONObject(s.substring(a, i + 1)) } catch (_: Exception) { null }
        }
        return null
    }

    fun slugId(t: String?, kind: String): String {
        val src = if (t.isNullOrEmpty()) kind else t
        val s = Normalizer.normalize(src.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("[^A-Za-z0-9_\\s-]"), "").trim()
            .replace(Regex("[\\s_]+"), "-").replace(Regex("-+"), "-").take(48)
        return s.ifEmpty { kind }
    }

    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    internal fun s(o: JSONObject?, k: String): String = if (o != null && o.has(k) && !o.isNull(k)) o.opt(k).toString() else ""
    internal fun sessOf(e: JSONObject): String? = if (e.has("session") && !e.isNull("session")) e.optString("session") else null
    internal fun nul(v: Any?): Any = v ?: JSONObject.NULL
    internal fun rx(p: String): Pattern = Pattern.compile(p, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    internal fun objs(o: JSONObject?): List<JSONObject> { if (o == null) return emptyList(); val out = ArrayList<JSONObject>(); val it = o.keys(); while (it.hasNext()) o.optJSONObject(it.next())?.let { out.add(it) }; return out }
    internal fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    // ---------------- state ----------------

    fun load(): JSONObject = synchronized(lock) {
        val j = try { if (file.exists()) JSONObject(file.readText()) else null } catch (_: Exception) { null }
        val st = if (j != null && j.has("entries")) j else JSONObject().put("schema", 1).put("rev", 0).put("entries", JSONObject()).put("history", JSONArray()).put("auto", JSONObject().put("every", 0))
        val en = st.optJSONObject("entries") ?: JSONObject().also { st.put("entries", it) }
        for (k in KINDS) if (en.optJSONObject(k) == null) en.put(k, JSONObject())
        if (st.optJSONArray("history") == null) st.put("history", JSONArray())
        if (st.optJSONObject("auto") == null) st.put("auto", JSONObject().put("every", 0))
        if (st.optInt("autoV", 0) == 0) { val au = st.getJSONObject("auto"); if (au.optInt("every", 0) == 0) au.put("every", 6); st.put("autoV", 2) }
        st
    }

    fun save(st: JSONObject): Int = synchronized(lock) {
        val r = st.optInt("rev", 0) + 1
        st.put("rev", r)
        write(st)
        r
    }

    /** Counters only: no rev bump. */
    internal fun rawSave(st: JSONObject) = synchronized(lock) { write(st) }

    private fun write(st: JSONObject) {
        val tmp = File(dir, "state.tmp")
        tmp.writeText(st.toString(1))
        if (!tmp.renameTo(file)) { file.writeText(st.toString(1)); tmp.delete() }
    }

    fun rev(): Int = load().optInt("rev", 0)

    /** Global lessons (all kinds) for the Outlook backup, so another phone learns the same. */
    fun exportGlobal(): JSONArray = JSONArray().also { out ->
        val st = load()
        for (k in KINDS) for (e in objs(entries(st, k))) if (s(e, "scope") == "global") out.put(JSONObject(e.toString()).put("kind", k))
    }

    /** Replaces the global lessons with [arr] (live sync); conversation-local lessons stay. */
    fun replaceGlobal(arr: JSONArray) {
        val st = load()
        for (k in KINDS) { val en = entries(st, k); for (e in objs(en)) if (s(e, "scope") == "global") en.remove(s(e, "id")) }
        for (i in 0 until arr.length()) { val e = arr.optJSONObject(i) ?: continue; val k = s(e, "kind").takeIf { it in KINDS } ?: continue; val id = s(e, "id"); if (id.isEmpty()) continue
            entries(st, k).put(id, JSONObject(e.toString()).put("scope", "global").put("session", JSONObject.NULL)) }
        save(st)
    }

    /** Adds the backed-up global lessons that are not here yet (by id, or by the same title in the same kind). Returns how many were added. */
    fun importGlobal(arr: JSONArray?): Int {
        if (arr == null || arr.length() == 0) return 0
        val st = load(); var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val k = s(e, "kind").takeIf { it in KINDS } ?: continue
            val id = s(e, "id"); if (id.isEmpty()) continue
            val en = entries(st, k)
            if (en.has(id) || objs(en).any { s(it, "title").equals(s(e, "title"), true) }) continue
            en.put(id, JSONObject(e.toString()).put("scope", "global").put("session", JSONObject.NULL))
            n++
        }
        if (n > 0) { save(st); Diag.i("harness", "restored $n global lessons from the Outlook backup") }
        return n
    }

    fun entries(st: JSONObject, kind: String): JSONObject = st.getJSONObject("entries").optJSONObject(kind) ?: JSONObject().also { st.getJSONObject("entries").put(kind, it) }

    fun all(st: JSONObject): List<JSONObject> = KINDS.flatMap { objs(entries(st, it)) }

    fun visible(st: JSONObject, sid: String?): List<JSONObject> = all(st).filter { s(it, "scope") == "global" || sessOf(it) == sid }

    fun skillPath(id: String): File = LearnHooks.skillFile?.invoke(id) ?: File(dir, "skills/$id/SKILL.md")

    private fun byScore(a: JSONObject, b: JSONObject): Int {
        val d = Learn.score(b).compareTo(Learn.score(a))
        return if (d != 0) d else s(b, "updated_at").compareTo(s(a, "updated_at"))
    }

    /** Compact digest for the system prompt: prompt notes in full, other kinds as one-line routing hints. "" when empty. */
    fun digest(convoId: String?, globalOnly: Boolean = false): String {
        val st = load()
        val list = visible(st, convoId).filter { (!globalOnly || s(it, "scope") == "global") && s(it, "status") != "suspended" }
        if (list.isEmpty()) return ""
        val lines = arrayListOf("# Continual harness", "Supplemental notes, memories, skills and subagent specs learned in earlier work (the base prompt above is unchanged). Use them when relevant; delegate_task accepts spec=<subagent id>.")
        for (k in KINDS) {
            val es = list.filter { s(it, "kind") == k }.sortedWith { a, b -> byScore(a, b) }
            if (es.isEmpty()) continue
            lines.add("$k: ${es.size}")
            for (e in es.take(Learn.CAP[k] ?: 12)) {
                val c = s(e, "content")
                lines.add("- [${s(e, "scope")}:${s(e, "id")}] ${s(e, "title")}" + when (k) {
                    "prompt" -> ": " + clip(c, 600)
                    "skill" -> " (skill_view ${s(e, "id")})"
                    else -> ": " + clip(c.replace(Regex("\\s+"), " "), 180)
                })
            }
        }
        return lines.joinToString("\n")
    }

    private fun appliedLine(a: JSONObject) = "${if (a.optBoolean("ok")) "applied" else "failed"} ${s(a, "action")} ${s(a, "kind")}:${s(a, "id")}"

    fun overview(convoId: String?): String {
        val st = load()
        val out = arrayListOf(digest(convoId).ifEmpty { "No saved harness entries yet." })
        val h = arr(st.getJSONArray("history"))
        out.add("\nrecent refinements: ${h.size}")
        for (x in h.takeLast(8)) out.add("[${s(x, "id")}]${if (s(x, "rollbackOf").isNotEmpty()) " rollbackOf=" + s(x, "rollbackOf") else ""} ${s(x, "summary")} — ${arr(x.optJSONArray("applied")).joinToString(", ") { appliedLine(it) }.ifEmpty { "no edits" }}")
        return out.joinToString("\n")
    }

    /** History lines for the harness tool (action=history). */
    fun historyText(n: Int = 15): String {
        val h = arr(load().getJSONArray("history")).takeLast(n)
        if (h.isEmpty()) return "No refinements yet."
        val df = java.text.DateFormat.getDateTimeInstance()
        return h.joinToString("\n") { x ->
            "[${s(x, "id")}] ${df.format(Date(x.optLong("created")))} ${s(x, "trigger")}${if (s(x, "rollbackOf").isNotEmpty()) " rollbackOf=" + s(x, "rollbackOf") else ""} · ${s(x, "summary")} · " +
                arr(x.optJSONArray("applied")).joinToString(", ") { "${if (it.optBoolean("ok")) "" else "FAILED "}${s(it, "action")} ${s(it, "kind")}:${s(it, "id")}" }
        }
    }

    private val BASE_ID = Regex("^base[_-]?system[_-]?prompt$", RegexOption.IGNORE_CASE)

    private fun validate(e: JSONObject, scope: String, st: JSONObject): String? {
        val action = s(e, "action"); val kind = s(e, "kind"); val id = s(e, "id")
        if (action !in listOf("create", "update", "delete")) return "unsupported action $action"
        if (kind !in KINDS) return "unsupported kind $kind"
        if (BASE_ID.matches(id)) return "the base system prompt is immutable"
        if (action != "delete" && (s(e, "title").isEmpty() || s(e, "content").isEmpty())) return "title and content are required"
        val cur = if (id.isNotEmpty()) entries(st, kind).optJSONObject(id) else null
        if (action != "create" && cur == null) return "no $kind entry with id $id"
        if (cur != null && s(cur, "scope") != scope && action != "create") return "entry $id is ${s(cur, "scope")}; a $scope refinement cannot change it"
        return null
    }

    private fun writeSkill(entry: JSONObject) {
        val f = skillPath(s(entry, "id")); f.parentFile?.mkdirs()
        f.writeText("---\nname: ${s(entry, "id")}\ndescription: ${s(entry, "title").replace("\n", " ")}\n---\n${s(entry, "content").trim()}\n")
    }

    private val AUTO_TRIGGER = Regex("^(auto|learn|sleep)$")

    /** Applies edits (or a rollback) and records before/after snapshots so any refinement can be undone. Returns the history record. */
    fun apply(proposal: JSONObject, scope: String = "local", sid: String? = null, trigger: String = "manual", rollbackOf: String? = null): JSONObject {
        val rec: JSONObject
        var skillsTouched = false
        synchronized(lock) {
            val st = load()
            val id = "r" + System.currentTimeMillis().toString(36)
            val applied = JSONArray()
            val edits = proposal.optJSONArray("edits") ?: JSONArray()
            fun fail(e: JSONObject, err: String) = applied.put(JSONObject().put("action", s(e, "action")).put("kind", s(e, "kind")).put("id", s(e, "id").ifEmpty { "?" }).put("ok", false).put("error", err))
            for (n in 0 until minOf(12, edits.length())) {
                val raw = edits.optJSONObject(n) ?: continue
                val e = JSONObject(raw.toString())
                val kind = s(e, "kind")
                if (s(e, "action") == "create" && s(e, "id").isEmpty()) e.put("id", slugId(s(e, "title"), kind))
                // Mem0/ACE reconciliation: a create that repeats an existing lesson in the same scope becomes an update of it (its counters stay)
                if (rollbackOf == null && s(e, "action") == "create" && kind in KINDS && s(e, "title").isNotEmpty() && s(e, "content").isNotEmpty()) {
                    val dup = Learn.similar(st, e, scope, sid)
                    if (dup != null) { e.put("action", "update"); e.put("id", s(dup, "id")); e.put("reason", (if (s(e, "reason").isNotEmpty()) s(e, "reason") + " " else "") + "(merged into the existing similar entry)") }
                }
                // erosion guard: automatic passes may not delete proven lessons or wipe many entries at once
                if (rollbackOf == null && s(e, "action") == "delete" && AUTO_TRIGGER.matches(trigger) && kind in KINDS) {
                    val cur = entries(st, kind).optJSONObject(s(e, "id"))
                    val dels = arr(applied).count { it.optBoolean("ok") && s(it, "action") == "delete" }
                    val hp = cur?.optInt("helpful", 0) ?: 0; val hm = cur?.optInt("harmful", 0) ?: 0
                    val why = if (cur != null && hp >= 3 && hp > hm) "erosion guard: a proven lesson (helped ${hp}×) is not deleted automatically" else if (dels >= 2) "erosion guard: at most 2 automatic deletions per pass" else null
                    if (why != null) { fail(e, why); continue }
                }
                if (s(e, "action") == "create" && kind in KINDS && entries(st, kind).has(s(e, "id"))) {
                    var k = 2; while (entries(st, kind).has("${s(e, "id")}-$k")) k++
                    e.put("id", "${s(e, "id")}-$k")
                }
                val err = if (rollbackOf != null) null else validate(e, scope, st)
                if (err != null) { fail(e, err); continue }
                if (kind !in KINDS) { fail(e, "unsupported kind $kind"); continue }
                val eid = s(e, "id")
                val store = entries(st, kind)
                val before = store.optJSONObject(eid)?.let { JSONObject(it.toString()) }
                val sf = if (kind == "skill") skillPath(eid) else null
                val skillBefore = if (sf != null && sf.isFile) sf.readText() else null
                var after: JSONObject? = null
                if (rollbackOf != null) {
                    after = if (e.has("restore") && !e.isNull("restore")) e.optJSONObject("restore") else null
                    if (after != null) store.put(eid, after) else store.remove(eid)
                } else if (s(e, "action") == "delete") store.remove(eid)
                else {
                    val now = nowIso()
                    after = JSONObject().put("id", eid).put("kind", kind).put("title", s(e, "title").take(160)).put("content", s(e, "content").take(6000))
                        .put("path", s(e, "path")).put("scope", scope).put("session", nul(if (scope == "local") sid else null)).put("source", trigger)
                        .put("created_at", if (before != null) s(before, "created_at") else now).put("updated_at", now)
                        .put("version", if (before != null) before.optInt("version", 1) + 1 else 1)
                    if (before != null) {
                        for (k in listOf("helpful", "harmful", "lastHelpful", "lastHarmful")) if (before.optLong(k, 0) != 0L) after.put(k, before.opt(k))
                        if (s(before, "status") == "suspended") { // a fixed lesson gets a fresh trial
                            after.put("status", "active"); after.put("harmful", minOf(after.optInt("harmful", 0), after.optInt("helpful", 0))); after.put("revived", now)
                        }
                        if (s(before, "scope") != scope) { after.put("scope", s(before, "scope")); after.put("session", nul(sessOf(before))) }
                    }
                    store.put(eid, after)
                }
                if (kind == "skill" && sf != null) {
                    skillsTouched = true
                    if (rollbackOf != null && e.has("restoreSkill")) {
                        if (e.isNull("restoreSkill")) { if (sf.isFile) sf.delete() } else { sf.parentFile?.mkdirs(); sf.writeText(e.optString("restoreSkill")) }
                    } else if (after != null) writeSkill(after) else if (sf.isFile) sf.delete()
                }
                applied.put(JSONObject().put("action", s(e, "action")).put("kind", kind).put("id", eid).put("ok", true).put("before", nul(before)).put("after", nul(after))
                    .put("skillBefore", nul(skillBefore)).put("reason", s(e, "reason")))
            }
            val summary = s(proposal, "summary").ifEmpty { if (rollbackOf != null) "Rollback of $rollbackOf" else "Refinement" }
            rec = JSONObject().put("id", id).put("trigger", trigger).put("scope", scope).put("session", nul(sid)).put("summary", summary.take(300))
                .put("rationale", s(proposal, "rationale").take(1200)).put("expectedOutcome", s(proposal, "expectedOutcome").take(600))
                .put("applied", applied).put("rollbackOf", nul(rollbackOf)).put("created", System.currentTimeMillis())
            val h = st.getJSONArray("history"); h.put(rec)
            if (h.length() > 60) { val keep = JSONArray(); for (i in h.length() - 60 until h.length()) keep.put(h.get(i)); st.put("history", keep) }
            save(st)
        }
        if (skillsTouched && arr(rec.optJSONArray("applied")).any { it.optBoolean("ok") && s(it, "kind") == "skill" }) try { LearnHooks.onSkillsChanged?.invoke() } catch (_: Throwable) {}
        return rec
    }

    fun rollback(id: String, sid: String?): JSONObject {
        val st = load(); val h = arr(st.getJSONArray("history"))
        val target = (if (id == "last" || id.isEmpty()) h.asReversed().firstOrNull { s(it, "rollbackOf").isEmpty() && arr(it.optJSONArray("applied")).any { a -> a.optBoolean("ok") } } else h.firstOrNull { s(it, "id") == id })
            ?: throw IllegalArgumentException("refinement $id not found. Recent: ${h.takeLast(5).joinToString(", ") { s(it, "id") }.ifEmpty { "none" }}")
        val edits = JSONArray()
        for (a in arr(target.optJSONArray("applied")).filter { it.optBoolean("ok") }.asReversed()) {
            val e = JSONObject().put("action", "restore").put("kind", s(a, "kind")).put("id", s(a, "id")).put("restore", a.opt("before") ?: JSONObject.NULL)
            if (s(a, "kind") == "skill") e.put("restoreSkill", a.opt("skillBefore") ?: JSONObject.NULL)
            edits.put(e)
        }
        return apply(JSONObject().put("summary", "Rollback of ${s(target, "id")}: ${s(target, "summary")}").put("rationale", "Restores the harness entries as they were before that refinement.").put("edits", edits),
            s(target, "scope").ifEmpty { "local" }, sid, "rollback", s(target, "id"))
    }

    fun transcript(messages: JSONArray, n: Int = 40): String {
        val from = maxOf(0, messages.length() - n)
        val sb = ArrayList<String>()
        for (i in from until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role == "tool") { sb.add("[tool] " + clip(m.optString("content"), 400)); continue }
            val c = m.opt("content"); val text = if (c is String) c else if (c == null || c == JSONObject.NULL) "" else "[media]"
            var line = "[$role] ${clip(text, 1500)}"
            val tc = m.optJSONArray("tool_calls")
            if (tc != null && tc.length() > 0) line += " calls " + arr(tc).joinToString("; ") { val f = it.optJSONObject("function"); "${s(f, "name")}(${clip(s(f, "arguments"), 160)})" }
            sb.add(line)
        }
        return sb.joinToString("\n")
    }

    /** Runs /refine over a conversation (messages may be empty for the sleep pass). Throws when the model gives no usable JSON. */
    fun refine(messages: JSONArray, sid: String?, instructions: String = "", global: Boolean = false, trigger: String = "manual", cancel: Cancel? = null, failures: String = ""): JSONObject {
        val st = load(); val scope = if (global) "global" else "local"
        val state = visible(st, sid).joinToString("\n") { e ->
            "- [${s(e, "scope")}:${s(e, "id")}] ${s(e, "kind")} \"${s(e, "title")}\" v${e.optInt("version", 1)} ✓${e.optInt("helpful", 0)} ✗${e.optInt("harmful", 0)}${if (s(e, "status") == "suspended") " [suspended]" else ""}: ${clip(s(e, "content").replace(Regex("\\s+"), " "), 300)}"
        }.ifEmpty { "(empty)" }
        val history = arr(st.getJSONArray("history")).takeLast(10).joinToString("\n\n") { h ->
            "[${s(h, "id")}]${if (s(h, "rollbackOf").isNotEmpty()) " rollbackOf=" + s(h, "rollbackOf") else ""} ${s(h, "summary")}\n${arr(h.optJSONArray("applied")).joinToString("\n") { appliedLine(it) }}\nExpected outcome: ${s(h, "expectedOutcome")}"
        }.ifEmpty { "(none)" }
        val policy = if (global) "Requested refinement scope: global. Only propose stable cross-session continual harness edits, durable user preferences, reusable skills/subagents, or explicitly project-qualified facts that should affect future conversations. Do not persist session-only progress, temporary blockers, or current-run coordination globally."
        else "Requested refinement scope: local. Prefer local continual harness edits for current task progress, temporary blockers, current-run coordination, and project facts that are not clearly reusable across conversations. Global entries are read-only context: do not propose update or delete edits for them; create a local entry instead if an override is needed."
        val user = listOf(
            "<current_harness_state>\n$state\n</current_harness_state>",
            "<refinement_history>\n$history\n</refinement_history>",
            "<conversation>\n${clip(transcript(messages), 40000)}\n</conversation>",
            "<scope_policy>\n$policy\n</scope_policy>",
            if (failures.isNotEmpty()) "<recent_failures>\n$failures\n</recent_failures>" else "",
            if (instructions.isNotEmpty()) "<user_refine_instructions>\n$instructions\n</user_refine_instructions>" else "",
            "Return only JSON edits. If no useful edit is justified, return an empty edits array with a rationale."
        ).filter { it.isNotEmpty() }.joinToString("\n\n")
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", REFINEMENT_SYSTEM_PROMPT)).put(JSONObject().put("role", "user").put("content", user))
        val r = Llm.chat(msgs, null, Cfg.model, cancel = cancel, stream = false)
        val proposal = firstJson(r.content.ifEmpty { r.reasoning })
        if (proposal == null || proposal.optJSONArray("edits") == null)
            throw IllegalStateException(if (r.finish == "length") "the model stopped before completing its JSON (output limit); try again with narrower instructions" else "the model did not return the JSON edit format")
        return apply(proposal, scope, sid, trigger)
    }

    // ---------------- automatic checkpoints ----------------

    private val NOT_REAL_USER = Regex("^(\\(Reply in [^)]*\\) )?\\[(harness|verify-on-stop|Context summary)")
    private val STANDING = rx("\\b(bundan sonra|artık hep|her zaman|daima|asla|hiçbir zaman|tüm konuşmalarda|unutma|aklında tut|hatırla|from now on|always|never|in every conversation|remember (that|to))\\b")
    private val CORRECTION = rx("^(hayır|yok|yanlış|öyle değil|olmadı|olmamış|hatalı|bu değil|neden .*(yapmadın|aramadın|sormadın)|bunu yapma|yapmamalısın|tekrar dene)|\\b(yanlış (yaptın|anladın)|öyle değil|beni dinlemiyorsun|demiştim|söylemiştim|no,|that's wrong|not what I|you didn'?t|stop doing|don'?t do)\\b")
    private val USER_SPLIT = Regex("\n\n\\[(Language|Relevant memories|Feedback on your previous|Past mistakes on similar|Skill |Attached files)")
    private val LOOP = Regex("\\[harness note\\] Loop check: ([^.]*)")

    internal fun isRealUser(m: JSONObject): Boolean = m.optString("role") == "user" && !NOT_REAL_USER.containsMatchIn(m.optString("content"))

    /** A turn worth learning from right away: a correction, a standing instruction, or a harness intervention. "" otherwise. */
    fun signal(c: Convo): String {
        val ms = c.messages
        var i = ms.length() - 1
        while (i >= 0 && !isRealUser(ms.getJSONObject(i))) i--
        if (i < 0) return ""
        val um = ms.getJSONObject(i)
        val u = um.optString("display").ifEmpty { um.optString("content").split(USER_SPLIT)[0] }
        val after = (i + 1 until ms.length()).joinToString("\n") { ms.getJSONObject(it).optString("content", "") }
        if (STANDING.matcher(u).find()) return "standing_instruction: the user just said \"${clip(u, 300)}\" — durable, so a global entry is appropriate"
        if (CORRECTION.matcher(u.trim()).find()) return "user_correction: the user corrected the previous answer: \"${clip(u, 300)}\""
        if (after.contains("[harness] You said you do not know, but your memory")) return "harness_intervention: the model answered 'I don't know' although its memory held the answer; it must check memory (memory action=search) before saying it does not know anything about the user"
        if (after.contains("[harness] You said you do not know")) return "harness_intervention: the model answered 'I don't know' without searching and had to be told to search the web first"
        if (after.contains("[harness] Your answer above is in")) return "harness_intervention: the model answered in the wrong language and had to rewrite"
        LOOP.find(after)?.let { return "harness_intervention: yaver got stuck in a loop (${it.groupValues[1]}) and had to be told to change approach" }
        if (after.contains("[verify-on-stop]")) return "harness_intervention: the final answer had unsupported claims and had to be verified"
        return ""
    }

    /**
     * Automatic checkpoint every N turns (or right away on a [reason] from [signal] / feedback): System-1 (via
     * [LearnHooks.refineGate]) or a small LLM review decides whether /refine runs. Blocking; call from a worker thread.
     * Emits a "refine" event when something was learned. Returns the record or null.
     */
    fun autoCheck(c: Convo, reason: String = "", cancel: Cancel? = null): JSONObject? {
        if (!LearnSettings.learn) return null
        val st = load(); val every = st.getJSONObject("auto").optInt("every", 0)
        if ((every == 0 && reason.isEmpty()) || Cfg.model.isEmpty()) return null
        if (reason.isEmpty()) {
            val k = "learn.rc." + c.id; val n = Prefs.int(k, 0) + 1; Prefs.put(k, n.toString())
            if (n % every != 0) return null
        }
        // inference-volume guard: periodic reviews at most every 10 minutes across all conversations; signal-driven ones at most every 45 s
        run {
            val last = Prefs.get("learn.lastReview", "0").toLongOrNull() ?: 0L
            val gap = if (reason.isNotEmpty()) 45_000L else 10 * 60_000L
            if (System.currentTimeMillis() - last < gap) return null
            Prefs.put("learn.lastReview", System.currentTimeMillis().toString())
        }
        var go = false; var global = false; var instructions = ""
        val gate = LearnHooks.refineGate
        val questions = JSONObject()
            .put("useful", JSONObject().put("type", "noul").put("instructions", "Does this trajectory contain a lesson worth keeping for later turns (a repeated failure and its fix, a reusable procedure, a user correction, a durable preference)?"))
            .put("global", JSONObject().put("type", "noul").put("instructions", "Is that lesson a durable cross-session one (user preference, reusable procedure) rather than current-task progress?"))
        val g = if (gate != null) try { gate(clip(transcript(c.messages, 24), 3500), questions) } catch (e: Throwable) { null } else null
        if (g != null) {
            val pu = g.optDouble("useful", 0.0); val pg = g.optDouble("global", 0.0)
            go = pu >= 0.6; global = pg >= 0.7
            val confident = if (g.has("confident")) g.optBoolean("confident") else maxOf(pu, 1 - pu) >= 0.7
            try { LearnHooks.gateMark?.invoke("refine", confident, if (go) "refine(${if (global) "global" else "local"})" else "skip") } catch (_: Throwable) {}
            if (!go) return null
        } else {
            val user = "<trigger>\n${reason.ifEmpty { "turn_interval; $every turns since last auto-refine review" }}\n</trigger>\n\n<current_harness_state>\n${digest(c.id).ifEmpty { "(empty)" }}\n</current_harness_state>\n\n<conversation>\n${clip(transcript(c.messages, 30), 30000)}\n</conversation>"
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", AUTO_REFINE_REVIEW_SYSTEM_PROMPT)).put(JSONObject().put("role", "user").put("content", user))
            val r = Llm.chat(msgs, null, Cfg.fastModel.ifEmpty { Cfg.model }, cancel = cancel, maxTokens = 1200, stream = false)
            val j = firstJson(r.content.ifEmpty { r.reasoning }) ?: JSONObject()
            go = j.opt("shouldRefine") == true; global = j.opt("global") == true; instructions = s(j, "instructions")
        }
        if (reason.startsWith("standing_instruction") || reason.startsWith("harness_intervention")) global = true
        if (!go && (reason.startsWith("standing_instruction") || reason.startsWith("user_correction"))) {
            go = true
            if (instructions.isEmpty()) instructions = "The user corrected yaver or gave a standing instruction. Capture the lesson as a short, concrete prompt note (what to do differently next time), global if it applies to future conversations."
        }
        if (!go) return null
        val rec = refine(c.messages, c.id, instructions, global, if (reason.isNotEmpty()) "learn" else "auto", cancel)
        if (arr(rec.optJSONArray("applied")).any { it.optBoolean("ok") }) LearnEvents.emit("refine", refineCard(rec, c.id))
        return rec
    }

    /** Snapshot copy so background learning never reads a conversation the agent is still writing. */
    internal fun snapshot(c: Convo): Convo = Convo.fromJson(JSONObject(c.toJson().toString()))

    /** Background checkpoint (also call it when a non-cancelled turn ended with an error). */
    fun checkpoint(c: Convo, reason: String? = null) {
        val copy = snapshot(c)
        val why = reason ?: signal(copy)
        Y.bg {
            try { autoCheck(copy, why, null) } catch (e: CancelledException) {
            } catch (e: Throwable) {
                Diag.e("learn", "auto refine", e)
                LearnEvents.emit("note", JSONObject().put("text", "✨ " + Y.t("learning step skipped: ", "öğrenme adımı atlandı: ") + (e.message ?: e.toString())).put("convoId", c.id))
            }
        }
    }

    /**
     * Call right after Agent.run finished a user turn (not for internal/background turns).
     * [turnStart] = c.messages.length() before the user message was added. Judges the previous answer from this message
     * (implicit feedback), tags the final answer with the lessons that shaped it (field "lr"), saves the convo and
     * starts the auto-refine checkpoint in the background unless [cancel] was cancelled.
     * Returns the index of the final answer message (for 👍/👎), or -1.
     */
    fun afterTurn(c: Convo, turnStart: Int, userText: String, cancel: Cancel? = null): Int {
        var idx = -1
        try { Learn.outcome(c, signal(c)) } catch (e: Throwable) { Diag.e("learn", "outcome", e) }
        try { idx = Learn.tag(c, turnStart, userText) } catch (e: Throwable) { Diag.e("learn", "tag", e) }
        try { Convos.save(c, touch = false) } catch (_: Throwable) {}
        if (cancel?.cancelled != true) checkpoint(c)
        return idx
    }

    // ---------------- events / UI data ----------------

    private fun kindLabel(k: String) = when (k) { "prompt" -> Y.t("working note", "çalışma notu"); "memory" -> Y.t("memory", "hafıza"); "skill" -> Y.t("skill", "yetenek"); "subagent" -> Y.t("sub-agent", "alt ajan"); else -> k }

    /** Data for the "yaver learned something" card (event "refine"). */
    fun refineCard(rec: JSONObject, convoId: String?): JSONObject {
        val ok = arr(rec.optJSONArray("applied")).filter { it.optBoolean("ok") }
        val bad = arr(rec.optJSONArray("applied")).filter { !it.optBoolean("ok") }
        val rb = s(rec, "rollbackOf").isNotEmpty()
        val items = JSONArray()
        for (a in ok) {
            val after = a.optJSONObject("after")
            items.put(JSONObject().put("kind", s(a, "kind")).put("kindLabel", kindLabel(s(a, "kind"))).put("action", s(a, "action")).put("id", s(a, "id"))
                .put("text", if (after != null) ": " + s(after, "title") else if (s(a, "action") == "delete") Y.t(" removed", " kaldırıldı") else "")
                .put("detail", if (after != null && s(a, "kind") == "prompt") clip(s(after, "content"), 220) else ""))
        }
        return JSONObject().put("convoId", nul(convoId)).put("id", s(rec, "id")).put("rollbackOf", nul(rec.opt("rollbackOf").takeIf { rb }))
            .put("title", if (rb) Y.t("Lesson rolled back", "Ders geri alındı") else Y.t("yaver learned something", "yaver bir ders çıkardı"))
            .put("scope", s(rec, "scope")).put("scopeLabel", if (s(rec, "scope") == "global") Y.t("all conversations", "tüm konuşmalarda") else Y.t("this conversation", "bu konuşmada"))
            .put("summary", s(rec, "summary")).put("items", items)
            .put("empty", if (ok.isEmpty()) Y.t("No lasting lesson in this conversation.", "Bu konuşmadan kalıcı bir ders çıkmadı.") + (if (s(rec, "rationale").isNotEmpty()) " " + s(rec, "rationale") else "") else "")
            .put("rejected", if (bad.isNotEmpty()) "Rejected: " + bad.joinToString("; ") { "${s(it, "action")} ${s(it, "kind")}:${s(it, "id")} (${s(it, "error")})" } else "")
            .put("expected", if (s(rec, "expectedOutcome").isNotEmpty() && ok.isNotEmpty()) "Expected: " + s(rec, "expectedOutcome") else "")
            .put("canUndo", ok.isNotEmpty() && !rb).put("undoLabel", Y.t("Undo", "Geri al"))
    }

    /** "Undo" on a refine card: rolls back and emits the rollback card. */
    fun undo(recId: String, convoId: String?): JSONObject {
        val r = rollback(recId, convoId)
        LearnEvents.emit("refine", refineCard(r, convoId))
        return r
    }

    /** Lessons screen: stats, entries (with labels), standing rules with counters, recent failures, sleep passes. */
    fun screen(convoId: String? = null, filter: String = "all", q: String = ""): JSONObject {
        val st = load(); val fq = LearnText.fold(q)
        val all = all(st)
        val titles = HashMap<String, String>(); try { for (m in Convos.list()) titles[m.optString("id")] = m.optString("title") } catch (_: Throwable) {}
        val srcL = mapOf("learn" to Y.t("after a correction", "bir düzeltmeden sonra"), "auto" to Y.t("periodic review", "periyodik gözden geçirme"), "manual" to "/refine", "sleep" to Y.t("sleep pass", "uyku aşaması"), "tidy" to Y.t("sleep pass", "uyku aşaması"))
        val list = all.filter { fq.isEmpty() || LearnText.fold(s(it, "title") + " " + s(it, "content")).contains(fq) }
            .filter { if (filter !in listOf("global", "local", "suspended")) true else if (filter == "suspended") s(it, "status") == "suspended" else s(it, "scope") == filter }
            .sortedWith { a, b ->
                val sa = if (s(a, "status") == "suspended") 1 else 0; val sb = if (s(b, "status") == "suspended") 1 else 0
                if (sa != sb) sa - sb else {
                    val ga = if (s(a, "scope") == "global") 1 else 0; val gb = if (s(b, "scope") == "global") 1 else 0
                    if (ga != gb) gb - ga else byScore(a, b)
                }
            }
        val act = all.filter { s(it, "status") != "suspended" }
        val vH = all.sumOf { it.optInt("helpful", 0) }; val vB = all.sumOf { it.optInt("harmful", 0) }
        val entries = JSONArray()
        for (e in list) {
            val o = JSONObject(e.toString())
            val k = s(e, "kind")
            o.put("key", "$k:${s(e, "id")}").put("kindLabel", when (k) { "prompt" -> Y.t("Working note", "Çalışma notu"); "memory" -> Y.t("Memory", "Hafıza"); "skill" -> Y.t("Skill", "Yetenek"); "subagent" -> Y.t("Sub-agent", "Alt ajan"); else -> k })
                .put("scopeLabel", if (s(e, "scope") == "global") Y.t("every conversation", "tüm konuşmalarda") else Y.t("one conversation", "tek konuşma"))
                .put("sourceLabel", srcL[s(e, "source")] ?: s(e, "source").ifEmpty { "—" }).put("suspended", s(e, "status") == "suspended")
                .put("current", sessOf(e) != null && sessOf(e) == convoId)
            sessOf(e)?.let { sid -> titles[sid]?.let { o.put("convoTitle", it) } }
            entries.put(o)
        }
        val rules = JSONArray()
        for (r in Learn.ruleRefs()) { val m = Learn.ruleMeta(r.first, r.second); rules.put(JSONObject().put("target", r.first).put("text", r.second).put("helpful", m.optInt("helpful", 0)).put("harmful", m.optInt("harmful", 0))) }
        val fails = JSONArray(); for (f in Learn.failures().take(6)) fails.put(f)
        val sl = Learn.sleepLog(); val slog = JSONArray(); for (l in sl.take(5)) slog.put(l)
        return JSONObject()
            .put("stats", JSONObject().put("active", act.size).put("activeGlobal", act.count { s(it, "scope") == "global" }).put("suspended", all.size - act.size)
                .put("helpful", vH).put("harmful", vB).put("ratio", if (vH + vB > 0) vH.toDouble() / (vH + vB) else JSONObject.NULL)
                .put("lastSleep", if (sl.isNotEmpty()) sl[0].optLong("at") else JSONObject.NULL))
            .put("explanation", Y.t("What yaver learned from experience: working notes, memories, skills and sub-agent specs written after your corrections, 👍/👎 and periodic reviews. Each lesson counts how often it helped (✓) or led to a wrong answer (✗); one that keeps hurting is suspended automatically. While yaver is idle, a daily sleep pass merges duplicates, removes failed lessons and turns repeated mistakes into new rules.",
                "yaver'in deneyimden öğrendikleri: düzeltmeleriniz, 👍/👎 ve periyodik gözden geçirmeler sonrasında yazılan çalışma notları, hafıza kayıtları, yetenekler ve alt ajan tanımları. Her ders kaç kez işe yaradığını (✓) ve kaç kez yanlış yanıta yol açtığını (✗) sayar; sürekli zarar veren ders kendiliğinden askıya alınır. yaver boştayken günde bir kez çalışan uyku aşaması tekrarları birleştirir, başarısız dersleri kaldırır, tekrarlanan hataları yeni kurala çevirir."))
            .put("empty", if (q.isNotEmpty()) Y.t("No lesson matches your search.", "Aramanıza uyan ders yok.") else Y.t("No lessons yet. They appear after corrections, 👎 feedback or standing instructions like 'from now on…'.", "Henüz ders yok. Düzeltmelerinizden, 👎 geri bildiriminden veya 'bundan sonra…' gibi kalıcı talimatlardan sonra burada görünür."))
            .put("entries", entries).put("rules", rules).put("failures", fails).put("sleepLog", slog)
            .put("learn", LearnSettings.learn).put("every", every())
    }

    /** All entries (raw JSON), for simple lists. */
    fun list(convoId: String? = null): JSONArray { val st = load(); val a = JSONArray(); for (e in if (convoId == null) all(st) else visible(st, convoId)) a.put(e); return a }

    /** Suspend (on=false) or switch back on with a fresh trial (on=true). */
    fun setEnabled(kind: String, id: String, on: Boolean): Boolean = synchronized(lock) {
        val st = load(); val x = entries(st, kind).optJSONObject(id) ?: return false
        if (on) { x.put("status", "active"); x.put("harmful", minOf(x.optInt("harmful", 0), x.optInt("helpful", 0))); x.put("revived", nowIso()) }
        else { x.put("status", "suspended"); x.put("suspendedAt", System.currentTimeMillis()) }
        save(st); true
    }

    /** Deletes a lesson (undoable with rollback). {ok, error?, message} */
    fun delete(kind: String, id: String): JSONObject {
        val e = entries(load(), kind).optJSONObject(id) ?: return JSONObject().put("ok", false).put("error", "no $kind entry with id $id")
        val r = apply(JSONObject().put("summary", Y.t("Deleted by you", "Sizin tarafınızdan silindi")).put("edits", JSONArray().put(JSONObject().put("action", "delete").put("kind", kind).put("id", id))), s(e, "scope"), sessOf(e), "manual")
        val a = r.getJSONArray("applied").getJSONObject(0)
        return if (a.optBoolean("ok")) JSONObject().put("ok", true).put("message", Y.t("Lesson deleted", "Ders silindi")).put("recId", s(r, "id")) else JSONObject().put("ok", false).put("error", s(a, "error"))
    }

    /** Edits a lesson's title and text. {ok, error?} */
    fun edit(kind: String, id: String, title: String, content: String): JSONObject {
        val t = title.trim(); val c = content.trim()
        if (t.isEmpty() || c.isEmpty()) return JSONObject().put("ok", false).put("error", Y.t("Title and text are required", "Başlık ve metin gerekli"))
        val e = entries(load(), kind).optJSONObject(id) ?: return JSONObject().put("ok", false).put("error", "no $kind entry with id $id")
        val r = apply(JSONObject().put("summary", Y.t("Edited by you", "Sizin tarafınızdan düzenlendi")).put("edits", JSONArray().put(JSONObject().put("action", "update").put("kind", kind).put("id", id).put("title", t).put("content", c))), s(e, "scope"), sessOf(e), "manual")
        val a = r.getJSONArray("applied").getJSONObject(0)
        return if (a.optBoolean("ok")) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("error", s(a, "error"))
    }

    fun every(): Int = load().getJSONObject("auto").optInt("every", 0)

    /** Settings screen: "Learn from experience — review every N turns". off → every = 0. */
    fun setAuto(on: Boolean, every: Int) {
        LearnSettings.learn = on
        synchronized(lock) { val st = load(); st.getJSONObject("auto").put("every", if (on) every.coerceIn(1, 50) else 0); save(st) }
    }

    /** When a conversation is deleted: drop the harness notes that live only inside it. */
    fun forgetConvo(convoId: String): Int = synchronized(lock) {
        val st = load(); var n = 0
        for (k in KINDS) { val es = entries(st, k); for (e in objs(es)) if (sessOf(e) == convoId && s(e, "scope") != "global") { es.remove(s(e, "id")); n++ } }
        if (n > 0) save(st); n
    }

    /** Subagent spec for delegate_task(spec=…): the entry by id or title. Throws with the saved ids when missing. */
    fun subagentSpec(spec: String): JSONObject {
        val es = entries(load(), "subagent")
        return es.optJSONObject(spec) ?: objs(es).firstOrNull { s(it, "title") == spec }
            ?: throw IllegalArgumentException("no subagent spec \"$spec\". Saved specs: ${objs(es).joinToString(", ") { s(it, "id") }.ifEmpty { "none" }}")
    }

    /**
     * Slash commands "/harness" and "/refine [history | rollback [id] | auto N|off | [--global] instructions]".
     * Returns a note to show, or null when [text] is not one of these commands. Blocking for refine — call off the main thread.
     */
    fun command(c: Convo, text: String, busy: Boolean = false, cancel: Cancel? = null): String? {
        val m = Regex("^/(\\w+)\\s*([\\s\\S]*)$").find(text.trim()) ?: return null
        val cmd = m.groupValues[1]; val rest = m.groupValues[2].trim()
        if (cmd == "harness") return "✨ " + overview(c.id)
        if (cmd != "refine") return null
        if (rest == "history") { val h = arr(load().getJSONArray("history")).takeLast(10); return "✨ " + (if (h.isNotEmpty()) h.joinToString(" · ") { "[${s(it, "id")}] ${s(it, "trigger")} · ${s(it, "summary")}" } else "No refinements yet.") }
        Regex("^rollback\\s*(\\S*)$").find(rest)?.let { rb ->
            return try { val r = rollback(rb.groupValues[1].ifEmpty { "last" }, c.id); LearnEvents.emit("refine", refineCard(r, c.id)); "Rolled back ${s(r, "rollbackOf")}." } catch (e: Throwable) { e.message ?: e.toString() }
        }
        Regex("^auto\\s+(\\S+)$").find(rest)?.let { au ->
            val v = au.groupValues[1]
            val n = if (v.equals("off", true)) 0 else maxOf(1, Regex("^\\d+").find(v)?.value?.toIntOrNull() ?: 0)
            synchronized(lock) { val st = load(); st.getJSONObject("auto").put("every", n); save(st) }
            return if (n > 0) "Auto-refine checkpoint every $n turns" else "Auto-refine off"
        }
        if (busy) return "Wait for the current task to finish, or ask yaver to call the harness tool"
        val gl = Regex("(^|\\s)--global\\b")
        val global = gl.containsMatchIn(rest); val instructions = rest.replaceFirst(gl, "").trim()
        return try {
            val r = refine(c.messages, c.id, instructions, global, "manual", cancel)
            LearnEvents.emit("refine", refineCard(r, c.id))
            "✨ Refining the continual harness" + (if (global) " (global)" else "") + "… " + s(r, "summary")
        } catch (e: CancelledException) { throw e } catch (e: Throwable) { "Refine failed: " + (e.message ?: e.toString()) }
    }

    // ---------------- tool ----------------

    fun register() {
        Tools.add(Tool(
            "harness",
            "The continual harness: persistent prompt notes, memories, skills and subagent specs that improve yaver across turns and conversations. Actions: overview (show entries and recent refinements), refine (review this conversation and apply small evidence-backed edits; use after a repeated failure, a user correction, or when a reusable tactic or delegation role emerges), rollback (undo a refinement by id, or 'last'), history.",
            P.props(
                "action" to P.s("overview | refine | rollback | history", "overview", "refine", "rollback", "history"),
                "instructions" to P.s("For refine: what to focus on"),
                "global" to P.b("For refine: store stable cross-conversation lessons globally (default: local to this conversation)"),
                "id" to P.s("For rollback: refinement id or 'last'")
            ),
            listOf("action")
        ) { a, ctx ->
            val c = ctx.convo
            when (a.str("action")) {
                "overview" -> overview(c.id)
                "history" -> historyText(15)
                "rollback" -> {
                    val r = rollback(a.str("id").ifEmpty { "last" }, c.id)
                    LearnEvents.emit("refine", refineCard(r, c.id))
                    val n = r.getJSONArray("applied").length()
                    "Rolled back ${s(r, "rollbackOf")}: $n entr${if (n == 1) "y" else "ies"} restored."
                }
                else -> {
                    ctx.progress(Y.t("Refining the continual harness…", "Sürekli öğrenme durumu gözden geçiriliyor…"))
                    val r = refine(c.messages, c.id, a.str("instructions"), a.optBoolean("global", false), "agent", ctx.cancel)
                    LearnEvents.emit("refine", refineCard(r, c.id))
                    "Refinement ${s(r, "id")}: ${s(r, "summary")}\n" + arr(r.optJSONArray("applied")).joinToString("\n") { "${if (it.optBoolean("ok")) "applied" else "failed (" + s(it, "error") + ")"} ${s(it, "action")} ${s(it, "kind")}:${s(it, "id")}" }.ifEmpty { "no edits" } +
                        "\nExpected outcome: ${s(r, "expectedOutcome")}"
                }
            }
        })
    }
}
