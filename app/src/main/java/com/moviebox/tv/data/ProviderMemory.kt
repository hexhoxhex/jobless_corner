package com.moviebox.tv.data

import android.content.Context

/**
 * Remembers which provider actually served a given title.
 *
 * The failover chain tries sources in order, which is right the first time but
 * wasteful forever after: Malcolm in the Middle misses on MovieBox AND VidNest
 * (~5 s each) before VixSrc answers, so EVERY episode change paid ~6 s of dead
 * lookups — visible as the player sitting on a black screen "changing streams"
 * between episodes.
 *
 * Keyed by the title's own subjectId (not per-episode), so episode 2 of a
 * series benefits from what episode 1 learned.
 *
 * Purely an optimisation: a remembered provider is only moved to the FRONT of
 * the chain, never made exclusive. If it stops working the rest of the chain
 * still runs, and the next success overwrites the memory.
 */
object ProviderMemory {

    private const val PREFS = "provider_memory"
    private const val MAX = 300

    /** Provider label that last served [subjectId], or null. */
    fun preferredFor(ctx: Context, subjectId: String): String? =
        runCatching {
            prefs(ctx).getString(key(subjectId), null)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** Record that [provider] served [subjectId]. */
    fun remember(ctx: Context, subjectId: String, provider: String) {
        if (subjectId.isBlank() || provider.isBlank()) return
        runCatching {
            val p = prefs(ctx)
            // Cheap bound: this is a hint store, not a database. Clearing it
            // wholesale just means the next play re-learns in one round.
            if (p.all.size >= MAX) p.edit().clear().apply()
            p.edit().putString(key(subjectId), provider).apply()
        }
    }

    /** Forget a title's provider — used when the remembered one fails, so a
     *  source that dies doesn't keep getting first refusal forever. */
    fun forget(ctx: Context, subjectId: String) {
        runCatching { prefs(ctx).edit().remove(key(subjectId)).apply() }
    }

    /** Strip provider prefixes so every id for the same title shares one slot
     *  (`vn:tv:2004`, `vix:tv:2004` and the catalogue id all key together
     *  only insofar as the caller passes a stable id — we normalise the
     *  prefix so a remembered pick survives the chain rewriting the id). */
    private fun key(subjectId: String): String =
        subjectId.substringAfterLast(':').ifBlank { subjectId }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
