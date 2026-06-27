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
            runCatching { items.getJSONObject(i).toItem() }.getOrNull()
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
        val q = buildString {
            append("subjectId=").append(subjectId)
            append("&se=").append(season)
            append("&ep=").append(episode)
            append("&detailPath=").append(urlEncode(detailPath))
        }
        val raw = H5Client.proxyGet(PATH_PLAY, q)
        val root = JSONObject(raw)
        val data = root.optJSONObject("data")
            ?: return@withContext PlayResult(emptyList(), hasResource = false)
        val streamsJson = data.optJSONArray("streams") ?: JSONArray()
        val out = mutableListOf<PlayStream>()
        for (i in 0 until streamsJson.length()) {
            val s = streamsJson.getJSONObject(i)
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
        PlayResult(out, hasResource = data.optBoolean("hasResource"))
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
