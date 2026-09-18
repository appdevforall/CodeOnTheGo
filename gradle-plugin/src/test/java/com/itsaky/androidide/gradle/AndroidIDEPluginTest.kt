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

package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_ENABLED
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * @author Akash Yadav
 */
class AndroidIDEPluginTest {
	@Test
	fun `test logsender must be enabled by default`() {
		val result = buildProject()
		assertThat(result.output).doesNotContain("LogSender is disabled")
	}

	@Disabled(
		"LogSenderPlugin reads ApplicationVariantBuilder.debuggable inside an AGP beforeVariants " +
			"callback, which AGP forbids with PropertyAccessNotAllowedException, so enabling " +
			"LogSender fails to configure ':app'. Same LogSenderPlugin/AGP issue that disables the " +
			"debuggable-variants test in AndroidIDEInitScriptPluginTest. The fix (move the read to " +
			"onVariants, which also covers JdwpPlugin) is ADFA-5433; re-enabling this test is ADFA-5459.",
	)
	@Test
	fun `test logsender must be enabled if specified explicitly`(
		@TempDir dir: File,
	) {
		// LogSenderPlugin fails the build unless an AAR path is set, so enabling it without
		// one tests nothing. buildProject sets both properties when given the AAR.
		val aar = File(dir, "logsender.aar").apply { writeText("aar") }
		val result =
			buildProject(logSenderAar = aar, configureArgs = {
				it.add("-P$PROPERTY_LOG_SENDER_ENABLED=true")
			})
		assertThat(result.output).contains("Applying LogSenderPlugin to project ':app'")
	}
}
