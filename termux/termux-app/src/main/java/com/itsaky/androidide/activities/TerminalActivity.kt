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

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.terminal.CommandIntentRouter
import com.itsaky.androidide.terminal.TerminalSessionFactory
import com.itsaky.androidide.utils.Environment
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * @author Akash Yadav
 */
class TerminalActivity : TermuxActivity() {
    private var pendingWorkingDir: String? = null
    private var pendingSessionName: String? = null
    private var pendingIsFailsafe: Boolean = false
    // Each command request that arrived before the service connected, in order.
    private val pendingCommandRequestIds = mutableListOf<String>()

    // True when this instance was started only to run a plugin's command, not recreated or reused.
    private var launchedForCommand = false

    override val navigationBarColor: Int
        get() = ContextCompat.getColor(this, android.R.color.black)
    override val statusBarColor: Int
        get() = ContextCompat.getColor(this, android.R.color.black)

    override fun onCreate(savedInstanceState: Bundle?) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightNavigationBars = false
        controller.isAppearanceLightStatusBars = false
        // Read before super: TermuxActivity consumes the intent once its service connects.
        CommandIntentRouter.shared.requestId(intent)?.let(pendingCommandRequestIds::add)
        launchedForCommand = savedInstanceState == null && pendingCommandRequestIds.isNotEmpty()
        super.onCreate(savedInstanceState)
    }

    override fun onServiceConnected(componentName: ComponentName?, service: IBinder?) {
        super.onServiceConnected(componentName, service)
        lifecycleScope.launch(Dispatchers.IO) {
            Environment.mkdirIfNotExists(Environment.TMP_DIR)
        }

        val termuxService = mTermuxService
        if (termuxService != null && (pendingWorkingDir != null || pendingSessionName != null)) {
            createAndSetSession(termuxService, pendingWorkingDir, pendingSessionName, pendingIsFailsafe)

            pendingWorkingDir = null
            pendingSessionName = null
            pendingIsFailsafe = false
        }

        // No service yet: keep the requests and launchedForCommand for the next connection.
        if (termuxService == null) return

        val requestIds = pendingCommandRequestIds.toList()
        pendingCommandRequestIds.clear()
        val closeIfNoneStarts = launchedForCommand
        launchedForCommand = false
        var unrouted = requestIds.size
        var anyStarted = false
        requestIds.forEach { requestId ->
            runCommand(termuxService, requestId) { started ->
                anyStarted = anyStarted || started
                // Withdrawn or cancelled before their sessions started: close the window opened for them.
                if (--unrouted == 0 && !anyStarted && closeIfNoneStarts) finishActivityIfNotFinishing()
            }
        }
    }

    override fun setupTermuxSessionOnServiceConnected(
        intent: Intent?,
        workingDir: String?,
        sessionName: String?,
        existingSession: TermuxSession?,
        launchFailsafe: Boolean
    ) {
        // The command runs in a plugin session; a default shell beside it would be left behind.
        if (launchedForCommand && mTermuxService.isTermuxSessionsEmpty) return
        super.setupTermuxSessionOnServiceConnected(intent, workingDir, sessionName, existingSession, launchFailsafe)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent == null) return

        val commandRequestId = CommandIntentRouter.shared.requestId(intent)
        if (commandRequestId != null) {
            val service = mTermuxService
            if (service != null) runCommand(service, commandRequestId) else pendingCommandRequestIds += commandRequestId
            return
        }

        val newWorkingDir = intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_SESSION_WORKING_DIR)
        val newSessionName = intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_SESSION_NAME)
        val isFailsafe = intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)

        val service = mTermuxService
        if (service != null) {
            createAndSetSession(service, newWorkingDir, newSessionName, isFailsafe)
        } else {
            pendingWorkingDir = newWorkingDir
            pendingSessionName = newSessionName
            pendingIsFailsafe = isFailsafe
        }
    }

    /**
     * Runs a plugin's command in an idle session of that plugin, or a new one, and shows it. Tells
     * [onRouted] whether it started.
     */
    private fun runCommand(service: TermuxService, requestId: String, onRouted: (Boolean) -> Unit = {}) {
        // TermuxActivity finishes a window with no sessions when its service connects before onStart.
        CommandIntentRouter.shared.route(requestId, sessionFactory(service), screenClosing = { isFinishing }) { session ->
            // Destroyed meanwhile: there is no window to show it in.
            if (isDestroyed) return@route
            if (session != null) mTermuxTerminalSessionActivityClient.setCurrentSession(session)
            onRouted(session != null)
        }
    }

    private fun sessionFactory(service: TermuxService) = object : TerminalSessionFactory {
        override fun isOpen(session: TerminalSession) = service.getIndexOfSession(session) >= 0 && session.isRunning

        override fun open(name: String, bashArguments: Array<String>, workingDirectory: String?) =
            service.createTermuxSession(
                Environment.BASH_SHELL.absolutePath,
                bashArguments,
                null,
                workingDirectory,
                false,
                name
            )?.terminalSession
    }

    private fun createAndSetSession(
        service: TermuxService,
        workingDir: String?,
        sessionName: String?,
        isFailsafe: Boolean
    ) {
        val newSession = service.createTermuxSession(
            null,
            null,
            null,
            workingDir,
            isFailsafe,
            sessionName
        )

        if (newSession != null) {
            mTermuxTerminalSessionActivityClient.setCurrentSession(newSession.terminalSession)
        }
    }
}
