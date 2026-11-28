package com.g41us.movio

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Space under the floating dock so the last row is never hidden behind it. */
@Composable
fun dockSpace(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (LocalWide.current) 32.dp else 112.dp

/** Space under the top bar for screens without a hero. */
@Composable
fun barSpace(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 70.dp

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

@Composable
private fun <T> load(key: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var attempt by remember { mutableIntStateOf(0) }
    @Suppress("UNCHECKED_CAST") val pre = LocalPreload.current[key] as T?
    var state by remember(key) { mutableStateOf<Load<T>>(if (pre != null) Load.Done(pre) else Load.Loading) }
    LaunchedEffect(key, attempt) {
        if (pre != null && attempt == 0) return@LaunchedEffect
        state = Load.Loading
        state = try { Load.Done(block()) } catch (e: Exception) { Load.Failed(e.message ?: "Something went wrong") }
    }
    return state to { attempt++ }
}

@Composable
private fun Failed(retry: () -> Unit) =
    Message("Can't load right now", "Check your internet connection and try again.", "Try again", retry)

// ---------- Home / Movies / TV ----------

@Composable
fun FeedScreen(kind: Kind?, store: Store, onOpen: (Title) -> Unit, onPlay: (Title) -> Unit, onPlatform: (Int) -> Unit = {}, onDownloads: () -> Unit = {}) {
    var genre by remember(kind) { mutableStateOf<Genre?>(null) }
    val (state, retry) = load(kind to genre) {
        if (kind != null) Tmdb.browse(kind, genre) else {
            val rows = Tmdb.home()
            val watched = store.continueWatching.map { it.title.key }.toSet()
            val byw = Tmdb.becauseYouWatched(store.continueWatching.map { it.title } + store.myList, watched)
            // Top 10, New in cinemas, Trending, then "Because you watched", then the rest.
            rows.take(Tmdb.PERSONAL_AFTER) + byw + rows.drop(Tmdb.PERSONAL_AFTER)
        }
    }
    LaunchedEffect(state) { if (state !is Load.Loading) Boot.ready = true }
    // Get the titles you're most likely to play ready ahead of time.
    LaunchedEffect(Unit) { store.continueWatching.take(3).forEach { Prefetch.title(it.title, it.season, it.episode) } }
    val tv1 = rememberScrollState()
    ScrollList(tv1, PaddingValues(bottom = dockSpace())) {
        when (val s = state) {
            Load.Loading -> {
                item { HeroSkeleton() }
                items(3) { RowSkeleton() }
            }
            is Load.Failed -> {
                item { Spacer(Modifier.height(barSpace())) }
                item { Message("You're offline", "Connect to the internet to browse, or watch something you've downloaded.", "Open Downloads", onDownloads) }
                item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GhostButton("Try again", R.drawable.ic_refresh, onClick = retry) } }
            }
            is Load.Done -> {
                val rows = s.value
                item(key = "hero") {
                    Hero(
                        (rows.firstOrNull { it.name.startsWith("Trending") } ?: rows.firstOrNull())?.items?.filter { it.overview.isNotBlank() }?.take(6) ?: emptyList(),
                        saved = { store.inList(it) }, onPlay = onPlay, onOpen = onOpen, onToggle = { store.toggle(it) },
                    )
                }
                if (kind != null) item(key = "genres") {
                    LazyRow(Modifier.padding(top = 18.dp).tvEdgeFade(), contentPadding = PaddingValues(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Chip("All", genre == null) { genre = null } }
                        items(if (kind == Kind.MOVIE) Tmdb.movieGenres else Tmdb.tvGenres) { g -> Chip(g.name, genre == g) { genre = g } }
                    }
                }
                if (kind == null && store.continueWatching.isNotEmpty()) item(key = "continue") {
                    // The first thing you'd continue is found ahead of time, so it starts at once.
                    LaunchedEffect(store.continueWatching.first().title.key) {
                        val p = store.continueWatching.first()
                        kotlinx.coroutines.delay(1500)
                        Source.warmUp("Club", p.title.kind, p.title.id, if (p.title.kind == Kind.TV) p.season else 1, if (p.title.kind == Kind.TV) p.episode else 1)
                    }
                    SectionTitle("Continue watching")
                    LazyRow(Modifier.tvEdgeFade(), contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = if (LocalTv.current) 12.dp else 0.dp, bottom = if (LocalTv.current) 8.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (LocalWide.current) 18.dp else 12.dp)) {
                        items(store.continueWatching, key = { it.title.key }) { p ->
                            val pos = store.position(store.posKey(p.title, p.season, p.episode))
                            // a < 0 means that episode was finished: no "time left", it'll play on from the next one.
                            ContinueCard(p, pos?.let { (a, b) -> if (b > 0 && a >= 0) a.toFloat() / b else null }, pos?.let { (a, b) -> (b - a).takeIf { b > 0 && a >= 0 && it > 0 } }) { onOpen(p.title); onPlay(p.title) }  // leaving the player lands on its page, not Home
                        }
                    }
                }
                if (kind == null && !Tmdb.kids) item(key = "platforms") { PlatformRow(onPlatform) }
                rows.forEachIndexed { i, row ->
                    item(key = "row$i${row.name}") { Box(Modifier.enterUp(i)) { Column { PosterRow(row, onOpen) } } }
                }
            }
        }
    }
}

// ---------- Platforms ----------

