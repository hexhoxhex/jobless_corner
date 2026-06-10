package com.moviebox.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.moviebox.tv.data.SubjectType
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted

@Composable
fun SearchScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextField(
            value = state.query,
            onValueChange = vm::onQuery,
            placeholder = { Text("Search movies & TV shows") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceElevated,
                unfocusedContainerColor = SurfaceElevated,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("All", state.searchType == SubjectType.ALL) {
                vm.onSearchType(SubjectType.ALL); vm.search()
            }
            FilterChip("Movies", state.searchType == SubjectType.MOVIE) {
                vm.onSearchType(SubjectType.MOVIE); vm.search()
            }
            FilterChip("TV Shows", state.searchType == SubjectType.TV_SERIES) {
                vm.onSearchType(SubjectType.TV_SERIES); vm.search()
            }
        }

        if (state.searchLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (!state.searchLoading && state.searchResults.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (state.error != null) Text(friendlyError(state.error), color = TextMuted)
                else Text("Search for something to watch", color = TextMuted)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.searchResults) { item ->
                PosterCard(item, width = 110.dp) { vm.openItem(item) }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else TextMuted,
        )
    }
}
