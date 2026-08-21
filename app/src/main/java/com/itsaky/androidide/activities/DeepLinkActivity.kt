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
import com.itsaky.androidide.api.ActionContextProvider
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.resources.R.string

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
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val request = DeepLinkRequest.parse(intent?.data)
		if (request == null) {
			// A Toast, not flashError -- this activity finishes immediately below, tearing down its
			// window before a view-based Flashbar could ever render.
			Toast.makeText(this, getString(string.msg_deeplink_invalid_link), Toast.LENGTH_LONG).show()
			finish()
			return
		}

		// ActionContextProvider tracks the live EditorHandlerActivity instance (set in its onCreate
		// and re-asserted in onResume, cleared in onDestroy) -- this reflects "is an editor instance
		// already alive to hand this off to via onNewIntent", unlike IProjectManager's workspace,
		// which stays null for the whole duration of a Gradle sync even while EditorActivityKt is
		// already open.
		val target =
			if (ActionContextProvider.getActivity() != null) {
				EditorActivityKt::class.java
			} else {
				MainActivity::class.java
			}

		startActivity(
			Intent(this, target).apply {
				putExtra(DeepLinkRequest.EXTRA_KEY, request)
				// FLAG_ACTIVITY_CLEAR_TOP deliberately omitted: MainActivity has no special launch
				// mode, so if an existing MainActivity instance sits lower in this task's back stack
				// under a live EditorActivityKt - which ActionContextProvider.getActivity() can miss
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
