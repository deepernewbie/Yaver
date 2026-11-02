package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * deep_research — breadth × depth recursion (dzhng/deep-research) with a research brief and a cited final report
 * (langchain open_deep_research). A System-1 director may decide "enough?" and rank follow-ups; without it an LLM
 * reflection step does.
 */
object Research {
    /** Language name for the report ("Turkish"…), or null. Default: the fixed app language, else a Turkish-letter check. */
    @Volatile var langName: (String) -> String? = { q ->
        when (Cfg.lang) {
            "tr" -> "Turkish"; "en" -> "English"
            else -> if (Regex("[çğıöşüÇĞİÖŞÜ]").containsMatchIn(q)) "Turkish" else null
        }
    }

    /**
     * System-1 research gate (Gates engineer): (brief, recent learnings, candidate follow-ups, keep) →
     * Pair(stop, chosen queries) or null when System-1 is off/unsure (then the LLM director decides).
     */
    @Volatile var s1Director: ((String, List<String>, List<String>, Int) -> Pair<Boolean, List<String>?>?)? = null

    private class Learning(val text: String, val src: List<Int>)
    private class Src(val n: Int, val title: String)
    private class Page(val url: String, val title: String, val text: String)

    fun slugify(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60).ifEmpty { "research" }

    /** Runs [tasks] with at most [n] at a time; rethrows the first failure. */
    private fun pool(tasks: List<() -> Unit>, n: Int, cancel: Cancel) {
        val q = ConcurrentLinkedQueue(tasks)
        val k = minOf(n, tasks.size)
        if (k == 0) return
        val done = CountDownLatch(k)
        val err = arrayOfNulls<Throwable>(1)
        repeat(k) {
            Y.io.execute {
                try { while (true) { if (cancel.cancelled || err[0] != null) break; val t = q.poll() ?: break; t() } }
                catch (e: Throwable) { synchronized(err) { if (err[0] == null) err[0] = e } }
                finally { done.countDown() }
            }
        }
        done.await()
        cancel.check()
        err[0]?.let { throw it }
    }

    private fun json(sys: String, user: String, cancel: Cancel): JSONObject {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
        val r = Llm.chat(msgs, null, Cfg.fastModel.ifEmpty { Cfg.model }, cancel = cancel, stream = false)
        val m = Regex("\\{[\\s\\S]*\\}").find(r.content.ifEmpty { r.reasoning }) ?: return JSONObject()
        return try { JSONObject(m.value) } catch (_: Throwable) { JSONObject() }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }

