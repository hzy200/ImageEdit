package com.example.imageedit.service

import com.example.imageedit.core.export.ExportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExportUiState(
    val running: Boolean = false,
    val progress: String = "",
    val result: ExportResult? = null,
    val error: String? = null,
)

/**
 * Process-local bridge between the foreground export service and the ViewModel.
 * A StateFlow is retained across Activity recreation while the process lives.
 */
object ExportStatusBus {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun running(progress: String) {
        _state.value = ExportUiState(running = true, progress = progress)
    }

    fun finished(result: ExportResult) {
        _state.value = ExportUiState(result = result)
    }

    fun failed(message: String) {
        _state.value = ExportUiState(error = message)
    }

    fun reset() {
        _state.value = ExportUiState()
    }
}
