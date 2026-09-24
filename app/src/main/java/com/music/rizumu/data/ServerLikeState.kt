package com.music.rizumu.data

import com.music.rizumu.data.model.Song
import com.music.rizumu.data.sources.SourceRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which tracks on a configured server this session knows to be starred, keyed
 * by the track's whole `src:{config}::{id}` id.
 *
 * The server remains the source of truth — this is what the app has learned
 * since it last asked, the way [LikeState] is for YouTube — but the two are
 * deliberately separate objects. A server like is a star on one server and
 * nothing else: signing out of Google must not forget it, and a thumb-down has
 * no server-side meaning that could clear it.
 *
 * Entries arrive two ways: [seedStarred] when a list the server answered
 * carries a star timestamp, and [set] when the user taps a heart. A session
 * change always wins over a later seed, so an in-flight list response cannot
 * undo a tap; see [seedStarred].
 */
object ServerLikeState {
    private val _starred = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Every track whose star state this session knows or has changed. */
    val starred: StateFlow<Map<String, Boolean>> = _starred.asStateFlow()

    /**
     * One change made by [set], with the row behind it when the caller had
     * one. Seeds do not appear here: a list that arrived already saying what
     * it says is not news to the screens it came from.
     */
    data class Change(val videoId: String, val starred: Boolean, val song: Song?)

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 32)

    /**
     * Star changes the user made, for the surfaces that list the track —
     * the Play tab's Liked songs row, the liked page, Android Auto's cache —
     * so a tap on the notification or in the player corrects them too, not
     * only the tap made from the list itself.
     */
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    /** Whether [videoId] is starred, as far as this session knows. */
    fun isStarred(videoId: String): Boolean = _starred.value[videoId] == true

    /**
     * Records a change made in the UI or the player; wins over anything seeded.
     *
     * [song] is carried when the caller has the row, so list surfaces can be
     * corrected without re-reading the server.
     */
    fun set(videoId: String, starred: Boolean, song: Song? = null) {
        _starred.update { it + (videoId to starred) }
        _changes.tryEmit(Change(videoId, starred, song))
    }

    /**
     * Marks [videoIds] starred without touching anything this session has
     * already decided: a song arriving in a list that still says "starred"
     * must not overwrite the unstar the user just tapped.
     */
    fun seedStarred(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _starred.update { current ->
            val missing = videoIds.filterNot { it in current }
            if (missing.isEmpty()) current else current + missing.associateWith { true }
        }
    }

    /**
     * Forgets every star known for one configured source.
     *
     * Called when that source is edited: track ids carry the config id, which
     * survives an edit, so without this the new account would inherit the old
     * one's stars — and a stale `false` would suppress a `true` the new
     * account's own lists are about to seed.
     */
    fun forget(configId: String) {
        val prefix = SourceRegistry.trackKey(configId, "")
        _starred.update { current ->
            if (current.keys.none { it.startsWith(prefix) }) {
                current
            } else {
                current.filterKeys { !it.startsWith(prefix) }
            }
        }
    }

    /**
     * Forgets every star.
     *
     * Not wired to YouTube's sign-out — see the class note for why — but a
     * test that shares the process needs it, and so would any future "reset
     * this source" action.
     */
    fun clear() {
        _starred.value = emptyMap()
    }
}
