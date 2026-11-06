package com.plusai.yaver.app

/**
 * Exchange wants an item's ChangeKey (its version) for some operations and answers "ChangeKey is required for this
 * operation" without it: SendItem, replies / forwards / meeting responses (CreateItem with ReferenceItemId) and
 * calendar updates that notify attendees. yaver only keeps item Ids (short handles in the chat), so this wrapper
 * around every mail transport adds the current ChangeKey before such calls (one GetItem for all ids of the call),
 * and for any other operation retries once with ChangeKeys added when the server asks for them.
 */
class ChangeKeyTransport(private val inner: MailTransport) : MailTransport {
    override val label: String get() = inner.label
    /** The wrapped transport (OWA / EWS), for code that needs to know which one it is. */
    val base: MailTransport get() = inner

    private val ID_TAG = Regex("<t:(ItemId|ReferenceItemId)\\s+Id=\"([^\"]+)\"\\s*/>")

    private fun op(xml: String) = Regex("^\\s*<m:(\\w+)").find(xml)?.groupValues?.get(1) ?: ""

    /** Operations that always need the version. */
    fun needsUpFront(xml: String): Boolean {
        val o = op(xml)
        return o == "SendItem" || (o == "CreateItem" && xml.contains("<t:ReferenceItemId")) ||
            (o == "UpdateItem" && xml.contains("SendMeetingInvitationsOrCancellations"))
    }

    /** The call with ChangeKey added to every ItemId / ReferenceItemId that lacks one. */
    fun withKeys(xml: String, cancel: Cancel?): String {
        val ids = ID_TAG.findAll(xml).map { it.groupValues[2] }.distinct().toList()
        if (ids.isEmpty()) return xml
        val keys = HashMap<String, String>()
        try {
            val doc = inner.call("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:ItemIds>${ids.joinToString("") { "<t:ItemId Id=\"${it}\"/>" }}</m:ItemIds></m:GetItem>", 30000, cancel)
            for (el in Xml.kids(doc, "ItemId")) { val id = el.getAttribute("Id"); val ck = el.getAttribute("ChangeKey"); if (id.isNotEmpty() && ck.isNotEmpty()) keys[id] = ck }
        } catch (e: CancelledException) { throw e } catch (e: Exception) { Diag.i("mail", "ChangeKey lookup failed: ${e.message}") }
        if (keys.isEmpty()) return xml
        return ID_TAG.replace(xml) { m -> val ck = keys[m.groupValues[2]]; if (ck == null) m.value else "<t:${m.groupValues[1]} Id=\"${m.groupValues[2]}\" ChangeKey=\"${Xml.esc(ck)}\"/>" }
    }

    /** Creating / changing / answering / cancelling a meeting or appointment: the brief and the widget must refresh. */
    fun changesCalendar(xml: String): Boolean {
        val o = op(xml)
        if (o != "CreateItem" && o != "UpdateItem" && o != "DeleteItem") return false
        return xml.contains("<t:CalendarItem") || xml.contains("SendMeetingInvitations") || xml.contains("SendMeetingCancellations") ||
            xml.contains("<t:AcceptItem") || xml.contains("<t:DeclineItem") || xml.contains("<t:TentativelyAcceptItem") || xml.contains("calendar:")
    }

    private fun asksForKey(e: Throwable) = (e.message ?: "").contains("ChangeKey", true) || (e as? MailException)?.code?.contains("ChangeKey", true) == true

    override fun call(inner: String, timeoutMs: Int, cancel: Cancel?): org.w3c.dom.Document {
        val first = if (needsUpFront(inner)) withKeys(inner, cancel) else inner
        if (changesCalendar(inner)) try { FiveBrief.calendarChanged() } catch (_: Throwable) {}
        return try { this.inner.call(first, timeoutMs, cancel) } catch (e: Exception) {
            if (e is CancelledException || !asksForKey(e) || first !== inner) throw e
            Diag.i("mail", "${op(inner)}: the server wants ChangeKeys — retrying with them")
            val again = withKeys(inner, cancel)
            if (again == inner) throw e
            this.inner.call(again, timeoutMs, cancel)
        }
    }
}

/** The real transport behind the ChangeKey wrapper. */
fun MailTransport.unwrap(): MailTransport = if (this is ChangeKeyTransport) base else this
