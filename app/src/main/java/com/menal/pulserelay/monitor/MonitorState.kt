package com.menal.pulserelay.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    OFFLINE,
    RECONNECTING,
    ERROR,
}

data class MonitorSnapshot(
    val bpm: Int? = null,
    val bpmAtElapsedMs: Long = 0L,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val error: String? = null,
    val enabled: Boolean = false,
    val intervalSec: Int = 1,
)

class MonitorState {
    private val _state = MutableStateFlow(MonitorSnapshot())
    val state: StateFlow<MonitorSnapshot> = _state.asStateFlow()

    fun snapshot(): MonitorSnapshot = _state.value

    fun setBpm(bpm: Int?, atElapsedMs: Long = 0L) {
        _state.update { it.copy(bpm = bpm, bpmAtElapsedMs = atElapsedMs) }
    }

    fun setConnection(connection: ConnectionState, error: String? = null) {
        _state.update { it.copy(connection = connection, error = error) }
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
    }

    fun setInterval(intervalSec: Int) {
        _state.update { it.copy(intervalSec = intervalSec) }
    }
}
