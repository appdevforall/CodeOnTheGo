package com.itsaky.androidide.repositories

import android.content.pm.PackageManager
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
import com.itsaky.androidide.plugins.services.DebuggerEndpoint as PluginDebuggerEndpoint

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
	/** Required, with no default: one that answered true would serve an uninstalled app's port and token as live. */
	private val isInstalled: (String) -> Boolean,
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
	 * Call when the install of [applicationId] succeeded. Pairs it with the project that built it,
	 * if [expectInstallAsync] noted one, and returns whether it did. An install the user started
	 * some other way, such as opening an APK file, is not paired.
	 */
	suspend fun confirmInstall(applicationId: String): Boolean {
		val pending = pendingInstalls.remove(applicationId) ?: return false
		// A note this old belongs to an install that never reported a result.
		if (clock() - pending.noteTimeMillis > PENDING_INSTALL_MAX_AGE_MILLIS) return false
		return recordInstalled(applicationId, pending.location)
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

	/**
	 * The endpoints the apps of [projectDir] have reported, for the plugin API (ADFA-7139). Looked
	 * up by the canonical path, as the pairing stores it; apps that have not reported yet are left
	 * out, and so are apps that are no longer installed: their stored port and token would otherwise
	 * come back as live. An endpoint can still be stale when the app's process has died. Unlike the writes, a read failure is thrown: the plugin API reports it rather than
	 * answering "no apps".
	 */
	suspend fun reportedEndpoints(projectDir: File): List<PluginDebuggerEndpoint> =
		dao.getByProjectLocation(projectDir.canonicalProjectLocation()).mapNotNull { row ->
			if (!isInstalled(row.applicationId)) return@mapNotNull null
			val token = row.token ?: return@mapNotNull null
			val port = row.port ?: return@mapNotNull null
			PluginDebuggerEndpoint(row.applicationId, port, token, row.updatedAt ?: 0L)
		}

	companion object {
		/**
		 * Whether [applicationId] is installed. Every package is visible because Code on the Go targets SDK 28; at a
		 * target of 30 or later, package visibility filtering would make every endpoint look uninstalled unless the
		 * manifest declares a matching `<queries>`.
		 */
		fun isPackageInstalled(
			packageManager: PackageManager,
			applicationId: String,
		): Boolean =
			try {
				packageManager.getPackageInfo(applicationId, 0)
				true
			} catch (e: PackageManager.NameNotFoundException) {
				false
			}

		private const val PENDING_INSTALL_MAX_AGE_MILLIS = 5 * 60 * 1000L
		private val log = LoggerFactory.getLogger(DebuggerEndpointRepository::class.java)
	}
}
