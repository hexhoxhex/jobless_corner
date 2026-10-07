package com.moviebox.tv.data.live

import java.util.zip.Inflater

/**
 * Recovers MPEG-TS that a CDN has disguised as an image.
 *
 * Found 2026-10-06, and the reason live TV stopped working: once the resolver
 * reached the working route again (/stream -> dembed.top ->
 * edge.cowedd4855ws.sbs), every segment arrived as a PNG from TikTok's image
 * CDN — `p19-common-sign.tiktokcdn-us.com/...~tplv-tiktokx-origin.image`,
 * `Content-Type: image/png`, a valid PNG signature at byte 0. ExoPlayer looks
 * for the TS sync byte near the start, finds an image, and fails the whole
 * stream: "ERROR_CODE_PARSING_CONTAINER_MALFORMED — Cannot find sync byte".
 *
 * Two shapes exist, and this handles both:
 *
 *  1. **TS appended after a fake image header** — a tiny valid PNG, then the
 *     raw stream (the Icefy VOD provider's 120-byte variant). A sync scan over
 *     the raw bytes recovers it.
 *  2. **TS carried AS the image's pixel data** — the case that broke live TV.
 *     A 512-pixel-wide RGB image, zlib-compressed across 8 KB IDAT chunks,
 *     every row carrying a PNG filter (all five types appear). Undo the PNG
 *     exactly — inflate, then reconstruct each row — and the pixel bytes are
 *     `TIKTIKPX`, a 4-byte length, and a GZIP member whose payload is the TS.
 *     Full chain: PNG -> inflate -> unfilter -> gunzip -> MPEG-TS. Measured on
 *     a USA Network segment: 10,272 contiguous packets with nothing left over,
 *     every frame decoding cleanly in ffmpeg as H.264 1280x720 at 29.97 fps
 *     plus AAC 48 kHz stereo.
 *
 * Everything is done in place on two buffers (the downloaded file and its
 * inflated pixels), because this runs on a TV with little memory to spare.
 */
object DisguisedSegment {

    private const val SYNC: Byte = 0x47
    private const val PACKET = 188

    /** Fewer clean packets than this is not a stream, it's a coincidence. */
    private const val MIN_PACKETS = 20

    /** A sane segment is a few MB; refuse to inflate anything absurd. */
    private const val MAX_PIXEL_BYTES = 48L * 1024 * 1024

    private val PNG_SIG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isPng(head: ByteArray, n: Int): Boolean =
        n >= PNG_SIG.size && PNG_SIG.indices.all { head[it] == PNG_SIG[it] }

    /**
     * Clean MPEG-TS from a disguised segment, as (buffer, length) — the bytes
     * are `buffer[0 until length]`. Null when nothing stream-like was found,
     * in which case the caller should pass the original through untouched.
     * May overwrite [file].
     */
    fun unwrap(file: ByteArray): Pair<ByteArray, Int>? {
        // Shape 1: the stream sits in the raw bytes, after a fake header.
        val rawTs = countTs(file, file.size)
        if (rawTs >= file.size * 9 / 10) {
            return file to resyncInPlace(file, file.size)
        }
        // Shape 2: the stream is the pixel data.
        val pixels = runCatching { decodePixels(file) }.getOrNull()
        if (pixels != null) {
            val (buf, len) = pixels
            // The pixels are not the stream yet: they are "TIKTIKPX", a
            // 4-byte length, then a GZIP member holding the TS in stored
            // (uncompressed) deflate blocks. That is why the raw pixels LOOK
            // almost like TS — with gzip's 5-byte block header landing every
            // 65,540 bytes, usually in the middle of a packet. Resyncing
            // around those headers keeps the cadence but quietly corrupts one
            // packet per block: on the TV that was macroblocking across the
            // lower third of the picture, and ffmpeg reported H.264 and AAC
            // decode errors. Gunzipping gives the exact original: 10,272
            // packets, contiguous, every frame decoding cleanly.
            runCatching { gunzipFrom(buf, len) }.getOrNull()?.let { (ts, tsLen) ->
                // A no-op on a clean stream; trims any trailing partial packet.
                val clean = resyncInPlace(ts, tsLen)
                if (clean >= MIN_PACKETS * PACKET) return ts to clean
            }
            // No gzip layer: fall back to the best effort.
            val tsLen = resyncInPlace(buf, len)
            if (tsLen >= MIN_PACKETS * PACKET && tsLen >= rawTs) return buf to tsLen
        }
        if (rawTs >= MIN_PACKETS * PACKET) return file to resyncInPlace(file, file.size)
        return null
    }

