#!/usr/bin/env bash
# Drives OpenTune Videos on an emulator the way a TV remote would, taking a
# screenshot at each step and measuring how smooth the pages are.
#
#   videotv-smoke.sh <apk> <results-dir>
#
# Writes <results-dir>/NN-step.png, jank.txt (Android's frame statistics per
# step), summary.txt (what happened), logcat.txt and crash.txt. Never stops
# part way: every step runs, so one failure doesn't hide the rest.
set -u
APK="$1"
OUT="$2"
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

say "== logs"
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
adb logcat -d 2>/dev/null | grep -E "OpenTune|AndroidRuntime|FATAL|ExoPlayer|MediaCodec.*rror" | tail -600 > "$OUT/logcat.txt"
if [ -s "$OUT/crash.txt" ]; then say "CRASHES: see crash.txt"; else say "no crashes logged"; fi
grep -E "video .* via |playback failed|no stream|gave no video" "$OUT/logcat.txt" | tail -20 | sed 's/^/[playback] /' | tee -a "$OUT/summary.txt"
exit 0
