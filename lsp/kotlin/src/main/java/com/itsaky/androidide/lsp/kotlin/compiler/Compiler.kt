package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.utils.DocumentUtils
import org.jetbrains.kotlin.com.intellij.lang.Language
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
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

	fun updateLanguageClient(client: ILanguageClient?) {
		defaultCompilationEnv.languageClient = client
		// TODO: update client for script env once we have one
	}

	fun compilationKindFor(file: Path): CompilationKind {
		// TODO: This should return a different environment for Kotlin script files
		return CompilationKind.Default
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

	fun psiFileFactoryFor(compilationKind: CompilationKind): PsiFileFactory =
		PsiFileFactory.getInstance(compilationEnvironmentFor(compilationKind).project)

	fun createPsiFileFor(
		content: String,
		file: Path = Paths.get("dummy.virtual.kt"),
		language: Language = KotlinLanguage.INSTANCE,
		compilationKind: CompilationKind = CompilationKind.Default
	): PsiFile {
		require(!content.contains('\r'))

		val psiFile = psiFileFactoryFor(compilationKind).createFileFromText(
			file.pathString, language, content, true, false
		)
		check(psiFile.virtualFile != null) {
			"No virtual-file associated with newly created psiFile"
		}

		return psiFile
	}

	fun createKtFile(
		content: String,
		file: Path = Paths.get("dummy.virtual.kt"),
		compilationKind: CompilationKind = CompilationKind.Default
	): KtFile = createPsiFileFor(content, file, KotlinLanguage.INSTANCE, compilationKind) as KtFile

	override fun close() {
		defaultCompilationEnv.close()
	}
}