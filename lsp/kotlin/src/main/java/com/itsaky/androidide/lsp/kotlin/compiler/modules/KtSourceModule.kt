package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.lsp.kotlin.compiler.DEFAULT_JVM_TARGET
import com.itsaky.androidide.lsp.kotlin.compiler.DEFAULT_LANGUAGE_VERSION
import com.itsaky.androidide.lsp.kotlin.compiler.SourceModuleLayout
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
import java.nio.file.Path

@OptIn(KaPlatformInterface::class)
internal class KtSourceModule(
	project: Project,
	val layout: SourceModuleLayout,
	directRegularDependencies: List<KtModule>,
) : AbstractSourceModule(project, directRegularDependencies),
	KaSourceModule {
	companion object {
		private val logger = LoggerFactory.getLogger(KtSourceModule::class.java)
	}

	class Builder(
		private val project: Project,
	) {
		lateinit var layout: SourceModuleLayout

		// A set: a jar on both the boot and compile classpath is one shared module (ADFA-6381).
		private val dependencies = linkedSetOf<KtModule>()

		fun addDependency(dep: KtModule) {
			dependencies.add(dep)
		}

		fun build(): KtSourceModule = KtSourceModule(project, layout, dependencies.toList())
	}

	override val id: String
		get() = layout.path

	override val contentRoots: Set<Path>
		get() = layout.sourceRoots

	private val versions by lazy {
		val apiVersion =
			layout.apiVersion?.let {
				LanguageVersion.fromVersionString(it) ?: LanguageVersion.fromFullVersionString(it)
			}

		val jvmTarget = layout.jvmTarget?.let { JvmTarget.fromString(it) }

		(apiVersion ?: DEFAULT_LANGUAGE_VERSION) to (jvmTarget ?: DEFAULT_JVM_TARGET)
	}

	override val name: String
		get() = layout.name

	@OptIn(KaExperimentalApi::class)
	override val moduleDescription: String
		get() = super<AbstractSourceModule>.moduleDescription

	override val languageVersionSettings: LanguageVersionSettings
		get() =
			LanguageVersionSettingsImpl(
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
