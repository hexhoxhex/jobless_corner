package com.moviebox.tv.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for "do we have internet?".
 *
 * Three states, derived from ConnectivityManager + how long we've been in
 * each state:
 *
 *  - **[State.Online]** — we have a usable network. UI shows nothing.
 *  - **[State.Checking]** — connectivity dropped, we're <3 minutes in. UI
 *    shows a quiet banner "Checking your connection…". The user shouldn't
 *    be hit with a giant error page for a 30-second Wi-Fi blip.
 *  - **[State.OfflineLong]** — still offline after 3 minutes. UI shows a
 *    full-page clean error explaining the problem. After [LONG_FAIL_MS]
 *    (1 hour) we add "still no connection — try moving closer to the
 *    router or restarting it" guidance.
 *
 * Internally we listen on [ConnectivityManager.NetworkCallback]. Android
 * fires `onAvailable` / `onLost` reliably; we don't need to poll, the
 * system already polls for us. The Telemetry hooks log every transition
 * for the Debug pane.
 */
object NetworkMonitor {

    enum class State { Online, Checking, OfflineLong }

    private val _state = MutableStateFlow(State.Online)
    val state: StateFlow<State> = _state.asStateFlow()

    /** When did we enter the current state? Used by the UI to render
     *  "still no connection after Xs" copy. */
    @Volatile private var enteredAtMs: Long = SystemClock.elapsedRealtime()

    fun timeInStateMs(): Long = SystemClock.elapsedRealtime() - enteredAtMs

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        // Validated capability == the OS has confirmed reachability of
        // captive-portal probe, not just an attached IP. Matters for hotels.
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = transitionTo(State.Online)
            override fun onLost(network: Network) {
                if (_state.value == State.Online) transitionTo(State.Checking)
            }
            override fun onUnavailable() {
                if (_state.value == State.Online) transitionTo(State.Checking)
            }
        })
        // Seed: query the current state synchronously so we don't show
        // "Online" for an extra second before the first callback fires.
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val haveNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        transitionTo(if (haveNet) State.Online else State.Checking)
    }

    /** Promote Checking → OfflineLong once the soft window has elapsed.
     *  Called from a periodic tick on the main thread (cheap; just reads
     *  state.value and a long). */
    fun tick() {
        if (_state.value == State.Checking && timeInStateMs() >= SOFT_FAIL_MS) {
            transitionTo(State.OfflineLong)
        }
    }

    private fun transitionTo(next: State) {
        if (_state.value == next) return
        enteredAtMs = SystemClock.elapsedRealtime()
        _state.value = next
        Telemetry.note(
            when (next) {
                State.Online       -> Telemetry.Severity.INFO
                State.Checking     -> Telemetry.Severity.WARN
                State.OfflineLong  -> Telemetry.Severity.ERROR
            },
            "Network: ${next.name}",
        )
    }

    @Volatile private var started = false

    /** Move from "Checking" to "OfflineLong" after this much time. The UI
     *  switches from a quiet banner to a full-screen clean error page
     *  at this threshold. 3 minutes — long enough for a router reboot to
     *  recover transparently, short enough that an actually-offline user
     *  knows the app noticed. */
    const val SOFT_FAIL_MS: Long = 3L * 60 * 1000

    /** After this much time we change the OfflineLong copy to include
     *  troubleshooting guidance. 1 hour — at this point it's clearly
     *  not a transient blip. */
    const val LONG_FAIL_MS: Long = 60L * 60 * 1000
}
