package com.g41us.movio

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

private data class Choice(val label: String, val selected: Boolean, val apply: () -> Unit)

private enum class Menu { QUALITY, SPEED, SUBTITLES }

fun clock(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Movio's own player controls, drawn over the video. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControls(
    player: Player,
    label: String,
    showNext: Boolean,
    fill: Boolean,
    onFill: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onWeb: () -> Unit,
    onSources: () -> Unit = {},
    source: String = "",
) {
    var visible by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (!dragging) position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            if (visible && menu == null && player.isPlaying && System.currentTimeMillis() - lastTouch > 3500) visible = false
            delay(250)
        }
    }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(650); seekHint = null } }

    fun touch() { lastTouch = System.currentTimeMillis() }
    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE))
        seekHint = if (ms > 0) "+10s" else "−10s"
        touch()
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { if (menu != null) menu = null else { visible = !visible; touch() } },
                onDoubleTap = { o -> seekBy(if (o.x < size.width / 2) -10_000 else 10_000) },
            )
        },
    ) {
        if (buffering) CircularProgressIndicator(color = Violet, trackColor = Glass, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(46.dp))
        seekHint?.let {
            Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, style = jakarta(),
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Ink.copy(alpha = .6f)).padding(horizontal = 18.dp, vertical = 10.dp))
        }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .7f), .3f to Color.Transparent, .65f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f)))) {
                // Top
                Row(
                    Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIcon(R.drawable.ic_back, size = 44.dp) { onBack() }
                    Spacer(Modifier.width(14.dp))
                    Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = jakarta(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GhostButton(source.ifBlank { "Sources" }, R.drawable.ic_servers) { onSources(); touch() }
                        GlassIcon(R.drawable.ic_cc, active = menu == Menu.SUBTITLES, size = 44.dp) { menu = Menu.SUBTITLES; touch() }
                        GlassIcon(R.drawable.ic_speed, active = menu == Menu.SPEED, size = 44.dp) { menu = Menu.SPEED; touch() }
                        GlassIcon(R.drawable.ic_settings, active = menu == Menu.QUALITY, size = 44.dp) { menu = Menu.QUALITY; touch() }
                    }
                }
                // Centre
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    GlassIcon(R.drawable.ic_back10, size = 56.dp) { seekBy(-10_000) }
                    Box(
                        Modifier.size(78.dp).clip(CircleShape).background(Color.White).clickable {
                            if (player.isPlaying) player.pause() else player.play(); touch()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_fill), null, tint = Ink, modifier = Modifier.size(34.dp))
                    }
                    GlassIcon(R.drawable.ic_fwd10, size = 56.dp) { seekBy(10_000) }
                }
                // Bottom
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(if (dragging) (dragValue * duration).toLong() else position), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = jakarta())
                        Slider(
                            value = if (dragging) dragValue else if (duration > 0) position.toFloat() / duration else 0f,
                            onValueChange = { dragging = true; dragValue = it; touch() },
                            onValueChangeFinished = { player.seekTo((dragValue * duration).toLong()); dragging = false },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Violet, inactiveTrackColor = Color.White.copy(alpha = .25f)),
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        )
                        Text(clock(duration), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = jakarta())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showNext) GhostButton("Next episode", R.drawable.ic_next) { onNext() }
                        Spacer(Modifier.weight(1f))
                        GhostButton(if (fill) "Fit" else "Fill", R.drawable.ic_fit) { onFill(); touch() }
                        GhostButton("Web player", R.drawable.ic_web) { onWeb() }
                    }
                }
            }
        }

        // Option panel slides in from the right.
        AnimatedVisibility(
            menu != null, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val m = menu ?: Menu.SPEED
            val choices = choices(player, m)
            Column(
                Modifier.fillMaxHeight().width(280.dp).background(Color(0xF0101219)).safeDrawingPadding()
                    .padding(18.dp).verticalScroll(rememberScrollState()),
            ) {
                Label(when (m) { Menu.QUALITY -> "Quality"; Menu.SPEED -> "Playback speed"; Menu.SUBTITLES -> "Subtitles" })
                Spacer(Modifier.height(10.dp))
                if (choices.isEmpty()) Text("Not available for this video", color = Muted, fontSize = 14.sp, style = jakarta())
                choices.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (c.selected) Violet.copy(alpha = .28f) else Color.Transparent)
                            .border(1.dp, if (c.selected) Violet.copy(alpha = .6f) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { c.apply(); menu = null; touch() }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.label, color = Color.White, fontSize = 15.sp, fontWeight = if (c.selected) FontWeight.Bold else FontWeight.Medium, style = jakarta(), modifier = Modifier.weight(1f))
                        if (c.selected) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun choices(player: Player, menu: Menu): List<Choice> {
    val params = player.trackSelectionParameters
    return when (menu) {
        Menu.SPEED -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { s ->
            Choice(if (s == 1f) "Normal" else "${s}x", player.playbackParameters.speed == s) { player.setPlaybackSpeed(s) }
        }
        Menu.QUALITY -> {
            val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return emptyList()
            val override = params.overrides[group.mediaTrackGroup]
            val auto = Choice("Auto", override == null) {
                player.trackSelectionParameters = params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            }
            val tracks = (0 until group.length).filter { group.isTrackSupported(it) }
                .distinctBy { group.getTrackFormat(it).height }
                .sortedByDescending { group.getTrackFormat(it).height }
                .map { i ->
                    val h = group.getTrackFormat(i).height
                    Choice(if (h > 0) "${h}p" else "Track ${i + 1}", override?.trackIndices?.contains(i) == true) {
                        player.trackSelectionParameters = params.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                    }
                }
            if (tracks.size <= 1) tracks.ifEmpty { listOf(auto) } else listOf(auto) + tracks
        }
        Menu.SUBTITLES -> {
            val off = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            if (groups.isEmpty()) return emptyList()
            listOf(Choice("Off", off) {
                player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }) + groups.flatMapIndexed { gi, g ->
                (0 until g.length).map { i ->
                    val f = g.getTrackFormat(i)
                    val name = f.label ?: f.language?.let { java.util.Locale(it).displayLanguage } ?: "Subtitles ${gi + 1}"
                    Choice(name, !off && g.isTrackSelected(i)) {
                        player.trackSelectionParameters = params.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
        }
    }
}
