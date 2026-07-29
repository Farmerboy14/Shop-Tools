# Silage Loads — native Android app

The tracker as a real installable app, so it can do the things a web page can't:
count loads **with the screen off and the app closed**, and put a **home-screen
widget** with +/− buttons on your launcher.

## What it does

- **Big + LOAD HAULED button**, undo, and today / yesterday / job-total counts.
- **Auto count via a dump zone.** Park at the pit, set the zone, flip AUTO ON.
  Every arrival counts a load by itself — handled by Android's geofencing, which
  keeps working with the phone in your pocket and costs very little battery.
  Each auto load is stamped with the time and coordinates and tagged AUTO.
- **Home-screen widget** showing the day's count with + and − buttons.
- **Per-load history** — tap a day to see every load with its time and a MAP
  button that opens the exact spot.
- **Jobs** — start a fresh tracker per field; switch, rename, delete.
- **Keep screen on** and **auto-dim** (near-black idle screen) for dash use.
- Counts live on the phone. Nothing is uploaded anywhere.

## Building it

You need [Android Studio](https://developer.android.com/studio) (free) on any
Windows, Mac, or Linux machine. The Gradle wrapper is checked in, so:

1. Android Studio → **Open** → pick this `android/` folder.
2. Let it sync (it downloads Gradle and the Android SDK on first run).
3. Plug your phone in with USB debugging on, then press **Run** (▶).

Prefer the command line? From this folder:

```sh
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and push to a connected phone
```

To sideload without a cable, copy `app-debug.apk` to the phone and open it
(you'll be asked to allow installs from that app once).

Release builds are wired to the debug signing key so `assembleRelease` also
produces something installable. Swap in your own keystore before any Play Store
upload.

## Permissions it asks for, and why

| Permission | Why |
|---|---|
| Location — **Allow all the time** | Required for the dump zone to count a load while the app is closed. Android asks for this separately from normal location access; without it, auto counting only works with the app open. |
| Notifications | Posts a quiet "Load counted" note when the zone fires, so there's proof it happened. |
| Boot completed | Geofences are dropped when the phone restarts; this puts them back. |

## Verifying the data layer

`LoadStore` holds all the counting and persistence that the app, the widget, and
the background receiver share. It can be tested on a plain JVM, no Android SDK
and no device:

```sh
./verify/run.sh
```

This compiles the real `LoadStore.kt` against an in-memory `SharedPreferences`
and runs assertions over counting, undo clamping, per-load GPS stamping, job
isolation, zone handling, migration from the web app's older storage format, and
recovery from corrupt data.

## Moving your counts over from the web version

Both store the same JSON shape, so the web app's `silage-loads-v1` localStorage
value can be dropped into this app's `silage_loads` preference as `state`. There
is no in-app import button yet — say the word and it can be added.
