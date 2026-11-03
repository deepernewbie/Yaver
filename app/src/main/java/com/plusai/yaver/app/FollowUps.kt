package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Follow-up suggestions: after each finished turn, the 4 most likely next requests as buttons ("Next steps"). */
object FollowUps {
    /** Setting: show next-step suggestions (CFG.llm.followups in the original). */
    var enabled: Boolean get() = Prefs.on("followups.on", true); set(v) = Prefs.set("followups.on", v)

    private val file: File get() = File(File(Y.app.filesDir, "harness").apply { mkdirs() }, "followups.json")
    private val lock = Any()

    private fun all(): JSONObject = synchronized(lock) { try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (_: Exception) { JSONObject() } }
    private fun store(j: JSONObject) = synchronized(lock) { try { file.writeText(j.toString()) } catch (_: Throwable) {} }

    /** Saved suggestions for a conversation (to re-render them when it is opened again). */
    fun get(convoId: String): List<String> {
        val a = all().optJSONObject(convoId)?.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    /** Call when a new turn starts (and when the user hides the row: "Hide suggestions"). */
    fun clear(convoId: String) { synchronized(lock) { val j = all(); if (j.has(convoId)) { j.remove(convoId); store(j) } } }

    /** Label strings for the row. */
    fun labels(): JSONObject = JSONObject().put("title", Y.t("Next steps", "Sonraki adımlar")).put("loading", Y.t("Preparing next-step suggestions…", "Sonraki adım önerileri hazırlanıyor…")).put("hide", Y.t("Hide suggestions", "Önerileri gizle"))

    private val WRITE_RX = Regex("write|patch|edit|save|download|pack|create|move|copy", RegexOption.IGNORE_CASE)

    /** Files the turn wrote (path-like arguments of writing tools). */
    fun filesFrom(msgs: List<JSONObject>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in msgs) {
            val tc = m.optJSONArray("tool_calls") ?: continue
            for (k in 0 until tc.length()) {
                val f = tc.optJSONObject(k)?.optJSONObject("function") ?: continue
                if (!WRITE_RX.containsMatchIn(f.optString("name"))) continue
                val a = try { JSONObject(f.optString("arguments", "{}")) } catch (_: Exception) { continue }
                for (key in listOf("path", "save_as", "dest", "to", "out", "output", "file")) {
                    val v = a.opt(key); if (v is String && v.isNotBlank() && !v.contains("://") && !v.contains("@")) out.add(v)
                }
            }
        }
        return out.toList()
    }

    private val TR_RX = Regex("[çğışöüÇĞİŞÖÜ]|\\b(ve|bir|bu|ne|için|icin|nasıl|nasil|mi|mı|bana|benim|yap|lütfen|lutfen)\\b", RegexOption.IGNORE_CASE)

    private fun lang(c: Convo, userText: String): String {
        when (Cfg.lang) { "tr" -> return "Turkish"; "en" -> return "English" }
        LearnHooks.langName?.let { f -> try { return f(c, userText) } catch (_: Throwable) {} }
        return when (Cfg.lang) {
            "tr" -> "Turkish"
            "en" -> "English"
            else -> if (TR_RX.containsMatchIn(userText)) "Turkish" else if (Regex("\\p{L}").containsMatchIn(userText)) "English" else if (Y.tr) "Turkish" else "English"
        }
    }

