package com.moviebox.tv.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Listens to what is playing, to check the subtitles against it.
 *
 * Measured 2026-10-09 on "The Early Spring": the English file OpenSubtitles
 * lists for episode 2 is episode 1's, and other files run seconds early or
 * late. The only reliable judge is the audio: people speak when a matching
 * subtitle says they do. So for every 50 ms of the episode this keeps one
 * number — how much speech-band energy there is — and SubtitleSession
 * slides the cue timeline against it to find the offset that lines them
 * up, or to find that nothing does (wrong file).
 *
 * It reads the decoded PCM on its way into the audio output and changes
 * nothing; a few multiply-adds per sample. Media time comes from the
 * buffers' own timestamps.
 */
internal object SpeechTap {
    const val FRAME_MS = 50L
    private const val MAX_FRAMES = 4 * 60 * 60 * 1000 / 50   // 4 h

    @Volatile private var frames = FloatArray(0)
    @Volatile private var generation = 0
    @Volatile private var count = 0

    /** New item: forget the previous one's audio. */
    fun reset() {
        synchronized(this) {
            generation++
            frames = FloatArray(0)
            count = 0
        }
    }

    fun framesHeard(): Int = count

    /** Feature per 50 ms frame of media time; NaN where nothing was heard
     *  (seeked over, not reached yet). A copy. */
    fun snapshot(): FloatArray = synchronized(this) { frames.copyOf() }

    internal fun put(gen: Int, frameIndex: Int, value: Float) {
        if (frameIndex < 0 || frameIndex >= MAX_FRAMES) return
        synchronized(this) {
            if (gen != generation) return
            if (frameIndex >= frames.size) {
                val n = maxOf(frameIndex + 1, frames.size * 2, 12_000).coerceAtMost(MAX_FRAMES)
                frames = frames.copyOf(n).also { a -> a.fill(Float.NaN, frames.size, n) }
            }
            if (frames[frameIndex].isNaN()) count++
            frames[frameIndex] = value
        }
    }

    internal fun gen() = generation
}

/** Audio output that hands a copy of each PCM buffer's speech energy to
 *  [SpeechTap] before playing it unchanged. */
