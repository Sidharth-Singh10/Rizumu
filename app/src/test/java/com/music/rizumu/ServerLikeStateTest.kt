package com.music.rizumu

import com.music.rizumu.data.ServerLikeState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ServerLikeState]'s rules: seeds fill what this session does not know, a tap
 * always wins over a seed, and a track nobody has said anything about reads as
 * not liked.
 *
 * These are the only guarantees the heart's state has on a server track, so
 * they are pinned rather than left to the call sites.
 */
class ServerLikeStateTest {

    @Before
    fun setUp() = ServerLikeState.clear()

    @After
    fun tearDown() = ServerLikeState.clear()

    @Test
    fun `a seed marks a track liked and leaves the rest alone`() {
        ServerLikeState.seedStarred(listOf("src:a::1"))

        assertTrue(ServerLikeState.isStarred("src:a::1"))
        assertFalse(ServerLikeState.isStarred("src:a::2"))
    }

    @Test
    fun `a seed never overwrites an unstar made this session`() {
        ServerLikeState.set("src:a::1", false)

        ServerLikeState.seedStarred(listOf("src:a::1"))

        assertFalse(ServerLikeState.isStarred("src:a::1"))
    }

    @Test
    fun `a tap after a seed still wins`() {
        ServerLikeState.seedStarred(listOf("src:a::1"))

        ServerLikeState.set("src:a::1", false)

        assertFalse(ServerLikeState.isStarred("src:a::1"))
    }
}