    /**
     * Asks the fast model for the 4 most likely next requests after the turn that started at [turnStart]
     * (c.messages.length() before the user message). Blocking; run with Y.bg after a finished user turn.
     * Emits "followups" events {convoId, state: loading|ready|none, items} and returns the items (empty when skipped).
     */
    fun suggest(c: Convo, turnStart: Int, userText: String, cancel: Cancel? = null): List<String> {
        if (!enabled || Cfg.model.isEmpty()) return emptyList()
        val turn = (maxOf(0, turnStart) until c.messages.length()).mapNotNull { c.messages.optJSONObject(it) }
        val last = turn.lastOrNull { it.optString("role") == "assistant" && it.optString("content").isNotBlank() && !it.optBoolean("local") } ?: return emptyList()
        val tools = LinkedHashSet<String>()
        for (m in turn) { val tc = m.optJSONArray("tool_calls") ?: continue; for (k in 0 until tc.length()) tc.optJSONObject(k)?.optJSONObject("function")?.optString("name")?.let { if (it.isNotEmpty()) tools.add(it) } }
        val files = filesFrom(turn)
        val language = lang(c, userText)
        Diag.i("followups", "language $language (setting ${Cfg.lang}, chat ${c.lang ?: "-"})")
        LearnEvents.emit("followups", JSONObject().put("convoId", c.id).put("state", "loading").put("text", labels().optString("loading")))
        var items: List<String> = emptyList()
        try {
            val tr = language == "Turkish"
            val sys = if (tr) "Kullanıcının, asistanın aşağıdaki cevabından hemen sonra büyük olasılıkla ne isteyeceğini öner. Tam 4 kısa devam isteği yaz; kullanıcı asistana nasıl yazarsa öyle (emir kipi, en fazla 12 kelime, bu sonuca özgü — gerçek dosyayı, konuyu ya da öğeyi an), her biri asistanın araçlarıyla yapabileceği bir iş olsun (dosyalar, web, kod, posta, takvim, görevler). Birbirinden farklı olsunlar: örneğin daha derine in, düzelt/iyileştir, dışa aktar/paylaş, mantıklı bir sonraki adım. Yapılmış olanı tekrarlama. Hepsini Türkçe yaz. Yalnızca 4 metinlik bir JSON dizisi ver; başka hiçbir şey yazma."
                else "You suggest what the user will most likely ask next, right after the assistant's answer below. Give exactly 4 short follow-up requests, written as the user would type them to the assistant (imperative, at most 12 words, specific to this result — mention the actual file, topic or item), each something the assistant can do with its tools (files, web, code, mail, calendar, tasks). Make them different from each other: e.g. go deeper, fix/improve, export/share, a logical next step. Do not repeat what was already done. Write them in $language. Output only a JSON array of 4 strings, nothing else."
            val user = (if (tr) "Kullanıcının isteği: " else "User asked: ") + clip(userText, 800) +
                (if (tr) "\n\nKullanılan araçlar: " else "\n\nTools used: ") + tools.joinToString(", ").ifEmpty { "-" } +
                (if (files.isNotEmpty()) (if (tr) "\nYazılan dosyalar: " else "\nFiles written: ") + files.take(8).joinToString(", ") else "") +
                (if (tr) "\n\nAsistanın son cevabı:\n" else "\n\nAssistant's final answer:\n") + clip(last.optString("content"), 2500) +
                (if (tr) "\n\nŞimdi yalnızca JSON dizisini ver: [\"…\", \"…\", \"…\", \"…\"] — Türkçe." else "\n\nNow output only the JSON array: [\"…\", \"…\", \"…\", \"…\"] — in $language.")
            val wantCode = when (language) { "Turkish" -> "tr"; "English" -> "en"; else -> null }
            // a list counts only when it parses as JSON, holds real requests and is in the wanted language
            fun parse(t: String): List<String> {
                val clean = t.replace(Regex("```(?:json)?"), "")
                val m = Regex("\\[[\\s\\S]*\\]").find(clean) ?: return emptyList()
                val a = try { JSONArray(m.value) } catch (_: Exception) { return emptyList() }
                val junk = Regex("(?i)^(here (is|are)|işte|translation|çeviri|sure|tabii|json|output|sonuç)\\b|:$")
                return (0 until a.length()).map { a.optString(it).trim().trim('"', '“', '”') }
                    .filter { it.length in 4..160 && !junk.containsMatchIn(it) }.distinct().take(4)
            }
            fun ok(l: List<String>): Boolean {
                if (l.size < 2) return false
                if (wantCode == null) return true
                val got = Lang.detect(l.joinToString(". ")) ?: return true
                return got == wantCode
            }
            val models = listOf(Cfg.fastModel.ifEmpty { Cfg.model }, Cfg.model).distinct()
            loop@ for ((n, model) in models.withIndex()) for (attempt in 0..1) {
                cancel?.check()
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
                if (attempt == 1) msgs.put(JSONObject().put("role", "user").put("content", if (tr) "Önceki cevabın kullanılamadı. Yalnızca Türkçe 4 metinlik JSON dizisini yaz, açıklama ekleme." else "Your previous answer was unusable. Output only the JSON array of 4 strings in $language, no explanation."))
                val r = Llm.chat(msgs, null, model, cancel = cancel, maxTokens = if (attempt == 0) 1500 else 4000, stream = false)
                val l = parse(r.content)
                Diag.i("followups", "model ${model.substringAfterLast('/')} try ${attempt + 1}: ${l.size} items" + if (l.isNotEmpty()) " (${Lang.detect(l.joinToString(". "))})" else " · raw: " + r.content.take(120).replace('\n', ' '))
                if (ok(l)) { items = l; break@loop }
                if (n == 0 && models.size > 1 && r.content.isBlank() && attempt == 1) break  // still empty (reasoning model): go to the main model
            }
        } catch (e: CancelledException) {
            items = emptyList()
        } catch (e: Throwable) {
            Diag.e("followups", "suggest", e)
            items = emptyList()
        }
        val cur = LearnHooks.currentConvoId?.invoke()
        if (items.isEmpty() || (cur != null && cur != c.id) || LearnHooks.busy?.invoke() == true) {
            LearnEvents.emit("followups", JSONObject().put("convoId", c.id).put("state", "none"))
            return emptyList()
        }
        synchronized(lock) { val j = all(); j.put(c.id, JSONObject().put("items", JSONArray(items)).put("at", System.currentTimeMillis())); store(j) }
        LearnEvents.emit("followups", JSONObject().put("convoId", c.id).put("state", "ready").put("items", JSONArray(items)).put("labels", labels()))
        return items
    }
}
