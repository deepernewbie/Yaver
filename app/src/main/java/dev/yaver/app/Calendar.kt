package dev.yaver.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * The phone's own calendar, through the same provider the stock Calendar app
 * uses. Not an .ics file the user has to import by hand — this reads what is
 * actually there and changes it.
 *
 * READ_CALENDAR and WRITE_CALENDAR are ordinary dangerous permissions. They do
 * not put the app on Play Protect's blocked list, so asking for them costs
 * nothing at install time.
 */
object Calendar {

    const val REQUEST_CODE = 4711
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    data class Event(
        val id: Long, val title: String, val start: Long, val end: Long,
        val location: String, val notes: String, val allDay: Boolean
    )

    fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun request(activity: Activity) {
        activity.requestPermissions(PERMISSIONS, REQUEST_CODE)
    }

    private fun writableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        var fallback: Long? = null
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = c.getLong(0)
                if (c.getInt(2) == 1) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    /**
     * Instances rather than Events, so a repeating meeting is expanded into the
     * occurrences the user actually sees in their week.
     */
    fun list(context: Context, from: Long, to: Long, limit: Int = 50): List<Event> {
        if (!canRead(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString()).appendPath(to.toString()).build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY
        )
        val out = mutableListOf<Event>()
        try {
            context.contentResolver.query(
                uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out.add(Event(
                        id = c.getLong(0),
                        title = c.getString(1) ?: "(no title)",
                        start = c.getLong(2),
                        end = c.getLong(3),
                        location = c.getString(4) ?: "",
                        notes = c.getString(5) ?: "",
                        allDay = c.getInt(6) == 1
                    ))
                }
            }
        } catch (e: Exception) {
            Log.error("calendar read failed: ${e.message}")
        }
        return out
    }

    fun create(
        context: Context, title: String, start: Long, end: Long,
        location: String = "", notes: String = "", remindMinutes: Int = 30
    ): Long {
        if (!canWrite(context)) throw IllegalStateException("Calendar permission not granted")
        val calId = writableCalendarId(context)
            ?: throw IllegalStateException("No writable calendar on this device")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (notes.isNotEmpty()) put(CalendarContract.Events.DESCRIPTION, notes)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("The calendar refused the event")
        val id = ContentUris.parseId(uri)

        // Putting something in a calendar without an alert is filing it, not
        // remembering it.
        if (remindMinutes >= 0) {
            try {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, id)
                        put(CalendarContract.Reminders.MINUTES, remindMinutes)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    })
            } catch (e: Exception) { Log.error("reminder not set: ${e.message}") }
        }
        return id
    }

    fun update(
        context: Context, id: Long, title: String? = null, start: Long? = null,
        end: Long? = null, location: String? = null, notes: String? = null
    ): Boolean {
        if (!canWrite(context)) throw IllegalStateException("Calendar permission not granted")
        val values = ContentValues()
        title?.let { values.put(CalendarContract.Events.TITLE, it) }
        start?.let { values.put(CalendarContract.Events.DTSTART, it) }
        end?.let { values.put(CalendarContract.Events.DTEND, it) }
        location?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        notes?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        if (values.size() == 0) return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    fun delete(context: Context, id: Long): Boolean {
        if (!canWrite(context)) throw IllegalStateException("Calendar permission not granted")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        return context.contentResolver.delete(uri, null, null) > 0
    }

    fun toJson(events: List<Event>): JSONArray {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject()
                .put("id", e.id).put("title", e.title)
                .put("starts", Store.localIso(e.start))
                .put("ends", Store.localIso(e.end))
                .put("location", e.location)
                .put("all_day", e.allDay))
        }
        return arr
    }
}
