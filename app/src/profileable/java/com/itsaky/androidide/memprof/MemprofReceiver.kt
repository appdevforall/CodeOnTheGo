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

package com.itsaky.androidide.memprof

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.java.JavaLanguageServer
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.xml.XMLLanguageServer
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors

/**
 * Drives a memory measurement from the shell, so a workload runs the same way every time.
 *
 * Declared only in the `profileable` variant and **not exported**: `am broadcast` still reaches it
 * because ActivityManager skips the component export check for root, which the measurement harness
 * already runs as. That keeps the surface closed on any build a user could install.
 *
 * ```
 * su 0 am broadcast -n com.itsaky.androidide/com.itsaky.androidide.memprof.MemprofReceiver \
 *     -a com.itsaky.androidide.memprof.CMD --es op mark --es name pre_completion
 * su 0 am broadcast -n com.itsaky.androidide/com.itsaky.androidide.memprof.MemprofReceiver \
 *     -a com.itsaky.androidide.memprof.CMD --es op report
 * su 0 am broadcast -n com.itsaky.androidide/com.itsaky.androidide.memprof.MemprofReceiver \
 *     -a com.itsaky.androidide.memprof.CMD --es op completion \
 *     --es file /storage/emulated/0/.../Foo.kt --ei line 42 --ei column 8 --ei repeat 5
 * ```
 */
class MemprofReceiver : BroadcastReceiver() {
	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		val op = intent.getStringExtra(EXTRA_OP)
		if (op == null) {
			logger.warn("MEMPROF|error|{}|reason=missing_op", System.currentTimeMillis())
			return
		}

		// onReceive runs on the main thread and every op here does disk or compiler work.
		val pending = goAsync()
		worker.execute {
			try {
				dispatch(op, intent)
			} catch (failure: Throwable) {
				logger.error("MEMPROF|error|{}|op={}", System.currentTimeMillis(), op, failure)
			}
		}
		// Nothing reads the broadcast result, and holding it through an hprof dump or repeated
		// completions risks a background-broadcast ANR; finish as soon as the work is handed off.
		pending.finish()
	}

	private fun dispatch(
		op: String,
		intent: Intent,
	) {
		when (op) {
			"mark" -> {
				AllocationSampler.mark(intent.getStringExtra("name") ?: "unnamed")
			}

			"sampler" -> {
				when (intent.getStringExtra("state")) {
					"stop" -> {
						AllocationSampler.stop()
					}

					else -> {
						AllocationSampler.start(
							intent.getLongExtra("intervalMs", AllocationSampler.DEFAULT_INTERVAL_MS),
						)
					}
				}
			}

			"hprof" -> {
				dumpHprof(intent.getStringExtra("path"))
			}

			"report" -> {
				MemprofSession.writer?.apply {
					write()
					log()
				}
			}

			"dumpat" -> {
				val fraction = intent.getFloatExtra("fraction", 0.85f).toDouble()
				AllocationSampler.dumpAtFraction = fraction
				logger.info("MEMPROF|dumpat|{}|fraction={}", System.currentTimeMillis(), fraction)
			}

			"completion" -> {
				complete(
					file = intent.getStringExtra("file") ?: error("completion needs --es file"),
					line = intent.getIntExtra("line", -1),
					column = intent.getIntExtra("column", -1),
					repeat = intent.getIntExtra("repeat", 1),
				)
			}

			else -> {
				logger.warn("MEMPROF|error|{}|reason=unknown_op|op={}", System.currentTimeMillis(), op)
			}
		}
	}

	private fun dumpHprof(path: String?) {
		val target = path ?: "${AllocationSampler.dumpDir}/memprof-${System.currentTimeMillis()}.hprof"
		AllocationSampler.sampleNow("pre_hprof")
		val started = System.currentTimeMillis()
		Debug.dumpHprofData(target)
		logger.info(
			"MEMPROF|hprof|{}|path={}|durationMs={}|bytes={}",
			System.currentTimeMillis(),
			target,
			System.currentTimeMillis() - started,
			File(target).length(),
		)
		AllocationSampler.sampleNow("post_hprof")
	}

	private fun complete(
		file: String,
		line: Int,
		column: Int,
		repeat: Int,
	) {
		val source = File(file)
		if (!source.isFile) {
			logger.warn("MEMPROF|error|{}|reason=no_such_file|file={}", System.currentTimeMillis(), file)
			return
		}

		val serverId = serverIdFor(source)
		if (serverId == null) {
			logger.warn("MEMPROF|error|{}|reason=no_server_for|file={}", System.currentTimeMillis(), file)
			return
		}

		val server = ILanguageServerRegistry.default.getServer(serverId)
		if (server == null) {
			logger.warn("MEMPROF|error|{}|reason=server_absent|server={}", System.currentTimeMillis(), serverId)
			return
		}

		val text = source.readText()
		val index = offsetOf(text, line, column)
		val prefix = prefixAt(text, index)

		repeat(repeat) { iteration ->
			val params = CompletionParams(Position(line, column, index), source.toPath(), ICancelChecker.Default())
			params.content = text
			params.prefix = prefix

			AllocationSampler.sampleNow("pre_completion_$iteration")
			val started = System.currentTimeMillis()
			val result = server.complete(params)
			val elapsed = System.currentTimeMillis() - started
			AllocationSampler.sampleNow("post_completion_$iteration")

			logger.info(
				"MEMPROF|completion|{}|server={}|iteration={}|prefix={}|items={}|incomplete={}|durationMs={}",
				System.currentTimeMillis(),
				serverId,
				iteration,
				prefix,
				result.items.size,
				result.isIncomplete,
				elapsed,
			)
		}
	}

	private fun serverIdFor(file: File): String? =
		when (file.extension) {
			"kt", "kts" -> KotlinLanguageServer.SERVER_ID
			"java" -> JavaLanguageServer.SERVER_ID
			"xml" -> XMLLanguageServer.SERVER_ID
			else -> null
		}

	/** Character offset of a zero-based (line, column), clamped to the end of the text. */
	private fun offsetOf(
		text: String,
		line: Int,
		column: Int,
	): Int {
		var offset = 0
		var seen = 0
		while (seen < line && offset < text.length) {
			if (text[offset] == '\n') {
				seen++
			}
			offset++
		}
		return (offset + column).coerceIn(0, text.length)
	}

	/** The identifier immediately left of [index], which is what a completion request is keyed on. */
	private fun prefixAt(
		text: String,
		index: Int,
	): String {
		var start = index
		while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) {
			start--
		}
		return text.substring(start, index)
	}

	companion object {
		private val logger = LoggerFactory.getLogger(MemprofReceiver::class.java)
		private val worker =
			Executors.newSingleThreadExecutor { runnable ->
				Thread(runnable, "memprof-receiver").apply { isDaemon = true }
			}

		private const val EXTRA_OP = "op"
	}
}
