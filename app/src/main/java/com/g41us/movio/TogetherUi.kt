package com.g41us.movio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val KINDS = listOf("partner" to ("❤️" to "Partner"), "friend" to ("🤝" to "Friend"), "family" to ("🏠" to "Family"))
private val AvatarColors = listOf(listOf(Color(0xFF6B7CFF), Color(0xFFB56BFF)), listOf(Color(0xFF10B981), Color(0xFF34D399)), listOf(Color(0xFFF472B6), Color(0xFFFB7185)))

@Composable
fun Avatar(name: String, size: Dp, index: Int = 0, online: Boolean = false, heart: Boolean = false, selected: Boolean = false) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().then(if (selected) Modifier.softRing(1f, CircleShape) else Modifier).clip(CircleShape)
                .background(Brush.linearGradient(AvatarColors[index % AvatarColors.size])),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Text((name.firstOrNull() ?: '?').uppercase(), color = Color.White, fontSize = (size.value * .38f).sp, fontWeight = FontWeight.ExtraBold, style = jakarta()) }
        if (online) Box(Modifier.align(Alignment.TopStart).padding(2.dp).size(size * .2f).clip(CircleShape).background(Ink).padding(2.dp).clip(CircleShape).background(Color(0xFF34D27B)))
        if (heart) androidx.compose.material3.Text("❤️", fontSize = (size.value * .26f).sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** The Together tab: the people you watch with, adding someone, and joining a party with its code. */
@Composable
fun TogetherScreen(onJoined: (Title, Int, Int, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = LocalTv.current
    var sel by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Tg.refresh(ctx) }
    val cur = Tg.people.firstOrNull { it.pid == sel } ?: Tg.people.firstOrNull()
    val tvBar = LocalTopBar.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = if (tvBar != null) 0.dp else barSpace(), bottom = dockSpace())) {
        tvBar?.invoke()
        Text("Together", fontSize = if (tv) 30.sp else 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta(), modifier = Modifier.padding(start = Gutter, top = 6.dp))
        Spacer(Modifier.height(14.dp))
        LazyRow(Modifier.tvEdgeFade(), contentPadding = PaddingValues(start = Gutter, end = GutterEnd, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(Tg.people.size) { i ->
                val p = Tg.people[i]
                var f by remember { mutableStateOf(false) }
                Column(Modifier.width(92.dp).onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(16.dp)).background(if (f) Color.White.copy(alpha = .1f) else Color.Transparent)
                    .clickable { sel = p.pid }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(p.name, 64.dp, i, p.online, p.kind == "partner", selected = cur?.pid == p.pid)
                    Spacer(Modifier.height(8.dp))
                    Text(p.name, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(KINDS.firstOrNull { it.first == p.kind }?.second?.second ?: "Friend", fontSize = 11.5.sp, color = Muted, style = jakarta())
                }
            }
            item {
                var f by remember { mutableStateOf(false) }
                Column(Modifier.width(92.dp).onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(16.dp)).background(if (f) Color.White.copy(alpha = .1f) else Color.Transparent)
                    .clickable { adding = true }.padding(vertical = 8.dp).tvFirstFocus(Tg.people.isEmpty()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = .28f), CircleShape), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text("+", color = Soft, fontSize = 28.sp, style = jakarta())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Add someone", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(), textAlign = TextAlign.Center)
                    Text("With a code", fontSize = 11.5.sp, color = Muted, style = jakarta())
                }
            }
        }
        Column(Modifier.padding(start = Gutter, end = GutterEnd).widthIn(max = 760.dp)) {
            Spacer(Modifier.height(10.dp))
            if (cur != null) Card {
                Text(tf("With {1}", cur.name), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (cur.online) "● " + t("On Movio now") else t("Not on Movio right now"), if (cur.online) Color(0xFF34D27B) else Muted)
                    KINDS.firstOrNull { it.first == cur.kind }?.second?.let { (e, l) -> Pill("$e ${t(l)}", Color.White) }
                }
                Spacer(Modifier.height(12.dp))
                Text(tf("To watch with {1}, open any film or episode and press Watch together. They'll get an invite.", cur.name), fontSize = 14.sp, lineHeight = 20.sp, color = Soft, style = jakarta())
                Spacer(Modifier.height(14.dp))
                GhostButton("Remove", null) { scope.launch { runCatching { Tg.call(ctx, "remove") { put("pid", cur.pid) } }; sel = "" } }
            } else Card {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Text("👥", fontSize = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Watch with the people you love", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("Add your partner, friends or family with a code. Then watch films and shows together, in sync, wherever you are.",
                        fontSize = 14.sp, lineHeight = 20.sp, color = Soft, style = jakarta(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Add someone", R.drawable.ic_people) { adding = true }
                }
            }
            Card {
                Text("Join a watch party", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta())
                Spacer(Modifier.height(4.dp))
                Text("Got a 4-letter party code? Enter it here.", fontSize = 13.sp, color = Muted, style = jakarta())
                Spacer(Modifier.height(12.dp))
                var code by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                val join = {
                    if (code.length == 4 && !busy) {
                        busy = true
                        scope.launch {
                            val o = Tg.party(code)
                            busy = false
                            if (o == null) Toast.makeText(ctx, t("That party code wasn't found."), Toast.LENGTH_SHORT).show()
                            else onJoined(Tg.titleOf(o), o.optInt("s", 1), o.optInt("e", 1), code.uppercase())
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CodeInput(code, 4, onDone = join) { code = it } }
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton(if (busy) "…" else "Join", R.drawable.ic_play_fill) { join() }
                }
            }
        }
    }
    if (adding) AddSomeone { adding = false }
}

