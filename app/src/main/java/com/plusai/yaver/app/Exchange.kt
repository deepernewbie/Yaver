package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

class AuthException(msg: String) : RuntimeException(msg)
class MailException(msg: String, val code: String = "") : RuntimeException(msg)

/** Sends one EWS operation (a SOAP body fragment with m:/t: prefixes) and returns the answer as a DOM. */
interface MailTransport {
    val label: String
    fun call(inner: String, timeoutMs: Int = 60000, cancel: Cancel? = null): Document
}

object Xml {
    private val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    fun parse(s: String): Document = synchronized(f) { f.newDocumentBuilder() }.parse(InputSource(StringReader(s)))
    fun newDoc(): Document = synchronized(f) { f.newDocumentBuilder() }.newDocument()
    fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    fun kids(el: Element?, name: String): List<Element> {
        if (el == null) return emptyList()
        val nl = el.getElementsByTagNameNS("*", name)
        return (0 until nl.length).map { nl.item(it) as Element }
    }
    fun kids(d: Document, name: String): List<Element> = kids(d.documentElement, name)
    fun children(el: Element): List<Element> {
        val out = ArrayList<Element>()
        var n = el.firstChild
        while (n != null) { if (n is Element) out.add(n); n = n.nextSibling }
        return out
    }
    fun txt(el: Element?, name: String): String = kids(el, name).firstOrNull()?.textContent ?: ""
}

/** EWS over SOAP with NTLM (Windows login) or Basic authentication. */
class EwsTransport(private val url: String, private val user: String, private val pass: String) : MailTransport {
    private val u = URL(url)
    private val sock = SockHttp(u)
    private var basic = false
    var version = "Exchange2013"
    override val label = "EWS ${u.host}"

    private fun env(inner: String) = """<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"><soap:Header><t:RequestServerVersion Version="$version"/></soap:Header><soap:Body>$inner</soap:Body></soap:Envelope>"""

    @Synchronized private fun post(body: ByteArray, cancel: Cancel?): SockResp {
        val path = (u.path.ifEmpty { "/EWS/Exchange.asmx" }) + (u.query?.let { "?$it" } ?: "")
        val base = listOf("Content-Type" to "text/xml; charset=utf-8", "Accept" to "text/xml")
        val basicHdr = "Basic " + android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
        var lastErr: Exception? = null
        for (attempt in 0..1) {
            try {
                if (basic) return sock.exchange("POST", path, base + ("Authorization" to basicHdr), body, cancel)
                var r = sock.exchange("POST", path, base, body, cancel)
                if (r.code != 401) return r
                val offered = r.all("www-authenticate")
                val scheme = offered.firstOrNull { it.startsWith("NTLM", true) }?.let { "NTLM" }
                    ?: offered.firstOrNull { it.startsWith("Negotiate", true) }?.let { "Negotiate" }
                if (scheme != null && user.isNotEmpty()) {
                    if (!sock.isOpen) sock.exchange("HEAD", path, emptyList(), null, cancel)
                    r = sock.exchange("POST", path, base + ("Authorization" to "$scheme ${Ntlm.type1()}"), body, cancel)
                    val ch = r.all("www-authenticate").firstOrNull { it.startsWith(scheme, true) && it.length > scheme.length + 5 }
                        ?: throw AuthException(Y.t("The mail server did not continue the Windows login (NTLM).", "Posta sunucusu Windows oturum açmayı (NTLM) sürdürmedi."))
                    val t3 = Ntlm.type3(Ntlm.parseType2(ch.substring(scheme.length).trim()), user, pass)
                    r = sock.exchange("POST", path, base + ("Authorization" to "$scheme $t3"), body, cancel)
                    if (r.code == 401) throw AuthException(Y.t("The mail server rejected the user name or password (EWS).", "Posta sunucusu kullanıcı adını veya şifreyi kabul etmedi (EWS)."))
                    sock.markAuthenticated()
                    return r
                }
                if (offered.any { it.startsWith("Basic", true) } && user.isNotEmpty()) {
                    basic = true
                    r = sock.exchange("POST", path, base + ("Authorization" to basicHdr), body, cancel)
                    if (r.code == 401) { basic = false; throw AuthException(Y.t("The mail server rejected the user name or password (EWS).", "Posta sunucusu kullanıcı adını veya şifreyi kabul etmedi (EWS).")) }
                    return r
                }
                throw AuthException(if (user.isEmpty()) Y.t("EWS needs your user name and password (Settings → Mail).", "EWS kullanıcı adı ve şifre istiyor (Ayarlar → Posta).") else "EWS asks for an unsupported login method: ${offered.joinToString()}")
            } catch (e: AuthException) {
                throw e
            } catch (e: java.io.IOException) {
                if (cancel?.cancelled == true) throw CancelledException()
                lastErr = e
                sock.close()
            }
        }
        throw MailException(Y.t("Cannot reach the mail server ${u.host}: ", "Posta sunucusuna ulaşılamıyor (${u.host}): ") + (lastErr?.message ?: ""))
    }

