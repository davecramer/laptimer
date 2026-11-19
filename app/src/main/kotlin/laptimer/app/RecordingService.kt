package laptimer.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import laptimer.core.RaceBox
import laptimer.core.SessionLogWriter
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
    private val recentTimes = ArrayDeque<Long>()
    private var status = RecorderStatus()
    private var lastPublishNanos = 0L

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
                handler.post { if (address != null) begin(address, name ?: "RaceBox") }
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
    private fun begin(address: String, name: String) {
        if (connection != null) return
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
        parser = UbxStreamParser()
        recentTimes.clear()
        status = RecorderStatus(phase = Phase.CONNECTING, deviceName = name, sessionPath = file.absolutePath)
        publish(status, force = true)

        val adapter = getSystemService(BluetoothManager::class.java).adapter
        connection = RaceBoxConnection(this, adapter.getRemoteDevice(address), handler, this)
            .also { it.connect() }
    }

    private fun finish() {
        val wasRecording = connection != null || log != null
        connection?.close()
        connection = null
        try {
            log?.close()
        } catch (e: IOException) {
            status = status.copy(error = "Closing the session file failed: ${e.message}")
        }
        log = null
        if (wasRecording) {
            publish(RecorderStatus(sessionPath = status.sessionPath, error = status.error), force = true)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStreaming() {
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
        for (frame in parser.feed(data)) {
            val fix = RaceBox.decodeFix(frame) ?: continue
            fixes++
            lastFix = fix
            recentTimes.addLast(fix.gpsTimeOfWeekMs)
            while (recentTimes.size > RATE_WINDOW) recentTimes.removeFirst()
        }
        status = status.copy(
            lastFix = lastFix,
            fixes = fixes,
            fixRateHz = fixRate(),
            checksumErrors = parser.checksumErrors,
            bytesRecorded = status.bytesRecorded + data.size,
        )
        publish(status)
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

        fun start(context: Context, address: String, name: String) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_ADDRESS, address)
                    .putExtra(EXTRA_NAME, name),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
