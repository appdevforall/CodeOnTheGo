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

package com.itsaky.androidide.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.analytics.DeepLinkDepth
import com.itsaky.androidide.analytics.DeepLinkMetric
import com.itsaky.androidide.analytics.DeepLinkOutcome
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.analytics.depth
import com.itsaky.androidide.api.ActionContextProvider
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.resources.R.string
import org.koin.android.ext.android.inject

/**
 * The sole `<intent-filter>` holder for `https://appdevforall.org/device/open/project/...` (and the
 * identical `www` subdomain) App Links. Never shows any UI -- it only parses the incoming
 * [android.net.Uri], decides whether a project is already loaded, and hands off to whichever real
 * activity owns that scenario:
 * [MainActivity] if nothing is open yet, or the already-running [EditorActivityKt] (via its
 * `singleTask` `onNewIntent`) if one is.
 *
 * Kept as a plain [Activity] (like [SplashActivity]), not [com.itsaky.androidide.app.BaseIDEActivity],
 * since it never calls `setContentView` and has no theming needs of its own.
 */
class DeepLinkActivity : Activity() {
	private val analyticsManager: IAnalyticsManager by inject()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// A link can arrive before the IDE is usable -- a fresh install, or a Clear Data. Both targets
		// below sit *past* SplashActivity and OnboardingActivity, which are the only things enforcing
		// the terms, the permissions, the JDK and SDK install, the low-storage check and the x86
		// exit, so following the link now would land the user in an editor that cannot build, on a
		// device the app is supposed to refuse to run on at all (ADFA-5067 review).
		//
		// The link is dropped rather than deferred: carrying a request through an onboarding that can
		// take several minutes, and may not finish at all, is a lot of machinery for a rare case. The
		// user is told, and sent to the normal entry point, which decides what they actually need --
		// storage, ABI and onboarding are SplashActivity's to enforce, not this activity's to repeat.
		if (!isIdeSetupComplete()) {
			// Depth is UNKNOWN rather than parsed: the readiness gate deliberately runs before the
			// URI is looked at, and reordering it just to enrich a metric would put parsing ahead of
			// the check that exists to stop this activity acting on anything at all.
			analyticsManager.trackDeepLink(DeepLinkMetric(DeepLinkDepth.UNKNOWN, DeepLinkOutcome.SETUP_INCOMPLETE))
			Toast.makeText(this, getString(string.msg_deeplink_setup_incomplete), Toast.LENGTH_LONG).show()
			// FLAG_ACTIVITY_NEW_TASK, matching the success branch below. A sender that starts this
			// trampoline without it -- an in-app WebView host, another app's explicit intent, `am start`
			// -- puts this activity in the *caller's* task, and an unflagged start here would run the
			// whole terms/permissions/JDK-install onboarding inside that app's back stack, where
			// back-press returns to them rather than exiting CoGo.
			startActivity(
				Intent(this, SplashActivity::class.java).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				},
			)
			finish()
			return
		}

		val request = DeepLinkRequest.parse(intent?.data)
		if (request == null) {
			// Counted, not just shown: this activity is exported, so a rise here is as likely to be
			// another app poking it with an arbitrary Uri as it is a broken published link.
			analyticsManager.trackDeepLink(DeepLinkMetric(DeepLinkDepth.UNKNOWN, DeepLinkOutcome.INVALID_LINK))
			// A Toast, not flashError -- this activity finishes immediately below, tearing down its
			// window before a view-based Flashbar could ever render.
			Toast.makeText(this, getString(string.msg_deeplink_invalid_link), Toast.LENGTH_LONG).show()
			finish()
			return
		}

		// The one place every accepted link passes through, whichever activity ends up handling it.
		// Paired with a terminal outcome logged wherever the request is finally resolved, so a link
		// that is accepted here and then quietly goes nowhere shows up as a gap between the two.
		analyticsManager.trackDeepLink(DeepLinkMetric(request.depth(), DeepLinkOutcome.RECEIVED, request.projectName))

		// ActionContextProvider tracks the live EditorHandlerActivity instance (set in its onCreate
		// and re-asserted in onResume, cleared in onDestroy) -- this reflects "is an editor instance
		// already alive to hand this off to via onNewIntent", unlike IProjectManager's workspace,
		// which stays null for the whole duration of a Gradle sync even while EditorActivityKt is
		// already open.
		val target =
			if (ActionContextProvider.getLiveActivity() != null) {
				EditorActivityKt::class.java
			} else {
				MainActivity::class.java
			}

		startActivity(
			Intent(this, target).apply {
				putExtra(DeepLinkRequest.EXTRA_KEY, request)
				// FLAG_ACTIVITY_CLEAR_TOP deliberately omitted: MainActivity has no special launch
				// mode, so if an existing MainActivity instance sits lower in this task's back stack
				// under a live EditorActivityKt - which ActionContextProvider.getLiveActivity() can miss
				// even when that editor is alive (see its KDoc) - CLEAR_TOP would destroy that editor
				// to clear the path down to MainActivity, discarding unsaved work with no prompt.
				// Without it, this may at worst stack a redundant MainActivity instance, a harmless
				// nuisance; EditorActivityKt is singleTask, so it always reuses its live instance via
				// onNewIntent regardless of these flags.
				addFlags(
					Intent.FLAG_ACTIVITY_NEW_TASK or
						Intent.FLAG_ACTIVITY_SINGLE_TOP,
				)
			},
		)
		finish()
	}
}
