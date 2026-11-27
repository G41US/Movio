package com.g41us.movio

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF3DDC97)
private val ErrorRed = Color(0xFFFF8FA3)

/** The gradient tile with the two-devices icon (green tick once linked). */
@Composable
fun LinkOrb(ok: Boolean = false, size: Dp = 60.dp) {
    val colors = if (ok) listOf(Color(0xFF3DDC97), Color(0xFF22B07D)) else listOf(Violet, Pink)
    Box(
        Modifier.size(size).shadow(18.dp, RoundedCornerShape(size * .32f), ambientColor = colors[0], spotColor = colors[0])
            .clip(RoundedCornerShape(size * .32f)).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_devices), null, tint = Color.White, modifier = Modifier.size(size * .52f)) }
}

/** Six letter tiles. [live]: being typed (cursor on the next empty tile). */
@Composable
private fun CodeTiles(code: String, live: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val blink by rememberInfiniteTransition(label = "c").animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = { if (it < .5f) 0f else 1f }), RepeatMode.Reverse), label = "b")
    // Codes read left to right, even in Arabic.
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(6) { i ->
                val ch = code.getOrNull(i)
                val cur = live && focused && i == code.length.coerceAtMost(5)
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier.weight(1f).height(if (LocalTv.current) 54.dp else 58.dp).clip(shape)
                        .background(if (live) Color.Black.copy(alpha = .28f) else Violet.copy(alpha = .16f))
                        .border(if (cur) 1.5.dp else 1.dp, when {
                            cur -> Violet
                            !live -> Violet.copy(alpha = .4f)
                            ch != null -> Color.White.copy(alpha = .28f)
                            else -> Color.White.copy(alpha = .1f)
                        }, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ch != null) Text(ch.toString(), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
                    else if (cur) Box(Modifier.width(2.dp).height(26.dp).alpha(blink).background(Violet))
                }
            }
        }
    }
}

/**
 * A text box that's safe on TV: there it's a plain button until you press OK, so the on-screen keyboard
 * doesn't jump up while you're only moving past it. On phones it's a normal text box.
 */
@Composable
fun TapToType(
    autoFocus: Boolean = false,
    field: @Composable (Modifier) -> Unit,
    shown: @Composable (focused: Boolean) -> Unit,
) {
    val tv = LocalTv.current
    var editing by remember { mutableStateOf(!tv) }
    var had by remember { mutableStateOf(false) }
    val r = remember { FocusRequester() }
    if (!editing) {
        var f by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(16.dp)).focusGlow(RoundedCornerShape(16.dp), grow = false)
            .clickableNoRipple { editing = true }) { shown(f) }
    } else {
        field(Modifier.focusRequester(r).onFocusChanged {
            if (it.isFocused) had = true else if (tv && had) { editing = false; had = false }
        })
        LaunchedEffect(editing, autoFocus) {
            if (tv || autoFocus) { kotlinx.coroutines.delay(if (tv) 60 else 250); runCatching { r.requestFocus() } }
        }
    }
}

/** The 6-letter code entry. */
@Composable
private fun CodeField(value: String, onValue: (String) -> Unit, onDone: () -> Unit, autoFocus: Boolean) {
    val tv = LocalTv.current
    TapToType(autoFocus, field = { mod ->
        var focused by remember { mutableStateOf(false) }
        BasicTextField(value, { v -> onValue(v.uppercase().filter { it.isLetterOrDigit() }.take(6)) }, singleLine = true,
            textStyle = TextStyle(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = mod.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner -> Box { CodeTiles(value, true, focused); Box(Modifier.matchParentSize().alpha(0f)) { inner() } } })
    }) { f -> CodeTiles(value, true, f) }
    if (tv) { Spacer(Modifier.height(10.dp)); Text("Press OK to type the code", fontSize = 13.sp, color = Muted, style = jakarta()) }
}

@Composable
private fun Caps(text: String, modifier: Modifier = Modifier) =
    Text(t(text).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Muted, style = jakarta(), modifier = modifier)

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .075f), Color.White.copy(alpha = .035f))))
            .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(22.dp)).padding(18.dp),
        content = content,
    )
}

@Composable
private fun ErrorNote(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ErrorRed, style = jakarta(),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x1FFF5F7A)).padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun LinkedPill() {
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(.35f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "a")
    Row(Modifier.clip(RoundedCornerShape(50)).background(Green.copy(alpha = .13f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).alpha(pulse).clip(RoundedCornerShape(50)).background(Green))
        Spacer(Modifier.width(7.dp))
        Text("Linked", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5FE6AA), style = jakarta())
    }
}

private fun ago(t: Long): String {
    if (t == 0L) return t("Not synced yet")
    val m = ((System.currentTimeMillis() - t) / 60_000).toInt()
    return tf("Last synced {1}", when {
        m < 1 -> t("just now")
        m < 60 -> tf("{1} min ago", m.toString())
        else -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))
    })
}

