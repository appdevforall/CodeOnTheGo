package com.itsaky.androidide.editor.language.treesitter

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.editor.schemes.LanguageSpecProvider
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import io.github.rosemoe.sora.editor.ts.LineSpansGenerator
import io.github.rosemoe.sora.editor.ts.TsAnalyzeManager
import io.github.rosemoe.sora.editor.ts.TsTheme
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.BracketsProvider
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LineSpansSnapshotTest {
	private val source =
		(
			listOf("package sample;", "", "public class Sample {") +
				(0 until 40).map { "\tprivate static final int VALUE_$it = $it;" } +
				listOf("}")
		).joinToString("\n")

	@Before
	fun loadNative() {
		TreeSitter.loadLibrary()
	}

	@Test
	fun spansComeFromTheParsedTextWhenTheLiveTextMovesAhead() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val languageSpec = LanguageSpecProvider.getLanguageSpec(context, "java", TSLanguageJava.getInstance())
		val manager = TsAnalyzeManager(languageSpec.spec, TsTheme(languageSpec.spec.tsQuery))
		val generatorReady = CountDownLatch(1)
		manager.setReceiver(ReadySignal(generatorReady))
		val content = Content(source)
		manager.reset(ContentReference(content), Bundle())
		try {
			assertThat(generatorReady.await(10, TimeUnit.SECONDS)).isTrue()
			val generator = manager.styles.spans as LineSpansGenerator
			val lastConstantLine = content.lineCount - 2

			content.delete(1, 0, content.lineCount - 1, 0)

			assertThat(awaitComputedSpans(generator, lastConstantLine).size).isGreaterThan(1)
		} finally {
			manager.destroy()
			languageSpec.close()
		}
	}

	private fun awaitComputedSpans(
		generator: LineSpansGenerator,
		line: Int,
	): List<Span> {
		val reader = generator.read()
		val deadline = System.currentTimeMillis() + 5_000
		var spans = reader.getSpansOnLine(line)
		while (spans.size < 2 && System.currentTimeMillis() < deadline) {
			Thread.sleep(20)
			spans = reader.getSpansOnLine(line)
		}
		return spans
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
}
