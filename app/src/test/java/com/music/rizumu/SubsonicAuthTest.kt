package com.music.rizumu

import com.music.rizumu.data.sources.SubsonicAuthMode
import com.music.rizumu.data.subsonic.SubsonicAuth
import com.music.rizumu.data.subsonic.SubsonicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic and the addresses: everything a Subsonic request carries
 * before a socket is opened.
 *
 * These are the pieces most worth pinning down in a unit test, because a
 * mistake in any of them fails as "the server refused us" on a device and
 * says nothing about which of the four parameters was wrong.
 */
class SubsonicAuthTest {

    @Test
    fun `md5Hex matches the known vector`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", SubsonicAuth.md5Hex("abc"))
    }

    @Test
    fun `token params carry the user, the salt and the derived token`() {
        val params = SubsonicAuth.tokenParams("levi", "hunter2", salt = "abcdefgh")
        assertEquals("levi", params["u"])
        assertEquals("abcdefgh", params["s"])
        assertEquals(SubsonicAuth.md5Hex("hunter2abcdefgh"), params["t"])
        assertEquals("1.16.1", params["v"])
        assertEquals("Rizumu", params["c"])
        assertEquals("json", params["f"])
        assertNull("token auth must not also send the password", params["p"])
    }

    @Test
    fun `legacy params hex-encode the password and send no token`() {
        val params = SubsonicAuth.legacyParams("levi", "abc")
        assertEquals("levi", params["u"])
        assertEquals("enc:616263", params["p"])
        assertNull(params["t"])
        assertNull(params["s"])
    }

    @Test
    fun `a random salt is fresh, long enough and alphanumeric`() {
        val salt = SubsonicAuth.randomSalt()
        assertEquals(12, salt.length)
        assertTrue("salt must stay inside the alphabet", salt.all { it.isLetterOrDigit() })
        assertNotEquals("two salts must not collide", salt, SubsonicAuth.randomSalt())
    }

    @Test
    fun `normalizeBase accepts the address with or without rest`() {
        assertEquals("https://music.example.com", SubsonicClient.normalizeBase("https://music.example.com/"))
        assertEquals("https://music.example.com", SubsonicClient.normalizeBase("https://music.example.com/rest"))
        assertEquals("https://music.example.com", SubsonicClient.normalizeBase("https://music.example.com/rest/"))
        assertEquals("https://music.example.com", SubsonicClient.normalizeBase("  https://music.example.com//  "))
        assertEquals(
            "a sub-path must survive, since a reverse proxy may serve the API under one",
            "https://example.com/music",
            SubsonicClient.normalizeBase("https://example.com/music/"),
        )
    }

    @Test
    fun `redact hides the query, which is where the credentials travel`() {
        assertEquals(
            "https://music.example.com/rest/ping.view",
            SubsonicClient.redact("https://music.example.com/rest/ping.view?u=levi&t=deadbeef&s=abcdefgh"),
        )
        assertEquals("***", SubsonicClient.redact("not a url"))
    }

    @Test
    fun `displayType turns the server's own name into something readable`() {
        assertEquals("Navidrome", SubsonicAuth.displayType("navidrome"))
        assertEquals("Gonic", SubsonicAuth.displayType("gonic"))
        assertEquals("", SubsonicAuth.displayType("   "))
    }

    @Test
    fun `auth modes name the three choices`() {
        assertEquals(
            listOf(SubsonicAuthMode.AUTO, SubsonicAuthMode.TOKEN, SubsonicAuthMode.LEGACY),
            SubsonicAuthMode.entries.toList(),
        )
    }
}
