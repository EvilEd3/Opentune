#!/usr/bin/env bash
# Drives OpenTune Videos on an emulator the way a TV remote would, taking a
# screenshot at each step and measuring how smooth the pages are.
#
#   videotv-smoke.sh <apk> <results-dir> [test-clip.mp4]
#
# Writes <results-dir>/NN-step.png, jank.txt (Android's frame statistics per
# step), summary.txt (what happened), logcat.txt and crash.txt. Never stops
# part way: every step runs, so one failure doesn't hide the rest.
set -u
APK="$1"
OUT="$2"
CLIP="${3:-}"
PKG=com.opentune.videotv
mkdir -p "$OUT"
: > "$OUT/summary.txt"
: > "$OUT/jank.txt"

say() { echo "$*" | tee -a "$OUT/summary.txt"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; say "screenshot: $1"; }
key() { for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
alive() {
  if adb shell pidof "$PKG" >/dev/null 2>&1; then say "[$1] app running"; else say "[$1] APP NOT RUNNING (crashed?)"; fi
}
jank_reset() { adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1; }
jank() {
  adb shell dumpsys gfxinfo "$PKG" 2>/dev/null \
    | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" \
    | sed "s/^/[$1] /" | tee -a "$OUT/jank.txt"
}

say "== install"
adb shell settings put secure screensaver_enabled 0 >/dev/null 2>&1
adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
adb shell svc power stayon true >/dev/null 2>&1
adb install -r -g "$APK" 2>&1 | tail -1 | tee -a "$OUT/summary.txt"
adb logcat -c

say "== 1. launch, Home loads"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 40
shot 01-home
alive home

say "== 2. scroll Home, nothing playing (baseline smoothness)"
jank_reset
key DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP DPAD_LEFT
jank home-scroll-nothing-playing
shot 02-home-scrolled

say "== 3. play a video (voice-search intent: search and play the first)"
adb shell "am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -n $PKG/.MainActivity --es query 'dua lipa houdini'" >/dev/null
sleep 30
shot 03-player
alive player
sleep 8
shot 04-player-controls-hidden

say "== 4. Up next panel (Down with the controls hidden)"
key DPAD_DOWN
sleep 2
shot 05-up-next
key BACK
sleep 7

say "== 5. Back to the pages while it plays"
key BACK
sleep 4
shot 06-pages-while-playing
alive pages-while-playing

say "== 6. scroll Home while playing"
jank_reset
key DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP DPAD_LEFT
jank home-scroll-while-playing
shot 07-home-scrolled-while-playing

say "== 7. a playlist page (public playlist, found by search)"
adb shell "am start -n $PKG/.MainActivity --es opentune.test.playlist_query 'top hits'" >/dev/null
sleep 20
shot 08-playlist
jank_reset
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP
jank playlist-scroll-while-playing
shot 09-playlist-scrolled

say "== 8. pick a track while playing: the menu, then Down x3 (must stay in the menu)"
key DPAD_CENTER
sleep 2
shot 10-menu
key DPAD_DOWN DPAD_DOWN DPAD_DOWN
sleep 1
shot 11-menu-after-down
key BACK
sleep 2
shot 12-playlist-after-menu

say "== 9. hold OK on a track: straight into the queue"
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep 2
shot 13-after-hold-ok

say "== 10. Queue tab and Back to full screen"
key BACK
sleep 2
shot 14-library-after-back
key BACK
sleep 4
shot 15-back-to-full-screen
alive end

say "== logs (YouTube part)"
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
adb logcat -d 2>/dev/null | grep -E "OpenTune|AndroidRuntime|FATAL|ExoPlayer|MediaCodec.*rror" | tail -600 > "$OUT/logcat-youtube.txt"

if [ -n "$CLIP" ] && [ -f "$CLIP" ]; then
  say "== 11. the player's controls, on a 20-minute test clip"
  # Served over the emulator's loopback (adb reverse) by a small web server that
  # answers byte ranges, as YouTube's servers do, so seeking works as it would.
  python3 "$(dirname "$0")/range-server.py" "$(dirname "$CLIP")" 8099 > "$OUT/range-server.log" 2>&1 &
  SERVER=$!
  sleep 1
  adb reverse tcp:8099 tcp:8099 >/dev/null && say "clip served on the emulator's 127.0.0.1:8099"
  adb logcat -c
  adb shell "am start -n $PKG/.MainActivity --es opentune.test.video_url http://127.0.0.1:8099/$(basename "$CLIP")" >/dev/null
  sleep 12
  shot 20-clip-playing
  sleep 6
  shot 21-clip-controls-hidden
  say "-- Right x3 (expect +30 s), then Left (expect -10 s)"
  key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT
  shot 22-after-right-x3
  key DPAD_LEFT
  shot 23-after-left
  sleep 3
  say "-- OK with the controls hidden (expect: paused, controls up, focus on Play/Pause)"
  key DPAD_CENTER
  sleep 1.5
  shot 24-after-ok
  say "-- OK again (expect: playing)"
  key DPAD_CENTER
  sleep 1.5
  shot 25-after-ok-again
  say "-- Down to the time bar, Right x3 (expect about +30 s, not +3 min)"
  key DPAD_DOWN
  shot 26-time-bar-focused
  key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT
  sleep 2.5
  shot 27-after-time-bar-x3
  say "-- leave to the pages while it plays"
  sleep 6
  key BACK
  sleep 5
  shot 28-pages-while-clip-plays
  jank_reset
  key DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_UP DPAD_UP DPAD_LEFT
  jank pages-scroll-while-clip-plays
  say "-- Back to full screen (expect: no pause, no jump)"
  key BACK
  sleep 1
  key BACK
  sleep 4
  shot 29-back-in-full-screen
  sleep 5
  shot 30-full-screen-5s-later
  alive clip-end
  adb logcat -d 2>/dev/null | grep "test:" | sed 's/^.*test: /[player] /' >> "$OUT/summary.txt"
  grep '^\[player\]' "$OUT/summary.txt" | tail -60
  kill "$SERVER" 2>/dev/null
fi

say "== logs"
adb logcat -d -b crash >> "$OUT/crash.txt" 2>/dev/null
adb logcat -d 2>/dev/null | grep -E "OpenTune|AndroidRuntime|FATAL|ExoPlayer|MediaCodec.*rror" | tail -600 > "$OUT/logcat.txt"
if [ -s "$OUT/crash.txt" ]; then say "CRASHES: see crash.txt"; else say "no crashes logged"; fi
grep -E "video .* via |playback failed|no stream|gave no video" "$OUT/logcat-youtube.txt" | tail -20 | sed 's/^/[playback] /' | tee -a "$OUT/summary.txt"
exit 0
