# Sill for Huawei MatePad Mini

A native Android client (Kotlin, Jetpack Compose, MediaCodec) for [Sill](https://github.com/Saffsanity/sill), the open-source app that streams Mac windows to an iPhone or iPad. This port lets a tablet connect to the unmodified Sill for Mac.

**It is made specifically for the Huawei MatePad Mini (MLR-W09) running HarmonyOS 4.3 (global version).** It may work on other Huawei tablets running global HarmonyOS 4.x or EMUI (Android 12 or later), but it has only been tested on the MatePad Mini. It will not install on HarmonyOS NEXT or HarmonyOS 5 and later, which have no Android runtime.

This is not an ArkTS or HarmonyOS NEXT app. Global HarmonyOS 4.3 on this tablet runs apps on an Android 12 (API 31) layer, with only the old API 8 Java HarmonyOS SDK on top, so a native APK is the app format that fits. See [RESEARCH.md](RESEARCH.md) for the checks behind that decision.

This is an unofficial, independent project. It is not affiliated with or endorsed by the Sill authors or Huawei.

## Requirements

- Huawei MatePad Mini (MLR-W09) on HarmonyOS 4.3, global version.
- A Mac running Sill for Mac (from [getsill.app](https://getsill.app), macOS 14 or later), on the same Wi-Fi network as the tablet for pairing.
- For USB mode: `adb` on the Mac (`brew install android-platform-tools`) and USB debugging turned on in the tablet's developer options.

## Install

1. Download the APK from the Releases page.
2. On the tablet, allow installing apps from unknown sources for the app you open the APK with, then open the APK.

Or, with the tablet connected over USB with debugging on:

```
adb install -r sill-matepad.apk
```

The release APK is debug-signed and meant for sideloading.

## First use

1. Open Sill on the Mac.
2. Open the app on the tablet.
3. Tap your Mac in the list.
4. Type the 12-digit code the Mac shows.

## USB cable mode

Run `./sill-usb.sh` on the Mac and leave it open. It keeps `adb reverse` forwarding the tablet's `127.0.0.1:47455` to Sill's port on the Mac. The app then offers "Connect over USB". Pair over Wi-Fi once first.

## Features

- Hardware HEVC decoding on the Kirin decoder, with a low-latency fallback ladder for decoders that refuse low-latency mode.
- Pinned TLS 1.3 pairing, compatible with Sill.
- Desktop streaming and per-window streaming, with live thumbnails.
- Window switcher and app launcher.
- The Mac's menus, with search.
- Clipboard in both directions.
- Three- and four-finger trackpad gestures.
- A floating bar or side rail that can be hidden.
- Laptop mode in portrait, with keys and a trackpad.
- Settings: quality, resolution, 60 or 120 fps, text size, reverse scrolling.
- Automatic reconnect after sleep or Wi-Fi changes, with path diagnostics.
- USB cable mode.

## Known limits

- No Mac audio yet.
- No QR code pairing.
- No remote access away from home yet.
- The "always use USB" automatic switch is not reliable yet.
- Huawei's three-finger screenshot gesture may intercept the three-finger swipe down.

## Build from source

Requires JDK 21 (set `JAVA_HOME` to it) and the Android SDK with platform 35 (point `sdk.dir` in `local.properties`, or `ANDROID_HOME`, at it).

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Credits and license

Licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). The protocol, pairing scheme and design this client reimplements come from the upstream [Sill](https://github.com/Saffsanity/sill) project, also Apache-2.0. See [NOTICE](NOTICE).
