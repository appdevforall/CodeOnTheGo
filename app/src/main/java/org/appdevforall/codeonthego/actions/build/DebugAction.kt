package org.appdevforall.codeonthego.actions.build

import android.content.Context
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.canShowPairingNotification
import org.appdevforall.codeonthego.actions.handleMissingOverlayPermission
import org.appdevforall.codeonthego.actions.showDebuggerNotReadyMessage
import org.appdevforall.codeonthego.actions.showNotificationPermissionDialog
import org.appdevforall.codeonthego.actions.showPairingDialog
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lsp.api.ILanguageServerRegistry
import org.appdevforall.codeonthego.lsp.java.JavaLanguageServer
import org.appdevforall.codeonthego.lsp.java.debug.JdwpOptions
import org.appdevforall.codeonthego.projects.IProjectManager
import org.appdevforall.codeonthego.projects.isPluginProject
import com.itsaky.androidide.resources.R
import org.appdevforall.codeonthego.utils.PermissionsHelper
import org.appdevforall.codeonthego.utils.flashError
import org.appdevforall.codeonthego.utils.isAtLeastR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Run the application to
 *
 * @author Akash Yadav
 */
class DebugAction(
	context: Context,
	override val order: Int,
) : AbstractRunAction(
		context = context,
		labelRes = R.string.action_start_debugger,
		iconRes = R.drawable.ic_db_startdebugger,
	) {
	override val id = ID

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean) = TooltipTag.EDITOR_TOOLBAR_DEBUG

	companion object {
		const val ID = "ide.editor.build.debug"
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (IProjectManager.getInstance().isPluginProject()) {
			visible = false
			return
		}

		val buildIsInProgress = data.getActivity().isBuildInProgress()

		// should be enabled if Shizuku is not running
		// the user should not be required to wait for the build to complete
		// in order to start the ADB pairing process
		@Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions")
		enabled = !(Shizuku.pingBinder()) || (JdwpOptions.JDWP_ENABLED && !buildIsInProgress)
	}

	override suspend fun preExec(data: ActionData): Boolean {
		val activity = data.requireActivity()
		if (!isAtLeastR()) {
			activity.flashError(R.string.err_debugger_requires_a11)
			return false
		}

		val javaLsp =
			ILanguageServerRegistry.default
				.getServer(JavaLanguageServer.SERVER_ID)
		if (javaLsp?.debugAdapter?.isReady != true
		) {
			withContext(Dispatchers.Main.immediate) {
				showDebuggerNotReadyMessage(activity)
			}
			return false
		}

		if (!canShowPairingNotification(activity)) {
			withContext(Dispatchers.Main.immediate) {
				showNotificationPermissionDialog(activity, onError = {
					log.error("Failed to open notification settings", it)
				})
			}
			return false
		}

		val overlayState =
			withContext(Dispatchers.Main.immediate) {
				PermissionsHelper.getOverlayPermissionState(activity)
			}

		if (overlayState != PermissionsHelper.OverlayPermissionState.GRANTED) {
			handleMissingOverlayPermission(activity, overlayState, onError = {
				log.error("Failed to launch overlay settings", it)
			})
			return false
		}

		if (!Shizuku.pingBinder()) {
			log.error("Shizuku service is not running")
			withContext(Dispatchers.Main.immediate) {
				showPairingDialog(activity, log = log, onError = {
					log.error("Failed to open developer options", it)
				})
			}
			return false
		}

		return Shizuku.pingBinder()
	}
}
