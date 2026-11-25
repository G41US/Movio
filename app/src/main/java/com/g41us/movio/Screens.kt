package com.g41us.movio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun dockSpace(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 112.dp

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
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key, attempt) {
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
fun FeedScreen(kind: Kind?, store: Store, onOpen: (Title) -> Unit, onPlay: (Title) -> Unit) {
    var genre by remember(kind) { mutableStateOf<Genre?>(null) }
    val (state, retry) = load(kind to genre) { if (kind == null) Tmdb.home() else Tmdb.browse(kind, genre) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = dockSpace())) {
        when (val s = state) {
            Load.Loading -> {
                item { HeroSkeleton() }
                items(3) { RowSkeleton() }
            }
            is Load.Failed -> {
                item { Spacer(Modifier.height(barSpace())) }
                item { Failed(retry) }
            }
            is Load.Done -> {
                val rows = s.value
                item(key = "hero") {
                    Hero(
                        rows.firstOrNull()?.items?.filter { it.overview.isNotBlank() }?.take(6) ?: emptyList(),
                        saved = { store.inList(it) }, onPlay = onPlay, onOpen = onOpen, onToggle = { store.toggle(it) },
                    )
                }
                if (kind != null) item(key = "genres") {
                    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 18.dp)) {
                        item { Chip("All", genre == null) { genre = null } }
                        items(if (kind == Kind.MOVIE) Tmdb.movieGenres else Tmdb.tvGenres) { g -> Chip(g.name, genre == g) { genre = g } }
                    }
                }
                if (kind == null && store.continueWatching.isNotEmpty()) item(key = "continue") {
                    SectionTitle("Continue watching")
                    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(store.continueWatching, key = { it.title.key }) { p ->
                            val pos = store.position(store.posKey(p.title, p.season, p.episode))
                            ContinueCard(p, pos?.let { (a, b) -> if (b > 0) a.toFloat() / b else null }) { onPlay(p.title) }
                        }
                    }
                }
                rows.forEachIndexed { i, row ->
                    item(key = "row$i${row.name}") { Box(Modifier.enterUp(i)) { Column { PosterRow(row, onOpen) } } }
                }
            }
        }
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen(onOpen: (Title) -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(250); focus.requestFocus() }
    LaunchedEffect(query) { delay(450); submitted = query.trim() }
    val (state, retry) = load(submitted) { if (submitted.length < 2) emptyList() else Tmdb.search(submitted) }

    LazyVerticalGrid(
        GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = barSpace(), bottom = dockSpace()),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(Glass)
                    .border(1.dp, Hairline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
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
                        keyboardActions = KeyboardActions(onSearch = { submitted = query.trim(); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) GlassIcon(R.drawable.ic_close, size = 30.dp) { query = "" }
            }
        }
        when (val s = state) {
            Load.Loading -> if (submitted.length >= 2) items(9) { Box(Modifier.aspectRatio(2f / 3f).shimmer()) }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Failed(retry) }
            is Load.Done -> when {
                submitted.length < 2 -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Message("Find something to watch", "Search across thousands of movies and shows.")
                }
                s.value.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Message("No results", "Try a different title.")
                }
                else -> items(s.value, key = { it.key }) { t -> PosterCard(t, 108.dp) { onOpen(t) } }
            }
        }
    }
}

// ---------- My List ----------

@Composable
fun MyListScreen(store: Store, onOpen: (Title) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = barSpace(), bottom = dockSpace()),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (store.myList.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Message("Your list is empty", "Tap My List on any movie or show to save it here.")
        } else items(store.myList, key = { it.key }) { t -> PosterCard(t, 108.dp) { onOpen(t) } }
    }
}

// ---------- Details ----------