    // ---------------------------------------------------------- MPEG-TS --

    private fun runStartsAt(b: ByteArray, o: Int, len: Int): Boolean =
        o + PACKET * 2 < len &&
            b[o] == SYNC && b[o + PACKET] == SYNC && b[o + PACKET * 2] == SYNC

    /** How many bytes of [b] are aligned TS packets. Read-only. */
    private fun countTs(b: ByteArray, len: Int): Int {
        var o = 0
        var total = 0
        while (o < len) {
            if (runStartsAt(b, o, len)) {
                while (o + PACKET <= len && b[o] == SYNC) {
                    total += PACKET
                    o += PACKET
                }
            } else {
                o++
            }
        }
        return total
    }

    /** Keep only aligned packets, compacted to the front of [b]. Safe in
     *  place: the write position never passes the read position. */
    private fun resyncInPlace(b: ByteArray, len: Int): Int {
        var r = 0
        var w = 0
        while (r < len) {
            if (runStartsAt(b, r, len)) {
                while (r + PACKET <= len && b[r] == SYNC) {
                    if (w != r) System.arraycopy(b, r, b, w, PACKET)
                    w += PACKET
                    r += PACKET
                }
            } else {
                r++
            }
        }
        return w
    }

    // -------------------------------------------------------------- PNG --

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    /** The image's reconstructed pixel bytes, as (buffer, length). */
    private fun decodePixels(png: ByteArray): Pair<ByteArray, Int>? {
        var width = 0
        var height = 0
        var depth = 0
        var colour = 0
        // First pass: header, so the output can be sized once.
        var i = 8
        while (i + 8 <= png.size) {
            val len = u32(png, i)
            if (len < 0 || i + 12 + len > png.size) break
            if (png[i + 4] == 'I'.code.toByte() && png[i + 5] == 'H'.code.toByte()) {
                width = u32(png, i + 8)
                height = u32(png, i + 12)
                depth = png[i + 16].toInt() and 0xFF
                colour = png[i + 17].toInt() and 0xFF
                break
            }
            i += 12 + len
        }
        if (width <= 0 || height <= 0 || depth != 8) return null
        val bpp = when (colour) {
            0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4
            else -> return null
        }
        val row = width * bpp
        val stride = row + 1                       // + the filter-type byte
        val expected = stride.toLong() * height
        if (expected > MAX_PIXEL_BYTES) return null
        val raw = ByteArray(expected.toInt())

        // Second pass: inflate the IDAT chunks straight from the file, no
        // intermediate copy of the compressed data.
        val inflater = Inflater()
        var got = 0
        try {
            i = 8
            chunks@ while (i + 8 <= png.size && got < raw.size) {
                val len = u32(png, i)
                if (len < 0 || i + 12 + len > png.size) break
                val t0 = png[i + 4]; val t1 = png[i + 5]; val t2 = png[i + 6]; val t3 = png[i + 7]
                val isIdat = t0 == 'I'.code.toByte() && t1 == 'D'.code.toByte() &&
                    t2 == 'A'.code.toByte() && t3 == 'T'.code.toByte()
                val isIend = t0 == 'I'.code.toByte() && t1 == 'E'.code.toByte()
                if (isIend) break@chunks
                if (isIdat && len > 0) {
                    inflater.setInput(png, i + 8, len)
                    while (!inflater.needsInput() && got < raw.size) {
                        val n = inflater.inflate(raw, got, raw.size - got)
                        if (n == 0 && (inflater.finished() || inflater.needsDictionary())) {
                            break@chunks
                        }
                        got += n
                    }
                }
                i += 12 + len
            }
        } finally {
            inflater.end()
        }
        val rows = got / stride
        if (rows == 0) return null

        // Reconstruct each row in place (PNG filters 0-4), then shift it left
        // over the filter bytes so the pixels end up contiguous. Row r's
        // reconstructed bytes land at r*row, which never overtakes where row
        // r+1 is still being read from (r*stride + 1 onward).
        for (r in 0 until rows) {
            val filter = raw[r * stride].toInt() and 0xFF
            val src = r * stride + 1
            val dst = r * row
            val prev = dst - row                   // previous row, already shifted
            for (x in 0 until row) {
                val v = raw[src + x].toInt() and 0xFF
                val a = if (x >= bpp) raw[dst + x - bpp].toInt() and 0xFF else 0
                val b = if (r > 0) raw[prev + x].toInt() and 0xFF else 0
                val c = if (r > 0 && x >= bpp) raw[prev + x - bpp].toInt() and 0xFF else 0
                val out = when (filter) {
                    0 -> v
                    1 -> v + a
                    2 -> v + b
                    3 -> v + ((a + b) ushr 1)
                    4 -> v + paeth(a, b, c)
                    else -> return null
                }
                raw[dst + x] = out.toByte()
            }
        }
        return raw to rows * row
    }

