package com.itsaky.androidide.actions.filetree

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag

class HelpAction(context: Context, override val order: Int) :
    BaseFileTreeAction(
        context = context,
        labelRes = R.string.help,
        iconRes = R.drawable.ic_action_help
    ) {
    override val id: String = "ide.editor.fileTree.help"
    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String {
        return if (isReadOnlyContext) {
            TooltipTag.PROJECT_FOLDER_HELP
        } else {
            TooltipTag.PROJECT_FILE_HELP
        }
    }

    override suspend fun execAction(data: ActionData) {
        val context = data.requireContext()

        TooltipManager.showTooltip(
            context = context,
            anchorView = data.requireTreeNode().viewHolder.view,
            category = TooltipCategory.CATEGORY_IDE,
            tag = TooltipTag.PROJECT_FILE_HELP,
        )
    }

}