package com.plusai.yaver.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.plusai.yaver.R
import java.util.TimeZone

/**
 * Home-screen widget (4×2, resizable): next meeting, unread / needs-reply counts, a big "Ask" button and a mic.
 * Redrawn when the brief refreshes, when mail/tasks change, by the system every 30 min (updatePeriodMillis) and by a
 * non-waking alarm when the shown meeting starts or ends — none of these wake the phone from Doze.
 */
class FiveWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        Y.init(context)
        val p = goAsync()
        Y.bg {
            try { FiveWidget.render(context, mgr, ids); FiveBrief.refreshIfStale() }
            catch (e: Throwable) { Diag.e("widget", "update", e) } finally { p.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        onUpdate(context, mgr, intArrayOf(id))
    }

    override fun onDisabled(context: Context) { FiveWidget.cancelTick(context) }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FiveWidget.ACTION_TICK) {
            Y.init(context)
            val mgr = AppWidgetManager.getInstance(context)
            val ids = FiveWidget.ids(context)
            if (ids.isNotEmpty()) onUpdate(context, mgr, ids)
            return
        }
        super.onReceive(context, intent)
    }
}

object FiveWidget {
    const val ACTION_TICK = "com.plusai.yaver.WIDGET_TICK"
    /** Extra on the widget's "Ask" intent: MainActivity raises the keyboard. */
    const val EXTRA_KEYBOARD = "yaver_keyboard"

