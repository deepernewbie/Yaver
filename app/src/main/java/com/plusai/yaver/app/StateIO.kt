package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Whole-state export and import (port of the original's exportState / handleZip / applyImport, all.js 4737-4775).
 *
 * The zip has the original's layout, so either app can read the other's:
 *   yaver-state.json        {format:"plusai-yaver", version:1, app, exported, current, config, stats, secrets?}
 *   fs/workspace/…          every file of /workspace, as bytes (binaries included)
 *   fs/.yaver/PERSONA.md, fs/.yaver/skills/…, fs/.yaver/memories/{MEMORY.md, USER.md, meta.json}
 *   fs/.yaver/sessions/<id>.json   every conversation with all messages (tool calls and results)
 *   native/…                the phone's own stores (memory, harness and lessons, task board, conversation state,
 *                            heartbeats, follow-ups) for a full restore on another phone
 * Import shows a preview and offers Merge (the zip's files over the disk; local stores kept, memories and lessons
 * united) or Replace (wipe, then load the snapshot).
 */
object StateIO {
    const val MANIFEST = "yaver-state.json"
    @Volatile var base: () -> File = { Y.app.filesDir }

    /** filesDir sub-paths copied as native/<path> (not caches, not device-bound: indexes, photos, connected folders). */
    val NATIVE = listOf("memory", "harness", "convo-state", "tasks", "memory.json", "trace")
    private val NATIVE_SKIP = listOf("harness/index", "harness/folders.json", "harness/mail-index.json")

    /** Secret prefs that only go out when the user asks (stored decrypted in the export). */
    val SECRET_KEYS = listOf("llmKey", "brave", "tavily", "mailPass", "mailTotp", "s1Key")
    /** Prefs that make no sense on another phone. */
    private val PREF_SKIP = setOf("migrated", "currentConvo")

    var includeKeys: Boolean get() = Prefs.on("cfg.exportKeys", false); set(v) = Prefs.set("cfg.exportKeys", v)

    // ---------------------------------------------------------------- export
    fun config(): JSONObject {
        val prefs = JSONObject()
        for ((k, v) in Prefs.all()) if (!k.startsWith("sec.") && k !in PREF_SKIP && !k.startsWith("stats.")) prefs.put(k, v)
        // the original's CFG shape for the fields both apps have (an HTML yaver can read these)
        val llm = JSONObject().put("baseUrl", Cfg.llmBase).put("model", Cfg.model).put("fastModel", Cfg.fastModel).put("visionModel", Cfg.visionModel)
            .put("temperature", Cfg.temperature).put("maxIterations", Cfg.maxSteps).put("maxTokens", Cfg.maxOut).put("stream", Cfg.stream)
            .put("extraHeaders", LlmX.extraHeadersJson)
        return JSONObject().put("llm", llm).put("web", JSONObject().put("searxng", Cfg.searxng)).put("mail", JSONObject().put("url", Cfg.mailOwa).put("ews", Cfg.mailEws))
            .put("permissions", Permissions.mode).put("uiLang", Cfg.lang).put("native", prefs)
    }

    fun secrets(): JSONObject {
        val o = JSONObject()
        for (k in SECRET_KEYS) { val v = Secure.dec(Prefs.get("sec.$k", "")); if (v.isNotEmpty()) o.put(k, v) }
        return o
    }

    private fun putFile(z: ZipOutputStream, name: String, f: File) {
        val e = ZipEntry(name); e.time = f.lastModified(); z.putNextEntry(e)
        f.inputStream().use { it.copyTo(z, 65536) }; z.closeEntry()
    }
    private fun putBytes(z: ZipOutputStream, name: String, b: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() }