/** Settings: link devices with a short code, so My List and Continue watching follow you. */
@Composable
fun LinkCard(store: Store) {
    var code by remember { mutableStateOf(store.syncCode) }
    var typed by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf("") }
    var last by remember { mutableLongStateOf(store.lastSync) }
    val clip = LocalClipboardManager.current
    val tv = LocalTv.current
    fun join() {
        if (busy.isNotEmpty()) return
        if (typed.length < 6) { err = t("Type the code"); return }
        busy = "link"; err = null
        store.joinCode(typed) { ok -> busy = ""; if (ok) { code = store.syncCode; last = store.lastSync; typed = "" } else err = t("That code wasn't found. Check it and try again.") }
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkOrb(size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Link devices", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
                Text("My List, Continue watching and where you stopped follow you on every device with the same code.", fontSize = 13.sp, lineHeight = 18.sp, color = Muted, style = jakarta())
            }
        }
        Spacer(Modifier.height(18.dp))
        if (code.isNotEmpty()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinkedPill(); Spacer(Modifier.weight(1f))
                    Text(ago(last), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted, style = jakarta())
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caps("This device's code", Modifier.weight(1f))
                    if (!tv) Text("Copy", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .1f)).clickableNoRipple { clip.setText(AnnotatedString(code)) }
                            .padding(horizontal = 12.dp, vertical = 5.dp))
                }
                Spacer(Modifier.height(10.dp))
                CodeTiles(code, false, false)
                Spacer(Modifier.height(14.dp))
                Text("On your other device, open Link devices and type this code.", fontSize = 13.sp, lineHeight = 18.sp, color = Muted, style = jakarta())
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Unlink", null, Modifier.weight(1f)) { store.syncCode = ""; code = ""; err = null }
                PrimaryButton(if (busy == "sync") t("Syncing…") else t("Sync now"), R.drawable.ic_refresh, Modifier.weight(2f)) {
                    if (busy.isEmpty()) { busy = "sync"; store.pushNow { ok -> busy = ""; code = store.syncCode; last = store.lastSync; if (!ok) err = t("Couldn't sync") else err = null } }
                }
            }
            err?.let { ErrorNote(it) }
        } else {
            Panel {
                Caps("Type the code")
                Spacer(Modifier.height(10.dp))
                CodeField(typed, { typed = it; err = null; if (it.length == 6) join() }, { join() }, autoFocus = false)
                err?.let { ErrorNote(it) }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(if (busy == "link") t("Linking…") else t("Link"), R.drawable.ic_devices, Modifier.fillMaxWidth()) { join() }
            }
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = .1f)))
                Caps("or", Modifier.padding(horizontal = 12.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = .1f)))
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("No code yet?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta())
                        Text("Start a new link from this device.", fontSize = 13.sp, color = Muted, style = jakarta())
                    }
                    Spacer(Modifier.width(12.dp))
                    GhostButton(if (busy == "new") t("Checking…") else t("Get a code"), null) {
                        if (busy.isEmpty()) { busy = "new"; err = null
                            store.createCode { c -> busy = ""; if (c != null) { code = c; last = store.lastSync } else err = t("Couldn't reach the server. Try again.") } }
                    }
                }
            }
        }
    }
}

/** First-time setup: type a code from another device; [onLinked] gets your name back (if that device had one). */
@Composable
fun SetupLink(store: Store, ready: Boolean, onLinked: (name: String, list: Int, cont: Int) -> Unit) {
    var typed by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun join() {
        if (busy) return
        if (typed.length < 6) { err = t("Type the code"); return }
        busy = true; err = null
        store.joinCode(typed) { ok -> busy = false
            if (ok) onLinked(I18n.name, store.myList.size, store.continueWatching.size) else err = t("That code wasn't found. Check it and try again.") }
    }
    val tv = LocalTv.current
    // TV screens are short: the icon sits beside the title there.
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (tv) { LinkOrb(size = 52.dp); Spacer(Modifier.width(16.dp)) }
        Column {
            if (!tv) { LinkOrb(); Spacer(Modifier.height(20.dp)) }
            Text("Link with a code", fontSize = if (tv) 26.sp else 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
            Spacer(Modifier.height(if (tv) 4.dp else 8.dp))
            Text("Enter the code shown on your other device.", fontSize = 15.sp, lineHeight = 21.sp, color = Muted, style = jakarta())
        }
    }
    Spacer(Modifier.height(if (tv) 16.dp else 22.dp))
    Panel {
        Caps("Type the code")
        Spacer(Modifier.height(10.dp))
        CodeField(typed, { typed = it; err = null; if (it.length == 6) join() }, { join() }, autoFocus = ready && !LocalWide.current)
        err?.let { ErrorNote(it) }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(if (busy) t("Linking…") else t("Link"), R.drawable.ic_devices, Modifier.fillMaxWidth()) { join() }
    }
}

/** After linking during setup: "Welcome back, Marvelous" and what came across. */
@Composable
fun WelcomeBack(name: String, list: Int, cont: Int) {
    LinkOrb(ok = true, size = if (LocalTv.current) 52.dp else 60.dp)
    Spacer(Modifier.height(if (LocalTv.current) 14.dp else 20.dp))
    Text(if (name.isNotBlank()) tf("Welcome back, {1}", name) else t("You're linked"), fontSize = if (LocalTv.current) 26.sp else 30.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
    Spacer(Modifier.height(8.dp))
    Text("Your list and progress are here.", fontSize = 15.sp, color = Muted, style = jakarta())
    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(list to "My List", cont to "Continue watching").forEach { (n, label) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .06f))
                .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text(n.toString(), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted, style = jakarta())
            }
        }
    }
}
