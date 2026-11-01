package com.plusai.yaver.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Small helpers for building screens in code. */
object U {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun dp(v: Int) = (v * Y.app.resources.displayMetrics.density).toInt()

    fun round(color: Int, radiusDp: Int, c: Context, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(c, radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    fun text(c: Context, s: CharSequence, size: Float = 15f, color: Int = Palette(c).text, bold: Boolean = false): TextView = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val p = Palette(c)
        return Button(c).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) Color.WHITE else p.text)
            background = round(if (primary) p.accent else p.surface, 10, c, if (primary) 0 else p.line)
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
    }

    fun iconButton(c: Context, glyph: String, color: Int, onClick: () -> Unit): TextView = TextView(c).apply {
        text = glyph
        textSize = 20f
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6))
        setOnClickListener { onClick() }
        isClickable = true
    }

    fun input(c: Context, hint: String, value: String = "", password: Boolean = false, multi: Boolean = false): EditText {
        val p = Palette(c)
        return EditText(c).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            background = round(p.surface, 10, c, p.line)
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else if (multi) { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; minLines = 3; gravity = Gravity.TOP }
            else isSingleLine = true
        }
    }

    fun bar(a: Activity, title: String, back: Boolean = true): LinearLayout {
        val p = Palette(a)
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.bar)
            setPadding(dp(a, 4), dp(a, 6), dp(a, 8), dp(a, 6))
            if (back) addView(iconButton(a, "←", Color.WHITE) { a.finish() })
            addView(text(a, title, 18f, Color.WHITE, true).apply { setPadding(dp(a, 8), 0, 0, 0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun toast(c: Context, s: String) = Y.ui { Toast.makeText(c, s, Toast.LENGTH_LONG).show() }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun applyTheme(a: Activity) {
        a.setTheme(if (Y.dark(a)) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        a.window.statusBarColor = Palette(a).bar
        a.window.navigationBarColor = Palette(a).bg
    }

    /** What happens when a link in yaver's text is tapped. */
    fun openLink(c: Context, url: String) {
        try {
            when {
                url.startsWith("mail:") -> c.startActivity(Intent(c, MailActivity::class.java).putExtra("id", url.removePrefix("mail:")))
                url.startsWith("http://") || url.startsWith("https://") -> c.startActivity(Intent(c, BrowserActivity::class.java).putExtra("url", url))
                url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("geo:") -> c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                else -> {
                    val f = Workspace.resolve(url.removePrefix("file:"))
                    if (f.exists()) Workspace.open(c, f) else toast(c, "Not found: $url")
                }
            }
        } catch (e: Throwable) { toast(c, e.message ?: "cannot open") }
    }
}

/** Markdown → styled text for chat bubbles (headings, lists, code, tables, quotes, links, bold/italic, pictures). */
object Markdown {
    private class Link(val url: String, val color: Int) : ClickableSpan() {
        override fun onClick(widget: View) { U.openLink(widget.context, url) }
        override fun updateDrawState(ds: TextPaint) { ds.color = color; ds.isUnderlineText = false }
    }

    fun render(c: Context, md: String, width: Int = 900): CharSequence {
        val p = Palette(c)
        val sb = SpannableStringBuilder()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimEnd()
            if (t.trimStart().startsWith("```")) {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                i++
                val st = sb.length
                sb.append(code.toString().trimEnd('\n')).append('\n')
                sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.88f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(p.code), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            val h = Regex("^(#{1,6})\\s+(.*)$").find(t)
            when {
                h != null -> {
                    val st = sb.length
                    inline(c, sb, h.groupValues[2], p)
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(when (h.groupValues[1].length) { 1 -> 1.35f; 2 -> 1.22f; 3 -> 1.1f; else -> 1.0f }), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append('\n')
                }
                t.matches(Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")) -> {
                    val st = sb.length
                    sb.append("──────────\n")
                    sb.setSpan(ForegroundColorSpan(p.line), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                t.trimStart().startsWith("|") -> {
                    // tables: rows as aligned monospace text, separator rows dropped
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) || it.isEmpty() }) rows.add(cells)
                        i++
                    }
                    i--
                    val cols = rows.maxOfOrNull { it.size } ?: 0
                    val widths = IntArray(cols) { k -> rows.maxOf { r -> (r.getOrNull(k) ?: "").replace("**", "").length }.coerceAtMost(28) }
                    val st = sb.length
                    for ((ri, r) in rows.withIndex()) {
                        val rs = sb.length
                        sb.append((0 until cols).joinToString("  ") { k -> (r.getOrNull(k) ?: "").replace("**", "").take(28).padEnd(widths[k]) }.trimEnd()).append('\n')
                        if (ri == 0) sb.setSpan(StyleSpan(Typeface.BOLD), rs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.82f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                t.trimStart().startsWith(">") -> {
                    val st = sb.length
                    inline(c, sb, t.trimStart().removePrefix(">").trim(), p)
                    sb.append('\n')
                    sb.setSpan(QuoteSpan(p.accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(p.muted), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Regex("^(\\s*)([-*+])\\s+(.*)$").matches(t) -> {
                    val m = Regex("^(\\s*)([-*+])\\s+(.*)$").find(t)!!
                    val depth = m.groupValues[1].length / 2
                    val st = sb.length
                    sb.append(if (depth == 0) "•  " else "◦  ")
                    var body = m.groupValues[3]
                    if (body.startsWith("[ ] ")) { sb.append("☐ "); body = body.drop(4) } else if (body.startsWith("[x] ", true)) { sb.append("☑ "); body = body.drop(4) }
                    inline(c, sb, body, p)
                    sb.append('\n')
                    sb.setSpan(LeadingMarginSpan.Standard(U.dp(c, 8 + depth * 16), U.dp(c, 22 + depth * 16)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$").matches(t) -> {
                    val m = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$").find(t)!!
                    val depth = m.groupValues[1].length / 2
                    val st = sb.length
                    sb.append(m.groupValues[2]).append(".  ")
                    inline(c, sb, m.groupValues[3], p)
                    sb.append('\n')
                    sb.setSpan(LeadingMarginSpan.Standard(U.dp(c, 6 + depth * 16), U.dp(c, 24 + depth * 16)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    inline(c, sb, t, p, width)
                    sb.append('\n')
                }
            }
            i++
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private val INLINE = Regex("(!\\[([^\\]]*)]\\(([^)\\s]+)\\))|(\\[([^\\]]+)]\\(([^)\\s]+)\\))|(`([^`]+)`)|(\\*\\*([^*]+)\\*\\*)|(__([^_]+)__)|(~~([^~]+)~~)|((?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*]))|((?<![\\w])_([^_\\n]+)_(?![\\w]))|(https?://[^\\s)<>\\]]+[^\\s)<>\\].,;:!?'\"])")

    private fun inline(c: Context, sb: SpannableStringBuilder, s: String, p: Palette, width: Int = 900) {
        var pos = 0
        for (m in INLINE.findAll(s)) {
            if (m.range.first < pos) continue
            sb.append(s.substring(pos, m.range.first))
            val g = m.groupValues
            val st = sb.length
            when {
                g[1].isNotEmpty() -> {
                    val path = g[3]
                    val bmp = try {
                        if (!path.startsWith("http")) {
                            val f = Workspace.resolve(path.removePrefix("file:"))
                            if (f.exists()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = if (f.length() > 3_000_000) 2 else 1 }) else null
                        } else null
                    } catch (e: Exception) { null }
                    if (bmp != null) {
                        val d = BitmapDrawable(c.resources, bmp)
                        val w = minOf(width, bmp.width * 2)
                        d.setBounds(0, 0, w, (bmp.height.toFloat() * w / bmp.width).toInt())
                        sb.append("￼")
                        sb.setSpan(ImageSpan(d), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(Link(path, p.accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.append("\n")
                    } else {
                        sb.append("🖼 ").append(g[2].ifEmpty { path })
                        sb.setSpan(Link(path, p.accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                g[4].isNotEmpty() -> { sb.append(g[5]); sb.setSpan(Link(g[6], p.accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[7].isNotEmpty() -> {
                    sb.append(g[8])
                    sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(p.code), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[9].isNotEmpty() -> { inline(c, sb, g[10], p); sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[11].isNotEmpty() -> { inline(c, sb, g[12], p); sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[13].isNotEmpty() -> { sb.append(g[14]); sb.setSpan(StrikethroughSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[15].isNotEmpty() -> { inline(c, sb, g[16], p); sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[17].isNotEmpty() -> { inline(c, sb, g[18], p); sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[19].isNotEmpty() -> { sb.append(g[19]); sb.setSpan(Link(g[19], p.accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            pos = m.range.last + 1
        }
        sb.append(s.substring(pos))
    }
}
