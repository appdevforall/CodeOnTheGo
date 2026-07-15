package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.lsp.kotlin.compiler.DEFAULT_JVM_TARGET
import com.itsaky.androidide.lsp.kotlin.compiler.DEFAULT_LANGUAGE_VERSION
import com.itsaky.androidide.projects.api.ModuleProject
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.slf4j.LoggerFactory

@OptIn(KaPlatformInterface::class)
internal class KtSourceModule(
	project: Project,
	val module: ModuleProject,
	directRegularDependencies: List<KtModule>,
) : KaSourceModule, AbstractSourceModule(project, directRegularDependencies) {

	companion object {
		private val logger = LoggerFactory.getLogger(KtSourceModule::class.java)
	}

	class Builder(private val project: Project) {
		lateinit var module: ModuleProject
		private val dependencies = mutableListOf<KtModule>()

		fun addDependency(dep: KtModule) {
			dependencies.add(dep)
		}

		fun build(): KtSourceModule = KtSourceModule(project, module, dependencies.toList())
	}

	override val id: String
		get() = module.path

	override val contentRoots by lazy {
		module.getSourceDirectories()
			.asSequence()
			.map { it.toPath() }
			.toSet()
	}

	private val versions by lazy {
		val kotlinCompilerSettings = when {
			module.hasJavaProject() -> module.javaProject
				.kotlinCompilerSettings

			module.hasAndroidProject() -> module.androidProject
				.kotlinCompilerSettings

			else -> null
		}

		if (kotlinCompilerSettings == null) {
			return@lazy DEFAULT_LANGUAGE_VERSION to DEFAULT_JVM_TARGET
		}

		val apiVersion = LanguageVersion.fromVersionString(kotlinCompilerSettings.apiVersion)
			?: LanguageVersion.fromFullVersionString(kotlinCompilerSettings.apiVersion)

		val jvmTarget = JvmTarget.fromString(kotlinCompilerSettings.jvmTarget)

		(apiVersion ?: DEFAULT_LANGUAGE_VERSION) to (jvmTarget ?: DEFAULT_JVM_TARGET)
	}

	override val name: String
		get() = module.name

	@OptIn(KaExperimentalApi::class)
	override val moduleDescription: String
		get() = super<AbstractSourceModule>.moduleDescription

	override val languageVersionSettings: LanguageVersionSettings
		get() = LanguageVersionSettingsImpl(
			languageVersion = versions.first,
			apiVersion = ApiVersion.createByLanguageVersion(versions.first),
		)

	override val targetPlatform: TargetPlatform
		get() = JvmPlatforms.jvmPlatformByTargetVersion(versions.second)


}

internal fun buildKtSourceModule(
	project: Project,
	init: KtSourceModule.Builder.() -> Unit,
): KtSourceModule = KtSourceModule.Builder(project).apply(init).build()