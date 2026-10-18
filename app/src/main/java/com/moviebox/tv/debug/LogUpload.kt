package com.moviebox.tv.debug

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Puts a log on litterbox.catbox.moe, a temporary file host, and returns its
 * link, so the remote's WhatsApp button sends the whole log as a link.
 *
 * Why a link: the remote is a plain-http page on the home network, and a
 * browser only lets a secure (https) page hand a file to another app. So
 * WhatsApp got the summary text but never the file, and people sent the
 * summary alone (2026-10-18).
 *
 * Only when the viewer presses Share. The log was cleaned when it was
 * stored (AppLog: no links, tokens, cookies, keys or IP addresses), and the
 * host deletes the upload after [KEEP].
 */
object LogUpload {
    private const val ENDPOINT = "https://litterbox.catbox.moe/resources/internals/api.php"

    /** How long the host keeps it: 1h, 12h, 24h or 72h. */
    const val KEEP = "72h"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** The link to [text] saved as [name]; throws when the host refuses. */
    fun upload(name: String, text: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("time", KEEP)
            .addFormDataPart(
                "fileToUpload", name,
                text.toByteArray(Charsets.UTF_8).toRequestBody("text/plain".toMediaType()),
            )
            .build()
        val request = Request.Builder().url(ENDPOINT).post(body).build()
        client.newCall(request).execute().use { r ->
            val answer = r.body?.string().orEmpty().trim()
            if (!r.isSuccessful || !answer.startsWith("https://") || answer.contains(' ')) {
                throw java.io.IOException("HTTP ${r.code}: ${answer.take(80)}")
            }
            return answer
        }
    }
}
