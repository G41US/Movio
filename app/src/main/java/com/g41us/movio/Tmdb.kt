package com.g41us.movio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

enum class Kind(val api: String) { MOVIE("movie"), TV("tv") }

data class Title(
    val id: Int,
    val kind: Kind,
    val name: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val year: String?,
    val rating: Double,
    val genreIds: List<Int> = emptyList(),
) {
    val key get() = "${kind.api}:$id"
}

data class Season(val number: Int, val name: String, val episodeCount: Int)
data class Episode(val number: Int, val name: String, val overview: String, val still: String?, val runtime: Int?)

data class Person(val id: Int, val name: String, val role: String, val photo: String?)

data class Details(
    val title: Title,
    val tagline: String,
    val runtime: Int?,
    val genres: List<String>,
    val seasons: List<Season>,
    val cast: List<Person>,
    val similar: List<Title>,
    val logo: String?,
    val trailer: String?,
    /** Poster with no title printed on it (or null if TMDB has none). */
    val cleanPoster: String?,
)

/** Artwork without baked-in title text, plus the title logo, for big hero images. */
data class Art(val image: String?, val wide: Boolean, val logo: String?)

data class PersonDetails(val person: Person, val bio: String, val known: List<Title>)

data class Row(val name: String, val items: List<Title>, val ranked: Boolean = false)

data class Genre(val id: Int, val name: String)

