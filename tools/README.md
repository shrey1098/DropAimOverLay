# DropAim — Owner's tools (activation + metrics)

Everything here stays on **your** PC. Nothing in this folder ships with the app.

## One-time setup

```
cd tools
node dropaim-licence.js init
```

Creates:

| file | what it is |
|---|---|
| `dropaim-private.pem` | **THE SECRET.** Only this can mint activation codes. Back it up offline. Never commit it, never send it, never put it on the GCS. |
| `dropaim-public.txt` | Safe to share. Goes into the app. |

Then paste the contents of `dropaim-public.txt` into
`android/app/src/main/java/com/dropaim/app/Licence.kt`:

```kotlin
private const val PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQ...."
```

and rebuild the APK. **Every device you ever activate depends on this one key** —
if you lose the private key, previously issued codes keep working but you can
never issue another, and you would have to ship a new APK with a new key.

> Run `init` **once**. It refuses to overwrite an existing key, because a new
> key would invalidate every code already issued.

## Activating a new GCS

1. Operator installs the APK and opens it. It is **locked** and shows a Device ID:
   `DA-7F3C-9B21-E45A`
2. They send you that ID — WhatsApp, phone, radio, written on paper. No internet needed.
3. You issue a code:
   ```
   node dropaim-licence.js issue DA-7F3C-9B21-E45A --unit "51 Fd Regt"
   ```
4. Send the printed code back. They paste it in and press ACTIVATE. Done, permanently.

Every issue is appended to `issued-licences.csv` so you have a register of who
has what.

To check a code yourself before sending:
```
node dropaim-licence.js verify DA-7F3C-9B21-E45A <CODE>
```

**A code only works on the device it was issued for.** Pasting it into a second
GCS produces a different Device ID and is refused. The check re-runs on every
launch, so copying the stored licence file across does not work either.

### If a device is re-flashed / factory reset
The Device ID is derived from the Android ID, which changes on a factory reset.
The unit will report a new ID and you simply issue a new code. The old row stays
in your register.

## Collecting usage metrics

Two routes. Both are enabled; use whichever fits.

### a) Automatic, over the internet
Set in `Config.kt`:
```kotlin
const val METRICS_URL = "https://your-server.example/dropaim/metrics"
```
The app then posts new records in the background roughly every 6 hours **whenever
the device has a connection**, including while the app is closed, and after a
reboot. Records are only marked sent once your server answers 2xx, so a dropped
connection retries rather than loses data.

Your endpoint receives:
```json
{ "device_id": "DA-7F3C-9B21-E45A",
  "app_version": "1.0",
  "records": [ {"ts":"…","event":"drop","alt_agl_m":150,"miss_m":2.1, …} ] }
```
Leave `METRICS_URL` unchanged to disable uploading entirely.

### b) By USB, from your PC — no upload needed
There is deliberately **no export button in the app**; an operator cannot pull the
logbook. Plug the GCS into your PC and run:

```
adb shell am broadcast -a com.dropaim.app.EXPORT \
     -n com.dropaim.app/.ExportReceiver --es token CHANGE-ME-EXPORT-TOKEN

adb pull /sdcard/Android/data/com.dropaim.app/files/dropaim_metrics.csv
adb pull /sdcard/Android/data/com.dropaim.app/files/dropaim_drops.jsonl
```

(Set your own `EXPORT_TOKEN` in `Config.kt` before building.) The second file is
the full per-drop log — the one that feeds the drag calibration.

## What gets recorded

`activated`, `app_start`, `session_end` (minutes), and `drop` (altitude, wind,
miss, raw downwind residual, whether the offset was on) — each stamped with the
device ID, timestamp and app version.

## Honest limits

- **This stops copying and sharing. It does not stop a determined reverse-engineer.**
  The APK runs on someone else's hardware; anyone with the file, the skill and
  the time can decompile it and remove the check. That is true of every offline
  licensing scheme. It is a lock on the door, not a vault.
- **Background upload can be throttled by the device.** Aggressive OEM battery
  optimisation may delay or kill periodic work. Exempting the app from battery
  optimisation on the GCS makes it reliable; otherwise treat USB export as the
  dependable route.
- **Think about where the metrics land.** Operational altitudes, winds and miss
  distances leaving a controlled network is a security decision, not just a
  technical one. If in doubt, leave `METRICS_URL` unset and use USB only.
