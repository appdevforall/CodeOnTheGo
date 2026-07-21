package com.itsaky.androidide.actions.build

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.actions.getContext
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.resolveAttr
import org.appdevforall.cotg.quickbuild.domain.QuickBuildTone
import org.appdevforall.cotg.quickbuild.domain.toTone
import org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory

/**
 * The lightning-bolt Quick Build action (ADFA-4128, plan 2.6). First tap starts the
 * session (setup build + test-app install); every later tap forces a quick build of
 * whatever is pending (redeploy when nothing changed). All lifecycle logic lives in
 * [QuickBuildSessionManager]; this action is a dumb trigger on purpose.
 *
 * The icon shape AND color both track the session's derived [QuickBuildTone] (plan A2) -
 * status is never carried by color alone, so it reads the same for a colorblind user.
 * Long-press opens a split-button dropdown (Quick Build / Standard Run / Restart session /
 * Help); that's wired in `EditorHandlerActivity.prepareOptionsMenu`, not here, since only
 * that call site owns the toolbar's long-press behavior.
 *
 * Registered only when experiments are enabled (see EditorActivityActions), so no
 * runtime gate is needed here. Works from API 28: resource reloads below API 30 take
 * the degraded addAssetPath shim (ResourceSwapStrategy in :quickbuild-runtime).
 */
class QuickBuildAction(
	context: Context,
	override val order: Int,
) : EditorActivityAction() {
	override val id: String = ID

	init {
		label = context.getString(R.string.quick_build_action_label)
		icon = ContextCompat.getDrawable(context, R.drawable.ic_quick_build)
	}

	override suspend fun execAction(data: ActionData): Any {
		val sessionManager = currentSessionManager() ?: return false
		// Best-effort, like the Run path's build metrics: analytics must never block
		// or fail the build action (REVIEW.md section 11).
		runCatching { GlobalContext.get().get<IAnalyticsManager>().trackFeatureUsed(FEATURE_NAME) }
			.onFailure { log.warn("Quick Build analytics unavailable", it) }
		// Same-app-id entry gate (Path B, contract section 3): a tap with the mode
		// toggle on but no confirmed episode (first session after enabling, or
		// re-entry after a Standard Run restore) must pass the clobber warning before
		// anything builds or installs. The activity owns the dialog; the tap proceeds
		// only on accept. Hop to the UI thread - actions run on a default dispatcher.
		val activity = data.getActivity()
		if (activity != null) {
			activity.runOnUiThread {
				activity.ensureSameAppIdEntryConfirmed { sessionManager.onQuickBuildTapped() }
			}
			return true
		}
		sessionManager.onQuickBuildTapped()
		return true
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)
		val context = data.getContext() ?: return
		icon = ContextCompat.getDrawable(context, iconResFor(currentTone()))
	}

	override fun createColorFilter(data: ActionData): ColorFilter? {
		val context = data.getContext() ?: return super.createColorFilter(data)
		return PorterDuffColorFilter(
			context.resolveAttr(colorAttrFor(currentTone())),
			PorterDuff.Mode.SRC_ATOP,
		)
	}

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.EDITOR_TOOLBAR_QUICK_BUILD

	private fun currentSessionManager(): QuickBuildSessionManager? =
		runCatching { GlobalContext.get().get<QuickBuildSessionManager>() }
			.onFailure { log.error("Quick Build session manager unavailable", it) }
			.getOrNull()

	private fun currentTone(): QuickBuildTone = currentSessionManager()?.status?.value?.toTone() ?: QuickBuildTone.READY

	companion object {
		private val log = LoggerFactory.getLogger(QuickBuildAction::class.java)

		const val ID = "ide.editor.build.quickBuild"

		/** Low-cardinality feature name for [IAnalyticsManager.trackFeatureUsed]. */
		const val FEATURE_NAME = "quick_build"

		@DrawableRes
		fun iconResFor(tone: QuickBuildTone): Int =
			when (tone) {
				QuickBuildTone.READY -> R.drawable.ic_quick_build
				QuickBuildTone.BUILDING -> R.drawable.ic_quick_build_outline
				QuickBuildTone.ATTENTION -> R.drawable.ic_quick_build_alert
			}

		@AttrRes
		fun colorAttrFor(tone: QuickBuildTone): Int =
			when (tone) {
				// Neutral, matching the framework default (ActionItem.createColorFilter) -
				// the outline shape alone signals "in progress" for this tone.
				QuickBuildTone.READY -> R.attr.colorSuccess
				QuickBuildTone.BUILDING -> R.attr.colorOnSurface
				QuickBuildTone.ATTENTION -> R.attr.colorError
			}
	}
}
