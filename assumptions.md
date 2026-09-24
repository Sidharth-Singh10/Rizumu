# Assumptions

Decisions made while fixing `reviews.md` that were not fully specified, and the
behaviour the app is expected to have as a result.

1. **Degraded Keystore storage is fail-closed, not silent.** When
   `EncryptedSharedPreferences` cannot be initialised, the app still runs, but
   `SourceRegistry` never writes a Subsonic password to the unencrypted fallback
   store: passwords are stripped on persist and asked for again next launch. A
   plain store holding passwords from an earlier build is rewritten without them.
   When encryption works again, plain-store sources are migrated in and the plain
   copy is cleared. Assumed acceptable: a server may show as incomplete after a
   restart on a device whose Keystore is broken.

2. **Plain HTTP is an explicit per-source choice.** `usesCleartextTraffic` stays
   `true` at the platform level (self-hosted LAN servers are the supported case),
   but `SubsonicClient` refuses any `http://` request unless
   `SourceConfig.allowInsecureHttp` is on, and the editor blocks Save/Test until
   the user accepts it. Assumed acceptable: an existing `http://` source saved
   before this change stops working until its editor row is turned on; it is
   never silently trusted. HTTPS→HTTP redirects are always refused.

3. **Subsonic playlist ownership is inferred from `owner`.** The protocol has no
   capability flag. A playlist is editable when its `owner` matches the configured
   username (case-insensitive) or when the server sends no owner. A server that
   omits owner for someone else's shared playlist is assumed to be listing the
   account's own playlists; rename/delete are only offered once this resolves to
   true, and the ViewModel refuses an explicit false.

4. **Auth negotiation costs one ping.** For an `AUTO` client whose auth form is
   not yet known, the first direct media/art URL triggers one `ping` so the URL is
   signed with the form the server accepts. `TOKEN`/`LEGACY` clients never ping.

5. **Server identity is a fingerprint of the whole config.** Reload keys and cache
   keys use all `SourceConfig` fields (password folded to a hash) rather than
   `id@baseUrl`, so an account/quality/auth/label edit is treated as a changed
   server. Keys are in-memory only.

6. **Like writes are serialised app-wide.** `ServerStarQueue` owns one mutex per
   track and its own scope; the notification and the ViewModel share it. Each
   queued write sends the latest desired state, and a failed write rolls the
   optimistic state back only while no newer tap has arrived. The queue's lock map
   is not garbage-collected; it is bounded by the tracks toggled in a session.

7. **Server mode consumes Listen Together invites without opening them.** An
   incoming invite is marked handled so it cannot spring open a Google surface,
   but no message is shown; the user is assumed not to expect the feature in
   server mode.

8. **Shelf completion is bounded and honest about it.** The YouTube walk stops at
   200 pages or 10,000 cards and reports a partial list; the grid then says the
   list may be incomplete instead of “Nothing here matches”. The bounded library
   load stores its continuation token in memory so Show all resumes instead of
   re-fetching the preview pages; losing the token only costs that re-fetch.

9. **Server playlist write failures are surfaced as a toast.** `serverActionError`
   is one-shot: the UI shows it and clears it. Pages are mutated only after a
   successful server response.

10. **Legacy downloads are adopted, not migrated.** The duplicate check reads both
    `Music/Rizumu` and `Music/BitChord`, but new downloads still write to
    `Music/Rizumu`; existing files are left where the user can manage them.

11. **Documentation is corrected, not versioned.** `docs/SUBSONIC.md` is updated to
    describe the app as it behaves at HEAD; the review's per-commit doc states are
    not preserved.
