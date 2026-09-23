package com.music.rizumu

import com.music.rizumu.data.sources.ServerAlbum
import com.music.rizumu.data.sources.ServerArtist
import com.music.rizumu.data.sources.playedArtists
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule behind the Library's "Your artists" row.
 *
 * The protocol has no "artists you listen to" call, so the row is derived from
 * the server's per-user album lists — most played first, then recently played —
 * and the two things that have to be right are the order an artist inherits
 * from their best-placed album, and that nobody appears twice because they were
 * played in two months or on two albums.
 */
class PlayedArtistsTest {

    private fun album(id: String, artist: String, artistId: String?) = ServerAlbum(
        id = id,
        name = "Album $id",
        artist = artist,
        year = null,
        songCount = 1,
        thumbnailUrl = "art-$id",
        artistId = artistId,
    )

    private fun artist(id: String, name: String, albums: Int, art: String? = null) =
        ServerArtist(id = id, name = name, albumCount = albums, thumbnailUrl = art)

    @Test
    fun `artists follow their best-placed album, most played before recent`() {
        val artists = playedArtists(
            frequent = listOf(album("a1", "Massive Attack", "ar-1"), album("a2", "Portishead", "ar-2")),
            recent = listOf(album("a3", "Björk", "ar-3")),
            known = emptyList(),
            limit = 10,
        )

        assertEquals(listOf("Massive Attack", "Portishead", "Björk"), artists.map { it.name })
    }

    @Test
    fun `an artist played on two albums, or in two lists, appears once`() {
        val artists = playedArtists(
            frequent = listOf(album("a1", "Massive Attack", "ar-1"), album("a2", "Massive Attack", "ar-1")),
            recent = listOf(album("a3", "Massive Attack", "ar-1"), album("a4", "Portishead", "ar-2")),
            known = emptyList(),
            limit = 10,
        )

        assertEquals(listOf("Massive Attack", "Portishead"), artists.map { it.name })
    }

    @Test
    fun `the known artist supplies the picture and the album count`() {
        val artists = playedArtists(
            frequent = listOf(album("a1", "Portishead", "ar-2")),
            recent = emptyList(),
            known = listOf(artist("ar-2", "Portishead", albums = 3, art = "artist-art")),
            limit = 10,
        )

        assertEquals("artist-art", artists.single().thumbnailUrl)
        assertEquals(3, artists.single().albumCount)
    }

    @Test
    fun `an artist the index has no picture for takes the album's cover`() {
        val artists = playedArtists(
            frequent = listOf(album("a1", "Portishead", "ar-2")),
            recent = emptyList(),
            known = listOf(artist("ar-2", "Portishead", albums = 3)),
            limit = 10,
        )

        assertEquals("art-a1", artists.single().thumbnailUrl)
    }

    @Test
    fun `an artist the server does not index still makes a card`() {
        val artists = playedArtists(
            frequent = listOf(album("a1", "Somebody", "ar-9")),
            recent = emptyList(),
            known = listOf(artist("ar-1", "Someone Else", albums = 1)),
            limit = 10,
        )

        assertEquals("Somebody", artists.single().name)
        assertEquals("art-a1", artists.single().thumbnailUrl)
        assertEquals(0, artists.single().albumCount)
    }

    @Test
    fun `an album with no artist id is skipped, not guessed at`() {
        val artists = playedArtists(
            frequent = listOf(album("a1", "Unknown", null), album("a2", "Portishead", "ar-2")),
            recent = emptyList(),
            known = emptyList(),
            limit = 10,
        )

        assertEquals(listOf("Portishead"), artists.map { it.name })
    }

    @Test
    fun `the limit stops the row at the most played artists`() {
        val frequent = (1..10).map { album("a$it", "Artist $it", "ar-$it") }

        val artists = playedArtists(frequent, emptyList(), emptyList(), limit = 3)

        assertEquals(listOf("Artist 1", "Artist 2", "Artist 3"), artists.map { it.name })
    }

    @Test
    fun `nothing played is no row at all`() {
        assertEquals(emptyList<ServerArtist>(), playedArtists(emptyList(), emptyList(), emptyList(), limit = 10))
        assertEquals(emptyList<ServerArtist>(), playedArtists(emptyList(), emptyList(), emptyList(), limit = 0))
    }

    @Test
    fun `a blank artist id is skipped too, not used as one`() {
        val artists = playedArtists(
            frequent = listOf(album("a1", "Somebody", "  ")),
            recent = emptyList(),
            known = emptyList(),
            limit = 10,
        )

        // Blank is not an id. The mapping already turns it into null on the way
        // in, and this holds the line for any caller that passes one directly.
        assertEquals(emptyList<ServerArtist>(), artists)
    }
}
