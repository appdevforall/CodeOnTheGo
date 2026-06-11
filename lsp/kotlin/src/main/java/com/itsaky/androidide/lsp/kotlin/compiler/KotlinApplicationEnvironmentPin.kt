package com.itsaky.androidide.lsp.kotlin.compiler

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment.Companion.getOrCreateApplicationEnvironmentForProduction as acquireApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(K1Deprecation::class)
internal object KotlinApplicationEnvironmentPin {

	private var pinned = false

	private val pin = Disposer.newDisposable("kotlin-lsp-application-environment-pin")

	@Synchronized
	fun ensure(configuration: CompilerConfiguration) {
		if (pinned) return
		acquireApplicationEnvironment(pin, configuration)
		pinned = true
	}
}
