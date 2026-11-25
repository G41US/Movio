package com.g41us.movio

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

class MainActivity : AppCompatActivity() {

    private val host = "braflix.at"
    private val homeUrl = "https://$host/"

    private enum class Tab(val label: String, val icon: Int) {
        HOME("Home", R.drawable.ic_home),
        MOVIES("Movies", R.drawable.ic_movies),
        TV("TV Shows", R.drawable.ic_tv),
        SEARCH("Search", R.drawable.ic_search),
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var splash: View
    private lateinit var errorView: View
    private lateinit var topBar: LinearLayout
    private lateinit var dockWrap: FrameLayout
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var settingsView: ScrollView
    private lateinit var settingsIcon: ImageView
    private lateinit var backBtn: View
    private lateinit var screenTitle: TextView
    private lateinit var wordFlix: View
    private lateinit var wordEr: TextView

    private val dockItems = mutableMapOf<Tab, Pair<View, TextView>>()
    private var tab = Tab.HOME
    private var fullscreenView: View? = null
    private var doubleBackToExitPressedOnce = false
    private var mainFrameError = false
    private var pendingSearch: String? = null
    private var captureCount = 0
    private var webViewDead = false
    private var downloadedApk: File? = null
    private val main = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("movio", Context.MODE_PRIVATE) }
    private val feelJs by lazy { assets.open("feel.js").bufferedReader().use { it.readText() } }
    private val siteJs by lazy { assets.open("site.js").bufferedReader().use { it.readText() } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        progress = findViewById(R.id.progress)
        splash = findViewById(R.id.splash)
        errorView = findViewById(R.id.errorView)
        topBar = findViewById(R.id.topBar)
        dockWrap = findViewById(R.id.dockWrap)
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        settingsView = findViewById(R.id.settingsView)
        settingsIcon = findViewById(R.id.settingsIcon)
        backBtn = findViewById(R.id.backBtn)
        screenTitle = findViewById(R.id.screenTitle)
        wordFlix = findViewById(R.id.wordFlix)
        wordEr = findViewById(R.id.wordEr)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        gradientText(wordEr)
        gradientText(findViewById(R.id.splashEr))
        findViewById<View>(R.id.splashLogo).apply {
            scaleX = .8f; scaleY = .8f; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(500).start()
        }

        applyInsets()
        buildDock()
        setupWebView()
        setupSearch()
        setupSettings()

        swipeRefresh.setColorSchemeResources(R.color.violet, R.color.pink)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.panel)
        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }

        findViewById<View>(R.id.retryBtn).setOnClickListener {
            mainFrameError = false
            errorView.visibility = View.GONE
            webView.reload()
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            if (settingsView.visibility == View.VISIBLE) closeSettings() else openSettings()
        }
        backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        if (savedInstanceState == null) webView.loadUrl(homeUrl) else webView.restoreState(savedInstanceState)
        main.postDelayed({ hideSplash() }, 15000)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> webView.webChromeClient?.onHideCustomView()
                    settingsView.visibility == View.VISIBLE -> closeSettings()
                    errorView.visibility == View.VISIBLE && webView.canGoBack() -> {
                        errorView.visibility = View.GONE; mainFrameError = false; webView.goBack()
                    }
                    webView.canGoBack() -> webView.goBack()
                    tab != Tab.HOME -> openTab(Tab.HOME)
                    else -> handleExit()
                }
            }
        })
    }

    // ---------- Layout ----------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun gradientText(tv: TextView) {
        tv.post {
            tv.paint.shader = LinearGradient(
                0f, 0f, tv.width.toFloat(), 0f,
                ContextCompat.getColor(this, R.color.violet), ContextCompat.getColor(this, R.color.pink),
                Shader.TileMode.CLAMP
            )
            tv.invalidate()
        }
    }

    /** Top bar sits under the status bar / camera hole, the dock above the gesture bar. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            topBar.updatePadding(left = bars.left + dp(20f).toInt(), top = bars.top + dp(10f).toInt(), right = bars.right + dp(16f).toInt())
            dockWrap.updatePadding(left = bars.left + dp(14f).toInt(), right = bars.right + dp(14f).toInt(), bottom = bars.bottom + dp(10f).toInt())
            findViewById<View>(R.id.shell).updatePadding(bottom = if (imeOpen) ime.bottom else 0)
            dockWrap.visibility = if (imeOpen || fullscreenView != null) View.GONE else View.VISIBLE
            settingsView.updatePadding(bottom = bars.bottom)
            pushDockSpace()
            insets
        }
    }

    /** Reserve exactly the dock's height at the bottom of pages so nothing hides behind it. */
    private fun pushDockSpace() {
        if (webViewDead) return
        dockWrap.post {
            val css = (dockWrap.height / resources.displayMetrics.density).toInt() + 12
            webView.evaluateJavascript("document.documentElement.style.setProperty('--fx-dock','${css}px')", null)
        }
    }

    private fun buildDock() {
        val dock = findViewById<LinearLayout>(R.id.dock)
        val jakartaBold = ResourcesCompat.getFont(this, R.font.jakarta_bold)
        Tab.values().forEach { t ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                setOnClickListener { openTab(t) }
            }
            val iconBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52f).toInt(), dp(30f).toInt())
            }
            val pill = View(this).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_pill)
                layoutParams = FrameLayout.LayoutParams(dp(50f).toInt(), dp(30f).toInt(), Gravity.CENTER)
                scaleX = 0f; alpha = 0f
            }
            val icon = ImageView(this).apply {
                setImageResource(t.icon)
                layoutParams = FrameLayout.LayoutParams(dp(21f).toInt(), dp(21f).toInt(), Gravity.CENTER)
                tag = "icon"
            }
            iconBox.addView(pill); iconBox.addView(icon)
            val label = TextView(this).apply {
                text = t.label
                textSize = 10.5f
                typeface = jakartaBold
                maxLines = 1
                setPadding(0, dp(3f).toInt(), 0, 0)
            }
            item.addView(iconBox); item.addView(label)
            dock.addView(item)
            dockItems[t] = pill to label
            item.tag = icon
        }
        highlight(Tab.HOME)
    }

    private fun highlight(t: Tab) {
        tab = t
        val white = ContextCompat.getColor(this, R.color.white)
        val muted = ContextCompat.getColor(this, R.color.muted)
        dockItems.forEach { (k, v) ->
            val active = k == t
            val (pill, label) = v
            pill.animate().scaleX(if (active) 1f else 0f).alpha(if (active) 1f else 0f).setDuration(220).start()
            label.setTextColor(if (active) white else muted)
            ((label.parent as View).tag as ImageView).setColorFilter(if (active) white else muted)
        }
    }

    // ---------- Tabs ----------

    private fun tabUrl(t: Tab): String = when (t) {
        Tab.HOME -> homeUrl
        Tab.MOVIES -> prefs.getString("movies", null) ?: "https://$host/movies/"
        Tab.TV -> prefs.getString("tv", null) ?: "https://$host/tv-shows/"
        Tab.SEARCH -> homeUrl
    }

    private fun openTab(t: Tab) {
        if (webViewDead) { recreate(); return }
        if (settingsView.visibility == View.VISIBLE) closeSettings()
        val again = t == tab
        highlight(t)
        if (t == Tab.SEARCH) {
            searchBar.visibility = View.VISIBLE
            searchInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput, 0)
            return
        }
        hideSearch()
        if (again) webView.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null)
        else webView.loadUrl(tabUrl(t))
    }

    private fun hideSearch() {
        searchBar.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun setupSearch() {
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) runSearch(q)
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
                true
            } else false
        }
    }

    private fun runSearch(q: String) {
        if (webViewDead) { recreate(); return }
        webView.evaluateJavascript("window.__movioSearch ? window.__movioSearch(${JSONObject.quote(q)}) : false") { r ->
            if (r != "true") {
                pendingSearch = q
                webView.loadUrl(homeUrl)
            }
        }
    }

    /** Keep the dock in step with the page that's showing. */
    private fun syncTab(url: String) {
        if (settingsView.visibility == View.VISIBLE) return
        val t = when {
            url.trimEnd('/') == tabUrl(Tab.MOVIES).trimEnd('/') -> Tab.MOVIES
            url.trimEnd('/') == tabUrl(Tab.TV).trimEnd('/') -> Tab.TV
            url.trimEnd('/') == homeUrl.trimEnd('/') || url.trimEnd('/') == "https://$host" -> if (tab == Tab.SEARCH) Tab.SEARCH else Tab.HOME
            Regex("[?&/](search|keyword|s)[=/]", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Tab.SEARCH
            else -> tab
        }
        if (t != tab) highlight(t)
        if (t != Tab.SEARCH) searchBar.visibility = View.GONE
    }

    // ---------- Settings ----------

    private fun openSettings() {
        hideSearch()
        settingsView.visibility = View.VISIBLE
        settingsView.alpha = 0f
        settingsView.translationY = dp(24f)
        settingsView.animate().alpha(1f).translationY(0f).setDuration(260).start()
        settingsIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))
        findViewById<View>(R.id.settingsBtn).background = ContextCompat.getDrawable(this, R.drawable.bg_dock_pill)
        backBtn.visibility = View.VISIBLE
        wordFlix.visibility = View.GONE; wordEr.visibility = View.GONE
        screenTitle.text = "Settings"; screenTitle.visibility = View.VISIBLE
        dockItems.values.forEach { (pill, label) ->
            pill.animate().scaleX(0f).alpha(0f).setDuration(180).start()
            label.setTextColor(ContextCompat.getColor(this, R.color.muted))
            ((label.parent as View).tag as ImageView).setColorFilter(ContextCompat.getColor(this, R.color.muted))
        }
    }

    private fun closeSettings() {
        settingsView.visibility = View.GONE
        settingsIcon.setColorFilter(ContextCompat.getColor(this, R.color.muted))
        findViewById<View>(R.id.settingsBtn).background = ContextCompat.getDrawable(this, R.drawable.bg_glass_circle)
        backBtn.visibility = View.GONE
        screenTitle.visibility = View.GONE
        wordFlix.visibility = View.VISIBLE; wordEr.visibility = View.VISIBLE
        highlight(tab)
    }

    private fun setupSettings() {
        findViewById<TextView>(R.id.versionText).text = "Version ${Updates.installedName(this)}"
        findViewById<View>(R.id.updateBtn).setOnClickListener { onUpdateTap() }
        findViewById<View>(R.id.clearCacheBtn).setOnClickListener {
            webView.clearCache(true)
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUi(status: String, button: String?, progressPct: Int? = null) {
        findViewById<TextView>(R.id.updateStatus).text = status
        val btn = findViewById<TextView>(R.id.updateBtn)
        btn.visibility = if (button == null) View.GONE else View.VISIBLE
        if (button != null) btn.text = button
        val bar = findViewById<ProgressBar>(R.id.updateProgress)
        bar.visibility = if (progressPct == null) View.GONE else View.VISIBLE
        if (progressPct != null) { bar.isIndeterminate = progressPct < 0; bar.progress = progressPct }
    }

    private fun onUpdateTap() {
        downloadedApk?.let { if (it.exists()) { installUpdate(it); return } }
        updateUi("Checking for updates…", null, -1)
        Thread {
            try {
                val info = Updates.latest()
                if (info == null) {
                    main.post { updateUi("You're on the latest version.", "Check again") }
                    return@Thread
                }
                main.post { updateUi("Downloading version ${info.versionName}…", null, 0) }
                val apk = Updates.download(this, info) { pct -> main.post { updateUi("Downloading version ${info.versionName}…", null, pct) } }
                downloadedApk = apk
                main.post {
                    updateUi("Version ${info.versionName} is ready.", "Install update")
                    installUpdate(apk)
                }
            } catch (e: Exception) {
                main.post { updateUi(e.message ?: "Couldn't check for updates. Try again.", "Try again") }
            }
        }.start()
    }

    private fun installUpdate(apk: File) {
        if (!Updates.install(this, apk)) {
            Toast.makeText(this, "Allow Movio to install updates, then tap Install update", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isHapticFeedbackEnabled = false
        webView.setOnLongClickListener { true }
        webView.addJavascriptInterface(Bridge(), "MovioApp")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, feelJs, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, siteJs, setOf("https://$host", "https://*.$host"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                progress.progress = 5
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                progress.visibility = View.GONE
                view?.evaluateJavascript(feelJs, null)
                if (url != null && (Uri(url) == host || Uri(url).endsWith(".$host"))) view?.evaluateJavascript(siteJs, null)
                if (!mainFrameError) main.postDelayed({ hideSplash() }, 350)
                url?.let { syncTab(it) }
                pushDockSpace()
                pendingSearch?.let { q ->
                    main.postDelayed({
                        webView.evaluateJavascript("window.__movioSearch ? window.__movioSearch(${JSONObject.quote(q)}) : false") { r ->
                            if (r == "true") pendingSearch = null
                        }
                    }, 600)
                }
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    mainFrameError = true
                    errorView.visibility = View.VISIBLE
                    hideSplash()
                }
            }

            // If the page engine crashes, keep the app open and offer a retry.
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                view?.destroy()
                webViewDead = true
                mainFrameError = true
                errorView.visibility = View.VISIBLE
                findViewById<View>(R.id.retryBtn).setOnClickListener { recreate() }
                hideSplash()
                return true
            }

            // App fonts for the site, served from the APK with CORS so the page may use them.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                if (u.host == "appassets.androidplatform.net" && u.path?.startsWith("/fonts/") == true) {
                    return try {
                        WebResourceResponse("font/ttf", null, assets.open("fonts/" + u.lastPathSegment)).apply {
                            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")
                        }
                    } catch (_: Exception) { null }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val h = request?.url?.host.orEmpty()
                return !(h == host || h.endsWith(".$host"))
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullscreenView != null) return
                fullscreenView = view
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                setVideoFullscreen(true)
            }

            override fun onHideCustomView() {
                if (fullscreenView == null) return
                fullscreenContainer.removeView(fullscreenView)
                fullscreenView = null
                fullscreenContainer.visibility = View.GONE
                setVideoFullscreen(false)
            }
        }
    }

    @Suppress("FunctionName")
    private fun Uri(url: String): String = android.net.Uri.parse(url).host.orEmpty()

    inner class Bridge {
        /** Movies / TV page addresses found on the site, so the dock opens the right pages. */
        @JavascriptInterface
        fun links(json: String) {
            try {
                val o = JSONObject(json)
                val e = prefs.edit()
                listOf("movies", "tv").forEach { k ->
                    val v = o.optString(k)
                    val h = Uri(v)
                    if (v.startsWith("https://") && (h == host || h.endsWith(".$host"))) e.putString(k, v)
                }
                e.apply()
            } catch (_: Exception) { }
        }
    }

    private fun setVideoFullscreen(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            dockWrap.visibility = View.GONE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            dockWrap.visibility = View.VISIBLE
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.root))
    }

    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE) return
        splash.animate().alpha(0f).setDuration(300).withEndAction { splash.visibility = View.GONE }.start()
    }

    // Hidden tool: tap with 3 fingers to save the page's code to Downloads/MovioCapture
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 3) capturePage()
        return super.dispatchTouchEvent(ev)
    }

    private fun capturePage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val url = webView.url.orEmpty()
        webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            try {
                val html = JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                captureCount++
                val name = "page-${System.currentTimeMillis() / 1000}-$captureCount.html"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/html")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MovioCapture")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@evaluateJavascript
                contentResolver.openOutputStream(uri)?.use { it.write("<!-- URL: $url -->\n$html".toByteArray()) }
                Toast.makeText(this, "Saved $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDead) webView.saveState(outState)
    }

    private fun handleExit() {
        if (doubleBackToExitPressedOnce) { finish(); return }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
        main.postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fullscreenView != null) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }
}
