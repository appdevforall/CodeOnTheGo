package org.appdevforall.cotg.profiler.aidl;

import org.appdevforall.cotg.profiler.aidl.IProcessListObserver;
import org.appdevforall.cotg.profiler.aidl.ICpuProfileObserver;

interface IProfilerService {

    // MUST have this destroy method with this specific transaction code.
    // DO NOT REMOVE!
    void destroy() = 16777114;

    void exit() = 1;

    void dumpHeapForPid(in ParcelFileDescriptor outFile, int pid) = 2;
    void dumpHeapForPackage(in ParcelFileDescriptor outFile, in String packageName) = 3;

    // Live process list. The service starts observing on the first registration and stops once the
    // last observer is removed. Dead clients are dropped automatically via linkToDeath.
    void registerProcessListObserver(IProcessListObserver observer) = 4;
    void unregisterProcessListObserver(IProcessListObserver observer) = 5;

    // CPU profiling (single session). startCpuProfiling runs simpleperf on the target process and
    // streams live CPU-usage samples to [observer]. stopCpuProfiling stops the recording, converts
    // the perf.data to a protobuf report-sample dump, and writes it to [reportOut].
    void startCpuProfiling(int pid, in String packageName, ICpuProfileObserver observer) = 6;
    void stopCpuProfiling(in ParcelFileDescriptor reportOut) = 7;

    // Aborts the current CPU session (including an in-flight report generation triggered by
    // stopCpuProfiling): kills simpleperf/report-sample and discards any leftover temp files.
    void cancelCpuProfiling() = 8;
}