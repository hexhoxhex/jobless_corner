package com.moviebox.tv.reminders

import android.content.Intent

/**
 * Everything a fired reminder needs to describe itself and to offer a
 * one-press "Watch now" — carried through the alarm [Intent] because the
 * process may well have been killed between scheduling and firing.
 */
data class ReminderPayload(
    val eventKey: String,
    /** The follow that triggered this, e.g. "Manchester United". */
    val followLabel: String,
    /** Raw catalog title, kept for the detail line. */
    val title: String,
    /** Other side of the fixture, when there is one. */
    val opponent: String?,
    val startUnix: Long,
    val channelId: String?,
    val channelName: String?,
) {
    /** "Manchester United vs Arsenal" — or the follow label alone when we
     *  couldn't parse an opponent (a show rather than a fixture). */
    fun headline(): String =
        if (!opponent.isNullOrBlank()) "$followLabel vs $opponent" else followLabel

    fun writeTo(intent: Intent) {
        intent.putExtra(K_KEY, eventKey)
        intent.putExtra(K_LABEL, followLabel)
        intent.putExtra(K_TITLE, title)
        intent.putExtra(K_OPPONENT, opponent)
        intent.putExtra(K_START, startUnix)
        intent.putExtra(K_CH_ID, channelId)
        intent.putExtra(K_CH_NAME, channelName)
    }

    companion object {
        private const val K_KEY = "r_key"
        private const val K_LABEL = "r_label"
        private const val K_TITLE = "r_title"
        private const val K_OPPONENT = "r_opp"
        private const val K_START = "r_start"
        private const val K_CH_ID = "r_ch"
        private const val K_CH_NAME = "r_chname"

        fun readFrom(intent: Intent): ReminderPayload? {
            val key = intent.getStringExtra(K_KEY) ?: return null
            return ReminderPayload(
                eventKey = key,
                followLabel = intent.getStringExtra(K_LABEL).orEmpty(),
                title = intent.getStringExtra(K_TITLE).orEmpty(),
                opponent = intent.getStringExtra(K_OPPONENT),
                startUnix = intent.getLongExtra(K_START, 0L),
                channelId = intent.getStringExtra(K_CH_ID),
                channelName = intent.getStringExtra(K_CH_NAME),
            )
        }
    }
}
