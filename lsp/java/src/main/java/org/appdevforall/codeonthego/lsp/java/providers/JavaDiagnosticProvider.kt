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
package org.appdevforall.codeonthego.lsp.java.providers

import org.appdevforall.codeonthego.lsp.java.compiler.CompileTask
import org.appdevforall.codeonthego.lsp.java.compiler.JavaCompilerService
import org.appdevforall.codeonthego.lsp.java.providers.DiagnosticsProvider.findDiagnostics
import org.appdevforall.codeonthego.lsp.java.utils.CancelChecker
import org.appdevforall.codeonthego.lsp.models.DiagnosticResult
import org.appdevforall.codeonthego.progress.ProgressManager
import org.appdevforall.codeonthego.progress.ProgressManager.Companion.abortIfCancelled
import org.appdevforall.codeonthego.projects.FileManager
import org.appdevforall.codeonthego.projects.IProjectManager
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Code analyzer for java source code.
 *
 * @author Akash Yadav
 */
class JavaDiagnosticProvider {

	private val analyzeTimestamps = mutableMapOf<Path, Instant>()
	private var cachedDiagnostics = DiagnosticResult.NO_UPDATE
	private var analyzing = AtomicBoolean(false)
	private var analyzingThread: AnalyzingThread? = null

	companion object {

		private val log = LoggerFactory.getLogger(JavaDiagnosticProvider::class.java)
	}

	fun analyze(file: Path): DiagnosticResult {

		val module = IProjectManager.getInstance().findModuleForFile(file, false)
			?: return DiagnosticResult.NO_UPDATE
		val compiler = JavaCompilerService(module)

		abortIfCancelled()

		log.debug("Analyzing: {}", file)

		val modifiedAt = FileManager.getLastModified(file)
		val analyzedAt = analyzeTimestamps[file]

		if (analyzedAt?.isAfter(modifiedAt) == true) {
			log.debug("Using cached analyze results...")
			return cachedDiagnostics
		}

		analyzingThread?.let { analyzingThread ->
			if (analyzing.get()) {
				log.debug("Cancelling currently analyzing thread...")
				ProgressManager.instance.cancel(analyzingThread)
				this.analyzingThread = null
			}
		}

		analyzing.set(true)

		val analyzingThread = AnalyzingThread(compiler, file).also {
			analyzingThread = it
			it.start()
			it.join()
		}

		return analyzingThread.result.also {
			this.analyzingThread = null
		}
	}

	fun isAnalyzing(): Boolean {
		return this.analyzing.get()
	}

	fun cancel() {
		this.analyzingThread?.cancel()
	}

	fun clearTimestamp(file: Path) {
		analyzeTimestamps.remove(file)
	}

	private fun doAnalyze(file: Path, task: CompileTask): DiagnosticResult {
		val result =
			if (!isTaskValid(task)) {
				// Do not use Collections.emptyList ()
				// The returned list is accessed and the list returned by Collections.emptyList()
				// throws exception when trying to access.
				log.info("Using cached diagnostics")
				cachedDiagnostics
			} else
				DiagnosticResult(
					file,
					findDiagnostics(task, file).sortedBy {
						it.range
					}
				)
		return result.also {
			log.info("Analyze file completed. Found {} diagnostic items", result.diagnostics.size)
		}
	}

	private fun isTaskValid(task: CompileTask?): Boolean {
		abortIfCancelled()
		return task?.task != null && task.roots != null && task.roots.size > 0
	}

	inner class AnalyzingThread(val compiler: JavaCompilerService, val file: Path) :
		Thread("JavaAnalyzerThread") {

		var result: DiagnosticResult = DiagnosticResult.NO_UPDATE

		fun cancel() {
			ProgressManager.instance.cancel(this)
		}

		override fun run() {
			result =
				try {
					compiler.compile(file).get { task -> doAnalyze(file, task) }
				} catch (err: Throwable) {
					if (CancelChecker.isCancelled(err)) {
						log.error("Analyze request cancelled")
					} else {
						log.warn("Unable to analyze file", err)
					}
					DiagnosticResult.NO_UPDATE
				} finally {
					compiler.destroy()
					analyzing.set(false)
				}
					.also {
						cachedDiagnostics = it
						analyzeTimestamps[file] = Instant.now()
					}
		}
	}
}
