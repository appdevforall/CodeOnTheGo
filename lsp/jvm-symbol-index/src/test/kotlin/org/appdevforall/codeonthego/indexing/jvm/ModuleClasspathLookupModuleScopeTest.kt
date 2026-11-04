package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.ProjectManagerImpl
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * A module's lookup is scoped to its own compile classpath, although the indexes it reads hold the
 * JARs of every module.
 */
@RunWith(JUnit4::class)
class ModuleClasspathLookupModuleScopeTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val fixtures = ModuleFixtures(temp)

	@After
	fun tearDown() {
		runCatching { ProjectManagerImpl.getInstance().workspace = null }
	}

	/** An index whose active set is [activeSources], as a registered indexing service leaves it. */
	private fun index(activeSources: Set<String>): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return JvmSymbolIndex(backing, BackgroundIndexer(backing)).apply { setActiveSources(activeSources) }
	}

	private fun topLevelClass(
		qualifiedName: String,
		sourceId: String,
	): JvmSymbol {
		val internalName = qualifiedName.replace('.', '/')
		return JvmSymbol(
			key = internalName,
			sourceId = sourceId,
			name = internalName,
			shortName = qualifiedName.substringAfterLast('.'),
			packageName = qualifiedName.substringBeforeLast('.'),
			kind = JvmSymbolKind.CLASS,
			language = JvmSourceLanguage.KOTLIN,
			data = JvmClassInfo(internalName = internalName),
		)
	}

	@Test
	fun `a module does not see the output classes of a module it does not depend on`() =
		runTest {
			val app = fixtures.androidModule(":app", moduleDeps = listOf(":lib"))
			val lib = fixtures.androidModule(":lib")
			fixtures.installWorkspace(app, lib)
			val appJar = app.getGeneratedJar().absolutePath
			val libJar = lib.getGeneratedJar().absolutePath
			val moduleOutput = index(setOf(appJar, libJar))
			moduleOutput.insert(topLevelClass("com.app.AppScreen", appJar))
			moduleOutput.insert(topLevelClass("com.lib.LibWidget", libJar))

			val libLookup = ModuleClasspathLookup(lib, null, null, moduleOutput)
			val appLookup = ModuleClasspathLookup(app, null, null, moduleOutput)

			assertThat(libLookup.qualifiedNamesByPrefix("", limit = 10)).containsExactly("com.lib.LibWidget")
			assertThat(libLookup.isPackage("com.app")).isFalse()
			assertThat(appLookup.qualifiedNamesByPrefix("", limit = 10))
				.containsExactly("com.app.AppScreen", "com.lib.LibWidget")
		}

	@Test
	fun `a module sees the library jars of the modules it depends on`() =
		runTest {
			val guava = fixtures.builtFile("ext/guava.jar")
			val okio = fixtures.builtFile("ext/okio.jar")
			val app = fixtures.androidModule(":app", moduleDeps = listOf(":lib"), externalJars = listOf(okio))
			val lib = fixtures.androidModule(":lib", externalJars = listOf(guava))
			fixtures.installWorkspace(app, lib)
			val library = index(setOf(guava, okio))
			library.insert(topLevelClass("com.google.common.Lists", guava))
			library.insert(topLevelClass("okio.Buffer", okio))

			val libLookup = ModuleClasspathLookup(lib, library, null, null)
			val appLookup = ModuleClasspathLookup(app, library, null, null)

			assertThat(libLookup.qualifiedNamesByPrefix("", limit = 10)).containsExactly("com.google.common.Lists")
			assertThat(appLookup.qualifiedNamesByPrefix("", limit = 10))
				.containsExactly("com.google.common.Lists", "okio.Buffer")
		}
}
