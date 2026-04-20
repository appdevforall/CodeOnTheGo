package com.itsaky.androidide.lsp.kotlin.compiler

import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilder
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.cli.common.intellijPluginRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.jdkHome
import org.jetbrains.kotlin.config.jdkRelease
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.useFir
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * A compilation environment for compiling Kotlin sources.
 *
 * @param intellijPluginRoot The IntelliJ plugin root. This is usually the location of the embeddable JAR file. Required.
 * @param languageVersion The language version that this environment should be compatible with.
 * @param jdkHome Path to the JDK installation directory.
 * @param jdkRelease The JDK release version at [jdkHome].
 */
class CompilationEnvironment(
	intellijPluginRoot: Path,
	jdkHome: Path,
	jdkRelease: Int,
	languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
	enableParserEventSystem: Boolean = true,
	configureSession: StandaloneAnalysisAPISessionBuilder.() -> Unit = {}
) : AutoCloseable {
	private val disposable = Disposer.newDisposable()

	val session: StandaloneAnalysisAPISession
	val parser: KtPsiFactory
	val psiManager: PsiManager
	val psiDocumentManager: PsiDocumentManager

	private val envMessageCollector = object: MessageCollector {
		override fun clear() {
		}

		override fun report(
			severity: CompilerMessageSeverity,
			message: String,
			location: CompilerMessageSourceLocation?
		) {
			logger.info("[{}] {} ({})", severity.name, message, location)
		}

		override fun hasErrors(): Boolean {
			return false
		}

	}

	companion object {
		private val logger = LoggerFactory.getLogger(CompilationEnvironment::class.java)
	}

	init {
		val configuration = CompilerConfiguration().apply {
			this.moduleName = JvmProtoBufUtil.DEFAULT_MODULE_NAME
			this.useFir = true
			this.intellijPluginRoot = intellijPluginRoot.pathString
			this.languageVersionSettings = LanguageVersionSettingsImpl(
				languageVersion = languageVersion,
				apiVersion = ApiVersion.createByLanguageVersion(languageVersion),
				analysisFlags = emptyMap(),
				specificFeatures = buildMap {
					// enable all features
					putAll(LanguageFeature.entries.associateWith { LanguageFeature.State.ENABLED })
				}
			)

			this.jdkHome = jdkHome.toFile()
			this.jdkRelease = jdkRelease

			this.messageCollector = envMessageCollector
		}

		session = buildStandaloneAnalysisAPISession(
			projectDisposable = disposable,
			unitTestMode = false,
			compilerConfiguration = configuration,
			init = configureSession
		)

		parser = KtPsiFactory(session.project, eventSystemEnabled = enableParserEventSystem)
		psiManager = PsiManager.getInstance(session.project)
		psiDocumentManager = PsiDocumentManager.getInstance(session.project)
	}

	override fun close() {
		disposable.dispose()
	}
}