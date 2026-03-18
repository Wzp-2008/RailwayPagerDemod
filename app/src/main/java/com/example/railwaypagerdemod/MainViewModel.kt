package com.example.railwaypagerdemod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of all data shown in the UI. */
data class UiState(
    val vehicleId:      String  = "",
    val route:          String  = "",
    val latitude:       String  = "",
    val longitude:      String  = "",
    val trainNo:        String  = "",
    val speed:          String  = "",
    val mileage:        String  = "",
    val signalStrength: Float   = 0f,    // 0..1
    val logMessages:    String  = "",
    val isConnected:    Boolean = false
)

/**
 * ViewModel that owns the [Demodulator] and [TcpClient], bridges them to the
 * Compose UI through a [StateFlow], and handles the signal-strength polling.
 */
class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val demodulator = Demodulator()
    private var tcpClient:  TcpClient? = null
    private var connectJob: Job?       = null
    private var pollJob:    Job?       = null

    // ─────────────────────────────────────────────────────────────────────────
    // Public actions
    // ─────────────────────────────────────────────────────────────────────────

    /** Start a TCP connection to [host]:[port]. Does nothing if already connected. */
    fun connect(host: String, port: Int) {
        if (_state.value.isConnected) return

        val client = TcpClient(
            demodulator = demodulator,
            onMessage   = ::onMessage,
            onError     = ::onError
        )
        tcpClient = client

        connectJob = viewModelScope.launch {
            _state.update { it.copy(isConnected = true) }
            client.connect(host, port)
            // connect() returned → connection is closed
            _state.update { it.copy(isConnected = false) }
            pollJob?.cancel()
        }

        // Poll signal strength every 200 ms while connected
        pollJob = viewModelScope.launch {
            while (_state.value.isConnected) {
                val raw     = demodulator.magsqRaw.coerceIn(0.0, 0.5)
                val percent = (raw / 0.5).toFloat()
                _state.update { it.copy(signalStrength = percent) }
                delay(200)
            }
        }
    }

    /** Stop the active connection. */
    fun disconnect() {
        tcpClient?.stop()
        connectJob?.cancel()
        pollJob?.cancel()
        _state.update { it.copy(isConnected = false) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal callbacks
    // ─────────────────────────────────────────────────────────────────────────

    private fun onMessage(raw: String) {
        val line = raw.trimEnd('\n')

        // Append to log
        _state.update { it.copy(logMessages = it.logMessages + line + "\n") }

        // Parse POCSAG messages and update data fields
        val regex = "\\[MSG\\]\\s*(.+)".toRegex()
        regex.findAll(line).forEach { match ->
            val msg = match.groupValues[1]
            if (msg.length > 10) {
                val parsed = MessageParser.parseMessage(msg)
                mergeIntoState(parsed)
            }
        }
    }

    private fun onError(msg: String) {
        _state.update { it.copy(logMessages = it.logMessages + "Error: $msg\n") }
    }

    /**
     * Merge a [ParsedMessage] into the running UI state.
     * Fields from 1234002 messages update GPS/route; fields from 1234000
     * messages update train-operation data.
     */
    private fun mergeIntoState(parsed: ParsedMessage) {
        _state.update { cur ->
            var next = cur
            if (parsed.latitude.isNotEmpty()) {
                next = next.copy(
                    vehicleId = parsed.vehicleId.ifEmpty { cur.vehicleId },
                    route     = parsed.route.ifEmpty    { cur.route },
                    latitude  = parsed.latitude,
                    longitude = parsed.longitude
                )
            }
            if (parsed.trainNo.isNotEmpty()) {
                next = next.copy(
                    trainNo = parsed.trainNo,
                    speed   = parsed.speed,
                    mileage = parsed.mileage
                )
            }
            next
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