/** TMDB catalog. Braflix plays titles by TMDB id, so everything shown here is playable there. */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3/"
    private val cache = ConcurrentHashMap<String, JSONObject>()

    val movieGenres = listOf(
        Genre(28, "Action"), Genre(35, "Comedy"), Genre(18, "Drama"), Genre(27, "Horror"),
        Genre(878, "Sci-Fi"), Genre(53, "Thriller"), Genre(10749, "Romance"), Genre(16, "Animation"),
        Genre(80, "Crime"), Genre(12, "Adventure"), Genre(99, "Documentary"), Genre(10751, "Family"),
    )
    val tvGenres = listOf(
        Genre(10759, "Action"), Genre(35, "Comedy"), Genre(18, "Drama"), Genre(80, "Crime"),
        Genre(10765, "Sci-Fi"), Genre(9648, "Mystery"), Genre(16, "Animation"), Genre(99, "Documentary"),
        Genre(10764, "Reality"), Genre(10751, "Family"),
    )

    fun img(path: String?, size: String = "w500"): String? = path?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** Where catalogue responses are kept on the phone (set at app start). */
    @Volatile var diskDir: java.io.File? = null
    private const val FRESH_MS = 6 * 60 * 60 * 1000L

    /**
     * Catalogue request with a disk cache: anything fetched in the last 6 hours opens instantly,
     * and with no internet the last saved copy is used however old it is.
     */
    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val q = (params + ("api_key" to BuildConfig.TMDB_KEY)).entries
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val url = "$API$path?$q"
        cache[url]?.let { return@withContext it }
        val file = diskDir?.let { java.io.File(it, Integer.toHexString(url.hashCode()) + ".json") }
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < FRESH_MS) {
            try { return@withContext JSONObject(file.readText()).also { cache[url] = it } } catch (_: Exception) { }
        }
        try {
            if (BuildConfig.TMDB_KEY.isBlank()) throw IOException("Catalog key missing")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 12_000
                conn.readTimeout = 15_000
                if (conn.responseCode !in 200..299) throw IOException("Catalog error ${conn.responseCode}")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                try { file?.writeText(text) } catch (_: Exception) { }
                JSONObject(text).also { cache[url] = it }
            } finally { conn.disconnect() }
        } catch (e: IOException) {
            // Offline: fall back to the last saved copy.
            if (file != null && file.exists()) JSONObject(file.readText()).also { cache[url] = it } else throw e
        }
    }

    private fun parse(o: JSONObject, fallback: Kind?): Title? {
        val kind = when (o.optString("media_type")) {
            "movie" -> Kind.MOVIE
            "tv" -> Kind.TV
            "" -> fallback ?: return null
            else -> return null
        }
        val name = o.optString(if (kind == Kind.MOVIE) "title" else "name").ifBlank { return null }
        val date = o.optString(if (kind == Kind.MOVIE) "release_date" else "first_air_date")
        val genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
        return Title(
            id = o.getInt("id"), kind = kind, name = name,
            overview = o.optString("overview"),
            poster = o.optString("poster_path").takeIf { it.startsWith("/") },
            backdrop = o.optString("backdrop_path").takeIf { it.startsWith("/") },
            year = date.take(4).takeIf { it.length == 4 },
            rating = o.optDouble("vote_average", 0.0),
            genreIds = genres,
        )
    }

    private fun parseList(a: JSONArray?, fallback: Kind?): List<Title> =
        (0 until (a?.length() ?: 0)).mapNotNull { parse(a!!.getJSONObject(it), fallback) }.filter { it.poster != null }

    suspend fun list(path: String, kind: Kind?, params: Map<String, String> = emptyMap()): List<Title> =
        parseList(get(path, params).optJSONArray("results"), kind)

    /** "Because you watched" rows from recent titles' recommendations. */
    suspend fun becauseYouWatched(recent: List<Title>, exclude: Set<String>): List<Row> =
        recent.distinctBy { it.key }.take(3).mapNotNull { t ->
            try {
                val recs = details(t).similar.filter { it.key !in exclude }
                if (recs.size >= 4) Row("Because you watched ${t.name}", recs) else null
            } catch (_: Exception) { null }
        }

    suspend fun home(): List<Row> = listOf(
        Row("Trending this week", list("trending/all/week", null)),
        Row("Top 10 today", list("trending/all/day", null).take(10), ranked = true),
        Row("New in cinemas", list("movie/now_playing", Kind.MOVIE)),
        Row("Popular movies", list("movie/popular", Kind.MOVIE)),
        Row("Popular shows", list("tv/popular", Kind.TV)),
        Row("Top rated movies", list("movie/top_rated", Kind.MOVIE)),
        Row("Top rated shows", list("tv/top_rated", Kind.TV)),
    ).filter { it.items.isNotEmpty() }

    suspend fun browse(kind: Kind, genre: Genre?): List<Row> {
        if (genre != null) {
            val params = mapOf("with_genres" to genre.id.toString(), "sort_by" to "popularity.desc")
            val page2 = params + ("page" to "2")
            return listOf(
                Row("Popular ${genre.name}", list("discover/${kind.api}", kind, params)),
                Row("More ${genre.name}", list("discover/${kind.api}", kind, page2)),
                Row("Highly rated ${genre.name}", list("discover/${kind.api}", kind, params + mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "300"))),
            ).filter { it.items.isNotEmpty() }
        }
        return if (kind == Kind.MOVIE) listOf(
            Row("Trending movies", list("trending/movie/week", Kind.MOVIE)),
            Row("Top 10 movies", list("trending/movie/day", Kind.MOVIE).take(10), ranked = true),
            Row("Now playing", list("movie/now_playing", Kind.MOVIE)),
            Row("Popular", list("movie/popular", Kind.MOVIE)),
            Row("Coming soon", list("movie/upcoming", Kind.MOVIE)),
            Row("Top rated", list("movie/top_rated", Kind.MOVIE)),
        ) else listOf(
            Row("Trending shows", list("trending/tv/week", Kind.TV)),
            Row("Top 10 shows", list("trending/tv/day", Kind.TV).take(10), ranked = true),
            Row("Airing today", list("tv/airing_today", Kind.TV)),
            Row("Popular", list("tv/popular", Kind.TV)),
            Row("On the air", list("tv/on_the_air", Kind.TV)),
            Row("Top rated", list("tv/top_rated", Kind.TV)),
        )
    }

    suspend fun search(q: String): List<Title> = list("search/multi", null, mapOf("query" to q, "include_adult" to "false"))

    suspend fun details(t: Title): Details {
        val o = get("${t.kind.api}/${t.id}", mapOf("append_to_response" to "credits,similar,recommendations,images,videos", "include_image_language" to "en,null"))
        val full = parse(o, t.kind) ?: t
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList()
        val runtime = if (t.kind == Kind.MOVIE) o.optInt("runtime").takeIf { it > 0 }
        else o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        val seasons = o.optJSONArray("seasons")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optInt("season_number") > 0 && it.optInt("episode_count") > 0 }
                .map { Season(it.optInt("season_number"), it.optString("name"), it.optInt("episode_count")) }
        } ?: emptyList()
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 15)).map { a.getJSONObject(it) }.map {
                Person(it.optInt("id"), it.optString("name"), it.optString("character"), it.optString("profile_path").takeIf { p -> p.startsWith("/") })
            }
        } ?: emptyList()
        val trailer = o.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") }
                .sortedBy { if (it.optString("type") == "Trailer") 0 else 1 }
                .firstOrNull()?.optString("key")
        }
        val recs = parseList(o.optJSONObject("recommendations")?.optJSONArray("results"), t.kind)
        val similar = recs.ifEmpty { parseList(o.optJSONObject("similar")?.optJSONArray("results"), t.kind) }
        val logo = o.optJSONObject("images")?.optJSONArray("logos")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optString("iso_639_1") == "en" || it.isNull("iso_639_1") }
                ?.optString("file_path")
        }
        val clean = o.optJSONObject("images")?.optJSONArray("posters")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.isNull("iso_639_1") || it.optString("iso_639_1").isBlank() }
                .maxByOrNull { it.optDouble("vote_average", 0.0) * 10 + it.optInt("vote_count") }?.optString("file_path")
        }
        return Details(full.copy(poster = full.poster ?: t.poster), o.optString("tagline"), runtime, genres, seasons, cast, similar, logo, trailer, clean)
    }

    /** Clean hero artwork: textless poster, else the (text-free) backdrop; plus the logo. */
    suspend fun art(t: Title): Art = try {
        val d = details(t)
        when {
            d.cleanPoster != null -> Art(d.cleanPoster, false, d.logo)
            d.title.backdrop != null -> Art(d.title.backdrop, true, d.logo)
            else -> Art(t.poster, false, null)
        }
    } catch (_: Exception) { Art(t.backdrop ?: t.poster, t.backdrop != null, null) }

    suspend fun person(id: Int): PersonDetails {
        val o = get("person/$id", mapOf("append_to_response" to "combined_credits"))
        val credits = parseList(o.optJSONObject("combined_credits")?.optJSONArray("cast"), null)
            .distinctBy { it.key }.sortedByDescending { it.rating * (if (it.year != null) 1 else 0) }
        return PersonDetails(
            Person(id, o.optString("name"), o.optString("known_for_department"), o.optString("profile_path").takeIf { it.startsWith("/") }),
            o.optString("biography"), credits.take(60),
        )
    }

    /** Latest aired episode of a show, as "season:episode" plus its air date. */
    suspend fun lastAired(showId: Int): Pair<String, String>? {
        val l = get("tv/$showId").optJSONObject("last_episode_to_air") ?: return null
        return "${l.optInt("season_number")}:${l.optInt("episode_number")}" to l.optString("air_date")
    }

    suspend fun episodeCount(showId: Int, season: Int): Int =
        get("tv/$showId").optJSONArray("seasons")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt("season_number") == season }?.optInt("episode_count")
        } ?: 0

    suspend fun seasonCount(showId: Int): Int = get("tv/$showId").optInt("number_of_seasons")

    suspend fun episodes(showId: Int, season: Int): List<Episode> {
        val a = get("tv/$showId/season/$season").optJSONArray("episodes") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Episode(
                it.optInt("episode_number"), it.optString("name"), it.optString("overview"),
                it.optString("still_path").takeIf { p -> p.startsWith("/") }, it.optInt("runtime").takeIf { r -> r > 0 },
            )
        }
    }
}
