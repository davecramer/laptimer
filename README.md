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
  - `LapTiming.kt`: the lap timer. It finds where the path between two
    consecutive fixes crosses a timing line in the counting direction and
    interpolates the crossing time, using the receiver's clock. Laps with a
    GPS gap of more than 2 seconds are flagged and don't count as bests.
  - `ReferenceLap.kt`: the predictive delta. The fastest lap's path is kept
    as a reference, and the current position is matched to the nearest
    point on it, searching only the stretch the car can have reached since
    the last match. Matching by position rather than distance driven keeps
    the delta right when the car takes a different line.
  - `Obd.kt`: engine data from an OBD-II adapter that speaks the ELM327
    command set, such as an OBDLink. The poller sets the adapter up, asks
    the car which readings it supports, then reads RPM, throttle, speed,
    manifold pressure and airflow in rotation, and coolant, intake air and
    oil temperature and barometric pressure about once a second.
  - `ObdEmulation.kt`: an emulated ELM327 adapter and a simple engine model,
    for the RaceBox emulator app and tests.
  - `Can.kt`, `CanMonitor.kt`: a car's own CAN messages through an OBDLink.
    Signal definitions as in a DBC file (with a DBC reader), a built-in
    profile for the Porsche 718, and the monitor that sets the adapter to
    listen silently, surveys the bus, filters to the profile's messages,
    and decodes them.
  - `Sessions.kt`: reads a session log, summarizes it with its laps, and
    exports it as a laps CSV, a data CSV (one row per fix, with lap number
    and time into the lap), or a Racelogic VBO file.
  - `DrivingSimulator.kt`: simulated laps for testing, with exact pass
    times to test lap timing against.
- `replay`: command-line tool that replays a session log through the same
  parser, decoder and lap timer the app uses, prints a summary and the
  laps, and can export fixes to CSV.
- `app`: the Android app (Android 12 and later). It scans for a RaceBox,
  records in a foreground service, reconnects after a dropout, and shows
  live fix data. With a track picked on the Record tab, it times laps
  live: a large lap clock, the predictive delta with a bar and a predicted
  lap time, last, best and optimal lap, and sector times against the best
  for each sector. The fastest lap on each track is saved, so the delta
  works from the first lap of the next session. The Sessions tab lists
  recordings with a lap table each, can time a session on a different
  track, and shares exports through Android's share sheet. The Tracks tab finds a track on the map and places the
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

To test reconnecting, turn Bluetooth off on the emulator's device and on
again. The RaceBox Emulator starts advertising again by itself when
Bluetooth comes back, unless you tapped Stop. From a computer:

    adb -s emulator-5556 shell cmd bluetooth_manager disable
    adb -s emulator-5556 shell cmd bluetooth_manager enable

The emulator app cannot end the connection itself: Android leaves that to
the device that opened it, which is the lap timer. After its Bluetooth
restarts, the emulator's device advertises under a different address. The
lap timer finds it again by name, or by the RaceBox service when the
advertisement has no name, and records an `address changed` event in the
session log.

## Timing laps

1. Add the track in the Tracks tab and place at least the start/finish
   line.
2. On the Record tab, pick the track, then scan and tap the RaceBox.
3. The first crossing of the start/finish line starts lap 1. A crossing
   less than 10 seconds into a lap is ignored, so creeping over the line
   in the pits doesn't start a new lap.
4. The delta compares against the faster of today's best lap and the best
   lap saved for the track. Negative (green) is ahead. Moving the
   start/finish line or changing the track type discards the saved lap,
   because its times no longer apply. Laps with a GPS gap never become the
   reference.

For a quick test, add Laguna Seca in the lap timer, pick it on the Record
tab, and have the RaceBox Emulator drive Laguna Seca.

The session file stores the whole track, so the replay tool can time a
session again later, even after the track is edited or deleted.

## Engine data (OBD-II)

The app reads an OBD-II adapter alongside the RaceBox. OBDLink adapters
work over Bluetooth Classic (MX+, LX) or BLE (CX).

1. MX+ or LX: pair the adapter in Android's Bluetooth settings first,
   pressing its button when asked. CX and other BLE adapters need no
   pairing.
2. Turn the ignition on.
3. On the Record tab, pick the adapter under "OBD adapter" (BLE adapters
   appear after "Find BLE adapters"), then connect the RaceBox as usual.

The recording screen then shows RPM, throttle, boost, OBD speed and
temperatures. The OBD side connects and reconnects on its own; a session
never waits for it. Everything the adapter sends is stored in the session
file, so the data CSV and VBO exports include the engine readings, each
taken as the latest reading before the GPS fix.

