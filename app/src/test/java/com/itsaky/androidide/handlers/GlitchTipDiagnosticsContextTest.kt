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

package com.itsaky.androidide.handlers

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.buildinfo.BuildInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Verifies that [GlitchTipDiagnosticsContext] enriches events and, crucially, that
 * a single failing field collector never prevents the rest of the event from
 * being reported.
 *
 * @author Hal Eisen
 */
@RunWith(RobolectricTestRunner::class)
class GlitchTipDiagnosticsContextTest {
	private lateinit var app: IDEApplication

	@Before
	fun setUp() {
		// Skip the native-library block in IDEApplication's static initializer.
		System.setProperty("androidide.test.mode", "true")

		app = mockk(relaxed = true)
		every { app.packageName } returns "com.itsaky.androidide"
		every { app.filesDir } returns File("/tmp/androidide-test")
		every { app.isUserUnlocked } returns true

		mockkObject(IDEApplication.Companion)
		every { IDEApplication.instance } returns app
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	/** Installs the processor on a fresh options instance and enriches a new event. */
	private fun enrichNewEvent(): SentryEvent {
		val options = SentryOptions()
		GlitchTipDiagnosticsContext.install(options)
		val processor =
			options.eventProcessors
				.first { it.javaClass.name.contains("GlitchTipDiagnosticsContext") }
		return processor.process(SentryEvent(), Hint())!!
	}

	@Test
	fun `enrich populates diagnostic context on the event`() {
		val event = enrichNewEvent()

		// Live boot state and a compile-time release constant are both attached.
		assertThat(event.getTag("boot_mode")).isEqualTo("credential_unlocked")
		assertThat(event.getTag("app_version_name")).isEqualTo(BuildInfo.VERSION_NAME_SIMPLE)
	}

	@Test
	fun `a single failing collector never breaks the rest of the event`() {
		// Make the boot-mode collector blow up (simulating e.g. an SELinux denial).
		every { app.isUserUnlocked } throws RuntimeException("read denied")

		val event = enrichNewEvent()

		// The failing field is simply dropped...
		assertThat(event.getTag("boot_mode")).isNull()
		// ...the event is still returned, with every other field intact.
		assertThat(event.getTag("app_version_name")).isEqualTo(BuildInfo.VERSION_NAME_SIMPLE)
	}
}
