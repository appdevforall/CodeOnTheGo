package org.appdevforall.cotg.profiler.aidl;

import org.appdevforall.cotg.profiler.aidl.IProcessListObserver;

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
}