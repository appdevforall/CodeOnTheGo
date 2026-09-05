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

package com.itsaky.androidide.editor.floating

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.floating.model.DockableContent
import com.itsaky.androidide.floating.window.FloatingWindowHost
import com.itsaky.androidide.ui.MetricsCarouselController

/**
 * Adapts the editor's metrics carousel to [DockableContent] so it can float over other apps
 * (ADFA-5486).
 *
 * The window rebinds the editor's own [MetricsCarouselController] rather than building a second
 * one. Only one carousel can be live at a time -- the watchers hold a single listener each -- so
 * undocking moves the carousel out of the editor rather than copying it, which is also how an
 * editor file tab undocks. The editor shows a "tap to bring them back" message in the space it
 * vacates.
 *
 * The sample history is unaffected by the move: the watchers own it, so the carousel is redrawn in
 * full wherever it is bound.
 *
 * @property controller The carousel to rebind into this window.
 * @property title Window title, resolved by the caller against the IDE's resources.
 */
class MetricsCarouselDockableContent(
	private val controller: MetricsCarouselController,
	override val title: String,
) : DockableContent {
	override val id: String = ID

	override fun onCreateView(
		context: Context,
		host: FloatingWindowHost,
	): View {
		val binding = LayoutMemUsageBinding.inflate(LayoutInflater.from(context))

		// The editor sizes the carousel to a fixed strip; in a window it should fill whatever the
		// user has dragged the frame out to.
		binding.root.layoutParams =
			ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)

		// A two-finger tap is what undocked it; inside the window the chrome's dock control is the
		// way back, so the gesture would only be a second, less discoverable route.
		binding.root.onTwoFingerTap = null

		controller.bind(binding)
		return binding.root
	}

	override fun onDestroyView() {
		controller.unbind()
	}

	companion object {
		/** Stable id, shared with the docked carousel this content was undocked from. */
		const val ID = "ide.metrics.carousel"
	}
}
