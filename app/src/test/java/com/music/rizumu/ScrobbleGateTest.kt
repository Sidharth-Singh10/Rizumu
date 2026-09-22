package com.music.rizumu

import com.music.rizumu.data.scrobbling.ScrobbleManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the scrobble timer is worth running.
 *
 * Two independent backends feed the same timer, and either one being
 * available is reason enough for it to exist: Last.fm for the listener who
 * configured it, and a server that can report plays for the listener who runs
 * their own library — and, through it, whatever the server forwards to. Only
 * when neither is present is there nothing to report, and the timer is
 * destroyed rather than left running.
 */
class ScrobbleGateTest {

    @Test
    fun `lastfm alone is enough`() {
        assertTrue(ScrobbleManager.shouldRun(lastfmConfigured = true, serverConfigured = false))
    }

    @Test
    fun `a server alone is enough`() {
        assertTrue(ScrobbleManager.shouldRun(lastfmConfigured = false, serverConfigured = true))
    }

    @Test
    fun `both still run one timer`() {
        assertTrue(ScrobbleManager.shouldRun(lastfmConfigured = true, serverConfigured = true))
    }

    @Test
    fun `neither means nothing to report`() {
        assertFalse(ScrobbleManager.shouldRun(lastfmConfigured = false, serverConfigured = false))
    }
}
