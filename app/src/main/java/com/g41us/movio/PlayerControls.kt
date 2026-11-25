package com.g41us.movio

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Everything the controls need from the player screen. */
interface PlayerHost {
    val label: String
    val source: String
    val isTv: Boolean
    val season: Int
    val episode: Int
    val episodes: List<Episode>
    val hasNext: Boolean
    val aspectName: String
    val downloadLabel: String?
    val subSize: Int
    val subBox: Boolean
    val subOffset: Long
    val autoplay: Boolean
    fun back()
    fun next()
    fun web()
    fun sources()
    fun download()
    fun playEpisode(e: Int)
    fun cycleAspect()
    fun brightness(delta: Float): Float
    fun volume(delta: Float): Float
    fun setSubSize(i: Int)
    fun setSubBox(b: Boolean)
    fun setSubOffset(ms: Long)
}

private data class Choice(val label: String, val selected: Boolean, val apply: () -> Unit)

private enum class Sheet { QUALITY, SPEED, SUBTITLES, EPISODES }

fun clock(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Movio's own player controls, drawn over the video. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControls(player: Player, host: PlayerHost, visible: Boolean, onVisible: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var panel by remember { mutableStateOf<Sheet?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var gauge by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    var locked by remember { mutableStateOf(false) }
    var boosting by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var countdown by remember { mutableIntStateOf(-1) }
    var countdownDismissed by remember(host.episode) { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (!dragging) position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            if (visible && panel == null && player.isPlaying && System.currentTimeMillis() - lastTouch > 3500) onVisible(false)
            // Up-next countdown over the last 20 seconds of an episode.
            val left = duration - position
            countdown = if (host.isTv && host.hasNext && host.autoplay && !countdownDismissed && duration > 0 &&
                (left in 1..20_000 || player.playbackState == Player.STATE_ENDED)) (left / 1000).toInt().coerceAtLeast(0) else -1
            if (countdown == 0) { countdownDismissed = true; host.next() }
            delay(250)
        }
    }
    LaunchedEffect(hint) { if (hint != null) { delay(700); hint = null } }
    LaunchedEffect(gauge) { if (gauge != null) { delay(900); gauge = null } }

    fun touch() { lastTouch = System.currentTimeMillis() }
    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE))
        hint = if (ms > 0) "+${ms / 1000}s" else "−${-ms / 1000}s"
        touch()
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(locked, visible, panel) {
                if (locked) { detectTapGestures(onTap = { onVisible(!visible) }); return@pointerInput }
                var job: Job? = null
                detectTapGestures(
                    onTap = { if (panel != null) panel = null else { onVisible(!visible); touch() } },
                    onDoubleTap = { o -> seekBy(if (o.x < size.width / 2) -10_000 else 10_000) },
                    onPress = {
                        job = scope.launch {
                            delay(550)
                            boosting = true
                            player.setPlaybackSpeed(2f)
                        }
                        tryAwaitRelease()
                        job?.cancel()
                        if (boosting) { boosting = false; player.setPlaybackSpeed(1f) }
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                // Vertical: brightness (left) / volume (right). Horizontal: scrub.
                var mode = 0
                var side = 0
                var startPos = 0L
                var scrubTo = 0L
                detectDragGestures(
                    onDragStart = { o -> mode = 0; startPos = player.currentPosition; scrubTo = startPos; side = if (o.x < size.width / 2) 1 else 2 },
                    onDragEnd = { if (mode == 3) { player.seekTo(scrubTo); dragging = false }; mode = 0 },
                    onDragCancel = { mode = 0; dragging = false },
                ) { change, d ->
                    if (mode == 0) mode = if (abs(d.x) > abs(d.y)) 3 else side
                    when (mode) {
                        1 -> gauge = 1 to host.brightness(-d.y / size.height * 1.6f)
                        2 -> gauge = 2 to host.volume(-d.y / size.height * 1.6f)
                        3 -> if (duration > 0) {
                            scrubTo = (scrubTo + (d.x / size.width * 180_000).toLong()).coerceIn(0, duration)
                            dragging = true; dragValue = scrubTo.toFloat() / duration
                            hint = "${clock(scrubTo)}  (${if (scrubTo >= startPos) "+" else "−"}${clock(abs(scrubTo - startPos))})"
                        }
                    }
                    change.consume()
                }
            },
    ) {
        if (buffering) CircularProgressIndicator(color = Violet, trackColor = Glass, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(46.dp))
        hint?.let { Bubble(it, Modifier.align(Alignment.Center)) }
        if (boosting) Bubble("2× speed", Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 18.dp))
        gauge?.let { (kind, v) ->
            Bubble("${if (kind == 1) "Brightness" else "Volume"}  ${(v * 100).toInt()}%", Modifier.align(Alignment.Center))
        }

        if (locked) {
            AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                Box(Modifier.safeDrawingPadding().padding(24.dp)) {
                    GhostButton("Unlock", R.drawable.ic_lock) { locked = false; touch() }
                }
            }
        } else AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .72f), .3f to Color.Transparent, .62f to Color.Transparent, 1f to Color.Black.copy(alpha = .82f)))) {
                // Top
                Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIcon(R.drawable.ic_back, size = 44.dp) { host.back() }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(host.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val ends = if (duration > 0) " · Ends at " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + duration - position)) else ""
                        Text(host.source + ends, color = Muted, fontSize = 12.sp, style = jakarta(), maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GhostButton("Sources", R.drawable.ic_servers) { host.sources(); touch() }
                        GlassIcon(R.drawable.ic_cc, active = panel == Sheet.SUBTITLES, size = 44.dp) { panel = Sheet.SUBTITLES; touch() }
                        GlassIcon(R.drawable.ic_speed, active = panel == Sheet.SPEED, size = 44.dp) { panel = Sheet.SPEED; touch() }
                        GlassIcon(R.drawable.ic_settings, active = panel == Sheet.QUALITY, size = 44.dp) { panel = Sheet.QUALITY; touch() }
                        GlassIcon(R.drawable.ic_lock, size = 44.dp) { locked = true; panel = null; touch() }
                    }
                }
                // Centre
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    SeekButton(R.drawable.ic_back10) { seekBy(-10_000) }
                    Box(
                        Modifier.size(78.dp).clip(CircleShape).background(Color.White).focusGlow(CircleShape).clickable {
                            if (player.isPlaying) player.pause() else player.play(); touch()
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_fill), null, tint = Ink, modifier = Modifier.size(34.dp)) }
                    SeekButton(R.drawable.ic_fwd10) { seekBy(10_000) }
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
                        Text("−" + clock(duration - position), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = jakarta())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (host.isTv && host.hasNext) GhostButton("Next episode", R.drawable.ic_next) { host.next() }
                        if (host.isTv && host.episodes.isNotEmpty()) GhostButton("Episodes", R.drawable.ic_list) { panel = Sheet.EPISODES; touch() }
                        Spacer(Modifier.weight(1f))
                        GhostButton(host.aspectName, R.drawable.ic_fit) { host.cycleAspect(); touch() }
                        GhostButton(host.downloadLabel ?: "Download", R.drawable.ic_download) { host.download(); touch() }
                        GhostButton("Web", R.drawable.ic_web) { host.web() }
                    }
                }
            }
        }

        // Skip intro: shown early in episodes, even when controls are hidden.
        if (!locked && host.isTv && position in 20_000..240_000 && countdown < 0 && panel == null) {
            Box(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 22.dp, bottom = if (visible) 120.dp else 28.dp)) {
                PrimaryButton("Skip intro", R.drawable.ic_next) { seekBy(85_000) }
            }
        }

        // Up next
        AnimatedVisibility(countdown >= 0, modifier = Modifier.align(Alignment.BottomEnd), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Column(
                Modifier.safeDrawingPadding().padding(22.dp).width(300.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF2101219))
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp)).padding(16.dp),
            ) {
                Label("Up next")
                Spacer(Modifier.height(6.dp))
                val nextEp = host.episodes.firstOrNull { it.number == host.episode + 1 }
                Text(nextEp?.let { "E${it.number} · ${it.name}" } ?: "Next episode", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    style = jakarta(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Play in ${countdown.coerceAtLeast(0)}", R.drawable.ic_play_fill) { countdownDismissed = true; host.next() }
                    GhostButton("Cancel", null) { countdownDismissed = true }
                }
            }
        }

        // Side panels
        AnimatedVisibility(
            panel != null, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val p = panel ?: Sheet.SPEED
            Column(Modifier.fillMaxHeight().width(if (p == Sheet.EPISODES) 360.dp else 300.dp).background(Color(0xF2101219)).safeDrawingPadding().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(when (p) { Sheet.QUALITY -> "Quality"; Sheet.SPEED -> "Playback speed"; Sheet.SUBTITLES -> "Subtitles"; Sheet.EPISODES -> "Season ${host.season}" })
                    Spacer(Modifier.weight(1f))
                    GlassIcon(R.drawable.ic_close, size = 34.dp) { panel = null }
                }
                Spacer(Modifier.height(10.dp))
                when (p) {
                    Sheet.EPISODES -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(host.episodes, key = { it.number }) { e ->
                            val current = e.number == host.episode
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (current) Violet.copy(alpha = .25f) else Color.Transparent)
                                    .focusGlow(RoundedCornerShape(12.dp)).clickable { panel = null; host.playEpisode(e.number) }.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Panel)) {
                                    AsyncImage(Tmdb.img(e.still, "w300"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${e.number}. ${e.name}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = jakarta(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (current) "Watching" else e.runtime?.let { "${it}m" } ?: "", color = if (current) Violet else Muted, fontSize = 11.sp, style = jakarta())
                                }
                            }
                        }
                    }
                    Sheet.SUBTITLES -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Options(choices(player, Sheet.SUBTITLES)) { panel = null; touch() }
                        Spacer(Modifier.height(14.dp))
                        Label("Size")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("S", "M", "L", "XL").forEachIndexed { i, l -> Chip(l, host.subSize == i) { host.setSubSize(i) } }
                        }
                        Spacer(Modifier.height(14.dp))
                        Label("Background")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("Box", host.subBox) { host.setSubBox(true) }
                            Chip("Outline", !host.subBox) { host.setSubBox(false) }
                        }
                        Spacer(Modifier.height(14.dp))
                        Label("Timing")
                        Spacer(Modifier.height(4.dp))
                        Text("Subtitles too early? Press +. Too late? Press −.", color = Muted, fontSize = 12.sp, style = jakarta())
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("−0.5s", null) { host.setSubOffset(host.subOffset - 500) }
                            Text("%+.1fs".format(host.subOffset / 1000f), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = jakarta())
                            GhostButton("+0.5s", null) { host.setSubOffset(host.subOffset + 500) }
                        }
                        if (host.subOffset != 0L) {
                            Spacer(Modifier.height(6.dp))
                            Text("Reset", color = Violet, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = jakarta(), modifier = Modifier.clickable { host.setSubOffset(0) }.padding(6.dp))
                        }
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) { Options(choices(player, p)) { panel = null; touch() } }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, modifier: Modifier) {
    Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, style = jakarta(),
        modifier = modifier.clip(RoundedCornerShape(50)).background(Ink.copy(alpha = .7f)).padding(horizontal = 18.dp, vertical = 10.dp))
}

