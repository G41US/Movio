package com.g41us.movio

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** What the viewer is watching: a title, plus season/episode for shows. */
data class Progress(val title: Title, val season: Int, val episode: Int)

/** Movio's helper (also used by the web app). */
const val HELPER = "https://movio-helper.g41us-jarvis.workers.dev"

/** My List and Continue Watching, kept on the device and synced to linked devices. */
class Store private constructor(context: Context) {
    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("movio_store", Context.MODE_PRIVATE)

    val myList = mutableStateListOf<Title>().apply { addAll(load("my_list").map { it.first }) }
    val continueWatching = mutableStateListOf<Progress>().apply {
        addAll(load("continue").map { (t, o) -> Progress(t, o.optInt("s", 1), o.optInt("e", 1)) })
    }

    fun inList(t: Title) = myList.any { it.key == t.key }

    // ---------- Likes, search history, and what people watch (counts only) ----------
    val liked = mutableStateListOf<Title>().apply { addAll(load("liked").map { it.first }) }
    fun isLiked(t: Title) = liked.any { it.key == t.key }
    fun toggleLike(t: Title, genres: List<String>): Boolean {
        val on = !isLiked(t)
        if (on) liked.add(0, t) else liked.removeAll { it.key == t.key }
        save("liked", liked.map { toJson(it) })
        stat(if (on) "like" else "unlike", t, genres)
        return on
    }
    fun stat(ev: String, t: Title, genres: List<String>) = Thread {
        runCatching {
            val c = java.net.URL("$HELPER/stat").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8_000; c.readTimeout = 8_000
            val body = JSONObject().put("ev", ev).put("kind", t.kind.api).put("id", t.id).put("name", t.name)
                .put("poster", t.poster ?: JSONObject.NULL).put("genres", JSONArray(genres))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode
        }
    }.start()

    val searches = mutableStateListOf<String>().apply {
        runCatching { JSONArray(prefs.getString("searches", "[]")).let { a -> (0 until a.length()).forEach { add(a.getString(it)) } } }
    }
    fun rememberSearch(q: String) {
        val v = q.trim(); if (v.length < 2) return
        searches.removeAll { it.equals(v, ignoreCase = true) }; searches.add(0, v)
        while (searches.size > 12) searches.removeAt(searches.lastIndex)
        prefs.edit().putString("searches", JSONArray(searches.toList()).toString()).apply()
    }
    fun forgetSearch(q: String?) {
        if (q == null) searches.clear() else searches.remove(q)
        prefs.edit().putString("searches", JSONArray(searches.toList()).toString()).apply()
    }

    fun toggle(t: Title) {
        val had = inList(t)
        if (had) myList.removeAll { it.key == t.key } else myList.add(0, t)
        save("my_list", myList.map { toJson(it) })
        mark("l:${t.key}", had)
    }

    fun watched(t: Title, season: Int = 1, episode: Int = 1) {
        continueWatching.removeAll { it.title.key == t.key }
        continueWatching.add(0, Progress(t, season, episode))
        while (continueWatching.size > 20) continueWatching.removeAt(continueWatching.lastIndex)
        save("continue", continueWatching.map { p -> toJson(p.title).put("s", p.season).put("e", p.episode) })
        mark("c:${t.key}", false)
    }

    private val positions = context.getSharedPreferences("movio_positions", Context.MODE_PRIVATE)

    fun posKey(t: Title, season: Int, episode: Int) = if (t.kind == Kind.TV) "${t.key}:$season:$episode" else t.key

