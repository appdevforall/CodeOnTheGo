package com.itsaky.androidide.editor.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.slot
import io.mockk.spyk
import org.junit.Test
import org.junit.runner.RunWith

// TODO: This should have been added in the :editor module
//   But a large portion of our codebase depends on using SLF4J loggers
//   However, Logback (the backend for SLF4J) couldn't work in non-JVM runtimes
//   We fix this in :app module by replacing the invocations to broken functions
//   with our own implementation (using our desugaring plugin). However, this
//   desugaring plugin is not applied to any module other than :app and hence,
//   we can't use loggers in instrumentation tests in such modules

/**
 * @author Akash Yadav
 */
@RunWith(AndroidJUnit4::class)
class IDEEditorTest {

	private fun assertEditorCopiesDebugInfo(
		expected: String,
		initialText: String = "some text",
		includeDebugInfo: Boolean = true,
	) {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		instrumentation.runOnMainSync {
			val context = instrumentation.targetContext

			val editor = spyk(IDEEditor(context))
			editor.append(initialText)

			val copySlot = slot<CharSequence>()
			every { editor.doCopy(capture(copySlot), any(), any()) } returns Unit

			editor.includeDebugInfoOnCopy = includeDebugInfo
			editor.copyText()

			assertThat(copySlot.isCaptured).isTrue()

			val clip = copySlot.captured
			assertThat(clip).isNotNull()

			assertThat(clip.toString().toByteArray()).isEqualTo(expected.toByteArray())
		}
	}

	@Test
	fun test_includeDebugInfoFlagOnCopy_ifEnabled() {
		val initial = "some text"
		assertEditorCopiesDebugInfo(
			expected = "CodeOnTheGo (unspecified)${System.lineSeparator()}${initial}",
			includeDebugInfo = true,
			initialText = initial
		)
	}

	@Test
	fun test_includeDebugInfoFlagOnCopy_ifDisabled() {
		val initial = "some other text"
		assertEditorCopiesDebugInfo(
			expected = initial,
			includeDebugInfo = false,
			initialText = initial
		)
	}
}