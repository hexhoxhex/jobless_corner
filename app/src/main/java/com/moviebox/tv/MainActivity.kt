package com.moviebox.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.moviebox.tv.data.TastePrefs
import com.moviebox.tv.data.UnavailableCatalog
import com.moviebox.tv.remote.RemoteController
import com.moviebox.tv.ui.AppRoot
import com.moviebox.tv.ui.MainViewModel
import com.moviebox.tv.ui.Screen
import com.moviebox.tv.ui.theme.Bg
import com.moviebox.tv.ui.theme.MovieBoxTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Init early — Repository.home() (called from the ViewModel's init block
        // the moment AppRoot composes) reads it.
        TastePrefs.init(applicationContext)
        UnavailableCatalog.init(applicationContext)
        // Spin the mobile-remote server up eagerly. It used to wait until
        // the user navigated to the Remote QR screen, which means anyone
        // diagnosing reconnect issues over adb couldn't drive playback
        // via /api/live/play without first poking the TV's d-pad. Eager
        // start has no side effects when no client has paired.
        com.moviebox.tv.remote.RemoteServerManager.ensureStarted(this)
        setContent {
            MovieBoxTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    AppRoot(vm)
                }
            }
        }
    }

    /** Notice TV-remote (D-pad) use so we can suggest the phone remote. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                vm.onDpadUsed()
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Player-screen transport keys intercepted at the Activity level so they
     * NEVER reach the Compose focus tree. Reason: the player overlay has
     * focusable buttons that would consume DPAD_LEFT/RIGHT to navigate between
     * themselves, swallowing the seek intent entirely (this was the "OK works
     * but forward/rewind don't" symptom users reported).
     *
     * Netflix and Apple TV both do exactly this: arrow-left/right on the
     * player surface ALWAYS means seek; the overlay reserves UP/DOWN for its
     * own pickers and CENTER to activate. We mirror that.
     *
     * Gated on:
     *   - we're actually on Screen.PLAYER (don't grab keys on Home/grids)
     *   - native ExoPlayer is active (RemoteController.player != null —
     *     null while the LiveWebPlayer fallback is rendered)
     *   - the event is a KeyDown (KeyUp passes through unchanged)
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            vm.state.value.screen == Screen.PLAYER
        ) {
            val exo = RemoteController.player
            if (exo != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0))
                        vm.onDpadUsed()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                        exo.seekTo(exo.currentPosition + 10_000)
                        vm.onDpadUsed()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        exo.playWhenReady = !exo.isPlaying
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        exo.playWhenReady = true; return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        exo.playWhenReady = false; return true
                    }
                    // UP/DOWN/CENTER intentionally fall through so the
                    // overlay's button focus + activation still work.
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
