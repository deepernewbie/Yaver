package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The skills editor of the Knowledge page (port of the original's KB.skillDetail / newSkill, all.js 313-356):
 * view a skill (rendered SKILL.md, files, usage), edit name/description/body, duplicate, delete, add files.
 * Files inside a skill are opened and edited with the Files screen (files.read / files.write) by their path.
 * Also counts skill_view calls (sort by last use) and seeds the original's "web-research" skill on first run.
 */
object SkillsUi {
    private fun root(): File = VPath.skillsDir().apply { mkdirs() }

    private fun dirOf(name: String): File {
        val d = name.trim().trim('/')
        if (d.isEmpty() || d.contains('/') || d == "." || d == "..") throw IllegalArgumentException(Y.t("Not a valid skill", "Geçerli bir yetenek değil"))
        return File(root(), d)
    }

    fun slug(name: String): String {
        val s = name.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[çÇ]"), "c").replace(Regex("[ğĞ]"), "g").replace(Regex("[ıİ]"), "i").replace(Regex("[öÖ]"), "o").replace(Regex("[şŞ]"), "s").replace(Regex("[üÜ]"), "u")
            .replace("i̇", "i").replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-|-$"), "")
        return s.ifEmpty { "skill" }
    }

    // ---------------------------------------------------------------- usage (KB.init wraps skill_view)
    private fun usage(): JSONObject = try { JSONObject(Prefs.get("skills.use", "{}")) } catch (_: Exception) { JSONObject() }

    fun touch(nameOrDir: String) {
        try {
            val s = Skills.list().firstOrNull { it.name == nameOrDir || it.dir == nameOrDir } ?: return
            val u = usage(); val cur = u.optJSONObject(s.dir) ?: JSONObject()
            u.put(s.dir, JSONObject().put("n", cur.optInt("n") + 1).put("last", System.currentTimeMillis()))
            Prefs.put("skills.use", u.toString())
        } catch (_: Throwable) {}
    }

    /** skills.list with usage, newest use first (then by name). */
    fun list(): JSONArray {
        val u = usage()
        val a = Skills.listJson()
        val l = (0 until a.length()).map { a.getJSONObject(it) }.onEach { o -> u.optJSONObject(o.optString("dir"))?.let { o.put("uses", it.optInt("n")).put("last", it.optLong("last")) } }
        return JSONArray(l.sortedWith(compareByDescending<JSONObject> { it.optLong("last") }.thenBy { it.optString("name").lowercase() }))
    }

    fun get(dir: String): JSONObject {
        val d = dirOf(dir)
        val md = File(d, "SKILL.md")
        if (!md.isFile) throw IllegalArgumentException(Y.t("Skill not found: ", "Yetenek bulunamadı: ") + dir)
        val raw = md.readText()
        val (fm, body) = Skills.parse(raw)
        val files = JSONArray()
        d.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(d).path }.forEach { f ->
            val rel = f.relativeTo(d).path.replace(File.separatorChar, '/')
            files.put(JSONObject().put("name", rel).put("path", "${VPath.SKILLS}/${d.name}/$rel").put("size", f.length()).put("text", Workspace.isText(f)))
        }
        val u = usage().optJSONObject(d.name)
        return JSONObject().put("dir", d.name).put("path", "${VPath.SKILLS}/${d.name}").put("name", fm["name"]?.ifBlank { null } ?: d.name)
            .put("description", fm["description"] ?: "").put("body", body.replace(Regex("^\\n+"), "")).put("raw", raw).put("files", files)
            .put("uses", u?.optInt("n") ?: 0).put("last", u?.optLong("last") ?: 0L)
    }

    /** Saves name / description / body; other frontmatter keys are kept. */
    fun save(dir: String, name: String, description: String, body: String): JSONObject {
        val d = dirOf(dir)
        val md = File(d, "SKILL.md")
        val (fm, _) = if (md.isFile) Skills.parse(md.readText()) else Pair(emptyMap<String, String>(), "")
        val extra = fm.filterKeys { it != "name" && it != "description" }.entries.joinToString("") { "${it.key}: ${it.value}\n" }
        d.mkdirs()
        md.writeText("---\nname: ${name.trim().ifEmpty { d.name }.replace("\n", " ")}\ndescription: ${description.trim().replace(Regex("\\s*\\n\\s*"), " ")}\n$extra---\n\n${body.trim()}\n")
        changed("${VPath.SKILLS}/${d.name}/SKILL.md")
        return get(d.name)
    }

    fun create(name: String): JSONObject {
        val n = name.trim()
        if (n.isEmpty()) throw IllegalArgumentException(Y.t("Give the skill a name", "Yeteneğe bir ad verin"))
        val slug = slug(n)
        val d = File(root(), slug)
        if (d.exists()) return get(slug).put("existed", true)
        d.mkdirs()
        File(d, "SKILL.md").writeText("---\nname: $slug\ndescription: \n---\n\n# $n\n\n## ${Y.t("Steps", "Adımlar")}\n1. \n\n## ${Y.t("Pitfalls", "Dikkat")}\n- \n")
        changed("${VPath.SKILLS}/$slug/SKILL.md")
        return get(slug)
    }

    fun copy(dir: String): JSONObject {
        val d = dirOf(dir)
        if (!d.isDirectory) throw IllegalArgumentException(Y.t("Skill not found: ", "Yetenek bulunamadı: ") + dir)
        var n = "${d.name}-copy"; var k = 2
        while (File(root(), n).exists()) n = "${d.name}-copy${k++}"
        val to = File(root(), n)
        d.copyRecursively(to, false)
        val md = File(to, "SKILL.md")
        if (md.isFile) md.writeText(md.readText().replaceFirst(Regex("(?m)^name:.*$"), "name: $n"))
        changed("${VPath.SKILLS}/$n/SKILL.md")
        return get(n)
    }

    fun delete(dir: String): JSONObject {
        val d = dirOf(dir)
        if (!d.isDirectory) throw IllegalArgumentException(Y.t("Skill not found: ", "Yetenek bulunamadı: ") + dir)
        val n = d.walkTopDown().count { it.isFile }
        d.deleteRecursively()
        try { val u = usage(); u.remove(d.name); Prefs.put("skills.use", u.toString()) } catch (_: Throwable) {}
        changed("${VPath.SKILLS}/${d.name}")
        return JSONObject().put("deleted", d.name).put("files", n)
    }

    /** A new (empty) file inside a skill; returns its path for the editor. */
    fun addFile(dir: String, rel0: String): JSONObject {
        val d = dirOf(dir)
        val rel = rel0.trim().replace('\\', '/').trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." || it == "." || it.isEmpty() }) throw IllegalArgumentException(Y.t("Not a valid file name", "Geçerli bir dosya adı değil"))
        val f = File(d, rel).canonicalFile
        if (!f.path.startsWith(d.canonicalPath + File.separator)) throw IllegalArgumentException(Y.t("Not a valid file name", "Geçerli bir dosya adı değil"))
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText("") }
        val vp = "${VPath.SKILLS}/${d.name}/$rel"
        changed(vp)
        return JSONObject().put("path", vp)
    }

    private fun changed(vp: String) { try { VPath.commit(vp) } catch (_: Throwable) {} }

    // ---------------------------------------------------------------- seed skill (SEED_SKILL, all.js 1361)
    const val SEED = """---
name: web-research
description: Research a question on the live web with search, extraction and the browser, then answer with sources.
---
# Web research

0. For a broad question that needs a real review of many sources, call `deep_research` and summarize its report.
1. `web_search` with 2–3 focused queries (not one vague one).
2. `web_extract` the 2–4 most relevant results; prefer primary sources.
3. If a page needs interaction (forms, pagination), use `browser_navigate` → `browser_snapshot` → `browser_find`/`browser_click`, or hand the whole multi-step task to `browser_use`. For JSON APIs use `fetch`.
4. Save long raw material to /workspace/research/<topic>.md with `write_file` so later turns can re-read it.
5. Answer with a short "Sources:" list of the URLs actually used.

Pitfalls
- A page that needs a sign-in or a captcha: hand it to the user with the browser tool instead of looping.
- HTTP 403/429 means rate limiting: wait or try another source, do not loop.
"""

    /** Writes the seed skill once (a new install starts with one skill, as in the original). */
    fun seed() {
        try {
            if (Prefs.on("skills.seeded", false)) return
            Prefs.set("skills.seeded", true)
            val d = File(root(), "web-research")
            if (!d.exists()) { d.mkdirs(); File(d, "SKILL.md").writeText(SEED) }
        } catch (e: Throwable) { Diag.e("skills", "seed", e) }
    }
}
