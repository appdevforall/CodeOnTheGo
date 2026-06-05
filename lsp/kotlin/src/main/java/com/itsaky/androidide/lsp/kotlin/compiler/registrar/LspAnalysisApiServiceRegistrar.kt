package com.itsaky.androidide.lsp.kotlin.compiler.registrar

import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.AnalysisApiSimpleServiceRegistrar
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.PluginStructureProvider
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.com.intellij.mock.MockApplication
import org.jetbrains.kotlin.com.intellij.mock.MockComponentManager
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl

@OptIn(KaImplementationDetail::class)
internal class LspAnalysisApiServiceRegistrar(
	private val provider: AnalysisApiServiceProvider,
): AnalysisApiSimpleServiceRegistrar() {

	@Suppress("UNCHECKED_CAST")
	private fun MockComponentManager.servAll(services: ServiceMap) {
		services.forEach { (_, registration) ->
			registration.register(this)
		}
	}

	override fun registerApplicationServices(application: MockApplication) {
		provider.pluginRelativePath?.also { pluginRelativePath ->
			PluginStructureProvider.registerApplicationServices(application, pluginRelativePath)
		}

		application.servAll(provider.applicationServices)
	}

	override fun registerProjectServices(project: MockProject) {
		provider.pluginRelativePath?.also { pluginRelativePath ->
			PluginStructureProvider.registerProjectServices(project, pluginRelativePath)
		}

		project.servAll(provider.projectServices)
	}

	override fun registerProjectModelServices(project: MockProject, disposable: Disposable) {
		with(PsiElementFinder.EP.getPoint(project)) {
			registerExtension(JavaElementFinder(project), disposable)
			registerExtension(PsiElementFinderImpl(project), disposable)
		}
	}
}