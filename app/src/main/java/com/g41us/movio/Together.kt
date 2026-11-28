package com.g41us.movio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Someone you watch with. */
data class TgPerson(val pid: String, val name: String, val kind: String, val online: Boolean)
data class TgInvite(val id: Long, val room: String, val from: String, val title: JSONObject)

/**
 * Together: the people you watch with (5-letter codes, two-way connections, invites) on Movio's helper.
 * You are one person across all your linked devices (your link code); otherwise this device.
 */
object Tg {
    var code by mutableStateOf("")
    var people by mutableStateOf<List<TgPerson>>(emptyList())
    var invites by mutableStateOf<List<TgInvite>>(emptyList())
    private val dismissed = HashSet<Long>()

    private fun who(c: Context): Pair<String, String?> {
        val p = c.getSharedPreferences("movio_misc", Context.MODE_PRIVATE)
        val sync = Store.get(c).syncCode
        val w = if (sync.isNotEmpty()) "c:$sync" else "d:" + Ping.deviceId(c)
        val last = p.getString("tg_who", null)
        if (last != null && last != w) p.edit().putString("tg_prev", last).apply()
        p.edit().putString("tg_who", w).apply()
        return w to p.getString("tg_prev", null)
    }

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val h = URL("$HELPER$path").openConnection() as HttpURLConnection
        h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = 10_000; h.readTimeout = 12_000
        h.setRequestProperty("Content-Type", "application/json")
        h.setRequestProperty("User-Agent", "Movio-Android/${BuildConfig.VERSION_NAME}")
        h.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = h.responseCode
        val text = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        h.disconnect()
        return code to runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    /** Runs a Together action; throws with a readable message when it fails. */
    suspend fun call(c: Context, op: String, extra: JSONObject.() -> Unit = {}): JSONObject = withContext(Dispatchers.IO) {
        val (w, prev) = who(c)
        val body = JSONObject().put("who", w).put("name", I18n.name).apply { if (prev != null) put("prev", prev) }.apply(extra)
        val (status, o) = post("/tg/$op", body)
        if (status !in 200..299) throw Exception(o.optString("error").ifBlank { t("Couldn't reach Movio. Try again.") })
        c.getSharedPreferences("movio_misc", Context.MODE_PRIVATE).edit().remove("tg_prev").apply()
        val ppl = o.optJSONArray("people") ?: JSONArray()
        val inv = o.optJSONArray("invites") ?: JSONArray()
        withContext(Dispatchers.Main) {
            code = o.optString("code")
            people = (0 until ppl.length()).map { ppl.getJSONObject(it) }.map { TgPerson(it.optString("pid"), it.optString("name"), it.optString("kind"), it.optBoolean("online")) }
            invites = (0 until inv.length()).map { inv.getJSONObject(it) }.map { TgInvite(it.optLong("id"), it.optString("room"), it.optString("from"), it.optJSONObject("title") ?: JSONObject()) }
                .filter { it.id !in dismissed }
        }
        o
    }
    suspend fun refresh(c: Context) = runCatching { call(c, "me") }.getOrNull()
    suspend fun dismiss(c: Context, id: Long) { dismissed += id; invites = invites.filter { it.id != id }; runCatching { call(c, "dismiss") { put("id", id) } } }

    /** A new watch party for [t]; invites the chosen people. Returns the 4-letter party code. */
    suspend fun newParty(c: Context, t: Title, s: Int, e: Int, invite: List<String>): String? = withContext(Dispatchers.IO) {
        val title = JSONObject().put("kind", if (t.kind == Kind.TV) "tv" else "movie").put("id", t.id).put("name", t.name)
            .put("poster", t.poster).put("backdrop", t.backdrop).put("s", s).put("e", e)
        val (status, o) = runCatching { post("/room/new", JSONObject().put("name", I18n.name).put("title", title)) }.getOrElse { return@withContext null }
        val room = o.optString("room").takeIf { status == 200 && it.length == 4 } ?: return@withContext null
        if (invite.isNotEmpty()) runCatching { call(c, "invite") { put("to", JSONArray(invite)); put("room", room); put("title", title) } }
        room
    }

