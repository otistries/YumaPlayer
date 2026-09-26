# Offline Playlists — Keep-Offline Downloads for Spotify Content

> Deep-dive for the `feat/offline-playlists` work (download availability & reliability).
> Companion docs: [E2E_REPORT.md](../../E2E_REPORT.md) (verification evidence),
> [REMAINING_WORK.md](../../REMAINING_WORK.md) (open items), [DECISIONS.md](DECISIONS.md) (ADR-013).

## What this feature does

Lets a user mark any **remote Spotify playlist** (or the **Spotify liked-songs**
library) as *keep offline*. From then on:

1. Every track in the playlist is enqueued as a media3 `DownloadRequest`.
2. Tracks download **strictly one at a time, in playlist order (top-down)** —
   never in parallel, never shuffled.
3. New tracks that appear on the Spotify side during later syncs are
   **auto-enqueued incrementally** (targeted per-playlist sync, not a full resync).
4. If the user reinstalls, clears the queue, or the app dies mid-way, a
   **startup self-heal** re-enqueues whatever is missing.
5. Optionally (`ExportKeepOfflineKey`), completed downloads are also **exported
   as real FLAC files** to the user's music folder via `FlacDownloader`.
6. Toggling keep-offline **off** removes all downloads for that playlist
   (with a confirmation dialog) and cancels the self-heal for it.

Per-track downloaded badges, a wavy progress header, and a floating
pause/cancel toolbar expose the state in the UI; a dedicated Downloads screen
lists the queue top-down by start time.

## Architecture / data flow

```
Spotify GraphQL ──▶ SpotifySyncOps (sync playlists / liked songs)
                        │
                        │  new/removed tracks?
                        ▼
        PlaylistEntity.keepOffline (Room, default 0)
                        │
        ┌───────────────┴────────────────┐
        ▼                                ▼
 sendAddMissingDownloads()       OfflineResyncer (startup self-heal)
 (incremental, per toggle/sync)  (re-enqueues missing rows if keepOffline)
        │                                │
        └───────────▶ ExoDownloadService (media3 DownloadManager)
                              │  maxParallelDownloads = 1
                              ▼
                      DownloadUtil.downloads: StateFlow<Map<id, Download>>
                              │
        ┌─────────────────────┼──────────────────────┐
        ▼                     ▼                      ▼
 PlaylistOfflineStatus  HeaderDownloadProgress   DownloadsScreen /
 (per-playlist          (wavy header + floating  per-track badges
  completeness)          toolbar)                (ItemBadges)
```

### Download ordering and the 1-by-1 gate

- `DownloadUtil.DEFAULT_MAX_PARALLEL_DOWNLOADS = 1`
  (`app/.../playback/DownloadUtil.kt`) is the single knob that enforces
  sequential downloads. It feeds both the `DownloadManager`
  (`maxParallelDownloads`) and the OkHttp dispatcher (`maxRequestsPerHost`),
  so there is exactly **one** audio fetch in flight at any moment.
- **Top-down order is emergent, not scheduled**: tracks are enqueued in
  playlist row order (e.g. `likedSongsByRowIdAscSpotify()`), and with a
  parallelism of 1 media3 drains its queue FIFO. If you ever raise the
  constant, top-down ordering degrades to approximate — keep the two facts in
  sync.

### Keep-offline flag

- `PlaylistEntity.keepOffline` (`database/.../entities/PlaylistEntity.kt`,
  Room column `keepOffline`, default `0`) marks remote playlists. There is a
  DB migration bump in `MusicDatabase` — bump the version again if you touch
  entities.
- Spotify liked songs are not a `PlaylistEntity`; their toggle lives in
  DataStore under `SpotifyLikedKeepOfflineKey`
  (`constants/PreferenceKeys.kt`). Any new "keep offline" surface must decide
  which storage its toggle writes to (Room flag vs DataStore key) and read the
  same one back in the resyncer.

### Incremental sync → enqueue

- `SpotifySyncOps.syncSingleSpotifyPlaylist(spotifyPlaylistId)` performs a
  **targeted** sync of one playlist (used after download-related changes and by
  the sync worker) instead of resyncing the whole library.
- `SpotifySyncOps.enqueueKeepOfflineDownloads(resolvedTracks)` is the single
  funnel that turns resolved tracks into `DownloadRequest`s — new code should
  call it rather than building requests inline.
- `OfflineSyncWorker` (WorkManager `CoroutineWorker`) triggers Spotify
  auto-sync through `SyncUtils`; it exists so background syncs also honor
  keep-offline enqueueing, not just foreground ones.

### Startup self-heal

