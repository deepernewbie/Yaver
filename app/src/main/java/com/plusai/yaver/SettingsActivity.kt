package com.plusai.yaver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Phone-side settings: always on, default assistant, notifications, battery, mail logins. */
class SettingsActivity : Activity() {
    private lateinit var box: LinearLayout
    private lateinit var statusView: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        window.statusBarColor = Color.parseColor("#1f2430")
        val sv = ScrollView(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(18), dp(18), dp(36))
        sv.addView(box)
        setContentView(sv)

        heading("yaver on this phone")
        statusView = TextView(this)
        statusView.textSize = 14f
        statusView.setPadding(0, dp(4), 0, dp(10))
        box.addView(statusView)

        section("Default assistant")
        note("Make yaver open when you long-press the home button / swipe up from a bottom corner / hold the power button (depending on your phone). In the screen that opens choose Digital assistant app → yaver.")
        button("Choose yaver as the default assistant…") { openAssistantSettings() }
        toggle("Start listening when opened as assistant", "voiceOnAssist", false) {}
        toggle("Quick Settings tile starts voice input", "tileVoice", true) {}
        note("Also available: long-press the yaver icon for shortcuts (voice, new chat, mail, plan my day), the “Ask yaver” Quick Settings tile (edit your Quick Settings to add it), “Share → yaver” from any app, and “yaver” in the text-selection menu.")

        section("Always on")
        toggle("Keep yaver running in the background", "alwaysOn", true) { on ->
            if (on) KeepAliveService.start(this) else KeepAliveService.stop(this)
        }
        note("Shows a small silent notification. Needed for mail checks, heartbeats, background agents and reminder prompts while the screen is closed. Starts again after a restart of the phone.")
        button("Let yaver run without battery limits") { openBatterySettings() }

        section("Notifications")
        toggle("Notify me when yaver finishes while I'm away", "notifyReplies", true) {}
        toggle("Run reminder prompts automatically", "runReminders", true) {}
        button("Notification settings") {
            startSafe(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        if (Build.VERSION.SDK_INT in 31..32) {
            button("Allow exact reminder times") {
                startSafe(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }

        section("Company mail")
        toggle("Reconnect mail automatically when yaver starts", "autoMail", true) {}
        note("yaver opens your Outlook-on-the-web or EWS page in a built-in login screen and connects by itself. No bookmark is needed.")
        button("Forget saved mail passwords") {
            Creds.clear()
            Toast.makeText(this, "Saved mail passwords removed", Toast.LENGTH_SHORT).show()
        }
        button("Log out of all websites (clear cookies)") {
            AlertDialog.Builder(this).setMessage("Remove all website logins, including your mail session?")
                .setPositiveButton("Remove") { _, _ ->
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    Toast.makeText(this, "Cookies cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }

        section("Permissions")
        button("Allow calendar access") {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)
        }
        button("App permissions…") {
            startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        section("Troubleshooting")
        button("Show the last crash report") {
            val f = File(filesDir, "last-crash-shown.txt")
            val t = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
            AlertDialog.Builder(this).setTitle("Last crash").setMessage(if (t.isEmpty()) "No crash recorded." else t.take(6000))
                .setPositiveButton("OK", null).show()
        }
        button("Reload yaver") {
            YaverCore.web?.reload()
            Toast.makeText(this, "Reloading…", Toast.LENGTH_SHORT).show()
            finish()
        }
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Throwable) { "?" }
        note("yaver for Android $v")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val assistant = Settings.Secure.getString(contentResolver, "assistant") ?: ""
        val isDefault = assistant.contains(packageName)
        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        val notif = Notifier.canPost(this)
        val cal = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        statusView.text = listOf(
            (if (isDefault) "✅" else "⬜") + " Default assistant",
            (if (KeepAliveService.running) "✅" else "⬜") + " Always on",
            (if (unrestricted) "✅" else "⬜") + " No battery limits",
            (if (notif) "✅" else "⬜") + " Notifications",
            (if (cal) "✅" else "⬜") + " Phone calendar"
        ).joinToString("\n")
    }

    private fun startSafe(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: Throwable) {
        false
    }

    private fun openAssistantSettings() {
        if (startSafe(Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))) {
            Toast.makeText(this, "Tap “Digital assistant app”, then choose yaver", Toast.LENGTH_LONG).show()
            return
        }
        if (startSafe(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) return
        startSafe(Intent(Settings.ACTION_SETTINGS))
    }

    @Suppress("BatteryLife")
    private fun openBatterySettings() {
        if (!startSafe(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))) {
            startSafe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun heading(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 22f
        v.setTypeface(v.typeface, Typeface.BOLD)
        box.addView(v)
    }

    private fun section(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(Color.parseColor("#5b6b8c"))
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.setPadding(0, dp(20), 0, dp(6))
        box.addView(v)
    }

    private fun note(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(Color.parseColor("#666666"))
        v.setPadding(0, dp(2), 0, dp(8))
        box.addView(v)
    }

    private fun button(label: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(label: String, key: String, def: Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = label
        s.textSize = 15f
        s.isChecked = Prefs.on(key, def)
        s.setPadding(0, dp(8), 0, dp(8))
        s.setOnCheckedChangeListener { _, checked ->
            Prefs.set(key, checked)
            onChange(checked)
            refreshStatus()
        }
        box.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
