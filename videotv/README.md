# OpenTune Videos (Android TV)

A separate Android TV / Google TV app built on OpenTune's `:core`. It does one thing: plays **music videos** from YouTube Music, full screen, with no ads, no account and no tracking.

- **Home**: rows of music videos: chart and new-release video shelves, then rows by genre (Pop, Hip-hop, K-pop, Bollywood, Latin, Rock, …). Every row comes from YouTube Music's *video* uploads only, never audio-only tracks.
- **Search**: searches music videos only, with results in a 4-column grid. It also answers the TV's voice search ("Play … on OpenTune Videos").
- **Player**: Media3's remote-friendly controls. OK pauses, left/right seek, skip goes through the queue, and Back hides the controls and then leaves the player.
- **Autoplay**: when a video ends, the next one in the row plays. When the row runs out, the queue is topped up with the music videos from YouTube Music's radio for the last video, or more videos by the same artist if the radio has none.
- **Library**: your YouTube Music playlists and liked songs, after signing in with the OpenTune phone app (scan a code, no typing). Songs play as their music videos: YouTube Music's own pairing of song and video first, then a title-and-artist search. A song with no video plays with its cover art.
- **Queue**: leaving the player doesn't stop the video. The sound keeps playing while you browse, with a now-playing card at the end of the tab bar; the picture is switched off until you return to full screen (OK on the card, or Back on a main page), which keeps browsing smooth. Picking a video plays just that one. While one plays, picking offers **Play now**, **Play next** or **Add to queue**, and holding OK adds straight to the queue. In the player, Down (with the controls hidden) opens **Up next**, where you can play, move or remove queued videos, like the current one, or save it to a playlist. The Queue tab does the same from the pages.
- **For you**: signed in, Home starts with YouTube Music's picks for the account and its mixes.
- **Phone remote**: pair the OpenTune phone app (Settings → Phone remote) for a trackpad, D-pad, keyboard and media keys.
- **Settings**: a quality ceiling (480p–1080p, plus 1440p/4K on TVs with a hardware VP9 decoder) and an autoplay switch.

Its application ID is `com.opentune.videotv`, so it installs next to OpenTune and OpenTune TV.

## How video works

`core/.../innertube/VideoStreams.kt` is the video counterpart of `StreamResolver`, which is audio-only:

1. It walks the same Innertube player clients in the same order. From the first client that serves the video, it picks the tallest H.264 video-only stream under the quality ceiling (or VP9 above 1080p, when allowed) plus the best AAC audio stream. It unlocks both URLs with NewPipe's signature and `n` solvers, as the audio path does, and checks that the video URL really serves bytes.
2. If no client yields a usable pair, it uses the iOS client's HLS manifest (picture and sound together).
3. As a last resort it uses NewPipeExtractor (video-only + audio, or a muxed progressive stream).

The player merges video and audio with `MergingMediaSource`. Each file is read in 10 MB ranges (`ChunkedDataSource`), the way YouTube's own players fetch it, which avoids googlevideo throttling long open-ended downloads.

## Build

Same requirements as the rest of the repo: JDK 17+ and an Android SDK with platform 37.

```bash
./gradlew :videotv:assembleDebug
# APK: videotv/build/outputs/apk/debug/videotv-debug.apk
```

Install on a TV that has developer options and network debugging turned on:

```bash
adb connect <tv-ip>:5555
adb install -r videotv/build/outputs/apk/debug/videotv-debug.apk
```

On GitHub, `.github/workflows/videotv.yml` builds just this app on every push that touches it and publishes it at a fixed address:

```
https://github.com/<owner>/<repo>/releases/download/videotv-latest/OpenTune-Videos.apk
```

With the repository secrets `VIDEOTV_KEYSTORE` (the release keystore, base64) and `VIDEOTV_KEYSTORE_PASSWORD` set, that APK is a signed, optimised release. Each build's version code counts up, so it installs straight over the previous one. Without the secrets it is a debug build.

## Licence

GPL-3.0, like OpenTune.
