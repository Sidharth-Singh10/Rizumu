package com.music.rizumu

import com.music.rizumu.ui.ServerArtworkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the Play tab's genre and decade covers.
 *
 * The one that matters is the negative: a card with nothing to show is an
 * answer worth remembering, and storing it must not throw. The first version
 * of this kept nulls in a ConcurrentHashMap, which does not accept them — so
 * the first genre without a release to borrow a cover from took the whole app
 * down three seconds after it opened.
 */
class ServerArtworkCacheTest {

    @Test
    fun `a card that has not been asked about has no cover`() {
        val cache = ServerArtworkCache()

        assertNull(cache.get("srcb:cfg-1::genre::Trip-Hop"))
        assertFalse(cache.asked("srcb:cfg-1::genre::Trip-Hop"))
    }

    @Test
    fun `a resolved cover is kept and read back`() {
        val cache = ServerArtworkCache()

        cache.put("srcb:cfg-1::genre::Trip-Hop", "https://server/cover/1")

        assertEquals("https://server/cover/1", cache.get("srcb:cfg-1::genre::Trip-Hop"))
        assertTrue(cache.asked("srcb:cfg-1::genre::Trip-Hop"))
    }

    @Test
    fun `a card with no cover is remembered as asked, and reads as none`() {
        val cache = ServerArtworkCache()

        cache.put("srcb:cfg-1::decade::1960-1969", null)

        assertNull(cache.get("srcb:cfg-1::decade::1960-1969"))
        assertTrue(cache.asked("srcb:cfg-1::decade::1960-1969"))
    }

    @Test
    fun `a blank cover is no cover, not a URL`() {
        val cache = ServerArtworkCache()

        cache.put("srcb:cfg-1::genre::Rock", "  ")

        assertNull(cache.get("srcb:cfg-1::genre::Rock"))
        assertTrue(cache.asked("srcb:cfg-1::genre::Rock"))
    }

    @Test
    fun `asking twice is not an error, and the second answer wins`() {
        val cache = ServerArtworkCache()

        cache.put("srcb:cfg-1::genre::Rock", null)
        cache.put("srcb:cfg-1::genre::Rock", "https://server/cover/2")

        assertEquals("https://server/cover/2", cache.get("srcb:cfg-1::genre::Rock"))
    }
}
