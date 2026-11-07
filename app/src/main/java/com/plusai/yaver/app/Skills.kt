package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Skills: reusable procedures stored as /.yaver/skills/<name>/SKILL.md (with --- name/description --- frontmatter) plus
 * supporting files (scripts/, templates/, references/ …). The folder is part of the virtual disk (VPath), so a skill's
 * scripts run from their paths with execute_code or the terminal, exactly as in the original.
 */
object Skills {
    const val GUIDANCE = "When you work out a non-trivial workflow, record it with skill_manage for future reuse. If a listed skill matches the task, load it with skill_view before starting."

    /** [status]: "active", "trial" (learned from one successful task, waiting for the user's "Keep" — hidden from the agent; L51) or "disabled" (hidden from the agent).
     *  [source]: "learned" for skills yaver drafted itself (L51), "" otherwise. */
    class Skill(val name: String, val dir: String, val description: String, val file: File, val files: List<String>, val status: String = "active", val source: String = "")

    /** Called after a skill is created, patched or deleted (the UI may refresh its skills list). */
    @Volatile var onChange: (() -> Unit)? = null

    val root: File get() = File(Y.app.filesDir, "skills")

    fun parse(md: String): Pair<Map<String, String>, String> {
        val m = Regex("^---\\n([\\s\\S]*?)\\n---\\n?").find(md)
        val fm = LinkedHashMap<String, String>()
        if (m != null) for (l in m.groupValues[1].split("\n")) {
            val k = Regex("^(\\w[\\w-]*):\\s*(.*)$").find(l) ?: continue
            fm[k.groupValues[1]] = k.groupValues[2].replace(Regex("^[\"']|[\"']$"), "")
        }
        return Pair(fm, if (m != null) md.substring(m.value.length) else md)
    }

