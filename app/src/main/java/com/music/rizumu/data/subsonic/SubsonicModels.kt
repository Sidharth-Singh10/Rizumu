package com.music.rizumu.data.subsonic

import kotlinx.serialization.Serializable

/**
 * The wire shapes of the Subsonic API this app reads.
 *
 * Only the fields Rizumu uses are declared; the parser ignores unknown keys,
 * so a server with a richer payload — OpenSubsonic adds `musicBrainzId`,
 * `genres`, `artists`, `displayArtist`, `transcodedSuffix` and more — is not a
 * parse failure, it is a payload with fields this app has no use for yet.
 * Everything is defaulted, because Subsonic servers disagree about which
 * optional fields they send and the protocol allows all of them to be absent.
 *
 * The envelope (`subsonic-response`, `status`, `error`) is handled in
 * [SubsonicClient] rather than modelled here: those fields decide whether the
 * payload is usable at all, and decoding them into the same class that carries
 * the payload would make "the server refused us" look like "here is an empty
 * search result".
 */

@Serializable
data class SubsonicSearchResult3(
    val artist: List<SubsonicArtist> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val song: List<SubsonicSong> = emptyList(),
)

/**
 * The payload of a `search3` answer: the envelope's `searchResult3` object.
 *
 * One wrapper rather than decoding the envelope directly into
 * [SubsonicSearchResult3], because the rows do not sit at the top level of the
 * response — `search3` nests them under `searchResult3`, and a decoder pointed
 * at the wrong level silently produces an empty list rather than an error.
 */
@Serializable
data class SubsonicSearchResponse(
    val searchResult3: SubsonicSearchResult3 = SubsonicSearchResult3(),
)

/**
 * One song row.
 *
 * [suffix] and [bitRate] are what make quality legible: the server states the
 * file's real container and its real bitrate, so a row can say `FLAC` or
 * `320 kbps` before anything is streamed, and the player can describe a
 * bit-exact stream as one.
 */
@Serializable
data class SubsonicSong(
    val id: String = "",
    val parent: String = "",
    val title: String = "",
    val album: String = "",
    val artist: String = "",
    val track: Int = 0,
    val year: Int? = null,
    val genre: String = "",
    val coverArt: String = "",
    /** Length in seconds. */
    val duration: Int = 0,
    /** The file's own bitrate in kbps, when the server states one. */
    val bitRate: Int = 0,
    /** `flac`, `mp3`, `aac`, `opus`… — the file's real codec. */
    val suffix: String = "",
    val contentType: String = "",
    val albumId: String = "",
    val artistId: String = "",
    /** A timestamp when starred, absent otherwise. Not read yet. */
    val starred: String? = null,
) {
    val durationSec: Int? get() = duration.takeIf { it > 0 }
    val bitrateKbps: Int? get() = bitRate.takeIf { it > 0 }
    val hasCoverArt: Boolean get() = coverArt.isNotBlank()
}

@Serializable
data class SubsonicArtist(
    val id: String = "",
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String = "",
)

@Serializable
data class SubsonicAlbum(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    val coverArt: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
)

/**
 * What a `ping` says about the server itself, assembled from the response
 * envelope rather than any one payload field.
 */
data class SubsonicServerInfo(
    /** `navidrome`, `gonic`, `airsonic`… as the server names itself, lowercased. */
    val type: String,
    /** The server's own version (`serverVersion`), not the API version. */
    val serverVersion: String,
    /** Whether the server advertises the OpenSubsonic extension set. */
    val openSubsonic: Boolean,
    /** The API version the server answered with, e.g. `1.16.1`. */
    val apiVersion: String,
) {
    /**
     * The one-line description the sources screen shows: "Navidrome 0.54.5 ·
     * OpenSubsonic". Empty parts drop out rather than leaving stray separators.
     */
    val detail: String?
        get() = listOfNotNull(
            listOf(type, serverVersion).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
            "OpenSubsonic".takeIf { openSubsonic },
        ).joinToString(" · ").ifBlank { null }
}

// ── Browsing ────────────────────────────────────────────────────────────
//
// One wrapper per endpoint's payload, for the same reason [SubsonicSearchResponse]
// exists: the rows sit under a named key inside the envelope (`artist`,
// `album`, `playlist`…), and decoding at the wrong level produces an empty
// answer rather than an error. The wrappers are the contract with that shape.

/** `getArtists`: the server's whole artist list, grouped into alphabetical indexes. */
@Serializable
data class SubsonicArtists(
    val index: List<SubsonicArtistIndex> = emptyList(),
) {
    /** Every artist across every index, in the server's own order. */
    val flat: List<SubsonicArtist> get() = index.flatMap { it.artist }
}

/** `getArtists`' envelope payload — the rows sit under `artists`, not at the top. */
@Serializable
data class SubsonicArtistsResponse(
    val artists: SubsonicArtists = SubsonicArtists(),
)

@Serializable
data class SubsonicArtistIndex(
    val name: String = "",
    val artist: List<SubsonicArtist> = emptyList(),
)

