package com.moviebox.tv.net

import com.moviebox.tv.data.Item
import com.moviebox.tv.data.SubjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper over [H5Client] that produces the app's [Item] model from H5
 * `subject/search` and resolves a playable stream URL via the
 * `themoviebox.org/wefeed-h5api-bff/subject/play` proxy.
 *
 * Response shapes captured live:
 *  - search: `{code, data:{items:[{subjectId,title,subjectType,releaseDate,
 *    cover:{url},detailPath,imdbRatingValue,hasResource,…}], pager}}`
 *  - play:   `{code, data:{streams:[{format,id,url,resolutions,size,duration,
 *    codecName}], dash:[…], hls:[…], hasResource, vipLocked}}`
 */
object H5Api {

    private const val PATH_SEARCH = "/wefeed-h5api-bff/subject/search"
    private const val PATH_PLAY = "/wefeed-h5api-bff/subject/play"

    data class PlayStream(
        val url: String,
        val resolution: Int,
        val format: String,
        val id: String,
        val durationSec: Int,
    )
    data class PlayResult(val streams: List<PlayStream>, val hasResource: Boolean)

    /** Cached detailPath per subjectId — populated by search results so play
     *  can use the real canonical slug. The proxy rejects synthetic slugs with
     *  400 "empty subjectId" (it validates the slug matches the subjectId).
     *  Cleared with the process; no persistence needed. */
    private val detailPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun detailPathFor(subjectId: String): String? = detailPathCache[subjectId]

    suspend fun search(
        keyword: String,
        page: Int = 1,
        perPage: Int = 20,
        subjectType: Int = 0,
    ): List<Item> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("keyword", keyword)
            .put("page", page)
            .put("perPage", perPage)
            .put("subjectType", subjectType)
            .toString()
        val raw = H5Client.signedPost(PATH_SEARCH, body)
        val root = JSONObject(raw)
        if (root.optInt("code") != 0) return@withContext emptyList()
        val items = root.optJSONObject("data")?.optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { i ->
            runCatching {
                val raw = items.getJSONObject(i)
                val item = raw.toItem()
                raw.optString("detailPath").takeIf { it.isNotBlank() }
                    ?.let { detailPathCache[item.subjectId] = it }
                item
            }.getOrNull()
        }
    }

    /** Resolve a playable stream for [subjectId]. For movies pass se=0, ep=0;
     *  for episodes the real season+episode. [detailPath] should come from the
     *  search result if available — otherwise a synthetic one is used. */
    suspend fun play(
        subjectId: String,
        season: Int,
        episode: Int,
        detailPath: String,
    ): PlayResult = withContext(Dispatchers.IO) {
        // Prime the proxy with a movie-page hit: this is what mints a
        // resource-capable `token` cookie. Without it, the play call returns
        // hasResource:false. Cheap and safe to repeat (the proxy just resets
        // the cookie). Verified via headless capture — only after the page
        // visit did the play response include streams.
        H5Client.primeProxyForPlay(detailPath)
        val q = buildString {
            append("subjectId=").append(subjectId)
            append("&se=").append(season)
            append("&ep=").append(episode)
            append("&detailPath=").append(urlEncode(detailPath))
        }
        val raw = runCatching { H5Client.proxyGet(PATH_PLAY, q) }.getOrNull()
        val data = raw?.let { runCatching { JSONObject(it).optJSONObject("data") }.getOrNull() }
        val direct = data?.let { parseStreamsList(it) } ?: emptyList()
        if (direct.isNotEmpty()) {
            return@withContext PlayResult(direct, hasResource = data?.optBoolean("hasResource") ?: true)
        }
        // Direct HTTP returned 0 streams (typical: the proxy requires a real
        // browser session to unlock the resource). Drive a headless WebView
        // through themoviebox.org's movie page — its JS minting establishes
        // the session state the proxy needs.
        android.util.Log.i("H5Api", "direct play empty; falling back to WebView resolver")
        val viaWebView = H5PlayResolver.resolve(
            subjectId = subjectId, detailPath = detailPath,
            season = season, episode = episode,
        )
        PlayResult(viaWebView, hasResource = viaWebView.isNotEmpty())
    }

    private fun parseStreamsList(data: JSONObject): List<PlayStream> {
        val arr = data.optJSONArray("streams") ?: JSONArray()
        val out = mutableListOf<PlayStream>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val res = s.optString("resolutions").filter { it.isDigit() }.toIntOrNull() ?: 0
            out.add(
                PlayStream(
                    url = url,
                    resolution = res,
                    format = s.optString("format"),
                    id = s.optString("id"),
                    durationSec = s.optInt("duration"),
                )
            )
        }
        return out
    }

    private fun JSONObject.toItem(): Item {
        val cover = optJSONObject("cover")?.optString("url")
            ?: optString("cover").takeIf { it.isNotBlank() }
        val rawType = optInt("subjectType", 0)
        val type = SubjectType.fromCode(rawType)
        val rating = when (val r = opt("imdbRatingValue")) {
            is Number -> r.toDouble()
            is String -> r.toDoubleOrNull()
            else -> null
        }
        val year = optString("releaseDate").takeIf { it.length >= 4 }
            ?.substring(0, 4)?.toIntOrNull()
        return Item(
            subjectId = optString("subjectId"),
            title = optString("title"),
            type = type,
            year = year,
            rating = rating,
            coverUrl = cover,
            seasonCount = optInt("season", 0),
        )
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
