package dev.yaver.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Yaver's one screen: a transcript, a composer, and a row of panels.
 *
 * Views are built in code rather than XML. Nobody can attach a debugger to
 * this build, so every layout resource and theme attribute is a thing that can
 * fail with a message that points elsewhere. A LinearLayout assembled in
 * onCreate has none of that.
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var dueBar: TextView

    private var turns = mutableListOf<Store.Turn>()
    private var busy = false

    private var currentBubble: TextView? = null
    private var currentText = StringBuilder()

    // Bone paper, ink navy, and brass for anything waiting on the user.
    private val paper = Color.parseColor("#F4F1E8")
    private val surface = Color.parseColor("#FBF9F3")
    private val ink = Color.parseColor("#161A22")
    private val soft = Color.parseColor("#4A5163")
    private val faint = Color.parseColor("#858B9B")
    private val line = Color.parseColor("#D6D0BE")
    private val signal = Color.parseColor("#2C3A63")
    private val brass = Color.parseColor("#9A6A16")
    private val danger = Color.parseColor("#8E2C2C")

    /**
     * ui.post returns Boolean, which makes `override fun f() = ui.post { … }`
     * infer the wrong return type and fail to compile against a Unit-returning
     * interface. This wrapper returns Unit so the concise form works.
     */
    private fun post(block: () -> Unit) { ui.post(block) }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        Store.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }

        root.addView(buildTopBar())

        transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        scroll = ScrollView(this).apply {
            addView(transcript, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        dueBar = TextView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.parseColor("#F2E7CE"))
            setTextColor(brass)
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { showTasks() }
        }
        root.addView(dueBar)

        root.addView(buildComposer())
        root.addView(buildPanelBar())

        setContentView(root)

        turns = Store.loadSession()
        if (turns.isEmpty()) showWelcome() else replay()
        refreshDueBar()
        showLastCrash()

        io.execute {
            val dropped = Store.pruneMemories()
            if (dropped.isNotEmpty()) {
                ui.post { toast("Forgot ${dropped.size} memories unused for ${Store.MEMORY_TTL_DAYS} days") }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Store.saveSession(turns)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != Calendar.REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        toast(if (granted) "Calendar access granted — ask me again" else "Calendar access denied")
    }

    // ── chrome ───────────────────────────────────────────────────────────────

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
            setBackgroundColor(paper)
        }

        // The mark: Y with the Z carried as an exponent.
        val mark = TextView(this).apply {
            text = android.text.Html.fromHtml("Y<sup><small>z</small></sup>aver", 0)
            setTextColor(ink)
            textSize = 20f
            typeface = Typeface.SERIF
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(mark)

        bar.addView(TextView(this).apply {
            text = Store.model().substringAfterLast('/')
            setTextColor(faint)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(10), 0)
        })

        bar.addView(Button(this).apply {
            text = "New"
            isAllCaps = false
            textSize = 13f
            setTextColor(signal)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { newSession() }
        })
        return bar
    }

    private fun buildComposer(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(6))
            setBackgroundColor(paper)
        }

        input = EditText(this).apply {
            hint = "Ask, or say what you need done"
            setTextColor(ink)
            setHintTextColor(faint)
            textSize = 15f
            setBackgroundColor(surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(input)

        sendButton = Button(this).apply {
            text = "→"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(signal)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(46)).apply { leftMargin = dp(8) }
            setOnClickListener { if (busy) stop() else send() }
        }
        row.addView(sendButton)
        return row
    }

    private fun buildPanelBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), dp(10))
            setBackgroundColor(paper)
        }
        fun tab(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(soft)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        bar.addView(tab("Tasks") { showTasks() })
        bar.addView(tab("Calendar") { showCalendar() })
        bar.addView(tab("Memory") { showMemory() })
        bar.addView(tab("Settings") { showSettings() })
        bar.addView(tab("Debug") { showDebug() })
        return bar
    }

    // ── transcript ───────────────────────────────────────────────────────────

    private fun showWelcome() {
        transcript.removeAllViews()
        transcript.addView(TextView(this).apply {
            text = "At your orders."
            setTextColor(ink)
            textSize = 30f
            typeface = Typeface.SERIF
            setPadding(0, dp(30), 0, dp(10))
        })
        transcript.addView(TextView(this).apply {
            text = "I read what comes in, turn it into a plan, look things up, do the arithmetic. " +
                   "Nothing leaves your hands without your say-so."
            setTextColor(soft)
            textSize = 14f
            setPadding(0, 0, 0, dp(20))
        })
        listOf(
            "What's on my calendar this week?",
            "Remind me to call the hotel tomorrow at 10",
            "What are my open tasks?"
        ).forEach { starter ->
            transcript.addView(Button(this).apply {
                text = starter
                isAllCaps = false
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(ink)
                setBackgroundColor(surface)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener { input.setText(starter); send() }
            })
        }
    }

    private fun replay() {
        transcript.removeAllViews()
        turns.forEach { t ->
            when (t.role) {
                "user" -> addUserBubble(t.content)
                "assistant" -> {
                    val prose = Agent.stripCalls(t.content)
                    if (prose.isNotBlank()) addAgentBubble(prose)
                }
            }
        }
        scrollDown()
    }

    private fun addUserBubble(text: String) {
        transcript.addView(TextView(this).apply {
            this.text = text
            setTextColor(ink)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(4), 0, dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14); bottomMargin = dp(6) }
        })
    }

    private fun addAgentBubble(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = render(text)
            setTextColor(ink)
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(2), 0, dp(10))
        }
        transcript.addView(tv)
        return tv
    }

    /** Just enough markdown for what models emit: bold, code, headings, bullets. */
    private fun render(md: String): CharSequence {
        val html = md
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace(Regex("`([^`]+)`"), "<tt>$1</tt>")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?m)^#{1,6}\\s*(.+)$"), "<b>$1</b>")
            .replace(Regex("(?m)^\\s*[-*]\\s+(.+)$"), "•&nbsp;$1")
            .replace("\n", "<br>")
        return android.text.Html.fromHtml(html, 0)
    }

    private fun addToolStrip(name: String, detail: String): TextView {
        val tv = TextView(this).apply {
            text = "▰  $name  $detail"
            setTextColor(soft)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
        }
        transcript.addView(tv)
        return tv
    }

    private fun addCard(title: String, bodyText: String, flagged: Boolean, action: Pair<String, () -> Unit>?) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = title.uppercase(Locale.ROOT)
            setTextColor(if (flagged) brass else signal)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = bodyText
            setTextColor(ink)
            textSize = 14f
        })
        action?.let { (label, onClick) ->
            card.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (flagged) brass else signal)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnClickListener { onClick() }
            })
        }
        transcript.addView(card)
    }

    private fun scrollDown() = ui.post { scroll.fullScroll(View.FOCUS_DOWN) }

    // ── sending ──────────────────────────────────────────────────────────────

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (Store.setting(Store.API_KEY).isBlank()) {
            toast("Add your OpenRouter key in Settings first")
            showSettings()
            return
        }

        if (turns.isEmpty()) transcript.removeAllViews()
        input.setText("")
        addUserBubble(text)
        turns.add(Store.Turn("user", text))
        setBusy(true)
        scrollDown()

        io.execute {
            Agent.run(this, text, turns.dropLast(1), object : Agent.Listener {

                override fun onAssistantTurnStart() = post {
                    currentBubble = null
                    currentText = StringBuilder()
                }

                override fun onAssistantToken(delta: String) = post {
                    currentText.append(delta)
                    val prose = Agent.stripCalls(currentText.toString())
                    if (prose.isBlank()) return@post
                    if (currentBubble == null) currentBubble = addAgentBubble(prose)
                    else currentBubble!!.text = render(prose)
                    scrollDown()
                }

                override fun onToolStart(name: String, args: JSONObject) = post {
                    val detail = listOf("query", "title", "url", "expression", "text")
                        .firstNotNullOfOrNull { args.optString(it).ifBlank { null } } ?: ""
                    addToolStrip(name, detail.take(48))
                    scrollDown()
                }

                override fun onToolEnd(name: String, ok: Boolean, ms: Long, result: JSONObject) = post {
                    val last = transcript.getChildAt(transcript.childCount - 1)
                    if (last is TextView && last.text.startsWith("▰")) {
                        last.text = "${if (ok) "▪" else "✕"}  $name  ${ms}ms"
                        last.setTextColor(if (ok) soft else danger)
                        if (!ok) {
                            val why = result.optString("error").ifBlank { result.optString("instruction") }
                            if (why.isNotBlank()) last.append("\n    $why")
                        }
                    }
                }

                override fun onCard(card: JSONObject) = post {
                    when (card.optString("type")) {
                        "permission" -> addCard(
                            "Permission needed",
                            "I need access to your phone calendar to read or change events. Android asks you directly — nothing is shared anywhere.",
                            true, Pair("Grant calendar access", { Calendar.request(this@MainActivity) })
                        )
                        "event" -> addCard(
                            "Added to your calendar",
                            "${card.optString("title")}\n${card.optString("starts")}",
                            false, null
                        )
                        else -> { /* nothing to draw */ }
                    }
                    scrollDown()
                }

                override fun onFinished(reply: String) = post {
                    turns.add(Store.Turn("assistant", reply))
                    if (currentBubble == null && reply.isNotBlank()) {
                        addAgentBubble(reply)
                    } else {
                        currentBubble?.text = render(Agent.stripCalls(reply))
                    }
                    Store.saveSession(turns)
                    setBusy(false)
                    refreshDueBar()
                    scrollDown()

                    // Harvesting only when someone taps "New" means it almost
                    // never runs; every few exchanges is what makes memory real.
                    if (turns.count { it.role == "user" } % 3 == 0) {
                        io.execute { Agent.harvest(turns) }
                    }
                }

                override fun onFailed(message: String) = post {
                    addAgentBubble("**Stopped.** $message")
                    setBusy(false)
                    scrollDown()
                }
            })
        }
    }

    private fun stop() {
        Llm.cancelled = true
        setBusy(false)
        toast("Stopping…")
    }

    private fun setBusy(value: Boolean) {
        busy = value
        sendButton.text = if (value) "■" else "→"
        sendButton.setBackgroundColor(if (value) danger else signal)
    }

    private fun newSession() {
        if (turns.isNotEmpty()) {
            val snapshot = turns.toList()
            io.execute { Agent.harvest(snapshot) }
        }
        turns = mutableListOf()
        Store.clearSession()
        showWelcome()
    }

    // ── panels ───────────────────────────────────────────────────────────────

    private fun sheet(title: String, build: (LinearLayout) -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        build(content)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun rowLabel(text: String, colour: Int = ink, size: Float = 14f) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        textSize = size
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun showTasks() = sheet("Tasks") { box ->
        val now = System.currentTimeMillis()
        val list = Store.tasks().sortedWith(compareBy<Store.Task>(
            { it.done },
            { when (Store.urgency(it, now)) { "overdue" -> 0; "soon" -> 1; "later" -> 2; else -> 3 } },
            { it.due ?: Long.MAX_VALUE }
        ))
        if (list.isEmpty()) { box.addView(rowLabel("Nothing on the list.", faint)); return@sheet }

        list.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val urgency = Store.urgency(t, now)
            val main = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            main.addView(rowLabel(
                (if (t.done) "✓ " else "") + t.title,
                if (t.done) faint else ink
            ))
            val bits = mutableListOf<String>()
            t.due?.let { bits.add(Store.localIso(it).replace("T", " ").take(16)) }
            urgency?.takeIf { it != "later" }?.let { bits.add(it) }
            if (t.priority != "normal") bits.add(t.priority)
            if (bits.isNotEmpty()) main.addView(rowLabel(bits.joinToString(" · "),
                if (urgency == "overdue") danger else faint, 11f))
            row.addView(main)

            row.addView(Button(this).apply {
                text = if (t.done) "↺" else "✓"
                isAllCaps = false
                setTextColor(signal)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    val all = Store.tasks()
                    all.firstOrNull { it.id == t.id }?.let { it.done = !it.done }
                    Store.saveTasks(all)
                    refreshDueBar()
                    toast(if (t.done) "Reopened" else "Done")
                }
            })
            row.addView(Button(this).apply {
                text = "×"
                isAllCaps = false
                setTextColor(faint)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    Store.saveTasks(Store.tasks().filter { it.id != t.id })
                    refreshDueBar()
                    toast("Deleted")
                }
            })
            box.addView(row)
        }
    }

    private fun showCalendar() {
        if (!Calendar.canRead(this)) {
            AlertDialog.Builder(this)
                .setTitle("Calendar")
                .setMessage("Yaver needs permission to read and change your phone's calendar.")
                .setPositiveButton("Grant") { _, _ -> Calendar.request(this) }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        val now = System.currentTimeMillis()
        val events = Calendar.list(this, now, now + 30L * 86_400_000L, 60)
        sheet("Calendar · next 30 days") { box ->
            if (events.isEmpty()) { box.addView(rowLabel("Nothing scheduled.", faint)); return@sheet }
            var lastDay = ""
            events.forEach { e ->
                val day = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(e.start))
                if (day != lastDay) {
                    lastDay = day
                    box.addView(rowLabel(day, signal, 12f))
                }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val main = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                main.addView(rowLabel(e.title))
                val time = if (e.allDay) "all day" else
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.start))
                main.addView(rowLabel(time + if (e.location.isNotBlank()) " · ${e.location}" else "", faint, 11f))
                row.addView(main)
                row.addView(Button(this).apply {
                    text = "×"
                    isAllCaps = false
                    setTextColor(faint)
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener {
                        try {
                            if (Calendar.delete(this@MainActivity, e.id)) toast("Removed from calendar")
                            else toast("Could not remove it")
                        } catch (ex: Exception) { toast(ex.message ?: "Failed") }
                    }
                })
                box.addView(row)
            }
        }
    }

    private fun showMemory() = sheet("Memory") { box ->
        val all = Store.memories()
        val fading = all.filter { !Store.memoryProtected(it) && Store.memoryIdleDays(it) >= Store.MEMORY_WARN_DAYS }
        box.addView(rowLabel(
            "${all.size} stored · ${all.count { it.pinned }} pinned · ${fading.size} fading",
            if (fading.isEmpty()) soft else brass, 12f))

        val field = EditText(this).apply {
            hint = "Something I should know about you…"
            setBackgroundColor(surface)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        box.addView(field)
        box.addView(Button(this).apply {
            text = "Remember this"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(signal)
            setOnClickListener {
                val text = field.text.toString().trim()
                if (text.isEmpty()) return@setOnClickListener
                // Something the user wrote themselves is something they mean:
                // pinned, so it never quietly fades.
                Store.addMemory(text, importance = 0.9, pinned = true)
                field.setText("")
                toast("Remembered")
            }
        })

        if (all.isEmpty()) { box.addView(rowLabel("Nothing remembered yet.", faint)); return@sheet }

        all.forEach { m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val main = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            main.addView(rowLabel(m.text))
            val idle = Store.memoryIdleDays(m)
            val note = when {
                m.pinned -> "pinned — kept"
                Store.memoryProtected(m) -> "important — kept"
                idle >= Store.MEMORY_WARN_DAYS -> "unused $idle days — forgotten in ${Store.MEMORY_TTL_DAYS - idle}"
                else -> "recalled ${m.uses}×"
            }
            main.addView(rowLabel(note, if (idle >= Store.MEMORY_WARN_DAYS && !Store.memoryProtected(m)) brass else faint, 11f))
            row.addView(main)
            row.addView(Button(this).apply {
                text = if (m.pinned) "★" else "☆"
                isAllCaps = false
                setTextColor(brass)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    val list = Store.memories()
                    list.firstOrNull { it.id == m.id }?.let { it.pinned = !it.pinned }
                    Store.saveMemories(list)
                    toast(if (m.pinned) "Unpinned" else "Pinned")
                }
            })
            row.addView(Button(this).apply {
                text = "×"
                isAllCaps = false
                setTextColor(faint)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    Store.saveMemories(Store.memories().filter { it.id != m.id })
                    toast("Forgotten")
                }
            })
            box.addView(row)
        }
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        fun labelled(label: String, value: String, hint: String, password: Boolean = false): EditText {
            content.addView(rowLabel(label, soft, 12f))
            val field = EditText(this).apply {
                setText(value)
                this.hint = hint
                setBackgroundColor(surface)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            content.addView(field)
            return field
        }

        val keyField = labelled("OpenRouter API key", Store.setting(Store.API_KEY), "sk-or-v1-…", true)
        val modelField = labelled("Model", Store.model(), "anthropic/claude-sonnet-4.5")
        content.addView(rowLabel("Any slug from openrouter.ai/models. Small free models often ignore the tool format.", faint, 11f))
        val nameField = labelled("Your name", Store.setting(Store.USER_NAME), "How I should refer to you")
        val personaField = labelled("Standing instructions", Store.setting(Store.PERSONA),
            "e.g. Reply in Turkish. Keep summaries short.")

        content.addView(rowLabel("Calendar access", soft, 12f))
        content.addView(rowLabel(
            if (Calendar.canRead(this)) "Granted" else "Not granted",
            if (Calendar.canRead(this)) signal else brass, 12f))
        if (!Calendar.canRead(this)) {
            content.addView(Button(this).apply {
                text = "Grant calendar access"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(signal)
                setOnClickListener { Calendar.request(this@MainActivity) }
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save") { _, _ ->
                Store.setSetting(Store.API_KEY, keyField.text.toString().trim())
                Store.setSetting(Store.MODEL, modelField.text.toString().trim()
                    .ifBlank { "anthropic/claude-sonnet-4.5" })
                Store.setSetting(Store.USER_NAME, nameField.text.toString().trim())
                Store.setSetting(Store.PERSONA, personaField.text.toString().trim())
                toast("Saved")
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDebug() {
        val text = TextView(this).apply {
            this.text = Log.dump().ifBlank { "Nothing recorded yet." }
            setTextColor(soft)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Debug · ${Log.errorCount()} error(s)")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Copy") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("Yaver debug", Log.dump()))
                toast("Copied")
            }
            .setNegativeButton("Clear") { _, _ -> Log.clear() }
            .setNeutralButton("Close", null)
            .show()
    }

    // ── odds and ends ────────────────────────────────────────────────────────

    private fun refreshDueBar() {
        val now = System.currentTimeMillis()
        val urgent = Store.tasks().filter {
            val u = Store.urgency(it, now)
            u == "overdue" || u == "soon"
        }
        if (urgent.isEmpty()) { dueBar.visibility = View.GONE; return }
        val overdue = urgent.count { Store.urgency(it, now) == "overdue" }
        dueBar.visibility = View.VISIBLE
        dueBar.text = if (overdue > 0) "▲  $overdue task(s) overdue" else "▲  ${urgent.size} due soon"
        dueBar.setTextColor(if (overdue > 0) danger else brass)
    }

    private fun toast(message: String) = ui.post {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * There is no logcat on the other end of this app, so an unhandled
     * exception would otherwise be a silent disappearing act.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, "last-crash.txt").writeText(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    "  thread=${thread.name}\n" +
                    android.util.Log.getStackTraceString(error)
                )
            } catch (e: Throwable) { /* nothing useful left to do */ }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showLastCrash() {
        val file = File(filesDir, "last-crash.txt")
        if (!file.exists()) return
        val text = try { file.readText() } catch (e: Exception) { "" }
        file.delete()
        if (text.isBlank()) return
        Log.error("previous run crashed:\n$text")
        AlertDialog.Builder(this)
            .setTitle("Yaver crashed last time")
            .setMessage(text.take(3000))
            .setPositiveButton("Copy") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("Yaver crash", text))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}

/**
 * Anything shared to Yaver from another app. No UI: it writes, confirms and
 * finishes, so sharing never interrupts what the user was doing.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
        } else {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            Store.addForward(if (subject.isBlank()) text else "$subject\n\n$text", "shared")
            Toast.makeText(this, "Saved for Yaver", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
