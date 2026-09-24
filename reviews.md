# Commit reviews

Scope: all 34 commits on `main` whose author and committer are **Sidharth-Singh10**, reviewed against each commit's first parent, newest to oldest. Resource XML in the changed commits parses successfully and `git show --check` found no whitespace errors. With the harness JDK 17 configured, `assembleDevDebug` succeeds and `testDevDebugUnitTest` ran 603 tests against aggregate `HEAD`: 600 passed and 3 failed (one live Genius network test and two localization tests). The localization failures are in pre-existing upstream resources/test assumptions, and the live Genius test is network-dependent; none is attributed to a reviewed commit. Historical commits were reviewed statically rather than rebuilt individually.

## 1. `4d761274c7017c4de7fb31e3e687daca545a1978` — `chore(repo): drop the unused logo copy and ignore local brand art`

**Scope:** Deletes the unreferenced root `Logo.png` and ignores `/new_logo.png`.

**Findings:** No actionable correctness, security, or architecture issues. A tree-wide reference check confirms that the deleted image is not referenced by the README, build files, or resources remaining at this commit.

**Verdict:** **Approve.**

## 2. `397df5df044e394a0aaf180d44e9e845fa9f139a` — `fix(library): search the whole catalogue on Your artists and Your albums`

**Scope:** Adds completion for the `Your artists` and `Your albums` shelves and changes album paging to start at offset zero so ranked previews do not create gaps in the catalogue.

**Findings:** No high-confidence issues. The new artist completion deduplicates against the preview, and starting the album walk at zero is the correct behavior for both the alphabetical shelf and the play-count-ranked selection.

**Verdict:** **Approve.**

## 3. `9a309a7fd0119d2d1a6750c2ce4a1f38395020bd` — `feat(library): load a shelf's whole list when its grid opens`

**Scope:** Adds background completion for server album/genre shelves and continuation walking for YouTube shelves, exposing the progress state to the Show-all grid.

**Findings:**

- **Required — the YouTube completion walk has no resource bound.** `app/src/main/java/com/music/rizumu/data/YtMusicRepository.kt:698-723` loops until the endpoint returns no continuation or repeats a token. A large feed can therefore generate a very long sequence of network requests and an unbounded `shelfExtras` list, while a malformed endpoint that returns unique tokens can keep the grid loading indefinitely. The old library walk deliberately capped pages; carry a high page/item budget (and expose a partial-result state) rather than removing every bound.

- **Required — completion state is shared without a generation check.** `app/src/main/java/com/music/rizumu/ui/MainViewModel.kt:3184-3257` cancels the old job but keeps one global `shelfExtras`/loading state. If shelf A's request completes after the user opens shelf B, A can append cards to B's grid, and A's `finally` can clear B's loading indicator. This is possible because the HTTP call is blocking inside the coroutine and cancellation is not a completion fence. Tag each job with a generation and ignore appends/finally blocks from stale generations.

- **Required — a failed continuation is presented as an exhaustive empty search.** `MainViewModel.kt:3233-3240` turns completion failure into `completing = false`, after which `LibraryScreen.kt:514-525` shows “Nothing here matches …” for any empty match, even when later pages were never fetched. Track completion success separately (or expose a partial/error state) and do not claim no matches after a failed walk.

- **Optional — completion re-fetches the preview pages.** `YtMusicRepository.completeLibraryShelf()` starts again with `token = null` (`:698-710`) even though the initial `itemsPaged()` load already followed up to ten pages. Opening Show all can issue those same ten requests again before reaching the remainder. Pass the saved continuation boundary and seed deduplication with the preview items.

- **Required — genre completion uses a positional drop.** `MainViewModel.kt:3213-3218` does `genres.drop(shelf.items.size)`. If affinity changes or the server order changes between opening the row and opening the grid, a newly promoted genre can fall inside the dropped prefix and never become searchable. Append the refreshed full list and deduplicate by stable browse key instead of using the preview count as an offset.

**Verdict:** **Request changes.**

## 4. `0874d7ec9628132753ab010dd977dd53da623859` — `feat(library): search the Show all grid`

**Scope:** Adds a search field and case-insensitive title/subtitle filtering to the Show-all grid, with tests for matching and order preservation.

**Findings:**

