package org.appdevforall.cotg.profiler

sealed interface ProfilerIntent {
    data object DumpHeap : ProfilerIntent

    data object CpuHotspot : ProfilerIntent
}
