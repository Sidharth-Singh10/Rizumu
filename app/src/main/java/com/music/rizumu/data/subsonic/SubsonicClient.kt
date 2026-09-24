package com.music.rizumu.data.subsonic

import com.music.rizumu.data.Http
import com.music.rizumu.data.TrackLog
import com.music.rizumu.data.sources.SubsonicAuthMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One Subsonic server, and every question this app knows how to ask it.
 *
 * The protocol is one GET per call under `{server}/rest/{endpoint}`, with the
 * identity of the caller and the app smeared across the query string, and an
 * envelope around every answer:
 *
 * ```
 * {"subsonic-response": {"status":"ok", "version":"1.16.1", ...payload}}
 * ```
 *
 * Two things here are deliberate. Auth is [SubsonicAuthMode.AUTO] by default
 * and falls back from token to password **per server, once**, when the server
 * says error 41 — a server that wants the legacy form is told so in the same
 * call, rather than making the user find the setting. And every failure is a
 * typed exception, because the caller has three genuinely different things to
 * say to a user: wrong password, server down, and server too old are not the
 * same problem and must not be rendered as one.
 *
 * Requests borrow [Http.client]'s pool and dispatcher rather than starting a
 * second OkHttp stack, for the same reason the addon client does: this app
 * funnels every request through one client so connection setup, DNS and the
 * data-usage instrumentation stay in one place. What is added is a call
 * timeout, because a server that accepts a connection and then dribbles is the
 * one failure a read timeout alone will not catch.
 */
