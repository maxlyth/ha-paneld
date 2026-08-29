package io.github.maxlyth.hapaneld.camera

/**
 * What a stream URL asked for — `rtsp://<panel>:8554/live?res=720p&fps=15&kbps=2000` — before the
 * profile caps are applied. Configuration is URL parameters rather than settings: unrecognised
 * parameters and unparsable values are ignored, and an omitted one takes the profile default, which
 * is the cap itself.
 */
data class StreamRequest(val resolution: CameraResolution? = null, val fps: Int? = null, val kbps: Int? = null) {

    /** Clamp to the caps: a URL may ask for less than the ceiling and never for more. */
    fun bind(maxResolution: CameraResolution, maxFps: Int, maxKbps: Int): Bound {
        val capFps = maxFps.coerceIn(MIN_FPS, MAX_FPS)
        val capKbps = maxKbps.coerceAtLeast(MIN_KBPS)
        return Bound(
            resolution = CameraResolution.clamp(resolution ?: maxResolution, maxResolution),
            fps = (fps ?: capFps).coerceIn(MIN_FPS, capFps),
            kbps = (kbps ?: capKbps).coerceIn(MIN_KBPS, capKbps),
        )
    }

    data class Bound(val resolution: CameraResolution, val fps: Int, val kbps: Int) {
        val binding: StreamBinding get() = StreamBinding(fps = fps, kbps = kbps)
    }

    companion object {
        const val MIN_FPS = 1
        const val MAX_FPS = 30
        /** The registry floor for `camera_max_kbps`; below this H.264 at any size is not a picture. */
        const val MIN_KBPS = 250

        fun parse(query: Map<String, String>): StreamRequest = StreamRequest(
            resolution = CameraResolution.parse(query["res"]),
            fps = query["fps"]?.toIntOrNull(),
            kbps = query["kbps"]?.toIntOrNull(),
        )

        /** The query part of a URL (after `?`, `#` and beyond dropped); the values are plain tokens, so no percent-decoding. */
        fun parseQuery(url: String): Map<String, String> {
            val q = url.substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return emptyMap()
            return q.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=')
                if (key.isEmpty()) null else key to pair.substringAfter('=', "")
            }.toMap()
        }

        fun fromUrl(url: String): StreamRequest = parse(parseQuery(url))
    }
}
