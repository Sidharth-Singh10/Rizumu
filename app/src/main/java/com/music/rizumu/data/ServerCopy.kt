package com.music.rizumu.data

import com.music.rizumu.data.model.Song
import com.music.rizumu.data.sources.SourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which playing tracks are being served from a server, and by which row.
 *
 * A track queued from YouTube keeps its bare video id — that is the identity
 * the listener picked, and it is what the queue, the history and the "playing
 * from" label all mean by this song. But when a configured server is ranked
 * above YouTube, [SourceResolver] may be serving that same recording from the
 * server instead, and that server's own row is a thing that can be starred.
 *
 * Without this the app knew both facts and could join neither: the player held
 * a YouTube id, so it offered no heart at all, while the audio coming out of
 * the speaker was a file the server could have starred. This is the join.
 *
 * ### What it is not
 *
 * It does not change what the track *is*. The queue entry, the scrobbles, the
 * lyrics lookup and the revert-to-original path all keep working off the
 * YouTube id exactly as before; this only answers "and what could I like about
 * it". Anything that needs the server identity for playback should keep
 * reading [SourceResolver][com.music.rizumu.data.sources.SourceResolver].
 *
 * ### Lifetime
 *
 * One session, in memory, like [StreamChoice][com.music.rizumu.playback.StreamChoice]
 * and [NerdStats] beside it. A track served straight from the disk cache in a
 * later session is never matched again, so nothing here is written down: the
 * record is made when a server actually answers, and dropped when the same
 * track is resolved from YouTube or reverted by hand.
 */
object ServerCopy {

    private val _songs = MutableStateFlow<Map<String, Song>>(emptyMap())

    /**
     * The server row behind each playing track, keyed by the id the app plays
     * it under. Read by the player to decide what a heart would write to.
     */
    val songs: StateFlow<Map<String, Song>> = _songs.asStateFlow()

    /** The server row serving [videoId], or null when YouTube is. */
    fun of(videoId: String?): Song? = videoId?.let { _songs.value[it] }

    /**
     * Records the row serving [videoId], or forgets it when [song] is null.
     *
     * Both directions in one call because that is how the callers have the
     * answer: a resolve either came back from a server or it did not, and a
     * stale record is worse than none — it would light a heart that writes a
     * star to a server the track is no longer playing from.
     */
    fun record(videoId: String?, song: Song?) {
        if (videoId.isNullOrBlank()) return
        if (song == null) {
            forget(videoId)
            return
        }
        _songs.update { current ->
            // Capped like [NerdStats]' maps beside it, but evicting one entry
            // rather than clearing the lot: this map holds the track being
            // listened to as well as the ones already gone, and a clear would
            // take the heart off the song currently playing. Oldest first —
            // the map is built by `+`, so its iteration order is insertion
            // order, and anything re-resolved moves to the end.
            val room = if (current.size >= MAX_REMEMBERED && videoId !in current) {
                current - current.keys.first()
            } else {
                current
            }
            room + (videoId to song)
        }
    }

    /**
     * Forgets [videoId]'s server row.
     *
     * Called when the track is sent back to YouTube's own upload by hand: the
     * listener has said the catalogue match is wrong for this song, and a
     * heart pointing at it would offer to star the very row they rejected.
     */
    fun forget(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        _songs.update { current ->
            if (videoId !in current) current else current - videoId
        }
    }

    /** Forgets every row known for one configured source — see [ServerLikeState.forget]. */
    fun forgetSource(configId: String) {
        val prefix = SourceRegistry.trackKey(configId, "")
        _songs.update { current ->
            if (current.values.none { it.videoId.startsWith(prefix) }) {
                current
            } else {
                current.filterValues { !it.videoId.startsWith(prefix) }
            }
        }
    }

    /** Forgets every row. For tests that share the process, and a future "reset this source". */
    fun clear() {
        _songs.value = emptyMap()
    }

    /**
     * How many rows are kept before the oldest is dropped. The same size
     * [NerdStats] holds, and far more than a queue's worth — see [record].
     */
    private const val MAX_REMEMBERED = 64
}
