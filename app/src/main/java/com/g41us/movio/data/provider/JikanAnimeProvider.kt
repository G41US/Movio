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

class JikanAnimeProvider : CatalogProvider {
    override val id = "jikan"
    override val displayName = "Jikan / MyAnimeList"

    override suspend fun search(query: String): List<MediaItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = HttpJson.getObject("https://api.jikan.moe/v4/anime?q=$encoded&limit=12&sfw=true")
        return response.optJSONArray("data").toMediaItems()
    }

    override suspend fun getDetails(item: MediaItem): MediaDetails {
        val response = HttpJson.getObject("https://api.jikan.moe/v4/anime/${item.providerItemId}/full")
        val anime = response.optJSONObject("data") ?: return MediaDetails(item)
        val cast = anime.optJSONArray("studios").toStringList("name")
        val status = anime.optString("status").takeIf { it.isNotBlank() }
        val rating = anime.optString("rating").takeIf { it.isNotBlank() }
        return MediaDetails(anime.toMediaItem(), cast, status, rating)
    }

    override suspend fun getSeasons(item: MediaItem): List<Season> = emptyList()

    override suspend fun getEpisodes(item: MediaItem, season: Season?): List<Episode> {
        val response = HttpJson.getObject("https://api.jikan.moe/v4/anime/${item.providerItemId}/episodes")
        return response.optJSONArray("data").let { array ->
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { episode ->
                    val episodeId = episode.optLong("mal_id")
                    Episode(
                        id = "jikan-${item.providerItemId}-$episodeId",
                        title = episode.optString("title").ifBlank { "Episode ${episode.optInt("episode")}" },
                        number = episode.optInt("episode"),
                        overview = episode.optString("title_japanese"),
                        providerEpisodeId = episodeId.toString()
                    )
                }
            }
        }
    }

    override suspend fun getStreams(episode: Episode): List<StreamCandidate> = emptyList()

    private fun JSONObject.toMediaItem(): MediaItem {
        val images = optJSONObject("images")?.optJSONObject("jpg")
        val titles = optJSONArray("titles")
        val genres = optJSONArray("genres").toStringList("name")
        val year = optJSONObject("aired")?.optString("from")?.take(4)?.toIntOrNull()
            ?: optInt("year").takeIf { it > 0 }
        val id = optLong("mal_id").toString()
        return MediaItem(
            id = "jikan-$id",
            title = optString("title_english").takeIf { it.isNotBlank() }
                ?: optString("title").ifBlank { "Untitled anime" },
            overview = optString("synopsis"),
            posterUrl = images?.optString("large_image_url")?.takeIf { it.isNotBlank() }
                ?: images?.optString("image_url")?.takeIf { it.isNotBlank() },
            backdropUrl = null,
            releaseYear = year,
            genres = genres,
            mediaType = MediaType.ANIME,
            providerId = this@JikanAnimeProvider.id,
            providerItemId = id
        )
    }

    private fun JSONArray?.toMediaItems(): List<MediaItem> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.toMediaItem() }
    }

    private fun JSONArray?.toStringList(key: String): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.optString(key)?.takeIf(String::isNotBlank) }
    }
}
