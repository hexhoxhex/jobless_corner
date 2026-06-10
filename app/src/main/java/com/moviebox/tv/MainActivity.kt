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
import com.moviebox.tv.ui.AppRoot
import com.moviebox.tv.ui.MainViewModel
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
}
