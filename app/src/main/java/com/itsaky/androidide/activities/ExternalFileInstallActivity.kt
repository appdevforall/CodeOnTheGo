package com.itsaky.androidide.activities

import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import com.itsaky.androidide.app.IDEActivity
import com.itsaky.androidide.viewmodels.ExternalFileInstallViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Trampoline activity that receives a `.cgp`/`.cgt` file opened from outside the app (e.g. an
 * email attachment), prompts to install it, and finishes - it has no content of its own beyond
 * the dialogs [ExternalFileInstallScreen] shows.
 */
class ExternalFileInstallActivity : IDEActivity() {
	private val viewModel: ExternalFileInstallViewModel by viewModel()

	override fun bindLayout(): View =
		ComposeView(this).apply {
			setContent { ExternalFileInstallScreen(viewModel) }
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val uri = intent?.data
		if (uri == null) {
			finish()
			return
		}

		// No savedInstanceState guard here: onReceived() is idempotent per ViewModel instance
		// (a rotation keeps the same instance, so this is a no-op there), and calling it
		// unconditionally means a process-death-recreated instance - which starts fresh and
		// would otherwise never see the restored intent's data - still gets processed.
		viewModel.onReceived(uri)
	}
}
