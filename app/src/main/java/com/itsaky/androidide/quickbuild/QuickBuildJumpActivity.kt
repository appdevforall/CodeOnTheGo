package com.itsaky.androidide.quickbuild

import android.app.Activity
import android.os.Bundle
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.FeatureFlags
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Tap-to-jump trampoline for the Quick Build error overlay (plan A1). The test app
 * starts this activity when the user taps a build-failure banner; because it shares
 * CoGo's task affinity, launching it brings CoGo's existing task to the foreground,
 * and finishing immediately (Theme.NoDisplay) reveals the editor beneath - which
 * receives the posted [QuickBuildErrorJumpEvent] and opens the failing file at the
 * error line.
 *
 * Exported by necessity (the caller is another package). Hardening: the file must
 * exist AND resolve inside the currently open project, so an arbitrary sender can at
 * worst open a file of the user's own project - something the user can do anyway.
 */
class QuickBuildJumpActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		try {
			handleJump()
		} catch (e: Exception) {
			log.warn("Ignoring unusable quick-build jump intent", e)
		}
		// Theme.NoDisplay requires finishing before resume; all paths land here.
		finish()
	}

	private fun handleJump() {
		// Every other Quick Build surface is behind the experiments flag; with the
		// feature off there is no legitimate sender, so drop the intent (review S4/S12).
		if (!FeatureFlags.isExperimentsEnabled) {
			log.warn("Ignoring quick-build jump intent: experiments disabled")
			return
		}
		val path = intent?.getStringExtra(EXTRA_FILE) ?: return
		val file = File(path).canonicalFile
		if (!file.isFile || !isInOpenProject(file)) {
			log.warn("Rejected quick-build jump to {}", path)
			return
		}
		val line = intent.getIntExtra(EXTRA_LINE, -1)
		val column = intent.getIntExtra(EXTRA_COLUMN, -1)
		// No subscriber (editor not up) means the event is dropped - correct: the
		// overlay only exists while a quick-build session, and thus the editor, is live.
		EventBus.getDefault().post(QuickBuildErrorJumpEvent(file, line, column))
	}

	private fun isInOpenProject(file: File): Boolean {
		val projectPath = IProjectManager.getInstance().projectDirPath
		if (projectPath.isEmpty()) {
			return false
		}
		val projectDir = File(projectPath).canonicalFile
		return file.path.startsWith(projectDir.path + File.separator)
	}

	companion object {
		// Contract with the test-app runtime; see JumpToEditor in :quickbuild-runtime.
		const val ACTION_JUMP_TO_ERROR = "com.itsaky.androidide.quickbuild.action.JUMP_TO_ERROR"
		const val EXTRA_FILE = "com.itsaky.androidide.quickbuild.extra.FILE"
		const val EXTRA_LINE = "com.itsaky.androidide.quickbuild.extra.LINE"
		const val EXTRA_COLUMN = "com.itsaky.androidide.quickbuild.extra.COLUMN"

		private val log = LoggerFactory.getLogger(QuickBuildJumpActivity::class.java)
	}
}
