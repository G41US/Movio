package com.g41us.movio.data.repository

import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.Season
import com.g41us.movio.data.provider.MediaDetails
import com.g41us.movio.data.provider.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CatalogRepository(
    private val registry: ProviderRegistry = ProviderRegistry()
) {
    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        registry.catalogProviders.flatMap { provider ->
            runCatching { provider.search(query.trim()) }.getOrDefault(emptyList())
        }.distinctBy { it.id }.sortedBy { it.title.lowercase() }
    }

    suspend fun details(item: MediaItem): MediaDetails? = withContext(Dispatchers.IO) {
        registry.catalogProvider(item.providerId)?.let { provider ->
            runCatching { provider.getDetails(item) }.getOrNull()
        }
    }

    suspend fun seasons(item: MediaItem): List<Season> = withContext(Dispatchers.IO) {
        registry.catalogProvider(item.providerId)?.let { provider ->
            runCatching { provider.getSeasons(item) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun episodes(item: MediaItem, season: Season? = null): List<Episode> = withContext(Dispatchers.IO) {
        registry.catalogProvider(item.providerId)?.let { provider ->
            runCatching { provider.getEpisodes(item, season) }.getOrDefault(emptyList())
        } ?: emptyList()
    }
}
