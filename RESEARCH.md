# Sill on a Huawei MatePad Mini (HarmonyOS 4.3): research

Date: 2026-11-25. Upstream: https://github.com/Saffsanity/sill (Apache-2.0, cloned to `research/sill-src`).

## 1. What Sill is, and what a port means

Sill has two halves:

| Half | Language | Port needed? |
|---|---|---|
| **Sill for Mac** (menu bar host): ScreenCaptureKit capture → VideoToolbox HEVC → TLS 1.3 → device | Swift, macOS-only | **No.** It stays on your Mac as it is. |
| **Sill for iPhone/iPad** (`iOSClient/`, ~37k lines Swift, SwiftUI/UIKit) | Swift, iOS-only | **Yes: rewrite it** as a tablet client. |

So "porting Sill" means writing a new **client** for the MatePad that speaks Sill's wire protocol. No Swift code can run on the tablet. The protocol is documented in plain terms and is portable:

- **Discovery:** Bonjour/mDNS service `_sill._tcp` (home door, ephemeral port).
- **Transport:** TCP, then TLS 1.3 only. Both sides present a self-signed **P-256** certificate and each pins the other's **SHA-256 of the SPKI**. No chain or name checks. Tickets and resumption off. ALPN `sill/1` (session) and `sill-pair/1` (pairing). SNI `sill`.
- **Framing:** 14-byte big-endian header `kind(1) timestamp(8, double) isKeyframe(1) length(4)`, then a payload (`Sources/StreamProtocol/StreamMessage.swift`, kinds 0 to 47). Mostly JSON payloads; video is binary.
- **Video:** HEVC access units, length-prefixed NALs (hvcC style), plus a parameter-sets message (VPS/SPS/PPS). Real-time, no B-frames.
- **Audio (beta):** AAC-ELD, 48 kHz stereo, ~10 ms packets.
- **Input:** JSON `InputEvent`: pointer down/up/move, scroll with phases and momentum, text, HID key codes with modifiers, 3/4-finger gestures.
- **Pairing:** 12-digit code with a Damm check digit; key = PBKDF2-HMAC-SHA256(code, "sill-pair-v1" ‖ Mac fingerprint, 600,000 rounds), then HMAC proofs. Python test vectors exist in the repo's docs, and `Scripts/sillclient.py` is a working reference client.

Everything above is standard crypto and standard codecs, so it can be rebuilt on any platform with hardware HEVC decoding.

## 2. What "HarmonyOS 4.3" on a MatePad Mini actually is (verified so far)

