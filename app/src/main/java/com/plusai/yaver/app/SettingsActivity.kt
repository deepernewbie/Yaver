package com.plusai.yaver.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

/** Every setting on one native screen. */
class SettingsActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var box: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var modelBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        U.applyTheme(this)
        super.onCreate(savedInstanceState)
        Y.init(this)
        p = Palette(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        root.addView(U.bar(this, Y.t("Settings", "Ayarlar")), U.lp())
        val sv = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(context, 16), U.dp(context, 8), U.dp(context, 16), U.dp(context, 40)) }
        sv.addView(box)
        root.addView(sv, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        build()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun section(t: String) = box.addView(U.text(this, t, 13f, p.accent, true).apply { setPadding(0, U.dp(context, 22), 0, U.dp(context, 6)) })
    private fun note(t: String) = box.addView(U.text(this, t, 13f, p.muted).apply { setPadding(0, U.dp(context, 2), 0, U.dp(context, 6)) })
    private fun label(t: String) = box.addView(U.text(this, t, 13.5f, p.text).apply { setPadding(0, U.dp(context, 8), 0, U.dp(context, 3)) })

    private fun field(title: String, value: String, hint: String = "", password: Boolean = false, multi: Boolean = false, save: (String) -> Unit): EditText {
        label(title)
        val shown = if (password && value.isNotEmpty()) "" else value
        val e = U.input(this, if (password && value.isNotEmpty()) Y.t("•••••• (saved — type to change)", "•••••• (kayıtlı — değiştirmek için yazın)") else hint, shown, password, multi)
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { val v = s?.toString() ?: ""; if (!password || v.isNotEmpty()) save(v) }
        })
        box.addView(e, U.lp())
        return e
    }

    private fun button(t: String, primary: Boolean = false, f: () -> Unit) = box.addView(U.button(this, t, primary, f), U.lp().apply { topMargin = U.dp(8) })

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(t: String, key: String, def: Boolean, f: (Boolean) -> Unit = {}) {
        val s = Switch(this)
        s.text = t; s.textSize = 15f; s.setTextColor(p.text)
        s.isChecked = Prefs.on(key, def)
        s.setPadding(0, U.dp(this, 8), 0, U.dp(this, 8))
        s.setOnCheckedChangeListener { _, on -> Prefs.set(key, on); f(on); refreshStatus() }
        box.addView(s, U.lp())
    }

    private fun build() {
        statusView = U.text(this, "", 14f, p.text).apply { background = U.round(p.surface, 12, context, p.line); setPadding(U.dp(context, 14), U.dp(context, 10), U.dp(context, 14), U.dp(context, 10)) }
        box.addView(statusView, U.lp().apply { topMargin = U.dp(8) })

        // ---- model
        section(Y.t("LANGUAGE MODEL", "DİL MODELİ"))
        note(Y.t("Any OpenAI-compatible service: OpenRouter (one key for hundreds of models, many free), OpenAI, Groq, Together, or a model on your own computer (Ollama, LM Studio).", "OpenAI uyumlu her hizmet: OpenRouter (tek anahtarla yüzlerce model, birçoğu ücretsiz), OpenAI, Groq, Together ya da kendi bilgisayarınızdaki model (Ollama, LM Studio)."))
        val base = field(Y.t("Service address", "Hizmet adresi"), Cfg.llmBase, "https://openrouter.ai/api/v1") { Cfg.llmBase = it }
        button(Y.t("Choose a service…", "Hizmet seç…")) {
            val presets = listOf("OpenRouter" to "https://openrouter.ai/api/v1", "OpenAI" to "https://api.openai.com/v1", "Groq" to "https://api.groq.com/openai/v1", "Together" to "https://api.together.xyz/v1", "Google Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai", "Ollama (local)" to "http://localhost:11434/v1", "LM Studio (local)" to "http://localhost:1234/v1")
            AlertDialog.Builder(this).setItems(presets.map { it.first }.toTypedArray()) { _, w -> base.setText(presets[w].second) }.show()
        }
        field(Y.t("API key", "API anahtarı"), Cfg.llmKey, "sk-…", password = true) { Cfg.llmKey = it }
        modelBox = field(Y.t("Model", "Model"), Cfg.model, "e.g. openai/gpt-4o-mini") { Cfg.model = it }
        button(Y.t("Pick from the service's model list…", "Hizmetin model listesinden seç…")) { pickModel { modelBox.setText(it) } }
        field(Y.t("Fast model (summaries, optional)", "Hızlı model (özetler, isteğe bağlı)"), Cfg.fastModel, Y.t("same as main", "ana modelle aynı")) { Cfg.fastModel = it }
        field(Y.t("Vision model (pictures, scanned PDFs; optional)", "Görsel model (resimler, taranmış PDF; isteğe bağlı)"), Cfg.visionModel, Y.t("same as main", "ana modelle aynı")) { Cfg.visionModel = it }
        field(Y.t("Max steps per request", "İstek başına en fazla adım"), Cfg.maxSteps.toString(), "30") { it.toIntOrNull()?.let { v -> Cfg.maxSteps = v.coerceIn(3, 100) } }
        field(Y.t("Context budget (tokens)", "Bağlam bütçesi (token)"), Cfg.contextTokens.toString(), "48000") { it.toIntOrNull()?.let { v -> Cfg.contextTokens = v.coerceIn(4000, 1000000) } }
        val stream = Switch(this).apply { text = Y.t("Stream answers word by word", "Cevapları kelime kelime göster"); textSize = 15f; setTextColor(p.text); isChecked = Cfg.stream; setOnCheckedChangeListener { _, b -> Cfg.stream = b } }
        box.addView(stream, U.lp())
        button(Y.t("Test the model", "Modeli dene"), true) {
            U.toast(this, Y.t("Asking the model…", "Modele soruluyor…"))
            Y.bg { val r = try { "✅ " + Llm.ask("Answer in at most five words.", "Say hello.", fast = false, maxTokens = 50) } catch (e: Throwable) { "❌ " + e.message }; U.toast(this, r) }
        }

        // ---- behaviour
        section(Y.t("LANGUAGE AND BEHAVIOUR", "DİL VE DAVRANIŞ"))
        label(Y.t("Answer language", "Cevap dili"))
        val langs = listOf("auto" to Y.t("Same as my message", "Mesajımla aynı"), "tr" to "Türkçe", "en" to "English")
        box.addView(U.button(this, langs.first { it.first == Cfg.lang }.second) {}.also { b ->
            b.setOnClickListener { AlertDialog.Builder(this).setItems(langs.map { it.second }.toTypedArray()) { _, w -> Cfg.lang = langs[w].first; b.text = langs[w].second; Shortcuts.publish(this) }.show() }
        }, U.lp())
        field(Y.t("Persona (who yaver is; empty = default)", "Kişilik (yaver kimdir; boş = varsayılan)"), Cfg.persona, Agent.defaultPersona(), multi = true) { Cfg.persona = it }
        val risky = Switch(this).apply { text = Y.t("Ask before actions with side effects (delete, move, overwrite…)", "Yan etkili işlemlerden önce sor (silme, taşıma, üzerine yazma…)"); textSize = 15f; setTextColor(p.text); isChecked = Cfg.askRisky; setOnCheckedChangeListener { _, b -> Cfg.askRisky = b } }
        box.addView(risky, U.lp())
        note(Y.t("Sending e-mail, meeting invitations and responses are always confirmed.", "E-posta gönderme, toplantı daveti ve yanıtları her zaman onaylatılır."))
        button(Y.t("Memory — what yaver knows about you", "Hafıza — yaver'in sizin hakkınızda bildikleri")) { startActivity(Intent(this, MemoryActivity::class.java)) }

        // ---- mail
        section(Y.t("COMPANY MAIL (EXCHANGE)", "KURUM POSTASI (EXCHANGE)"))
        note(Y.t("yaver logs in to your mail server itself and logs in again when the session ends — your password is stored encrypted on this phone only. Fill in the Outlook web address (and EWS if you know it).", "yaver posta sunucunuza kendisi bağlanır ve oturum kapanınca yeniden giriş yapar — şifreniz yalnızca bu telefonda şifreli saklanır. Outlook web adresini (biliyorsanız EWS'yi de) girin."))
        field(Y.t("Outlook web (OWA) address", "Outlook web (OWA) adresi"), Cfg.mailOwa, "https://mail.company.com/owa/") { Cfg.mailOwa = it; Mail.reset() }
        field(Y.t("EWS address (optional)", "EWS adresi (isteğe bağlı)"), Cfg.mailEws, "https://mail.company.com/EWS/Exchange.asmx") { Cfg.mailEws = it; Mail.reset() }
        field(Y.t("User name", "Kullanıcı adı"), Cfg.mailUser, "DOMAIN\\user  /  name@company.com") { Cfg.mailUser = it; Mail.reset() }
        field(Y.t("Password", "Şifre"), Cfg.mailPass, "", password = true) { Cfg.mailPass = it; Mail.reset() }
        field(Y.t("Check for new mail every … minutes", "Yeni postayı her … dakikada kontrol et"), Cfg.mailPoll.toString(), "3") { it.toIntOrNull()?.let { v -> Cfg.mailPoll = v.coerceIn(1, 120) } }
        val mn = Switch(this).apply { text = Y.t("Notify me about new mail", "Yeni postayı bildir"); textSize = 15f; setTextColor(p.text); isChecked = Cfg.mailNotify; setOnCheckedChangeListener { _, b -> Cfg.mailNotify = b } }
        box.addView(mn, U.lp())
        button(Y.t("Test the connection", "Bağlantıyı dene"), true) {
            U.toast(this, Y.t("Connecting…", "Bağlanılıyor…"))
            Y.bg { Mail.reset(); val r = try { Mail.connect(); "✅ " + Mail.status } catch (e: Throwable) { "❌ " + e.message }; Y.ui { AlertDialog.Builder(this).setMessage(r).setPositiveButton("OK", null).show() } }
        }
        button(Y.t("Log in on the web page (two-factor / company login page)", "Web sayfasında oturum aç (iki adımlı / kurumsal giriş)")) { startActivity(Intent(this, MailLoginActivity::class.java)) }

        // ---- web
        section(Y.t("WEB SEARCH", "WEB ARAMA"))
        note(Y.t("Works without any key (DuckDuckGo, Bing, Mojeek straight from the phone). Optional: a Brave or Tavily key, or your own SearXNG.", "Anahtarsız çalışır (DuckDuckGo, Bing, Mojeek doğrudan telefondan). İsteğe bağlı: Brave veya Tavily anahtarı ya da kendi SearXNG'niz."))
        field("Brave Search API key", Cfg.braveKey, "", password = true) { Cfg.braveKey = it }
        field("Tavily API key", Cfg.tavilyKey, "", password = true) { Cfg.tavilyKey = it }
        field("SearXNG", Cfg.searxng, "https://searx.example.org") { Cfg.searxng = it }

        // ---- assistant / phone
        section(Y.t("ASSISTANT AND PHONE", "ASİSTAN VE TELEFON"))
        note(Y.t("Make yaver the phone's assistant: long-press home / swipe up from a bottom corner / hold the power button (depends on the phone) opens yaver.", "yaver'i telefonun asistanı yapın: ana ekran tuşuna uzun basmak / alt köşeden kaydırmak / güç tuşunu basılı tutmak (telefona göre) yaver'i açar."))
        button(Y.t("Choose yaver as the default assistant…", "yaver'i varsayılan asistan seç…"), true) {
            if (!start(Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))) if (!start(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) start(Intent(Settings.ACTION_SETTINGS))
            U.toast(this, Y.t("Tap “Digital assistant app”, then choose yaver", "“Dijital asistan uygulaması”na dokunup yaver'i seçin"))
        }
        toggle(Y.t("Start listening when opened as assistant", "Asistan olarak açılınca dinlemeye başla"), "voiceOnAssist", false)
        toggle(Y.t("Read the answer aloud after a voice request", "Sesli istekten sonra cevabı sesli oku"), "speakVoice", true)
        toggle(Y.t("Quick Settings tile starts voice input", "Hızlı ayarlar kutucuğu sesle başlasın"), "tileVoice", true)
        toggle(Y.t("Always on (mail checks, reminders and tasks in the background)", "Sürekli açık (arka planda posta, hatırlatma ve görevler)"), "alwaysOn", true) { if (it) AgentService.start(this) else AgentService.stop(this) }
        toggle(Y.t("Notify me when an answer finishes while I'm away", "Ben yokken biten cevapları bildir"), "notifyReplies", true)
        toggle(Y.t("Run reminder and task prompts automatically", "Hatırlatma ve görev isteklerini otomatik çalıştır"), "runReminders", true)
        button(Y.t("Let yaver run without battery limits", "yaver pil kısıtlaması olmadan çalışsın")) {
            if (!start(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))) start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button(Y.t("Allow the phone calendar", "Telefon takvimine izin ver")) { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1) }
        button(Y.t("Notification settings", "Bildirim ayarları")) { start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        if (Build.VERSION.SDK_INT in 31..32) button(Y.t("Allow exact reminder times", "Tam zamanlı hatırlatmalara izin ver")) { start(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }

        // ---- data
        section(Y.t("DATA", "VERİ"))
        button(Y.t("Import a yaver export (.zip)", "yaver dışa aktarımını içe al (.zip)")) {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7) } catch (_: Throwable) {}
        }
        button(Y.t("Files in yaver's workspace", "yaver çalışma alanındaki dosyalar")) {
            val t = Workspace.list(Workspace.root).ifEmpty { Y.t("(empty)", "(boş)") }
            AlertDialog.Builder(this).setMessage(t.take(6000)).setPositiveButton("OK", null).show()
        }
        button(Y.t("Log out of websites and mail (clear cookies)", "Web sitelerinden ve postadan çık (çerezleri sil)")) {
            CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); Mail.reset(); U.toast(this, "OK")
        }
        button(Y.t("Show the last crash report", "Son çökme raporunu göster")) {
            val f = File(filesDir, "last-crash-shown.txt")
            AlertDialog.Builder(this).setMessage(if (f.exists()) f.readText().take(6000) else Y.t("No crash recorded.", "Kayıtlı çökme yok.")).setPositiveButton("OK", null).show()
        }
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Throwable) { "?" }
        note("yaver $v · native Android")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            Y.bg {
                val msg = try { Migration.importZip(u).ifEmpty { Y.t("Nothing new to import.", "İçe alınacak yeni bir şey yok.") } } catch (e: Throwable) { "❌ " + e.message }
                Y.ui { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun start(i: Intent): Boolean = try { startActivity(i); true } catch (e: Throwable) { false }

    private fun pickModel(done: (String) -> Unit) {
        U.toast(this, Y.t("Loading models…", "Modeller yükleniyor…"))
        Y.bg {
            val r = try { Llm.models() } catch (e: Throwable) { Y.ui { U.toast(this, "❌ " + e.message) }; return@bg }
            Y.ui {
                val all = r
                val lv = ListView(this)
                val search = U.input(this, Y.t("Filter (e.g. free, gpt, claude, gemini)", "Süz (ör. free, gpt, claude, gemini)"))
                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(all))
                lv.adapter = adapter
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { val q = s?.toString()?.lowercase() ?: ""; adapter.clear(); adapter.addAll(all.filter { it.lowercase().contains(q) }) }
                })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(U.dp(context, 16), U.dp(context, 8), U.dp(context, 16), 0); addView(search); addView(lv, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(context, 420))) }
                val d = AlertDialog.Builder(this).setTitle("${all.size} " + Y.t("models", "model")).setView(col).setNegativeButton(Y.t("Cancel", "İptal"), null).create()
                lv.setOnItemClickListener { _, _, pos, _ -> adapter.getItem(pos)?.let { done(it) }; d.dismiss() }
                d.show()
            }
        }
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val assistant = Settings.Secure.getString(contentResolver, "assistant") ?: ""
        val unrestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        val cal = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        fun mark(b: Boolean) = if (b) "✅" else "⬜"
        statusView.text = listOf(
            "${mark(Cfg.llmReady)} " + Y.t("Model", "Model") + if (Cfg.model.isNotEmpty()) ": ${Cfg.model}" else "",
            "${mark(Mail.connected)} " + Y.t("Company mail", "Kurum postası") + if (Mail.connected) " (${Mail.status})" else if (Cfg.mailConfigured) Y.t(" (set up, not connected yet)", " (ayarlı, henüz bağlanmadı)") else "",
            "${mark(assistant.contains(packageName))} " + Y.t("Default assistant", "Varsayılan asistan"),
            "${mark(AgentService.running)} " + Y.t("Always on", "Sürekli açık"),
            "${mark(unrestricted)} " + Y.t("No battery limits", "Pil kısıtlaması yok"),
            "${mark(Notifier.canPost(this))} " + Y.t("Notifications", "Bildirimler"),
            "${mark(cal)} " + Y.t("Phone calendar", "Telefon takvimi")
        ).joinToString("\n")
        statusView.setTypeface(null, Typeface.NORMAL)
    }
}
