package com.moviebox.tv.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.moviebox.tv.R

/**
 * Branded loading indicator. Wraps `lottie-compose` around the bundled
 * `res/raw/loader_dots.json` — a 3-dot pulse loop in our brand green
 * (#07B84E / #2FF58B). Used in place of `CircularProgressIndicator` at
 * every user-visible loading spot for the same smooth feel MovieWay gets
 * from its 87 Lottie references.
 *
 * Pass a [size] that fits the surface — 64.dp for the big home loader,
 * 36.dp for inline spinners. The composition is cached by the library, so
 * rendering many at once is cheap.
 */
@Composable
fun LottieLoader(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp,
) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.loader_dots),
    )
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        modifier = modifier.size(size),
    )
}