    fun deepResearch(query: String, breadth0: Int, depth0: Int, saveTo: String, cancel: Cancel, say: (String) -> Unit): String {
        val breadth = (if (breadth0 <= 0) 3 else breadth0).coerceIn(1, 6)
        val depth = (if (depth0 <= 0) 2 else depth0).coerceIn(1, 4)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lang = langName(query)
        val inLang = if (lang != null && lang != "English") " Write it in $lang." else ""
        say("Planning · breadth $breadth · depth $depth")
        val plan = json(
            "You are the lead of a research project. Today is $today. Reply with JSON only: {\"brief\": \"a precise research brief: the question, scope, and what a complete answer must cover\", \"queries\": [\"search query\", ...]}. Each query explores a different angle and is specific enough for a web search engine." +
                if (lang != null && lang != "English") " Write the brief in $lang; queries may be in $lang or English, whichever finds better sources." else "",
            "Topic: $query\nGive $breadth queries.", cancel
        )
        val brief = plan.optString("brief").ifBlank { query }
        var queries = strings(plan.optJSONArray("queries")).ifEmpty { listOf(query) }.take(breadth)
        val learnings = ArrayList<Learning>()
        val sources = LinkedHashMap<String, Src>()
        val seen = HashSet<String>()
        val lock = Any()
        for (level in 0 until depth) {
            val followups = ArrayList<String>()
            pool(queries.map { q -> {
                val key = q.lowercase().trim()
                val fresh = synchronized(lock) { seen.add(key) }
                if (fresh && !cancel.cancelled) {
                    say("Search: $q")
                    val hits = try { Web.search(q, 6, cancel).second } catch (e: CancelledException) { throw e } catch (e: Throwable) { say("  search failed: ${e.message}"); null }
                    if (hits != null) {
                        val pages = ArrayList<Page>()
                        for (hit in hits.filter { h -> synchronized(lock) { !sources.containsKey(h.url) } }.take(3)) {
                            cancel.check()
                            try {
                                val rt = Web.read(hit.url, cancel)
                                if (rt.text.length > 200) { val t = rt.title.ifBlank { hit.title }; pages.add(Page(hit.url, t, clip(rt.text, 5000))); say("  read: ${t.take(80)}") }
                            } catch (e: CancelledException) { throw e } catch (e: Throwable) { say("  skipped ${hit.url} (${(e.message ?: "").take(60)})") }
                        }
                        if (pages.isEmpty()) pages.addAll(hits.take(5).map { Page(it.url, it.title, it.snippet) })
                        val numbered = synchronized(lock) {
                            for (p in pages) if (!sources.containsKey(p.url)) sources[p.url] = Src(sources.size + 1, p.title)
                            pages.joinToString("\n\n---\n\n") { p -> "[${sources.getValue(p.url).n}] ${p.title} (${p.url})\n${p.text}" }
                        }
                        val ex = json(
                            "You extract research learnings from sources. Reply with JSON only: {\"learnings\": [{\"text\": \"one dense, specific finding with names, numbers and dates\", \"sources\": [source numbers]}], \"followups\": [\"a follow-up web search query\"]}. At most ${breadth + 2} learnings and $breadth follow-ups. Use only what the sources state.",
                            "Research brief: $brief\nQuery: $q\n\n$numbered", cancel
                        )
                        val got = ArrayList<Learning>()
                        val la = ex.optJSONArray("learnings") ?: JSONArray()
                        for (i in 0 until la.length()) {
                            val l = la.optJSONObject(i) ?: continue
                            val t = l.optString("text"); if (t.isBlank()) continue
                            val sa = l.optJSONArray("sources") ?: JSONArray()
                            got.add(Learning(t, (0 until sa.length()).mapNotNull { sa.opt(it)?.toString()?.toDoubleOrNull()?.toInt() }.filter { it > 0 }))
                        }
                        val total = synchronized(lock) { learnings.addAll(got); followups.addAll(strings(ex.optJSONArray("followups"))); learnings.size }
                        say("  +${got.size} learnings (total $total)")
                    }
                }
            } }, 2, cancel)
            if (level == depth - 1 || followups.isEmpty()) break
            val cands = followups.distinct().filter { it.lowercase().trim() !in seen }.take(12)
            if (cands.isEmpty()) break
            val keep = maxOf(1, (breadth + 1) / 2)
            var stop = false
            var chosen: List<String>? = null
            s1Director?.let { d ->
                try { d(brief, learnings.takeLast(15).map { it.text }, cands, keep)?.let { r -> stop = r.first; if (!stop) chosen = r.second?.take(keep) } }
                catch (e: CancelledException) { throw e } catch (e: Throwable) { Diag.e("research", "s1", e) }
            }
            if (!stop && chosen == null) {
                val d = json(
                    "You direct a research project. Reply with JSON only: {\"done\": true or false, \"queries\": [\"...\"]}. Set done to true only if the learnings already answer the brief thoroughly. Otherwise choose up to $keep of the most valuable next queries (you may rewrite them).",
                    "Brief: $brief\n\nLearnings so far:\n${learnings.joinToString("\n") { "- " + it.text }}\n\nCandidate follow-ups:\n${cands.joinToString("\n")}", cancel
                )
                stop = d.opt("done") == true
                chosen = strings(d.optJSONArray("queries")).ifEmpty { cands }.take(keep)
            }
            if (stop) { say("Enough material, stopping early"); break }
            queries = chosen ?: cands.take(keep)
            say("Going deeper (level ${level + 2}) with ${queries.size} direction${if (queries.size > 1) "s" else ""}")
        }
        if (learnings.isEmpty()) return "Deep research found no usable sources for \"$query\". Web search may be blocked or rate-limited right now; try again later, or set up SearXNG, Brave or Tavily search in Settings."
        say("Writing the report from ${learnings.size} learnings and ${sources.size} sources")
        val srcList = sources.entries.joinToString("\n") { (u, s) -> "[${s.n}] ${s.title} — $u" }
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Write a thorough, well-structured markdown research report that answers the brief. Today is $today. Start with a short summary, then sections with headings. Cite sources inline as [n] with the numbers given; never invent sources or facts. Note disagreements between sources and open questions. End with a \"## Sources\" section listing the cited sources exactly as given.$inLang"))
            .put(JSONObject().put("role", "user").put("content", "Brief: $brief\n\nLearnings:\n${learnings.joinToString("\n") { l -> "- ${l.text} ${l.src.joinToString("") { "[$it]" }}" }}\n\nSources:\n$srcList"))
        val rep = Llm.chat(msgs, null, Cfg.model, cancel = cancel, stream = false)
        val content = rep.content.ifEmpty { rep.reasoning }
        val f = Workspace.resolve(saveTo.ifBlank { "research/${slugify(query)}.md" })
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "Deep research complete: ${learnings.size} learnings from ${sources.size} sources across ${seen.size} searches. Report saved to ${Workspace.rel(f)}.\n\n${clip(content, 7000)}"
    }

    fun register() {
        Tools.add(Tool(
            "deep_research",
            "Run a multi-step web research project on a question: plans a brief, searches several angles in parallel, reads sources, follows up on promising leads for several rounds, and writes a cited markdown report to a file. Takes minutes and many requests; use it for questions that need a real literature/web review, not for quick lookups.",
            P.props(
                "query" to P.s("The research question, with any scope or constraints"), "breadth" to P.n("Parallel search angles per round, 1–6 (default 3)"),
                "depth" to P.n("Research rounds, 1–4 (default 2)"), "save_to" to P.s("Report path (default research/<topic>.md in the workspace)")
            ), listOf("query")
        ) { a, ctx -> deepResearch(a.str("query"), a.int("breadth", 3), a.int("depth", 2), a.str("save_to"), ctx.cancel) { ctx.progress(it) } })
    }
}

