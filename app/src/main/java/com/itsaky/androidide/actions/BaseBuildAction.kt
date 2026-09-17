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

package com.itsaky.androidide.actions

import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashInfoLong

/**
 * Marker class for actions that execute build related tasks.
 *
 * @author Akash Yadav
 */
abstract class BaseBuildAction : EditorActivityAction() {
	protected val buildService: BuildService?
		get() = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)

	override fun prepare(data: ActionData) {
		super.prepare(data)
		val context = data.getActivity()
		if (context == null) {
			visible = false
			return
		} else {
			visible = true
		}

		// The user-visible flag, not the raw one: Quick Build's eager prebuild holds the slot after
		// every project open, and greying these out for it gave the user no reason. A tap in that
		// window is refused with an explanation by refuseWhileSlotBusy instead.
		enabled = buildService?.let { !it.isUserVisibleBuildInProgress } == true
	}

	/**
	 * Refuses a tap while an internal build (Quick Build's proxy app build) holds the one Gradle
	 * slot, and flashes why. [prepare] leaves these actions enabled in that window, and starting a
	 * second build would throw BuildInProgressException deep in the service and surface as a raw
	 * error. Reads the raw [BuildService.isBuildInProgress], because this guards the slot rather
	 * than presenting state.
	 *
	 * @return true when the tap was refused and the caller must start nothing.
	 */
	protected fun refuseWhileSlotBusy(data: ActionData): Boolean {
		if (buildService?.isBuildInProgress != true) return false
		// Long, not the default 1 s: a two-sentence explanation is gone before it can be read at
		// the short duration.
		data.getActivity()?.let { it.flashInfoLong(it.getString(R.string.msg_build_slot_busy)) }
		return true
	}
}
