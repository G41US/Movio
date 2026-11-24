package com.g41us.movio.data.provider

import android.content.Context
import com.g41us.movio.BuildConfig
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * Matches catalogue metadata to TMDB. The user's API key is stored locally in
 * app preferences; no key is bundled in the APK or sent to Movio servers.
 *
 * Order: existing TMDB ID → exact IMDb lookup → strict title + year search.
 * A wrong match plays the wrong title, so the search returns null rather than guess.
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

        val isMovie = when (item.mediaType) {
            MediaType.MOVIE -> true
            MediaType.SERIES, MediaType.ANIME -> false
            else -> return@withContext null
        }

        item.imdbId?.let { imdb ->
            val root = get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")
            val results = root.optJSONArray(if (isMovie) "movie_results" else "tv_results")
            results?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.let { return@withContext it }
        }

        val endpoint = if (isMovie) "movie" else "tv"
        val query = URLEncoder.encode(item.title, "UTF-8")
        val root = get("https://api.themoviedb.org/3/search/$endpoint?api_key=$key&query=$query&include_adult=false")
        val results = root.optJSONArray("results") ?: return@withContext null
        val wanted = normalize(item.title)

        data class Candidate(val id: Int, val year: Int?, val popularity: Double)
        val exact = (0 until results.length()).mapNotNull { index ->
            val row = results.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val names = listOf("title", "original_title", "name", "original_name")
                .map { normalize(row.optString(it)) }.filter { it.isNotBlank() }
            if (wanted !in names) return@mapNotNull null
            val year = row.optString("release_date").ifBlank { row.optString("first_air_date") }
                .take(4).toIntOrNull()
            Candidate(id, year, row.optDouble("popularity", 0.0))
        }

        val itemYear = item.releaseYear
        when {
            // Year known: only accept a title released that year (±1 for regional dates).
            itemYear != null -> exact.filter { it.year != null && abs(it.year - itemYear) <= 1 }
                .minWithOrNull(compareBy<Candidate> { abs(it.year!! - itemYear) }.thenByDescending { it.popularity })
                ?.id
            // No year: only safe when the exact title is unique.
            else -> exact.singleOrNull()?.id
        }
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Movio-Android/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status == 401) throw IllegalStateException("TMDB rejected the API key. Check it in Settings")
            if (status !in 200..299) throw IllegalStateException("TMDB matching failed (HTTP $status)")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
