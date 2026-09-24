# Fixes for `reviews.md`

Applied at `HEAD` (`4d76127`), in descending review order. Issues that later commits
had already resolved at HEAD are marked **already fixed** and were verified rather
than re-changed. Builds: `assembleProdRelease` and `:app:testProdDebugUnitTest`
(612 tests, only the 3 pre-existing failures: live Genius test, 2 localization tests).

## 34 — `4fd4d71` native Subsonic support

- **Critical — plaintext password fallback.** `SourceRegistry.init` no longer treats
  the unencrypted fallback store as equivalent. When the encrypted store cannot be
  initialised it sets `encryptedAtRest = false`, and `publish` strips `password` from
  what it writes. On init it also rewrites a plain store that already holds passwords,
  and migrates plain-store sources into the encrypted store when encryption becomes
  available (clearing the plain copy). (`data/sources/SourceRegistry.kt`)
- **High — cold-start direct URLs never negotiated legacy auth.** Added
  `SubsonicClient.ensureAuthNegotiated()`, a one-time ping before the first
  `streamUrl`/`coverArtUrl`; `SubsonicSource.stream()` calls it before signing the URL.
  (`data/subsonic/SubsonicClient.kt`, `data/sources/SubsonicSource.kt`)
- **Required — server card actions fell through to YouTube.** `collectSongs` now parses
  `srcb:` ids and loads the server page through `serverBrowseSongs` (artist pages keep
  their top-songs policy) instead of calling `YtMusicRepository.allSongs`.
  (`ui/MainViewModel.kt`)
- **Required — playlist rows unreachable.** Verified already fixed at HEAD
  (`SongActionsSheet` gates ratings and playlist rows on `youtubeRated || serverRated`,
  and `MainActivity` wires the server picker); the removal row is now additionally
  ownership-gated (see 33).
- **Required — playlist writes not capability/result-aware.** `ServerPlaylist` gained
  `canEdit` (server `owner` vs configured account; blank owner = own playlist).
  Rename/delete are offered only once `resolveServerPlaylistOwnership` says yes, mutate
  the page only on success, and surface failures via `serverActionError` → toast.
  (`data/sources/ServerLibrary.kt`, `SubsonicSource.kt`, `ui/MainViewModel.kt`,
  `MainActivity.kt`)

## 33 — `b171c22` rename form for server playlists

- **Required — failed rename shown as success.** `renameServerPlaylist` updates the page
  title only in `onSuccess`; failures set `serverActionError` and leave the old title.
- **Required — not ownership-aware.** Same `canEdit`/`resolveServerPlaylistOwnership`
  gate as 34; the ViewModel also refuses an explicit `false`.

## 32 — `ded8300` server scrobbling and lyrics

- **Required — reporting gated by Last.fm.** **Already fixed** at HEAD by `d8edcee`
  (`ScrobbleManager.shouldRun`, independent `useLastFm`, `updateNowPlaying` always calls
  the server reporter). Verified in `PlaybackService` and `ScrobbleManager`.
- **Required — BiniLyrics preflight for server tracks.** `LyricsRepository.lyrics()`
  skips `identify()` when the track is server-backed and `SERVER` is enabled, so no
  third-party contact is made to improve a lookup the first-party source answers.
  (`data/lyrics/LyricsRepository.kt`)

## 31 — `48d5e14` Subsonic documentation

- **Required — play-reporting claim.** **Already fixed** at HEAD by `d8edcee`; the claim
  is true. `docs/SUBSONIC.md` was still updated for the HTTP opt-in, credential
  fail-closed behaviour, stars support, and the album/artist-star limitation.

## 30 — `8e9d5d8` cleartext traffic

- **High — app-wide cleartext.** Added `SourceConfig.allowInsecureHttp` (default off) and
  a `SubsonicClient` gate that refuses any request to an `http://` address without it.
  The editor shows an explicit **Allow plain HTTP** row (blocked by default, warning,
  Save/Test disabled until accepted). An HTTPS→HTTP redirect is refused by an OkHttp
  interceptor. Manifest cleartext stays permitted at platform level with a comment
  explaining that the app enforces the per-source opt-in.
  (`SourceRegistry.kt`, `SubsonicClient.kt`, `SourcesScreen.kt`, `AccountAlerts.kt`,
  `strings.xml`, `AndroidManifest.xml`)

## 27 — `20cf301` server home/library

- **Required — server cards typed `OTHER`.** `browseTypeOf` parses `ServerBrowseRef`
  first, so server playlists are recorded as playlists for download metadata.
