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

package com.itsaky.androidide.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.adapters.EditorBottomSheetTabAdapter
import com.itsaky.androidide.fragments.output.BuildOutputFragment
import com.itsaky.androidide.ui.EditorBottomSheet
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the session-file fallback [BuildOutputProvider.getBuildOutputContent] gained for
 * ADFA-5216. Most cases set no bottom sheet, which is the state the AI plugins read in: the tab may
 * never have been materialised, and the log matters most after a build that killed the service. The
 * live-content cases cover the other half -- a sheet that exists but answers blank while detached.
 */
class BuildOutputProviderTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@After
	fun tearDown() {
		BuildOutputProvider.setSessionDirectoryForTest(null)
		BuildOutputProvider.clearBottomSheet()
	}

	@Test
	fun `givenSessionFile_whenNoBottomSheet_thenReturnsItsContent`() {
		writeSessionFile("> Task :app:compileV8DebugKotlin\ne: Foo.kt:12:5 unresolved reference: bar\n")

		assertThat(BuildOutputProvider.getBuildOutputContent())
			.isEqualTo("> Task :app:compileV8DebugKotlin\ne: Foo.kt:12:5 unresolved reference: bar\n")
	}

	@Test
	fun `givenNoSessionFile_whenReadingOutput_thenReturnsNull`() {
		BuildOutputProvider.setSessionDirectoryForTest(tempFolder.root)

		// Null, not a status message: a non-empty answer is indistinguishable from a build log.
		assertThat(BuildOutputProvider.getBuildOutputContent()).isNull()
	}

	@Test
	fun `givenEmptySessionFile_whenReadingOutput_thenReturnsNull`() {
		writeSessionFile("")

		assertThat(BuildOutputProvider.getBuildOutputContent()).isNull()
	}

	@Test
	fun `givenBlankSessionFile_whenReadingOutput_thenReturnsNull`() {
		writeSessionFile("   \n\n\t\n")

		assertThat(BuildOutputProvider.getBuildOutputContent()).isNull()
	}

	@Test
	fun `givenUnresolvableSessionDirectory_whenReadingOutput_thenReturnsNull`() {
		// No override and no IDEApplication in a JVM test: the provider must not throw.
		assertThat(BuildOutputProvider.getBuildOutputContent()).isNull()
	}

	@Test
	fun `givenTimingPrefixes_whenReadingOutput_thenTheyAreStripped`() {
		val prefix = BuildOutputViewModel.formatLinePrefix(nowMs = 0L, stepDeltaMs = 12L)
		writeSessionFile("${prefix}e: Foo.kt:12:5 unresolved reference: bar\n")

		assertThat(BuildOutputProvider.getBuildOutputContent())
			.isEqualTo("e: Foo.kt:12:5 unresolved reference: bar\n")
	}

	@Test
	fun `givenLogLongerThanTheWindow_whenReadingOutput_thenTheTailIsReturned`() {
		val line = "> Task :app:someTaskWithAReasonablyLongName\n"
		val repeats = (BuildOutputProvider.WINDOW_MAX_CHARS / line.length) + 100
		writeSessionFile(line.repeat(repeats) + "FAILURE: Build failed with an exception.\n")

		val output = BuildOutputProvider.getBuildOutputContent()

		assertThat(output).isNotNull()
		assertThat(output!!.length).isAtMost(BuildOutputProvider.WINDOW_MAX_CHARS)
		assertThat(output).contains("FAILURE: Build failed with an exception.")
	}

	@Test
	fun `givenLogLongerThanTheWindow_whenReadingOutput_thenItStartsOnAWholeLine`() {
		// A tail sliced at a character offset leaves a fragment the start-anchored prefix regex
		// cannot match, so half a timestamp survives into the agent's first line.
		val prefix = BuildOutputViewModel.formatLinePrefix(nowMs = 0L, stepDeltaMs = 12L)
		val line = "$prefix> Task :app:someTaskWithAReasonablyLongName\n"
		val repeats = (BuildOutputProvider.WINDOW_MAX_CHARS / line.length) + 100
		writeSessionFile(line.repeat(repeats))

		val output = BuildOutputProvider.getBuildOutputContent()

		assertThat(output).isNotNull()
		assertThat(output!!.first()).isEqualTo('>')
		// Every prefix was stripped, so no fragment of one is left anywhere.
		assertThat(output).doesNotContain("]")
	}

	@Test
	fun `givenLiveContent_whenReadingOutput_thenTheSessionFileIsNotConsulted`() {
		writeSessionFile("stale session file\n")
		setLiveContent("> Task :app:compileV8DebugKotlin\n")

		assertThat(BuildOutputProvider.getBuildOutputContent())
			.isEqualTo("> Task :app:compileV8DebugKotlin\n")
	}

	@Test
	fun `givenBlankLiveContent_whenSessionFileExists_thenTheFileIsRead`() {
		// getShareableContent() returns "" while the fragment is detached; blank must fall through.
		writeSessionFile("e: Foo.kt:12:5 unresolved reference: bar\n")
		setLiveContent("   \n\t\n")

		assertThat(BuildOutputProvider.getBuildOutputContent())
			.isEqualTo("e: Foo.kt:12:5 unresolved reference: bar\n")
	}

	@Test
	fun `givenBlankLiveContent_whenNoSessionFile_thenReturnsNull`() {
		BuildOutputProvider.setSessionDirectoryForTest(tempFolder.root)
		setLiveContent("")

		assertThat(BuildOutputProvider.getBuildOutputContent()).isNull()
	}

	private fun setLiveContent(content: String) {
		val fragment = mockk<BuildOutputFragment>()
		every { fragment.getShareableContent() } returns content
		val adapter = mockk<EditorBottomSheetTabAdapter>()
		every { adapter.buildOutputFragment } returns fragment
		val sheet = mockk<EditorBottomSheet>()
		every { sheet.pagerAdapter } returns adapter
		BuildOutputProvider.setBottomSheet(sheet)
	}

	private fun writeSessionFile(content: String) {
		File(tempFolder.root, BuildOutputViewModel.SESSION_FILE_NAME).writeText(content)
		BuildOutputProvider.setSessionDirectoryForTest(tempFolder.root)
	}
}
