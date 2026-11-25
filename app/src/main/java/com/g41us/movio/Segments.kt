package com.g41us.movio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A part of a title that can be skipped. [endMs] null means "to the end". */
data class Segment(val type: String, val startMs: Long, val endMs: Long?) {
    fun contains(pos: Long) = pos >= startMs && (endMs == null || pos < endMs)
    val label get() = when (type) { "recap" -> "Skip recap"; "preview" -> "Skip preview"; "credits" -> "Skip credits"; else -> "Skip intro" }
}

/**
 * Exact intro, recap, credits and preview times from TheIntroDB (free, community-made), by TMDB id.
 * Titles nobody has timed yet return nothing, so no skip button is shown rather than a guess.
 */
object Segments {
    private val cache = HashMap<String, List<Segment>>()

    suspend fun find(kind: Kind, id: Int, season: Int, episode: Int): List<Segment> = withContext(Dispatchers.IO) {
        val q = if (kind == Kind.TV) "tmdb_id=$id&season=$season&episode=$episode" else "tmdb_id=$id"
        cache[q]?.let { return@withContext it }
        try {
            val c = URL("https://api.theintrodb.org/v4/media?$q").openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            if (c.responseCode !in 200..299) return@withContext emptyList()
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val out = listOf("recap", "intro", "credits", "preview").flatMap { type ->
                val a = o.optJSONArray(type) ?: return@flatMap emptyList()
                (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
                    Segment(
                        type,
                        if (s.isNull("start_ms")) 0L else s.optLong("start_ms"),
                        if (s.isNull("end_ms")) null else s.optLong("end_ms"),
                    )
                }
            }.filter { it.endMs == null || it.endMs > it.startMs }.sortedBy { it.startMs }
            cache[q] = out
            out
        } catch (_: Exception) { emptyList() }
    }
}