internal class SpeechTapSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    private var pcm16 = false
    private var channels = 2
    private var rate = 48_000
    private var lastPts = Long.MIN_VALUE

    // Running state for the current 50 ms frame.
    private var frameIndex = -1
    private var bandSum = 0.0
    private var lowSum = 0.0
    private var hp = Biquad()
    private var lp = Biquad()
    private var low = Biquad()

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pcm16 = inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT
        channels = inputFormat.channelCount.coerceAtLeast(1)
        rate = inputFormat.sampleRate.takeIf { it > 0 } ?: 48_000
        hp = Biquad.highPass(rate, 300.0)
        lp = Biquad.lowPass(rate, 3_400.0)
        low = Biquad.lowPass(rate, 300.0)
        frameIndex = -1
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The same buffer comes back until the output has taken all of it;
        // measure it once.
        if (pcm16 && presentationTimeUs != lastPts && buffer.remaining() > 0) {
            lastPts = presentationTimeUs
            runCatching { measure(buffer, presentationTimeUs) }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        frameIndex = -1
        lastPts = Long.MIN_VALUE
        super.flush()
    }

    private fun measure(buffer: ByteBuffer, ptsUs: Long) {
        // Renderer timestamps carry ExoPlayer's fixed start offset (10^12 us
        // for a freshly set item); what remains is media time.
        val mediaUs = ptsUs - RENDERER_OFFSET_US
        if (mediaUs < 0 || mediaUs > 6L * 3_600_000_000L) return
        val gen = SpeechTap.gen()
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameSamples = (rate * SpeechTap.FRAME_MS / 1000).toInt()
        val samples = b.remaining() / (2 * channels)
        val firstSample = mediaUs * rate / 1_000_000L
        var pos = b.position()
        for (s in 0 until samples) {
            var sum = 0
            for (c in 0 until channels) {
                sum += b.getShort(pos).toInt()
                pos += 2
            }
            val x = sum.toDouble() / (channels * 32768.0)
            val idx = ((firstSample + s) / frameSamples).toInt()
            if (idx != frameIndex) {
                if (frameIndex >= 0) emit(gen, frameIndex)
                frameIndex = idx
                bandSum = 0.0
                lowSum = 0.0
            }
            val band = lp.step(hp.step(x))
            val l = low.step(x)
            bandSum += band * band
            lowSum += l * l
        }
    }

    private fun emit(gen: Int, idx: Int) {
        val v = ln(1.0 + bandSum * 1e4) - 0.5 * ln(1.0 + lowSum * 1e4)
        SpeechTap.put(gen, idx, v.toFloat())
    }

    /** RBJ cookbook biquad, direct form I. */
    private class Biquad(
        val b0: Double = 1.0, val b1: Double = 0.0, val b2: Double = 0.0,
        val a1: Double = 0.0, val a2: Double = 0.0,
    ) {
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        companion object {
            private const val Q = 0.7071
            fun lowPass(rate: Int, f: Double): Biquad {
                val w = 2 * PI * f / rate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
            fun highPass(rate: Int, f: Double): Biquad {
                val w = 2 * PI * f / rate
                val alpha = sin(w) / (2 * Q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }

    companion object {
        /** ExoPlayer's initial renderer position offset for a new playlist. */
        const val RENDERER_OFFSET_US = 1_000_000_000_000L
    }
}

/**
 * Where the subtitles line up with the speech: slides the cue timeline
 * against [SpeechTap]'s frames by up to ±[maxShiftMs] and scores each shift.
 * Returns the best offset (positive = subtitles should be LATER) and how far
 * it stands out from all the others (a z-score), or null with too little
 * overlap to judge.
 */
internal data class SyncResult(val offsetMs: Long, val z: Double, val heardMs: Long)

internal fun estimateSync(cues: List<SubCue>, frames: FloatArray, maxShiftMs: Long = 20_000): SyncResult? {
    val n = frames.size
    if (n == 0 || cues.isEmpty()) return null
    // Normalise the speech feature over what was heard.
    var cnt = 0
    var mean = 0.0
    for (v in frames) if (!v.isNaN()) { cnt++; mean += v }
    if (cnt < 3 * 60 * 20) return null                      // < 3 min heard
    mean /= cnt
    var varSum = 0.0
    for (v in frames) if (!v.isNaN()) varSum += (v - mean) * (v - mean)
    val sd = sqrt(varSum / cnt).takeIf { it > 1e-6 } ?: return null
    val f = FloatArray(n) { i -> if (frames[i].isNaN()) Float.NaN else ((frames[i] - mean) / sd).toFloat() }
    // Cue activity on the same 50 ms grid.
    val cue = FloatArray(n)
    for (c in cues) {
        val a = (c.startMs / SpeechTap.FRAME_MS).toInt().coerceIn(0, n)
        val b = (c.endMs / SpeechTap.FRAME_MS).toInt().coerceIn(0, n)
        for (i in a until b) cue[i] = 1f
    }
    val maxK = (maxShiftMs / SpeechTap.FRAME_MS).toInt()
    val scores = DoubleArray(2 * maxK + 1)
    var bestK = 0
    var best = Double.NEGATIVE_INFINITY
    for (k in -maxK..maxK) {
        // Speech at i + k for a cue at i: show subtitles k frames later.
        var s = 0.0
        var fSum = 0.0
        var cSum = 0.0
        var m = 0
        var i = maxOf(0, -k)
        val end = minOf(n, n - k)
        while (i < end) {
            val v = f[i + k]
            if (!v.isNaN()) { s += v * cue[i]; fSum += v; cSum += cue[i]; m++ }
            i++
        }
        if (m < 3 * 60 * 20) { scores[k + maxK] = Double.NaN; continue }
        // Covariance of speech and cue activity over the overlap, so a
        // shift's score does not depend on how much heard audio it covers.
        val sc = (s - fSum * cSum / m) / m
        scores[k + maxK] = sc
        if (sc > best) { best = sc; bestK = k }
    }
    val valid = scores.filter { !it.isNaN() }
    if (valid.size < 20) return null
    val mu = valid.average()
    val sigma = sqrt(valid.sumOf { (it - mu) * (it - mu) } / valid.size).takeIf { it > 1e-9 } ?: return null
    return SyncResult(bestK * SpeechTap.FRAME_MS, (best - mu) / sigma, cnt * SpeechTap.FRAME_MS)
}
