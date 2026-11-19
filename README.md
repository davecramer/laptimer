# laptimer

A lap timer for track days: an Android app that records a RaceBox GPS
receiver over Bluetooth LE and times laps against tracks taken from
OpenStreetMap.

## Modules

- `core`: plain Kotlin, no Android dependencies, so it builds and tests on
  any JVM.
  - `Ubx.kt`: UBX framing and a stream parser that reassembles frames split
    across Bluetooth notifications.
  - `RaceBox.kt`: RaceBox BLE UUIDs and the decoder for data messages
    (class `0xFF`, ids `0x01` live and `0x21` history).
  - `GpsFix.kt`: a decoded fix in SI units.
  - `SessionLog.kt`: the session file format (see the comment at the top of
    the file). The log stores raw Bluetooth notifications, so a session
    recorded today can be replayed through a corrected parser later.
  - `Geo.kt`: flat east/north coordinates around a point, distances,
    bearings, and snapping a point to a polyline.
  - `Track.kt`: tracks and timing lines, and the JSON file each track is
    saved in. A timing line's two ends are named for the driver's left and
    right, which sets the direction a crossing counts in.
  - `OsmApi.kt`: finds tracks in OpenStreetMap through the Overpass API
    (`highway=raceway` ways joined where they share a node, and
    `leisure=track` areas), and finds places by name through Nominatim.
- `replay`: command-line tool that replays a session log through the same
  parser and decoder the app uses, prints a summary, and can export fixes
  to CSV.
- `app`: the Android app (Android 12 and later). It scans for a RaceBox,
  records in a foreground service, reconnects after a dropout, and shows
  live fix data. The Tracks tab finds a track on the map and places the
  start/finish line, sector lines, and a separate finish line for point to
  point tracks. Building it needs the Android SDK; `local.properties`
  points at it.

## Adding a track

1. In the Tracks tab, tap "Add a track", then search for the place by
   name, or pan the map to it.
2. Zoom in so the track fills the map and tap "Find tracks on this map".
   Pick the track from the list. Its outline from OpenStreetMap is drawn in
   blue.
3. Tap "Place" next to the start/finish line, then tap the track where the
   line is. The line snaps across the track. Tap away from the track to
   place both ends by hand instead: the driver's left end first.
4. Check that the arrow points the way you drive, and tap Flip if not.
   When the OpenStreetMap ways are tagged one-way, the arrow already
   follows the driving direction.
5. Add sector lines in driving order, then Save.

## Online services

- Map tiles come from OpenFreeMap: free, with no key, and commercial use
  allowed. MapLibre shows the required attribution.
- Track search uses the public Overpass servers, which volunteers run and
  which are often busy. The app tries three of them in turn. Their guidance
  is that a published app with many users should run its own server, so
  the server list is a parameter of `OsmClient`.
- Track outlines are OpenStreetMap data under the ODbL. Each track file
  records the elements it came from and the attribution.

- `racebox-emulator`: an Android app that acts as a RaceBox over
  Bluetooth LE, so the lap timer can be tested without a car. It drives
  simulated laps of Laguna Seca (built in), a test oval, or any track found
  in OpenStreetMap, and sends 25 Hz data frames exactly as the device does.
  The speed model (`core/DrivingSimulator.kt`) brakes for corners within a
  grip limit, varies each lap slightly, and adds GPS noise. It can also
  lose the GPS fix on demand.

## Build and test

    ./gradlew test

## Install on a phone

Turn on USB debugging on the phone, connect it, then:

    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Sessions are saved on the phone in the app's folder. Copy them off with:

    adb pull /sdcard/Android/data/laptimer.app/files/sessions/

## Test with the RaceBox emulator

The emulator app runs on a second Android device or emulator. Android
emulators on the same computer share a virtual Bluetooth network, so two
emulators can talk to each other. Use Android 13 or later system images
for both.

1. Build both apps:

       ./gradlew :app:assembleDebug :racebox-emulator:assembleDebug

2. Start two emulators. In Android Studio's Device Manager, create two
   virtual devices and start both. Install one app on each (`adb devices`
   lists them):

       adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
       adb -s emulator-5556 install -r racebox-emulator/build/outputs/apk/debug/racebox-emulator-debug.apk

3. In the RaceBox Emulator: allow Bluetooth, tap "Start advertising", pick
   a track (Laguna Seca works offline), and tap Drive.
4. In the lap timer: Record tab, "Scan for RaceBox", then tap the device.
   It appears under the emulator device's Bluetooth name, or as "RaceBox
   (no name)"; the emulator can rename itself to "RaceBox Emulator".

The emulator shows the exact time of each simulated lap, measured where
its loop starts, for comparing with the lap timer. "Lose GPS for 5 s"
tests fix loss.

To test reconnecting, turn Bluetooth off on the emulator's device, turn it
on again, then tap Stop and "Start advertising" in the RaceBox Emulator.
From a computer:

    adb -s emulator-5556 shell cmd bluetooth_manager disable
    adb -s emulator-5556 shell cmd bluetooth_manager enable

The emulator app cannot end the connection itself: Android leaves that to
the device that opened it, which is the lap timer. After its Bluetooth
restarts, the emulator's device advertises under a different address. The
lap timer finds it again by name, or by the RaceBox service when the
advertisement has no name, and records an `address changed` event in the
session log.

## Replay a session

    ./gradlew -q :replay:run --args="path/to/session.ltlog --csv fixes.csv"

The summary shows the update rate measured from the receiver's own clock,
gaps longer than 1.5 times the median interval, checksum errors, the median
horizontal accuracy, and whether the file ended cleanly or was cut off
during a write.

## RaceBox protocol

The devices use the Nordic UART Service:

| UUID | Use |
|---|---|
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | service |
| `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | phone to device (commands) |
| `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | device to phone (notifications) |

The payload offsets come from the RaceBox BLE Protocol document, revision 8.
That document was not reachable from the build machine, so the layout was
checked against three open-source clients, which agree with each other:
lgnap/RaceBoxMicroBleClient (its code, not its header comment, which lists
older offsets), lademeister/ESP32-RaceBox, and
anchit92/Open-Source-RaceBox-mini-Emulator. The test vector in
`SyntheticRaceBox.PROTOCOL_DOC_FRAME` is the sample frame from the protocol
document as transcribed in lgnap's tests. Its checksum verifies, and its UTC
time and GPS time of week differ by exactly the 18 leap seconds in effect in
2022, which confirms how the time fields are read.

Before relying on this with real hardware, compare against the official
document from RaceBox.

## Plan

1. Connect to the RaceBox, record raw data, replay it. Built; not yet
   tested with a real phone and RaceBox.
2. Import tracks from OpenStreetMap; draw start/finish and sector lines.
3. Lap timing, sectors, live screen.
4. Predictive delta, best and optimal lap.
5. Session list, lap table, CSV and VBO export.
