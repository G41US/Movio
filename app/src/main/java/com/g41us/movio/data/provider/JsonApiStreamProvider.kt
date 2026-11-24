package com.g41us.movio.data.provider

import android.net.Uri
import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.MediaType
import com.g41us.movio.data.model.StreamCandidate
import com.g41us.movio.data.model.StreamFormat
import com.g41us.movio.data.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Clean, provider-neutral adapter for a JSON API that the Movio owner is
 * authorized to use. It deliberately does not scrape embed pages, execute
 * page scripts, defeat access checks, or extract DRM-protected media.
 *
 * URL templates may contain {tmdb_id}, {season}, and {episode}.
 */
data class JsonStreamProviderConfig(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val movieUrl: String? = null,
    val tvUrl: String? = null,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val displayOrder: Int = 100,
) {
    companion object {
        fun fromJson(json: JSONObject): JsonStreamProviderConfig {
            val headersObject = json.optJSONObject("headers") ?: JSONObject()
            val headers = buildMap {
                val keys = headersObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headersObject.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
            return JsonStreamProviderConfig(
                id = json.optString("id").trim(),
                name = json.optString("name").ifBlank { json.optString("id") },
                enabled = json.optBoolean("enabled", false),
                movieUrl = json.optString("movie_url").takeIf { it.isNotBlank() },
                tvUrl = json.optString("tv_url").takeIf { it.isNotBlank() },
                referer = json.optString("referer").takeIf { it.isNotBlank() },
                headers = headers,
                displayOrder = json.optInt("display_order", 100),
            )
        }
    }
}

/** A generic JSON endpoint adapter; no provider is enabled by default. */
class JsonApiStreamProvider(
    private val config: JsonStreamProviderConfig,
) : StreamProvider {
    override val id: String = config.id
    override val displayName: String = config.name

    override fun supports(item: MediaItem): Boolean {
        if (!config.enabled || item.tmdbId == null || item.tmdbId <= 0) return false
        return when (item.mediaType) {
            MediaType.MOVIE -> !config.movieUrl.isNullOrBlank()
            MediaType.SERIES, MediaType.ANIME -> !config.tvUrl.isNullOrBlank()
            else -> false
        }
    }

    override suspend fun resolve(item: MediaItem, episode: Episode?): List<StreamCandidate> =
        withContext(Dispatchers.IO) {
            val tmdbId = item.tmdbId ?: return@withContext emptyList()
            val template = when (item.mediaType) {
                MediaType.MOVIE -> config.movieUrl
                MediaType.SERIES, MediaType.ANIME -> config.tvUrl
                else -> null
            } ?: return@withContext emptyList()

            val endpoint = if (item.mediaType == MediaType.MOVIE) {
                template.replace("{tmdb_id}", Uri.encode(tmdbId.toString()))
            } else {
                val selectedEpisode = episode ?: return@withContext emptyList()
                val season = selectedEpisode.seasonNumber ?: return@withContext emptyList()
                template
                    .replace("{tmdb_id}", Uri.encode(tmdbId.toString()))
                    .replace("{season}", Uri.encode(season.toString()))
                    .replace("{episode}", Uri.encode(selectedEpisode.number.toString()))
            }

            val response = request(endpoint)
            parseCandidates(response)
        }

    private fun request(endpoint: String): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Movio-Android/0.2.0")
            config.referer?.let { connection.setRequestProperty("Referer", it) }
            config.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("${config.name} returned HTTP $status")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCandidates(root: JSONObject): List<StreamCandidate> {
        val nested = root.optJSONObject("data") ?: root
        val sources = nested.optJSONArray("sources")
            ?: nested.optJSONArray("streams")
            ?: nested.optJSONArray("results")
        val candidates = mutableListOf<StreamCandidate>()

        if (sources != null) {
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                candidateFrom(source)?.let(candidates::add)
            }
        } else {
            candidateFrom(nested)?.let(candidates::add)
        }
        return candidates.distinctBy { it.url }
    }

    private fun candidateFrom(source: JSONObject): StreamCandidate? {
        val url = listOf("url", "file", "src", "link")
            .firstNotNullOfOrNull { key -> source.optString(key).takeIf { it.startsWith("http://") || it.startsWith("https://") } }
            ?: return null

        val declaredType = listOf("type", "streamType", "mimeType", "mime_type")
            .firstNotNullOfOrNull { key -> source.optString(key).takeIf { it.isNotBlank() } }
            ?.lowercase()
        val format = when {
            declaredType?.contains("mpegurl") == true || declaredType == "hls" || url.substringBefore('?').endsWith(".m3u8", true) -> StreamFormat.HLS
            declaredType?.contains("dash") == true || url.substringBefore('?').endsWith(".mpd", true) -> StreamFormat.DASH
            declaredType?.contains("mp4") == true || url.substringBefore('?').endsWith(".mp4", true) -> StreamFormat.MP4
            else -> StreamFormat.UNKNOWN
        }

        val headers = linkedMapOf<String, String>().apply {
            putAll(config.headers)
            val sourceHeaders = source.optJSONObject("headers")
            if (sourceHeaders != null) {
                val keys = sourceHeaders.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = sourceHeaders.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
            config.referer?.let { putIfAbsent("Referer", it) }
        }

        val subtitles = parseSubtitles(source.optJSONArray("subtitles"))
        val quality = listOf("quality", "label", "name")
            .firstNotNullOfOrNull { key -> source.optString(key).takeIf { it.isNotBlank() } }
        val mime = source.optString("mimeType").takeIf { it.isNotBlank() }
            ?: when (format) {
                StreamFormat.HLS -> "application/x-mpegURL"
                StreamFormat.DASH -> "application/dash+xml"
                StreamFormat.MP4 -> "video/mp4"
                StreamFormat.UNKNOWN -> null
            }

        return StreamCandidate(
            url = url,
            format = format,
            qualityLabel = quality,
            mimeType = mime,
            headers = headers,
            subtitles = subtitles,
            expiresAtEpochSeconds = source.optLong("expiresAt").takeIf { it > 0 }
        )
    }

    private fun parseSubtitles(array: JSONArray?): List<SubtitleTrack> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = listOf("url", "file", "src")
                .firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.startsWith("http://") || it.startsWith("https://") } }
                ?: return@mapNotNull null
            SubtitleTrack(
                url = url,
                language = item.optString("lang").takeIf { it.isNotBlank() }
                    ?: item.optString("language").takeIf { it.isNotBlank() },
                label = item.optString("label").takeIf { it.isNotBlank() }
                    ?: item.optString("name").takeIf { it.isNotBlank() },
                isDefault = item.optBoolean("default", false)
            )
        }
    }
}

/** Builds an independent provider registry from a JSON config's providers array. */
object JsonStreamProviderRegistryFactory {
    fun fromJson(config: JSONObject): StreamProviderRegistry {
        val array = config.optJSONArray("providers") ?: JSONArray()
        val providers = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val providerConfig = runCatching { JsonStreamProviderConfig.fromJson(item) }.getOrNull()
                ?: return@mapNotNull null
            if (providerConfig.id.isBlank() || providerConfig.name.isBlank()) return@mapNotNull null
            JsonApiStreamProvider(providerConfig)
        }.sortedBy { (it as JsonApiStreamProvider).let { provider -> provider.id } }
        return StreamProviderRegistry(providers)
    }
}
