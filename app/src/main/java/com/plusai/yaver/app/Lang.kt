package com.plusai.yaver.app

/**
 * Conversation language: detection, sticky switching, the per-turn [Language: …] note.
 * Port of the original's LANG object. Codes are ISO-639-1 ("tr", "en", …).
 */
object Lang {
    val names: Map<String, String> = linkedMapOf(
        "tr" to "Turkish", "en" to "English", "de" to "German", "fr" to "French", "es" to "Spanish", "it" to "Italian",
        "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "sv" to "Swedish", "az" to "Azerbaijani", "ru" to "Russian",
        "uk" to "Ukrainian", "bg" to "Bulgarian", "ar" to "Arabic", "fa" to "Persian", "he" to "Hebrew", "el" to "Greek",
        "hi" to "Hindi", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean"
    )

    private val words: Map<String, String> = linkedMapOf(
        "tr" to "devam tamam evet hayir hayır peki olmus olmuş eksik kaldi kaldı nasil nasıl neden niye var yok ama bu su şu ne mi mı mu mü da de ile gibi daha cok çok simdi şimdi sonra once önce olustur oluştur hazirla hazırla yap yaz bul getir goster göster ozetle özetle cevir çevir indir kaydet ekle sil guncelle güncelle anlat acikla açıkla listele karsilastir karşılaştır degistir değiştir duzelt düzelt haber haberler rapor dosya sayfa tablo grafik bugun bugün dun dün yarin yarın icin lutfen degil cok simdi nasil neden nerede hangisi olsun olmali olmalı istiyorum turkce türkçe ingilizce hakkinda hakkında ile ve bir bu şu için ile ne mi mı mu mü nasıl neden niye değil çok daha gibi olan var yok ben sen biz siz bana beni sana lütfen ama fakat da de ki hangi nedir nerede yap yaz oku bul göster anlat hakkında şimdi sonra önce evet hayır tamam teşekkürler teşekkür merhaba selam dosya rapor özet kaç tane olarak olsun istiyorum lazım gerek bunu şunu onu her hiç ile birlikte kadar",
        "en" to "the and is are was were what how why who which you your please for with this that these those of to in on it can could would should do does did not have has will from about summarize write find show give tell me my i",
        "de" to "der die das und ist sind nicht ich sie wir mit für wie was warum ein eine einen bitte auch oder aber auf zu von den dem noch schreib zeig",
        "fr" to "le la les et est sont pas je vous nous pour avec que qui une des du au aux comment pourquoi ce cette dans sur mais ou très merci bonjour",
        "es" to "el la los las y es son no que para con una unos por como qué cómo porque este esta pero muy también gracias hola del al",
        "it" to "il lo gli e è sono non che per con una uno come perché questo questa ma molto anche grazie ciao del della",
        "pt" to "o os as e é são não que para com uma um como por que este esta mas muito também obrigado olá você do da",
        "nl" to "de het een en is zijn niet ik je jij wij van voor met wat hoe waarom dit dat maar ook dank",
        "pl" to "i w nie jest to że na się co jak dlaczego proszę dla z ale też",
        "sv" to "och är inte jag du vi för med vad hur varför det den men också tack",
        "az" to "və bir bu üçün ilə nə necə niyə deyil çox daha mən sən bizə xahiş amma"
    )
    private val sets: Map<String, Set<String>> = words.mapValuesTo(LinkedHashMap()) { it.value.split(" ").toSet() }

    private val chars: Map<String, Regex> = linkedMapOf(
        "tr" to Regex("[ğış]"), "de" to Regex("[ßäö]"), "fr" to Regex("[éèêàçœ]"), "es" to Regex("[ñ¿¡áí]"),
        "pt" to Regex("[ãõâ]"), "pl" to Regex("[ąęłńśźż]"), "sv" to Regex("[å]"), "az" to Regex("[əğış]"), "it" to Regex("[àèìòù]")
    )

