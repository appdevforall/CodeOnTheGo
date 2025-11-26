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
import com.itsaky.androidide.actions.ActionItem
import android.util.Log
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_FILE_TABS
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_FILE_TREE
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_TOOLBAR
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.build.DebugAction
import com.itsaky.androidide.actions.build.ProjectSyncAction
import com.itsaky.androidide.actions.build.QuickRunAction
import com.itsaky.androidide.actions.build.RunTasksAction
import com.itsaky.androidide.actions.editor.CopyAction
import com.itsaky.androidide.actions.editor.CutAction
import com.itsaky.androidide.actions.editor.ExpandSelectionAction
import com.itsaky.androidide.actions.editor.LongSelectAction
import com.itsaky.androidide.actions.editor.PasteAction
import com.itsaky.androidide.actions.editor.SelectAllAction
import com.itsaky.androidide.actions.etc.DisconnectLogSendersAction
import com.itsaky.androidide.actions.etc.FindAction
import com.itsaky.androidide.actions.etc.FindInFileAction
import com.itsaky.androidide.actions.etc.FindInProjectAction
import com.itsaky.androidide.actions.etc.LaunchAppAction
import com.itsaky.androidide.actions.etc.PreviewLayoutAction
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
import com.itsaky.androidide.actions.text.RedoAction
import com.itsaky.androidide.actions.text.UndoAction
import com.itsaky.androidide.actions.PluginActionItem
import com.itsaky.androidide.actions.etc.GenerateXMLAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.adapters.EditorBottomSheetTabAdapter
import com.itsaky.androidide.plugins.manager.core.PluginManager

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
            registry.registerAction(QuickRunAction(context, order++))
            registry.registerAction(ProjectSyncAction(context, order++))
            registry.registerAction(DebugAction(context, order++))
            registry.registerAction(RunTasksAction(context, order++))
            registry.registerAction(UndoAction(context, order++))
            registry.registerAction(RedoAction(context, order++))
            registry.registerAction(SaveFileAction(context, order++))
            registry.registerAction(PreviewLayoutAction(context, order++))
            registry.registerAction(FindAction(context, order++))
            registry.registerAction(FindInFileAction(context, order++))
            registry.registerAction(FindInProjectAction(context, order++))
            registry.registerAction(LaunchAppAction(context, order++))
            registry.registerAction(DisconnectLogSendersAction(context, order++))
            registry.registerAction(action= GenerateXMLAction(context, order=order++))

            // Plugin contributions
            order = registerPluginActions(context, registry, order)

            // editor text actions
            registry.registerAction(ExpandSelectionAction(context, order++))
            registry.registerAction(SelectAllAction(context, order++))
            registry.registerAction(LongSelectAction(context, order++))
            registry.registerAction(CutAction(context, order++))
            registry.registerAction(CopyAction(context, order++))
            registry.registerAction(PasteAction(context, order++))
            registry.registerAction(FormatCodeAction(context, order++))
            registry.registerAction(ShowTooltipAction(context, order++))

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

        @JvmStatic
        fun clearActions() {
            // Clear actions but preserve build actions to prevent cancellation during onPause
            val locations = arrayOf(
                EDITOR_TOOLBAR,
                EDITOR_FILE_TABS,
                EDITOR_FILE_TREE,
                ActionItem.Location.EDITOR_FIND_ACTION_MENU
            )
            val registry = ActionsRegistry.getInstance()
            locations.forEach(registry::clearActions)

            // Clear toolbar actions except build actions
            registry.clearActionsExceptWhere(EDITOR_TOOLBAR) { action ->
                action.id == QuickRunAction.ID ||
                        action.id == RunTasksAction.ID ||
                        action.id == ProjectSyncAction.ID
      }
    }

    /**
     * Register plugin UI contributions to the actions registry.
     *
     * @param context The application context
     * @param registry The actions registry
     * @param startOrder The starting order for plugin actions
     * @return The next available order number
     */
    @JvmStatic
    private fun registerPluginActions(context: Context, registry: ActionsRegistry, startOrder: Int): Int {
        var order = startOrder

        val pluginManager = PluginManager.getInstance() ?: return order

        pluginManager.getAllPluginInstances()
            .filterIsInstance<UIExtension>()
            .forEach { plugin ->
                try {
                    Log.d("plugin_debug", "Registering menu items for plugin: ${plugin.javaClass.simpleName}")
                    plugin.getMainMenuItems().forEach { menuItem ->
                        val action = PluginActionItem(context, menuItem, order++)
                        registry.registerAction(action)
                    }
                } catch (e: Exception) {
                    // Continue with other plugins if one fails
                    System.err.println("")
                    Log.d("plugin_debug", "Failed to register menu items for plugin: ${plugin.javaClass.simpleName} - ${e.message}")
                }
            }

        return order
    }
  }
}