class SubsonicClient(
    rawBaseUrl: String,
    private val username: String,
    private val password: String,
    private val authMode: SubsonicAuthMode = SubsonicAuthMode.AUTO,
    /**
     * Whether the user explicitly accepted a plain-HTTP server. Off by
     * default: HTTP puts the account name and a reusable credential on the
     * wire for anyone on the network to read and replay, so it is an opt-in
     * with a warning rather than something a pasted `http://` silently picks.
     */
    private val allowInsecureHttp: Boolean = false,
) {

    /** Where this server lives, with any trailing `/` or `/rest` off. */
    val baseUrl: String = normalizeBase(rawBaseUrl)

    /** Whether the legacy password form has been forced by a server that refused tokens. */
    @Volatile
    private var legacy: Boolean = authMode == SubsonicAuthMode.LEGACY

    /**
     * Whether an API call has already established which auth form this server
     * accepts. False only while an [SubsonicAuthMode.AUTO] client has not made
     * its first call yet — the state a cold-start stream URL is generated in,
     * which is what [ensureAuthNegotiated] exists for.
     */
    @Volatile
    private var authNegotiated: Boolean = authMode != SubsonicAuthMode.AUTO

    /**
     * Makes sure [streamUrl] and [coverArtUrl] are signed the way this server
     * will accept, before either is handed to the player or the image loader.
     *
     * Those two cannot fall back the way [call] does: a URL is followed by
     * ExoPlayer or Coil, not by this class, so a token-signed URL for a
     * server that only takes the password form fails playback with no retry.
     * A queue restored after process death goes straight to [streamUrl] with
     * no API call in between, which is the case this closes.
     *
     * One ping on the first direct URL per client, and nothing after it.
     */
    suspend fun ensureAuthNegotiated() {
        if (authNegotiated) return
        ping()
    }

    /**
     * The salt [streamUrl] uses, stable for the life of this client.
     *
     * API calls get a fresh salt each time, which is what the scheme intends.
     * The URLs handed outward are different: the player and the image loader
     * cache by URL, and a fresh salt per call would make every cover art URL a
     * new cache key and re-fetch the same image on every screen. A client is
     * rebuilt whenever its config changes, so this is a per-run value rather
     * than a permanent one.
     */
    private val urlSalt = SubsonicAuth.randomSalt()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * A derived client with a ceiling on the whole call, sharing the pool and
     * dispatcher of [Http.client]. See the class note for why one stack.
     */
    private val client: OkHttpClient get() = sharedClient

    // ── Endpoints ─────────────────────────────────────────────────────────

    /**
     * Asks the server to prove it is there and to say what it is.
     *
     * Ping is also the only call that distinguishes "wrong password" from
     * every other failure before a library is touched, which is what makes it
     * the health probe.
     */
    suspend fun ping(): SubsonicServerInfo = withContext(Dispatchers.IO) {
        val envelope = envelopeWithFallback("ping", emptyList())
        SubsonicServerInfo(
            type = SubsonicAuth.displayType(envelope.string("type").orEmpty()),
            serverVersion = envelope.string("serverVersion").orEmpty(),
            openSubsonic = envelope.flag("openSubsonic"),
            apiVersion = envelope.string("version").orEmpty(),
        )
    }

    /**
     * Songs matching [query].
     *
     * Only songs are asked for: this app's source layer is about taking a
     * track from a name to audio, and the artist and album rows a broader
     * search would return belong to the browse screens, which ask for them
     * directly rather than through a text query.
     */
    suspend fun search3(query: String, limit: Int): SubsonicSearchResult3 {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SubsonicSearchResult3()
        return call(
            endpoint = "search3",
            params = mapOf(
                "query" to trimmed,
                "songCount" to limit.coerceIn(1, MAX_SEARCH_RESULTS).toString(),
                "songOffset" to "0",
                "artistCount" to "0",
                "albumCount" to "0",
            ),
            deserializer = SubsonicSearchResponse.serializer(),
        ).searchResult3
    }

    // ── Browsing ──────────────────────────────────────────────────────────
    //
    // One method per endpoint the browse screens need. All of them throw on
    // failure rather than returning empty: a page that could not be loaded and
    // a library that is genuinely empty are different answers, and the caller
    // is the one that can say which of them the user is looking at.

    /** Every artist the server holds, flattened out of its alphabetical indexes. */
    suspend fun artists(): List<SubsonicArtist> =
        call("getArtists", emptyMap(), SubsonicArtistsResponse.serializer()).artists.flat

    /** One artist with their albums. */
    suspend fun artist(id: String): SubsonicArtistDetail =
        call("getArtist", mapOf("id" to id), SubsonicArtistResponse.serializer()).artist

    /** The biography and pictures, when the server holds any. */
    suspend fun artistInfo(id: String): SubsonicArtistInfo2 =
        call("getArtistInfo2", mapOf("id" to id), SubsonicArtistInfoResponse.serializer()).artistInfo2

    /** One album with its tracks. */
    suspend fun album(id: String): SubsonicAlbumDetail =
        call("getAlbum", mapOf("id" to id), SubsonicAlbumResponse.serializer()).album

    /**
     * One page of albums of [type] — `newest`, `recent`, `frequent`,
     * `alphabeticalByName`, `random`, `byYear`, `byGenre` — in the server's own
     * order. [fromYear] and [toYear] bound a `byYear` listing and [genre]
     * names a `byGenre` one; every other type leaves them off the request.
     */
    suspend fun albums(
        type: String,
        offset: Int,
        size: Int,
        fromYear: Int? = null,
        toYear: Int? = null,
        genre: String? = null,
    ): List<SubsonicAlbum> =
        call(
            endpoint = "getAlbumList2",
            params = buildMap {
                put("type", type)
                put("size", size.coerceIn(1, MAX_PAGE).toString())
                put("offset", offset.coerceAtLeast(0).toString())
                fromYear?.let { put("fromYear", it.toString()) }
                toYear?.let { put("toYear", it.toString()) }
                genre?.let { put("genre", it) }
            },
            deserializer = SubsonicAlbumListResponse.serializer(),
        ).albumList2.album

    /** A random selection from the library, optionally bounded to a year range. */
    suspend fun randomSongs(size: Int, fromYear: Int? = null, toYear: Int? = null): List<SubsonicSong> =
        call(
            endpoint = "getRandomSongs",
            params = buildMap {
                put("size", size.coerceIn(1, MAX_PAGE).toString())
                fromYear?.let { put("fromYear", it.toString()) }
                toYear?.let { put("toYear", it.toString()) }
            },
            deserializer = SubsonicRandomSongsResponse.serializer(),
        ).randomSongs.song

    /** Songs filed under one genre, for a genre page. */
    suspend fun songsByGenre(genre: String, size: Int, offset: Int = 0): List<SubsonicSong> =
        call(
            endpoint = "getSongsByGenre",
            params = mapOf(
                "genre" to genre,
                "count" to size.coerceIn(1, MAX_PAGE).toString(),
                "offset" to offset.coerceAtLeast(0).toString(),
            ),
            deserializer = SubsonicSongsByGenreResponse.serializer(),
        ).songsByGenre.song

    /** The server's own idea of an artist's best-known tracks. */
    suspend fun topSongs(artistName: String, count: Int): List<SubsonicSong> =
        call(
            endpoint = "getTopSongs",
            params = mapOf(
                "artist" to artistName,
                "count" to count.coerceIn(1, MAX_PAGE).toString(),
            ),
            deserializer = SubsonicTopSongsResponse.serializer(),
        ).topSongs.song

    /** Everything the account has starred, by type. */
    suspend fun starred(): SubsonicStarred =
        call("getStarred2", emptyMap(), SubsonicStarredResponse.serializer()).starred2

    /** Every genre the library holds. */
    suspend fun genres(): List<SubsonicGenre> =
        call("getGenres", emptyMap(), SubsonicGenresResponse.serializer()).genres.genre

    /**
     * Stars or unstars one song.
     *
     * `id` names the song itself. The endpoint accepts `albumId` and
     * `artistId` too, for the other two ID3 media types; this app stars songs.
     * The answer is only the envelope, hence [SubsonicAck].
     */
    suspend fun setSongStarred(songId: String, starred: Boolean) {
        call(
            endpoint = if (starred) "star" else "unstar",
            params = listOf("id" to songId),
            deserializer = SubsonicAck.serializer(),
        )
    }

    /** The account's playlists, without their entries. */
    suspend fun playlists(): List<SubsonicPlaylist> =
        call("getPlaylists", emptyMap(), SubsonicPlaylistsResponse.serializer()).playlists.playlist

    /** One playlist with its entries. */
    suspend fun playlist(id: String): SubsonicPlaylistDetail =
        call("getPlaylist", mapOf("id" to id), SubsonicPlaylistResponse.serializer()).playlist

    // ── Playlist writes ───────────────────────────────────────────────────

    /**
     * Creates a playlist and returns its id, or null when the server answered
     * without one — some return only a status for a create, and a caller with
     * no id cannot open what it just made.
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? {
        val response = call(
            endpoint = "createPlaylist",
            params = buildList {
                add("name" to name)
                songIds.forEach { add("songId" to it) }
            },
            deserializer = SubsonicPlaylistResponse.serializer(),
        )
        return response.playlist.id.ifBlank { null }
    }

    /**
     * Renames a playlist and/or changes its entries.
     *
     * The add and remove lists are sent as repeated parameters, which is the
     * one shape a map could not express — hence the list form of [call].
     */
    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        addSongIds: List<String> = emptyList(),
        removeSongIds: List<String> = emptyList(),
    ) {
        call(
            endpoint = "updatePlaylist",
            params = buildList {
                add("playlistId" to id)
                name?.let { add("name" to it) }
                addSongIds.forEach { add("songIdToAdd" to it) }
                removeSongIds.forEach { add("songIdToRemove" to it) }
            },
            deserializer = SubsonicAck.serializer(),
        )
    }

    suspend fun deletePlaylist(id: String) {
        call("deletePlaylist", listOf("playlistId" to id), SubsonicAck.serializer())
    }

    // ── Play reporting ────────────────────────────────────────────────────

    /**
     * Reports a play to the server.
     *
     * `submission=false` is "now playing", which Navidrome shows against the
     * account and never counts; `true` is a finished play, which is what
     * increments the play count and feeds its own recommendations. [timeMs] is
     * when the play began, in milliseconds since the epoch — the protocol's
     * `time` parameter, not the current clock.
     *
     * This is the legacy `scrobble` call, which every Subsonic server has;
     * OpenSubsonic's `reportPlayback` says the same thing in more detail and is
     * worth using when a server advertises it, but nothing here depends on it.
     */
    suspend fun scrobble(songId: String, submission: Boolean, timeMs: Long? = null) {
        call(
            endpoint = "scrobble",
            params = buildList {
                add("id" to songId)
                add("submission" to submission.toString())
                timeMs?.let { add("time" to it.toString()) }
            },
            deserializer = SubsonicAck.serializer(),
        )
    }

    // ── Lyrics ────────────────────────────────────────────────────────────

    /**
     * The structured lyrics the server holds for one song, by the server's own
     * id for it. OpenSubsonic only: a server that does not advertise the
     * extension answers with an empty list rather than an error, and the
     * legacy [lyrics] call is what covers those.
     */
    suspend fun lyricsBySongId(songId: String): List<SubsonicStructuredLyrics> =
        call(
            endpoint = "getLyricsBySongId",
            params = listOf("id" to songId),
            deserializer = SubsonicLyricsResponse.serializer(),
        ).lyricsList.structuredLyrics

    /** The older lyrics call: one plain-text blob, matched on artist and title. */
    suspend fun lyrics(artist: String, title: String): SubsonicLyrics =
        call(
            endpoint = "getLyrics",
            params = listOf("artist" to artist, "title" to title),
            deserializer = SubsonicLegacyLyricsResponse.serializer(),
        ).lyrics

    // ── URLs ──────────────────────────────────────────────────────────────

    /**
     * A fully-signed URL for one endpoint, for the places that need a URL
     * rather than a parsed answer: the player fetching `/stream`, Coil
     * fetching `/getCoverArt`, the downloader fetching either.
     *
     * The credentials travel in the query string because that is where every
     * Subsonic client has to put them — the player is handed a URL, and there
     * is no header channel that survives into ExoPlayer's fetch and Coil's
     * cache key both. The salt is stable for this client so these URLs stay
     * cacheable; see [urlSalt].
     */
    fun streamUrl(endpoint: String, params: Map<String, String> = emptyMap()): String =
        urlFor(endpoint, params.map { it.key to it.value }, legacy, urlSalt)

    /** Cover art as a URL the image loader can fetch directly. */
    fun coverArtUrl(coverArtId: String, sizePx: Int = DEFAULT_ART_SIZE): String? {
        if (coverArtId.isBlank()) return null
        return streamUrl(
            "getCoverArt",
            mapOf("id" to coverArtId, "size" to sizePx.coerceIn(32, 2048).toString()),
        )
    }

    // ── Transport ─────────────────────────────────────────────────────────

    /**
     * One call, decoded into [T].
     *
     * Decoding is separate from fetching because the envelope is also read
     * without a payload — [ping] — and both have to go through the same
     * token-to-password fallback. The list form is what carries parameters
     * that may repeat (`songId`), which a map cannot express.
     */
    private suspend fun <T> call(
        endpoint: String,
        params: Map<String, String>,
        deserializer: DeserializationStrategy<T>,
    ): T = call(endpoint, params.map { it.key to it.value }, deserializer)

    private suspend fun <T> call(
        endpoint: String,
        params: List<Pair<String, String>>,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        decode(envelopeWithFallback(endpoint, params), deserializer, endpoint)
    }

    /**
     * The envelope for one call, with the token-to-password fallback applied.
     *
     * The fallback is only ever one step, and only when the mode allows it:
     * a server that answers 41 to the token attempt gets asked again with the
     * password in the same call, and every later call on this client goes
     * straight to the form that worked. A server that refuses tokens is a
     * perfectly ordinary Subsonic server — several of the older ones are — and
     * making the user find a setting to talk to one would be a setting that
     * exists only to undo a default.
     */
    private suspend fun envelopeWithFallback(
        endpoint: String,
        params: List<Pair<String, String>>,
    ): JsonObject {
        val attemptLegacy = legacy
        val envelope = try {
            fetchEnvelope(endpoint, params, attemptLegacy)
        } catch (refused: TokenAuthUnsupported) {
            if (attemptLegacy || authMode != SubsonicAuthMode.AUTO) {
                throw SubsonicException(refused.message ?: "This server does not support token authentication")
            }
            legacy = true
            fetchEnvelope(endpoint, params, useLegacy = true)
        }
        // The form that answered is the form this client will sign every URL
        // with from here on.
        authNegotiated = true
        return envelope
    }

    /** Fetches, unwraps and validates the envelope: status, errors, JSON-ness. */
    private suspend fun fetchEnvelope(
        endpoint: String,
        params: List<Pair<String, String>>,
        useLegacy: Boolean,
    ): JsonObject {
        val url = urlFor(endpoint, params, useLegacy, salt = null)
        val body = execute(url)
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val response = root?.get("subsonic-response") as? JsonObject
        if (response == null) {
            val answered = root != null
            throw if (answered) {
                SubsonicException("That address answered, but not as a Subsonic server")
            } else {
                SubsonicUnavailable("That address answered, but not with JSON")
            }
        }
        val status = response.string("status")
        if (status != null && status != "ok") {
            val error = response["error"] as? JsonObject
            val code = error?.get("code")?.asInt() ?: UNKNOWN_ERROR
            val message = error?.string("message")?.takeIf { it.isNotBlank() } ?: defaultMessage(code)
            // 41 is the one refusal that is not final: it says "try the other
            // form", and [envelopeWithFallback] is what does.
            if (code == TOKEN_AUTH_NOT_SUPPORTED) throw TokenAuthUnsupported(message)
            throw errorFor(code, message)
        }
        return response
    }

    private fun <T> decode(
        envelope: JsonObject,
        deserializer: DeserializationStrategy<T>,
        endpoint: String,
    ): T = runCatching { json.decodeFromJsonElement(deserializer, envelope) }
        .getOrElse { failure ->
            if (failure is CancellationException) throw failure
            TrackLog.w(TAG, "  ✗ subsonic $endpoint could not be read: ${failure.message}")
            throw SubsonicException("The server's answer could not be read")
        }

    /** One GET, with a call-level timeout and this app's identity on the wire. */
    private suspend fun execute(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", CLIENT_NAME)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful ->
                        response.body?.string()?.takeIf { it.isNotBlank() }
                            ?: throw SubsonicUnavailable("The server answered with nothing")
                    response.code == 401 || response.code == 403 ->
                        throw SubsonicException("The server refused those credentials")
                    response.code >= 500 -> throw SubsonicUnavailable("HTTP ${response.code}")
                    else -> throw SubsonicException("HTTP ${response.code}")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            TrackLog.w(TAG, "  ✗ subsonic call failed ${redact(url)}: ${failure.message}")
            throw SubsonicUnavailable(failure.message ?: "Could not reach the server")
        }
    }

    private fun urlFor(
        endpoint: String,
        params: List<Pair<String, String>>,
        useLegacy: Boolean,
        salt: String?,
    ): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw SubsonicException("That is not a usable server address")
        // Enforced here rather than only in the editor: every credential this
        // class sends travels in the URL, so a plain-HTTP address that was
        // never opted into must not reach the network from any path — an API
        // call, a stream, or a cover fetch.
        if (base.scheme == "http" && !allowInsecureHttp) {
            throw SubsonicException(INSECURE_HTTP_MESSAGE)
        }
        val auth = when {
            useLegacy -> SubsonicAuth.legacyParams(username, password)
            salt != null -> SubsonicAuth.tokenParams(username, password, salt)
            else -> SubsonicAuth.tokenParams(username, password)
        }
        val builder = base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
        (auth.map { it.key to it.value } + params).forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    companion object {
        private const val TAG = "Rizumu"

        const val CLIENT_NAME = SubsonicAuth.CLIENT_NAME

        /**
         * What a plain-HTTP address that was never opted into gets told.
         *
         * A constant because two surfaces say it: the gate itself, as a
         * rejection the probe shows on the Sources row, and the row's status
         * line when the config already makes the block a fact rather than
         * something only a network probe can discover.
         */
        const val INSECURE_HTTP_MESSAGE =
            "This server uses plain HTTP; enable insecure HTTP for it to allow that"

        /**
         * The shared client, with a ceiling on the whole call.
         *
         * Derived from [Http.client] rather than built beside it, so the
         * connection pool, dispatcher and DNS stay shared with the rest of the
         * app. The base client bounds each *read*, which a server that accepts
         * a connection and then dribbles a byte at a time never trips.
         */
        private val sharedClient: OkHttpClient by lazy {
            Http.client.newBuilder()
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // OkHttp follows redirects by itself, and a server reached at
                // an HTTPS address may hand back a Location on plain HTTP.
                // Following that would put the credentials on the wire in the
                // clear even though the address the user saved was secure.
                // The application interceptor sees the final URL after any
                // redirects, which is what makes the downgrade visible here.
                .addInterceptor { chain ->
                    val request = chain.request()
                    val response = chain.proceed(request)
                    if (request.url.isHttps && !response.request.url.isHttps) {
                        response.close()
                        throw IOException("Refusing a redirect from HTTPS to plain HTTP")
                    }
                    response
                }
                .build()
        }

        private const val CALL_TIMEOUT_SECONDS = 20L

        /** What `/getCoverArt` is asked for when the caller does not say. */
        const val DEFAULT_ART_SIZE = 600

        private const val MAX_SEARCH_RESULTS = 200

        /** The largest page any browse endpoint is asked for in one call. */
        private const val MAX_PAGE = 500

        /** Subsonic error code: the server does not accept token authentication. */
        const val TOKEN_AUTH_NOT_SUPPORTED = 41

        private const val UNKNOWN_ERROR = 0

        private const val GENERIC_MESSAGE = "The server refused the request"

        private fun defaultMessage(code: Int) = when (code) {
            30 -> "This server does not support Subsonic API ${SubsonicAuth.API_VERSION}"
            40 -> "Wrong username or password"
            41 -> "This server does not support token authentication"
            42 -> "This server does not support the authentication Rizumu tried"
            43 -> "This server wants a different kind of authentication"
            50 -> "Those credentials are not allowed to stream"
            60 -> "The server's trial period has expired"
            70 -> "Not held by this server"
            else -> GENERIC_MESSAGE
        }

        private fun errorFor(code: Int, message: String): Exception = when (code) {
            // 44 is "try again later" — the one failure here that is worth
            // retrying untouched, and so the one that must not be shown to the
            // user as a configuration problem.
            44 -> SubsonicUnavailable(message)
            70 -> SubsonicNotFound()
            else -> SubsonicException(message)
        }

        /**
         * A server address from whatever was typed.
         *
         * Both forms people paste mean the same server: `https://host` and
         * `https://host/rest` — the second is what a Subsonic client's URL box
         * often shows and what the API documentation spells endpoints after.
         * The base is what the `/rest/` segment is appended to, so stripping
         * one that is already there is what keeps `/rest/rest/ping`.
         */
        fun normalizeBase(raw: String): String {
            var trimmed = raw.trim().trimEnd('/')
            if (trimmed.endsWith("/rest", ignoreCase = true)) {
                trimmed = trimmed.dropLast("/rest".length).trimEnd('/')
            }
            return trimmed
        }

        /**
         * A URL with its query — and so its credentials — hidden.
         *
         * The query is the secret here: `t`/`s` or `p` travel in it on every
         * call and both are enough to authenticate. The host and path are what
         * make a line traceable, and neither is sensitive.
         */
        fun redact(url: String): String = url.toHttpUrlOrNull()
            ?.let { "${it.scheme}://${it.host}${it.encodedPath}" }
            ?: "***"
    }
}

