package com.music.rizumu.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * The covers resolved for server cards that have none of their own, keyed by
 * the card's browse id.
 *
 * A [ConcurrentHashMap] holds no null values, but "asked, and there is
 * nothing" is a real answer worth keeping — without it every refresh asks the
 * server again. Blank stands in for nothing, and [get] reads the two the same
 * way, so callers keep the simple question they want to ask: what cover does
 * this card have?
 *
 * It is a class rather than a map and a private function because the whole
 * rule lives in the negative — the case that crashed the Play tab the first
 * time a genre had no release to borrow a cover from — and a rule that only
 * shows up when there is nothing to show deserves a test.
 */
internal class ServerArtworkCache {

    private val covers = ConcurrentHashMap<String, String>()

    /** Whether this card has been asked about, with or without a cover. */
    fun asked(id: String): Boolean = covers.containsKey(id)

    /** The cover for [id], or null when it has none or has not been asked. */
    fun get(id: String): String? = covers[id]?.takeIf { it.isNotBlank() }

    /** Records the answer for [id]; null is "no cover". */
    fun put(id: String, cover: String?) {
        covers[id] = cover.orEmpty()
    }

    /**
     * Forgets every answer for cards whose browse id starts with [prefix].
     *
     * Used when a server's configuration changes: the ids carry only the
     * stable config id, so an edit would otherwise reuse the old server's
     * signed cover URL and suppress a fresh lookup.
     */
    fun clear(prefix: String) {
        covers.keys.removeIf { it.startsWith(prefix) }
    }
}
