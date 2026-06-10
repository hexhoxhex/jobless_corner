package com.moviebox.tv.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.moviebox.tv.remote.RemoteServerManager
import com.moviebox.tv.ui.theme.Surface
import com.moviebox.tv.ui.theme.SurfaceElevated
import com.moviebox.tv.ui.theme.TextMuted
import kotlinx.coroutines.delay

/** Transparent QR overlay — works over the player or any page. */
@Composable
fun RemoteOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val url by produceState<String?>(initialValue = null) {
        RemoteServerManager.ensureStarted(context)
        value = RemoteServerManager.url()
    }
    val pairUrl by produceState<String?>(initialValue = null) {
        RemoteServerManager.ensureStarted(context)
        value = RemoteServerManager.pairUrl()
    }
    Box(
        Modifier.fillMaxSize().background(ComposeColor(0xB3000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp))
                .background(Surface).padding(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid, contentDescription = null,
                    tint = ComposeColor.White, modifier = Modifier.size(22.dp),
                )
                Text(
                    "Phone Remote", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (url == null) {
                Text("Connect to Wi-Fi to use the remote.", color = TextMuted,
                    textAlign = TextAlign.Center)
            } else {
                val bmp = remember(pairUrl, url) { qrBitmap(pairUrl ?: url!!, 480) }
                Image(
                    bmp.asImageBitmap(), "Remote QR",
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp))
                        .background(ComposeColor.White).padding(10.dp),
                )
                Text(url!!, fontWeight = FontWeight.SemiBold)
                Text("Pair code: ${com.moviebox.tv.remote.RemoteAccess.pairCode}",
                    color = TextMuted, fontSize = 12.sp)
                Text("Scan with your phone's camera (same Wi-Fi).",
                    color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

/** Small, auto-dismissing tip shown after some D-pad use. */
@Composable
fun SuggestionPopup(onShow: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { delay(9_000); onDismiss() }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))
                .background(SurfaceElevated).padding(start = 14.dp, end = 4.dp,
                    top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lightbulb, contentDescription = null,
                tint = TextMuted, modifier = Modifier.size(18.dp),
            )
            Text(
                "Use your phone as a remote — scan a QR",
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
            )
            TextButton(onClick = onShow) { Text("Show") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, "Dismiss", tint = TextMuted)
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}
