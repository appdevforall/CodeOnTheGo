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
	/**
	 * Asks the app to report its endpoint again, after each pairing; see DebuggerEndpointAnnounce. Required, with no
	 * default: one that did nothing would leave an app that was already running unknown until its process restarts.
	 */
	private val announce: (applicationId: String) -> Unit,
) {
	private class PendingInstall(
		val location: String,
		val noteTimeMillis: Long,
	)

	/** Installs expected to finish, by applicationId, with the project location that built them. */
	private val pendingInstalls = ConcurrentHashMap<String, PendingInstall>()

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
				pendingInstalls[id] = PendingInstall(projectDir.canonicalProjectLocation(), clock())
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.warn("Could not note the install of project {}", projectDir, e)
			}
		}
	}

	/**
	 * Call when the install of [applicationId] succeeded, before the app is launched, and returns whether it paired
	 * the app. With a note from [expectInstallAsync] at most 5 minutes old, the app moves to the project that built
	 * it. Without one, as when the install prompt stayed open longer, the launch that follows pairs it as
	 * [recordLaunched] does, with [launchingProjectDir]: the caller passes the open project's directory when that
	 * project builds [applicationId], and null otherwise, so an unrelated APK installed from a file is not paired.
	 */
	suspend fun confirmInstall(
		applicationId: String,
		launchingProjectDir: File?,
	): Boolean {
		val pending = pendingInstalls.remove(applicationId)
		// An older note may belong to an install that never reported a result, from a project that is no longer
		// open, so it must not move the pairing; recordLaunched never moves one.
		if (pending != null && clock() - pending.noteTimeMillis <= PENDING_INSTALL_MAX_AGE_MILLIS) {
			return recordInstalled(applicationId, pending.location)
		}
		return launchingProjectDir != null && recordLaunched(applicationId, launchingProjectDir)
	}

	/**
	 * Call when the install of [applicationId] ended without success (failed, cancelled, blocked), so
	 * a later install of the same app from elsewhere, such as an APK file, is not paired with the
	 * project that started this one.
	 */
	fun discardPendingInstall(applicationId: String) {
		pendingInstalls.remove(applicationId)
	}

	/**
	 * Moves [applicationId] to [projectLocation], clearing its token and port if the project changed, then asks the
	 * app to report again. Returns false when the project has no recent_project_table row yet, or the write failed.
	 */
	suspend fun recordInstalled(
		applicationId: String,
		projectLocation: String,
	): Boolean =
		guarded("pair $applicationId with project $projectLocation") { dao.upsertPairing(applicationId, projectLocation) }
			.also { if (it) announce(applicationId) }

	/**
	 * Pairs [applicationId] with the project at [projectDir] only if it has no pairing; an existing
	 * one, with its token and port, is left alone because launching restarts nothing. Either way the
	 * app is then asked to report again, which an app that was already running needs.
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
		}.also { if (it) announce(applicationId) }

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
		private const val PENDING_INSTALL_MAX_AGE_MILLIS = 5 * 60 * 1000L
		private val log = LoggerFactory.getLogger(DebuggerEndpointRepository::class.java)
	}
}