    private fun walk(d: File): List<String> =
        d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path }.sorted().toList()

    /** Every skill, disabled ones included (skills screen). */
    fun listAll(): List<Skill> {
        val r = root
        if (!r.isDirectory) return emptyList()
        return (r.listFiles() ?: emptyArray()).filter { it.isDirectory && File(it, "SKILL.md").isFile }.sortedBy { it.name }.map { d ->
            val f = File(d, "SKILL.md")
            val (fm, _) = parse(try { f.readText() } catch (_: Exception) { "" })
            val st = (fm["status"] ?: "").trim().lowercase().let { if (it == "trial" || it == "disabled") it else "active" }
            Skill(fm["name"]?.ifBlank { null } ?: d.name, d.name, fm["description"] ?: "", f, walk(d), st, (fm["source"] ?: "").trim())
        }
    }

    /** The skills the agent may use: trial skills (learned, not yet kept by the user) and disabled ones are left out —
     *  never listed, auto-loaded by System-1 or loadable with skill_view until the user taps "Keep" (L51.confirm). */
    fun list(): List<Skill> = listAll().filter { it.status == "active" }

    fun get(name: String): Skill {
        val l = list()
        return l.find { it.name == name || it.dir == name }
            ?: throw IllegalArgumentException("skill \"$name\" not found. Available: ${l.joinToString(", ") { it.name }.ifEmpty { "none" }}")
    }

    /** Block for the system prompt ("" when there are no skills). */
    fun index(): String {
        val l = list()
        if (l.isEmpty()) return ""
        return "## Skills (load with skill_view before doing a matching task)\n" + l.joinToString("\n") { "- ${it.name}: ${it.description}" } + "\n\n"
    }

    /** For a settings/skills screen (all skills; "status": active|trial|disabled, "source": learned|""). */
    fun listJson(): JSONArray = JSONArray(listAll().map {
        JSONObject().put("name", it.name).put("dir", it.dir).put("path", vdir(it)).put("description", it.description).put("files", JSONArray(it.files))
            .put("status", it.status).put("source", it.source)
    })

    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("^-|-$"), "")

    private fun inside(base: File, rel: String): File {
        val f = File(base, rel.replace(Regex("^/+"), "")).canonicalFile
        if (!f.path.startsWith(base.canonicalPath + File.separator) && f != base.canonicalFile) throw IllegalArgumentException("path is outside the skill folder: $rel")
        return f
    }

    private fun shown(f: File) = VPath.SKILLS + "/" + f.canonicalPath.removePrefix(root.canonicalPath).trimStart('/')

    /** The virtual folder of a skill. */
    fun vdir(s: Skill) = VPath.SKILLS + "/" + s.dir

    /** What skill_view adds after SKILL.md: where the skill lives and its files, as paths the other tools take. */
    fun footer(s: Skill): String {
        val others = s.files.filter { it != "SKILL.md" }
        return "\n\n---\n[Skill folder: ${vdir(s)}/" + (if (others.isNotEmpty()) " — files: " + others.take(60).joinToString(", ") { "${vdir(s)}/$it" } + (if (others.size > 60) " … (${others.size} files)" else "") else "") +
            ". These are real paths on the disk: read them with read_file, run scripts with execute_code (Python: exec(open(path).read()); JavaScript: fs.readFile(path)) or the terminal (python ${vdir(s)}/scripts/x.py, node …, cp, zip), and write results to /workspace.]"
    }

    fun register() {
        Tools.add(Tool("skills_list", "List skills with descriptions.", P.props(), emptyList()) { _, _ ->
            list().joinToString("\n") { "- ${it.name}: ${it.description} (${vdir(it)}/, ${it.files.size} files)" }.ifEmpty { "(no skills yet)" }
        })

        Tools.add(Tool(
            "skill_view", "Load a skill's SKILL.md (or one of its files) before doing a task it covers.",
            P.props("name" to P.s("Skill name"), "file" to P.s("Optional file inside the skill, e.g. references/x.md")), listOf("name")
        ) { a, _ ->
            val s = get(a.str("name"))
            val rel = a.str("file").removePrefix(vdir(s) + "/").removePrefix("skills/${s.dir}/")
            val f = if (rel.isNotEmpty()) inside(s.file.parentFile!!, rel) else s.file
            if (!f.isFile) throw IllegalArgumentException("not found: ${a.str("file")} (files of ${s.name}: ${s.files.joinToString(", ")})")
            if (rel.isEmpty()) f.readText() + footer(s)
            else if (Workspace.isText(f)) f.readText()
            else "${shown(f)} is a binary file (${Workspace.fmtBytes(f.length())}) — use it by its path, e.g. in execute_code or the terminal."
        })

        Tools.add(Tool(
            "skill_manage",
            "Create or improve a skill: the procedure for a class of task, done the way THIS user wants, with pitfalls. Actions: create (full SKILL.md with --- name/description --- frontmatter), patch (old_string/new_string), write_file (supporting file), delete.",
            P.props(
                "action" to P.s("create | patch | write_file | delete", "create", "patch", "write_file", "delete"), "name" to P.s("kebab-case skill name"),
                "content" to P.s("SKILL.md content (create) or file content (write_file)"), "old_string" to P.s("for patch"), "new_string" to P.s("for patch"),
                "file" to P.s("for write_file, e.g. references/notes.md")
            ),
            listOf("action", "name")
        ) { a, ctx ->
            val slug = slug(a.optString("name"))
            if (slug.isEmpty()) throw IllegalArgumentException("name is required")
            val dir = File(root, slug)
            val md = File(dir, "SKILL.md")
            when (a.str("action")) {
                "create" -> {
                    var content = a.optString("content", "")
                    if (!Regex("^---\\n[\\s\\S]*?name:[\\s\\S]*?description:[\\s\\S]*?\\n---").containsMatchIn(content))
                        content = "---\nname: $slug\ndescription: ${content.split("\n")[0].take(150)}\n---\n" + content
                    dir.mkdirs(); md.writeText(content); onChange?.invoke()
                    "Created skill $slug at ${shown(md)}"
                }
                "patch" -> {
                    if (!md.isFile) throw IllegalArgumentException("skill \"$slug\" not found")
                    val t = md.readText()
                    val old = a.optString("old_string", "")
                    if (old.isEmpty() || !t.contains(old)) throw IllegalArgumentException("old_string not found in SKILL.md")
                    val i = t.indexOf(old)
                    md.writeText(t.substring(0, i) + a.optString("new_string", "") + t.substring(i + old.length)); onChange?.invoke()
                    "Patched ${shown(md)}"
                }
                "write_file" -> {
                    if (a.str("file").isEmpty()) throw IllegalArgumentException("file required")
                    dir.mkdirs()
                    val f = inside(dir, a.str("file"))
                    f.parentFile?.mkdirs(); f.writeText(a.optString("content", "")); onChange?.invoke()
                    "Wrote ${shown(f)}"
                }
                "delete" -> {
                    if (!dir.exists()) throw IllegalArgumentException("skill \"$slug\" not found")
                    if (!ctx.approve(Y.t("Delete skill", "Yeteneği sil") + ": " + slug, listOf(Y.t("Reason", "Neden") to Y.t("deletes a skill", "bir yeteneği siler")), danger = true)) return@Tool ctx.denied("delete skill $slug")
                    dir.deleteRecursively(); try { L51.forgetSkill(slug) } catch (_: Throwable) {}; onChange?.invoke()
                    "Deleted skill $slug"
                }
                else -> throw IllegalArgumentException("unknown action")
            }
        })
    }
}
