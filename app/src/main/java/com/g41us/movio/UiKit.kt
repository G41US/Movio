package com.g41us.movio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val Ink = Color(0xFF07080D)
val Panel = Color(0xFF151722)
val Violet = Color(0xFF9B6DFF)
val Pink = Color(0xFFFF5FA2)
val Muted = Color(0xFF9EA1B3)
val Soft = Color(0xFFDADBE5)
val Gold = Color(0xFFFFC94D)
val Glass = Color.White.copy(alpha = .09f)
val Hairline = Color.White.copy(alpha = .12f)
val Brand = Brush.linearGradient(listOf(Violet, Pink))
val Gutter = 18.dp

val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
    Font(R.font.jakarta_extrabold, FontWeight.Black),
)

fun jakarta(brush: Brush? = null) = TextStyle(fontFamily = Jakarta, brush = brush)

/** Animated light sweep for loading placeholders. */
fun Modifier.shimmer(shape: RoundedCornerShape = RoundedCornerShape(14.dp)): Modifier = composed {
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep",
    )
    this.clip(shape).background(Color(0xFF181A26)).background(
        Brush.linearGradient(
            0f to Color.Transparent, .5f to Color.White.copy(alpha = .07f), 1f to Color.Transparent,
            start = Offset(sweep * 900f - 450f, 0f), end = Offset(sweep * 900f + 150f, 600f),
        )
    )
}

/** Click that also shrinks the element slightly while a finger is down. */
fun Modifier.tappable(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = if (pressed) .96f else 1f
    this.graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = source, indication = null, onClick = onClick)
}

private val EnterEase = CubicBezierEasing(.16f, 1f, .3f, 1f)

/** Fades and lifts content in the first time it appears; [order] staggers neighbours. */
fun Modifier.enterUp(order: Int = 0): Modifier = composed {
    if (LocalInspectionMode.current) return@composed this
    val progress = remember { Animatable(0f) }
    val lift = with(LocalDensity.current) { 36.dp.toPx() }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(800, delayMillis = 90 * order.coerceAtMost(6), easing = EnterEase)) }
    this.graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * lift }
}

/** Slow push-in on hero artwork. */
fun Modifier.kenBurns(): Modifier = composed {
    if (LocalInspectionMode.current) return@composed this
    val zoom by rememberInfiniteTransition(label = "kb").animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom",
    )
    this.graphicsLayer { scaleX = zoom; scaleY = zoom }
}

fun rating(r: Double) = if (r <= 0) null else String.format("%.1f", r)
