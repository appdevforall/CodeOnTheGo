package com.itsaky.androidide.actions.filetree

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.UrlManager

class HelpAction(context: Context, override val order: Int) :
    BaseFileTreeAction(
        context = context,
        labelRes = R.string.help,
        iconRes = R.drawable.ic_action_help
    ) {
    override val id: String = "ide.editor.fileTree.help"
    override fun retrieveTooltipTag(isSecondaryVersion: Boolean): String {
        return if (isSecondaryVersion) {
            TooltipTag.PROJECT_FOLDER_HELP
        } else {
            TooltipTag.PROJECT_FILE_HELP
        }
    }

    override suspend fun execAction(data: ActionData) {
        val context = data.requireContext()
        UrlManager.openUrl(
            url = HELP_URL,
            context = context
        )
    }

    companion object {
        private const val HELP_URL = "https://code-on-the-go.com/help"
    }
}