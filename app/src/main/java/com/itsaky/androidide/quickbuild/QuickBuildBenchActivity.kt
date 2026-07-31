package com.itsaky.androidide.quickbuild

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.runBlocking
import org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * adb-triggerable "open project + start Quick Build", for the ADFA-4128 benchmark harness
 * only. Opens a project the same way [com.itsaky.androidide.activities.MainActivity] does
 * and arms [QuickBuildBenchAutostart] so the editor fires the first Quick Build tap the
 * moment the project initializes - replacing the human's lightning-bolt tap in an
 * unattended edit->hot-reload measurement.
 *
 * Double-gated (experiments AND qbbench flags) and hardened like [QuickBuildJumpActivity]:
 * exported by necessity (the harness is another package), it accepts only an existing
 * directory inside [Environment.PROJECTS_DIR], so a hostile sender can at worst open one of
 * the user's own projects.
 */
class QuickBuildBenchActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		try {
			handleBenchOpen()
		} catch (e: Exception) {
			log.warn("Ignoring unusable quick-build bench intent", e)
		}
		// Theme.NoDisplay requires finishing before resume; all paths land here.
		finish()
	}

	private fun handleBenchOpen() {
		// A cold start straight into this activity may precede FeatureFlags.initialize();
		// the checks are cheap file-exists probes, so blocking briefly is acceptable on a
		// path that only exists for benchmarking.
		runBlocking { FeatureFlags.initialize() }
		if (!FeatureFlags.isExperimentsEnabled || !FeatureFlags.isQuickBuildBenchEnabled) {
			log.warn("Ignoring quick-build bench intent: benchmark flags disabled")
			return
		}

		val path = intent?.getStringExtra(EXTRA_PROJECT_PATH) ?: return
		val project = File(path).canonicalFile
		if (!project.isDirectory || !isInProjectsDir(project)) {
			log.warn("Rejected quick-build bench open of {}", path)
			return
		}

		val mode = intent?.getStringExtra(EXTRA_MODE) ?: QuickBuildBenchAutostart.MODE_QUICK_BUILD
		if (mode != QuickBuildBenchAutostart.MODE_QUICK_BUILD &&
			mode != QuickBuildBenchAutostart.MODE_STANDARD
		) {
			log.warn("Rejected quick-build bench open: unknown mode {}", mode)
			return
		}

		// Idempotent re-trigger: if this exact project is already the open, initialized
		// project, there is no re-initialization to hook - tap Quick Build directly. The
		// harness relies on this to retry a session (e.g. after an install-confirm
		// timeout) without paying a force-stop + full project re-open, and to fire the
		// proxy app build right after a bench standard build (the marginal-cost measurement).
		// A still-armed autostart means the project never finished initializing - in that
		// case fall through to re-arm + re-open instead of tapping an uninitialized project.
		// A standard-mode re-trigger also goes through arm + re-open: the single-top editor
		// receives it in onNewIntent and fires the build on the WARM daemon - this is how
		// the harness measures a post-edit INCREMENTAL standard build (a force-stop would
		// kill the daemon and contaminate the measurement).
		val current =
			runCatching {
				File(ProjectManagerImpl.getInstance().projectDirPath).canonicalFile.path
			}.getOrNull()
		if (current == project.path && QuickBuildBenchAutostart.pendingProjectPath == null) {
			if (mode == QuickBuildBenchAutostart.MODE_QUICK_BUILD) {
				val manager =
					runCatching {
						GlobalContext.get().get<QuickBuildSessionManager>()
					}.getOrNull()
				if (manager != null) {
					log.info("Bench re-trigger for already-open {}", project.path)
					manager.onQuickBuildTapped()
					return
				}
			}
		}

		// Arm the editor's one-shot autostart BEFORE opening, so the tap fires as soon as
		// this project initializes (see ProjectHandlerActivity).
		QuickBuildBenchAutostart.pendingMode = mode
		QuickBuildBenchAutostart.pendingProjectPath = project.path

		ProjectManagerImpl.getInstance().projectPath = project.path
		GeneralPreferences.lastOpenedProject = project.path
		val editor =
			Intent(this, EditorActivityKt::class.java).apply {
				putExtra("PROJECT_PATH", project.path)
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			}
		startActivity(editor)
		log.info("Bench open started for {}", project.path)
	}

	private fun isInProjectsDir(dir: File): Boolean {
		val projectsDir = Environment.PROJECTS_DIR?.canonicalFile ?: return false
		return dir.path.startsWith(projectsDir.path + File.separator)
	}

	companion object {
		const val ACTION_BENCH_OPEN_PROJECT = "com.itsaky.androidide.quickbuild.action.BENCH_OPEN_PROJECT"
		const val EXTRA_PROJECT_PATH = "com.itsaky.androidide.quickbuild.extra.PROJECT_PATH"

		/**
		 * Which build the autostart fires once the project initializes:
		 * [QuickBuildBenchAutostart.MODE_QUICK_BUILD] (default) or
		 * [QuickBuildBenchAutostart.MODE_STANDARD] (standard Run, for the cold
		 * standard-vs-proxy app build comparison). Unknown values reject the intent.
		 */
		const val EXTRA_MODE = "com.itsaky.androidide.quickbuild.extra.MODE"

		private val log = LoggerFactory.getLogger(QuickBuildBenchActivity::class.java)
	}
}
