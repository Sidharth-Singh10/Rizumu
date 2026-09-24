package com.music.rizumu

import com.music.rizumu.data.stats.TrackEntry
import com.music.rizumu.data.stats.genreAffinityFor
import com.music.rizumu.data.stats.genreKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule behind the Play tab's genre row.
 *
 * Genres are not asked of the server as a preference — it has no such call —
 * they are learned from the tracks that were played, each carrying the tag the
 * server gave it. So the two things that have to be right are that listening
 * time adds up per genre, and that two spellings of one tag do not end up as
 * two half-liked genres.
 */
class GenreAffinityTest {

    private fun track(
        id: String,
        genre: String?,
        ms: Long,
        title: String = "T",
    ) = TrackEntry(id = id, title = title, artist = "A", ms = ms, genre = genre)

    @Test
    fun `listening time adds up per genre`() {
        val affinity = genreAffinityFor(
            listOf(
                track("a", "Trip-Hop", 60_000),
                track("b", "Rock", 30_000),
                track("c", "Trip-Hop", 90_000),
            ),
        )

        assertEquals(mapOf("triphop" to 150_000L, "rock" to 30_000L), affinity)
    }

    @Test
    fun `a track whose source named no genre is not counted`() {
        val affinity = genreAffinityFor(
            listOf(
                track("a", null, 60_000),
                track("b", "", 30_000),
                track("c", "   ", 30_000),
                track("d", "Rock", 10_000),
            ),
        )

        assertEquals(mapOf("rock" to 10_000L), affinity)
    }

    @Test
    fun `a track with no time on it yet is not counted`() {
        val affinity = genreAffinityFor(
            listOf(
                track("a", "Rock", 0),
                track("b", "Trip-Hop", 5_000),
            ),
        )

        assertEquals(mapOf("triphop" to 5_000L), affinity)
    }

    @Test
    fun `case and punctuation do not split one genre in two`() {
        val affinity = genreAffinityFor(
            listOf(
                track("a", "Trip-Hop", 60_000),
                track("b", "trip hop", 30_000),
                track("c", "TRIPHOP", 10_000),
            ),
        )

        assertEquals(mapOf("triphop" to 100_000L), affinity)
    }

    @Test
    fun `nothing played is no ranking at all`() {
        assertEquals(emptyMap<String, Long>(), genreAffinityFor(emptyList()))
    }

    @Test
    fun `the key keeps letters and digits and drops the rest`() {
        assertEquals("triphop", genreKey("Trip-Hop"))
        assertEquals("triphop", genreKey("  trip hop  "))
        // Punctuation goes, words do not: "Drum & Bass" and "Drum and Bass" are
        // two keys, because telling those apart is a language question and both
        // sides of this comparison are usually the same tag to begin with.
        assertEquals("drumbass", genreKey("Drum & Bass"))
        assertEquals("", genreKey("---"))
    }

    @Test
    fun `a later play fills in a genre the entry did not have`() {
        val older = track("a", null, 60_000)
        val newer = track("a", "Trip-Hop", 30_000)

        val merged = older.copy().also { it.absorb(newer) }

        assertEquals(90_000L, merged.ms)
        assertEquals("Trip-Hop", merged.genre)
    }

    @Test
    fun `a genre already recorded is not overwritten by a later one`() {
        val existing = track("a", "Trip-Hop", 60_000)
        val other = track("a", "Rock", 30_000)

        val merged = existing.copy().also { it.absorb(other) }

        assertEquals("Trip-Hop", merged.genre)
    }

    @Test
    fun `a track that never had a genre stays without one`() {
        val merged = track("a", null, 60_000).copy().also { it.absorb(track("a", null, 30_000)) }

        assertNull(merged.genre)
    }
}
