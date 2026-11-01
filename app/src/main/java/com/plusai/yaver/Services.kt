package com.plusai.yaver

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.io.File

/**
 * "Always on": a foreground service with a quiet notification. It keeps the process — and with it the
 * yaver page — alive when the screen is closed, so mail checks, heartbeats, background agents and
 * reminder prompts keep running. Started at boot too.
 */
class KeepAliveService : Service() {
    companion object {
        @Volatile var running = false
        private const val NOTIF_ID = 7

        fun start(c: Context) {
            val i = Intent(c, KeepAliveService::class.java)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, KeepAliveService::class.java).setAction("stop"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pi(action: String, code: Int): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .putExtra(Keys.ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("yaver is on")
            .setContentText("Tap to ask · mail, reminders and scheduled work keep running")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi("assist", 101))
            .addAction(Notification.Action.Builder(Icon.createWithResource("android", android.R.drawable.ic_btn_speak_now), "Ask by voice", pi("voice", 102)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource("android", android.R.drawable.ic_menu_add), "New chat", pi("new", 103)).build())
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
            running = true
        } catch (e: Throwable) {
            runCatching { File(filesDir, "last-crash.txt").writeText("Always-on service could not start:\n" + android.util.Log.getStackTraceString(e)) }
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.init(this)
        YaverCore.init(this)
        // run the yaver page even when no screen is open (after boot, or after the screen was closed)
        YaverCore.main.post {
            try { YaverCore.ensure(YaverCore.activity ?: applicationContext) } catch (_: Throwable) {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        YaverCore.init(context)
        try { ReminderStore.rescheduleAll(context) } catch (_: Throwable) {}
        if (Prefs.on("alwaysOn", true)) {
            try {
                KeepAliveService.start(context)
            } catch (e: Throwable) {
                runCatching { File(context.filesDir, "last-crash.txt").writeText("Start at boot failed:\n" + android.util.Log.getStackTraceString(e)) }
            }
        }
    }
}

/** Quick Settings tile: swipe down, tap "Ask yaver". */
class YaverTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        t.state = Tile.STATE_INACTIVE
        t.label = "Ask yaver"
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val action = if (Prefs.on("tileVoice", true)) "voice" else "assist"
        val i = Intent(this, MainActivity::class.java)
            .putExtra(Keys.ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val p = PendingIntent.getActivity(this, 201, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(p)
        } else {
            startActivityAndCollapse(i)
        }
    }
}