    /** What a party is watching, or null if the code is wrong. */
    suspend fun party(code: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val h = URL("$HELPER/room/${code.uppercase()}/info").openConnection() as HttpURLConnection
            h.connectTimeout = 8000; h.readTimeout = 8000
            if (h.responseCode != 200) null else JSONObject(h.inputStream.bufferedReader().use { it.readText() }).optJSONObject("title")
        }.getOrNull()
    }

    fun titleOf(o: JSONObject) = Title(
        id = o.optInt("id"), kind = if (o.optString("kind") == "tv") Kind.TV else Kind.MOVIE, name = o.optString("name"),
        poster = o.optString("poster").takeIf { it.isNotBlank() && it != "null" }, backdrop = o.optString("backdrop").takeIf { it.isNotBlank() && it != "null" },
        year = null, rating = 0.0, overview = "",
    )
}

/**
 * The live link to a watch party: everyone follows one shared state (playing, position, episode).
 * Callbacks arrive on the main thread.
 */
class PartyLink(val code: String, private val name: String, private val onMessage: (JSONObject) -> Unit) {
    data class Member(val id: String, val name: String, val waiting: Boolean)
    data class State(val playing: Boolean, val pos: Double, val at: Long, val s: Int, val e: Int)

    var you = ""
    var skew = 0L
    val members = mutableStateListOf<Member>()
    var st: State? = null
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var closed = false

    fun open() {
        if (closed) return
        val url = HELPER.replace("https://", "wss://") + "/room/$code/ws?name=" + java.net.URLEncoder.encode(name.ifBlank { "Friend" }, "UTF-8")
        ws = http.newWebSocket(Request.Builder().url(url).header("User-Agent", "Movio-Android").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post {
                    skew = m.optLong("now", System.currentTimeMillis()) - System.currentTimeMillis()
                    when (m.optString("t")) {
                        "hello" -> { you = m.optString("you"); setMembers(m.optJSONArray("members")); st = state(m.optJSONObject("st")) }
                        "members" -> setMembers(m.optJSONArray("members"))
                        "state" -> st = state(m.optJSONObject("st"))
                    }
                    onMessage(m)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.postDelayed({ if (!closed) open() }, 2000) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.postDelayed({ if (!closed) open() }, 2000) }
        })
    }
    private fun setMembers(a: JSONArray?) {
        members.clear()
        if (a != null) for (i in 0 until a.length()) a.getJSONObject(i).let { members += Member(it.optString("id"), it.optString("name"), it.optBoolean("waiting")) }
    }
    private fun state(o: JSONObject?) = o?.let { State(it.optBoolean("playing"), it.optDouble("pos"), it.optLong("at"), it.optInt("s", 1), it.optInt("e", 1)) }

    val others get() = members.filter { it.id != you }
    /** Where the party is now (ms), from the last shared state. */
    fun expectedMs(): Long? = st?.let { (it.pos * 1000).toLong() + if (it.playing) System.currentTimeMillis() + skew - it.at else 0 }

    fun send(o: JSONObject) { runCatching { ws?.send(o.toString()) } }
    fun state(playing: Boolean, posMs: Long, s: Int, e: Int, why: String) =
        send(JSONObject().put("t", "state").put("playing", playing).put("pos", posMs / 1000.0).put("s", s).put("e", e).put("why", why))
    fun waiting(on: Boolean) = send(JSONObject().put("t", "wait").put("on", on))
    fun react(e: String) = send(JSONObject().put("t", "react").put("e", e))
    fun chat(m: String) = send(JSONObject().put("t", "chat").put("m", m))
    fun close() { closed = true; runCatching { ws?.close(1000, null) }; http.dispatcher.executorService.shutdown() }
}
