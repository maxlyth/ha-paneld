package io.github.maxlyth.hapaneld.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The URL is the configuration surface; the caps are ceilings it can never exceed. */
class StreamRequestTest {

    @Test fun omittedParametersTakeTheProfileCaps() {
        val bound = StreamRequest().bind(CameraResolution.P720, maxFps = 15, maxKbps = 2_000)
        assertEquals(StreamRequest.Bound(CameraResolution.P720, fps = 15, kbps = 2_000), bound)
    }

    @Test fun aUrlMayAskForLessAndNeverForMore() {
        val less = StreamRequest.fromUrl("rtsp://panel:8554/live?res=480p&fps=5&kbps=500")
            .bind(CameraResolution.P720, maxFps = 15, maxKbps = 2_000)
        assertEquals(StreamRequest.Bound(CameraResolution.P480, fps = 5, kbps = 500), less)
        val more = StreamRequest.fromUrl("rtsp://panel:8554/live?res=1080p&fps=30&kbps=8000")
            .bind(CameraResolution.P720, maxFps = 15, maxKbps = 2_000)
        assertEquals("clamped to every cap", StreamRequest.Bound(CameraResolution.P720, fps = 15, kbps = 2_000), more)
    }

    @Test fun unrecognisedParametersAndUnparsableValuesAreIgnored() {
        val request = StreamRequest.fromUrl("rtsp://panel/live?codec=h265&res=4k&fps=fast&kbps=&audio=1#frag")
        assertEquals(StreamRequest(), request)
        assertNull(request.resolution)
        assertNull(request.fps)
        assertNull(request.kbps)
    }

    @Test fun theFloorsHoldEvenWhenTheUrlAsksForNothingUsable() {
        val bound = StreamRequest(fps = 0, kbps = 1).bind(CameraResolution.P480, maxFps = 15, maxKbps = 2_000)
        assertEquals(StreamRequest.MIN_FPS, bound.fps)
        assertEquals(StreamRequest.MIN_KBPS, bound.kbps)
        assertEquals(StreamBinding(fps = 1, kbps = 250), bound.binding)
    }

    @Test fun theQueryParserKeepsOnlyKeyValuePairs() {
        assertEquals(mapOf("res" to "720p", "fps" to "15"), StreamRequest.parseQuery("rtsp://p:8554/live?res=720p&fps=15"))
        assertEquals(emptyMap<String, String>(), StreamRequest.parseQuery("rtsp://p:8554/live"))
        assertEquals(mapOf("a" to ""), StreamRequest.parseQuery("/live?a&=b"))
    }
}
