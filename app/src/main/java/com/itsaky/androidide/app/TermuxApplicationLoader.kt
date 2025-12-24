package com.itsaky.androidide.app

import android.content.Context
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.file.TermuxFileUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.am.TermuxAmSocketServer
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.theme.TermuxThemeUtils
import org.slf4j.LoggerFactory

/**
 * [ApplicationLoader] used to load the Termux part of the IDE. Adapted from
 * `TermuxApplication` classes.
 *
 * @author Akash Yadav
 */
object TermuxApplicationLoader : ApplicationLoader {
	private val logger = LoggerFactory.getLogger(TermuxApplicationLoader::class.java)

	override suspend fun load(app: IDEApplication) {
		// Set log config for the app
		setLogConfig(app)

		logger.debug("Starting application")

		// Init app wide SharedProperties loaded from termux.properties
		val properties = TermuxAppSharedProperties.init(app)

		// Init app wide shell manager
		TermuxShellManager.init(app)

		// Set NightMode.APP_NIGHT_MODE
		TermuxThemeUtils.setAppNightMode(properties.nightMode)

		// Check and create termux files directory. If failed to access it like in case of secondary
		// user or external sd card installation, then don't run files directory related code
		var error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(app, true, true)
		val isTermuxFilesDirectoryAccessible = error == null
		if (isTermuxFilesDirectoryAccessible) {
			logger.info("Termux files directory is accessible")

			error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true)
			if (error != null) {
				logger.error("Create apps/termux-app directory failed\n{}", error)
				return
			}

			// Setup termux-am-socket server
			TermuxAmSocketServer.setupTermuxAmSocketServer(app)
		} else {
			logger.error("Termux files directory is not accessible\n{}", error)
		}

		// Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
		TermuxShellEnvironment.init(app)

		if (isTermuxFilesDirectoryAccessible) {
			TermuxShellEnvironment.writeEnvironmentToFile(app)
		}
	}

	private fun setLogConfig(context: Context) {
		Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME)

		// Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
		val preferences = TermuxAppSharedPreferences.build(context) ?: return
		preferences.setLogLevel(null, preferences.logLevel)
	}
}
