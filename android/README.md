# DropAim — Android APK (standalone GCS app)

A self-contained Android app: no Node server, no manual start. It runs the
**exact, unchanged** DropAim web app (`../public/index.html`) inside a WebView,
with a tiny embedded server replicating what `server.js` used to do, natively:

| Job | Old (Node) | Now (native, in-app) |
|-----|------------|----------------------|
| Serve the UI + `/stream` + `/api/*` | Express | NanoHTTPD on `127.0.0.1:3000` |
| RTSP video | ffmpeg | Media3/ExoPlayer → TextureView → JPEG frames |
| MAVLink UDP parse / relay / commands | dgram | `MavlinkService.kt` (faithful port) |
| Telemetry to UI | ws | NanoHTTPD WebSocket `/telemetry` |
| Drop log | `drops.jsonl` | app-private `drops.jsonl` |

The **training simulator** (`sim.js`) is bundled too: it synthesises the feed and
telemetry so operators can practise the full engagement with no aircraft. It is
switched on from the TRAINING SIMULATOR panel in the sidebar.

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
4. Set the camera address if it differs: `Config.kt` → `cameras`. (Not required
   for a one-off: ports and camera URLs are editable in the app itself — see
   **Ports / config**.)
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

## Video (RTSP)

Media3/ExoPlayer decodes the RTSP stream to a `TextureView` (hardware decode, no
ffmpeg binary). `VideoPipe` grabs frames off that surface at `VIDEO_FPS`, encodes
them as JPEG and publishes them to the embedded server's `/stream`, so the WebView
can both display the feed **and read its pixels** — which the Lucas-Kanade target
tracker requires.

RTP-over-TCP is forced, matching the old `-rtsp_transport tcp`.

That JPEG round-trip is the price of keeping the tracker in the WebView; it is
what the planned native-video milestone removes (native SurfaceView + OpenCV
tracker), at which point the feed goes straight to the screen with no re-encode.

If the camera does not open, check logcat for `VideoPipe` — the player logs the
RTSP error code and retries every 3 s. Some cameras need the exact stream path
(`/main`, `/stream1`, …); set it in `Config.kt`.

## Ports / config

Defaults are in `Config.kt` — mirrors the old `CONFIG`:
- HTTP/WebView: `3000` (loopback)
- MAVLink in (from datalink): `14551`
- QGC relay: `14550` (loopback)
- RTSP: `cameras`

**The three that vary between ground stations are editable in the app**, under
**⚙ CONNECTION → SETTINGS** at the bottom of the side panel: the MAVLink listen
port, the QGC port, and each camera's RTSP URL. Skydroid delivers telemetry to
`14551`; a SIYI controller may hand it to QGC on `14550` instead, and a camera
may sit at a different address — none of which should need a rebuild, a
reinstall and a fresh activation code.

Saved in `SharedPreferences` on the device (`Settings.kt`) and applied
immediately: changing a port rebinds the UDP socket, changing a URL re-opens the
stream, and only what actually changed is restarted, so editing a camera URL
does not drop telemetry. Clearing a URL restores the compiled-in default;
**RESTORE DEFAULTS** clears all of them. `Config.kt` holds the defaults, and
nothing here touches the ballistics or the aim solution.

## Typechecking without an Android SDK

`../tools/kotlin-typecheck.sh` compiles every Kotlin source in the app in about a
minute, with no Android SDK and no Gradle. It exists because a full
`assembleDebug` needs `android.jar`, AAPT2, AGP and the androidx AARs, all of
which come from `dl.google.com` — on a machine that cannot reach that host there
is otherwise no way to find a Kotlin error before the APK fails to build.

It compiles against a real `android.jar` (Robolectric's `android-all` for API 34,
from Maven Central), the real NanoHTTPD jars, and hand-written stubs in
`../tools/ktcheck/stubs` for androidx alone — whose signatures are transcribed
from upstream source at the versions `app/build.gradle` pins.

Catches syntax, types, null-safety, overload resolution, bad overrides and our
own API misuse. Does **not** cover resources, the manifest, ProGuard or
packaging. It is a fast gate, not a replacement for a real build before you fly.

If you bump `compileSdk`, the Kotlin plugin version or `jvmTarget`, update the
constants at the top of the script to match.

## Known limitations / TODO before ops

- Runs in a single foreground **Activity**; if you need video + MAVLink to
  survive the screen turning off, promote the services to a foreground Service.
- The launcher icon is a placeholder vector.
- No release signing config is included (add your keystore).
- The WebView service worker caches the UI; after updating the web app, clear the
  app's storage or bump the cache name.
