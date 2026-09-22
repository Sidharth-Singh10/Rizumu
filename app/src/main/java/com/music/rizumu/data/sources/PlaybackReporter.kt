package com.music.rizumu.data.sources

import com.music.rizumu.data.TrackLog
import com.music.rizumu.data.model.Song

/**
 * A source that can report plays back to its own server.
 *
 * Separate from [MusicSource] for the same reason [ServerLibrary] is: a
 * catalogue behind somebody else's API has no server of yours to report to.
 * A source implements this when the place a play happened is also a place that
 * keeps a play count.
 */
interface PlaybackReporter {

    /**
     * Tells the server a track is playing ([submission] false) or has been
     * played ([submission] true). [timeMs] is when the play began, in
     * milliseconds since the epoch; ignored for a now-playing.
     */
    suspend fun reportPlayback(songId: String, submission: Boolean, timeMs: Long? = null)
}

/**
 * Tells the server that owns [song] that it has started.
 *
 * A no-op for every track that came from somewhere else, which is most of
 * them: the track key says whether there is a server at all, and a YouTube or
 * local track has nobody to tell. Failures are logged and swallowed — a
 * scrobble that did not land is not a playback problem, and nothing on screen
 * should hear about it.
 */
suspend fun reportNowPlaying(song: Song) = reportToOwner(song, submission = false, timeMs = null)

/** As [reportNowPlaying], for a play that has passed the scrobble threshold. */
suspend fun reportPlayed(song: Song, startedAtSeconds: Long) =
    reportToOwner(song, submission = true, timeMs = startedAtSeconds * 1000)

private suspend fun reportToOwner(song: Song, submission: Boolean, timeMs: Long?) {
    val (configId, songId) = SourceRegistry.parseTrackKey(song.videoId) ?: return
    val reporter = SourceRegistry.instance(configId) as? PlaybackReporter ?: return
    runCatching { reporter.reportPlayback(songId, submission, timeMs) }
        .onFailure { TrackLog.w(TAG, "server playback report failed: ${it.message}") }
}

private const val TAG = "Rizumu"
