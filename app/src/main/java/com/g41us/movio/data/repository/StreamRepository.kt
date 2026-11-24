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

data class ProviderDiagnostic(
    val providerName: String,
    val sourceCount: Int,
    val error: String? = null
)

data class StreamResolution(
    val candidates: List<StreamCandidate>,
    val diagnostics: List<ProviderDiagnostic>
)

class StreamRepository(
    private val registry: StreamProviderRegistry = MovioStreamProviders.createRegistry()
) {
    fun providerNames(item: MediaItem): List<String> =
        registry.providersFor(item).map { it.displayName }

    /** Compatibility wrapper for callers that only need playable candidates. */
    suspend fun resolve(item: MediaItem, episode: Episode? = null): List<StreamCandidate> =
        resolveDetailed(item, episode).candidates

    /** Resolve providers independently and retain each provider's outcome for diagnostics. */
    suspend fun resolveDetailed(item: MediaItem, episode: Episode? = null): StreamResolution =
        withContext(Dispatchers.IO) {
            val providers = registry.providersFor(item)
            val outcomes = supervisorScope {
                providers.map { provider ->
                    async {
                        try {
                            val candidates = provider.resolve(item, episode)
                            ProviderDiagnostic(provider.displayName, candidates.size)
                                .let { it to candidates }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            val reason = error.message?.take(180)
                                ?: error.javaClass.simpleName
                            ProviderDiagnostic(provider.displayName, 0, reason) to emptyList()
                        }
                    }
                }.map { it.await() }
            }
            val candidates = outcomes.flatMap { it.second }.distinctBy { it.url }
                .sortedWith(
                    compareBy<StreamCandidate> { formatRank(it) }
                        .thenByDescending { qualityRank(it.qualityLabel) }
                )
            StreamResolution(candidates, outcomes.map { it.first })
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
