package com.g41us.movio.data.repository

import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.StreamCandidate
import com.g41us.movio.data.provider.MovioStreamProviders
import com.g41us.movio.data.provider.StreamProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StreamRepository(
    private val registry: StreamProviderRegistry = MovioStreamProviders.createRegistry()
) {
    suspend fun resolve(item: MediaItem, episode: Episode? = null): List<StreamCandidate> =
        withContext(Dispatchers.IO) {
            registry.providersFor(item).flatMap { provider ->
                runCatching { provider.resolve(item, episode) }.getOrDefault(emptyList())
            }.distinctBy { it.url }
        }
}
