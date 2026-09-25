package com.music.rizumu

import com.music.rizumu.data.ServerCopy
import com.music.rizumu.data.model.Song
import com.music.rizumu.data.sources.SourceRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [ServerCopy]'s rules: a track a server is serving is joined to that server's
 * own row, a track YouTube is serving has no such row, and a source edit takes
 * the last account's matches with it.
 *
 * This join is the whole reason a song queued from Search can offer a heart at
 * all, so the two directions matter equally — a stale row would light a heart
 * that writes a star to a server the track is no longer playing from.
 */
class ServerCopyTest {

    private fun row(configId: String, trackId: String) = Song(
        videoId = SourceRegistry.trackKey(configId, trackId),
        title = "Haareya",
        artist = "Arijit Singh",
        thumbnailUrl = null,
    )

    @Before
    @After
    fun reset() = ServerCopy.clear()

    @Test
    fun `a recorded row is handed back for its track`() {
        val row = row("a", "1")

        ServerCopy.record("youtube-id", row)

        assertEquals(row, ServerCopy.of("youtube-id"))
    }

    @Test
    fun `a track nothing has matched reads as not matched`() {
        assertNull(ServerCopy.of("youtube-id"))
    }

    /** A resolve that came back from YouTube clears what a server had claimed. */
    @Test
    fun `recording a null row forgets the track`() {
        ServerCopy.record("youtube-id", row("a", "1"))

        ServerCopy.record("youtube-id", null)

        assertNull(ServerCopy.of("youtube-id"))
    }

    @Test
    fun `forget drops one track and leaves the others alone`() {
        val kept = row("a", "2")
        ServerCopy.record("first", row("a", "1"))
        ServerCopy.record("second", kept)

        ServerCopy.forget("first")

        assertNull(ServerCopy.of("first"))
        assertEquals(kept, ServerCopy.of("second"))
    }

    @Test
    fun `forgetSource clears one server and leaves the others alone`() {
        val other = row("b", "1")
        ServerCopy.record("first", row("a", "1"))
        ServerCopy.record("second", row("a", "2"))
        ServerCopy.record("third", other)

        ServerCopy.forgetSource("a")

        assertNull(ServerCopy.of("first"))
        assertNull(ServerCopy.of("second"))
        assertEquals(other, ServerCopy.of("third"))
    }

    /** Blank ids come from an item with no `v=` parameter, and mean nothing. */
    @Test
    fun `a blank id is ignored in both directions`() {
        ServerCopy.record("", row("a", "1"))

        assertNull(ServerCopy.of(""))
        assertNull(ServerCopy.of(null))
    }

    /**
     * The map holds the track being listened to as well as the ones already
     * gone, so the cap evicts one row rather than clearing the lot — a clear
     * would take the heart off the song currently playing.
     */
    @Test
    fun `the oldest row is evicted once the cap is reached, never the newest`() {
        (0 until 70).forEach { ServerCopy.record("track-$it", row("a", it.toString())) }

        val newest = row("a", "69")
        ServerCopy.record("track-69", newest)

        assertEquals(newest, ServerCopy.of("track-69"))
        assertNull(ServerCopy.of("track-0"))
    }
}
