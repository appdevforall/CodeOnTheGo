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

package com.itsaky.androidide.assets

import android.content.Context
import androidx.annotation.WorkerThread
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.utils.Environment
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_API_NAME_JAR
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Post-installation audit: verifies that all binary assets were correctly
 * delivered (exist and meet expected size) before marking setup complete.
 */
object AssetsInstallationAudit {

	private const val SIZE_TOLERANCE = 0.95

	private val logger = LoggerFactory.getLogger(AssetsInstallationAudit::class.java)
	private val installer = AssetsInstaller.CURRENT_INSTALLER

	private val expectedEntries =
		arrayOf(
			GRADLE_DISTRIBUTION_ARCHIVE_NAME,
			ANDROID_SDK_ZIP,
			DOCUMENTATION_DB,
			LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
			AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME,
			GRADLE_API_NAME_JAR_ZIP,
			AssetsInstallationHelper.LLAMA_AAR,
			AssetsInstallationHelper.PLUGIN_ARTIFACTS_ZIP,
		)

	sealed interface Result {
		data object Success : Result
		data class Failure(val entryName: String, val message: String) : Result
	}

	/**
	 * Runs the audit. Call from IO dispatcher.
	 * Verifies each asset destination exists and (when expected size is known)
	 * meets minimum size (expectedSize * SIZE_TOLERANCE).
	 */
	@WorkerThread
	fun run(context: Context): Result {
		for (entryName in expectedEntries) {
			val failure = checkEntry(context, entryName)
			if (failure != null) {
				logger.warn("Audit failed for '{}': {}", entryName, failure)
				return Result.Failure(entryName, failure)
			}
		}
		return Result.Success
	}

	private fun checkEntry(context: Context, entryName: String): String? {
		val expectedSize = installer.expectedSize(entryName)
		return when (entryName) {
			GRADLE_DISTRIBUTION_ARCHIVE_NAME ->
				checkDir(Environment.GRADLE_DISTS, entryName, expectedSize)
			ANDROID_SDK_ZIP ->
				checkDir(Environment.ANDROID_HOME, entryName, expectedSize)
			DOCUMENTATION_DB ->
				checkFile(Environment.DOC_DB, entryName, expectedSize)
			LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME ->
				checkDir(Environment.LOCAL_MAVEN_DIR, entryName, expectedSize)
			AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME ->
				checkBootstrap(entryName, expectedSize)
			GRADLE_API_NAME_JAR_ZIP -> {
				val jarFile = File(Environment.GRADLE_GEN_JARS, GRADLE_API_NAME_JAR)
				checkFile(jarFile, entryName, expectedSize)
			}
			AssetsInstallationHelper.LLAMA_AAR ->
				checkLlamaAar(context, entryName, expectedSize)
			AssetsInstallationHelper.PLUGIN_ARTIFACTS_ZIP ->
				checkPluginArtifacts(entryName, expectedSize)
			else -> "Unknown entry: $entryName"
		}
	}

	private fun checkFile(file: File, entryName: String, expectedSize: Long): String? {
		if (!file.exists()) return "File missing: ${file.absolutePath}"
		if (!file.isFile) return "Not a file: ${file.absolutePath}"
		if (expectedSize > 0) {
			val minSize = (expectedSize * SIZE_TOLERANCE).toLong()
			if (file.length() < minSize) {
				return "File too small: ${file.absolutePath} (${file.length()} < $minSize)"
			}
		} else if (file.length() == 0L) {
			return "File empty: ${file.absolutePath}"
		}
		return null
	}

	private fun checkDir(dir: File, entryName: String, expectedSize: Long): String? {
		if (!dir.exists()) return "Directory missing: ${dir.absolutePath}"
		if (!dir.isDirectory) return "Not a directory: ${dir.absolutePath}"
		if (expectedSize > 0) {
			val totalSize = dir.recursiveSize()
			val minSize = (expectedSize * SIZE_TOLERANCE).toLong()
			if (totalSize < minSize) {
				return "Directory too small: ${dir.absolutePath} ($totalSize < $minSize)"
			}
		} else {
			if (dir.recursiveSize() == 0L) return "Directory empty: ${dir.absolutePath}"
		}
		return null
	}

	private fun checkBootstrap(entryName: String, expectedSize: Long): String? {
		val bash = Environment.BASH_SHELL
		val login = Environment.LOGIN_SHELL
		if (!bash.exists() || !bash.isFile || bash.length() == 0L) {
			return "Bootstrap missing or empty: ${bash.absolutePath}"
		}
		if (!login.exists() || !login.isFile || login.length() == 0L) {
			return "Bootstrap missing or empty: ${login.absolutePath}"
		}
		return null
	}

	private fun checkLlamaAar(context: Context, entryName: String, expectedSize: Long): String? {
		val cpuArch = IDEBuildConfigProvider.getInstance().cpuArch
		when (cpuArch) {
			CpuArch.AARCH64,
			CpuArch.ARM,
			-> {
				val destDir = context.getDir("dynamic_libs", Context.MODE_PRIVATE)
				val llamaFile = File(destDir, "llama.aar")
				return checkFile(llamaFile, entryName, expectedSize)
			}
			else -> {
				// Unsupported arch: installer skips; audit passes without file
				return null
			}
		}
	}

	private fun checkPluginArtifacts(entryName: String, expectedSize: Long): String? {
		val pluginDir = Environment.PLUGIN_API_JAR.parentFile ?: return "Plugin dir null"
		if (!pluginDir.exists()) return "Plugin directory missing: ${pluginDir.absolutePath}"
		if (!pluginDir.isDirectory) return "Not a directory: ${pluginDir.absolutePath}"
		if (!Environment.PLUGIN_API_JAR.exists()) {
			return "Plugin API jar missing: ${Environment.PLUGIN_API_JAR.absolutePath}"
		}
		if (expectedSize > 0) {
			val totalSize = pluginDir.recursiveSize()
			val minSize = (expectedSize * SIZE_TOLERANCE).toLong()
			if (totalSize < minSize) {
				return "Plugin dir too small: ${pluginDir.absolutePath} ($totalSize < $minSize)"
			}
		} else if (pluginDir.recursiveSize() == 0L) {
			return "Plugin directory empty: ${pluginDir.absolutePath}"
		}
		return null
	}

	private fun File.recursiveSize(): Long =
		if (isFile) length()
		else listFiles()?.sumOf { it.recursiveSize() } ?: 0L
}
