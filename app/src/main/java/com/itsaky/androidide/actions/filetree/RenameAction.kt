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

package com.itsaky.androidide.actions.filetree

import android.content.Context
import android.view.LayoutInflater
import androidx.activity.viewModels
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.showWithLongPressTooltip
import com.itsaky.androidide.viewmodel.FileManagerViewModel
import org.greenrobot.eventbus.EventBus
import java.io.File

/**
 * Action to rename the selected file.
 *
 * @author Akash Yadav
 */
class RenameAction(
	context: Context,
	override val order: Int,
) : BaseFileTreeAction(
		context,
		labelRes = R.string.rename_file,
		iconRes = R.drawable.ic_file_rename,
	) {
	override val id: String = "ide.editor.fileTree.rename"

	override suspend fun execAction(data: ActionData) {
		val context = data.requireActivity()
		val file = data.requireFile()
		val lastHeld = data.getTreeNode()
		val binding = LayoutDialogTextInputBinding.inflate(LayoutInflater.from(context))
		val builder = DialogUtils.newMaterialDialogBuilder(context)
		binding.name.editText!!.hint =
			context.getString(com.itsaky.androidide.resources.R.string.new_name)
		binding.name.editText!!.setText(file.name)
		builder.setTitle(com.itsaky.androidide.resources.R.string.rename_file)
		builder.setMessage(com.itsaky.androidide.resources.R.string.msg_rename_file)
		builder.setView(binding.root)
		builder.setNegativeButton(android.R.string.cancel, null)
		builder.setPositiveButton(com.itsaky.androidide.resources.R.string.rename_file) {
			dialogInterface,
			_ ->
			dialogInterface.dismiss()
            val fileManagerViewModel: FileManagerViewModel by context.viewModels()
            val name: String = binding.name.editText?.text.toString().trim()
            fileManagerViewModel.renameFile(file, name)
		}

        builder.showWithLongPressTooltip(
            context = context,
            tooltipTag = TooltipTag.PROJECT_RENAME_DIALOG
        )
	}

    private fun notifyFileRenamed(
        file: File,
        name: String,
        context: Context,
    ) {
        val renameEvent = FileRenameEvent(file, File(file.parent, name))

        // Notify FileManager first
        FileManager.onFileRenamed(renameEvent)

        EventBus.getDefault().post(renameEvent.apply { putData(context) })
    }
}