    override fun call(inner: String, timeoutMs: Int, cancel: Cancel?): Document {
        val r = post(env(inner).toByteArray(Charsets.UTF_8), cancel)
        val text = r.text()
        when (r.code) {
            401 -> throw AuthException(Y.t("EWS answered 401 (not logged in).", "EWS 401 döndürdü (oturum açılmadı)."))
            403 -> throw MailException(Y.t("EWS answered 403: EWS is probably switched off for your mailbox. Only IT can enable it.", "EWS 403 döndürdü: EWS büyük olasılıkla posta kutunuz için kapalı. Yalnızca BT açabilir."))
            404 -> throw MailException(Y.t("There is no EWS service at $url.", "$url adresinde EWS hizmeti yok."))
        }
        if (r.code in 300..399) throw MailException("EWS redirected (HTTP ${r.code}) to ${r.header("location")} — this is not an EWS address")
        val doc = try { Xml.parse(text) } catch (e: Exception) { throw MailException("the server answered HTTP ${r.code} with something that is not EWS XML: ${Html.toText(text).take(160)}") }
        if (Xml.kids(doc, "Envelope").isEmpty() && doc.documentElement.localName != "Envelope") throw MailException("not an EWS answer (HTTP ${r.code})")
        val fault = Xml.kids(doc, "faultstring").firstOrNull()
        if (fault != null) {
            if (Regex("RequestServerVersion|version", RegexOption.IGNORE_CASE).containsMatchIn(fault.textContent) && version != "Exchange2010_SP2") {
                version = "Exchange2010_SP2"
                return call(inner, timeoutMs, cancel)
            }
            throw MailException("mail server: " + fault.textContent.trim())
        }
        Exchange.checkErrors(doc)
        return doc
    }
}

/**
 * Outlook on the web (OWA): logs in with the saved user name and password (forms login), keeps the session
 * cookies and logs in again by itself when the session expires. Talks to /owa/service.svc in JSON; EWS SOAP
 * fragments are converted to that JSON and the answers back to an EWS-shaped DOM (ported from yaver's adapter).
 */
class OwaTransport(private val origin: String, private val user: String, private val pass: String) : MailTransport {
    override val label = "Outlook web ${URL(origin).host}"
    private val fix = HashMap<String, MutableSet<String>>()

    private fun loggedIn(): Boolean = Http.cookie("$origin/owa/", "X-OWA-CANARY") != null

    /** Forms login (/owa/auth.owa). */
    @Synchronized fun login(cancel: Cancel? = null) {
        val r0 = Http.request("$origin/owa/", headers = mapOf("Accept" to "text/html"), cancel = cancel)
        val final0 = r0.url
        if (!final0.contains("/auth/", true) && !final0.contains("logon", true) && URL(final0).host == URL(origin).host && loggedIn()) return
        if (URL(final0).host != URL(origin).host) throw AuthException(Y.t("This mail server logs in through another site (${URL(final0).host}). Use “Log in on the web page” in Settings → Mail once.", "Bu posta sunucusu başka bir site üzerinden oturum açıyor (${URL(final0).host}). Ayarlar → Posta'daki “Web sayfasında oturum aç”ı bir kez kullanın."))
        if (user.isEmpty() || pass.isEmpty()) throw AuthException(Y.t("Outlook web needs your user name and password (Settings → Mail).", "Outlook web için kullanıcı adı ve şifre gerekiyor (Ayarlar → Posta)."))
        Http.setCookie(origin, "PBack=0; path=/")
        Http.setCookie(origin, "PrivateComputer=true; path=/")
        val form = linkedMapOf(
            "destination" to "$origin/owa/", "flags" to "4", "forcedownlevel" to "0", "trusted" to "4",
            "username" to user, "password" to pass, "passwordText" to "", "isUtf8" to "1"
        )
        val r = Http.postForm("$origin/owa/auth.owa", form, mapOf("Referer" to final0, "Origin" to origin), cancel = cancel)
        if (r.url.contains("reason=2") || r.url.contains("logon.aspx", true) && !loggedIn()) throw AuthException(Y.t("Outlook web did not accept the user name or password.", "Outlook web kullanıcı adını veya şifreyi kabul etmedi."))
        if (!loggedIn()) Http.request("$origin/owa/", headers = mapOf("Accept" to "text/html"), cancel = cancel)
        if (r.code >= 400 && !loggedIn()) throw AuthException("Outlook web login failed (HTTP ${r.code})")
    }

    private fun tz(): String = when (ZoneId.systemDefault().id) {
        "Europe/Istanbul" -> "Turkey Standard Time"
        "Europe/London" -> "GMT Standard Time"
        "Europe/Berlin", "Europe/Amsterdam", "Europe/Vienna", "Europe/Rome", "Europe/Stockholm", "Europe/Zurich" -> "W. Europe Standard Time"
        "Europe/Paris", "Europe/Brussels", "Europe/Madrid", "Europe/Copenhagen" -> "Romance Standard Time"
        "America/New_York" -> "Eastern Standard Time"
        "America/Chicago" -> "Central Standard Time"
        "America/Denver" -> "Mountain Standard Time"
        "America/Los_Angeles" -> "Pacific Standard Time"
        "Asia/Dubai" -> "Arabian Standard Time"
        "Europe/Moscow" -> "Russian Standard Time"
        else -> "UTC"
    }

    override fun call(inner: String, timeoutMs: Int, cancel: Cancel?): Document {
        val x = Xml.parse("<r xmlns:m=\"urn:m\" xmlns:t=\"urn:t\">$inner</r>")
        val root = Xml.children(x.documentElement).firstOrNull() ?: throw MailException("bad request")
        return send(root, root.localName, 0, timeoutMs, cancel)
    }

