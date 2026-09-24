package com.music.rizumu

import com.music.rizumu.data.stats.ListeningStats
import com.music.rizumu.data.stats.ReplayPeriod
import com.music.rizumu.data.stats.StoredBucket
import com.music.rizumu.data.stats.TrackEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The Replay reconstruction path.
 *
 * A track played from the Replay charts is rebuilt into a [com.music.rizumu.data.model.Song]
 * from its stored totals, and that row is what the next play records. If the
 * rebuild drops a field — the genre being the one the Play tab's ranking is
 * ordered by — the loss is invisible until the ranking stops learning.
 */
class ReplayGenreTest {

    @Test
    fun `a track rebuilt from the replay keeps its server metadata`() {
        val bucket = StoredBucket(
            month = "2026-09",
            tracks = listOf(
                TrackEntry(
                    id = "src:server::1",
                    title = "Teardrop",
                    artist = "Massive Attack",
                    album = "Mezzanine",
                    albumId = "srcb:server::album::al-12",
                    artistId = "srcb:server::artist::ar-3",
                    ms = 60_000,
                    plays = 1,
                    genre = "Trip-Hop",
                ),
            ),
        )

        val merged = ListeningStats.MergedBucket()
        merged.add(bucket)
        val summary = merged.toSummary(ReplayPeriod.ALL_TIME, LocalDate.of(2026, 9, 24))

        val song = summary.songs.single().song
        assertEquals("Trip-Hop", song.genre)
        assertEquals("Mezzanine", song.albumName)
        assertEquals("srcb:server::album::al-12", song.albumId)
        assertEquals("srcb:server::artist::ar-3", song.artistId)
    }
}
