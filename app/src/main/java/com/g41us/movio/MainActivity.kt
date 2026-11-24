package com.g41us.movio

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.StreamCandidate
import com.g41us.movio.data.provider.DemoPlaybackProvider
import com.g41us.movio.data.repository.CatalogRepository
import com.g41us.movio.data.repository.StreamRepository
import com.g41us.movio.playback.MovioPlayerScreen
import kotlinx.coroutines.launch

private val MovioBackground = Color(0xFF090B10)
private val MovioSurface = Color(0xFF151923)
private val MovioAccent = Color(0xFF8B5CF6)
private val MovioMuted = Color(0xFF9AA3B2)
private val catalogRepository = CatalogRepository()
private val streamRepository = StreamRepository()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = MovioAccent,
                background = MovioBackground,
                surface = MovioSurface,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                MovioApp()
            }
        }
    }
}

private enum class Destination(val label: String) {
    HOME("Home"), SEARCH("Search"), LIBRARY("My List"), SETTINGS("Settings")
}

@Composable
private fun MovioApp() {
    var selected by remember { mutableStateOf(Destination.HOME) }
    var selectedMedia by remember { mutableStateOf<MediaItem?>(null) }
    var playback by remember { mutableStateOf<StreamCandidate?>(null) }
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 800
    val context = LocalContext.current
    val isTv = remember(context) { isTelevision(context) }

    Surface(modifier = Modifier.fillMaxSize(), color = MovioBackground) {
        if (playback != null) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(MovioSurface).padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("‹  Back", color = Color.White, modifier = Modifier.clickable { playback = null }.focusable().padding(8.dp))
                    Text("Playback test", color = Color.White, fontWeight = FontWeight.Bold)
                }
                MovioPlayerScreen(candidate = playback!!, modifier = Modifier.weight(1f))
            }
        } else if (selectedMedia != null) {
            MediaDetailsScreen(
                item = selectedMedia!!,
                onBack = { selectedMedia = null },
                onPlaySample = { playback = DemoPlaybackProvider.sample() },
                onPlayStream = { candidate -> playback = candidate }
            )
        } else {
            Column {
                if (wide || isTv) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MOVIO", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(42.dp))
                        Destination.entries.forEach { destination ->
                            Text(
                                destination.label,
                                color = if (selected == destination) Color.White else MovioMuted,
                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selected = destination }
                                    .focusable()
                                    .padding(5.dp),
                                fontWeight = if (selected == destination) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MOVIO", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.weight(1f))
                        Text("STREAM YOUR WAY", color = MovioMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                }

                when (selected) {
                    Destination.HOME -> HomeScreen(wide || isTv, onSearch = { selected = Destination.SEARCH })
                    Destination.SEARCH -> SearchScreen(onSelect = { selectedMedia = it })
                    Destination.LIBRARY -> PlaceholderScreen("My List", "Saved titles will appear here when watchlist storage is added.")
                    Destination.SETTINGS -> SettingsScreen(onPlaySample = { playback = DemoPlaybackProvider.sample() })
                }

                if (!wide && !isTv) {
                    NavigationBar(containerColor = MovioSurface) {
                        Destination.entries.forEach { destination ->
                            val icon = when (destination) {
                                Destination.HOME -> Icons.Default.Home
                                Destination.SEARCH -> Icons.Default.Search
                                Destination.LIBRARY -> Icons.Default.VideoLibrary
                                Destination.SETTINGS -> Icons.Default.Settings
                            }
                            NavigationBarItem(
                                selected = selected == destination,
                                onClick = { selected = destination },
                                icon = { Icon(icon, contentDescription = destination.label) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(largeLayout: Boolean, onSearch: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = if (largeLayout) 36.dp else 16.dp)
                .height(if (largeLayout) 390.dp else 300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF35245D), Color(0xFF171B2B), Color(0xFF10131B))))
        ) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(if (largeLayout) 34.dp else 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("YOUR NEXT\nFAVOURITE STORY", color = Color.White, fontSize = if (largeLayout) 38.sp else 29.sp, fontWeight = FontWeight.Black, lineHeight = 39.sp)
                Text("Discover movies, series and anime.", color = Color(0xFFD5D0E2), fontSize = 14.sp)
                Button(onClick = onSearch, colors = ButtonDefaults.buttonColors(containerColor = MovioAccent)) {
                    Text("Explore catalogue")
                }
            }
            Text("MOVIO", modifier = Modifier.align(Alignment.TopStart).padding(22.dp), color = Color(0xFFD8C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(28.dp))
        SectionTitle("Explore Movio", "Search across connected metadata providers")
        val categories = listOf("Movies", "TV Series", "Anime", "Live TV", "Sports")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(categories) { category ->
                Box(
                    modifier = Modifier.width(if (largeLayout) 190.dp else 145.dp)
                        .height(92.dp).clip(RoundedCornerShape(16.dp))
                        .background(MovioSurface).clickable(onClick = onSearch).focusable(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(category, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionTitle("Connected catalogues", "TVmaze for series · Jikan for anime")
        Text(
            "Catalogue entries provide metadata and episode guides. A catalogue result does not automatically include a playable video stream.",
            modifier = Modifier.padding(horizontal = 18.dp),
            color = MovioMuted,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SearchScreen(onSelect: (MediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search TV shows or anime.") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text("Discover", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search titles") },
            keyboardOptions = KeyboardOptions.Default,
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    loading = true
                    message = ""
                    results = catalogRepository.search(query)
                    message = if (results.isEmpty()) "No results found. Try another title or search again later." else ""
                    loading = false
                }
            },
            enabled = query.isNotBlank() && !loading
        ) { Text("Search catalogue") }
        if (loading) {
            CircularProgressIndicator(color = MovioAccent, modifier = Modifier.padding(20.dp))
        } else if (results.isEmpty()) {
            Text(message, color = MovioMuted, modifier = Modifier.padding(top = 18.dp))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                items(results, key = { it.id }) { item ->
                    Surface(
                        color = MovioSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF292F3D)),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(item) }.focusable()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                listOfNotNull(item.mediaType.name.lowercase().replaceFirstChar { it.uppercase() }, item.releaseYear?.toString(), item.genres.take(3).joinToString())
                                    .joinToString("  ·  "),
                                color = Color(0xFFC4B5FD),
                                fontSize = 11.sp
                            )
                            if (item.overview.isNotBlank()) {
                                Text(item.overview, color = MovioMuted, fontSize = 12.sp, maxLines = 3)
                            }
                            Text("Source: ${item.providerId}", color = MovioMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetailsScreen(
    item: MediaItem,
    onBack: () -> Unit,
    onPlaySample: () -> Unit,
    onPlayStream: (StreamCandidate) -> Unit
) {
    var summary by remember(item.id) { mutableStateOf(item.overview) }
    var cast by remember(item.id) { mutableStateOf<List<String>>(emptyList()) }
    var episodes by remember(item.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var resolvingStreams by remember(item.id) { mutableStateOf(false) }
    var streamCandidates by remember(item.id) { mutableStateOf<List<StreamCandidate>>(emptyList()) }
    var streamMessage by remember(item.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun resolveStreams(episode: Episode? = null) {
        scope.launch {
            resolvingStreams = true
            streamCandidates = emptyList()
            streamMessage = if (item.tmdbId == null) {
                "This catalogue result has no TMDB ID, so the configured movie sources cannot look it up yet."
            } else {
                "Checking configured sources…"
            }
            if (item.tmdbId != null) {
                streamCandidates = streamRepository.resolve(item, episode)
                streamMessage = if (streamCandidates.isEmpty()) {
                    "No direct playable source was returned. This can mean the provider is unavailable, the title is not indexed, or its response needs a provider-specific resolver."
                } else {
                    "Choose a source to play."
                }
            }
            resolvingStreams = false
        }
    }

    LaunchedEffect(item.id) {
        val details = catalogRepository.details(item)
        if (details != null) {
            summary = details.item.overview.ifBlank { item.overview }
            cast = details.cast
        }
        episodes = catalogRepository.episodes(item)
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("‹  Back to results", color = Color.White, modifier = Modifier.clickable(onClick = onBack).focusable().padding(vertical = 8.dp))
        Spacer(Modifier.height(10.dp))
        Text(item.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("${item.mediaType.name}  ·  ${item.releaseYear ?: "Year unavailable"}  ·  ${item.providerId}", color = Color(0xFFC4B5FD), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Text(summary.ifBlank { "No synopsis supplied by the metadata provider." }, color = MovioMuted)
        if (cast.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Studios / cast", color = Color.White, fontWeight = FontWeight.Bold)
            Text(cast.joinToString(" · "), color = MovioMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        if (item.mediaType == com.g41us.movio.data.model.MediaType.MOVIE) {
            Button(
                onClick = { resolveStreams() },
                enabled = !resolvingStreams,
                colors = ButtonDefaults.buttonColors(containerColor = MovioAccent)
            ) { Text(if (resolvingStreams) "Finding sources…" else "Find available streams") }
        }
        Text("Episodes", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        when {
            loading -> CircularProgressIndicator(color = MovioAccent, modifier = Modifier.padding(12.dp))
            episodes.isEmpty() -> Text("No episode guide is available from this provider.", color = MovioMuted, modifier = Modifier.padding(vertical = 8.dp))
            else -> episodes.take(30).forEach { episode ->
                Text(
                    "${episode.number}. ${episode.title}",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { resolveStreams(episode) }
                        .focusable()
                        .padding(vertical = 10.dp)
                )
            }
        }
        if (item.mediaType != com.g41us.movio.data.model.MediaType.MOVIE && episodes.isNotEmpty()) {
            Text("Select an episode to check its available streams.", color = MovioMuted, fontSize = 12.sp)
        }
        if (resolvingStreams) {
            CircularProgressIndicator(color = MovioAccent, modifier = Modifier.padding(12.dp))
        }
        if (streamMessage.isNotBlank()) {
            Text(streamMessage, color = MovioMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        streamCandidates.forEachIndexed { index, candidate ->
            Button(
                onClick = { onPlayStream(candidate) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MovioSurface)
            ) {
                Text("${candidate.qualityLabel ?: candidate.format.name} · Source ${index + 1}")
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Catalogue metadata and playback sources are separate. A title is playable only when a configured source returns a direct stream URL supported by the player.",
            color = MovioMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onPlaySample, colors = ButtonDefaults.buttonColors(containerColor = MovioAccent)) {
            Text("Test video player with sample clip")
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MovioMuted, fontSize = 12.sp)
    }
}

@Composable
private fun PlaceholderScreen(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(message, color = MovioMuted)
    }
}

@Composable
private fun SettingsScreen(onPlaySample: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Surface(color = MovioSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF292F3D))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback diagnostics", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Play a public sample MP4 to verify the Media3 player and device decoding pipeline.", color = MovioMuted, fontSize = 13.sp)
                Button(onClick = onPlaySample) { Text("Test video playback") }
            }
        }
        Surface(color = MovioSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF292F3D))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App updates", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("GitHub Releases updater is planned. Release signing and download verification are not configured yet.", color = MovioMuted, fontSize = 13.sp)
                Text("Current version  •  0.1.0", color = Color(0xFFC4B5FD), fontSize = 12.sp)
            }
        }
        Text("About Movio", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Built for Android phones and Android TV.", color = MovioMuted)
    }
}

private fun isTelevision(context: Context): Boolean {
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature("android.software.leanback")
}
