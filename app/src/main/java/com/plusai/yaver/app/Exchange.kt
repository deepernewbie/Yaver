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

open class AuthException(msg: String) : RuntimeException(msg)

/** The server wants the user on its own sign-in page (gateway, authenticator, company login). */
class LoginRequired(msg: String) : AuthException(msg)
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
class OwaTransport(private val origin: String, private val user: String, private val pass: String, private val web: Boolean = false) : MailTransport {
    override val label = "Outlook web ${URL(origin).host}" + if (web) " (web)" else ""
    private val fix = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()   // parallel mail reads share it

    private val host = URL(origin).host
    private fun ua() = mapOf("User-Agent" to Mail.ua)

    private fun loggedIn(): Boolean = Http.cookie("$origin/owa/", "X-OWA-CANARY") != null

    private fun signInNeeded(where: String) = LoginRequired(
        Y.t("Sign in to your mail once on its own page ($where) — your password is filled in for you; enter the authenticator code if asked.",
            "Postaya kendi sayfasında bir kez oturum açın ($where) — şifreniz otomatik doldurulur; istenirse doğrulama kodunu girin.")
    )

    /**
     * Makes sure there is a mail session. Plain Exchange forms login is done here with the saved password;
     * gateways (F5 /my.policy, ADFS, two-factor) need the sign-in page, so they raise [LoginRequired].
     */
    @Synchronized fun login(cancel: Cancel? = null) {
        if (web) {
            // the session belongs to the sign-in page; look at /owa/ the way the browser does
            val r = WebOwa.send(origin, "$origin/owa/", mapOf("Accept" to "text/html"), null, 30000, cancel)
            Diag.i("owa", "web check /owa/ → ${r.code} redirected=${r.redirected} canary=${loggedIn()} ${r.text.length}B")
            if (r.redirected || r.code == 0 || r.code == 401 || r.code == 440) throw signInNeeded(host)
            if (Regex("my\\.policy|/vdesk/|logon\\.aspx|type=.password", RegexOption.IGNORE_CASE).containsMatchIn(r.text)) throw signInNeeded(host)
            return
        }
        // redirects are followed by hand and never into a gateway: touching its policy page could end the session
        var r0 = Http.request("$origin/owa/", headers = ua() + ("Accept" to "text/html"), follow = false, cancel = cancel)
        var hops = 0
        while (r0.code in 300..399 && hops++ < 6) {
            val loc = URL(URL(r0.url), r0.header("location") ?: break).toString()
            Diag.i("owa", "login redirect ${r0.code} → ${Diag.url(loc)}")
            if (isGateway(loc) && !loc.contains("logon.aspx", true)) throw signInNeeded(URL(loc).host + URL(loc).path)
            r0 = Http.request(loc, headers = ua() + ("Accept" to "text/html"), follow = false, cancel = cancel)
        }
        val final0 = r0.url
        val u0 = URL(final0)
        val p0 = u0.path.lowercase()
        if (u0.host == host && p0.startsWith("/owa") && !p0.contains("/auth") && !p0.contains("logon") && loggedIn()) return
        if (u0.host != host || p0.contains("my.policy") || p0.startsWith("/vdesk") || p0.contains("adfs") || !p0.contains("logon.aspx")) throw signInNeeded(u0.host + u0.path)
        if (user.isEmpty() || pass.isEmpty()) throw signInNeeded(u0.host)
        Http.setCookie(origin, "PBack=0; path=/")
        Http.setCookie(origin, "PrivateComputer=true; path=/")
        val form = linkedMapOf(
            "destination" to "$origin/owa/", "flags" to "4", "forcedownlevel" to "0", "trusted" to "4",
            "username" to user, "password" to pass, "passwordText" to "", "isUtf8" to "1"
        )
        val r = Http.postForm("$origin/owa/auth.owa", form, ua() + mapOf("Referer" to final0, "Origin" to origin), cancel = cancel)
        if (r.url.contains("reason=2") || r.url.contains("logon.aspx", true) && !loggedIn()) throw AuthException(Y.t("Outlook web did not accept the user name or password.", "Outlook web kullanıcı adını veya şifreyi kabul etmedi."))
        if (!loggedIn()) Http.request("$origin/owa/", headers = ua() + ("Accept" to "text/html"), cancel = cancel)
        if (!loggedIn()) throw signInNeeded(host)
    }

    private fun isGateway(loc: String) = loc.contains("logon", true) || loc.contains("/auth", true) || loc.contains("my.policy", true) || loc.contains("/vdesk", true) || loc.contains("adfs", true) ||
        (loc.startsWith("http") && try { URL(loc).host != host } catch (e: Exception) { false })

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

    /**
     * An attachment through Outlook web's own download address (what the browser uses for "Download").
     * Null when this server does not offer it; the type comes from the answer, the name from the caller.
     */
    fun download(attId: String, cancel: Cancel?): Exchange.Attachment? {
        if (!web && !loggedIn()) login(cancel)
        val canary = Http.cookie("$origin/owa/", "X-OWA-CANARY") ?: ""
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "$origin/owa/service.svc/s/GetFileAttachment?id=${enc(attId)}&X-OWA-CANARY=${enc(canary)}"
        val ct: String
        val bytes: ByteArray
        if (web) {
            val r = WebOwa.send(origin, url, mapOf("X-OWA-CANARY" to canary, "X-Requested-With" to "XMLHttpRequest"), null, 120000, cancel, binary = true)
            if (r.redirected || r.code == 0 || r.code == 440) throw signInNeeded(host)
            if (r.code != 200) { Diag.i("owa", "GetFileAttachment → ${r.code}"); return null }
            ct = r.text.substringBefore('\n')
            bytes = android.util.Base64.decode(r.text.substringAfter('\n', ""), android.util.Base64.DEFAULT)
        } else {
            val r = Http.request(url, headers = ua() + ("X-OWA-CANARY" to canary), follow = false, timeoutMs = 120000, cancel = cancel)
            if (r.code == 440 || r.code == 401) throw AuthException(Y.t("The Outlook web session ended.", "Outlook web oturumu kapandı."))
            if (r.code != 200) { Diag.i("owa", "GetFileAttachment → ${r.code}"); return null }
            ct = r.contentType
            bytes = r.body
        }
        // a sign-in or error page instead of the file
        if (ct.startsWith("text/html", true) && Regex("<form|logon|my\\.policy|error", RegexOption.IGNORE_CASE).containsMatchIn(String(bytes.take(4000).toByteArray()))) return null
        return Exchange.Attachment("", ct.substringBefore(';').trim().ifEmpty { "application/octet-stream" }, bytes)
    }

    override fun call(inner: String, timeoutMs: Int, cancel: Cancel?): Document {
        val x = Xml.parse("<r xmlns:m=\"urn:m\" xmlns:t=\"urn:t\">$inner</r>")
        val root = Xml.children(x.documentElement).firstOrNull() ?: throw MailException("bad request")
        return send(root, root.localName, 0, timeoutMs, cancel)
    }

