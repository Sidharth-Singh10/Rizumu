package com.music.rizumu.data.scrobbling

import com.music.rizumu.data.DebugLog as Log
import com.music.rizumu.data.model.Song
import com.music.rizumu.data.sources.reportNowPlaying
import com.music.rizumu.data.sources.reportPlayed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    var useNowPlaying = true
    var usePrimaryArtistOnly = false

    /**
     * Whether Last.fm is configured at all.
     *
     * The manager runs for two independent reasons — Last.fm, and a source
     * that reports plays to its own server — and this is what keeps a
     * Last.fm-less listener from paying for calls that could only fail. The
     * server reports are not gated on it: they are a different service, and a
     * ListenBrainz setup behind a Navidrome has no other way to hear about a
     * play.
     */
    var useLastFm = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun onSongStart(
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(song, durationMs)
        // Always asked; [updateNowPlaying] decides for itself whether that
        // means Last.fm, the track's own server, or both.
        updateNowPlaying(song)
    }

    fun onSongResume(song: Song) {
        resumeScrobbleTimer(song)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
    }

    private fun startScrobbleTimer(
        song: Song,
        durationMs: Long? = null,
    ) {
        scrobbleJob?.cancel()
        val resolvedDurationSeconds = durationMs?.toInt()?.div(1000)
            ?: song.durationText?.let { parseDurationSeconds(it) }
            ?: return

        if (resolvedDurationSeconds <= minSongDuration) return

        val thresholdMs = (resolvedDurationSeconds * 1000L * scrobbleDelayPercent).roundToLong()
        scrobbleRemainingMillis = min(thresholdMs, scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSong(song, resolvedDurationSeconds)
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(song, resolvedDurationSeconds)
                scrobbleJob = null
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(song: Song) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                val durationSeconds = song.durationText?.let { parseDurationSeconds(it) } ?: 0
                scrobbleSong(song, durationSeconds)
                scrobbleJob = null
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun scrobbleSong(song: Song, durationSeconds: Int) {
        if (useLastFm) {
            val scrobbleArtist = song.artist.forScrobble()
            scope.launch {
                LastFM
                    .scrobble(
                        artist = scrobbleArtist,
                        track = song.title,
                        duration = durationSeconds,
                        timestamp = songStartedAt,
                        album = song.albumName,
                    ).onSuccess {
                        Log.d(TAG, "Scrobbled: ${song.title} by ${song.artist}")
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        Log.e(TAG, "Failed to scrobble: ${song.title}", throwable)
                    }
            }
        }
        // The track's own server is told too, when it has one. Separate from
        // Last.fm rather than part of the same call: they are independent
        // accounts, one may be configured and not the other, and a failure in
        // either must not stop the other from being reported.
        scope.launch { reportPlayed(song, songStartedAt) }
    }

    private fun updateNowPlaying(song: Song) {
        if (useLastFm && useNowPlaying) {
            val scrobbleArtist = song.artist.forScrobble()
            scope.launch {
                LastFM
                    .updateNowPlaying(
                        artist = scrobbleArtist,
                        track = song.title,
                        album = song.albumName,
                        duration = song.durationText?.let { parseDurationSeconds(it) },
                    ).onSuccess {
                        Log.d(TAG, "Updated now playing: ${song.title}")
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        Log.e(TAG, "Failed to update now playing: ${song.title}", throwable)
                    }
            }
        }
        scope.launch { reportNowPlaying(song) }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(song, durationMs)
            } else {
                onSongResume(song)
            }
        } else {
            onSongPause()
        }
    }

    /**
     * Parse "M:SS" or "MM:SS" duration text to total seconds.
     */
    private fun parseDurationSeconds(text: String): Int {
        val parts = text.split(":")
        if (parts.size != 2) return 0
        val minutes = parts[0].toIntOrNull() ?: return 0
        val seconds = parts[1].toIntOrNull() ?: return 0
        return minutes * 60 + seconds
    }

    private fun String.forScrobble(): String = if (usePrimaryArtistOnly) primaryArtist() else this

    companion object {
        private const val TAG = "ScrobbleManager"

        /**
         * Whether the scrobble timer is worth running at all.
         *
         * It runs when Last.fm is configured *or* when a source can report
         * plays to its own server — the two are independent, and a listener
         * with a Navidrome and no Last.fm account still wants their plays
         * counted by the server (and by whatever that server forwards them
         * to). Kept as a function rather than an inline expression so every
         * combination is a unit test instead of something only reachable
         * through the playback service.
         */
        fun shouldRun(lastfmConfigured: Boolean, serverConfigured: Boolean): Boolean =
            lastfmConfigured || serverConfigured
    }
}