    private fun send(root: Element, op: String, depth: Int, timeoutMs: Int, cancel: Cancel?): Document {
        val f = fix.getOrPut(op) { HashSet() }
        val body = OwaJson.toJson(root, null) as JSONObject
        var post: Pair<String, String>? = null
        if ("noRestr" in f && body.has("Restriction")) {
            val it = body.optJSONObject("Restriction")?.optJSONObject("Item")
            if (it != null && it.optString("__type").startsWith("IsEqualTo")) {
                val field = it.optJSONObject("Item")?.optString("FieldURI") ?: ""
                val v = it.optJSONObject("FieldURIOrConstant")?.optJSONObject("Item")?.optString("Value") ?: ""
                post = Pair(field, v)
            }
            body.remove("Restriction")
        }
        val shape = body.optJSONObject("ItemShape") ?: body.optJSONObject("FolderShape")
        if (shape != null && ("extOnly" in f || "noProps" in f)) {
            val ap = shape.optJSONArray("AdditionalProperties")
            val keep = JSONArray()
            if ("noProps" !in f && ap != null) for (i in 0 until ap.length()) if (ap.optJSONObject(i)?.optString("__type")?.contains("ExtendedPropertyUri") == true) keep.put(ap.get(i))
            if (keep.length() > 0) shape.put("AdditionalProperties", keep) else shape.remove("AdditionalProperties")
            if (shape.optString("BaseShape") == "IdOnly" || shape.optString("BaseShape") == "Default") shape.put("BaseShape", "AllProperties")
        }
        if ("noSort" in f) body.remove("SortOrder")
        val dflt = OwaJson.defaults(op)
        if (dflt != null) for (k in dflt.keys()) if (!body.has(k) && ("noDefaults" !in f || k == "Paging")) body.put(k, dflt.get(k))
        val reqBody = JSONObject().put("__type", "${op}Request:#Exchange")
        for (k in body.keys()) if (k != "__type") reqBody.put(k, body.get(k))
        val req = JSONObject()
            .put("__type", "${op}JsonRequest:#Exchange")
            .put("Header", JSONObject().put("__type", "JsonRequestHeaders:#Exchange").put("RequestServerVersion", "Exchange2013")
                .put("TimeZoneContext", JSONObject().put("__type", "TimeZoneContext:#Exchange").put("TimeZoneDefinition", JSONObject().put("__type", "TimeZoneDefinitionType:#Exchange").put("Id", tz()))))
            .put("Body", reqBody)

        var r: HttpResp? = null
        for (attempt in 0..2) {
            if (!loggedIn()) login(cancel)
            val canary = Http.cookie("$origin/owa/", "X-OWA-CANARY") ?: ""
            r = Http.request(
                "$origin/owa/service.svc?action=$op&EP=1&ID=-1&AC=1", "POST",
                mapOf("Content-Type" to "application/json; charset=utf-8", "Action" to op, "X-OWA-CANARY" to canary, "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"),
                req.toString().toByteArray(), follow = false, timeoutMs = timeoutMs, cancel = cancel
            )
            val code = r.code
            val toLogon = code in 300..399 && (r.header("location") ?: "").let { it.contains("logon", true) || it.contains("/auth", true) }
            if (code == 449) continue // new canary was set in a cookie: just retry
            if (code == 440 || code == 401 || toLogon) {
                if (attempt == 2) throw AuthException(Y.t("The Outlook web session ended and logging in again did not work.", "Outlook web oturumu kapandı ve yeniden oturum açılamadı."))
                Http.setCookie("$origin/owa/", "X-OWA-CANARY=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
                login(cancel)
                continue
            }
            break
        }
        val resp = r ?: throw MailException("no answer")
        val text = resp.text()
        val json = try { JSONObject(text) } catch (e: Exception) { null }
        fun nextFix(): String? {
            val hasProps = shape?.optJSONArray("AdditionalProperties")?.length() ?: 0
            if (body.has("Restriction") && "noRestr" !in f) return "noRestr"
            if (hasProps > 0 && "extOnly" !in f) return "extOnly"
            if (hasProps > 0 && "noProps" !in f) return "noProps"
            if (body.has("SortOrder") && "noSort" !in f) return "noSort"
            if (dflt != null && "noDefaults" !in f) return "noDefaults"
            return null
        }
        if (json == null) {
            if (resp.code >= 500 && depth < 6) { nextFix()?.let { f.add(it); return send(root, op, depth + 1, timeoutMs, cancel) } }
            throw MailException("web mail HTTP ${resp.code}: " + Html.toText(text).take(160).ifEmpty { "empty answer" })
        }
        val b = json.optJSONObject("Body")
        val bad = if (b != null && (b.has("ExceptionName") || b.has("FaultMessage"))) "${b.optString("ExceptionName")} ${b.optString("FaultMessage", b.optString("Message"))}".trim()
        else if (resp.code >= 400) json.toString().take(200) else ""
        if (bad.isNotEmpty()) {
            if (depth < 6) nextFix()?.let { f.add(it); return send(root, op, depth + 1, timeoutMs, cancel) }
            throw MailException("web mail: $bad")
        }
        val doc = OwaJson.toXml(op, json)
        if (post != null) {
            val norm = { s: String -> s.trim().lowercase() }
            for (box in Xml.kids(doc, "Items") + Xml.kids(doc, "Folders")) for (c in Xml.children(box)) {
                val v = c.getAttribute(post.first).ifEmpty { Xml.txt(c, post.first) }
                if (norm(v) != norm(post.second)) box.removeChild(c)
            }
        }
        Exchange.checkErrors(doc)
        if (op == "FindItem" && "noSort" in f) {
            val its = Xml.kids(doc, "Items").firstOrNull()
            if (its != null) Xml.children(its).sortedByDescending { Xml.txt(it, "DateTimeReceived").ifEmpty { Xml.txt(it, "Start") } }.forEach { its.appendChild(it) }
        }
        return doc
    }
}

/** EWS ⇄ OWA JSON conversion, ported 1:1 from yaver's browser adapter. */
object OwaJson {
    private val ARRAYS = setOf("ItemIds", "FolderIds", "ParentFolderIds", "AdditionalProperties", "Items", "ItemChanges", "Updates", "ToRecipients", "CcRecipients", "BccRecipients", "RequiredAttendees", "OptionalAttendees", "Resources", "AttachmentIds", "Attachments", "SortOrder", "Folders", "Categories", "ReplyTo")
    private val TYPES = mapOf("FieldURI" to "PropertyUri", "ExtendedFieldURI" to "ExtendedPropertyUri", "ExtendedProperty" to "ExtendedPropertyType", "IndexedFieldURI" to "DictionaryPropertyUri", "Mailbox" to "EmailAddress", "Body" to "BodyContentType", "NewBodyContent" to "BodyContentType", "IndexedPageItemView" to "IndexedPageView", "CalendarView" to "CalendarPageView", "FieldOrder" to "SortResults", "ItemShape" to "ItemResponseShape", "FolderShape" to "FolderResponseShape", "Attendee" to "AttendeeType", "Restriction" to "RestrictionType", "FieldURIOrConstant" to "FieldURIOrConstantType", "SavedItemFolderId" to "TargetFolderId", "ParentFolderId" to "TargetFolderId", "ToFolderId" to "TargetFolderId", "QueryString" to "QueryStringType", "ReferenceItemId" to "ItemId")
    private val NUM = setOf("MaxEntriesReturned", "Offset", "PercentComplete", "ReminderMinutesBeforeStart")
    private val TEXTY = setOf("Body", "NewBodyContent", "QueryString")

