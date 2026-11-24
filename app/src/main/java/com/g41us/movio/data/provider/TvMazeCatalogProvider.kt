package com.g41us.movio.data.provider

import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.MediaType
import com.g41us.movio.data.model.Season
import com.g41us.movio.data.model.StreamCandidate
import com.g41us.movio.data.network.HttpJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class TvMazeCatalogProvider : CatalogProvider {
    override val id = "tvmaze"
    override val displayName = "TVmaze"

    override suspend fun search(query: String): List<MediaItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = HttpJson.getArray("https://api.tvmaze.com/search/shows?q=$encoded")
        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.optJSONObject("show")?.toMediaItem()
        }
    }

    override suspend fun getDetails(item: MediaItem): MediaDetails {
        val show = HttpJson.getObject("https://api.tvmaze.com/shows/${item.providerItemId}")
        val cast = runCatching {
            val castArray = HttpJson.getArray("https://api.tvmaze.com/shows/${item.providerItemId}/cast")
            (0 until castArray.length()).mapNotNull { castArray.optJSONObject(it)?.optJSONObject("person")?.optString("name") }
        }.getOrDefault(emptyList())
        return MediaDetails(
            item = show.toMediaItem(),
            cast = cast.filter { !it.isNullOrBlank() }.take(12),
            status = show.optString("status").takeIf { it.isNotBlank() },
            contentRating = show.optJSONObject("rating")?.optDouble("average")?.takeIf { it > 0 }?.let { "TVmaze rating $it" }
        )
    }

    override suspend fun getSeasons(item: MediaItem): List<Season> {
        val seasons = HttpJson.getArray("https://api.tvmaze.com/shows/${item.providerItemId}/seasons")
        return (0 until seasons.length()).mapNotNull { index ->
            seasons.optJSONObject(index)?.let { season ->
                val id = season.optLong("id").toString()
                Season(
                    id = id,
                    title = season.optString("name").ifBlank { "Season ${season.optInt("number")}" },
                    number = season.optInt("number"),
                    episodes = emptyList()
                )
            }
        }
    }

    override suspend fun getEpisodes(item: MediaItem, season: Season?): List<Episode> {
        val endpoint = if (season != null) {
            "https://api.tvmaze.com/seasons/${season.id}/episodes"
        } else {
            "https://api.tvmaze.com/shows/${item.providerItemId}/episodes"
        }
        val episodes = HttpJson.getArray(endpoint)
        return (0 until episodes.length()).mapNotNull { index ->
            episodes.optJSONObject(index)?.let { episode ->
                Episode(
                    id = "tvmaze-${episode.optLong("id")}",
                    title = episode.optString("name").ifBlank { "Episode ${episode.optInt("number")}" },
                    number = episode.optInt("number"),
                    overview = episode.optString("summary").stripHtml(),
                    thumbnailUrl = episode.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() },
                    providerEpisodeId = episode.optLong("id").toString(),
                    seasonNumber = episode.optInt("season").takeIf { it > 0 }
                )
            }
        }
    }

    override suspend fun getStreams(episode: Episode): List<StreamCandidate> = emptyList()

    private fun JSONObject.toMediaItem(): MediaItem {
        val image = optJSONObject("image")
        val premiered = optString("premiered")
        val year = premiered.take(4).toIntOrNull()
        val genres = optJSONArray("genres").toStringList()
        val id = optLong("id").toString()
        // TVMaze has no TMDB field; its IMDb ID lets TMDB match the show exactly.
        val imdbId = optJSONObject("externals")?.optString("imdb")
            ?.takeIf { it.matches(Regex("tt\\d+")) }
        return MediaItem(
            id = "tvmaze-$id",
            title = optString("name").ifBlank { "Untitled show" },
            overview = optString("summary").stripHtml(),
            posterUrl = image?.optString("medium")?.takeIf { it.isNotBlank() },
            backdropUrl = image?.optString("original")?.takeIf { it.isNotBlank() },
            releaseYear = year,
            genres = genres,
            mediaType = MediaType.SERIES,
            providerId = this@TvMazeCatalogProvider.id,
            providerItemId = id,
            imdbId = imdbId
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun String.stripHtml(): String = replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()
}
