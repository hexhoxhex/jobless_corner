package com.moviebox.tv.net

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the bearer token the API hands back via the `x-user` response header,
 * mirroring the Python client's transparent token refresh.
 */
object TokenStore {
    private val token = AtomicReference<String?>(null)

    fun current(): String? = token.get()

    fun absorbXUser(xUserHeader: String?) {
        if (xUserHeader.isNullOrBlank()) return
        runCatching {
            val obj = org.json.JSONObject(xUserHeader)
            val value = obj.optString("token", "")
            if (value.isNotBlank()) token.set(value)
        }
    }
}
