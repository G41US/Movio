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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MovioBackground = Color(0xFF090B10)
private val MovioSurface = Color(0xFF151923)
private val MovioAccent = Color(0xFF8B5CF6)
private val MovioMuted = Color(0xFF9AA3B2)

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
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 800
    val context = LocalContext.current
    val isTv = remember(context) { isTelevision(context) }

    Surface(modifier = Modifier.fillMaxSize(), color = MovioBackground) {
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
                Destination.HOME -> HomeScreen(wide || isTv)
                Destination.SEARCH -> PlaceholderScreen("Search", "Find movies and series when catalogue providers are connected.")
                Destination.LIBRARY -> PlaceholderScreen("My List", "Your saved titles will appear here.")
                Destination.SETTINGS -> SettingsScreen()
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

@Composable
private fun HomeScreen(largeLayout: Boolean) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)
    ) {
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
                Text("Movies, series and more — all in one place.", color = Color(0xFFD5D0E2), fontSize = 14.sp)
                Button(
                    onClick = { },
                    colors = ButtonDefaults.buttonColors(containerColor = MovioAccent),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                ) { Text("Explore Movio") }
            }
            Text("MOVIO ORIGINAL", modifier = Modifier.align(Alignment.TopStart).padding(22.dp), color = Color(0xFFD8C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }

        Spacer(Modifier.height(28.dp))
        SectionTitle("Explore Movio", "Your entertainment hub")
        val categories = listOf("Movies", "TV Series", "Anime", "Live TV", "Sports")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(categories) { item ->
                Box(
                    modifier = Modifier.width(if (largeLayout) 190.dp else 145.dp)
                        .height(92.dp).clip(RoundedCornerShape(16.dp))
                        .background(MovioSurface).clickable { }.focusable(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        SectionTitle("Coming into focus", "Your catalogue will appear here")
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(105.dp)
                .clip(RoundedCornerShape(16.dp)).background(MovioSurface),
            contentAlignment = Alignment.Center
        ) {
            Text("Catalogue providers are not connected yet", color = MovioMuted, fontSize = 13.sp)
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
private fun SettingsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Surface(color = MovioSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF292F3D))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App updates", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Movio will check GitHub Releases for new versions. The updater will be enabled when release signing and download verification are configured.", color = MovioMuted, fontSize = 13.sp)
                Text("Current version  •  0.1.0", color = Color(0xFFC4B5FD), fontSize = 12.sp)
                Button(onClick = { }, enabled = false) { Text("Check for updates — coming soon") }
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
