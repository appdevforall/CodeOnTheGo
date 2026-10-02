package com.itsaky.androidide.lsp.kotlin.compiler.registrar

import com.itsaky.androidide.lsp.kotlin.compiler.services.AnalysisPermissionOptions
import com.itsaky.androidide.lsp.kotlin.compiler.services.AnnotationsResolverFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.DeclarationProviderFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.DeclarationProviderMerger
import com.itsaky.androidide.lsp.kotlin.compiler.services.ModificationTrackerFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.ModuleDependentsProvider
import com.itsaky.androidide.lsp.kotlin.compiler.services.NoOpAsyncExecutionService
import com.itsaky.androidide.lsp.kotlin.compiler.services.PackagePartProviderFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.PackageProviderFactory
import com.itsaky.androidide.lsp.kotlin.compiler.services.PackageProviderMerger
import com.itsaky.androidide.lsp.kotlin.compiler.services.PlatformSettings
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
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
import org.jetbrains.kotlin.com.intellij.openapi.application.AsyncExecutionService
import org.jetbrains.kotlin.com.intellij.psi.SmartTypePointerManager
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartTypePointerManagerImpl
import org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory
import org.jetbrains.kotlin.load.java.structure.impl.source.JavaFixedElementSourceFactory

@Suppress("UnstableApiUsage")
@OptIn(KaImplementationDetail::class)
internal object AnalysisApiServiceProviders {
	val BaseProvider =
		AnalysisApiServiceProvider
			.Builder()
			.apply {
				pluginRelativePath = "/META-INF/kt-lsp/kt-lsp.xml"

				appService(KotlinAnalysisPermissionOptions::class, AnalysisPermissionOptions::class)
				appService(AsyncExecutionService::class, NoOpAsyncExecutionService::class)

				projectService(
					KotlinLifetimeTokenFactory::class,
					KotlinReadActionConfinementLifetimeTokenFactory::class,
				)

				projectService(KotlinPlatformSettings::class, PlatformSettings::class)
				projectService(SmartTypePointerManager::class, SmartTypePointerManagerImpl::class)
				projectService(KotlinProjectStructureProvider::class, ProjectStructureProvider::class)
				projectService(KotlinModuleDependentsProvider::class, ModuleDependentsProvider::class)
				projectService(KotlinModificationTrackerFactory::class, ModificationTrackerFactory::class)
				projectService(KotlinAnnotationsResolverFactory::class, AnnotationsResolverFactory::class)
				projectService(KotlinDeclarationProviderMerger::class, DeclarationProviderMerger::class)
				projectService(KotlinPackageProviderMerger::class, PackageProviderMerger::class)
				projectService(KotlinPackagePartProviderFactory::class, PackagePartProviderFactory::class)
				projectService(KotlinPackageProviderFactory::class, PackageProviderFactory::class)
				projectService(KotlinDeclarationProviderFactory::class, DeclarationProviderFactory::class)

				// analysis-api-impl-base.xml maps this to JavaElementSourceWithSmartPointerFactory, which holds
				// library PSI only through a weak/soft SmartPsiElementPointer. Smart pointers exist to survive
				// VFS invalidation of library PSI, which a standalone LSP never does - so on a phone the cls PSI
				// is simply collected under heap pressure and the pointer cannot re-derive it, and FIR resolution
				// throws "Cannot restore ..." (ADFA-6199). The fixed factory keeps the PSI in a plain field; it
				// is what the Kotlin CLI compiler registers for its own MockProject. Registered here so it lands
				// after the plugin XML and replaces it.
				projectService(JavaElementSourceFactory::class, JavaFixedElementSourceFactory::class)
			}.build()

	val Production = BaseProvider
}
