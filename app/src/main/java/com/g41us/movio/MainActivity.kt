package com.g41us.movio

import android.graphics.Color as AColor
import android.os.Bundle
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data class Details(val title: Title) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AdBlock.warm(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this).crossfade(true)
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizePercent(.04).build() }
                .build()
        )
        setContent { App() }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { Store.get(context) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val stack = remember { mutableStateListOf<Screen>() }
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var apk by remember { mutableStateOf<File?>(null) }
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1100); splash = false }

    val top = stack.lastOrNull()
    BackHandler(enabled = top != null || tab != Tab.HOME) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.HOME
    }

    fun open(t: Title) { stack.add(Screen.Details(t)) }
    fun play(t: Title, s: Int = 1, e: Int = 1) {
        val last = store.lastEpisode(t)
        if (t.kind == Kind.TV && s == 1 && e == 1 && last != null) PlayerActivity.start(context, t, last.season, last.episode)
        else PlayerActivity.start(context, t, s, e)
    }
    fun onUpdate() {
        apk?.let { if (it.exists()) { Updates.install(context, it); return } }
        scope.launch {
            update = UpdateState.Checking
            update = try {
                val info = withContext(Dispatchers.IO) { Updates.latest() }
                if (info == null) UpdateState.UpToDate else {
                    update = UpdateState.Downloading(info.versionName, 0)
                    val file = withContext(Dispatchers.IO) {
                        Updates.download(context, info) { pct -> scope.launch { update = UpdateState.Downloading(info.versionName, pct) } }
                    }
                    apk = file
                    if (!Updates.install(context, file)) Toast.makeText(context, "Allow Movio to install updates, then tap Install update", Toast.LENGTH_LONG).show()
                    UpdateState.Ready(info.versionName)
                }
            } catch (e: Exception) { UpdateState.Failed(e.message ?: "Couldn't check for updates. Try again.") }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(top ?: tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "screen") { s ->
            when (s) {
                is Screen.Details -> DetailsScreen(s.title, store, ::open) { t, se, ep -> PlayerActivity.start(context, t, se, ep) }
                Screen.Settings -> SettingsScreen(Updates.installedName(context), update, AdBlock.size, ::onUpdate) {
                    context.cacheDir.resolve("images").deleteRecursively()
                    WebStorage.getInstance().deleteAllData()
                    Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                }
                Tab.HOME -> FeedScreen(null, store, ::open) { play(it) }
                Tab.MOVIES -> FeedScreen(Kind.MOVIE, store, ::open) { play(it) }
                Tab.TV -> FeedScreen(Kind.TV, store, ::open) { play(it) }
                Tab.SEARCH -> SearchScreen(::open)
                Tab.LIST -> MyListScreen(store, ::open)
                else -> {}
            }
        }
        TopBar(
            title = when {
                top is Screen.Settings -> "Settings"
                top is Screen.Details -> null
                tab == Tab.SEARCH -> "Search"
                tab == Tab.LIST -> "My List"
                else -> null
            },
            onBack = if (top != null) ({ stack.removeAt(stack.lastIndex) }) else null,
            settingsActive = top is Screen.Settings,
            onSettings = { if (top is Screen.Settings) stack.removeAt(stack.lastIndex) else stack.add(Screen.Settings) },
        )
        Dock(if (top == null) tab else null, { t -> stack.clear(); tab = t }, Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(splash, enter = fadeIn(), exit = fadeOut(tween(400))) {
            Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                Box(Modifier.enterUp()) { Logo(30) }
            }
        }
    }
}
