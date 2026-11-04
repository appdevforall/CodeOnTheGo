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

package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The baseline-generation stamp: `-P` property parsing (missing/malformed -> 0, for hosts older
 * than the stamping change) and the asset write the runtime reads pre-Context.
 */
class BaselineGenerationAssetTest {
	@TempDir lateinit var assetsRoot: File

	@Test
	fun `parses the property value the host passes`() {
		assertThat(BaselineGenerationAsset.parse("7")).isEqualTo(7L)
		assertThat(BaselineGenerationAsset.parse(" 42 ")).isEqualTo(42L)
	}

	@Test
	fun `a missing property stamps 0`() {
		// Compat: a CoGo host older than the stamping change passes no -P at all, and the
		// runtime treats a 0 stamp exactly like its pre-stamp constant baseline.
		assertThat(BaselineGenerationAsset.parse(null)).isEqualTo(0L)
	}

	@Test
	fun `a malformed or negative property stamps 0`() {
		assertThat(BaselineGenerationAsset.parse("")).isEqualTo(0L)
		assertThat(BaselineGenerationAsset.parse("garbage")).isEqualTo(0L)
		assertThat(BaselineGenerationAsset.parse("-3")).isEqualTo(0L)
	}

	@Test
	fun `writes the stamp as a sibling of the baseline payload dex`() {
		BaselineGenerationAsset.write(assetsRoot, 9L)

		val stamp = File(assetsRoot, BaselineGenerationAsset.ASSET_RELATIVE_PATH)
		assertThat(stamp.readText()).isEqualTo("9")
		// Sibling contract: the runtime resolves the stamp next to the baseline dex, so
		// both must live under the same assets/quickbuild/ directory in the APK.
		assertThat(stamp.parentFile.name).isEqualTo("quickbuild")
		assertThat(BaselineGenerationAsset.ASSET_RELATIVE_PATH).isEqualTo("quickbuild/baseline-generation.txt")
	}
}
