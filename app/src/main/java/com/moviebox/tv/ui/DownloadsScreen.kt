package com.moviebox.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.data.local.DownloadEntity
import com.moviebox.tv.data.local.DownloadStatus
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.TextMuted

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val downloads by vm.downloads.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text(
            "Downloads",
            fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No downloads yet — tap ⬇ on a title.", color = TextMuted)
            }
            return
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(downloads) { d -> DownloadRow(d, vm) }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadEntity, vm: MainViewModel) {
    val complete = d.isComplete
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = complete) { vm.playDownload(d) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(8.dp))) {
            PosterImage(d.coverUrl, d.title, Modifier.fillMaxSize())
            if (complete) {
                Icon(
                    Icons.Filled.PlayCircle, "Play", tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                d.title + if (d.season > 0) "  ·  S${d.season}E${d.episode}" else "",
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2,
            )
            Text(statusLine(d), color = TextMuted, fontSize = 12.sp)
            if (!complete && d.status == DownloadStatus.DOWNLOADING.name) {
                Box(
                    Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(d.progress)
                            .background(Accent),
                    )
                }
            }
        }
        Box(
            Modifier.size(36.dp).clickable { vm.removeDownload(d) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Delete", tint = TextMuted) }
    }
}

private fun statusLine(d: DownloadEntity): String = when (d.status) {
    DownloadStatus.COMPLETED.name -> "Downloaded · plays offline"
    DownloadStatus.DOWNLOADING.name -> "Downloading ${(d.progress * 100).toInt()}%"
    DownloadStatus.QUEUED.name -> "Queued"
    DownloadStatus.FAILED.name -> "Failed — tap ✕ and retry"
    else -> d.status
}