    /**
     * Recipients go to OWA in its own JSON shape: flat {"__type":"EmailAddress:#Exchange", Name, EmailAddress, RoutingType}
     * (that is also how OWA answers). The older adapter wrapped each one as EmailAddressWrapper{Mailbox:{…}}; if a server
     * refuses the flat shape the other one is tried once and, when it works, used first from then on.
     */
    @Volatile private var wrapFirst = false

    /**
     * Field names web mail refuses ("Invalid value 'Status' for enum PropertyUriEnum") are learned: the request is retried
     * with the name the server knows (TaskStatus, ItemLastModifiedTime …) or, when none works, without that field.
     * What works is kept for every later request, so each name is found once per server.
     */
    private object Aliases {
        private val map: MutableMap<String, String> = try { val j = JSONObject(Prefs.get("owa.alias", "{}")); j.keys().asSequence().associateWith { j.getString(it) }.toMutableMap() } catch (e: Exception) { HashMap() }
        @Synchronized fun get(n: String): String? = map[n]
        @Synchronized fun set(n: String, v: String) { map[n] = v; Prefs.put("owa.alias", JSONObject(map as Map<*, *>).toString()) }
        @Synchronized fun remove(n: String) { map.remove(n); Prefs.put("owa.alias", JSONObject(map as Map<*, *>).toString()) }
        fun candidates(n: String) = listOf("Task$n", "Item$n", "Message$n", "Calendar$n", "Contact$n", "Folder$n", "")
    }

    /** Applies the learned names to every PropertyUri in the request; "" removes the field. */
    private fun applyAliases(v: Any?): Any? {
        when (v) {
            is JSONObject -> {
                if (v.optString("__type").startsWith("PropertyUri") && v.has("FieldURI")) Aliases.get(v.optString("FieldURI"))?.let { a -> if (a.isEmpty()) return null else v.put("FieldURI", a) }
                for (k in v.keys().asSequence().toList()) { val r = applyAliases(v.get(k)); if (r == null) v.remove(k) else v.put(k, r) }
                return v
            }
            is JSONArray -> {
                val out = JSONArray(); for (i in 0 until v.length()) applyAliases(v.get(i))?.let { out.put(it) }; return out
            }
            else -> return v
        }
    }

