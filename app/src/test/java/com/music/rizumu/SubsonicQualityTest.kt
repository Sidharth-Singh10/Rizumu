package com.music.rizumu

import com.music.rizumu.data.settings.AudioQuality
import com.music.rizumu.data.sources.StreamRequest
import com.music.rizumu.data.sources.SubsonicQuality
import com.music.rizumu.data.sources.SubsonicStreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole quality matrix, preference × request × rung, as arithmetic.
 *
 * This is the decision the rest of the Subsonic source exists to carry out, and
 * it is the one thing about a server that cannot be seen from the outside: a
 * wrong answer plays a track at the wrong bitrate, which is exactly the kind of
 * bug that sounds like a server setting rather than an app one.
 */
class SubsonicQualityTest {

    private fun params(
        quality: SubsonicStreamQuality,
        request: StreamRequest,
        rung: AudioQuality = AudioQuality.LOSSLESS,
    ) = SubsonicQuality.paramsFor(quality, request, rung)

    @Test
    fun `original asks for the raw file on every rung`() {
        for (rung in AudioQuality.entries) {
            val p = params(SubsonicStreamQuality.ORIGINAL, StreamRequest.Best, rung)
            assertTrue("rung $rung should ask for raw, got $p", SubsonicQuality.isRaw(p))
            assertFalse("raw must not carry a bitrate cap", p.containsKey(SubsonicQuality.MAX_BITRATE))
        }
    }

    @Test
    fun `a named cap is never exceeded, whatever the preference says`() {
        for (quality in SubsonicStreamQuality.entries) {
            val p = params(quality, StreamRequest.Capped(64), AudioQuality.LOSSLESS)
            assertEquals("64", p[SubsonicQuality.MAX_BITRATE])
            assertFalse("a cap and raw are mutually exclusive", p.containsKey("format"))
        }
    }

    @Test
    fun `match network maps each rung`() {
        val quality = SubsonicStreamQuality.MATCH_NETWORK
        assertTrue(SubsonicQuality.isRaw(params(quality, StreamRequest.Best, AudioQuality.LOSSLESS)))
        assertTrue(SubsonicQuality.isRaw(params(quality, StreamRequest.Best, AudioQuality.HIGH)))
        assertEquals("256", params(quality, StreamRequest.Best, AudioQuality.MEDIUM)[SubsonicQuality.MAX_BITRATE])
        assertEquals("64", params(quality, StreamRequest.Best, AudioQuality.LOW)[SubsonicQuality.MAX_BITRATE])
    }

    @Test
    fun `an explicit cap transcribes to that number`() {
        assertEquals("320", params(SubsonicStreamQuality.KBPS_320, StreamRequest.Best)[SubsonicQuality.MAX_BITRATE])
        assertEquals("128", params(SubsonicStreamQuality.KBPS_128, StreamRequest.Best)[SubsonicQuality.MAX_BITRATE])
        assertEquals("96", params(SubsonicStreamQuality.KBPS_96, StreamRequest.Best)[SubsonicQuality.MAX_BITRATE])
    }

    @Test
    fun `a lossless request is raw even under a capped preference`() {
        // The request says "bit-exact, whatever it costs" — which is what a
        // lossless download sends — and a standing preference is not a reason
        // to hand back a transcode of the file someone asked to keep.
        val p = params(SubsonicStreamQuality.KBPS_128, StreamRequest.Lossless)
        assertTrue(SubsonicQuality.isRaw(p))
    }

    @Test
    fun `caps are stated in kbps and labelled for the list`() {
        assertEquals(320, SubsonicStreamQuality.KBPS_320.kbps)
        assertEquals(64, SubsonicStreamQuality.KBPS_64.kbps)
        assertTrue(SubsonicStreamQuality.KBPS_128.transcodes)
        assertFalse(SubsonicStreamQuality.ORIGINAL.transcodes)
        assertFalse(SubsonicStreamQuality.MATCH_NETWORK.transcodes)
    }
}
