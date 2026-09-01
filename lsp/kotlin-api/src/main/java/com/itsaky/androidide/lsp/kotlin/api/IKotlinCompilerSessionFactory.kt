package com.itsaky.androidide.lsp.kotlin.api

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.projects.api.Workspace
import java.nio.file.Path

/**
 * Entry point loaded by name (reflection) from the carrier APK's `DexClassLoader` --
 * see `KotlinCompilerLoader`. Implemented by `KotlinCompilerSessionFactoryImpl` in the
 * isolated `lsp:kotlin-compiler-impl` module, which must expose a public no-arg
 * constructor for this to work.
 */
interface IKotlinCompilerSessionFactory {
	fun create(
		workspace: Workspace,
		intellijPluginRoot: Path,
		jdkHome: Path,
		jdkRelease: Int,
		jdkVersionString: String,
		languageClient: ILanguageClient?,
	): IKotlinCompilerSession
}
