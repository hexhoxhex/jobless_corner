package com.moviebox.tv.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moviebox.tv.data.local.AppDatabase
import com.moviebox.tv.data.local.ReminderFiredEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alarm landed: raise the reminder and record it so it can't repeat. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val payload = ReminderPayload.readFrom(intent) ?: return
        Log.i("Reminders", "fired: ${payload.headline()}")
        ReminderNotifier.raise(context, payload)

        // goAsync() would be tidier, but the write is tiny and the
        // notification has already been posted — the ledger entry only
        // guards against a *future* reschedule re-alerting.
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                AppDatabase.get(app).remindersFired().mark(
                    ReminderFiredEntity(payload.eventKey, System.currentTimeMillis()),
                )
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.moviebox.tv.REMINDER_FIRE"
    }
}

/**
 * Alarms are wiped by a reboot, so rebuild them once the box is back up.
 *
 * The schedule isn't loaded at boot, so this can't reschedule directly —
 * it flips a flag the app checks on next start. That's honest about the
 * constraint: a TV that reboots and is never opened again won't alert,
 * because nothing of ours is running to notice.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("Reminders", "boot — reminders will rebuild on next app start")
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
            .edit().putBoolean("needs_rebuild", true).apply()
    }
}
