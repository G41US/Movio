package com.g41us.movio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// ---------- Brand ----------

@Composable
fun Logo(size: Int = 20, anchor: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_tile), null, Modifier.size((size * 1.5).dp)
            .then(if (anchor) Modifier.onGloballyPositioned { Boot.logo = it.boundsInRoot() } else Modifier))
        Spacer(Modifier.width(10.dp))
        Text("MOV", fontSize = size.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Color.White, style = jakarta())
        Text("IO", fontSize = size.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, style = jakarta(Brand))
    }
}

@Composable
fun GlassIcon(icon: Int, active: Boolean = false, size: Dp = 42.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (active) Brand else Brush.linearGradient(listOf(Glass, Glass)))
            .border(1.dp, if (active) Color.Transparent else Hairline, CircleShape)
            .focusGlow(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), null, tint = if (active) Color.White else Soft, modifier = Modifier.size(size * .5f)) }
}

/** Top bar over a scrim so it stays readable on hero artwork; sits below the status bar. */
@Composable
fun TopBar(title: String? = null, onBack: (() -> Unit)? = null, settingsActive: Boolean = false, tab: Tab? = null, onTab: ((Tab) -> Unit)? = null, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = .92f), Ink.copy(alpha = .55f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = Gutter, end = 14.dp, top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            GlassIcon(R.drawable.ic_back, size = 40.dp, onClick = onBack)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (title != null && !(LocalWide.current && onTab != null)) Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
            else Logo(anchor = true)
        }
        if (LocalWide.current && onTab != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab.entries.forEach { t ->
                    val active = t == tab
                    Text(t.label, fontSize = 16.sp, style = jakarta(), fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) Color.White else Muted,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) Brush.linearGradient(listOf(Violet.copy(alpha = .45f), Pink.copy(alpha = .35f))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                            .focusGlow(RoundedCornerShape(50)).clickable { onTab(t) }.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
        }
        GlassIcon(R.drawable.ic_settings, active = settingsActive, onClick = onSettings)
    }
}

enum class Tab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_home),
    MOVIES("Movies", R.drawable.ic_movies),
    TV("TV Shows", R.drawable.ic_tv),
    SEARCH("Search", R.drawable.ic_search),
    LIST("My List", R.drawable.ic_bookmark),
}

/** Floating frosted dock above the gesture bar; pages scroll underneath it. */
@Composable
fun Dock(tab: Tab?, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, .45f to Ink.copy(alpha = .85f), 1f to Ink))
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(66.dp)
                .shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1D2030), Color(0xFF12141D))))
                .border(1.dp, Hairline, shape)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t -> DockItem(t, t == tab, Modifier.weight(1f)) { onTab(t) } }
        }
    }
}

@Composable
private fun DockItem(t: Tab, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pill by animateDpAsState(if (active) 46.dp else 0.dp, label = "dock")
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).focusGlow(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(30.dp).width(50.dp)) {
            Box(Modifier.width(pill).height(30.dp).background(Brush.linearGradient(listOf(Violet.copy(alpha = .55f), Pink.copy(alpha = .45f))), CircleShape))
            Icon(painterResource(t.icon), null, tint = if (active) Color.White else Muted, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            t.label, fontSize = 10.sp, maxLines = 1, style = jakarta(),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) Color.White else Muted,
        )
    }
}

// ---------- Text & buttons ----------

@Composable
fun SectionTitle(text: String, caption: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp, color = Color.White, style = jakarta())
        if (caption != null) {
            Spacer(Modifier.width(10.dp))
            Text(caption, fontSize = 13.sp, color = Muted, style = jakarta(), modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
fun PrimaryButton(text: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).tappable(onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = Ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, style = jakarta(), maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, icon: Int?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .tappable(onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, style = jakarta(), maxLines = 1)
    }
}

@Composable
fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 14.sp, style = jakarta(),
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        color = if (active) Color.White else Soft,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (active) Brand else Brush.linearGradient(listOf(Glass, Glass)))
            .border(1.dp, if (active) Color.Transparent else Hairline, RoundedCornerShape(50))
            .focusGlow(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
fun Badge(text: String) {
    Text(
        text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = Color.White, style = jakarta(),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.copy(alpha = .5f))
            .border(1.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun Meta(t: Title, extra: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        rating(t.rating)?.let {
            Icon(painterResource(R.drawable.ic_star), null, tint = Gold, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta())
            Text("  ·  ", fontSize = 13.sp, color = Muted)
        }
        Text(listOfNotNull(t.year, if (t.kind == Kind.TV) "Series" else "Movie", extra).joinToString("  ·  "), fontSize = 13.sp, color = Soft, style = jakarta())
    }
}

// ---------- Cards ----------

@Composable
fun Poster(t: Title, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(14.dp))) {
        AsyncImage(Tmdb.img(t.poster, "w342"), t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        rating(t.rating)?.let {
            Row(
                Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(50)).background(Ink.copy(alpha = .72f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_star), null, tint = Gold, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta())
            }
        }
    }
}

@Composable
fun PosterCard(t: Title, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).tappable(onClick)) {
        Poster(t, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(t.name, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = Soft, style = jakarta(),
            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(t.year, if (t.kind == Kind.TV) "Series" else "Movie").joinToString("  ·  "), fontSize = 11.5.sp, color = Muted, style = jakarta(), maxLines = 1)
    }
}

@Composable
fun PosterRow(row: Row, onOpen: (Title) -> Unit) {
    SectionTitle(row.name)
    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(if (row.ranked) 4.dp else 12.dp)) {
        itemsIndexed(row.items, key = { _, t -> t.key }) { i, t ->
            if (row.ranked) RankedCard(i + 1, t) { onOpen(t) } else PosterCard(t, 118.dp) { onOpen(t) }
        }
    }
}

