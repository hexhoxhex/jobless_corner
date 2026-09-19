package com.moviebox.tv.reminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps match reminders armed without anyone opening the app.
 *
 * Two holes this fills, both of which made a reminder silently not happen:
 *
 *  1. **A reboot wipes alarms.** Android drops every scheduled alarm on
 *     restart. [BootReceiver] could only leave a "rebuild me" flag for the
 *     next app start, so a TV that restarted and went back to its home
 *     screen never alerted again — and a TV is restarted far more often
 *     than a phone.
 *  2. **New fixtures were never learned.** The schedule was fetched at app
 *     start ([ReminderWarm]), so a TV nobody opens today knows nothing about
 *     tomorrow's matches and cannot arm an alarm for them.
 *
 * WorkManager survives both: it persists its queue across process death and
 * re-registers its own work after a reboot, so this runs on a TV sitting on
 * its home screen. It does nothing when the viewer follows nothing —
 * [ReminderWarm.run] skips the network entirely in that case.
 *
 * The one limit worth being honest about: a TV that is fully powered off
 * runs nothing at all. Alarms resume from the next boot onward; a match that
 * started while it was off has already been missed.
 */
class ScheduleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        runCatching {
            ReminderWarm.run(applicationContext)
            Result.success()
        }.getOrElse {
            // Transient (no network yet after boot, catalog 503) — let
            // WorkManager back off and try again rather than dropping the
            // day's reminders.
            Result.retry()
        }

    companion object {
        private const val PERIODIC = "schedule-sync"
        private const val NOW = "schedule-sync-now"

        /**
         * Every 6 hours. Fixtures are published a day or more ahead and a
         * reminder fires 20 minutes before kickoff, so this is many times
         * more often than it needs to be while still costing an idle TV
         * almost nothing.
         */
        private const val PERIOD_HOURS = 6L

        private val NETWORK = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Idempotent: safe to call on every app start. */
        fun schedule(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC,
                    // KEEP, so an app start doesn't reset the cadence and
                    // push the next run 6 hours out every time.
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                        PERIOD_HOURS, TimeUnit.HOURS,
                    ).setConstraints(NETWORK).build(),
                )
            }
        }

        /** Re-arm as soon as possible — used after a reboot. */
        fun syncNow(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    NOW,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ScheduleSyncWorker>()
                        .setConstraints(NETWORK)
                        .build(),
                )
            }
        }
    }
}
