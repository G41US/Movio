package com.g41us.movio.data.provider

import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.Season
import com.g41us.movio.data.model.StreamCandidate

/**
 * Contract implemented by each catalogue source.
 *
 * Implementations must respect the source's access rules and return normalized
 * Movio models. Keep source-specific parsing and identifiers inside the adapter.
 */
interface CatalogProvider {
    val id: String
    val displayName: String

    suspend fun search(query: String): List<MediaItem>

    suspend fun getDetails(item: MediaItem): MediaDetails

    suspend fun getSeasons(item: MediaItem): List<Season>

    suspend fun getEpisodes(item: MediaItem, season: Season? = null): List<Episode>

    suspend fun getStreams(episode: Episode): List<StreamCandidate>
}

data class MediaDetails(
    val item: MediaItem,
    val cast: List<String> = emptyList(),
    val status: String? = null,
    val contentRating: String? = null,
)

data class ProviderStatus(
    val providerId: String,
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    val message: String? = null,
)
