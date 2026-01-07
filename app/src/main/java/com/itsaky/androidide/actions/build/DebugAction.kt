package com.itsaky.androidide.actions.build

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.view.setPadding
import com.google.android.material.textview.MaterialTextView
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.PermissionsHelper
import com.itsaky.androidide.utils.appendHtmlWithLinks
import com.itsaky.androidide.utils.appendOrderedList
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.utils.isAtLeastS
import com.itsaky.androidide.utils.isAtLeastT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.adb.AdbPairingService
import org.adfa.constants.CONTENT_KEY
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

		if (!canShowPairingNotification(activity)) {
			withContext(Dispatchers.Main.immediate) {
				showNotificationPermissionDialog(activity)
			}
			return false
		}

		if (!Shizuku.pingBinder()) {
			log.error("Shizuku service is not running")
			withContext(Dispatchers.Main.immediate) {
				showPairingDialog(activity)
			}
			return false
		}

		return Shizuku.pingBinder()
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun showPairingDialog(context: Context): AlertDialog? {
		val launchHelp = { url: String ->
			context.startActivity(Intent(context, HelpActivity::class.java).apply {
				putExtra(CONTENT_KEY, url)
			})
		}

		val ssb = SpannableStringBuilder()
		ssb.appendHtmlWithLinks(
			context.getString(R.string.debugger_setup_description_header),
			launchHelp
		)

		ssb.append(System.lineSeparator())
		ssb.append(System.lineSeparator())

		ssb.appendOrderedList(*context.resources.getStringArray(R.array.debugger_setup_pairing_steps))
		ssb.append(System.lineSeparator())

		ssb.appendHtmlWithLinks(
			context.getString(R.string.debugger_setup_description_footer),
			launchHelp
		)

		val text = MaterialTextView(context)
		text.setPadding(context.resources.getDimensionPixelSize(R.dimen.content_padding_double))
		text.movementMethod = LinkMovementMethod.getInstance()
		text.highlightColor = Color.TRANSPARENT
		text.text = ssb
		text.setLineSpacing(text.lineSpacingExtra, 1.1f)

		return DialogUtils.newMaterialDialogBuilder(context)
			.setTitle(R.string.debugger_setup_title)
			.setView(text)
			.setPositiveButton(R.string.adb_pairing_action_start) { dialog, _ ->
				dialog.dismiss()

				val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
				intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
				intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
				try {
					if (startPairingService(context)) {
						context.startActivity(intent)
					}
				} catch (e: ActivityNotFoundException) {
					log.error("Failed to open developer options", e)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun startPairingService(context: Context): Boolean {
		val intent = AdbPairingService.startIntent(context)
		return try {
			startForegroundService(context, intent)
			true
		} catch (e: Throwable) {
			log.error("Failed to start pairing service", e)

			if (isAtLeastS() && e is ForegroundServiceStartNotAllowedException) {
				val mode =
					context
						.getSystemService(AppOpsManager::class.java)
						.noteOpNoThrow(
							"android:start_foreground",
							android.os.Process.myUid(),
							context.packageName,
							null,
							null,
						)
				if (mode == AppOpsManager.MODE_ERRORED) {
					flashError(context.getString(R.string.err_foreground_service_denial))
				}

				context.startService(intent)
				true
			} else false
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun canShowPairingNotification(context: Context): Boolean {
		val nm = context.getSystemService(NotificationManager::class.java)
		val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
		return nm.areNotificationsEnabled() &&
				(channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
	}

	private fun showNotificationPermissionDialog(context: Context): AlertDialog? =
		DialogUtils.newMaterialDialogBuilder(context)
			.setTitle(R.string.adb_pairing_action_enable_notifications)
			.setMessage(
				context.getString(
					R.string.adb_pairing_tutorial_content_notification,
					context.getString(R.string.notification_channel_adb_pairing)
				)
			)
			.setPositiveButton(R.string.title_grant) { dialog, _ ->
				val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
				intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
				try {
					context.startActivity(intent)
				} catch (e: ActivityNotFoundException) {
					log.error("Failed to open notification settings", e)
				}

				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel) { dialog, _ ->
				dialog.dismiss()
			}
			.show()
}
