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
import com.itsaky.androidide.actions.PluginActionItem
import com.itsaky.androidide.plugins.manager.PluginManager
import com.itsaky.androidide.plugins.extensions.UIExtension

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
      registry.registerAction(DisconnectLogSendersAction(context, order++))
      registry.registerAction(LaunchAppAction(context, order++))
      registry.registerAction(GitHubCommitAction(context, order++))
      registry.registerAction(GitHubPushAction(context, order++))
      registry.registerAction(GitHubFetchAction(context, order++))
      registry.registerAction(GitHubPullAction(context, order++))

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

    @JvmStatic
    fun clearActions() {
      // Clear actions but preserve build actions to prevent cancellation during onPause
      val locations = arrayOf(EDITOR_FILE_TABS, EDITOR_FILE_TREE)
      val registry = ActionsRegistry.getInstance()
      locations.forEach(registry::clearActions)
      
      // Clear toolbar actions except build actions
      registry.clearActionsExceptWhere(EDITOR_TOOLBAR) { action ->
        action.id == "ide.editor.build.quickRun" || 
        action.id == "ide.editor.build.runTasks" || 
        action.id == "ide.editor.build.sync"
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
      try {
        val pluginManager = PluginManager.getInstance()
        if (pluginManager == null) {
          android.util.Log.d("EditorActivityActions", "PluginManager not initialized, skipping plugin UI registration")
          return order
        }

        val loadedPlugins = pluginManager.getAllPluginInstances()
        android.util.Log.d("EditorActivityActions", "Found ${loadedPlugins.size} loaded plugins")
        
        for (plugin in loadedPlugins) {
          try {
            if (plugin is UIExtension) {
              android.util.Log.d("EditorActivityActions", "Processing UIExtension plugin: ${plugin.javaClass.simpleName}")
              
              // Register main menu contributions
              val menuItems = plugin.contributeToMainMenu()
              android.util.Log.d("EditorActivityActions", "Plugin ${plugin.javaClass.simpleName} contributed ${menuItems.size} menu items")
              
              for (menuItem in menuItems) {
                val action = PluginActionItem(context, menuItem, order++)
                registry.registerAction(action)
                android.util.Log.d("EditorActivityActions", "Registered plugin action: ${action.id} - ${action.label}")
              }
            }
          } catch (e: Exception) {
            // Log error but continue with other plugins
            android.util.Log.e("EditorActivityActions", "Error registering plugin UI for ${plugin.javaClass.simpleName}: ${e.message}", e)
          }
        }
      } catch (e: Exception) {
        // Log error but don't break the menu system
        android.util.Log.e("EditorActivityActions", "Error in plugin integration: ${e.message}", e)
      }
      return order
    }
  }
}
