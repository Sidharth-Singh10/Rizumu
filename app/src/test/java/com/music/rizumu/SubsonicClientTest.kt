package com.music.rizumu

import com.music.rizumu.data.sources.SubsonicAuthMode
import com.music.rizumu.data.subsonic.SubsonicAuth
import com.music.rizumu.data.subsonic.SubsonicClient
import com.music.rizumu.data.subsonic.SubsonicException
import com.music.rizumu.data.subsonic.SubsonicNotFound
import com.music.rizumu.data.subsonic.SubsonicUnavailable
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Subsonic protocol at the seam: what Rizumu puts on the wire, and what
 * it does with what comes back.
 *
 * A real HTTP server rather than a fake client, for the same reason the addon
 * tests use one — the interesting failures all live in the gap between what
 * this code assumes a server sends and what one actually does, and a hand-
 * rolled fake would encode the same assumptions twice.
 */
class SubsonicClientTest {

    private lateinit var server: MockWebServer

    /** What the server answers next; each test sets it before it runs. */
    private var responder: (RecordedRequest) -> MockResponse = {
        MockResponse().setResponseCode(404)
    }

    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return responder(request)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(mode: SubsonicAuthMode = SubsonicAuthMode.AUTO) =
        SubsonicClient(
            rawBaseUrl = server.url("/").toString(),
            username = "levi",
            password = "hunter2",
            authMode = mode,
            // MockWebServer listens on plain HTTP; production requires this
            // opt-in before it will send credentials to an http:// address.
            allowInsecureHttp = true,
        )

