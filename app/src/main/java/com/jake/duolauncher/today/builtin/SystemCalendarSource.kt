package com.jake.duolauncher.today.builtin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/** Projection order is the cursor's column order, so the indices below are fixed by this array. */
private val PROJECTION = arrayOf(
    CalendarContract.Instances.EVENT_ID,
    CalendarContract.Instances.TITLE,
    CalendarContract.Instances.BEGIN,
    CalendarContract.Instances.END,
    CalendarContract.Instances.ALL_DAY,
    CalendarContract.Instances.DISPLAY_COLOR,
    CalendarContract.Instances.STATUS,
    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
)

private const val COLUMN_EVENT_ID = 0
private const val COLUMN_TITLE = 1
private const val COLUMN_BEGIN = 2
private const val COLUMN_END = 3
private const val COLUMN_ALL_DAY = 4
private const val COLUMN_COLOR = 5
private const val COLUMN_STATUS = 6
private const val COLUMN_ATTENDEE_STATUS = 7

/** Guard against a pathological calendar; the feed only ever shows [CALENDAR_EVENT_COUNT]. */
private const val MAX_ROWS = 256

/**
 * Reads the next few events from the system calendar provider, on demand.
 *
 * **Nothing is cached and nothing is persisted (NFR-S4).** Each call opens a cursor, copies the
 * handful of fields the widget draws, closes the cursor and returns. There is no store, no file and
 * no field on this class holding an event between calls.
 *
 * The permission is re-read on every call rather than remembered, so revoking `READ_CALENDAR` takes
 * effect on the next refresh.
 */
class SystemCalendarSource(context: Context) : CalendarSource {
    private val appContext = context.applicationContext

    override fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Instances overlapping the window, or null when the provider could not be read.
     *
     * The window is encoded in the URI path, which is how `CalendarContract.Instances` expands
     * recurring events into concrete occurrences. The query goes through the `ContentResolver`
     * directly rather than the `Instances.query` helper so that the runtime permission check above
     * is the single gate on this call.
     *
     * Requires `<uses-permission android:name="android.permission.READ_CALENDAR" />` in the
     * manifest; without it the resolver throws and this returns null, which the feed surfaces as
     * [CalendarFeedState.Unavailable] rather than a crash.
     */
    override fun query(fromMillis: Long, toMillis: Long): List<CalendarEvent>? {
        if (!isPermissionGranted()) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMillis.toString())
            .appendPath(toMillis.toString())
            .build()
        return runCatching {
            appContext.contentResolver.query(
                uri,
                PROJECTION,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use(::readEvents)
        }.getOrNull()
    }

    private fun readEvents(cursor: Cursor): List<CalendarEvent> {
        val events = ArrayList<CalendarEvent>()
        while (cursor.moveToNext() && events.size < MAX_ROWS) {
            // Cancelled events and ones the user declined are not upcoming commitments, so they are
            // filtered here rather than in the selection string: an invalid selection would fail the
            // whole query, while a missing column here simply leaves the row in.
            if (!cursor.isNull(COLUMN_STATUS) &&
                cursor.getInt(COLUMN_STATUS) == CalendarContract.Events.STATUS_CANCELED
            ) {
                continue
            }
            if (!cursor.isNull(COLUMN_ATTENDEE_STATUS) &&
                cursor.getInt(COLUMN_ATTENDEE_STATUS) ==
                CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
            ) {
                continue
            }
            val begin = cursor.getLong(COLUMN_BEGIN)
            val end = cursor.getLong(COLUMN_END)
            if (end < begin) continue
            events += CalendarEvent(
                eventId = cursor.getLong(COLUMN_EVENT_ID),
                title = cursor.getString(COLUMN_TITLE).orEmpty().trim(),
                beginMillis = begin,
                endMillis = end,
                allDay = !cursor.isNull(COLUMN_ALL_DAY) && cursor.getInt(COLUMN_ALL_DAY) != 0,
                color = if (cursor.isNull(COLUMN_COLOR)) 0 else cursor.getInt(COLUMN_COLOR),
            )
        }
        return events
    }
}