// ── Envelope helpers ────────────────────────────────────────────────────

private fun JsonObject.string(key: String): String? = this[key].stringOrNull()

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull

/** `openSubsonic` is a boolean in the spec but has been seen as the string `"true"`. */
private fun JsonObject.flag(key: String): Boolean {
    val primitive = get(key) as? JsonPrimitive ?: return false
    primitive.booleanOrNull?.let { return it }
    return primitive.contentOrNull.equals("true", ignoreCase = true)
}

/**
 * The server answered error 41: token authentication is not supported. Not a
 * final failure — `SubsonicClient.envelopeWithFallback` turns it into a retry
 * with the legacy password form, which is the one thing this exception exists
 * to carry out of the fetch.
 */
private class TokenAuthUnsupported(message: String) : Exception(message)

/**
 * The server or its URL is wrong in a way trying again will not fix — a
 * refusal, a response that is not Subsonic at all, a credentials problem.
 * The sources screen shows this as a rejection.
 */
class SubsonicException(message: String) : Exception(message)

/**
 * The server is momentarily not answering: down, overloaded, a 5xx, a DNS
 * lookup that has not resolved yet. Worth keeping switched on and worth asking
 * again on the next track.
 */
class SubsonicUnavailable(message: String) : Exception(message)

/** The server answered, and does not hold what was asked for. A miss, not a fault. */
class SubsonicNotFound : Exception("Not held by this server")
