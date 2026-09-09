package com.moviebox.tv.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.moviebox.tv.data.live.FollowMatcher
import com.moviebox.tv.data.live.ScheduleEvent
import com.moviebox.tv.data.local.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turns "I follow Manchester United" into an alarm that fires 20 minutes
 * before they kick off.
 *
 * Rescheduling is idempotent and runs often — on app start, whenever the
 * schedule refreshes, and whenever the user adds or drops a follow. Two
 * things make that safe:
 *
 *  - the [PendingIntent] request code is derived from the event key, so a
 *    repeat schedule of the same match replaces its alarm instead of
 *    stacking a second one;
 *  - [com.moviebox.tv.data.local.ReminderFiredDao] records what has
 *    already been raised, so a reschedule after an alert can't re-alert.
 *
 * Alarms live in the system, not in our process, so they survive the app
 * being killed — but not a reboot, which is why [BootReceiver] re-runs
 * this on BOOT_COMPLETED.
 */
object ReminderScheduler {

    private const val TAG = "Reminders"

    /** Fires that are due within this window are raised immediately rather
     *  than scheduled — covers the case where the app starts up already
     *  inside a match's lead time. */
    private const val IMMEDIATE_WINDOW_SEC = 60L

    /** Bounded so a catalog with hundreds of followed fixtures can't
     *  exhaust the system's per-app alarm budget. Soonest kickoffs win. */
    private const val MAX_ALARMS = 40

    /** The most recent reminder, for the in-app banner. Cleared once the
     *  user acts on it or it ages out. */
    private val _pending = MutableStateFlow<ReminderPayload?>(null)
    val pending: StateFlow<ReminderPayload?> = _pending

    fun postInApp(payload: ReminderPayload) { _pending.value = payload }
    fun clearInApp() { _pending.value = null }

    /**
     * Rebuild the alarm set from the current follows and schedule.
     *
     * Safe to call with an empty [events] list — that just means the live
     * catalog hasn't loaded yet and there is nothing to schedule against;
     * existing alarms are left alone rather than being torn down, so a
     * cold start doesn't silently cancel today's reminders.
     */
    suspend fun reschedule(context: Context, events: List<ScheduleEvent>) {
        if (events.isEmpty()) {
            Log.d(TAG, "reschedule: no schedule loaded yet — keeping existing alarms")
            return
        }
        val db = AppDatabase.get(context)
        val follows = db.follows().allNow().filter { it.remind }
        if (follows.isEmpty()) {
            Log.d(TAG, "reschedule: nothing followed")
            return
        }
        val nowSec = System.currentTimeMillis() / 1000
        db.remindersFired().prune(nowSec - 2 * 24 * 60 * 60)

        val matches = FollowMatcher.upcoming(follows, events, nowSec)
        var scheduled = 0
        var fired = 0
        // Built once and shared across every match in this pass.
        val concurrency = com.moviebox.tv.data.live.EventChannelPicker
            .concurrencyMap(events, nowSec)
        for (m in matches) {
            if (scheduled >= MAX_ALARMS) break
            val start = m.event.startUnix ?: continue
            val key = FollowMatcher.eventKey(m.follow, m.event)
            if (db.remindersFired().wasFired(key)) continue

            val fireAt = start - m.follow.remindMinutes * 60L
            // NOT channels.first(). The catalog lists a channel against
            // every event it carries that day, so the first entry is
            // routinely a generic feed ("Sky Sports Main Event", "TSN5")
            // that is showing one of the OTHER fixtures when the reminder
            // fires — which is exactly the "it opened a channel without my
            // game on it" complaint. Pick the feed least likely to be
            // showing something else. See EventChannelPicker.
            val channel = com.moviebox.tv.data.live.EventChannelPicker
                .best(m.event, events, concurrency)
            val payload = ReminderPayload(
                eventKey = key,
                followLabel = m.follow.label,
                title = m.event.title,
                opponent = m.opponent,
                startUnix = start,
                channelId = channel?.id,
                channelName = channel?.name,
            )

            when {
                // Kickoff already passed, or we're inside the lead time
                // and never alerted — tell the user now, they can still
                // catch it.
                fireAt <= nowSec + IMMEDIATE_WINDOW_SEC -> {
                    if (start > nowSec - FollowMatcher.LIVE_GRACE_SEC) {
                        ReminderNotifier.raise(context, payload)
                        db.remindersFired().mark(
                            com.moviebox.tv.data.local.ReminderFiredEntity(
                                key, System.currentTimeMillis(),
                            )
                        )
                        fired++
                    }
                }

                else -> {
                    setAlarm(context, fireAt, payload)
                    scheduled++
                }
            }
        }
        Log.i(
            TAG,
            "reschedule: ${follows.size} follows, ${matches.size} matches -> " +
                "$scheduled alarms, $fired raised now",
        )
    }

    private fun setAlarm(context: Context, fireAtSec: Long, payload: ReminderPayload) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            payload.writeTo(this)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            // Stable per event so a reschedule replaces rather than stacks.
            payload.eventKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val atMs = fireAtSec * 1000

        // Exact where we're allowed to be. On API 31+ an app without the
        // exact-alarm privilege throws on setExact*, so check first and
        // degrade to the inexact-but-doze-proof variant rather than
        // crashing — a reminder a couple of minutes loose still does its
        // job, a SecurityException doesn't.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        }.onFailure {
            Log.w(TAG, "exact alarm refused, falling back", it)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) }
        }
    }
}
