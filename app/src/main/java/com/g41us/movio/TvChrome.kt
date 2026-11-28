package com.g41us.movio

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Width of the TV menu when folded to icons; pages start to the right of it. */
val TvRailWidth = 76.dp

private class RailItem(val label: String, val icon: Int, val active: Boolean, val onClick: () -> Unit) {
    lateinit var focus: androidx.compose.ui.focus.FocusRequester
}

/**
 * TV menu down the left edge: soft icons grouped in the middle. When the remote moves onto it (◀),
 * it opens to show names; ▶ goes back to the page.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvRail(tab: Tab, top: Screen?, onTab: (Tab) -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit, open: Boolean, onOpen: (Boolean) -> Unit, onRight: () -> Unit) {
    val w by animateDpAsState(if (open) 270.dp else TvRailWidth, tween(220), label = "rail")
    val items = listOf(
        RailItem("Home", R.drawable.ic_home, top == null && tab == Tab.HOME) { onTab(Tab.HOME) },
        RailItem("Search", R.drawable.ic_search, top == null && tab == Tab.SEARCH) { onTab(Tab.SEARCH) },
        RailItem("Movies", R.drawable.ic_movies, top == null && tab == Tab.MOVIES) { onTab(Tab.MOVIES) },
        RailItem("TV Shows", R.drawable.ic_tv, top == null && tab == Tab.TV) { onTab(Tab.TV) },
        RailItem("Live TV", R.drawable.ic_live, top == null && tab == Tab.LIVE) { onTab(Tab.LIVE) },
        RailItem("My List", R.drawable.ic_bookmark, top == null && tab == Tab.LIST) { onTab(Tab.LIST) },
        RailItem("Downloads", R.drawable.ic_download, top is Screen.Downloads, onDownloads),
        RailItem("Settings", R.drawable.ic_settings, top is Screen.Settings, onSettings),
    )
    val reqs = remember { List(items.size) { androidx.compose.ui.focus.FocusRequester() } }
    items.forEachIndexed { i, it -> it.focus = reqs[i] }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Choosing an item opens that page and closes the menu (focus moves onto the page).
    val picked: (RailItem) -> Unit = { item -> item.onClick(); scope.launch { kotlinx.coroutines.delay(200); onRight(); kotlinx.coroutines.delay(250); onRight() } }
    Box(
        Modifier.fillMaxHeight().width(w)
            // Folded: no panel at all, just the icons over the picture. Open: a dark wash so the names read.
            .background(if (open) Brush.horizontalGradient(0f to Ink, .72f to Ink.copy(alpha = .94f), 1f to Color.Transparent)
                // Folded: just a soft shade behind the icons so they read over pictures. No panel, no edge.
                else Brush.horizontalGradient(0f to Ink.copy(alpha = .55f), .5f to Ink.copy(alpha = .3f), 1f to Color.Transparent))
            .onFocusChanged { onOpen(it.hasFocus) }
            .onPreviewKeyEvent { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionRight) { onRight(); true } else false
            }
            // ◀ into the menu lands on the page you're on, not whatever icon happens to be level with you.
            .focusProperties {
                @Suppress("DEPRECATION")
                enter = { (items.firstOrNull { it.active } ?: items.first()).focus }
            }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 14.dp)) {
            Spacer(Modifier.weight(1f))
            items.forEach { RailButton(it, open) { picked(it) } }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RailButton(item: RailItem, open: Boolean, onPick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        // The page you're on: a short white bar at the screen edge.
        if (item.active) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(22.dp).clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(Color.White))
        Row(
            Modifier.padding(start = 14.dp, end = if (open) 18.dp else 0.dp)
                .then(if (open) Modifier.fillMaxWidth() else Modifier.width(46.dp))
                .height(42.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                // The item you're on: a light glow that fades out to the right, no hard edge.
                .background(if (focused) Brush.horizontalGradient(0f to Color.White.copy(alpha = .34f), .6f to Color.White.copy(alpha = .1f), 1f to Color.Transparent)
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                .focusRequester(item.focus)
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPick)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = when { focused || item.active -> Color.White; else -> Color.White.copy(alpha = .5f) }
            Icon(painterResource(item.icon), null, tint = tint, modifier = Modifier.size(24.dp))
            if (open) {
                Spacer(Modifier.width(16.dp))
                Text(item.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (focused || item.active) Color.White else Soft,
                    style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Profile(open: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val initial = I18n.name.trim().firstOrNull()?.uppercase()
    Row(
        Modifier.padding(start = 14.dp, end = 12.dp).clip(RoundedCornerShape(26.dp))
            .background(if (focused) Color.White.copy(alpha = .14f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
            if (initial != null) Text(initial, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
            else Icon(painterResource(R.drawable.ic_settings), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (open) {
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(end = 8.dp)) {
                Text(I18n.name.ifBlank { "Profile" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(), maxLines = 1)
                val lang = I18n.langs.firstOrNull { it.code == I18n.lang }?.native ?: "English"
                val subs = if (I18n.subLang == "off") t("No subtitles") else I18n.langs.firstOrNull { it.code == I18n.subLang }?.english?.let { tf("Subtitles {1}", t(it)) } ?: ""
                Text("$lang · $subs", fontSize = 12.sp, color = Muted, style = jakarta(), maxLines = 1)
            }
        }
    }
}

/** The page area on TV: remembers what you were on, so coming back from the menu lands there. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.tvPage(r: androidx.compose.ui.focus.FocusRequester): Modifier =
    this.focusRequester(r).focusRestorer().focusGroup()

/**
 * TV rows: pictures fade away as they slide toward the menu, so nothing sits hard against the icons.
 * Drawn as a dark wash over the edge (one cheap rectangle) rather than an off-screen mask, so scrolling stays smooth.
 */
@androidx.compose.runtime.Composable
fun Modifier.tvEdgeFade(): Modifier {
    if (!LocalTv.current) return this
    val end = with(androidx.compose.ui.platform.LocalDensity.current) { Gutter.toPx() }
    val wash = remember(end) {
        Brush.horizontalGradient(0f to Ink, .25f to Ink.copy(alpha = .92f), .5f to Ink.copy(alpha = .66f), .75f to Ink.copy(alpha = .3f), 1f to Ink.copy(alpha = 0f), startX = 0f, endX = end)
    }
    return this.drawWithContent { drawContent(); drawRect(wash, size = androidx.compose.ui.geometry.Size(end, size.height)) }
}

/** Movio's logo and name, always at the top-left of the TV screen (outside the menu, so it's never cut off). */
@Composable
fun TvLogo() {
    // Only once the page has scrolled: a dark band so text moving up never runs into the logo.
    // At the top of a page the picture shows in full.
    val scrolled = TvScroll.target?.canScrollBackward == true
    androidx.compose.animation.AnimatedVisibility(scrolled, enter = androidx.compose.animation.fadeIn(tween(200)), exit = androidx.compose.animation.fadeOut(tween(200))) {
        Box(Modifier.fillMaxWidth().height(92.dp).background(Brush.verticalGradient(0f to Ink, .62f to Ink.copy(alpha = .9f), 1f to Color.Transparent)))
    }
    Box(Modifier.padding(start = 20.dp, top = 20.dp)) { Logo(size = 20, anchor = true) }
}

/** TV pages: the clock at the top-right (Movio's logo sits in the menu). */
@Composable
fun TvHeader() {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, end = 40.dp, bottom = 8.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        TvClock()
    }
}

@Composable
fun TvClock() {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    Text(android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(now)), fontSize = 18.sp, fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = .85f), style = jakarta())
}
