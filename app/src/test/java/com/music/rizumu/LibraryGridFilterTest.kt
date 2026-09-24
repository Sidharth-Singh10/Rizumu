package com.music.rizumu

import com.music.rizumu.data.model.ShelfItem
import com.music.rizumu.ui.screens.matching
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The filter behind a "Show all" page's search field: what it matches, what it
 * leaves alone, and that it does not reorder what survives.
 */
class LibraryGridFilterTest {

    private fun card(title: String, subtitle: String = "") = ShelfItem(
        title = title,
        subtitle = subtitle,
        thumbnailUrl = null,
        videoId = null,
        browseId = "browse:$title",
    )

    @Test
    fun `an empty query keeps every card, in order`() {
        val cards = listOf(card("Mezzanine", "Massive Attack"), card("Blue Lines", "Massive Attack"))

        assertEquals(cards, cards.matching(""))
        assertEquals(cards, cards.matching("   "))
    }

    @Test
    fun `a query matches a title whatever its case`() {
        val cards = listOf(card("Mezzanine"), card("Blue Lines"))

        assertEquals(listOf(cards[0]), cards.matching("mezz"))
    }

    @Test
    fun `a query matches the subtitle, which is the artist on an album card`() {
        val cards = listOf(
            card("Mezzanine", "Massive Attack"),
            card("Blue Lines", "Massive Attack"),
            card("Kid A", "Radiohead"),
        )

        assertEquals(listOf(cards[0], cards[1]), cards.matching("massive"))
    }

    @Test
    fun `a query matching neither leaves nothing`() {
        assertEquals(
            emptyList<ShelfItem>(),
            listOf(card("Mezzanine", "Massive Attack")).matching("radiohead"),
        )
    }

    @Test
    fun `matching preserves the order it was given`() {
        val cards = listOf(card("Ziggy", "Bowie"), card("Aladdin", "Bowie"), card("Midnight", "Bowie"))

        assertEquals(cards, cards.matching("bowie"))
    }
}
