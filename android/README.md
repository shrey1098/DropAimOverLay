# DropAim — Android APK (standalone GCS app)

A self-contained Android app: no Node server, no manual start. It runs the
**exact, unchanged** DropAim web app (`../public/index.html`) inside a WebView,
with a tiny embedded server replicating what `server.js` used to do, natively:

| Job | Old (Node) | Now (native, in-app) |
|-----|------------|----------------------|
| Serve the UI + `/stream` + `/api/*` | Express | NanoHTTPD on `127.0.0.1:3000` |
| RTSP → MJPEG video | ffmpeg | FFmpegKit (same ffmpeg command) → MJPEG |
| MAVLink UDP parse / relay / commands | dgram | `MavlinkService.kt` (faithful port) |
| Telemetry to UI | ws | NanoHTTPD WebSocket `/telemetry` |
| Drop log | `drops.jsonl` | app-private `drops.jsonl` |

The web app is loaded from `http://127.0.0.1:3000/`, so **the tracker, physics,
offset and logging all work unchanged** — the WebView receives real JPEG frames
it can read pixels from.

> ⚠️ **This project has not been compiled or tested against real hardware.** It
> was written without an Android SDK or a device/camera available. Expect to fix
> a few things on the first build in Android Studio (dependency resolution,
> minor API signatures) and to tune the RTSP step against the actual C20 feed.
> Do not treat the first successful build as flight-verified — bench-test the
> video, telemetry, LOCK/RTL and a dummy drop before operational use.

## Build

1. Install **Android Studio** (latest). Let it install the Android SDK + a
   build-tools + platform 34 when prompted.
2. Open the `android/` folder as a project (not the repo root).
3. First sync downloads Gradle 8.7, AGP 8.5.2, and the libraries. If a library
   fails to resolve, see **Video** below.
4. Set the camera address if it differs: `Config.kt` → `rtspUrl`.
5. **Build → Build APK(s)** (or `./gradlew assembleDebug`). The APK lands in
   `app/build/outputs/apk/debug/app-debug.apk`.
6. Sideload to the G20: `adb install -r app-debug.apk`, or copy the APK across
   and tap it (enable "install unknown apps").
7. For a release build you must add a signing keystore (Build → Generate Signed
   Bundle/APK). Android will not install an unsigned release APK.

## How the web app gets into the APK

`app/build.gradle` has a `syncWebAssets` task that copies `../public/{index.html,
manifest.json,icon.svg}` into `app/src/main/assets/www/` before every build, so
`public/` stays the single source of truth. To copy manually:

```
bash sync-web.sh
```

## Video (RTSP) — the one likely sticking point

The app uses **FFmpegKit** to run the same ffmpeg command that already worked
with your camera (`-rtsp_transport tcp … -f image2pipe -vcodec mjpeg`). FFmpegKit
was retired upstream in 2025; the Maven Central artifacts for pinned versions are
still published, so `com.arthenica:ffmpeg-kit-https:6.0-2.LTS` should resolve.

If it does **not** resolve, two options:
- Try `com.arthenica:ffmpeg-kit-full:6.0-2.LTS` (larger, all codecs), or a
  community mirror repo added in `settings.gradle`.
- Swap `VideoPipe.kt` to decode RTSP with `androidx.media3:media3-exoplayer-rtsp`
  rendered to a `TextureView`, grabbing frames with `PixelCopy` → JPEG. This uses
  only Google libraries but ExoPlayer's RTSP is less tolerant of some cameras.

## Ports / config

All in `Config.kt` — mirrors the old `CONFIG`:
- HTTP/WebView: `3000` (loopback)
- MAVLink in (from datalink): `14551`
- QGC relay: `14550` (loopback)
- RTSP: `rtspUrl`

## Known limitations / TODO before ops

- Runs in a single foreground **Activity**; if you need video + MAVLink to
  survive the screen turning off, promote the services to a foreground Service.
- The launcher icon is a placeholder vector.
- No release signing config is included (add your keystore).
- The WebView service worker caches the UI; after updating the web app, clear the
  app's storage or bump the cache name.
