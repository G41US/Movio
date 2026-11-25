package com.g41us.movio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Subtitle(val label: String, val lang: String, val url: String)
data class Playable(val url: String, val subtitles: List<Subtitle>, val headers: Map<String, String>)

/**
 * Asks the player service behind braflix for a title's stream directly, by TMDB id, so no web page
 * (and none of its ads) ever loads. Returns an HLS master playlist with every quality.
 */
object Source {
    const val BASE = "https://vidsrc.party"
    private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    val headers = mapOf("Referer" to "$BASE/", "Origin" to BASE, "User-Agent" to UA)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 40_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) throw IOException("Source error ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    suspend fun resolve(kind: Kind, id: Int, season: Int, episode: Int): Playable = withContext(Dispatchers.IO) {
        val path = if (kind == Kind.TV) "api/play/tv/$id/$season/$episode" else "api/play/movie/$id"
        var file: JSONObject? = null
        // The service may still be preparing the stream; ask again for up to ~40s.
        for (attempt in 0 until 8) {
            val o = JSONObject(get("$BASE/$path?async=1&wait=1"))
            file = o.optJSONObject("result")?.optJSONObject("file")
            if (file != null && o.optString("status") == "ready") break
            if (o.optString("status") in setOf("error", "failed", "not_found")) throw IOException(o.optString("message", "Not available"))
            delay(5_000)
        }
        val f = file ?: throw IOException("Stream not ready")
        val stream = f.optString("playlist").ifBlank { f.optString("stream") }.ifBlank { throw IOException("No stream") }
        val subs = try {
            val a = JSONArray(get(BASE + f.optString("subtitles")))
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { Subtitle(it.optString("label"), it.optString("lang"), BASE + it.optString("src")) }
                .filter { it.url.endsWith(".vtt") || it.url.endsWith(".srt") }
                .distinctBy { it.lang }
                .sortedBy { if (it.lang == "en") 0 else 1 }
                .take(16)
        } catch (_: Exception) { emptyList() }
        Playable(if (stream.startsWith("http")) stream else BASE + stream, subs, headers)
    }
}
