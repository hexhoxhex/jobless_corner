package com.moviebox.tv.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Check a stream the way the player will fetch it, before a provider offers it.
 *
 * Providers keep handing out links that don't play: VidNest's goodstream.cc
 * links (2026-10-09) answered with a 403 in the player. Counting a link as a
 * success just because the provider returned one made the app remember that
 * provider for the show and sit on the error. The top playlist can answer
 * while what's behind it doesn't, so this follows it down to one segment.
 */
object StreamProbe {

    /** A link that hasn't answered in 6 s is no use to a viewer. */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * "hls" for a playlist whose first variant and first segment answer,
     * "file" for anything else that answers, null when it refuses. Sent with
     * exactly the player's headers for this stream
     * ([Constants.mediaHeadersFor]).
     */
    fun check(url: String, streamHeaders: Map<String, String>): String? = runCatching {
        val h = Constants.mediaHeadersFor(streamHeaders)
        fun request(u: String) = Request.Builder().url(u).apply {
            h.forEach { (k, v) -> header(k, v) }
        }
        // The start of [u]'s body, or null when it refuses or serves a page
        // (a challenge or error) instead of media.
        fun open(u: String): String? =
            client.newCall(request(u).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val head = r.peekBody(64L * 1024).string()
                    .trimStart('﻿', ' ', '\r', '\n', '\t')
                if (r.header("Content-Type").orEmpty().contains("text/html", true) ||
                    head.startsWith("<")
                ) null else head
            }
        fun firstUri(playlist: String, base: String): String? =
            playlist.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.let { base.toHttpUrlOrNull()?.resolve(it)?.toString() }

        var text = open(url) ?: return@runCatching null
        if (!text.startsWith("#EXTM3U")) return@runCatching "file"
        var base = url
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = firstUri(text, base) ?: return@runCatching null
            text = open(variant)?.takeIf { it.startsWith("#EXTM3U") }
                ?: return@runCatching null
            base = variant
        }
        val segment = firstUri(text, base) ?: return@runCatching "hls"
        val ok = client.newCall(
            request(segment).header("Range", "bytes=0-1023").get().build(),
        ).execute().use { it.isSuccessful }
        if (ok) "hls" else null
    }.getOrNull()
}
