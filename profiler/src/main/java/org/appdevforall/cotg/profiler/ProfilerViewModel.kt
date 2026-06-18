package org.appdevforall.cotg.profiler

import android.app.Application
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.profiler.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.profiler.aidl.IProcessListObserver
import org.appdevforall.cotg.profiler.aidl.IProfilerService
import org.appdevforall.cotg.profiler.model.ProcessInfo
import org.slf4j.LoggerFactory
import java.io.File
import org.appdevforall.cotg.profiler.aidl.ProcessInfo as AidlProcessInfo

class ProfilerViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        private val logger = LoggerFactory.getLogger(ProfilerViewModel::class.java)
    }

    private val _state = MutableStateFlow<ProfilerUiState>(ProfilerUiState.Idle)
    val state: StateFlow<ProfilerUiState> = _state.asStateFlow()

    /** The bound privileged service, or null when disconnected. */
    private var service: IProfilerService? = null

    /** Live process list keyed by pid, mutated from binder threads (guarded by [processLock]). */
    private val processLock = Any()
    private val liveProcesses = LinkedHashMap<Int, AidlProcessInfo>()
    private val _processes = MutableStateFlow<List<ProcessInfo>>(emptyList())

    private val processObserver =
        object : IProcessListObserver.Stub() {
            override fun onProcessSnapshot(processes: MutableList<AidlProcessInfo>?) {
                synchronized(processLock) {
                    liveProcesses.clear()
                    processes?.forEach { liveProcesses[it.pid] = it }
                }
                publishProcesses()
            }

            override fun onProcessesAdded(processes: MutableList<AidlProcessInfo>?) {
                synchronized(processLock) {
                    processes?.forEach { liveProcesses[it.pid] = it }
                }
                publishProcesses()
            }

            override fun onProcessesRemoved(pids: IntArray?) {
                synchronized(processLock) {
                    pids?.forEach { liveProcesses.remove(it) }
                }
                publishProcesses()
            }
        }

    init {
        // Keep the picker in sync with the live process list while selecting.
        viewModelScope.launch {
            _processes.collect { processes ->
                _state.update { current ->
                    if (current is ProfilerUiState.SelectingProcess) {
                        current.copy(processes = processes.filter { it.supports(current.mode) })
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun onServiceConnected(service: IProfilerService) {
        logger.debug("onServiceConnected: service={}", service)
        this.service = service
        runCatching { service.registerProcessListObserver(processObserver) }
            .onFailure {
                logger.error("Failed to register process observer", it)
                setError(R.string.profiler_service_unavailable)
            }
    }

    fun onServiceDisconnected() {
        logger.debug("onServiceDisconnected")
        val previous = service
        service = null
        runCatching { previous?.unregisterProcessListObserver(processObserver) }
        synchronized(processLock) { liveProcesses.clear() }
        publishProcesses()
    }

    fun onServiceUnavailable() {
        logger.debug("onServiceUnavailable")
        service = null
        setError(R.string.profiler_service_unavailable)
    }

    fun onIntent(intent: ProfilerIntent) {
        logger.debug("onIntent: intent={}", intent)
        when (intent) {
            ProfilerIntent.DumpHeap -> startSelection(ProfilerMode.Heap)
            ProfilerIntent.CpuHotspot -> startSelection(ProfilerMode.Cpu)
            is ProfilerIntent.SelectProcess -> onProcessSelected(intent.process)
            ProfilerIntent.Reset -> _state.value = ProfilerUiState.Idle
        }
    }

    private fun startSelection(mode: ProfilerMode) {
        if (service == null) {
            logger.warn("cannot perform process selection: service unavailable")
            setError(R.string.profiler_service_unavailable)
            return
        }
        _state.value = ProfilerUiState.SelectingProcess(
            mode = mode,
            processes = _processes.value.filter { it.supports(mode) },
        )
    }

    private fun onProcessSelected(process: ProcessInfo) {
        val mode = (_state.value as? ProfilerUiState.SelectingProcess)?.mode ?: return
        when (mode) {
            ProfilerMode.Heap -> dumpHeap(process)
            ProfilerMode.Cpu -> setError(R.string.profiler_cpu_not_implemented)
        }
    }

    private fun dumpHeap(process: ProcessInfo) {
        val service = service ?: run {
            setError(R.string.profiler_service_unavailable)
            return
        }

        _state.value = ProfilerUiState.Running(ProfilerMode.Heap, process)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(
                        getApplication<Application>().cacheDir,
                        "heap-${process.pid}-${System.currentTimeMillis()}.hprof",
                    )
                    try {
                        ParcelFileDescriptor.open(
                            file,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_WRITE_ONLY or
                                ParcelFileDescriptor.MODE_TRUNCATE,
                        ).use { pfd ->
                            // Blocks until the dump completes (the service waits on its callback).
                            service.dumpHeapForPid(pfd, process.pid)
                        }
                        HeapDumpAnalyzer.analyze(file)
                    } finally {
                        file.delete()
                    }
                }
            }.onSuccess { rows ->
                _state.value = ProfilerUiState.Results(ProfilerMode.Heap, process, rows)
            }.onFailure { error ->
                logger.error("Heap dump failed for pid={}", process.pid, error)
                setError(getString(R.string.profiler_heap_dump_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun publishProcesses() {
        _processes.value = synchronized(processLock) {
            liveProcesses.values.map { it.toModel() }
        }
    }

    private fun setError(@StringRes resId: Int) {
        setError(getString(resId))
    }

    private fun setError(message: String) {
        _state.value = ProfilerUiState.Error(message)
    }

    private fun getString(@StringRes resId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resId, *formatArgs)

    override fun onCleared() {
        onServiceDisconnected()
        super.onCleared()
    }
}

private fun AidlProcessInfo.toModel(): ProcessInfo =
    ProcessInfo(
        pid = pid,
        packageName = packageName ?: processName,
        label = appLabel ?: packageName ?: processName,
        debuggable = debuggable,
        profileable = profileable,
    )

private fun ProcessInfo.supports(mode: ProfilerMode): Boolean =
    when (mode) {
        ProfilerMode.Heap -> debuggable
        ProfilerMode.Cpu -> profileable
    }
