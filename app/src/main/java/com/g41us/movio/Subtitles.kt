package com.g41us.movio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Subtitles for any source, found by TMDB id: Wyzie Subs when a key is set (best matches),
 * otherwise the subtitle list of Movio's direct stream service.
 */
object Subtitles {
    private val wanted = listOf("en", "fr", "es", "pt", "ar", "de", "it", "yo", "ha", "ig")

    private val found = java.util.concurrent.ConcurrentHashMap<String, List<Subtitle>>()

    suspend fun find(kind: Kind, id: Int, season: Int, episode: Int): List<Subtitle> = withContext(Dispatchers.IO) {
        val key = "${kind.api}:$id:$season:$episode"
        found[key]?.let { return@withContext it }
        lookup(kind, id, season, episode).also { if (it.isNotEmpty()) found[key] = it }
    }

    private suspend fun lookup(kind: Kind, id: Int, season: Int, episode: Int): List<Subtitle> = withContext(Dispatchers.IO) {
        val wyzie = if (BuildConfig.WYZIE_KEY.isNotBlank()) try { wyzie(kind, id, season, episode) } catch (_: Exception) { emptyList() } else emptyList()
        wyzie.ifEmpty {
            try { Source.resolve(kind, id, season, episode).subtitles } catch (_: Exception) { emptyList() }
        }
    }

    private fun wyzie(kind: Kind, id: Int, season: Int, episode: Int): List<Subtitle> {
        val ep = if (kind == Kind.TV) "&season=$season&episode=$episode" else ""
        val c = URL("https://sub.wyzie.io/search?id=$id$ep&language=${wanted.joinToString(",")}&key=${BuildConfig.WYZIE_KEY}")
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            if (c.responseCode !in 200..299) return emptyList()
            val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            return (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optString("url").startsWith("https://") }
                .sortedBy { o -> wanted.indexOf(o.optString("language")).let { if (it < 0) 99 else it } }
                .distinctBy { it.optString("language") }
                .map { o ->
                    val lang = o.optString("language")
                    val name = o.optString("display").ifBlank { Locale(lang).displayLanguage.ifBlank { lang } }
                    Subtitle(name, lang, o.optString("url"), srt = o.optString("format").equals("srt", true))
                }
        } finally { c.disconnect() }
    }
}
