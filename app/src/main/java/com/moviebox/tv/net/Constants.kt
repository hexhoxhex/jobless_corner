package com.moviebox.tv.net

import kotlin.random.Random

/** Ported from `moviebox_api.v3.constants` / `v1.constants`. */
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

    val RETRY_STATUS_CODES = setOf(403, 407, 429, 500, 502, 503, 504)

    // Endpoint paths
    const val MAIN_PAGE_PATH = "/wefeed-mobile-bff/tab-operating"
    const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search"
    const val SUBJECT_GET_PATH = "/wefeed-mobile-bff/subject-api/get"
    const val SEASON_INFO_PATH = "/wefeed-mobile-bff/subject-api/season-info"
    const val RESOURCE_PATH = "/wefeed-mobile-bff/subject-api/resource"
    const val EXT_CAPTIONS_PATH =
        "/wefeed-mobile-bff/subject-api/get-ext-captions"

    /**
     * Headers the CDN requires for media + subtitle playback. ExoPlayer injects
     * these on its HTTP data source, which is why no proxy is needed.
     */
    // Signed mp4 URLs from the H5 play proxy are issued for themoviebox.org —
    // bcdnxw.hakunaymatata.com rejects requests without this Referer. Matches
    // the exact header set captured from a working browser play.
    const val MEDIA_REFERER = "https://themoviebox.org/"
    const val MEDIA_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    val mediaHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to MEDIA_USER_AGENT,
            "Referer" to MEDIA_REFERER,
            "Origin" to "https://themoviebox.org",
            "Accept" to "*/*",
        )

    // Randomised per-process Android client fingerprint (mirrors the Python
    // _generate_client_info()).
    val userAgent: String
    val clientInfo: String

    init {
        val androidVersions = listOf(
            "9" to "PQ3A.190605.03081104",
            "10" to "QP1A.191005.007.A3",
            "11" to "RP1A.200720.011",
            "12" to "S1B.220414.015",
            "13" to "TQ2A.230405.003",
        )
        val devices = listOf(
            "23078RKD5C", "2201117TY", "2201117TG", "22101316G",
            "21121210G", "M2012K11AG", "M2007J20CG",
        )
        val versionCodes = listOf(50020042, 50020043, 50020044, 50020045, 50020046)
        val networks = listOf("NETWORK_WIFI", "NETWORK_MOBILE")
        val timezones = listOf(
            "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo",
            "America/New_York", "Europe/London",
        )

        val (osVer, build) = androidVersions.random()
        val model = devices.random()
        val versionCode = versionCodes.random()
        val net = networks.random()
        val timezone = timezones.random()
        val deviceId = randomHex(32)
        val gaid = java.util.UUID.randomUUID().toString()

        userAgent = "com.community.oneroom/$versionCode " +
            "(Linux; U; Android $osVer; en_US; $model; Build/$build; " +
            "Cronet/135.0.7012.3)"

        clientInfo = """{"package_name":"com.community.oneroom",""" +
            """"version_name":"3.0.03.0529.03","version_code":$versionCode,""" +
            """"os":"android","os_version":"$osVer","install_ch":"ps",""" +
            """"device_id":"$deviceId","install_store":"ps","gaid":"$gaid",""" +
            """"brand":"Redmi","model":"$model","system_language":"en",""" +
            """"net":"$net","region":"US","timezone":"$timezone",""" +
            """"sp_code":"40401","X-Play-Mode":"2"}"""
    }

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return buildString { repeat(length) { append(chars[Random.nextInt(16)]) } }
    }
}
