package com.moviebox.tv.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Remote control over the streaming-provider list.
 *
 * These endpoints rot fast: of the 16 providers in a maintained upstream repo,
 * only two still worked when tested. Previously a dead provider meant editing
 * Kotlin, cutting a release, and waiting for every user to update — while they
 * sat through its timeout on every play in the meantime.
 *
 * Now the ORDER and the ENABLED set come from a small JSON file published
 * alongside the live-TV catalogue, so disabling a dead source (or re-ordering
 * after one gets slow) is a file edit that reaches existing installs within
 * the TTL. Only the per-provider scraping logic stays in code — that genuinely
 * has to be written, and can't be expressed as config.
 *
 * The file is advisory: anything missing or malformed falls back to the
 * built-in defaults, so a bad publish can never leave users with no providers.
 *
 * Expected shape (all fields optional):
 * ```json
 * {
 *   "order":    ["MovieBox", "VidNest", "Icefy", "VixSrc", "4KHDHub"],
 *   "disabled": ["VixSrc"],
 *   "notes":    "VixSrc CDN slow from EA since 2026-08"
 * }
 * ```
 */
object ProviderConfig {

    private const val URL =
        "https://raw.githubusercontent.com/hexhoxhex/mkurugenzi_viewer/main/data/providers.json"
    private const val PREFS = "provider_config"
    private const val KEY_ORDER = "order"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_AT = "fetched_at"
    private const val TTL_MS = 60 * 60 * 1000L   // 1 h
    private const val TAG = "ProviderConfig"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var order: List<String> = emptyList()
    @Volatile private var disabled: Set<String> = emptySet()
    @Volatile private var loaded = false

    /** Apply the config to the built-in provider list. Unknown names in the
     *  config are ignored and providers it doesn't mention keep their default
     *  position, so a partial or stale file still yields a usable chain. */
    fun apply(defaults: List<Repository.Provider>): List<Repository.Provider> {
        if (!loaded) return defaults
        val ranked = defaults.filterNot { disabled.contains(it.label) }
        if (order.isEmpty()) return ranked.ifEmpty { defaults }
        val position = order.withIndex().associate { (i, label) -> label to i }
        return ranked
            .sortedBy { position[it.label] ?: Int.MAX_VALUE }
            .ifEmpty { defaults }
    }

    /** Load from cache immediately, then refresh from the network if stale.
     *  Safe to call on every app start; never throws. */
    suspend fun warm(ctx: Context) {
        runCatching { readCache(ctx) }
        val age = runCatching {
            System.currentTimeMillis() - prefs(ctx).getLong(KEY_AT, 0L)
        }.getOrDefault(Long.MAX_VALUE)
        if (age < TTL_MS && loaded) return
        val body = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(Request.Builder().url(URL).get().build()).execute()
                    .use { if (it.isSuccessful) it.body?.string() else null }
            }
        }.getOrNull() ?: return
        runCatching {
            val o = JSONObject(body)
            val newOrder = o.optJSONArray("order")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            val newDisabled = o.optJSONArray("disabled")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty().toSet()
            // Refuse a config that would disable EVERYTHING — that would leave
            // the app unable to play anything at all until the file is fixed.
            if (newDisabled.size >= Repository.Provider.entries.size) {
                android.util.Log.w(TAG, "ignoring config that disables every provider")
                return
            }
            order = newOrder
            disabled = newDisabled
            loaded = true
            prefs(ctx).edit()
                .putString(KEY_ORDER, newOrder.joinToString(","))
                .putString(KEY_DISABLED, newDisabled.joinToString(","))
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
            android.util.Log.i(TAG, "order=$newOrder disabled=$newDisabled")
        }
    }

    private fun readCache(ctx: Context) {
        val p = prefs(ctx)
        val o = p.getString(KEY_ORDER, null)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val d = p.getString(KEY_DISABLED, null)?.split(",")?.filter { it.isNotBlank() }
            .orEmpty().toSet()
        if (o.isNotEmpty() || d.isNotEmpty()) {
            order = o
            disabled = d
            loaded = true
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