    private fun field(v: String): String = mapOf("item:LastModifiedTime" to "LastModifiedTime", "item:ParentFolderId" to "ParentFolderId", "item:ItemId" to "ItemId")[v] ?: v.replace(Regex("^[a-z]+:"), "")

    private fun scalar(name: String, v: String): Any = when {
        v == "true" -> true
        v == "false" -> false
        name in NUM && v.matches(Regex("\\d+")) -> v.toInt()
        else -> v
    }

    fun toJson(el: Element, parent: String?): Any? {
        val name = el.localName
        val type = (TYPES[name] ?: name) + ":#Exchange"
        val kids = Xml.children(el)
        val o = JSONObject().put("__type", type)
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            val an = a.localName ?: a.nodeName
            if (a.nodeName.startsWith("xmlns")) continue
            val v: Any = if (name == "FieldURI" && an == "FieldURI") field(a.nodeValue) else if (name == "Constant") a.nodeValue else scalar(an, a.nodeValue)
            o.put(an, v)
        }
        if (name == "SavedItemFolderId" || name == "ParentFolderId" || name == "ToFolderId") return JSONObject().put("__type", type).put("BaseFolderId", kids.firstOrNull()?.let { toJson(it, name) } ?: JSONObject.NULL)
        if (name == "Restriction") return JSONObject().put("__type", type).put("Item", kids.firstOrNull()?.let { toJson(it, name) } ?: JSONObject.NULL)
        if (kids.isEmpty()) {
            val t = el.textContent ?: ""
            if (attrs.length == 0 || name in TEXTY) {
                if (name in TEXTY) { o.put("Value", t); return o }
                return null
            }
            return o
        }
        for (k in kids) {
            val kn = k.localName
            if (kn == "ExtendedProperty" && name != "SetItemField") {
                val arr = o.optJSONArray("ExtendedProperty") ?: JSONArray().also { o.put("ExtendedProperty", it) }
                arr.put(toJson(k, name)); continue
            }
            if (kn == "ToRecipients" || kn == "CcRecipients" || kn == "BccRecipients" || kn == "ReplyTo") {
                val arr = JSONArray()
                for (c in Xml.children(k)) {
                    val mb = (toJson(c, kn) as? JSONObject) ?: JSONObject()
                    mb.put("RoutingType", "SMTP")
                    arr.put(JSONObject().put("__type", "EmailAddressWrapper:#Exchange").put("Mailbox", mb))
                }
                o.put(kn, arr); continue
            }
            if (kn in ARRAYS) {
                val arr = JSONArray()
                for (c in Xml.children(k)) {
                    if (Xml.children(c).isNotEmpty() || c.attributes.length > 0 || c.localName == "Body" || c.localName == "Mailbox") arr.put(toJson(c, kn) ?: JSONObject.NULL)
                    else arr.put(c.textContent)
                }
                o.put(kn, arr); continue
            }
            if (Xml.children(k).isEmpty() && k.attributes.length == 0 && kn !in TEXTY) {
                o.put(kn, scalar(kn, k.textContent ?: "")); continue
            }
            var key = kn
            if (kn == "IndexedPageItemView" || kn == "CalendarView" || kn == "FractionalPageItemView") key = "Paging"
            else if (kn == "FieldURI" || kn == "ExtendedFieldURI" || kn == "IndexedFieldURI") key = if (name in setOf("SetItemField", "AppendToItemField", "DeleteItemField", "FieldOrder")) "Path" else if (name == "ExtendedProperty") "ExtendedFieldURI" else "Item"
            else if (name == "SetItemField" || name == "AppendToItemField") key = "Item"
            else if (name == "FieldURIOrConstant") key = "Item"
            o.put(key, toJson(k, name) ?: JSONObject.NULL)
        }
        return o
    }

    fun defaults(op: String): JSONObject? = when (op) {
        "DeleteItem" -> JSONObject().put("DeleteType", "MoveToDeletedItems").put("SendMeetingCancellations", "SendToNone").put("AffectedTaskOccurrences", "AllOccurrences")
        "UpdateItem" -> JSONObject().put("MessageDisposition", "SaveOnly").put("ConflictResolution", "AlwaysOverwrite").put("SendMeetingInvitationsOrCancellations", "SendToNone")
        "FindFolder" -> JSONObject().put("Paging", JSONObject().put("__type", "IndexedPageView:#Exchange").put("BasePoint", "Beginning").put("Offset", 0).put("MaxEntriesReturned", 200))
        "FindItem" -> JSONObject().put("Paging", JSONObject().put("__type", "IndexedPageView:#Exchange").put("BasePoint", "Beginning").put("Offset", 0).put("MaxEntriesReturned", 100))
        "CreateItem" -> JSONObject().put("MessageDisposition", "SaveOnly").put("SendMeetingInvitations", "SendToNone")
        "SendItem" -> JSONObject().put("SaveItemToFolder", true)
        "MoveItem", "CopyItem" -> JSONObject().put("ReturnNewItemIds", true)
        else -> null
    }

