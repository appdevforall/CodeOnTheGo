package org.appdevforall.cotg.profiler.aidl;

/**
 * Client (app-process) callback for a CPU profiling session. `oneway` so the privileged service is
 * never blocked by a slow/dead client.
 *
 * Live CPU-usage samples are derived from /proc/<pid>/stat by the service while simpleperf records
 * in the background; the recorded perf.data is only converted to a call tree when profiling stops.
 */
oneway interface ICpuProfileObserver {

    // simpleperf started recording successfully.
    void onProfilingStarted();

    // A live CPU-usage sample. [elapsedMillis] is milliseconds since profiling started;
    // [cpuPercent] is the process CPU usage over the last interval (may exceed 100% on multi-core).
    void onCpuSample(long elapsedMillis, float cpuPercent);

    // Profiling could not start or was aborted (e.g. simpleperf missing, permission denied, target died).
    void onProfilingError(in String message);
}
