# OpenTune Videos (Android TV)

A separate Android TV / Google TV app built on OpenTune's `:core`. It does one thing: plays **music videos** from YouTube Music, full screen, with no ads, no account and no tracking.

- **Home**: rows of music videos: chart and new-release video shelves, then rows by genre (Pop, Hip-hop, K-pop, Bollywood, Latin, Rock, …). Every row comes from YouTube Music's *video* uploads only, never audio-only tracks.
- **Search**: searches music videos only, with results in a 4-column grid. It also answers the TV's voice search ("Play … on OpenTune Videos").
- **Player**: Media3's remote-friendly controls. OK pauses, left/right seek, skip goes through the queue, and Back hides the controls and then leaves the player.
- **Autoplay**: when a video ends, the next one in the row plays. When the row runs out, the queue is topped up with the music videos from YouTube Music's radio for the last video, or more videos by the same artist if the radio has none.
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

On GitHub, `.github/workflows/videotv.yml` builds just this app on every push that touches it. Download the APK from the run's **OpenTune-Videos-TV** artifact. The main CI builds it too, along with everything else.

## Licence

GPL-3.0, like OpenTune.
