package com.moviebox.tv.debug

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window failure tracker for each upstream provider we depend on.
 *
 * Why distinct from network-level monitoring: a user's home internet can
 * be perfectly fine while the donis CDN, the aoneroom signed-request
 * server, or GitHub's API is having a regional outage. The UI message
 * needs to distinguish "your Wi-Fi is out" from "this catalog is down" so
 * the user doesn't fumble at their router for no reason.
 *
 * Each provider is identified by an opaque string (e.g. "donis",
 * "aoneroom", "mkurugenzi", "tmdb", "github"). Successes and failures are
 * counted in a 5-minute sliding window; we declare a provider DOWN when
 * either:
 *
 *   - the failure ratio in the window exceeds 75% over ≥3 attempts, or
 *   - there have been ≥4 consecutive failures.
 *
 * The first time a provider crosses to DOWN, the Telemetry event log
 * gets an ERROR entry. When it crosses back to UP, INFO.
 *
 * Everything is in-memory; no persistence needed because providers
 * recover on their own and we re-discover state on each interaction.
 */
object ProviderHealth {

    enum class Status { Up, Degraded, Down }

    data class Snapshot(
        val name: String,
        val status: Status,
        val recentSuccesses: Int,
        val recentFailures: Int,
        val lastFailure: String?,
        val lastFailureAtMs: Long?,
    )

    fun success(provider: String) {
        val s = stateFor(provider)
        synchronized(s.lock) {
            trim(s)
            s.successes.add(SystemClock.elapsedRealtime())
            s.consecutiveFailures = 0
            evaluate(provider, s)
        }
    }

    fun failure(provider: String, reason: String) {
        val s = stateFor(provider)
        synchronized(s.lock) {
            trim(s)
            s.failures.add(SystemClock.elapsedRealtime())
            s.consecutiveFailures += 1
            s.lastFailure = reason
            s.lastFailureAtMs = SystemClock.elapsedRealtime()
            evaluate(provider, s)
        }
    }

    fun snapshotAll(): List<Snapshot> {
        return states.map { (name, s) ->
            synchronized(s.lock) {
                trim(s)
                Snapshot(
                    name = name,
                    status = s.status,
                    recentSuccesses = s.successes.size,
                    recentFailures = s.failures.size,
                    lastFailure = s.lastFailure,
                    lastFailureAtMs = s.lastFailureAtMs,
                )
            }
        }
    }

    fun clear() {
        states.clear()
    }

    // ---- internals ----

    private fun stateFor(provider: String): ProviderState =
        states.getOrPut(provider) { ProviderState() }

    private fun trim(s: ProviderState) {
        val cutoff = SystemClock.elapsedRealtime() - WINDOW_MS
        while (s.successes.isNotEmpty() && s.successes.first() < cutoff) s.successes.removeFirst()
        while (s.failures.isNotEmpty()  && s.failures.first()  < cutoff) s.failures.removeFirst()
    }

    private fun evaluate(provider: String, s: ProviderState) {
        val attempts = s.successes.size + s.failures.size
        val failRatio = if (attempts > 0) s.failures.size.toFloat() / attempts else 0f
        val next = when {
            s.consecutiveFailures >= CONSEC_DOWN -> Status.Down
            attempts >= MIN_ATTEMPTS && failRatio >= DOWN_RATIO -> Status.Down
            attempts >= MIN_ATTEMPTS && failRatio >= DEGRADED_RATIO -> Status.Degraded
            else -> Status.Up
        }
        if (next != s.status) {
            val prev = s.status
            s.status = next
            Telemetry.note(
                when (next) {
                    Status.Down -> Telemetry.Severity.ERROR
                    Status.Degraded -> Telemetry.Severity.WARN
                    Status.Up -> Telemetry.Severity.INFO
                },
                "Provider $provider: ${prev.name} → ${next.name}",
            )
        }
    }

    private val states = ConcurrentHashMap<String, ProviderState>()

    private class ProviderState {
        val lock = Any()
        val successes = ArrayDeque<Long>()
        val failures  = ArrayDeque<Long>()
        var consecutiveFailures = 0
        @Volatile var status: Status = Status.Up
        @Volatile var lastFailure: String? = null
        @Volatile var lastFailureAtMs: Long? = null
    }

    private const val WINDOW_MS: Long = 5L * 60 * 1000   // 5 minutes
    private const val MIN_ATTEMPTS = 3
    private const val DOWN_RATIO = 0.75f
    private const val DEGRADED_RATIO = 0.40f
    private const val CONSEC_DOWN = 4
}
