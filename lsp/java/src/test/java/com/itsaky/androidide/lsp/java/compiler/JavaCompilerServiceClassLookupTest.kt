package com.itsaky.androidide.lsp.java.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.ClassTrie
import com.itsaky.androidide.utils.Environment
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@RunWith(JUnit4::class)
class JavaCompilerServiceClassLookupTest {
	companion object {
		@JvmStatic
		@BeforeClass
		fun setUpEnvironment() {
			// JavaCompilerService's static initializer reads it; only a device sets it.
			if (Environment.ANDROID_JAR == null) {
				Environment.ANDROID_JAR = File("android.jar")
			}
		}
	}

	private val indexed =
		object : ClasspathClassNames {
			override fun qualifiedNamesOf(simpleName: String) = listOf("com.indexed.$simpleName").filter { simpleName == "Indexed" }

			override fun qualifiedNamesByPrefix(
				prefix: String,
				limit: Int,
			) = emptyList<String>()

			override fun classesNamed(simpleName: String): List<JvmSymbol> =
				qualifiedNamesOf(simpleName).map { fqName ->
					JvmSymbol(
						key = fqName,
						sourceId = "fake",
						name = fqName.replace('.', '/'),
						shortName = simpleName,
						packageName = fqName.substringBeforeLast('.', missingDelimiterValue = ""),
						kind = JvmSymbolKind.CLASS,
						language = JvmSourceLanguage.JAVA,
						data = JvmClassInfo(),
					)
				}

			override fun isClass(qualifiedName: String) = qualifiedName == "com.indexed.Indexed"

			override fun children(packageName: String) = emptyList<ModuleClasspathLookup.Child>()
		}

	private fun compilerWithTrieClass(trieClass: String): JavaCompilerService {
		val trie = ClassTrie().apply { append(trieClass) }
		return JavaCompilerService(
			null,
			SourceFileManager.NO_MODULE,
			emptySet(),
			trie.allClassNames(),
			ClasspathTypeLookup({ emptyList() }, { indexed }, { emptySet() }),
		)
	}

	@Test
	fun `qualified names do not come from the classpath trie`() {
		val compiler = compilerWithTrieClass("com.trie.OnlyInTrie")

		assertThat(compiler.findQualifiedNames("OnlyInTrie", false)).isEmpty()
	}

	@Test
	fun `qualified names come from the index`() {
		val compiler = compilerWithTrieClass("com.trie.OnlyInTrie")

		assertThat(compiler.findQualifiedNames("Indexed", false)).containsExactly("com.indexed.Indexed")
	}

	@Test
	fun `a copy answers from the same class lookup`() {
		val compiler = compilerWithTrieClass("com.trie.OnlyInTrie").copy()

		assertThat(compiler.findQualifiedNames("Indexed", true)).containsExactly("com.indexed.Indexed")
	}
}
