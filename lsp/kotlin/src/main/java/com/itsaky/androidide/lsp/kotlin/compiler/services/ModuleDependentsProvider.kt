package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.allDirectDependencies
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil.createConcurrentSoftMap

internal class ModuleDependentsProvider : KtLspService, KotlinModuleDependentsProviderBase() {

	private lateinit var modules: List<KtModule>

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>
	) {
		this.modules = modules
	}

	private val directDependentsByKtModule by lazy {
		modules.asSequence()
			.map { module ->
				buildDependentsMap(module, module.allDirectDependencies())
			}
			.reduce { acc, value -> acc + value }
	}

	private val transitiveDependentsByKtModule = createConcurrentSoftMap<KaModule, Set<KaModule>>()
	private val refinementDependentsByKtModule by lazy {
		modules
			.asSequence()
			.map { buildDependentsMap(it, it.transitiveDependsOnDependencies.asSequence()) }
			.reduce { acc, map -> acc + map }
	}

	override fun getDirectDependents(module: KaModule): Set<KaModule> {
		return directDependentsByKtModule[module].orEmpty()
	}

	override fun getRefinementDependents(module: KaModule): Set<KaModule> {
		return refinementDependentsByKtModule[module].orEmpty()
	}

	override fun getTransitiveDependents(module: KaModule): Set<KaModule> {
		return transitiveDependentsByKtModule.computeIfAbsent(module) { key ->
			computeTransitiveDependents(
				key
			)
		}
	}
}

private fun buildDependentsMap(
	module: KaModule,
	dependencies: Sequence<KaModule>,
): Map<KaModule, MutableSet<KaModule>> = buildMap {
	dependencies.forEach { dependency ->
		if (dependency == module) return@forEach
		val dependents = computeIfAbsent(dependency) { mutableSetOf() }
		dependents.add(module)
	}
}