    private fun tree(z: ZipOutputStream, prefix: String, root: File, skip: (String) -> Boolean = { false }): Int {
        if (!root.exists()) return 0
        if (root.isFile) { putFile(z, prefix, root); return 1 }
        var n = 0
        val b = root.canonicalFile
        for (f in b.walkTopDown()) {
            val rel = f.canonicalFile.path.removePrefix(b.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
            if (rel.isEmpty() || skip(rel)) continue
            if (f.isFile) { putFile(z, "$prefix/$rel", f); n++ }
            else if (f.isDirectory && (f.listFiles()?.isEmpty() == true)) { putBytes(z, "$prefix/$rel/.keep", ByteArray(0)) }
        }
        return n
    }

    /** Writes the whole state to [out]. Returns {path, files, sessions, skills, bytes}. */
    fun export(out: File, withKeys: Boolean = includeKeys, memoryText: (String) -> String = { t -> MemoryX.entries(t).joinToString(MemoryX.DELIM) }): JSONObject {
        out.parentFile?.mkdirs()
        var files = 0; var sessions = 0
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            val man = JSONObject().put("format", "plusai-yaver").put("version", 1).put("app", "yaver-android")
                .put("exported", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(java.util.Date()))
                .put("current", Prefs.get("currentConvo", ""))
            try { man.put("config", config()) } catch (e: Throwable) { Diag.e("export", "config", e) }
            try { man.put("stats", Trace.statsJson()) } catch (_: Throwable) {}
            if (withKeys) try { man.put("secrets", secrets()) } catch (e: Throwable) { Diag.e("export", "secrets", e) }
            putBytes(z, MANIFEST, man.toString(2).toByteArray())
            files += tree(z, "fs/workspace", VPath.workspaceDir()) { it.startsWith(Spill.DIR + "/") }
            files += tree(z, "fs/.yaver/skills", VPath.skillsDir())
            try { putBytes(z, "fs/.yaver/PERSONA.md", VPath.personaGet().toByteArray()); files++ } catch (_: Throwable) {}
            for (t in listOf("memory" to "MEMORY.md", "user" to "USER.md")) try { putBytes(z, "fs/.yaver/memories/${t.second}", memoryText(t.first).toByteArray()); files++ } catch (_: Throwable) {}
            File(base(), "memory/meta.json").takeIf { it.isFile }?.let { putFile(z, "fs/.yaver/memories/meta.json", it); files++ }
            val cd = VPath.sessionsDir()
            cd.listFiles()?.filter { it.isFile && it.name.endsWith(".json") && it.name != "index.json" }?.forEach { f ->
                // the conversation plus its harness state (todos, goal, plan mode, toolsets) as the original keeps them in the session
                try {
                    val j = JSONObject(f.readText())
                    File(base(), "convo-state/" + f.name).takeIf { it.isFile }?.let { s -> try { j.put("state", JSONObject(s.readText())) } catch (_: Exception) {} }
                    putBytes(z, "fs/.yaver/sessions/${f.name}", j.toString().toByteArray()); sessions++
                } catch (e: Exception) { putFile(z, "fs/.yaver/sessions/${f.name}", f); sessions++ }
            }
            for (p in NATIVE) tree(z, "native/$p", File(base(), p)) { rel -> NATIVE_SKIP.any { s -> "$p/$rel".startsWith(s) } }
        }
        val skills = VPath.skillsDir().listFiles()?.count { File(it, "SKILL.md").isFile } ?: 0
        Diag.i("export", "state export: $files files, $sessions sessions, $skills skills, ${out.length()} bytes")
        return JSONObject().put("file", out.path).put("files", files).put("sessions", sessions).put("skills", skills).put("bytes", out.length()).put("keys", withKeys)
    }

    // ---------------------------------------------------------------- import
    fun safe(name: String): String? {
        val n = name.replace('\\', '/')
        if (n.startsWith("/") || n.split('/').any { it == ".." }) return null
        return n
    }

