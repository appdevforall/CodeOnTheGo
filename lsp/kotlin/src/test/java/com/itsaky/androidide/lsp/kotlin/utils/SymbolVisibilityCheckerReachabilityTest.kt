package com.itsaky.androidide.lsp.kotlin.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.JvmVisibility
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Requirements for checking many unimported-symbol candidates against one completion's use site. */
@RunWith(JUnit4::class)
class SymbolVisibilityCheckerReachabilityTest {
	private val structureProvider = mockk<ProjectStructureProvider>()
	private val checker = SymbolVisibilityChecker(structureProvider)

	private fun module(
		name: String,
		deps: List<KaModule> = emptyList(),
	): KaModule =
		mockk(name = name) {
			every { directRegularDependencies } returns deps
			every { directFriendDependencies } returns emptyList()
			every { directDependsOnDependencies } returns emptyList()
			every { targetPlatform } returns JvmPlatforms.defaultJvmPlatform
		}

	private fun publicSymbolIn(
		sourceId: String,
		declaringModule: KaModule,
	): JvmSymbol {
		every { structureProvider.findModuleForSourceId(sourceId) } returns declaringModule
		return JvmSymbol(
			key = "com/example/Foo",
			sourceId = sourceId,
			name = "com/example/Foo",
			shortName = "Foo",
			packageName = "com.example",
			kind = JvmSymbolKind.CLASS,
			language = JvmSourceLanguage.KOTLIN,
			visibility = JvmVisibility.PUBLIC,
			data = JvmClassInfo(),
		)
	}

	@Test
	fun `a public symbol in a transitive dependency of the use site is visible`() {
		val libB = module("libB")
		val app = module("app", deps = listOf(module("libA", deps = listOf(libB))))

		assertThat(checker.forUseSite(app).isVisible(publicSymbolIn("b.jar", libB), useSitePackage = "app")).isTrue()
	}

	@Test
	fun `a public symbol in a module the use site does not depend on is not visible`() {
		val unrelated = module("unrelated")
		val app = module("app", deps = listOf(module("libA")))

		assertThat(checker.forUseSite(app).isVisible(publicSymbolIn("u.jar", unrelated), useSitePackage = "app"))
			.isFalse()
	}

	@Test
	fun `checking many symbols from one use site walks the dependency graph once`() {
		val libB = module("libB")
		val libA = module("libA", deps = listOf(libB))
		val app = module("app", deps = listOf(libA))
		val visibility = checker.forUseSite(app)

		repeat(3) { visibility.isVisible(publicSymbolIn("b.jar", libB), useSitePackage = "app") }

		verify(exactly = 1) { libA.directRegularDependencies }
	}
}
