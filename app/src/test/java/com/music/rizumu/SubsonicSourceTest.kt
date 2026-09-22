package com.music.rizumu

import com.music.rizumu.data.settings.AppSettings
import com.music.rizumu.data.settings.AudioQuality
import com.music.rizumu.data.sources.ModuleSource
import com.music.rizumu.data.sources.SourceConfig
import com.music.rizumu.data.sources.SourceHealth
import com.music.rizumu.data.sources.SourceKind
import com.music.rizumu.data.sources.SourceRegistry
import com.music.rizumu.data.sources.ServerBrowseKind
import com.music.rizumu.data.sources.ServerBrowseRef
import com.music.rizumu.data.sources.StreamRequest
import com.music.rizumu.data.sources.SubsonicSource
import com.music.rizumu.data.sources.SubsonicStreamQuality
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Subsonic source end to end, against a real HTTP server.
 *
 * What is being pinned down here is the translation: a server's song row into
 * this app's [Song], a [StreamRequest] into query parameters, and a refusal
 * into the right kind of [SourceHealth]. Playback itself is out of reach in a
 * unit test, but the URL the player would be handed is not.
 */
class SubsonicSourceTest {

    private lateinit var server: MockWebServer

    private val routes = mutableMapOf<String, MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val path = request.requestUrl?.encodedPath.orEmpty()
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        // The rung is process-wide state; a test that moved it puts it back.
        AppSettings.audioQualityWifi.value = AudioQuality.LOSSLESS
        AppSettings.audioQualityCellular.value = AudioQuality.LOSSLESS
    }

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
    }

    private fun ok(payload: String = "") = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
            """"serverVersion":"0.54.5","openSubsonic":true$payload}}""",
    )

    private fun failure(code: Int, message: String) = MockResponse().setBody(
        """{"subsonic-response":{"status":"failed","error":{"code":$code,"message":"$message"}}}""",
    )

    private val searchBody = ok(
        ""","searchResult3":{"song":[""" +
            """{"id":"300","title":"Teardrop","artist":"Massive Attack","album":"Mezzanine",""" +
            """"duration":330,"bitRate":1411,"suffix":"flac","coverArt":"al-12","albumId":"al-12","artistId":"ar-3"},""" +
            """{"id":"301","title":"Angel","artist":"Massive Attack","album":"Mezzanine",""" +
            """"duration":384,"bitRate":128,"suffix":"mp3","coverArt":"al-12","albumId":"al-12","artistId":"ar-3"}]}""",
    )

    private fun config(
        quality: SubsonicStreamQuality = SubsonicStreamQuality.ORIGINAL,
        username: String = "levi",
        password: String = "hunter2",
        baseUrl: String = server.url("/").toString(),
    ) = SourceConfig(
        kind = SourceKind.SUBSONIC,
        baseUrl = baseUrl,
        username = username,
        password = password,
        streamQuality = quality,
    )

    private fun source(
        quality: SubsonicStreamQuality = SubsonicStreamQuality.ORIGINAL,
        username: String = "levi",
        password: String = "hunter2",
        baseUrl: String = server.url("/").toString(),
    ) = SubsonicSource(config(quality, username, password, baseUrl))

    // ── Health ────────────────────────────────────────────────────────────

    @Test
    fun `a working server reports its name, version and OpenSubsonic support`() = runBlocking {
        route("/rest/ping", ok())
        val health = source().health()
        assertTrue("expected Ok, got $health", health is SourceHealth.Ok)
        assertEquals("Navidrome 0.54.5 · OpenSubsonic", (health as SourceHealth.Ok).detail)
    }

    @Test
    fun `a wrong password is a rejection, not an outage`() = runBlocking {
        route("/rest/ping", failure(40, "Wrong username or password"))
        val health = source().health()
        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertEquals("Wrong username or password", (health as SourceHealth.Rejected).reason)
    }

    @Test
    fun `a server without an account never leaves the device`() = runBlocking {
        val health = source(username = "", password = "").health()
        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertTrue("an incomplete config must not probe", seen.isEmpty())
    }

    @Test
    fun `a server having a bad minute is unreachable, not rejected`() = runBlocking {
        route("/rest/ping", MockResponse().setResponseCode(503))
        val health = source().health()
        assertTrue("expected Unreachable, got $health", health is SourceHealth.Unreachable)
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search maps a server row into this app's shape`() = runBlocking {
        route("/rest/search3", searchBody)
        val songs = source().search("teardrop", 25, waitForAll = false)

        assertEquals(2, songs.size)
        val flac = songs[0]
        assertEquals("Teardrop", flac.title)
        assertEquals("Massive Attack", flac.artist)
        assertEquals("Mezzanine", flac.albumName)
        assertEquals("5:30", flac.durationText)
        assertEquals(ModuleSource.LOSSLESS, flac.sourceQuality)
        val art = flac.thumbnailUrl
        assertTrue("the row must carry a fetchable cover URL", art!!.contains("/rest/getCoverArt"))
        assertTrue(art.contains("id=al-12"))

        // The id packed into the row is what routes playback back here.
        val key = SourceRegistry.parseTrackKey(flac.videoId)
        assertEquals("300", key?.second)

        // A 128 kbps MP3 is a low rung, which is what lets the resolver choose
        // between this and a lossless copy of the same recording.
        assertEquals(ModuleSource.LOW, songs[1].sourceQuality)
    }

    @Test
    fun `a failed search is an empty list, not a throw`() = runBlocking {
        route("/rest/search3", MockResponse().setResponseCode(500))
        assertTrue(source().search("teardrop", 25, waitForAll = false).isEmpty())
    }

    // ── Stream ────────────────────────────────────────────────────────────

    @Test
    fun `the original preference hands over the file itself`() = runBlocking {
        route("/rest/search3", searchBody)
        val source = source()
        source.search("teardrop", 25, waitForAll = false)

        val stream = source.stream("300", StreamRequest.Best)
        assertTrue(stream!!.url.contains("format=raw"))
        assertFalse("raw and a cap must never travel together", stream.url.contains("maxBitRate"))
        assertEquals("flac", stream.format.codec)
        assertEquals(1411, stream.format.kbps)
        assertEquals(330, stream.durationSec)
    }

    @Test
    fun `a capped request is never exceeded`() = runBlocking {
        route("/rest/search3", searchBody)
        val source = source(quality = SubsonicStreamQuality.ORIGINAL)
        source.search("teardrop", 25, waitForAll = false)

        val stream = source.stream("300", StreamRequest.Capped(64))
        assertTrue(stream!!.url.contains("maxBitRate=64"))
        assertFalse(stream.url.contains("format=raw"))
        assertNull("a transcode's codec is the server's choice", stream.format.codec)
        assertEquals(64, stream.format.kbps)
    }

    @Test
    fun `a standing cap applies when the request names no number`() = runBlocking {
        route("/rest/search3", searchBody)
        val source = source(quality = SubsonicStreamQuality.KBPS_128)
        source.search("teardrop", 25, waitForAll = false)

        val stream = source.stream("300", StreamRequest.Best)
        assertTrue(stream!!.url.contains("maxBitRate=128"))
    }

    @Test
    fun `a lossless request is raw even under a capped preference`() = runBlocking {
        route("/rest/search3", searchBody)
        val source = source(quality = SubsonicStreamQuality.KBPS_128)
        source.search("teardrop", 25, waitForAll = false)

        val stream = source.stream("300", StreamRequest.Lossless)
        assertTrue(stream!!.url.contains("format=raw"))
    }

    @Test
    fun `match network comes down to 256 on the Medium rung`() = runBlocking {
        AppSettings.audioQualityWifi.value = AudioQuality.MEDIUM
        route("/rest/search3", searchBody)
        val source = source(quality = SubsonicStreamQuality.MATCH_NETWORK)
        source.search("teardrop", 25, waitForAll = false)

        val stream = source.stream("300", StreamRequest.Best)
        assertTrue(stream!!.url.contains("maxBitRate=256"))
    }

    @Test
    fun `an unusable address is a miss, not a crash`() = runBlocking {
        val source = SubsonicSource(
            SourceConfig(
                kind = SourceKind.SUBSONIC,
                baseUrl = "not a url",
                username = "levi",
                password = "hunter2",
            ),
        )
        assertNull(source.stream("300", StreamRequest.Best))
    }

    // ── Config ────────────────────────────────────────────────────────────

    @Test
    fun `a server is only complete once it has an address and an account`() {
        assertFalse(SourceConfig(kind = SourceKind.SUBSONIC, baseUrl = "https://music.example.com").isComplete)
        assertFalse(
            SourceConfig(
                kind = SourceKind.SUBSONIC,
                baseUrl = "https://music.example.com",
                username = "levi",
            ).isComplete,
        )
        assertTrue(
            SourceConfig(
                kind = SourceKind.SUBSONIC,
                baseUrl = "https://music.example.com",
                username = "levi",
                password = "hunter2",
            ).isComplete,
        )
    }

    // ── Browse ────────────────────────────────────────────────────────────

    @Test
    fun `browse ids round-trip through the registry`() {
        val key = SourceRegistry.browseKey("cfg-1", ServerBrowseKind.ALBUM, "al/12::x")
        assertEquals(
            ServerBrowseRef("cfg-1", ServerBrowseKind.ALBUM, "al/12::x"),
            SourceRegistry.parseBrowseKey(key),
        )
        // A track id is not a browse id, and must not be mistaken for one.
        assertNull(SourceRegistry.parseBrowseKey(SourceRegistry.trackKey("cfg-1", "300")))
        assertNull(SourceRegistry.parseBrowseKey("MPREb_whatever"))
    }

    @Test
    fun `the artist page carries albums, top songs and the bio`() = runBlocking {
        route(
            "/rest/getArtist",
            ok(
                ""","artist":{"id":"ar-3","name":"Massive Attack","coverArt":"ar-3","albumCount":1,""" +
                    """"album":[{"id":"al-12","name":"Mezzanine","artist":"Massive Attack","coverArt":"al-12","songCount":11}]}""",
            ),
        )
        route("/rest/getArtistInfo2", ok(""","artistInfo2":{"biography":"Bristol duo."}"""))
        route(
            "/rest/getTopSongs",
            ok(
                ""","topSongs":{"song":[{"id":"300","title":"Teardrop","artist":"Massive Attack","duration":330,"suffix":"flac","bitRate":1411}]}""",
            ),
        )

        val page = source().artist("ar-3")
        assertEquals("Massive Attack", page!!.artist.name)
        assertEquals("Mezzanine", page.albums.single().name)
        assertEquals("Teardrop", page.topSongs.single().title)
        assertEquals("Bristol duo.", page.bio)
        // The top song is remembered, so playing it from the artist page has
        // the same metadata as playing it from search.
        val key = SourceRegistry.parseTrackKey(page.topSongs.single().videoId)
        assertEquals("300", key?.second)
    }

    @Test
    fun `the album page carries its tracks and namespaced links`() = runBlocking {
        route(
            "/rest/getAlbum",
            ok(
                ""","album":{"id":"al-12","name":"Mezzanine","artist":"Massive Attack","artistId":"ar-3",""" +
                    """"coverArt":"al-12","songCount":1,"song":[{"id":"300","title":"Teardrop","artist":"Massive Attack",""" +
                    """"duration":330,"suffix":"flac","bitRate":1411,"albumId":"al-12","artistId":"ar-3"}]}""",
            ),
        )

        val song = source().album("al-12")!!.songs.single()
        val albumRef = SourceRegistry.parseBrowseKey(song.albumId!!)
        assertEquals(ServerBrowseKind.ALBUM, albumRef?.kind)
        assertEquals("al-12", albumRef?.id)
        val artistRef = SourceRegistry.parseBrowseKey(song.artistId!!)
        assertEquals(ServerBrowseKind.ARTIST, artistRef?.kind)
        assertEquals("ar-3", artistRef?.id)
    }

    @Test
    fun `a row the server no longer holds is a null page`() = runBlocking {
        route("/rest/getAlbum", failure(70, "Not found"))
        assertNull(source().album("gone"))
    }

    @Test
    fun `random songs and playlists map through`() = runBlocking {
        route(
            "/rest/getRandomSongs",
            ok(""","randomSongs":{"song":[{"id":"300","title":"Teardrop","duration":330,"suffix":"flac","bitRate":1411}]}"""),
        )
        route(
            "/rest/getPlaylists",
            ok(""","playlists":{"playlist":[{"id":"pl-1","name":"Mix","owner":"levi","songCount":2}]}"""),
        )
        route(
            "/rest/getPlaylist",
            ok(
                ""","playlist":{"id":"pl-1","name":"Mix","owner":"levi","songCount":1,""" +
                    """"entry":[{"id":"300","title":"Teardrop","duration":330,"suffix":"flac","bitRate":1411}]}""",
            ),
        )

        val source = source()
        assertEquals(1, source.randomSongs(10).size)
        val playlists = source.playlists()
        assertEquals("Mix", playlists.single().name)
        assertEquals("levi", playlists.single().owner)
        assertEquals(1, source.playlist("pl-1")!!.size)
    }

    @Test
    fun `playlist writes reach the server`() = runBlocking {
        route("/rest/createPlaylist", ok(""","playlist":{"id":"pl-9","name":"New"}"""))
        route("/rest/updatePlaylist", ok())
        route("/rest/deletePlaylist", ok())

        val source = source()
        assertEquals("pl-9", source.createPlaylist("New", listOf("300")))
        source.updatePlaylist("pl-9", name = "Renamed", addSongIds = listOf("301"), removeSongIds = listOf("300"))
        source.deletePlaylist("pl-9")

        assertEquals(3, seen.size)
        assertEquals("/rest/createPlaylist", seen[0].requestUrl?.encodedPath)
        assertEquals("/rest/updatePlaylist", seen[1].requestUrl?.encodedPath)
        assertEquals("/rest/deletePlaylist", seen[2].requestUrl?.encodedPath)
    }

    // ── Play reporting and lyrics ─────────────────────────────────────────

    @Test
    fun `a finished play is reported to the server`() = runBlocking {
        route("/rest/scrobble", ok())
        source().reportPlayback("300", submission = true, timeMs = 1_700_000_000_000)
        assertEquals("/rest/scrobble", seen.single().requestUrl?.encodedPath)
        assertEquals("true", seen.single().requestUrl?.queryParameter("submission"))
        assertEquals("300", seen.single().requestUrl?.queryParameter("id"))
    }

    @Test
    fun `server lyrics prefer the synced document and keep line timings`() = runBlocking {
        route(
            "/rest/getLyricsBySongId",
            ok(
                ""","lyricsList":{"structuredLyrics":[""" +
                    """{"lang":"eng","synced":false,"line":[{"value":"Plain"}]},""" +
                    """{"lang":"eng","synced":true,"line":[{"value":"First","start":1000},{"value":"Second","start":2500}]}]}""",
            ),
        )
        val lines = source().structuredLyrics("300")!!
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("Second", lines[1].text)
    }

    @Test
    fun `server lyrics fall back to the name-matched call`() = runBlocking {
        route("/rest/getLyricsBySongId", ok(""","lyricsList":{"structuredLyrics":[]}"""))
        route("/rest/getLyrics", ok(""","lyrics":{"value":"Line one\n\nLine two"}"""))
        val lines = source().lyrics("Teardrop", "Massive Attack")!!
        assertEquals(listOf("Line one", "Line two"), lines.map { it.text })
    }
}
