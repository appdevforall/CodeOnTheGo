package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.utils.DocumentUtils
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

internal class Compiler(
	workspace: Workspace,
	projectModel: KotlinProjectModel,
	intellijPluginRoot: Path,
	jdkHome: Path,
	jdkRelease: Int,
	languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
) : AutoCloseable {
	private val logger = LoggerFactory.getLogger(Compiler::class.java)

	@Suppress("JoinDeclarationAndAssignment")
	private val defaultCompilationEnv: CompilationEnvironment

	val fileSystem: VirtualFileSystem

	val defaultKotlinParser: KtPsiFactory
		get() = defaultCompilationEnv.parser

	init {
		defaultCompilationEnv = CompilationEnvironment(
			name = "default",
			kind = CompilationKind.Default,
			workspace = workspace,
			ktProject = projectModel,
			intellijPluginRoot = intellijPluginRoot,
			jdkHome = jdkHome,
			jdkRelease = jdkRelease,
			languageVersion = languageVersion,
			enableParserEventSystem = true,
		)

		// must be initialized AFTER the compilation env has been initialized
		fileSystem =
			VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)
	}

	fun refreshSources() {
		defaultCompilationEnv.refreshSources()
	}

	fun updateLanguageClient(client: ILanguageClient?) {
		defaultCompilationEnv.languageClient = client
		// TODO: update client for script env once we have one
	}

	fun compilationKindFor(file: Path): CompilationKind {
		if (CompilationKind.Default.acceptsFile(file)) return CompilationKind.Default
		if (CompilationKind.Script.acceptsFile(file)) return CompilationKind.Script
		throw IllegalStateException("Cannot get compilation kind for file: ${file.pathString}. It may not be supported.")
	}

	fun compilationEnvironmentFor(file: Path): CompilationEnvironment? {
		if (!DocumentUtils.isKotlinFile(file)) return null

		return compilationEnvironmentFor(compilationKindFor(file))
	}

	fun compilationEnvironmentFor(compilationKind: CompilationKind): CompilationEnvironment =
		when (compilationKind) {
			CompilationKind.Default -> defaultCompilationEnv
			CompilationKind.Script -> throw UnsupportedOperationException("Not supported yet")
		}

	override fun close() {
		defaultCompilationEnv.close()
	}
}