    /** What a zip holds, for the preview sheet. */
    fun preview(zip: File): JSONObject {
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            val manE = z.getEntry(MANIFEST)
            val man = manE?.let { try { JSONObject(String(z.getInputStream(it).readBytes())) } catch (_: Exception) { null } }
            val fs = names.filter { it.startsWith("fs/") && !it.endsWith("/.keep") }
            return JSONObject().put("state", man != null).put("app", man?.optString("app")?.ifEmpty { "html" } ?: "")
                .put("exported", man?.optString("exported") ?: "").put("files", fs.size)
                .put("workspace", fs.count { it.startsWith("fs/workspace/") })
                .put("sessions", fs.count { it.startsWith("fs/.yaver/sessions/") && it.endsWith(".json") })
                .put("skills", fs.count { Regex("^fs/\\.yaver/skills/[^/]+/SKILL\\.md$").matches(it) })
                .put("native", names.any { it.startsWith("native/") }).put("secrets", man?.optJSONObject("secrets")?.length()?.let { it > 0 } ?: false)
                .put("entries", names.size).put("name", zip.name)
        }
    }

    private fun wipe(f: File) { f.listFiles()?.forEach { it.deleteRecursively() } }

    /** Converts a session of either app to a phone conversation (all messages kept; the original's "_" fields dropped). */
    fun convoFrom(j: JSONObject): Convo? {
        val msgs = j.optJSONArray("messages") ?: return null
        val out = JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role !in setOf("user", "assistant", "tool")) continue
            val o = JSONObject(m.toString())
            for (k in o.keys().asSequence().toList()) if (k.startsWith("_")) o.remove(k)
            if (role == "user" && !o.has("display") && o.opt("content") is String && !Regex("^\\[(harness|goal round|heartbeat)").containsMatchIn(o.optString("content"))) o.put("display", o.optString("content"))
            out.put(o)
        }
        if (out.length() == 0) return null
        val id = j.optString("id").let { if (Regex("^[A-Za-z0-9_-]{2,64}$").matches(it)) it else Convo.create().id }
        val now = System.currentTimeMillis()
        return Convo(id, j.optString("title", "yaver").ifBlank { "yaver" }, j.optLong("created", now), j.optLong("updated", now), out,
            j.optString("summary", ""), j.optInt("summaryUpTo", 0), j.optBoolean("background", false), j.optString("system", ""),
            if (j.isNull("lang")) null else j.optString("lang", "").ifEmpty { null })
    }

    class Hooks(
        val saveConvo: (Convo) -> Unit = { Convos.save(it, touch = false) },
        val memory: (target: String, entries: List<String>, replace: Boolean) -> Unit = { t, l, r -> MemoryX.save(t, if (r) l else (MemoryX.entries(t) + l).distinct()) },
        val memoryMeta: (String) -> Unit = { MemoryX.importMeta(it) },
        val persona: (String) -> Unit = { VPath.personaSet(it) },
        val applyConfig: (JSONObject, JSONObject?, Boolean) -> List<String> = { c, s, r -> StateIO.applyConfig(c, s, r) }
    )

    /**
     * Loads a zip. [mode] "merge" or "replace". Returns {message, restart}: restart = the phone's own stores were
     * replaced and yaver must start again to load them.
     */
    fun apply(zip: File, mode: String, hooks: Hooks = Hooks()): JSONObject {
        val replace = mode == "replace"
        val done = ArrayList<String>()
        var restart = false
        ZipFile(zip).use { z ->
            val man = z.getEntry(MANIFEST)?.let { try { JSONObject(String(z.getInputStream(it).readBytes())) } catch (_: Exception) { null } }
            val entries = z.entries().asSequence().filter { !it.isDirectory }.toList()
            if (man == null && entries.none { it.name.startsWith("fs/") }) throw IllegalArgumentException(Y.t("This is not a yaver export", "Bu bir yaver dışa aktarımı değil"))
            val ws = VPath.workspaceDir().apply { mkdirs() }; val sk = VPath.skillsDir().apply { mkdirs() }
            val hasNative = entries.any { it.name.startsWith("native/") }
            if (replace) {
                wipe(ws); wipe(sk)
                VPath.sessionsDir().listFiles()?.forEach { it.delete() }
                if (hasNative) for (p in NATIVE) { val f = File(base(), p); if (f.isDirectory) f.listFiles()?.forEach { c -> if (NATIVE_SKIP.none { s -> "$p/${c.name}".startsWith(s) }) c.deleteRecursively() } else f.delete() }
            }
            var nFiles = 0; var nSessions = 0; var nSkills = 0
            val memText = HashMap<String, String>()
            for (e in entries) {
                val name = safe(e.name) ?: continue
                fun bytes() = z.getInputStream(e).use { it.readBytes() }
                when {
                    name.endsWith("/.keep") -> {
                        val d = name.removeSuffix("/.keep")
                        when { d.startsWith("fs/workspace/") -> File(ws, d.removePrefix("fs/workspace/")).mkdirs(); d.startsWith("fs/.yaver/skills/") -> File(sk, d.removePrefix("fs/.yaver/skills/")).mkdirs() }
                    }
                    name.startsWith("fs/workspace/") -> { val f = File(ws, name.removePrefix("fs/workspace/")); f.parentFile?.mkdirs(); f.writeBytes(bytes()); nFiles++ }
                    name.startsWith("fs/.yaver/skills/") -> { val f = File(sk, name.removePrefix("fs/.yaver/skills/")); f.parentFile?.mkdirs(); f.writeBytes(bytes()); if (f.name == "SKILL.md") nSkills++ }
                    name == "fs/.yaver/PERSONA.md" -> { val t = String(bytes()); if (t.isNotBlank() && !t.contains("running entirely inside the user's web browser")) try { hooks.persona(t) } catch (_: Throwable) {} }
                    name.startsWith("fs/.yaver/memories/") -> memText[name.removePrefix("fs/.yaver/memories/")] = String(bytes())
                    name.startsWith("fs/.yaver/sessions/") && name.endsWith(".json") -> try {
                        val j = JSONObject(String(bytes()))
                        val c = convoFrom(j)
                        if (c != null) {
                            hooks.saveConvo(c); nSessions++
                            // todos, goal, plan mode, toolsets: the phone's own copy (native/convo-state) wins when it is in the zip
                            j.optJSONObject("state")?.let { s -> val f = File(base(), "convo-state/${c.id}.json"); if (replace || !f.exists()) { f.parentFile?.mkdirs(); f.writeText(s.toString()) } }
                            ?: run {
                                // the original keeps them on the session
                                val s = JSONObject(); for (k in listOf("todos", "goal", "planMode", "toolsets")) if (j.has(k) && !j.isNull(k)) s.put(k, j.get(k))
                                if (s.length() > 0) { val f = File(base(), "convo-state/${c.id}.json"); if (replace || !f.exists()) { f.parentFile?.mkdirs(); f.writeText(s.toString()) } }
                            }
                        }
                    } catch (ex: Exception) { Diag.e("import", "session ${e.name}", ex) }
                    name.startsWith("native/") -> {
                        val rel = name.removePrefix("native/")
                        if (NATIVE.none { rel == it || rel.startsWith("$it/") } || NATIVE_SKIP.any { rel.startsWith(it) }) continue
                        // the stores of memory and lessons are merged through their own code below; the rest: replace, or fill in what is missing
                        if (!replace && (rel.startsWith("memory/") || rel == "harness/state.json")) continue
                        val f = File(base(), rel)
                        if (replace || !f.exists()) { f.parentFile?.mkdirs(); f.writeBytes(bytes()); restart = true }
                    }
                }
            }
            // memory: a full restore copies the phone's store (native/memory); otherwise the entries are united
            val nativeMem = entries.any { it.name.startsWith("native/memory/") }
            if (!(replace && nativeMem)) {
                for ((file, target) in listOf("USER.md" to "user", "MEMORY.md" to "memory")) memText[file]?.let { t ->
                    val l = t.split(Regex("\\n?§\\n?")).map { it.trim() }.filter { it.isNotEmpty() }
                    if (l.isNotEmpty()) try { hooks.memory(target, l, replace); done.add(Y.t("${l.size} ${if (target == "user") "facts about you" else "memory notes"}", "${l.size} ${if (target == "user") "kişisel bilgi" else "hafıza notu"}")) } catch (ex: Throwable) { Diag.e("import", "memory", ex) }
                }
                memText["meta.json"]?.let { try { hooks.memoryMeta(it) } catch (_: Throwable) {} }
            } else done.add(Y.t("memory", "hafıza"))
            if (nFiles > 0) done.add(Y.t("$nFiles files", "$nFiles dosya"))
            if (nSkills > 0) done.add(Y.t("$nSkills skills", "$nSkills yetenek"))
            if (nSessions > 0) done.add(Y.t("$nSessions conversations", "$nSessions sohbet"))
            if (man != null) man.optJSONObject("config")?.let { c -> try { done.addAll(hooks.applyConfig(c, man.optJSONObject("secrets"), replace)) } catch (ex: Throwable) { Diag.e("import", "config", ex) } }
            if (replace && man?.optJSONObject("stats") != null) try { Prefs.put("stats.llm", man.getJSONObject("stats").toString()) } catch (_: Throwable) {}
            if (hasNative && restart) done.add(Y.t("lessons, task board and chat state", "dersler, görev panosu ve sohbet durumu"))
            Diag.i("import", "state import ($mode): " + done.joinToString(", "))
        }
        val msg = if (done.isEmpty()) Y.t("Nothing new to import.", "İçe alınacak yeni bir şey yok.") else Y.t("Imported: ", "İçe alındı: ") + done.joinToString(", ")
        return JSONObject().put("message", msg).put("restart", restart)
    }

    /** Settings from an export of either app. A merge fills in only what is not set here; a replace takes everything. */
    fun applyConfig(c: JSONObject, sec: JSONObject?, replace: Boolean): List<String> {
        val out = ArrayList<String>()
        val nat = c.optJSONObject("native")
        if (nat != null) {
            for (k in nat.keys()) if (replace || Prefs.get(k, "").isEmpty()) Prefs.put(k, nat.optString(k))
            out.add(Y.t("settings", "ayarlar"))
        } else {
            c.optJSONObject("llm")?.let { l ->
                if (replace || Cfg.model.isEmpty()) {
                    l.optString("baseUrl").takeIf { it.startsWith("http") }?.let { Cfg.llmBase = it }
                    l.optString("model").takeIf { it.isNotEmpty() }?.let { Cfg.model = it }
                    l.optString("fastModel").takeIf { it.isNotEmpty() }?.let { Cfg.fastModel = it }
                    l.optString("visionModel").takeIf { it.isNotEmpty() }?.let { Cfg.visionModel = it }
                    if (l.has("temperature")) Cfg.temperature = l.optDouble("temperature", 0.3).coerceIn(0.0, 2.0)
                    if (l.optInt("maxIterations", 0) > 0) Cfg.maxSteps = l.optInt("maxIterations").coerceIn(3, 100)
                    when (val x = l.opt("extraHeaders")) { is JSONObject -> try { LlmX.extraHeadersJson = x.toString() } catch (_: Throwable) {}; is String -> if (x.isNotBlank()) try { LlmX.extraHeadersJson = x } catch (_: Throwable) {} }
                    out.add(Y.t("model", "model"))
                }
            }
            c.optJSONObject("mail")?.let { m ->
                val bad = Regex("company\\.com", RegexOption.IGNORE_CASE)
                m.optString("url").takeIf { it.startsWith("http") && !bad.containsMatchIn(it) && (replace || Cfg.mailOwa.isEmpty()) }?.let { Cfg.mailOwa = it; out.add("Outlook web") }
                m.optString("ews").takeIf { it.startsWith("http") && !bad.containsMatchIn(it) && (replace || Cfg.mailEws.isEmpty()) }?.let { Cfg.mailEws = it }
            }
            c.optJSONObject("web")?.optString("searxng")?.takeIf { it.startsWith("http") }?.let { Cfg.searxng = it }
            c.optString("permissions").takeIf { it in Permissions.MODES }?.let { if (replace || Prefs.get("cfg.permissions", "").isEmpty()) Permissions.mode = it }
        }
        if (sec != null && sec.length() > 0) {
            var n = 0
            for (k in sec.keys()) {
                val v = sec.optString(k); if (v.isEmpty()) continue
                val key = if (k == "braveKey") "brave" else if (k == "tavilyKey") "tavily" else k
                if (key !in SECRET_KEYS) continue
                if (replace || Secure.dec(Prefs.get("sec.$key", "")).isEmpty()) { Prefs.put("sec.$key", Secure.enc(if (key == "mailTotp") Totp.secretFrom(v) else v)); n++ }
            }
            if (n > 0) out.add(Y.t("$n keys", "$n anahtar"))
        }
        return out
    }

    // ---------------------------------------------------------------- staged files (the picker hands over a content:// address)
    private val staged = java.util.concurrent.ConcurrentHashMap<String, File>()

    fun stage(uri: android.net.Uri): JSONObject {
        val dir = File(Y.app.cacheDir, "import").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000) it.delete() }
        val tok = "z" + System.currentTimeMillis().toString(36)
        val f = File(dir, "$tok.zip")
        (Y.app.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("cannot open the file")).use { i -> f.outputStream().use { i.copyTo(it, 65536) } }
        staged[tok] = f
        return try { preview(f).put("token", tok) } catch (e: Exception) { f.delete(); staged.remove(tok); throw IllegalArgumentException(Y.t("This is not a zip file", "Bu bir zip dosyası değil")) }
    }

    /** A zip already on the disk (an attachment in /workspace/uploads). */
    fun stagePath(vp: String): JSONObject {
        val f = VPath.file(VPath.normTool(vp))
        if (!f.isFile) throw IllegalArgumentException("not found: $vp")
        val tok = "p" + System.currentTimeMillis().toString(36)
        staged[tok] = f
        return preview(f).put("token", tok)
    }

    fun applyStaged(token: String, mode: String): JSONObject {
        val f = staged[token] ?: throw IllegalArgumentException(Y.t("Pick the file again", "Dosyayı yeniden seçin"))
        val r = apply(f, mode)
        if (token.startsWith("z")) { staged.remove(token); f.delete() }
        try { Workspace.changed(null) } catch (_: Throwable) {}
        try { Skills.onChange?.invoke() } catch (_: Throwable) {}
        return r
    }

    /** Extracts a plain zip next to itself (the original's drop of a zip that is not a state export). */
    fun extract(vp: String): JSONObject {
        val src = VPath.file(VPath.normTool(vp))
        val dirVp = VPath.normTool(vp).removeSuffix(".zip").removeSuffix(".ZIP")
        val dir = VPath.fileW(dirVp).apply { mkdirs() }
        var n = 0
        ZipFile(src).use { z ->
            for (e in z.entries()) {
                val rel = safe(e.name) ?: continue
                val f = File(dir, rel)
                if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs(); z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it, 65536) } }; n++
            }
        }
        try { VPath.commit(dirVp) } catch (_: Throwable) {}
        return JSONObject().put("dir", dirVp).put("files", n)
    }

    /** Starts yaver again (after a full restore, so every store loads from the restored files). */
    fun restart() {
        val c = Y.app
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        val pi = android.app.PendingIntent.getActivity(c, 7, android.content.Intent.makeRestartActivityTask(i.component), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT)
        (c.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 600, pi)
        Y.main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }

    /** Erase everything (P3-13): the disk, the stores, settings and secrets, cookies. */
    fun eraseAll() {
        wipe(VPath.workspaceDir()); wipe(VPath.skillsDir()); VPath.sessionsDir().listFiles()?.forEach { it.deleteRecursively() }
        for (p in NATIVE + listOf("changes", "photos", "vfs", "harness")) File(base(), p).deleteRecursively()
        for (k in Prefs.all().keys) Prefs.remove(k)
        try { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.CookieManager.getInstance().flush() } catch (_: Throwable) {}
        Diag.i("erase", "everything erased")
    }
}