- **Required — Playlists “Show all” opened YouTube creation.** **Already fixed** at HEAD
  by `b2fa4f9` (`!serverMode` guard); verified.
- **Required — playlist writes left pages stale.** Successful create/add/remove/rename/
  delete now call `refreshServerPagesFor`, which reloads the primary Home/Library pages
  in place; the open Show-all grid re-derives its shelf from the reloaded page.
- **Required — edits with same id/baseUrl didn't reload.** `SourceConfig.fingerprint`
  (all fields, password hashed) replaced `id@baseUrl` in `serverKey`, in both
  `MainViewModel` and `MainActivity`.
- **Required — stale refresh could publish over a newer server.** Home/Library loads now
  carry a generation, cancel the previous job, and check the generation before every
  publish and before clearing loading state.
- **Required — returning to a tab repeated its load.** Verified already idempotent at
  HEAD; the key checks remain and now use the full fingerprint.
- **Optional — unconfigured Library showed Retry.** The Library tab now shows the
  Add-server empty state when no server is configured, same as Home.

## 26 — `25c5358` hide Google surfaces in server mode

- **Required — player could reopen Explore.** `PlaybackSourceType.EXPLORE` maps to Home
  in server mode.
- **Required — Listen Together could still open.** The incoming-invite effects and the
  player callback are guarded in server mode; an invite is consumed without opening the
  surface.

## 20 — `9aff12e` rebrand

- **Required — legacy downloads excluded from duplicate check.** `DownloadStore.existing`
  now searches `Music/Rizumu` and `Music/BitChord` (MediaStore query and pre-Q file
  lookup); new downloads still write to `Music/Rizumu`.

## 19 / 18 — README

- Removed the dead `[**Support**](#support)` navigation link (19) and the unmatched
  closing `</div>` after the disclaimer (18). The unmatched opening wrapper from 19 was
  already gone at HEAD; README now has balanced wrappers.

## 17 — `f9282de` server tab refetching

- **Required — load key ignored config edits.** Fixed by `fingerprint` (see 27).
- **Required — switch during in-flight load dropped replacement.** Fixed by the
  generation/cancel logic (see 27).
- **Required — server Library pull not reflected in top bar.** `topBarRefreshing` now
  selects `serverLibraryRefreshing`/`serverHomeRefreshing` in server mode.

## 15 — `be53ac4` listening dashboard

- **Required — recently played cards dropped server metadata.** `ShelfItem` gained an
  optional `song`; `Song.toShelfItem` and `TrackEntry.toShelfItem` carry the full row.
  `serverCardClick` and `shelfSong` use it, so playback, queue source and
  Open album/artist keep the server ids, album name and genre.

## 14 — `981c889` genre/decade covers

- **Required — null into ConcurrentHashMap.** **Already fixed** at HEAD by `634903f`
  (`ServerArtworkCache` blank sentinel); verified.
- **Required — artwork cache not scoped to config.** `ServerArtworkCache.clear(prefix)`
  and the ViewModel's config collector clear covers and in-flight markers when a
  server's fingerprint changes.

## 11 — `8f909b9` server likes

- Search seeding (11a) and short Liked shelves (11b): **already fixed** at HEAD by
  `054b824` and `45ee99b`; verified.
