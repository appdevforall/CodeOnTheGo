

package com.itsaky.androidide.activities

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
		} catch (e: Exception) {
			// Log the error and finish the activity if something goes wrong
			e.printStackTrace()
			flashError(getString(R.string.msg_plugin_manager_init_failed, e.message))
			finish()
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
