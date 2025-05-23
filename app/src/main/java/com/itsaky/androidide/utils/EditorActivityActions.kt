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
package com.itsaky.androidide.utils

import android.content.Context
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_FILE_TABS
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_FILE_TREE
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_TOOLBAR
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.build.ProjectSyncAction
import com.itsaky.androidide.actions.build.QuickRunWithCancellationAction
import com.itsaky.androidide.actions.build.RunTasksAction
import com.itsaky.androidide.actions.editor.CopyAction
import com.itsaky.androidide.actions.editor.CutAction
import com.itsaky.androidide.actions.editor.ExpandSelectionAction
import com.itsaky.androidide.actions.editor.LongSelectAction
import com.itsaky.androidide.actions.editor.PasteAction
import com.itsaky.androidide.actions.editor.SelectAllAction
import com.itsaky.androidide.actions.etc.DisconnectLogSendersAction
import com.itsaky.androidide.actions.etc.FindActionMenu
import com.itsaky.androidide.actions.etc.LaunchAppAction
import com.itsaky.androidide.actions.etc.PreviewLayoutAction
import com.itsaky.androidide.actions.etc.ReloadColorSchemesAction
import com.itsaky.androidide.actions.file.CloseAllFilesAction
import com.itsaky.androidide.actions.file.CloseFileAction
import com.itsaky.androidide.actions.file.CloseOtherFilesAction
import com.itsaky.androidide.actions.file.FormatCodeAction
import com.itsaky.androidide.actions.file.SaveFileAction
import com.itsaky.androidide.actions.file.ShowTooltipAction
import com.itsaky.androidide.actions.filetree.CopyPathAction
import com.itsaky.androidide.actions.filetree.DeleteAction
import com.itsaky.androidide.actions.filetree.HelpAction
import com.itsaky.androidide.actions.filetree.NewFileAction
import com.itsaky.androidide.actions.filetree.NewFolderAction
import com.itsaky.androidide.actions.filetree.OpenWithAction
import com.itsaky.androidide.actions.filetree.RenameAction
import com.itsaky.androidide.actions.github.GitHubCommitAction
import com.itsaky.androidide.actions.github.GitHubFetchAction
import com.itsaky.androidide.actions.github.GitHubPullAction
import com.itsaky.androidide.actions.github.GitHubPushAction
import com.itsaky.androidide.actions.text.RedoAction
import com.itsaky.androidide.actions.text.UndoAction

/**
 * Takes care of registering actions to the actions registry for the editor activity.
 *
 * @author Akash Yadav
 */
class EditorActivityActions {

  companion object {

    private const val ORDER_COPY_PATH = 100
    private const val ORDER_DELETE = 200
    private const val ORDER_NEW_FILE = 300
    private const val ORDER_NEW_FOLDER = 400
    private const val ORDER_OPEN_WITH = 500
    private const val ORDER_RENAME = 600
    private const val ORDER_HELP = 1000

    @JvmStatic
    fun register(context: Context) {
      clear()
      val registry = ActionsRegistry.getInstance()
      var order = 0

      // Toolbar actions
      registry.registerAction(UndoAction(context, order++))
      registry.registerAction(RedoAction(context, order++))
      registry.registerAction(QuickRunWithCancellationAction(context, order++))
      registry.registerAction(RunTasksAction(context, order++))
      registry.registerAction(SaveFileAction(context, order++))
      registry.registerAction(PreviewLayoutAction(context, order++))
      registry.registerAction(FindActionMenu(context, order++))
      registry.registerAction(ProjectSyncAction(context, order++))
      registry.registerAction(ReloadColorSchemesAction(context, order++))
      registry.registerAction(DisconnectLogSendersAction(context, order++))
      registry.registerAction(LaunchAppAction(context, order++))
      registry.registerAction(GitHubCommitAction(context, order++))
      registry.registerAction(GitHubPushAction(context, order++))
      registry.registerAction(GitHubFetchAction(context, order++))
      registry.registerAction(GitHubPullAction(context, order++))

      // editor text actions
      registry.registerAction(ExpandSelectionAction(context, order++))
      registry.registerAction(SelectAllAction(context, order++))
      registry.registerAction(LongSelectAction(context, order++))
      registry.registerAction(CutAction(context, order++))
      registry.registerAction(CopyAction(context, order++))
      registry.registerAction(PasteAction(context, order++))
      registry.registerAction(FormatCodeAction(context, order++))
      registry.registerAction(ShowTooltipAction(context,order++))

      // file tab actions
      registry.registerAction(CloseFileAction(context, order++))
      registry.registerAction(CloseOtherFilesAction(context, order++))
      registry.registerAction(CloseAllFilesAction(context, order++))

      // file tree actions
      registry.registerAction(CopyPathAction(context, ORDER_COPY_PATH))
      registry.registerAction(DeleteAction(context, ORDER_DELETE))
      registry.registerAction(NewFileAction(context, ORDER_NEW_FILE))
      registry.registerAction(NewFolderAction(context, ORDER_NEW_FOLDER))
      registry.registerAction(OpenWithAction(context, ORDER_OPEN_WITH))
      registry.registerAction(RenameAction(context, ORDER_RENAME))
      registry.registerAction(HelpAction(context, ORDER_HELP))
    }

    @JvmStatic
    fun clear() {
      // EDITOR_TEXT_ACTIONS should not be cleared as the language servers register actions there as
      // well
      val locations = arrayOf(EDITOR_TOOLBAR, EDITOR_FILE_TABS, EDITOR_FILE_TREE)
      val registry = ActionsRegistry.getInstance()
      locations.forEach(registry::clearActions)
    }
  }
}
