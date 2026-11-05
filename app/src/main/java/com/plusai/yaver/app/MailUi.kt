package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge methods behind the mail and calendar screens (ported from the original's full Outlook-style view):
 * the reading view with the HTML body and inline pictures, server folders with counts, drafts edited and sent in
 * place, empty folder, "Draft with AI", and the calendar views with event details, new / edit / delete.
 * The plain mail methods (list, markRead, remove, move, compose, attachment, respond) stay in [Bridge].
 */
object MailUi {
    private val METHODS = setOf("mail.full", "mail.inline", "mail.folders", "mail.updateDraft", "mail.sendDraft", "mail.emptyFolder", "mail.aiDraft",
        "mail.range", "mail.event.get", "mail.event.create", "mail.event.update", "mail.event.delete")

    fun handles(m: String) = m in METHODS

    private fun boxJson(b: MailBox) = JSONObject().put("name", b.name).put("email", b.email)

    fun triageJson(id: String): Any = MailPlus.triage(id)?.let { JSONObject().put("s", it.s).put("r", it.r).put("p", it.p) } ?: JSONObject.NULL

    fun headerJson(h: MailHeader): JSONObject = JSONObject().put("id", h.id).put("subject", h.subject).put("from", h.from?.let { boxJson(it) } ?: JSONObject.NULL)
        .put("received", Exchange.parseTime(h.received)).put("read", h.read).put("attachments", h.attachments).put("importance", h.importance).put("preview", h.preview)
        .put("displayTo", h.displayTo).put("triage", triageJson(h.id))

    /** The reading view's message: HTML (sanitised and framed by the page), plain text for quoting, files with content ids. */
    fun fullJson(f: MailFull): JSONObject {
        val msg = f.m
        return headerJson(msg.h).put("kind", msg.kind).put("to", JSONArray(msg.to.map { boxJson(it) })).put("cc", JSONArray(msg.cc.map { boxJson(it) }))
            .put("body", msg.body).put("html", f.html).put("isDraft", f.isDraft).put("sent", Exchange.parseTime(f.sent)).put("invite", msg.invite ?: JSONObject.NULL)
            .put("files", JSONArray(msg.files.map { JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("size", it.size).put("item", it.item).put("inline", it.inline).put("cid", it.cid) }))
    }

    fun eventJson(e: CalEvent): JSONObject = JSONObject().put("id", e.id).put("subject", e.subject).put("start", Exchange.parseTime(e.start)).put("end", Exchange.parseTime(e.end))
        .put("location", e.location).put("organizer", e.organizer?.short ?: "").put("allDay", e.allDay).put("response", e.response).put("cancelled", e.cancelled)
        .put("meeting", e.meeting).put("recurring", e.recurring).put("freeBusy", e.freeBusy).put("categories", JSONArray(e.categories))

    private val FOLDER_IDS = listOf("inbox", "drafts", "sentitems", "deleteditems", "junkemail", "archive")

    /** The system prompt of "Draft with AI" (original aiDraft): body only, the user's language or the original's. */
    fun aiDraftSystem(lang: String, fixed: Boolean) = "You write e-mails for the user, a professional. Write only the body text: no subject line, no placeholders like [Name] unless unavoidable, a short greeting and sign-off. Write in $lang${if (fixed) "" else " (or the language of the original email if it clearly differs)"}, unless the user's notes ask for another language. Keep it concise and polite."

    fun aiDraftUser(kind: String, orig: MailMessage?, subject: String, to: String, notes: String): String =
        (if (orig != null) "Write a ${if (kind == "forward") "short forwarding note" else "reply"} to this email.\nFrom: ${MxText.who(orig.h.from)}\nSubject: ${orig.h.subject}\n\n${orig.body.replace(Regex("\\n{3,}"), "\n\n").trim().take(6000)}\n\n"
        else "Write an email with the subject \"$subject\" to $to.\n") + (if (notes.isNotBlank()) "Use these points/notes from me: $notes" else "")

    private fun ms(a: JSONObject, k: String): Long = if (a.has(k) && !a.isNull(k)) a.optDouble(k, 0.0).toLong() else 0L

    private fun eventArgs(a: JSONObject): Triple<String, Long, Long> {
        val subject = a.str("subject").ifEmpty { throw IllegalArgumentException(Y.t("Add a title", "Bir başlık ekleyin")) }
        val s = ms(a, "start"); var e = ms(a, "end")
        if (s <= 0) throw IllegalArgumentException(Y.t("Pick a date and time", "Tarih ve saat seçin"))
        if (e <= s) e = s + 30 * 60000L
        return Triple(subject, s, e)
    }

    private fun html(text: String, ex: Exchange) = if (text.isBlank()) "" else ex.textToHtml(text)

    fun dispatch(m: String, a: JSONObject): Any? = when (m) {
        "mail.full" -> {
            val f = Mail.use { it.getFull(a.str("id")) }
            if (!f.m.h.read && !f.isDraft) {
                try { Mail.use { it.markRead(f.m.h.id, true) } } catch (_: Throwable) {}
                try { MailIndex.markRead(f.m.h.id, true) } catch (_: Throwable) {}
            }
            fullJson(f)
        }
        "mail.inline" -> {
            // inline pictures (cid:) of the HTML body, as data URLs; at most 20, each under 4 MB
            val out = JSONObject()
            val list = a.optJSONArray("atts") ?: JSONArray()
            for (i in 0 until minOf(20, list.length())) {
                val o = list.optJSONObject(i) ?: continue
                val cid = o.optString("cid").removePrefix("<").removeSuffix(">")
                if (cid.isEmpty() || o.optLong("size", 0) > 4_000_000) continue
                try {
                    val att = Mail.use { it.attachment(Refs.real(o.optString("id"))) }
                    if (att.bytes.size > 4_000_000) continue
                    out.put(cid, "data:" + att.type.ifEmpty { "image/png" } + ";base64," + android.util.Base64.encodeToString(att.bytes, android.util.Base64.NO_WRAP))
                } catch (e: AuthException) { throw e } catch (_: Exception) {}
            }
            out
        }
        "mail.folders" -> {
            val info = MxEws.folderList(null)
            JSONArray(FOLDER_IDS.mapNotNull { id -> info[id]?.let { JSONObject().put("id", id).put("name", it.name).put("unread", it.unread).put("total", it.total) } })
        }
        "mail.updateDraft" -> {
            val id = Mail.use { ex -> ex.updateDraftFull(a.str("id"), a.list("to"), a.list("cc"), a.optString("subject"), ex.textToHtml(a.optString("body")) + a.optString("tail"), null) }
            if (a.optBoolean("send")) Mail.use { it.sendDraft(id) }
            try { MailIndex.forget(a.str("id")) } catch (_: Throwable) {}
            JSONObject().put("id", id).put("sent", a.optBoolean("send"))
        }
        "mail.sendDraft" -> { Mail.use { it.sendDraft(a.str("id")) }; true }
        "mail.emptyFolder" -> {
            val f = Exchange.folder(a.str("folder"))
            if (f != "deleteditems" && f != "junkemail") throw IllegalArgumentException(Y.t("Only Deleted Items and Junk can be emptied", "Yalnızca Silinmiş Öğeler ve Gereksiz boşaltılabilir"))
            MxEws.emptyFolder(f, null)
            true
        }
        "mail.aiDraft" -> {
            if (!Cfg.llmReady) throw IllegalStateException(Y.t("Choose a model in Settings first", "Önce Ayarlar'dan bir model seçin"))
            val ref = a.str("ref")
            val orig = if (ref.isNotEmpty()) Mail.use { it.get(ref) } else null
            val fixed = Lang.fixed() != null
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", aiDraftSystem(Lang.out(null), fixed)))
                .put(JSONObject().put("role", "user").put("content", aiDraftUser(a.str("kind"), orig, a.optString("subject"), a.list("to").joinToString(", "), a.optString("notes"))))
            val t = Llm.text(msgs, Cfg.model, null, 900, purpose = "mail_draft").trim()
            if (t.isEmpty()) throw IllegalStateException(Y.t("The model returned no text", "Model metin döndürmedi"))
            t
        }
        "mail.range" -> {
            val s = ms(a, "start"); val e = ms(a, "end")
            if (s <= 0 || e <= s) throw IllegalArgumentException("start and end are required")
            JSONArray(Mail.use { it.eventsFull(s, e) }.map { eventJson(it) })
        }
        "mail.event.get" -> Mail.use { it.getEvent(a.str("id")) }
        "mail.event.create" -> {
            val (subject, s, e) = eventArgs(a)
            val id = Mail.use { ex -> ex.createEventFull(subject, s, e, a.optBoolean("allDay"), a.str("location"), html(a.optString("body"), ex), a.list("attendees"), a.list("optional")) }
            MailPlus.event("mail.changed")
            JSONObject().put("id", id)
        }
        "mail.event.update" -> {
            val (subject, s, e) = eventArgs(a)
            val att = a.list("attendees"); val opt = a.list("optional")
            val id = Mail.use { ex -> ex.updateEvent(a.str("id"), subject, s, e, a.optBoolean("allDay"), a.str("location"), html(a.optString("body"), ex), att, opt, att.isNotEmpty() || opt.isNotEmpty()) }
            MailPlus.event("mail.changed")
            JSONObject().put("id", id)
        }
        "mail.event.delete" -> {
            // the organiser of a meeting cancels it (attendees are told); anything else just leaves the calendar
            if (a.optBoolean("cancel")) Mail.use { it.cancelEvent(a.str("id")) } else Mail.use { it.deleteEvent(a.str("id")) }
            MailPlus.event("mail.changed")
            true
        }
        else -> throw IllegalArgumentException("unknown method $m")
    }
}
