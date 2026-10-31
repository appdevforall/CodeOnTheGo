/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.actions.file

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.ExternalFileInstallActivity
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.fileProviderUriFor
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.launch
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import org.koin.core.context.GlobalContext
import java.io.File

/** What the file-tab Install action does with a file; one kind per supported extension. */
internal enum class InstallKind { APK, PLUGIN, TEMPLATE }

/** The [InstallKind] for [file], by case-insensitive extension, or null when it is not installable. */
internal fun installKindFor(file: File?): InstallKind? =
	when (file?.extension?.lowercase()) {
		"apk" -> InstallKind.APK
		PLUGIN_ARCHIVE_EXTENSION -> InstallKind.PLUGIN
		TEMPLATE_ARCHIVE_EXTENSION -> InstallKind.TEMPLATE
		else -> null
	}

/**
 * An explicit VIEW intent that hands [file] to [ExternalFileInstallActivity] as a FileProvider
 * `content://` Uri, so the editor reuses the install dialogs built for files opened from other apps.
 */
internal fun templateInstallIntent(
	context: Context,
	file: File,
): Intent =
	Intent(Intent.ACTION_VIEW)
		.setClass(context, ExternalFileInstallActivity::class.java)
		.setData(context.fileProviderUriFor(file))
		.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

class InstallFileAction(
	context: Context,
	override val order: Int,
) : FileTabAction() {
	override val id: String = "ide.editor.fileTab.install"

	init {
		label = context.getString(R.string.action_install)
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)
		if (!visible) return
		val activity =
			data.getActivity() ?: run {
				markInvisible()
				return
			}
		val currentFile = activity.editorViewModel.getCurrentFile()
		visible = installKindFor(currentFile) != null
		enabled = visible
	}

	override fun EditorHandlerActivity.doAction(data: ActionData): Boolean {
		val file = editorViewModel.getCurrentFile() ?: return false
		when (installKindFor(file)) {
			InstallKind.APK -> {
				apkInstallationViewModel.installApk(
					context = this,
					apk = file,
					launchInDebugMode = false,
				)
			}

			InstallKind.PLUGIN -> {
				lifecycleScope.launch {
					val repo = GlobalContext.get().get<PluginRepository>()
					repo
						.installPluginFromFile(file)
						.onSuccess {
							flashSuccess(getString(R.string.msg_plugin_installed_restart))
							DialogUtils.showRestartPrompt(this@doAction)
						}.onFailure { e ->
							flashError(getString(R.string.msg_plugin_install_failed, e.message))
						}
				}
			}

			InstallKind.TEMPLATE -> {
				// FileProvider.getUriForFile rejects a path outside the provider's roots; flash the same
				// "could not read" error the shared install screen uses instead of crashing the editor.
				try {
					startActivity(templateInstallIntent(this, file))
				} catch (e: IllegalArgumentException) {
					flashError(getString(R.string.msg_invalid_incoming_file))
					return false
				} catch (e: ActivityNotFoundException) {
					flashError(getString(R.string.msg_invalid_incoming_file))
					return false
				}
			}

			null -> {
				return false
			}
		}
		return true
	}
}
