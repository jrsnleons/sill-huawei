# Sill for MatePad: status

Native Android client (Kotlin, Compose, MediaCodec) for Sill for Mac, running on the MatePad Mini's
Android 12 layer under global HarmonyOS 4.3. See RESEARCH.md for why it isn't an ArkTS app.

Build and install (tablet on USB or wireless adb):

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Verified on the tablet (2026-11-25)
- Discovery (Bonjour `_sill._tcp`), pairing with the 12-digit code, pinned TLS 1.3 sessions.
- Live picture on the Kirin hardware HEVC decoder (it refuses `KEY_LOW_LATENCY`; the fallback ladder handles it).
- Window bar, Desktop, touch / mouse / keyboard input, ~16–20 ms round trip.
- Clipboard both ways, the Mac's menus (fetch, browse, press).

## Built 2026-11-25 evening, not yet tried on the tablet
- **Three/four-finger gestures** (kind 28): swipe up Mission Control, down App Exposé, left/right Spaces,
  pinch Apps, spread Show Desktop. Three-finger tap still shows/hides the bar. Two fingers now scroll too.
  Watch for: Huawei's own three-finger swipe-down screenshot gesture may take that one first
  (Settings › Accessibility › Shortcuts & gestures › Take screenshot).
- **Reconnect**: a dropped link (sleep, Wi-Fi change, Sill restarting) keeps the last picture under a
  "Reconnecting…" veil and redials at 0.5, 1, 2, 4, then every 5 s for 3 minutes, at once when the
  network returns or the app comes back to the front, looking the Mac up again by Bonjour name
  (its port changes when Sill restarts). A 4 s silence watchdog catches dead links TCP would miss.
  Goodbyes with `reconnect` (or quit/restarting) redial; "removed" forgets the pairing.
- **Lower latency**: MediaCodec in async (callback) mode, no polling; decode thread at display priority;
  HiSilicon's vendor low-latency key tried before plain real-time priority; at most 3 frames queued, then
  skip to the next keyframe; Wi-Fi `WIFI_MODE_FULL_LOW_LATENCY` lock while streaming (no power save);
  DSCP AF41 on the socket; 120 Hz display mode and surface frame rate; reader thread at max priority.
- **Hide the bar**: ⌃ in the bar hides it (remembered), a small handle at the top brings it back.
- **App icon**: adaptive (window on a sill, amber on dark) with a monochrome layer.

## Not built
- Mac sound (AAC-ELD, kind 43), QR-code pairing, remote access away from home, Aa text size, settings panel.