- `OfflineResyncer.enqueueMissingForKeepOfflinePlaylists()` runs at app start
  (`App.kt`): for each keep-offline source (DataStore key for liked songs,
  Room flag for playlists) it diffs the track list against
  `DownloadUtil.downloads` and enqueues only the missing ids via
  `sendAddMissingDownloads()`.
- It **intentionally skips** when download rows already exist (it is a heal,
  not a resume): after a force-stop the queue stays paused until any download
  service trigger occurs — `ExoDownloadService` start calls
  `resumeDownloads()`. Known limitation, see REMAINING_WORK.md.

### Keep-offline real-file export

- `DownloadUtil.maybeExportKeepOffline(songId)` fires on the
  `STATE_COMPLETED` transition (previous state must be non-null and non-completed
  — this prevents re-export on app restarts where the map reload fires the
  listener with an already-completed download).
- Gate order: `ExportKeepOfflineKey` DataStore flag →
  `database.isSongInKeepOfflinePlaylist(songId)` → `FlacDownloader.downloadFlac`.
  Export failures are logged, never surfaced as download failures.

### Per-playlist offline status

- `OfflineSyncLogic.PlaylistOfflineStatus.compute()` is the **single source of
  truth** for "is this playlist fully offline": it dedupes song ids, counts
  `STATE_COMPLETED` / `STATE_FAILED` / active (`QUEUED|DOWNLOADING|RESTARTING`)
  and sums bytes. UI (hero sections, badges, toolbar) must derive state from
  this, not from ad-hoc count queries. Unit-tested in
  `OfflineSyncLogicTest`.

### UI surfaces

| File | Role |
|---|---|
| `ui/screens/settings/DownloadsScreen.kt` + `viewmodels/DownloadsViewModel.kt` | Queue screen: top-down by start time, per-row state, floating toolbar |
| `ui/screens/playlist/SpotifyPlaylistScreen.kt` | Keep-offline toggle in playlist header; observes `PlaylistOfflineStatus` |
| `ui/screens/library/SpotifyLikedSongsScreen.kt` / `SpotifyLikedHeaderHero.kt` | Liked-songs keep-offline toggle (DataStore key) |
| `ui/component/items/ItemBadges.kt` | Per-track downloaded badge on artwork |
| `ui/utils/HeaderDownloadProgressIndicator.kt` | Wavy progress header + floating pause/cancel toolbar |
| `ui/utils/HeaderDownloadState.kt` | `sendAddMissingDownloads` / `sendRemoveDownloads` / `sendPauseDownloads` helpers (thin wrappers over `DownloadService.send*`) |

## Reliability fixes that ship with this feature

1. **BotGuard honors the YouTube proxy** (`BotGuardTokenGenerator.kt`): the
   BotGuard HTTP client previously used a bare `OkHttpClient`, bypassing the
   user-configured proxy. On networks where YouTube is blocked, poToken
   generation hung → every request fell back to `LOGIN_REQUIRED` → downloads
   stalled at 0 bytes forever. The client now applies `YouTube.proxy` per call.
   **If you touch any InnerTube-adjacent HTTP client, check it honors
   `YouTube.proxy`** — this class of bug is silent and fatal to downloads.
2. **Failed downloads are retried once on startup**
   (`DownloadUtil.retryFailedDownloads` on initial load) — transient network
   failures no longer strand a playlist at 99% forever.
3. **Spotify requests send device locale** (`Accept-Language`) so sync results
   match the library the user actually sees.

## media3 download state cheat sheet (1.10.1)

| Constant | Value |
|---|---|
| `STATE_QUEUED` | 0 |
| `STATE_STOPPED` | 1 |
| `STATE_DOWNLOADING` | 2 |
| `STATE_COMPLETED` | 3 |
| `STATE_FAILED` | 4 |
| `STATE_REMOVING` | 5 |
| `STATE_RESTARTING` | 7 |

(Earlier notes in REMAINING_WORK.md used a different mapping — this table is
the authoritative one; it matches media3 `Download.STATE_*`.)

## Testing notes

- Unit: `OfflineSyncLogicTest` covers completeness math. UI-relevant tests:
  `*OfflineSync*`, `*HeaderDownload*`, `*Downloads*` (some unrelated
  ViewModel/Lyrics tests fail on a clean tree — see REMAINING_WORK.md, they
  are pre-existing and not from this branch).
- E2E recipe + DB-poll commands are in `E2E_REPORT.md` (media3 states are
  observable via `databases/song.db` → `ExoPlayerDownloads` table).
- BotGuard/proxy: verify `Minted token for <id>` appears in logs when YouTube
  is only reachable via proxy; without it downloads sit at `bytes_downloaded=0`.

## Where to pick up next

See `REMAINING_WORK.md`. Headline items: auto-resume after force-stop,
account sign-in / residential IP for the final network leg in emulators, and
surfacing export failures in UI instead of log-only.
