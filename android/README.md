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

## Telemetry over Bluetooth (SIYI MK32)

Not every ground station puts MAVLink on IP. The SIYI MK32 hands it to Android
over a **Bluetooth serial (SPP) link at 57600** — which is why a scan of eleven
UDP ports, every socket on the device and the whole `192.168.144.x` subnet found
nothing. There was nothing on the network to find. UniGCS on the same handheld
shows full telemetry with its Datalink set to "Bluetooth", which is what
identified it.

**⚙ CONNECTION → SETTINGS → Telemetry source → BLUETOOTH**, then pick the paired
datalink (or leave it on Auto, which takes the first paired device advertising
the serial profile).

Two things follow from RFCOMM being point-to-point:

- **The link is exclusive.** While this app holds it, no other app on the ground
  station can read the aircraft. That is why the app relays the raw stream on to
  the QGC port — anything else that wants telemetry takes it over UDP from there.
- **Commands go back the same way.** LOCK/UNLOCK/RTL are written to the serial
  link rather than sent to a UDP peer, and unlike UDP it does not need the
  duplicate send.

`BluetoothLink` tries three ways in — secure SPP, insecure SPP, then RFCOMM
channel 1 by reflection — because serial bridges vary and cheap ones often have
a missing or wrong SDP record. It reconnects with backoff if the link drops.

A serial link is a **byte stream**, not datagrams: a MAVLink frame can be split
across two reads and two frames can arrive in one. `MavlinkService.feed` buffers
and consumes only whole frames; handing a half frame to a parser that assumes
datagram boundaries silently drops telemetry. The reassembly is verified against
chunk sizes from 1 byte upward, including junk injected mid-stream.

## Finding MAVLink on an unfamiliar ground station

**⚙ CONNECTION → ◎ MAVLINK SCAN** answers "which port is the telemetry on?"
without a rebuild per guess. It is **read-only** — it opens its own sockets,
looks, and closes them; the live telemetry path is untouched.

It reports three things:

1. **This device's addresses** — whether it is on the datalink's subnet at all.
2. **Every UDP port open on the device**, from `/proc/net/udp`, with the owning
   uid. The port another app is listening on is visible here without guessing.
3. **What actually arrives** on ~11 common MAVLink ports, listened to
   simultaneously for 6 s, with the source address and any decoded MAVLink
   system and message ids.

**A silent result is not proof of absence.** On Linux a *unicast* datagram goes
to exactly one socket even when both set `SO_REUSEADDR`, so telemetry addressed
to another app's socket is invisible to (3). Broadcast and multicast reach every
bound socket and do show up. That is why (2) matters as much as (3): if nothing
is heard but a foreign app holds a plausible port, the transport is unicast and
the fix is a forwarding change, not a port change.

The scan holds a `MulticastLock` while it runs — Android drops broadcast and
multicast on Wi-Fi without one, which would hide a datalink that broadcasts to
`x.x.x.255`. That is what `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE`
in the manifest are for; neither prompts the user.

## Release build: signing, R8, and what it protects

`assembleDebug` is unchanged and needs no setup. A **release** build adds four
things, and none of them make the app secure — they raise the cost of copying
it from "read it over coffee" to real work. Anything that runs on hardware
someone else owns can eventually be taken apart.

### 1. Create a keystore, once

```
keytool -genkeypair -v -keystore dropaim-release.jks -alias dropaim \
        -keyalg RSA -keysize 4096 -validity 10000
```

Keep the .jks **off this repository and backed up**: lose it and you can never
ship an update that installs over an existing one.

### 2. android/keystore.properties (git-ignored)

```
storeFile=C:/keys/dropaim-release.jks
storePassword=...
keyAlias=dropaim
keyPassword=...
exportToken=<a long random string>
releaseSignatureSha256=        # fill in after the first release build
```

Without this file the project still builds; the release APK is simply unsigned
(with a warning) and the export is disabled. Nothing here is committed — the
repository has been public, and a secret in a source file is only as private as
the repository.

### 3. What each part does