    fun ids(c: Context): IntArray = try { AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, FiveWidgetProvider::class.java)) } catch (_: Throwable) { IntArray(0) }

    fun hasWidgets(c: Context) = ids(c).isNotEmpty()

    /** Redraws every placed widget from the cached brief (worker thread). */
    fun updateAll(c: Context) {
        val ids = ids(c)
        if (ids.isEmpty()) return
        render(c, AppWidgetManager.getInstance(c), ids)
    }

    @Volatile private var soonAt = 0L
    /** Debounced redraw after mail / triage / task changes. */
    private val soon = Runnable { Y.bg { try { updateAll(Y.app) } catch (e: Throwable) { Diag.e("widget", "soon", e) } } }
    /** Redraw shortly after a change; a burst of changes gives one redraw after the last (trailing debounce). */
    fun updateSoon() {
        soonAt = System.currentTimeMillis()
        Y.main.removeCallbacks(soon); Y.main.postDelayed(soon, 1500)
    }

    private fun open(c: Context, req: Int, action: String, payload: String? = null, keyboard: Boolean = false): PendingIntent {
        val i = Intent(c, MainActivity::class.java).setAction("com.plusai.yaver.WIDGET_$req")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Keys.TOKEN, Keys.token()).putExtra(Keys.ACTION, action)
        if (payload != null) i.putExtra(Keys.PAYLOAD, payload)
        if (keyboard) i.putExtra(EXTRA_KEYBOARD, true)
        return PendingIntent.getActivity(c, 300 + req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun render(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val brief = try { FiveBrief.snapshot() } catch (e: Throwable) { null }
        val now = System.currentTimeMillis()
        val w = FiveLogic.widgetText(brief, now, TimeZone.getDefault(), Y.tr)
        val full = views(c, w, now, compact = false)
        val rv = if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Android 12+: the launcher picks the layout for the widget's real size; a short widget drops the place line
            RemoteViews(mapOf(android.util.SizeF(180f, 110f) to views(c, w, now, compact = true), android.util.SizeF(180f, 168f) to full))
        } else full
        mgr.updateAppWidget(ids, rv)
        scheduleTick(c, FiveLogic.nextWidgetTick(brief, now))
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun views(c: Context, w: FiveLogic.WidgetText, now: Long, compact: Boolean): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.five_widget)
        val loc = if (Y.tr) java.util.Locale("tr", "TR") else java.util.Locale.US
        rv.setTextViewText(R.id.five_w_date, java.text.SimpleDateFormat(if (Y.tr) "d MMMM EEE" else "EEE, MMM d", loc).format(java.util.Date(now)))
        // chips: unread / waiting for a reply / next alarm (or "mail not connected")
        if (!w.connected) {
            rv.setTextViewText(R.id.five_w_unread, Y.t("Mail off", "Posta kapalı"))
            rv.setViewVisibility(R.id.five_w_unread, View.VISIBLE)
            rv.setViewVisibility(R.id.five_w_reply, View.GONE)
        } else {
            rv.setTextViewText(R.id.five_w_unread, w.unread.toString())
            rv.setContentDescription(R.id.five_w_unread, Y.t("${w.unread} unread", "${w.unread} okunmamış"))
            rv.setViewVisibility(R.id.five_w_unread, View.VISIBLE)
            rv.setTextViewText(R.id.five_w_reply, w.reply.toString())
            rv.setContentDescription(R.id.five_w_reply, Y.t("${w.reply} need a reply", "${w.reply} yanıt bekliyor"))
            rv.setViewVisibility(R.id.five_w_reply, if (w.reply > 0) View.VISIBLE else View.GONE)
        }
        rv.setTextViewText(R.id.five_w_alarm, w.alarm)
        rv.setContentDescription(R.id.five_w_alarm, Y.t("Alarm ", "Alarm ") + w.alarm)
        rv.setViewVisibility(R.id.five_w_alarm, if (w.alarm.isEmpty()) View.GONE else View.VISIBLE)
        // the next meeting
        if (w.hasMeeting && compact) {
            // a short widget: one line "14:00  Title" (the time in the accent colour), tighter spacing
            val time = if (w.meetTime.contains('–')) w.meetTime.substringBefore('–') else w.meetTime
            val sp = android.text.SpannableString("$time  ${w.meetTitle}")
            sp.setSpan(android.text.style.ForegroundColorSpan(c.getColor(R.color.five_w_accent)), 0, time.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setTextViewText(R.id.five_w_title, sp)
            rv.setViewVisibility(R.id.five_w_time, View.GONE)
            rv.setViewVisibility(R.id.five_w_bar, View.VISIBLE)
        } else if (w.hasMeeting) {
            rv.setTextViewText(R.id.five_w_time, listOf(w.meetTime, w.meetRel).filter { it.isNotEmpty() }.joinToString(" · "))
            rv.setViewVisibility(R.id.five_w_time, View.VISIBLE)
            rv.setViewVisibility(R.id.five_w_bar, View.VISIBLE)
            rv.setTextViewText(R.id.five_w_title, w.meetTitle)
        } else {
            rv.setViewVisibility(R.id.five_w_time, View.GONE)
            rv.setViewVisibility(R.id.five_w_bar, View.GONE)
            rv.setTextViewText(R.id.five_w_title, w.meetTitle)
        }
        if (compact && android.os.Build.VERSION.SDK_INT >= 31) {
            rv.setViewLayoutMargin(R.id.five_w_card, RemoteViews.MARGIN_TOP, 6f, android.util.TypedValue.COMPLEX_UNIT_DIP)
            rv.setViewPadding(R.id.five_w_card, dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4))
            rv.setViewLayoutMargin(R.id.five_w_askrow, RemoteViews.MARGIN_TOP, 6f, android.util.TypedValue.COMPLEX_UNIT_DIP)
        }
        val place = !compact && w.hasMeeting && w.meetPlace.isNotEmpty()
        rv.setViewVisibility(R.id.five_w_place_row, if (place) View.VISIBLE else View.GONE)
        if (place) {
            rv.setTextViewText(R.id.five_w_place, w.meetPlace)
            rv.setImageViewResource(R.id.five_w_place_icon, if (w.online) R.drawable.five_ic_video else R.drawable.five_ic_place)
        }
        rv.setTextViewText(R.id.five_w_ask_text, Y.t("Ask yaver", "yaver'e sor"))
        rv.setContentDescription(R.id.five_w_mic, Y.t("Ask by voice", "Sesle sor"))
        rv.setOnClickPendingIntent(R.id.five_w_card, open(c, 1, "nav", "calendar"))
        rv.setOnClickPendingIntent(R.id.five_w_unread, open(c, 2, "nav", "mail"))
        rv.setOnClickPendingIntent(R.id.five_w_reply, open(c, 2, "nav", "mail"))
        rv.setOnClickPendingIntent(R.id.five_w_ask, open(c, 3, "assist", keyboard = true))
        rv.setOnClickPendingIntent(R.id.five_w_mic, open(c, 4, "voice"))
        rv.setOnClickPendingIntent(R.id.five_w_head, open(c, 5, "open"))
        rv.setOnClickPendingIntent(R.id.five_w_alarm, open(c, 5, "open"))
        return rv
    }

    private fun tickIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 399,
        Intent(c, FiveWidgetProvider::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** A non-waking, inexact alarm: delivered when the phone is next awake (Doze-friendly). */
    private fun scheduleTick(c: Context, at: Long) {
        // a 1-minute window: on time while the phone is in use (a meeting rolls over when it ends), still non-waking
        try { c.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC, at, 60_000L, tickIntent(c)) } catch (e: Throwable) { Diag.e("widget", "tick", e) }
    }

    fun cancelTick(c: Context) { try { c.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(c)) } catch (_: Throwable) {} }
}
