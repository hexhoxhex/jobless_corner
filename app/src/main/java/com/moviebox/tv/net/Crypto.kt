package com.moviebox.tv.net

import java.security.MessageDigest
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Request signing for the aoneroom mobile API.
 *
 * This is a faithful, byte-for-byte port of the Python `moviebox_api.v3.crypto`
 * module. Validated against the reference implementation:
 *   token  -> "<ts>,<md5(reverse(ts))>"
 *   x-tr   -> "<ts>|2|<base64(hmac-md5(canonical, key))>"
 */
object Crypto {

    private const val SIGNATURE_BODY_MAX_BYTES = 102_400

    fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun b64Decode(value: String): ByteArray {
        val pad = (4 - value.length % 4) % 4
        return java.util.Base64.getDecoder().decode(value + "=".repeat(pad))
    }

    private fun b64Encode(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data)

    /** token = "<ts>,<md5(reverse(<ts>))>" */
    fun clientToken(timestampMs: Long): String {
        val ts = timestampMs.toString()
        val reversed = ts.reversed()
        return "$ts,${md5Hex(reversed.toByteArray(Charsets.UTF_8))}"
    }

    /** Rebuild the query string with keys sorted; values are NOT re-encoded. */
    private fun sortedQuery(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return ""
        val query = url.substring(q + 1)
        if (query.isEmpty()) return ""
        val map = TreeMap<String, MutableList<String>>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            map.getOrPut(key) { mutableListOf() }.add(value)
        }
        return buildString {
            for ((key, values) in map) for (v in values) {
                if (isNotEmpty()) append('&')
                append(key).append('=').append(v)
            }
        }
    }

    private fun path(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val slash = url.indexOf('/', start)
        if (slash < 0) return ""
        val q = url.indexOf('?', slash)
        return if (q < 0) url.substring(slash) else url.substring(slash, q)
    }

    private fun canonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestampMs: Long,
    ): String {
        val p = path(url)
        val query = sortedQuery(url)
        val canonicalUrl = if (query.isNotEmpty()) "$p?$query" else p

        var bodyHash = ""
        var bodyLength = ""
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val truncated =
                if (bytes.size > SIGNATURE_BODY_MAX_BYTES)
                    bytes.copyOf(SIGNATURE_BODY_MAX_BYTES)
                else bytes
            bodyHash = md5Hex(truncated)
            bodyLength = bytes.size.toString()
        }

        return buildString {
            append(method.uppercase()).append('\n')
            append(accept ?: "").append('\n')
            append(contentType ?: "").append('\n')
            append(bodyLength).append('\n')
            append(timestampMs).append('\n')
            append(bodyHash).append('\n')
            append(canonicalUrl)
        }
    }

    /** Returns the `x-tr-signature` header value. */
    fun trSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestampMs: Long,
        secretBase64: String = Constants.SECRET_KEY_DEFAULT,
    ): String {
        val canonical =
            canonicalString(method, accept, contentType, url, body, timestampMs)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(b64Decode(secretBase64), "HmacMD5"))
        val sig = b64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
        return "$timestampMs|2|$sig"
    }
}