    /** Where playback stopped, so it can resume there. */
    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val done = positionMs > durationMs - 90_000
        positions.edit().putString(key, if (done) "-1:$durationMs" else "$positionMs:$durationMs").apply()
        mark("p:$key", false, quiet = true)
    }

    /** Finished films drop off Continue watching. */
    fun finished(t: Title) {
        continueWatching.removeAll { it.title.key == t.key }
        save("continue", continueWatching.map { p -> toJson(p.title).put("s", p.season).put("e", p.episode) })
        mark("c:${t.key}", true)
    }

    fun position(key: String): Pair<Long, Long>? = positions.getString(key, null)?.split(":")?.let {
        val p = it.getOrNull(0)?.toLongOrNull() ?: return null
        val d = it.getOrNull(1)?.toLongOrNull() ?: return null
        p to d
    }

    private val misc = context.getSharedPreferences("movio_misc", Context.MODE_PRIVATE)

    /** The source that last played this title, tried first next time. */
    fun sourceFor(t: Title): String? = misc.getString("src:${t.key}", null)
    fun setSourceFor(t: Title, name: String) = misc.edit().putString("src:${t.key}", name).apply()

    var subSize: Int
        get() = misc.getInt("sub_size", 1)
        set(v) = misc.edit().putInt("sub_size", v).apply()
    /** 0 none (outline), 1 shadow, 2 see-through box, 3 solid box */
    var subStyle: Int
        get() = misc.getInt("sub_style", 1)
        set(v) = misc.edit().putInt("sub_style", v).apply()
    var subColor: Int
        get() = misc.getInt("sub_color", 0)
        set(v) = misc.edit().putInt("sub_color", v).apply()
    var autoplayNext: Boolean
        get() = misc.getBoolean("autoplay", true)
        set(v) = misc.edit().putBoolean("autoplay", v).apply()
    var autoSkip: Boolean
        get() = misc.getBoolean("auto_skip", false)
        set(v) = misc.edit().putBoolean("auto_skip", v).apply()
    var alerts: Boolean
        get() = misc.getBoolean("alerts", true)
        set(v) = misc.edit().putBoolean("alerts", v).apply()

    fun lastSeen(showKey: String): String? = misc.getString("aired:$showKey", null)
    fun setLastSeen(showKey: String, v: String) = misc.edit().putString("aired:$showKey", v).apply()

    fun lastEpisode(t: Title): Progress? = continueWatching.firstOrNull { it.title.key == t.key }

    // ---------- Sync between devices (a short link code, kept by Movio's helper) ----------
    private val syncPrefs = context.getSharedPreferences("movio_sync", Context.MODE_PRIVATE)
    private val ledger: HashMap<String, Long> = try {
        JSONObject(syncPrefs.getString("ledger", "{}")!!).let { o -> HashMap(o.keys().asSequence().associateWith { o.getLong(it) }) }
    } catch (_: Exception) { HashMap() }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val pushLater = Runnable { pushNow() }
    @Volatile private var busy = false

    var syncCode: String
        get() = syncPrefs.getString("code", "") ?: ""
        set(v) = syncPrefs.edit().putString("code", v).apply()
    var kids: Boolean
        get() = misc.getBoolean("kids", false)
        set(v) = misc.edit().putBoolean("kids", v).apply()

    private fun at(k: String) = kotlin.math.abs(ledger[k] ?: 0L)
    private fun saveLedger() = syncPrefs.edit().putString("ledger", JSONObject(ledger as Map<*, *>).toString()).apply()

    private fun mark(k: String, removed: Boolean, quiet: Boolean = false) {
        ledger[k] = (if (removed) -1 else 1) * System.currentTimeMillis()
        saveLedger()
        if (syncCode.isEmpty()) return
        main.removeCallbacks(pushLater)
        main.postDelayed(pushLater, if (quiet) 15_000 else 1_500)
    }

    private fun wire(t: Title) = JSONObject().put("id", t.id).put("kind", t.kind.api).put("name", t.name).put("overview", t.overview.take(400))
        .put("poster", t.poster ?: JSONObject.NULL).put("backdrop", t.backdrop ?: JSONObject.NULL).put("year", t.year ?: JSONObject.NULL).put("rating", t.rating)

    private fun fromWire(o: JSONObject?): Title? = if (o == null) null else try {
        Title(o.getInt("id"), if (o.optString("kind") == "tv") Kind.TV else Kind.MOVIE, o.getString("name"), o.optString("overview"),
            o.optString("poster").takeIf { it.startsWith("/") }, o.optString("backdrop").takeIf { it.startsWith("/") },
            o.optString("year").takeIf { it.isNotBlank() && it != "null" }, o.optDouble("rating", 0.0))
    } catch (_: Exception) { null }

    private fun state(): JSONObject {
        val list = JSONObject(); val cont = JSONObject(); val pos = JSONObject()
        myList.forEachIndexed { i, t -> list.put(t.key, JSONObject().put("at", at("l:${t.key}").takeIf { it > 0 } ?: (1000L - i)).put("t", wire(t))) }
        continueWatching.forEachIndexed { i, p -> cont.put(p.title.key, JSONObject().put("at", at("c:${p.title.key}").takeIf { it > 0 } ?: (1000L - i)).put("t", wire(p.title)).put("s", p.season).put("e", p.episode)) }
        for ((k, v) in positions.all) {
            val parts = (v as? String)?.split(":") ?: continue
            val p = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val d = parts.getOrNull(1)?.toLongOrNull() ?: continue
            pos.put(k, JSONObject().put("at", at("p:$k").takeIf { it > 0 } ?: 1L).put("p", if (p < 0) -1.0 else p / 1000.0).put("d", d / 1000.0))
        }
        for ((k, v) in ledger) {
            if (v >= 0) continue
            val rest = k.substring(2)
            if (k.startsWith("l:") && !list.has(rest)) list.put(rest, JSONObject().put("at", -v).put("del", true))
            if (k.startsWith("c:") && !cont.has(rest)) cont.put(rest, JSONObject().put("at", -v).put("del", true))
        }
        val out = JSONObject().put("list", list).put("cont", cont).put("pos", pos)
        // Your name travels too, so a newly linked device can greet you. A changed name counts as newer.
        val name = I18n.name.trim()
        if (name.isNotEmpty()) {
            if (name != syncPrefs.getString("name_last", null)) syncPrefs.edit().putString("name_last", name).putLong("name_at", System.currentTimeMillis()).apply()
            out.put("prof", JSONObject().put("at", syncPrefs.getLong("name_at", 1L)).put("name", name))
        }
        return out
    }

    /** When this device last synced (0 = never). */
    val lastSync get() = syncPrefs.getLong("last", 0L)

    private fun apply(st: JSONObject) {
        st.optJSONObject("list")?.let { o ->
            for (k in o.keys()) {
                val v = o.getJSONObject(k); val a = v.getLong("at")
                if (a <= at("l:$k")) continue
                myList.removeAll { it.key == k }
                if (!v.optBoolean("del")) fromWire(v.optJSONObject("t"))?.let { myList.add(it) }
                ledger["l:$k"] = if (v.optBoolean("del")) -a else a
            }
            val sorted = myList.sortedByDescending { at("l:${it.key}") }
            myList.clear(); myList.addAll(sorted)
            save("my_list", myList.map { toJson(it) })
        }
        st.optJSONObject("pos")?.let { o ->
            val e = positions.edit()
            for (k in o.keys()) {
                val v = o.getJSONObject(k); val a = v.getLong("at")
                if (a <= at("p:$k")) continue
                val d = (v.optDouble("d") * 1000).toLong(); val p = v.optDouble("p")
                e.putString(k, if (p < 0) "-1:$d" else "${(p * 1000).toLong()}:$d")
                ledger["p:$k"] = a
            }
            e.apply()
        }
        st.optJSONObject("cont")?.let { o ->
            for (k in o.keys()) {
                val v = o.getJSONObject(k); val a = v.getLong("at")
                if (a <= at("c:$k")) continue
                continueWatching.removeAll { it.title.key == k }
                if (!v.optBoolean("del")) fromWire(v.optJSONObject("t"))?.let { continueWatching.add(Progress(it, v.optInt("s", 1), v.optInt("e", 1))) }
                ledger["c:$k"] = if (v.optBoolean("del")) -a else a
            }
            val sorted = continueWatching.sortedByDescending { at("c:${it.title.key}") }.take(20)
            continueWatching.clear(); continueWatching.addAll(sorted)
            save("continue", continueWatching.map { p -> toJson(p.title).put("s", p.season).put("e", p.episode) })
        }
        st.optJSONObject("prof")?.let { o ->
            val a = o.optLong("at"); val n = o.optString("name").trim()
            if (n.isNotEmpty() && a > syncPrefs.getLong("name_at", 0L)) {
                I18n.name = n; I18n.save()
                syncPrefs.edit().putString("name_last", n).putLong("name_at", a).apply()
            }
        }
        syncPrefs.edit().putLong("last", System.currentTimeMillis()).apply()
        saveLedger()
    }

    /** Sends this device's lists, gets everyone's back, merged. */
    fun pushNow(done: ((Boolean) -> Unit)? = null) {
        val code = syncCode
        if (code.isEmpty() || busy) { done?.invoke(false); return }
        busy = true
        val body = state().toString()
        Thread {
            val res = runCatching {
                val c = java.net.URL("$HELPER/sync/$code").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                val status = c.responseCode
                if (status == 404) { main.post { syncCode = "" }; null } else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            }.getOrNull()
            main.post {
                busy = false
                val st = res?.optJSONObject("state")
                if (res?.optBoolean("ok") == true && st != null) runCatching { apply(st) }
                done?.invoke(st != null)
            }
        }.start()
    }

    fun createCode(done: (String?) -> Unit) = Thread {
        val code = runCatching {
            val c = java.net.URL("$HELPER/sync/new").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 15_000
            JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("code").takeIf { it.length == 6 }
        }.getOrNull()
        main.post { if (code != null) { syncCode = code; pushNow() }; done(code) }
    }.start()

    fun joinCode(raw: String, done: (Boolean) -> Unit) = Thread {
        val code = raw.uppercase().filter { it.isLetterOrDigit() }
        val ok = code.length == 6 && runCatching {
            (java.net.URL("$HELPER/sync/$code").openConnection() as java.net.HttpURLConnection).let { it.connectTimeout = 10_000; it.responseCode == 200 }
        }.getOrDefault(false)
        main.post { if (ok) { syncCode = code; pushNow { done(true) } } else done(false) }
    }.start()

    private fun toJson(t: Title) = JSONObject()
        .put("id", t.id).put("kind", t.kind.name).put("name", t.name).put("overview", t.overview)
        .put("poster", t.poster ?: "").put("backdrop", t.backdrop ?: "").put("year", t.year ?: "").put("rating", t.rating)

    private fun save(key: String, items: List<JSONObject>) =
        prefs.edit().putString(key, JSONArray(items).toString()).apply()

    private fun load(key: String): List<Pair<Title, JSONObject>> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Title(
                o.getInt("id"), Kind.valueOf(o.getString("kind")), o.getString("name"), o.optString("overview"),
                o.optString("poster").ifBlank { null }, o.optString("backdrop").ifBlank { null },
                o.optString("year").ifBlank { null }, o.optDouble("rating", 0.0),
            ) to o
        }
    } catch (_: Exception) { emptyList() }
}
