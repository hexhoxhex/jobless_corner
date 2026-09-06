package com.moviebox.tv.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.moviebox.tv.MainActivity
import kotlin.math.max

/**
 * Raises a fired reminder on both surfaces we have.
 *
 * Android TV's notification story is thin — many boxes have no shade at
 * all — so the notification is the fallback, not the plan. The primary
 * path is [ReminderScheduler.pending], which the running app renders as
 * an on-screen banner with a "Watch now" button. Posting both means the
 * user gets told whether or not the app happens to be in the foreground.
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "reminders"
    private const val CHANNEL_NAME = "Match & show reminders"

    fun raise(context: Context, payload: ReminderPayload) {
        // In-app banner — the one that actually gets seen on a TV.
        ReminderScheduler.postInApp(payload)
        runCatching { notify(context, payload) }
    }

    private fun notify(context: Context, payload: ReminderPayload) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Alerts before a followed team or show starts" }
            )
        }

        val minutes = max(
            0L,
            (payload.startUnix - System.currentTimeMillis() / 1000) / 60,
        )
        val whenText = when {
            minutes <= 0 -> "starting now"
            minutes == 1L -> "starts in 1 minute"
            else -> "starts in $minutes minutes"
        }
        val body = buildString {
            append(whenText)
            payload.channelName?.takeIf { it.isNotBlank() }?.let { append(" · on ").append(it) }
        }

        // Tapping opens the app straight onto the carrying channel.
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = MainActivity.ACTION_PLAY_CHANNEL
            payload.channelId?.let { putExtra(MainActivity.EXTRA_CHANNEL_ID, it) }
        }
        val pi = PendingIntent.getActivity(
            context,
            payload.eventKey.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(payload.headline())
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n${payload.title}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        // POST_NOTIFICATIONS is declared; on 33+ it may still be denied,
        // in which case notify() throws and the in-app banner carries it.
        nm.notify(payload.eventKey.hashCode(), n)
    }
}
