package com.moviebox.tv.tv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.moviebox.tv.MainActivity
import com.moviebox.tv.data.local.WatchHistoryEntity

/**
 * Puts what you were last watching on the TV's own home screen, the way
 * YouTube and Netflix do.
 *
 * Android TV launchers keep a "Continue watching" row fed by the system's
 * Watch Next provider. Until now the app published nothing to it, so
 * resuming a film meant opening the app and finding the row inside it. One
 * entry only — the most recent thing, film or episode — because that is what
 * the row is for, and a stack of half-finished titles from months ago is
 * noise on someone's home screen.
 *
 * Everything is wrapped: not every box ships the TV provider (this app also
 * runs on phones), and a launcher that rejects the write must never take
 * playback down with it.
 */
object WatchNext {

    private const val TAG = "WatchNext"

    /** Marks the rows as ours so we replace rather than accumulate. */
    private const val PROVIDER_ID = "vijanabarubaru-continue"

    /** Below this, the viewer has barely started — not worth surfacing. */
    private const val MIN_POSITION_MS = 30_000L

    /** Within this of the end it is finished; drop it from the row. */
    private const val END_SLACK_MS = 20_000L

    fun publish(context: Context, h: WatchHistoryEntity) {
        runCatching {
            val ctx = context.applicationContext
            if (h.durationMs > 0 && h.positionMs >= h.durationMs - END_SLACK_MS) {
                // Finished — clear the row instead of inviting someone to
                // resume the last 10 seconds of something they completed.
                clear(ctx)
                return@runCatching
            }
            if (h.positionMs < MIN_POSITION_MS) return@runCatching
            clear(ctx)

            val isEpisode = h.season > 0 || h.episode > 0
            val resume = Intent(ctx, MainActivity::class.java).apply {
                action = MainActivity.ACTION_RESUME
                putExtra(MainActivity.EXTRA_SUBJECT_ID, h.subjectId)
                putExtra(MainActivity.EXTRA_TITLE, h.title)
                putExtra(MainActivity.EXTRA_COVER, h.coverUrl)
                putExtra(MainActivity.EXTRA_TYPE, h.type)
                putExtra(MainActivity.EXTRA_SEASON, h.season)
                putExtra(MainActivity.EXTRA_EPISODE, h.episode)
                h.year?.let { putExtra(MainActivity.EXTRA_YEAR, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val builder = WatchNextProgram.Builder()
                .setType(
                    if (isEpisode) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                    else TvContractCompat.WatchNextPrograms.TYPE_MOVIE,
                )
                .setWatchNextType(
                    TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
                )
                .setTitle(h.title)
                .setInternalProviderId(PROVIDER_ID)
                .setLastEngagementTimeUtcMillis(h.updatedAt)
                .setIntentUri(Uri.parse(resume.toUri(Intent.URI_INTENT_SCHEME)))
            if (isEpisode) {
                if (h.season > 0) builder.setSeasonNumber(h.season)
                if (h.episode > 0) builder.setEpisodeNumber(h.episode)
                h.episodeTitle.takeIf { it.isNotBlank() }
                    ?.let { builder.setEpisodeTitle(it) }
            }
            h.coverUrl?.takeIf { it.isNotBlank() }
                ?.let { builder.setPosterArtUri(Uri.parse(it)) }
            if (h.positionMs > 0) builder.setLastPlaybackPositionMillis(h.positionMs.toInt())
            if (h.durationMs > 0) builder.setDurationMillis(h.durationMs.toInt())

            val uri = ctx.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                builder.build().toContentValues(),
            )
            Log.i(TAG, "published \"${h.title}\" -> $uri")
        }.onFailure {
            // A launcher without the provider, or one that refuses the write.
            Log.i(TAG, "not published: ${it.message}")
        }
    }

    /** Remove our entry. Only ever touches rows carrying [PROVIDER_ID]. */
    fun clear(context: Context) {
        runCatching {
            val cr = context.applicationContext.contentResolver
            cr.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) != PROVIDER_ID) continue
                    cr.delete(
                        ContentUris.withAppendedId(
                            TvContractCompat.WatchNextPrograms.CONTENT_URI,
                            c.getLong(0),
                        ),
                        null, null,
                    )
                }
            }
        }
    }
}
