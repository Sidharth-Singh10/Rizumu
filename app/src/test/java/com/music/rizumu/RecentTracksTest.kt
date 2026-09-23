package com.music.rizumu

import com.music.rizumu.data.sources.SourceRegistry
import com.music.rizumu.data.stats.TrackEntry
import com.music.rizumu.data.stats.recentTracksFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule behind the Play tab's recently-played shelf.
 *
 * The shelf reads this device's own listening history, which is keyed by track
 * id — so the one thing that has to be right is which entries belong to the
 * server in hand: its own `src:{configId}::` keys and nothing else. A YouTube
 * play, or a play against a different server, must never surface there.
 */
class RecentTracksTest {

    private fun entry(id: String, last: Long) =
        TrackEntry(id = id, title = id, artist = "A", last = last)

    @Test
    fun `only the source's own tracks are offered, newest first`() {
        val entries = listOf(
            entry(SourceRegistry.trackKey("cfg-1", "a"), 10),
            entry(SourceRegistry.trackKey("cfg-2", "b"), 30),
            entry(SourceRegistry.trackKey("cfg-1", "c"), 20),
            entry("dQw4w9WgXcQ", 40),
        )

        val recent = recentTracksFor(entries, "cfg-1", limit = 10)

        assertEquals(listOf("c", "a"), recent.map { SourceRegistry.parseTrackKey(it.id)?.second })
    }

    @Test
    fun `the limit keeps the newest listens`() {
        val entries = (1..10).map { entry(SourceRegistry.trackKey("cfg-1", "s$it"), it.toLong()) }

        val recent = recentTracksFor(entries, "cfg-1", limit = 3)

        assertEquals(
            listOf("s10", "s9", "s8"),
            recent.map { SourceRegistry.parseTrackKey(it.id)?.second },
        )
    }

    @Test
    fun `a fresh install has no shelf to show`() {
        val entries = listOf(
            entry("dQw4w9WgXcQ", 5),
            entry(SourceRegistry.trackKey("cfg-2", "b"), 9),
        )

        assertEquals(emptyList<TrackEntry>(), recentTracksFor(entries, "cfg-1", limit = 10))
    }
}
