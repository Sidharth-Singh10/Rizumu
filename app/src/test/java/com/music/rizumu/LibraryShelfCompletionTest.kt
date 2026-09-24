package com.music.rizumu

import com.music.rizumu.data.YtMusicRepository
import com.music.rizumu.data.innertube.InnertubeParser
import com.music.rizumu.data.model.ShelfItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YtMusicRepository.completeLibraryShelf]'s walk: it goes past the bounded
 * library load, reports each page as it arrives, stops on a repeated token or
 * an unreadable page, and never hands the same card over twice.
 */
class LibraryShelfCompletionTest {

    private fun card(id: String) = ShelfItem("Album $id", "Artist", null, null, "browse:$id")

    @Test
    fun `it walks past the bounded library load`() = runBlocking {
        val delivered = mutableListOf<List<ShelfItem>>()
        var calls = 0

        YtMusicRepository.completeLibraryShelf(
            browseId = "FEmusic_liked_albums",
            load = { token ->
                calls++
                // Two pages more than `itemsPaged`'s ten: the completion exists
                // to reach what the bounded load leaves behind.
                InnertubeParser.LibraryItemPage(listOf(card("$calls")), "token-$calls".takeIf { calls < 12 })
            },
        ) { page -> delivered += page }

        assertEquals(12, calls)
        assertEquals((1..12).map { listOf(card("$it")) }, delivered)
    }

    @Test
    fun `a repeated token ends the walk`() = runBlocking {
        var calls = 0
        val delivered = mutableListOf<List<ShelfItem>>()

        YtMusicRepository.completeLibraryShelf(
            browseId = "feed",
            load = {
                calls++
                InnertubeParser.LibraryItemPage(listOf(card("$calls")), "same-token")
            },
        ) { page -> delivered += page }

        // The first page sets the token, the second sees it repeat: a feed
        // pointing back at itself must not be walked forever.
        assertEquals(2, calls)
        assertEquals(2, delivered.size)
    }

    @Test
    fun `the same card is only handed over once`() = runBlocking {
        var calls = 0
        val delivered = mutableListOf<ShelfItem>()

        YtMusicRepository.completeLibraryShelf(
            browseId = "feed",
            load = {
                calls++
                InnertubeParser.LibraryItemPage(listOf(card("same")), "token-$calls".takeIf { calls < 2 })
            },
        ) { page -> delivered += page }

        assertEquals(1, delivered.size)
        assertEquals(2, calls)
    }

    @Test
    fun `a page that could not be read ends the walk`() = runBlocking {
        var calls = 0
        val delivered = mutableListOf<List<ShelfItem>>()

        YtMusicRepository.completeLibraryShelf(
            browseId = "feed",
            load = {
                calls++
                null
            },
        ) { page -> delivered += page }

        assertEquals(1, calls)
        assertTrue(delivered.isEmpty())
    }
}
