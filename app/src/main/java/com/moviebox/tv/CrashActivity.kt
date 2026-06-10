package com.moviebox.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.ui.friendlyError
import com.moviebox.tv.ui.theme.Bg
import com.moviebox.tv.ui.theme.MovieBoxTheme
import com.moviebox.tv.ui.theme.TextMuted
import kotlin.system.exitProcess

/** Shown (in its own process) when the app hits an uncaught exception. */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = intent.getStringExtra(EXTRA_ERROR)
        setContent {
            MovieBoxTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    Column(
                        Modifier.fillMaxSize().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(
                            16.dp, Alignment.CenterVertically,
                        ),
                    ) {
                        Text("😬", fontSize = 40.sp)
                        Text(
                            "Something went wrong",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            friendlyError(message),
                            color = TextMuted, textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                        )
                        Button(onClick = ::restart) { Text("Restart app") }
                    }
                }
            }
        }
    }

    private fun restart() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        )
        finish()
        exitProcess(0)
    }

    companion object {
        const val EXTRA_ERROR = "error"
    }
}
