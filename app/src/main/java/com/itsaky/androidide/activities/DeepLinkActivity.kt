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
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.projects.IProjectManager

/**
 * The sole `<intent-filter>` holder for `https://www.appdevforall.org/device/open/project/...` App
 * Links. Never shows any UI -- it only parses the incoming [android.net.Uri], decides whether a
 * project is already loaded, and hands off to whichever real activity owns that scenario:
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
			finish()
			return
		}

		val target =
			if (IProjectManager.getInstance().workspace != null) {
				EditorActivityKt::class.java
			} else {
				MainActivity::class.java
			}

		startActivity(
			Intent(this, target).apply {
				putExtra(DeepLinkRequest.EXTRA_KEY, request)
				// SINGLE_TOP: if `target` is MainActivity and one is already on top of the stack
				// (e.g. the user was browsing recent projects when the link was tapped), reuse it via
				// onNewIntent instead of stacking a second instance. EditorActivityKt is singleTask,
				// so it always reuses its live instance regardless of this flag.
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			},
		)
		finish()
	}
}
