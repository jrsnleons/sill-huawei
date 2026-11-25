#!/bin/bash
# Sill over a USB cable, for the MatePad client.
#
# Keeps `adb reverse tcp:47455 tcp:<Sill's home port>` up for every tablet plugged into this Mac, so the
# tablet reaches Sill for Mac at 127.0.0.1:47455 over the cable instead of Wi-Fi. Sill for Mac serves a
# loopback client as a device at home (full quality). Pair the tablet over Wi-Fi once first.
#
# Run it in a Terminal tab and leave it running; Ctrl-C stops it and removes the forwards.
# Needs adb (brew install android-platform-tools) and USB debugging on the tablet.

set -u
TABLET_PORT=47455
REMOTE_DOOR=7455   # Sill's away-from-home port, never the one to forward

command -v adb >/dev/null || { echo "adb not found: brew install android-platform-tools"; exit 1; }

cleanup() {
  for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
    adb -s "$s" reverse --remove tcp:$TABLET_PORT >/dev/null 2>&1
  done
  echo; echo "USB link stopped."
  exit 0
}
trap cleanup INT TERM

# Sill's home door: the TCP port the Sill app listens on, other than the remote door.
sill_port() {
  lsof -nP -a -c Sill -iTCP -sTCP:LISTEN 2>/dev/null \
    | awk 'NR>1 {n=split($9,a,":"); print a[n]}' \
    | grep -vx "$REMOTE_DOOR" | sort -u | head -1
}

last=""
echo "Sill USB link: waiting for Sill and a tablet… (Ctrl-C to stop)"
while true; do
  port=$(sill_port)
  devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ -z "$port" ]; then
    msg="Sill isn't running on this Mac. Open it."
  elif [ -z "$devices" ]; then
    msg="No tablet on the cable (is USB debugging on, and did you tap Allow?)."
  else
    for s in $devices; do
      want="tcp:$TABLET_PORT tcp:$port"
      if ! adb -s "$s" reverse --list 2>/dev/null | grep -q "$want"; then
        adb -s "$s" reverse tcp:$TABLET_PORT tcp:"$port" >/dev/null
      fi
    done
    msg="Linked: $(echo $devices | wc -w | tr -d ' ') tablet(s) → Sill on port $port. Open Sill on the tablet."
  fi
  [ "$msg" != "$last" ] && { echo "$(date +%H:%M:%S)  $msg"; last="$msg"; }
  sleep 3
done
