package com.itsaky.androidide.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.graphics.Insets
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.R
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityPluginManagerBinding
import com.itsaky.androidide.ui.compose.ManagerScreen
import com.itsaky.androidide.ui.compose.theme.ManagerTheme
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.viewmodels.PluginManagerViewModel
import com.itsaky.androidide.viewmodels.TemplateManagerViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class PluginManagerActivity : EdgeToEdgeIDEActivity() {
	companion object {
		/**
		 * Absolute path of a `.cgp` file forwarded from [ExternalFileInstallActivity] - a plain
		 * path rather than a `content://` Uri, since both activities run in this same process and
		 * already trust filesDir paths, letting the install skip a redundant ContentResolver copy.
		 */
		const val EXTRA_PENDING_INSTALL_FILE_PATH = "pending_install_file_path"
	}

	@Suppress("ktlint:standard:backing-property-naming")
	private var _binding: ActivityPluginManagerBinding? = null
	private val binding: ActivityPluginManagerBinding
		get() = checkNotNull(_binding) { "Activity has been destroyed" }

	private var feedbackButtonManager: FeedbackButtonManager? = null

	private val pluginViewModel: PluginManagerViewModel by viewModel()
	private val templateViewModel: TemplateManagerViewModel by viewModel()

	override fun bindLayout(): View {
		_binding = ActivityPluginManagerBinding.inflate(layoutInflater)
		return binding.root
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		try {
			super.onCreate(savedInstanceState)

			// setContent only registers the composable; its lambda runs later, at first
			// layout, after onCreate has returned - by which point this try/catch can no
			// longer see it. Force the Koin `by viewModel()` delegates to resolve here instead,
			// so a failure (e.g. Environment.TEMPLATES_DIR still null after a partial
			// DeviceProtectedApplicationLoader init) is caught below rather than crashing.
			val resolvedPluginViewModel = pluginViewModel
			val resolvedTemplateViewModel = templateViewModel

			binding.composeView.setContent {
				ManagerTheme {
					ManagerScreen(
						activity = this,
						pluginViewModel = resolvedPluginViewModel,
						templateViewModel = resolvedTemplateViewModel,
					)
				}
			}

			setupFeedbackButton()

			// Safe to emit before the Compose collector attaches: the ViewModel's uiEffect
			// channel is buffered precisely so a decision made synchronously in onCreate()
			// isn't dropped on the floor.
			handlePendingInstallExtra()
		} catch (e: Exception) {
			// Log the error and finish the activity if something goes wrong
			e.printStackTrace()
			flashError(getString(R.string.msg_plugin_manager_init_failed, e.message))
			finish()
		}
	}

	// ForwardToPluginManager's launch Intent carries FLAG_ACTIVITY_CLEAR_TOP/SINGLE_TOP so a
	// forwarded install reuses an already-running instance instead of stacking a duplicate one -
	// which routes the extra through onNewIntent() rather than a fresh onCreate().
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handlePendingInstallExtra()
	}

	/**
	 * Hands a forwarded `.cgp` path to the ViewModel, which gates against re-showing the dialog,
	 * probes the file off the main thread, and emits the same install-confirmation effect the SAF
	 * pick uses - so [ManagerScreen]'s Plugins tab renders one dialog for both entry points.
	 */
	private fun handlePendingInstallExtra() {
		intent.getStringExtra(EXTRA_PENDING_INSTALL_FILE_PATH)?.let { filePath ->
			pluginViewModel.onPendingInstallFile(filePath)
		}
	}

	override fun onResume() {
		super.onResume()
		feedbackButtonManager?.loadFabPosition()
	}

	override fun onDestroy() {
		super.onDestroy()
		_binding = null
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		binding.root.setPaddingRelative(
			insets.left,
			insets.top,
			insets.right,
			insets.bottom,
		)
	}

	private fun setupFeedbackButton() {
		feedbackButtonManager =
			FeedbackButtonManager(
				activity = this,
				feedbackFab = binding.fabFeedback.root,
			)
		feedbackButtonManager?.setupDraggableFab()
	}
}
