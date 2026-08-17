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

	/** Call once, from `Activity.onCreate()`, with the VIEW intent's data [Uri]. */
	fun onReceived(uri: Uri) {
		if (!receivedUriGate.consume(uri)) return

		viewModelScope.launch {
			val displayName = withContext(Dispatchers.IO) { UriFileImporter.getDisplayName(contentResolver, uri) }
			val extension = displayName?.substringAfterLast('.', "")?.lowercase()

			if (displayName.isNullOrBlank() || extension.isNullOrBlank()) {
				sendErrorAndFinish(R.string.msg_invalid_incoming_file)
				return@launch
			}

			if (extension != PLUGIN_ARCHIVE_EXTENSION && extension != TEMPLATE_ARCHIVE_EXTENSION) {
				sendErrorAndFinish(R.string.msg_unsupported_file_type)
				return@launch
			}

			if (extension == PLUGIN_ARCHIVE_EXTENSION &&
				!awaitAvailable(pluginRepository::isPluginManagerAvailable)
			) {
				sendErrorAndFinish(R.string.msg_ide_setup_incomplete)
				return@launch
			}

			if (extension == TEMPLATE_ARCHIVE_EXTENSION &&
				!awaitAvailable(templateCollectionRepository::isTemplatesFeatureAvailable)
			) {
				sendErrorAndFinish(R.string.msg_ide_setup_incomplete)
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
					sendErrorAndFinish(R.string.msg_invalid_incoming_file)
					return@launch
				}

			val baseName = sanitizeBaseName(displayName.substringBeforeLast('.', "templates"))

			if (extension == PLUGIN_ARCHIVE_EXTENSION) {
				// Forwarded as a plain path, not a content:// Uri: both activities run in this
				// same process and already trust filesDir paths, so PluginManagerViewModel can
				// install straight from this file instead of copying it a second time.
				_uiEffect.trySend(ExternalFileInstallUiEffect.ForwardToPluginManager(tempFile.absolutePath))
			} else {
				dispatchTemplateInstall(tempFile, baseName)
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
	) {
		val info =
			templateCollectionRepository.inspectCollection(tempFile).getOrElse { exception ->
				log.warn("Invalid template collection file: {}", tempFile.name, exception)
				deleteQuietly(tempFile)
				sendErrorAndFinish(R.string.msg_template_invalid_file)
				return
			}

		val existing = templateCollectionRepository.findExistingCollision(baseName)
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
				viewModelScope.launch {
					deleteQuietly(event.tempFile)
					_uiEffect.trySend(ExternalFileInstallUiEffect.Finish)
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
		_isInstalling.value = true

		viewModelScope.launch {
			templateCollectionRepository
				.installCollection(tempFile, targetBaseName, overwrite)
				.onSuccess {
					// The Screen suspends on ShowSuccess until the flashbar's entrance animation
					// actually finishes (flashSuccessAwaitShown) before processing the next
					// buffered effect, so Finish here doesn't need its own artificial delay.
					_uiEffect.trySend(ExternalFileInstallUiEffect.ShowSuccess(R.string.msg_template_installed))
					_uiEffect.trySend(ExternalFileInstallUiEffect.Finish)
				}.onFailure { exception ->
					// Deliberately don't delete tempFile or Finish here: the dialog the user was
					// just on (install-confirm / name-conflict / rename) stays open so they can
					// retry - e.g. pick a different name after a collision, or Overwrite instead.
					log.error("Failed to install template collection", exception)
					_uiEffect.trySend(ExternalFileInstallUiEffect.ShowError(R.string.msg_template_install_failed))
				}
			_isInstalling.value = false
		}
	}

	/** Suggests a unique base name for the rename dialog by appending "(2)", "(3)", etc. */
	suspend fun suggestUniqueBaseName(baseName: String): String {
		var candidate = baseName
		var suffix = 2
		while (suffix <= MAX_SUGGESTION_ATTEMPTS && templateCollectionRepository.findExistingCollision(candidate) != null) {
			candidate = "$baseName ($suffix)"
			suffix++
		}
		return candidate
	}

	fun sanitizeBaseName(rawName: String): String = rawName.replace(UNSAFE_FILENAME_CHARS, "_").trim().ifBlank { "templates" }

	private fun sendErrorAndFinish(
		@StringRes messageResId: Int,
	) {
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
