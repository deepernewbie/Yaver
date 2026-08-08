package dev.yaver.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
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
    private var sessionId = ""
    private var harvesting = false
    private val DICTATE = 7301
    private var tts: android.speech.tts.TextToSpeech? = null
    private var lastAnswer = ""

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
            isFocusable = false
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

        sessionId = Store.currentSessionId()
        turns = Store.loadSession(sessionId)
        if (turns.isEmpty()) showWelcome() else replay()
        refreshDueBar()
        showLastCrash()
        Browser.start(this)
        askNotificationPermission()
        io.execute { Reminders.rescheduleAll(this) }
        handleLaunchPrompt(intent)

        io.execute {
            Store.pruneMessages()
            val dropped = Store.pruneMemories()
            if (dropped.isNotEmpty()) {
                ui.post { toast("Forgot ${dropped.size} memories unused for ${Store.MEMORY_TTL_DAYS} days") }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Store.saveSession(turns, sessionId)
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        tts = null
    }

    /**
     * Read the last answer aloud. Uses whatever voice the phone has, which on
     * a Turkish device means Turkish — a bundled engine would be a large
     * download and worse at it.
     */
    private fun speak() {
        if (lastAnswer.isBlank()) { toast("Nothing to read yet"); return }
        val engine = tts
        if (engine != null) {
            if (engine.isSpeaking) { engine.stop(); return }
            speakNow(engine)
            return
        }
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                post { toast("No speech engine on this phone") }
                return@TextToSpeech
            }
            tts?.language = java.util.Locale.getDefault()
            post { tts?.let { speakNow(it) } }
        }
    }

    private fun speakNow(engine: android.speech.tts.TextToSpeech) {
        // Strip the markup, or it reads asterisks and pipes out loud.
        val plain = lastAnswer
            .replace(Regex("[*_`#|]"), " ")
            .replace(Regex("\\s+"), " ")
            .take(3500)
        engine.speak(plain, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "yaver")
    }

    override fun onStop() {
        super.onStop()
        // Leaving is the last chance to keep what this conversation taught.
        if (turns.size >= 2 && !harvesting) {
            harvesting = true
            val snapshot = turns.toList()
            io.execute {
                try { Agent.harvest(snapshot) } finally { harvesting = false }
            }
        }
    }

    /** A routine notification carries the prompt to run. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleLaunchPrompt(intent)
    }

    private fun handleLaunchPrompt(intent: Intent?) {
        val prompt = intent?.getStringExtra(Reminders.EXTRA_PROMPT) ?: return
        intent.removeExtra(Reminders.EXTRA_PROMPT)
        if (busy) return
        input.setText(prompt)
        toast("Running your routine")
        ui.postDelayed({ send() }, 400)
    }

    /**
     * Android 13 made notifications opt-in, and reminders are most of why this
     * app is native — silently having none would gut it.
     */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5150)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            Calendar.REQUEST_CODE ->
                toast(if (granted) "Calendar access granted — ask me again" else "Calendar access denied")
            Whereabouts.REQUEST_CODE ->
                toast(if (granted) "Location granted — ask me again" else "Location denied")
        }
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
            text = "🔊"
            isAllCaps = false
            textSize = 15f
            setTextColor(soft)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { speak() }
        })
        bar.addView(Button(this).apply {
            text = "☰"
            isAllCaps = false
            textSize = 16f
            setTextColor(soft)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showSessions() }
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

        val micButton = Button(this).apply {
            text = "🎙"
            textSize = 17f
            setTextColor(soft)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(46))
            setOnClickListener { startDictation() }
        }
        row.addView(micButton)

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
        bar.addView(tab("Routines") { showRoutines() })
        bar.addView(tab("More") { showMore() })
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
            setPadding(0, dp(2), 0, dp(10))
            isFocusable = false
            setOnLongClickListener {
                val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("Yaver", this.text))
                toast("Copied")
                true
            }
        }
        transcript.addView(tv)
        return tv
    }

    /**
     * Turn a markdown pipe table into aligned monospace text.
     *
     * A TextView cannot lay out a real table, and models produce them
     * constantly — vocabulary lists, comparisons, prices. Aligned columns in a
     * fixed-width block is the honest version of the same information.
     */
    private fun renderTables(md: String): String {
        val lines = md.split("\n")
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val isSeparator = i + 1 < lines.size &&
                lines[i].contains('|') &&
                Regex("^\\s*\\|?[\\s:|-]*-[\\s:|-]*\\|?\\s*$").matches(lines[i + 1])

            if (!isSeparator) { out.append(lines[i]).append('\n'); i++; continue }

            val rows = mutableListOf<List<String>>()
            fun cells(line: String) = line.trim()
                .removePrefix("|").removeSuffix("|")
                .split('|').map { it.trim() }

            rows.add(cells(lines[i]))
            var j = i + 2
            while (j < lines.size && lines[j].contains('|')) { rows.add(cells(lines[j])); j++ }

            val columns = rows.maxOf { it.size }
            val widths = IntArray(columns)
            rows.forEach { r -> r.forEachIndexed { c, v -> widths[c] = maxOf(widths[c], v.length) } }
            // A very wide column would wrap and destroy the alignment anyway.
            for (c in widths.indices) widths[c] = widths[c].coerceAtMost(22)

            out.append("<tt>")
            rows.forEachIndexed { index, r ->
                val line = (0 until columns).joinToString("  ") { c ->
                    (r.getOrElse(c) { "" }).take(widths[c]).padEnd(widths[c])
                }.trimEnd()
                out.append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                out.append("<br>")
                if (index == 0) {
                    out.append("─".repeat(minOf(widths.sum() + (columns - 1) * 2, 60))).append("<br>")
                }
            }
            out.append("</tt>\n")
            i = j
        }
        return out.toString()
    }

    /** Just enough markdown for what models emit: bold, code, headings, bullets. */
    private fun render(md: String): CharSequence {
        val withTables = renderTables(md)
        // The table renderer already emits escaped HTML inside <tt>…</tt>, so
        // protect those blocks from a second round of escaping.
        val parts = withTables.split("<tt>", "</tt>")
        val html = parts.mapIndexed { index, part ->
            if (index % 2 == 1) "<tt>$part</tt>"
            else part.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }.joinToString("")
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

    /**
     * A picture with its credit underneath. Fetched on a background thread —
     * a card that blocks the UI while a photo downloads is worse than no card.
     */
    private fun addImageCard(title: String, url: String, credit: String, licence: String) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(8) }
        }
        val image = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        holder.addView(image)
        holder.addView(TextView(this).apply {
            text = title + (if (credit.isNotBlank()) " · $credit" else "") +
                   (if (licence.isNotBlank()) " · $licence" else "")
            setTextColor(faint); textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        transcript.addView(holder)

        io.execute {
            val bitmap = try {
                java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            post {
                if (bitmap != null) image.setImageBitmap(bitmap)
                else holder.addView(rowLabel("(could not load the picture)", faint, 11f))
                scrollDown()
            }
        }
    }

    private fun scrollDown() {
        ui.post {
            val child = scroll.getChildAt(0) ?: return@post
            scroll.scrollTo(0, maxOf(0, child.height - scroll.height))
        }
    }

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
                        val blocked = result.optString("blocked")
                        val empty = blocked.isNotEmpty() && blocked != "null"
                        last.text = "${if (!ok || empty) "✕" else "▪"}  $name  ${ms}ms" +
                            (if (empty) "  ·  nothing readable" else "")
                        last.setTextColor(if (ok && !empty) soft else danger)
                        if (!ok) {
                            val why = result.optString("error").ifBlank { result.optString("instruction") }
                            if (why.isNotBlank()) last.append("\n    $why")
                        }
                    }
                }

                override fun onCard(card: JSONObject) = post {
                    when (card.optString("type")) {
                        "permission" -> {
                            val what = card.optString("what", "calendar")
                            if (what == "location") {
                                addCard("Permission needed",
                                    "I need a rough idea of where you are to search nearby. Approximate only, and it never leaves the phone except as a search term.",
                                    true,
                                    Pair("Grant location", { Whereabouts.request(this@MainActivity) }))
                            } else {
                                addCard("Permission needed",
                                    "I need access to your phone calendar to read or change events. Android asks you directly — nothing is shared anywhere.",
                                    true,
                                    Pair("Grant calendar access", { Calendar.request(this@MainActivity) }))
                            }
                        }
                        "html", "file" -> {
                            val path = card.optString("path")
                            val title = card.optString("title", "Document")
                            addCard(
                                if (card.optString("type") == "html") "Report" else "Saved",
                                title, false,
                                Pair("Open", { openArtifact(path, title) })
                            )
                        }
                        "images" -> {
                            val items = card.optJSONArray("items") ?: JSONArray()
                            for (i in 0 until items.length()) {
                                val img = items.optJSONObject(i) ?: continue
                                addImageCard(img.optString("title"), img.optString("url"),
                                    img.optString("credit"), img.optString("licence"))
                            }
                        }
                        "places" -> {
                            val items = card.optJSONArray("items") ?: JSONArray()
                            for (i in 0 until items.length()) {
                                val place = items.optJSONObject(i) ?: continue
                                val lat = place.optDouble("lat")
                                val lon = place.optDouble("lon")
                                val name = place.optString("name")
                                addCard("Place", "$name\n${place.optString("address")}", false,
                                    Pair("Open in Maps", {
                                        val geo = android.net.Uri.parse(
                                            "geo:$lat,$lon?q=$lat,$lon(" +
                                            android.net.Uri.encode(name) + ")")
                                        try {
                                            startActivity(Intent(Intent.ACTION_VIEW, geo))
                                        } catch (e: Exception) { toast("No maps app installed") }
                                    }))
                            }
                        }
                        "browser" -> addCard(
                            "The browser needs you",
                            card.optString("why"),
                            true,
                            Pair("Open browser", { showBrowser() })
                        )
                        "draft" -> {
                            val body = card.optString("text")
                            val url = card.optString("url")
                            addCard(
                                "Draft — not sent",
                                (if (card.optString("to").isNotBlank()) "To ${card.optString("to")}\n\n" else "") + body,
                                true,
                                Pair("Open WhatsApp", {
                                    try {
                                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                                    } catch (e: Exception) { toast("WhatsApp is not installed") }
                                })
                            )
                        }
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
                    lastAnswer = Agent.stripCalls(reply)
                    if (currentBubble == null && reply.isNotBlank()) {
                        addAgentBubble(reply)
                    } else {
                        currentBubble?.text = render(Agent.stripCalls(reply))
                    }
                    Store.saveSession(turns, sessionId)
                    setBusy(false)
                    refreshDueBar()
                    scrollDown()

                    // Harvesting only when someone taps "New" means it almost
                    // never runs; every few exchanges is what makes memory real.
                    if (turns.count { it.role == "user" } % 3 == 0 && !harvesting) {
                        harvesting = true
                        val snapshot = turns.toList()
                        io.execute {
                            try { Agent.harvest(snapshot) } finally { harvesting = false }
                        }
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

    /**
     * Hand off to whatever speech recogniser the phone already has. A bundled
     * model would mean a 40 MB download and worse Turkish; this is one intent.
     */
    private fun startDictation() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Söyleyin")
        }
        try {
            startActivityForResult(intent, DICTATE)
        } catch (e: Exception) {
            toast("No speech recogniser on this phone")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DICTATE || resultCode != Activity.RESULT_OK) return
        val spoken = data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull() ?: return
        // Append rather than replace: dictating a second sentence should add to
        // the first, not throw it away.
        val existing = input.text.toString()
        input.setText(if (existing.isBlank()) spoken else "$existing $spoken")
        input.setSelection(input.text.length)
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
            Store.saveSession(snapshot, sessionId)
            io.execute { Agent.harvest(snapshot) }
        }
        sessionId = Store.newSession()
        turns = mutableListOf()
        showWelcome()
    }

    private fun showSessions() {
        val list = Store.sessions()
        if (list.isEmpty()) { toast("No past conversations yet"); return }
        val labels = list.map { info ->
            val when_ = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(info.updated))
            "${info.title}\n     $when_  ·  ${info.turns} messages"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Conversations")
            .setItems(labels) { _, index ->
                val chosen = list[index]
                if (turns.isNotEmpty()) Store.saveSession(turns, sessionId)
                sessionId = chosen.id
                Store.switchSession(chosen.id)
                turns = Store.loadSession(chosen.id)
                if (turns.isEmpty()) showWelcome() else replay()
                toast(chosen.title.take(40))
            }
            .setNeutralButton("Delete all") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete every conversation?")
                    .setMessage("Memories and tasks are kept. Only the transcripts go.")
                    .setPositiveButton("Delete") { _, _ ->
                        list.forEach { Store.deleteSession(it.id) }
                        sessionId = Store.newSession()
                        turns = mutableListOf()
                        showWelcome()
                        toast("Conversations deleted")
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRoutines() {
        val list = Store.routines()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        if (list.isEmpty()) {
            content.addView(rowLabel("Nothing recurring yet.", faint))
            content.addView(rowLabel(
                "Ask me: \"her sabah 8'de günün özetini çıkar\" — I'll set it up.", faint, 12f))
        }
        list.forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val main = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            main.addView(rowLabel(r.name))
            val next = r.nextFireAt()?.let {
                SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(it))
            } ?: "never"
            main.addView(rowLabel(
                "${r.every} at ${String.format(Locale.US, "%02d:%02d", r.hour, r.minute)}  ·  next $next",
                faint, 11f))
            row.addView(main)
            row.addView(iconAction("▶", signal) { input.setText(r.prompt); send() })
            row.addView(iconAction("×", faint) {
                Store.saveRoutines(Store.routines().filter { it.id != r.id })
                Reminders.cancelRoutine(this@MainActivity, r.id)
                toast("Removed")
            })
            content.addView(row)
        }
        AlertDialog.Builder(this)
            .setTitle("Routines")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null)
            .show()
    }

    // ── panels ───────────────────────────────────────────────────────────────

    private fun sheet(title: String, build: (LinearLayout) -> Unit): AlertDialog {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        build(content)

        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        frame.addView(TextView(this).apply {
            text = title
            setTextColor(ink)
            textSize = 20f
            typeface = Typeface.SERIF
            setPadding(dp(18), dp(18), dp(18), dp(6))
        })
        frame.addView(ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        frame.addView(TextView(this).apply {
            text = "Close"
            setTextColor(signal)
            textSize = 15f
            gravity = Gravity.END
            setPadding(dp(18), dp(12), dp(20), dp(18))
            isClickable = true
        })

        val dialog = AlertDialog.Builder(this).setView(frame).create()
        (frame.getChildAt(2) as TextView).setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(paper))
        dialog.show()
        // Fill the screen width; the default leaves a third of it unused.
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96).toInt(),
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        frame.layoutParams = frame.layoutParams?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        return dialog
    }

    /** A tap target sized to the glyph rather than to Android's button minimum. */
    private fun iconAction(glyph: String, colour: Int, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        setTextColor(colour)
        textSize = 17f
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        setOnClickListener { onClick() }
    }

    private fun rowLabel(text: String, colour: Int = ink, size: Float = 14f) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        textSize = size
        setPadding(0, dp(4), 0, dp(4))
    }

    /** Settings, past conversations and the log — the things you reach for rarely. */
    private fun showMore() {
        AlertDialog.Builder(this)
            .setTitle("More")
            .setItems(arrayOf(
                "Goals", "Messages", "Files", "Shared with me", "Browser",
                "Conversations", "Skills", "Usage", "Compress this conversation",
                "Settings", "Debug log"
            )) { _, index ->
                when (index) {
                    0 -> showGoals()
                    1 -> showMessages()
                    2 -> showFiles()
                    3 -> showInbox()
                    4 -> showBrowser()
                    5 -> showSessions()
                    6 -> showSkills()
                    7 -> showUsage()
                    8 -> compressConversation()
                    9 -> showSettings()
                    10 -> showDebug()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Open a saved document. HTML goes into a WebView so a report looks like a
     * report; anything else is shown as text.
     */
    private fun openArtifact(path: String, title: String) {
        val text = Store.readText(path)
        if (text == null) { toast("That file is gone"); return }

        val content: View = if (path.endsWith(".html", true)) {
            android.webkit.WebView(this).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(Color.WHITE)
                loadDataWithBaseURL(null, text, "text/html", "UTF-8", null)
            }
        } else {
            ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    this.text = render(text)
                    setTextColor(ink)
                    textSize = 14f
                    setPadding(dp(16), dp(12), dp(16), dp(16))
                })
            }
        }

        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        frame.addView(TextView(this).apply {
            this.text = title
            setTextColor(ink); textSize = 18f
            typeface = Typeface.SERIF
            setPadding(dp(18), dp(16), dp(18), dp(8))
        })
        frame.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dialog = AlertDialog.Builder(this).setView(frame).create()
        bar.addView(TextView(this).apply {
            this.text = "Share"
            setTextColor(signal); textSize = 15f
            setPadding(dp(18), dp(12), dp(18), dp(16))
            isClickable = true
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = if (path.endsWith(".html", true)) "text/html" else "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Share $title"))
            }
        })
        bar.addView(TextView(this).apply {
            this.text = "Close"
            setTextColor(soft); textSize = 15f
            gravity = Gravity.END
            setPadding(dp(18), dp(12), dp(20), dp(16))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialog.dismiss() }
        })
        frame.addView(bar)

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(paper))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.97).toInt(),
            (resources.displayMetrics.heightPixels * 0.9).toInt()
        )
    }

    /**
     * Hand the browser to the user.
     *
     * Signing in, solving a check, picking something from a list nobody can
     * describe — these are not things to automate, and pretending otherwise
     * produces an agent that fails silently on every site with a login. The
     * session persists, so the agent carries on from wherever they leave it.
     */
    private fun showBrowser() {
        val view = Browser.view()
        if (view == null) { toast("The browser is not running"); return }

        // It has been living off-screen, measured but unattached.
        (view.parent as? ViewGroup)?.removeView(view)

        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        frame.addView(TextView(this).apply {
            text = "Browser — Yaver is watching this page"
            setTextColor(soft); textSize = 12f
            setPadding(dp(16), dp(12), dp(16), dp(8))
        })
        frame.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = AlertDialog.Builder(this).setView(frame).create()
        frame.addView(TextView(this).apply {
            text = "Done — hand it back"
            setTextColor(signal); textSize = 15f
            gravity = Gravity.END
            setPadding(dp(18), dp(12), dp(20), dp(16))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setOnDismissListener {
            // Return it to its off-screen life, or the next action finds a
            // view still attached to a dead dialog.
            (view.parent as? ViewGroup)?.removeView(view)
            val metrics = resources.displayMetrics
            view.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            toast("Browser handed back — tell me when to carry on")
        }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(paper))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.97).toInt(),
            (resources.displayMetrics.heightPixels * 0.92).toInt()
        )
    }

    private fun showFiles() = sheet("Files") { box ->
        val list = Store.artifacts()
        if (list.isEmpty()) {
            box.addView(rowLabel("Nothing saved yet.", faint))
            box.addView(rowLabel(
                "Reports and notes I make for you collect here, and stay after the conversation goes.",
                faint, 12f))
            return@sheet
        }
        list.forEach { a ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val main = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            main.addView(rowLabel(a.name))
            main.addView(rowLabel(
                "${a.bytes} chars · " +
                SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(a.modified)),
                faint, 11f))
            main.setOnClickListener { openArtifact(a.path, a.name) }
            row.addView(main)
            row.addView(iconAction("×", faint) { Store.delete(a.path); toast("Deleted") })
            box.addView(row)
        }
    }

    private fun showMessages() = sheet("Messages") { box ->
        val enabled = NotificationCapture.isEnabled()
        val access = NotificationCapture.hasAccess(this)

        box.addView(rowLabel(
            when {
                !access -> "Notification access not granted"
                !enabled -> "Capture is off"
                else -> "Capture is on"
            },
            if (enabled && access) signal else brass, 12f))

        box.addView(rowLabel(
            "I can only see messages sent to you that raise a notification. Never your own — " +
            "WhatsApp raises no notification for those, so a chat with only you captures nothing. " +
            "Muted chats and anything you read before it notified are missed too.",
            faint, 11f))

        if (!access) {
            box.addView(Button(this).apply {
                text = "Grant notification access"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(signal)
                setOnClickListener { NotificationCapture.openAccessSettings(this@MainActivity) }
            })
        } else {
            box.addView(Button(this).apply {
                text = if (enabled) "Turn capture off" else "Turn capture on"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(if (enabled) faint else signal)
                setOnClickListener {
                    Store.setSetting(NotificationCapture.ENABLED, if (enabled) "false" else "true")
                    toast(if (enabled) "Capture off" else "Capture on")
                }
            })
        }

        val list = Store.messages(System.currentTimeMillis() - 3 * 86_400_000L, limit = 80)
        if (list.isEmpty()) {
            box.addView(rowLabel("Nothing captured in the last three days.", faint))
            return@sheet
        }

        box.addView(rowLabel("${list.size} message(s), last three days", soft, 12f))
        var lastChat = ""
        list.forEach { m ->
            if (m.chat != lastChat) {
                lastChat = m.chat
                box.addView(rowLabel(m.chat, signal, 12f))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(rowLabel(
                "${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(m.ts))}" +
                if (m.sender.isNotBlank() && m.sender != m.chat) " · ${m.sender}" else "",
                faint, 11f))
            row.addView(rowLabel(m.text.take(300)))
            box.addView(row)
        }

        box.addView(Button(this).apply {
            text = "Clear captured messages"
            isAllCaps = false
            setTextColor(faint)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { Store.clearMessages(); toast("Cleared") }
        })
    }

    private fun showInbox() = sheet("Shared with me") { box ->
        val items = Store.forwards()
        if (items.isEmpty()) {
            box.addView(rowLabel("Nothing shared yet.", faint))
            box.addView(rowLabel(
                "In any app — WhatsApp, a browser, Maps — use Share and pick Yaver. " +
                "It arrives here and I treat it as something you handed me on purpose.",
                faint, 12f))
            return@sheet
        }
        box.addView(rowLabel("${items.size} item(s)", soft, 12f))
        items.forEach { f ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(rowLabel(
                SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(f.ts)), faint, 11f))
            row.addView(rowLabel(f.text.take(400)))
            row.setOnClickListener {
                input.setText("Bunu ele al:\n\n${f.text.take(2000)}")
                toast("Added to the composer")
            }
            box.addView(row)
        }
        box.addView(rowLabel(""))
        box.addView(Button(this).apply {
            text = "Clear all"
            isAllCaps = false
            setTextColor(faint)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { Store.clearForwards(); toast("Cleared") }
        })
    }

    private fun showGoals() = sheet("Goals") { box ->
        val list = Store.goals().sortedWith(compareBy<Store.Goal>(
            { it.status != "active" }, { -it.updated }
        ))
        if (list.isEmpty()) {
            box.addView(rowLabel("Nothing being pursued.", faint))
            box.addView(rowLabel(
                "A goal is something that takes weeks — a flat, a trip, a deal. Tell me about one and I'll keep track between conversations.",
                faint, 12f))
            return@sheet
        }
        list.forEach { g ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val main = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            main.addView(rowLabel(g.title + if (g.status != "active") "  (${g.status})" else "",
                if (g.status == "active") ink else faint))
            if (g.detail.isNotBlank()) main.addView(rowLabel(g.detail, faint, 11f))
            main.addView(rowLabel(
                "${g.notes.size} note(s) · last " +
                SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(g.updated)),
                faint, 11f))
            main.setOnClickListener {
                val history = g.notes.takeLast(20).joinToString("\n\n") { (at, text) ->
                    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at)) + "\n" + text
                }
                AlertDialog.Builder(this)
                    .setTitle(g.title)
                    .setMessage(if (history.isBlank()) "No notes yet." else history)
                    .setPositiveButton("Close", null)
                    .show()
            }
            row.addView(main)
            row.addView(iconAction("×", faint) {
                Store.saveGoals(Store.goals().filter { it.id != g.id })
                toast("Removed")
            })
            box.addView(row)
        }
    }

    private fun showSkills() = sheet("Skills") { box ->
        val list = Store.skills()
        if (list.isEmpty()) {
            box.addView(rowLabel("Nothing written yet.", faint))
            box.addView(rowLabel(
                "I write these after finishing something worth repeating, so I do it your way next time.",
                faint, 12f))
            return@sheet
        }
        list.forEach { sk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val main = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            main.addView(rowLabel(sk.title))
            if (sk.useWhen.isNotBlank()) main.addView(rowLabel("when ${sk.useWhen}", faint, 11f))
            main.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(sk.title)
                    .setMessage(sk.body.take(4000))
                    .setPositiveButton("Close", null)
                    .show()
            }
            row.addView(main)
            row.addView(iconAction("×", faint) { Store.deleteSkill(sk.name); toast("Deleted") })
            box.addView(row)
        }
    }

    /**
     * Dialogs cannot rebuild themselves in place, so filtering means closing
     * and reopening. Cheap, and it keeps the panel code a single function.
     */
    private var memorySheet: AlertDialog? = null

    private fun closeAndReopenMemory() {
        memorySheet?.dismiss()
        ui.postDelayed({ showMemory() }, 120)
    }

    private fun showUsage() = sheet("Usage · last 7 days") { box ->
        val days = Store.usage(7)
        if (days.isEmpty()) { box.addView(rowLabel("Nothing recorded yet.", faint)); return@sheet }
        var calls = 0; var tokens = 0; var cost = 0.0
        days.forEach { d ->
            calls += d.calls; tokens += d.tokens; cost += d.cost
            box.addView(rowLabel(
                "${d.day}   ${d.calls} calls   ${d.tokens} tokens" +
                if (d.cost > 0) "   $${String.format(Locale.US, "%.4f", d.cost)}" else "",
                ink, 12f))
        }
        box.addView(rowLabel(
            "Total  $calls calls, $tokens tokens" +
            if (cost > 0) ", $${String.format(Locale.US, "%.4f", cost)}" else "",
            signal, 13f))
    }

    private fun compressConversation() {
        if (turns.size < 6) { toast("Not much to compress yet"); return }
        toast("Compressing…")
        val snapshot = turns.toList()
        io.execute {
            val summary = Agent.compress(snapshot)
            post {
                if (summary.isBlank()) { toast("Could not compress"); return@post }
                turns = mutableListOf(
                    Store.Turn("user", "(earlier conversation)"),
                    Store.Turn("assistant", summary)
                )
                Store.saveSession(turns, sessionId)
                replay()
                toast("History compressed")
            }
        }
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

            row.addView(iconAction(if (t.done) "↺" else "✓", signal) {
                val all = Store.tasks()
                val target = all.firstOrNull { it.id == t.id }
                if (target != null) {
                    target.done = !target.done
                    Store.saveTasks(all)
                    if (target.done) Reminders.cancelTask(this@MainActivity, target.id)
                    else Reminders.scheduleTask(this@MainActivity, target)
                }
                refreshDueBar()
                toast(if (t.done) "Reopened" else "Done")
            })
            row.addView(iconAction("×", faint) {
                Store.saveTasks(Store.tasks().filter { it.id != t.id })
                Reminders.cancelTask(this@MainActivity, t.id)
                refreshDueBar()
                toast("Deleted")
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
                row.addView(iconAction("×", faint) {
                    try {
                        if (Calendar.delete(this@MainActivity, e.id)) toast("Removed from calendar")
                        else toast("Could not remove it")
                    } catch (ex: Exception) { toast(ex.message ?: "Failed") }
                })
                box.addView(row)
            }
        }
    }

    private var memoryFilter = ""

    private fun showMemory() { memorySheet = sheet("Memory") { box ->
        val everything = Store.memories()
        val all = if (memoryFilter.isBlank()) everything else everything.filter { m ->
            val hay = (m.text + " " + m.entities.joinToString(" ") + " " +
                       m.topics.joinToString(" ")).lowercase(Locale.ROOT)
            hay.contains(memoryFilter.lowercase(Locale.ROOT))
        }
        val fading = all.filter { !Store.memoryProtected(it) && Store.memoryIdleDays(it) >= Store.MEMORY_WARN_DAYS }
        box.addView(rowLabel(
            "${all.size} stored · ${all.count { it.pinned }} pinned · ${fading.size} fading",
            if (fading.isEmpty()) soft else brass, 12f))

        val search = EditText(this).apply {
            hint = "Search memories"
            setText(memoryFilter)
            setBackgroundColor(surface)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        box.addView(search)
        box.addView(Button(this).apply {
            text = "Filter"
            isAllCaps = false
            setTextColor(soft)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                memoryFilter = search.text.toString().trim()
                closeAndReopenMemory()
            }
        })

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
                closeAndReopenMemory()
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
            row.addView(iconAction(if (m.pinned) "★" else "☆", brass) {
                val list = Store.memories()
                list.firstOrNull { it.id == m.id }?.let { it.pinned = !it.pinned }
                Store.saveMemories(list)
                toast(if (m.pinned) "Unpinned" else "Pinned")
            })
            row.addView(iconAction("×", faint) {
                Store.saveMemories(Store.memories().filter { it.id != m.id })
                toast("Forgotten")
            })
            box.addView(row)
        }
    } }

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
        val promptSize = Agent.promptSize()
        val sizeLine = "prompt: ~${promptSize.first} tokens fixed, ~${promptSize.second} tokens context\n\n"
        val revisions = Store.revisions(15)
        val harness = if (revisions.isEmpty()) "" else
            "── changes to my own instructions ──\n" +
            revisions.joinToString("\n") { r ->
                SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(r.ts)) +
                "  ${r.what}: ${r.why}"
            } + "\n\n"

        val text = TextView(this).apply {
            this.text = sizeLine + harness + Log.dump().ifBlank { "Nothing recorded yet." }
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
        val stream = intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""

        when {
            stream != null -> {
                // Documents are read here rather than stored as a path: a URI
                // granted to this one intent is not readable later.
                val name = displayName(stream)
                val mime = intent?.type ?: contentResolver.getType(stream)
                val extract = Docs.fromUri(this, stream, mime, name)
                if (extract.text.isBlank()) {
                    Toast.makeText(this, extract.note ?: "Nothing readable in that file", Toast.LENGTH_LONG).show()
                } else {
                    val header = "[$name]" + (extract.note?.let { " $it" } ?: "")
                    Store.addForward("$header\n\n${extract.text}", name ?: "document")
                    Toast.makeText(this, "Read $name — ${extract.text.length} characters", Toast.LENGTH_SHORT).show()
                }
            }
            !text.isNullOrEmpty() -> {
                Store.addForward(if (subject.isBlank()) text else "$subject\n\n$text", "shared")
                Toast.makeText(this, "Saved for Yaver", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun displayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && c.moveToFirst()) c.getString(index) else null
        }
    } catch (e: Exception) { null }
}
