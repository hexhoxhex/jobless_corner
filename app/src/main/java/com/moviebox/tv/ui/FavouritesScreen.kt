package com.moviebox.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.ui.theme.Gold
import com.moviebox.tv.ui.theme.TextMuted

@Composable
fun FavouritesScreen(vm: MainViewModel) {
    val favourites by vm.favourites.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text(
            "Favourites",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
        if (favourites.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No favourites yet — tap ♡ on a title.", color = TextMuted)
            }
            return
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(favourites) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openItem(item) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(64.dp).height(96.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    ) { PosterImage(item.coverUrl, item.title, Modifier.fillMaxSize()) }
                    Column(
                        Modifier.weight(1f).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            maxLines = 2)
                        Text(
                            buildString {
                                append(if (item.isSeries) "TV Series" else "Movie")
                                item.year?.let { append("  •  $it") }
                            },
                            color = TextMuted, fontSize = 12.sp,
                        )
                        item.rating?.takeIf { it > 0 }?.let {
                            Text("★ %.1f".format(it), color = Gold, fontSize = 12.sp)
                        }
                    }
                    Box(
                        Modifier.size(36.dp).clickable { vm.removeFavourite(item.subjectId) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, "Remove", tint = TextMuted) }
                }
            }
        }
    }
}
