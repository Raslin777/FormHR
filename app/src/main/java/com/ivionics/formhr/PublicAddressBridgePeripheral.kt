package com.ivionics.formhr

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Connects the regular app to the ADB shell-owned public-address BLE peripheral. */
class PublicAddressBridgePeripheral(
    private val onStatus: (PeripheralStatus) -> Unit,
    private val onEvent: (String) -> Unit = {},
) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val writerExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var runRequested = false

    @Volatile
    private var currentBpm = 0

    @Volatile
    private var hasHeartRate = false

    @Volatile
    private var connectionGeneration = 0L

    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    fun start() {
        stop(sendStop = false)
        currentBpm = 0
        hasHeartRate = false
        val generation = synchronized(lock) {
            runRequested = true
            connectionGeneration += 1
            connectionGeneration
        }
        publish(detail = "Connecting to public-address helper…")
        executor.execute { connectLoop(generation) }
    }

    fun updateHeartRate(beatsPerMinute: Int) {
        currentBpm = beatsPerMinute.coerceIn(30, 240)
        val firstMeasurement = !hasHeartRate
        hasHeartRate = true
        send(if (firstMeasurement) "START $currentBpm" else "BPM $currentBpm")
    }

    fun stop() {
        stop(sendStop = true)
    }

    fun close() {
        stop(sendStop = true)
        executor.shutdownNow()
        writerExecutor.shutdown()
    }

    private fun connectLoop(generation: Long) {
        var reportedFailure = false
        while (isCurrent(generation)) {
            val connected = connectAndRead(generation)
            if (!isCurrent(generation)) return
            publish(detail = "Connecting to public-address helper…")
            if (!connected && !reportedFailure) {
                onEvent("Helper unavailable · retrying")
                reportedFailure = true
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun connectAndRead(generation: Long): Boolean {
        val newSocket = Socket()
        var connected = false
        try {
            newSocket.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), BRIDGE_PORT),
                CONNECT_TIMEOUT_MS,
            )
            val newWriter = PrintWriter(newSocket.getOutputStream(), true)
            synchronized(lock) {
                if (!isCurrent(generation)) {
                    newSocket.close()
                    return false
                }
                socket = newSocket
                writer = newWriter
            }
            connected = true
            onEvent("Public-address helper connected")
            if (hasHeartRate) send("START $currentBpm")

            BufferedReader(InputStreamReader(newSocket.getInputStream())).use { reader ->
                while (isCurrent(generation)) {
                    val line = reader.readLine() ?: break
                    handleLine(line)
                }
            }
        } catch (exception: Exception) {
            if (isCurrent(generation) && connected) {
                onEvent("Helper connection lost · ${exception.message ?: exception.javaClass.simpleName}")
            }
        } finally {
            synchronized(lock) {
                if (socket === newSocket) {
                    writer = null
                    socket = null
                }
            }
            runCatching { newSocket.close() }
        }
        return connected
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("READY ") -> onEvent("Helper ready")
            line.startsWith("EVENT ") -> onEvent(line.removePrefix("EVENT "))
            line.startsWith("STATUS ") -> {
                val fields = line.split(' ')
                if (fields.size < 5) return
                val advertising = fields[1] == "1"
                val connected = fields[2].toIntOrNull() ?: 0
                val subscribed = fields[3].toIntOrNull() ?: 0
                val reportedBpm = fields[4].toIntOrNull() ?: currentBpm
                val detail = when {
                    subscribed > 0 -> "Streaming $reportedBpm BPM via public address"
                    connected > 0 -> "FORM connected; waiting for heart-rate subscription"
                    advertising -> "Public address active; waiting for FORM"
                    !hasHeartRate -> "Waiting for watch heart rate…"
                    else -> "Starting public-address broadcast…"
                }
                onStatus(
                    PeripheralStatus(
                        advertising = advertising,
                        connectedDevices = connected,
                        subscribedDevices = subscribed,
                        detail = detail,
                    ),
                )
            }
            line.startsWith("ERROR ") -> publish(detail = line.removePrefix("ERROR "))
        }
    }

    private fun send(line: String) {
        writerExecutor.execute {
            synchronized(lock) {
                writer?.let {
                    it.println(line)
                    it.flush()
                    if (it.checkError()) {
                        onEvent("Helper write failed · reconnecting")
                        runCatching { socket?.close() }
                    }
                }
            }
        }
    }

    private fun stop(sendStop: Boolean) {
        val connectionToClose = synchronized(lock) {
            runRequested = false
            connectionGeneration += 1
            val oldConnection = writer to socket
            writer = null
            socket = null
            oldConnection
        }
        writerExecutor.execute {
            val (oldWriter, oldSocket) = connectionToClose
            if (sendStop) {
                runCatching {
                    oldWriter?.println("STOP")
                    oldWriter?.flush()
                }
            }
            runCatching { oldSocket?.close() }
        }
        publish(detail = "Stopped")
    }

    private fun isCurrent(generation: Long): Boolean =
        runRequested && connectionGeneration == generation

    private fun publish(detail: String) {
        onStatus(PeripheralStatus(detail = detail))
    }

    companion object {
        private const val BRIDGE_PORT = 39737
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val RECONNECT_DELAY_MS = 1_000L
    }
}