@Composable
private fun SeekButton(icon: Int, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        GlassIcon(icon, size = 56.dp, onClick = onClick)
        Text("10", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = jakarta(), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Options(choices: List<Choice>, onPicked: () -> Unit) {
    if (choices.isEmpty()) Text("Not available for this video", color = Muted, fontSize = 14.sp, style = jakarta())
    choices.forEach { c ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                .background(if (c.selected) Violet.copy(alpha = .28f) else Color.Transparent)
                .border(1.dp, if (c.selected) Violet.copy(alpha = .6f) else Color.Transparent, RoundedCornerShape(12.dp))
                .focusGlow(RoundedCornerShape(12.dp))
                .clickable { c.apply(); onPicked() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.label, color = Color.White, fontSize = 15.sp, fontWeight = if (c.selected) FontWeight.Bold else FontWeight.Medium, style = jakarta(), modifier = Modifier.weight(1f))
            if (c.selected) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(UnstableApi::class)
private fun choices(player: Player, panel: Sheet): List<Choice> {
    val params = player.trackSelectionParameters
    return when (panel) {
        Sheet.SPEED -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { s ->
            Choice(if (s == 1f) "Normal" else "${s}x", player.playbackParameters.speed == s) { player.setPlaybackSpeed(s) }
        }
        Sheet.QUALITY -> {
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
                        player.trackSelectionParameters = params.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                    }
                }
            if (tracks.size <= 1) tracks.ifEmpty { listOf(auto) } else listOf(auto) + tracks
        }
        Sheet.SUBTITLES -> {
            val off = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            if (groups.isEmpty()) return emptyList()
            listOf(Choice("Off", off) {
                player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }) + groups.flatMapIndexed { gi, g ->
                (0 until g.length).map { i ->
                    val f = g.getTrackFormat(i)
                    val name = f.label ?: f.language?.let { Locale(it).displayLanguage } ?: "Subtitles ${gi + 1}"
                    Choice(name, !off && g.isTrackSelected(i)) {
                        player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }.distinctBy { it.label }
        }
        Sheet.EPISODES -> emptyList()
    }
}
