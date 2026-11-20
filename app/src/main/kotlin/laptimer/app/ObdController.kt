package laptimer.app

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import laptimer.core.ObdPoller

enum class ObdConnection { CONNECTING, CONNECTED, RECONNECTING }

/** The OBD side of a session, as the screen shows it. */
data class ObdStatus(
    val target: ObdTarget,
    val connection: ObdConnection = ObdConnection.CONNECTING,
    val poller: ObdPoller.Status? = null,
    val disconnects: Int = 0,
    val lastError: String? = null,
)

/**
 * Connects to an OBD adapter, polls it, and keeps reconnecting while the
 * session lasts. Everything runs on [handler]'s thread, the recording
 * service's.
 */
class ObdController(
    private val context: Context,
    private val target: ObdTarget,
    private val handler: Handler,
    private val log: Log,
    private val onChange: (ObdStatus, important: Boolean) -> Unit,
) : ObdLink.Listener {
    /** Where commands and replies are recorded. */
    interface Log {
        fun sent(timeNanos: Long, command: String)
        fun received(timeNanos: Long, data: ByteArray)
    }

    private var link: ObdLink? = null
    private var running = false
    private val poller = ObdPoller(send = { command ->
        log.sent(now(), command)
        link?.write(command)
    })

    var status = ObdStatus(target)
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (status.connection == ObdConnection.CONNECTED) {
                poller.tick(now())
                update(important = false)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val reconnect = Runnable { if (running) open() }

    fun start() {
        if (running) return
        running = true
        open()
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(reconnect)
        link?.close()
        link = null
    }

    private fun open() {
        link = ObdLink.create(context, target, handler, this).also { it.connect() }
    }

    override fun onObdConnected() {
        if (!running) return
        status = status.copy(connection = ObdConnection.CONNECTED, lastError = null)
        poller.start(now())
        update(important = true)
    }

    override fun onObdBytes(timeNanos: Long, data: ByteArray) {
        if (!running) return
        log.received(timeNanos, data)
        poller.onBytes(data, timeNanos)
        update(important = false)
    }

    override fun onObdDisconnected(reason: String) {
        link = null
        if (!running) return
        status = status.copy(connection = ObdConnection.RECONNECTING, disconnects = status.disconnects + 1, lastError = reason)
        update(important = true)
        handler.postDelayed(reconnect, RECONNECT_MS)
    }

    private fun update(important: Boolean) {
        val before = status.poller?.phase
        status = status.copy(poller = poller.status)
        onChange(status, important || before != poller.status.phase)
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()

    private companion object {
        const val TICK_MS = 250L
        const val RECONNECT_MS = 3_000L
    }
}
