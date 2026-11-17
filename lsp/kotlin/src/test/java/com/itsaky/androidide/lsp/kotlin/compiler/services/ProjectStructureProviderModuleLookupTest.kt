package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path
import java.nio.file.Paths

/** Requirements for [ProjectStructureProvider.findModuleForSourceId], the per-candidate lookup completion relies on. */
@RunWith(JUnit4::class)
class ProjectStructureProviderModuleLookupTest {
	private val provider = ProjectStructureProvider()

	@OptIn(KaExperimentalApi::class)
	private fun module(
		name: String,
		roots: Set<Path>,
		deps: List<KtModule> = emptyList(),
		descriptionReadable: Boolean = true,
	): KtModule =
		mockk(name = name) {
			every { contentRoots } returns roots
			every { directRegularDependencies } returns deps
			if (descriptionReadable) {
				every { moduleDescription } returns "module '$name'"
			} else {
				every { moduleDescription } throws AssertionError("module lookup read the description of $name")
			}
		}

	private fun setUp(vararg modules: KtModule) {
		provider.setupWith(
			project = mockk<MockProject>(relaxed = true),
			index = mockk<KtSymbolIndex>(relaxed = true),
			modules = modules.toList(),
			libraryRoots = emptyList(),
		)
	}

	private val jarA = Paths.get("/libs/a.jar")
	private val jarB = Paths.get("/libs/b.jar")
	private val appSources = Paths.get("/project/app/src/main/java")

	@Test
	fun `module lookup does not read module descriptions`() {
		val libB = module("libB", setOf(jarB), descriptionReadable = false)
		val libA = module("libA", setOf(jarA), deps = listOf(libB), descriptionReadable = false)
		setUp(module("app", setOf(appSources), deps = listOf(libA), descriptionReadable = false))

		assertThat(provider.findModuleForSourceId(jarB.toString())).isSameInstanceAs(libB)
	}

	@Test
	fun `module lookup returns the first module in dependency order whose roots contain the path`() {
		val libA = module("libA", setOf(jarA))
		val shadowingLibA = module("shadowingLibA", setOf(jarA))
		val app = module("app", setOf(appSources), deps = listOf(libA, shadowingLibA))
		setUp(app)

		assertThat(provider.findModuleForSourceId(jarA.toString())).isSameInstanceAs(libA)
		assertThat(provider.findModuleForSourceId(appSources.resolve("com/example/Main.kt").toString()))
			.isSameInstanceAs(app)
	}

	@Test
	fun `module lookup returns null for a path no module contains, on every call`() {
		setUp(module("app", setOf(appSources), deps = listOf(module("libA", setOf(jarA)))))

		assertThat(provider.findModuleForSourceId("/elsewhere/c.jar")).isNull()
		assertThat(provider.findModuleForSourceId("/elsewhere/c.jar")).isNull()
	}

	@Test
	fun `a repeated module lookup does not walk the module graph again`() {
		val libA = module("libA", setOf(jarA))
		setUp(module("app", setOf(appSources), deps = listOf(libA)))

		provider.findModuleForSourceId(jarA.toString())
		provider.findModuleForSourceId(jarA.toString())

		verify(exactly = 1) { libA.contentRoots }
	}

	@Test
	fun `a repeated lookup of a path no module contains does not walk the module graph again`() {
		val libA = module("libA", setOf(jarA))
		setUp(module("app", setOf(appSources), deps = listOf(libA)))

		provider.findModuleForSourceId("/elsewhere/c.jar")
		provider.findModuleForSourceId("/elsewhere/c.jar")

		verify(exactly = 1) { libA.contentRoots }
	}

	@Test
	fun `setupWith discards module lookups made against the previous module graph`() {
		setUp(module("app", setOf(appSources), deps = listOf(module("oldLibA", setOf(jarA)))))
		provider.findModuleForSourceId(jarA.toString())

		val newLibA = module("newLibA", setOf(jarA))
		setUp(module("app", setOf(appSources), deps = listOf(newLibA)))

		assertThat(provider.findModuleForSourceId(jarA.toString())).isSameInstanceAs(newLibA)
	}

	@Test
	fun `memoized module lookups stay within their bound`() {
		setUp(module("app", setOf(appSources)))

		repeat(ProjectStructureProvider.MAX_MEMOIZED_MODULE_LOOKUPS + 100) { i ->
			provider.findModuleForSourceId("/elsewhere/$i.jar")
		}

		assertThat(provider.memoizedModuleLookupCount())
			.isAtMost(ProjectStructureProvider.MAX_MEMOIZED_MODULE_LOOKUPS)
	}
}
