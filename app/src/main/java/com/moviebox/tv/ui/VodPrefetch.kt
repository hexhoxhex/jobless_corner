package com.moviebox.tv.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Downloads the next few chunks of a VOD HLS stream side by side while the
 * player is still on the current one.
 *
 * Measured 2026-10-25 on 7Movies' Vela CDN (edge.reelvault.click, behind
 * Cloudflare; Family Guy S13E13, one 1080p rendition, 1.8 Mbit/s average and
 * 3.4 at peaks): a chunk someone fetched lately comes at 5-25 Mbit/s, but a
 * cold one is pulled from origin at 0.5-0.8 Mbit/s per request, on a kept-alive
 * connection or a fresh one alike. The player loads one chunk at a time, so on
 * the TV it got ~1 Mbit/s and stopped to buffer for 3-7 s every 10-20 s
 * (47 rebuffers in 27 minutes). The cap is per request, not per address: from
 * the same network, 4 cold chunks at once came at 1.73 Mbit/s together and
 * 6 at once at 3.08 Mbit/s (2.0x real time).
 *
 * So when the player opens chunk i, chunks i..i+[AHEAD] are all downloading,
 * and the player reads chunk i from here as its bytes arrive. Only streams
 * PlayerScreen [start]s are touched; every other request goes straight
 * through.
 */
internal object VodPrefetch {
    private const val TAG = "VodPrefetch"

    /** Chunks fetched beyond the one playing: 6 requests at once. */
    private const val AHEAD = 5

    /** No new bytes for this long and the reader gives up on a chunk (the
     *  fetch's own read timeout is 20 s; this is the backstop). */
    private const val STALL_MS = 30_000L

    /** One chunk's bytes, readable while they are still arriving. */
    class Body(val url: String) {
        private val lock = Object()
        private var buf = ByteArray(0)
        private var size = 0
        private var opened = false
        private var done = false
        private var error: IOException? = null
        @Volatile var cancelled = false
            private set
        /** Content length from the response, or [C.LENGTH_UNSET]. */
        var length: Long = C.LENGTH_UNSET.toLong()
            private set

        fun opened(contentLength: Long) = synchronized(lock) {
            length = contentLength
            if (contentLength in 1..MAX_PREALLOC) buf = ByteArray(contentLength.toInt())
            opened = true
            lock.notifyAll()
        }

        fun append(src: ByteArray, n: Int) = synchronized(lock) {
            if (size + n > buf.size) buf = buf.copyOf(maxOf(size + n, buf.size * 2, 256 * 1024))
            System.arraycopy(src, 0, buf, size, n)
            size += n
            lock.notifyAll()
        }

        fun finish() = synchronized(lock) { done = true; lock.notifyAll() }

        fun fail(e: IOException) = synchronized(lock) {
            if (error == null) error = e
            lock.notifyAll()
        }

        fun cancel() {
            cancelled = true
            fail(IOException("prefetch cancelled"))
        }

        /** Waits for the response to start. False if the fetch failed first. */
        fun awaitOpen(): Boolean = synchronized(lock) {
            val until = System.currentTimeMillis() + STALL_MS
            while (!opened && error == null) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            opened && error == null
        }

        /** Copies bytes from [pos], waiting for them if they haven't come yet. */
        fun read(pos: Int, dst: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
            val until = System.currentTimeMillis() + STALL_MS
            while (pos >= size) {
                error?.let { throw it }
                if (done) return C.RESULT_END_OF_INPUT
                val left = until - System.currentTimeMillis()
                if (left <= 0) throw IOException("chunk stalled for ${STALL_MS / 1000} s")
                lock.wait(left)
            }
            val n = minOf(len, size - pos)
            System.arraycopy(buf, pos, dst, off, n)
            n
        }

