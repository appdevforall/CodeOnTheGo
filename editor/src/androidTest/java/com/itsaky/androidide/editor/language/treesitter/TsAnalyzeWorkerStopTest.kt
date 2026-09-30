package com.itsaky.androidide.editor.language.treesitter

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.editor.schemes.LanguageSpecProvider
import com.itsaky.androidide.logging.provider.IdeGlobalLogBuffer
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.treesitter.log.TSLanguageLog
import io.github.rosemoe.sora.editor.ts.LineSpansGenerator
import io.github.rosemoe.sora.editor.ts.TsAnalyzeManager
import io.github.rosemoe.sora.editor.ts.TsTheme
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.BracketsProvider
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.slf4j.event.Level
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TsAnalyzeWorkerStopTest {
	@Before
	fun loadNative() {
		TreeSitter.loadLibrary()
	}

	@Test
	fun restartingTheAnalyzerMidEditNeverCrashesTheStoppedWorker() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val languageSpec = LanguageSpecProvider.getLanguageSpec(context, "log", TSLanguageLog.getInstance())
		val manager = TsAnalyzeManager(languageSpec.spec, TsTheme(languageSpec.spec.tsQuery))
		val workerCrashes = WorkerCrashRecorder()
		IdeGlobalLogBuffer.registerConsumer(workerCrashes)
		workerCrashes.messages.clear()
		try {
			repeat(ITERATIONS) { iteration ->
				val content = Content(logText())
				startAndAwaitStyles(manager, content)

				val end = content.indexer.getCharPosition(TRIMMED_LINES, 0)
				val deleted = content.subContent(0, 0, TRIMMED_LINES, 0)
				content.delete(0, 0, TRIMMED_LINES, 0)
				manager.delete(content.indexer.getCharPosition(0), end, deleted)
				Thread.sleep((iteration % 4).toLong())
				manager.reset(ContentReference(content), Bundle())
			}
			manager.destroy()
			Thread.sleep(500)
		} finally {
			IdeGlobalLogBuffer.unregisterConsumer(workerCrashes)
			languageSpec.close()
		}

		assertThat(workerCrashes.messages).isEmpty()
	}

	private fun startAndAwaitStyles(
		manager: TsAnalyzeManager,
		content: Content,
	) {
		val generatorReady = CountDownLatch(1)
		manager.setReceiver(ReadySignal(generatorReady))
		manager.reset(ContentReference(content), Bundle())
		assertThat(generatorReady.await(30, TimeUnit.SECONDS)).isTrue()
	}

	private fun logText(): String =
		(0 until LINES).joinToString("\n") { index ->
			"09-30 12:07:48.292  7932 10618 I Flood   : line $index ${"abcdefghijklmnopqrstuvwxyz0123456789".repeat(8)}"
		}

	private class WorkerCrashRecorder : IdeGlobalLogBuffer.Consumer {
		val messages = CopyOnWriteArrayList<String>()

		override val logLevel: Level = Level.ERROR

		override fun consume(
			level: Level,
			message: String,
		) {
			if (message.contains("AnalyzeWorker[")) messages.add(message)
		}
	}

	private class ReadySignal(
		private val latch: CountDownLatch,
	) : StyleReceiver {
		override fun setStyles(
			sourceManager: AnalyzeManager,
			styles: Styles?,
		) {
			if (styles?.spans is LineSpansGenerator) latch.countDown()
		}

		override fun setStyles(
			sourceManager: AnalyzeManager,
			styles: Styles?,
			action: Runnable?,
		) = setStyles(sourceManager, styles)

		override fun setDiagnostics(
			sourceManager: AnalyzeManager,
			diagnostics: DiagnosticsContainer?,
		) = Unit

		override fun updateBracketProvider(
			sourceManager: AnalyzeManager,
			provider: BracketsProvider?,
		) = Unit
	}

	private companion object {
		const val ITERATIONS = 40
		const val LINES = 3_000
		const val TRIMMED_LINES = 300
	}
}
