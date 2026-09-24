package com.music.rizumu

import com.music.rizumu.data.ServerLikeState
import com.music.rizumu.data.ServerStarQueue
import com.music.rizumu.data.model.Song
import com.music.rizumu.data.sources.ServerAlbum
import com.music.rizumu.data.sources.ServerAlbumListType
import com.music.rizumu.data.sources.ServerAlbumPage
import com.music.rizumu.data.sources.ServerArtist
import com.music.rizumu.data.sources.ServerArtistPage
import com.music.rizumu.data.sources.ServerGenre
import com.music.rizumu.data.sources.ServerLibrary
import com.music.rizumu.data.sources.ServerPlaylist
import com.music.rizumu.data.sources.ServerStarred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The write queue behind the heart.
 *
 * A tap flips the state optimistically and asks [ServerStarQueue] to write it.
 * A second tap while the first request is on the wire must not race it: the
 * writes for one track queue, and the write that runs reads the state the user
 * last asked for. The failure path must roll that state back — but only while
 * no newer tap has already decided the outcome.
 */
class ServerStarQueueTest {

    private val song = Song(videoId = "src:a::1", title = "T", artist = "A", thumbnailUrl = null)

    @Before
    fun setUp() = ServerLikeState.clear()

    @After
    fun tearDown() = ServerLikeState.clear()

    private open class FakeLibrary : ServerLibrary {
        override val configId: String = "a"
        override val serverName: String = "Fake"
        val writes = mutableListOf<Boolean>()

        override suspend fun setSongStarred(songId: String, starred: Boolean) {
            // Long enough that a second request queues behind this one.
            delay(20)
            writes += starred
        }

        override suspend fun artists(): List<ServerArtist> = error("unused")
        override suspend fun albums(
            type: ServerAlbumListType,
            offset: Int,
            size: Int,
            fromYear: Int?,
            toYear: Int?,
            genre: String?,
        ): List<ServerAlbum> = error("unused")

        override suspend fun allAlbums(
            type: ServerAlbumListType,
            onPage: suspend (List<ServerAlbum>) -> Unit,
        ) = error("unused")

        override suspend fun randomSongs(size: Int, fromYear: Int?, toYear: Int?): List<Song> =
            error("unused")

        override suspend fun songsByGenre(genre: String, size: Int, offset: Int): List<Song> =
            error("unused")

        override suspend fun genres(): List<ServerGenre> = error("unused")
        override suspend fun starred(): ServerStarred = error("unused")
        override suspend fun artist(id: String): ServerArtistPage? = error("unused")
        override suspend fun album(id: String): ServerAlbumPage? = error("unused")
        override suspend fun playlists(): List<ServerPlaylist> = error("unused")
        override suspend fun playlist(id: String): List<Song>? = error("unused")
        override suspend fun createPlaylist(name: String, songIds: List<String>): String? =
            error("unused")

        override suspend fun updatePlaylist(
            id: String,
            name: String?,
            addSongIds: List<String>,
            removeSongIds: List<String>,
        ) = error("unused")

        override suspend fun deletePlaylist(id: String) = error("unused")
    }

    @Test
    fun `a rapid second tap is written after the first and wins`() = runBlocking {
        val library = FakeLibrary()

        ServerLikeState.set(song.videoId, true, song)
        val first = ServerStarQueue.request(song.videoId, song, library, "1")
        ServerLikeState.set(song.videoId, false, song)
        val second = ServerStarQueue.request(song.videoId, song, library, "1")
        first.join()
        second.join()

        assertEquals(
            "the server must end where the UI is",
            false,
            library.writes.last(),
        )
    }

    @Test
    fun `a refused write rolls the optimistic state back`() = runBlocking {
        val library = object : FakeLibrary() {
            override suspend fun setSongStarred(songId: String, starred: Boolean) {
                throw IllegalStateException("refused")
            }
        }

        ServerLikeState.set(song.videoId, true, song)
        ServerStarQueue.request(song.videoId, song, library, "1").join()

        assertFalse(ServerLikeState.isStarred(song.videoId))
    }
}