    private fun ok(payload: String = "") = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
            """"serverVersion":"0.54.5","openSubsonic":true$payload}}""",
    )

    private fun failure(code: Int, message: String) = MockResponse().setBody(
        """{"subsonic-response":{"status":"failed","error":{"code":$code,"message":"$message"}}}""",
    )

    private fun pathOf(request: RecordedRequest) = request.requestUrl?.encodedPath

    private fun query(request: RecordedRequest, key: String) =
        request.requestUrl?.queryParameter(key)

    // ── Ping and identity ─────────────────────────────────────────────────

    @Test
    fun `ping reads what the server says it is`() = runBlocking {
        responder = { ok() }
        val info = client().ping()
        assertEquals("Navidrome", info.type)
        assertEquals("0.54.5", info.serverVersion)
        assertTrue(info.openSubsonic)
        assertEquals("1.16.1", info.apiVersion)
        assertEquals("Navidrome 0.54.5 · OpenSubsonic", info.detail)
    }

    @Test
    fun `every call carries the token parameters and this app's identity`() = runBlocking {
        responder = { ok() }
        client().ping()

        val request = seen.single()
        assertEquals("/rest/ping", pathOf(request))
        assertEquals("levi", query(request, "u"))
        assertEquals("1.16.1", query(request, "v"))
        assertEquals("Rizumu", query(request, "c"))
        assertEquals("json", query(request, "f"))
        val salt = query(request, "s")
        assertNotNull("token auth must send a salt", salt)
        assertEquals(SubsonicAuth.md5Hex("hunter2$salt"), query(request, "t"))
        assertNull("token auth must not also send the password", query(request, "p"))
    }

    @Test
    fun `a server that refuses tokens is retried with the password, once`() = runBlocking {
        responder = { request ->
            if (query(request, "t") != null) {
                failure(41, "Token authentication not supported")
            } else {
                ok()
            }
        }

        val client = client()
        client.ping()
        assertEquals("the refusal and the retry are two requests", 2, seen.size)
        assertNotNull("the retry carries the password", query(seen[1], "p"))

        // And every call after it goes straight to the form that worked.
        client.ping()
        assertEquals(3, seen.size)
        assertNotNull(query(seen[2], "p"))
        assertNull(query(seen[2], "t"))
    }

    @Test
    fun `token mode never falls back, so a refusal stays a refusal`() = runBlocking {
        responder = { failure(41, "Token authentication not supported") }
        val failure = runCatching { client(SubsonicAuthMode.TOKEN).ping() }.exceptionOrNull()
        assertTrue("expected a rejection, got $failure", failure is SubsonicException)
        assertEquals("exactly one attempt", 1, seen.size)
    }

    @Test
    fun `legacy mode sends the password from the first call`() = runBlocking {
        responder = { ok() }
        client(SubsonicAuthMode.LEGACY).ping()
        assertNotNull(query(seen.single(), "p"))
        assertNull(query(seen.single(), "t"))
    }

    @Test
    fun `plain HTTP is refused until it is explicitly allowed`() = runBlocking {
        responder = { ok() }
        val refused = SubsonicClient(
            rawBaseUrl = server.url("/").toString(),
            username = "levi",
            password = "hunter2",
        )

        val failure = runCatching { refused.ping() }.exceptionOrNull()

        assertTrue("expected a rejection, got $failure", failure is SubsonicException)
        assertTrue("no credential may reach the wire", seen.isEmpty())
    }

    @Test
    fun `a direct URL is negotiated before it is handed out`() = runBlocking {
        responder = { request ->
            if (query(request, "t") != null) {
                failure(41, "Token authentication not supported")
            } else {
                ok()
            }
        }

        val client = client()
        // A cold-start stream URL with no API call behind it: the negotiation
        // is what makes it carry the form this server accepts.
        client.ensureAuthNegotiated()
        val url = client.streamUrl("stream", mapOf("id" to "300"))
        assertTrue("the URL must use the password form", url.contains("p="))
        assertFalse("the URL must not also carry a token", url.contains("t="))

        // And only once: every later URL is signed with what was learned.
        client.ensureAuthNegotiated()
        assertEquals("one negotiation, one retry", 2, seen.size)
    }

    // ── Failures ──────────────────────────────────────────────────────────

    @Test
    fun `wrong credentials are a rejection, not a retry`() = runBlocking {
        responder = { failure(40, "Wrong username or password") }
        val failure = runCatching { client().ping() }.exceptionOrNull()
        assertTrue("expected a rejection, got $failure", failure is SubsonicException)
        assertEquals("Wrong username or password", failure?.message)
        assertEquals("a wrong password is not worth asking twice", 1, seen.size)
    }

    @Test
    fun `a server error is temporary, not a rejection`() = runBlocking {
        responder = { MockResponse().setResponseCode(503) }
        val failure = runCatching { client().ping() }.exceptionOrNull()
        assertTrue("expected unavailable, got $failure", failure is SubsonicUnavailable)
    }

    @Test
    fun `an answer that is not Subsonic at all is rejected`() = runBlocking {
        responder = { MockResponse().setBody("""{"hello":"world"}""") }
        val failure = runCatching { client().ping() }.exceptionOrNull()
        assertTrue("expected a rejection, got $failure", failure is SubsonicException)
    }

    @Test
    fun `an answer that is not JSON is treated as unreachable`() = runBlocking {
        responder = { MockResponse().setBody("<html>not here</html>") }
        val failure = runCatching { client().ping() }.exceptionOrNull()
        assertTrue("expected unavailable, got $failure", failure is SubsonicUnavailable)
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search3 asks for songs only and maps every field a row needs`() = runBlocking {
        responder = {
            ok(
                ""","searchResult3":{"song":[{"id":"300","title":"Teardrop","artist":"Massive Attack",""" +
                    """"album":"Mezzanine","duration":330,"bitRate":1411,"suffix":"flac","coverArt":"al-12",""" +
                    """"albumId":"al-12","artistId":"ar-3"}]}""",
            )
        }

        val result = client().search3("teardrop", 10)
        val song = result.song.single()
        assertEquals("300", song.id)
        assertEquals("Teardrop", song.title)
        assertEquals("Massive Attack", song.artist)
        assertEquals("Mezzanine", song.album)
        assertEquals(330, song.durationSec)
        assertEquals(1411, song.bitrateKbps)
        assertEquals("flac", song.suffix)
        assertTrue(song.hasCoverArt)

        val request = seen.single()
        assertEquals("/rest/search3", pathOf(request))
        assertEquals("teardrop", query(request, "query"))
        assertEquals("10", query(request, "songCount"))
        assertEquals("0", query(request, "artistCount"))
        assertEquals("0", query(request, "albumCount"))
    }

    @Test
    fun `an empty query never reaches the server`() = runBlocking {
        val result = client().search3("   ", 10)
        assertTrue(result.song.isEmpty())
        assertTrue("a blank query is answered locally", seen.isEmpty())
    }

    @Test
    fun `a missing song is a miss, not a fault`() = runBlocking {
        responder = { failure(70, "Not found") }
        val failure = runCatching { client().search3("x", 1) }.exceptionOrNull()
        assertTrue("expected a miss, got $failure", failure is SubsonicNotFound)
    }

    // ── URLs handed outward ───────────────────────────────────────────────

    @Test
    fun `stream URLs carry the parameters and reuse one salt so they cache`() {
        val client = client()
        val first = client.streamUrl("stream", mapOf("id" to "300", "format" to "raw"))
        val second = client.streamUrl("stream", mapOf("id" to "300", "format" to "raw"))
        assertEquals("the same request must produce the same URL", first, second)
        assertTrue(first.startsWith("${client.baseUrl}/rest/stream?"))
        assertTrue(first.contains("id=300"))
        assertTrue(first.contains("format=raw"))
    }

    @Test
    fun `cover art is only a URL when the row has an id`() {
        val client = client()
        assertNull(client.coverArtUrl(""))
        val url = client.coverArtUrl("al-12", sizePx = 600)
        assertNotNull(url)
        assertTrue(url!!.contains("/rest/getCoverArt?"))
        assertTrue(url.contains("id=al-12"))
        assertTrue(url.contains("size=600"))
    }

    // ── Browsing ──────────────────────────────────────────────────────────

    @Test
    fun `getArtists flattens the alphabetical indexes`() = runBlocking {
        responder = {
            ok(
                ""","artists":{"index":[""" +
                    """{"name":"A","artist":[{"id":"ar-1","name":"Aphex Twin","albumCount":5}]},""" +
                    """{"name":"M","artist":[{"id":"ar-2","name":"Massive Attack","albumCount":5}]}]}""",
            )
        }
        val artists = client().artists()
        assertEquals(listOf("Aphex Twin", "Massive Attack"), artists.map { it.name })
        assertEquals(5, artists[0].albumCount)
        assertEquals("/rest/getArtists", pathOf(seen.single()))
    }

    @Test
    fun `getAlbum returns the release with its running order`() = runBlocking {
        responder = {
            ok(
                ""","album":{"id":"al-12","name":"Mezzanine","artist":"Massive Attack","artistId":"ar-3",""" +
                    """"coverArt":"al-12","songCount":1,"duration":330,"year":1998,""" +
                    """"song":[{"id":"300","title":"Teardrop","artist":"Massive Attack","duration":330,"suffix":"flac","bitRate":1411}]}""",
            )
        }
        val album = client().album("al-12")
        assertEquals("Mezzanine", album.name)
        assertEquals(1998, album.year)
        assertEquals("Teardrop", album.song.single().title)
        assertEquals("al-12", query(seen.single(), "id"))
    }

    @Test
    fun `getAlbumList2 asks for the type and the page`() = runBlocking {
        responder = {
            ok(""","albumList2":{"album":[{"id":"al-1","name":"One","artist":"A","songCount":3}]}""")
        }
        val albums = client().albums("newest", offset = 20, size = 30)
        assertEquals("One", albums.single().name)
        val request = seen.single()
        assertEquals("/rest/getAlbumList2", pathOf(request))
        assertEquals("newest", query(request, "type"))
        assertEquals("30", query(request, "size"))
        assertEquals("20", query(request, "offset"))
    }

    @Test
    fun `getAlbumList2 carries the year range and the genre`() = runBlocking {
        responder = {
            ok(""","albumList2":{"album":[{"id":"al-1","name":"One","artist":"A","coverArt":"al-1"}]}""")
        }
        client().albums("byYear", offset = 0, size = 1, fromYear = 1990, toYear = 1999)
        client().albums("byGenre", offset = 0, size = 1, genre = "Trip-Hop")

        val byYear = seen[0]
        assertEquals("/rest/getAlbumList2", pathOf(byYear))
        assertEquals("byYear", query(byYear, "type"))
        assertEquals("1990", query(byYear, "fromYear"))
        assertEquals("1999", query(byYear, "toYear"))
        assertNull(query(byYear, "genre"))

        val byGenre = seen[1]
        assertEquals("byGenre", query(byGenre, "type"))
        assertEquals("Trip-Hop", query(byGenre, "genre"))
        assertNull(query(byGenre, "fromYear"))
        assertNull(query(byGenre, "toYear"))
    }

    @Test
    fun `star and unstar name the song`() = runBlocking {
        responder = { ok() }
        val client = client()
        client.setSongStarred("300", starred = true)
        client.setSongStarred("300", starred = false)

        val star = seen[0]
        assertEquals("/rest/star", pathOf(star))
        assertEquals("300", query(star, "id"))

        val unstar = seen[1]
        assertEquals("/rest/unstar", pathOf(unstar))
        assertEquals("300", query(unstar, "id"))
    }

    @Test
    fun `getRandomSongs sends the size and an optional year range`() = runBlocking {
        responder = {
            ok(""","randomSongs":{"song":[{"id":"300","title":"Teardrop","duration":330}]}""")
        }
        val songs = client().randomSongs(size = 500, fromYear = 1990, toYear = 1999)
        assertEquals("Teardrop", songs.single().title)
        val request = seen.single()
        assertEquals("/rest/getRandomSongs", pathOf(request))
        assertEquals("500", query(request, "size"))
        assertEquals("1990", query(request, "fromYear"))
        assertEquals("1999", query(request, "toYear"))
    }

    @Test
    fun `getRandomSongs without a range leaves the year parameters off`() = runBlocking {
        responder = {
            ok(""","randomSongs":{"song":[{"id":"300","title":"Teardrop"}]}""")
        }
        client().randomSongs(size = 500)
        val request = seen.single()
        assertEquals("500", query(request, "size"))
        assertNull(query(request, "fromYear"))
        assertNull(query(request, "toYear"))
    }

    @Test
    fun `getSongsByGenre names the genre and the page`() = runBlocking {
        responder = {
            ok(""","songsByGenre":{"song":[{"id":"300","title":"Teardrop","genre":"Trip-Hop"}]}""")
        }
        val songs = client().songsByGenre("Trip-Hop", size = 200, offset = 40)
        assertEquals("Teardrop", songs.single().title)
        val request = seen.single()
        assertEquals("/rest/getSongsByGenre", pathOf(request))
        assertEquals("Trip-Hop", query(request, "genre"))
        assertEquals("200", query(request, "count"))
        assertEquals("40", query(request, "offset"))
    }

    @Test
    fun `getPlaylist returns its entries`() = runBlocking {
        responder = {
            ok(
                ""","playlist":{"id":"pl-1","name":"Mix","owner":"levi","songCount":1,""" +
                    """"entry":[{"id":"300","title":"Teardrop","artist":"Massive Attack","duration":330}]}""",
            )
        }
        val playlist = client().playlist("pl-1")
        assertEquals("Mix", playlist.name)
        assertEquals("Teardrop", playlist.entry.single().title)
    }

    @Test
    fun `getStarred2 splits the three kinds`() = runBlocking {
        responder = {
            ok(
                ""","starred2":{"artist":[{"id":"ar-1","name":"A"}],"album":[{"id":"al-1","name":"B"}],""" +
                    """"song":[{"id":"s-1","title":"C"}]}""",
            )
        }
        val starred = client().starred()
        assertEquals("A", starred.artist.single().name)
        assertEquals("B", starred.album.single().name)
        assertEquals("C", starred.song.single().title)
    }

    @Test
    fun `getTopSongs asks by artist name`() = runBlocking {
        responder = { ok(""","topSongs":{"song":[{"id":"s-1","title":"C"}]}""") }
        assertEquals(1, client().topSongs("Massive Attack", 20).size)
        assertEquals("Massive Attack", query(seen.single(), "artist"))
        assertEquals("20", query(seen.single(), "count"))
    }

    // ── Playlist writes ───────────────────────────────────────────────────

    @Test
    fun `createPlaylist sends the name and every song id`() = runBlocking {
        responder = { ok(""","playlist":{"id":"pl-9","name":"New"}""") }
        val id = client().createPlaylist("New", listOf("1", "2"))
        assertEquals("pl-9", id)
        val request = seen.single()
        assertEquals("/rest/createPlaylist", pathOf(request))
        assertEquals("New", query(request, "name"))
        assertEquals(listOf("1", "2"), request.requestUrl!!.queryParameterValues("songId"))
    }

    @Test
    fun `updatePlaylist sends adds and removes as repeated parameters`() = runBlocking {
        responder = { ok() }
        client().updatePlaylist(
            id = "pl-9",
            name = "Renamed",
            addSongIds = listOf("3"),
            removeSongIds = listOf("1", "2"),
        )
        val request = seen.single()
        assertEquals("/rest/updatePlaylist", pathOf(request))
        assertEquals("pl-9", query(request, "playlistId"))
        assertEquals("Renamed", query(request, "name"))
        assertEquals(listOf("3"), request.requestUrl!!.queryParameterValues("songIdToAdd"))
        assertEquals(listOf("1", "2"), request.requestUrl!!.queryParameterValues("songIdToRemove"))
    }

    @Test
    fun `deletePlaylist names the playlist`() = runBlocking {
        responder = { ok() }
        client().deletePlaylist("pl-9")
        assertEquals("/rest/deletePlaylist", pathOf(seen.single()))
        assertEquals("pl-9", query(seen.single(), "playlistId"))
    }

    // ── Play reporting and lyrics ─────────────────────────────────────────

    @Test
    fun `scrobble says whether the play is finished and when it began`() = runBlocking {
        responder = { ok() }
        val client = client()
        client.scrobble("300", submission = false)
        client.scrobble("300", submission = true, timeMs = 1_700_000_000_000)

        assertEquals("/rest/scrobble", pathOf(seen[0]))
        assertEquals("300", query(seen[0], "id"))
        assertEquals("false", query(seen[0], "submission"))
        assertNull("now playing has no timestamp", query(seen[0], "time"))
        assertEquals("true", query(seen[1], "submission"))
        assertEquals("1700000000000", query(seen[1], "time"))
    }

    @Test
    fun `getLyricsBySongId reads structured lines with their timings`() = runBlocking {
        responder = {
            ok(
                ""","lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,""" +
                    """"line":[{"value":"First","start":1000},{"value":"Second","start":2500}]}]}""",
            )
        }
        val doc = client().lyricsBySongId("300").single()
        assertEquals("eng", doc.lang)
        assertTrue(doc.synced)
        assertEquals(2500L, doc.line[1].start)
        assertEquals("300", query(seen.single(), "id"))
    }

    @Test
    fun `getLyrics reads the legacy plain-text answer`() = runBlocking {
        responder = {
            ok(""","lyrics":{"artist":"Massive Attack","title":"Teardrop","value":"Line one\nLine two"}""")
        }
        val lyrics = client().lyrics("Massive Attack", "Teardrop")
        assertEquals("Line one\nLine two", lyrics.value)
        assertEquals("Massive Attack", query(seen.single(), "artist"))
        assertEquals("Teardrop", query(seen.single(), "title"))
    }
}
