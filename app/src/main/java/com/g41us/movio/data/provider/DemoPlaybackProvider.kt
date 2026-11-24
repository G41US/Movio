package com.g41us.movio.data.provider

import com.g41us.movio.data.model.StreamCandidate
import com.g41us.movio.data.model.StreamFormat

/**
 * Public sample media for verifying the Media3 pipeline only.
 * This is not a movie/TV catalogue source and is not used for catalogue items.
 */
object DemoPlaybackProvider {
    fun sample(): StreamCandidate = StreamCandidate(
        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        format = StreamFormat.MP4,
        qualityLabel = "Sample MP4",
        mimeType = "video/mp4"
    )
}
