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
        val rv = RemoteViews(c.packageName, R.layout.five_widget)
        rv.setTextViewText(R.id.five_w_when, w.meetWhen)
        rv.setViewVisibility(R.id.five_w_when, if (w.meetWhen.isEmpty()) View.GONE else View.VISIBLE)
        rv.setTextViewText(R.id.five_w_title, w.meetTitle)
        rv.setTextViewText(R.id.five_w_mail, w.mailLine)
        rv.setViewVisibility(R.id.five_w_mail, if (w.mailLine.isEmpty()) View.GONE else View.VISIBLE)
        rv.setTextViewText(R.id.five_w_alarm, if (w.alarm.isEmpty()) "" else "⏰ " + w.alarm)
        rv.setViewVisibility(R.id.five_w_alarm, if (w.alarm.isEmpty()) View.GONE else View.VISIBLE)
        rv.setTextViewText(R.id.five_w_ask, Y.t("Ask yaver", "Sor"))
        rv.setContentDescription(R.id.five_w_mic, Y.t("Ask by voice", "Sesle sor"))
        rv.setOnClickPendingIntent(R.id.five_w_meet, open(c, 1, "nav", "calendar"))
        rv.setOnClickPendingIntent(R.id.five_w_mail, open(c, 2, "nav", "mail"))
        rv.setOnClickPendingIntent(R.id.five_w_ask, open(c, 3, "assist", keyboard = true))
        rv.setOnClickPendingIntent(R.id.five_w_mic, open(c, 4, "voice"))
        rv.setOnClickPendingIntent(R.id.five_w_head, open(c, 5, "open"))
        mgr.updateAppWidget(ids, rv)
        scheduleTick(c, FiveLogic.nextWidgetTick(brief, now))
    }

    private fun tickIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 399,
        Intent(c, FiveWidgetProvider::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** A non-waking, inexact alarm: delivered when the phone is next awake (Doze-friendly). */
    private fun scheduleTick(c: Context, at: Long) {
        try { c.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, at, tickIntent(c)) } catch (e: Throwable) { Diag.e("widget", "tick", e) }
    }

    fun cancelTick(c: Context) { try { c.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(c)) } catch (_: Throwable) {} }
}
