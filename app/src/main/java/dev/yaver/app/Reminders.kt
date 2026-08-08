package dev.yaver.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The thing a web app could never do: speak up while it is closed.
 *
 * Everything else in Yaver works because you opened it. A reminder that only
 * fires when you happen to be looking at the screen is not a reminder, and
 * that limitation is most of the reason this app is native at all.
 *
 * Alarms are exact when the system allows it and approximate otherwise.
 * Android 12 put exact alarms behind a user grant, and an app that refuses to
 * remind you at all because it was denied a privilege is worse than one that
 * is a few minutes late.
 */
object Reminders {

    private const val CHANNEL_TASKS = "tasks"
    private const val CHANNEL_ROUTINES = "routines"

    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_PROMPT = "prompt"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_TASKS, "Task reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Warnings before something is due" })
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ROUTINES, "Routines", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Recurring jobs, like a morning brief" })
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingFor(context: Context, requestCode: Int, extras: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, extras,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** A stable request code per item, so rescheduling replaces rather than piles up. */
    private fun codeFor(prefix: String, id: String) = (prefix + id).hashCode()

    private fun schedule(context: Context, at: Long, intent: Intent, code: Int) {
        if (at <= System.currentTimeMillis()) return
        val am = alarmManager(context)
        val pending = pendingFor(context, code, intent)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: SecurityException) {
            // Denied exact alarms mid-flight; approximate is still useful.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    // ── tasks ────────────────────────────────────────────────────────────────

    fun scheduleTask(context: Context, task: Store.Task) {
        cancelTask(context, task.id)
        val due = task.due ?: return
        if (task.done) return
        val fireAt = due - task.remind.coerceAtLeast(0) * 60_000L

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_KIND, "task")
            putExtra(EXTRA_ID, task.id)
            putExtra(EXTRA_TITLE, task.title)
            putExtra("due", due)
            // A unique action stops Android from treating two alarms as one.
            action = "dev.yaver.app.TASK_${task.id}"
        }
        schedule(context, fireAt, intent, codeFor("task", task.id))
    }

    fun cancelTask(context: Context, id: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "dev.yaver.app.TASK_$id"
        }
        alarmManager(context).cancel(pendingFor(context, codeFor("task", id), intent))
    }

    /** Re-arm everything. Alarms do not survive a reboot or a reinstall. */
    fun rescheduleAll(context: Context) {
        ensureChannels(context)
        Store.tasks().filter { !it.done && it.due != null }.forEach { scheduleTask(context, it) }
        Store.routines().forEach { scheduleRoutine(context, it) }
        Log.info("re-armed alarms for tasks and routines")
    }

    // ── routines ─────────────────────────────────────────────────────────────

    fun scheduleRoutine(context: Context, routine: Store.Routine) {
        cancelRoutine(context, routine.id)
        val next = routine.nextFireAt() ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_KIND, "routine")
            putExtra(EXTRA_ID, routine.id)
            putExtra(EXTRA_TITLE, routine.name)
            putExtra(EXTRA_PROMPT, routine.prompt)
            action = "dev.yaver.app.ROUTINE_${routine.id}"
        }
        schedule(context, next, intent, codeFor("routine", routine.id))
    }

    fun cancelRoutine(context: Context, id: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "dev.yaver.app.ROUTINE_$id"
        }
        alarmManager(context).cancel(pendingFor(context, codeFor("routine", id), intent))
    }

    // ── posting ──────────────────────────────────────────────────────────────

    const val ACTION_DONE = "dev.yaver.app.DONE"
    const val ACTION_SNOOZE = "dev.yaver.app.SNOOZE"
    private const val SNOOZE_MINUTES = 15

    /**
     * A reminder you can act on without opening anything.
     *
     * Most of the time the answer to "call the hotel at 10" is either "already
     * did" or "not now" — both of which should take one tap from the lock
     * screen rather than a trip through the app.
     */
    private fun taskActions(context: Context, taskId: String): List<Notification.Action> {
        fun action(label: String, act: String, icon: Int): Notification.Action {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = act
                putExtra(EXTRA_ID, taskId)
            }
            val pending = PendingIntent.getBroadcast(
                context, (act + taskId).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, icon), label, pending
            ).build()
        }
        return listOf(
            action("Done", ACTION_DONE, android.R.drawable.checkbox_on_background),
            action("Snooze ${SNOOZE_MINUTES}m", ACTION_SNOOZE, android.R.drawable.ic_menu_recent_history)
        )
    }

    fun snooze(context: Context, taskId: String) {
        val task = Store.tasks().firstOrNull { it.id == taskId } ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_KIND, "task")
            putExtra(EXTRA_ID, task.id)
            putExtra(EXTRA_TITLE, task.title)
            action = "dev.yaver.app.TASK_${task.id}"
        }
        schedule(context, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L,
            intent, codeFor("task", task.id))
        Log.info("snoozed ${task.title} by $SNOOZE_MINUTES minutes")
    }

    fun notify(
        context: Context, id: Int, channel: String, title: String, body: String,
        prompt: String?, taskId: String? = null
    ) {
        ensureChannels(context)

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            prompt?.let { putExtra(EXTRA_PROMPT, it) }
        }
        val pending = PendingIntent.getActivity(
            context, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
        taskId?.let { id2 -> taskActions(context, id2).forEach { builder.addAction(it) } }
        val notification = builder.build()

        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (e: Exception) {
            Log.error("could not post notification: ${e.message}")
        }
    }
}

/**
 * Wakes for a due task or a routine. Runs in a few milliseconds: post the
 * notification, re-arm the next occurrence, and get out. Anything slower here
 * risks being killed mid-work.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            Reminders.rescheduleAll(context)
            return
        }

        // Acting on a reminder from the shade, without opening the app.
        when (intent.action) {
            Reminders.ACTION_DONE -> {
                val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
                val list = Store.tasks()
                list.firstOrNull { it.id == id }?.let { task ->
                    task.done = true
                    Store.saveTasks(list)
                    Reminders.cancelTask(context, id)
                    Log.info("completed from the notification: ${task.title}")
                }
                context.getSystemService(android.app.NotificationManager::class.java)?.cancel(id.hashCode())
                return
            }
            Reminders.ACTION_SNOOZE -> {
                val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
                Reminders.snooze(context, id)
                context.getSystemService(android.app.NotificationManager::class.java)?.cancel(id.hashCode())
                return
            }
        }

        when (intent.getStringExtra(Reminders.EXTRA_KIND)) {
            "task" -> {
                val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
                val task = Store.tasks().firstOrNull { it.id == id } ?: return
                if (task.done) return
                val due = task.due
                val body = if (due != null) {
                    "Due ${Store.localIso(due).replace("T", " ").take(16)}"
                } else "Due now"
                Reminders.notify(context, id.hashCode(), "tasks", task.title, body, null, task.id)
                Log.info("reminded: ${task.title}")
            }
            "routine" -> {
                val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
                val routine = Store.routines().firstOrNull { it.id == id } ?: return
                Reminders.notify(
                    context, id.hashCode(), "routines", routine.name,
                    "Tap to run it now", routine.prompt
                )
                // Arm the next occurrence immediately; a repeating alarm that
                // depends on the app being opened is not repeating.
                Reminders.scheduleRoutine(context, routine)
                Log.info("routine due: ${routine.name}")
            }
        }
    }
}