- **Required — the saved query is keyed by a non-unique display title.** `app/src/main/java/com/music/rizumu/ui/screens/LibraryScreen.kt:465-467` uses `rememberSaveable(shelf.title)`. Opening a server `Albums` grid, typing a query, and then opening the YouTube `Albums` grid (or another source's shelf with the same localized title) keeps the old query because the title is identical, so the new grid starts incorrectly filtered. Key the state by a stable shelf identity (for example, the completion/browse identity) or explicitly reset it when the shelf object changes.

**Verdict:** **Request changes.**

## 5. `256d723461b91fc08c4b4005068d4cf030827e98` — `fix(playback): carry the track's genre through the player's item`

**Scope:** Serializes `Song.genre` into Media3 extras and restores it in `MediaItem.toSong()`.

**Findings:**

- **Required — cold-start queue persistence still drops the new genre.** `app/src/main/java/com/music/rizumu/playback/LastPlayed.kt:132-187` has no `genre` field in `StoredTrack`, while `PlayerConnection.kt:278-302` now restores genre from the live MediaItem. A queue restored after process death therefore comes back with `genre = null`; the listening sampler records subsequent plays without the server tag, so the Play-tab genre ranking cannot learn them. Add a backward-compatible nullable field to `StoredTrack` and map it in both directions.

**Verdict:** **Request changes.** The live MediaItem round trip is correct, but the persisted queue path is part of the same playback state contract.

## 6. `b554ddcfd5b1cbf500636bac22fa17c59f3ad742` — `feat(play-tab): order the decades row newest first`

**Scope:** Reverses the fixed decade list so 2020–2029 is first.

**Findings:** No actionable issues. The browse IDs remain unchanged, and the ordering does not alter the range or page lookup.

**Verdict:** **Approve.**

## 7. `5514a27723706dc5bb1ad67012e55af14fae655d` — `feat(play-tab): order the genres row by what you listen to`

**Scope:** Carries the Subsonic genre into `Song`, persists it in listening history, and ranks the server's genre shelf by normalized listening time.

**Findings:**

- **Required — the new genre is dropped when Replay entries are rebuilt.** `app/src/main/java/com/music/rizumu/data/stats/ListeningStats.kt:549-558` constructs `RankedSong` without copying `TrackEntry.genre`. A user who plays a track from the Replay charts records the next play with `genre = null`, so the new affinity ranking cannot learn that play or backfill the track's tag. Copy the field into the ranked `Song` and cover the Replay path with a round-trip test.

**Verdict:** **Request changes.** The aggregation and normalization tests are useful, but they do not cover the Replay reconstruction path.

## 8. `45ee99b21038a20576f90473af6f56e6feffc2fe` — `fix(play): offer Show all for a shelf that names its own page`

**Scope:** Always exposes `Show all` when a shelf has a `moreBrowseId`, so short Liked songs rows can still reach the collection page.

**Findings:** No actionable issues. The condition is intentionally limited to shelves that actually name a page; ordinary preview shelves still wait until the row is long enough to need the affordance.

**Verdict:** **Approve.**

## 9. `8ce57de706bb7841e97d0e79c6ef19741fee0612` — `fix(likes): correct liked lists in place and serialize star writes`

**Scope:** Patches the visible Liked shelf/page after writes and attempts to supersede an in-flight write when a user taps again.

**Findings:**

- **Required — cancellation does not serialize the actual HTTP writes.** `app/src/main/java/com/music/rizumu/ui/MainViewModel.kt:604-625` cancels the previous `Job`, but `SubsonicClient` performs a blocking OkHttp `execute()` inside a coroutine. Cancellation is not a server-side ordering guarantee: a first `star` request can already be in flight while the replacement `unstar` request is sent, and the first request can complete last. The UI then reflects the optimistic last tap while the server remains in the earlier state. Use a per-track mutex/serialized write queue (or cancel the underlying call and verify completion) and add a rapid-toggle regression test.

- **Required — an unknown liked-page error is converted into a false collection.** `app/src/main/java/com/music/rizumu/ui/MainViewModel.kt:664-680` treats every `UiState.Error` as an empty list. If the page failed because of a network or parsing error, a subsequent star/unstar replaces that unknown state with a one-song list or “No liked songs yet.” Only convert the specific `server_liked_empty` state to an empty collection; preserve other errors.

**Verdict:** **Request changes.**

## 10. `054b824354ff5e6feb9f72779ef12839ade28823` — `fix(likes): seed server star state from search results`

**Scope:** Routes Subsonic search rows through the same `toSongs()` path used by browse results and adds a starred/unstarred search regression test.

**Findings:** No actionable issues. This directly closes the search-path state-seeding gap while preserving the session's explicit unstar precedence.

**Verdict:** **Approve.**

## 11. `8f909b9e73c662b27ee09093eb884c2fb7971fcd` — `let server songs be liked, with a Liked songs row on the Play tab`

**Scope:** Adds server star state and star/unstar writes across the player, sheet, notification, Play-tab shelf, liked collection page, and Android Auto.

**Findings:**

- **Required — search results bypass star-state seeding.** `SubsonicSource.search()` (`SubsonicSource.kt:116-135`) maps rows directly, while `toSongs()` (`:354-369`) seeds `ServerLikeState`. A server-starred search result therefore appears unliked, and tapping its heart can write `unstar`. Route search through `toSongs()` and add a regression test; the following `054b824` fixes this path.

- **Required — short Liked shelves cannot open their collection page.** `MainActivity.kt:1473-1484` relies on `moreBrowseId`, but `LibraryScreen.kt:413-420` hides Show all until a shelf has more than five cards. With one to five liked songs, the collection page and its queue/download actions are unreachable. A non-null `moreBrowseId` should always expose Show all; the following `45ee99b` fixes this.

- **Required — liked shelves/pages are not patched in both directions.** `MainViewModel.kt:590-600,613-631` removes rows on unstar but does not insert the first starred track; notification writes (`PlaybackService.kt:1853-1867`) do not update the ViewModel’s visible lists at all. Removing the final song also writes `UiState.Success(emptyList())` instead of the custom empty state and leaves the old subtitle count. Use one shared patch operation for both directions and notification/UI writes.

- **Required — rapid star writes can reach the server out of order.** `MainViewModel.kt:584-600` launches an independent request for every tap. A quick star/unstar sequence can complete in reverse order, leaving the server and optimistic UI disagreeing. Serialize writes per track, rethrow cancellation, and reconcile only the latest desired state; the following `8ce57de` attempts this fix.

- **Required — the notification layout does not observe server-star changes.** `PlaybackService.kt:1112-1116` collects only `LikeState.overrides`, although `notificationButtons()` reads `ServerLikeState` at `:1522-1523`. A star from the player or sheet updates state but leaves the notification heart stale. Combine both state flows.

- **Required — Android Auto folder resolution ignores server-primary mode.** `PlaybackService.kt:5795-5799` always resolves `MEDIA_LIKED_ID` through `cachedLikedSongs()`, while the browse callback selects `cachedServerLikedSongs()` at `:5433-5448`. A controller that resolves the folder itself can receive YouTube/empty data in server mode. Use the same primary-library decision in both paths.

- **Required — the Android Auto Liked-songs cache is not invalidated by a like write.** `PlaybackService.kt:304-357` caches server stars for 60 seconds, while the ViewModel and notification write paths do not update or clear it. After Auto has populated the cache, starring or unstarring a track can leave Auto showing the old list. Invalidate or update the cache on every server-star transition.

- **Required — source edits retain stale keyed star state.** `ServerLikeState.kt:24-47` keys state by stable track/config ID and `seedStarred()` never replaces an existing entry; `SourceRegistry.update()` preserves the config ID. Editing a server account can carry the old account’s state into the new one, and an old `false` can suppress a newly seeded `true`. Clear/reseed on config changes or include an account/configuration revision in the key.

- **Required — the feature documentation contradicts the implementation.** `docs/SUBSONIC.md:84-88` still says server stars are unsupported and the heart is hidden, although this commit adds server starring. Update the supported-feature and limitations sections.

**Verdict:** **Request changes.**

## 12. `634903fc2c5a38a4d33e989e8e3986cc199f19b5` — `fix the crash on the Play tab's first genre with no cover`

**Scope:** Replaces nullable `ConcurrentHashMap` entries with `ServerArtworkCache`, which uses a blank string for a known “no cover” result, and adds focused tests.

**Findings:** No actionable issues. This is a direct fix for the null-value write in the preceding artwork implementation, and the negative-path tests cover the original failure mode.

**Verdict:** **Approve.**

## 13. `6c5fec8d5afd43df7a6e2a7a7f32083d8ba12967` — `put the listener's own artists and albums in the server Library`

**Scope:** Adds `Your artists`/`Your albums` shelves, carries album artist IDs, derives a deduplicated artist row, and loads the Library shelves concurrently while making per-user lists best-effort.

**Findings:** No high-confidence correctness issues. The derivation deliberately skips rows without an artist ID rather than opening an ambiguous page, and the tests cover ordering, deduplication, fallback artwork, missing IDs, and limits.

**Verdict:** **Approve.**

## 14. `981c8891fb0aeb940ea8d79fa7fbf27b7e3c42bd` — `give the server Play tab's genre and decade cards covers`

**Scope:** Adds `byYear`/`byGenre` album queries, asynchronous cover resolution with negative-result caching, and a generic no-art fallback card.

**Findings:**

- **Required — a no-cover result writes `null` into `ConcurrentHashMap`.** `app/src/main/java/com/music/rizumu/ui/MainViewModel.kt:206-216,281-300` declares `ConcurrentHashMap<String, String?>` and executes `serverDiscoveryArtwork[browseId] = artwork`, where `artwork` is null whenever a genre/decade has no matching album/song cover or the server refuses the list. `ConcurrentHashMap` forbids null values, so the first such card throws in the artwork coroutine instead of being remembered as “asked, no cover.” Use a non-null sentinel/cache wrapper (as the following commit does) and add a test through the resolver, not only the request mapper.

- **Required — the artwork cache is not scoped to the edited server configuration.** The cache is keyed by browse ID (`MainViewModel.kt:2519-2520`), which contains only the stable config ID, kind, and row ID. Editing a source to another base URL/account preserves the key, so the dashboard can reuse the old server’s signed cover URL and suppress a fresh lookup. Include a configuration revision in the key or clear the cache and in-flight set when the source changes.

**Verdict:** **Request changes.**

## 15. `be53ac44e5079d888626db24bde97646974ebb87` — `turn the server Play tab into a listening dashboard`

**Scope:** Replaces the server home listing with shuffle, listening-history, catalog, genre, and decade shelves, plus server-aware card actions and browsing.

**Findings:**

- **Required — recently played cards discard the server navigation metadata.** `app/src/main/java/com/music/rizumu/ui/MainViewModel.kt:2334-2344` maps a stored `TrackEntry` to only title/artist/art/video ID, and `MainActivity.kt:1440-1454` builds a new `Song` with only those fields. The stored album/artist IDs (and genre/duration) are lost, so tapping Recently played can stream the track but its long-press Open album/Open artist actions have no server IDs to route, and the queue source is also reduced to `null`. Carry the full server `Song` metadata through the card or resolve the source row before constructing the queue item.

**Verdict:** **Request changes.**

## 16. `f38ca7337486fd23a3e328f8e20770485562d931` — `tidy`

**Scope:** Adds the `banner-screens.png` README image.

**Findings:** No actionable issues. The asset is referenced by the README and the change has no runtime impact.

**Verdict:** **Approve.**

## 17. `f9282de238dd10fd05c985b94c06f3de372914eb` — `stop the server tabs refetching on every switch`

**Scope:** Moves server-tab loading to feed effects, adds idempotent load keys, and adds pull-to-refresh for the server Library.

**Findings:**

- **Required — the load key does not represent the edited server configuration.** `app/src/main/java/com/music/rizumu/ui/MainViewModel.kt:2464-2465` uses only `id@baseUrl`. Editing the username, password, display label, or other fields leaves the key unchanged, so a successful page is treated as current and never reloads; the old server's data remains on screen. Include the relevant config fields (or the complete config) in the key.

- **Required — a server switch during an in-flight load drops the replacement request.** `onServerHomeShown()`/`onServerLibraryShown()` return while `server*Loading` is true (`:2409-2412`, `:2505-2508`). If the source changes during that request, the new effect returns, and the old coroutine later publishes its old page/key; no reload is queued. Cancel/replace the old job or record a generation and re-check the active key before publishing.

- **Required — server Library pull state is not connected to the top-bar progress state.** `MainActivity.kt:854-858` selects the ordinary `libraryPull` state, and `:2825-2826` reads generic `refreshing` rather than `serverLibraryRefreshing`. The Library screen receives its separate state, but the top-bar progress line does not reflect the server pull. Route both `currentPull` and the top-bar value through the server-specific states.

**Verdict:** **Request changes.**

## 18. `b722fb515917c1be38dfd9f0a5192411b7b585a2` — `Replace logo with banner and clean up README`

**Scope:** Replaces the README logo with the full-width banner and removes some wrapper markup.

**Findings:**

- **Required — the README contains an unmatched closing `</div>`.** The removed support-area wrappers leave the former wrapper’s closing tag after the disclaimer (`README.md:98-107` in this commit). The resulting HTML is structurally invalid and can misnest later sections in Markdown renderers. Remove the orphan closing tag or restore the corresponding wrapper.

**Verdict:** **Request changes** for the documentation structure; the image replacement itself is fine.

## 19. `42acbb6351ac0ce3e58f48c71a397bf56dd0bac7` — `Remove support section from README`

**Scope:** Removes the donation/support section.

**Findings:**

- **Required — the navigation retains a dead Support link.** `README.md:17` retains `[**Support**](#support)`, but this commit removes the only `id="support"` heading. The link now lands nowhere. Remove the navigation item or retain a replacement section.

- **Required — the removal leaves an unmatched opening wrapper.** The support section’s opening `<div>` remains at `README.md:102-105` without its matching close, and the disclaimer wrapper starts afterward. Remove the orphan opening tag so the README remains valid HTML.

**Verdict:** **Request changes** for the broken README link.

## 20. `9aff12eda6e4bd5756d65bce729e49e2c7185ce7` — `rebrand the app as Rizumu`

**Scope:** Renames the application/namespace, Java/Kotlin package tree, resources, URI scheme, preference/download names, JNI symbols/native library, documentation, and CI/release identifiers. It explicitly treats the application-ID change as a new app and retains old download/lyrics compatibility plus the existing Listen Together domain.

**Findings:**

- **Required — legacy downloads are displayed but excluded from the duplicate check.** `LocalMediaRepository` deliberately scans both `Music/Rizumu` and `Music/BitChord` (`app/src/main/java/com/music/rizumu/data/LocalMediaRepository.kt:91-96,135-143`), but `DownloadStore.existing()` only queries the new `FOLDER` (`app/src/main/java/com/music/rizumu/download/DownloadStore.kt:160-184,315-319`). After the rebrand, a track already stored under `Music/BitChord` is visible to the library yet re-downloading it can create a second copy under `Music/Rizumu` instead of adopting the existing file. Search/migrate both folders, and add a regression test for the post-upgrade duplicate case.

**Verdict:** **Request changes.** The package, native, resource, and JNI renames are internally consistent; the legacy-download compatibility gap is actionable. The aggregate build succeeds, but historical-SHA build validation was not run.

## 21. `4266ec9e9e7a3699054a18019294f9d7b342a9ea` — `clean up the adaptive foreground's edge keying`

**Scope:** Replaces four launcher/notification raster assets with edge-keyed variants.

**Findings:** No actionable code issues. The changed resources remain at the same names and are still referenced by the adaptive icon and notification call sites; there is no source-level behavior to regress here.

**Verdict:** **Approve.** Visual verification on launcher masks/OEM themes is still appropriate for asset-only changes.

## 22. `1a3d4ad4b84d665b408672b0b3b8dba47d57f5b0` — `replace every launcher icon, in-app mark and notification icon`

**Scope:** Replaces launcher, adaptive, in-app, notification, store, and Discord fallback artwork; removes the old vector/SVG sources and points the themed layer at a new monochrome asset.

**Findings:** No high-confidence issues. References to `ic_logo`, `ic_notification_logo`, and `ic_launcher_foreground` still resolve to the replacement raster resources, and the manifest/resource XML remains structurally valid.

**Verdict:** **Approve.** Device-level visual testing is still needed to check mask cropping and notification contrast.

## 23. `56aa851b50b0ec6d9df12b1aea41c26abcff7b9c` — `replace the logo and banner with the new artwork`

**Scope:** Replaces `Logo.png` and `Banner.png` and updates the README image labels.

**Findings:** No actionable issues. Both assets remain at their existing paths and the README references are updated consistently.

**Verdict:** **Approve.**

## 24. `4ca9485999f5ac049d8ce4bf85ea1e19658f9e56` — `credit BitChord in the readme`

**Scope:** Adds an upstream credit section describing BitChord and the fork's additions.

**Findings:** No actionable issues. The links and attribution are explicit, and the later rebrand intentionally retains this credit.

**Verdict:** **Approve.**

## 25. `b2fa4f9596cb9651a54e7b6dbb915866b56fbc5e` — `document the primary-library choice`

**Scope:** Documents server-primary mode and prevents the YouTube “New playlist” tile from appearing in that mode.

**Findings:** No high-confidence issues. The UI condition matches the documented account boundary, and the documentation change does not introduce a new runtime path.

**Verdict:** **Approve.**

## 26. `25c53589dce8d12a47316e1ad3eaf156e62c3837` — `hide the google surfaces when the server is primary`

**Scope:** Removes YouTube-only tabs and account/rating/playlist surfaces in server mode while preserving the stored Google session and independent scrobbling integrations.

**Findings:**

- **Required — player-origin navigation can reopen the hidden Explore tab.** `MainActivity.kt:695-702` removes Explore from `visibleTabs`, and the mode-change effect resets it only once (`MainActivity.kt:910-914`). A later player callback with `PlaybackSourceType.EXPLORE` still assigns `selectedTab = TAB_EXPLORE` (`MainActivity.kt:1932-1949`), so server mode can render the hidden Explore branch while the navigation bar coerces the selection to Home. Route Explore-origin playback through the visible-tab set or explicitly map it to Home in server mode.

- **Required — hidden Listen Together can still be opened by invite/player entry points.** Settings hides the row (`SettingsSheet.kt:400-417`), but the incoming-invite effect and the player's party callback unconditionally set `showListenTogether = true` (`MainActivity.kt:616-642,1968-1977`). A server-mode invite or player action can therefore open the Google/Listen Together surface despite the mode's stated account boundary. Guard these entry points or provide an explicit server-mode behavior.

**Verdict:** **Request changes.**

## 27. `20cf301fe589c2e845f79266fe008192d0dd4e78` — `make the server the home and library in server mode`

**Scope:** Routes Home and Library to server pages, adds server-library shelves, and makes collection actions load server data.

**Findings:**

- **Required — server cards are misclassified as `BrowseType.OTHER`.** `MainViewModel.kt:2239-2260` creates `srcb:` IDs, but `browseTypeOf()` (`MainViewModel.kt:2622-2629`) only recognizes local/YouTube prefixes. The download action then records a server playlist as `playlist = false` (`MainActivity.kt:3507-3519`), so downloaded-playlist metadata is lost. Parse `ServerBrowseKind` before the generic fallback.

- **Required — the server Library’s Playlists “Show all” page opens YouTube playlist creation.** The generic `library_show_all` route supplies `onNewPlaylist` whenever the shelf title is Playlists (`MainActivity.kt:2158-2171`) without checking `serverMode`. In server mode the tile therefore opens the YouTube picker instead of server playlist creation. Gate it by mode; the following `b2fa4f9` commit adds that guard.

- **Required — playlist writes leave the new server Home and Library pages stale.** `createServerPlaylist()` does not invalidate either page, `renameServerPlaylist()` updates only an open detail page, and `deleteServerPlaylist()` only removes that detail entry (`MainViewModel.kt:2288-2344`). After a successful create/rename/delete, the visible shelves and an open Show-all snapshot can still show the old playlist state. Invalidate and reload the owning page after successful writes.

- **Required — server edits with the same ID and base URL do not refresh the primary pages.** `MainActivity.kt:687-694` builds the effect key from only `id@baseUrl`, while the comment promises that an edited server reloads. Changing the username, password, label, or other config fields leaves the key unchanged, so the old page remains current. Include the full relevant configuration in the key or observe the config object directly.

- **Required — a stale refresh can publish over a newer server selection.** `MainViewModel.kt:2403-2430,2450-2484` launches independent Home/Library jobs and publishes their results without checking that the originating configuration is still current. Switching or disabling a server can clear the state, then an older request can restore the previous server's data. Use a complete configuration fingerprint plus a generation/cancellation check before publishing.

- **Required — returning to a server tab always repeats its load.** The tab-content `LaunchedEffect(serverKey)` calls refresh directly (`MainActivity.kt:2462-2464,2681`), so re-entering Home rerolls random content and re-entering Library repeats all shelf requests. Gate by the current content key and keep existing shelves visible while refreshing.

- **Optional — an unconfigured server Library shows only a misleading Retry action.** `refreshServerLibrary()` represents no complete server as `UiState.Error` (`MainViewModel.kt:2444-2448`), and `ServerLibraryScreen.kt:58-63` renders every error with Retry. There is no Add Server path from that state. Model it as a setup/empty state with an actionable configuration route.

**Verdict:** **Request changes.**

## 28. `8f5590adefb579696f266ca94ca14c24a7eb1a71` — `choose the primary library on first run`

**Scope:** Adds the persisted first-run chooser, Settings entry, and server-editor handoff when the server option is selected without a configured server.

**Findings:** No high-confidence issues. The null preference is deliberately distinct from either mode, and the chooser can be dismissed for the session without silently selecting a library.

**Verdict:** **Approve.**

## 29. `d8edceed8cf465125f5fd5195d1391fd041e86f8` — `report server plays without lastfm configured`

**Scope:** Makes the scrobble timer run when either Last.fm or a configured Subsonic source is available, gates Last.fm separately, and adds a pure gate test.

**Findings:** No actionable issues. Server now-playing remains independent of the Last.fm now-playing preference, and the service observes source configuration changes so the timer can be enabled or destroyed appropriately.

**Verdict:** **Approve.**

## 30. `8e9d5d8b2964fa4c902feb1e4dbf92270e181239` — `allowed cleartext so self-hosted servers work over http`

**Scope:** Changes the application-wide Android policy from `usesCleartextTraffic="false"` to `true` and documents the HTTP/TLS tradeoff.

**Findings:**

- **High — this enables reusable credential exposure for every HTTP server configuration, not just the address the user selected.** `app/src/main/AndroidManifest.xml:60-64` makes cleartext app-wide. On a shared or hostile network, a user who enters `http://music.home` sends the username and a reusable Subsonic token (or reversibly encoded password in legacy mode) in every request; an observer can replay the token and read the audio. The setting also permits cleartext redirects from otherwise HTTPS requests. Keep cleartext disabled by default and require an explicit per-source insecure-HTTP opt-in with a blocking warning, or at minimum prohibit legacy password auth over HTTP and prevent HTTPS-to-HTTP redirects.

**Verdict:** **Request changes.**

## 31. `48d5e141fd9b04204e929011d95b3aa52cb0a63d` — `documented subsonic server support`

**Scope:** Adds setup, quality, supported-feature, limitations, and architecture documentation for the Subsonic integration.

**Findings:**

- **Required — the documentation claims server play reporting before it works without Last.fm.** `docs/SUBSONIC.md:55` says now-playing and finished plays are sent to the server, but at this revision `PlaybackService` still constructs the scrobble timer only when Last.fm is enabled and fully configured. A Subsonic-only user therefore receives no reports. Land the gate fix first or qualify/remove the claim until the following `d8edcee` correction is included.

**Verdict:** **Request changes.**

## 32. `ded83001f8797bcb33858a5921efa3b50ab91375` — `scrobble to music servers and read their lyrics`

**Scope:** Adds server play reporting and a `SERVER` lyrics provider with structured-then-legacy fallback.

**Findings:**

- **Required — server reporting is still gated by Last.fm at this revision.** `app/src/main/java/com/music/bitchord/data/scrobbling/ScrobbleManager.kt:45-47,136-159` only enters the reporting path when the existing manager is created, and `PlaybackService.kt:4514-4519` still creates it only for a fully configured Last.fm account. In addition, `onSongStart()` only calls `updateNowPlaying()` when `useNowPlaying` is true, so disabling Last.fm now-playing also suppresses the new server now-playing call. Run the manager for either backend and gate each backend independently; the following `d8edcee` commit makes part of this correction.

- **Required — adding `SERVER` to the default provider order does not avoid the existing third-party preflight.** `LyricsRepository.lyrics()` still calls `identify()` whenever BiniLyrics is enabled before it starts the provider race (`LyricsRepository.kt:82-91,238-247`). For a server-backed track this contacts BiniLyrics even though the new provider is supposed to answer from the user's own server first. Skip name/ISRC identification for `SERVER`-eligible tracks, or make the server lookup a true first-party path.

**Verdict:** **Request changes.**

## 33. `b171c2271e1fe6bd76af135a17aad107ec00f74d` — `let the playlist rename form serve server playlists`

**Scope:** Generalizes the rename form to an initial string and makes the server playlist rename callback reachable.

**Findings:**

- **Required — a failed server rename is presented as successful.** `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:2403-2412` logs `updatePlaylist` failure and then unconditionally changes the open page title to the requested name. With the form now reachable for server playlists, an offline/rejected rename leaves the UI showing a name the server never accepted. Update the title only after a successful result and surface the error otherwise.

- **Required — the newly reachable form is not ownership-aware.** `getPlaylists` can return shared or public playlists, but the action path offers rename for every server playlist and carries no `owner`/allowed-management check. Do not expose a destructive write for a playlist the account cannot edit; carry the capability from the server response or handle the refusal without presenting a false local success.

**Verdict:** **Request changes.**

## 34. `4fd4d71f46e929c0831582902dfd512a7d201958` — `added native subsonic server support`

**Scope:** Introduces the Subsonic protocol client/models/auth, encrypted source configuration, source routing, server browsing and playlist writes, playback/cover URLs, lyrics/play-report APIs, quality selection, editor UI, and unit tests.

**Findings:**

- **Critical — Keystore failure silently downgrades passwords to plaintext storage.** `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt:108-123,285-308` falls back to `bitchord_sources_plain` when `EncryptedSharedPreferences` cannot initialize, then serializes `SourceConfig.password` into it. That contradicts the commit's encrypted-at-rest guarantee and makes credentials recoverable from device storage/backups. Fail closed or keep secrets in a non-downgrading secret store; do not persist credential-bearing configs in the fallback.

- **High — a cold-start direct media URL never negotiates legacy auth.** `app/src/main/java/com/music/bitchord/data/subsonic/SubsonicClient.kt:59-61,314-315` starts every new client with `legacy=false`, while `SubsonicSource.stream()` (`:279-289`) calls `streamUrl()` directly. If a restored queue is played after process death on a server that only accepts password auth, the first URL is token-authenticated and playback fails without the error-41 fallback that API calls perform. Persist/negotiate the effective auth mode before generating stream/art URLs, or retry through an authenticated request.

- **Required — server card collection actions fall through to YouTube.** `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:2501-2527` sends every non-local `browseId` to `YtMusicRepository.allSongs()`. A server album/playlist card opened from the server page has a `srcb:` ID and no loaded song list, so Play Next/Add to Queue/Download either fails or asks YouTube for an ID it cannot resolve. Parse `ServerBrowseRef` first and load the owning `ServerLibrary` entries (with an explicit artist-page policy).

- **Required — server playlist add/remove controls are unreachable.** In the initial `SongActionsSheet` gate at `app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt:221-259`, rating and playlist rows are rendered only when `parseTrackKey(...) == null`. A Subsonic track therefore cannot reach the server playlist picker that `MainActivity` wires up, and a server playlist page cannot expose its removal row. Split YouTube rating visibility from playlist capability visibility, as the later server-like work begins to do.

- **Required — playlist writes are not capability- or result-aware.** `app/src/main/java/com/music/bitchord/MainActivity.kt:3428-3448` wires rename/delete callbacks for every server playlist (delete is reachable; rename is still blocked by the old form gate), and `MainViewModel.kt:2403-2430` changes the page title or pops it even when the server rejects the request. `getPlaylists` may include shared/public playlists. Carry editability/ownership in the model, expose writes only when allowed, and mutate the page only after a successful response.

**Verdict:** **Request changes.** The added MockWebServer and source tests are useful, but they do not cover the failure paths above.

## Verification

- `git show --check` and `git diff --check` pass for the reviewed changes and this review file.
- `assembleDevDebug` passes at aggregate `HEAD`.
- `testDevDebugUnitTest`: 603 tests run, 3 failed (one live Genius network test and two pre-existing localization-test failures).
- Targeted `GenreAffinityTest`, `LibraryGridFilterTest`, `LibraryShelfCompletionTest`, and `SubsonicSourceTest` pass.
- Historical commits were inspected statically; no source files were modified.
