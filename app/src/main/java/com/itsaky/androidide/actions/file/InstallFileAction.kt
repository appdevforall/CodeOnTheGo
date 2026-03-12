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

import android.content.Context
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class InstallFileAction(context: Context, override val order: Int) : FileTabAction() {

    override val id: String = "ide.editor.fileTab.install"

    init {
        label = context.getString(R.string.action_install)
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (!visible) return
        val activity = data.getActivity() ?: run { markInvisible(); return }
        val currentFile = activity.editorViewModel.getCurrentFile()
        visible = currentFile?.extension?.lowercase() in setOf("apk", "cgp")
        enabled = visible
    }

    override fun EditorHandlerActivity.doAction(data: ActionData): Boolean {
        val file = editorViewModel.getCurrentFile() ?: return false
        when (file.extension.lowercase()) {
            "apk" -> apkInstallationViewModel.installApk(
                context = this, apk = file, launchInDebugMode = false
            )
            "cgp" -> lifecycleScope.launch {
                val repo = GlobalContext.get().get<PluginRepository>()
                repo.installPluginFromFile(file)
                    .onSuccess {
                        flashSuccess(getString(R.string.msg_plugin_installed_restart))
                        DialogUtils.showRestartPrompt(this@doAction)
                    }
                    .onFailure { e ->
                        flashError(getString(R.string.msg_plugin_install_failed, e.message))
                    }
            }
        }
        return true
    }
}
