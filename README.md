# Tread Sync

An Android app that connects to a walking pad or treadmill over Bluetooth, records each walk automatically, and syncs it to Health Connect (which Google Health reads) and to Strava.

## How it works

1. The app connects to the treadmill using the standard Bluetooth Fitness Machine Service (FTMS) and reads speed, distance and (when the treadmill reports them) calories.
2. A workout starts when the belt moves, pauses when it stops, and finishes on its own after the belt has been stopped for 2 minutes (configurable). You can also tap **Finish and sync**, on the main screen or in the notification.
3. Each finished workout is saved on the phone, then:
   - written to Health Connect as a walking session with distance, steps, speed and calories;
   - converted to a FIT file and uploaded to Strava as an indoor walk (`trainer=1`, sport walking, sub-sport treadmill).
4. Anything that fails (no network, permissions missing) stays pending and is retried the next time the app opens, or with **Retry sync** on the workout's page.

With **Start automatically** on, Android wakes Tread Sync when the treadmill turns on, so walks are recorded without opening the app (Android 12 or newer). The recording notification stops on its own once the treadmill has been off for 5 minutes.

Steps are estimated from distance and the step length in Settings, because FTMS does not carry a step count. To measure your step length, count steps for one minute at your usual speed: length in inches = mph × 1056 ÷ steps, or length in cm = km/h × 1667 ÷ steps.

Only one app can be connected to the treadmill at a time. Turn off **Start automatically** (or tap Disconnect) before using another treadmill app such as FitYo.

## Screens

- **Main:** connection status, a large timer, distance, speed, steps and calories, belt controls (start, pause, speed up and down) when the treadmill accepts them, and recent workouts with their sync status.
- **Workout:** stats, a speed chart with pauses shaded, sync status with retry, a link to the Strava activity, and FIT file sharing.
- **Settings:** units (imperial or metric), treadmill pairing and auto-start, Health Connect, Strava and the activity title, step length, auto-finish delay, and diagnostics.

## One-time setup

### Strava API application

1. Go to https://www.strava.com/settings/api and create an application. Set **Authorization Callback Domain** to `localhost`. Any name, category and website work, and Strava asks for an icon image afterwards (`strava-app-icon.png` in this repository works).
2. In Tread Sync, open Settings, paste the **Client ID** and **Client Secret** into the Strava section and tap **Connect Strava**. Approve the "Upload your activities" permission.

The secret and tokens are stored in the app's private storage on your phone. New Strava API applications are limited to one athlete, so everyone who uses Tread Sync creates their own.

### Health Connect

Tap **Grant permission** in the Health Connect section of Settings. The app only asks to write data.

## Build and install

The build uses a JDK and Android SDK installed under the home directory:

```bash
export JAVA_HOME=$HOME/.local/jdk/current PATH=$HOME/.local/jdk/current/bin:$HOME/Android/Sdk/platform-tools:$PATH
```

Run the unit tests and build the APK:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Install on a phone connected by USB (or wireless debugging) with developer options enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it (allow installing from unknown sources).

## If the treadmill is not FTMS

If the app says it is connected with an unknown protocol, the treadmill uses a proprietary Bluetooth format. Walk for a minute, then open **Settings > Diagnostics** and tap **Share**. The shared text lists every Bluetooth service and the raw bytes the treadmill sends, which is enough to add a decoder for it in `ble/`.

## Code layout

- `ble/FtmsParser.kt`: decodes FTMS Treadmill Data packets.
- `ble/TreadmillConnection.kt`: GATT connection, reconnects, FTMS control point, raw logging.
- `ble/Companion.kt`: Companion Device Manager pairing and the service Android wakes when the treadmill appears.
- `workout/WorkoutRecorder.kt`: auto start, pause and finish logic, distance accumulation that survives odometer resets.
- `fit/FitEncoder.kt`: writes the FIT activity file.
- `sync/HealthConnectSync.kt`, `sync/StravaClient.kt`, `sync/SyncManager.kt`: the two destinations and retry bookkeeping.
- `RecordingService.kt`: foreground service that keeps recording with the screen off.
- `ui/`: Compose screens (main, settings, workout detail) and shared components.
