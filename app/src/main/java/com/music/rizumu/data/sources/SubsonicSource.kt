package com.music.rizumu.data.sources

import com.music.rizumu.data.ServerLikeState
import com.music.rizumu.data.TrackLog
import com.music.rizumu.data.lyrics.LyricLine
import com.music.rizumu.data.model.Song
import com.music.rizumu.data.settings.AppSettings
import com.music.rizumu.data.subsonic.SubsonicClient
import com.music.rizumu.data.subsonic.SubsonicException
import com.music.rizumu.data.subsonic.SubsonicAlbum
import com.music.rizumu.data.subsonic.SubsonicAlbumDetail
import com.music.rizumu.data.subsonic.SubsonicArtist
import com.music.rizumu.data.subsonic.SubsonicArtistDetail
import com.music.rizumu.data.subsonic.SubsonicNotFound
import com.music.rizumu.data.subsonic.SubsonicPlaylist
import com.music.rizumu.data.subsonic.SubsonicSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

/**
 * A [MusicSource] backed by one Subsonic-compatible server — Navidrome,
 * Airsonic, Gonic, Ampache, Jellyfin's endpoint.
 *
 * The translation is deliberately thin. A Subsonic song row already carries
 * everything this app's rows need — a stable id, a title, a credit, an album,
 * a runtime, a cover art id, and the file's own codec and bitrate — so the
 * only judgement here is what to ask the server to serve. That question is
 * answered by [SubsonicQuality] from the server's standing preference, the
 * request in hand and the connection's ceiling, and it is the one place this
 * source behaves differently from the addon one: a Subsonic server can both
 * hand over a bit-exact file and transcode on demand, so it is worth asking on
 * every rung rather than only on the lossless one.
 *
 * Nothing here opens a connection until something is asked for — the client is
 * cheap to build and every call happens inside a suspend body — which is what
 * makes it safe for [SourceRegistry] to hold one per configured server.
 */
