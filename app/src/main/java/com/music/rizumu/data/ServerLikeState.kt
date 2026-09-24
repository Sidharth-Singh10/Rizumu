package com.music.rizumu.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Whether [videoId] is starred, as far as this session knows. */
    fun isStarred(videoId: String): Boolean = _starred.value[videoId] == true

    /** Records a change made in the UI or the player; wins over anything seeded. */
    fun set(videoId: String, starred: Boolean) {
        _starred.update { it + (videoId to starred) }
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
