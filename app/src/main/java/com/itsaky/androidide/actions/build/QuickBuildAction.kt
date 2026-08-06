package com.itsaky.androidide.actions.build

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.actions.getContext
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.resolveAttr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.appdevforall.cotg.quickbuild.domain.QuickBuildTone
import org.appdevforall.cotg.quickbuild.domain.toTone
import org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory

/**
 * The Quick Build toolbar action (ADFA-4128, plan 2.6). First tap starts the session (proxy app
 * build + proxy-app install); every later tap forces a quick build of whatever is pending
 * (redeploy when nothing changed). All lifecycle logic lives in [QuickBuildSessionManager];
 * this action is a dumb trigger on purpose.
 *
 * It is TWO buttons in one, because a build the user started is the only thing they can
 * usefully do next: while a quick build runs it becomes the standard build's stop button and
 * a tap cancels (Bryan's behaviours 1 and 5). Icon, label, content description and tap
 * behaviour all derive from the same [QuickBuildTone], so the picture and the action can
 * never disagree.
 *
 * The icon shape AND color both track the tone - status is never carried by color
 * alone, so it reads the same for a colorblind user. Long-press opens a split-button dropdown
 * (Quick Build / Standard Run / Restart session / Help); that's wired in
 * `EditorHandlerActivity.prepareOptionsMenu`, not here, since only that call site owns the
 * toolbar's long-press behavior.
 *
 * Registered only when experiments are enabled (see EditorActivityActions), so no runtime
 * gate is needed here. Works from API 28: resource reloads below API 30 take the degraded
 * addAssetPath shim (ResourceSwapStrategy in :quickbuild:runtime).
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

		// Behaviour 5: while the button shows the stop icon, a tap stops. Keyed off exactly
		// the tone that drew that icon, so the two cannot drift apart.
		if (currentTone() == QuickBuildTone.BUILDING) {
			sessionManager.onCancelRequested()
			return true
		}

		val activity = data.getActivity()
		if (activity == null) {
			sessionManager.onQuickBuildTapped()
			return true
		}

		// The rest of the tap runs on the ACTIVITY's scope, not this action's: execAction
		// runs on the actions registry's process-lifetime dispatcher, so an awaited save that
		// outlived the activity would then post a dialog onto a dead window
		// (WindowManager$BadTokenException) or provision against whatever project opened next.
		activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
			// Flush unsaved editor buffers BEFORE triggering the build. The Quick Build
			// watcher is filesystem-based, so an unflushed buffer means the build silently
			// uses stale on-disk content while the editor shows the user's edit -- the app
			// then disagrees with the screen at the exact moment the user asked to build,
			// until some later save happens to flush it. The Standard Run path saves too
			// (AbstractModuleAssemblerAction), so not saving here was an inconsistency
			// between the two build paths rather than a deliberate choice.
			// Awaited, not fire-and-forget: the tap must build what the user sees.
			try {
				activity.saveAllResult()
			} catch (e: CancellationException) {
				// The activity is going away; the tap goes with it. Rethrown rather than
				// swallowed so the coroutine really unwinds instead of building on.
				throw e
			} catch (e: Throwable) {
				// Do NOT fall through to a build: building stale content is the exact bug
				// saving first exists to prevent. Tell the user why nothing happened - a
				// silent `return false` reads as "the button is broken".
				log.error("Quick Build: could not save open files; not building stale state", e)
				activity.flashError(R.string.save_failed)
				return@launch
			}
			if (activity.isDestroyed || activity.isFinishing) {
				log.info("Quick Build: the activity went away during the save; dropping the tap")
				return@launch
			}
			// Confirm-on-switch gate (ADFA-4128): Quick Build installs the proxy app under the
			// project's real applicationId. If the Standard Run build currently occupies that
			// id, a tap replaces it, so the activity confirms the clobber first and the build
			// proceeds only on accept.
			activity.ensureQuickBuildClobberConfirmed { sessionManager.onQuickBuildTapped() }
		}
		return true
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)
		val context = data.getContext() ?: return
		val tone = currentTone()
		icon = ContextCompat.getDrawable(context, iconResFor(tone))
		// The label moves with the icon: it is what the long-press dropdown and the
		// overflow menu read, so leaving it on "Quick Build" while the icon says stop would
		// offer the user two different actions for one button.
		label = context.getString(labelResFor(tone))
	}

	override fun createColorFilter(data: ActionData): ColorFilter? {
		val context = data.getContext() ?: return super.createColorFilter(data)
		return PorterDuffColorFilter(
			context.resolveAttr(colorAttrFor(currentTone())),
			PorterDuff.Mode.SRC_ATOP,
		)
	}

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.EDITOR_TOOLBAR_QUICK_BUILD

	companion object {
		private val log = LoggerFactory.getLogger("QB-Action")

		const val ID = "ide.editor.build.quickBuild"

		/** Low-cardinality feature name for [IAnalyticsManager.trackFeatureUsed]. */
		const val FEATURE_NAME = "quick_build"

		private fun currentSessionManager(): QuickBuildSessionManager? =
			runCatching { GlobalContext.get().get<QuickBuildSessionManager>() }
				.onFailure { log.error("Quick Build session manager unavailable", it) }
				.getOrNull()

		/**
		 * The one fact this button presents, read pull-style. Public so the toolbar's
		 * content-description lookup can key off the same value the icon does - a stop icon
		 * announced as "Quick Build" is a bug a screen-reader user cannot see around.
		 */
		fun currentTone(): QuickBuildTone = currentSessionManager()?.status?.value?.toTone() ?: QuickBuildTone.READY

		@DrawableRes
		fun iconResFor(tone: QuickBuildTone): Int =
			when (tone) {
				QuickBuildTone.READY -> R.drawable.ic_quick_build

				// Behaviour 1: a running build shows the STANDARD build's stop button, not a
				// variant of the bolt. The bolt did not communicate "a build is running" at
				// all, and the outline variant it used was a distinction only someone who
				// already knew the feature could read.
				QuickBuildTone.BUILDING -> R.drawable.ic_stop_daemons

				QuickBuildTone.ATTENTION -> R.drawable.ic_quick_build_alert
			}

		@StringRes
		fun labelResFor(tone: QuickBuildTone): Int =
			when (tone) {
				// Same wording the standard build's stop affordance uses, so the two buttons
				// do not name the same operation differently.
				QuickBuildTone.BUILDING -> R.string.title_cancel_build

				QuickBuildTone.READY,
				QuickBuildTone.ATTENTION,
				-> R.string.quick_build_action_label
			}

		@AttrRes
		fun colorAttrFor(tone: QuickBuildTone): Int =
			when (tone) {
				QuickBuildTone.READY -> R.attr.colorSuccess

				// Neutral, matching the framework default (ActionItem.createColorFilter) -
				// the stop SHAPE carries "in progress", so this tone must not rely on color.
				QuickBuildTone.BUILDING -> R.attr.colorOnSurface

				QuickBuildTone.ATTENTION -> R.attr.colorError
			}
	}
}
