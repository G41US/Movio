package com.g41us.movio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * Intro, recap, credits and preview times from two free community databases, matched up:
 * TheIntroDB (by TMDB id) and IntroDB (by IMDb id, with how sure it is). When both have an intro and
 * agree, it's used; when they disagree, the one more people confirmed wins. Titles nobody has timed
 * return nothing, so no skip button is shown rather than a guess.
 */
object Segments {
    private val cache = HashMap<String, List<Segment>>()

    private fun read(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        try { if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    /** TheIntroDB: lists of { start_ms, end_ms }. */
    private fun theIntroDb(q: String): List<Segment> {
        val o = read("https://api.theintrodb.org/v4/media?$q") ?: return emptyList()
        return listOf("recap", "intro", "credits", "preview").flatMap { type ->
            val a = o.optJSONArray(type) ?: return@flatMap emptyList()
            (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
                Segment(type, if (s.isNull("start_ms")) 0L else s.optLong("start_ms"), if (s.isNull("end_ms")) null else s.optLong("end_ms"))
            }
        }
    }

    /** IntroDB: one { start_ms, end_ms, confidence, submission_count } per kind. Returns segments and how sure each is. */
    private fun introDb(imdb: String, kind: Kind, season: Int, episode: Int): List<Pair<Segment, Double>> {
        val q = if (kind == Kind.TV) "imdb_id=$imdb&season=$season&episode=$episode" else "imdb_id=$imdb"
        val o = read("https://api.introdb.app/segments?$q") ?: return emptyList()
        return listOf("recap" to "recap", "intro" to "intro", "outro" to "credits").mapNotNull { (k, type) ->
            val s = o.optJSONObject(k) ?: return@mapNotNull null
            val sure = s.optDouble("confidence", .5) * minOf(1.0, .5 + .25 * s.optInt("submission_count", 1))
            Segment(type, s.optLong("start_ms"), if (s.isNull("end_ms")) null else s.optLong("end_ms")) to sure
        }
    }

    private fun close(a: Segment, b: Segment) = kotlin.math.abs(a.startMs - b.startMs) < 4_000 &&
        kotlin.math.abs((a.endMs ?: Long.MAX_VALUE / 2) - (b.endMs ?: Long.MAX_VALUE / 2)) < 4_000

    suspend fun find(kind: Kind, id: Int, season: Int, episode: Int): List<Segment> = withContext(Dispatchers.IO) {
        val q = if (kind == Kind.TV) "tmdb_id=$id&season=$season&episode=$episode" else "tmdb_id=$id"
        cache[q]?.let { return@withContext it }
        val a = async { theIntroDb(q) }
        val b = async { Tmdb.imdb(kind, id)?.let { introDb(it, kind, season, episode) } ?: emptyList() }
        val one = a.await(); val two = b.await()
        val out = ArrayList<Segment>()
        for (type in listOf("recap", "intro", "credits", "preview")) {
            val x = one.filter { it.type == type }
            val y = two.filter { it.first.type == type }
            out += when {
                y.isEmpty() -> x
                x.isEmpty() -> y.filter { it.second >= .4 }.map { it.first }
                // Both agree: average them. They disagree: trust IntroDB only when several people confirmed it.
                close(x[0], y[0].first) -> listOf(Segment(type, (x[0].startMs + y[0].first.startMs) / 2,
                    if (x[0].endMs == null || y[0].first.endMs == null) x[0].endMs ?: y[0].first.endMs else (x[0].endMs!! + y[0].first.endMs!!) / 2)) + x.drop(1)
                y[0].second >= .75 -> listOf(y[0].first)
                else -> x
            }
        }
        val res = out.filter { it.endMs == null || it.endMs > it.startMs }.sortedBy { it.startMs }
        cache[q] = res
        res
    }
}
