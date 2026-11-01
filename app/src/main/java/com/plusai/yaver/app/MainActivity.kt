package com.plusai.yaver.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One row of the chat. */
class ChatItem(val kind: String, var text: String, val extra: String = "", var ok: Boolean = true)

/** A run that keeps going when the screen goes away; the screen re-attaches when it comes back. */
object LiveRun {
    @Volatile var convoId: String? = null
    @Volatile var cancel: Cancel? = null
    val text = StringBuilder()
    @Volatile var status: String = ""
    @Volatile var screen: MainActivity? = null
    val busy: Boolean get() = convoId != null
}

class MainActivity : Activity() {
    companion object {
        private const val REQ_FILES = 51
        private const val REQ_SPEECH = 52
        private const val REQ_ZIP = 53
    }

    private lateinit var p: Palette
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var chips: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var empty: ScrollView
    private val items = ArrayList<ChatItem>()
    private val adapter = ChatAdapter()
    private var convo: Convo = Convo.create()
    private val pendingImages = ArrayList<String>()
    private val pendingFiles = ArrayList<String>()
    private var resumed = false
    private var voiceTurn = false
    private var lastRender = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        p = Palette(this)
        PermGate.activity = this
        buildUi()
        val cid = savedInstanceState?.getString("convo") ?: Prefs.get("currentConvo", "")
        val c = if (cid.isNotEmpty()) Convos.load(cid) else null
        if (c != null && !c.background) convo = c
        reload()
        showCrashIfAny()
        askNotificationPermission()
        if (Prefs.on("alwaysOn", true)) AgentService.start(this)
        if (Migration.needed()) Migration.runOnce { msg -> U.toast(this, msg); reload() }
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("convo", convo.id)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        PermGate.activity = this
        LiveRun.screen = this
        if (!LiveRun.busy) Convos.load(convo.id)?.let { if (it.messages.length() != convo.messages.length()) { convo = it; reload() } }
        else reload()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        if (LiveRun.screen === this) LiveRun.screen = null
        if (PermGate.activity === this) PermGate.activity = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        // top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(p.bar)
            setPadding(U.dp(context, 2), U.dp(context, 4), U.dp(context, 2), U.dp(context, 4))
        }
        bar.addView(U.iconButton(this, "☰", Color.WHITE) { startActivity(Intent(this, ConvosActivity::class.java)) })
        titleView = U.text(this, "yaver", 17f, Color.WHITE, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { rename() }
        }
        bar.addView(titleView, U.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(U.iconButton(this, "✉", Color.WHITE) { startActivity(Intent(this, MailActivity::class.java)) })
        bar.addView(U.iconButton(this, "＋", Color.WHITE) { newChat() })
        bar.addView(U.iconButton(this, "⚙", Color.WHITE) { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar, U.lp())

        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = this@MainActivity.adapter
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
            isStackFromBottom = false
            setPadding(U.dp(context, 10), U.dp(context, 6), U.dp(context, 10), U.dp(context, 6))
            clipToPadding = false
            setSelector(android.R.color.transparent)
        }
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = ScrollView(this)
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statusView = U.text(this, "", 12.5f, p.muted).apply { setPadding(U.dp(context, 16), U.dp(context, 2), U.dp(context, 16), U.dp(context, 2)); visibility = View.GONE }
        root.addView(statusView, U.lp())

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(U.dp(context, 8), 0, U.dp(context, 8), 0) }
        chipsScroll = HorizontalScrollView(this).apply { addView(chips); visibility = View.GONE; isHorizontalScrollBarEnabled = false }
        root.addView(chipsScroll, U.lp())

        // composer
        val comp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(U.dp(context, 6), U.dp(context, 6), U.dp(context, 6), U.dp(context, 8))
            setBackgroundColor(p.bg)
        }
        comp.addView(U.iconButton(this, "📎", p.muted) { pickFiles() })
        comp.addView(U.iconButton(this, "🎤", p.muted) { listen(false) })
        input = EditText(this).apply {
            hint = Y.t("Ask yaver…", "yaver'e sor…")
            textSize = 16f
            setTextColor(p.text); setHintTextColor(p.muted)
            background = U.round(p.surface, 22, context, p.line)
            setPadding(U.dp(context, 14), U.dp(context, 10), U.dp(context, 14), U.dp(context, 10))
            maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { syncSend() }
            })
        }
        comp.addView(input, U.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendBtn = U.iconButton(this, "➤", p.accent) { if (LiveRun.busy && LiveRun.convoId == convo.id) LiveRun.cancel?.cancel() else send() }
        comp.addView(sendBtn)
        root.addView(comp, U.lp())
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun syncSend() {
        val busyHere = LiveRun.busy && LiveRun.convoId == convo.id
        sendBtn.text = if (busyHere) "■" else "➤"
        sendBtn.setTextColor(if (busyHere) p.error else p.accent)
    }

    private fun buildEmpty() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(U.dp(context, 24), U.dp(context, 48), U.dp(context, 24), U.dp(context, 24)) }
        box.addView(U.text(this, "y", 64f, p.accent, true).apply { gravity = Gravity.CENTER })
        box.addView(U.text(this, Y.t("How can I help?", "Nasıl yardımcı olabilirim?"), 20f, p.text, true).apply { gravity = Gravity.CENTER; setPadding(0, U.dp(context, 4), 0, U.dp(context, 18)) })
        if (!Cfg.llmReady) {
            box.addView(U.text(this, Y.t("First, choose a language model and enter its API key.", "Önce bir dil modeli seçin ve API anahtarını girin."), 15f, p.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, U.dp(context, 12)) })
            box.addView(U.button(this, Y.t("Set up the model", "Modeli ayarla"), true) { startActivity(Intent(this, SettingsActivity::class.java)) })
        } else {
            val ideas = if (Y.tr) listOf("Bugün takvimimde ne var?", "Okunmamış e-postalarımı özetle", "Günümü planla", "Yarın sabah 8'de beni uyandır", "Bugünkü önemli haberler neler?")
            else listOf("What's on my calendar today?", "Summarize my unread e-mail", "Plan my day", "Wake me up at 8 tomorrow", "What are today's top news?")
            for (s in ideas) {
                box.addView(U.text(this, s, 15f, p.text).apply {
                    background = U.round(p.surface, 14, context, p.line)
                    setPadding(U.dp(context, 14), U.dp(context, 10), U.dp(context, 14), U.dp(context, 10))
                    setOnClickListener { input.setText(s); send() }
                }, U.lp().apply { topMargin = U.dp(8) })
            }
        }
        empty.removeAllViews()
        empty.addView(box)
    }

    // ---------------------------------------------------------------- chat model → rows

    private fun toolLabel(name: String, args: JSONObject): String {
        val tr = Y.tr
        val what = args.optString("query").ifEmpty { args.optString("url").ifEmpty { args.optString("title").ifEmpty { args.optString("subject").ifEmpty { args.optString("path").ifEmpty { args.optString("prompt") } } } } }
        val act = args.optString("action")
        val base = when (name) {
            "web_search" -> if (tr) "🔎 Web'de aranıyor" else "🔎 Searching the web"
            "read_page" -> if (tr) "📄 Sayfa okunuyor" else "📄 Reading a page"
            "mail" -> "✉️ " + (if (tr) "Posta" else "Mail") + if (act.isNotEmpty()) " · $act" else ""
            "calendar" -> "📅 " + (if (tr) "Takvim" else "Calendar") + if (act.isNotEmpty()) " · $act" else ""
            "people" -> if (tr) "👥 Rehber" else "👥 Address book"
            "memory" -> "🧠 " + (if (tr) "Hafıza" else "Memory") + if (act.isNotEmpty()) " · $act" else ""
            "run_code" -> if (tr) "⚙️ Kod çalışıyor" else "⚙️ Running code"
            "read_document" -> if (tr) "📑 Belge okunuyor" else "📑 Reading a document"
            "look_at_image" -> if (tr) "🖼 Resme bakılıyor" else "🖼 Looking at a picture"
            "phone_calendar" -> "📆 " + (if (tr) "Telefon takvimi" else "Phone calendar") + if (act.isNotEmpty()) " · $act" else ""
            "phone_alarm" -> if (tr) "⏰ Alarm/zamanlayıcı" else "⏰ Alarm/timer"
            "phone_reminder" -> if (tr) "🔔 Hatırlatma" else "🔔 Reminder"
            "schedule_task" -> if (tr) "🗓 Zamanlanmış görev" else "🗓 Scheduled task"
            "delegate_task" -> if (tr) "🤝 Yardımcı ajan" else "🤝 Helper agent"
            else -> "🔧 $name"
        }
        return if (what.isNotEmpty()) "$base: ${what.take(80)}" else base
    }

    private fun rebuildItems() {
        items.clear()
        val ms = convo.messages
        val toolNames = HashMap<String, Pair<String, JSONObject>>()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            when (m.optString("role")) {
                "user" -> {
                    val label = m.optString("label")
                    if (label.isNotEmpty()) { items.add(ChatItem("note", "⏰ $label")); continue }
                    val att = ArrayList<String>()
                    m.optJSONArray("images")?.let { a -> for (k in 0 until a.length()) att.add(a.getString(k)) }
                    m.optJSONArray("files")?.let { a -> for (k in 0 until a.length()) att.add(a.getString(k)) }
                    items.add(ChatItem("user", m.optString("display", m.optString("content")), att.joinToString("\n")))
                }
                "assistant" -> {
                    val tc = m.optJSONArray("tool_calls")
                    val text = m.optString("content")
                    if (text.isNotBlank()) items.add(ChatItem(if (m.optBoolean("error")) "error" else "bot", text))
                    if (tc != null) for (k in 0 until tc.length()) {
                        val call = tc.getJSONObject(k)
                        val f = call.optJSONObject("function") ?: continue
                        val args = try { JSONObject(f.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
                        toolNames[call.optString("id")] = Pair(f.optString("name"), args)
                    }
                }
                "tool" -> {
                    val info = toolNames[m.optString("tool_call_id")]
                    items.add(ChatItem("tool", if (info != null) toolLabel(info.first, info.second) else "🔧 " + m.optString("name"), m.optString("content"), m.optBoolean("ok", true)))
                }
            }
        }
        if (LiveRun.busy && LiveRun.convoId == convo.id) {
            if (LiveRun.text.isNotEmpty()) items.add(ChatItem("live", LiveRun.text.toString()))
            else items.add(ChatItem("typing", "…"))
        }
    }

    fun reload() {
        try { rebuildItems() } catch (e: Exception) { android.util.Log.w("yaver", "rows: $e") }
        adapter.notifyDataSetChanged()
        titleView.text = if (convo.messages.length() == 0) "yaver" else convo.title
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) buildEmpty()
        val busyHere = LiveRun.busy && LiveRun.convoId == convo.id
        statusView.visibility = if (busyHere && LiveRun.status.isNotEmpty()) View.VISIBLE else View.GONE
        statusView.text = LiveRun.status
        syncSend()
        list.post { list.setSelection(adapter.count - 1) }
    }

    /** Fast path while streaming: only the last row changes. */
    private fun updateLive() {
        val now = System.currentTimeMillis()
        if (now - lastRender < 70) return
        lastRender = now
        val last = items.lastOrNull()
        if (last != null && (last.kind == "live" || last.kind == "typing")) {
            items[items.size - 1] = ChatItem("live", LiveRun.text.toString())
        } else items.add(ChatItem("live", LiveRun.text.toString()))
        adapter.notifyDataSetChanged()
        list.setSelection(adapter.count - 1)
    }

    inner class ChatAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val it = items[position]
            val c = this@MainActivity
            val row = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, U.dp(c, 4), 0, U.dp(c, 4)) }
            when (it.kind) {
                "user" -> {
                    val tv = U.text(c, it.text, 15.5f, p.text).apply {
                        background = U.round(p.user, 16, c)
                        setPadding(U.dp(c, 14), U.dp(c, 10), U.dp(c, 14), U.dp(c, 10))
                        setTextIsSelectable(true)
                    }
                    row.gravity = Gravity.END
                    row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = U.dp(c, 48) })
                    if (it.extra.isNotEmpty()) for (f in it.extra.split("\n")) {
                        row.addView(U.text(c, "📎 " + f.substringAfterLast('/'), 12.5f, p.muted).apply { setOnClickListener { U.openLink(c, f) }; gravity = Gravity.END })
                    }
                }
                "bot", "live" -> {
                    val tv = TextView(c).apply {
                        textSize = 15.5f
                        setTextColor(p.text)
                        setLineSpacing(0f, 1.12f)
                        text = Markdown.render(c, it.text, (resources.displayMetrics.widthPixels * 0.8).toInt())
                        movementMethod = LinkMovementMethod.getInstance()
                        setPadding(U.dp(c, 4), U.dp(c, 2), U.dp(c, 4), U.dp(c, 2))
                        setOnLongClickListener { _ -> botMenu(it.text); true }
                    }
                    row.addView(tv, U.lp())
                }
                "tool" -> {
                    val tv = U.text(c, (if (it.ok) "" else "⚠ ") + it.text, 13f, if (it.ok) p.muted else p.error).apply {
                        background = U.round(p.tool, 10, c)
                        setPadding(U.dp(c, 10), U.dp(c, 6), U.dp(c, 10), U.dp(c, 6))
                        maxLines = 2
                        setOnClickListener { _ -> showToolResult(it) }
                    }
                    row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                "typing" -> row.addView(U.text(c, Y.t("yaver is thinking…", "yaver düşünüyor…"), 14f, p.muted).apply { setTypeface(typeface, Typeface.ITALIC) })
                "note" -> row.addView(U.text(c, it.text, 13f, p.muted).apply { gravity = Gravity.CENTER }, U.lp())
                "error" -> row.addView(U.text(c, it.text, 14.5f, p.error).apply { setTextIsSelectable(true); background = U.round(p.tool, 10, c); setPadding(U.dp(c, 12), U.dp(c, 8), U.dp(c, 12), U.dp(c, 8)) }, U.lp())
            }
            return row
        }
    }

    private fun showToolResult(it: ChatItem) {
        val tv = U.text(this, clip(it.extra, 20000), 13f, p.text).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE; setPadding(U.dp(context, 16), U.dp(context, 8), U.dp(context, 16), U.dp(context, 8)) }
        AlertDialog.Builder(this).setTitle(it.text).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("OK", null).show()
    }

    private fun botMenu(text: String) {
        val opts = arrayOf(Y.t("Copy", "Kopyala"), Y.t("Share", "Paylaş"), Y.t("Read aloud", "Sesli oku"))
        AlertDialog.Builder(this).setItems(opts) { _, w ->
            when (w) {
                0 -> { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("yaver", text)); U.toast(this, Y.t("Copied", "Kopyalandı")) }
                1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "yaver"))
                2 -> Speaker.speak(text)
            }
        }.show()
    }

    // ---------------------------------------------------------------- sending

    fun send(textIn: String? = null) {
        val text = (textIn ?: input.text.toString()).trim()
        if (text.isEmpty() && pendingFiles.isEmpty() && pendingImages.isEmpty()) return
        if (LiveRun.busy) { U.toast(this, Y.t("yaver is still working on the previous request — tap ■ to stop it.", "yaver önceki isteği bitirmedi — durdurmak için ■'e dokunun.")); return }
        if (!Cfg.llmReady) { startActivity(Intent(this, SettingsActivity::class.java)); U.toast(this, Y.t("Choose a model and API key first.", "Önce model ve API anahtarı seçin.")); return }
        input.setText("")
        val imgs = ArrayList(pendingImages); val files = ArrayList(pendingFiles)
        pendingImages.clear(); pendingFiles.clear(); renderChips()
        val speakReply = voiceTurn && Prefs.on("speakVoice", true)
        voiceTurn = false
        val c = convo
        Prefs.put("currentConvo", c.id)
        val cancel = Cancel()
        LiveRun.convoId = c.id; LiveRun.cancel = cancel; LiveRun.text.setLength(0); LiveRun.status = ""
        // show the user's message immediately
        c.messages.put(Agent.userMessage(text.ifEmpty { Y.t("(see attachments)", "(eklere bakın)") }, imgs, files))
        rebuildItems(); c.messages.remove(c.messages.length() - 1)
        reload()
        Y.bg {
            var answer = ""
            var err: String? = null
            try {
                answer = Agent.run(c, text.ifEmpty { Y.t("(see attachments)", "(eklere bakın)") }, imgs, files, Listener(c), cancel)
            } catch (e: CancelledException) {
            } catch (e: Throwable) {
                err = e.message ?: e.toString()
            }
            LiveRun.convoId = null; LiveRun.cancel = null; LiveRun.text.setLength(0); LiveRun.status = ""
            Y.ui {
                val s = LiveRun.screen
                if (s != null && s.convo.id == c.id) { s.convo = Convos.load(c.id) ?: c; s.reload() } else s?.reload()
                if (speakReply && answer.isNotBlank()) Speaker.speak(answer.take(2500))
                val visible = s != null && s.resumed
                if (!visible && Prefs.on("notifyReplies", true)) {
                    Notifier.show(Y.app, Y.t("yaver replied", "yaver yanıtladı") + " · " + c.title, (err ?: answer).replace(Regex("[*#`]"), "").take(900), "open_convo", c.id)
                }
            }
        }
    }

    private class Listener(val c: Convo) : AgentListener {
        private fun screen(): MainActivity? = LiveRun.screen?.takeIf { it.convo.id == c.id && !it.isDestroyed }
        override fun onDelta(text: String) { LiveRun.text.append(text); LiveRun.status = ""; Y.ui { screen()?.updateLive() } }
        override fun onStep() { LiveRun.text.setLength(0); Y.ui { screen()?.let { it.convo = c; it.reload() } } }
        override fun onToolStart(name: String, args: JSONObject) { LiveRun.status = "⏳ " + (screen()?.toolLabel(name, args) ?: name); Y.ui { screen()?.reload() } }
        override fun onToolEnd(name: String, result: String, ok: Boolean) { LiveRun.status = ""; Y.ui { screen()?.let { it.convo = c; it.reload() } } }
        override fun onProgress(text: String) { LiveRun.status = "⏳ $text"; Y.ui { screen()?.let { s -> s.statusView.text = LiveRun.status; s.statusView.visibility = View.VISIBLE } } }
        override fun approve(title: String, details: String, danger: Boolean): Boolean {
            val l = CountDownLatch(1)
            var ok = false
            Y.ui {
                val a = LiveRun.screen
                if (a == null || a.isFinishing) { l.countDown(); return@ui }
                val b = AlertDialog.Builder(a).setTitle(title).setMessage(details.ifEmpty { title })
                    .setPositiveButton(if (danger) Y.t("Yes, do it", "Evet, yap") else Y.t("Allow", "İzin ver")) { _, _ -> ok = true; l.countDown() }
                    .setNegativeButton(Y.t("No", "Hayır")) { _, _ -> l.countDown() }
                    .setOnCancelListener { l.countDown() }
                    .create()
                b.show()
                if (danger) b.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Palette(a).error)
            }
            l.await(10, TimeUnit.MINUTES)
            return ok
        }
    }

    private fun newChat() {
        if (LiveRun.busy && LiveRun.convoId == convo.id) { U.toast(this, Y.t("Stop the running task first (■).", "Önce çalışan işi durdurun (■).")); return }
        convo = Convo.create()
        Prefs.put("currentConvo", "")
        reload()
        input.requestFocus()
    }

    fun openConvo(id: String) {
        val c = Convos.load(id) ?: return
        convo = c
        if (!c.background) Prefs.put("currentConvo", c.id)
        reload()
    }

    private fun rename() {
        if (convo.messages.length() == 0) return
        val e = U.input(this, Y.t("Title", "Başlık"), convo.title)
        AlertDialog.Builder(this).setTitle(Y.t("Rename chat", "Sohbeti yeniden adlandır")).setView(e)
            .setPositiveButton("OK") { _, _ -> convo.title = e.text.toString().trim().ifEmpty { convo.title }; Convos.save(convo); reload() }
            .setNegativeButton(Y.t("Cancel", "İptal"), null).show()
    }

    // ---------------------------------------------------------------- attachments, voice

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_FILES) } catch (e: Throwable) { U.toast(this, e.message ?: "no file picker") }
    }

    private fun addAttachment(uri: Uri) {
        Y.bg {
            try {
                val path = Workspace.importUri(this, uri)
                Y.ui { if (Workspace.isImage(path)) pendingImages.add(path) else pendingFiles.add(path); renderChips() }
            } catch (e: Throwable) { U.toast(this, Y.t("Could not add the file: ", "Dosya eklenemedi: ") + e.message) }
        }
    }

    private fun renderChips() {
        chips.removeAllViews()
        for (f in pendingImages + pendingFiles) {
            chips.addView(U.text(this, "📎 ${f.substringAfterLast('/')}  ✕", 13f, p.text).apply {
                background = U.round(p.surface, 14, context, p.line)
                setPadding(U.dp(context, 10), U.dp(context, 5), U.dp(context, 10), U.dp(context, 5))
                setOnClickListener { pendingImages.remove(f); pendingFiles.remove(f); renderChips() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = U.dp(6) })
        }
        chipsScroll.visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE
    }

    fun listen(autoSend: Boolean) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, Y.t("Speak to yaver", "yaver'e konuşun"))
        if (Y.tr) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        voiceTurn = autoSend
        try { startActivityForResult(i, REQ_SPEECH) } catch (e: Throwable) { U.toast(this, Y.t("Speech recognition is not available (install or enable the Google app).", "Konuşma tanıma yok (Google uygulamasını kurun veya etkinleştirin).")) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) { voiceTurn = false; return }
        when (requestCode) {
            REQ_FILES -> {
                val clip = data.clipData
                if (clip != null) for (k in 0 until clip.itemCount) addAttachment(clip.getItemAt(k).uri)
                else data.data?.let { addAttachment(it) }
            }
            REQ_SPEECH -> {
                val t = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                val cur = input.text.toString()
                input.setText(if (cur.isBlank()) t else "$cur $t")
                input.setSelection(input.text.length)
                if (voiceTurn) send()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) PermGate.done()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !Prefs.on("askedNotif", false)) {
            Prefs.set("askedNotif", true)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
    }

    private fun showCrashIfAny() {
        val f = File(filesDir, "last-crash.txt")
        if (!f.exists()) return
        val t = runCatching { f.readText() }.getOrDefault("")
        runCatching { f.copyTo(File(filesDir, "last-crash-shown.txt"), true) }
        f.delete()
        val tv = U.text(this, t.take(6000), 11f, p.text).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(U.dp(context, 16), U.dp(context, 8), U.dp(context, 16), U.dp(context, 8)) }
        AlertDialog.Builder(this).setTitle(Y.t("yaver closed unexpectedly last time", "yaver geçen sefer beklenmedik şekilde kapandı")).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(Y.t("Copy report", "Raporu kopyala")) { _, _ -> getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("crash", t)) }
            .setNegativeButton(Y.t("Close", "Kapat"), null).show()
    }

    private fun showKeyboard() {
        input.requestFocus()
        input.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 250)
    }

    // ---------------------------------------------------------------- launches: assistant, shortcuts, notifications, share

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val nid = i.getIntExtra(Keys.NOTIF_ID, -1)
        if (nid > 0) getSystemService(NotificationManager::class.java)?.cancel(nid)
        val action = i.getStringExtra(Keys.ACTION)
        val payload = i.getStringExtra(Keys.PAYLOAD)
        when {
            i.action == Intent.ACTION_ASSIST || i.action == "android.intent.action.VOICE_COMMAND" || i.action == Intent.ACTION_SEARCH_LONG_PRESS -> {
                if (!LiveRun.busy) newChat()
                if (Prefs.on("voiceOnAssist", false)) listen(true) else showKeyboard()
            }
            i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE -> {
                val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()).joinToString("\n").trim()
                if (text.isNotEmpty()) input.setText(text)
                if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { addAttachment(it) }
                else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAttachment(it) }
                showKeyboard()
            }
            i.action == Intent.ACTION_PROCESS_TEXT -> { input.setText(i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""); showKeyboard() }
            action == "voice" -> { if (!LiveRun.busy) newChat(); listen(true) }
            action == "assist" -> { if (!LiveRun.busy) newChat(); showKeyboard() }
            action == "new" -> { newChat(); showKeyboard() }
            action == "mail" -> startActivity(Intent(this, MailActivity::class.java))
            action == "mail_open" -> startActivity(Intent(this, MailActivity::class.java).putExtra("id", payload))
            action == "mail_login" -> startActivity(Intent(this, MailLoginActivity::class.java))
            action == "plan" -> { newChat(); send(Y.t("Plan my day: check today's company calendar and my unread / important e-mail, then give me today's meetings in order with what to prepare, the e-mails that need my answer (most important first), free blocks for focused work and a suggested order for the day.", "Günümü planla: bugünkü kurum takvimime ve okunmamış / önemli e-postalarıma bak; bugünkü toplantıları sırayla ve neye hazırlanmam gerektiğiyle, yanıt bekleyen e-postaları (en önemlisi önce), odaklı çalışma için boş zamanları ve gün için önerilen sırayı ver.")) }
            action == "ask" && !payload.isNullOrEmpty() -> { newChat(); send(payload) }
            action == "open_convo" && !payload.isNullOrEmpty() -> openConvo(payload)
        }
        i.removeExtra(Keys.ACTION); i.removeExtra(Keys.PAYLOAD)
        if (i.action != Intent.ACTION_MAIN) i.action = Intent.ACTION_MAIN
    }
}
