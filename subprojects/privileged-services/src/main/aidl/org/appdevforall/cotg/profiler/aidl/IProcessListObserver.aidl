package org.appdevforall.cotg.profiler.aidl;

import org.appdevforall.cotg.profiler.aidl.ProcessInfo;

/**
 * Client (app-process) callback for live process-list updates. `oneway` so the privileged service
 * is never blocked by a slow/dead client.
 */
oneway interface IProcessListObserver {

    // Full state, delivered once right after the observer is registered.
    void onProcessSnapshot(in List<ProcessInfo> processes);

    // Deltas delivered as processes are spawned / killed.
    void onProcessesAdded(in List<ProcessInfo> processes);
    void onProcessesRemoved(in int[] pids);
}
