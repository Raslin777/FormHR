package com.ivionics.formhr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val state: StateFlow<MainUiState> = FormHrSession.state

    fun start() {
        if (state.value.running) return
        HeartRateForegroundService.start(getApplication())
    }

    fun stop() {
        if (!state.value.running) return
        HeartRateForegroundService.stop(getApplication())
    }

    fun permissionDenied() {
        FormHrSession.failed("Permission denied; allow Nearby devices and heart rate")
    }
}
