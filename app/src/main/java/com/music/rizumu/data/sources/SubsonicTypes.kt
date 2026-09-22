package com.music.rizumu.data.sources

import com.music.rizumu.data.settings.AudioQuality

/**
 * How a Subsonic server is asked to prove who is calling.
 *
 * The Subsonic API has two authentication schemes and servers differ in which
 * they accept. [TOKEN] sends `u` plus a per-request `s`/`t` pair
 * (`t = md5(password + s)`), which is the scheme Navidrome and every
 * OpenSubsonic server implement and the one worth preferring because the
 * password itself never travels. [LEGACY] sends `u` plus `p=enc:<hex>`, which
 * is what some older servers only understand.
 *
 * [AUTO] is the default and the only mode most people should ever be on: token
 * first, and if the server answers with error 41 — "token authentication not
 * supported" — the same call is retried with the legacy parameter and the
 * client remembers for the rest of the run.
 */
enum class SubsonicAuthMode(val label: String) {
    AUTO("Automatic"),
    TOKEN("Token"),
    LEGACY("Password"),
}

/**
 * What Rizumu asks a Subsonic server to serve, remembered per server.
 *
 * The per-network [AudioQuality] ceiling is a property of the connection in
 * force, and it is what tells the app how much a stream may cost right now —
 * but it has no idea that this particular source holds a bit-exact FLAC the
 * listener would rather hear, or that this server's transcoder is worse than
 * its files. So a Subsonic source carries its own standing answer, and
 * [ORIGINAL] is the default because the people who point Rizumu at a
 * Navidrome went to the trouble of having the real files.
 *
 * [MATCH_NETWORK] hands the rung back: Lossless and High get the original,
 * Medium comes down to 256 kbps and Low to 64. Explicit rungs ([KBPS_320] and
 * friends) transcode to that number whatever the connection says.
 *
 * One rule overrides all of it: a request that *names* a bitrate ceiling —
 * [StreamRequest.Capped], which is what the Low rung and a capped download
 * produce — is never exceeded. A preference says what to serve when nobody
 * has said, not a licence to ignore someone who has.
 */
enum class SubsonicStreamQuality(val label: String, val kbps: Int?) {
    ORIGINAL("Original (raw)", null),
    MATCH_NETWORK("Match network", null),
    KBPS_320("320 kbps", 320),
    KBPS_256("256 kbps", 256),
    KBPS_192("192 kbps", 192),
    KBPS_128("128 kbps", 128),
    KBPS_96("96 kbps", 96),
    KBPS_64("64 kbps", 64),
    ;

    /** Whether the server is being asked to transcode rather than hand over the file. */
    val transcodes: Boolean get() = kbps != null
}

/**
 * The query parameters a stream request carries, from the source's standing
 * preference and the request in hand.
 *
 * Pure and standalone so the whole matrix — preference × request × network
 * rung — is a unit test rather than something only reachable through a live
 * server. `format=raw` is the one parameter that means "the file itself";
 * `maxBitRate` is the one that means "transcode to at most this many kbps".
 * Neither is ever sent beside the other.
 */
internal object SubsonicQuality {

    private val RAW = mapOf("format" to "raw")

    fun paramsFor(
        quality: SubsonicStreamQuality,
        request: StreamRequest,
        rung: AudioQuality,
    ): Map<String, String> = when {
        // A named ceiling wins outright. The Low rung and DownloadQuality's
        // Standard both arrive here, and neither is a number to second-guess.
        request is StreamRequest.Capped -> cap(request.maxKbps)

        // "Bit-exact, whatever it costs" is a request in its own right — it is
        // what the Lossless rung and a lossless download send — and a standing
        // preference is not a reason to hand back a transcode of the file
        // someone asked to keep.
        request is StreamRequest.Lossless -> RAW

        quality == SubsonicStreamQuality.ORIGINAL -> RAW

        quality == SubsonicStreamQuality.MATCH_NETWORK -> when (rung) {
            AudioQuality.LOSSLESS, AudioQuality.HIGH -> RAW
            AudioQuality.MEDIUM -> cap(MEDIUM_KBPS)
            AudioQuality.LOW -> cap(LOW_KBPS)
        }

        else -> quality.kbps?.let(::cap) ?: RAW
    }

    /** True when the parameters being sent ask the server not to transcode. */
    fun isRaw(params: Map<String, String>): Boolean = params["format"] == "raw"

    private fun cap(kbps: Int) = mapOf(MAX_BITRATE to kbps.toString())

    const val MAX_BITRATE = "maxBitRate"

    /** What [SubsonicStreamQuality.MATCH_NETWORK] asks for on the Medium rung. */
    const val MEDIUM_KBPS = 256

    /** What it asks for on the Low rung, matching [AudioQuality.LOW]. */
    const val LOW_KBPS = 64
}
