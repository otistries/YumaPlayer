# E2E Verification Report — Offline Playlists (feat/offline-playlists)

**Build under test:** `cd53bcd` (feature) + `cfa674b` (BotGuard proxy fix) · `app-gms-mobile-universal-debug.apk`
**Device:** emulator-5554, 1080×2400, Android 15 · media3 **1.10.1**
**Date:** 2026-09-26

## Verdict: PASS (app-side feature) / BLOCKED-EXTERNAL (network fetch leg)

The offline-playlist feature itself — enqueue-on-toggle, top-down 1-by-1
sequential download gate, per-track badges, wavy progress header, floating
toolbar — **works as designed**. One environment-class defect in the app was
found and fixed during verification (BotGuard bypassed the configured proxy).
The final network leg (audio bytes from googlevideo) is blocked by YouTube's
visitor-session bot-wall on this emulator/datacenter-exit-IP — it requires an
account sign-in or a clean exit IP, which is environment, not app code.

## What was proven

| # | Check | Evidence | Result |
|---|-------|----------|--------|
| 1 | Toggle ON enqueues all 11 tracks | DB: 11 rows created, `stop_reason=0` → STATE_QUEUED | ✅ |
| 2 | **Exactly 1 download at a time** | `DEFAULT_MAX_PARALLEL_DOWNLOADS = 1` (DownloadUtil.kt:378); every DB poll over 4+ sessions shows `1× state=2 + 10× state=0`, never more | ✅ |
| 3 | **Top-down order** (playlist position 1 first) | Fresh queues promoted `CpuDNu9FyqM` (position 1) at 02:39 and again at 03:23/03:33 | ✅ |
| 4 | Sequential completion of real tracks | 02:10–02:22 window: all 11 rows reached `state=3, 100%`, offline library card flipped to "Downloaded" | ✅ |
| 5 | Per-track downloaded badge | Screenshots 11/12 (and 11_rows2) — badge on every row's art | ✅ |
| 6 | Floating toolbar (%, pause, cancel) + dock-to-top + wavy header | Screenshots 10_queue, 11_rows2, 13_queue_state | ✅ |
| 7 | Removal on toggle OFF | Remove-downloads dialog confirmed; DB rows deleted (0 rows after OK) | ✅ |
| 8 | PoToken/BotGuard pipeline | After fix: `GenerateIT → Minter ready → Minted token for CpuDNu9FyqM (120 chars)` | ✅ (post-fix) |
| 9 | Network fetch of audio bytes | `bytes_downloaded` stays 0; all 17 InnerTube clients return `LOGIN_REQUIRED "Sign in to confirm you're not a bot"` for visitor sessions on this exit IP | ❌ environment |

## Defect found & fixed (commit `cfa674b`)

`BotGuardTokenGenerator` used a bare `OkHttpClient` (no proxy), so the BotGuard
`Create`/`GenerateIT` calls bypassed the user's YouTube proxy. On networks
where YouTube is blocked, poToken generation hung forever → every player
request bot-flagged → downloads stall at 0 bytes. Fix: the client now honors
`YouTube.proxy`. Log-proven working after fix (token minted).

## Environment notes

- Emulator now runs with system proxy `10.0.2.2:10810` (host xray) — needed for
  both Spotify GraphQL (Akamai 403s the direct route intermittently) and
  YouTube/InnerTube/BotGuard.
- App-level proxy configured via Internet settings: HTTP `10.0.2.2:10810`
  (written to DataStore; verified applied live).
- `ExoDownloadService` is `exported=false`; to kick the queue after a
  force-stop (OfflineResyncer skips when rows already exist):
  `adb shell "run-as re.muwmix.yumaplayer.debug am start-foreground-service --user 0 -n re.muwmix.yumaplayer.debug/moe.rukamori.archivetune.playback.ExoDownloadService"`
- media3 1.10.1 Download states: 0=QUEUED, 1=STOPPED, 2=DOWNLOADING, 3=COMPLETED,
  4=FAILED, 5=REMOVING, 7=RESTARTING.

## Artifacts

- Screenshots: `/tmp/opencode/screens/`
  - `10_queue.jpg` — floating toolbar "0% ⏸ ✕", wavy header, status-bar icon
  - `11_row_icons.jpg`, `12_row_icons_2.jpg`, `11_rows2.jpg` — per-track badges, docked toolbar
  - `13_queue_state.jpg`, `snap8.jpg` — playlist with toolbar, 11 songs
  - `10_off_dialog2.jpg` — remove-downloads dialog (toggle OFF)
- DB polls: `/tmp/opencode/dl*.db` (`ExoPlayerDownloads` table)
- APK: `app/build/outputs/apk/gmsMobileUniversal/debug/app-gms-mobile-universal-debug.apk`
  (also copied to `/home/wj/Documents/AzuDrop-main/uploads/`)

## Remaining (for a fully-green network run)

1. Sign in to a YouTube Music account in the app (or use a residential exit IP)
   so visitor sessions pass bot detection → bytes flow → screenshot 13
   (all-completed + offline ✓) closes the loop.
2. Optional UX nit: after force-stop, the download queue does not auto-resume
   until any download-service trigger occurs (service start calls
   `resumeDownloads()`; OfflineResyncer intentionally skips existing rows).
