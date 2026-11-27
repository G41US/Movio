package com.g41us.movio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A free-to-air channel (from iptv-org; Movio's lists keep only ones that answered in the last check). */
data class Channel(val id: String, val name: String, val logo: String?, val url: String, val cats: List<String>, val referrer: String?, val ua: String?) {
    fun json(): JSONObject = JSONObject().put("id", id).put("n", name).put("l", logo ?: JSONObject.NULL).put("u", url)
        .put("c", JSONArray(cats)).apply { referrer?.let { put("r", it) }; ua?.let { put("a", it) } }

    companion object {
        fun from(o: JSONObject) = Channel(
            o.getString("id"), o.getString("n"), o.optString("l").takeIf { it.startsWith("http") }, o.getString("u"),
            o.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optString("r").ifBlank { null }, o.optString("a").ifBlank { null },
        )
    }
}

object Live {
    private const val BASE = "https://g41us.github.io/Movio/live"
    class Index(val total: Int, val countries: List<Triple<String, String, Int>>, val cats: Set<String>)
    @Volatile private var index: Index? = null
    private val lists = HashMap<String, List<Channel>>()

    private fun read(path: String): String? = runCatching {
        val c = URL("$BASE/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000; c.useCaches = false
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    }.getOrNull()

    suspend fun index(): Index? = index ?: withContext(Dispatchers.IO) {
        val o = read("index.json")?.let { JSONObject(it) } ?: return@withContext null
        val cs = o.getJSONArray("countries")
        val cats = o.getJSONArray("categories")
        Index(o.optInt("total"), (0 until cs.length()).map { cs.getJSONObject(it).let { c -> Triple(c.getString("code"), c.getString("name"), c.getInt("n")) } },
            (0 until cats.length()).map { cats.getJSONObject(it).getString("id") }.toSet()).also { index = it }
    }

    suspend fun channels(path: String): List<Channel> = lists[path] ?: withContext(Dispatchers.IO) {
        val a = read(path)?.let { JSONArray(it) } ?: return@withContext emptyList()
        (0 until a.length()).map { Channel.from(a.getJSONObject(it)) }.also { lists[path] = it }
    }

    fun myCountry(): String {
        val tz = java.util.TimeZone.getDefault().id
        return mapOf("Africa/Lagos" to "NG", "Africa/Accra" to "GH", "Africa/Nairobi" to "KE", "Africa/Johannesburg" to "ZA", "Europe/London" to "UK", "Europe/Paris" to "FR")[tz]
            ?: java.util.Locale.getDefault().country.ifBlank { "US" }.uppercase()
    }
    fun flag(cc: String) = "https://flagcdn.com/w40/${if (cc == "UK") "gb" else cc.lowercase()}.png"

    // Favourites, kept on this device.
    private fun favPrefs(c: Context) = c.getSharedPreferences("movio_live", Context.MODE_PRIVATE)
    fun favs(c: Context): List<Channel> = runCatching { JSONArray(favPrefs(c).getString("fav", "[]")).let { a -> (0 until a.length()).map { Channel.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun isFav(c: Context, ch: Channel) = favs(c).any { it.id == ch.id }
    fun toggleFav(c: Context, ch: Channel): Boolean {
        val l = favs(c).toMutableList()
        val on = l.none { it.id == ch.id }
        if (on) l.add(0, ch) else l.removeAll { it.id == ch.id }
        favPrefs(c).edit().putString("fav", JSONArray(l.map { it.json() }).toString()).apply()
        return on
    }

    /** The channel list the player can move through (kept here rather than squeezed into an Intent). */
    var queue: List<Channel> = emptyList()
    fun play(c: Context, list: List<Channel>, i: Int) {
        queue = list
        c.startActivity(Intent(c, LivePlayerActivity::class.java).putExtra("i", i))
    }
}

private val LIVE_CATS = listOf("news", "sports", "movies", "entertainment", "music", "kids", "documentary", "religious", "general")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var index by remember { mutableStateOf<Live.Index?>(null) }
    var failed by remember { mutableStateOf(false) }
    var cc by rememberSaveableString("liveCc", Live.myCountry())
    var cat by rememberSaveableString("liveCat", "")
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Channel>?>(null) }
    var favs by remember { mutableStateOf(Live.favs(ctx)) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { index = Live.index(); failed = index == null }
    val idx = index
    var limit by remember(cc, cat, query) { mutableIntStateOf(120) }
    LaunchedEffect(idx, cc) {
        if (idx == null) return@LaunchedEffect
        if (cc != "ALL" && idx.countries.none { it.first == cc }) cc = "US"
        list = null
        list = Live.channels(if (cc == "ALL") "all.json" else "c/${cc.lowercase()}.json")
    }
    val wide = LocalWide.current
    val tvBar = LocalTopBar.current
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        GridCells.Adaptive(if (wide) 220.dp else 150.dp), Modifier.fillMaxSize().dpadScroll(grid), state = grid,
        contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = if (tvBar != null) 0.dp else barSpace(), bottom = dockSpace()),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (tvBar != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(Gutter, GutterEnd)) { tvBar() } }
        if (idx == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (failed) Message("Live TV is getting ready", "Try again in a few minutes.", "Try again") { failed = false; index = null }
                else Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Violet) }
            }
            return@LazyVerticalGrid
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Live TV", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta(), modifier = Modifier.weight(1f))
                    Text(tf("{1} channels", idx.total), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, style = jakarta())
                }
                if (!LocalTv.current) Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(15.dp)).background(Glass).border(1.dp, Edge, RoundedCornerShape(15.dp)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_search), null, tint = Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Find a channel", color = Muted, fontSize = 15.sp, style = jakarta())
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = Jakarta),
                            cursorBrush = SolidColor(Violet), modifier = Modifier.fillMaxWidth())
                    }
                }
                // All, then quick picks in alphabetical order (the full list is under "All countries").
                val top = (listOf(cc, Live.myCountry(), "NG", "GH", "KE", "ZA", "US", "UK", "FR")).distinct()
                    .mapNotNull { c -> idx.countries.firstOrNull { it.first == c } }.sortedBy { it.second }
                Box(Modifier.bleed(Gutter, GutterEnd)) {
                    LazyRow(contentPadding = PaddingValues(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Chip(t("All"), cc == "ALL") { cc = "ALL" } }
                        items(top, key = { it.first }) { c -> Chip(c.second, cc == c.first) { cc = c.first } }
                        item { Chip(t("All countries") + " ›", false) { picking = true } }
                    }
                }
                Box(Modifier.bleed(Gutter, GutterEnd)) {
                    LazyRow(contentPadding = PaddingValues(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Chip(t("All"), cat.isEmpty()) { cat = "" } }
                        items(LIVE_CATS.filter { it in idx.cats }, key = { it }) { c -> Chip(t(c.replaceFirstChar { it.uppercase() }), cat == c) { cat = c } }
                    }
                }
                if (favs.isNotEmpty() && query.isBlank()) {
                    SectionTitle("Favourites")
                    Box(Modifier.bleed(Gutter, GutterEnd)) {
                        LazyRow(contentPadding = PaddingValues(start = Gutter, end = GutterEnd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(favs, key = { "f" + it.id }) { ch -> Box(Modifier.width(if (wide) 220.dp else 150.dp)) { ChannelTile(ch, true, { Live.play(ctx, favs, favs.indexOf(ch)) }) { Live.toggleFav(ctx, ch); favs = Live.favs(ctx) } } }
                        }
                    }
                }
            }
        }
        val l = list
        if (l == null) items(8) { Box(Modifier.aspectRatio(16f / 10f).shimmer(RoundedCornerShape(16.dp))) }
        else {
            val q = query.trim().lowercase()
            val shown = l.filter { (cat.isEmpty() || it.cats.contains(cat) || (cat == "general" && it.cats.isEmpty())) && (q.isEmpty() || it.name.lowercase().contains(q)) }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Message("No channels here", "Try another country or category.") }
            else items(shown.take(limit), key = { it.id }) { ch ->
                ChannelTile(ch, favs.any { it.id == ch.id }, { Live.play(ctx, shown, shown.indexOf(ch)) }) {
                    val on = Live.toggleFav(ctx, ch); favs = Live.favs(ctx)
                    android.widget.Toast.makeText(ctx, t(if (on) "Added to Favourites" else "Removed from Favourites"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // Big lists (All has thousands) come in steps.
            if (shown.size > limit) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    GhostButton(tf("Show more ({1} left)", shown.size - limit), null) { limit += 120 }
                }
            }
        }
    }
    if (picking && idx != null) {
        androidx.compose.ui.window.Dialog({ picking = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(22.dp)).background(Panel).padding(vertical = 14.dp)) {
                Text("Country", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                androidx.compose.foundation.lazy.LazyColumn {
                    items(idx.countries.sortedBy { it.second }, key = { it.first }) { (code, name, n) ->
                        Row(Modifier.fillMaxWidth().focusGlow(RoundedCornerShape(12.dp), grow = false).combinedClickable { cc = code; picking = false }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            NetImage(Live.flag(code), null, Modifier.size(24.dp, 17.dp).clip(RoundedCornerShape(3.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Spacer(Modifier.width(12.dp))
                            androidx.compose.material3.Text(name, color = if (code == cc) Violet else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = jakarta(), modifier = Modifier.weight(1f))
                            androidx.compose.material3.Text("$n", color = Muted, fontSize = 13.sp, style = jakarta())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSaveableString(key: String, default: String): MutableState<String> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val p = remember { ctx.getSharedPreferences("movio_live", Context.MODE_PRIVATE) }
    val s = remember { mutableStateOf(p.getString(key, null) ?: default) }
    LaunchedEffect(s.value) { p.edit().putString(key, s.value).apply() }
    return s
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(ch: Channel, fav: Boolean, onOpen: () -> Unit, onFav: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).focusGlow(shape).clip(shape)
                .background(Brush.radialGradient(listOf(Color(0xFF2A2B3A), Color(0xFF15161F)), radius = 600f))
                .border(1.dp, Color.White.copy(alpha = .07f), shape)
                .combinedClickable(onLongClick = onFav, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            if (ch.logo != null) NetImage(ch.logo, null, Modifier.fillMaxWidth(.62f).fillMaxHeight(.58f))
            else androidx.compose.material3.Text(ch.name.take(2).uppercase(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Soft, style = jakarta())
            LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            if (fav) androidx.compose.material3.Text("★", color = Color(0xFFFFD25E), fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 9.dp, vertical = 5.dp))
        }
        androidx.compose.material3.Text(ch.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text((ch.cats.firstOrNull() ?: "general").replaceFirstChar { it.uppercase() }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted, style = jakarta())
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xE6FF3B5C)).padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(Color.White))
        Spacer(Modifier.width(5.dp))
        androidx.compose.material3.Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = .6.sp)
    }
}

/** Full-screen live channel; ↑ ↓ (or Channel +/−) change channel on a remote. */
class LivePlayerActivity : ComponentActivity() {
    private lateinit var exo: ExoPlayer
    private lateinit var view: PlayerView
    private var i by mutableIntStateOf(0)
    private var state by mutableStateOf("tuning") // tuning · playing · off
    private var controls by mutableStateOf(true)
    private var fav by mutableStateOf(false)
    private var token = 0
    private var lastTouch = 0L
    private val list get() = Live.queue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (list.isEmpty()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        i = intent.getIntExtra("i", 0).coerceIn(0, list.lastIndex)
        exo = ExoPlayer.Builder(this).build()
        view = PlayerView(this).apply { useController = false; player = exo; setBackgroundColor(android.graphics.Color.BLACK); keepScreenOn = true }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) state = "playing" }
            override fun onPlayerError(error: PlaybackException) { failed() }
        })
        val root = FrameLayout(this)
        val overlay = ComposeView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val tvDevice = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        overlay.setContent { CompositionLocalProvider(LocalTv provides tvDevice) { Overlay() } }
        tune()
        lifecycleScope.launch { while (true) { delay(1000); if (controls && state == "playing" && System.currentTimeMillis() - lastTouch > 4000) controls = false } }
    }

    private var viaHelper = false
    private fun tune(helper: Boolean = false) {
        val ch = list[i]
        val t = ++token
        viaHelper = helper
        state = "tuning"; fav = Live.isFav(this, ch); poke()
        exo.stop()
        lifecycleScope.launch {
            val url = if (!helper) ch.url else withContext(Dispatchers.IO) {
                runCatching {
                    val enc = { s: String? -> java.net.URLEncoder.encode(s ?: "", "UTF-8") }
                    val c = URL("$HELPER/live?u=${enc(ch.url)}&r=${enc(ch.referrer)}&a=${enc(ch.ua)}").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 10000
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("url").ifBlank { null }
                }.getOrNull()
            }
            if (t != token) return@launch
            if (url == null) { state = "off"; return@launch }
            val headers = HashMap<String, String>()
            if (!helper) { ch.referrer?.let { headers["Referer"] = it; headers["Origin"] = runCatching { URL(it).let { u -> "${u.protocol}://${u.host}" } }.getOrDefault(it) } }
            val ds = DefaultHttpDataSource.Factory().setUserAgent(if (!helper) ch.ua ?: UA_LIVE else UA_LIVE).setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
            exo.setMediaSource(HlsMediaSource.Factory(ds).createMediaSource(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()))
            exo.prepare(); exo.playWhenReady = true
            delay(20_000)
            if (t == token && state == "tuning") failed()
        }
    }

    private fun failed() {
        // Straight from the channel didn't work: try once through Movio's helper, then say it's off air.
        if (!viaHelper) tune(helper = true) else { state = "off"; controls = true }
    }

    private fun step(d: Int) { i = (i + d + list.size) % list.size; tune() }
    private fun poke() { lastTouch = System.currentTimeMillis(); controls = true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls) { poke(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { exo.playWhenReady = !exo.playWhenReady; poke(); return true }
        }
        poke()
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() { super.onStop(); if (::exo.isInitialized) exo.pause() }
    override fun onStart() { super.onStart(); if (::exo.isInitialized && state == "playing") exo.play() }
    override fun onDestroy() { super.onDestroy(); if (::exo.isInitialized) exo.release() }

    @Composable
    private fun Overlay() {
        val ch = list[i]
        Box(Modifier.fillMaxSize().combinedClickableNoRipple { if (controls) controls = false else poke() }) {
            if (state == "tuning") Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Violet)
                Spacer(Modifier.height(12.dp))
                Text("Tuning in…", color = Soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = jakarta())
            }
            if (state == "off") Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This channel is off air right now", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = jakarta())
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Next channel", R.drawable.ic_next) { step(1) }
            }
            AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .75f), Color.Transparent)))
                            .windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIcon(R.drawable.ic_back) { finish() }
                        Spacer(Modifier.width(14.dp))
                        if (ch.logo != null) { NetImage(ch.logo, null, Modifier.size(56.dp, 38.dp)); Spacer(Modifier.width(12.dp)) }
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text(ch.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            LiveBadge()
                        }
                        androidx.compose.material3.Text(if (fav) "★" else "☆", color = if (fav) Color(0xFFFFD25E) else Color.White, fontSize = 26.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).focusGlow(RoundedCornerShape(50)).clickableNoRipple { fav = Live.toggleFav(this@LivePlayerActivity, ch) }.padding(10.dp))
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f))))
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostButton("Previous", R.drawable.ic_back) { step(-1) }
                        GlassIcon(if (exo.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play_fill, size = 54.dp) { exo.playWhenReady = !exo.playWhenReady; poke() }
                        GhostButton("Next", R.drawable.ic_next) { step(1) }
                    }
                }
            }
        }
    }
}

private const val UA_LIVE = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

private fun Modifier.combinedClickableNoRipple(onClick: () -> Unit) = this.then(Modifier.clickableNoRipple(onClick))