    fun toXml(op: String, json: JSONObject): Document {
        val ns = "urn:yaver-owa"
        val doc = Xml.newDoc()
        fun mk(n: String): Element {
            var s = n.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            if (s.isEmpty()) s = "x"
            if (!(s[0].isLetter() || s[0] == '_')) s = "_$s"
            return doc.createElementNS(ns, s)
        }
        lateinit var fill: (Element, JSONObject) -> Unit
        fun put(parent: Element, key: String, v0: Any?) {
            if (v0 == null || v0 == JSONObject.NULL) return
            if (v0 is JSONArray) {
                val box = mk(key); parent.appendChild(box)
                for (i in 0 until v0.length()) {
                    var x: Any? = v0.opt(i)
                    if (x is JSONObject && x.optString("__type").startsWith("EmailAddressWrapper") && x.optJSONObject("Mailbox") != null) {
                        val mb = x.getJSONObject("Mailbox")
                        val copy = JSONObject(mb.toString()); copy.put("__type", "EmailAddress:#Exchange"); x = copy
                    }
                    if (key == "Categories" && x !is JSONObject) { val e = mk("String"); e.textContent = x.toString(); box.appendChild(e); continue }
                    if (x is JSONObject) {
                        var n = x.optString("__type").substringBefore(":").ifEmpty { key.removeSuffix("s") }
                        if (n == "EmailAddress") n = "Mailbox"
                        if (n.endsWith("Type") && n != "MeetingRequestType") n = n.removeSuffix("Type")
                        val e = mk(n); box.appendChild(e); fill(e, x)
                    } else if (x != null && x != JSONObject.NULL) {
                        val e = mk(key.removeSuffix("s")); e.textContent = x.toString(); box.appendChild(e)
                    }
                }
                return
            }
            if (v0 is JSONObject) { val e = mk(key); parent.appendChild(e); fill(e, v0); return }
            val s = v0.toString()
            try { parent.setAttribute(key, s) } catch (_: Exception) {}
            val e = mk(key); e.textContent = s; parent.appendChild(e)
        }
        fill = { el, o ->
            val v = o.opt("Value")
            val onlyScalars = o.keys().asSequence().none { k -> k != "Value" && (o.opt(k) is JSONObject || o.opt(k) is JSONArray) }
            if (o.has("Value") && v !is JSONObject && v !is JSONArray && onlyScalars) {
                for (k in o.keys()) if (k != "Value" && k != "__type") { val x = o.opt(k); if (x != null && x != JSONObject.NULL) try { el.setAttribute(k, x.toString()) } catch (_: Exception) {} }
                el.textContent = if (v == null || v == JSONObject.NULL) "" else v.toString()
            } else {
                for (k in o.keys()) { if (k == "__type") continue; put(el, k, o.opt(k)) }
            }
        }
        val env = doc.createElementNS(ns, "Envelope"); doc.appendChild(env)
        val body = mk("Body"); env.appendChild(body)
        val resp = mk(op + "Response"); body.appendChild(resp)
        val rms = mk("ResponseMessages"); resp.appendChild(rms)
        val items = json.optJSONObject("Body")?.optJSONObject("ResponseMessages")?.optJSONArray("Items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val e = mk(m.optString("__type", op + "ResponseMessage").substringBefore(":"))
            rms.appendChild(e); fill(e, m)
        }
        return doc
    }
}

class MailBox(val name: String, val email: String) {
    override fun toString() = if (name.isNotEmpty() && name != email) "$name <$email>" else email
    val short: String get() = name.ifEmpty { email }
}

class MailHeader(val id: String, val ck: String, val subject: String, val from: MailBox?, val received: String, val read: Boolean, val attachments: Boolean, val importance: String, val preview: String)
class MailFile(val id: String, val name: String, val type: String, val size: Long, val inline: Boolean)
class MailMessage(val h: MailHeader, val kind: String, val to: List<MailBox>, val cc: List<MailBox>, val body: String, val files: List<MailFile>, val invite: JSONObject?)
class CalEvent(val id: String, val subject: String, val start: String, val end: String, val location: String, val organizer: MailBox?, val allDay: Boolean, val response: String, val cancelled: Boolean, val meeting: Boolean)

/** Mailbox operations (ported from yaver's EWS object). Works the same over EWS or Outlook web. */
class Exchange(val t: MailTransport) {
    var noPreview = false

