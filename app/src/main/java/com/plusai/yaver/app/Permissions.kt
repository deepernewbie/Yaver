package com.plusai.yaver.app

import org.json.JSONObject

/**
 * Permission presets (port of the original's PERMISSIONS / hasSideEffect / execTool, all.js 2841-2873):
 *  - ask:      every change or side effect asks the user first (work tools — mail, calendar, task board — show their own cards)
 *  - auto:     risky actions go through the smart-approval gate (System-1 or the LLM safety check), then the user
 *  - full:     no approvals, except actions that are always confirmed (sending mail, invitations…) and fixed block rules
 *  - readonly: nothing on the disk, the phone or the web is changed
 * The decision is pure (testable on the JVM); the agent loop applies it before each tool runs.
 */
object Permissions {
    val MODES = listOf("ask", "auto", "full", "readonly")

    /** Labels for the settings sheet and /permissions. */
    fun label(m: String) = when (m) {
        "ask" -> Y.t("Ask first", "Önce sor"); "full" -> Y.t("Full auto", "Tam otomatik"); "readonly" -> Y.t("Read-only", "Salt okunur")
        else -> Y.t("Auto", "Otomatik")
    }
    fun help(m: String) = when (m) {
        "ask" -> Y.t("Every change or side effect asks you first", "Her değişiklik veya yan etki önce size sorulur")
        "full" -> Y.t("No approvals, except sending mail and invitations and fixed block rules", "Onay yok; e-posta/davet gönderimi ve sabit engel kuralları hariç")
        "readonly" -> Y.t("Nothing on the disk, the phone or the web is changed", "Diskte, telefonda veya web'de hiçbir şey değiştirilmez")
        else -> Y.t("Risky actions go through the safety check (System-1 or the model), then you", "Riskli işlemler güvenlik kontrolünden (System-1 veya model), sonra sizden geçer")
    }

    /** The stored preset. A phone that had "Ask before actions" switched on explicitly keeps asking. */
    var mode: String
        get() {
            val v = Prefs.get("cfg.permissions", "")
            if (v in MODES) return v
            // the older switch "Ask before actions": only an explicit choice carries over
            return when (Prefs.get("cfg.askRisky", "")) { "1" -> "ask"; else -> "auto" }
        }
        set(v) { Prefs.put("cfg.permissions", if (v in MODES) v else "auto") }

    /** Every side effect of the original's SIDE_EFFECT_TOOLS plus the phone port's own writers. */
    val SIDE_EFFECT_TOOLS = setOf(
        "write_file", "patch", "skill_manage", "memory", "unpack_archive", "pack_archive", "browser_download",
        "delete_file", "export_file", "download_file", "write_document", "page_images", "open_in_browser", "phone_notify", "phone_open"
    )
    /** Tools whose actions are read-only except the ones listed here. */
    private val ACTION_WRITES = mapOf(
        "mail" to setOf("draft", "send", "send_now", "mark_read", "delete", "move", "archive", "empty_folder"),
        "calendar" to setOf("create", "respond", "cancel"),
        "task_board" to setOf("add", "update", "close", "reopen", "add_note", "edit_note", "delete_note", "add_person", "delete"),
        "memory" to setOf("add", "replace", "remove"),
        "heartbeat" to setOf("create", "pause", "resume", "delete"),
        "subagents" to setOf("message", "stop", "delete"),
        "harness" to setOf("refine", "rollback"),
        "folder" to setOf("sync", "deletes", "disconnect"),
        "phone_calendar" to setOf("create", "delete"),
        "phone_alarm" to setOf("alarm", "timer"),
        "phone_reminder" to setOf("add", "cancel"),
        "schedule_task" to setOf("add", "cancel"),
        "phone_clipboard" to setOf("set"),
        "goal" to setOf("set", "update", "complete", "block", "pause", "resume", "clear")
    )
    /** Tools that change something by themselves and show their own approval card (the original's WorkOK). */
    val WORK_TOOLS = setOf("task_board", "mail", "calendar")

    private val SHELL_WRITE = Regex("(^|[;&|]\\s*)(rm|mv|cp|mkdir|touch|rmdir|unzip|tar|gunzip|zip|curl|wget|python3?|pip3?|node|sed\\s+-i)\\b|>")
    private val PY_WRITE = Regex("open\\([^)]*['\"][wax+]|\\.to_\\w+\\(|savefig|\\.save\\(|\\bos\\.|shutil|pathlib|write_text|write_bytes|pyfetch|micropip|requests\\.|urllib")
    private val JS_WRITE = Regex("fs\\.writeFile|fs\\.(rm|unlink|mkdir|rename)|tools\\.|fetch\\(")

    fun hasSideEffect(name: String, args: JSONObject): Boolean {
        val action = args.optString("action")
        ACTION_WRITES[name]?.let { w -> return if (name == "memory" && action.isEmpty()) true else action in w }
        if (name in SIDE_EFFECT_TOOLS) return true
        return when (name) {
            "terminal" -> SHELL_WRITE.containsMatchIn(args.optString("command"))
            "execute_code", "run_code" -> {
                val lang = args.optString("language").ifEmpty { "python" }
                val code = args.optString("code")
                if (lang.startsWith("py")) PY_WRITE.containsMatchIn(code) else JS_WRITE.containsMatchIn(code)
            }
            "fetch", "http_request" -> args.optString("method").let { it.isNotEmpty() && !it.equals("GET", true) && !it.equals("HEAD", true) }
            "run_html" -> args.has("save") || args.optString("path").isNotEmpty() && args.optString("html").isNotEmpty()
            "delegate_task", "browser_use" -> false
            else -> false
        }
    }

    /** What the agent loop does before running a tool. */
    sealed class Decision {
        object Run : Decision()
        class Refuse(val message: String) : Decision()
        class Ask(val title: String, val details: String) : Decision()
    }

    const val READONLY_MSG = "Not executed: permissions are set to read-only. Tell the user what you would change instead."

    fun decide(mode: String, name: String, args: JSONObject): Decision {
        val side = hasSideEffect(name, args)
        if (!side) return Decision.Run
        return when (mode) {
            "readonly" -> Decision.Refuse(READONLY_MSG)
            "ask" -> if (name in WORK_TOOLS) Decision.Run else Decision.Ask(Y.t("Allow ", "İzin ver: ") + name + "?", summary(args))
            else -> Decision.Run
        }
    }

    /** The approval card's details: one line per argument, long values clipped. */
    fun summary(args: JSONObject): String = args.keys().asSequence().joinToString("\n") { k ->
        val v = args.opt(k); "$k: " + clip((v?.toString() ?: "").replace("\n", " ⏎ "), 300)
    }

    /** /permissions [mode] */
    fun command(rest: String): String {
        val r = rest.trim().lowercase().replace("read-only", "readonly").replace("full-auto", "full")
        if (r.isEmpty()) return "🛡 " + Y.t("Permissions: ", "İzinler: ") + label(mode) + " — " + help(mode) + "\n" +
            MODES.joinToString("\n") { "• /permissions $it — ${label(it)}: ${help(it)}" }
        if (r !in MODES) return Y.t("Unknown preset \"$r\". Use ask | auto | full | readonly.", "Bilinmeyen ayar \"$r\". ask | auto | full | readonly kullanın.")
        mode = r
        Diag.i("permissions", "set to $r")
        return "🛡 " + Y.t("Permissions set to ", "İzinler şu ayara alındı: ") + label(r) + " — " + help(r)
    }
}