/** `getArtist`: one artist with their albums. */
@Serializable
data class SubsonicArtistDetail(
    val id: String = "",
    val name: String = "",
    val coverArt: String = "",
    val albumCount: Int = 0,
    val album: List<SubsonicAlbum> = emptyList(),
)

@Serializable
data class SubsonicArtistResponse(
    val artist: SubsonicArtistDetail = SubsonicArtistDetail(),
)

/** `getArtistInfo2`: the biography and pictures a server may hold for an artist. */
@Serializable
data class SubsonicArtistInfo2(
    val biography: String = "",
    val smallImageUrl: String = "",
    val mediumImageUrl: String = "",
    val largeImageUrl: String = "",
)

@Serializable
data class SubsonicArtistInfoResponse(
    val artistInfo2: SubsonicArtistInfo2 = SubsonicArtistInfo2(),
)

/** `getAlbum`: one album with its tracks. */
@Serializable
data class SubsonicAlbumDetail(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    val coverArt: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val song: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicAlbumResponse(
    val album: SubsonicAlbumDetail = SubsonicAlbumDetail(),
)

/** `getAlbumList2`: a page of albums of one kind. */
@Serializable
data class SubsonicAlbumList(
    val album: List<SubsonicAlbum> = emptyList(),
)

/** `getAlbumList2`'s envelope payload. */
@Serializable
data class SubsonicAlbumListResponse(
    val albumList2: SubsonicAlbumList = SubsonicAlbumList(),
)

/** `getRandomSongs` and `getTopSongs`: a bare list of songs. */
@Serializable
data class SubsonicSongs(
    val song: List<SubsonicSong> = emptyList(),
)

/** `getRandomSongs`' envelope payload. */
@Serializable
data class SubsonicRandomSongsResponse(
    val randomSongs: SubsonicSongs = SubsonicSongs(),
)

/** `getTopSongs`' envelope payload. */
@Serializable
data class SubsonicTopSongsResponse(
    val topSongs: SubsonicSongs = SubsonicSongs(),
)

/** `getStarred2`: everything the account has starred, by type. */
@Serializable
data class SubsonicStarred(
    val artist: List<SubsonicArtist> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val song: List<SubsonicSong> = emptyList(),
)

/** `getStarred2`'s envelope payload. */
@Serializable
data class SubsonicStarredResponse(
    val starred2: SubsonicStarred = SubsonicStarred(),
)

@Serializable
data class SubsonicGenre(
    val value: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

/** `getGenres`: every genre the library holds. */
@Serializable
data class SubsonicGenres(
    val genre: List<SubsonicGenre> = emptyList(),
)

/** `getGenres`' envelope payload. */
@Serializable
data class SubsonicGenresResponse(
    val genres: SubsonicGenres = SubsonicGenres(),
)

/** One playlist row from `getPlaylists`. */
@Serializable
data class SubsonicPlaylist(
    val id: String = "",
    val name: String = "",
    val comment: String = "",
    val owner: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String = "",
)

/** `getPlaylist`: one playlist with its entries. */
@Serializable
data class SubsonicPlaylistDetail(
    val id: String = "",
    val name: String = "",
    val comment: String = "",
    val owner: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String = "",
    val entry: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicPlaylists(
    val playlist: List<SubsonicPlaylist> = emptyList(),
)

/** `getPlaylists`' envelope payload. */
@Serializable
data class SubsonicPlaylistsResponse(
    val playlists: SubsonicPlaylists = SubsonicPlaylists(),
)

@Serializable
data class SubsonicPlaylistResponse(
    val playlist: SubsonicPlaylistDetail = SubsonicPlaylistDetail(),
)

/**
 * For the endpoints whose answer is only the envelope — `updatePlaylist`,
 * `deletePlaylist`, `star`. They still go through the same decode path, so
 * they need *something* to decode into; an empty object with every field
 * defaulted is that something.
 */
@Serializable
data class SubsonicAck(
    val ignored: Boolean = false,
)

// ── Lyrics ──────────────────────────────────────────────────────────────

/** One line of OpenSubsonic structured lyrics. */
@Serializable
data class SubsonicLyricLine(
    val value: String = "",
    /** Start in milliseconds, when the server says when the line is sung. */
    val start: Long? = null,
)

/** One lyrics document — a language and whether its lines carry timings. */
@Serializable
data class SubsonicStructuredLyrics(
    val lang: String = "",
    val synced: Boolean = false,
    val line: List<SubsonicLyricLine> = emptyList(),
)

/** `getLyricsBySongId`'s envelope payload. */
@Serializable
data class SubsonicLyricsList(
    val structuredLyrics: List<SubsonicStructuredLyrics> = emptyList(),
)

@Serializable
data class SubsonicLyricsResponse(
    val lyricsList: SubsonicLyricsList = SubsonicLyricsList(),
)

/** The older `getLyrics` payload: one plain-text blob, matched by name. */
@Serializable
data class SubsonicLyrics(
    val artist: String = "",
    val title: String = "",
    val value: String = "",
)

@Serializable
data class SubsonicLegacyLyricsResponse(
    val lyrics: SubsonicLyrics = SubsonicLyrics(),
)
