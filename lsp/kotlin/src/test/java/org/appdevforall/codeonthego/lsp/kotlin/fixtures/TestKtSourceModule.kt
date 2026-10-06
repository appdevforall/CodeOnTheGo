package org.appdevforall.codeonthego.lsp.kotlin.fixtures

import org.appdevforall.codeonthego.lsp.kotlin.compiler.DEFAULT_JVM_TARGET
import org.appdevforall.codeonthego.lsp.kotlin.compiler.DEFAULT_LANGUAGE_VERSION
import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.AbstractSourceModule
import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.KtModule
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
import java.nio.file.Path

@OptIn(KaPlatformInterface::class, KaExperimentalApi::class)
internal class TestKtSourceModule(
    project: Project,
    override val name: String,
    roots: Set<Path>,
    dependencies: List<KtModule> = emptyList(),
    languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
    jvmTarget: JvmTarget = DEFAULT_JVM_TARGET,
) : KaSourceModule, AbstractSourceModule(project, dependencies) {

    override val id: String = name
    override val contentRoots: Set<Path> = roots
    override val moduleDescription: String = "test:$name"

    override val languageVersionSettings: LanguageVersionSettings =
        LanguageVersionSettingsImpl(
            languageVersion = languageVersion,
            apiVersion = ApiVersion.createByLanguageVersion(languageVersion),
        )

    override val targetPlatform: TargetPlatform =
        JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)
}
