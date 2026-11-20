package laptimer.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import laptimer.core.CAN_PROFILE_KEY
import laptimer.core.CanProfile
import laptimer.core.CanProfiles
import laptimer.core.LapTimer
import laptimer.core.RaceBox
import laptimer.core.ReferenceStore
import laptimer.core.SessionLogWriter
import laptimer.core.TrackJson
import laptimer.core.UbxStreamParser
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Records one session from a RaceBox to a session log.
 *
 * It runs as a foreground service so Android keeps it alive with the screen
 * off. All work happens on one background thread: Bluetooth callbacks,
 * file writes and status updates. The file is synced at least once a
 * second, so if Android kills the process the session keeps everything up
 * to the last sync.
 */
class RecordingService : Service(), RaceBoxConnection.Listener {
    private val thread = HandlerThread("racebox").apply { start() }
    private val handler = Handler(thread.looper)

    // Owned by the background thread.
    private var connection: RaceBoxConnection? = null
    private var log: SessionLogWriter? = null
    private var parser = UbxStreamParser()
    private var lapTimer: LapTimer? = null
    private var obd: ObdController? = null
    private val references: ReferenceStore by lazy { referenceStore(this) }
    private val recentTimes = ArrayDeque<Long>()
    private var status = RecorderStatus()
    private var lastPublishNanos = 0L

    /** The address [connection] uses. A rescan can replace it. */
    private var address: String? = null
    private var scanning = false

