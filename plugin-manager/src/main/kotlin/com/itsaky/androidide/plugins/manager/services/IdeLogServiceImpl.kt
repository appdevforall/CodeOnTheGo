package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeLogService
import com.itsaky.androidide.plugins.services.LogQuery
import com.itsaky.androidide.plugins.services.LogReadResult
import com.itsaky.androidide.plugins.services.LogSource
import org.slf4j.LoggerFactory

/**
 * Host side of [IdeLogService]. The log buffers live in the app module, so the app installs a
 * reader with [setLogReader]; until it does, every read is empty.
 */
class IdeLogServiceImpl internal constructor() : IdeLogService {
	@Volatile
	private var logReader: ((LogSource, LogQuery) -> LogReadResult)? = null

	companion object {
		private val log = LoggerFactory.getLogger(IdeLogServiceImpl::class.java)

		@Volatile
		private var instance: IdeLogServiceImpl? = null

		fun getInstance(): IdeLogServiceImpl =
			instance ?: synchronized(this) {
				instance ?: IdeLogServiceImpl().also { instance = it }
			}
	}

	override fun readLogs(
		source: LogSource,
		query: LogQuery,
	): LogReadResult {
		val reader = logReader ?: return LogReadResult.EMPTY
		// The contract is "empty, never a throw" for an unreadable log; a plugin should not have
		// to guard a read against host internals. That includes an Error such as a LinkageError
		// from a mismatched host, but not a VirtualMachineError, which no caller can recover from.
		return try {
			reader(source, query)
		} catch (e: VirtualMachineError) {
			throw e
		} catch (e: Throwable) {
			log.error("Failed to read {} logs", source, e)
			LogReadResult.EMPTY
		}
	}

	/** Installs the app's log reader. Called once by the app during plugin service setup. */
	fun setLogReader(reader: ((LogSource, LogQuery) -> LogReadResult)?) {
		logReader = reader
	}
}