/* ---------- Side questions (/btw): answered from the conversation, without tools, not added to it ---------- */
object SideQuestion {
    const val INSTRUCTION = "The user asked this via `/btw` — a temporary side thread cloned from the main conversation to answer a question without interrupting the main work. Tools are deactivated in this side thread; answer using only the conversation context above. Nothing here is added to the main session, so don't start or plan main-session work from this thread."

    /** System prompt for the side thread (sysFor(session)); the Prompt engineer may replace it. */
    @Volatile var system: (Convo) -> String = { c -> Prompt.forRequest(c, false) }
    /** Language note for the question (LANG.note(LANG.detect(question) || session.lang)). */
    @Volatile var langNote: (Convo, String) -> String = { c, _ -> Turns.langNote(c.id) }

    /** Last [n] messages in API form, with tool rows kept only next to their calls (fixAlternation). */
    fun recent(c: Convo, n: Int): JSONArray {
        val rows = (maxOf(0, c.messages.length() - n) until c.messages.length()).map { c.messages.getJSONObject(it) }.filter { !it.optBoolean("local") }
        val results = rows.filter { it.optString("role") == "tool" }.map { it.optString("tool_call_id") }.toSet()
        val out = JSONArray()
        val open = HashSet<String>()
        for (m in rows) when (m.optString("role")) {
            "user" -> { open.clear(); out.put(JSONObject().put("role", "user").put("content", m.optString("content"))) }
            "assistant" -> {
                open.clear()
                val o = JSONObject().put("role", "assistant").put("content", m.optString("content", ""))
                val tc = m.optJSONArray("tool_calls")
                if (tc != null && tc.length() > 0 && (0 until tc.length()).all { tc.getJSONObject(it).optString("id") in results }) {
                    o.put("tool_calls", tc); for (k in 0 until tc.length()) open.add(tc.getJSONObject(k).optString("id"))
                } else if (o.optString("content").isBlank()) continue
                out.put(o)
            }
            "tool" -> if (m.optString("tool_call_id") in open) out.put(JSONObject().put("role", "tool").put("tool_call_id", m.optString("tool_call_id")).put("content", clip(m.optString("content"), 8000)))
        }
        return out
    }

    fun ask(convoId: String, question: String, cancel: Cancel? = null): String {
        val c = Convos.load(convoId) ?: throw IllegalArgumentException("no such conversation")
        val hist = recent(c, 30)
        while (hist.length() > 0 && hist.getJSONObject(hist.length() - 1).optString("role") == "user") hist.remove(hist.length() - 1)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system(c)))
        for (i in 0 until hist.length()) msgs.put(hist.get(i))
        val ln = langNote(c, question)
        msgs.put(JSONObject().put("role", "user").put("content", "<side_question>\n$INSTRUCTION\n\n$question\n</side_question>" + if (ln.isNotBlank()) "\n\n$ln" else ""))
        val r = Llm.chat(msgs, null, Cfg.fastModel.ifEmpty { Cfg.model }, cancel = cancel, stream = false)
        return r.content.ifEmpty { "(no answer)" }
    }

    /** /btw <question>: answers asynchronously; the answer arrives as a "btw" Bridge event (UI.sideCard / sideAnswer). */
    fun command(convoId: String, rest: String): String? {
        val q = rest.trim()
        if (q.isEmpty()) return Y.t("Use /btw <question>", "Kullanım: /btw <soru>")
        val id = System.currentTimeMillis()
        Bridge.emit("btw", JSONObject().put("convoId", convoId).put("id", id).put("question", q).put("phase", "start"))
        Y.bg {
            val ev = JSONObject().put("convoId", convoId).put("id", id).put("question", q).put("phase", "answer")
            try { ev.put("answer", ask(convoId, q)) } catch (e: Throwable) { ev.put("answer", "Error: " + (e.message ?: e.toString())).put("error", true) }
            Bridge.emit("btw", ev)
        }
        return null
    }
}

/* ---------- Long inputs become variables: the text is saved in the workspace and the model works on it with code ---------- */
object LongInput {
    const val CHARS = 24000

    class Stashed(val path: String, val content: String)

    /** For a user message longer than [CHARS]: saves it and returns the short content to send instead. */
    fun stash(text: String): Stashed {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
        val p = Workspace.write("inputs", "input-$ts.txt", text.toByteArray())
        val lines = text.split("\n").size
        val name = p.substringAfterLast('/')
        return Stashed(p, "${text.take(1500)}\n\n…[The user's message is long (${String.format("%,d", text.length)} characters, ${String.format("%,d", lines)} lines), so it was stored as a variable instead of being placed in your context. Full text: $p. In run_code pass files=[\"$p\"] and open it by name (Python: `text = open(\"$name\").read()`); slice, search and parse it with code. read_file also works on it (use offset/limit).]")
    }

    fun maybe(text: String): Stashed? = if (text.length > CHARS) try { stash(text) } catch (e: Throwable) { Diag.e("longinput", "stash", e); null } else null
}
