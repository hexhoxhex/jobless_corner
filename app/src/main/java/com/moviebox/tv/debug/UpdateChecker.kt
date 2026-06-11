package com.moviebox.tv.debug

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Polls hexhoxhex/jobless_corner/releases/latest and reports back whether
 * a newer release is available than the currently-installed
 * BuildConfig.VERSION_NAME.
 *
 * No auth — public release lookups don't need a token. We cap our request
 * rate to once-per-launch + once-per-day so even if every TV in the wild
 * is checking, we stay well inside GitHub's unauthenticated rate limit.
 */
class UpdateChecker {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a [Result] describing the latest release if it is newer than
     * [currentVersionName], or null otherwise. Failures (no network, GitHub
     * 5xx) return null silently — the calling UI just won't show the
     * banner.
     */
    suspend fun check(currentVersionName: String): Result? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/hexhoxhex/jobless_corner/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            // GitHub asks public clients to identify themselves with a UA.
            .header("User-Agent", "vijana-barubaru/$currentVersionName")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ProviderHealth.failure("github", "HTTP ${resp.code}")
                    return@use null
                }
                ProviderHealth.success("github")
                val body = resp.body?.string() ?: return@use null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trimStart('v').takeIf { it.isNotEmpty() }
                    ?: return@use null
                if (!isNewer(currentVersionName, tag)) return@use null
                val name = json.optString("name").takeIf { it.isNotEmpty() } ?: "v$tag"
                val htmlUrl = json.optString("html_url")
                // Pick the first .apk asset; release-page link is the fallback.
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val n = a.optString("name")
                        if (n.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                val notes = json.optString("body").take(MAX_NOTES_LEN)
                Result(
                    tag = tag,
                    name = name,
                    htmlUrl = htmlUrl,
                    apkUrl = apkUrl ?: htmlUrl,
                    notes = notes,
                )
            }
        }.onFailure { Log.w(TAG, "update check failed: ${it.message}") }.getOrNull()
    }

    /** "0.1.4" < "0.1.5", "0.1.10" > "0.1.9", "0.2.0" > "0.1.99". Anything
     *  that doesn't parse as a dot-separated number sequence returns false
     *  (no update offered). Pre-release suffixes like "-rc1" are ignored. */
    private fun isNewer(current: String, candidate: String): Boolean {
        val cur = current.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val cand = candidate.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        if (cur.isEmpty() || cand.isEmpty()) return false
        val n = maxOf(cur.size, cand.size)
        for (i in 0 until n) {
            val a = cur.getOrElse(i) { 0 }
            val b = cand.getOrElse(i) { 0 }
            if (a != b) return b > a
        }
        return false
    }

    data class Result(
        val tag: String,
        val name: String,
        val htmlUrl: String,
        val apkUrl: String,
        val notes: String,
    ) {
        fun toJson(): String {
            val o = JSONObject()
            o.put("tag", tag)
            o.put("name", name)
            o.put("htmlUrl", htmlUrl)
            o.put("apkUrl", apkUrl)
            o.put("notes", notes)
            return o.toString()
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val MAX_NOTES_LEN = 800
    }
}
