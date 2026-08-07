package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilerSession
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilerSessionFactory
import com.itsaky.androidide.projects.api.Workspace
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import java.nio.file.Path

/**
 * Reflection entry point loaded by name from the carrier APK's DexClassLoader (see
 * `KotlinCompilerLoader` in `lsp:kotlin`). Requires a public no-arg constructor.
 */
class KotlinCompilerSessionFactoryImpl : IKotlinCompilerSessionFactory {
	override fun create(
		workspace: Workspace,
		intellijPluginRoot: Path,
		jdkHome: Path,
		jdkRelease: Int,
		jdkVersionString: String,
		languageClient: ILanguageClient?,
	): IKotlinCompilerSession {
		val jvmTarget = JvmTarget.fromString(jdkVersionString) ?: JvmTarget.JVM_21
		val jvmPlatform = JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)

		val projectModel = KotlinProjectModel()
		projectModel.update(workspace, jvmPlatform)

		val compiler =
			Compiler(
				workspace = workspace,
				projectModel = projectModel,
				intellijPluginRoot = intellijPluginRoot,
				jdkHome = jdkHome,
				jdkRelease = jdkRelease,
				languageVersion = LanguageVersion.LATEST_STABLE,
			)
		compiler.updateLanguageClient(languageClient)

		return KotlinCompilerSessionImpl(compiler, projectModel, jvmPlatform)
	}
}
