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
        val pruned = prune()
        if (pruned > 0) {
            android.util.Log.i("RemoteAccess", "pruned $pruned stale device record(s)")
            save()
        }
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

    /**
     * Look up a device by its token. Updates last-seen IN MEMORY.
     *
     * This runs on every authenticated request, and it used to call save()
     * each time — re-serialising EVERY stored device to JSON under the lock
     * and rewriting the whole blob to SharedPreferences. The phone remote
     * polls state, debug and reminders every few seconds, so that happened
     * continuously. Measured on the TV: 7,676 stored devices, and 8
     * concurrent remote requests took 2.4-5.0 s though each alone took
     * ~0.5 s — they were queuing on this lock. That is the "web remote
     * becomes unresponsive" report. A fresher lastSeen is not worth a full
     * rewrite; persist it at most once per [TOUCH_SAVE_INTERVAL_MS].
     */
    fun touch(token: String?, ip: String?): Device? {
        if (token.isNullOrBlank()) return null
        val dev = synchronized(devices) { devices[token] } ?: return null
        val now = System.currentTimeMillis()
        dev.lastSeen = now
        if (ip != null) dev.ip = ip
        if (now - lastSaveAtMs > TOUCH_SAVE_INTERVAL_MS) save()
        return dev
    }

    /**
     * The device for requests from this TV itself (adb forward, on-device
     * tooling). Reuses ONE record.
     *
     * The server used to call pair() for every tokenless loopback request,
     * minting and persisting a brand-new SUPERUSER device each time — 7,518
     * "loopback (adb)" records had accumulated, every one of them then
     * serialised on each save().
     */
    fun loopbackDevice(ip: String): Device {
        synchronized(devices) {
            devices.values.firstOrNull { it.label == LOOPBACK_LABEL }?.let { d ->
                d.lastSeen = System.currentTimeMillis()
                d.ip = ip
                if (d.role != Role.SUPERUSER) d.role = Role.SUPERUSER
                return d
            }
        }
        val dev = pair(code = null, ip = ip, label = LOOPBACK_LABEL)
        setRole(dev.token, Role.SUPERUSER)
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

    /**
     * Drop records that can only slow things down, without ever locking out
     * a phone that is actually in use:
     *  - collapse loopback duplicates to the single most recent one;
     *  - drop devices unseen for [STALE_DEVICE_MS] — a phone in use is seen
     *    every few seconds;
     *  - hard cap at [MAX_DEVICES], oldest first.
     * The most recently seen real superuser is always kept. New phones are
     * auto-admitted while allowAll is on, so a pruned token only means a
     * silent re-pair, never lost access.
     */
    private fun prune(): Int {
        val now = System.currentTimeMillis()
        synchronized(devices) {
            val before = devices.size
            devices.values.filter { it.label == LOOPBACK_LABEL }
                .sortedByDescending { it.lastSeen }
                .drop(1)
                .forEach { devices.remove(it.token) }
            val keepSuper = devices.values
                .filter { it.role == Role.SUPERUSER && it.label != LOOPBACK_LABEL }
                .maxByOrNull { it.lastSeen }?.token
            devices.values
                .filter { now - it.lastSeen > STALE_DEVICE_MS && it.token != keepSuper }
                .forEach { devices.remove(it.token) }
            if (devices.size > MAX_DEVICES) {
                devices.values.sortedBy { it.lastSeen }
                    .filter { it.token != keepSuper }
                    .take(devices.size - MAX_DEVICES)
                    .forEach { devices.remove(it.token) }
            }
            return before - devices.size
        }
    }

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
        lastSaveAtMs = System.currentTimeMillis()
    }

    @Volatile private var lastSaveAtMs = 0L

    private fun newToken(): String {
        val bytes = ByteArray(18)
        rnd.nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
    }

    private const val LOOPBACK_LABEL = "loopback (adb)"
    private const val TOUCH_SAVE_INTERVAL_MS = 60_000L
    private const val STALE_DEVICE_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_DEVICES = 50

    private const val KEY_ALLOW_ALL = "allowAll"
    private const val KEY_DEVICES = "devices"
    private const val KEY_PAIR_CODE = "pairCode"

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
    private fun JSONArray.toIntList(): List<Int> =
        (0 until length()).map { getInt(it) }
}
