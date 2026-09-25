# Remaining Work — Offline Playlist E2E (feat/offline-playlists)

State as of Sep 25, 2026. Code work is **done and committed**: `cd53bcd` (top-down 1-by-1 downloads, per-track download badge, wavy progress + floating toolbar), pushed to `fork`. Fresh APK built and copied to `/home/wj/Documents/AzuDrop-main/uploads/app-gms-mobile-universal-debug.apk`.

What's left is **E2E verification only**, paused because the host VPN was down (xray couldn't start — missing `geosite.dat`/`geoip.dat`, since fixed on disk).

## 1. Preconditions
- Proxy `127.0.0.1:10810` must be up: `curl -m 8 -x http://127.0.0.1:10810 https://www.google.com`
  - geo files were the root cause; config validates OK now. Do NOT restart v2ray/xray — the user manages it. Next core reload brings the port back.
- Emulator keeps wedging (~1000% CPU, adb transport gone, no 555x ports). If wedged:
  `kill -9 <qemu pid>` then relaunch detached:
  `setsid ~/Android/Sdk/emulator/emulator -avd verify -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot -http-proxy http://127.0.0.1:10810 -memory 4096 -cores 4 > /tmp/opencode/emulator.log 2>&1 < /dev/null &`
  Wait for `sys.boot_completed=1`. App data survives cold boot (no wipe), just `adb install -r` the APK again if needed.
- Avoid `pkill -f <pattern>` that matches your own bash command line (killed the shell once).

## 2. App state notes
- Package `re.muwmix.yumaplayer.debug`; launch via `am start -n re.muwmix.yumaplayer.debug/moe.rukamori.archivetune.MainActivity` (monkey opens LeakCanary).
- Spotify "not connected" seen earlier was **not** data loss: sp_dc + token + account name are intact in DataStore (`files/datastore/settings.preferences_pb`). Token had expired and `restoreSession()` needs network (proxy was down). Relaunching the app with a working proxy should reconnect automatically. No cookie injection needed.
- DataStore file lives at `files/datastore/` (not `datastore/`). Writing back via `run-as ... sh -c 'cat > ...'` needs device-side quoting or it fails.

## 3. E2E steps remaining
Coords (1080x2400): Library tab (830,2195); "Spotify Playlist" tab (927,611); breakcore row (527,1238); download toggle in playlist header (975,1792); Library gear (1006,212).

1. Verify Spotify reconnects: relaunch app → Library → Spotify Playlist tab shows playlists (screenshot).
2. Open "breakcore but LOTS OF DRUMS AND PIANO" (11 tracks, id `LPMQrvqtGe`); tap (975,1792) to start downloads; floating toolbar should show wavy progress + Pause + Close.
3. **Sequential proof — DB polls ×4, ~6s apart**:
   `adb exec-out run-as re.muwmix.yumaplayer.debug sh -c 'cat databases/song.db'` (+ `-wal`) → sqlite `SELECT id,state FROM ExoPlayerDownloads`.
   Assert: count(state=2 DOWNLOADING) ≤ 1 per poll; downloaded ids appear in playlist (top-down) order. States: 1=COMPLETED 2=DOWNLOADING 3=QUEUED 5=FAILED.
4. Screenshot Downloads screen (Library → gear): queue listed top-down by start time → `10_queue_order.png`.
5. Back in playlist: screenshots of row thumbnails with download badge bottom-left → `11_row_icons.png`, swipe → `12_row_icons_2.png`.
6. Wait for completion (user net is slow; 1 track expected to fail state=5): header shows offline icon, toolbar disappears → `13_done.png`.
7. Final report: pass/fail per behavior + DB poll numbers + screenshot paths.

## 4. Known non-blockers
- Pre-existing test failures (verified on clean tree): `PlayerViewModelHandleActionTest`, `PlayerViewModelLyricsTest`, `StaticLyricsTest`. Relevant tests (`*OfflineSync*`, `*HeaderDownload*`, `*Downloads*`) pass.
- Sub-agent (Task tool) API was flaky ("read body failed", "Task cancelled") — E2E is run manually via bash+adb.
- Screenshots dir: `/tmp/opencode/screens/` (07_toolbar_resolving.png already captured from an earlier run).
