package org.appdevforall.cotg.profiler

import androidx.compose.runtime.Immutable
import org.appdevforall.cotg.profiler.model.ProcessInfo
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableRow

@Immutable
sealed interface ProfilerUiState {
    data object Idle : ProfilerUiState

    data class SelectingProcess(
        val mode: ProfilerMode,
        val processes: List<ProcessInfo>,
    ) : ProfilerUiState

    /** A profiling action (e.g. heap dump) is in progress for [process]. */
    data class Running(
        val mode: ProfilerMode,
        val process: ProcessInfo,
    ) : ProfilerUiState

    data class Results(
        val mode: ProfilerMode,
        val process: ProcessInfo,
        val rows: List<ProfilerTableRow>,
    ) : ProfilerUiState

    /** A recoverable failure or unavailable-service message shown to the user. */
    data class Error(
        val message: String,
    ) : ProfilerUiState
}