/** Top 10 card: a big chrome numeral tucked under the poster. */
@Composable
fun RankedCard(rank: Int, t: Title, onClick: () -> Unit) {
    Row(Modifier.tappable(onClick), verticalAlignment = Alignment.Bottom) {
        Text(
            rank.toString(), fontSize = 120.sp, lineHeight = 120.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-10).sp,
            style = jakarta(Brush.verticalGradient(0f to Color.White, .45f to Color(0xFFDCD6F5), .8f to Color(0xFF6E6891), 1f to Color(0xFF221F35))),
            modifier = Modifier.offset(x = 14.dp, y = 18.dp),
        )
        Poster(t, Modifier.width(112.dp))
    }
}

@Composable
fun ContinueCard(p: Progress, progress: Float? = null, onClick: () -> Unit) {
    Column(Modifier.width(220.dp).tappable(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(14.dp))) {
            AsyncImage(Tmdb.img(p.title.backdrop ?: p.title.poster, "w500"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Ink.copy(alpha = .85f))))
            Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_play_fill), null, tint = Ink, modifier = Modifier.size(20.dp))
            }
            if (p.title.kind == Kind.TV) Text(
                "S${p.season} · E${p.episode}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp),
            )
            if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .25f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Brand))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(p.title.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Soft, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Hero ----------

@Composable
fun Hero(items: List<Title>, saved: (Title) -> Boolean, onPlay: (Title) -> Unit, onOpen: (Title) -> Unit, onToggle: (Title) -> Unit) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(0) }
    LaunchedEffect(items) { while (items.size > 1) { delay(7000); index = (index + 1) % items.size } }
    val wide = LocalWide.current
    val heroH = if (wide) (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .66f).dp else 560.dp
    Box(Modifier.fillMaxWidth().height(heroH).clipToBounds()) {
        val arts = remember(items) { androidx.compose.runtime.mutableStateMapOf<String, Art>() }
        LaunchedEffect(items) { items.forEach { t -> if (t.key !in arts) arts[t.key] = Tmdb.art(t) } }
        AnimatedContent(items[index], transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "hero") { t ->
            Box(Modifier.fillMaxSize()) {
                val art = arts[t.key]
                val image = when {
                    wide -> Tmdb.img(t.backdrop ?: art?.image, "w1280")
                    art == null -> null
                    else -> Tmdb.img(art.image, if (art.wide) "w1280" else "w780")
                }
                if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().kenBurns())
                if (wide) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = .85f), .55f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = .35f), .3f to Color.Transparent, .62f to Ink.copy(alpha = .55f), 1f to Ink)))
            }
        }
        val t = items[index]
        Column(Modifier.align(Alignment.BottomStart).padding(start = if (wide) 48.dp else Gutter, end = Gutter, bottom = 18.dp)
            .then(if (wide) Modifier.width(560.dp) else Modifier).enterUp()) {
            Badge(if (t.kind == Kind.TV) "SERIES" else "MOVIE")
            Spacer(Modifier.height(12.dp))
            val logo = arts[t.key]?.logo
            if (logo != null) AsyncImage(Tmdb.img(logo, "w500"), t.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.width(260.dp).height(84.dp))
            else Text(t.name, fontSize = if (wide) 40.sp else 34.sp, lineHeight = if (wide) 44.sp else 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Color.White,
                style = jakarta(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Meta(t)
            if (wide && t.overview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(t.overview, fontSize = 14.sp, lineHeight = 20.sp, color = Soft, style = jakarta(), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Play", R.drawable.ic_play_fill, Modifier.weight(1f)) { onPlay(t) }
                GhostButton(if (saved(t)) "Saved" else "My List", if (saved(t)) R.drawable.ic_check else R.drawable.ic_plus) { onToggle(t) }
                GlassIcon(R.drawable.ic_info, size = 50.dp) { onOpen(t) }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                items.indices.forEach { i ->
                    val w by animateDpAsState(if (i == index) 22.dp else 6.dp, label = "dot")
                    Box(Modifier.padding(horizontal = 3.dp).width(w).height(6.dp).clip(CircleShape)
                        .background(if (i == index) Brand else Brush.linearGradient(listOf(Hairline, Hairline))))
                }
            }
        }
    }
}

// ---------- States ----------

@Composable
fun RowSkeleton() {
    Column {
        Box(Modifier.padding(start = Gutter, top = 28.dp, bottom = 12.dp).width(160.dp).height(18.dp).shimmer(RoundedCornerShape(6.dp)))
        Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { Box(Modifier.width(118.dp).aspectRatio(2f / 3f).shimmer()) }
        }
    }
}

@Composable
fun HeroSkeleton() {
    Box(Modifier.fillMaxWidth().height(560.dp).shimmer(RoundedCornerShape(0.dp)))
}

@Composable
fun Message(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
        Spacer(Modifier.height(8.dp))
        Text(body, fontSize = 14.sp, color = Muted, style = jakarta(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action, R.drawable.ic_check, onClick = onAction)
        }
    }
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B1E2C), Color(0xFF12141D))))
            .border(1.dp, Hairline, RoundedCornerShape(22.dp)).padding(20.dp),
    ) { content() }
}

@Composable
fun Label(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Violet, style = jakarta())
}

@Suppress("unused")
@Composable
fun BoxScope.Scrim() = Box(Modifier.matchParentSize().background(Ink.copy(alpha = .6f)))
