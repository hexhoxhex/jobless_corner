package com.moviebox.tv.data.live

import android.content.Context
import android.util.Log
import com.moviebox.tv.App
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * How a live source wants to be read, as worked out by [SourceTeacher] from
 * the source's own player, kept per host across restarts.
 */
data class Lesson(
    val host: String,
    val learnedAt: Long,
    /** How to get the playlist token, when the player asks for one. */
    val token: TokenRecipe?,
    /** How to open sealed chunk lines, when the playlist has them. */
    val seal: SealRule?,
    /** Origin/Referer the player's playlist requests carried. */
    val headers: Map<String, String>,
)

/** `<urlTemplate with {slug}>` answers JSON; [tokenField] goes on the
 *  playlist request as [param]. */
data class TokenRecipe(
    val urlTemplate: String,
    val tokenField: String,
    val param: String,
    val ttlField: String?,
) {
    fun url(slug: String): String = urlTemplate
        .replace("{slug}", java.net.URLEncoder.encode(slug, "UTF-8"))
        .replace("{now}", System.currentTimeMillis().toString())
}

/**
 * A sealed chunk line is [lineEnc] text of ciphertext under [alg], keyed by
 * the token answer's [keyField] ([keyEnc] text), with the IV/counter made by
 * [ivRule] from the chunk's media sequence number:
 *  - `seq64be:N` / `seq32be:N` / `seq64le:N`: N bytes, zeros then the number
 *  - `zero:N`: N zero bytes
 *  - `prefix:N`: the first N bytes of the line itself
 *  - `field:<name>:<enc>`: another field of the token answer
 */
data class SealRule(
    val alg: String,
    val keyField: String,
    val keyEnc: String,
    val ivRule: String,
    val lineEnc: String,
    /** AES-GCM tag length in bits. */
    val tagBits: Int = 128,
) {
    companion object {
        /** The edge's scheme as of 2026-11-07 (see LiveStreamProxy.unseal). */
        val BUILT_IN = SealRule("AES-CTR", "k", "b64url", "seq64be:16", "b64url")
    }
}

object Seal {
    /** [text] decoded as [enc]; null when it isn't. */
    fun decode(text: String, enc: String): ByteArray? = runCatching {
        when (enc) {
            "b64url" -> android.util.Base64.decode(text, android.util.Base64.URL_SAFE)
            "b64" -> android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            "hex" -> {
                if (text.length % 2 != 0 || !text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    return null
                }
                ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
            else -> null
        }
    }.getOrNull()

    /** The IV/counter [rule] makes for chunk [seq]; null when it needs what
     *  isn't there. Returns the IV and the ciphertext left after it. */
    fun iv(rule: String, seq: Long, data: ByteArray, token: JSONObject?): Pair<ByteArray, ByteArray>? {
        val kind = rule.substringBefore(':')
        val arg = rule.substringAfter(':', "")
        return when (kind) {
            "seq64be", "seq32be", "seq64le", "zero" -> {
                val n = arg.toIntOrNull()?.takeIf { it in 8..16 } ?: return null
                val iv = ByteArray(n)
                when (kind) {
                    "seq64be" -> ByteBuffer.wrap(iv).putLong(n - 8, seq)
                    "seq32be" -> ByteBuffer.wrap(iv).putInt(n - 4, seq.toInt())
                    "seq64le" -> ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putLong(n - 8, seq)
                }
                iv to data
            }
            "prefix" -> {
                val n = arg.toIntOrNull()?.takeIf { it in 8..16 && it < data.size } ?: return null
                data.copyOfRange(0, n) to data.copyOfRange(n, data.size)
            }
            "field" -> {
                val name = arg.substringBefore(':')
                val enc = arg.substringAfter(':', "b64url")
                val v = token?.optString(name)?.takeIf { it.isNotEmpty() } ?: return null
                (decode(v, enc) ?: return null) to data
            }
            else -> null
        }
    }

    /** Raw decrypt; null when it fails. */
    fun decrypt(alg: String, key: ByteArray, iv: ByteArray, data: ByteArray, tagBits: Int = 128): ByteArray? =
        runCatching {
            val spec = javax.crypto.spec.SecretKeySpec(key, "AES")
            val cipher = when (alg) {
                "AES-CTR" -> javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply {
                    init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.IvParameterSpec(iv))
                }
                "AES-CBC" -> javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.IvParameterSpec(iv))
                }
                "AES-GCM" -> javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.GCMParameterSpec(tagBits, iv))
                }
                else -> return null
            }
            cipher.doFinal(data)
        }.getOrNull()

    /** One sealed [line] of chunk [seq] opened with [rule] and the token
     *  answer [token]; null unless it opens into an address. */
    fun open(rule: SealRule, line: String, seq: Long, token: JSONObject): String? {
        val key = token.optString(rule.keyField).takeIf { it.isNotEmpty() }
            ?.let { decode(it, rule.keyEnc) }?.takeIf { it.size in setOf(16, 24, 32) } ?: return null
        val bytes = decode(line, rule.lineEnc) ?: return null
        val (iv, data) = iv(rule.ivRule, seq, bytes, token) ?: return null
        val out = decrypt(rule.alg, key, iv, data, rule.tagBits) ?: return null
        return String(out, Charsets.UTF_8).takeIf { it.startsWith("http") }
    }

    /** A playlist line that looks sealed: no address, no file name. */
    fun looksSealed(line: String): Boolean =
        line.length >= 40 && !line.startsWith("http") && '.' !in line &&
            line.all { it.isLetterOrDigit() || it in "+/=_-" }

    /**
     * Work out how [body]'s sealed lines open, from the token answer [token]
     * that came with it: every string field that decodes to an AES key, each
     * of AES-CTR/CBC/GCM, the usual ways of making the IV/counter from the
     * chunk's sequence number (or another field, or the line's own first
     * bytes), and the three usual text encodings. A few hundred tries on the
     * first line, then every line must open for a rule to count. Null when
     * nothing does.
     */
    fun search(body: String, token: JSONObject): SealRule? {
        var seq = 0L
        val lines = ArrayList<Pair<String, Long>>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                line.substringAfter(':').trim().toLongOrNull()?.let { seq = it }
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            lines += line to seq
            seq++
        }
        val (firstLine, firstSeq) = lines.firstOrNull() ?: return null
        val encs = listOf("b64url", "b64", "hex")
        val keys = ArrayList<Pair<String, String>>()   // field, encoding
        for (f in token.keys()) {
            val v = token.opt(f) as? String ?: continue
            for (e in encs) {
                if (decode(v, e)?.size in setOf(16, 24, 32)) keys += f to e
            }
        }
        if (keys.isEmpty()) return null
        val fieldIvs = token.keys().asSequence().flatMap { f -> encs.asSequence().map { "field:$f:$it" } }.toList()
        for ((keyField, keyEnc) in keys) {
            for (alg in listOf("AES-CTR", "AES-CBC", "AES-GCM")) {
                val n = if (alg == "AES-GCM") 12 else 16
                val ivRules = listOf("seq64be:$n", "seq32be:$n", "seq64le:$n", "zero:$n", "prefix:$n") + fieldIvs
                for (lineEnc in encs) {
                    if (decode(firstLine, lineEnc) == null) continue
                    for (ivRule in ivRules) {
                        val rule = SealRule(alg, keyField, keyEnc, ivRule, lineEnc)
                        if (open(rule, firstLine, firstSeq, token) == null) continue
                        if (lines.all { (l, s) -> open(rule, l, s, token) != null }) return rule
                    }
                }
            }
        }
        return null
    }
}

