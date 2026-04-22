package com.itsaky.androidide.lsp.kotlin.compiler.registrar

import com.itsaky.androidide.lsp.kotlin.compiler.services.AnalysisPermissionOptions
import com.itsaky.androidide.lsp.kotlin.compiler.services.AnnotationsResolverFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.NoOpAsyncExecutionService
import com.itsaky.androidide.lsp.kotlin.compiler.services.DeclarationProviderFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.DeclarationProviderMerger
import com.itsaky.androidide.lsp.kotlin.compiler.services.ModificationTrackerFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.ModuleDependentsProvider
import com.itsaky.androidide.lsp.kotlin.compiler.services.PackagePartProviderFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.PackageProviderFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.PackageProviderMerger
import com.itsaky.androidide.lsp.kotlin.compiler.services.PlatformSettings
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinReadActionConfinementLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.AnalysisApiSimpleServiceRegistrar
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.PluginStructureProvider
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.analysis.decompiler.stub.file.DummyFileAttributeService
import org.jetbrains.kotlin.analysis.decompiler.stub.file.FileAttributeService
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.mock.MockApplication
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.application.AsyncExecutionService
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeEvent
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener
import org.jetbrains.kotlin.com.intellij.psi.SmartPointerManager
import org.jetbrains.kotlin.com.intellij.psi.SmartTypePointerManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartTypePointerManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.source.codeStyle.IndentHelper

@OptIn(KaImplementationDetail::class)
internal object LspServiceRegistrar : AnalysisApiSimpleServiceRegistrar() {

	private const val PLUGIN_RELATIVE_PATH = "/META-INF/kt-lsp/kt-lsp.xml"

	override fun registerApplicationServices(application: MockApplication) {
		PluginStructureProvider.registerApplicationServices(application, PLUGIN_RELATIVE_PATH)

		with(application) {
			registerService(FileAttributeService::class.java, DummyFileAttributeService::class.java)
			registerService(
				KotlinAnalysisPermissionOptions::class.java,
				AnalysisPermissionOptions::class.java
			)
			registerService(ClsKotlinBinaryClassCache::class.java)
			registerService(AsyncExecutionService::class.java, NoOpAsyncExecutionService::class.java)
		}
	}

	override fun registerProjectServices(project: MockProject) {
		PluginStructureProvider.registerProjectServices(project, PLUGIN_RELATIVE_PATH)


		with(project) {
			registerService(
				KotlinLifetimeTokenFactory::class.java,
				KotlinReadActionConfinementLifetimeTokenFactory::class.java
			)
			registerService(KotlinPlatformSettings::class.java, PlatformSettings::class.java)
			registerService(
				SmartTypePointerManager::class.java,
				SmartTypePointerManagerImpl::class.java
			)
			registerService(SmartPointerManager::class.java, SmartPointerManagerImpl::class.java)
			registerService(
				KotlinProjectStructureProvider::class.java,
				ProjectStructureProvider::class.java
			)
			registerService(
				KotlinModuleDependentsProvider::class.java,
				ModuleDependentsProvider::class.java
			)
			registerService(
				KotlinModificationTrackerFactory::class.java,
				ModificationTrackerFactory::class.java
			)
			registerService(
				KotlinAnnotationsResolverFactory::class.java,
				AnnotationsResolverFactory::class.java
			)
			registerService(
				KotlinDeclarationProviderFactory::class.java,
				DeclarationProviderFactory::class.java
			)
			registerService(
				KotlinDeclarationProviderMerger::class.java,
				DeclarationProviderMerger::class.java
			)
			registerService(
				KotlinPackageProviderFactory::class.java,
				PackageProviderFactory::class.java
			)
			registerService(KotlinPackageProviderMerger::class.java, PackageProviderMerger::class.java)
			registerService(
				KotlinPackagePartProviderFactory::class.java,
				PackagePartProviderFactory::class.java
			)
		}
	}

	@OptIn(KaExperimentalApi::class)
	@Suppress("TestOnlyProblems")
	override fun registerProjectModelServices(project: MockProject, disposable: Disposable) {
		with(PsiElementFinder.EP.getPoint(project)) {
			registerExtension(JavaElementFinder(project), disposable)
			registerExtension(PsiElementFinderImpl(project), disposable)
		}
	}
}
