package com.h_ide4pda.wakemywatch.watch.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.h_ide4pda.wakemywatch.core.VibroPatterns
import com.h_ide4pda.wakemywatch.watch.R

// True ease-in-out (CSS-style), gentle at both ends — not the linear/one-sided Compose presets.
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

@Composable
fun WatchLookAtPhoneScreen(onOk: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        VibroPatterns.playLookAtPhone(context)
    }
    val transition = rememberInfiniteTransition(label = "look_at_phone")

    // 1. Phone — slowest, smallest amplitude: a gentle rotational wobble.
    val phoneTilt by transition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phone_tilt",
    )

    // 2. Arrow — faster than the phone, horizontal drift toward/away from it.
    val arrowOffset by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "arrow_offset",
    )

    // 3. Small blue wave — fastest layer, own short cycle.
    val smallWaveOffset by transition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "small_wave_offset",
    )
    val smallWaveAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "small_wave_alpha",
    )

    // 4. Big violet wave — its own cycle length AND a start delay so it moves out of phase with
    // the small wave (they drift apart/together instead of moving in lockstep).
    val bigWaveOffset by transition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(300),
        ),
        label = "big_wave_offset",
    )
    val bigWaveAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(300),
        ),
        label = "big_wave_alpha",
    )

    Scaffold {
        Box(
            Modifier.fillMaxSize().background(WatchColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.height(95.dp), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.arrow_illustration),
                        contentDescription = null,
                        modifier = Modifier
                            .height(34.dp)
                            .width(40.dp)
                            .graphicsLayer { translationX = (-50).dp.toPx() + arrowOffset.dp.toPx() },
                    )
                    Image(
                        painter = painterResource(R.drawable.phone_illustration),
                        contentDescription = null,
                        modifier = Modifier
                            .height(90.dp)
                            .width(120.dp)
                            .graphicsLayer { rotationZ = phoneTilt },
                    )
                    Image(
                        painter = painterResource(R.drawable.vibro_small_blue),
                        contentDescription = null,
                        modifier = Modifier
                            .height(56.dp)
                            .width(18.dp)
                            .graphicsLayer {
                                translationX = 42.dp.toPx() + smallWaveOffset.dp.toPx()
                                alpha = smallWaveAlpha
                            },
                    )
                    Image(
                        painter = painterResource(R.drawable.vibro_big_violet),
                        contentDescription = null,
                        modifier = Modifier
                            .height(74.dp)
                            .width(20.dp)
                            .graphicsLayer {
                                translationX = 58.dp.toPx() + bigWaveOffset.dp.toPx()
                                alpha = bigWaveAlpha
                            },
                    )
                }
                Spacer(10.dp)
                Text(
                    text = "Загляните в телефон",
                    color = WatchColors.Text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body1,
                )
                Spacer(10.dp)
                Chip(
                    onClick = onOk,
                    label = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("ОК", style = MaterialTheme.typography.button, textAlign = TextAlign.Center)
                        }
                    },
                    modifier = Modifier.width(100.dp),
                    colors = ChipDefaults.chipColors(
                        backgroundColor = WatchColors.Purple,
                        contentColor = WatchColors.Text,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp = 12.dp) = Box(Modifier.height(height))