/** [Lesson]s by host, kept in preferences. */
object Lessons {
    private const val PREFS = "live_lessons"
    private const val TAG = "LiveDiag"
    private val byHost = ConcurrentHashMap<String, Lesson>()
    @Volatile private var loaded = false

    fun forHost(host: String): Lesson? {
        load()
        return byHost[host.lowercase()]
    }

    fun save(lesson: Lesson) {
        load()
        byHost[lesson.host.lowercase()] = lesson
        runCatching {
            prefs().edit().putString(lesson.host.lowercase(), toJson(lesson).toString()).apply()
        }
    }

    fun forget(host: String) {
        load()
        byHost.remove(host.lowercase())
        runCatching { prefs().edit().remove(host.lowercase()).apply() }
        Log.i(TAG, "LEARN forgot what $host taught")
    }

    fun all(): List<Lesson> {
        load()
        return byHost.values.toList()
    }

    fun toJson(l: Lesson): JSONObject = JSONObject().apply {
        put("host", l.host)
        put("learnedAt", l.learnedAt)
        l.token?.let {
            put("token", JSONObject().apply {
                put("url", it.urlTemplate); put("field", it.tokenField); put("param", it.param)
                it.ttlField?.let { f -> put("ttl", f) }
            })
        }
        l.seal?.let {
            put("seal", JSONObject().apply {
                put("alg", it.alg); put("keyField", it.keyField); put("keyEnc", it.keyEnc)
                put("iv", it.ivRule); put("lineEnc", it.lineEnc); put("tagBits", it.tagBits)
            })
        }
        put("headers", JSONObject(l.headers))
    }

    private fun fromJson(o: JSONObject): Lesson? = runCatching {
        val t = o.optJSONObject("token")
        val s = o.optJSONObject("seal")
        val h = o.optJSONObject("headers")
        Lesson(
            host = o.getString("host"),
            learnedAt = o.optLong("learnedAt"),
            token = t?.let {
                TokenRecipe(it.getString("url"), it.getString("field"), it.getString("param"),
                    it.optString("ttl").takeIf { f -> f.isNotEmpty() })
            },
            seal = s?.let {
                SealRule(it.getString("alg"), it.getString("keyField"), it.getString("keyEnc"),
                    it.getString("iv"), it.getString("lineEnc"), it.optInt("tagBits", 128))
            },
            headers = h?.keys()?.asSequence()?.associateWith { k -> h.getString(k) }.orEmpty(),
        )
    }.getOrNull()

    private fun prefs() = App.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                prefs().all.values.forEach { v ->
                    (v as? String)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
                        ?.let { l -> byHost[l.host.lowercase()] = l }
                }
            }
            loaded = true
        }
    }
}
