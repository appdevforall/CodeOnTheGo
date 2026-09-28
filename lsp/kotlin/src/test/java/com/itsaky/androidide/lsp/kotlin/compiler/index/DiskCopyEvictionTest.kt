package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.writeText

@OptIn(ResolutionSideKtFileAccess::class)
internal class DiskCopyEvictionTest : KtLspTest() {
	private fun diskText(path: Path): String = env.project.read { env.ktSymbolIndex.getKtFile(path)!!.text }

	@Test
	fun evictingTheDiskCopyServesTheRewrittenText() {
		createSourceFile("Foo.kt", "fun oldName() = 1")
		val path = env.sourceRoots.first().resolve("Foo.kt")
		assertThat(diskText(path)).contains("oldName")

		path.writeText("fun renamedFunction(): Int = 2")
		assertThat(diskText(path)).contains("oldName")

		env.ktSymbolIndex.evictDiskCopy(path)
		assertThat(diskText(path)).contains("renamedFunction")
	}
}
