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
import android.view.MotionEvent
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Plays a title in Movio's own player. The title's page loads out of sight; the video stream it
 * requests is caught and handed to ExoPlayer with the page's headers, so the site's player and its
 * ads never show. If no stream can be caught, the page itself is shown as a fallback ("web player").
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val HOST = "braflix.at"
        fun start(context: Context, t: Title, season: Int, episode: Int) {
            Store.get(context).watched(t, season, episode)
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra("id", t.id).putExtra("kind", t.kind.name).putExtra("name", t.name)
                    .putExtra("s", season).putExtra("e", episode)
            )
        }
        fun url(id: Int, kind: Kind, s: Int, e: Int) =
            if (kind == Kind.TV) "https://$HOST/watch/$id?s=$s&e=$e" else "https://$HOST/watch/$id"
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
    private val playerJs by lazy { assets.open("player.js").bufferedReader().use { it.readText() } }
    private val store by lazy { Store.get(this) }

    private var phase by mutableStateOf(Phase.FINDING)
    private var label by mutableStateOf("")
    private var fill by mutableStateOf(false)
    private var webBar by mutableStateOf(true)
    private var pip by mutableStateOf(false)

    private var id = 0
    private var kind = Kind.MOVIE
    private var name = ""
    private var season = 1
    private var episode = 1
    private var title: Title? = null

    // Stream capture state for the current page.
    private val candidates = mutableListOf<Stream>()
    private val rejected = mutableSetOf<String>()
    private val subtitles = linkedSetOf<String>()
    private var trying: Stream? = null
    private var pageToken = 0
    private var resolveJob: Job? = null
    private var direct = true

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
        fullscreen = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(android.graphics.Color.BLACK) }
        val overlay = ComposeView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        overlay.setContent {
            if (!pip) Box(Modifier.fillMaxSize()) {
                when (phase) {
                    Phase.NATIVE -> PlayerControls(
                        exo, label, showNext = kind == Kind.TV, fill = fill,
                        onFill = { fill = !fill; playerView.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
                        onBack = { finish() }, onNext = { nextEpisode() }, onWeb = { showWeb(manual = true) },
                    )
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
                                Text("Web player · tap play to start", color = Muted, fontSize = 12.sp, style = jakarta())
                            }
                            if (kind == Kind.TV) { GhostButton("Next", R.drawable.ic_next) { nextEpisode() }; Spacer(Modifier.width(10.dp)) }
                            GhostButton("Movio player", R.drawable.ic_play_fill) { load() }
                        }
                    }
                    Phase.FINDING, Phase.FAILED -> Column(
                        Modifier.fillMaxSize().background(Ink), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        if (phase == Phase.FAILED) {
                            Message("Can't play right now", "Check your internet connection and try again.", "Try again") { load() }
                        } else {
                            CircularProgressIndicator(color = Violet, trackColor = Glass, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(18.dp))
                            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = jakarta())
                            Text("Getting your stream ready", color = Muted, fontSize = 13.sp, style = jakarta())
                        }
                    }
                }
                if (phase == Phase.FINDING || phase == Phase.FAILED) {
                    Box(Modifier.safeDrawingPadding().padding(16.dp)) { GlassIcon(R.drawable.ic_back, size = 42.dp) { finish() } }
                }
            }
        }

        setupWebView()
        load()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) webView.webChromeClient?.onHideCustomView() else finish()
            }
        })
    }

    private fun updateLabel() { label = if (kind == Kind.TV) "$name · S$season E$episode" else name }
    private fun posKey() = title?.let { store.posKey(it, season, episode) } ?: "${kind.api}:$id${if (kind == Kind.TV) ":$season:$episode" else ""}"

    /** Get the stream straight from the player service; fall back to the title's web page. */
    private fun load() {
        pageToken++
        candidates.clear(); subtitles.clear(); trying = null; rejected.clear()
        exo.stop(); exo.clearMediaItems()
        playerView.visibility = View.GONE
        phase = Phase.FINDING
        direct = true
        webView.stopLoading()
        webView.loadUrl("about:blank")
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            try {
                val p = Source.resolve(kind, id, season, episode)
                subtitleTracks = p.subtitles
                trying = Stream(p.url, p.headers, hls = true)
                play(trying!!)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loadPage()
            }
        }
    }

    /** Fallback: load the title's page out of sight and catch the stream it requests. */
    private fun loadPage() {
        direct = false
        pageToken++
        val token = pageToken
        candidates.clear(); subtitles.clear(); trying = null
        phase = Phase.FINDING
        webView.onResume()
        webView.loadUrl(url(id, kind, season, episode))
        // If the page waits for a tap, tap the middle of the player for the viewer.
        listOf(6_000L, 10_000L, 14_000L).forEach { d -> main.postDelayed({ if (token == pageToken && phase == Phase.FINDING && trying == null) tapCentre() }, d) }
        main.postDelayed({ if (token == pageToken && phase == Phase.FINDING && trying == null) showWeb(manual = false) }, 20_000)
    }

    private fun tapCentre() {
        val x = webView.width / 2f; val y = webView.height / 2f
        val t = SystemClock.uptimeMillis()
        webView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        webView.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun showWeb(manual: Boolean) {
        if (phase == Phase.NATIVE) savePosition()
        exo.pause()
        playerView.visibility = View.GONE
        phase = Phase.WEB
        webBar = true
        main.postDelayed({ if (phase == Phase.WEB) webBar = false }, 5000)
        if (manual) {
            // Bring the title's page back so it can play there.
            direct = false
            pageToken++
            candidates.clear(); trying = null
            exo.stop()
            webView.onResume()
            webView.loadUrl(url(id, kind, season, episode))
        }
    }

    private fun onCandidate(s: Stream) {
        if (s.url in rejected || candidates.any { it.url == s.url }) return
        candidates.add(s)
        if (trying == null && phase != Phase.NATIVE) tryNext()
    }

    private fun tryNext() {
        val next = candidates.sortedByDescending { it.hls }.firstOrNull { it.url !in rejected } ?: run { trying = null; return }
        trying = next
        play(next)
    }

    private var subtitleTracks: List<Subtitle> = emptyList()

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
        val subs = subtitleTracks.map { t ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(t.url))
                .setMimeType(if (t.url.endsWith(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                .setLanguage(t.lang).setLabel(t.label)
                .build()
        } + subtitles.take(6).map { u ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(u))
                .setMimeType(if (u.contains(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                .setLabel(u.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').take(24))
                .build()
        }
        val item = MediaItem.Builder().setUri(s.url)
            .setMimeType(if (s.hls) MimeTypes.APPLICATION_M3U8 else null)
            .setSubtitleConfigurations(subs)
            .build()
        exo.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        exo.prepare()
    }

    /** Short clips are ads; anything real gets the screen and the page is shut down. */
    private fun onReady() {
        val s = trying ?: return
        if (phase == Phase.NATIVE) return
        val d = exo.duration
        if (!direct && d != C.TIME_UNSET && d in 1 until 150_000) {
            rejected.add(s.url); trying = null; exo.stop(); tryNext(); return
        }
        phase = Phase.NATIVE
        playerView.visibility = View.VISIBLE
        store.position(posKey())?.let { (p, dur) -> if (p > 30_000 && (dur <= 0 || p < dur - 60_000)) exo.seekTo(p) }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setPreferredTextLanguage("en").build()
        exo.play()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.onPause()
    }

    private fun onStreamFailed() {
        trying?.let { rejected.add(it.url) }
        trying = null
        if (direct) { subtitleTracks = emptyList(); playerView.visibility = View.GONE; phase = Phase.FINDING; loadPage(); return }
        if (phase == Phase.NATIVE) { savePosition(); showWeb(manual = true); Toast.makeText(this, "Switched to the web player", Toast.LENGTH_SHORT).show(); return }
        tryNext()
    }

    private fun nextEpisode() {
        savePosition()
        episode += 1
        updateLabel()
        title?.let { store.watched(it, season, episode) }
        load()
    }

    private fun savePosition() {
        if (phase != Phase.NATIVE) return
        store.savePosition(posKey(), exo.currentPosition, exo.duration.takeIf { it != C.TIME_UNSET } ?: 0)
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

    inner class Bridge {
        @JavascriptInterface
        fun state(s: String) {
            main.post { if (s == "challenge" && phase == Phase.FINDING) showWeb(manual = false) }
        }
    }

    private val empty get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

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
            WebViewCompat.addDocumentStartJavaScript(webView, playerJs, setOf("https://$HOST", "https://*.$HOST"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "about:blank") return
                view?.evaluateJavascript(feelJs, null)
                val h = android.net.Uri.parse(url ?: "").host.orEmpty()
                if (h == HOST || h.endsWith(".$HOST")) view?.evaluateJavascript(playerJs, null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true && phase != Phase.NATIVE) phase = Phase.FAILED
            }

            // Every request from every frame passes here: ads are dropped, video streams are caught.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val host = uri.host ?: return null
                val own = host == HOST || host.endsWith(".$HOST")
                if (!own && AdBlock.blocks(host)) return empty
                val u = uri.toString()
                val path = (uri.path ?: "").lowercase()
                val token = pageToken
                when {
                    u.contains(".m3u8", ignoreCase = true) || Regex("/(stream|master|playlist|manifest)(\\.\\w+)?$").containsMatchIn(path) ->
                        main.post { if (token == pageToken) onCandidate(Stream(u, request.requestHeaders.orEmpty(), hls = true)) }
                    path.endsWith(".mp4") && !path.contains("preview") && !path.contains("thumb") ->
                        main.post { if (token == pageToken) onCandidate(Stream(u, request.requestHeaders.orEmpty(), hls = false)) }
                    path.endsWith(".vtt") || path.endsWith(".srt") ->
                        main.post { if (token == pageToken) subtitles.add(u) }
                }
                return null
            }

            // The page may never leave the site: redirects to ads go nowhere.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val h = request?.url?.host.orEmpty()
                if (request?.isForMainFrame == false) return AdBlock.blocks(h)
                return !(h == HOST || h.endsWith(".$HOST"))
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
