package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class SourceClassTrieNameClashTest {
	@get:Rule
	val temp = TemporaryFolder()

	private lateinit var sourceDir: Path
	private lateinit var calculator: Path
	private lateinit var helper: Path

	@Before
	fun setUp() {
		sourceDir = temp.newFolder("java").toPath()
		calculator = source("com/example/Calculator.java", "package com.example;\n\npublic class Calculator {}\n")
		helper = source("com/example/Calculator/Helper.java", "package com.example.Calculator;\n\npublic class Helper {}\n")
	}

	@Test
	fun fileIndexedBeforeASameNamedPackageDoesNotCrash() {
		val trie = SourceClassTrie()

		trie.append(calculator, sourceDir)
		trie.append(helper, sourceDir)

		assertThat(trie.allSources().map { it.file }).containsExactly(calculator, helper)
	}

	@Test
	fun fileIndexedAfterASameNamedPackageKeepsThePackagesSources() {
		val trie = SourceClassTrie()

		trie.append(helper, sourceDir)
		trie.append(calculator, sourceDir)

		assertThat(trie.allSources().map { it.file }).containsExactly(calculator, helper)
	}

	@Test
	fun thePackageNameStillResolvesToThePackage() {
		val trie = indexedTrie()

		val node = trie.findNode("com.example.Calculator")

		assertThat(node).isInstanceOf(SourceClassTrie.SourcePackageNode::class.java)
		assertThat(
			node!!
				.children.values
				.filterIsInstance<SourceClassTrie.SourceNode>()
				.map { it.file },
		).containsExactly(helper)
	}

	@Test
	fun theClashingFileIsStillListedInItsDirectory() {
		val trie = indexedTrie()

		assertThat(trie.getSourceFilesInDir(calculator.parent).map { it.file }).containsExactly(calculator)
	}

	@Test
	fun removingTheClashingFileKeepsThePackage() {
		val trie = indexedTrie()

		val source = trie.findSource(calculator)!!
		source.parent!!.removeChild(source)

		assertThat(trie.findSource(calculator)).isNull()
		assertThat(trie.findSource(helper)).isNotNull()
	}

	private fun indexedTrie() =
		SourceClassTrie().apply {
			append(calculator, sourceDir)
			append(helper, sourceDir)
		}

	private fun source(
		relativePath: String,
		content: String,
	): Path {
		val file = sourceDir.resolve(relativePath)
		file.parent.toFile().mkdirs()
		file.toFile().writeText(content)
		return file
	}
}
