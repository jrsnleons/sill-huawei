#!/bin/bash
# Checks a USB-connected Huawei tablet for what a native Sill client needs. Read-only: installs nothing.
set -u
adb get-state >/dev/null 2>&1 || { echo "No device over adb. Turn on USB debugging and accept the prompt on the tablet."; exit 1; }
p() { adb shell getprop "$1" | tr -d '\r'; }
echo "Model:            $(p ro.product.model)  ($(p ro.product.brand))"
echo "Android API:      $(p ro.build.version.sdk)  (Android $(p ro.build.version.release))"
echo "HarmonyOS/EMUI:   $(p ro.huawei.build.display.id) $(p hw_sc.build.platform.version) $(p ro.build.version.emui)"
echo "Display:          $(adb shell wm size | tr -d '\r')  $(adb shell wm density | tr -d '\r')"
echo "Refresh rates:    $(adb shell dumpsys display | grep -o 'fps=[0-9.]*' | sort -u | tr '\n' ' ')"
echo "Unknown sources:  $(adb shell settings get secure install_non_market_apps | tr -d '\r')"
echo; echo "HEVC decoders (hardware ones are what we want):"
adb shell dumpsys media.player 2>/dev/null | grep -iA2 'hevc' | head -20
adb shell 'cat /vendor/etc/media_codecs*.xml /odm/etc/media_codecs*.xml 2>/dev/null' | grep -iE 'name="[^"]*(hevc|h265)[^"]*decoder' | head -10
echo; echo "Is the Mac's Sill visible over mDNS from here? (needs Sill for Mac running on the same Wi-Fi)"
echo "  Checked later from the test app; adb shell can't browse mDNS."
