package com.itsaky.androidide.repositories

import com.itsaky.androidide.roomData.recentproject.DebuggerEndpointDao
import com.itsaky.androidide.sqlitedebugger.DebuggerEndpointValidation
import com.itsaky.androidide.utils.canonicalProjectLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Which app each project builds, and the SQLite debugger endpoint the app last reported (ADFA-7137).
 * Nothing here throws except [CancellationException]: any other failure is logged and reported as
 * false, because none of the callers can do anything about it, and they run on the application scope,
 * which has no CoroutineExceptionHandler (an escaping exception would crash the process).
 */
class DebuggerEndpointRepository(
	private val dao: DebuggerEndpointDao,
	private val scope: CoroutineScope,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	/** Installs expected to finish, by applicationId, with the project location that built them. */
	private val pendingInstalls = ConcurrentHashMap<String, String>()

	/**
	 * Notes, off the main thread, that [projectDir] is installing the app [applicationId] returns
	 * (it may read an APK). Nothing is written until [confirmInstall] reports the install succeeded.
	 */
	fun expectInstallAsync(
		projectDir: File,
		applicationId: () -> String?,
	) {
		scope.launch {
			try {
				val id = runCatching(applicationId).getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
				pendingInstalls[id] = projectDir.canonicalProjectLocation()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.warn("Could not note the install of project {}", projectDir, e)
			}
		}
	}

	/**
	 * Call when the install of [applicationId] succeeded. Pairs it with the project that built it,
	 * if [expectInstallAsync] noted one, and returns whether it did. An install the user started
	 * some other way, such as opening an APK file, is not paired.
	 */
	suspend fun confirmInstall(applicationId: String): Boolean {
		val location = pendingInstalls.remove(applicationId) ?: return false
		return recordInstalled(applicationId, location)
	}

	/**
	 * Moves [applicationId] to [projectLocation], clearing its token and port if the project changed.
	 * Returns false when the project has no recent_project_table row yet, or the write failed.
	 */
	suspend fun recordInstalled(
		applicationId: String,
		projectLocation: String,
	): Boolean = guarded("pair $applicationId with project $projectLocation") { dao.upsertPairing(applicationId, projectLocation) }

	/**
	 * Pairs [applicationId] with the project at [projectDir] only if it has no pairing; an existing
	 * one, with its token and port, is left alone because launching restarts nothing.
	 */
	suspend fun recordLaunched(
		applicationId: String,
		projectDir: File,
	): Boolean {
		val location = withContext(Dispatchers.IO) { projectDir.canonicalProjectLocation() }
		return recordLaunched(applicationId, location)
	}

	suspend fun recordLaunched(
		applicationId: String,
		projectLocation: String,
	): Boolean =
		guarded("pair launched $applicationId with project $projectLocation") {
			dao.insertPairingIfAbsent(applicationId, projectLocation)
		}

	/** Returns true when [applicationId] has a pairing and its token and port were stored. */
	suspend fun storeEndpoint(
		applicationId: String,
		endpoint: DebuggerEndpointValidation.Endpoint,
	): Boolean {
		var stored = false
		val ok =
			guarded("store the endpoint of $applicationId") {
				stored = dao.updateEndpoint(applicationId, endpoint.token, endpoint.port, clock()) > 0
			}
		return ok && stored
	}

	/**
	 * Room can throw more than SQLiteException (android.database.SQLException, IllegalStateException
	 * from a closed database), and a constraint failure is expected when the project is not in Recents.
	 */
	private suspend fun guarded(
		what: String,
		block: suspend () -> Unit,
	): Boolean =
		try {
			block()
			true
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			log.warn("Could not {}", what, e)
			false
		}

	private companion object {
		private val log = LoggerFactory.getLogger(DebuggerEndpointRepository::class.java)
	}
}
