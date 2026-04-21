package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.index.packageExistsInSource
import com.itsaky.androidide.lsp.kotlin.compiler.index.subpackageNames
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinCompositePackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderBase
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.packages.createPackageProvider
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.JvmPackagePartProvider
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class PackageProviderFactory: KtLspService, KotlinPackageProviderFactory {
	private lateinit var project: Project
	private lateinit var index: KtSymbolIndex

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>
	) {
		this.project = project
		this.index = index
	}

	override fun createPackageProvider(searchScope: GlobalSearchScope): KotlinPackageProvider = PackageProvider(project, searchScope, index)
}

private class PackageProvider(
	project: Project,
	searchScope: GlobalSearchScope,
	private val index: KtSymbolIndex
): KotlinPackageProviderBase(project, searchScope) {
	override fun doesKotlinOnlyPackageExist(packageFqName: FqName): Boolean {
		return packageFqName.isRoot || index.packageExistsInSource(packageFqName.asString())
	}

	override fun getKotlinOnlySubpackageNames(packageFqName: FqName): Set<Name> {
		return index.subpackageNames(packageFqName.asString()).map { Name.identifier(it) }.toSet()
	}
}

internal class PackageProviderMerger(private val project: Project) : KotlinPackageProviderMerger {
	override fun merge(providers: List<KotlinPackageProvider>): KotlinPackageProvider =
		providers.mergeSpecificProviders<_, PackageProvider>(KotlinCompositePackageProvider.factory) { targetProviders ->
			val combinedScope = GlobalSearchScope.union(targetProviders.map { it.searchScope })
			project.createPackageProvider(combinedScope).apply {
				check(this is PackageProvider) {
					"`${PackageProvider::class.simpleName}` can only be merged into a combined package provider of the same type."
				}
			}
		}
}

internal class PackagePartProviderFactory: KtLspService, KotlinPackagePartProviderFactory {
	private lateinit var allLibraryRoots: List<JavaRoot>

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>
	) {
		this.allLibraryRoots = libraryRoots
	}

	override fun createPackagePartProvider(scope: GlobalSearchScope): PackagePartProvider {
		return JvmPackagePartProvider(latestLanguageVersionSettings, scope).apply {
			addRoots(allLibraryRoots, MessageCollector.NONE)
		}
	}
}