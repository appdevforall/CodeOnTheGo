package org.appdevforall.codeonthego.actions.filetree

import android.content.Context
import com.itsaky.androidide.R
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.requireContext
import org.appdevforall.codeonthego.idetooltips.TooltipManager
import org.appdevforall.codeonthego.idetooltips.TooltipTag

class HelpAction(context: Context, override val order: Int) :
    BaseFileTreeAction(
        context = context,
        labelRes = R.string.help,
        iconRes = R.drawable.ic_action_help_outlined
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

        TooltipManager.showIdeCategoryTooltip(
            context = context,
            anchorView = data.requireTreeNode().viewHolder.view,
            tag = TooltipTag.PROJECT_FILE_HELP,
        )
    }

}