    // ------------------------------------------------------------- GZIP --

    /** Gunzip the first gzip member found near the start of [buf] (it sits
     *  after a short marker), as (buffer, length). Null if there is none. */
    private fun gunzipFrom(buf: ByteArray, len: Int): Pair<ByteArray, Int>? {
        val start = (0 until minOf(64, len - 10)).firstOrNull {
            buf[it] == 0x1f.toByte() && buf[it + 1] == 0x8b.toByte() && buf[it + 2] == 0x08.toByte()
        } ?: return null
        // RFC 1952 header: 10 fixed bytes, then optional fields per FLG.
        val flg = buf[start + 3].toInt() and 0xFF
        var p = start + 10
        if (flg and 0x04 != 0) {                              // FEXTRA
            val xlen = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
            p += 2 + xlen
        }
        if (flg and 0x08 != 0) { while (p < len && buf[p] != 0.toByte()) p++; p++ }  // FNAME
        if (flg and 0x10 != 0) { while (p < len && buf[p] != 0.toByte()) p++; p++ }  // FCOMMENT
        if (flg and 0x02 != 0) p += 2                          // FHCRC
        if (p >= len) return null

        val inflater = Inflater(true)                          // raw deflate
        var out = ByteArray(len)                               // stored blocks never grow
        var got = 0
        try {
            inflater.setInput(buf, p, len - p)
            while (!inflater.finished()) {
                if (got == out.size) out = out.copyOf(out.size + out.size / 2)
                val n = inflater.inflate(out, got, out.size - got)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                got += n
            }
        } finally {
            inflater.end()
        }
        return if (got > 0) out to got else null
    }

    /**
     * Lowest and highest PES presentation timestamp (90 kHz ticks) in the
     * TS bytes, across all streams — for logging whether consecutive
     * segments join up. Null when no PTS is found. Read-only, one pass.
     */
    fun ptsRange(b: ByteArray, len: Int): Pair<Long, Long>? {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        var o = 0
        while (o + PACKET <= len) {
            if (b[o] != SYNC) { o++; continue }
            val pusi = (b[o + 1].toInt() and 0x40) != 0
            val afc = (b[o + 3].toInt() ushr 4) and 0x3
            var p = o + 4
            if (afc == 2 || afc == 3) p += 1 + (b[o + 4].toInt() and 0xFF)
            if (pusi && (afc == 1 || afc == 3) && p + 14 <= o + PACKET &&
                b[p] == 0.toByte() && b[p + 1] == 0.toByte() && b[p + 2] == 1.toByte()
            ) {
                val flags = b[p + 7].toInt() and 0xFF
                if (flags and 0x80 != 0) {
                    val q = p + 9
                    val pts = ((b[q].toLong() and 0x0E) shl 29) or
                        ((b[q + 1].toLong() and 0xFF) shl 22) or
                        ((b[q + 2].toLong() and 0xFE) shl 14) or
                        ((b[q + 3].toLong() and 0xFF) shl 7) or
                        ((b[q + 4].toLong() and 0xFE) ushr 1)
                    if (pts < lo) lo = pts
                    if (pts > hi) hi = pts
                }
            }
            o += PACKET
        }
        return if (lo == Long.MAX_VALUE) null else lo to hi
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
