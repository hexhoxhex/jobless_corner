package com.moviebox.tv.data.live

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One live chunk, fetched in pieces over many connections at once.
 *
 * Every channel's chunks are shared out between two CDN hosts, 1 to 5 of the
 * 6 listed on each (12 channels, 2026-11-03), and on this network one host
 * can't be relied on over a single connection. p16 is a local edge at
 * 2.7-4.8 MB/s; p19 is Fastly, and its speed swings through the day. At its
 * worst on the TV one connection managed 44-130 KB/s: CNN, ITV 1 and Sky Sports
 * Main Event never started in 150 s, each retrying the same chunk every 17 s,
 * because the player loads chunks in order and nothing after a stuck one can
 * play. In a better half hour Sky Sports Main Event (5 MB chunks), one
 * connection against pieces, took turns: first picture 31-40 s against 6-9 s,
 * playing in 69-72% of checks against 100%, typical buffer 6-13 s against 33 s.
 *
 * Asking again finds the same download still going (see [LiveStreamProxy]), so
 * time spent on a chunk isn't thrown away when the player's patience runs out.
 */
class ChunkPieces(
    val channelId: String,
    private val url: String,
    private val client: OkHttpClient,
    private val request: (String) -> Request.Builder,
) {
    /** [from] moves on as bytes arrive, so a request cut off partway only
     *  has to ask for the rest. */
    private class Piece(var from: Int, val to: Int) {
        var tries = 0
    }

    private val startedAt = System.currentTimeMillis()
    private val finished = CountDownLatch(1)
    private val stopped = AtomicBoolean(false)
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val queue = ConcurrentLinkedQueue<Piece>()
    private val left = AtomicInteger(0)
    private val waiting = AtomicInteger(0)
    private val retried = AtomicInteger(0)
    private val slowestMs = AtomicInteger(0)
    @Volatile private var pieces = 0
    @Volatile private var size = 0
    @Volatile private var tookMs = 0L
    @Volatile private var data: ByteArray? = null
    @Volatile private var arrived = false

    /** The host answered with a refusal rather than slowly (a dead token, a
     *  file that has gone); the caller's usual handling of those applies. */
    @Volatile var refused = false
        private set
    @Volatile var contentType = "video/mp2t"
        private set

    fun start() {
        pool.execute { begin() }
    }

    /** True once it has finished, whichever way. Each wait ends in a [take]. */
    fun await(ms: Long): Boolean {
        waiting.incrementAndGet()
        return finished.await(ms, TimeUnit.MILLISECONDS)
    }

    /** The chunk, for one asker. Unwrapping overwrites the bytes, so when the
     *  player has asked twice at once, as it can at a channel start, whoever
     *  isn't last gets a copy. Null if it isn't there (yet). */
    @Synchronized
    fun take(): ByteArray? {
        val bytes = data
        if (waiting.decrementAndGet() > 0) return bytes?.copyOf()
        data = null
        return bytes
    }

    fun cancel() = finish(null)

    /** For the log. */
    fun describe(): String {
        val total = size / 1024
        val done = pieces - left.get()
        return when {
            finished.count != 0L -> "$done of $pieces pieces of $total KB after " +
                "${System.currentTimeMillis() - startedAt}ms"
            !arrived -> "gave up after ${tookMs}ms with $done of $pieces pieces of $total KB" +
                (if (refused) ", refused" else "")
            else -> "$total KB in ${tookMs}ms (${size * 1000L / 1024 / tookMs.coerceAtLeast(1)} KB/s), " +
                "$pieces pieces, slowest ${slowestMs.get()}ms, ${retried.get()} asked again"
        }
    }

    /** The first piece also says how big the chunk is. */
    private fun begin() {
        var tries = 0
        while (!stopped.get() && tries++ < PIECE_TRIES) {
            if (asking(0, pieceBytes - 1) { firstAnswer(it) } == true) return
            retried.incrementAndGet()
        }
        finish(null)
    }

    /** True when that settles it, false to ask again. */
    private fun firstAnswer(r: Response): Boolean {
        if (!r.isSuccessful) {
            refused = true
            finish(null)
            return true
        }
        r.header("Content-Type")?.let { contentType = it }
        val body = r.body ?: return false
        val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        if (r.code != 206 || total == null) {
            // No pieces from this host: what comes on this connection is all
            // there is, within the client's limit on one request.
            val whole = body.bytes()
            size = whole.size
            finish(whole)
            return true
        }
        if (total > MAX_CHUNK_BYTES) {
            refused = true
            finish(null)
            return true
        }
        val out = ByteArray(total.toInt())
        size = out.size
        val first = minOf(pieceBytes, out.size)
        val head = Piece(0, first - 1)
        var from = first
        while (from < out.size) {
            val to = minOf(from + pieceBytes, out.size)
            queue += Piece(from, to - 1)
            from = to
        }
        pieces = queue.size + 1
        left.set(pieces)
        // The headers gave the size, so the others start now. Waiting for
        // this piece first meant that on a very slow host it hit its time
        // limit over and over, and nothing else ever started.
        repeat(minOf((connections - 1).coerceAtLeast(1), queue.size)) {
            pool.execute { worker(out) }
        }
        val whole = try {
            read(body.byteStream(), out, head)
        } catch (e: java.io.IOException) {
            false
        }
        if (whole) {
            if (left.decrementAndGet() == 0) finish(out)
        } else if (!stopped.get()) {
            retried.incrementAndGet()
            queue += head
            pool.execute { worker(out) }
        }
        return true
    }

    private fun worker(out: ByteArray) {
        while (!stopped.get()) {
            val p = queue.poll() ?: return
            val t0 = System.currentTimeMillis()
            val at = p.from
            val ok = asking(p.from, p.to) { r ->
                val body = r.body
                r.code == 206 && body != null && read(body.byteStream(), out, p)
            } == true
            if (ok) {
                val ms = (System.currentTimeMillis() - t0).toInt()
                slowestMs.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
                if (left.decrementAndGet() == 0) finish(out)
            } else if (p.from == at && ++p.tries >= PIECE_TRIES) {
                // Nothing at all, time after time. Slow but moving goes on.
                finish(null)
            } else {
                retried.incrementAndGet()
                queue += p
            }
        }
    }

    /** One request for bytes [from]..[to]. Null if it failed on the way. */
    private inline fun <T> asking(from: Int, to: Int, read: (Response) -> T): T? {
        val call = client.newCall(request(url).header("Range", "bytes=$from-$to").build())
        calls += call
        return try {
            if (stopped.get()) null else call.execute().use(read)
        } catch (e: java.io.IOException) {
            null
        } finally {
            calls -= call
        }
    }

    /** The rest of [p] into [into]. False if the answer ended early. */
    private fun read(input: InputStream, into: ByteArray, p: Piece): Boolean {
        while (p.from <= p.to) {
            val n = input.read(into, p.from, p.to - p.from + 1)
            if (n < 0) return false
            p.from += n
        }
        return true
    }

    private fun finish(bytes: ByteArray?) {
        if (!stopped.compareAndSet(false, true)) return
        tookMs = System.currentTimeMillis() - startedAt
        arrived = bytes != null
        synchronized(this) { data = bytes }
        finished.countDown()
        if (bytes == null) calls.forEach { runCatching { it.cancel() } }
    }

    companion object {
        /** Test hook (/api/debug/pieces); 0 connections turns pieces off. On
         *  the TV 16 to 32 connections and 64 to 128 KB pieces came out about
         *  the same; 256 KB pieces were worse (buffer down to 2 s). */
        @Volatile var connections = 24
        @Volatile var pieceBytes = 128 * 1024

        private const val PIECE_TRIES = 4
        /** A sane chunk is a few MB; see DisguisedSegment. */
        private const val MAX_CHUNK_BYTES = 32L * 1024 * 1024

        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "chunk-pieces").apply { isDaemon = true }
        }
    }
}