    // Android delivers scan results on the main thread.
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onFoundAgain(result.device) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val name = intent.getStringExtra(EXTRA_NAME) ?: address
                val trackId = intent.getStringExtra(EXTRA_TRACK_ID)
                val obdTarget = intent.getStringExtra(EXTRA_OBD_ADDRESS)?.let { a ->
                    ObdTarget(
                        address = a,
                        name = intent.getStringExtra(EXTRA_OBD_NAME) ?: a,
                        transport = ObdTransport.valueOf(intent.getStringExtra(EXTRA_OBD_TRANSPORT) ?: ObdTransport.BLE.name),
                    )
                }
                val canProfile = intent.getStringExtra(EXTRA_CAN_PROFILE)?.let { id -> CanProfiles.ALL.find { it.id == id } }
                handler.post { if (address != null) begin(address, name ?: "RaceBox", trackId, obdTarget, canProfile) }
            }
            ACTION_STOP -> handler.post { finish() }
        }
        // If Android kills the service, do not restart it with a null
        // intent; the user starts a new session from the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.post { finish() }
        // quitSafely runs the posted finish() before the thread exits.
        thread.quitSafely()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // MainActivity checks BLUETOOTH_CONNECT first.
    private fun begin(address: String, name: String, trackId: String?, obdTarget: ObdTarget?, canProfile: CanProfile?) {
        if (connection != null) return
        val track = trackId?.let { id -> trackStore(this).loadAll().tracks.find { it.id == id } }
            ?.takeIf { it.problems().isEmpty() }
        val file = try {
            newSessionFile()
        } catch (e: IOException) {
            publish(RecorderStatus(error = "Cannot create session file: ${e.message}"), force = true)
            stopSelf()
            return
        }
        log = try {
            SessionLogWriter(file)
        } catch (e: IOException) {
            publish(RecorderStatus(error = "Cannot open $file: ${e.message}"), force = true)
            stopSelf()
            return
        }
        writeEvent(
            "device" to name,
            "address" to address,
            "startUtc" to Instant.now().toString(),
            "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
        )
        // The whole track goes in the log, so the session can be timed
        // again later even if the track is edited or deleted.
        if (track != null) writeEvent("trackName" to track.name, "track" to TrackJson.encodeCompact(track))
        parser = UbxStreamParser()
        // The track's best lap from earlier sessions, so the delta works from the first lap.
        lapTimer = track?.let { LapTimer(it, initialReference = references.load(it)) }
        recentTimes.clear()
        status = RecorderStatus(
            phase = Phase.CONNECTING,
            deviceName = name,
            sessionPath = file.absolutePath,
            trackName = track?.name,
            timing = lapTimer?.state,
        )
        publish(status, force = true)

        this.address = address
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        connection = RaceBoxConnection(this, adapter.getRemoteDevice(address), handler, this)
            .also { it.connect() }

        // The OBD adapter connects on its own schedule; the session doesn't wait for it.
        if (obdTarget != null) {
            writeEvent("obdAdapter" to obdTarget.name, "obdAddress" to obdTarget.address, "obdTransport" to obdTarget.transport.name)
            // With a profile the adapter's bytes are CAN monitor lines; the profile
            // goes in the log so the session decodes with the same definitions later.
            if (canProfile != null) writeEvent("canProfileName" to canProfile.name, CAN_PROFILE_KEY to canProfile.toJson())
            if (log == null) return
            obd = ObdController(this, obdTarget, handler, obdLog, canProfile) { s, important ->
                status = status.copy(obd = s)
                publish(status, force = important)
            }.also {
                status = status.copy(obd = it.status, canProfile = canProfile)
                it.start()
            }
        }
    }

    /** Records the OBD adapter's traffic. A failed write ends the session, as for the RaceBox's. */
    private val obdLog = object : ObdController.Log {
        override fun sent(timeNanos: Long, command: String) = record { it.writeObdSent(timeNanos, command) }
        override fun received(timeNanos: Long, data: ByteArray) = record { it.writeObdReceived(timeNanos, data) }

        private fun record(write: (SessionLogWriter) -> Unit) {
            val writer = log ?: return
            try {
                write(writer)
            } catch (e: IOException) {
                status = status.copy(error = "Writing the session file failed: ${e.message}")
                publish(status, force = true)
                finish()
            }
        }
    }

    private fun finish() {
        val wasRecording = connection != null || log != null
        obd?.stop()
        obd = null
        stopRescan()
        connection?.close()
        connection = null
        try {
            log?.close()
        } catch (e: IOException) {
            status = status.copy(error = "Closing the session file failed: ${e.message}")
        }
        log = null
        lapTimer = null
        if (wasRecording) {
            // Keep the laps on screen after the session ends.
            publish(
                RecorderStatus(sessionPath = status.sessionPath, error = status.error, trackName = status.trackName, timing = status.timing),
                force = true,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStreaming() {
        stopRescan()
        writeEvent("event" to "streaming")
        // The session may have ended: a failed write, or a callback that was
        // already queued when the user pressed Stop.
        if (log == null) return
        status = status.copy(phase = Phase.STREAMING)
        publish(status, force = true)
    }

    override fun onBytes(timeNanos: Long, data: ByteArray) {
        val writer = log ?: return
        try {
            writer.writeDeviceBytes(timeNanos, data)
        } catch (e: IOException) {
            status = status.copy(error = "Writing the session file failed: ${e.message}")
            publish(status, force = true)
            finish()
            return
        }
        var fixes = status.fixes
        var lastFix = status.lastFix
        var lapFinished = false
        for (frame in parser.feed(data)) {
            val fix = RaceBox.decodeFix(frame) ?: continue
            fixes++
            lastFix = fix
            recentTimes.addLast(fix.gpsTimeOfWeekMs)
            while (recentTimes.size > RATE_WINDOW) recentTimes.removeFirst()
            for (lap in lapTimer?.feed(fix).orEmpty()) {
                lapFinished = true
                // Timing can be redone from the raw data; this is for reading the log.
                writeEvent(
                    "event" to "lap",
                    "lap" to lap.number.toString(),
                    "timeS" to "%.3f".format(java.util.Locale.ROOT, lap.timeS),
                    "sectorsS" to lap.sectorTimesS.joinToString(",") { it?.let { s -> "%.3f".format(java.util.Locale.ROOT, s) } ?: "" },
                    "gap" to lap.hadGap.toString(),
                )
                if (log == null) return
            }
        }
        // A new fastest lap becomes the track's reference for later sessions too.
        lapTimer?.takeNewReference()?.let { ref ->
            try {
                references.save(ref)
            } catch (e: IOException) {
                // Timing carries on; only the saved best is lost.
                status = status.copy(error = "Saving the best lap failed: ${e.message}")
            }
        }
        status = status.copy(
            lastFix = lastFix,
            fixes = fixes,
            fixRateHz = fixRate(),
            checksumErrors = parser.checksumErrors,
            bytesRecorded = status.bytesRecorded + data.size,
            timing = lapTimer?.state ?: status.timing,
            timingAtNanos = if (lapTimer != null) timeNanos else status.timingAtNanos,
        )
        // A finished lap shows at once, not up to 200 ms later.
        publish(status, force = lapFinished)
    }

    override fun onDisconnected(reason: String) {
        writeEvent("event" to "disconnected", "reason" to reason)
        if (log == null) return
        status = status.copy(
            phase = Phase.RECONNECTING,
            disconnects = status.disconnects + 1,
            lastDisconnectReason = reason,
        )
        publish(status, force = true)
        // Keep trying while the session is open; the RaceBox may only be
        // out of range for a moment.
        handler.postDelayed({ connection?.connect() }, RECONNECT_DELAY_MS)
        startRescan()
    }

    /**
     * Looks for the device under another address while its saved address
     * is retried. A phone running the RaceBox emulator can advertise under
     * a different address after its Bluetooth restarts, and connecting to
     * the saved address then fails every time.
     *
     * The scan needs a filter: Android stops a scan without a filter when
     * the screen turns off.
     */
    @SuppressLint("MissingPermission") // MainActivity checks BLUETOOTH_SCAN first.
    private fun startRescan() {
        if (scanning) return
        val scanner = getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: return
        val name = status.deviceName
        // Several RaceBoxes can be in range at a track, and the name tells
        // them apart. Only a device that advertised without a name is
        // matched by its service.
        val filter = if (name != null && name != RaceBoxScanner.NO_NAME) {
            ScanFilter.Builder().setDeviceName(name).build()
        } else {
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(RaceBox.SERVICE_UUID)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    @SuppressLint("MissingPermission") // MainActivity checks BLUETOOTH_SCAN first.
    private fun stopRescan() {
        if (!scanning) return
        scanning = false
        getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun onFoundAgain(device: BluetoothDevice) {
        // A result can still be queued after the scan stops.
        if (!scanning || log == null) return
        // The saved address is already being retried.
        if (device.address == address) return
        stopRescan()
        writeEvent("event" to "address changed", "address" to device.address)
        // A failed write ends the session.
        if (log == null) return
        connection?.close()
        address = device.address
        connection = RaceBoxConnection(this, device, handler, this).also { it.connect() }
    }

    /**
     * Writes a metadata record. A failed write ends the session: a log that
     * can no longer be written records nothing more.
     */
    private fun writeEvent(vararg entries: Pair<String, String>) {
        try {
            log?.writeMetadata(SystemClock.elapsedRealtimeNanos(), mapOf(*entries))
        } catch (e: IOException) {
            status = status.copy(error = "Writing the session file failed: ${e.message}")
            publish(status, force = true)
            finish()
        }
    }

    private fun fixRate(): Double? {
        if (recentTimes.size < 2) return null
        val span = recentTimes.last() - recentTimes.first()
        return if (span > 0) (recentTimes.size - 1) * 1000.0 / span else null
    }

    /** Publishes at most five times a second unless [force] is set. */
    private fun publish(s: RecorderStatus, force: Boolean = false) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (!force && now - lastPublishNanos < 200_000_000L) return
        lastPublishNanos = now
        RecorderState.mutable.value = s
    }

    private fun newSessionFile(): File {
        val dir = sessionDirectory(this)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        return File(dir, "session-$stamp.ltlog")
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Recording session")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "laptimer.app.START"
        private const val ACTION_STOP = "laptimer.app.STOP"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TRACK_ID = "trackId"
        private const val EXTRA_OBD_ADDRESS = "obdAddress"
        private const val EXTRA_OBD_NAME = "obdName"
        private const val EXTRA_OBD_TRANSPORT = "obdTransport"
        private const val EXTRA_CAN_PROFILE = "canProfile"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val RECONNECT_DELAY_MS = 2_000L

        /** About two seconds of fixes at 25 Hz. */
        private const val RATE_WINDOW = 50

        /**
         * Sessions go in the app's folder on shared storage, so they can be
         * copied off the phone with `adb pull` or a file manager.
         */
        fun sessionDirectory(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")

        /**
         * Starts a session. With [trackId], laps are timed on that track;
         * with [obd], engine data is read from that adapter too.
         */
        fun start(context: Context, address: String, name: String, trackId: String?, obd: ObdTarget? = null, canProfileId: String? = null) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_TRACK_ID, trackId)
            if (obd != null) {
                intent.putExtra(EXTRA_OBD_ADDRESS, obd.address)
                    .putExtra(EXTRA_OBD_NAME, obd.name)
                    .putExtra(EXTRA_OBD_TRANSPORT, obd.transport.name)
                    .putExtra(EXTRA_CAN_PROFILE, canProfileId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