@Composable
private fun PlatformRow(onPlatform: (Int) -> Unit) {
    val tv = LocalTv.current
    SectionTitle("Platforms")
    LazyRow(Modifier.tvEdgeFade(), contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = if (tv) 12.dp else 4.dp, bottom = if (tv) 12.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(if (LocalWide.current) 16.dp else 12.dp)) {
        items(Tmdb.PLATFORMS, key = { it.id }) { p -> if (tv) PlatformCard(p) { onPlatform(p.id) } else PlatformTile(p) { onPlatform(p.id) } }
    }
}

/** TV: a slim card, logo and name inside, the same width as a poster; the highlighted one turns white. */
@Composable
private fun PlatformCard(p: Tmdb.Platform, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    val k by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.04f else 1f, label = "pc")
    Row(
        Modifier.width(TvCard).height(58.dp).graphicsLayer { scaleX = k; scaleY = k }
            .onFocusChanged { focused = it.isFocused }.clickableNoRipple(onClick)
            .softRing(if (focused) .7f else 0f, shape)
            .clip(shape).background(if (focused) Color.White else Color(0xFF15161E))
            .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = .08f), shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetImage(Tmdb.img(p.logo, "w92"), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (focused) Ink else Soft, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Phone: a wide dark tile with the logo, name underneath. */
@Composable
private fun PlatformTile(p: Tmdb.Platform, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    // The Sunset glow only shows while the tile is pressed (or highlighted with a keyboard/remote).
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val on = pressed || focused
    Column(Modifier.width(150.dp).onFocusChanged { focused = it.hasFocus }.clickable(interactionSource = src, indication = null, onClick = onClick)) {
        Box(
            Modifier.size(150.dp, 84.dp).then(if (on) Modifier.shadow(12.dp, shape, ambientColor = Violet, spotColor = Violet) else Modifier)
                .background(Brush.linearGradient(listOf(Color(0xFF1A1B25), Color(0xFF0F1017))), shape)
                .border(1.dp, if (on) Violet.copy(alpha = .8f) else Color.White.copy(alpha = .08f), shape),
            contentAlignment = Alignment.Center,
        ) { NetImage(Tmdb.img(p.logo, "w154"), null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Soft, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlatformScreen(id: Int, onOpen: (Title) -> Unit) {
    val p = Tmdb.PLATFORMS.firstOrNull { it.id == id } ?: Tmdb.PLATFORMS[0]
    var kind by rememberSaveable { mutableStateOf(Kind.TV) }
    var sort by rememberSaveable { mutableStateOf("popular") }
    val items = remember(kind, sort) { mutableStateListOf<Title>() }
    var page by remember(kind, sort) { mutableIntStateOf(1) }
    var more by remember(kind, sort) { mutableStateOf(true) }
    var loading by remember(kind, sort) { mutableStateOf(false) }
    var failed by remember(kind, sort) { mutableStateOf(false) }
    LaunchedEffect(kind, sort, page) {
        loading = true
        runCatching { Tmdb.platform(p.id, kind, sort, page) }
            .onSuccess { (got, has) -> items.addAll(got.filter { t -> items.none { it.key == t.key } }); more = has && got.isNotEmpty(); failed = false }
            .onFailure { failed = true }
        loading = false
    }
    val grid = rememberLazyGridState()
    // More as you reach the bottom.
    LaunchedEffect(grid, kind, sort) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!loading && more && items.isNotEmpty() && last >= grid.layoutInfo.totalItemsCount - 6) page++
        }
    }
    val tvBar = LocalTopBar.current
    LazyVerticalGrid(
        GridCells.Adaptive(if (LocalTv.current) TvCard - 16.dp else if (LocalWide.current) 200.dp else 108.dp), Modifier.fillMaxSize().dpadScroll(grid), state = grid,
        contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = if (tvBar != null) 0.dp else barSpace(), bottom = dockSpace()),
        horizontalArrangement = Arrangement.spacedBy(if (LocalTv.current) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (LocalTv.current) 6.dp else 18.dp),
    ) {
        if (tvBar != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(Gutter, GutterEnd)) { tvBar() } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NetImage(Tmdb.img(p.logo, "w154"), null, Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        androidx.compose.material3.Text(p.name, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
                        Text("Everything here plays in Movio.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted, style = jakarta())
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(t("Shows"), kind == Kind.TV, Modifier.tvFirstFocus(p.id)) { kind = Kind.TV }
                    Chip(t("Movies"), kind == Kind.MOVIE) { kind = Kind.MOVIE }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("popular" to "Popular", "new" to "New", "top" to "Top rated").forEach { (k, l) -> Chip(t(l), sort == k) { sort = k } }
                }
            }
        }
        items(items, key = { it.key }) { t -> PosterCard(t, 108.dp) { onOpen(t) } }
        if (loading) items(if (items.isEmpty()) 9 else 3) { Box(Modifier.aspectRatio(2f / 3f).shimmer()) }
        if (failed && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Failed { page = 1; failed = false; loading = false } }
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen(onOpen: (Title) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { Store.get(context) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val tv = LocalTv.current
    LaunchedEffect(Unit) { if (!tv) { delay(250); runCatching { focus.requestFocus() } } }
    LaunchedEffect(query) { delay(450); submitted = query.trim() }
    val filtering = cat.isNotEmpty() || year.isNotEmpty()
    val (state, retry) = load(Triple(submitted, cat, year)) {
        if (submitted.length < 2 && !filtering) emptyList() else Tmdb.find(submitted, Tmdb.CATS.find { it.id == cat }, year.ifEmpty { null })
    }

    val tv2 = rememberLazyGridState()
    val tvBar = LocalTopBar.current
    LazyVerticalGrid(
        GridCells.Adaptive(if (LocalTv.current) TvCard - 16.dp else if (LocalWide.current) 200.dp else 108.dp), Modifier.fillMaxSize().dpadScroll(tv2), state = tv2,
        contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = if (tvBar != null) 0.dp else barSpace(), bottom = dockSpace()),
        horizontalArrangement = Arrangement.spacedBy(if (LocalTv.current) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (LocalTv.current) 6.dp else 18.dp),
    ) {
        if (tvBar != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(Gutter, GutterEnd)) { tvBar() } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(Glass)
                    .border(1.dp, Edge, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Movies, shows, people…", color = Muted, fontSize = 16.sp, style = jakarta())
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = Jakarta),
                        cursorBrush = SolidColor(Violet),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitted = query.trim(); store.rememberSearch(query); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) GlassIcon(R.drawable.ic_close, size = 30.dp) { query = "" }
            }
        }
        // Kind of film, then year.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.bleed(Gutter, GutterEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(Modifier.tvEdgeFade(), contentPadding = PaddingValues(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip(t("All"), cat.isEmpty()) { cat = "" } }
                    items(Tmdb.CATS, key = { it.id }) { c -> Chip(t(c.name), cat == c.id) { cat = c.id } }
                }
                LazyRow(Modifier.tvEdgeFade(), contentPadding = PaddingValues(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip(t("Any year"), year.isEmpty()) { year = "" } }
                    items(Tmdb.YEARS, key = { it }) { y -> Chip(t(y), year == y) { year = y } }
                }
            }
        }
        if (query.isBlank() && store.searches.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(), modifier = Modifier.weight(1f))
                    Text("Clear", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Violet, style = jakarta(),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).focusGlow(RoundedCornerShape(8.dp)).clickable { store.forgetSearch(null) }.padding(8.dp))
                }
                store.searches.toList().forEach { q ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).focusGlow(RoundedCornerShape(10.dp), grow = false)
                        .clickable { query = q; submitted = q; store.rememberSearch(q) }.padding(vertical = 11.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_clock), null, tint = Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.material3.Text(q, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = jakarta(), modifier = Modifier.weight(1f))
                        if (!tv) Icon(painterResource(R.drawable.ic_close), null, tint = Muted, modifier = Modifier.size(16.dp).clickable { store.forgetSearch(q) })
                    }
                }
            }
        }
        when (val s = state) {
            Load.Loading -> if (submitted.length >= 2 || filtering) items(9) { Box(Modifier.aspectRatio(2f / 3f).shimmer()) }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Failed(retry) }
            is Load.Done -> when {
                submitted.length < 2 && !filtering -> item(span = { GridItemSpan(maxLineSpan) }) {
                    if (store.searches.isEmpty()) Message("Find something to watch", "Movies, shows and people.")
                }
                s.value.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Message("No results", if (filtering) "Try another year or category." else "Try a different title.")
                }
                else -> items(s.value, key = { it.key }) { t -> PosterCard(t, 108.dp) { if (submitted.length >= 2) store.rememberSearch(submitted); onOpen(t) } }
            }
        }
    }
}