    // \w spelled out: JS \w is ASCII-only, Android's ICU \w is not
    private val STRIP = listOf(
        Regex("```[\\s\\S]*?```"), Regex("`[^`]*`"), Regex("https?://\\S+"),
        Regex("[@~]?/?[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+"), Regex("\\[[^\\]]{0,200}\\]")
    )
    private val TOK = Regex("\\p{L}+")
    private val TR_SUFFIX = Regex("(lar|ler|lari|leri|ları|mısın|misin|musun|müsün|yor|ıyor|iyor|uyor|üyor|dır|dir|dur|dür|tir|tur|sın|sin|ın|nın|nin|nun|dan|den|tan|ten|ya|ye|yi|yu|u|ü)$")
    private val BG_AND = Regex("(?<![\\p{L}\\p{N}_])и(?![\\p{L}\\p{N}_])")

    private fun count(t: String, re: Regex) = re.findAll(t).count()
    private fun toks(text: String) = TOK.findAll(text.lowercase()).map { it.value }.toList()

    fun detect(text: String?): String? {
        var t = text ?: ""
        for (r in STRIP) t = r.replace(t, " ")
        if (Regex("[\\u3040-\\u30FF]").containsMatchIn(t)) return "ja"
        if (Regex("[\\uAC00-\\uD7AF]").containsMatchIn(t)) return "ko"
        if (Regex("[\\u4E00-\\u9FFF]").containsMatchIn(t)) return "zh"
        val cyr = count(t, Regex("[\\u0400-\\u04FF]"))
        if (cyr > 2) return if (Regex("[іїєґ]", RegexOption.IGNORE_CASE).containsMatchIn(t)) "uk"
            else if (t.contains('ъ') && BG_AND.containsMatchIn(t) && !Regex("[ыэ]").containsMatchIn(t)) "bg" else "ru"
        if (count(t, Regex("[\\u0600-\\u06FF]")) > 2) return if (Regex("[پچژگ]").containsMatchIn(t)) "fa" else "ar"
        if (count(t, Regex("[\\u0590-\\u05FF]")) > 2) return "he"
        if (count(t, Regex("[\\u0370-\\u03FF]")) > 2) return "el"
        if (count(t, Regex("[\\u0900-\\u097F]")) > 2) return "hi"
        val tk = toks(t)
        if (tk.isEmpty()) return null
        val score = LinkedHashMap<String, Double>()
        for ((k, set) in sets) score[k] = tk.count { it in set }.toDouble()
        val low = t.lowercase()
        for ((k, re) in chars) score[k] = (score[k] ?: 0.0) + minOf(4, count(low, re)) * 1.5
        if (t.contains('ı') || t.contains('İ')) score["tr"] = score["tr"]!! + 3   // dotless ı / dotted İ: almost only Turkish (and Azerbaijani)
        if (t.contains('ə')) score["az"] = score["az"]!! + 4 else score["az"] = score["az"]!! - 2
        if (Regex("[ğış]").containsMatchIn(low)) { score["de"] = score["de"]!! - 2; score["fr"] = score["fr"]!! - 1 }
        // Turkish suffixes on words (question particles and common endings)
        score["tr"] = score["tr"]!! + minOf(3.0, tk.count { TR_SUFFIX.containsMatchIn(it) && it.length > 4 } * 0.5)
        val best = score.entries.sortedByDescending { it.value }   // stable, like Array.sort
        if (best.isEmpty() || best[0].value < 1) return null
        if (best.size > 1 && best[0].value - best[1].value < 0.5 && tk.size < 3) return null   // too short and ambiguous
        return best[0].key
    }

    /** The conversation language changes only on clear evidence: a short message ("Devam et", "ok") keeps it. */
    fun switch(prev: String?, text: String?): String? {
        val d = detect(text)
        if (d == null || prev == null || d == prev) return d ?: prev
        val tk = toks(text ?: "")
        if (tk.size < 5) return prev
        fun hits(set: Set<String>?) = if (set == null) 0 else tk.count { it in set }
        val hn = hits(sets[d])
        if (hn < 2 || hn <= hits(sets[prev])) return prev
        return d
    }

