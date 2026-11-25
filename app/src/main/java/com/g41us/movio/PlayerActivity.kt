package com.g41us.movio

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.media.AudioManager
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.core.content.res.ResourcesCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * Plays a title in Movio's own player from one of many sources. Movio's direct source asks the
 * stream service by TMDB id; any other source's page loads out of sight and the stream its player
 * requests is caught and played natively, so the source's player and its ads never show. If a
 * source can't be caught, Movio moves to the next one, and finally shows the source's page.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        fun start(context: Context, t: Title, season: Int, episode: Int, offline: String? = null) {
            Store.get(context).watched(t, season, episode)
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra("id", t.id).putExtra("kind", t.kind.name).putExtra("name", t.name)
                    .putExtra("s", season).putExtra("e", episode).putExtra("offline", offline)
            )
        }
    }

    private enum class Phase { FINDING, NATIVE, WEB, FAILED }

    private data class Stream(val url: String, val headers: Map<String, String>, val hls: Boolean)

    private lateinit var webView: WebView
    private lateinit var playerView: PlayerView
    private lateinit var fullscreen: FrameLayout
    private lateinit var exo: ExoPlayer
    private var customView: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val feelJs by lazy { assets.open("feel.js").bufferedReader().use { it.readText() } }
    private val sniffJs by lazy { assets.open("sniff.js").bufferedReader().use { it.readText() } }
    private val playerJs by lazy { assets.open("player.js").bufferedReader().use { it.readText() } }
    private val store by lazy { Store.get(this) }
    private val prefs by lazy { getSharedPreferences("movio_player", MODE_PRIVATE) }

    private var phase by mutableStateOf(Phase.FINDING)
    private var label by mutableStateOf("")
    private var fill by mutableStateOf(false)
    private var webBar by mutableStateOf(true)
    private var pip by mutableStateOf(false)
    private var sourcesOpen by mutableStateOf(false)
    private var sourceIndex by mutableIntStateOf(0)
    private var status by mutableStateOf("Getting your stream ready")
    private val failedSources = mutableSetOf<Int>()
    private var controlsVisible by mutableStateOf(true)
    private var episodesList by mutableStateOf<List<Episode>>(emptyList())
    private var episodeCount = 0
    private var seasonCount = 0
    private var aspect by mutableIntStateOf(0)
    private var subSizeState by mutableIntStateOf(1)
    private var subBoxState by mutableStateOf(true)
    private var subOffsetState by mutableLongStateOf(0L)
    private var downloadLabelState by mutableStateOf<String?>(null)
    private var offlineId: String? = null

    private var id = 0
    private var kind = Kind.MOVIE
    private var name = ""
    private var season = 1
    private var episode = 1
    private var title: Title? = null
    /** Expected length from TMDB, to spot a source playing the wrong title. */
    @Volatile private var runtimeMs = 0L

    // Per-attempt state.
    private val candidates = mutableListOf<Stream>()
    private val rejected = mutableSetOf<String>()
    private var trying: Stream? = null
    private var attempt = 0
    private var auto = true
    private var autoTried = 0
    private var resolveJob: Job? = null
    private var subtitleTracks: List<Subtitle> = emptyList()
    private var subtitleJob: Job? = null
    private val pageSubs = linkedSetOf<String>()
    @Volatile private var pageHost = ""

    private val source get() = Sources.all[sourceIndex]

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlock.warm(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        immersive()

        id = intent.getIntExtra("id", 0)
        kind = Kind.valueOf(intent.getStringExtra("kind") ?: "MOVIE")
        name = intent.getStringExtra("name").orEmpty()
        season = intent.getIntExtra("s", 1)
        episode = intent.getIntExtra("e", 1)
        title = store.continueWatching.firstOrNull { it.title.id == id && it.title.kind == kind }?.title
        updateLabel()
        offlineId = intent.getStringExtra("offline")
        subSizeState = store.subSize; subBoxState = store.subBox
        if (kind == Kind.TV) lifecycleScope.launch { loadEpisodeInfo() }
        lifecycleScope.launch {
            runtimeMs = try {
                val t = title ?: Title(id, kind, name, "", null, null, null, 0.0)
                if (kind == Kind.MOVIE) (Tmdb.details(t).runtime ?: 0) * 60_000L
                else (Tmdb.episodes(id, season).firstOrNull { it.number == episode }?.runtime ?: 0) * 60_000L
            } catch (_: Exception) { 0L }
        }

        exo = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) onReady() }
                override fun onPlayerError(error: PlaybackException) { onStreamFailed() }
            })
        }

        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        webView = WebView(this)
        playerView = PlayerView(this).apply {
            useController = false
            player = exo
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            setKeepContentOnPlayerReset(true)
            visibility = View.GONE
        }
        applySubtitleStyle()
        fullscreen = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(android.graphics.Color.BLACK) }
        val overlay = ComposeView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        overlay.setContent { Overlay() }

        setupWebView()
        val off = offlineId
        if (off != null) playOffline(off) else {
            val t = title
            val perTitle = t?.let { store.sourceFor(it) }
            start(Sources.index(perTitle ?: prefs.getString("source2", null)), automatic = true)
        }
        lifecycleScope.launch { while (true) { refreshDownloadLabel(); kotlinx.coroutines.delay(2000) } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sourcesOpen -> sourcesOpen = false
                    customView != null -> webView.webChromeClient?.onHideCustomView()
                    else -> finish()
                }
            }
        })
    }

    // ---------- UI ----------

    @Composable
    private fun Overlay() {
        if (pip) return
        Box(Modifier.fillMaxSize()) {
            when (phase) {
                Phase.NATIVE -> PlayerControls(exo, host, controlsVisible) { controlsVisible = it }
                Phase.WEB -> AnimatedVisibility(webBar, enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .75f), Color.Transparent)))
                            .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIcon(R.drawable.ic_back, size = 42.dp) { finish() }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${source.name} · web player", color = Muted, fontSize = 12.sp, style = jakarta())
                        }
                        if (kind == Kind.TV) { GhostButton("Next", R.drawable.ic_next) { nextEpisode() }; Spacer(Modifier.width(10.dp)) }
                        GhostButton("Sources", R.drawable.ic_servers) { sourcesOpen = true }
                    }
                }
                Phase.FINDING, Phase.FAILED -> Column(
                    Modifier.fillMaxSize().background(Ink), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    if (phase == Phase.FAILED) {
                        Message("Can't play right now", "None of the sources could play this. Check your connection or pick a source.", "Try again") {
                            failedSources.clear(); autoTried = 0; start(Sources.fallbackOrder.first(), automatic = true)
                        }
                    } else {
                        CircularProgressIndicator(color = Violet, trackColor = Glass, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(18.dp))
                        Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = jakarta())
                        Text(status, color = Muted, fontSize = 13.sp, style = jakarta())
                    }
                }
            }
            if (phase == Phase.FINDING || phase == Phase.FAILED) {
                Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIcon(R.drawable.ic_back, size = 42.dp) { finish() }
                    Spacer(Modifier.weight(1f))
                    GhostButton(source.name, R.drawable.ic_servers) { sourcesOpen = true }
                }
            }
            AnimatedVisibility(
                sourcesOpen, modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
            ) { SourcesPanel() }
        }
    }

    @Composable
    private fun SourcesPanel() {
        Column(Modifier.fillMaxHeight().width(300.dp).background(Color(0xF2101219)).safeDrawingPadding().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Sources")
                Spacer(Modifier.weight(1f))
                GlassIcon(R.drawable.ic_close, size = 34.dp) { sourcesOpen = false }
            }
            Spacer(Modifier.height(4.dp))
            Text("If one doesn't play, try another.", color = Muted, fontSize = 12.sp, style = jakarta())
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                itemsIndexed(Sources.all) { i, s ->
                    val selected = i == sourceIndex
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Violet.copy(alpha = .28f) else Color.Transparent)
                            .border(1.dp, if (selected) Violet.copy(alpha = .6f) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { sourcesOpen = false; start(i, automatic = false) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Color.White, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, style = jakarta())
                            when {
                                i in failedSources -> Text("Didn't play this title", color = Pink.copy(alpha = .8f), fontSize = 11.sp, style = jakarta())
                                s.web -> Text("Web player", color = Muted, fontSize = 11.sp, style = jakarta())
                                else -> Text("Movio player", color = Color(0xFF5EE6A8), fontSize = 11.sp, style = jakarta())
                            }
                        }
                        if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    private fun updateLabel() { label = if (kind == Kind.TV) "$name · S$season E$episode" else name }
    private fun posKey() = title?.let { store.posKey(it, season, episode) } ?: "${kind.api}:$id${if (kind == Kind.TV) ":$season:$episode" else ""}"

    // ---------- Sources ----------

    /** Play from source [index]. Automatic starts move on to the next source by themselves. */
    private fun start(index: Int, automatic: Boolean) {
        if (phase == Phase.NATIVE) savePosition()
        auto = automatic
        if (!automatic) { autoTried = 0; prefs.edit().putString("source2", Sources.all[index].name).apply() }
        sourceIndex = index
        attempt++
        val token = attempt
        candidates.clear(); rejected.clear(); trying = null; subtitleTracks = emptyList(); pageSubs.clear()
        resolveJob?.cancel()
        subtitleJob?.cancel()
        if (!source.direct) subtitleJob = lifecycleScope.launch {
            val found = Subtitles.find(kind, id, season, episode)
            if (token != attempt || found.isEmpty()) return@launch
            subtitleTracks = found
            // Stream already playing without them: reload in place so the subtitles show up.
            if (phase == Phase.NATIVE) trying?.let { s -> val pos = exo.currentPosition; play(s); exo.seekTo(pos) }
        }
        exo.stop(); exo.clearMediaItems()
        playerView.visibility = View.GONE
        phase = Phase.FINDING
        status = if (source.direct) "Getting your stream ready" else "Connecting to ${source.name}"
        webView.stopLoading()

        if (source.direct) {
            pageHost = ""
            webView.loadUrl("about:blank")
            resolveJob = lifecycleScope.launch {
                try {
                    val p = Source.resolve(kind, id, season, episode)
                    if (token != attempt) return@launch
                    subtitleTracks = p.subtitles
                    trying = Stream(p.url, p.headers, hls = true)
                    play(trying!!)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (token == attempt) sourceFailed()
                }
            }
            return
        }

        val url = source.url(kind, id, season, episode)
        pageHost = android.net.Uri.parse(url).host.orEmpty()
        webView.onResume()
        webView.loadUrl(url, mapOf("Referer" to "https://braflix.at/"))
        if (source.web) { showWeb(reload = false); return }
        // Many players wait for a tap on play; tap the middle for the viewer.
        listOf(5_000L, 9_000L, 13_000L).forEach { d -> main.postDelayed({ if (token == attempt && phase == Phase.FINDING && trying == null) tapCentre() }, d) }
        main.postDelayed({ if (token == attempt && phase == Phase.FINDING && trying == null) sourceFailed() }, 18_000)
    }

    /** This source couldn't play: try the next one, or show its page when it was picked by hand. */
    private fun sourceFailed() {
        failedSources.add(sourceIndex)
        if (auto && autoTried < 5) {
            autoTried++
            val next = Sources.fallbackOrder.firstOrNull { it !in failedSources }
            if (next != null) { start(next, automatic = true); return }
        }
        if (source.direct) { phase = Phase.FAILED; return }
        showWeb(reload = false)
    }

    private fun tapCentre() {
        val x = webView.width / 2f; val y = webView.height / 2f
        val t = SystemClock.uptimeMillis()
        webView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        webView.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun showWeb(reload: Boolean) {
        if (phase == Phase.NATIVE) savePosition()
        exo.pause()
        playerView.visibility = View.GONE
        if (reload) {
            if (source.direct) { start(Sources.fallbackOrder.first(), automatic = false); return }
            attempt++
            candidates.clear(); trying = null
            exo.stop()
            webView.onResume()
            webView.loadUrl(source.url(kind, id, season, episode), mapOf("Referer" to "https://braflix.at/"))
        }
        phase = Phase.WEB
        webBar = true
        main.postDelayed({ if (phase == Phase.WEB) webBar = false }, 5000)
    }

    private fun onCandidate(s: Stream) {
        if (s.url in rejected || candidates.any { it.url == s.url }) return
        candidates.add(s)
        if (trying == null && phase != Phase.NATIVE) tryNext()
    }

    private fun tryNext() {
        val next = candidates.sortedByDescending { it.hls }.firstOrNull { it.url !in rejected } ?: run { trying = null; return }
        trying = next
        status = "Starting ${source.name}"
        val token = attempt
        lifecycleScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(4_000) { subtitleJob?.join() }
            if (token == attempt && trying == next) play(next)
        }
    }

    private fun play(s: Stream) {
        val cookie = CookieManager.getInstance().getCookie(s.url)
        val headers = s.headers.filterKeys { k -> k.lowercase() in setOf("referer", "origin", "user-agent", "accept-language") }.toMutableMap()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        val ua = headers.remove("User-Agent") ?: headers.remove("user-agent") ?: webView.settings.userAgentString
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val pageTracks = pageSubs.filter { u -> subtitleTracks.none { it.url == u } }.take(4).mapIndexed { i, u -> Subtitle("Source ${i + 1}", "und", u) }
        val subs = (subtitleTracks + pageTracks).map { t ->
            val local = shiftedSubs[t.url]
            MediaItem.SubtitleConfiguration.Builder(if (local != null) android.net.Uri.fromFile(java.io.File(local)) else android.net.Uri.parse(t.url))
                .setMimeType(if (t.srt) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                .setLanguage(t.lang.takeIf { it != "und" }).setLabel(t.label)
                .build()
        }
        val item = MediaItem.Builder().setUri(s.url)
            .setMimeType(if (s.hls) MimeTypes.APPLICATION_M3U8 else null)
            .setSubtitleConfigurations(subs)
            .build()
        exo.setMediaSource(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http)).createMediaSource(item))
        exo.prepare()
        lastHeaders = headers
    }

    private val shiftedSubs = mutableMapOf<String, String>()
    private var lastHeaders: Map<String, String> = emptyMap()

    /** Short clips from a page are ads; anything real takes the screen and the page is shut down. */
    private fun onReady() {
        val s = trying ?: return
        if (phase == Phase.NATIVE) return
        val d = exo.duration
        if (offlineId != null) { phase = Phase.NATIVE; playerView.visibility = View.VISIBLE; store.position(posKey())?.let { (p, _) -> if (p > 30_000) exo.seekTo(p) }; exo.play(); return }
        if (!source.direct && d != C.TIME_UNSET && d in 1 until 150_000) {
            rejected.add(s.url); trying = null; exo.stop(); tryNext(); return
        }
        val rt = runtimeMs
        if (rt > 0 && d != C.TIME_UNSET && d > 0 && kotlin.math.abs(d - rt).toDouble() / rt > 0.3) {
            // Far from the real runtime: this source is playing a different title.
            rejected.add(s.url); trying = null; exo.stop()
            if (candidates.any { it.url !in rejected }) tryNext() else sourceFailed()
            return
        }
        phase = Phase.NATIVE
        playerView.visibility = View.VISIBLE
        store.position(posKey())?.let { (p, dur) -> if (p > 30_000 && (dur <= 0 || p < dur - 60_000)) exo.seekTo(p) }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setPreferredTextLanguage("en").build()
        exo.play()
        failedSources.remove(sourceIndex)
        if (offlineId == null) store.setSourceFor(title ?: Title(id, kind, name, "", null, null, null, 0.0), source.name)
        controlsVisible = true
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.onPause()
    }

    private fun onStreamFailed() {
        trying?.let { rejected.add(it.url) }
        trying = null
        if (phase == Phase.NATIVE) { savePosition(); sourceFailed(); return }
        if (source.direct) { sourceFailed(); return }
        if (candidates.any { it.url !in rejected }) tryNext()
    }

    private fun nextEpisode() {
        savePosition()
        if (episodeCount in 1..episode && season < seasonCount) { season += 1; episode = 1; lifecycleScope.launch { loadEpisodeInfo() } }
        else episode += 1
        updateLabel()
        title?.let { store.watched(it, season, episode) }
        failedSources.clear()
        autoTried = 0
        start(sourceIndex, automatic = true)
    }

    private fun savePosition() {
        if (phase != Phase.NATIVE) return
        store.savePosition(posKey(), exo.currentPosition, exo.duration.takeIf { it != C.TIME_UNSET } ?: 0)
    }

    private suspend fun loadEpisodeInfo() {
        try {
            episodesList = Tmdb.episodes(id, season)
            episodeCount = episodesList.size.takeIf { it > 0 } ?: Tmdb.episodeCount(id, season)
            seasonCount = Tmdb.seasonCount(id)
        } catch (_: Exception) { }
    }

    private fun playEpisode(e: Int) {
        savePosition()
        episode = e
        updateLabel()
        title?.let { store.watched(it, season, episode) }
        failedSources.clear(); autoTried = 0
        start(sourceIndex, automatic = true)
    }

    /** Plays a finished download straight from the phone. */
    private fun playOffline(id: String) {
        val d = Downloads.find(this, id) ?: run { phase = Phase.FAILED; return }
        val info = DownloadInfo.from(d.request.data)
        val sub = info?.subtitle?.let { path ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(java.io.File(path)))
                .setMimeType(MimeTypes.TEXT_VTT).setLanguage("en").setLabel("English").build()
        }
        val item = d.request.toMediaItem().buildUpon().setSubtitleConfigurations(listOfNotNull(sub)).build()
        exo.setMediaSource(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, Downloads.offlineFactory(this))).createMediaSource(item))
        exo.prepare()
        trying = Stream(d.request.uri.toString(), emptyMap(), hls = true)
        status = "Playing from Downloads"
    }

    private fun refreshDownloadLabel() {
        val t = title ?: return
        val d = Downloads.find(this, Downloads.idFor(t, season, episode))
        downloadLabelState = when {
            offlineId != null -> "Downloaded"
            d == null -> null
            d.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED -> "Downloaded"
            d.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED -> "Retry download"
            else -> "Downloading ${d.percentDownloaded.toInt().coerceAtLeast(0)}%"
        }
    }

    private fun startDownload() {
        val t = title ?: Title(id, kind, name, "", null, null, null, 0.0)
        val s = trying ?: return
        val existing = Downloads.find(this, Downloads.idFor(t, season, episode))
        if (existing != null && existing.state != androidx.media3.exoplayer.offline.Download.STATE_FAILED) {
            Toast.makeText(this, if (existing.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) "Already downloaded" else "Already downloading", Toast.LENGTH_SHORT).show()
            return
        }
        val english = subtitleTracks.firstOrNull { it.lang == "en" } ?: subtitleTracks.firstOrNull()
        Downloads.notificationChannel(this)
        Downloads.start(this, DownloadInfo(t, season, episode, lastHeaders, null), s.url, s.hls, english?.url) { err ->
            Toast.makeText(this, err ?: "Downloading — see Downloads", Toast.LENGTH_SHORT).show()
            refreshDownloadLabel()
        }
    }

    private fun applySubtitleStyle() {
        val sv: SubtitleView = playerView.subtitleView ?: return
        val face = ResourcesCompat.getFont(this, R.font.jakarta_semibold)
        sv.setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE,
                if (subBoxState) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                if (subBoxState) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK, face,
            )
        )
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * listOf(.85f, 1f, 1.25f, 1.5f)[subSizeState])
        sv.setApplyEmbeddedStyles(false)
    }

    /** Re-times the external subtitles by [ms] and reloads them in place. */
    private fun shiftSubtitles(ms: Long) {
        subOffsetState = ms
        val s = trying ?: return
        val tracks = subtitleTracks
        if (tracks.isEmpty()) { Toast.makeText(this, "Timing works on Movio's subtitles; pick one in CC", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val dir = java.io.File(cacheDir, "subs").apply { mkdirs() }
            shiftedSubs.clear()
            tracks.forEach { t -> SubtitleSync.shifted(dir, t, lastHeaders, ms)?.let { shiftedSubs[t.url] = it } }
            val pos = exo.currentPosition
            val lang = exo.trackSelectionParameters.preferredTextLanguages.firstOrNull()
            play(s)
            exo.seekTo(pos)
            if (lang != null) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setPreferredTextLanguage(lang).build()
        }
    }

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val host = object : PlayerHost {
        override val label get() = this@PlayerActivity.label
        override val source get() = if (offlineId != null) "Downloaded" else this@PlayerActivity.source.name
        override val isTv get() = kind == Kind.TV
        override val season get() = this@PlayerActivity.season
        override val episode get() = this@PlayerActivity.episode
        override val episodes get() = episodesList
        override val hasNext get() = kind == Kind.TV && offlineId == null && (episodeCount == 0 || episode < episodeCount || season < seasonCount)
        override val aspectName get() = listOf("Fit", "Fill", "Stretch")[aspect]
        override val downloadLabel get() = downloadLabelState
        override val subSize get() = subSizeState
        override val subBox get() = subBoxState
        override val subOffset get() = subOffsetState
        override val autoplay get() = store.autoplayNext
        override fun back() = finish()
        override fun next() = nextEpisode()
        override fun web() = showWeb(reload = true)
        override fun sources() { sourcesOpen = true }
        override fun download() = startDownload()
        override fun playEpisode(e: Int) = this@PlayerActivity.playEpisode(e)
        override fun cycleAspect() {
            aspect = (aspect + 1) % 3
            playerView.resizeMode = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)[aspect]
        }
        override fun brightness(delta: Float): Float {
            val lp = window.attributes
            val cur = if (lp.screenBrightness < 0) .5f else lp.screenBrightness
            lp.screenBrightness = (cur + delta).coerceIn(.02f, 1f)
            window.attributes = lp
            return lp.screenBrightness
        }
        private var vol = -1f
        override fun volume(delta: Float): Float {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (vol < 0) vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            vol = (vol + delta).coerceIn(0f, 1f)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * max).toInt(), 0)
            return vol
        }
        override fun setSubSize(i: Int) { subSizeState = i; store.subSize = i; applySubtitleStyle() }
        override fun setSubBox(b: Boolean) { subBoxState = b; store.subBox = b; applySubtitleStyle() }
        override fun setSubOffset(ms: Long) = shiftSubtitles(ms)
    }

    /** TV remote: left/right seek and select shows the controls while they're hidden. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && phase == Phase.NATIVE && !sourcesOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { if (exo.isPlaying) exo.pause() else exo.play(); controlsVisible = true; return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { exo.seekTo(exo.currentPosition + 10_000); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); return true }
            }
            if (!controlsVisible) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { exo.seekTo(exo.currentPosition + 10_000); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { controlsVisible = true; return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun immersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && phase == Phase.WEB) {
            webBar = true
            main.removeCallbacksAndMessages("bar")
            main.postAtTime({ webBar = false }, "bar", SystemClock.uptimeMillis() + 4000)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------- Page ----------

    /** Called from every frame of a source page (sniff.js / player.js). */
    inner class Bridge {
        @JavascriptInterface
        fun stream(url: String, page: String) = found(url, page, hls = true)

        @JavascriptInterface
        fun video(url: String, page: String) = found(url, page, hls = false)

        @JavascriptInterface
        fun state(s: String) {}

        @JavascriptInterface
        fun playing() {
            main.postDelayed({ if (phase == Phase.FINDING && trying == null && !source.direct) showWeb(reload = false) }, 2500)
        }

        private fun found(url: String, page: String, hls: Boolean) {
            val host = android.net.Uri.parse(url).host ?: return
            if (AdBlock.blocks(host)) return
            val p = android.net.Uri.parse(page)
            val origin = "${p.scheme}://${p.host}"
            val headers = mapOf("Referer" to "$origin/", "Origin" to origin)
            main.post { val token = attempt; if (phase == Phase.FINDING || phase == Phase.WEB) if (token == attempt) onCandidate(Stream(url, headers, hls)) }
        }
    }

    private val empty get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun sameSite(a: String, b: String): Boolean {
        fun root(h: String) = h.split('.').takeLast(2).joinToString(".")
        return a.isNotEmpty() && b.isNotEmpty() && root(a) == root(b)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setOnLongClickListener { true }
        webView.addJavascriptInterface(Bridge(), "MovioPlayer")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, feelJs, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, sniffJs, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, playerJs, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null || url == "about:blank") return
                view?.evaluateJavascript(feelJs, null)
                view?.evaluateJavascript(playerJs, null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true && phase == Phase.FINDING && !source.direct) sourceFailed()
            }

            // Every request from every frame passes here: ads are dropped, video playlists are caught.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val host = uri.host ?: return null
                if (!sameSite(host, pageHost) && AdBlock.blocks(host)) return empty
                val u = uri.toString()
                val path = (uri.path ?: "").lowercase()
                if ((path.endsWith(".vtt") || path.endsWith(".srt")) && !AdBlock.blocks(host)) main.post { if (phase == Phase.FINDING) pageSubs.add(u) }
                if (u.contains(".m3u8", ignoreCase = true) || Regex("/(stream|master|playlist|manifest)(\\.\\w+)?$").containsMatchIn(path) ||
                    (path.contains("/hls/") && !Regex("\\.(ts|m4s|mp4|aac|key|jpg|jpeg|png|webp|gif|vtt|js|css)$").containsMatchIn(path))) {
                    val headers = request.requestHeaders.orEmpty()
                    main.post { if (phase == Phase.FINDING || phase == Phase.WEB) onCandidate(Stream(u, headers, hls = true)) }
                }
                return null
            }

            // The page may follow its own redirects but never jump to another site (ad redirects).
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val h = request?.url?.host.orEmpty()
                if (AdBlock.blocks(h)) return true
                if (request?.isForMainFrame == false) return false
                if (sameSite(h, pageHost)) return false
                if (request?.isRedirect == true && request.hasGesture() == false) { pageHost = h; return false }
                return true
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                (view?.parent as? ViewGroup)?.removeView(view)
                view?.destroy()
                if (phase != Phase.NATIVE) finish()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) return
                customView = view
                fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreen.visibility = View.VISIBLE
                immersive()
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                fullscreen.removeView(v)
                customView = null
                fullscreen.visibility = View.GONE
                immersive()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (phase == Phase.NATIVE && exo.isPlaying || phase == Phase.WEB)) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) exo.pause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        savePosition()
        main.removeCallbacksAndMessages(null)
        exo.release()
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) { }
        super.onDestroy()
    }
}
