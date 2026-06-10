package com.moviebox.tv.net

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Single interceptor that:
 *  - signs every request (X-Client-Token, x-tr-signature, X-Client-Info),
 *  - injects the runtime bearer token,
 *  - absorbs refreshed tokens from `x-user`,
 *  - falls back across the host pool on retryable status codes / IO errors.
 */
class ApiInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val method = original.method

        val accept = "application/json"
        // Sign with the EXACT content-type that goes on the wire. OkHttp's
        // BridgeInterceptor overwrites our header with the body's media type
        // (Moshi -> "application/json; charset=UTF-8"), so the signature must
        // use that same string or the server rejects it.
        val body = original.body
        val contentType = body?.contentType()?.toString() ?: "application/json"

        val bodyString: String? = body?.let {
            val buffer = Buffer()
            it.writeTo(buffer)
            buffer.readUtf8()
        }

        var lastResponse: Response? = null
        var lastError: Exception? = null

        for (base in Constants.HOST_POOL) {
            val rewritten = original.url.newBuilder()
                .scheme("https")
                .host(base.removePrefix("https://"))
                .build()

            val ts = System.currentTimeMillis()
            val builder = original.newBuilder()
                .url(rewritten)
                .header("User-Agent", Constants.userAgent)
                .header("Accept", accept)
                .header("Content-Type", contentType)
                .header("Connection", "keep-alive")
                .header("X-Client-Token", Crypto.clientToken(ts))
                .header(
                    "x-tr-signature",
                    Crypto.trSignature(
                        method = method,
                        accept = accept,
                        contentType = contentType,
                        url = rewritten.toString(),
                        body = bodyString,
                        timestampMs = ts,
                    ),
                )
                .header("X-Client-Info", Constants.clientInfo)
                .header("X-Client-Status", "0")

            TokenStore.current()?.let {
                builder.header("Authorization", "Bearer $it")
            }

            try {
                val response = chain.proceed(builder.build())
                TokenStore.absorbXUser(response.header("x-user"))

                if (response.code !in Constants.RETRY_STATUS_CODES) {
                    return response
                }
                lastResponse?.close()
                lastResponse = response
            } catch (e: Exception) {
                lastError = e
            }
        }

        lastResponse?.let { return it }
        throw lastError ?: java.io.IOException("All API hosts exhausted")
    }
}
