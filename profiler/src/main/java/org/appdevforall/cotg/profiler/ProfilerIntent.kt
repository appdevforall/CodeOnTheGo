package org.appdevforall.cotg.profiler

import org.appdevforall.cotg.profiler.model.ProcessInfo

sealed interface ProfilerIntent {
    data object DumpHeap : ProfilerIntent

    data object CpuHotspot : ProfilerIntent

    data class SelectProcess(val process: ProcessInfo) : ProfilerIntent
}
