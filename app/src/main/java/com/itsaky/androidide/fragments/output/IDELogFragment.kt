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
import ch.qos.logback.classic.Level
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.logging.GlobalBufferAppender
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.viewmodel.IDELogsViewModel

/**
 * Fragment to show IDE logs.
 * @author Akash Yadav
 */
class IDELogFragment :
	LogViewFragment<IDELogsViewModel>(),
	GlobalBufferAppender.Consumer {
	override fun isSimpleFormattingEnabled() = true

	override fun getShareableFilename() = "ide_logs"

	override val tooltipTag = TooltipTag.PROJECT_IDE_LOGS

	override val viewModel by activityViewModels<IDELogsViewModel>()

	override val logLevel: Level
		get() = if (FeatureFlags.isDebugLoggingEnabled) Level.DEBUG else Level.INFO

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		emptyStateViewModel.setEmptyMessage(getString(R.string.msg_emptyview_idelogs))

		// Register with GlobalBufferAppender to receive all logs (including buffered ones)
		GlobalBufferAppender.registerConsumer(this)
	}

	override fun consume(message: String) = appendLine(message)

	override fun onDestroyView() {
		GlobalBufferAppender.unregisterConsumer(this)
		super.onDestroyView()
	}
}