@Composable
private fun Pill(text: String, color: Color) {
    androidx.compose.material3.Text(text, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, style = jakarta(),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .08f)).padding(horizontal = 11.dp, vertical = 6.dp))
}

/** A short code box (letters and numbers); on TV it only opens the keyboard when you press OK. */
@Composable
fun CodeInput(value: String, length: Int, onDone: () -> Unit = {}, onChange: (String) -> Unit) {
    TapToType(
        field = { m ->
            BasicTextField(value, { v -> onChange(v.uppercase().filter { it.isLetterOrDigit() }.take(length)) }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp, fontFamily = Jakarta),
                cursorBrush = SolidColor(Violet),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = m.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Glass).padding(horizontal = 16.dp, vertical = 12.dp))
        },
        shown = { f ->
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(if (f) Color.White.copy(alpha = .16f) else Glass).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                androidx.compose.material3.Text(value.ifEmpty { "A".repeat(0) + "· ".repeat(length).trim() }, color = if (value.isEmpty()) Muted else Color.White,
                    fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp, style = jakarta())
            }
        },
    )
}

/** Add someone: share your code, or enter theirs and say who they are. */
@Composable
fun AddSomeone(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("partner") }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val connect = {
        if (code.length < 5) err = t("Enter their 5-letter code.")
        else if (!busy) { busy = true; scope.launch {
            try { Tg.call(ctx, "connect") { put("code", code); put("kind", kind) }; Toast.makeText(ctx, t("Connected"), Toast.LENGTH_SHORT).show(); onClose() }
            catch (e: Exception) { err = e.message ?: "" }
            busy = false
        } }
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xF7121319))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(26.dp)).verticalScroll(rememberScrollState()).padding(22.dp)) {
            Text("Add someone", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
            Text("Each person or group gets their own shared things.", fontSize = 13.sp, color = Muted, style = jakarta())
            Spacer(Modifier.height(16.dp))
            Label("Your code for them")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                (Tg.code.ifBlank { "·····" }).forEach { c ->
                    Box(Modifier.size(46.dp, 56.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text(c.toString(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, style = jakarta())
                    }
                }
            }
            if (!LocalTv.current) {
                Spacer(Modifier.height(12.dp))
                GhostButton("Share on WhatsApp", null, Modifier.fillMaxWidth()) {
                    val msg = tf("Add me on Movio to watch together. My code is {1}", Tg.code) + " — https://g41us.github.io/Movio/"
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(msg)))) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Label("Or enter their code")
            Spacer(Modifier.height(10.dp))
            CodeInput(code, 5, onDone = connect) { code = it; err = "" }
            Spacer(Modifier.height(14.dp))
            Label("They are your…")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { KINDS.forEach { (k, v) -> Chip("${v.first} ${t(v.second)}", kind == k) { kind = k } } }
            if (err.isNotBlank()) { Spacer(Modifier.height(10.dp)); androidx.compose.material3.Text(err, color = Color(0xFFFF7A6B), fontSize = 13.sp, fontWeight = FontWeight.Bold, style = jakarta()) }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(if (busy) "…" else "Connect", R.drawable.ic_people, Modifier.fillMaxWidth()) { connect() }
        }
    }
}

