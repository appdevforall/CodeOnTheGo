package org.appdevforall.cotg.profiler

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.appdevforall.cotg.profiler.aidl.ICpuProfileObserver
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single CPU profiling session: records [pid] with the device's `simpleperf` while streaming live
 * CPU-usage samples (derived from `/proc/<pid>/stat`) to [observer]. On [stop] it SIGINTs simpleperf
 * (so it flushes `perf.data`), converts the recording to a protobuf `report-sample` dump, and copies
 * that into the caller-provided file descriptor.
 *
 * Runs as the shell user, so it can exec `simpleperf` and read `/proc` directly.
 *
 * @author Akash Yadav
 */
class CpuProfilingSession(
	val pid: Int,
	private val packageName: String?,
	private val observer: ICpuProfileObserver,
) {
	private companion object {
		const val TAG = "CpuProfilingSession"
		const val SIMPLEPERF = "/system/bin/simpleperf"
		const val SAMPLE_INTERVAL_MS = 500L
		const val STOP_TIMEOUT_SECONDS = 15L

		/** Kernel clock ticks per second (USER_HZ), used to convert /proc cpu time to seconds. */
		val CLOCK_TICKS_PER_SEC: Long = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()?.takeIf { it > 0 } ?: 100L
	}

	private val perfDataPath = "/data/local/tmp/cotg-perf-$pid-${System.currentTimeMillis()}.data"
	private val reportPath = "$perfDataPath.report"
	private val active = AtomicBoolean(false)

	/** Set by [cancel] so an in-flight [stop] bails out instead of producing a report. */
	private val cancelled = AtomicBoolean(false)

	private var recordProcess: Process? = null
	private var recordPid: Int = -1

	/** The report-sample process, tracked (volatile) so [cancel] can kill it from another thread. */
	@Volatile
	private var reportProcess: Process? = null

	@Volatile
	private var sampler: ScheduledExecutorService? = null
	private var startNanos = 0L

	// Sampler-thread-confined state for computing CPU% deltas.
	private var prevTicks = -1L
	private var prevNanos = 0L

	fun isActive(): Boolean = active.get()

	/** Starts recording. Returns false (and notifies [observer]) if simpleperf can't be launched. */
	@Synchronized
	fun start(): Boolean {
		if (!File(SIMPLEPERF).exists()) {
			notifyError("simpleperf is not available at $SIMPLEPERF on this device")
			return false
		}

		// On non-rooted devices, shell can only profile a profileable/debuggable app through the app
		// context. `--app` makes simpleperf re-enter via simpleperf_app_runner/run-as to gain that
		// permission, while `-p` keeps recording scoped to the selected process (simpleperf skips its
		// own process discovery once a target is set). The shell parent still owns the `-o` file
		// (passed to the in-app recorder as --out-fd) and forwards our SIGINT to it via a stop pipe.
		val cmd =
			buildList {
				add(SIMPLEPERF)
				add("record")
				add("-e")
				add("cpu-clock")
				add("-f")
				add("4000")
				add("-g")
				if (!packageName.isNullOrBlank()) {
					add("--app")
					add(packageName)
				}
				add("-p")
				add(pid.toString())
				add("-o")
				add(perfDataPath)
			}
		recordProcess =
			try {
				ProcessBuilder(cmd).redirectErrorStream(true).start()
			} catch (e: Exception) {
				notifyError("Failed to start simpleperf: ${e.message}")
				return false
			}

		recordPid = recordProcess?.let(::osPidOf) ?: -1
		active.set(true)
		startNanos = System.nanoTime()

		// Watch simpleperf: if it exits on its own (e.g. permission denied) while we still believe the
		// session is active, surface its output as an error.
		Thread({
			val output = runCatching { recordProcess?.inputStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
			val code = runCatching { recordProcess?.waitFor() }.getOrNull() ?: -1
			if (active.getAndSet(false)) {
				stopSampler()
				notifyError("simpleperf exited unexpectedly (code $code): ${output.takeLast(400)}")
			}
		}, "simpleperf-record-watch").start()

		startSampler()
		runCatching { observer.onProfilingStarted() }
		return true
	}

	/**
	 * Stops recording, generates a protobuf report-sample dump, and writes it to [reportOut] (always
	 * closed). Blocking; call off the main/binder dispatch thread.
	 */
	@Synchronized
	fun stop(reportOut: ParcelFileDescriptor) {
		active.set(false)
		stopSampler()

		recordProcess?.let { proc ->
			if (recordPid > 0) runCatching { android.os.Process.sendSignal(recordPid, OsConstants.SIGINT) }
			runCatching {
				if (!proc.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					proc.destroyForcibly()
				}
			}
		}

		try {
			if (cancelled.get()) {
				// Cancelled while stopping the recorder: skip report generation, but still release the
				// caller-supplied fd (the AutoCloseOutputStream below never gets a chance to close it).
				runCatching { reportOut.close() }
				return
			}
			ParcelFileDescriptor.AutoCloseOutputStream(reportOut).use { output ->
				runCatching {
					val reportCmd =
						listOf(
							SIMPLEPERF,
							"report-sample",
							"--protobuf",
							"--show-callchain",
							"-i",
							perfDataPath,
							"-o",
							reportPath,
						)
					val report = ProcessBuilder(reportCmd).redirectErrorStream(true).start()
					reportProcess = report
					val reportOutput = report.inputStream.bufferedReader().readText()
					val code = report.waitFor()
					if (code != 0) {
						Log.w(TAG, "report-sample exited $code: ${reportOutput.takeLast(400)}")
					}
					FileInputStream(reportPath).use { it.copyTo(output) }
				}.onFailure { Log.e(TAG, "Failed to generate report-sample for pid=$pid", it) }
			}
		} finally {
			reportProcess = null
			runCatching { File(perfDataPath).delete() }
			runCatching { File(reportPath).delete() }
		}
	}

	/** Aborts the session without producing a report (used on service shutdown). */
	@Synchronized
	fun abort() {
		active.set(false)
		stopSampler()
		recordProcess?.let { proc ->
			if (recordPid > 0) runCatching { android.os.Process.sendSignal(recordPid, OsConstants.SIGKILL) }
			runCatching { proc.destroyForcibly() }
		}
		runCatching { File(perfDataPath).delete() }
	}

	/**
	 * Cancels the session because the client backed out — kills both the simpleperf recorder and any
	 * in-flight report-sample process, and discards the leftover temp files. Unlike [abort]/[stop]
	 * this is intentionally NOT `@Synchronized`: it must be able to interrupt a [stop] call that is
	 * blocked waiting on those child processes, so it only touches thread-safe / volatile state.
	 */
	fun cancel() {
		cancelled.set(true)
		active.set(false)
		stopSampler()
		recordProcess?.let { proc ->
			if (recordPid > 0) runCatching { android.os.Process.sendSignal(recordPid, OsConstants.SIGKILL) }
			runCatching { proc.destroyForcibly() }
		}
		reportProcess?.let { runCatching { it.destroyForcibly() } }
		runCatching { File(perfDataPath).delete() }
		runCatching { File(reportPath).delete() }
	}

	private fun startSampler() {
		val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cpu-sampler-$pid") }
		sampler = exec
		exec.scheduleAtFixedRate(::sampleOnce, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
	}

	private fun stopSampler() {
		sampler?.shutdownNow()
		sampler = null
	}

	private fun sampleOnce() {
		if (!active.get()) return

		val ticks = readProcessCpuTicks()
		val now = System.nanoTime()
		if (ticks < 0) {
			// /proc entry gone: the target process died.
			if (active.getAndSet(false)) {
				stopSampler()
				notifyError("Target process $pid is no longer running")
			}
			return
		}

		if (prevTicks >= 0) {
			val deltaTicks = ticks - prevTicks
			val deltaSeconds = (now - prevNanos) / 1_000_000_000.0
			val percent =
				if (deltaSeconds > 0) {
					((deltaTicks.toDouble() / CLOCK_TICKS_PER_SEC) / deltaSeconds * 100.0).toFloat()
				} else {
					0f
				}
			val elapsedMillis = (now - startNanos) / 1_000_000L
			runCatching { observer.onCpuSample(elapsedMillis, percent.coerceAtLeast(0f)) }
		}
		prevTicks = ticks
		prevNanos = now
	}

	/** Sum of utime+stime (clock ticks) from /proc/<pid>/stat, or -1 if unavailable. */
	private fun readProcessCpuTicks(): Long =
		try {
			val stat = File("/proc/$pid/stat").readText()
			// The comm field (2) is wrapped in parens and may contain spaces; parse after the last ')'.
			val afterComm = stat.substring(stat.lastIndexOf(')') + 1).trim()
			val fields = afterComm.split(Regex("\\s+"))
			// fields[0] = state (field 3); utime = field 14 -> index 11; stime = field 15 -> index 12.
			fields[11].toLong() + fields[12].toLong()
		} catch (e: Exception) {
			-1L
		}

	private fun notifyError(message: String) {
		Log.w(TAG, message)
		runCatching { observer.onProfilingError(message) }
	}

	/**
	 * OS pid of a [Process]. `Process.pid()` is a Java 9 API not visible at this module's language
	 * level, so it is invoked reflectively (present at runtime on API 26+), with a field fallback.
	 */
	private fun osPidOf(process: Process): Int =
		runCatching { (Process::class.java.getMethod("pid").invoke(process) as Long).toInt() }
			.recoverCatching {
				process.javaClass
					.getDeclaredField("pid")
					.apply { isAccessible = true }
					.getInt(process)
			}.getOrDefault(-1)
}