- **Required — lists patched in both directions / notification writes.** `ServerLikeState`
  now emits a `changes` flow from `set` (seeds don't emit); `MainViewModel` collects it
  and patches the Liked row and page (insert or remove, empty state, subtitle) no matter
  where the tap came from.
- **Required — rapid writes out of order.** New shared `ServerStarQueue` serialises per
  track; each queued write reads the latest desired state, and rollback happens only if
  no newer tap arrived. Used by both the ViewModel and the notification.
- **Required — notification ignored server stars.** The custom-layout collector combines
  `LikeState.overrides` and `ServerLikeState.starred`.
- **Required — Auto ignored server-primary.** The folder-resolution path now makes the
  same primary-library decision as the browse callback.
- **Required — Auto cache not invalidated.** Collecting `ServerLikeState.changes` clears
  the cached starred snapshot; the cache key is the config fingerprint.
- **Required — stale star state on source edits.** `ServerLikeState.forget(configId)`
  drops that server's entries when its fingerprint changes.
- **Required — docs contradicted implementation.** `docs/SUBSONIC.md` now lists stars as
  supported and drops the stale “heart is hidden” limitation.

## 9 — `8ce57de` liked lists and write serialisation

- **Required — cancellation didn't serialise writes.** Fixed by `ServerStarQueue` (see 11).
- **Required — unknown liked-page error became a false collection.** `patchServerLikedLists`
  converts only `server_liked_empty` to an empty list; any other error page is left as-is.

## 7 — `5514a27` genre affinity

- **Required — Replay rebuild dropped genre.** `MergedBucket.toSummary` copies `genre`
  into the rebuilt `Song`; covered by the new `ReplayGenreTest` (also pins album/artist
  ids).

## 5 — `256d723` genre through the player item

- **Required — cold-start queue dropped genre.** `StoredTrack` gained nullable
  `genre`/`albumId`/`artistId`, mapped both ways (older snapshots still decode).

## 4 — `0874d7e` Show-all search

- **Required — query keyed by display title.** `HomeShelf.shelfIdentity()` (browse id or
  completion identity plus title) replaces `shelf.title` as the `rememberSaveable` key.

## 3 — `9a309a7` shelf completion

- **Required — unbounded YouTube walk.** `completeLibraryShelf` now has a
  page/item budget (200 pages / 10,000 items) and returns whether the list is whole.
- **Required — shared state without generation check.** Completion carries a generation;
  stale appends, incomplete markers and the `finally` clear are all generation-checked.
- **Required — failed walk shown as exhaustive empty search.** `shelfCompletionIncomplete`
  is exposed; the grid shows “Couldn't load the whole list” instead of “Nothing here
  matches” over a partial list.
- **Optional — preview pages re-fetched.** `itemsPaged` remembers the bounded load's
  continuation; `completeLibraryShelf` resumes from it and seeds dedup with the row's
  cards.
- **Required — positional genre drop.** The refreshed genre list is appended whole and
  deduped by browse key instead of `drop(shelf.items.size)`.

## 1, 2, 6, 8, 10, 12, 13, 16, 21–25, 28, 29 — approved

No changes required.

## Tests added/updated

- `SubsonicClientTest`: HTTP opt-in refusal, direct-URL auth negotiation.
- `SubsonicSourceTest`/`SubsonicClientTest`: MockWebServer configs set
  `allowInsecureHttp` (test harness only).
- `SubsonicSourceTest`: `blockedByHttpPolicy` truth table, blocked-server
  health names the refusal and never reaches the wire.
- `ServerStarQueueTest`: rapid toggle ordering, refusal rollback.
- `ServerLikeStateTest`: per-server `forget`.
- `ReplayGenreTest`: Replay rebuild keeps server metadata.
- `LibraryShelfCompletionTest`: budget, partial result, start token, known keys,
  resume token advanced by a budget-limited walk.

## Follow-up review fixes

- **Stuck pull-to-refresh indicator.** `loadServerHome`/`loadServerLibrary`
  cleared `_server*Refreshing` only when the finishing job was itself the pull,
  so a background load that superseded a pull left the indicator on. Both now
  clear it in the current generation's `finally` regardless of who started it.
  The write-only `serverHomeLoading`/`serverLibraryLoading` fields were removed.
- **Resume token never advanced.** A `completeLibraryShelf` walk that stopped on
  its budget left the map holding the token it started from, so reopening the
  grid re-walked the same pages and could never reach the tail. The budget exit
  now stores the token for the next unfetched page.
- **Config-change race.** The `SourceRegistry.configs` collector dropped its
  first emission; a publish landing between seeding `previous` and collecting
  was swallowed and its caches never cleared. It now compares the first
  emission (a no-op when nothing changed) instead of dropping it.
- **Blocked HTTP server discoverability.** `SourceConfig.blockedByHttpPolicy`
  makes the plain-HTTP refusal a fact of the stored config, the Sources row
  shows it (in error colour) before and independently of the probe, and the
  message is now the shared `SubsonicClient.INSECURE_HTTP_MESSAGE` constant so
  the gate and the row say the same thing. Note the review's stated symptom
  ("search returns no results") does not reproduce at HEAD: MainViewModel's
  `sourceResults` is dead code and the search pipeline is YouTube-only; the
  surfaces that do use a server already showed the refusal.
- **Settings unreachable in server mode.** Hiding `TopBarAccountButton` also
  hid the only route into Settings (YouTube mode reaches it via avatar →
  account selector → Settings), so the plain-HTTP error told the user to
  enable a setting they could not reach. A gear now takes the avatar's place
  at the root of any server-mode tab, opening Settings directly; sources,
  integrations and the rest are reachable again.
