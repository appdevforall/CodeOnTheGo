package com.itsaky.androidide.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.provider.IDEFileProvider
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEffect
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEvent
import com.itsaky.androidide.utils.UriFileImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import java.io.File
import java.util.UUID

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
		private const val TAG = "ExternalFileInstallVM"
		private val UNSAFE_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")

		// A cold OS-triggered launch of this activity can win the race against
		// IDEApplication's async setup (device-unlock -> CredentialProtectedApplicationLoader.load()),
		// so isPluginManagerAvailable()/isTemplatesFeatureAvailable() are polled briefly
		// instead of failing on the very first check.
		private const val SETUP_WAIT_ATTEMPTS = 10
		private const val SETUP_WAIT_INTERVAL_MS = 300L
	}

	// Buffered (not rendezvous): onReceived() runs via Dispatchers.Main.immediate right after
	// Activity.onCreate() starts collecting uiEffect, and a synchronous decision path (e.g. an
	// unsupported file type) can otherwise complete before the collector actually attaches,
	// silently dropping the effect.
	private val _uiEffect = Channel<ExternalFileInstallUiEffect>(capacity = Channel.BUFFERED)
	val uiEffect = _uiEffect.receiveAsFlow()

	// onReceived() must run exactly once per ViewModel instance: this instance survives a
	// rotation (so a duplicate call there is a no-op, not a re-processed intent), but is
	// recreated fresh by Koin after process death (so the fresh instance still processes the
	// restored intent instead of the call being skipped entirely).
	private var received = false

	/** Call once, from `Activity.onCreate()`, with the VIEW intent's data [Uri]. */
	fun onReceived(
		context: Context,
		uri: Uri,
	) {
		if (received) return
		received = true

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

			val tempDir = File(filesDir, "temp").apply { mkdirs() }
			val destination = File(tempDir, "incoming_${UUID.randomUUID()}.$extension")

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
					Log.e(TAG, "Failed to copy incoming file", e)
					withContext(Dispatchers.IO) { deleteQuietlyBlocking(destination) }
					sendErrorAndFinish(R.string.msg_invalid_incoming_file)
					return@launch
				}

			val baseName = sanitizeBaseName(displayName.substringBeforeLast('.', "templates"))

			if (extension == PLUGIN_ARCHIVE_EXTENSION) {
				val fileProviderUri = IDEFileProvider.getUriForFile(context, tempFile)
				_uiEffect.trySend(ExternalFileInstallUiEffect.ForwardToPluginManager(fileProviderUri))
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
				Log.w(TAG, "Invalid template collection file: ${tempFile.name}", exception)
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
		viewModelScope.launch {
			templateCollectionRepository
				.installCollection(tempFile, targetBaseName, overwrite)
				.onSuccess {
					_uiEffect.trySend(ExternalFileInstallUiEffect.ShowSuccess(R.string.msg_template_installed))
					_uiEffect.trySend(ExternalFileInstallUiEffect.Finish)
				}.onFailure { exception ->
					// Deliberately don't delete tempFile or Finish here: the dialog the user was
					// just on (install-confirm / name-conflict / rename) stays open so they can
					// retry - e.g. pick a different name after a collision, or Overwrite instead.
					Log.e(TAG, "Failed to install template collection", exception)
					_uiEffect.trySend(ExternalFileInstallUiEffect.ShowError(R.string.msg_template_install_failed))
				}
		}
	}

	/** Suggests a unique base name for the rename dialog by appending "(2)", "(3)", etc. */
	suspend fun suggestUniqueBaseName(baseName: String): String {
		var candidate = baseName
		var suffix = 2
		while (templateCollectionRepository.findExistingCollision(candidate) != null) {
			candidate = "$baseName ($suffix)"
			suffix++
		}
		return candidate
	}

	fun sanitizeBaseName(rawName: String): String = rawName.replace(UNSAFE_FILENAME_CHARS, "_").trim().ifBlank { "templates" }

	private suspend fun sendErrorAndFinish(
		@StringRes messageResId: Int,
	) {
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
