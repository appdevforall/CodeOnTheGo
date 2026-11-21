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
 * The writes throw nothing but [CancellationException]: any other failure is logged and reported as
 * false, because none of their callers can do anything about it, and they run on the application
 * scope, which has no CoroutineExceptionHandler (an escaping exception would crash the process).
 * [reportedEndpoints], the read behind the plugin API, is the exception: it throws on a read failure,
 * so call it where a failure is handled, not on the application scope.
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
	 * reports for another reason, or whose real success result names another package or none (see [confirmInstall]),
	 * stays unused until the process ends: session IDs are not reused.
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
	 * pairs the app with the project that started it on the application scope, moving it from any other project and
	 * clearing the endpoint it reported before (see [recordInstalled]), and returns that write; the caller need not
	 * wait for it before launching the app, which is asked to report again once paired. Only this session's result
	 * uses its note, however long the install took: another install of the same application ID, from this project
	 * or another, has its own session.
	 *
	 * Returns null, and writes nothing, for an install that was not noted, such as an APK opened from the file
	 * tree; its next launch from a project pairs it. Also when [installedApplicationId], the package the result
	 * names, is missing or is not the noted one: the result comes through an exported receiver, so it can be forged.
	 * Such a result leaves the note, so the session's real result still pairs. Unverified: that the framework names
	 * the package in every success result. AOSP's PackageInstallerSession.sendOnPackageInstalled is said to, and
	 * ProxyAppInstallerEdgeTest says some OEM installers do not; a real success that names none is paired at the
	 * app's next launch from its project instead.
	 */
	fun confirmInstall(
		sessionId: Int,
		installedApplicationId: String?,
	): Job? {
		val note = pendingInstalls[sessionId] ?: return null
		if (installedApplicationId != note.applicationId) {
			log.warn("Session {} installed {}, not {}; not paired", sessionId, installedApplicationId, note.applicationId)
			return null
		}
		// Of two results that both pass the check, only the first pairs.
		if (!pendingInstalls.remove(sessionId, note)) return null
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
	 * Moves the just-installed [applicationId] to [projectLocation] and clears its token and port, then asks the app
	 * to report again. Returns false when the project has no recent_project_table row yet, or the write failed.
	 *
	 * The install replaced the process that reported them, so they are dead whether or not the new build has the
	 * SQLite debugger: a build without it never reports again, and its row would otherwise keep a dead port. The
	 * clear is in the pairing's transaction, before the request: a report the new process sent before this write
	 * (the launch does not wait for it) is cleared, then sent again in reply to the request.
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
	 * up by the canonical path, as the pairing stores it; an app is left out until it reports again
	 * only when a Run install from ProjectHandlerActivity cleared its endpoint (a success result that
	 * names the package and arrives in the same Code on the Go process). An app installed by Quick
	 * Build, by a Run install with no project open or whose APK's application ID could not be read, by a
	 * success result with no package name, or by a result that arrived after Code on the Go restarted
	 * keeps its old token and port until it reports, so it can be listed with a dead endpoint. Apps that
	 * are no longer installed are left out too: their stored port and token would otherwise come back as
	 * live. An endpoint can also be stale when the app's process has died. Unlike the writes, a read failure is thrown: the plugin API reports it rather than answering "no apps".
	 */
	suspend fun reportedEndpoints(projectDir: File): List<PluginDebuggerEndpoint> =
		dao.getByProjectLocation(projectDir.canonicalProjectLocation()).mapNotNull { row ->
			val token = row.token ?: return@mapNotNull null
			val port = row.port ?: return@mapNotNull null
			// Last: a binder call per app, which an app that has not reported need not cost.
			if (!isInstalled(row.applicationId)) return@mapNotNull null
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
