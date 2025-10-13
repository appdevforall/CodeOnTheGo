package com.itsaky.androidide.utils

import android.content.ComponentName
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku

/**
 * @author Akash Yadav
 */
object PrivilegedActions {
	private val logger = LoggerFactory.getLogger(PrivilegedActions::class.java)

	/**
	 * Launch an application with privileged Shizuku APIs.
	 * @return Whether the launch was successful.
	 */
	suspend fun launchApp(
		component: ComponentName,
		action: String,
		categories: Set<String>,
		forceStop: Boolean = false,
		debugMode: Boolean = false,
		skipAnimation: Boolean = false,
	): Boolean {
		try {
			// @formatter:off
			val launchCmd = mutableListOf(
				"/system/bin/am",
				"start",

				// component name (e.g. com.itsaky.example/com.itsaky.example.MainActivity)
				"-n", component.flattenToString(),

				// action (e.g. android.intent.action.MAIN)
				"-a", action,
			)
			// @formatter:on

			categories.forEach { category ->
				// category (e.g. android.intent.category.LAUNCHER)
				launchCmd.add("-c")
				launchCmd.add(category)
			}

			if (skipAnimation) {
				launchCmd.add("--activity-no-animation")
			}

			if (forceStop) {
				launchCmd.add("-S")
			}

			if (debugMode) {
				// launch in debug mode,
				launchCmd.add("-D")

				// Instead of using ADB to connect to the already-running JDWP server (like Android Studio),
				// we instruct the system to attach the JDWP agent to process before it's started.
				// We also provide options to the JDWP agent so that it connects to us on the right
				// port.
				//
				// Why not use absolute path to our build of oj-libjdwp?
				// - Because the system will only look for `libjdwp.so` in the library search paths
				// already known to it. If we provide an absolute path, it'll still have to find
				// the dependent libraries (like `libdt_socket.so`), which might fail. This can be
				// fixed by recompiling libjdwp.so to include DT_RUNPATH/DT_RPATH entries in the
				// elf file, but that's too much work given that we can already use libjdwp.so from
				// the system. In case we need to add certain features to the debugger which are
				// not already available in system's libjdwp.so, we'll have to update this to load
				// our version of the agent.
				launchCmd.add("--attach-agent")
				launchCmd.add("libjdwp.so=${JdwpOptions.JDWP_OPTIONS}")
			}

			logger.debug("Launching app with command: {}", launchCmd.joinToString(" "))

			// TODO: Maybe use a UserService to handle this? Or maybe add custom APIs to Shizuku?
			@Suppress("DEPRECATION")
			val process =
				Shizuku.newProcess(
					launchCmd.toTypedArray(),
					null,
					null,
				)

			val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
			logger.debug("Launch process exited with exit code {}", exitCode)
			return exitCode == 0
		} catch (e: Throwable) {
			logger.error(
				"Failed to launch component={} action={} category={} forceStop={} debugMode={}",
				component.flattenToString(),
				action,
				categories.joinToString(separator = ","),
				forceStop,
				debugMode,
				e,
			)

			return false
		}
	}
}