@Composable
fun DetailsScreen(title: Title, store: Store, onOpen: (Title) -> Unit, onPlay: (Title, Int, Int) -> Unit) {
    val (state, retry) = load(title.key) { Tmdb.details(title) }
    val last = store.lastEpisode(title)
    var season by remember(title.key) { mutableIntStateOf(last?.season ?: 1) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = dockSpace())) {
        item(key = "top") {
            val d = (state as? Load.Done)?.value
            val t = d?.title ?: title
            Box(Modifier.fillMaxWidth().height(500.dp)) {
                AsyncImage(Tmdb.img(t.poster, "w780"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().kenBurns())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = .4f), .35f to Color.Transparent, .7f to Ink.copy(alpha = .7f), 1f to Ink)))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Gutter).enterUp()) {
                    if (d?.logo != null) AsyncImage(Tmdb.img(d.logo, "w500"), t.name, contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart, modifier = Modifier.fillMaxWidth(.75f).height(90.dp))
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
                    Column(Modifier.padding(horizontal = Gutter).padding(top = 18.dp)) {
                        val label = when {
                            t.kind == Kind.TV && last != null -> "Resume S${last.season} E${last.episode}"
                            t.kind == Kind.TV -> "Play S1 E1"
                            else -> "Play"
                        }
                        PrimaryButton(label, R.drawable.ic_play_fill, Modifier.fillMaxWidth()) {
                            onPlay(t, last?.season ?: 1, last?.episode ?: 1)
                        }
                        Spacer(Modifier.height(10.dp))
                        val saved = store.inList(t)
                        GhostButton(if (saved) "In My List" else "Add to My List", if (saved) R.drawable.ic_check else R.drawable.ic_plus, Modifier.fillMaxWidth()) { store.toggle(t) }
                        if (d.genres.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { d.genres.take(3).forEach { Badge(it.uppercase()) } }
                        }
                        if (d.tagline.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(d.tagline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = jakarta())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t.overview, fontSize = 14.5.sp, lineHeight = 21.sp, color = Soft, style = jakarta())
                        if (d.cast.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Starring  " + d.cast.take(5).joinToString(", "), fontSize = 13.sp, lineHeight = 19.sp, color = Muted, style = jakarta(),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (t.kind == Kind.TV && d.seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        SectionTitle("Episodes")
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(d.seasons) { se -> Chip("Season ${se.number}", se.number == season) { season = se.number } }
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

@Composable
private fun EpisodeList(show: Title, season: Int, last: Progress?, onPlay: (Int) -> Unit) {
    val (state, retry) = load(show.id to season) { Tmdb.episodes(show.id, season) }
    Column(Modifier.padding(horizontal = Gutter)) {
        when (val s = state) {
            Load.Loading -> repeat(3) { Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(78.dp).shimmer()) }
            is Load.Failed -> Failed(retry)
            is Load.Done -> s.value.forEach { e ->
                val current = last?.season == season && last.episode == e.number
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).tappable { onPlay(e.number) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, if (current) Violet else Hairline, RoundedCornerShape(12.dp))) {
                        AsyncImage(Tmdb.img(e.still ?: show.backdrop, "w300"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                            fontSize = 12.sp, color = if (current) Violet else Muted, style = jakarta())
                    }
                }
            }
        }
    }
}

// ---------- Settings ----------

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val version: String, val pct: Int) : UpdateState
    data class Ready(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Composable
fun SettingsScreen(version: String, update: UpdateState, blocked: Int, onUpdate: () -> Unit, onClearCache: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, top = barSpace(), bottom = dockSpace())) {
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
                Label("Ad blocking")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_shield), null, tint = Color(0xFF5EE6A8), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (blocked > 0) "On · ${"%,d".format(blocked)} ad and tracker sites blocked" else "On",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = jakarta())
                }
                Spacer(Modifier.height(6.dp))
                Text("Pop-ups, redirects and banner ads are blocked while you watch.", fontSize = 13.sp, color = Muted, style = jakarta())
            }
        }
        item {
            Card {
                Label("Storage")
                Spacer(Modifier.height(6.dp))
                Text("Clear cached images and player data if something looks stuck.", fontSize = 13.sp, color = Muted, style = jakarta())
                Spacer(Modifier.height(14.dp))
                GhostButton("Clear cache", null, onClick = onClearCache)
            }
        }
    }
}
