package com.itsaky.androidide.repositories

import android.content.pm.PackageManager
import com.itsaky.androidide.roomData.recentproject.DebuggerEndpointDao
import com.itsaky.androidide.sqlitedebugger.DebuggerEndpointValidation
import com.itsaky.androidide.utils.canonicalProjectLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
	/**
	 * Asks the app to report its endpoint again, after each pairing; see DebuggerEndpointAnnounce. Required, with no
	 * default: one that did nothing would leave an app that was already running unknown until its process restarts.
	 */
	private val announce: (applicationId: String) -> Unit,
) {
	private class PendingInstall(
		val applicationId: String,
		val location: String,
	)

	/**
	 * Installs Code on the Go started from a project, by PackageInstaller session ID, until the session reports a
	 * result. Held by this application-wide singleton, so recreating the editor Activity loses none; a process death
	 * loses them all, and each app is then paired by its next launch from its project (LaunchAppAction). Nothing
	 * expires, so a slow install still finds its note. A session whose staging or commit throws reports no result,
	 * so ApkInstaller's onCommitFailed drops its note through [discardPendingInstall]; a note whose session never
	 * reports for another reason stays unused until the process ends: session IDs are not reused.
	 */
	private val pendingInstalls = ConcurrentHashMap<Int, PendingInstall>()

	/**
	 * Notes that the PackageInstaller session [sessionId] installs [applicationId], built by [projectDir]. Call it
	 * before the session is committed, so its result always finds the note. It reads the file system: not on the
	 * main thread.
	 */
	fun notePendingInstall(
		sessionId: Int,
		projectDir: File,
		applicationId: String,
	) {
		pendingInstalls[sessionId] = PendingInstall(applicationId, projectDir.canonicalProjectLocation())
	}

	/**
	 * Call when the session [sessionId] succeeded, whether or not a launch follows. If [notePendingInstall] noted it,
	 * pairs the app with the project that started it on the application scope, moving it from any other project,
	 * and returns that write; the caller need not wait for it before launching the app, which is asked to report
	 * again once paired. Only this session's result uses its note, however long the install took: another install
	 * of the same application ID, from this project or another, has its own session.
	 *
	 * Returns null, and writes nothing, for an install that was not noted, such as an APK opened from the file
	 * tree; its next launch from a project pairs it. Also when [installedApplicationId], the package the result
	 * names, is missing or is not the noted one: the result comes through an exported receiver, so it can be forged,
	 * and the framework names the package in every result it sends.
	 */
	fun confirmInstall(
		sessionId: Int,
		installedApplicationId: String?,
	): Job? {
		val note = pendingInstalls.remove(sessionId) ?: return null
		if (installedApplicationId != note.applicationId) {
			log.warn("Session {} installed {}, not {}; not paired", sessionId, installedApplicationId, note.applicationId)
			return null
		}
		return scope.launch { recordInstalled(note.applicationId, note.location) }
	}

	/**
	 * Call when the session [sessionId] failed, was aborted or was blocked, or could not be committed, to drop its
	 * note; other sessions' stay.
	 */
	fun discardPendingInstall(sessionId: Int) {
		pendingInstalls.remove(sessionId)
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

		private val log = LoggerFactory.getLogger(DebuggerEndpointRepository::class.java)
	}
}
