package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class ChangedFilesTest {
	private fun known(vararg paths: String) = ChangedFiles.Known(paths.map(::File).toSet())

	@Test
	fun `union of known sets is the set union`() {
		val union = known("a.kt", "b.kt") + known("b.kt", "c.kt")

		assertThat(union).isEqualTo(known("a.kt", "b.kt", "c.kt"))
	}

	@Test
	fun `unknown absorbs known on either side`() {
		assertThat(known("a.kt") + ChangedFiles.Unknown).isEqualTo(ChangedFiles.Unknown)
		assertThat(ChangedFiles.Unknown + known("a.kt")).isEqualTo(ChangedFiles.Unknown)
		assertThat(ChangedFiles.Unknown + ChangedFiles.Unknown).isEqualTo(ChangedFiles.Unknown)
	}

	@Test
	fun `empty known set is empty but unknown is not`() {
		assertThat(ChangedFiles.Known.EMPTY.isEmpty).isTrue()
		assertThat(known("a.kt").isEmpty).isFalse()
		assertThat(ChangedFiles.Unknown.isEmpty).isFalse()
	}

	@Test
	fun `union with empty is identity`() {
		val set = known("a.kt")

		assertThat(set + ChangedFiles.Known.EMPTY).isEqualTo(set)
		assertThat(ChangedFiles.Known.EMPTY + set).isEqualTo(set)
	}
}