    private fun send(root: Element, op: String, depth: Int, timeoutMs: Int, cancel: Cancel?, alt: Boolean = false): Document {
        val f = fix.getOrPut(op) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        val wrap = wrapFirst != alt
        val body = OwaJson.toJson(root, null, wrap) as JSONObject
        val hasRcpt = OwaJson.hasRecipients(body)
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
        applyAliases(body)
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
            val url = "$origin/owa/service.svc?action=$op&EP=1&ID=-1&AC=1"
            val hdr = mapOf("Content-Type" to "application/json; charset=utf-8", "Action" to op, "X-OWA-CANARY" to canary, "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json")
            var toLogon: Boolean
            if (web) {
                val w = WebOwa.send(origin, url, hdr, req.toString(), timeoutMs, cancel)
                r = HttpResp(w.code, mapOf("Content-Type" to listOf("application/json; charset=utf-8")), w.text.toByteArray(), url)
                toLogon = w.redirected || w.code == 0
                if (toLogon) { Diag.i("owa", "$op web → redirected to sign-in"); throw signInNeeded(host) }
            } else {
                r = Http.request(url, "POST", ua() + hdr, req.toString().toByteArray(), follow = false, timeoutMs = timeoutMs, cancel = cancel)
                toLogon = r.code in 300..399 && isGateway(r.header("location") ?: "")
            }
            val code = r.code
            Diag.i("owa", "$op ${if (web) "web" else "native"} → $code")
            if (code == 449) continue // new canary was set in a cookie: just retry
            if (code == 440 || code == 401 || toLogon) {
                if (web) throw signInNeeded(host)
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
        // A simplification step is kept only when the request then works: one broken item (HTTP 500 for it alone)
        // must not switch every later request of this kind to a heavier shape.
        fun retryWith(fx: String): Document {
            f.add(fx)
            try { return send(root, op, depth + 1, timeoutMs, cancel, alt) } catch (e: MailException) { f.remove(fx); throw e }
        }
        // several items in one request: a failure is usually one bad item, which the caller narrows down
        val many = (body.optJSONArray("ItemIds")?.length() ?: 0) > 1
        // the request was refused: with recipients in it, first try the other recipient shape (nothing was sent)
        fun otherShape(): Document? {
            if (!hasRcpt || alt || depth >= 6) return null
            val d = send(root, op, depth + 1, timeoutMs, cancel, alt = true)
            wrapFirst = !wrapFirst
            Diag.i("owa", "$op: recipients accepted in the ${if (wrapFirst) "wrapped" else "flat"} shape")
            return d
        }
        if (json == null) {
            if (Regex("my\\.policy|/vdesk/|logon\\.aspx|<form[^>]+password", RegexOption.IGNORE_CASE).containsMatchIn(text)) throw signInNeeded(host)
            if (resp.code >= 500) try { otherShape()?.let { return it } } catch (e: MailException) { Diag.i("owa", "$op other recipient shape: ${e.message}") }
            if (resp.code >= 500 && depth < 6 && !many) { nextFix()?.let { return retryWith(it) } }
            throw MailException("web mail HTTP ${resp.code}: " + Html.toText(text).take(160).ifEmpty { Y.t("empty answer", "boş yanıt") })
        }
        val b = json.optJSONObject("Body")
        val bad = if (b != null && (b.has("ExceptionName") || b.has("FaultMessage"))) "${b.optString("ExceptionName")} ${b.optString("FaultMessage", b.optString("Message"))}".trim()
        else if (resp.code >= 400) OwaJson.errorText(json).ifEmpty { "HTTP ${resp.code}" } else ""
        if (bad.isNotEmpty()) {
            Diag.i("owa", "$op refused: ${bad.take(300)}")
            // a field name this server does not know: try its other names, then leave it out — and remember what worked
            Regex("Invalid value '([A-Za-z]+)' for enum '[\\w.]*PropertyUriEnum'").find(bad)?.groupValues?.get(1)?.let { badName ->
                // the refused name may already be an alias we tried: find the original field it stands for
                val base = Regex("^(Task|Item|Message|Calendar|Contact|Folder)(.+)$").find(badName)?.groupValues?.get(2)
                val orig = if (base != null && Aliases.get(base) == badName) base else badName
                val tried = Aliases.get(orig)
                val cands = Aliases.candidates(orig)
                val next = if (tried == null) cands.first() else cands.getOrNull(cands.indexOf(tried) + 1)
                if (next != null && depth < 12) {
                    Aliases.set(orig, next)
                    Diag.i("owa", "web mail does not know the field '$badName' — trying ${if (next.isEmpty()) "without it" else "'$next'"}")
                    return send(root, op, depth + 1, timeoutMs, cancel, alt)
                }
            }
            try { otherShape()?.let { return it } } catch (e: MailException) { Diag.i("owa", "$op other recipient shape: ${e.message}") }
            if (depth < 6 && !many) nextFix()?.let { return retryWith(it) }
            throw MailException("web mail: ${OwaJson.shortFault(bad)}")
        }
        val doc = OwaJson.toXml(op, json)
        if (hasRcpt && !alt) {
            // a recipient the server could not read in this shape comes back as an error message, not a fault
            val rcptErr = Xml.kids(doc, "ResponseMessages").flatMap { Xml.children(it) }.any { m ->
                m.getAttribute("ResponseClass") == "Error" && Regex("Recipient|EmailAddress|Mailbox", RegexOption.IGNORE_CASE).containsMatchIn(m.getAttribute("ResponseCode") + " " + m.getAttribute("MessageText"))
            }
            if (rcptErr) try { otherShape()?.let { return it } } catch (e: MailException) { Diag.i("owa", "$op other recipient shape: ${e.message}") }
        }
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

    private val RCPT = setOf("ToRecipients", "CcRecipients", "BccRecipients", "ReplyTo")
    private val ATTENDEES = setOf("RequiredAttendees", "OptionalAttendees", "Resources")
    /** Single-recipient fields (EWS SingleRecipientType: a Mailbox inside). */
    private val SINGLE = setOf("From", "Sender", "Organizer", "ReceivedBy", "ReceivedRepresenting")

    /** True when a converted request carries recipients somewhere (CreateItem, UpdateItem…). */
    fun hasRecipients(o: Any?): Boolean = when (o) {
        is JSONObject -> o.keys().asSequence().any { k -> (k in RCPT && o.opt(k) is JSONArray) || hasRecipients(o.opt(k)) }
        is JSONArray -> (0 until o.length()).any { hasRecipients(o.opt(it)) }
        else -> false
    }

    /** A readable reason from an OWA error answer (never the raw JSON). */
    fun errorText(json: JSONObject): String {
        val b = json.optJSONObject("Body") ?: json
        val direct = listOf("FaultMessage", "Message", "ExceptionName").map { b.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
        if (direct != null) return direct
        val items = b.optJSONObject("ResponseMessages")?.optJSONArray("Items")
        if (items != null) for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val t = m.optString("MessageText").ifEmpty { m.optString("ResponseCode") }
            if (t.isNotEmpty() && t != "NoError") return t
        }
        return ""
    }

    /** Server faults can carry a .NET stack trace or a whole serialized request: keep the first sentence-ish part. */
    fun shortFault(s: String): String = s.replace(Regex("\\s+"), " ").trim().let { t ->
        val cut = Regex("\\s+at [A-Z][\\w.`<>]+\\(|\\{\"__type").find(t)?.range?.first ?: t.length
        t.substring(0, cut).trim().take(300)
    }

    fun toJson(el: Element, parent: String?, wrapRecipients: Boolean = false): Any? {
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
        if (name == "SavedItemFolderId" || name == "ParentFolderId" || name == "ToFolderId") return JSONObject().put("__type", type).put("BaseFolderId", kids.firstOrNull()?.let { toJson(it, name, wrapRecipients) } ?: JSONObject.NULL)
        if (name == "Restriction") return JSONObject().put("__type", type).put("Item", kids.firstOrNull()?.let { toJson(it, name, wrapRecipients) } ?: JSONObject.NULL)
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
                arr.put(toJson(k, name, wrapRecipients)); continue
            }
            if (kn in RCPT) {
                val arr = JSONArray()
                for (c in Xml.children(k)) {
                    val mb = (toJson(c, kn, wrapRecipients) as? JSONObject) ?: JSONObject().put("__type", "EmailAddress:#Exchange")
                    mb.put("RoutingType", "SMTP")
                    arr.put(if (wrapRecipients) JSONObject().put("__type", "EmailAddressWrapper:#Exchange").put("Mailbox", mb) else mb)
                }
                o.put(kn, arr); continue
            }
            if (kn in ARRAYS) {
                val arr = JSONArray()
                for (c in Xml.children(k)) {
                    if (Xml.children(c).isNotEmpty() || c.attributes.length > 0 || c.localName == "Body" || c.localName == "Mailbox") arr.put(toJson(c, kn, wrapRecipients) ?: JSONObject.NULL)
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
            o.put(key, toJson(k, name, wrapRecipients) ?: JSONObject.NULL)
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
                    // recipients: OWA sends them flat ({Name, EmailAddress, RoutingType, MailboxType}, usually without
                    // "__type"); a Mailbox wrapper is unwrapped. Either way each one becomes <Mailbox>, as in EWS.
                    if (x is JSONObject && (key in RCPT || x.optString("__type").startsWith("EmailAddressWrapper")) && x.optJSONObject("Mailbox") != null) {
                        val copy = JSONObject(x.getJSONObject("Mailbox").toString()); copy.put("__type", "EmailAddress:#Exchange"); x = copy
                    }
                    if (key == "Categories" && x !is JSONObject) { val e = mk("String"); e.textContent = x.toString(); box.appendChild(e); continue }
                    if (x is JSONObject) {
                        var n = x.optString("__type").substringBefore(":")
                        if (key in RCPT && (n.isEmpty() || n.startsWith("EmailAddress") || x.has("EmailAddress"))) n = "Mailbox"
                        else if (key in ATTENDEES && (n.isEmpty() || n.startsWith("Attendee"))) n = "Attendee"
                        if (n.isEmpty()) n = if (key.endsWith("ies")) key.dropLast(3) + "y" else key.removeSuffix("s")
                        if (n == "EmailAddress" || n == "EmailAddressWrapper") n = "Mailbox"
                        if (n.endsWith("Type") && n != "MeetingRequestType") n = n.removeSuffix("Type")
                        val e = mk(n); box.appendChild(e); fill(e, x)
                    } else if (x != null && x != JSONObject.NULL) {
                        val e = mk(key.removeSuffix("s")); e.textContent = x.toString(); box.appendChild(e)
                    }
                }
                return
            }
            if (v0 is JSONObject) {
                val e = mk(key); parent.appendChild(e)
                // From / Sender / Organizer: {Mailbox:{…}} in EWS; a flat {Name, EmailAddress} gets the Mailbox level added
                if (key in SINGLE && !v0.has("Mailbox") && (v0.has("EmailAddress") || v0.has("Name"))) { val mb = mk("Mailbox"); e.appendChild(mb); fill(mb, v0) }
                else fill(e, v0)
                return
            }
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
    override fun toString() = if (email.isEmpty()) name else if (name.isNotEmpty() && !name.equals(email, true)) "$name <$email>" else email
    val short: String get() = name.ifEmpty { email }
}

/** displayTo: the recipients' names as the server lists them ("Ayşe Yılmaz; Mehmet Kaya"), for Sent Items and Drafts. */
class MailHeader(val id: String, val ck: String, val subject: String, val from: MailBox?, val received: String, val read: Boolean, val attachments: Boolean, val importance: String, val preview: String, val displayTo: String = "")
/** item=true: an attached e-mail / meeting (ItemAttachment); downloading it gives its text. */
class MailFile(val id: String, val name: String, val type: String, val size: Long, val inline: Boolean, val item: Boolean = false, val cid: String = "")
class MailMessage(val h: MailHeader, val kind: String, val to: List<MailBox>, val cc: List<MailBox>, val body: String, val files: List<MailFile>, val invite: JSONObject?)
class CalEvent(val id: String, val subject: String, val start: String, val end: String, val location: String, val organizer: MailBox?, val allDay: Boolean, val response: String, val cancelled: Boolean, val meeting: Boolean, val created: String = "",
    val recurring: Boolean = false, val freeBusy: String = "", val categories: List<String> = emptyList())
/** The reading view's message: the HTML body (when the mail has one), draft flag and send time on top of [MailMessage]. */
class MailFull(val m: MailMessage, val html: String, val isHtml: Boolean, val isDraft: Boolean, val sent: String)

/** Mailbox operations (ported from yaver's EWS object). Works the same over EWS or Outlook web. */
class Exchange(val t: MailTransport) {
    var noPreview = false
    /** The server refused item:DisplayTo in a list (old servers): lists without it. */
    var noDisplayTo = false

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

        /**
         * Server times → epoch ms. EWS sends UTC ("…Z"), OWA the request's time zone ("…+03:00"); both are exact.
         * A time without zone is read as local time; "/Date(ms)/" (old OWA JSON) and plain dates are accepted too.
         */
        fun parseTime(s0: String): Long {
            val s = s0.trim()
            if (s.isEmpty()) return 0
            Regex("^/Date\\((-?\\d+)([+-]\\d{4})?\\)/$").find(s)?.let { return it.groupValues[1].toLong() }
            return try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) {
                try { Instant.parse(s).toEpochMilli() } catch (e2: Exception) {
                    try { LocalDateTime.parse(s.take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (e3: Exception) {
                        try { java.time.LocalDate.parse(s.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (e4: Exception) { 0 }
                    }
                }
            }
        }

        /** A <Mailbox> (or any element with Name / EmailAddress children) → MailBox. X.500 addresses (/o=…) are not e-mail addresses. */
        fun mailbox(mb: Element): MailBox {
            val name = own(mb, "Name").trim()
            var email = own(mb, "EmailAddress").trim()
            if (email.startsWith("/o=", true) || email.startsWith("/cn=", true)) email = ""
            return MailBox(name, email)
        }

        /** The person in a From / Sender / Organizer / Attendee / Resolution element. */
        fun box(el: Element?): MailBox? {
            if (el == null) return null
            val mb = Xml.kids(el, "Mailbox").firstOrNull() ?: el.takeIf { e -> Xml.children(e).any { it.localName == "EmailAddress" } } ?: return null
            return mailbox(mb).takeIf { it.name.isNotEmpty() || it.email.isNotEmpty() }
        }

        /** The people in ToRecipients / CcRecipients / … of an item. Tolerates recipients that are not wrapped in <Mailbox>. */
        fun boxes(el: Element?, name: String): List<MailBox> {
            val c = (el?.let { e -> Xml.children(e).firstOrNull { it.localName == name } } ?: Xml.kids(el, name).firstOrNull()) ?: return emptyList()
            val mbs = Xml.kids(c, "Mailbox").ifEmpty { Xml.children(c).filter { x -> Xml.children(x).any { it.localName == "EmailAddress" || it.localName == "Name" } } }
            return mbs.map { mailbox(it) }.filter { it.name.isNotEmpty() || it.email.isNotEmpty() }
        }

        /** Names from DisplayTo / DisplayCc ("A; B"), for when the server did not send the recipient list. */
        fun names(display: String): List<MailBox> = display.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { MailBox(it, "") }

        /** Text of [el]'s own child [name] (not a nested item's), else the first such element anywhere below it. */
        fun own(el: Element?, name: String): String {
            if (el == null) return ""
            return Xml.children(el).firstOrNull { it.localName == name }?.textContent ?: Xml.txt(el, name)
        }

        /** EWS element name for an item; OWA calls meeting messages "MeetingRequestMessage" etc. */
        fun kindOf(el: Element): String = el.localName.let { if (it.startsWith("Meeting") && it.endsWith("Message")) it.removeSuffix("Message") else it }

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
    }

    private fun call(inner: String, timeout: Int = 60000, cancel: Cancel? = null) = t.call(inner, timeout, cancel)

    private fun idOf(el: Element?): Pair<String, String>? {
        val i = el?.let { e -> Xml.children(e).firstOrNull { it.localName == "ItemId" } } ?: Xml.kids(el, "ItemId").firstOrNull() ?: return null
        return Pair(i.getAttribute("Id"), i.getAttribute("ChangeKey"))
    }

    private fun idOf(d: Document) = idOf(d.documentElement)

    private fun header(m: Element): MailHeader {
        val id = idOf(m)
        val from = box(Xml.children(m).firstOrNull { it.localName == "From" }) ?: box(Xml.children(m).firstOrNull { it.localName == "Sender" })
        return MailHeader(
            id?.first ?: "", id?.second ?: "", own(m, "Subject").ifEmpty { "(no subject)" }, from,
            own(m, "DateTimeReceived").ifEmpty { own(m, "DateTimeSent") }, own(m, "IsRead") != "false", own(m, "HasAttachments") == "true",
            own(m, "Importance").ifEmpty { "Normal" }, own(m, "Preview").take(400), own(m, "DisplayTo").trim()
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
        if (!noDisplayTo) props.add("item:DisplayTo")
        val restr = if (unread && query.isEmpty()) "<m:Restriction><t:IsEqualTo><t:FieldURI FieldURI=\"message:IsRead\"/><t:FieldURIOrConstant><t:Constant Value=\"false\"/></t:FieldURIOrConstant></t:IsEqualTo></m:Restriction>" else ""
        val body = "<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape>" +
            "<m:IndexedPageItemView MaxEntriesReturned=\"${limit.coerceIn(1, 100)}\" Offset=\"${maxOf(0, offset)}\" BasePoint=\"Beginning\"/>$restr" +
            "<m:SortOrder><t:FieldOrder Order=\"Descending\"><t:FieldURI FieldURI=\"item:DateTimeReceived\"/></t:FieldOrder></m:SortOrder>" +
            "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"${folder(folder)}\"/></m:ParentFolderIds>" +
            (if (query.isNotEmpty()) "<m:QueryString>${Xml.esc(query)}</m:QueryString>" else "") + "</m:FindItem>"
        val doc = try { call(body, cancel = cancel) } catch (e: MailException) {
            if (!noDisplayTo && Regex("DisplayTo", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) { noDisplayTo = true; return find(folder, query, unread, limit, offset, cancel) }
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
        val kind = kindOf(it)
        var invite: JSONObject? = null
        if (kind.contains("MeetingRequest") || kind.contains("MeetingCancellation")) {
            invite = inviteOf(it)
            if (invite.optString("start").isEmpty()) try { invite = inviteDetails(id, cancel) } catch (_: Exception) {}
        }
        val files = filesOf(it)
        val body = own(it, "Body").replace("\r\n", "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        // the recipient list; when a server leaves it out, the names it shows in DisplayTo / DisplayCc
        val to = boxes(it, "ToRecipients").ifEmpty { names(own(it, "DisplayTo")) }
        val cc = boxes(it, "CcRecipients").ifEmpty { names(own(it, "DisplayCc")) }
        return MailMessage(h, kind, to, cc, body, files, invite)
    }

    /** File attachments and attached items (forwarded e-mails, meetings) of an item. */
    private fun filesOf(it: Element): List<MailFile> {
        fun attId(a: Element) = Xml.kids(a, "AttachmentId").firstOrNull()?.getAttribute("Id") ?: ""
        val files = Xml.kids(it, "FileAttachment").map { a ->
            MailFile(attId(a), own(a, "Name"), own(a, "ContentType"), own(a, "Size").toLongOrNull() ?: 0, own(a, "IsInline") == "true", cid = own(a, "ContentId").trim().removePrefix("<").removeSuffix(">"))
        }
        val items = Xml.kids(it, "ItemAttachment").map { a ->
            MailFile(attId(a), own(a, "Name").ifEmpty { "attached item" }, own(a, "ContentType").ifEmpty { "message/rfc822" }, own(a, "Size").toLongOrNull() ?: 0, false, item = true)
        }
        return (files + items).filter { f -> f.id.isNotEmpty() }
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

    /** Downloads one attachment. An attached e-mail comes back as readable text (name ends in .txt). */
    fun attachment(attId: String, cancel: Cancel? = null): Attachment {
        val owa = t.unwrap() as? OwaTransport
        val doc = try {
            call("<m:GetAttachment><m:AttachmentIds><t:AttachmentId Id=\"${Xml.esc(attId)}\"/></m:AttachmentIds></m:GetAttachment>", 120000, cancel)
        } catch (e: MailException) {
            // Outlook on the web: its own download address works where the JSON GetAttachment is refused
            if (owa != null && e.code != "ErrorItemNotFound") owa.download(attId, cancel)?.let { return it }
            throw e
        }
        val a = Xml.kids(doc, "FileAttachment").firstOrNull()
        if (a != null) {
            val b64 = own(a, "Content").replace(Regex("\\s+"), "")
            if (b64.isEmpty() && owa != null) owa.download(attId, cancel)?.let { d -> return Attachment(own(a, "Name"), own(a, "ContentType").ifEmpty { d.type }, d.bytes) }
            return Attachment(own(a, "Name"), own(a, "ContentType").ifEmpty { "application/octet-stream" }, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        }
        val ia = Xml.kids(doc, "ItemAttachment").firstOrNull() ?: throw MailException(Y.t("attachment not found", "ek bulunamadı"))
        val item = Xml.children(ia).firstOrNull { x -> Xml.children(x).any { it.localName == "Subject" || it.localName == "ItemId" || it.localName == "Body" } }
        val name = own(ia, "Name").ifEmpty { item?.let { own(it, "Subject") } ?: "" }.ifEmpty { "attached item" }
        val text = StringBuilder()
        if (item != null) {
            text.append("Subject: ").append(own(item, "Subject")).append('\n')
            box(Xml.children(item).firstOrNull { it.localName == "From" })?.let { text.append("From: ").append(it).append('\n') }
            boxes(item, "ToRecipients").ifEmpty { names(own(item, "DisplayTo")) }.takeIf { it.isNotEmpty() }?.let { l -> text.append("To: ").append(l.joinToString(", ")).append('\n') }
            boxes(item, "CcRecipients").ifEmpty { names(own(item, "DisplayCc")) }.takeIf { it.isNotEmpty() }?.let { l -> text.append("Cc: ").append(l.joinToString(", ")).append('\n') }
            own(item, "DateTimeSent").ifEmpty { own(item, "DateTimeReceived") }.takeIf { it.isNotEmpty() }?.let { text.append("Date: ").append(MailTools.fmt(parseTime(it))).append('\n') }
            if (own(item, "Start").isNotEmpty()) text.append("When: ").append(MailTools.fmt(parseTime(own(item, "Start")))).append(" – ").append(MailTools.fmt(parseTime(own(item, "End")))).append('\n')
            val bodyEl = Xml.children(item).firstOrNull { it.localName == "Body" }
            val body = bodyEl?.textContent ?: ""
            val isHtml = bodyEl?.getAttribute("BodyType")?.equals("HTML", true) == true || Regex("^\\s*<(!doctype|html|div|p|table|body|meta)", RegexOption.IGNORE_CASE).containsMatchIn(body)
            text.append('\n').append(if (isHtml) Html.toText(body) else body.replace("\r\n", "\n"))
        }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+"), "_").take(120)
        return Attachment(if (safe.endsWith(".txt", true)) safe else "$safe.txt", "text/plain", text.toString().trim().toByteArray(Charsets.UTF_8))
    }

    /** kind: new | reply | replyall | forward. send=false saves to Drafts. Returns the new item id (drafts) or "". */
    fun compose(kind: String, ref: String, to: List<String>, cc: List<String>, subject: String, bodyText: String, send: Boolean, cancel: Cancel? = null): String {
        val disp = if (send) "SendAndSaveCopy" else "SaveOnly"
        val folder = if (send) "sentitems" else "drafts"
        val html = textToHtml(bodyText)
        val item = when (kind) {
            "reply", "replyall", "forward" -> {
                val tag = when (kind) { "reply" -> "ReplyToItem"; "replyall" -> "ReplyAllToItem"; else -> "ForwardItem" }
                "<t:$tag>${if (to.isNotEmpty() && kind == "forward") "<t:ToRecipients>${mbx(to)}</t:ToRecipients>" else ""}${if (cc.isNotEmpty()) "<t:CcRecipients>${mbx(cc)}</t:CcRecipients>" else ""}${refId(ref, cancel)}<t:NewBodyContent BodyType=\"HTML\">${Xml.esc(html)}</t:NewBodyContent></t:$tag>"
            }
            else -> "<t:Message><t:Subject>${Xml.esc(subject)}</t:Subject><t:Body BodyType=\"HTML\">${Xml.esc(html)}</t:Body>${if (to.isNotEmpty()) "<t:ToRecipients>${mbx(to)}</t:ToRecipients>" else ""}${if (cc.isNotEmpty()) "<t:CcRecipients>${mbx(cc)}</t:CcRecipients>" else ""}</t:Message>"
        }
        val doc = call("<m:CreateItem MessageDisposition=\"$disp\"><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"$folder\"/></m:SavedItemFolderId><m:Items>$item</m:Items></m:CreateItem>", cancel = cancel)
        return idOf(doc)?.first ?: ""
    }

    /**
     * The item's current ChangeKey (its version). Exchange refuses SendItem and replies / forwards / meeting responses
     * whose ReferenceItemId has no ChangeKey ("ChangeKey is required for this operation"). "" when it cannot be read.
     */
    private fun changeKey(id: String, cancel: Cancel?): String = try {
        idOf(call("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:GetItem>", cancel = cancel))?.second ?: ""
    } catch (e: CancelledException) { throw e } catch (e: Exception) { Diag.i("mail", "ChangeKey not readable: ${e.message}"); "" }

    private fun refId(id: String, cancel: Cancel?): String {
        val ck = changeKey(id, cancel)
        return "<t:ReferenceItemId Id=\"${Xml.esc(id)}\"${if (ck.isNotEmpty()) " ChangeKey=\"${Xml.esc(ck)}\"" else ""}/>"
    }

    fun sendDraft(id: String, cancel: Cancel? = null) {
        val cur = Pair(id, changeKey(id, cancel))
        call("<m:SendItem SaveItemToFolder=\"true\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"${if (!cur?.second.isNullOrEmpty()) " ChangeKey=\"${Xml.esc(cur?.second)}\"" else ""}/></m:ItemIds><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"sentitems\"/></m:SavedItemFolderId></m:SendItem>", cancel = cancel)
    }

    fun markRead(id: String, read: Boolean, cancel: Cancel? = null) {
        call("<m:UpdateItem MessageDisposition=\"SaveOnly\" ConflictResolution=\"AlwaysOverwrite\"><m:ItemChanges><t:ItemChange><t:ItemId Id=\"${Xml.esc(id)}\"/><t:Updates><t:SetItemField><t:FieldURI FieldURI=\"message:IsRead\"/><t:Message><t:IsRead>$read</t:IsRead></t:Message></t:SetItemField></t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>", cancel = cancel)
    }

    fun move(id: String, folderName: String, cancel: Cancel? = null): String {
        val doc = call("<m:MoveItem><m:ToFolderId><t:DistinguishedFolderId Id=\"${folder(folderName)}\"/></m:ToFolderId><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:MoveItem>", cancel = cancel)
        return idOf(doc)?.first ?: id
    }

    /** Delete: to Deleted Items (returns the moved item's new id, which Undo needs; null when the server only deleted it), or for good. */
    fun remove(id: String, permanent: Boolean, cancel: Cancel? = null): String? {
        if (permanent) {
            try { call("<m:DeleteItem DeleteType=\"HardDelete\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel) }
            catch (e: MailException) { if (e.code == "ErrorItemNotFound") throw e; call("<m:DeleteItem DeleteType=\"SoftDelete\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel) }
            return null
        }
        return try { move(id, "deleteditems", cancel) } catch (e: MailException) {
            if (e.code == "ErrorItemNotFound") throw e
            call("<m:DeleteItem DeleteType=\"MoveToDeletedItems\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel)
            null
        }
    }

    /** Calendar items overlapping [start, end) (recurring meetings expanded), sorted by start. */
    fun events(start: Long, end: Long, cancel: Cancel? = null, max: Int = 500): List<CalEvent> {
        val props = listOf("item:Subject", "calendar:Start", "calendar:End", "calendar:Location", "calendar:Organizer", "calendar:IsAllDayEvent", "calendar:MyResponseType", "calendar:IsCancelled", "calendar:IsMeeting", "item:DateTimeCreated")
        val doc = call("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape><m:CalendarView MaxEntriesReturned=\"${max.coerceIn(1, 1000)}\" StartDate=\"${iso(start)}\" EndDate=\"${iso(end)}\"/><m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds></m:FindItem>", cancel = cancel)
        val items = Xml.kids(doc, "Items").firstOrNull() ?: return emptyList()
        return Xml.children(items).map { c ->
            CalEvent(idOf(c)?.first ?: "", Xml.txt(c, "Subject").ifEmpty { "(no title)" }, Xml.txt(c, "Start"), Xml.txt(c, "End"), Xml.txt(c, "Location"),
                box(Xml.kids(c, "Organizer").firstOrNull()), Xml.txt(c, "IsAllDayEvent") == "true", Xml.txt(c, "MyResponseType"), Xml.txt(c, "IsCancelled") == "true", Xml.txt(c, "IsMeeting") == "true", Xml.txt(c, "DateTimeCreated"))
        }.sortedBy { parseTime(it.start) }.also { list ->
            if (cancel == null || max >= 100) try { FiveBrief.offerEvents(start, end, list) } catch (_: Throwable) {}
        }
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
        call("<m:CreateItem MessageDisposition=\"SendAndSaveCopy\"><m:Items><t:$tag>${if (message.isNotEmpty()) "<t:Body BodyType=\"Text\">${Xml.esc(message)}</t:Body>" else ""}${refId(id, cancel)}</t:$tag></m:Items></m:CreateItem>", cancel = cancel)
    }

    fun cancelEvent(id: String, cancel: Cancel? = null) {
        call("<m:DeleteItem DeleteType=\"MoveToDeletedItems\" SendMeetingCancellations=\"SendToAllAndSaveCopy\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel)
    }

    // ---------------------------------------------------------------- reading view, drafts and calendar views (ported from the full Outlook-style view)

    /** The server refused item:IsDraft (old servers): asks without it. */
    var noIsDraft = false
    /** The server refused IsRecurring / LegacyFreeBusyStatus / Categories in a calendar view. */
    var noCalExtras = false

    /** A message for the reading view: HTML body (BodyType HTML), attachments with their content ids, IsDraft. */
    fun getFull(id: String, cancel: Cancel? = null): MailFull {
        val props = listOf("message:ToRecipients", "message:CcRecipients", "item:Attachments", "item:DateTimeReceived", "item:DateTimeSent", "message:IsRead", "message:From", "item:Importance", "item:Body") + (if (noIsDraft) emptyList() else listOf("item:IsDraft"))
        val doc = try {
            call("<m:GetItem><m:ItemShape><t:BaseShape>Default</t:BaseShape><t:BodyType>HTML</t:BodyType><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:GetItem>", cancel = cancel)
        } catch (e: MailException) {
            if (!noIsDraft && Regex("IsDraft|property|schema", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) { noIsDraft = true; return getFull(id, cancel) }
            throw e
        }
        val it = Xml.kids(doc, "Items").firstOrNull()?.let { Xml.children(it).firstOrNull() } ?: throw MailException("message not found", "ErrorItemNotFound")
        val h = header(it)
        val kind = kindOf(it)
        var invite: JSONObject? = null
        if (kind.contains("MeetingRequest") || kind.contains("MeetingCancellation")) {
            invite = inviteOf(it)
            if (invite.optString("start").isEmpty()) try { invite = inviteDetails(id, cancel) } catch (_: Exception) {}
        }
        val bodyEl = Xml.children(it).firstOrNull { x -> x.localName == "Body" }
        val raw = bodyEl?.textContent ?: ""
        val isHtml = bodyEl?.getAttribute("BodyType")?.equals("HTML", true) == true || Regex("^\\s*<(!doctype|html|div|p|table|body|meta|head|span|font|style)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val text = (if (isHtml) Html.toText(raw) else raw).replace("\r\n", "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        val to = boxes(it, "ToRecipients").ifEmpty { names(own(it, "DisplayTo")) }
        val cc = boxes(it, "CcRecipients").ifEmpty { names(own(it, "DisplayCc")) }
        return MailFull(MailMessage(h, kind, to, cc, text, filesOf(it), invite), if (isHtml) raw else "", isHtml, own(it, "IsDraft") == "true", own(it, "DateTimeSent"))
    }

    /** Edits a saved draft in place: recipients, subject and body (HTML). Empty recipient lists are cleared. Returns the (possibly new) id. */
    fun updateDraftFull(id: String, to: List<String>, cc: List<String>, subject: String, html: String, cancel: Cancel? = null): String {
        fun set(uri: String, inner: String) = "<t:SetItemField><t:FieldURI FieldURI=\"$uri\"/><t:Message>$inner</t:Message></t:SetItemField>"
        fun del(uri: String) = "<t:DeleteItemField><t:FieldURI FieldURI=\"$uri\"/></t:DeleteItemField>"
        val clean = { l: List<String> -> l.map { it.trim() }.filter { it.isNotEmpty() } }
        val t = clean(to); val c = clean(cc)
        val ups = set("item:Subject", "<t:Subject>${Xml.esc(subject)}</t:Subject>") + set("item:Body", "<t:Body BodyType=\"HTML\">${Xml.esc(html)}</t:Body>") +
            (if (t.isNotEmpty()) set("message:ToRecipients", "<t:ToRecipients>${mbx(t)}</t:ToRecipients>") else del("message:ToRecipients")) +
            (if (c.isNotEmpty()) set("message:CcRecipients", "<t:CcRecipients>${mbx(c)}</t:CcRecipients>") else del("message:CcRecipients"))
        val doc = call("<m:UpdateItem MessageDisposition=\"SaveOnly\" ConflictResolution=\"AlwaysOverwrite\"><m:ItemChanges><t:ItemChange><t:ItemId Id=\"${Xml.esc(id)}\"/><t:Updates>$ups</t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>", cancel = cancel)
        return idOf(doc)?.first?.ifEmpty { null } ?: id
    }

    private fun attendeesJson(it: Element, name: String): JSONArray {
        val c = Xml.kids(it, name).firstOrNull() ?: return JSONArray()
        return JSONArray(Xml.kids(c, "Attendee").map { a -> val b = box(a); JSONObject().put("name", b?.name ?: "").put("email", b?.email ?: "").put("response", Xml.txt(a, "ResponseType")) })
    }

    /** One calendar item with its attendees and HTML description (the event details view). Times are epoch ms. */
    fun getEvent(id: String, cancel: Cancel? = null): JSONObject {
        val props = listOf("item:Subject", "item:Body", "calendar:Start", "calendar:End", "calendar:Location", "calendar:Organizer", "calendar:IsAllDayEvent", "calendar:MyResponseType", "calendar:IsCancelled", "calendar:IsMeeting", "calendar:IsRecurring", "calendar:RequiredAttendees", "calendar:OptionalAttendees", "calendar:LegacyFreeBusyStatus", "item:Categories")
        val doc = call("<m:GetItem><m:ItemShape><t:BaseShape>Default</t:BaseShape><t:BodyType>HTML</t:BodyType><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:GetItem>", cancel = cancel)
        val it = Xml.kids(doc, "Items").firstOrNull()?.let { Xml.children(it).firstOrNull() } ?: throw MailException("event not found", "ErrorItemNotFound")
        val org = box(Xml.kids(it, "Organizer").firstOrNull())
        val bodyEl = Xml.children(it).firstOrNull { x -> x.localName == "Body" }
        val raw = bodyEl?.textContent ?: ""
        val isHtml = bodyEl?.getAttribute("BodyType")?.equals("HTML", true) == true || Regex("^\\s*<", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        return JSONObject().put("id", idOf(it)?.first?.ifEmpty { null } ?: id).put("subject", own(it, "Subject").ifEmpty { "(no title)" })
            .put("start", parseTime(own(it, "Start"))).put("end", parseTime(own(it, "End"))).put("location", own(it, "Location"))
            .put("organizer", org?.let { JSONObject().put("name", it.name).put("email", it.email) } ?: JSONObject.NULL)
            .put("allDay", own(it, "IsAllDayEvent") == "true").put("response", own(it, "MyResponseType")).put("cancelled", own(it, "IsCancelled") == "true")
            .put("meeting", own(it, "IsMeeting") == "true").put("recurring", own(it, "IsRecurring") == "true").put("freeBusy", own(it, "LegacyFreeBusyStatus"))
            .put("categories", JSONArray(Xml.kids(it, "Categories").firstOrNull()?.let { c -> Xml.children(c).map { s -> s.textContent } } ?: emptyList<String>()))
            .put("required", attendeesJson(it, "RequiredAttendees")).put("optional", attendeesJson(it, "OptionalAttendees"))
            .put("html", if (isHtml) raw else "").put("text", (if (isHtml) Html.toText(raw) else raw).trim())
    }

    /** Calendar items overlapping [start, end) with recurrence, free/busy and categories (old servers: without them). */
    fun eventsFull(start: Long, end: Long, cancel: Cancel? = null): List<CalEvent> {
        val base = listOf("item:Subject", "calendar:Start", "calendar:End", "calendar:Location", "calendar:Organizer", "calendar:IsAllDayEvent", "calendar:MyResponseType", "calendar:IsCancelled", "calendar:IsMeeting")
        val props = if (noCalExtras) base else base + listOf("calendar:IsRecurring", "calendar:LegacyFreeBusyStatus", "item:Categories")
        val doc = try {
            call("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>${props.joinToString("") { "<t:FieldURI FieldURI=\"$it\"/>" }}</t:AdditionalProperties></m:ItemShape><m:CalendarView MaxEntriesReturned=\"500\" StartDate=\"${iso(start)}\" EndDate=\"${iso(end)}\"/><m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds></m:FindItem>", cancel = cancel)
        } catch (e: MailException) {
            if (!noCalExtras && Regex("property|schema|Categories|FreeBusy|Recurring", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) { noCalExtras = true; return eventsFull(start, end, cancel) }
            throw e
        }
        val items = Xml.kids(doc, "Items").firstOrNull() ?: return emptyList()
        return Xml.children(items).map { c ->
            CalEvent(idOf(c)?.first ?: "", own(c, "Subject").ifEmpty { "(no title)" }, own(c, "Start"), own(c, "End"), own(c, "Location"),
                box(Xml.kids(c, "Organizer").firstOrNull()), own(c, "IsAllDayEvent") == "true", own(c, "MyResponseType"), own(c, "IsCancelled") == "true", own(c, "IsMeeting") == "true", "",
                own(c, "IsRecurring") == "true", own(c, "LegacyFreeBusyStatus").ifEmpty { "Busy" }, Xml.kids(c, "Categories").firstOrNull()?.let { k -> Xml.children(k).map { s -> s.textContent } } ?: emptyList())
        }.sortedBy { parseTime(it.start) }
    }

    private fun attendeeXml(tag: String, list: List<String>) = if (list.isEmpty()) "" else "<t:$tag>${list.joinToString("") { "<t:Attendee>${mbx(listOf(it))}</t:Attendee>" }}</t:$tag>"

    /** New calendar item (all-day, required and optional attendees, HTML description); invitations go out when there are attendees. */
    fun createEventFull(subject: String, start: Long, end: Long, allDay: Boolean, location: String, html: String, attendees: List<String>, optional: List<String>, cancel: Cancel? = null): String {
        val inv = attendees.isNotEmpty() || optional.isNotEmpty()
        val doc = call("<m:CreateItem SendMeetingInvitations=\"${if (inv) "SendToAllAndSaveCopy" else "SendToNone"}\"><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"calendar\"/></m:SavedItemFolderId><m:Items><t:CalendarItem><t:Subject>${Xml.esc(subject)}</t:Subject>" +
            (if (html.isNotEmpty()) "<t:Body BodyType=\"HTML\">${Xml.esc(html)}</t:Body>" else "") +
            "<t:Start>${iso(start)}</t:Start><t:End>${iso(end)}</t:End>" + (if (allDay) "<t:IsAllDayEvent>true</t:IsAllDayEvent>" else "") +
            (if (location.isNotEmpty()) "<t:Location>${Xml.esc(location)}</t:Location>" else "") +
            attendeeXml("RequiredAttendees", attendees) + attendeeXml("OptionalAttendees", optional) +
            "</t:CalendarItem></m:Items></m:CreateItem>", cancel = cancel)
        return idOf(doc)?.first ?: ""
    }

    /**
     * Edits a calendar item: subject, time, all-day, location, description and attendees. A meeting's attendees get the
     * update ([notify]); for an appointment without attendees nothing is sent. Returns the (possibly new) id.
     */
    fun updateEvent(id: String, subject: String, start: Long, end: Long, allDay: Boolean, location: String, html: String, attendees: List<String>, optional: List<String>, notify: Boolean, cancel: Cancel? = null): String {
        fun set(uri: String, inner: String) = "<t:SetItemField><t:FieldURI FieldURI=\"$uri\"/><t:CalendarItem>$inner</t:CalendarItem></t:SetItemField>"
        fun del(uri: String) = "<t:DeleteItemField><t:FieldURI FieldURI=\"$uri\"/></t:DeleteItemField>"
        val ups = set("item:Subject", "<t:Subject>${Xml.esc(subject)}</t:Subject>") +
            set("item:Body", "<t:Body BodyType=\"HTML\">${Xml.esc(html)}</t:Body>") +
            set("calendar:Start", "<t:Start>${iso(start)}</t:Start>") + set("calendar:End", "<t:End>${iso(end)}</t:End>") +
            set("calendar:IsAllDayEvent", "<t:IsAllDayEvent>$allDay</t:IsAllDayEvent>") +
            (if (location.isNotEmpty()) set("calendar:Location", "<t:Location>${Xml.esc(location)}</t:Location>") else del("calendar:Location")) +
            (if (attendees.isNotEmpty()) set("calendar:RequiredAttendees", attendeeXml("RequiredAttendees", attendees)) else del("calendar:RequiredAttendees")) +
            (if (optional.isNotEmpty()) set("calendar:OptionalAttendees", attendeeXml("OptionalAttendees", optional)) else del("calendar:OptionalAttendees"))
        val send = if (notify) "SendToAllAndSaveCopy" else "SendToNone"
        val doc = call("<m:UpdateItem ConflictResolution=\"AlwaysOverwrite\" SendMeetingInvitationsOrCancellations=\"$send\"><m:ItemChanges><t:ItemChange><t:ItemId Id=\"${Xml.esc(id)}\"/><t:Updates>$ups</t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>", cancel = cancel)
        return idOf(doc)?.first?.ifEmpty { null } ?: id
    }

    /** Removes an appointment (or a meeting someone else organised) from the calendar without sending anything. */
    fun deleteEvent(id: String, cancel: Cancel? = null) {
        call("<m:DeleteItem DeleteType=\"MoveToDeletedItems\" SendMeetingCancellations=\"SendToNone\"><m:ItemIds><t:ItemId Id=\"${Xml.esc(id)}\"/></m:ItemIds></m:DeleteItem>", cancel = cancel)
    }

    class Person(val name: String, val email: String, val title: String, val dept: String)

    fun resolve(q: String, limit: Int = 8, cancel: Cancel? = null): List<Person> {
        if (q.trim().length < 2) return emptyList()
        val owa = t.unwrap() is OwaTransport
        fun req(shape: Boolean) = call("<m:ResolveNames ReturnFullContactData=\"true\" SearchScope=\"ActiveDirectoryContacts\"${if (shape) " ContactDataShape=\"Default\"" else ""}><m:UnresolvedEntry>${Xml.esc(q.trim())}</m:UnresolvedEntry></m:ResolveNames>", 20000, cancel)
        val doc = try {
            // Outlook web may leave the directory contact (with the real name) out unless the shape is named
            if (owa && !noContactShape) try { req(true) } catch (e: MailException) {
                if (Regex("NoResults|NameResolution|No results", RegexOption.IGNORE_CASE).containsMatchIn(e.code + " " + e.message)) throw e
                noContactShape = true; req(false)
            } else req(false)
        } catch (e: MailException) {
            if (Regex("NoResults|NameResolution|No results", RegexOption.IGNORE_CASE).containsMatchIn(e.code + " " + e.message)) return emptyList()
            throw e
        }
        val out = ArrayList<Person>()
        for (r in Xml.kids(doc, "Resolution")) {
            val b = box(r) ?: continue
            if (b.email.isEmpty() || out.any { it.email.equals(b.email, true) }) continue
            // the mailbox name is often just the account (a00058, t18503); the contact part has the real name
            val c = Xml.kids(r, "Contact").firstOrNull()
            val shown = listOf(own(c, "DisplayName"), Xml.txt(c, "FullName"), listOf(own(c, "GivenName"), own(c, "Surname")).filter { it.isNotBlank() }.joinToString(" "), Xml.txt(r, "DisplayName"))
                .map { it.trim() }.firstOrNull { it.isNotEmpty() && !accountLike(it) } ?: ""
            val name = shown.ifEmpty { realName(b.email, b.name) }
            out.add(Person(name, b.email, own(c, "JobTitle").ifEmpty { Xml.txt(r, "JobTitle") }, own(c, "Department").ifEmpty { Xml.txt(r, "Department") }))
        }
        return out.take(limit)
    }

    private var noContactShape = false

    /** "t18503", "a00058": an account id, not a person's name. */
    fun accountLike(n: String) = Regex("^[a-z]{0,2}\\d{4,}$", RegexOption.IGNORE_CASE).matches(n.trim())

    /**
     * A readable name for a directory entry whose name is an account id: the name seen in recent mail
     * (local index), else the address' local part ("emre.akyilmaz" → "Emre Akyilmaz").
     */
    fun realName(email: String, name: String): String {
        if (name.isNotBlank() && !accountLike(name) && !name.equals(email, true)) return name
        try { MailIndex.nameOf(email)?.let { return it } } catch (_: Throwable) {}
        val local = email.substringBefore('@')
        if (local.isEmpty() || accountLike(local)) return name.ifBlank { email }
        val loc = if (try { Y.tr } catch (_: Throwable) { false }) java.util.Locale("tr", "TR") else java.util.Locale.ROOT
        return local.split(Regex("[._-]+")).filter { it.isNotEmpty() && !it.all { ch -> ch.isDigit() } }
            .joinToString(" ") { w -> w.lowercase(loc).replaceFirstChar { ch -> ch.titlecase(loc) } }.ifEmpty { name.ifBlank { email } }
    }
}