/** From a title's page: choose who to invite, then start the party. */
@Composable
fun WatchTogetherDialog(t: Title, s: Int, e: Int, onStart: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Tg.refresh(ctx) }
    val chosen = remember { mutableStateListOf<String>() }
    LaunchedEffect(Tg.people) { if (chosen.isEmpty()) chosen += Tg.people.filter { it.kind == "partner" }.map { it.pid } }
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xF7121319))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(26.dp)).padding(22.dp)) {
            Text("Watch together", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = jakarta())
            androidx.compose.material3.Text(t.name + if (t.kind == Kind.TV) " · S$s E$e" else "", fontSize = 13.sp, color = Muted, style = jakarta())
            Spacer(Modifier.height(16.dp))
            if (Tg.people.isNotEmpty()) {
                Label("Invite")
                Spacer(Modifier.height(10.dp))
                Tg.people.forEachIndexed { i, p ->
                    var f by remember { mutableStateOf(false) }
                    val on = p.pid in chosen
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(if (f) Color.White.copy(alpha = .14f) else Color.White.copy(alpha = .05f))
                        .onFocusChanged { f = it.isFocused }.clickable { if (on) chosen.remove(p.pid) else chosen += p.pid }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name, 36.dp, i, p.online)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, style = jakarta(), modifier = Modifier.weight(1f))
                        if (on) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_check), null, tint = Violet, modifier = Modifier.size(20.dp))
                    }
                }
            } else Text("You'll get a code to share. Add people in the Together tab to invite them directly next time.", fontSize = 13.5.sp, lineHeight = 19.sp, color = Soft, style = jakarta())
            Spacer(Modifier.height(14.dp))
            PrimaryButton(if (busy) "…" else "Start watch party", R.drawable.ic_play_fill, Modifier.fillMaxWidth().tvFirstFocus(Unit)) {
                if (!busy) { busy = true; scope.launch {
                    val room = Tg.newParty(ctx, t, s, e, chosen.toList())
                    busy = false
                    if (room == null) Toast.makeText(ctx, t("Couldn't reach Movio. Try again."), Toast.LENGTH_SHORT).show() else { onClose(); onStart(room) }
                } }
            }
        }
    }
}

/** "Tobi wants to watch Street Fighter with you": shown over Movio when an invite comes in. */
@Composable
fun InviteBanner(onJoin: (Title, Int, Int, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { Tg.refresh(ctx); delay(20_000) } }
    val inv = Tg.invites.firstOrNull() ?: return
    val join = remember { FocusRequester() }
    LaunchedEffect(inv.id) { delay(300); runCatching { join.requestFocus() } }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
        Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).clip(RoundedCornerShape(20.dp)).background(Color(0xF5141520))
            .border(1.dp, Color.White.copy(alpha = .1f), RoundedCornerShape(20.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            inv.title.optString("poster").takeIf { it.isNotBlank() && it != "null" }?.let {
                NetImage(Tmdb.img(it, "w154"), null, Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(tf("{1} wants to watch with you", inv.from), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, style = jakarta())
                androidx.compose.material3.Text(inv.title.optString("name"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, style = jakarta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PrimaryButton("Join", R.drawable.ic_play_fill, Modifier.focusRequester(join)) {
                scope.launch { Tg.dismiss(ctx, inv.id) }
                onJoin(Tg.titleOf(inv.title), inv.title.optInt("s", 1), inv.title.optInt("e", 1), inv.room)
            }
            Spacer(Modifier.width(6.dp))
            GlassIcon(R.drawable.ic_close, size = 38.dp) { scope.launch { Tg.dismiss(ctx, inv.id) } }
        }
    }
}
