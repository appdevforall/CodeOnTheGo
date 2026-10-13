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
package org.appdevforall.codeonthego.utils

import android.content.Context
import android.util.Log
import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.actions.ActionItem.Location.EDITOR_FILE_TABS
import org.appdevforall.codeonthego.actions.ActionItem.Location.EDITOR_FILE_TREE
import org.appdevforall.codeonthego.actions.ActionItem.Location.EDITOR_TOOLBAR
import org.appdevforall.codeonthego.actions.ActionsRegistry
import org.appdevforall.codeonthego.actions.PluginActionItem
import org.appdevforall.codeonthego.actions.PluginToolbarActionItem
import org.appdevforall.codeonthego.actions.build.DebugAction
import org.appdevforall.codeonthego.actions.build.PluginBuildActionItem
import org.appdevforall.codeonthego.actions.build.ProjectSyncAction
import org.appdevforall.codeonthego.actions.build.QuickBuildAction
import org.appdevforall.codeonthego.actions.build.QuickRunAction
import org.appdevforall.codeonthego.actions.build.RunTasksAction
import org.appdevforall.codeonthego.actions.editor.CopyAction
import org.appdevforall.codeonthego.actions.editor.CutAction
import org.appdevforall.codeonthego.actions.editor.ExpandSelectionAction
import org.appdevforall.codeonthego.actions.editor.LongSelectAction
import org.appdevforall.codeonthego.actions.editor.PasteAction
import org.appdevforall.codeonthego.actions.editor.SelectAllAction
import org.appdevforall.codeonthego.actions.etc.DisconnectLogSendersAction
import org.appdevforall.codeonthego.actions.etc.FindAction
import org.appdevforall.codeonthego.actions.etc.FindInFileAction
import org.appdevforall.codeonthego.actions.etc.FindInProjectAction
import org.appdevforall.codeonthego.actions.etc.LaunchAppAction
import org.appdevforall.codeonthego.actions.etc.ReplaceInProjectAction
import org.appdevforall.codeonthego.actions.file.CloseAllFilesAction
import org.appdevforall.codeonthego.actions.file.CloseFileAction
import org.appdevforall.codeonthego.actions.file.CloseOtherFilesAction
import org.appdevforall.codeonthego.actions.file.CreateLinkAction
import org.appdevforall.codeonthego.actions.file.FormatCodeAction
import org.appdevforall.codeonthego.actions.file.InstallFileAction
import org.appdevforall.codeonthego.actions.file.SaveFileAction
import org.appdevforall.codeonthego.actions.file.ShowTooltipAction
import org.appdevforall.codeonthego.actions.filetree.CopyPathAction
import org.appdevforall.codeonthego.actions.filetree.DeleteAction
import org.appdevforall.codeonthego.actions.filetree.HelpAction
import org.appdevforall.codeonthego.actions.filetree.NewFileAction
import org.appdevforall.codeonthego.actions.filetree.NewFolderAction
import org.appdevforall.codeonthego.actions.filetree.OpenWithAction
import org.appdevforall.codeonthego.actions.filetree.RenameAction
import org.appdevforall.codeonthego.actions.profiler.ProfilerAction
import org.appdevforall.codeonthego.actions.text.RedoAction
import org.appdevforall.codeonthego.actions.text.UndoAction
import org.appdevforall.codeonthego.plugins.extensions.UIExtension
import org.appdevforall.codeonthego.plugins.manager.build.PluginBuildActionManager
import org.appdevforall.codeonthego.plugins.manager.core.PluginManager

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
		private const val ORDER_PROFILER = 1100

		@JvmStatic
		fun register(context: Context) {
			clear()
			val registry = ActionsRegistry.getInstance()
			var order = 0

			// Toolbar actions
			registry.registerAction(QuickRunAction(context, order++))
			// Quick Build (ADFA-4128): next to the Run button; experimental. Available
			// from API 28 - on 28/29 resource reloads take the degraded addAssetPath
			// shim (ResourceSwapStrategy in :quickbuild:runtime); 30+ uses ResourcesLoader.
			if (FeatureFlags.isExperimentsEnabled) {
				registry.registerAction(QuickBuildAction(context, order++))
			}
			registry.registerAction(ProjectSyncAction(context, order++))
			registry.registerAction(DebugAction(context, order++))
			registry.registerAction(RunTasksAction(context, order++))
			registry.registerAction(UndoAction(context, order++))
			registry.registerAction(RedoAction(context, order++))
			registry.registerAction(SaveFileAction(context, order++))
			registry.registerAction(FindAction(context, order++))
			registry.registerAction(FindInFileAction(context, order++))
			registry.registerAction(FindInProjectAction(context, order++))
			registry.registerAction(ReplaceInProjectAction(context, order++))
			registry.registerAction(LaunchAppAction(context, order++))
			registry.registerAction(DisconnectLogSendersAction(context, order++))

			// Plugin contributions
			order = registerPluginActions(context, registry, order)
			order = registerPluginBuildActions(context, registry, order)

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
			registry.registerAction(InstallFileAction(context, order++))
			registry.registerAction(CreateLinkAction(context, order++))

			// file tree actions
			registry.registerAction(CopyPathAction(context, ORDER_COPY_PATH))
			registry.registerAction(DeleteAction(context, ORDER_DELETE))
			registry.registerAction(NewFileAction(context, ORDER_NEW_FILE))
			registry.registerAction(NewFolderAction(context, ORDER_NEW_FOLDER))
			registry.registerAction(OpenWithAction(context, ORDER_OPEN_WITH))
			registry.registerAction(RenameAction(context, ORDER_RENAME))
			registry.registerAction(HelpAction(context, ORDER_HELP))

			// Profiler actions
			if (FeatureFlags.isExperimentsEnabled) {
				registry.registerAction(ProfilerAction(context, ORDER_PROFILER))
			}
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
			val locations =
				arrayOf(
					EDITOR_TOOLBAR,
					EDITOR_FILE_TABS,
					EDITOR_FILE_TREE,
					ActionItem.Location.EDITOR_FIND_ACTION_MENU,
				)
			val registry = ActionsRegistry.getInstance()
			locations.forEach(registry::clearActions)

			// Clear toolbar actions except build actions
			registry.clearActionsExceptWhere(EDITOR_TOOLBAR) { action ->
				action.id == QuickRunAction.ID ||
					action.id == QuickBuildAction.ID ||
					action.id == RunTasksAction.ID ||
					action.id == ProjectSyncAction.ID ||
					action.id.startsWith("plugin.build.")
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
		private fun registerPluginActions(
			context: Context,
			registry: ActionsRegistry,
			startOrder: Int,
		): Int {
			var order = startOrder

			val pluginManager = PluginManager.getInstance() ?: return order

			pluginManager
				.getAllPluginInstances()
				.filterIsInstance<UIExtension>()
				.forEach { plugin ->
					try {
						Log.d("plugin_debug", "Registering menu items for plugin: ${plugin.javaClass.simpleName}")
						val pluginId = pluginManager.getPluginIdForInstance(plugin as org.appdevforall.codeonthego.plugins.IPlugin) ?: ""
						plugin.getMainMenuItems().forEach { menuItem ->
							val action = PluginActionItem(context, menuItem, order++, pluginId)
							registry.registerAction(action)
						}
						// Toolbar actions carry their own order so a plugin can position its icon
						// among the built-in toolbar actions; do not consume the sequential counter.
						plugin.getToolbarActions().forEach { toolbarAction ->
							registry.registerAction(PluginToolbarActionItem(context, toolbarAction, pluginId))
						}
					} catch (e: Exception) {
						Log.w("plugin_debug", "Failed to register menu items for plugin: ${plugin.javaClass.simpleName}", e)
					}
				}

			return order
		}

		@JvmStatic
		private fun registerPluginBuildActions(
			context: Context,
			registry: ActionsRegistry,
			startOrder: Int,
		): Int {
			var order = startOrder

			PluginBuildActionManager.getInstance().getAllBuildActions().forEach { registered ->
				runCatching {
					registry.registerAction(PluginBuildActionItem(context, registered, order++))
					Log.d("plugin_debug", "Registered build action: ${registered.action.id} from plugin: ${registered.pluginId}")
				}.onFailure { e ->
					Log.w("plugin_debug", "Failed to register build action: ${registered.action.id}", e)
				}
			}

			return order
		}
	}
}
