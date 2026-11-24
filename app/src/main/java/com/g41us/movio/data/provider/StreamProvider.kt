package com.g41us.movio.data.provider

import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.StreamCandidate

/**
 * Resolves playable media only from a source the app is authorized to use.
 * Metadata providers and stream providers stay independent.
 */
interface StreamProvider {
    val id: String
    val displayName: String

    fun supports(item: MediaItem): Boolean

    suspend fun resolve(item: MediaItem, episode: Episode? = null): List<StreamCandidate>
}

class StreamProviderRegistry(
    val providers: List<StreamProvider> = emptyList()
) {
    fun providersFor(item: MediaItem): List<StreamProvider> =
        providers.filter { it.supports(item) }
}
