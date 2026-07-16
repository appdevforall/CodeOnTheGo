package org.appdevforall.cotg.profiler

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.profiler.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.profiler.aidl.ICpuProfileObserver
import org.appdevforall.cotg.profiler.aidl.IProcessListObserver
import org.appdevforall.cotg.profiler.aidl.IProfilerService
import org.appdevforall.cotg.profiler.cpu.CpuSample
import org.appdevforall.cotg.profiler.cpu.SimpleperfReportParser
import org.appdevforall.cotg.profiler.model.ProcessInfo
import org.appdevforall.cotg.profiler.service.ProfilerServiceConnection
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import org.appdevforall.cotg.profiler.aidl.ProcessInfo as AidlProcessInfo

/**
 * Activity-scoped (see [androidx.fragment.app.activityViewModels]) profiler state holder. Owns the
 * Shizuku [ProfilerServiceConnection] so the binding — and any in-flight profiling session —
 * survives bottom-sheet tab switches and fragment view recreation, and is torn down only when the
 * editor activity is destroyed ([onCleared]).
 */
class ProfilerViewModel(
	application: Application,
) : AndroidViewModel(application) {
	private companion object {
		private val logger = LoggerFactory.getLogger(ProfilerViewModel::class.java)
	}

	private val _state = MutableStateFlow<ProfilerUiState>(ProfilerUiState.Idle)
	val state: StateFlow<ProfilerUiState> = _state.asStateFlow()

	/** The bound privileged service, or null when disconnected. */
	private var service: IProfilerService? = null

	/** The privileged service connection, owned here so it outlives the fragment. */
	private val connection =
		ProfilerServiceConnection(
			context = getApplication(),
			onConnected = ::onServiceConnected,
			onDisconnected = ::onServiceDisconnected,
			onUnavailable = ::onServiceUnavailable,
		)

	/** The currently running profiling coroutine (heap dump or CPU report generation). */
	private var activeJob: Job? = null

	/** Live process list keyed by pid, mutated from binder threads (guarded by [processLock]). */
	private val processLock = Any()
	private val liveProcesses = LinkedHashMap<Int, AidlProcessInfo>()
	private val processesFlow = MutableStateFlow<List<ProcessInfo>>(emptyList())

	/** Non-null only while a CPU sampling session is live (gates live-sample handling). */
	@Volatile
	private var cpuProcess: ProcessInfo? = null
	private val cpuSamples = mutableListOf<CpuSample>()

	private val cpuObserver =
		object : ICpuProfileObserver.Stub() {
			override fun onProfilingStarted() {
				logger.debug("CPU profiling started")
			}

			override fun onCpuSample(
				elapsedMillis: Long,
				cpuPercent: Float,
			) {
				if (cpuProcess == null) return
				val snapshot =
					synchronized(cpuSamples) {
						cpuSamples.add(CpuSample(elapsedMillis, cpuPercent))
						cpuSamples.toList()
					}
				_state.update {
					if (it is ProfilerUiState.Profiling.CpuSampling && !it.finalizing) {
						it.copy(samples = snapshot)
					} else {
						it
					}
				}
			}

			override fun onProfilingError(message: String?) {
				logger.warn("CPU profiling error: {}", message)
				cpuProcess = null
				synchronized(cpuSamples) { cpuSamples.clear() }
				fail(message ?: getString(R.string.profiler_cpu_failed_generic))
			}
		}

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
		// Keep the picker in sync with the live process list while choosing.
		viewModelScope.launch {
			processesFlow.collect { processes ->
				_state.update { current ->
					if (current is ProfilerUiState.ChooseProcess) {
						current.copy(processes = processes.filter { it.supports(current.mode) })
					} else {
						current
					}
				}
			}
		}
	}

	/** Binds the privileged service. Called when the Profiler tab becomes visible; idempotent. */
	fun connect() = connection.connect()

	private fun onServiceConnected(service: IProfilerService) {
		logger.debug("onServiceConnected: service={}", service)
		this.service = service
		runCatching { service.registerProcessListObserver(processObserver) }
			.onFailure {
				logger.error("Failed to register process observer", it)
				fail(R.string.profiler_service_unavailable)
			}
	}

	private fun onServiceDisconnected() {
		logger.debug("onServiceDisconnected")
		val previous = service
		service = null
		runCatching { previous?.unregisterProcessListObserver(processObserver) }
		synchronized(processLock) { liveProcesses.clear() }
		publishProcesses()

		// A genuine disconnect (e.g. Shizuku/system_server restart, or project close) ends any live
		// run — don't leave the UI stuck mid-Profiling.
		if (_state.value is ProfilerUiState.Profiling<*>) {
			cancelActiveRun()
			fail(R.string.profiler_interrupted)
		}
	}

	private fun onServiceUnavailable() {
		logger.debug("onServiceUnavailable")
		service = null
		fail(R.string.profiler_service_unavailable)
	}

	fun onIntent(intent: ProfilerIntent) {
		logger.debug("onIntent: intent={}", intent)
		when (intent) {
			ProfilerIntent.DumpHeap -> startSelection(ProfilerMode.Heap)
			ProfilerIntent.CpuHotspot -> startSelection(ProfilerMode.Cpu)
			is ProfilerIntent.SelectProcess -> onProcessSelected(intent.process)
			ProfilerIntent.StopProfiling -> onStopProfiling()
			ProfilerIntent.Reset -> _state.value = ProfilerUiState.Idle
		}
	}

	private fun startSelection(mode: ProfilerMode) {
		// Only one task at a time: ignore start requests while a run is in flight.
		if (_state.value is ProfilerUiState.Profiling<*>) return
		if (service == null) {
			logger.warn("cannot choose a process: service unavailable")
			fail(R.string.profiler_service_unavailable)
			return
		}
		_state.value =
			ProfilerUiState.ChooseProcess(
				mode = mode,
				processes = processesFlow.value.filter { it.supports(mode) },
			)
	}

	private fun onProcessSelected(process: ProcessInfo) {
		val mode = (_state.value as? ProfilerUiState.ChooseProcess)?.mode ?: return
		when (mode) {
			ProfilerMode.Heap -> dumpHeap(process)
			ProfilerMode.Cpu -> startCpuProfiling(process)
		}
	}

	private fun onStopProfiling() {
		when (val current = _state.value) {
			is ProfilerUiState.Profiling.CpuSampling ->
				// While finalizing the report the same button acts as "Cancel": abort the in-flight
				// report generation instead of (re-)starting it.
				if (current.finalizing) cancelCpuReport() else finalizeCpuProfiling(current.process)
			is ProfilerUiState.Profiling.HeapDump -> cancelHeapDump()
			else -> {} // not profiling — nothing to stop
		}
	}

	private fun dumpHeap(process: ProcessInfo) {
		val service =
			service ?: run {
				fail(R.string.profiler_service_unavailable)
				return
			}
		_state.value = ProfilerUiState.Profiling.HeapDump(process)
		activeJob =
			viewModelScope.launch {
				try {
					val profile =
						withContext(Dispatchers.IO) {
							val file = cacheFile("heap-${process.pid}")
							try {
								openWriteFd(file).use { pfd ->
									// Blocks until the dump completes (the service waits on its callback).
									service.dumpHeapForPid(pfd, process.pid)
								}
								HeapDumpAnalyzer.analyze(file)
							} finally {
								file.delete()
							}
						}
					ensureActive() // the dump is a blocking binder call; bail if cancelled while it ran
					_state.value =
						ProfilerUiState.Completed(process, ProfilerReport.HeapDump(profile))
				} catch (e: CancellationException) {
					throw e // cancellation handled by cancelHeapDump()
				} catch (e: Throwable) {
					logger.error("Heap dump failed for pid={}", process.pid, e)
					fail(getString(R.string.profiler_heap_dump_failed, e.message ?: e.javaClass.simpleName))
				} finally {
					activeJob = null
				}
			}
	}

	/** Cancels an in-flight heap dump. The service-side dump may still finish; its result is discarded. */
	private fun cancelHeapDump() {
		cancelActiveRun()
		_state.value = ProfilerUiState.Failed(getString(R.string.profiler_cancelled), cancelled = true)
	}

	private fun startCpuProfiling(process: ProcessInfo) {
		val service =
			service ?: run {
				fail(R.string.profiler_service_unavailable)
				return
			}
		cpuProcess = process
		synchronized(cpuSamples) { cpuSamples.clear() }
		_state.value = ProfilerUiState.Profiling.CpuSampling(process, emptyList())
		runCatching { service.startCpuProfiling(process.pid, process.packageName, cpuObserver) }
			.onFailure {
				logger.error("Failed to start CPU profiling for pid={}", process.pid, it)
				cpuProcess = null
				fail(getString(R.string.profiler_cpu_failed, it.message ?: it.javaClass.simpleName))
			}
	}

	private fun finalizeCpuProfiling(process: ProcessInfo) {
		val service =
			service ?: run {
				fail(R.string.profiler_service_unavailable)
				return
			}
		cpuProcess = null // stop accepting live samples
		_state.update {
			if (it is ProfilerUiState.Profiling.CpuSampling) it.copy(finalizing = true) else it
		}
		activeJob =
			viewModelScope.launch {
				try {
					val profile =
						withContext(Dispatchers.IO) {
							val file = cacheFile("cpu-${process.pid}")
							try {
								openWriteFd(file).use { pfd ->
									// Blocks until simpleperf stops and the report-sample dump is written.
									service.stopCpuProfiling(pfd)
								}
								SimpleperfReportParser.parse(file)
							} finally {
								file.delete()
							}
						}
					if (profile.totalMicros <= 0L || profile.methods.isEmpty()) {
						logger.warn("CPU profiling produced no samples for pid={}", process.pid)
						fail(R.string.profiler_cpu_no_samples)
					} else {
						_state.value =
							ProfilerUiState.Completed(process, ProfilerReport.CpuSampling(profile))
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					logger.error("CPU report generation failed for pid={}", process.pid, e)
					fail(getString(R.string.profiler_cpu_failed, e.message ?: e.javaClass.simpleName))
				} finally {
					activeJob = null
				}
			}
	}

	/**
	 * Cancels an in-flight CPU report generation (the "Generating CPU report…" phase). Cancels the
	 * coroutine awaiting the report — its `finally` deletes the client-side temp file — and asks the
	 * service to kill the simpleperf/report-sample command and discard its leftover files. The
	 * service call runs off the main thread because [cancelActiveRun] only unblocks the awaiting
	 * binder call once the service-side processes are gone.
	 */
	private fun cancelCpuReport() {
		cancelActiveRun()
		val service = service
		_state.value = ProfilerUiState.Failed(getString(R.string.profiler_cancelled), cancelled = true)
		if (service != null) {
			viewModelScope.launch(Dispatchers.IO) {
				runCatching { service.cancelCpuProfiling() }
					.onFailure { logger.warn("Failed to cancel CPU report generation", it) }
			}
		}
	}

	private fun cancelActiveRun() {
		activeJob?.cancel()
		activeJob = null
		cpuProcess = null
		synchronized(cpuSamples) { cpuSamples.clear() }
	}

	private fun publishProcesses() {
		processesFlow.value =
			synchronized(processLock) {
				liveProcesses.values.map { it.toModel() }
			}
	}

	private fun cacheFile(prefix: String): File = File(getApplication<Application>().cacheDir, "$prefix-${System.currentTimeMillis()}.bin")

	private fun openWriteFd(file: File): ParcelFileDescriptor =
		ParcelFileDescriptor.open(
			file,
			ParcelFileDescriptor.MODE_CREATE or
				ParcelFileDescriptor.MODE_WRITE_ONLY or
				ParcelFileDescriptor.MODE_TRUNCATE,
		)

	private fun fail(
		@StringRes resId: Int,
	) {
		fail(getString(resId))
	}

	private fun fail(message: String) {
		_state.value = ProfilerUiState.Failed(message)
	}

	private fun getString(
		@StringRes resId: Int,
		vararg formatArgs: Any,
	): String = getApplication<Application>().getString(resId, *formatArgs)

	override fun onCleared() {
		// Project closed / editor destroyed: stop any in-flight work and tear down the service
		// (unbind with remove=true makes Shizuku destroy the user service, which aborts simpleperf).
		cancelActiveRun()
		connection.disconnect()
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