- The MatePad Mini (8.8" OLED 2560×1600, Kirin 9010B, Wi-Fi 7) is sold globally with **HarmonyOS 4.3.0** on Huawei's regional spec pages (UAE, Philippines, Malaysia, Mexico and others).
- HarmonyOS 4.x is the **last dual-framework** release: an AOSP-based Android compatibility layer plus HarmonyOS API 9. **HarmonyOS NEXT / 5+ is the version that dropped Android.**
- For devices sold **outside China**, Huawei stated it keeps Android app support and does not ship the HarmonyOS-specific app system there. So the global "HarmonyOS 4.3" is the EMUI line under a new name: **Android apps (APK) run natively through ART; no Google services.**
- Native HarmonyOS apps (ArkTS/ArkUI `.hap`) on that **global** firmware are **not confirmed**. Even where API 9 HAPs run (China 4.x), the API 9 SDK is weak for this app: native HEVC decoding (`OH_VideoDecoder`) is only partly there in 9 (more APIs marked "since 10", much deprecated in 11), and current DevEco Studio builds target NEXT/5+ only.

### Verdict

| Route | Runs on global 4.3? | Smooth HEVC at 2560×1600? | Verdict |
|---|---|---|---|
| **Android app, Kotlin + MediaCodec + SurfaceView** | Yes: that is the OS's own app runtime | Yes: Kirin hardware HEVC decoder, zero-copy to a Surface | ✅ **Recommended: this is "native" on this tablet** |
| ArkTS/ArkUI HAP (API 9) | Unconfirmed; likely no on global firmware | Weak API 9 media APIs, old toolchain | ❌ Dead end for 4.3 |
| ArkTS HAP (API 12+, NEXT) | No: needs HarmonyOS NEXT/5, which the global Mini doesn't get | Yes | Later, only if the tablet ever runs NEXT |
| Flutter / React Native / web | Yes | Adds a layer between decoder and screen | ❌ You ruled these out, and rightly for video |

"Native" here means Kotlin compiled for ART, using the OS's own `MediaCodec` hardware decoder that renders straight to a `SurfaceView`, with no cross-platform layer. That is the same design Sill uses on iOS (VideoToolbox → `AVSampleBufferDisplayLayer`).

### Verified on the tablet (2026-11-25, over USB, read-only)

| Check | Result |
|---|---|
| Model | MLR-W09 (MatePad Mini), build 4.3.0.118 (C636 = global), `features.oversea` |
| Android runtime | **API 31 (Android 12)**, EmotionUI_15 underneath |
| HarmonyOS app framework | `hw_sc.build.os.apiversion = 8`, os.version 3.0.0: the **old HarmonyOS 3 Java/JS (FA) SDK**, not ArkTS/Stage. `hw_sc.not_supported_ohc_runtime = true` |
| HEVC hardware decoder | `OMX.hisi.video.decoder.hevc`: hw-accelerated, up to 6400×6400, measured 1080p at 258 fps and 4K at 71 fps. 2560×1600 at 120 Hz fits its block rate |
| Display | 1600×2560, 380 dpi, 60/90/120 Hz |
| Stylus | `huawei.android.hardware.stylus` present |
| Sideloading | Unknown sources allowed (1) |

**Decision: build an APK (Kotlin + MediaCodec + Compose).** The only "HarmonyOS-native" target on this firmware is API 8, the deprecated Java SDK that DevEco dropped years ago. ArkTS needs API 9+ (NEXT for current tooling). On this tablet, an APK is the native app.

### Original checklist (done)

1. Settings › About tablet › tap **Build number** 7 times → Developer options → turn on **USB debugging**.
2. Plug it into this Mac and run `./verify-device.sh` (in this folder). It confirms:
   - that adb sees the device (proof it is the Android-based 4.3),
   - the Android API level (EMUI 14 base is expected to be API 31, Android 12),
   - the HEVC hardware decoders and their max size and frame rate,
   - whether it can install a test APK from outside AppGallery.
3. Have Sill for Mac running on the same Wi-Fi so we can check that `_sill._tcp` is visible from the tablet.

## 3. Client architecture (Android-native on HarmonyOS 4.3)

```
NsdManager (_sill._tcp) ─► SSLSocket TLS1.3 (Conscrypt, P-256 client cert, SPKI pin, ALPN sill/1)
        │                         │
        │                  FrameReader (14-byte header, size caps)
        │            ┌────────────┼──────────────┬─────────────┐
        ▼            ▼            ▼              ▼             ▼
   Saved Macs   MediaCodec HEVC  AAC-ELD →    JSON catalog   Input encoder ◄─ touch / keyboard /
   (Keystore)   → SurfaceView   AudioTrack    (windows,      (pointer, scroll,   M-Pencil / mouse
                (low-latency)   (low latency) thumbs, menus)  keys, gestures)
                                     │
                         Jetpack Compose UI (bar, drawer, settings, pairing)
```

Key choices:
- **Video:** `MediaCodec` async mode, `KEY_LOW_LATENCY=1` where supported, render with `releaseOutputBuffer(index, true)` straight to the `SurfaceView` (the HWC overlay, not a TextureView). Drop late frames, don't queue them. Send kind 44 (keyframe request) on decoder reset or resume.
- **Crypto:** Android Keystore P-256 key, self-signed cert built once, a custom `X509TrustManager` that checks only the SPKI SHA-256 pin, TLS 1.3 only, session cache off.
- **Input:** Compose pointer input → `InputEvent` JSON; hardware keyboard `KeyEvent` → HID usage table; stylus hover → `move`.
- **UI:** Jetpack Compose with an adaptive tablet layout and edge-to-edge, 120 Hz-friendly (no work on the main thread per frame).
- **Build:** Gradle + AGP, minSdk = the device's API level, sideloaded APK (`adb install`). No AppGallery, no Huawei developer account needed for personal use.

## 4. Skills

Installed skills that already apply: `mobile-native`, `apple-design` (gesture/motion ideas), `antislop-ui`, `accessibility`, `tdd`, `karpathy-guidelines`.

Found on skills.sh (not installed yet; waiting on your OK):

| Skill | Source | Why |
|---|---|---|
| `android-cli` | **android/skills** (official, Google, 10.7k★) | SDK, project creation, `install`/run on device, APK inspection |
| `adaptive` | android/skills | Tablet layouts, keyboard and mouse, configuration changes |
| `jetpack-compose-performance` | android/skills | Jank and recomposition: matters with live video under the UI |
| `edge-to-edge` | android/skills | System bars and insets on the OLED |
| `anr-audit-and-fix` | android/skills | Keeps decode and network off the main thread |
| `android-permissions-security` | android/skills | Local network, camera for QR pairing |
| `harmonyos-device-automation` | web-infra-dev/midscene-skills (2.5k installs) | Optional: driving the tablet's UI for tests |

Skipped: the community ArkTS/HarmonyOS skills (100 to 800 installs, small repos). They target NEXT/ArkTS, which this tablet can't run.

Gap with no good public skill: **low-latency MediaCodec streaming plus Sill's protocol.** Plan: write a project skill `sill-android-client` (protocol table, framing, pairing vectors, MediaCodec low-latency recipe, pinning TrustManager), using `skill-creator`.
