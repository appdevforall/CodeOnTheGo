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

package com.itsaky.androidide.fragments.output

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.itsaky.androidide.viewmodel.AppLogsViewModel
import com.itsaky.androidide.viewmodel.LogViewModel

/**
 * Fragment to show application logs.
 * @author Akash Yadav
 */
class AppLogFragment : LogViewFragment<LogViewModel>() {
	override val tooltipTag = TooltipTag.PROJECT_APP_LOGS

	override val viewModel by activityViewModels<AppLogsViewModel>()

	override fun isSimpleFormattingEnabled() = false

	override fun getShareableFilename() = "app_logs"

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		emptyStateViewModel.setEmptyMessage(
			if (DevOpsPreferences.logsenderEnabled) {
				getString(R.string.msg_emptyview_applogs)
			} else {
				getString(R.string.msg_logsender_disabled)
			},
		)
	}
}