    companion object {
        fun checkErrors(doc: Document) {
            for (rm in Xml.kids(doc, "ResponseMessages")) for (m in Xml.children(rm)) {
                if (m.getAttribute("ResponseClass") == "Error") {
                    val code = m.getAttribute("ResponseCode").ifEmpty { Xml.txt(m, "ResponseCode") }
                    val msg = m.getAttribute("MessageText").ifEmpty { Xml.txt(m, "MessageText") }
                    throw MailException("mail server: ${msg.ifEmpty { code }}", code)
                }
            }
        }

        fun folder(f: String?): String = mapOf(
            "trash" to "deleteditems", "spam" to "junkemail", "inbox" to "inbox", "sent" to "sentitems", "sentitems" to "sentitems", "drafts" to "drafts",
            "deleted" to "deleteditems", "deleteditems" to "deleteditems", "junk" to "junkemail", "junkemail" to "junkemail", "archive" to "archive", "outbox" to "outbox"
        )[(f ?: "inbox").lowercase().trim()] ?: "inbox"

        fun parseTime(s: String): Long {
            if (s.isEmpty()) return 0
            return try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) {
                try { Instant.parse(s).toEpochMilli() } catch (e2: Exception) {
                    try { LocalDateTime.parse(s.take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (e3: Exception) { 0 }
                }
            }
        }

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
    }

    private fun call(inner: String, timeout: Int = 60000, cancel: Cancel? = null) = t.call(inner, timeout, cancel)

    private fun box(el: Element?): MailBox? {
        val mb = Xml.kids(el, "Mailbox").firstOrNull() ?: return null
        return MailBox(Xml.txt(mb, "Name"), Xml.txt(mb, "EmailAddress"))
    }

    private fun boxes(el: Element?, name: String): List<MailBox> {
        val c = Xml.kids(el, name).firstOrNull() ?: return emptyList()
        return Xml.kids(c, "Mailbox").map { MailBox(Xml.txt(it, "Name"), Xml.txt(it, "EmailAddress")) }
    }

    private fun idOf(el: Element?): Pair<String, String>? {
        val i = Xml.kids(el, "ItemId").firstOrNull() ?: return null
        return Pair(i.getAttribute("Id"), i.getAttribute("ChangeKey"))
    }

    private fun idOf(d: Document) = idOf(d.documentElement)

    private fun header(m: Element): MailHeader {
        val id = idOf(m)
        return MailHeader(
            id?.first ?: "", id?.second ?: "", Xml.txt(m, "Subject").ifEmpty { "(no subject)" }, box(Xml.kids(m, "From").firstOrNull()),
            Xml.txt(m, "DateTimeReceived").ifEmpty { Xml.txt(m, "DateTimeSent") }, Xml.txt(m, "IsRead") != "false", Xml.txt(m, "HasAttachments") == "true",
            Xml.txt(m, "Importance").ifEmpty { "Normal" }, Xml.txt(m, "Preview").take(400)
        )
    }

    private fun mbx(list: List<String>): String = list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("") { a ->
        val m = Regex("^(.*)<([^>]+)>$").find(a)
        val name = m?.groupValues?.get(1)?.trim() ?: ""
        "<t:Mailbox>${if (name.isNotEmpty()) "<t:Name>${Xml.esc(name)}</t:Name>" else ""}<t:EmailAddress>${Xml.esc(m?.groupValues?.get(2) ?: a)}</t:EmailAddress></t:Mailbox>"
    }

    fun textToHtml(s: String): String = "<div style=\"font-family:Calibri,Arial,sans-serif;font-size:11pt\">" +
        Xml.esc(s).split(Regex("\\n{2,}")).joinToString("") { "<p>" + it.replace("\n", "<br>") + "</p>" } + "</div>"

    fun probe(cancel: Cancel? = null) {
        val d = call("<m:GetFolder><m:FolderShape><t:BaseShape>Default</t:BaseShape></m:FolderShape><m:FolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:FolderIds></m:GetFolder>", 30000, cancel)
        if (Xml.kids(d, "ResponseCode").isEmpty() && Xml.kids(d, "Folders").isEmpty() && Xml.kids(d, "Folder").isEmpty()) throw MailException("not an EWS answer")
    }

    class FindResult(val total: Int, val items: List<MailHeader>)

    fun find(folder: String = "inbox", query: String = "", unread: Boolean = false, limit: Int = 25, offset: Int = 0, cancel: Cancel? = null): FindResult {
        val props = mutableListOf("item:Subject", "message:From", "item:DateTimeReceived", "item:DateTimeSent", "message:IsRead", "item:HasAttachments", "item:Importance")
        if (!noPreview) props.add("item:Preview")
        val restr = if (unread && query.isEmpty()) "<m:Restriction><t:IsEqualTo><t:FieldURI FieldURI=\"message:IsRead\"/><t:FieldURIOrConstant><t:Constant Value=\"false\"/></t:FieldURIOrConstant></t:IsEqualTo></m:Restriction>" else ""
        val body = "<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape>" +
            "<m:IndexedPageItemView MaxEntriesReturned=\"${limit.coerceIn(1, 100)}\" Offset=\"${maxOf(0, offset)}\" BasePoint=\"Beginning\"/>$restr" +
            "<m:SortOrder><t:FieldOrder Order=\"Descending\"><t:FieldURI FieldURI=\"item:DateTimeReceived\"/></t:FieldOrder></m:SortOrder>" +
            "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"${folder(folder)}\"/></m:ParentFolderIds>" +
            (if (query.isNotEmpty()) "<m:QueryString>${Xml.esc(query)}</m:QueryString>" else "") + "</m:FindItem>"
        val doc = try { call(body, cancel = cancel) } catch (e: MailException) {
            if (!noPreview && Regex("Preview|property|schema", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) { noPreview = true; return find(folder, query, unread, limit, offset, cancel) }
            throw e
        }
        val rootF = Xml.kids(doc, "RootFolder").firstOrNull()
        val total = rootF?.getAttribute("TotalItemsInView")?.toIntOrNull() ?: Xml.txt(rootF, "TotalItemsInView").toIntOrNull() ?: 0
        val items = Xml.kids(doc, "Items").firstOrNull()
        return FindResult(total, items?.let { Xml.children(it).map { m -> header(m) } } ?: emptyList())
    }

    fun get(id: String, cancel: Cancel? = null): MailMessage {
        val doc = call("<m:GetItem><m:ItemShape><t:BaseShape>Default</t:BaseShape><t:BodyType>Text</t:BodyType><t:AdditionalProperties><t:FieldURI FieldURI=\"message:ToRecipients\"/><t:FieldURI FieldURI=\"message:CcRecipients\"/><t:FieldURI FieldURI=\"item:Attachments\"/><t:FieldURI FieldURI=\"item:DateTimeReceived\"/><t:FieldURI FieldURI=\"message:IsRead\"/></t:AdditionalProperties></m:ItemShape><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:GetItem>", cancel = cancel)
        val it = Xml.kids(doc, "Items").firstOrNull()?.let { Xml.children(it).firstOrNull() } ?: throw MailException("message not found")
        val h = header(it)
        val kind = it.localName
        var invite: JSONObject? = null
        if (kind.contains("MeetingRequest") || kind.contains("MeetingCancellation")) {
            invite = inviteOf(it)
            if (invite.optString("start").isEmpty()) try { invite = inviteDetails(id, cancel) } catch (_: Exception) {}
        }
        val files = Xml.kids(it, "FileAttachment").map { a ->
            MailFile(Xml.kids(a, "AttachmentId").firstOrNull()?.getAttribute("Id") ?: "", Xml.txt(a, "Name"), Xml.txt(a, "ContentType"), Xml.txt(a, "Size").toLongOrNull() ?: 0, Xml.txt(a, "IsInline") == "true")
        }
        val body = Xml.txt(it, "Body").replace("\r\n", "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return MailMessage(h, kind, boxes(it, "ToRecipients"), boxes(it, "CcRecipients"), body, files, invite)
    }

    private fun inviteOf(it: Element): JSONObject {
        fun att(name: String): JSONArray {
            val c = Xml.kids(it, name).firstOrNull() ?: return JSONArray()
            return JSONArray(Xml.kids(c, "Attendee").map { a -> (box(a)?.toString() ?: "") + Xml.txt(a, "ResponseType").let { r -> if (r.isNotEmpty()) " ($r)" else "" } })
        }
        return JSONObject().put("start", Xml.txt(it, "Start")).put("end", Xml.txt(it, "End")).put("location", Xml.txt(it, "Location"))
            .put("organizer", box(Xml.kids(it, "Organizer").firstOrNull())?.toString() ?: "").put("allDay", Xml.txt(it, "IsAllDayEvent") == "true")
            .put("required", att("RequiredAttendees")).put("optional", att("OptionalAttendees")).put("response", Xml.txt(it, "MyResponseType"))
    }

    private fun inviteDetails(id: String, cancel: Cancel?): JSONObject {
        val doc = call("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties><t:FieldURI FieldURI=\"calendar:Start\"/><t:FieldURI FieldURI=\"calendar:End\"/><t:FieldURI FieldURI=\"calendar:Location\"/><t:FieldURI FieldURI=\"calendar:Organizer\"/><t:FieldURI FieldURI=\"calendar:IsAllDayEvent\"/><t:FieldURI FieldURI=\"calendar:RequiredAttendees\"/><t:FieldURI FieldURI=\"calendar:OptionalAttendees\"/><t:FieldURI FieldURI=\"calendar:MyResponseType\"/></t:AdditionalProperties></m:ItemShape><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:GetItem>", cancel = cancel)
        val it = Xml.kids(doc, "Items").firstOrNull()?.let { Xml.children(it).firstOrNull() } ?: throw MailException("meeting request not found")
        return inviteOf(it)
    }

    class Attachment(val name: String, val type: String, val bytes: ByteArray)

    fun attachment(attId: String, cancel: Cancel? = null): Attachment {
        val doc = call("<m:GetAttachment><m:AttachmentIds><t:AttachmentId Id=\"${Xml.esc(attId)}\"/></m:AttachmentIds></m:GetAttachment>", 120000, cancel)
        val a = Xml.kids(doc, "FileAttachment").firstOrNull() ?: throw MailException("attachment not found")
        val b64 = Xml.txt(a, "Content").replace(Regex("\\s+"), "")
        return Attachment(Xml.txt(a, "Name"), Xml.txt(a, "ContentType").ifEmpty { "application/octet-stream" }, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
    }

    /** kind: new | reply | replyall | forward. send=false saves to Drafts. Returns the new item id (drafts) or "". */
    fun compose(kind: String, ref: String, to: List<String>, cc: List<String>, subject: String, bodyText: String, send: Boolean, cancel: Cancel? = null): String {
        val disp = if (send) "SendAndSaveCopy" else "SaveOnly"
        val folder = if (send) "sentitems" else "drafts"
        val html = textToHtml(bodyText)
        val item = when (kind) {
            "reply", "replyall", "forward" -> {
                val tag = when (kind) { "reply" -> "ReplyToItem"; "replyall" -> "ReplyAllToItem"; else -> "ForwardItem" }
                "<t:$tag>${if (to.isNotEmpty() && kind == "forward") "<t:ToRecipients>${mbx(to)}</t:ToRecipients>" else ""}${if (cc.isNotEmpty()) "<t:CcRecipients>${mbx(cc)}</t:CcRecipients>" else ""}<t:ReferenceItemId Id=\"${Xml.esc(ref)}\"/><t:NewBodyContent BodyType=\"HTML\">${Xml.esc(html)}</t:NewBodyContent></t:$tag>"
            }
            else -> "<t:Message><t:Subject>${Xml.esc(subject)}</t:Subject><t:Body BodyType=\"HTML\">${Xml.esc(html)}</t:Body>${if (to.isNotEmpty()) "<t:ToRecipients>${mbx(to)}</t:ToRecipients>" else ""}${if (cc.isNotEmpty()) "<t:CcRecipients>${mbx(cc)}</t:CcRecipients>" else ""}</t:Message>"
        }
        val doc = call("<m:CreateItem MessageDisposition=\"$disp\"><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"$folder\"/></m:SavedItemFolderId><m:Items>$item</m:Items></m:CreateItem>", cancel = cancel)
        return idOf(doc)?.first ?: ""
    }

    fun sendDraft(id: String, cancel: Cancel? = null) {
        val cur = idOf(call("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:GetItem>", cancel = cancel))
        call("<m:SendItem SaveItemToFolder=\"true\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"${if (!cur?.second.isNullOrEmpty()) " ChangeKey=\"${Xml.esc(cur?.second)}\"" else ""}/></m:ItemIds><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"sentitems\"/></m:SavedItemFolderId></m:SendItem>", cancel = cancel)
    }

    fun markRead(id: String, read: Boolean, cancel: Cancel? = null) {
        call("<m:UpdateItem MessageDisposition=\"SaveOnly\" ConflictResolution=\"AlwaysOverwrite\"><m:ItemChanges><t:ItemChange><t:ItemId Id=\"${Xml.esc(id)}\"/><t:Updates><t:SetItemField><t:FieldURI FieldURI=\"message:IsRead\"/><t:Message><t:IsRead>$read</t:IsRead></t:Message></t:SetItemField></t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>", cancel = cancel)
    }

    fun move(id: String, folderName: String, cancel: Cancel? = null): String {
        val doc = call("<m:MoveItem><m:ToFolderId><t:DistinguishedFolderId Id=\"${folder(folderName)}\"/></m:ToFolderId><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:MoveItem>", cancel = cancel)
        return idOf(doc)?.first ?: id
    }

    fun remove(id: String, permanent: Boolean, cancel: Cancel? = null) {
        if (permanent) {
            try { call("<m:DeleteItem DeleteType=\"HardDelete\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel) }
            catch (e: MailException) { if (e.code == "ErrorItemNotFound") throw e; call("<m:DeleteItem DeleteType=\"SoftDelete\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel) }
            return
        }
        try { move(id, "deleteditems", cancel) } catch (e: MailException) {
            if (e.code == "ErrorItemNotFound") throw e
            call("<m:DeleteItem DeleteType=\"MoveToDeletedItems\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel)
        }
    }

    fun events(start: Long, end: Long, cancel: Cancel? = null): List<CalEvent> {
        val props = listOf("item:Subject", "calendar:Start", "calendar:End", "calendar:Location", "calendar:Organizer", "calendar:IsAllDayEvent", "calendar:MyResponseType", "calendar:IsCancelled", "calendar:IsMeeting")
        val doc = call("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape><m:CalendarView MaxEntriesReturned=\"200\" StartDate=\"${iso(start)}\" EndDate=\"${iso(end)}\"/><m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds></m:FindItem>", cancel = cancel)
        val items = Xml.kids(doc, "Items").firstOrNull() ?: return emptyList()
        return Xml.children(items).map { c ->
            CalEvent(idOf(c)?.first ?: "", Xml.txt(c, "Subject").ifEmpty { "(no title)" }, Xml.txt(c, "Start"), Xml.txt(c, "End"), Xml.txt(c, "Location"),
                box(Xml.kids(c, "Organizer").firstOrNull()), Xml.txt(c, "IsAllDayEvent") == "true", Xml.txt(c, "MyResponseType"), Xml.txt(c, "IsCancelled") == "true", Xml.txt(c, "IsMeeting") == "true")
        }.sortedBy { parseTime(it.start) }
    }

    fun createEvent(subject: String, start: Long, end: Long, location: String, body: String, attendees: List<String>, cancel: Cancel? = null): String {
        val inv = if (attendees.isNotEmpty()) "SendToAllAndSaveCopy" else "SendToNone"
        val doc = call("<m:CreateItem SendMeetingInvitations=\"$inv\"><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"calendar\"/></m:SavedItemFolderId><m:Items><t:CalendarItem><t:Subject>${Xml.esc(subject)}</t:Subject>" +
            (if (body.isNotEmpty()) "<t:Body BodyType=\"Text\">${Xml.esc(body)}</t:Body>" else "") +
            "<t:Start>${iso(start)}</t:Start><t:End>${iso(end)}</t:End>" + (if (location.isNotEmpty()) "<t:Location>${Xml.esc(location)}</t:Location>" else "") +
            (if (attendees.isNotEmpty()) "<t:RequiredAttendees>${attendees.joinToString("") { "<t:Attendee>${mbx(listOf(it))}</t:Attendee>" }}</t:RequiredAttendees>" else "") +
            "</t:CalendarItem></m:Items></m:CreateItem>", cancel = cancel)
        return idOf(doc)?.first ?: ""
    }

    fun respond(id: String, response: String, message: String, cancel: Cancel? = null) {
        val tag = mapOf("accept" to "AcceptItem", "tentative" to "TentativelyAcceptItem", "decline" to "DeclineItem")[response] ?: throw IllegalArgumentException("response must be accept, tentative or decline")
        call("<m:CreateItem MessageDisposition=\"SendAndSaveCopy\"><m:Items><t:$tag>${if (message.isNotEmpty()) "<t:Body BodyType=\"Text\">${Xml.esc(message)}</t:Body>" else ""}<t:ReferenceItemId Id=\"${Xml.esc(id)}\"/></t:$tag></m:Items></m:CreateItem>", cancel = cancel)
    }

    fun cancelEvent(id: String, cancel: Cancel? = null) {
        call("<m:DeleteItem DeleteType=\"MoveToDeletedItems\" SendMeetingCancellations=\"SendToAllAndSaveCopy\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel)
    }

    class Person(val name: String, val email: String, val title: String, val dept: String)

    fun resolve(q: String, limit: Int = 8, cancel: Cancel? = null): List<Person> {
        if (q.trim().length < 2) return emptyList()
        val doc = try {
            call("<m:ResolveNames ReturnFullContactData=\"true\" SearchScope=\"ActiveDirectoryContacts\"><m:UnresolvedEntry>${Xml.esc(q.trim())}</m:UnresolvedEntry></m:ResolveNames>", 20000, cancel)
        } catch (e: MailException) {
            if (Regex("NoResults|NameResolution|No results", RegexOption.IGNORE_CASE).containsMatchIn(e.code + " " + e.message)) return emptyList()
            throw e
        }
        val out = ArrayList<Person>()
        for (r in Xml.kids(doc, "Resolution")) {
            val b = box(r) ?: continue
            if (b.email.isEmpty() || out.any { it.email.equals(b.email, true) }) continue
            out.add(Person(b.name.ifEmpty { b.email }, b.email, Xml.txt(r, "JobTitle"), Xml.txt(r, "Department")))
        }
        return out.take(limit)
    }
}
