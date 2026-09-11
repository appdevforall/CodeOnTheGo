package com.itsaky.androidide.viewmodels

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEffect
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEvent
import com.itsaky.androidide.utils.InstallTempFiles
import com.itsaky.androidide.utils.LastValueGate
import com.itsaky.androidide.utils.UriFileImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Handles a `.cgp`/`.cgt` file opened from outside the app (e.g. an email attachment), backing
 * [com.itsaky.androidide.activities.ExternalFileInstallActivity].
 */
class ExternalFileInstallViewModel(
	private val pluginRepository: PluginRepository,
	private val templateCollectionRepository: TemplateCollectionRepository,
	private val contentResolver: ContentResolver,
	private val filesDir: File,
) : ViewModel() {
	private companion object {
		private val log = LoggerFactory.getLogger(ExternalFileInstallViewModel::class.java)
		private val UNSAFE_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")

		// A cold OS-triggered launch of this activity can win the race against IDEApplication's
		// async setup (device-unlock -> CredentialProtectedApplicationLoader.load(), which itself
		// chains a long, unbounded sequence of Sentry/Firebase/EventBus/WorkManager/Termux/plugin
		// init work), so isPluginManagerAvailable()/isTemplatesFeatureAvailable() are polled
		// instead of failing on the very first check. ~8s total gives real cold starts a
		// realistic margin; there's no true completion signal to await instead (see ADFA-4934
		// code review notes), so this remains a bounded-poll approximation, not a hard guarantee.
		private const val SETUP_WAIT_ATTEMPTS = 20
		private const val SETUP_WAIT_INTERVAL_MS = 400L

		// Bounds suggestUniqueBaseName()'s search - a pathological repository (or a huge run of
		// pre-existing "foo (2)", "foo (3)", ... collections) must not hang the Rename dialog
		// forever waiting for a free name.
		private const val MAX_SUGGESTION_ATTEMPTS = 50
	}

	// Buffered (not rendezvous): onReceived() runs via Dispatchers.Main.immediate right after
	// Activity.onCreate() starts collecting uiEffect, and a synchronous decision path (e.g. an
	// unsupported file type) can otherwise complete before the collector actually attaches,
	// silently dropping the effect.
	private val _uiEffect = Channel<ExternalFileInstallUiEffect>(capacity = Channel.BUFFERED)
	val uiEffect = _uiEffect.receiveAsFlow()

	// onReceived() must run at most once per distinct uri per ViewModel instance: this instance
	// survives a rotation (so a duplicate call there for the same uri is a no-op, not a
	// re-processed intent), but is recreated fresh by Koin after process death (so the fresh
	// instance still processes the restored intent instead of the call being skipped entirely).
	private val receivedUriGate = LastValueGate<Uri>()

	private val _isInstalling = MutableStateFlow(false)
	val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

	// Monotonically increasing per onReceived() call, assigned synchronously (before launching
	// the coroutine below) so it always reflects real intent-arrival order. Two onReceived()
	// calls in quick succession (ExternalFileInstallActivity is singleTask, so a second VIEW
	// intent for a *different* file reaches this same instance via onNewIntent) run as
	// independent coroutines with no guarantee the first *finishes* before the second - a slow
	// first request can otherwise complete its async work (copy/inspect/collision-check) after a
	// faster second request already committed, and overwrite the Compose screen's single
	// dialogState slot with stale info. isCurrentGeneration() below lets each request notice, at
	// its final commit point, that it's been superseded and should abandon silently instead.
	private var currentRequestGeneration = 0

	// Tracks the temp file behind the most recently *committed* .cgt confirm/conflict dialog -
	// used to clean it up the moment a newer request supersedes it, rather than silently
	// orphaning it for InstallTempFiles' hour-long sweep. Only ever touched by whichever request
	// currently holds isCurrentGeneration()'s "true" (see supersedePendingConfirmation()), so
	// there's no ordering ambiguity about which file it refers to.
	private var pendingConfirmationTempFile: File? = null

	// The generation pendingConfirmationTempFile actually belongs to - NOT necessarily
	// currentRequestGeneration, which can already have moved on to a newer, still-in-flight
	// request by the time the user taps a button on the dialog still on screen (its onReceived()
	// bumped the counter synchronously, but hasn't reached supersedePendingConfirmation() yet).
	// confirmTemplateInstall()/onEvent() must key off this, not the live counter, or a stale
	// dialog's action gets misattributed to the newer request and can tear the Activity down out
	// from under it.
	private var pendingConfirmationGeneration: Int = 0

	private fun isCurrentGeneration(generation: Int) = generation == currentRequestGeneration

	private suspend fun supersedePendingConfirmation(
		newPendingFile: File?,
		newGeneration: Int,
	) {
		pendingConfirmationTempFile?.let { old -> if (old != newPendingFile) deleteQuietly(old) }
		pendingConfirmationTempFile = newPendingFile
		pendingConfirmationGeneration = newGeneration
	}

	/** Call once, from `Activity.onCreate()`/`onNewIntent()`, with the VIEW intent's data [Uri]. */
	fun onReceived(uri: Uri) {
		if (!receivedUriGate.consume(uri)) return

		val generation = ++currentRequestGeneration

		viewModelScope.launch {
			val displayName = withContext(Dispatchers.IO) { UriFileImporter.getDisplayName(contentResolver, uri) }
			val extension = displayName?.substringAfterLast('.', "")?.lowercase()

			if (displayName.isNullOrBlank() || extension.isNullOrBlank()) {
				sendErrorAndFinish(generation, R.string.msg_invalid_incoming_file)
				return@launch
			}

			if (extension != PLUGIN_ARCHIVE_EXTENSION && extension != TEMPLATE_ARCHIVE_EXTENSION) {
				sendErrorAndFinish(generation, R.string.msg_unsupported_file_type)
				return@launch
			}

			val featureAvailable =
				if (extension == PLUGIN_ARCHIVE_EXTENSION) {
					pluginRepository::isPluginManagerAvailable
				} else {
					templateCollectionRepository::isTemplatesFeatureAvailable
				}
			if (!awaitAvailable(featureAvailable)) {
				sendErrorAndFinish(generation, R.string.msg_ide_setup_incomplete)
				return@launch
			}

			val destination = InstallTempFiles.newTempFile(filesDir, "incoming", extension)

			val tempFile =
				try {
					withContext(Dispatchers.IO) {
						UriFileImporter.copyUriToFile(contentResolver, uri, destination) {
							IllegalStateException("Cannot open file")
						}
						destination
					}
				} catch (e: CancellationException) {
					withContext(NonCancellable + Dispatchers.IO) { deleteQuietlyBlocking(destination) }
					throw e
				} catch (e: Exception) {
					log.error("Failed to copy incoming file", e)
					withContext(Dispatchers.IO) { deleteQuietlyBlocking(destination) }
					sendErrorAndFinish(generation, R.string.msg_invalid_incoming_file)
					return@launch
				}

			if (!isCurrentGeneration(generation)) {
				// A newer VIEW intent has since arrived and is now authoritative - abandon this
				// one silently rather than emit an effect that would incorrectly supersede it.
				deleteQuietly(tempFile)
				return@launch
			}

			val baseName = sanitizeBaseName(displayName.substringBeforeLast('.', "templates"))

			if (extension == PLUGIN_ARCHIVE_EXTENSION) {
				// Forwarded as a plain path, not a content:// Uri: both activities run in this
				// same process and already trust filesDir paths, so PluginManagerViewModel can
				// install straight from this file instead of copying it a second time.
				supersedePendingConfirmation(null, generation)
				_uiEffect.trySend(ExternalFileInstallUiEffect.ForwardToPluginManager(tempFile.absolutePath))
			} else {
				dispatchTemplateInstall(tempFile, baseName, generation)
			}
		}
	}

	private suspend fun awaitAvailable(check: () -> Boolean): Boolean {
		repeat(SETUP_WAIT_ATTEMPTS) { attempt ->
			if (check()) return true
			if (attempt < SETUP_WAIT_ATTEMPTS - 1) delay(SETUP_WAIT_INTERVAL_MS)
		}
		return false
	}

	private suspend fun dispatchTemplateInstall(
		tempFile: File,
		baseName: String,
		generation: Int,
	) {
		val info =
			templateCollectionRepository.inspectCollection(tempFile).getOrElse { exception ->
				log.warn("Invalid template collection file: {}", tempFile.name, exception)
				deleteQuietly(tempFile)
				sendErrorAndFinish(generation, R.string.msg_template_invalid_file)
				return
			}

		val existing = templateCollectionRepository.findExistingCollision(baseName)

		if (!isCurrentGeneration(generation)) {
			deleteQuietly(tempFile)
			return
		}

		supersedePendingConfirmation(tempFile, generation)
		// This dialog's buttons must start enabled regardless of whether some earlier,
		// now-abandoned generation's install is still finishing up in the background (see
		// confirmTemplateInstall()'s own generation check for the other half of this).
		_isInstalling.value = false
		if (existing == null) {
			_uiEffect.trySend(
				ExternalFileInstallUiEffect.ShowTemplateInstallConfirmation(info, tempFile, baseName),
			)
		} else {
			_uiEffect.trySend(
				ExternalFileInstallUiEffect.ShowTemplateNameConflict(existing, info, tempFile),
			)
		}
	}

	fun onEvent(event: ExternalFileInstallUiEvent) {
		when (event) {
			is ExternalFileInstallUiEvent.ConfirmTemplateInstall -> {
				confirmTemplateInstall(event.tempFile, event.targetBaseName, event.overwrite)
			}

			is ExternalFileInstallUiEvent.IgnoreTemplateInstall -> {
				// If this doesn't match, the dialog this event was fired from has already been
				// superseded (and its tempFile already deleted by supersedePendingConfirmation) -
				// nothing left on screen to Finish, and Finish-ing anyway would tear down the
				// Activity out from under whatever newer dialog is now showing.
				if (pendingConfirmationTempFile == event.tempFile) {
					pendingConfirmationTempFile = null
					viewModelScope.launch {
						deleteQuietly(event.tempFile)
						_uiEffect.trySend(ExternalFileInstallUiEffect.Finish)
					}
				}
			}
		}
	}

	private fun confirmTemplateInstall(
		tempFile: File,
		targetBaseName: String,
		overwrite: Boolean,
	) {
		// Guards against a double-tap on Install/Overwrite/Rename firing this twice concurrently -
		// the second call's renameTo()/copyTo() would otherwise race the first's on the same
		// tempFile and surface a spurious failure toast.
		if (_isInstalling.value) return
		// If this doesn't match, the dialog this event was fired from has already been superseded
		// (its tempFile already deleted by supersedePendingConfirmation) - there's nothing left to
		// install, and proceeding anyway would mean using currentRequestGeneration as this
		// install's generation, misattributing it to whatever newer request bumped the counter.
		if (pendingConfirmationTempFile != tempFile) return
		_isInstalling.value = true
		// The generation the now-showing dialog was committed under - NOT currentRequestGeneration,
		// which may already have moved on to a newer, still-in-flight request (see
		// pendingConfirmationGeneration's kdoc). This install must neither tear down the Activity
		// out from under that newer request nor touch state that by then belongs to it.
		val generation = pendingConfirmationGeneration
		// From here on, tempFile's fate is owned by this install attempt, not "a dialog awaiting
		// an answer" - a subsequent onReceived() for a different file must not delete it out from
		// under an install already in flight.
		pendingConfirmationTempFile = null

		viewModelScope.launch {
			templateCollectionRepository
				.installCollection(tempFile, targetBaseName, overwrite)
				.onSuccess {
					// The install genuinely happened (the file's on disk in templatesDir) even if
					// a newer request has since taken over the screen, so still surface the
					// success - but only tear down the Activity (Finish) if nothing newer is now
					// relying on it staying alive. targetBaseName is included in the message so
					// the toast is unambiguous even when it overlays a newer, unrelated dialog.
					_uiEffect.trySend(
						ExternalFileInstallUiEffect.ShowSuccess(R.string.msg_template_installed, targetBaseName),
					)
					if (isCurrentGeneration(generation)) {
						// The Screen suspends on ShowSuccess until the flashbar's entrance
						// animation actually finishes (flashSuccessAwaitShown) before processing
						// the next buffered effect, so Finish here doesn't need its own delay.
						_uiEffect.trySend(ExternalFileInstallUiEffect.Finish)
					}
				}.onFailure { exception ->
					log.error("Failed to install template collection", exception)
					if (isCurrentGeneration(generation)) {
						// Deliberately don't delete tempFile or Finish here: the dialog the user
						// was just on (install-confirm / name-conflict / rename) stays open so
						// they can retry - e.g. pick a different name after a collision, or
						// Overwrite instead. If a newer request has since superseded this dialog,
						// there's nothing left on-screen to retry against, so skip ShowError too.
						// Restore pendingConfirmationTempFile/Generation (cleared above on entry):
						// a retry tap or Cancel/back on this still-open dialog must match again, or
						// confirmTemplateInstall()/IgnoreTemplateInstall's guards would treat every
						// button on it as a permanent no-op from here on.
						pendingConfirmationTempFile = tempFile
						pendingConfirmationGeneration = generation
						_uiEffect.trySend(
							ExternalFileInstallUiEffect.ShowError(
								R.string.msg_template_install_failed,
								listOf(exception.message ?: exception.javaClass.simpleName),
							),
						)
					}
				}
			if (isCurrentGeneration(generation)) {
				_isInstalling.value = false
			}
		}
	}

	/**
	 * Suggests a unique base name for the rename dialog by appending "(2)", "(3)", etc.
	 *
	 * Each candidate is checked via [TemplateCollectionRepository.findExistingCollision] - a
	 * fresh directory listing per call - rather than listing `templatesDir` once and checking
	 * membership in-memory. Left as-is deliberately: [MAX_SUGGESTION_ATTEMPTS] already bounds
	 * the worst case, a real templates directory is realistically small (a user's own installed
	 * collections), and avoiding the redundant scans would mean adding a batch-listing method to
	 * [TemplateCollectionRepository] purely for this one call site's benefit.
	 */
	suspend fun suggestUniqueBaseName(baseName: String): String {
		var candidate = baseName
		var suffix = 2
		// Collision must be checked before the attempt-count bound, not after: checking
		// `suffix <= MAX` first would let the bound short-circuit the very last candidate's
		// collision check, silently returning it unverified once the cap is hit.
		while (templateCollectionRepository.findExistingCollision(candidate) != null && suffix <= MAX_SUGGESTION_ATTEMPTS) {
			candidate = "$baseName ($suffix)"
			suffix++
		}
		return candidate
	}

	fun sanitizeBaseName(rawName: String): String = rawName.replace(UNSAFE_FILENAME_CHARS, "_").trim().ifBlank { "templates" }

	private suspend fun sendErrorAndFinish(
		generation: Int,
		@StringRes messageResId: Int,
	) {
		if (!isCurrentGeneration(generation)) return
		// A stale (already-superseded) request never reaches here (see the isCurrentGeneration
		// check above), so whatever's still pending at this point genuinely belongs to an earlier,
		// now-being-terminated request and must be cleaned up rather than left dangling.
		supersedePendingConfirmation(null, generation)
		// See confirmTemplateInstall()'s onSuccess: the Screen suspends on ShowError until the
		// flashbar is actually shown before processing Finish, so no delay is needed here either.
		_uiEffect.trySend(ExternalFileInstallUiEffect.ShowError(messageResId))
		_uiEffect.trySend(ExternalFileInstallUiEffect.Finish)
	}

	private suspend fun deleteQuietly(file: File) {
		withContext(Dispatchers.IO) { deleteQuietlyBlocking(file) }
	}

	private fun deleteQuietlyBlocking(file: File) {
		if (file.exists()) {
			file.delete()
		}
	}
}
