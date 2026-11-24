package com.g41us.movio.data.provider

import com.g41us.movio.data.model.StreamCandidate
import com.g41us.movio.data.model.StreamFormat
import com.g41us.movio.data.model.SubtitleTrack
import com.g41us.movio.data.model.Episode
import com.g41us.movio.data.model.MediaItem
import com.g41us.movio.data.model.MediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Initial movie/series source set derived from the saved provider configuration.
 * Vimeus and OneStream are embed/browser flows, not direct JSON stream APIs,
 * and are intentionally not advertised as playable sources in this registry.
 */
object MovioStreamProviders {
    fun createRegistry(): StreamProviderRegistry = StreamProviderRegistry(
        listOf(
            JsonApiStreamProvider(
                JsonStreamProviderConfig(
                    id = "vixsrc",
                    name = "VixSrc",
                    enabled = true,
                    movieUrl = "https://vixsrc.to/api/movie/{tmdb_id}",
                    tvUrl = "https://vixsrc.to/api/tv/{tmdb_id}/{season}/{episode}",
                    referer = "https://vixsrc.to/",
                    displayOrder = 2,
                )
            ),
            XPassStreamProvider(),
            JsonApiStreamProvider(
                JsonStreamProviderConfig(
                    id = "vidnest_videasy",
                    name = "VidNest Easy",
                    enabled = true,
                    movieUrl = "https://new.vidnest.fun/videasy/movie/{tmdb_id}",
                    tvUrl = "https://new.vidnest.fun/videasy/tv/{tmdb_id}/{season}/{episode}",
                    referer = "https://vidnest.fun/",
                    displayOrder = 3,
                )
            ),
            JsonApiStreamProvider(
                JsonStreamProviderConfig(
                    id = "vidnest_moviesapi",
                    name = "VidNest Movies",
                    enabled = true,
                    movieUrl = "https://new.vidnest.fun/moviesapi/movie/{tmdb_id}",
                    tvUrl = "https://new.vidnest.fun/moviesapi/tv/{tmdb_id}/{season}/{episode}",
                    referer = "https://vidnest.fun/",
                    displayOrder = 4,
                )
            ),
            JsonApiStreamProvider(
                JsonStreamProviderConfig(
                    id = "vidrock",
                    name = "VidRock",
                    enabled = true,
                    movieUrl = "https://vidrock.ru/api/movie/{tmdb_id}",
                    tvUrl = "https://vidrock.ru/api/tv/{tmdb_id}/{season}/{episode}",
                    referer = "https://vidrock.ru/",
                    displayOrder = 7,
                )
            ),
        )
    )
}