Notes:
- Fast readings share the adapter: with five fast channels, each updates
  about a fifth as often as the adapter answers. An OBDLink on a CAN car
  answers 50 or more times a second; a cheap clone 5 to 10.
- Manifold pressure (PID 0B) tops out at 255 kPa, so boost above about
  1.5 bar reads as the maximum.
- The BLE side looks for the common serial layouts (service FFF0, FFE0 or
  18F0), then any custom service with a notify and a write characteristic.
  It has not been tried with a real OBDLink CX yet.

To test without a car, the RaceBox Emulator also acts as a BLE OBD
adapter, with readings that follow the simulated driving. Pick it under
"OBD adapter" after "Find BLE adapters". Its "Ignition" button tests a car
that stops answering.

## CAN bus (Porsche 718)

An OBDLink (MX+, LX or CX) can listen to the car's CAN bus instead of
asking standard OBD questions. That gives the car's own signals at their
native rate, typically 25 to 100 times a second: steering angle, yaw rate,
lateral and longitudinal acceleration, all four wheel speeds, brake
pressure, throttle, RPM, gear, clutch, oil pressure, manifold pressure,
temperatures and fuel level.

The built-in profile is for the Porsche 718 (Cayman, GT4, Clubsport). Its
definitions come from
https://planetkris.com/porsche-718-cayman-can-bus-track-day-data/, where
they were reverse engineered. On the 718 these messages are not expected
on the factory OBD port, which sits behind a gateway; the article's author
tapped the bus and fed it to the adapter through an OBD splitter.

To use it, pick the adapter under "OBD adapter", then "CAN bus: Porsche
718 Cayman / GT4" under "Read". During setup the adapter:

1. turns on silent monitoring, so it never acknowledges or sends anything
   on the car's bus;
2. listens to all traffic for 3 seconds and lists which identifiers it
   saw: the recording screen names any of the profile's identifiers that
   are missing, which shows at once whether the tap is on the right bus;
3. sets a pass filter for each of the profile's identifiers, so the
   Bluetooth link carries only what is decoded, and monitors from then on.

The raw messages go in the session file with the profile, so exports and
the replay tool decode them with the same definitions. The data CSV gets a
column per signal and the VBO file a channel per signal.

Doubtful in the source data, so check against your own data:
- Steering angle and yaw rate have no left/right sign: the source gives
  none, and the direction bit is probably elsewhere in the message.
- The PDK gear signal is a guess at the bit order of RaceChrono's
  bitsToUInt. The manual gear signal is little-endian like the rest.
- The DBC gives the brake light an offset of 1; the profile uses 0.

`Dbc.parse` reads a DBC file into signals; there is no screen for loading
one yet. The RaceBox Emulator acts as an OBDLink too and sends the 718's
messages from the simulated driving.

## Exporting

On the Sessions tab, open a session and pick an export. Android's share
sheet then offers Drive, email, Files and other apps.

- Laps CSV: one row per lap with sector times and the gap to the best.
- Data CSV: one row per GPS fix, with the lap number and time into the lap.
- VBO: Racelogic's text format, for Circuit Tools and other analysis
  software. Positions are in minutes of arc with longitude positive to the
  west, time is UTC, and lines end in CR LF. This follows two independent
  open-source VBO writers; it has not been opened in Circuit Tools yet. The
  file leaves out the `[laptiming]` section, because its exact syntax could
  not be confirmed, so set the start/finish line in Circuit Tools.
- Share session file: the raw `.ltlog`, which the replay tool reads.

The replay tool writes the same exports: `--laps-csv laps.csv` and
`--vbo out.vbo`.

## Replay a session

    ./gradlew -q :replay:run --args="path/to/session.ltlog --csv fixes.csv"

The summary shows the update rate measured from the receiver's own clock,
gaps longer than 1.5 times the median interval, checksum errors, the median
horizontal accuracy, and whether the file ended cleanly or was cut off
during a write. If the session was recorded with a track, it also lists
each lap with its sector times, and the best sectors.

To time a session on a different track, or after editing the lines, pass
a track file. Tracks are in the app's private folder; copy one off with:

    adb exec-out run-as laptimer.app cat files/tracks/<id>.json > track.json
    ./gradlew -q :replay:run --args="path/to/session.ltlog --track $PWD/track.json"

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
   Built; tested on virtual devices.
3. Lap timing, sectors, live screen. Built; tested against the driving
   simulator, not yet on a device.
4. Predictive delta, best and optimal lap. Built; tested against the
   driving simulator, not yet on a device.
5. Session list, lap table, CSV and VBO export. Built; the exports are
   tested against the simulator, the VBO file not yet in Circuit Tools.
