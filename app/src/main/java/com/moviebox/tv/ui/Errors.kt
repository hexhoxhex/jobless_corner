package com.moviebox.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.ui.theme.TextMuted

/** Turn raw exception text into something a human wants to read. */
fun friendlyError(message: String?): String {
    if (message.isNullOrBlank()) return "Something went wrong. Please try again."
    val m = message.lowercase()
    return when {
        listOf("unable to resolve host", "failed to connect", "timeout",
            "timed out", "no address", "network is unreachable", "unreachable")
            .any { it in m } ->
            "No internet connection. Check your network and try again."
        "all api hosts" in m || "exhausted" in m ->
            "Can't reach the server right now. Please try again."
        "region" in m || "geo" in m || "403" in m ->
            "This content isn't available in your region."
        "empty" in m || "no results" in m || "different keyword" in m ->
            "No results found. Try a different search."
        else -> message
    }
}

@Composable
fun ErrorView(
    message: String?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.CloudOff, null, tint = TextMuted,
            modifier = Modifier.size(46.dp))
        Text(
            friendlyError(message),
            color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
    }
}
