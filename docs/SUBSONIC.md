# Your own music server (Subsonic / OpenSubsonic)

BitChord can stream from a Subsonic-compatible server — **Navidrome**,
Airsonic, Gonic, Ampache, Jellyfin's Subsonic endpoint, and anything else that
speaks Subsonic API 1.16.1 — and can be built around it entirely. Your files
are streamed directly from your server; nothing is uploaded anywhere, and no
third-party service is involved.

## Using the server as the main app

The first run asks which library BitChord is built around:

- **My music server** — Home becomes your server's home (random tracks, newest
  releases, playlists, artists, starred), Library becomes shelves of your own
  library, and there is no Google account anywhere in the UI: no account
  button, no sign-in prompts, no save/subscribe, no YouTube playlists or
  ratings. Last.fm, ListenBrainz and Discord remain, being accounts of their
  own. YouTube Music is still there as a *fallback*: it fills the gaps in
  search results and plays anything your server does not hold.
- **YouTube Music** — the app as it was, with a configured server still
  available as a source.

Switch any time from **Settings → Primary library**; the choice takes effect
immediately, and switching back to YouTube needs no re-login (an existing
Google session is kept, just hidden while the server is primary). If you pick
the server with none configured yet, the server editor opens ready to take
one.


## Setting it up

1. **Settings → Sources → Add music server**.
2. Fill in:
   - **Address** — `https://music.example.com` or `http://192.168.1.10:4533`.
     Both that and `.../rest` are accepted; a reverse proxy sub-path works
     too. Plain HTTP is allowed (a LAN or Tailscale address usually is one),
     but on an untrusted network put a TLS proxy in front — over HTTP the
     account token and the audio itself travel unencrypted.
   - **Username** and **password** — the same account you use with any other
     Subsonic client. A read-only account is enough unless you want to create
     or edit playlists from BitChord.
   - **Stream quality** — see below.
3. Tap **Test** (optional — a server that is asleep can still be saved) and
   **Save**. The row shows the server's name, version and whether it supports
   OpenSubsonic.
4. Tap **Browse** on the server's row to open its library.

Credentials are stored in the app's encrypted preferences, alongside every
other secret. Authentication uses Subsonic's token scheme
(`t = md5(password + salt)`), so the password itself is not sent on each
request; if a server does not support token auth, BitChord falls back to the
legacy password parameter automatically for that server.

## Stream quality

The choice is per server and standing — it does not change with the network —
because the people who run their own server usually want the file they stored.
The per-network quality ceiling still applies to YouTube, JioSaavn and addons.

| Mode | What is asked for |
|---|---|
| **Original (raw)** *(default)* | The file as stored, bit-exact. Ignores the mobile-data ceiling **for this server** — the note under the picker says so. |
| **Match network** | Original on Wi-Fi and High; 256 kbps on Medium, 64 kbps on Low. |
| **320 / 256 / 192 / 128 / 96 / 64 kbps** | A transcode to that bitrate. |

A bitrate the app *names* — the Low rung, or a Standard-quality download — is
never exceeded, whatever the mode. A **Lossless** download always asks for the
original file, whatever the mode.

## What is supported

| Feature | Notes |
|---|---|
| Search | Songs, from the search screen, alongside the other sources |
| Streaming | Original files and server-side transcodes |
| Cover art | Fetched from the server |
| Browse | Artists, albums (newest), random tracks, playlists and starred items on the server's home page; artist and album pages |
| Playlists | Read, play, create, add to, remove from, rename and delete |
| Play reporting | Now-playing and finished plays are sent to the server, in addition to Last.fm / ListenBrainz. No Last.fm account is needed for this: if the server itself scrobbles to Last.fm or ListenBrainz (Navidrome's per-user settings), it forwards them, so server tracks reach ListenBrainz without a token in BitChord. |
| Lyrics | OpenSubsonic structured lyrics (with timings), falling back to the server's own tag reader |
| Downloads & offline | The normal BitChord downloader; lossless keeps the original file |
| Multiple servers | Each is its own source, tried in the order shown on the Sources screen |

## What is not supported yet

- **Stars and ratings** — the heart is hidden for server tracks rather than
  pretending to rate them on YouTube. Server-side stars/ratings are a planned
  addition.
- **Play queue sync** (`getPlayQueue`/`savePlayQueue`) and OpenSubsonic
  `reportPlayback` — the legacy `scrobble` call is used instead, which every
  server understands.
- **Server administration** — scanning, users, shares, podcasts, internet
  radio and folder browsing are out of scope.

## Design notes

- A server is a `SourceKind.SUBSONIC` source with its own `SubsonicClient`
  (`data/subsonic/`) and `SubsonicSource` (`data/sources/`). It is a
  `MusicSource` like any other, plus two capability interfaces:
  `ServerLibrary` for browsing and playlist writes, and `PlaybackReporter`
  for play reporting. Sources that cannot browse simply do not implement them.
- Server pages travel through the same detail stack as YouTube pages, using
  namespaced ids (`srcb:{config}::{kind}::{id}`) so a server's album page and
  a YouTube album page are the same kind of object to the navigation, the
  back stack and the long-press menus.
- Requests share the app's one OkHttp client, so DNS, connection pooling and
  the data-usage instrumentation stay in one place. Credentials never appear
  in logs (`SubsonicClient.redact`).
