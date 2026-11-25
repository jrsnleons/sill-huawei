# Sill for MatePad: status

Native Android client (Kotlin, Jetpack Compose, MediaCodec) for Sill for Mac. It runs on the Huawei
MatePad Mini (MLR-W09) through the Android 12 layer of global HarmonyOS 4.3. RESEARCH.md explains why
it isn't an ArkTS app.

Build and install (tablet on USB, or wireless adb):

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Status as of v0.2.0 (2026-11-25).

## Verified on the tablet
- **Connection:** Bonjour discovery (`_sill._tcp`), pairing with the 12-digit code, pinned TLS 1.3 sessions.
- **Video:** a live picture on the Kirin hardware HEVC decoder. The firmware refuses `KEY_LOW_LATENCY`
  but accepts HiSilicon's own low-latency key, so the decoder runs at hints level 2.
- **Input and bar:** Desktop and per-window streaming, touch, mouse and keyboard input, about 8–20 ms round trip on Wi-Fi.
- **Clipboard** both ways, and the Mac's menus (fetch, browse, press).
- **Bar:** the hide button and the handle that brings it back.
- **Reconnect:** after a Wi-Fi drop, the last picture stays under a "Reconnecting…" screen and the app redials on schedule.
- **USB cable:** `./sill-usb.sh` on the Mac forwards the tablet's 127.0.0.1:47455 to Sill's port with `adb reverse`.
  The home screen detects it, and "Connect over USB" streams over the cable. The bar shows "USB".
- **Window previews** in the bar (kind 3 thumbnails).
- **App icon:** adaptive, a window on a sill.

## Built, not yet tried on the tablet
- **New home screen:** a card per Mac with its last picture and when it was last used; connect by address.
- **USB setup sheet:** checks USB debugging, the cable and the helper, then connects.
- **Pairing screen:** 12 digit boxes and a number pad, plus paste and hardware-keyboard digits.
- **Side rail layout,** chosen in Settings as an alternative to the floating bar.
- **Laptop mode**, when the tablet is held upright:
  - the picture on top;
  - a key row (esc, tab, ⌃ ⌥ ⌘ held for the next key, arrows, return);
  - a trackpad that moves a drawn pointer (tap clicks, two fingers scroll, hold then move drags);
  - Click and Right-click buttons.
- **Window switcher:**
  - a grid of window previews;
  - hold a window for Close, Minimize and Full Screen;
  - a searchable list of the Mac's apps that opens one when tapped.
- **Menu search** across every menu (it reads all the top-level menus when you type).
- **Settings:**
  - quality (Low, Balanced, High, Pro) and Standard or Retina, sent to the Mac as a settings change;
  - 60 or 120 fps;
  - text size for the streamed window (Aa);
  - bar style and auto-hide after 3 s;
  - prefer USB, reconnect by itself, gestures, reverse scrolling.
- **Clipboard:** a "Paste on ‹Mac›" offer appears when you copy text on the tablet.
- **Reconnecting screen:** checks the tablet's Wi-Fi, the router and the Mac, and offers "Use the USB cable" when the link is up.
- **Gesture guide:** shown the first time a picture appears.
- **Three- and four-finger gestures:**
  - swipe up for Mission Control, down for App Exposé, sideways for Spaces;
  - pinch for Apps, spread for Show Desktop;
  - a three-finger tap shows or hides the bar.

## New in 0.2.0, not yet tried end to end
- **The Mac's cursor** (kinds 14 and 26): the real cursor image is drawn at its hotspot, at the Mac's size in points.
  It follows this tablet's input and the Mac's own mouse, and hides when the pointer leaves the picture.
  Laptop mode's trackpad moves it and continues from where the Mac's pointer is.
- **Remote access away from home:**
  - The Mac's signed kind 18 is verified (ECDSA P-256 over the exact info bytes, key equal to the pin, Mac ID
    matching) and saved only if it is newer.
  - "Connect from afar" dials the saved addresses in order on the remote port, pinned to the paired key.
  - The card says what it knows ("From afar: Tailscale", or what to turn on).
  - The bar's tag reads "Away". An away session that drops reconnects over Wi-Fi if the Mac is on it.
- Installed on the tablet: the home card shows the Mac's recent picture and "From afar: not set up yet"
  (Remote Access is off on the test Mac).

## Known issues
- **"Always use USB when the cable is in"** doesn't switch over by itself when the USB link comes up; tap
  Connect over USB.
- **Wi-Fi between the tablet and Mac:** on the test network, the router sometimes stops passing traffic between
  them while both still reach the router. Reconnect can't get around that; USB can.
- **Screenshot gesture:** Huawei's three-finger swipe-down screenshot may take that gesture first. It can be turned off in
  Settings › Accessibility › Shortcuts & gestures.

## Not built
- Mac sound (AAC-ELD, kind 43).
- QR-code pairing.
