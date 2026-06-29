package com.moviebox.shared.net

import kotlin.random.Random

/**
 * Constants shared between :app and :desktop. Ported from
 * `moviebox_api.v3.constants` / `v1.constants`. Single source of
 * truth — a patch here lands in both Android + Desktop automatically.
 */
object Constants {

    const val SECRET_KEY_DEFAULT = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    const val SECRET_KEY_ALT = "Xqn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA"

    /** API host pool; tried in order with fallback on retryable status codes. */
    val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com",
    )

    /** H5 origin used by themoviebox.org's web SPA. */
    const val H5_BASE = "https://h5-api.aoneroom.com"
    const val PROXY_BASE = "https://themoviebox.org"
    const val PAGE_REFERER = "https://themoviebox.org/"

    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    /** Nairobi timezone — same value that returned the highest-tier
     *  anonymous bearer (atp:3) during reverse engineering. Used in
     *  the X-Client-Info header on every H5 call. */
    const val X_CLIENT_INFO = """{"timezone":"Africa/Nairobi"}"""
}
