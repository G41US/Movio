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
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/** Full-screen player: opens the title's player page with ads blocked and the page hidden around the video. */
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

    private lateinit var webView: WebView
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val feelJs by lazy { assets.open("feel.js").bufferedReader().use { it.readText() } }
    private val playerJs by lazy { assets.open("player.js").bufferedReader().use { it.readText() } }

    private var loading by mutableStateOf(true)
    private var controls by mutableStateOf(true)
    private var focusOn by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var season = 1
    private var episode = 1
    private var label by mutableStateOf("")

    private val hideControls = Runnable { controls = false }

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

        val id = intent.getIntExtra("id", 0)
        val kind = Kind.valueOf(intent.getStringExtra("kind") ?: "MOVIE")
        val name = intent.getStringExtra("name").orEmpty()
        season = intent.getIntExtra("s", 1)
        episode = intent.getIntExtra("e", 1)
        fun updateLabel() { label = if (kind == Kind.TV) "$name · S$season E$episode" else name }
        updateLabel()

        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        webView = WebView(this)
        fullscreen = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(android.graphics.Color.BLACK) }
        val overlay = ComposeView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        overlay.setContent {
            Box(Modifier.fillMaxSize()) {
                AnimatedVisibility(loading, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxSize().background(Ink), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (failed) {
                            Message("Can't play right now", "Check your internet connection and try again.", "Try again") {
                                failed = false; webView.reload()
                            }
                        } else {
                            CircularProgressIndicator(color = Violet, trackColor = Glass, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(18.dp))
                            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = jakarta())
                            Text("Getting your stream ready", color = Muted, fontSize = 13.sp, style = jakarta())
                        }
                    }
                }
                AnimatedVisibility(controls && !loading, enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
                            .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIcon(R.drawable.ic_back, size = 42.dp) { finish() }
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = jakarta(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (kind == Kind.TV) {
                            GhostButton("Next episode", R.drawable.ic_play_fill) {
                                episode += 1
                                updateLabel()
                                Store.get(this@PlayerActivity).watched(
                                    Title(id, kind, name, "", null, null, null, 0.0).let { t ->
                                        Store.get(this@PlayerActivity).lastEpisode(t)?.title ?: t
                                    }, season, episode,
                                )
                                loading = true
                                webView.loadUrl(url(id, kind, season, episode))
                            }
                            Spacer(Modifier.width(10.dp))
                        }
                        GlassIcon(R.drawable.ic_servers, active = !focusOn, size = 42.dp) {
                            focusOn = !focusOn
                            webView.evaluateJavascript("window.__mpToggle && window.__mpToggle($focusOn)", null)
                        }
                    }
                }
            }
        }

        setupWebView()
        if (savedInstanceState == null) webView.loadUrl(url(id, kind, season, episode)) else webView.restoreState(savedInstanceState)
        main.postDelayed({ loading = false }, 20_000)
        showControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) webView.webChromeClient?.onHideCustomView() else finish()
            }
        })
    }

    private fun immersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showControls() {
        controls = true
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, 3500)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showControls()
        return super.dispatchTouchEvent(ev)
    }

    inner class Bridge {
        @JavascriptInterface
        fun state(s: String) {
            main.post {
                if (s == "player" || s == "challenge") loading = false
            }
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
                view?.evaluateJavascript(feelJs, null)
                val h = android.net.Uri.parse(url ?: "").host.orEmpty()
                if (h == HOST || h.endsWith(".$HOST")) view?.evaluateJavascript(playerJs, null)
                main.postDelayed({ if (!failed) loading = false }, 2500)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) { failed = true; loading = true }
            }

            // Ads and trackers are dropped before they load, in every frame.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null
                if (host == HOST || host.endsWith(".$HOST")) return null
                return if (AdBlock.blocks(host)) empty else null
            }

            // The page itself may never leave the site: redirects to ads go nowhere.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val h = request?.url?.host.orEmpty()
                if (request?.isForMainFrame == false) return AdBlock.blocks(h)
                return !(h == HOST || h.endsWith(".$HOST"))
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                (view?.parent as? ViewGroup)?.removeView(view)
                view?.destroy()
                finish()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) controls = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
