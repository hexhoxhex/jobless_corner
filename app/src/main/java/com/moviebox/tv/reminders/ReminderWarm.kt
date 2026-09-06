package com.moviebox.tv.reminders

import android.content.Context
import android.util.Log
import com.moviebox.tv.data.live.LiveTvRepository
import com.moviebox.tv.data.live.ScheduleEvent
import com.moviebox.tv.data.local.AppDatabase

/**
 * Arms reminders without waiting for the user to visit the Live tab.
 *
 * The VM only fetches the schedule when someone opens Live, so a TV that
 * boots and sits on the home screen would never schedule a single alarm —
 * which defeats the point of a reminder. This pulls the schedule directly
 * on startup whenever there is at least one follow, and hands it to
 * [ReminderScheduler].
 *
 * It also keeps the last fetch around so the remote's Following endpoints
 * can answer from something real when the VM's own copy is still empty.
 */
object ReminderWarm {

    private const val TAG = "Reminders"

    @Volatile private var cached: List<ScheduleEvent> = emptyList()

    /** Schedule to answer API calls from: the VM's if it has one, else
     *  whatever the last warm fetched. Avoids "nothing scheduled today"
     *  on a cold app that simply hasn't opened Live yet. */
    fun scheduleOrCached(vmSchedule: List<ScheduleEvent>): List<ScheduleEvent> =
        vmSchedule.ifEmpty { cached }

    fun cache(events: List<ScheduleEvent>) {
        if (events.isNotEmpty()) cached = events
    }

    /**
     * Fetch the schedule and (re)arm alarms. Skips the network entirely
     * when nothing is followed — no reason to pull a schedule for a user
     * who hasn't asked to be told about anything.
     */
    suspend fun run(context: Context) {
        val app = context.applicationContext
        val follows = runCatching { AppDatabase.get(app).follows().allNow() }
            .getOrDefault(emptyList())
        if (follows.isEmpty()) {
            Log.d(TAG, "warm: nothing followed, skipping schedule fetch")
            return
        }
        val events = runCatching { LiveTvRepository().schedule() }.getOrNull().orEmpty()
        if (events.isEmpty()) {
            Log.w(TAG, "warm: schedule fetch came back empty")
            return
        }
        cache(events)
        Log.i(TAG, "warm: ${events.size} events for ${follows.size} follows")
        ReminderScheduler.reschedule(app, events)
    }
}
