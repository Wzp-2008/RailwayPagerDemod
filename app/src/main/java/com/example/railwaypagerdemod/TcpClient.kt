package com.example.railwaypagerdemod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coroutine-based TCP client for reading raw I/Q byte streams from an
 * RTL-SDR server (e.g. rtl_tcp).
 *
 * Ported from the worker-thread logic in C++ native-lib.cpp.
 *
 * Usage: call [connect] from a coroutine scope (it is a suspend function that
 * runs until the connection is closed or [stop] is called).
 */
class TcpClient(
    private val demodulator: Demodulator,
    private val onMessage:   (String) -> Unit,
    private val onError:     (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var socket:  Socket? = null

    companion object {
        private const val BUF_SIZE = 8192
        private const val DECIM    = 5          // decimate 240 kHz → 48 kHz
    }

    /**
     * Open a TCP connection to [host]:[port], read I/Q samples, feed them to
     * [demodulator], and emit decoded POCSAG messages via [onMessage].
     *
     * This is a blocking suspend function; it returns when the connection ends
     * or [stop] is called.
     */
    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext

        try {
            socket = Socket(host, port)
            onMessage("Connected to $host:$port")

            val input: InputStream = socket!!.getInputStream()
            val buffer = ByteArray(BUF_SIZE)

            var decimCounter = 0
            var accI = 0
            var accQ = 0

            while (isActive && running.get()) {
                val n = input.read(buffer)
                if (n <= 0) break

                var j = 0
                while (j + 1 < n) {
                    accI += buffer[j].toInt()     and 0xFF
                    accQ += buffer[j + 1].toInt() and 0xFF
                    j += 2

                    if (++decimCounter == DECIM) {
                        // Average DECIM samples, centre at 0 (unsigned byte → signed)
                        val iDs = ((accI.toFloat() / DECIM) - 128.0f)
                            .toInt().coerceIn(-128, 127).toByte()
                        val qDs = ((accQ.toFloat() / DECIM) - 128.0f)
                            .toInt().coerceIn(-128, 127).toByte()

                        demodulator.processOneSample(iDs, qDs)
                        accI         = 0
                        accQ         = 0
                        decimCounter = 0
                    }
                }

                // Check if the demodulator has a new message ready
                if (demodulator.isMessageReady) {
                    val addrStr = demodulator.address.toLong().toString().padStart(10, '0')
                    val msg = "[MSG] $addrStr ${demodulator.numericMsg}"
                    onMessage(msg)
                    demodulator.isMessageReady = false
                }
            }
        } catch (e: Exception) {
            if (running.get()) onError(e.message ?: "Connection error")
        } finally {
            socket?.close()
            socket  = null
            running.set(false)
            onMessage("Connection closed")
        }
    }

    /** Request graceful shutdown; the [connect] coroutine will exit shortly. */
    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
    }

    /** Whether the client is currently connected and receiving data. */
    val isRunning: Boolean get() = running.get()
}
