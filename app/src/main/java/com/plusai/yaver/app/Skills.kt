package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Skills: reusable procedures stored as skills/<name>/SKILL.md (with --- name/description --- frontmatter) plus supporting files. */
object Skills {
    const val GUIDANCE = "When you work out a non-trivial workflow, record it with skill_manage for future reuse. If a listed skill matches the task, load it with skill_view before starting."

    class Skill(val name: String, val dir: String, val description: String, val file: File, val files: List<String>)

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

    fun list(): List<Skill> {
        val r = root
        if (!r.isDirectory) return emptyList()
        return (r.listFiles() ?: emptyArray()).filter { it.isDirectory && File(it, "SKILL.md").isFile }.sortedBy { it.name }.map { d ->
            val f = File(d, "SKILL.md")
            val (fm, _) = parse(try { f.readText() } catch (_: Exception) { "" })
            Skill(fm["name"]?.ifBlank { null } ?: d.name, d.name, fm["description"] ?: "", f, walk(d))
        }
    }

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

    /** For a settings/skills screen. */
    fun listJson(): JSONArray = JSONArray(list().map {
        JSONObject().put("name", it.name).put("dir", it.dir).put("description", it.description).put("files", JSONArray(it.files))
    })

    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("^-|-$"), "")

    private fun inside(base: File, rel: String): File {
        val f = File(base, rel.replace(Regex("^/+"), "")).canonicalFile
        if (!f.path.startsWith(base.canonicalPath + File.separator) && f != base.canonicalFile) throw IllegalArgumentException("path is outside the skill folder: $rel")
        return f
    }

    private fun shown(f: File) = "skills/" + f.canonicalPath.removePrefix(root.canonicalPath).trimStart('/')

    fun register() {
        Tools.add(Tool("skills_list", "List skills with descriptions.", P.props(), emptyList()) { _, _ ->
            list().joinToString("\n") { "- ${it.name}: ${it.description} (${it.files.size} files)" }.ifEmpty { "(no skills yet)" }
        })

        Tools.add(Tool(
            "skill_view", "Load a skill's SKILL.md (or one of its files) before doing a task it covers.",
            P.props("name" to P.s("Skill name"), "file" to P.s("Optional file inside the skill, e.g. references/x.md")), listOf("name")
        ) { a, _ ->
            val s = get(a.str("name"))
            val f = if (a.str("file").isNotEmpty()) inside(s.file.parentFile!!, a.str("file")) else s.file
            if (!f.isFile) throw IllegalArgumentException("not found: ${a.str("file")}")
            f.readText()
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
                    f.parentFile?.mkdirs(); f.writeText(a.optString("content", ""))
                    "Wrote ${shown(f)}"
                }
                "delete" -> {
                    if (!dir.exists()) throw IllegalArgumentException("skill \"$slug\" not found")
                    if (!ctx.approve(Y.t("Delete skill", "Yeteneği sil") + ": " + slug, listOf(Y.t("Reason", "Neden") to Y.t("deletes a skill", "bir yeteneği siler")), danger = true)) return@Tool ctx.denied("delete skill $slug")
                    dir.deleteRecursively(); onChange?.invoke()
                    "Deleted skill $slug"
                }
                else -> throw IllegalArgumentException("unknown action")
            }
        })
    }
}