class SubsonicSource(
    override val config: SourceConfig,
) : MusicSource, ServerLibrary, PlaybackReporter, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.SUBSONIC
    override val displayName: String get() = config.displayName
    override val serverName: String get() = displayName

    private val client = SubsonicClient(
        rawBaseUrl = config.baseUrl,
        username = config.username,
        password = config.password,
        authMode = config.authMode,
        allowInsecureHttp = config.allowInsecureHttp,
    )

    /**
     * Rows this server has recently handed over, by their own song id.
     *
     * [stream] is given an id and nothing else, and the id alone does not say
     * how long the recording is or what it is made of. Both come off the
     * search row that produced it, which is why a bounded copy of those rows
     * is kept — the same bargain [AddonSource] makes, and for the same two
     * reasons: the duration is what a substitute stream is checked against,
     * and the codec and bitrate are what let a stream be described rather than
     * guessed at from a URL that may have no extension at all.
     */
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, SubsonicSong>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, SubsonicSong>) = size > MAX_ROWS
        },
    )

    // ── Health ────────────────────────────────────────────────────────────

    /**
     * Whether the server is there and will talk to this account.
     *
     * The two failures worth telling apart are the two the sources screen
     * paints differently: a refusal ([SourceHealth.Rejected] — wrong password,
     * an address that is not a Subsonic server, an API version it will not
     * speak) is a configuration problem, and a server that did not answer
     * ([SourceHealth.Unreachable]) is one worth leaving switched on.
     */
    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) {
            return@withContext SourceHealth.Rejected("A server address is required")
        }
        if (config.username.isBlank() || config.password.isBlank()) {
            return@withContext SourceHealth.Rejected("A username and password are required")
        }
        try {
            SourceHealth.Ok(client.ping().detail)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: SubsonicException) {
            TrackLog.w(TAG, "${config.displayName}: ${refused.message}")
            SourceHealth.Rejected(refused.message ?: "The server refused those credentials")
        } catch (failure: Exception) {
            TrackLog.w(TAG, "${config.displayName} unreachable: ${failure.message}")
            SourceHealth.Unreachable(failure.message ?: "Could not reach the server")
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Songs the server holds for [query], as [Song]s tagged with this
     * source's id so playing one comes back here.
     *
     * A failure is an empty list rather than a throw: every caller above this
     * reads a throw as "this source is broken", and a search that timed out
     * while another source answered is not that. The log line is where the
     * difference is kept.
     */
    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val found = try {
                client.search3(query, limit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TrackLog.w(TAG, "${config.displayName}: search failed — ${failure.message}")
                return@withContext emptyList()
            }
            found.song.asSequence()
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .take(limit)
                .toList()
                .toSongs()
        }

    // ── Browse ────────────────────────────────────────────────────────────
    //
    // The server as a library rather than a catalogue: lists, pages and the
    // playlists the account holds. Every failure is thrown rather than
    // swallowed — the screens have an empty state and an error state, and a
    // library that is genuinely empty must not be shown as one that failed to
    // load. A row the server no longer holds is the one exception: that is a
    // null page, because "gone" is a legitimate answer.

    override suspend fun artists(): List<ServerArtist> = withContext(Dispatchers.IO) {
        client.artists().map { it.toServerArtist() }
    }

    override suspend fun albums(
        type: ServerAlbumListType,
        offset: Int,
        size: Int,
        fromYear: Int?,
        toYear: Int?,
        genre: String?,
    ): List<ServerAlbum> =
        withContext(Dispatchers.IO) {
            client.albums(type.wire, offset, size, fromYear, toYear, genre).map { it.toServerAlbum() }
        }

    override suspend fun allAlbums(
        type: ServerAlbumListType,
        onPage: suspend (List<ServerAlbum>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var offset = 0
        var pages = 0
        while (pages < MAX_ALBUM_PAGES) {
            val page = client.albums(type.wire, offset, ALBUM_PAGE).map { it.toServerAlbum() }
            if (page.isEmpty()) return@withContext
            onPage(page)
            // A short page is the end of the catalogue; a full one means there
            // may be more behind the offset.
            if (page.size < ALBUM_PAGE) return@withContext
            offset += page.size
            pages++
        }
    }

    override suspend fun randomSongs(size: Int, fromYear: Int?, toYear: Int?): List<Song> =
        withContext(Dispatchers.IO) {
            client.randomSongs(size, fromYear, toYear).toSongs()
        }

    override suspend fun songsByGenre(genre: String, size: Int, offset: Int): List<Song> =
        withContext(Dispatchers.IO) {
            client.songsByGenre(genre, size, offset).toSongs()
        }

    override suspend fun genres(): List<ServerGenre> = withContext(Dispatchers.IO) {
        client.genres()
            .filter { it.value.isNotBlank() }
            .map { ServerGenre(name = it.value, songCount = it.songCount, albumCount = it.albumCount) }
    }

    override suspend fun starred(): ServerStarred = withContext(Dispatchers.IO) {
        val starred = client.starred()
        ServerStarred(
            artists = starred.artist.map { it.toServerArtist() },
            albums = starred.album.map { it.toServerAlbum() },
            songs = starred.song.toSongs(),
        )
    }

    override suspend fun setSongStarred(songId: String, starred: Boolean) = withContext(Dispatchers.IO) {
        client.setSongStarred(songId, starred)
    }

    override suspend fun artist(id: String): ServerArtistPage? = withContext(Dispatchers.IO) {
        val artist = try {
            client.artist(id)
        } catch (missing: SubsonicNotFound) {
            return@withContext null
        }
        // The biography and the top-songs list are extras the server may not
        // hold — a library of untagged files has neither — so they are fetched
        // best-effort rather than being allowed to stop the page opening.
        val info = runCatching { client.artistInfo(id) }.getOrNull()
        val top = runCatching { client.topSongs(artist.name, TOP_SONGS) }.getOrDefault(emptyList())
        ServerArtistPage(
            artist = artist.toServerArtist(),
            albums = artist.album.map { it.toServerAlbum() },
            topSongs = top.toSongs(),
            bio = info?.biography?.ifBlank { null },
        )
    }

    override suspend fun album(id: String): ServerAlbumPage? = withContext(Dispatchers.IO) {
        val album = try {
            client.album(id)
        } catch (missing: SubsonicNotFound) {
            return@withContext null
        }
        ServerAlbumPage(
            album = album.toServerAlbum(),
            songs = album.song.toSongs(),
        )
    }

    override suspend fun playlists(): List<ServerPlaylist> = withContext(Dispatchers.IO) {
        client.playlists().map { it.toServerPlaylist() }
    }

    override suspend fun playlist(id: String): List<Song>? = withContext(Dispatchers.IO) {
        try {
            client.playlist(id).entry.toSongs()
        } catch (missing: SubsonicNotFound) {
            null
        }
    }

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? =
        withContext(Dispatchers.IO) { client.createPlaylist(name, songIds) }

    override suspend fun updatePlaylist(
        id: String,
        name: String?,
        addSongIds: List<String>,
        removeSongIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        client.updatePlaylist(id, name, addSongIds, removeSongIds)
    }

    override suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        client.deletePlaylist(id)
    }

    // ── Play reporting ────────────────────────────────────────────────────

    override suspend fun reportPlayback(songId: String, submission: Boolean, timeMs: Long?) =
        withContext(Dispatchers.IO) {
            client.scrobble(songId, submission, timeMs)
        }

    // ── Lyrics ────────────────────────────────────────────────────────────

    /**
     * Structured lyrics the server holds for [songId], as this app's lines.
     *
     * A synced document wins over an unsynced one, and the first document of
     * the winning kind is taken: a server may hold several languages and this
     * app has no language preference to choose between them with yet. Null
     * when the server holds none — which is the common case for a library of
     * files nobody has written words into, and not a failure.
     */
    suspend fun structuredLyrics(songId: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val documents = runCatching { client.lyricsBySongId(songId) }.getOrDefault(emptyList())
        val best = documents.firstOrNull { it.synced && it.line.isNotEmpty() }
            ?: documents.firstOrNull { it.line.isNotEmpty() }
        best?.line
            ?.mapNotNull { line ->
                line.value.takeIf { it.isNotBlank() }?.let { LyricLine(line.start ?: 0L, it) }
            }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Plain lyrics from the server's own tag reader, matched on artist and
     * title — the pre-OpenSubsonic way, and the fallback for a server that
     * holds words only inside the files.
     */
    suspend fun lyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val value = runCatching { client.lyrics(artist, title) }.getOrNull()?.value?.trim().orEmpty()
        value.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { LyricLine(0L, it) }
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    // ── Stream ────────────────────────────────────────────────────────────

    /**
     * An openable URL for one of this server's song ids.
     *
     * The URL carries its own credentials, which is how every Subsonic client
     * has to work: the player is handed a URL, not a session. See
     * [SubsonicClient.streamUrl] for why those salts are stable for a run.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            val row = rows[trackId]
            val params = SubsonicQuality.paramsFor(
                quality = config.streamQuality,
                request = request,
                rung = AppSettings.effectiveAudioQuality,
            )
            val url = try {
                // A restored queue reaches this with no API call behind it, so
                // the client may still not know whether this server wants the
                // token or the password form. Settle that before signing a URL
                // the player follows without any fallback of its own.
                client.ensureAuthNegotiated()
                client.streamUrl("stream", params + ("id" to trackId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TrackLog.w(TAG, "${config.displayName}: could not build a stream URL — ${failure.message}")
                return@withContext null
            }
            SourceStream(
                url = url,
                format = formatOf(row, params),
                durationSec = row?.durationSec,
            )
        }

    /**
     * What is on the end of the URL, from the row and the parameters sent.
     *
     * A raw request is the file, so the row's own `suffix` and `bitRate` are
     * the truth and a FLAC can be described as one before a byte arrives. A
     * transcode is the server's choice of codec at a bitrate this app named,
     * so the codec is left unknown rather than guessed — "unknown" is
     * information, and naming the wrong codec is not.
     */
    private fun formatOf(row: SubsonicSong?, params: Map<String, String>): StreamFormat {
        if (!SubsonicQuality.isRaw(params)) {
            return StreamFormat(kbps = params[SubsonicQuality.MAX_BITRATE]?.toIntOrNull())
        }
        return StreamFormat(
            codec = row?.suffix?.lowercase(Locale.ROOT)?.takeIf { it in AUDIO_CODECS },
            kbps = row?.bitrateKbps,
        )
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    /**
     * Rows for the screens, with their metadata kept for [stream].
     *
     * Every path that hands a song outward goes through here — search, browse,
     * playlists — so a track played from an album page has the same duration
     * and codec available to its stream as one played from search.
     */
    private fun List<SubsonicSong>.toSongs(): List<Song> {
        // A row the server answers with carries its own star timestamp, so
        // opening any list — an album, a playlist, a genre, search — keeps the
        // likes this session knows about current without a separate starred
        // fetch to paint a heart. Absent means "not starred", so only starred
        // rows are seeded; a session unstar is never overwritten.
        ServerLikeState.seedStarred(
            mapNotNull { song ->
                song.takeIf { it.starred != null }?.let { SourceRegistry.trackKey(config.id, it.id) }
            },
        )
        return map { song ->
            rows[song.id] = song
            song.toSong()
        }
    }

    private fun SubsonicArtist.toServerArtist() = ServerArtist(
        id = id,
        name = name,
        albumCount = albumCount,
        thumbnailUrl = client.coverArtUrl(coverArt),
    )

    /** As [SubsonicArtist.toServerArtist], for the richer `getArtist` shape. */
    private fun SubsonicArtistDetail.toServerArtist() = ServerArtist(
        id = id,
        name = name,
        albumCount = albumCount,
        thumbnailUrl = client.coverArtUrl(coverArt),
    )

    private fun SubsonicAlbum.toServerAlbum() = ServerAlbum(
        id = id,
        name = name,
        artist = artist,
        year = year,
        songCount = songCount,
        thumbnailUrl = client.coverArtUrl(coverArt),
        artistId = artistId.ifBlank { null },
    )

    /** As [SubsonicAlbum.toServerAlbum], for the richer `getAlbum` shape. */
    private fun SubsonicAlbumDetail.toServerAlbum() = ServerAlbum(
        id = id,
        name = name,
        artist = artist,
        year = year,
        songCount = songCount,
        thumbnailUrl = client.coverArtUrl(coverArt),
        artistId = artistId.ifBlank { null },
    )

    private fun SubsonicPlaylist.toServerPlaylist() = ServerPlaylist(
        id = id,
        name = name,
        owner = owner,
        songCount = songCount,
        thumbnailUrl = client.coverArtUrl(coverArt),
        // The protocol carries no "may manage" flag. Owner is the closest
        // thing to one, and an owner the server did not name is treated as
        // the account itself — servers that omit it list the account's own
        // playlists, and hiding every write on those would be a regression.
        canEdit = owner.isBlank() || owner.equals(config.username, ignoreCase = true),
    )

    private fun SubsonicSong.toSong(): Song = Song(
        videoId = SourceRegistry.trackKey(config.id, id),
        title = title,
        artist = artist,
        albumName = album.ifBlank { null },
        thumbnailUrl = client.coverArtUrl(coverArt),
        durationText = durationSec?.let { seconds ->
            "${seconds / 60}:${"%02d".format(Locale.ROOT, seconds % 60)}"
        },
        sourceQuality = ModuleSource.qualityTier("$suffix $bitRate"),
        // Namespaced browse ids, so the player's "go to album" and "go to
        // artist" links open the server's own pages rather than being handed
        // to YouTube as ids it has never seen. See [SourceRegistry.browseKey].
        albumId = albumId.takeIf { it.isNotBlank() }
            ?.let { SourceRegistry.browseKey(config.id, ServerBrowseKind.ALBUM, it) },
        artistId = artistId.takeIf { it.isNotBlank() }
            ?.let { SourceRegistry.browseKey(config.id, ServerBrowseKind.ARTIST, it) },
        // The server's own tag for the recording. Carried so a play can be
        // filed under it locally — see `ListeningStats.genreAffinity` — which
        // is what orders the Play tab's genre row by what is actually played.
        genre = genre.takeIf { it.isNotBlank() },
    )

    private companion object {
        const val TAG = "Rizumu"

        /**
         * The endpoint's own page: `getAlbumList2` answers at most 500 rows.
         * Walking the catalogue in these steps is what lets a "Show all" page
         * hold every album rather than the first page of them.
         */
        const val ALBUM_PAGE = 500

        /**
         * A ceiling on the walk, not a product decision: a server that answers
         * a full page for an offset past the end of the catalogue would
         * otherwise be asked forever.
         */
        const val MAX_ALBUM_PAGES = 100

        /** How many search rows to remember. A long queue's worth, several times over. */
        const val MAX_ROWS = 256

        /** How many best-known tracks an artist page asks the server for. */
        const val TOP_SONGS = 20

        /**
         * What may be believed as a codec off a row's `suffix`.
         *
         * The same list the addon side believes, minus the containers that
         * name no codec: a Subsonic `suffix` is the file's real extension, and
         * an extension that does not settle the codec leaves it unknown.
         */
        val AUDIO_CODECS = setOf(
            "flac", "alac", "wav", "aiff", "mp3", "aac", "m4a", "opus", "ogg", "vorbis", "webm",
        )
    }
}