// ---------- My List ----------

@Composable
fun MyListScreen(store: Store, onOpen: (Title) -> Unit, onDownloads: () -> Unit) {
    val tv3 = rememberLazyGridState()
    var likedTab by rememberSaveable { mutableStateOf(false) }
    val tvBar = LocalTopBar.current
    LazyVerticalGrid(
        GridCells.Adaptive(if (LocalTv.current) TvCard - 16.dp else if (LocalWide.current) 200.dp else 108.dp), Modifier.fillMaxSize().dpadScroll(tv3), state = tv3,
        contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = if (tvBar != null) 0.dp else barSpace(), bottom = dockSpace()),
        horizontalArrangement = Arrangement.spacedBy(if (LocalTv.current) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (LocalTv.current) 6.dp else 18.dp),
    ) {
        if (tvBar != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(Gutter, GutterEnd)) { tvBar() } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("${t("Saved")} · ${store.myList.size}", !likedTab) { likedTab = false }
                Chip("${t("Liked")} · ${store.liked.size}", likedTab) { likedTab = true }
                Spacer(Modifier.weight(1f))
                GhostButton("Downloads", R.drawable.ic_download, onClick = onDownloads)
            }
        }
        val shown = if (likedTab) store.liked else store.myList
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            if (likedTab) Message("Nothing liked yet", "Tap ♥ Like on any title to keep it here.")
            else Message("Your list is empty", "Add movies and shows to watch later.")
        } else items(shown, key = { it.key }) { t -> PosterCard(t, 108.dp) { onOpen(t) } }
    }
}

// ---------- Details ----------

@Composable
private fun RowScope.ActionTile(icon: Int, label: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, tint = if (on) tint else Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Soft, style = jakarta())
    }
}

