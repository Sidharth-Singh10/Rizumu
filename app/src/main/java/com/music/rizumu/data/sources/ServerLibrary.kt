package com.music.rizumu.data.sources

import com.music.rizumu.data.model.Song

/**
 * What a source can answer when it is a whole library rather than a catalogue
 * to search.
 *
 * Kept apart from [MusicSource] on purpose. The source interface every kind
 * implements is deliberately two questions wide — search a name, turn an id
 * into a stream — because those are the only two things a catalogue behind
 * somebody else's API can always be asked. A server that holds *your* files is
 * a different shape: it can list its artists, page its albums, and hand back a
 * playlist, and none of those questions have an honest answer from a module or
 * an addon. Widening [MusicSource] to carry them would make every other source
 * implement methods it can only lie about.
 *
 * So a source that can browse implements this *as well*, and the screens ask
 * `instance is ServerLibrary` before offering a way in. The models here are
 * this app's own rather than the protocol's: nothing above this line should
 * have to know a Subsonic row from a Jellyfin one.
 *
 * Every method may throw — a page that failed to load is not the same as an
 * empty library — and callers show the difference.
 */
interface ServerLibrary {

    /** The configured instance this belongs to. */
    val configId: String

    /** What the user called it, for headings and cards. */
    val serverName: String

    /** Every artist the server holds, in the server's own order. */
    suspend fun artists(): List<ServerArtist>

    /**
     * One page of albums of [type].
     *
     * [fromYear] and [toYear] bound a `byYear` listing and [genre] names a
     * `byGenre` one — the two orderings that identify their rows by something
     * other than the ordering alone. Every other type ignores them.
     */
    suspend fun albums(
        type: ServerAlbumListType,
        offset: Int,
        size: Int,
        fromYear: Int? = null,
        toYear: Int? = null,
        genre: String? = null,
    ): List<ServerAlbum>

    /**
     * Every album of [type], a page at a time, until the server has no more.
     *
     * The endpoint answers at most 500 rows and takes an offset, so a
     * catalogue larger than one page is walked rather than asked for in a
     * single call. The walk starts at the beginning and lets the caller drop
     * what it already had, which is what makes it safe for a shelf that is a
     * selection rather than a prefix — a "most played" row, say — where
     * starting part-way in would skip albums the selection does not hold.
     *
     * [onPage] is invoked with each page as it arrives and in order, so a
     * caller can let the list grow on screen — this is how a "Show all" page
     * comes to hold the whole catalogue, which is what makes the search over
     * it exhaustive.
     */
    suspend fun allAlbums(
        type: ServerAlbumListType,
        onPage: suspend (List<ServerAlbum>) -> Unit,
    )

    /** A random selection from the library, optionally bounded to a year range. */
    suspend fun randomSongs(size: Int, fromYear: Int? = null, toYear: Int? = null): List<Song>

    /** Songs filed under one genre. */
    suspend fun songsByGenre(genre: String, size: Int, offset: Int = 0): List<Song>

    /** The genres the library holds, for a discovery row. */
    suspend fun genres(): List<ServerGenre>

    /** Everything the account has starred, by type. */
    suspend fun starred(): ServerStarred

    /**
     * Stars or unstars one song.
     *
     * Unlike [starred] this is a write to the account, so it may throw for the
     * usual reasons a server refuses one — no permission, no network, a song
     * that is gone. Callers show the change first and roll it back if this
     * fails.
     */
    suspend fun setSongStarred(songId: String, starred: Boolean)

    /** One artist with their albums and best-known tracks, or null if gone. */
    suspend fun artist(id: String): ServerArtistPage?

    /** One album with its tracks, or null if gone. */
    suspend fun album(id: String): ServerAlbumPage?

    /** The account's playlists, without their entries. */
    suspend fun playlists(): List<ServerPlaylist>

    /** One playlist's tracks, or null if gone. */
    suspend fun playlist(id: String): List<Song>?

    /**
     * Creates a playlist and returns its id, or null when the server named
     * none. [songIds] are this server's own ids — what a row's track key
     * carries as its second half.
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String?

    /** Renames a playlist and/or changes its entries. */
    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        addSongIds: List<String> = emptyList(),
        removeSongIds: List<String> = emptyList(),
    )

    suspend fun deletePlaylist(id: String)
}

