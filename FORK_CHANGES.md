# Fork Changes — What MuwMx/YumaPlayer Changed Since the Fork

This fork split from **otistries/YumaPlayer** at commit `6706087`
("chore(lyrics): delete dead PaxSenIX integration", 2026-09-24). Since then,
development happened on **two lines**, and this document is the map:

| Line | Where | What |
|---|---|---|
| `main` | `MuwMx/YumaPlayer` only | Glass/Haze UI system, app-shell refactor, artist/playlist screen redesign, duplicate checker (48 commits) |
| `feat/offline-playlists` | branch off the fork point | Keep-offline Spotify playlist downloads + reliability fixes (13 commits) |

> **Heads-up for future devs:** the two lines have **not been merged yet**. The
> `feat/offline-playlists` branch does *not* contain the glass/shell work, and
> `main` does *not* contain the offline work. Six files were touched on both
> lines and **will conflict on merge**: `constants/PreferenceKeys.kt`,
> `ui/screens/library/SpotifyLikedHeaderHero.kt`,
> `ui/screens/playlist/LocalPlaylistHeroSection.kt`,
> `ui/screens/playlist/LocalPlaylistWiring.kt`,
> `ui/screens/playlist/SpotifyPlaylistScreen.kt`, `res/values/strings.xml`.
> Merge `feat/offline-playlists` → `main` to recombine; resolve conflicts by
> keeping both the glass styling and the offline wiring in each hero/screen.

---

## 1. Keep-offline playlists (feat/offline-playlists)

The headline feature: mark any remote Spotify playlist (or Spotify liked
songs) as *keep offline* — tracks download strictly **one at a time, top-down
in playlist order**, re-enqueue themselves after crashes/reinstalls
(startup self-heal), stay current via targeted per-playlist syncs, and can
optionally export as real FLAC files. Per-track downloaded badges, wavy
progress header, floating pause/cancel toolbar, and a Downloads queue screen.

**Full deep dive: [docs/architecture/OFFLINE_PLAYLISTS.md](docs/architecture/OFFLINE_PLAYLISTS.md)**
(data flow, key files, gotchas, media3 state table, testing recipe) and
**ADR-013** in [docs/architecture/DECISIONS.md](docs/architecture/DECISIONS.md).
Reliability fixes shipping in the same branch:

- BotGuard poToken HTTP now honors the configured YouTube proxy — without it,
  downloads silently stall at 0 bytes on networks where YouTube is blocked
  (`cfa674b`).
- Failed downloads retry once at startup; Spotify requests send device locale
  (`Accept-Language`).
- CI: PR build workflow runs on `feat/**` pushes with robust artifact naming
  (`2998905`).

### Verification status (supersedes the deleted E2E_REPORT.md)

Verified end-to-end on an emulator against build `cd53bcd` + `cfa674b`:
toggle-on enqueues all tracks; DB polls over 4+ sessions show **exactly one**
`state=DOWNLOADING` row at any time; queue drains top-down (position-1 track
promoted first, every time); completed tracks get badges and flip the
library card to "Downloaded"; toggle-off removes downloads after a confirm
dialog; poToken mints successfully post-fix. The only unproven leg was
fetching audio bytes on a datacenter exit IP — YouTube's visitor-session
bot-wall requires an account sign-in or residential IP, which is
environment, not app code.

---

## 2. Glass / Haze UI system (main only)

A configurable backdrop-blur "glass" layer over the whole app, built on the
Haze library and canonicalized across screens:

- **Settings**: glass opacity token, blur-radius slider, and an appearance
  toggle (`BlurNavBarKey`, default on) in `AppearanceSettings` /
  `AppearanceContract` / `AppearanceSections`.
- **Rendering**: `HazeTint` shader with dynamic tint from blur intensity,
  adaptive borders, fluid morphing transitions, morphing tab indicator, and a
  canonical glass FAB (`ui/component/HideOnScrollFAB.kt`) with isolated Haze.
- **Tokens centralized** in `SettingsDimensions` — do not hardcode haze
  values in screens.
- **Screens canonicalized**: playlist, artist, album, history, news, local
  songs and top-playlist screens all follow the same transparent-top-bar +
  glass-action-row pattern; the artist hero got redesigned stats and a
  4-button action row.
- **Wiring contract**: `docs/rules` addition mandates single-root screen
  layout, `scrollBehavior` wired to `TopAppBar`, and `TopCenter` alignment in
  free-form Boxes. Follow it when adding screens.

Related fix history worth knowing: a bisect cycle reverted Haze usage to hunt
a touch-freeze bug (fixed by moving the player overlay to a sibling layer,
not `bottomBar`), and a `MainActivity` theme-effect leak was fixed.

## 3. App-shell refactor (main only)

`MainActivity.kt` (~3.2k lines) was decomposed into focused hosts (commits
W0–W6):

| New file | Role |
|---|---|
| `YumaApp.kt` | Root composable + `ScaffoldShell` wiring |
| `ScaffoldShell.kt` | Scaffold/shell chrome |
| `NavigationHost.kt` | Single `NavHost`, single `navController` source |
| `PlayerOverlayHost.kt` | Player overlay as sibling layer (fixes child touch freezing) |
| `GlobalDialogsHost.kt` | All global dialogs |
| `MainIntentRouter.kt` | Intent routing + service binding |

## 4. Duplicate checker (main only, dev tool)

`duplicate_checker.py` (repo root): Cyrillic-safe TF-IDF duplicate detection
for translations/playlists with markdown cleaning and two-tier thresholds.

## 5. Housekeeping (both lines)

Weblate l10n updates (Italian, Portuguese-BR, Malayalam), README/RU mirrors,
trendshift badge, stricter bug-report issue template.

---

## Known limitations & next steps (supersedes the deleted REMAINING_WORK.md)

1. **No auto-resume after force-stop**: the download queue stays paused until
   any download-service trigger occurs (`ExoDownloadService` start calls
   `resumeDownloads()`; the startup resyncer intentionally skips existing
   rows). Auto-resume is the top open item.
2. **Emulator/CI network leg**: audio bytes require a signed-in YouTube Music
   account or a residential exit IP (visitor sessions get
   `LOGIN_REQUIRED`); not an app-code defect.
3. **Keep-offline FLAC export failures are log-only** — surface them in UI.
4. **Pre-existing test failures** (on a clean tree, unrelated to this fork's
   work): `PlayerViewModelHandleActionTest`, `PlayerViewModelLyricsTest`,
   `StaticLyricsTest`. Offline-relevant tests (`*OfflineSync*`,
   `*HeaderDownload*`, `*Downloads*`) pass.
