package com.g41us.movio

/** A player site that streams any title by TMDB id. Index 0 is Movio's direct source. */
data class StreamSource(val name: String, val base: String) {
    val direct get() = base.isEmpty()

    fun url(kind: Kind, id: Int, s: Int, e: Int): String = if (kind == Kind.MOVIE) movie(id) else tv(id, s, e)

    private fun movie(id: Int) = when (name) {
        "Simplify" -> "$base/movie/$id?autoplay=true&back=false"
        "Hindi" -> "$base/movie/?id=$id&s=undefined&e=undefined"
        "PrimeWire" -> "$base/movie?imdb=$id&fallback=true&server_order=PrimeVid,Voe,Dood"
        "French" -> "$base/film.php?id=$id"
        "Italian" -> "$base/movie/$id?autoplay=true&lang=it"
        "Portuguese" -> "$base/filme/$id"
        else -> "$base/movie/$id"
    }

    private fun tv(id: Int, s: Int, e: Int): String = when (name) {
        "Simplify" -> "$base/tv/$id/$s/$e?autoplay=true&back=false"
        "Hindi" -> "$base/tv/?id=$id&s=$s&e=$e&next-ep=${e + 1}"
        "PrimeWire" -> "$base/tv?tmdb=$id&season=$s&episode=$e&third_party_fallback=true&server_order=PrimeVid,Voe,Dood"
        "French" -> "$base/serie.php?id=$id&sa=$s&epi=$e"
        "Club" -> "$base/tv/$id-$s-$e"
        "Italian" -> "$base/tv/$id/$s/$e?autoplay=true&lang=it"
        "Portuguese" -> "$base/serie/$id/$s/$e"
        "Vidlink" -> "$base/tv/$id/$s/$e?autoplay=true&title=true&poster=true"
        "Braflix" -> "$base/tv/$id/$s/$e?autonext=1&ds_lang=en"
        else -> "$base/tv/$id/$s/$e"
    }
}

/** The same sources braflix offers, in braflix's order, after Movio's own. */
object Sources {
    val all = listOf(
        StreamSource("Movio", ""),
        StreamSource("Brelix", "https://vidsrc.party"),
        StreamSource("Vidsrc HD", "https://braflix.dev/embed"),
        StreamSource("Vidads", "https://vidstuck.cc/embed"),
        StreamSource("Braflix", "https://api.cineby.homes/embed"),
        StreamSource("Bolt", "https://vidbolt.xyz"),
        StreamSource("Azute", "https://vidrock.to"),
        StreamSource("Vid", "https://embed.wplay.me/embed"),
        StreamSource("Mist", "https://play.xpass.top/e"),
        StreamSource("Peach", "https://peachify.net/embed"),
        StreamSource("Nest", "https://vidnest.fun"),
        StreamSource("Pass", "https://vidcore.net"),
        StreamSource("Mistify", "https://vaplayer.ru/embed"),
        StreamSource("Simplify", "https://zxcstream.cc/player"),
        StreamSource("Cine", "https://cinesrc.st/embed"),
        StreamSource("Vidmux", "https://vidlux.site/embed"),
        StreamSource("Diablo", "https://vsembed.ru/embed"),
        StreamSource("Vidind", "https://player.vidify.top/embed"),
        StreamSource("Main", "https://player.vidzee.wtf/embed"),
        StreamSource("Vidlink", "https://vidlink.pro"),
        StreamSource("Nero", "https://vidfast.pro"),
        StreamSource("Flixify", "https://vidflix.club"),
        StreamSource("Astra", "https://vidsrc.su/embed"),
        StreamSource("Vidplay", "https://vidsrc.cc/v2/embed"),
        StreamSource("Vidsrc", "https://vidsrc2.ru/embed"),
        StreamSource("2embed", "https://www.2embed.stream/embed"),
        StreamSource("PrimeWire", "https://primesrc.me/embed"),
        StreamSource("Club", "https://moviesapi.to"),
        StreamSource("Sage", "https://111movies.com"),
        StreamSource("Aura", "https://player.autoembed.app/embed"),
        StreamSource("Hindi", "https://www.viduki.net/1"),
        StreamSource("French", "https://frembed.pics/api"),
        StreamSource("French No Ads", "https://zenix.mom/embed"),
        StreamSource("Italian", "https://vixsrc.to"),
        StreamSource("Portuguese", "https://superflixapi.beer"),
    )

    /** Tried in this order when the current source can't play a title. */
    val fallbackOrder = listOf("Movio", "Azute", "Main", "Vidsrc HD", "Nest", "Vidlink", "Nero", "Vidplay")
        .mapNotNull { n -> all.indexOfFirst { it.name == n }.takeIf { it >= 0 } }
}