/** One artist on a music server. */
data class ServerArtist(
    val id: String,
    val name: String,
    val albumCount: Int,
    val thumbnailUrl: String?,
)

/** One album on a music server. */
data class ServerAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val year: Int?,
    val songCount: Int,
    val thumbnailUrl: String?,
    /**
     * The artist's own id, where the server sends one.
     *
     * Carried because an album row is often the only place an artist appears —
     * see [playedArtists] — and without it there is no way from that row to the
     * artist's page. Null rather than blank so "the server did not say" and
     * "the server said nothing useful" are the same case at every use site.
     */
    val artistId: String? = null,
)

/**
 * The artists behind the albums a listener actually plays.
 *
 * [frequent] and [recent] are the server's own per-user album lists, in the
 * order the server gave them: most played first, then most recently played. An
 * artist takes the place of the first album of theirs to appear and appears
 * once, because the protocol has no "frequent artists" call — this is the
 * honest way to answer the question from what it does keep.
 *
 * [known] is the library's artist list, which is where the artist's own
 * picture and album count come from; an album row names an artist but carries
 * no face. An artist the server lists albums for but not in its artist index
 * falls back to the album's cover, and one with no id at all is skipped — a
 * card that cannot open the artist's page is worse than a shorter row.
 */
internal fun playedArtists(
    frequent: List<ServerAlbum>,
    recent: List<ServerAlbum>,
    known: List<ServerArtist>,
    limit: Int,
): List<ServerArtist> {
    if (limit <= 0) return emptyList()
    val byId = known.associateBy { it.id }
    val seen = LinkedHashSet<String>()
    val ordered = mutableListOf<ServerArtist>()
    for (album in frequent + recent) {
        val id = album.artistId?.takeIf { it.isNotBlank() } ?: continue
        if (!seen.add(id)) continue
        val artist = byId[id]
        ordered += artist?.copy(thumbnailUrl = artist.thumbnailUrl ?: album.thumbnailUrl)
            ?: ServerArtist(id = id, name = album.artist, albumCount = 0, thumbnailUrl = album.thumbnailUrl)
        if (ordered.size == limit) break
    }
    return ordered
}

/** One playlist on a music server. Defaults so the picker can hold a blank row. */
data class ServerPlaylist(
    val id: String = "",
    val name: String = "",
    val owner: String = "",
    val songCount: Int = 0,
    val thumbnailUrl: String? = null,
)

/** One genre on a music server. */
data class ServerGenre(
    val name: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

/** An artist page: the artist, their releases, and what the server thinks is best. */
data class ServerArtistPage(
    val artist: ServerArtist,
    val albums: List<ServerAlbum>,
    val topSongs: List<Song>,
    val bio: String?,
)

/** An album page: the release and its running order. */
data class ServerAlbumPage(
    val album: ServerAlbum,
    val songs: List<Song>,
)

/** Everything starred, split the way the server splits it. */
data class ServerStarred(
    val artists: List<ServerArtist> = emptyList(),
    val albums: List<ServerAlbum> = emptyList(),
    val songs: List<Song> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/** The orderings `getAlbumList2` supports that this app offers. */
enum class ServerAlbumListType(val wire: String) {
    NEWEST("newest"),
    RECENT("recent"),
    FREQUENT("frequent"),
    ALPHABETICAL_BY_NAME("alphabeticalByName"),
    RANDOM("random"),

    /** Albums in the year range the caller supplies; the decade cards' covers. */
    BY_YEAR("byYear"),

    /** Albums filed under the genre the caller names; the genre cards' covers. */
    BY_GENRE("byGenre"),
}

/**
 * What kind of page a server browse id names.
 *
 * [SERVER] is the library's own home page — a random selection plus the
 * newest releases and the artist list; the rest are the pages a row can open.
 * Kept as an enum rather than a string so a typo is a compile error and an id
 * written by an older build fails to parse rather than opening the wrong kind
 * of page.
 */
enum class ServerBrowseKind { SERVER, ARTIST, ALBUM, PLAYLIST, GENRE, DECADE, STARRED }

/**
 * A parsed server browse id: which server, what kind of page, which row.
 *
 * [id] is empty for [ServerBrowseKind.SERVER], which is the only page that
 * names no row — its identity is the server itself.
 */
data class ServerBrowseRef(
    val configId: String,
    val kind: ServerBrowseKind,
    val id: String,
)
