package com.g41us.movio.data.model

/**
 * Provider-neutral catalogue models. Provider implementations map their own
 * metadata into these types so the UI and player never depend on a source site's
 * response format.
 */
data class MediaItem(
    val id: String,
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: Int? = null,
    val genres: List<String> = emptyList(),
    val mediaType: MediaType,
    val providerId: String,
    val providerItemId: String,
)

enum class MediaType {
    MOVIE,
    SERIES,
    ANIME,
    LIVE_CHANNEL,
    SPORTS_EVENT,
}

data class Season(
    val id: String,
    val title: String,
    val number: Int,
    val episodes: List<Episode>,
)

data class Episode(
    val id: String,
    val title: String,
    val number: Int,
    val overview: String = "",
    val thumbnailUrl: String? = null,
    val providerEpisodeId: String,
)

data class StreamCandidate(
    val url: String,
    val format: StreamFormat,
    val qualityLabel: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val expiresAtEpochSeconds: Long? = null,
)

enum class StreamFormat {
    HLS,
    DASH,
    MP4,
    UNKNOWN,
}

data class SubtitleTrack(
    val url: String,
    val language: String? = null,
    val label: String? = null,
    val isDefault: Boolean = false,
)
