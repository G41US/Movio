package com.g41us.movio.data.repository

import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.StreamCandidate
import com.g41us.movio.data.provider.MovioStreamProviders
import com.g41us.movio.data.provider.StreamProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class StreamRepository(
    private val registry: StreamProviderRegistry = MovioStreamProviders.createRegistry()
) {
    fun providerNames(item: MediaItem): List<String> =
        registry.providersFor(item).map { it.displayName }

    /** Run independent providers concurrently; one provider failure never cancels its siblings. */
    suspend fun resolve(item: MediaItem, episode: Episode? = null): List<StreamCandidate> =
        withContext(Dispatchers.IO) {
            val providers = registry.providersFor(item)
            supervisorScope {
                providers.map { provider ->
                    async {
                        try {
                            provider.resolve(item, episode)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.flatMap { it.await() }
            }.distinctBy { it.url }
                .sortedWith(
                    compareBy<StreamCandidate> { formatRank(it) }
                        .thenByDescending { qualityRank(it.qualityLabel) }
                )
        }

    private fun formatRank(candidate: StreamCandidate): Int = when (candidate.format) {
        com.g41us.movio.data.model.StreamFormat.HLS -> 0
        com.g41us.movio.data.model.StreamFormat.DASH -> 1
        com.g41us.movio.data.model.StreamFormat.MP4 -> 2
        com.g41us.movio.data.model.StreamFormat.UNKNOWN -> 3
    }

    private fun qualityRank(label: String?): Int {
        val text = label.orEmpty().lowercase()
        return Regex("(\\d{3,4})\\s*p").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d{3,4})").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
    }
}
