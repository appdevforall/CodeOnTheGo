package org.appdevforall.cotg.profiler

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.appdevforall.cotg.profiler.model.ProcessInfo
import org.appdevforall.cotg.profiler.ui.SampleProfileTables

class ProfilerViewModel : ViewModel() {
    private val _state = MutableStateFlow<ProfilerUiState>(ProfilerUiState.Idle)
    val state: StateFlow<ProfilerUiState> = _state.asStateFlow()

    fun onIntent(intent: ProfilerIntent) {
        when (intent) {
            ProfilerIntent.DumpHeap -> startSelection(ProfilerMode.Heap)
            ProfilerIntent.CpuHotspot -> startSelection(ProfilerMode.Cpu)
            is ProfilerIntent.SelectProcess -> showResults(intent.process)
        }
    }

    private fun startSelection(mode: ProfilerMode) {
        val processes = SampleProfileTables.SAMPLE_PROCESSES.filter { it.supports(mode) }
        _state.value = ProfilerUiState.SelectingProcess(mode, processes)
    }

    private fun showResults(process: ProcessInfo) {
        _state.update { current ->
            if (current !is ProfilerUiState.SelectingProcess) return@update current
            ProfilerUiState.Results(
                mode = current.mode,
                process = process,
                rows = rowsFor(current.mode),
            )
        }
    }

    private fun rowsFor(mode: ProfilerMode) =
        when (mode) {
            ProfilerMode.Heap -> SampleProfileTables.HEAP_ROWS
            ProfilerMode.Cpu -> SampleProfileTables.CPU_ROWS
        }

    private fun ProcessInfo.supports(mode: ProfilerMode): Boolean =
        when (mode) {
            ProfilerMode.Heap -> debuggable
            ProfilerMode.Cpu -> profileable
        }
}
