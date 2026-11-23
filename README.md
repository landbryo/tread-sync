# FitYo Connect

An Android app that connects to a walking pad over Bluetooth, records each walk automatically, and syncs it to Health Connect (which Google Health reads) and to Strava.

## How it works

1. The app connects to the treadmill using the standard Bluetooth Fitness Machine Service (FTMS) and reads speed, distance and (when the treadmill reports them) calories.
2. A workout starts when the belt moves, pauses when it stops, and finishes on its own after the belt has been stopped for 2 minutes (configurable). You can also tap "Finish and sync now".
3. Each finished workout is saved on the phone, then:
   - written to Health Connect as a walking session with distance, steps, speed and calories;
   - converted to a FIT file and uploaded to Strava as an indoor walk (`trainer=1`, sport walking, sub-sport treadmill).
4. Anything that fails (no network, permissions missing) stays "pending" and is retried the next time the app opens, or with the Retry button.

Steps are estimated from distance and the step length in Settings, because FTMS does not carry a step count. Compare a walk against FitYo's step count once and adjust the step length to calibrate.

Only one app can be connected to the treadmill at a time. Close FitYo (or turn off its connection) before connecting here, and tap Disconnect here before using FitYo.

## One-time setup

### Strava API application

1. Go to https://www.strava.com/settings/api and create an application. Any name and website work. Set **Authorization Callback Domain** to `localhost`.
2. In the app, paste the **Client ID** and **Client Secret** into the Strava card and tap **Connect Strava**. Approve the "Upload your activities" permission.

The secret and tokens are stored in the app's private storage on your phone.

### Health Connect

Tap **Grant permissions** in the Health Connect card. The app only asks to write data. FitYo also writes to Health Connect, so if you ever record the same walk in both apps you will see it twice.

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

If the Treadmill card shows "unknown protocol", the walking pad uses a proprietary Bluetooth format. Walk for a minute, then open **Diagnostics** and tap **Share**. The shared text lists every Bluetooth service and the raw bytes the treadmill sends, which is enough to add a decoder for it in `ble/`.

## Code layout

- `ble/FtmsParser.kt`: decodes FTMS Treadmill Data packets.
- `ble/TreadmillConnection.kt`: GATT connection, reconnects, FTMS control point, raw logging.
- `workout/WorkoutRecorder.kt`: auto start, pause and finish logic, distance accumulation that survives odometer resets.
- `fit/FitEncoder.kt`: writes the FIT activity file.
- `sync/HealthConnectSync.kt`, `sync/StravaClient.kt`, `sync/SyncManager.kt`: the two destinations and retry bookkeeping.
- `RecordingService.kt`: foreground service that keeps recording with the screen off.
