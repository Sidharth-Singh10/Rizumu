package com.music.rizumu.data.subsonic

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * The two ways a Subsonic server can be told who is calling, and the constants
 * every request carries.
 *
 * Token auth is the default worth using: the password itself stays on the
 * device and each request carries a throwaway salt with an MD5 of the password
 * and that salt. The legacy form sends the password (hex-encoded, but fully
 * reversible) and exists only because not every server has caught up — see
 * [SubsonicAuthMode.AUTO][com.music.rizumu.data.sources.SubsonicAuthMode],
 * which is what actually decides between them at call time.
 *
 * Logging note: [tokenParams] and [legacyParams] return credentials. Nothing
 * here logs, and [SubsonicClient.redact] is what keeps them out of the lines
 * that do.
 */
internal object SubsonicAuth {

    /**
     * The API version every request asks for.
     *
     * Navidrome implements Subsonic 1.16.1 and that is the newest version the
     * protocol has, so asking for anything lower buys nothing and asking for
     * more is a version no server can answer. OpenSubsonic features are not
     * negotiated through this number — they are detected by the `openSubsonic`
     * flag the server puts on every response.
     */
    const val API_VERSION = "1.16.1"

    /** Identifies this app to the server, per the protocol's `c` parameter. */
    const val CLIENT_NAME = "Rizumu"

    private const val SALT_LENGTH = 12
    private const val SALT_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private val random = SecureRandom()

    /**
     * `u`, `s`, `t` plus the constants every request carries.
     *
     * A salt may be supplied — see `SubsonicClient.streamUrl`, which reuses one
     * for the URLs handed to the player and the image loader so those stay
     * stable for a run and so stay cacheable — and defaults to a fresh random
     * one, which is what API calls use.
     */
    fun tokenParams(username: String, password: String, salt: String = randomSalt()): Map<String, String> =
        common(username) + mapOf(
            "s" to salt,
            "t" to md5Hex(password + salt),
        )

    /** `u`, `p=enc:<hex>` plus the constants every request carries. */
    fun legacyParams(username: String, password: String): Map<String, String> =
        common(username) + mapOf("p" to "enc:" + hex(password.toByteArray(Charsets.UTF_8)))

    private fun common(username: String) = mapOf(
        "u" to username,
        "v" to API_VERSION,
        "c" to CLIENT_NAME,
        "f" to "json",
    )

    /**
     * A fresh salt per request, as the protocol intends.
     *
     * Reusing one would make every token for a password identical, which is
     * exactly the property the scheme exists to avoid — a captured token would
     * stay valid for as long as the password did.
     */
    fun randomSalt(): String = buildString(SALT_LENGTH) {
        repeat(SALT_LENGTH) { append(SALT_ALPHABET[random.nextInt(SALT_ALPHABET.length)]) }
    }

    /** Lowercase hex MD5, which is the spelling the protocol's `t` parameter uses. */
    fun md5Hex(text: String): String =
        hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"

    /** Capitalises a server-reported type so it reads as a name: `navidrome` → `Navidrome`. */
    fun displayType(raw: String): String =
        raw.trim().takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercase(Locale.ROOT) }
            .orEmpty()
}
