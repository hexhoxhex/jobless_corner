package com.moviebox.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.data.TastePrefs
import com.moviebox.tv.remote.RemoteAccess
import com.moviebox.tv.ui.theme.Accent
import com.moviebox.tv.ui.theme.Bg
import com.moviebox.tv.ui.theme.Surface
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    // Light poll so role/last-seen reflect remote changes from the SPA.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick

    val allowAll = RemoteAccess.allowAll
    val devices = RemoteAccess.all()
    val pairCode = RemoteAccess.pairCode
    val deny = TastePrefs.denyLanguages()

    // LazyColumn instead of Column + verticalScroll. On TV, focus
    // traversal between focusable items inside a LazyColumn
    // automatically scrolls them into view via the focus restorer.
    // verticalScroll relies on touch / wheel only — DPAD presses
    // landed on the offscreen items but the surrounding view never
    // shifted, so the user couldn't reach the paired-devices section
    // or the new About card with the remote.
    LazyColumn(
        Modifier.fillMaxSize().background(Bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Text(
                    "Settings — Remotes", fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Pair-code card
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Surface)
                    .padding(16.dp),
            ) {
                Text("Pair code", color = TextMuted, fontSize = 12.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        pairCode, color = Accent, fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { RemoteAccess.regeneratePairCode(); tick++ }) {
                        Text("Regenerate")
                    }
                }
                Text(
                    "Anyone who scans the QR or enters this code is added as a new remote.",
                    color = TextMuted, fontSize = 12.sp,
                )
            }
        }

        // Allow-all toggle
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Allow all devices on the Wi-Fi", fontWeight = FontWeight.SemiBold)
                    Text(
                        "When off, new phones land in Pending until you approve them here.",
                        color = TextMuted, fontSize = 12.sp,
                    )
                }
                Switch(checked = allowAll, onCheckedChange = {
                    RemoteAccess.allowAll = it; tick++
                })
            }
        }

        // Languages to hide (e.g. drop [Hindi] / [Tamil] titles)
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Surface)
                    .padding(16.dp),
            ) {
                Text("Languages to hide", fontWeight = FontWeight.SemiBold)
                Text(
                    "Titles tagged with these (e.g. \"Hulk [Hindi]\") won't appear " +
                        "in Home or Search.",
                    color = TextMuted, fontSize = 12.sp,
                )
                Spacer(Modifier.size(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LANG_OPTIONS.forEach { (code, label) ->
                        LangChip(
                            label = label, on = code in deny,
                            onToggle = {
                                val next = deny.toMutableSet()
                                if (code in next) next.remove(code) else next.add(code)
                                TastePrefs.setDenyLanguages(next); tick++
                            }
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Paired devices", color = TextMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (devices.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                    Text("No phones paired yet.", color = TextMuted)
                }
            }
        } else {
            items(devices, key = { it.token }) { d -> DeviceRow(d) { tick++ } }
        }

        // About / version card. Lives at the bottom so the user can see
        // which build is on the TV — the "is my update actually applied?"
        // question came up enough that a visible version string is just
        // table stakes. "Check now" forces an UpdateChecker refresh.
        item { AboutCard() }
    }
}

@Composable
private fun AboutCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val versionName = com.moviebox.tv.BuildConfig.VERSION_NAME
    val versionCode = com.moviebox.tv.BuildConfig.VERSION_CODE
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(Surface)
            .padding(16.dp),
    ) {
        Text("About this build", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.size(6.dp))
        Text(
            "Vijana BaruBaru TV", fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(
            "v$versionName (build $versionCode)",
            color = TextMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            "Updates publish automatically via GitHub Releases. Open this " +
                "card after a tag push to confirm the new version installed.",
            color = TextMuted, fontSize = 12.sp,
        )
        Spacer(Modifier.size(10.dp))
        OutlinedButton(onClick = {
            android.widget.Toast.makeText(
                ctx,
                "Checking GitHub Releases…",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            // Defer to the existing UpdateChecker via a fresh CoroutineScope —
            // re-using viewModelScope would couple this card to the VM and
            // we'd rather keep the About card self-contained.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching {
                    com.moviebox.tv.debug.UpdateChecker()
                        .check(com.moviebox.tv.BuildConfig.VERSION_NAME)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Done — banner appears on Home if a new version was found.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }) {
            Text("Check for updates")
        }
    }
}

@Composable
private fun LangChip(label: String, on: Boolean, onToggle: () -> Unit) {
    val bg = if (on) Color(0x33E5484D) else SurfaceElevated
    val border = if (on) Color(0xFFE5484D) else Color.Transparent
    val fg = if (on) Color(0xFFE5484D) else TextMuted
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onToggle),
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val LANG_OPTIONS = listOf(
    "hi" to "Hindi",  "ta" to "Tamil",  "te" to "Telugu",
    "ml" to "Malayalam", "kn" to "Kannada", "bn" to "Bengali",
    "ur" to "Urdu",   "mr" to "Marathi", "pa" to "Punjabi",
    "ko" to "Korean", "ja" to "Japanese", "es" to "Spanish",
    "fr" to "French", "de" to "German",
)

@Composable
private fun DeviceRow(d: RemoteAccess.Device, onChange: () -> Unit) {
    var roleMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                .background(roleColor(d.role)),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(d.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${d.ip} · ${d.role.name.lowercase()} · ${ago(d.lastSeen)}",
                color = TextMuted, fontSize = 11.sp,
            )
        }
        Box {
            TextButton(onClick = { roleMenu = true }) { Text("Role") }
            DropdownMenu(roleMenu, onDismissRequest = { roleMenu = false }) {
                RemoteAccess.Role.values().forEach { r ->
                    DropdownMenuItem(text = { Text(r.name.lowercase()) }, onClick = {
                        RemoteAccess.setRole(d.token, r); roleMenu = false; onChange()
                    })
                }
            }
        }
        TextButton(onClick = { RemoteAccess.remove(d.token); onChange() }) {
            Text("Remove", color = Color(0xFFE5484D))
        }
    }
}

private fun roleColor(r: RemoteAccess.Role): Color = when (r) {
    RemoteAccess.Role.SUPERUSER -> Accent
    RemoteAccess.Role.USER -> Color(0xFF4ECB71)
    RemoteAccess.Role.PENDING -> Color(0xFFE8B341)
    RemoteAccess.Role.BLOCKED -> Color(0xFFE5484D)
}

private fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}
