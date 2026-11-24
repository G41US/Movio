package com.g41us.movio.data.provider

import android.content.Context
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

/**
 * Matches catalogue metadata to TMDB. The user's API key is stored locally in
 * app preferences; no key is bundled in the APK or sent to Movio servers.
 */
object TmdbMatcher {
    private const val PREFS = "movio_playback"
    private const val KEY = "tmdb_api_key"

    fun savedApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun saveApiKey(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, value.trim()).apply()
    }

    suspend fun match(context: Context, item: MediaItem): Int? = withContext(Dispatchers.IO) {
        item.tmdbId?.takeIf { it > 0 }?.let { return@withContext it }
        val key = savedApiKey(context)
        if (key.isBlank()) return@withContext null

        val endpoint = when (item.mediaType) {
            MediaType.MOVIE -> "movie"
            MediaType.SERIES, MediaType.ANIME -> "tv"
            else -> return@withContext null
        }
        val encoded = URLEncoder.encode(item.title, "UTF-8")
        val url = URL("https://api.themoviedb.org/3/search/$endpoint?api_key=$key&query=$encoded&include_adult=false")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Movio-Android")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("TMDB matching failed (HTTP $status)")
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val results = root.optJSONArray("results") ?: return@withContext null
            val wanted = normalize(item.title)
            val candidates = (0 until results.length()).mapNotNull { index ->
                val row = results.optJSONObject(index) ?: return@mapNotNull null
                val name = row.optString("title").ifBlank {
                    row.optString("name").ifBlank { row.optString("original_name") }
                }
                val id = row.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                val candidate = normalize(name)
                val exact = candidate == wanted
                val year = row.optString("release_date").ifBlank { row.optString("first_air_date") }
                    .take(4).toIntOrNull()
                val yearMatches = item.releaseYear == null || year == null || item.releaseYear == year
                val score = (if (exact) 100 else if (candidate.contains(wanted) || wanted.contains(candidate)) 65 else 0) +
                    (if (yearMatches) 15 else 0) +
                    (row.optDouble("vote_average", 0.0).coerceIn(0.0, 10.0).toInt())
                Triple(id, score, name)
            }
            candidates.maxByOrNull { it.second }?.takeIf { it.second >= 80 }?.first
        } finally {
            connection.disconnect()
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
