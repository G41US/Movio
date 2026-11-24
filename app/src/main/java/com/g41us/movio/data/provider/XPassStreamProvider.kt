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
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/**
 * XPass uses a two-request JSON flow: a title endpoint returns server/entry
 * URLs, and each entry URL returns stream metadata. Only JSON endpoints on the
 * configured XPass host are followed; media URLs are returned to Media3 as-is.
 */
class XPassStreamProvider(
    private val baseUrl: String = "https://play.xpass.top",
) : StreamProvider {
    override val id: String = "xpass"
    override val displayName: String = "XPass"

    private val baseHost = URL(baseUrl).host.lowercase()

    override fun supports(item: MediaItem): Boolean =
        item.tmdbId != null && item.tmdbId > 0 &&
            (item.mediaType == MediaType.MOVIE ||
                item.mediaType == MediaType.SERIES ||
                item.mediaType == MediaType.ANIME)

    override suspend fun resolve(item: MediaItem, episode: Episode?): List<StreamCandidate> =
        withContext(Dispatchers.IO) {
            val tmdbId = item.tmdbId ?: return@withContext emptyList()
            val endpoint = if (item.mediaType == MediaType.MOVIE) {
                "$baseUrl/e/movie/$tmdbId"
            } else {
                val selected = episode ?: return@withContext emptyList()
                val season = selected.seasonNumber ?: return@withContext emptyList()
                "$baseUrl/e/tv/$tmdbId/$season/${selected.number}"
            }

            val entryUrls = mutableListOf<String>()
            collectEntryUrls(requestJson(endpoint), entryUrls)
            entryUrls.distinct().take(8).flatMap { entryUrl ->
                runCatching {
                    collectCandidates(requestJson(entryUrl))
                }.getOrDefault(emptyList())
            }.distinctBy { it.url }
        }

    private fun requestJson(endpoint: String): Any {
        val parsed = URL(endpoint)
        require(parsed.protocol == "https" && parsed.host.equals(baseHost, true)) {
            "XPass JSON endpoint must remain on the configured HTTPS host"
        }
        val connection = parsed.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Movio-Android/${com.g41us.movio.BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Referer", "$baseUrl/")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("XPass returned HTTP $status")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONTokener(body).nextValue()
        } finally {
            connection.disconnect()
        }
    }

    private fun collectEntryUrls(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) collectEntryUrls(value.opt(i), output)
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key.equals("url", true) || key.equals("link", true) || key.equals("src", true)) {
                        val candidate = child?.toString().orEmpty()
                        normalizeEntry(candidate)?.let(output::add)
                    } else if (child is JSONObject || child is JSONArray) {
                        collectEntryUrls(child, output)
                    }
                }
            }
        }
    }

    private fun normalizeEntry(value: String): String? = runCatching {
        val resolved = URL(URL("$baseUrl/"), value)
        val uri = Uri.parse(resolved.toString())
        require(uri.scheme == "https" && uri.host.equals(baseHost, true))
        require(uri.path?.startsWith("/e/") == false)
        require(!uri.path.orEmpty().substringAfterLast('/').contains(".m3u8", true))
        require(!uri.path.orEmpty().substringAfterLast('/').contains(".mp4", true))
        resolved.toString()
    }.getOrNull()

    private fun collectCandidates(value: Any?): List<StreamCandidate> {
        val output = mutableListOf<StreamCandidate>()
        fun visit(node: Any?) {
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
                is JSONObject -> {
                    val url = sequenceOf("url", "file", "link", "src")
                        .map { node.optString(it) }
                        .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                    if (url != null) {
                        val type = sequenceOf("type", "format", "mimeType", "mime")
                            .map { node.optString(it).lowercase() }
                            .firstOrNull { it.isNotBlank() }.orEmpty()
                        val format = when {
                            type.contains("mpegurl") || type == "hls" || url.substringBefore('?').endsWith(".m3u8", true) -> StreamFormat.HLS
                            type.contains("dash") || url.substringBefore('?').endsWith(".mpd", true) -> StreamFormat.DASH
                            type.contains("mp4") || url.substringBefore('?').endsWith(".mp4", true) -> StreamFormat.MP4
                            else -> StreamFormat.UNKNOWN
                        }
                        val headers = linkedMapOf<String, String>()
                        node.optJSONObject("headers")?.let { h ->
                            val names = h.keys()
                            while (names.hasNext()) {
                                val name = names.next()
                                h.optString(name).takeIf { it.isNotBlank() }?.let { headers[name] = it }
                            }
                        }
                        val subtitles = node.optJSONArray("subtitles")?.let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                val sub = array.optJSONObject(index) ?: return@mapNotNull null
                                val subUrl = sequenceOf("url", "file", "src").map { sub.optString(it) }
                                    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                                    ?: return@mapNotNull null
                                SubtitleTrack(
                                    url = subUrl,
                                    language = sub.optString("lang").takeIf { it.isNotBlank() },
                                    label = sub.optString("label").takeIf { it.isNotBlank() },
                                    isDefault = sub.optBoolean("default", false),
                                )
                            }
                        }.orEmpty()
                        output += StreamCandidate(
                            url = url,
                            format = format,
                            qualityLabel = sequenceOf("quality", "label", "name")
                                .map { node.optString(it) }.firstOrNull { it.isNotBlank() },
                            mimeType = node.optString("mimeType").takeIf { it.isNotBlank() },
                            headers = headers,
                            subtitles = subtitles,
                        )
                    }
                    val names = node.keys()
                    while (names.hasNext()) {
                        val key = names.next()
                        if (key !in setOf("headers", "subtitles")) {
                            val child = node.opt(key)
                            if (child is JSONObject || child is JSONArray) visit(child)
                        }
                    }
                }
            }
        }
        visit(value)
        return output.distinctBy { it.url }
    }
}
