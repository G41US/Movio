package com.g41us.movio

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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

/** A person and everything they've been in; [roles] maps a title key to the part they played (or their job). */
data class PersonDetails(val person: Person, val bio: String, val known: List<Title>, val roles: Map<String, String> = emptyMap(), val facts: String = "")

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
        // Titles and descriptions in the viewer's language (TMDB falls back to English where it has none).
        val q = (mapOf("language" to I18n.tmdbLang) + params + ("api_key" to BuildConfig.TMDB_KEY)).entries
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

    /** Kids mode: only family, animated and kids titles anywhere in the catalog. */
    @Volatile var kids = false
    private val KIDS = setOf(16, 10751, 10762)
    fun kidsOk(t: Title) = !kids || t.genreIds.any { it in KIDS }

    private fun parseList(a: JSONArray?, fallback: Kind?): List<Title> =
        (0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.getJSONObject(i).takeIf { !(kids && it.optBoolean("adult")) }?.let { parse(it, fallback) } }
            .filter { it.poster != null && kidsOk(it) }

    suspend fun list(path: String, kind: Kind?, params: Map<String, String> = emptyMap()): List<Title> =
        parseList(get(path, params).optJSONArray("results"), kind)

    /** "Because you watched" rows from recent titles' recommendations. */
    suspend fun becauseYouWatched(recent: List<Title>, exclude: Set<String>): List<Row> = kotlinx.coroutines.coroutineScope {
        recent.distinctBy { it.key }.take(3).map { t ->
            async {
                try {
                    val recs = details(t).similar.filter { it.key !in exclude }
                    if (recs.size >= 4) Row("Because you watched ${t.name}", recs) else null
                } catch (_: Exception) { null }
            }
        }.mapNotNull { it.await() }
    }

    /** Loads every row at once instead of one after another. */
    private suspend fun rows(vararg specs: Pair<String, suspend () -> List<Title>>, ranked: String? = null): List<Row> =
        kotlinx.coroutines.coroutineScope {
            specs.map { (name, load) -> async { Row(name, try { load() } catch (_: Exception) { emptyList() }, ranked = name == ranked) } }.map { it.await() }
        }.filter { it.items.isNotEmpty() }.also { if (it.isEmpty()) error("Nothing loaded") }

    private fun r(name: String, load: suspend () -> List<Title>) = name to load

    /** Rows that come after "Because you watched" on Home. */
    const val PERSONAL_AFTER = 3

    private suspend fun kidsRows(only: Kind? = null): List<Row> = rows(
        *listOfNotNull(
            if (only != Kind.TV) r("Family movies") { list("discover/movie", Kind.MOVIE, mapOf("with_genres" to "10751", "sort_by" to "popularity.desc", "certification_country" to "US", "certification.lte" to "PG")) } else null,
            if (only != Kind.TV) r("Animated movies") { list("discover/movie", Kind.MOVIE, mapOf("with_genres" to "16", "sort_by" to "popularity.desc", "certification_country" to "US", "certification.lte" to "PG")) } else null,
            if (only != Kind.MOVIE) r("Kids shows") { list("discover/tv", Kind.TV, mapOf("with_genres" to "10762", "sort_by" to "popularity.desc")) } else null,
            if (only != Kind.MOVIE) r("Animated shows") { list("discover/tv", Kind.TV, mapOf("with_genres" to "16,10751", "sort_by" to "popularity.desc")) } else null,
            if (only != Kind.TV) r("Top rated family movies") { list("discover/movie", Kind.MOVIE, mapOf("with_genres" to "10751", "sort_by" to "vote_average.desc", "vote_count.gte" to "500", "certification_country" to "US", "certification.lte" to "PG")) } else null,
        ).toTypedArray(),
    )

    suspend fun home(): List<Row> = if (kids) kidsRows() else rows(
        r("Top 10 today") { list("trending/all/day", null).take(10) },
        r("New in cinemas") { list("movie/now_playing", Kind.MOVIE) },
        r("Trending this week") { list("trending/all/week", null) },
        r("Popular movies") { list("movie/popular", Kind.MOVIE) },
        r("Popular shows") { list("tv/popular", Kind.TV) },
        r("Top rated movies") { list("movie/top_rated", Kind.MOVIE) },
        r("Top rated shows") { list("tv/top_rated", Kind.TV) },
        ranked = "Top 10 today",
    )

    suspend fun browse(kind: Kind, genre: Genre?): List<Row> {
        if (kids && genre == null) return kidsRows(kind)
        if (genre != null) {
            val params = mapOf("with_genres" to genre.id.toString(), "sort_by" to "popularity.desc")
            val page2 = params + ("page" to "2")
            return listOf(
                Row("Popular ${genre.name}", list("discover/${kind.api}", kind, params)),
                Row("More ${genre.name}", list("discover/${kind.api}", kind, page2)),
                Row("Highly rated ${genre.name}", list("discover/${kind.api}", kind, params + mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "300"))),
            ).filter { it.items.isNotEmpty() }
        }
        return if (kind == Kind.MOVIE) rows(
            r("Top 10 movies") { list("trending/movie/day", Kind.MOVIE).take(10) },
            r("Now playing") { list("movie/now_playing", Kind.MOVIE) },
            r("Trending movies") { list("trending/movie/week", Kind.MOVIE) },
            r("Popular") { list("movie/popular", Kind.MOVIE) },
            r("Coming soon") { list("movie/upcoming", Kind.MOVIE) },
            r("Top rated") { list("movie/top_rated", Kind.MOVIE) },
            ranked = "Top 10 movies",
        ) else rows(
            r("Top 10 shows") { list("trending/tv/day", Kind.TV).take(10) },
            r("Airing today") { list("tv/airing_today", Kind.TV) },
            r("Trending shows") { list("trending/tv/week", Kind.TV) },
            r("Popular") { list("tv/popular", Kind.TV) },
            r("On the air") { list("tv/on_the_air", Kind.TV) },
            r("Top rated") { list("tv/top_rated", Kind.TV) },
            ranked = "Top 10 shows",
        )
    }

    suspend fun search(q: String): List<Title> = list("search/multi", null, mapOf("query" to q, "include_adult" to "false"))

    /** Search, or browse by category and year (q empty). Same categories as the web app. */
    suspend fun find(q: String, cat: Cat?, year: String?): List<Title> = kotlinx.coroutines.coroutineScope {
        val yr = yearRange(year)
        fun ofYear(o: JSONObject): Boolean {
            val y = (o.optString("release_date").ifBlank { o.optString("first_air_date") }).take(4).toIntOrNull()
            return yr == null || (y != null && y in yr)
        }
        val raw: List<Pair<JSONObject, Kind?>> = if (q.length >= 2) {
            listOf(1, 2).map { pg -> async { runCatching { get("search/multi", mapOf("query" to q, "include_adult" to "false", "page" to "$pg")) }.getOrNull() } }.awaitAll()
                .flatMap { o -> o?.optJSONArray("results")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList() }
                .filter { o -> val mt = o.optString("media_type"); (mt == "movie" || mt == "tv") &&
                    (cat == null || (cat.kinds.any { it.api == mt } && cat.ok(o))) && ofYear(o) }
                .map { it to null }
        } else if (cat != null || yr != null) {
            val kinds = cat?.kinds ?: listOf(Kind.MOVIE, Kind.TV)
            kinds.flatMap { k -> listOf(1, 2).map { pg -> async {
                // Series: only ones enough people have watched, and no talk shows, news, reality, kids' TV or daily soaps.
                val p = mutableMapOf("sort_by" to "popularity.desc", "page" to "$pg",
                    "vote_count.gte" to if (k == Kind.TV) (if (cat?.few == true) "5" else "50") else "5")
                if (k == Kind.TV) p["without_genres"] = "10763,10764,10767,10762,10766" + if (cat != null && cat.id != "anime" && cat.id != "nollywood") ",16" else ""
                cat?.q?.let { p.putAll(it) }
                if (yr != null) {
                    val (f, t) = if (k == Kind.MOVIE) "primary_release_date.gte" to "primary_release_date.lte" else "first_air_date.gte" to "first_air_date.lte"
                    p[f] = "${yr.first}-01-01"; p[t] = "${yr.last}-12-31"
                }
                runCatching { get("discover/${k.api}", p) }.getOrNull()?.optJSONArray("results")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) to k } } ?: emptyList()
            } } }.awaitAll().flatten().sortedByDescending { it.first.optDouble("popularity") }
        } else return@coroutineScope list("trending/all/week", null)
        val seen = HashSet<String>()
        raw.filter { !(kids && it.first.optBoolean("adult")) }.mapNotNull { (o, k) -> parse(o, k) }
            .filter { it.poster != null && kidsOk(it) && seen.add(it.key) }
    }

    val GENRE_NAMES = mapOf(28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        53 to "Thriller", 10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10768 to "War & Politics")

    /** Streaming platforms (TMDB's US lists); everything still plays in Movio. */
    class Platform(val id: Int, val name: String, val logo: String)
    val PLATFORMS = listOf(
        Platform(8, "Netflix", "/rK1KljqmbvO9HQa1PBFLILWah72.png"), Platform(9, "Prime Video", "/gMZdpavHmxFNnLpMHwVxfqeux2g.png"),
        Platform(337, "Disney+", "/5eZ872CghnHFLB1j8grszbrx0dx.png"), Platform(1899, "HBO Max", "/skypuy7SXuugIQeYg0IglmzoKaS.png"),
        Platform(350, "Apple TV", "/v9SnyvYt0U3KGgBH3Sbh3z4yODe.png"), Platform(15, "Hulu", "/44uAnmSqvA4yBOdbPWN8YgQHjWm.png"),
        Platform(386, "Peacock", "/a1UIdq5BrkcAxnxcUhFsNbXnxeu.png"), Platform(283, "Crunchyroll", "/uFL3c4Cq8M6WoLymlC5Y8bmGytV.png"),
    )
    /** One page of a platform's titles. sort: popular / new / top. Returns the titles and whether there's more. */
    suspend fun platform(id: Int, kind: Kind, sort: String, page: Int): Pair<List<Title>, Boolean> {
        val p = mutableMapOf("with_watch_providers" to "$id", "watch_region" to "US", "page" to "$page", "include_adult" to "false")
        when (sort) {
            "new" -> {
                p["sort_by"] = if (kind == Kind.MOVIE) "primary_release_date.desc" else "first_air_date.desc"
                p[if (kind == Kind.MOVIE) "primary_release_date.lte" else "first_air_date.lte"] = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                p["vote_count.gte"] = "10"
            }
            "top" -> { p["sort_by"] = "vote_average.desc"; p["vote_count.gte"] = "300" }
            else -> { p["sort_by"] = "popularity.desc"; p["vote_count.gte"] = "20" }
        }
        if (kind == Kind.TV) p["without_genres"] = "10763,10764,10767"
        val o = get("discover/${kind.api}", p)
        val a = o.optJSONArray("results") ?: return emptyList<Title>() to false
        val items = (0 until a.length()).mapNotNull { parse(a.getJSONObject(it), kind) }.filter { it.poster != null && kidsOk(it) }
        return items to (page < o.optInt("total_pages", 1))
    }

    class Cat(val id: String, val name: String, val kinds: List<Kind>, val q: Map<String, String>, val few: Boolean = false, val ok: (JSONObject) -> Boolean)
    private fun lang(o: JSONObject) = o.optString("original_language")
    val CATS = listOf(
        Cat("hollywood", "Hollywood", listOf(Kind.MOVIE), mapOf("with_origin_country" to "US", "with_original_language" to "en")) { lang(it) == "en" },
        Cat("nollywood", "Nollywood", listOf(Kind.MOVIE, Kind.TV), mapOf("with_origin_country" to "NG"), few = true) { o -> o.optJSONArray("origin_country")?.toString()?.contains("NG") == true },
        Cat("kdrama", "K-drama", listOf(Kind.TV), mapOf("with_original_language" to "ko", "with_genres" to "18")) { lang(it) == "ko" },
        Cat("anime", "Anime", listOf(Kind.TV, Kind.MOVIE), mapOf("with_original_language" to "ja", "with_genres" to "16")) { o -> lang(o) == "ja" && o.optJSONArray("genre_ids")?.toString()?.contains("16") == true },
        Cat("chinese", "Chinese", listOf(Kind.TV, Kind.MOVIE), mapOf("with_original_language" to "zh")) { lang(it) == "zh" || lang(it) == "cn" },
        Cat("bollywood", "Bollywood", listOf(Kind.MOVIE), mapOf("with_original_language" to "hi")) { lang(it) == "hi" },
        Cat("korean", "Korean movies", listOf(Kind.MOVIE), mapOf("with_original_language" to "ko")) { lang(it) == "ko" },
        Cat("turkish", "Turkish", listOf(Kind.TV), mapOf("with_original_language" to "tr")) { lang(it) == "tr" },
        Cat("spanish", "Spanish", listOf(Kind.TV, Kind.MOVIE), mapOf("with_original_language" to "es")) { lang(it) == "es" },
    )
    val YEARS: List<String> get() { val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR); return (0 until 12).map { "${y - it}" } + listOf("2010s", "2000s", "1990s", "Older") }
    private fun yearRange(y: String?): IntRange? = when {
        y.isNullOrEmpty() -> null
        y == "Older" -> 1900..1989
        y.endsWith("s") -> y.take(4).toInt().let { it..it + 9 }
        else -> y.toInt()..y.toInt()
    }

    suspend fun details(t: Title): Details {
        val o = get("${t.kind.api}/${t.id}", mapOf("append_to_response" to "credits,similar,recommendations,images,videos", "include_image_language" to "${I18n.lang},en,null"))
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
                // SVG logos can't be drawn by the image loader; take the first PNG in English (or language-free).
                .filter { it.optString("file_path").endsWith(".png", true) }
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
        val dept = o.optString("known_for_department")
        val cc = o.optJSONObject("combined_credits")
        fun arr(n: String) = cc?.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        // Actors: the parts they played. Directors, writers…: their work behind the camera too.
        val raw = arr("cast") + if (dept.isNotBlank() && dept != "Acting") arr("crew") else emptyList()
        val roles = HashMap<String, String>()
        val known = raw
            // chat shows, news and award-show guest spots crowd out the real work
            .filterNot { c ->
                val g = c.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
                c.optString("media_type") == "tv" && (10767 in g || 10763 in g || Regex("^(self|himself|herself)\\b", RegexOption.IGNORE_CASE).containsMatchIn(c.optString("character")))
            }
            .sortedByDescending { it.optDouble("popularity", 0.0) }
            .mapNotNull { c -> parse(c, null)?.takeIf { it.poster != null }?.also { t -> roles.putIfAbsent(t.key, c.optString("character").ifBlank { c.optString("job") }) } }
            .distinctBy { it.key }
        val born = o.optString("birthday").takeIf { it.length >= 4 }
        val died = o.optString("deathday").takeIf { it.length >= 4 }
        val age = born?.let { runCatching { java.time.Period.between(java.time.LocalDate.parse(it), java.time.LocalDate.now()).years }.getOrNull() }
        val facts = listOfNotNull(dept.ifBlank { null }?.let { t(it) },
            when { born == null -> null; died != null -> "${born.take(4)}–${died.take(4)}"; age != null -> tf("Born {1} · {2}", born.take(4), age); else -> tf("Born {1}", born.take(4)) },
            o.optString("place_of_birth").ifBlank { null }).joinToString("  ·  ")
        return PersonDetails(
            Person(id, o.optString("name"), dept, o.optString("profile_path").takeIf { it.startsWith("/") }),
            o.optString("biography"), known, roles, facts,
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
