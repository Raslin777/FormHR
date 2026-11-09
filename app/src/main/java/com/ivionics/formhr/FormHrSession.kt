package com.ivionics.formhr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MainUiState(
    val discoveryProfile: DiscoveryProfile = DiscoveryProfile.PUBLIC_ADDRESS_BRIDGE,
    val beatsPerMinute: Int = 0,
    val sensorStatus: String = "Pixel Watch sensor ready",
    val peripheralStatus: PeripheralStatus = PeripheralStatus(),
    val running: Boolean = false,
    val events: List<String> = emptyList(),
)

/** Process-wide session state owned by [HeartRateForegroundService], not by an Activity. */
object FormHrSession {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun begin() {
        mutableState.update {
            it.copy(
                beatsPerMinute = 0,
                sensorStatus = "Starting persistent watch sensor…",
                peripheralStatus = PeripheralStatus(detail = "Connecting to public-address helper…"),
                running = true,
                events = emptyList(),
            )
        }
    }

    fun heartRate(beatsPerMinute: Int) {
        mutableState.update { it.copy(beatsPerMinute = beatsPerMinute) }
    }

    fun sensorStatus(status: String) {
        mutableState.update { it.copy(sensorStatus = status) }
    }

    fun peripheralStatus(status: PeripheralStatus) {
        mutableState.update { it.copy(peripheralStatus = status) }
    }

    fun event(message: String) {
        mutableState.update {
            it.copy(events = (it.events + message).takeLast(MAX_VISIBLE_EVENTS))
        }
    }

    fun stopping() {
        mutableState.update { it.copy(sensorStatus = "Stopping watch sensor…") }
    }

    fun stopped(detail: String = "Stopped") {
        mutableState.update {
            it.copy(
                running = false,
                sensorStatus = detail,
                peripheralStatus = it.peripheralStatus.copy(
                    advertising = false,
                    connectedDevices = 0,
                    subscribedDevices = 0,
                    detail = "Stopped",
                ),
            )
        }
    }

    fun failed(message: String) {
        mutableState.update {
            it.copy(
                running = false,
                sensorStatus = message,
                peripheralStatus = it.peripheralStatus.copy(
                    advertising = false,
                    detail = message,
                ),
            )
        }
    }

    private const val MAX_VISIBLE_EVENTS = 7
}
