package com.music.rizumu.data.lyrics

import com.music.rizumu.data.sources.SourceRegistry
import com.music.rizumu.data.sources.SubsonicSource

/**
 * Lyrics held by the track's own server.
 *
 * The one provider in [LyricsRepository] that is not a third party: it asks
 * the machine the audio is already coming from, matched on the server's own id
 * for the recording rather than on a name. That makes it both the cheapest and
 * the most exact of the lot when it has anything at all — and silent when it
 * does not, which is the common case: most files carry no words, and a server
 * with no lyrics for a track answers with an empty list, not an error.
 *
 * Only source-backed tracks are eligible. A YouTube track has no server, and
 * the lookup returns before any request is made.
 */
internal object ServerLyrics {

    suspend fun lyrics(videoId: String, title: String, artist: String): List<LyricLine>? {
        val (configId, songId) = SourceRegistry.parseTrackKey(videoId) ?: return null
        val source = SourceRegistry.instance(configId) as? SubsonicSource ?: return null

        // Structured first: it is keyed on the server's own id, so it cannot
        // match the wrong edit, and it carries line timings when the file has
        // them. The name-matched legacy call covers servers that predate the
        // extension, and files whose words live in their tags.
        return source.structuredLyrics(songId) ?: source.lyrics(title, artist)
    }
}
