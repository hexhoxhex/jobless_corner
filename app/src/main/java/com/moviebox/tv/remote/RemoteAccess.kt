package com.moviebox.tv.remote

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-centric access control. Each paired phone gets a stable token; the TV
 * remembers the device's label, role, IP, and last-seen time. The QR URL
 * carries a one-shot pair code so the *first* phone becomes superuser
 * automatically; subsequent phones land in [Role.PENDING] when allow-all is
 * off and need approval.
 */
object RemoteAccess {

    enum class Role { SUPERUSER, USER, PENDING, BLOCKED }

    data class Device(
        val token: String,
        var label: String,
        var role: Role,
        var ip: String,
        var firstSeen: Long,
        var lastSeen: Long,
        var networks: List<String> = emptyList(),     // e.g. ["netflix","hbo"]
        var genres: List<Int> = emptyList(),          // TMDB genre ids
        var denyLanguages: List<String> = emptyList(), // e.g. ["hi","ta"]
    )

    private lateinit var prefs: SharedPreferences
    private val devices = mutableMapOf<String, Device>() // token -> device
    private val rnd = SecureRandom()

    @Volatile
    private var pairCodeCache: String = ""

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("remote_acl_v2", Context.MODE_PRIVATE)
        load()
        if (prefs.getString(KEY_PAIR_CODE, null).isNullOrBlank()) {
            regeneratePairCode()
        } else {
            pairCodeCache = prefs.getString(KEY_PAIR_CODE, "")!!
        }
    }

    var allowAll: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_ALL, true)
        set(v) { prefs.edit().putBoolean(KEY_ALLOW_ALL, v).apply() }

    val pairCode: String get() = pairCodeCache

    fun regeneratePairCode(): String {
        // 4 letters + 2 digits, easy to read.
        val letters = "ABCDEFGHJKMNPQRSTUVWXYZ"
        val digits = "23456789"
        val sb = StringBuilder()
        repeat(4) { sb.append(letters[rnd.nextInt(letters.length)]) }
        repeat(2) { sb.append(digits[rnd.nextInt(digits.length)]) }
        pairCodeCache = sb.toString()
        prefs.edit().putString(KEY_PAIR_CODE, pairCodeCache).apply()
        return pairCodeCache
    }

    /** Has any device been granted superuser yet? */
    fun hasSuperuser(): Boolean =
        synchronized(devices) { devices.values.any { it.role == Role.SUPERUSER } }

    /**
     * Issue a token for a fresh connection.
     * If [code] matches the current pair code, the device is admitted as
     * superuser (when none exists) or user (when one already does), and a new
     * pair code is generated. Otherwise allow-all decides between USER and
     * PENDING.
     */
    fun pair(code: String?, ip: String, label: String): Device {
        val now = System.currentTimeMillis()
        val codeOk = !code.isNullOrBlank() && code.equals(pairCode, ignoreCase = true)
        val role = when {
            codeOk && !hasSuperuser() -> Role.SUPERUSER
            codeOk -> Role.USER
            allowAll -> Role.USER
            else -> Role.PENDING
        }
        val token = newToken()
        val dev = Device(token, label, role, ip, now, now)
        synchronized(devices) { devices[token] = dev }
        if (codeOk) regeneratePairCode()
        save()
        return dev
    }

    /** Look up a device by its token. Updates last-seen. */
    fun touch(token: String?, ip: String?): Device? {
        if (token.isNullOrBlank()) return null
        val dev = synchronized(devices) { devices[token] } ?: return null
        dev.lastSeen = System.currentTimeMillis()
        if (ip != null) dev.ip = ip
        save()
        return dev
    }

    fun canAccess(dev: Device?): Boolean =
        dev != null && dev.role != Role.PENDING && dev.role != Role.BLOCKED

    fun isSuperuser(dev: Device?): Boolean = dev?.role == Role.SUPERUSER

    fun setRole(token: String, role: Role) {
        synchronized(devices) { devices[token]?.role = role }
        save()
    }

    fun setLabel(token: String, label: String) {
        synchronized(devices) { devices[token]?.label = label }
        save()
    }

    fun setPrefs(
        token: String,
        networks: List<String>?, genres: List<Int>?, denyLanguages: List<String>?,
    ) {
        synchronized(devices) {
            val d = devices[token] ?: return
            networks?.let { d.networks = it }
            genres?.let { d.genres = it }
            denyLanguages?.let { d.denyLanguages = it }
        }
        save()
    }

    fun remove(token: String) {
        synchronized(devices) { devices.remove(token) }
        save()
    }

    fun all(): List<Device> = synchronized(devices) {
        devices.values.sortedByDescending { it.lastSeen }.toList()
    }

    fun pendingCount(): Int =
        synchronized(devices) { devices.values.count { it.role == Role.PENDING } }

    // -- persistence -------------------------------------------------------

    private fun load() {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            synchronized(devices) {
                devices.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val token = o.getString("token")
                    devices[token] = Device(
                        token = token,
                        label = o.optString("label", "Phone"),
                        role = runCatching { Role.valueOf(o.getString("role")) }
                            .getOrDefault(Role.USER),
                        ip = o.optString("ip", ""),
                        firstSeen = o.optLong("firstSeen", 0L),
                        lastSeen = o.optLong("lastSeen", 0L),
                        networks = o.optJSONArray("networks")?.toStringList()
                            ?: emptyList(),
                        genres = o.optJSONArray("genres")?.toIntList()
                            ?: emptyList(),
                        denyLanguages = o.optJSONArray("denyLanguages")?.toStringList()
                            ?: emptyList(),
                    )
                }
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(devices) {
            devices.values.forEach { d ->
                arr.put(
                    JSONObject()
                        .put("token", d.token)
                        .put("label", d.label)
                        .put("role", d.role.name)
                        .put("ip", d.ip)
                        .put("firstSeen", d.firstSeen)
                        .put("lastSeen", d.lastSeen)
                        .put("networks", JSONArray(d.networks))
                        .put("genres", JSONArray(d.genres))
                        .put("denyLanguages", JSONArray(d.denyLanguages))
                )
            }
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    private fun newToken(): String {
        val bytes = ByteArray(18)
        rnd.nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
    }

    private const val KEY_ALLOW_ALL = "allowAll"
    private const val KEY_DEVICES = "devices"
    private const val KEY_PAIR_CODE = "pairCode"

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
    private fun JSONArray.toIntList(): List<Int> =
        (0 until length()).map { getInt(it) }
}
