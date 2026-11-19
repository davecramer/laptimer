# Handoff notes

Written on 2026-11-19 for the next person or agent working on this
project. The README describes the project and the normal test setup. This
file records what was done in the last session, what was checked, and what
is still open.

## What changed

1. The lap timer reconnects after the RaceBox changes its Bluetooth
   address. `RecordingService` still retries the saved address after a
   disconnect. It also starts a filtered scan. When the scan finds the
   device under a different address, the service connects there and writes
   an `address changed` event to the session log. The scan stops when
   streaming resumes or the session ends.
2. The scan matches on the device's name. It matches on the RaceBox
   service UUID only when the device advertised without a name. In that
   case the lap timer shows the name `RaceBoxScanner.NO_NAME`.
3. The "Drop connection" button and `RaceBoxPeripheral.dropConnections()`
   are removed from the emulator app.
4. The README documents how to test a reconnect: turn Bluetooth off and on
   on the emulator's device.

## Why "Drop connection" was removed

`BluetoothGattServer.cancelConnection()` did not end the link. On an API
36.1 virtual device the Bluetooth stack logged this when it was called:

    gatt_update_app_use_link_flag: ... is peripheral and the central
    device is responsible to disconnect if needed

Calling `BluetoothGattServer.connect(device, false)` first, so that the
server holds the link, did not change the result. That attempt was
reverted. This was checked on API 36.1 only.

## What was verified, and how

All of this ran on two Android virtual devices on one Mac: the lap timer
on `Medium_Phone_API_37.0` (API 37) and the RaceBox emulator app on
`small_phone` (API 36.1).

- The lap timer finds the emulator in a scan, connects, and records
  fixes with 0 checksum errors.
- After Bluetooth was turned off and on on `small_phone`, the lap timer
  reconnected. The session log showed `address=43:B6:D8:B1:A8:DE`, two
  `disconnected` events, `address changed` to `43:C2:DF:59:69:A6`, then
  `streaming`.
- The lap timer also reconnected after `small_phone` was shut down and
  started again.
- `./gradlew test` passed, and both apps build.

## What was not verified

- The scan path for a device that advertises without a name (the service
  UUID match). The emulator advertised as "RaceBox Emulator", so only the
  name match ran.
- A real RaceBox. No real hardware was used.
- A reconnect while the lap timer's screen is off.
- Two RaceBoxes with the same advertised name in range.

## Open questions

- Frame rate. The lap timer showed 13 to 18 Hz during testing and 25.0 Hz
  at the last two readings. The emulator app's "frames sent" count rose at
  about 18 per second during the slow period, so the emulator app was
  sending fewer frames, not the lap timer losing them. One untested
  explanation: the test commands (`uiautomator dump` every few seconds)
  slowed the virtual device. To check, let a session run without polling
  either device, then read the update rate from the session log with the
  `replay` tool.
- If the rescan fails to start (`onScanFailed`), `RecordingService` does
  not retry it during that outage. The saved address is still retried.
  Android limits an app to a few scan starts in 30 seconds, which is the
  case where this could matter.
- A scan that runs for a long outage is not restarted. Android may slow
  down a scan that has run for a long time; this was not looked into.

## Practical notes for testing on virtual devices

- Each app needs its own device. One device does not receive its own
  advertisement, so both apps on one virtual device cannot connect.
- Android Studio starts its virtual device with a hidden window. It is
  visible only in the Running Devices tool window. To get a desktop
  window for it, run `android device hub --device=emulator-5554`.
- `small_phone` restores a snapshot when it starts. The RaceBox emulator
  app in that snapshot can show "Starting..." with an old client. Force
  stop the app and start it again:
  `adb -s emulator-5556 shell am force-stop laptimer.raceboxemulator`.
- After Bluetooth restarts on the emulator's device, the RaceBox emulator
  app still shows "Advertising" but is not. Tap Stop, then "Start
  advertising".
- The apps can be driven from a computer with `adb shell input tap` and
  read with `adb shell uiautomator dump`. Button positions move when a
  status line appears or wraps, so read the positions from a fresh dump
  before each tap.
- Session logs are in
  `/sdcard/Android/data/laptimer.app/files/sessions/` on the lap timer's
  device. `strings` on a log shows its `event=` and `address=` records.

## Not part of this work

`gradle/gradle-daemon-jvm.properties` is untracked. It was there before
the session started and was left alone.
