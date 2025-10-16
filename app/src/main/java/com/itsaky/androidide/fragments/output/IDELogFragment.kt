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
import androidx.lifecycle.Lifecycle
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.logging.GlobalBufferAppender
import com.itsaky.androidide.logging.LifecycleAwareAppender
import org.slf4j.LoggerFactory

/**
 * Fragment to show IDE logs.
 * @author Akash Yadav
 */
class IDELogFragment : LogViewFragment() {
	private var lifecycleAwareAppender: LifecycleAwareAppender? = null

	override fun isSimpleFormattingEnabled() = true

	override fun getShareableFilename() = "ide_logs"

	override val tooltipTag = TooltipTag.PROJECT_IDE_LOGS

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		emptyStateViewModel.emptyMessage.value = getString(R.string.msg_emptyview_idelogs)

		// Register with GlobalBufferAppender to receive all logs (including buffered ones)
		GlobalBufferAppender.registerConsumer(this::appendLine)

		val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
		val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    val appender = lifecycleAwareAppender ?: LifecycleAwareAppender(Lifecycle.State.CREATED).also {
        lifecycleAwareAppender = it
    }
    appender.consumer = this::appendLine
    appender.attachTo(viewLifecycleOwner)

    appender.context = loggerContext
    if (!appender.isStarted) appender.start()

    rootLogger.detachAppender(appender)
    rootLogger.addAppender(appender)
	}

	override fun onDestroyView() {
		// Unregister from GlobalBufferAppender
		GlobalBufferAppender.unregisterConsumer(this::appendLine)
		val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
		lifecycleAwareAppender?.let { appender ->
			logger.detachAppender(appender)
			appender.consumer = null
			if (appender.isStarted) appender.stop()
		}
		lifecycleAwareAppender = null

		super.onDestroyView()
	}
}
