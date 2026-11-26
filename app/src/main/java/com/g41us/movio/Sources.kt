package com.g41us.movio

/**
 * A player site that streams any title by TMDB id. [web] sources only play in their own page
 * (shown straight away); the rest hand their stream to Movio's player. An empty [base] is
 * Movio's direct source.
 */
data class StreamSource(val name: String, val base: String, val web: Boolean = false) {
    val direct get() = base.isEmpty()

    fun url(kind: Kind, id: Int, s: Int, e: Int): String = if (kind == Kind.MOVIE) "$base/movie/$id" + query(movie = true) else when (name) {
        "Club" -> "$base/tv/$id-$s-$e"
        else -> "$base/tv/$id/$s/$e" + query(movie = false)
    }

    private fun query(movie: Boolean) = when (name) {
        "Vix" -> "?autoplay=true"
        "Braflix" -> if (movie) "" else "?autonext=1&ds_lang=en"
        else -> ""
    }
}

/** Sources that tested working, clearest first (Movio player), then page-only ones (web player). */
object Sources {
    val all = listOf(
        StreamSource("Nero", "https://vidfast.pro"),
        StreamSource("Flixify", "https://vidflix.club"),
        StreamSource("Club", "https://moviesapi.to"),
        StreamSource("Azute", "https://vidrock.to"),
        StreamSource("Cine", "https://cinesrc.st/embed"),
        StreamSource("Main", "https://player.vidzee.wtf/embed"),
        StreamSource("Vid", "https://embed.wplay.me/embed"),
        StreamSource("Peach", "https://peachify.net/embed"),
        StreamSource("Mist", "https://play.xpass.top/e"),
        StreamSource("Nest", "https://vidnest.fun"),
        StreamSource("Pass", "https://vidcore.net"),
        StreamSource("Vidmux", "https://vidlux.site/embed"),
        StreamSource("Vix", "https://vixsrc.to"),
        StreamSource("Vidsrc HD", "https://braflix.dev/embed", web = true),
        StreamSource("Vidads", "https://vidstuck.cc/embed", web = true),
        StreamSource("Braflix", "https://api.cineby.homes/embed", web = true),
        StreamSource("Vidind", "https://player.vidify.top/embed", web = true),
        StreamSource("Mistify", "https://vaplayer.ru/embed", web = true),
        StreamSource("Diablo", "https://vsembed.ru/embed", web = true),
        StreamSource("Vidsrc", "https://vidsrc2.ru/embed", web = true),
    )

    /** Tried in order when a source can't play a title (or plays the wrong one). */
    val fallbackOrder = all.indices.filter { !all[it].web && !all[it].direct }.take(8) + all.indices.filter { all[it].direct }

    fun index(name: String?) = all.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: 0
}