| | Effect |
|---|---|
| **R8** (`minifyEnabled true`) | renames and strips the Kotlin. `jadx` no longer returns readable source |
| **Comment stripping** | removes ~24% of the shipped web app: every comment explaining *why* a constant is what it is |
| **Signature check** | the app refuses to run from a re-signed APK, once `releaseSignatureSha256` is set |
| **Export token** | from BuildConfig, not source; unset disables the USB export outright |

`proguard-rules.pro` keeps the handful of things reached by name rather than by
a visible call — chiefly `UploadWorker`, which WorkManager instantiates from a
class-name string and which would otherwise break in release only.

The comment stripper is **not** a minifier and deliberately does not try to be:
it deletes comments and trailing whitespace and touches nothing else. A minifier
that mangles the aim solver and ships a subtly wrong solution to an aircraft is
a far worse outcome than a readable APK, and the reward would be a few KB. Its
output is verified to produce **bit-identical** ballistics.

### 4. After the first release build

`Integrity.logFingerprint` prints the APK's signing SHA-256 to logcat at
startup. Put that value in `releaseSignatureSha256` and rebuild; the check is
inert until then, because an integrity check that bricks a build nobody could
yet configure is a self-inflicted outage.

### Installing over an existing app

**A release APK will not install over a debug one** — different signing key.
Android requires an uninstall, and uninstalling **wipes the licence activation
and every setting**: telemetry source, Bluetooth device, ports, camera URLs and
any measured zoom. Note them first, then re-activate after installing.

From then on, release-to-release updates install over the top normally.

## Browser CSS must render on Chrome/WebView 66

Same reason as the JS floor below, and the failure is worse because it is
silent. `grid-template-columns: 1fr clamp(170px,26vw,320px)` needs **Chrome
79**. On a ground station with an older WebView the browser could not parse the
value, so it dropped the **whole declaration** — the grid fell back to one
implicit column, and the video pane (whose children are all absolutely
positioned) collapsed to zero height. The operator got a full screen of
parameters and no video, from a build that looked perfect on every other
machine.

Run `npm run check`. Things to avoid, and what to write instead:

| Don't | Needs | Do |
|---|---|---|
| `clamp()` / `min()` / `max()` | Chrome 79 | stepped `@media` queries with fixed px |
| `inset: 0` | Chrome 87 | `top/right/bottom/left` longhand |
| `gap` on a **flex** container | Chrome 84 | margins — `.x > * + * {margin-top:7px}` |
| `aspect-ratio` | Chrome 88 | a `padding-top` percentage box |
| `:is()` / `:where()` | Chrome 88 | the selectors written out |

`gap` on a **grid** container is fine (Chrome 66); the checker allows it and the
`grid-gap` alias is written alongside for margin.

## Browser JS must parse as ES2017

The ground stations run old Android with a stock System WebView — the SIYI
handheld is Android 9, whose WebView is around Chrome 66 and is never updated.
**Everything in `../public` must parse as ES2017 (Chrome/WebView 58).**

This is not a style preference. A syntax error does not break one feature; it
takes the whole `<script>` block out at parse time, so every handler in it
silently stops existing. Two optional chains (`?.`, Chrome 80+) once made the
settings button, the target marking, the HUD, the link pills and the training
simulator all dead on the SIYI GCS at once, while the identical file worked in
desktop Chrome and in every headless test.

Run `npm run check` (or `node ../tools/js-compat-check.js`) before shipping a
web change; it lists the offending line and its replacement. Common ones:

| Don't | Do |
|---|---|
| `a?.b` | `a && a.b` |
| `a ?? b` | `(a === null \|\| a === undefined) ? b : a` |
| `{...x, ...y}` | `Object.assign({}, x, y)` |
| `a \|\|= b` | `a = a \|\| b` |

`public/index.html` also opens with a small ES5-only error trap that prints any
script error across the bottom of the screen. A ground station has no logcat, so
without it a failure like the above just looks like "some buttons don't work".
Keep it first in the document and keep it ES5 — if it cannot parse, nothing can
report that it could not.

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