    fun name(code: String?): String? = if (code.isNullOrEmpty()) null else names[code] ?: code

    /** The language setting (EN/TR) decides the language of everything yaver writes; "auto" follows each message. */
    fun fixed(): String? = Cfg.lang.let { if (it == "en" || it == "tr") it else null }

    /** UI language (the JS uiLang()): the setting, or the phone's locale under "auto". */
    fun ui(): String = if (Y.tr) "tr" else "en"

    fun pick(prev: String?, text: String?): String? = fixed() ?: switch(prev, text) ?: prev ?: ui()

    /** Language name for output in a conversation whose stored language is [convoLang]. */
    fun out(convoLang: String?): String = name(fixed() ?: convoLang?.ifEmpty { null } ?: ui()) ?: "English"

    fun note(code: String?): String {
        val f = fixed()
        if (f != null) {
            val n = name(f)
            return "[Language: the user has chosen $n in yaver's language setting. Write your reply — and any report, summary, e-mail draft or file you create for them — in $n, even if the request, tool results or e-mails are in another language, unless the user explicitly asks for another language in this message.]"
        }
        val n = name(code)
        return if (n != null) "[Language: the user wrote in $n. Write your reply — and any report, summary or file you create for them — in $n, unless they explicitly ask for another language.]" else ""
    }

    // ---- agent-loop helpers (ports of the inline code in agentLoop / Agent.send / deliverPending) ----

    /**
     * Agent.send: called for each user message before it is stored. Updates the conversation language
     * (internal messages only follow a fixed setting) and returns the note to append to the message content
     * ("" when none). The caller sets ctx.lang = Lang.name(newLang).
     */
    fun onUserMessage(c: Convo, text: String, internal: Boolean = false): String {
        val d = fixed() ?: if (!internal) pick(ConvoFields.lang(c), text) else null
        if (d != null) ConvoFields.setLang(c, d)
        val l = ConvoFields.lang(c)
        return if (l != null) "\n\n" + note(l) else ""
    }

    /** deliverPending: messages typed while a turn runs. Returns the note suffix for the steer message. */
    fun onSteer(c: Convo, texts: List<String>): String {
        val d = pick(ConvoFields.lang(c), texts.joinToString(" "))
        if (d != null) ConvoFields.setLang(c, d)
        val l = ConvoFields.lang(c)
        return if (l != null) "\n\n" + note(l) else ""
    }

    /** withLang: appended to the content of the LAST message when it is a tool result (not for subagents). */
    fun toolReminder(c: Convo): String? {
        val want = fixed() ?: ConvoFields.lang(c) ?: return null
        return "\n\n[Reminder: whatever you write to the user must be in ${name(want)}.]"
    }

    /**
     * Final-answer language check: returns the detected wrong language code when [final] (≥12 words outside code)
     * is not in the wanted language, else null. Do this once per turn (ctx.langFix).
     */
    fun wrongLanguage(c: Convo, final: String): String? {
        val want = fixed() ?: ConvoFields.lang(c) ?: return null
        val got = detect(final) ?: return null
        val words = TOK.findAll(Regex("```[\\s\\S]*?```").replace(final, " ")).count()
        return if (got != want && words >= 12) got else null
    }

    /** The harness message asking for a rewrite (pushed as a user message, tools off for that round). */
    fun rewriteRequest(got: String, want: String): String {
        val n = name(want)
        return "[harness] Your answer above is in ${name(got)}, but the user's language is $n. Write the complete answer again in $n: same content, links and structure, no mention of this note."
    }

    /** Progress text shown while the rewrite runs. */
    fun rewriteChip(got: String, want: String): String =
        Y.t("answer came in ${name(got)} — rewriting in ${name(want)}", "cevap ${name(got)} geldi — ${name(want)} olarak yeniden yazılıyor")
}
