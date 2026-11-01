package com.plusai.yaver.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A native inbox and calendar for the company mailbox (no web page). */
class MailActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var status: TextView
    private lateinit var lv: ListView
    private lateinit var detail: ScrollView
    private lateinit var folderBtn: TextView
    private var folder = "inbox"
    private var mode = "mail"
    private var mails: List<MailHeader> = emptyList()
    private var events: List<CalEvent> = emptyList()
    private var showing: MailMessage? = null
    private val folders = listOf("inbox" to Y.t("Inbox", "Gelen Kutusu"), "sentitems" to Y.t("Sent", "Gönderilenler"), "drafts" to Y.t("Drafts", "Taslaklar"), "archive" to Y.t("Archive", "Arşiv"), "junkemail" to Y.t("Junk", "Gereksiz"), "deleteditems" to Y.t("Deleted", "Silinmiş"))

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        p = Palette(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        val bar = U.bar(this, Y.t("Mail", "Posta"))
        bar.addView(U.iconButton(this, "📅", Color.WHITE) { mode = if (mode == "mail") "cal" else "mail"; showList(); load() })
        bar.addView(U.iconButton(this, "⟳", Color.WHITE) { load() })
        root.addView(bar, U.lp())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(U.dp(context, 12), U.dp(context, 6), U.dp(context, 12), U.dp(context, 2)) }
        folderBtn = U.text(this, "", 15f, p.accent, true).apply { setOnClickListener { chooseFolder() } }
        row.addView(folderBtn)
        status = U.text(this, "", 12.5f, p.muted).apply { setPadding(U.dp(context, 12), 0, 0, 0); maxLines = 3 }
        row.addView(status, U.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, U.lp())
        val frame = FrameLayout(this)
        lv = ListView(this).apply { divider = null }
        detail = ScrollView(this).apply { visibility = View.GONE }
        frame.addView(lv); frame.addView(detail)
        root.addView(frame, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        lv.setOnItemClickListener { _, _, pos, _ -> if (mode == "mail") open(mails[pos].id) else eventDialog(events[pos]) }
        val id = intent.getStringExtra("id")
        if (!id.isNullOrEmpty()) open(id)
        showList(); load()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() { if (detail.visibility == View.VISIBLE) { showList(); load() } else finish() }

    private fun showList() {
        detail.visibility = View.GONE; lv.visibility = View.VISIBLE
        folderBtn.text = if (mode == "mail") "▾ " + (folders.firstOrNull { it.first == folder }?.second ?: folder) else "📅 " + Y.t("Next 7 days", "Önümüzdeki 7 gün")
    }

    private fun chooseFolder() {
        if (mode != "mail") return
        AlertDialog.Builder(this).setItems(folders.map { it.second }.toTypedArray()) { _, w -> folder = folders[w].first; showList(); load() }.show()
    }

    private fun setStatus(s: String) = Y.ui { status.text = s }

    private fun notConnected(msg: String) = Y.ui {
        status.text = ""
        lv.adapter = null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(context, 20), U.dp(context, 30), U.dp(context, 20), U.dp(context, 20)) }
        box.addView(U.text(this, msg, 15f, p.text))
        box.addView(U.button(this, Y.t("Mail settings", "Posta ayarları"), true) { startActivity(Intent(this, SettingsActivity::class.java)) }, U.lp().apply { topMargin = U.dp(16) })
        box.addView(U.button(this, Y.t("Log in on the web page", "Web sayfasında oturum aç")) { startActivity(Intent(this, MailLoginActivity::class.java)) }, U.lp().apply { topMargin = U.dp(8) })
        detail.removeAllViews(); detail.addView(box); detail.visibility = View.VISIBLE; lv.visibility = View.GONE
    }

    private fun load() {
        setStatus(Y.t("Loading…", "Yükleniyor…"))
        Y.bg {
            try {
                if (mode == "mail") {
                    val r = Mail.use { it.find(folder, "", false, 50, 0) }
                    Y.ui { mails = r.items; lv.adapter = MailAdapter(); status.text = "${r.items.count { !it.read }} " + Y.t("unread", "okunmamış") + " · ${r.total}" }
                } else {
                    val s = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
                    val r = Mail.use { it.events(s, s + 7 * 86400000L) }
                    Y.ui { events = r.filter { !it.cancelled }; lv.adapter = EventAdapter(); status.text = "${events.size} " + Y.t("events", "etkinlik") }
                }
            } catch (e: Throwable) {
                if (!Cfg.mailConfigured) notConnected(Y.t("Company mail is not set up yet.", "Kurum postası henüz ayarlanmadı."))
                else notConnected((e.message ?: e.toString()))
            }
        }
    }

    private fun fmt(iso: String): String {
        val t = Exchange.parseTime(iso)
        if (t == 0L) return ""
        val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (sameDay) "HH:mm" else "d MMM", if (Y.tr) Locale("tr", "TR") else Locale.getDefault()).format(Date(t))
    }

    inner class MailAdapter : BaseAdapter() {
        override fun getCount() = mails.size
        override fun getItem(position: Int) = mails[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = mails[position]
            val c = this@MailActivity
            return LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(U.dp(c, 14), U.dp(c, 10), U.dp(c, 14), U.dp(c, 10))
                val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                top.addView(U.text(c, (if (!m.read) "● " else "") + (m.from?.short ?: "?"), 15f, p.text, !m.read).apply { maxLines = 1 }, U.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                top.addView(U.text(c, (if (m.attachments) "📎 " else "") + fmt(m.received), 12.5f, p.muted))
                addView(top)
                addView(U.text(c, m.subject, 14.5f, p.text, !m.read).apply { maxLines = 1 })
                if (m.preview.isNotBlank()) addView(U.text(c, m.preview.replace(Regex("\\s+"), " "), 13f, p.muted).apply { maxLines = 2 })
            }
        }
    }

    inner class EventAdapter : BaseAdapter() {
        override fun getCount() = events.size
        override fun getItem(position: Int) = events[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = events[position]
            val c = this@MailActivity
            val s = Exchange.parseTime(e.start); val en = Exchange.parseTime(e.end)
            return LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(U.dp(c, 14), U.dp(c, 10), U.dp(c, 14), U.dp(c, 10))
                addView(U.text(c, if (e.allDay) SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(s)) + " · " + Y.t("all day", "tüm gün") else "${MailTools.fmt(s)} – ${MailTools.hm(en)}", 12.5f, p.accent))
                addView(U.text(c, e.subject, 15f, p.text, true))
                val more = listOfNotNull(e.location.ifEmpty { null }, e.organizer?.short, if (e.meeting && e.response.isNotEmpty() && e.response != "Organizer") e.response else null).joinToString(" · ")
                if (more.isNotEmpty()) addView(U.text(c, more, 13f, p.muted))
            }
        }
    }

    private fun eventDialog(e: CalEvent) {
        val b = AlertDialog.Builder(this).setTitle(e.subject).setMessage(listOfNotNull("${MailTools.fmt(Exchange.parseTime(e.start))} – ${MailTools.hm(Exchange.parseTime(e.end))}", e.location.ifEmpty { null }, e.organizer?.toString()).joinToString("\n"))
        if (e.meeting && e.response != "Organizer") {
            b.setPositiveButton(Y.t("Accept", "Kabul")) { _, _ -> respond(e.id, "accept") }
            b.setNeutralButton(Y.t("Tentative", "Belki")) { _, _ -> respond(e.id, "tentative") }
            b.setNegativeButton(Y.t("Decline", "Reddet")) { _, _ -> respond(e.id, "decline") }
        } else b.setPositiveButton("OK", null)
        b.show()
    }

    private fun respond(id: String, r: String) {
        Y.bg { val m = try { Mail.use { it.respond(id, r, "") }; "✅" } catch (e: Throwable) { "❌ " + e.message }; U.toast(this, m); Y.ui { load() } }
    }

    private fun open(id: String) {
        setStatus(Y.t("Opening…", "Açılıyor…"))
        Y.bg {
            try {
                val m = Mail.use { it.get(id) }
                if (!m.h.read) try { Mail.use { it.markRead(id, true) } } catch (_: Throwable) {}
                Y.ui { showing = m; renderDetail(m); status.text = "" }
            } catch (e: Throwable) { setStatus("❌ " + e.message) }
        }
    }

    private fun renderDetail(m: MailMessage) {
        val c = this
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(c, 16), U.dp(c, 10), U.dp(c, 16), U.dp(c, 30)) }
        box.addView(U.text(c, m.h.subject, 19f, p.text, true))
        box.addView(U.text(c, Y.t("From: ", "Kimden: ") + (m.h.from?.toString() ?: "?"), 13.5f, p.muted).apply { setTextIsSelectable(true); setPadding(0, U.dp(c, 6), 0, 0) })
        if (m.to.isNotEmpty()) box.addView(U.text(c, Y.t("To: ", "Kime: ") + m.to.joinToString(", "), 13.5f, p.muted).apply { setTextIsSelectable(true) })
        if (m.cc.isNotEmpty()) box.addView(U.text(c, "Cc: " + m.cc.joinToString(", "), 13.5f, p.muted).apply { setTextIsSelectable(true) })
        box.addView(U.text(c, MailTools.fmt(Exchange.parseTime(m.h.received)), 13f, p.muted))
        m.invite?.let { inv ->
            box.addView(U.text(c, "📅 " + MailTools.fmt(Exchange.parseTime(inv.optString("start"))) + (if (inv.optString("location").isNotEmpty()) " · " + inv.optString("location") else ""), 14f, p.accent).apply { setPadding(0, U.dp(c, 8), 0, 0) })
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for ((k, l) in listOf("accept" to Y.t("Accept", "Kabul"), "tentative" to Y.t("Tentative", "Belki"), "decline" to Y.t("Decline", "Reddet")))
                row.addView(U.button(c, l) { respond(m.h.id, k) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = U.dp(c, 6) })
            box.addView(row, U.lp().apply { topMargin = U.dp(c, 6) })
        }
        val docs = m.files.filter { !(it.inline && it.type.startsWith("image/")) }
        for (f in docs) box.addView(U.text(c, "📎 ${f.name} (${Workspace.fmtBytes(f.size)})", 14f, p.accent).apply {
            setPadding(0, U.dp(c, 8), 0, 0)
            setOnClickListener { download(f) }
        })
        val actions = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(label: String, f: () -> Unit) = actions.addView(U.button(c, label) { f() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = U.dp(c, 6) })
        act(Y.t("Reply", "Yanıtla")) { compose("reply", m) }
        act(Y.t("All", "Tümü")) { compose("replyall", m) }
        act(Y.t("Forward", "İlet")) { compose("forward", m) }
        act("🗑") { AlertDialog.Builder(c).setMessage(Y.t("Move to Deleted Items?", "Silinmiş Öğeler'e taşınsın mı?")).setPositiveButton(Y.t("Delete", "Sil")) { _, _ -> Y.bg { try { Mail.use { it.remove(m.h.id, false) }; Y.ui { showList(); load() } } catch (e: Throwable) { U.toast(c, "❌ " + e.message) } } }.setNegativeButton(Y.t("Cancel", "İptal"), null).show() }
        box.addView(actions, U.lp().apply { topMargin = U.dp(c, 12) })
        box.addView(U.button(c, "✨ " + Y.t("Ask yaver about this e-mail", "Bu e-postayı yaver'e sor"), true) {
            val prompt = Y.t("Look at this e-mail (mail id=${m.h.id}, subject \"${m.h.subject}\" from ${m.h.from?.short}). Summarize it, tell me what is expected of me and suggest a reply.", "Şu e-postaya bak (mail id=${m.h.id}, konu \"${m.h.subject}\", gönderen ${m.h.from?.short}). Özetle, benden ne bekleniyor söyle ve bir yanıt öner.")
            startActivity(Intent(c, MainActivity::class.java).putExtra(Keys.ACTION, "ask").putExtra(Keys.PAYLOAD, prompt).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, U.lp().apply { topMargin = U.dp(c, 8) })
        box.addView(U.text(c, m.body, 15f, p.text).apply { setTextIsSelectable(true); setPadding(0, U.dp(c, 16), 0, 0); setLineSpacing(0f, 1.1f); android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.EMAIL_ADDRESSES or android.text.util.Linkify.PHONE_NUMBERS) })
        detail.removeAllViews(); detail.addView(box); detail.scrollTo(0, 0)
        detail.visibility = View.VISIBLE; lv.visibility = View.GONE
    }

    private fun download(f: MailFile) {
        U.toast(this, Y.t("Downloading ", "İndiriliyor: ") + f.name)
        Y.bg {
            try {
                val a = Mail.use { it.attachment(f.id) }
                val path = Workspace.write("mail", a.name.ifEmpty { f.name }, a.bytes)
                Y.ui { try { Workspace.open(this, Workspace.resolve(path)) } catch (e: Throwable) { U.toast(this, "Saved to $path") } }
            } catch (e: Throwable) { U.toast(this, "❌ " + e.message) }
        }
    }

    private fun compose(kind: String, m: MailMessage) {
        val c = this
        val to = U.input(c, Y.t("To (for forwarding)", "Kime (iletmek için)"))
        val body = U.input(c, Y.t("Your message", "Mesajınız"), multi = true)
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(c, 18), U.dp(c, 8), U.dp(c, 18), 0) }
        if (kind == "forward") box.addView(to, U.lp())
        box.addView(body, U.lp().apply { topMargin = U.dp(c, 8) })
        val title = when (kind) { "reply" -> Y.t("Reply", "Yanıtla"); "replyall" -> Y.t("Reply all", "Tümünü yanıtla"); else -> Y.t("Forward", "İlet") } + ": " + m.h.subject
        AlertDialog.Builder(c).setTitle(title).setView(box)
            .setPositiveButton(Y.t("Send", "Gönder")) { _, _ -> doCompose(kind, m, to.text.toString(), body.text.toString(), true) }
            .setNeutralButton(Y.t("Save draft", "Taslak kaydet")) { _, _ -> doCompose(kind, m, to.text.toString(), body.text.toString(), false) }
            .setNegativeButton(Y.t("Cancel", "İptal"), null).show()
    }

    private fun doCompose(kind: String, m: MailMessage, to: String, body: String, send: Boolean) {
        Y.bg {
            val r = try {
                Mail.use { it.compose(kind, m.h.id, to.split(Regex("[;,]")).map { s -> s.trim() }.filter { s -> s.isNotEmpty() }, emptyList(), "", body, send) }
                if (send) Y.t("✅ Sent", "✅ Gönderildi") else Y.t("✅ Saved in Drafts", "✅ Taslaklara kaydedildi")
            } catch (e: Throwable) { "❌ " + e.message }
            U.toast(this, r)
        }
    }
}