/** Share a link that opens the title in Movio's web app. */
fun shareTitle(ctx: android.content.Context, t: Title) {
    val url = "https://g41us.github.io/Movio/#/title/${t.kind.api}/${t.id}"
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, t.name)
        .putExtra(android.content.Intent.EXTRA_TEXT, tf("Watch {1} on Movio", t.name) + "\n" + url)
    ctx.startActivity(android.content.Intent.createChooser(send, t("Share")))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(title: Title, store: Store, onOpen: (Title) -> Unit, onPerson: (Person) -> Unit, onTrailer: (String) -> Unit, onPlay: (Title, Int, Int) -> Unit) {
    val (state, retry) = load(title.key) { Tmdb.details(title) }
    val last = store.lastEpisode(title)
    // Find the stream while you read the page, so Play starts straight away.
    LaunchedEffect(title.key) {
        kotlinx.coroutines.delay(400)
        if (title.kind == Kind.TV) Source.warmUp("Club", Kind.TV, title.id, last?.season ?: 1, last?.episode ?: 1)
        else Source.warmUp("Club", Kind.MOVIE, title.id, 1, 1)
    }
    LaunchedEffect(title.key) { Prefetch.title(title, last?.season ?: 1, last?.episode ?: 1) }
    var season by remember(title.key) { mutableIntStateOf(last?.season ?: 1) }

    val tv4 = rememberScrollState()
    // TV: open at the top with the full hero; Play is already on screen, so don't scroll to it.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll) {
    ScrollList(tv4, PaddingValues(bottom = dockSpace())) {
        item(key = "top") {
            val d = (state as? Load.Done)?.value
            val t = d?.title ?: title
            val wide = LocalWide.current
            val artH = if (wide) (screenDp().height * .68f).coerceAtLeast(340.dp) else 500.dp
            Box(Modifier.fillMaxWidth().height(artH).clipToBounds()) {
                // Art without printed titles; while details load, show nothing rather than a poster with text.
                val image = when {
                    d == null -> null
                    wide && t.backdrop != null -> Tmdb.img(t.backdrop, "w1280")
                    d.cleanPoster != null -> Tmdb.img(d.cleanPoster, "w780")
                    else -> Tmdb.img(t.backdrop ?: t.poster, "w1280")
                }
                if (image != null) NetImage(image, null, contentScale = ContentScale.Crop, alignment = if (wide) androidx.compose.ui.BiasAlignment(0f, -0.75f) else Alignment.Center, modifier = Modifier.fillMaxSize().kenBurns())  // wide: keep the top of the picture (faces), the bottom goes under the fade
                if (wide) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = .85f), .6f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = .4f), .35f to Color.Transparent, .7f to Ink.copy(alpha = .7f), 1f to Ink)))
                Column(Modifier.align(Alignment.BottomStart).padding(start = Gutter, end = GutterEnd).enterUp()) {
                    if (d?.logo != null) NetImage(Tmdb.img(d.logo, "w500"), t.name, contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart, modifier = Modifier.width(260.dp).height(84.dp))
                    else Text(t.name, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Color.White, style = jakarta())
                    Spacer(Modifier.height(10.dp))
                    Meta(t, d?.runtime?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" })
                }
            }
        }
        when (val s = state) {
            Load.Loading -> item { Box(Modifier.padding(Gutter).fillMaxWidth().height(52.dp).shimmer()) }
            is Load.Failed -> item { Failed(retry) }
            is Load.Done -> {
                val d = s.value
                val t = d.title
                item(key = "actions") {
                    val wide = LocalWide.current
                    Column(Modifier.padding(start = Gutter, end = GutterEnd).padding(top = 18.dp).then(if (wide) Modifier.widthIn(max = 720.dp) else Modifier)) {
                        val label = when {
                            t.kind == Kind.TV && last != null -> "Resume S${last.season} E${last.episode}"
                            t.kind == Kind.TV -> "Play S1 E1"
                            else -> "Play"
                        }
                        val saved = store.inList(t)
                        val isLiked = store.isLiked(t)
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val like = { val on = store.toggleLike(t, d.genres); android.widget.Toast.makeText(ctx, t(if (on) "Liked" else "Removed from Liked"), android.widget.Toast.LENGTH_SHORT).show() }
                        if (wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(label, R.drawable.ic_play_fill, Modifier.widthIn(min = 170.dp).tvFirstFocus(t.key)) { onPlay(t, last?.season ?: 1, last?.episode ?: 1) }
                            GhostButton(if (saved) "In My List" else "My List", if (saved) R.drawable.ic_check else R.drawable.ic_plus) { store.toggle(t) }
                            GhostButton(if (isLiked) "Liked" else "Like", if (isLiked) R.drawable.ic_heart_fill else R.drawable.ic_heart) { like() }
                            if (d.trailer != null) GhostButton("Trailer", R.drawable.ic_trailer) { onTrailer(d.trailer) }
                            if (t.kind == Kind.MOVIE) { val dl = dlState(t, 1, 1); GhostButton(dlLabel(dl), if (dl == DL.DONE) R.drawable.ic_check else R.drawable.ic_download) { download(ctx, t, 1, 1) } }
                        } else {
                            PrimaryButton(label, R.drawable.ic_play_fill, Modifier.fillMaxWidth().tvFirstFocus(t.key)) {
                                onPlay(t, last?.season ?: 1, last?.episode ?: 1)
                            }
                            Spacer(Modifier.height(12.dp))
                            // Icon-over-label actions, like the iPhone app.
                            Row(Modifier.fillMaxWidth()) {
                                ActionTile(if (saved) R.drawable.ic_check else R.drawable.ic_plus, "My List", saved, Violet) { store.toggle(t) }
                                ActionTile(if (isLiked) R.drawable.ic_heart_fill else R.drawable.ic_heart, if (isLiked) "Liked" else "Like", isLiked, Pink) { like() }
                                ActionTile(R.drawable.ic_share, "Share", false, Color.White) { shareTitle(ctx, t) }
                                if (d.trailer != null) ActionTile(R.drawable.ic_trailer, "Trailer", false, Color.White) { onTrailer(d.trailer) }
                                if (t.kind == Kind.MOVIE) { val dl = dlState(t, 1, 1); ActionTile(if (dl == DL.DONE) R.drawable.ic_check else R.drawable.ic_download, dlLabel(dl), dl != DL.NONE, Color.White) { download(ctx, t, 1, 1) } }
                            }
                        }
                        if (d.genres.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Badge(d.genres.take(3).joinToString("  ·  ") { it.uppercase() })
                        }
                        if (d.tagline.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(d.tagline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = jakarta())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t.overview, fontSize = 14.5.sp, lineHeight = 21.sp, color = Soft, style = jakarta())

                    }
                }
                if (d.cast.isNotEmpty()) item(key = "cast") {
                    SectionTitle("Cast")
                    LazyRow(Modifier.tvEdgeFade(), contentPadding = PaddingValues(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(d.cast, key = { it.id }) { p -> CastCard(p) { onPerson(p) } }
                    }
                }
                if (t.kind == Kind.TV && d.seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val scope = rememberCoroutineScope()
                        SectionTitle("Episodes")
                        // Season: a dropdown; the download button next to it is always for the season shown.
                        Row(Modifier.padding(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SeasonPicker(d.seasons, season) { season = it }
                            DownloadPill(tf("Download season {1}", season)) {
                                val eps = d.seasons.firstOrNull { it.number == season }?.episodeCount ?: 0
                                val n = season
                                scope.launch {
                                    val list = runCatching { Tmdb.episodes(t.id, n).map { it.number } }.getOrNull() ?: (1..eps).toList()
                                    Downloads.fetchSeason(ctx, t, n, list)
                                    android.widget.Toast.makeText(ctx, tf("Downloading season {1} — see Downloads", n), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    item(key = "eps$season") { EpisodeList(t, season, last) { e -> onPlay(t, season, e) } }
                }
                if (d.similar.isNotEmpty()) item(key = "similar") { PosterRow(Row("More like this", d.similar), onOpen) }
            }
        }
    }
    }
}

@Composable
private fun EpisodeList(show: Title, season: Int, last: Progress?, onPlay: (Int) -> Unit) {
    val (state, retry) = load(show.id to season) { Tmdb.episodes(show.id, season) }
    val wide = LocalWide.current
    Column(Modifier.padding(start = Gutter, end = GutterEnd).then(if (wide) Modifier.widthIn(max = 900.dp) else Modifier)) {
        when (val s = state) {
            Load.Loading -> repeat(3) { Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(78.dp).shimmer()) }
            is Load.Failed -> Failed(retry)
            is Load.Done -> s.value.forEach { e ->
                val current = last?.season == season && last.episode == e.number
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).onFocusChanged { focused = it.hasFocus }.clip(RoundedCornerShape(16.dp))
                    .background(if (focused) Color.White.copy(alpha = .1f) else Color.Transparent), verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f).clickable(interactionSource = null, indication = null) { onPlay(e.number) }.padding(vertical = 6.dp, horizontal = if (wide) 8.dp else 0.dp),
                    verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(if (wide) 220.dp else 132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Panel).border(if (current) 1.5.dp else 1.dp, if (current) Violet else Edge, RoundedCornerShape(12.dp))) {
                        NetImage(Tmdb.img(e.still ?: show.backdrop, "w300"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.Center).size(32.dp).clip(RoundedCornerShape(50)).background(Ink.copy(alpha = .65f)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_play_fill), null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.number}. ${e.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (current) Color.White else Soft, style = jakarta(),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(listOfNotNull(e.runtime?.let { "${it}m" }, if (current) "Watching" else null).joinToString("  ·  ").ifBlank { "Episode ${e.number}" },
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (current) Violet else Muted, style = jakarta())
                        if (e.overview.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(e.overview, fontSize = if (wide) 14.sp else 12.5.sp, lineHeight = if (wide) 20.sp else 18.sp, color = Muted, style = jakarta(),
                                maxLines = if (wide) 3 else 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                    // Download this episode (tick when it's on the device, faded while it downloads).
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val dl = dlState(show, season, e.number)
                    var dlFocus by remember { mutableStateOf(false) }
                    Box(Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp).size(44.dp).onFocusChanged { dlFocus = it.isFocused }.clip(RoundedCornerShape(50))
                        .background(if (dlFocus) Color.White else Color.Transparent).clickable { download(ctx, show, season, e.number) },
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (dl == DL.DONE) R.drawable.ic_check else R.drawable.ic_download), t("Download"),
                            tint = if (dlFocus) Ink else if (dl == DL.DONE) Violet else Color.White.copy(alpha = if (dl == DL.RUNNING) .4f else .85f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ---------- Downloads from a title's page ----------

enum class DL { NONE, RUNNING, DONE }

/** "Season 2 · 10 episodes ▾": opens a dark sheet listing every season; the one you're on is ticked. */
@Composable
fun SeasonPicker(seasons: List<Season>, season: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = seasons.firstOrNull { it.number == season }
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White).focusGlow(RoundedCornerShape(50)).clickable { open = true }
        .padding(start = 18.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(cur?.name?.takeIf { it.isNotBlank() && !it.startsWith("Season") } ?: tf("Season {1}", season), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, style = jakarta())
        if (cur != null && cur.episodeCount > 0) Text("  ·  " + tf("{1} episodes", cur.episodeCount), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.copy(alpha = .55f), style = jakarta())
        Spacer(Modifier.width(6.dp))
        Icon(painterResource(R.drawable.ic_chevron_down), null, tint = Ink, modifier = Modifier.size(18.dp))
    }
    if (open) androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xF2121319))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(24.dp)).padding(vertical = 14.dp)) {
            Text(t("Seasons"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, style = jakarta(), modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(seasons.size) { i ->
                    val se = seasons[i]
                    var f by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (f) Color.White.copy(alpha = .12f) else Color.Transparent)
                        .then(if (se.number == season) Modifier.focusRequester(first) else if (i == 0 && seasons.none { it.number == season }) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { f = it.isFocused }.clickable { onPick(se.number); open = false }
                        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(se.name.ifBlank { tf("Season {1}", se.number) }, fontSize = 16.sp, fontWeight = if (se.number == season) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = Color.White, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (se.episodeCount > 0) Text(tf("{1} episodes", se.episodeCount), fontSize = 12.sp, color = Muted, style = jakarta())
                        }
                        if (se.number == season) Icon(painterResource(R.drawable.ic_check), null, tint = Violet, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadPill(label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Glass).border(1.dp, Edge, RoundedCornerShape(50)).focusGlow(RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_download), null, tint = Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = jakarta())
    }
}

/** Where a download stands, refreshed every couple of seconds while the page is open. */
@Composable
fun dlState(t: Title, season: Int, episode: Int): DL {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var st by remember(t.key, season, episode) { mutableStateOf(DL.NONE) }
    LaunchedEffect(t.key, season, episode) {
        while (true) {
            st = when (Downloads.find(ctx, Downloads.idFor(t, season, episode))?.state) {
                androidx.media3.exoplayer.offline.Download.STATE_COMPLETED -> DL.DONE
                null, androidx.media3.exoplayer.offline.Download.STATE_FAILED -> DL.NONE
                else -> DL.RUNNING
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    return st
}

fun dlLabel(d: DL) = when (d) { DL.DONE -> "Downloaded"; DL.RUNNING -> "Downloading"; DL.NONE -> "Download" }

fun download(ctx: android.content.Context, t: Title, season: Int, episode: Int) {
    Downloads.fetch(ctx, t, season, episode) { err ->
        android.widget.Toast.makeText(ctx, t(err ?: "Downloading — see Downloads"), android.widget.Toast.LENGTH_SHORT).show()
    }
}

// ---------- Settings ----------

/** Shown instead of the app when a newer version is out. No release notes, just the button. */
@Composable
fun ForceUpdate(state: UpdateState, onUpdate: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo_tile), null, Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)))
            Spacer(Modifier.height(22.dp))
            androidx.compose.material3.Text(t("Update required"), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text(t("A new version of Movio is out. Update to keep watching."), fontSize = 15.sp, color = Soft, style = jakarta(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(26.dp))
            val label = when (state) {
                is UpdateState.Checking -> t("Checking…")
                is UpdateState.Downloading -> if (state.pct >= 0) tf("Downloading… {1}%", state.pct) else t("Downloading…")
                is UpdateState.Ready -> t("Install update")
                is UpdateState.Failed -> t("Try again")
                else -> t("Update now")
            }
            PrimaryButton(label, R.drawable.ic_download, Modifier.focusRequester(focus)) {
                if (state !is UpdateState.Checking && state !is UpdateState.Downloading) onUpdate()
            }
            if (state is UpdateState.Failed) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Text(t(state.message), fontSize = 13.sp, color = Muted, style = jakarta(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val version: String, val pct: Int) : UpdateState
    data class Ready(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Composable
fun SettingsScreen(version: String, update: UpdateState, blocked: Int, store: Store, onLanguage: () -> Unit, onDownloads: () -> Unit, onAlerts: (Boolean) -> Unit, onUpdate: () -> Unit, onClearCache: () -> Unit) {
    val tv5 = rememberScrollState()
    ScrollList(tv5, PaddingValues(start = Gutter, end = GutterEnd, top = barSpace(), bottom = dockSpace()), narrow = true) {
        item { ProfileCard(onLanguage) }
        item { LinkCard(store) }
        item {
            Card {
                var kidsOn by remember { mutableStateOf(store.kids) }
                ToggleRow("Kids mode", "Only family and animated titles.", kidsOn) {
                    kidsOn = it; store.kids = it; Tmdb.kids = it; onLanguage()
                }
            }
        }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.logo_tile), null, Modifier.size(56.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Movio", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
                        Text("Version $version", fontSize = 14.sp, color = Muted, style = jakarta())
                    }
                }
                Spacer(Modifier.height(20.dp))
                Label("Updates")
                Spacer(Modifier.height(6.dp))
                Text(
                    when (update) {
                        UpdateState.Idle -> "Check for the newest version of Movio."
                        UpdateState.Checking -> "Checking for updates…"
                        UpdateState.UpToDate -> "You're on the latest version."
                        is UpdateState.Downloading -> "Downloading version ${update.version}…"
                        is UpdateState.Ready -> "Version ${update.version} is ready to install."
                        is UpdateState.Failed -> update.message
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = jakarta(),
                )
                if (update is UpdateState.Downloading || update is UpdateState.Checking) {
                    Spacer(Modifier.height(14.dp))
                    val pct = (update as? UpdateState.Downloading)?.pct ?: -1
                    if (pct < 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)), color = Violet, trackColor = Glass)
                    else LinearProgressIndicator({ pct / 100f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)), color = Violet, trackColor = Glass)
                }
                Spacer(Modifier.height(18.dp))
                when (update) {
                    UpdateState.Checking, is UpdateState.Downloading -> {}
                    is UpdateState.Ready -> PrimaryButton("Install update", R.drawable.ic_check, onClick = onUpdate)
                    else -> PrimaryButton("Check for updates", R.drawable.ic_refresh, onClick = onUpdate)
                }
            }
        }
        item {
            Card {
                Label("Playback")
                Spacer(Modifier.height(6.dp))
                var autoplay by remember { mutableStateOf(store.autoplayNext) }
                ToggleRow("Autoplay next episode", "Plays the next episode after a short countdown.", autoplay) { autoplay = it; store.autoplayNext = it }
                var autoSkip by remember { mutableStateOf(store.autoSkip) }
                ToggleRow("Skip intros automatically", "Skips intros and recaps the moment they start.", autoSkip) { autoSkip = it; store.autoSkip = it }
                var smart by remember { mutableStateOf(store.smartDownloads) }
                ToggleRow("Smart downloads", "When you finish a downloaded episode, it's deleted and the next one downloads.", smart) { smart = it; store.smartDownloads = it }
                var alerts by remember { mutableStateOf(store.alerts) }
                ToggleRow("New episode alerts", "Know when a show in My List has a new episode.", alerts) { alerts = it; store.alerts = it; onAlerts(it) }
                Spacer(Modifier.height(6.dp))
                GhostButton("Downloads", R.drawable.ic_download, onClick = onDownloads)
            }
        }
        item {
            Card {
                Label("Storage")
                Spacer(Modifier.height(6.dp))
                Text("Clear saved images and player data if something isn't loading.", fontSize = 13.sp, color = Muted, style = jakarta())
                Spacer(Modifier.height(14.dp))
                GhostButton("Clear cache", null, onClick = onClearCache)
            }
        }
    }
}


// ---------- People ----------

@Composable
fun Avatar(p: Person, size: Dp) {
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(Brush.linearGradient(listOf(Violet.copy(alpha = .5f), Pink.copy(alpha = .4f)))), contentAlignment = Alignment.Center) {
        Text(p.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = Color.White,
            fontSize = (size.value * .32f).sp, fontWeight = FontWeight.Bold, style = jakarta())
        if (p.photo != null) NetImage(Tmdb.img(p.photo, "w185"), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun CastCard(p: Person, onClick: () -> Unit) {
    Column(Modifier.width(82.dp).tappable(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(p, 72.dp)
        Spacer(Modifier.height(8.dp))
        Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Soft, style = jakarta(), maxLines = 2, minLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (p.role.isNotBlank()) Text(p.role, fontSize = 11.sp, color = Muted, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun PersonScreen(person: Person, onOpen: (Title) -> Unit) {
    val (state, retry) = load(person.id) { Tmdb.person(person.id) }
    var more by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<Kind?>(null) }
    val tv6 = rememberLazyGridState()
    val tvBar = LocalTopBar.current
    val wide = LocalWide.current
    val d = (state as? Load.Done)?.value
    LazyVerticalGrid(
        GridCells.Adaptive(if (LocalTv.current) TvCard - 16.dp else if (wide) 170.dp else 104.dp), Modifier.fillMaxSize().dpadScroll(tv6), state = tv6,
        contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = if (tvBar != null) 0.dp else barSpace(), bottom = dockSpace()),
        horizontalArrangement = Arrangement.spacedBy(if (wide) 18.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (tvBar != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(Gutter, GutterEnd)) { tvBar() } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val head: @Composable () -> Unit = {
                Text(person.name, fontSize = if (wide) 34.sp else 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
                if (d != null && d.facts.isNotBlank()) Text(d.facts, fontSize = 13.sp, color = Muted, style = jakarta())
                if (d != null && d.bio.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(d.bio, fontSize = 14.sp, lineHeight = 20.sp, color = Soft, style = jakarta(), maxLines = if (more) 60 else if (wide) 6 else 4,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.tappableRow { more = !more })
                }
            }
            Column(Modifier.fillMaxWidth()) {
                if (wide) Row(verticalAlignment = Alignment.Top) {
                    Avatar(d?.person ?: person, 150.dp)
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) { head() }
                } else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Avatar(d?.person ?: person, 120.dp)
                    Spacer(Modifier.height(14.dp))
                    head()
                }
                Spacer(Modifier.height(18.dp))
                if (d != null && d.known.isNotEmpty()) {
                    val movies = d.known.count { it.kind == Kind.MOVIE }
                    val shows = d.known.size - movies
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("All ${d.known.size}", filter == null) { filter = null }
                        if (movies > 0) Chip("Movies $movies", filter == Kind.MOVIE) { filter = Kind.MOVIE }
                        if (shows > 0) Chip("Shows $shows", filter == Kind.TV) { filter = Kind.TV }
                    }
                }
            }
        }
        when (val s = state) {
            Load.Loading -> items(6) { Box(Modifier.aspectRatio(2f / 3f).shimmer()) }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Failed(retry) }
            is Load.Done -> {
                val shown = s.value.known.filter { filter == null || it.kind == filter }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Message("Nothing to show", "No titles found for this person.") }
                items(shown, key = { it.key }) { t -> CreditCard(t, s.value.roles[t.key].orEmpty()) { onOpen(t) } }
            }
        }
    }
}

/** A title on a person's page: poster, then name, year and the part they played. */
@Composable
fun CreditCard(t: Title, role: String, onClick: () -> Unit) {
    val wide = LocalWide.current
    Column(Modifier.fillMaxWidth()) {
        Box(if (wide) Modifier.tappableCard(RoundedCornerShape(12.dp), onClick) else Modifier.tappable(onClick)) { Poster(t, Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(8.dp))
        Text(t.name, fontSize = if (wide) 15.sp else 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = Soft, style = jakarta(),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(t.year, role.ifBlank { null }).joinToString("  ·  "), fontSize = 11.5.sp, color = Muted, style = jakarta(),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** iOS-style switch row. */
@Composable
fun ToggleRow(title: String, body: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).focusGlow(RoundedCornerShape(12.dp), grow = false).clickableNoRipple { onChange(!on) }
        .padding(vertical = 10.dp, horizontal = if (LocalWide.current) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = jakarta())
            Text(body, fontSize = 12.sp, color = Muted, style = jakarta())
        }
        Spacer(Modifier.width(12.dp))
        val x by androidx.compose.animation.core.animateDpAsState(if (on) 22.dp else 2.dp, label = "toggle")
        Box(Modifier.width(51.dp).height(31.dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFF34C759) else Color.White.copy(alpha = .16f))) {
            Box(Modifier.padding(start = x, top = 2.dp).size(27.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White))
        }
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
)

// ---------- Downloads ----------

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DownloadsScreen(onPlay: (DownloadInfo, String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf(Downloads.all(context)) }
    LaunchedEffect(Unit) { while (true) { items = Downloads.all(context); delay(1000) } }
    val tv7 = rememberScrollState()
    ScrollList(tv7, PaddingValues(start = Gutter, end = GutterEnd, top = barSpace(), bottom = dockSpace()), narrow = true) {
        if (items.isEmpty()) item { Message("No downloads yet", "Save a movie or episode from the player to watch it offline.") }
        items(items, key = { it.request.id }) { d ->
            val info = DownloadInfo.from(d.request.data) ?: return@items
            val done = d.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
            val failed = d.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).tappableRow { if (done) onPlay(info, d.request.id) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Panel)) {
                    NetImage(Tmdb.img(info.title.backdrop ?: info.title.poster, "w300"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (done) Box(Modifier.align(Alignment.Center).size(32.dp).clip(RoundedCornerShape(50)).background(Ink.copy(alpha = .65f)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_play_fill), null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val mb = d.bytesDownloaded / 1_048_576
                    Text(when {
                        done -> "Downloaded · $mb MB"
                        failed -> "Download failed"
                        else -> "Downloading ${d.percentDownloaded.toInt().coerceAtLeast(0)}% · $mb MB"
                    }, fontSize = 12.sp, color = if (failed) Pink else if (done) Color(0xFF5EE6A8) else Muted, style = jakarta())
                    if (!done && !failed) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator({ (d.percentDownloaded / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)), color = Violet, trackColor = Glass)
                    }
                }
                Spacer(Modifier.width(8.dp))
                GlassIcon(R.drawable.ic_close, size = 34.dp) { Downloads.remove(context, d.request.id) }
            }
        }
    }
}

/**
 * A plain scrolling page that takes LazyColumn-style item {} calls. Every row exists at once,
 * so the TV remote can always find the next thing below and the page follows it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScrollList(state: androidx.compose.foundation.ScrollState, padding: PaddingValues, narrow: Boolean = false, content: @Composable ListShim.() -> Unit) {
    val bar = LocalTopBar.current
    // TV: the page only moves as far as needed, so the banner stays in full view while its buttons are selected.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll) {
    Column(Modifier.fillMaxSize().verticalScroll(state).dpadScroll(state)) {
        // On TV the top bar is part of the page (not floating over it), so Down from the tabs reaches the content.
        Box {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.then(if (narrow && LocalWide.current) Modifier.widthIn(max = 760.dp) else Modifier).padding(padding)) { ListShim.content() }
            }
            bar?.invoke()
        }
    }
    }
}

private object ListShim {
    @Composable fun item(key: Any? = null, content: @Composable () -> Unit) { androidx.compose.runtime.key(key) { content() } }
    @Composable fun items(count: Int, content: @Composable (Int) -> Unit) { repeat(count) { content(it) } }
    @Composable fun <T> items(list: List<T>, key: ((T) -> Any)? = null, content: @Composable (T) -> Unit) {
        list.forEach { androidx.compose.runtime.key(key?.invoke(it)) { content(it) } }
    }
}

/** Lets a full-width row ignore the grid's side padding. */
internal fun Modifier.bleed(start: Dp, end: Dp) = layout { m, c ->
    val s = start.roundToPx(); val extra = s + end.roundToPx()
    val p = m.measure(c.copy(minWidth = c.minWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-s, 0) }
}
