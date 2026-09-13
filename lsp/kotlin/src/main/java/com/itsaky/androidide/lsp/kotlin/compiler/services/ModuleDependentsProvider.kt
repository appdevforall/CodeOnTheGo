package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.allDirectDependencies
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil.createConcurrentSoftMap

internal class ModuleDependentsProvider :
	KotlinModuleDependentsProviderBase(),
	KtLspService {
	private lateinit var modules: List<KtModule>

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>,
	) {
		this.modules = modules
	}

	private val directDependentsByKtModule by lazy {
		buildDependentsMap(modules) { it.allDirectDependencies() }
	}

	private val transitiveDependentsByKtModule = createConcurrentSoftMap<KaModule, Set<KaModule>>()
	private val refinementDependentsByKtModule by lazy {
		buildDependentsMap(modules) { it.transitiveDependsOnDependencies.asSequence() }
	}

	override fun getDirectDependents(module: KaModule): Set<KaModule> = directDependentsByKtModule[module].orEmpty()

	override fun getRefinementDependents(module: KaModule): Set<KaModule> = refinementDependentsByKtModule[module].orEmpty()

	override fun getTransitiveDependents(module: KaModule): Set<KaModule> =
		transitiveDependentsByKtModule.computeIfAbsent(module) { key ->
			computeTransitiveDependents(
				key,
			)
		}
}

/**
 * Inverts every module's dependency edges into one dependency -> dependents map.
 *
 * Accumulated across all of [modules] rather than built per module and merged: `Map + Map` *replaces* a
 * shared dependency's dependent set, so a module used by more than one other kept only the last of them
 * and find usages then missed every call site in the rest.
 */
private fun buildDependentsMap(
	modules: List<KtModule>,
	dependenciesOf: (KtModule) -> Sequence<KaModule>,
): Map<KaModule, Set<KaModule>> =
	buildMap<KaModule, MutableSet<KaModule>> {
		modules.forEach { module ->
			dependenciesOf(module).forEach { dependency ->
				if (dependency != module) {
					getOrPut(dependency) { mutableSetOf() }.add(module)
				}
			}
		}
	}