        private companion object {
            const val MAX_PREALLOC = 32L * 1024 * 1024
        }
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "vod-prefetch").apply { isDaemon = true }
    }

    private var urls: List<String> = emptyList()
    @Volatile private var index: Map<String, Int> = emptyMap()
    private val bodies = HashMap<Int, Body>()

    /** Prefetch this stream's chunks (their URLs in playlist order).
     *  Replaces whatever stream was registered before. */
    @Synchronized
    fun start(segments: List<String>) {
        if (segments == urls) return
        clearLocked()
        urls = segments
        index = segments.withIndex().associate { (i, u) -> u to i }
        android.util.Log.i(TAG, "prefetching ${segments.size} chunks, $AHEAD ahead")
    }

    @Synchronized
    fun stop() {
        if (urls.isEmpty()) return
        clearLocked()
        android.util.Log.i(TAG, "stopped")
    }

    private fun clearLocked() {
        bodies.values.forEach { it.cancel() }
        bodies.clear()
        urls = emptyList()
        index = emptyMap()
    }

    /** The player is opening [url]: moves the window there and returns that
     *  chunk's body, or null when the URL isn't a chunk of the stream.
     *  Chunks are fetched with [source] — the player's own HTTP stack, so the
     *  CDN sees the same headers. */
    fun opening(url: String, source: () -> HttpDataSource): Body? {
        val i = index[url] ?: return null
        synchronized(this) {
            if (index[url] != i) return null
            val last = minOf(i + AHEAD, urls.lastIndex)
            bodies.entries.removeAll { (k, b) ->
                (k < i || k > last || b.cancelled).also { drop -> if (drop) b.cancel() }
            }
            for (k in i..last) bodies.getOrPut(k) { fetch(urls[k], source) }
            return bodies[i]
        }
    }

    /** A body that failed: the next open of it goes to the network. */
    @Synchronized
    fun forget(body: Body) {
        bodies.entries.removeAll { it.value === body }
    }

    private fun fetch(url: String, source: () -> HttpDataSource): Body {
        val body = Body(url)
        pool.execute {
            if (body.cancelled) return@execute
            val ds = source()
            try {
                body.opened(ds.open(DataSpec(Uri.parse(url))))
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    if (body.cancelled) return@execute
                    val n = ds.read(chunk, 0, chunk.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    body.append(chunk, n)
                }
                body.finish()
            } catch (e: IOException) {
                if (!body.cancelled) android.util.Log.w(TAG, "chunk failed: ${e.message}")
                body.fail(e)
            } finally {
                try { ds.close() } catch (_: IOException) {}
            }
        }
        return body
    }
}

/**
 * The player's HTTP source for VOD: a chunk [VodPrefetch] holds is read from
 * there, everything else (and any chunk whose prefetch failed) goes to
 * [upstream] unchanged. [fresh] makes the sources the prefetches use.
 */
internal class PrefetchingDataSource(
    private val upstream: HttpDataSource,
    private val fresh: () -> HttpDataSource,
) : HttpDataSource {
    private val listeners = ArrayList<TransferListener>()
    private var body: VodPrefetch.Body? = null
    private var spec: DataSpec? = null
    private var pos = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val whole = dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong() &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET
        val b = VodPrefetch.opening(dataSpec.uri.toString(), fresh)?.takeIf { whole }
        if (b == null || !b.awaitOpen()) {
            b?.let(VodPrefetch::forget)
            return upstream.open(dataSpec)
        }
        body = b
        spec = dataSpec
        pos = 0
        listeners.forEach { it.onTransferInitializing(this, dataSpec, true) }
        listeners.forEach { it.onTransferStart(this, dataSpec, true) }
        return b.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val b = body ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val n = try {
            b.read(pos, buffer, offset, length)
        } catch (e: IOException) {
            VodPrefetch.forget(b)
            throw HttpDataSource.HttpDataSourceException(
                e, spec!!, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
        if (n > 0) {
            pos += n
            listeners.forEach { it.onBytesTransferred(this, spec!!, true, n) }
        }
        return n
    }

    override fun getUri(): Uri? = if (body != null) spec?.uri else upstream.uri

    override fun close() {
        val s = spec
        if (body != null && s != null) listeners.forEach { it.onTransferEnd(this, s, true) }
        body = null
        spec = null
        upstream.close()
    }

    override fun getResponseCode(): Int = if (body != null) 200 else upstream.responseCode

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (body != null) emptyMap() else upstream.responseHeaders

    override fun setRequestProperty(name: String, value: String) =
        upstream.setRequestProperty(name, value)

    override fun clearRequestProperty(name: String) = upstream.clearRequestProperty(name)

    override fun clearAllRequestProperties() = upstream.clearAllRequestProperties()
}
